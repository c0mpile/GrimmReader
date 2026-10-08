package com.c0mpile.grimmreader.core.data.library

import android.net.Uri
import android.provider.DocumentsContract
import com.c0mpile.grimmreader.core.common.IoDispatcher
import com.c0mpile.grimmreader.core.data.safeName
import com.c0mpile.grimmreader.core.database.dao.BookDao
import com.c0mpile.grimmreader.core.database.dao.BookFileDao
import com.c0mpile.grimmreader.core.database.dao.DownloadDao
import com.c0mpile.grimmreader.core.database.entity.BookFileEntity
import com.c0mpile.grimmreader.core.datastore.AppPreferences
import com.c0mpile.grimmreader.core.files.DocumentStore
import com.c0mpile.grimmreader.core.files.FolderDocument
import com.c0mpile.grimmreader.core.files.FormatSniffer
import com.c0mpile.grimmreader.core.files.LocalFileStore
import com.c0mpile.grimmreader.core.model.BookSource
import com.c0mpile.grimmreader.core.model.ReadStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** A picked folder (the download folder); [accessible] is false once the grant was revoked or the volume is gone. */
data class BookFolder(
    val uri: String,
    val name: String,
    val accessible: Boolean,
)

/** What a scan changed. */
data class ScanResult(
    val added: Int = 0,
    val removed: Int = 0,
    val unreadableFolders: Int = 0,
    /** Duplicates of folder books dropped (the app's own copy deleted, see [FolderDuplicates]). */
    val merged: Int = 0,
)

/**
 * Local books read in place from the watch folders of the on-device libraries (see [LocalLibraryRepository]):
 * a book found in a library's folder is filed into that library. A scan adds new book files, follows moved or renamed
 * ones (same partial MD5, so position and bookmarks stay), and drops books whose file is gone. A dropped book
 * that was read is set aside instead (hidden, file link cleared, position, bookmarks and status kept), and comes
 * back when a scan finds its content again, say after its folder is added back. A folder that cannot be listed
 * (revoked, SD card out) is left untouched rather than treated as empty.
 */
@Singleton
class FolderLibrary
    @Inject
    constructor(
        private val prefs: AppPreferences,
        private val documents: DocumentStore,
        private val bookDao: BookDao,
        private val fileDao: BookFileDao,
        private val downloads: DownloadDao,
        private val localLibraries: LocalLibraryRepository,
        private val library: LibraryRepository,
        private val duplicates: FolderDuplicates,
        @IoDispatcher private val io: CoroutineDispatcher,
    ) {
        private val lock = Mutex()

        val downloadFolder: Flow<BookFolder?> =
            prefs.downloadFolder
                .map { it?.let { uri -> BookFolder(uri, documents.folderName(uri), documents.hasAccess(uri)) } }
                .flowOn(io)

        /** How many read books are set aside (see the class comment), waiting for their content to turn up. */
        val setAsideCount: Flow<Int> = fileDao.observeSetAsideCount()

        /** Drops every set-aside book with its position and bookmarks; their files were already out of reach. */
        suspend fun forgetSetAside() =
            lock.withLock {
                withContext(io) {
                    fileDao
                        .setAside()
                        .map { it.bookId }
                        .distinct()
                        .forEach { library.forgetLocal(it) }
                }
            }

        /**
         * New downloads go to [tree], or app storage when null. Earlier downloads stay where they are, so the old
         * folder keeps its grant while any of them is there.
         */
        suspend fun setDownloadFolder(tree: Uri?) =
            withContext(io) {
                val old = prefs.downloadFolder.first()
                tree?.let { documents.persist(it) }
                prefs.setDownloadFolder(tree?.toString())
                old?.let { releaseIfUnused(it) }
                if (tree != null) createLibrarySubfolders(tree.toString())
            }

        /** One subfolder per server library in the download folder, so they are there before the first download. */
        private suspend fun createLibrarySubfolders(tree: String) {
            localLibraries
                .all()
                .filter { it.serverLibraryId != null }
                .forEach { runCatching { documents.ensureFolder(tree, safeName(it.name, FALLBACK_LIBRARY)) } }
        }

        /**
         * Gives [libraryId] the watch folder [tree] (null = none) and scans it. The previous folder's books leave
         * the library (see [detach]).
         */
        suspend fun setLibraryFolder(
            libraryId: Long,
            tree: Uri?,
        ): ScanResult {
            lock.withLock {
                withContext(io) {
                    val old = localLibraries.get(libraryId)?.folderUri
                    tree?.let { documents.persist(it) }
                    localLibraries.setFolderUri(libraryId, tree?.toString())
                    if (old != null && old != tree?.toString()) detach(old)
                }
            }
            return scan()
        }

        /**
         * Deletes a library of the user's own. Books read in place from its folder leave the app's library (the
         * files stay); imported copies and the like fall back to being in no library. A library mirroring a
         * server library comes back with the next refresh, so it can only lose its folder.
         */
        suspend fun deleteLibrary(libraryId: Long) {
            lock.withLock {
                withContext(io) {
                    val row = localLibraries.get(libraryId) ?: return@withContext
                    if (row.serverLibraryId != null) return@withContext
                    localLibraries.delete(libraryId)
                    row.folderUri?.let { detach(it) }
                    bookDao.clearLocalLibrary(libraryId)
                }
            }
        }

        /**
         * Stops scanning [tree] (no library lists it as its folder any more) and removes its books from the library
         * (read ones are set aside, see the class comment); the files themselves stay. A book that another
         * library's folder also reaches (a subfolder of [tree]) is kept and moved to that folder's URI, since
         * [tree]'s grant is released. A server book that read its file from [tree] (a merged duplicate) goes back
         * to not downloaded, so it offers reading online or downloading again, unless [tree] is also the download
         * folder (whose grant stays).
         */
        private suspend fun detach(tree: String) {
            val remaining = roots().map { it.tree }.toSet() - tree
            val stillReached =
                remaining
                    .filter { documents.hasAccess(it) }
                    .flatMap { runCatching { documents.listBooks(it) }.getOrDefault(emptyList()) }
                    .associateBy { documentId(it.uri) }
            val downloadFolder = prefs.downloadFolder.first()
            fileDao
                .documents()
                .filter { file -> inFolder(file, tree) }
                .forEach { file ->
                    val other = stillReached[documentId(file.localUri!!)]
                    when {
                        other != null -> fileDao.setLocal(file.id, other.uri, file.sizeBytes, file.partialMd5)
                        isLocal(file) -> drop(file)
                        tree != downloadFolder -> fileDao.setLocal(file.id, null, file.sizeBytes, file.partialMd5)
                    }
                }
            releaseIfUnused(tree)
        }

        /** Gives up the grant on [tree] once no library folder, no download folder and no book file needs it any more. */
        private suspend fun releaseIfUnused(tree: String) {
            if (roots().any { it.tree == tree } || tree == prefs.downloadFolder.first()) return
            if (fileDao.documents().any { inFolder(it, tree) }) return
            documents.release(tree)
        }

        /** The library folders in library order, each with the library its books go to. */
        private suspend fun roots(): List<Root> = localLibraries.all().mapNotNull { l -> l.folderUri?.let { Root(l.id, it) } }

        private data class Root(
            val libraryId: Long,
            val tree: String,
        )

        @Volatile private var lastScanAt = 0L

        /** The automatic scan when a library screen opens: skipped when one ran in the last minute. */
        suspend fun scanIfStale(now: Long = System.currentTimeMillis()): ScanResult? =
            if (now - lastScanAt < RESCAN_AFTER_MS) null else scan()

        suspend fun scan(): ScanResult =
            lock.withLock {
                withContext(io) {
                    val roots = roots()
                    val listed = mutableMapOf<String, List<FolderDocument>>()
                    for (root in roots) {
                        if (!documents.hasAccess(root.tree)) continue
                        try {
                            listed[root.tree] = documents.listBooks(root.tree)
                        } catch (_: IOException) {
                            // Unlistable: keep its books as they are.
                        }
                    }
                    // A file reached through two library folders (parent and child) goes to the first library.
                    val libraryOf = mutableMapOf<String, Long>()
                    for (root in roots) listed[root.tree]?.forEach { libraryOf.putIfAbsent(documentId(it.uri), root.libraryId) }
                    val found = listed.values.flatten().distinctBy { documentId(it.uri) }
                    val foundIds = found.map { documentId(it.uri) }.toSet()
                    val known = fileDao.documents()
                    val knownIds = known.map { documentId(it.localUri!!) }.toSet()
                    val missing =
                        known
                            .filter { file -> listed.keys.any { inFolder(file, it) } && documentId(file.localUri!!) !in foundIds }
                            .filter { isLocal(it) }
                            .toMutableList()
                    val followable = (missing + fileDao.setAside()).toMutableList()
                    var added = 0
                    for (doc in found) {
                        if (documentId(doc.uri) in knownIds) continue
                        if (addOrFollow(doc, libraryOf.getValue(documentId(doc.uri)), followable)) added++
                    }
                    fileKnownBooks(known, libraryOf)
                    val gone = missing.filter { it in followable }
                    gone.forEach { drop(it) }
                    val merged = duplicates.merge(listed.keys)
                    forgetDeletedDownloads()
                    // Grants kept for earlier downloads go once their last file is removed.
                    documents.grantedTrees().forEach { releaseIfUnused(it) }
                    lastScanAt = System.currentTimeMillis()
                    ScanResult(added, gone.size, roots.size - listed.size, merged)
                }
            }

        /**
         * Files the local books found in a library folder that are in no library yet (they were there before the
         * folder became a library's, or from an earlier build). A book already in a library stays there.
         */
        private suspend fun fileKnownBooks(
            known: List<BookFileEntity>,
            libraryOf: Map<String, Long>,
        ) {
            val unsorted = bookDao.unsortedLocalIds().toSet()
            if (unsorted.isEmpty()) return
            known.filter { it.bookId in unsorted }.forEach { file ->
                libraryOf[documentId(file.localUri!!)]?.let { bookDao.setLocalLibrary(file.bookId, it) }
            }
        }

        /** [moved] (missing in this scan, or set aside) is now [doc]; true when it was set aside, so a book came back. */
        private suspend fun follow(
            moved: BookFileEntity,
            doc: FolderDocument,
            md5: String,
            libraryId: Long,
        ): Boolean {
            fileDao.setLocal(moved.id, doc.uri, doc.sizeBytes, md5)
            if (isLocal(moved)) bookDao.setLocalLibrary(moved.bookId, libraryId)
            return moved.localUri == null
        }

        /**
         * True when [doc] is a new book or one set aside coming back; a file with the same content as one of
         * [followable] (missing in this scan, or set aside) updates that book instead of adding one.
         */
        private suspend fun addOrFollow(
            doc: FolderDocument,
            libraryId: Long,
            followable: MutableList<BookFileEntity>,
        ): Boolean =
            try {
                documents.open(doc.uri).use { opened ->
                    val format = FormatSniffer.sniff(opened, doc.name) ?: return false
                    val md5 = LocalFileStore.partialMd5(opened)
                    val moved = followable.firstOrNull { it.partialMd5 == md5 }
                    if (moved != null) {
                        followable.remove(moved)
                        follow(moved, doc, md5, libraryId)
                    } else {
                        library.addLocalBook(opened, format, doc.name, doc.uri, doc.sizeBytes, md5, libraryId)
                        true
                    }
                }
            } catch (_: IOException) {
                false
            } catch (e: CancellationException) {
                throw e
            } catch (_: RuntimeException) {
                // A damaged file must not stop the rest of the scan.
                false
            }

        /**
         * Takes a local book whose file is no longer reached out of the library: one that was read is set aside
         * (see the class comment), an unread one is forgotten, as a later scan would add it as it was anyway. A
         * file without a known MD5 could never be matched again, so its book is forgotten too.
         */
        private suspend fun drop(file: BookFileEntity) {
            val row = bookDao.get(file.bookId) ?: return
            if (file.partialMd5 != null && (row.position != null || row.book.readStatus != ReadStatus.UNREAD)) {
                fileDao.setLocal(file.id, null, file.sizeBytes, file.partialMd5)
            } else {
                library.forgetLocal(file.bookId)
            }
        }

        /**
         * Server books whose downloaded copy was deleted outside the app (a file manager) go back to not
         * downloaded, so they offer reading online or downloading again instead of opening a missing file.
         */
        private suspend fun forgetDeletedDownloads() {
            fileDao
                .documents()
                .filter { !isLocal(it) && documents.isGone(it.localUri!!) }
                .forEach {
                    fileDao.setLocal(it.id, null, it.sizeBytes, it.partialMd5)
                    downloads.delete(it.id)
                }
        }

        private suspend fun isLocal(file: BookFileEntity) = bookDao.get(file.bookId)?.book?.source == BookSource.LOCAL

        private fun inFolder(
            file: BookFileEntity,
            tree: String,
        ) = file.localUri?.let { DocumentStore.inTree(it, tree) } == true

        /** The same file reached through two picked folders (parent and child) has two URIs but one id. */
        private fun documentId(uri: String): String = runCatching { DocumentsContract.getDocumentId(Uri.parse(uri)) }.getOrDefault(uri)

        private companion object {
            const val RESCAN_AFTER_MS = 60_000L
            const val FALLBACK_LIBRARY = "library"
        }
    }
