package com.c0mpile.grimmreader.core.data.library

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.room.withTransaction
import com.c0mpile.grimmreader.api.grimmory.BookSummaryDto
import com.c0mpile.grimmreader.api.grimmory.GrimmoryUrls
import com.c0mpile.grimmreader.api.grimmory.LibraryDto
import com.c0mpile.grimmreader.core.common.IoDispatcher
import com.c0mpile.grimmreader.core.data.progress.ProgressRepository
import com.c0mpile.grimmreader.core.data.server.ServerSession
import com.c0mpile.grimmreader.core.data.shelf.ShelfMirror
import com.c0mpile.grimmreader.core.data.shelf.ShelfSnapshot
import com.c0mpile.grimmreader.core.database.GrimmDatabase
import com.c0mpile.grimmreader.core.database.dao.BookDao
import com.c0mpile.grimmreader.core.database.entity.BookEntity
import com.c0mpile.grimmreader.core.database.entity.BookFileEntity
import com.c0mpile.grimmreader.core.database.entity.LibraryEntity
import com.c0mpile.grimmreader.core.datastore.AppPreferences
import com.c0mpile.grimmreader.core.files.BookHandle
import com.c0mpile.grimmreader.core.files.BookMetadataReader
import com.c0mpile.grimmreader.core.files.LocalFileStore
import com.c0mpile.grimmreader.core.model.Book
import com.c0mpile.grimmreader.core.model.BookFormat
import com.c0mpile.grimmreader.core.model.BookSource
import com.c0mpile.grimmreader.core.model.Library
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** [loaded] of [total] books fetched by a running refresh (the total as the server reports it). */
data class RefreshProgress(
    val loaded: Long,
    val total: Long,
)

/** The unified library: server books cached in Room plus local books. Room is the source of truth. */
@Singleton
class LibraryRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val db: GrimmDatabase,
        private val bookDao: BookDao,
        private val session: ServerSession,
        private val files: LocalFileStore,
        private val prefs: AppPreferences,
        private val shelfMirror: ShelfMirror,
        private val localLibraries: LocalLibraryRepository,
        @IoDispatcher private val io: CoroutineDispatcher,
    ) {
        private val libraryDao get() = db.libraryDao()
        private val fileDao get() = db.bookFileDao()
        private val refreshLock = Mutex()
        private val mirror get() = LibraryMirror(bookDao, fileDao)
        private val work = CoroutineScope(SupervisorJob() + io)
        private val _progress = MutableStateFlow<RefreshProgress?>(null)

        /** How far a running refresh has got; null when none runs. */
        val progress: StateFlow<RefreshProgress?> = _progress.asStateFlow()
        private val coversChecked = AtomicBoolean(false)

        /** Once per process: shrinks covers saved at full size by earlier builds ([LocalFileStore.shrinkLargeCovers]). */
        suspend fun shrinkLargeCovers() {
            if (coversChecked.compareAndSet(false, true)) withContext(io) { files.shrinkLargeCovers() }
        }

        fun observeLibrary(libraryId: Long? = null): Flow<List<Book>> =
            bookDao.observeLibrary(libraryId).map { rows ->
                // One directory listing per update instead of a file check per book.
                val covers = files.coverIds()
                val base = session.baseUrl()
                rows.filter { it.files.isNotEmpty() }.map { row ->
                    row.toDomain { coverModel(it, covers.contains(it.id), base) }
                }
            }

        fun observeBook(id: Long): Flow<Book?> = bookDao.observe(id).map { it?.toDomain(::coverModel) }

        /** The signed-in server's libraries, in server order; empty in local mode. */
        @OptIn(ExperimentalCoroutinesApi::class)
        fun observeLibraries(): Flow<List<Library>> =
            session.server.flatMapLatest { server ->
                if (server == null) flowOf(emptyList()) else libraryDao.observe(server.id).map { rows -> rows.map { it.toDomain() } }
            }

        /** Cover for Coil: the local extracted cover, else the server thumbnail (fetched with the authed client). */
        private fun coverModel(book: BookEntity): String? = coverModel(book.id, book.serverRowId, book.serverBookId, book.coverUpdatedOn)

        fun coverModel(
            bookId: Long,
            serverRowId: Long?,
            serverBookId: Long?,
            coverUpdatedOn: String?,
        ): String? {
            val local = files.coverFile(bookId)
            if (local.exists()) return local.absolutePath
            return thumbnailUrl(session.baseUrl(), serverRowId, serverBookId, coverUpdatedOn)
        }

        private fun coverModel(
            book: BookEntity,
            hasLocalCover: Boolean,
            base: HttpUrl?,
        ): String? =
            if (hasLocalCover) {
                files.coverFile(book.id).absolutePath
            } else {
                thumbnailUrl(base, book.serverRowId, book.serverBookId, book.coverUpdatedOn)
            }

        private fun thumbnailUrl(
            base: HttpUrl?,
            serverRowId: Long?,
            serverBookId: Long?,
            coverUpdatedOn: String?,
        ): String? {
            if (base == null || serverBookId == null || serverRowId == null) return null
            return GrimmoryUrls.thumbnail(base, serverBookId, coverUpdatedOn).toString()
        }

        /** The server thumbnail of every listed book without an extracted cover, in title order (for [CoverPrefetchWorker]). */
        suspend fun serverCoverUrls(): List<String> =
            withContext(io) {
                observeLibrary().first().sortedBy { it.title.lowercase() }.mapNotNull { it.coverUri?.takeIf(::isRemote) }
            }

        private fun isRemote(model: String) = model.startsWith("https://") || model.startsWith("http://")

        /**
         * The automatic refresh: runs [refresh] only when the last successful one for this server is older than
         * [STALE_AFTER_MS] (kept across restarts), or when shelves or on-device libraries were never mirrored (the server
         * always has Favorites; an update that added shelves or libraries must not wait). Null when skipped.
         */
        suspend fun refreshIfStale(now: Long = System.currentTimeMillis()): Result<Int>? {
            val server = session.server.value ?: return null
            val recent = now - prefs.libraryRefreshedAt(server.id) < STALE_AFTER_MS
            if (recent && withContext(io) { shelfMirror.hasShelves(server.id) && localLibraries.hasLinked() }) {
                CoverPrefetchWorker.enqueue(context) // Cheap when every cover is cached already.
                return null
            }
            return refresh()
        }

        /**
         * Pulls every page of `/api/v1/app/books` (sequential, [PAGE_SIZE] per request) and mirrors them into Room
         * in batches of [BATCH_BOOKS], so a big library fills in while it loads and a failure halfway keeps what
         * arrived. Books that disappeared are removed at the end, only after every page came, unless downloaded.
         * Audiobook-only items are skipped. One refresh at a time; a second caller waits for the first and then
         * runs its own. It runs in the repository's own scope: leaving the screen does not stop it.
         */
        suspend fun refresh(): Result<Int> = work.async { refreshLock.withLock { fetchAndMirror() } }.await()

        private suspend fun fetchAndMirror(): Result<Int> {
            val server = session.server.value ?: return Result.success(0)
            val api = session.api() ?: return Result.success(0)
            return runCatching {
                val started = SystemClock.elapsedRealtime()
                val rows = api.libraries().toEntities(server.id)
                val localIds =
                    db.withTransaction {
                        libraryDao.replaceAll(server.id, rows)
                        localLibraries.syncWithServer(server.id, rows)
                    }
                // A reset not sent yet: the server still reports the old progress.
                val resetting = db.outboxDao().ofKind(ProgressRepository.KIND_RESET).mapTo(HashSet()) { it.entityId }
                val seen = HashSet<Long>()
                val batch = mutableListOf<BookSummaryDto>()
                var page = 0
                var fetched = 0L
                do {
                    val result = api.books(page = page, size = PAGE_SIZE, sort = "title", dir = "asc")
                    batch += result.content
                    fetched += result.content.size
                    page++
                    if (batch.size >= BATCH_BOOKS || !result.hasNext) {
                        db.withTransaction { mirror.books(server.id, localIds, resetting, batch, seen) }
                        batch.clear()
                    }
                    _progress.value = RefreshProgress(fetched, result.totalElements)
                    if (page % LOG_EVERY_PAGES == 0) {
                        Log.d(TAG, "refresh: page $page of ${result.totalPages}, ${SystemClock.elapsedRealtime() - started} ms")
                    }
                } while (result.hasNext)
                val shelves = ShelfSnapshot.fetch(api)
                db.withTransaction {
                    mirror.removeGone(server.id, seen)
                    shelfMirror.apply(server.id, shelves)
                }
                session.reportOffline(false)
                prefs.setLibraryRefreshedAt(server.id, System.currentTimeMillis())
                CoverPrefetchWorker.enqueue(context)
                Log.d(TAG, "refresh: ${seen.size} books in ${SystemClock.elapsedRealtime() - started} ms")
                seen.size
            }.onFailure {
                Log.w(TAG, "refresh failed: ${it.javaClass.simpleName}")
                if (it is IOException) session.reportOffline(true)
            }.also { _progress.value = null }
        }

        /**
         * Only the server's library list (the first run lists them right after sign-in, before any book is
         * fetched): mirrored into Room and into the on-device libraries. Returns the libraries.
         */
        suspend fun refreshLibraries(): Result<List<Library>> =
            withContext(io) {
                // Right after sign-in the session may not have loaded the new server row yet.
                val server =
                    withTimeoutOrNull(SERVER_WAIT_MS) { session.server.filterNotNull().first() }
                        ?: return@withContext Result.success(emptyList())
                val api = session.api() ?: return@withContext Result.success(emptyList())
                runCatching {
                    val rows = api.libraries().toEntities(server.id)
                    db.withTransaction {
                        libraryDao.replaceAll(server.id, rows)
                        localLibraries.syncWithServer(server.id, rows)
                    }
                    rows.map { it.toDomain() }
                }
            }

        /** The server is gone: its libraries stay on the device, no longer mirroring a server library. */
        suspend fun serverRemoved() = localLibraries.unlinkServer()

        /** Copies a user-picked file into app storage and adds it as a local book, in [localLibraryId]. Returns the new book id. */
        suspend fun importLocal(
            uri: Uri,
            localLibraryId: Long? = null,
        ): Long =
            withContext(io) {
                val imported = files.importFrom(uri)
                BookHandle.open(imported.file).use { book ->
                    addLocalBook(
                        book,
                        imported.format,
                        imported.displayName,
                        imported.file.absolutePath,
                        imported.sizeBytes,
                        imported.partialMd5,
                        localLibraryId,
                    )
                }
            }

        /** Adds a local book for [book] (open now), stored as [localUri]. Returns the new book id. */
        internal suspend fun addLocalBook(
            book: BookHandle,
            format: BookFormat,
            displayName: String,
            localUri: String,
            sizeBytes: Long,
            partialMd5: String,
            localLibraryId: Long? = null,
        ): Long {
            val meta = BookMetadataReader.read(book, format)
            val title = meta.title ?: displayName.substringBeforeLast('.')
            val bookId =
                db.withTransaction {
                    val id =
                        bookDao.insert(
                            BookEntity(
                                source = BookSource.LOCAL,
                                localLibraryId = localLibraryId,
                                title = title,
                                authors = meta.authors.joinToString(BookEntity.AUTHOR_SEPARATOR),
                                seriesName = meta.series,
                                seriesNumber = meta.seriesNumber,
                                readingDirection = meta.readingDirection,
                                addedAt = System.currentTimeMillis(),
                                description = meta.description,
                                publisher = meta.publisher,
                                publishedDate = meta.publishedDate,
                                detailsLoadedAt = System.currentTimeMillis(),
                            ),
                        )
                    fileDao.upsert(
                        BookFileEntity(bookId = id, format = format, localUri = localUri, sizeBytes = sizeBytes, partialMd5 = partialMd5),
                    )
                    id
                }
            meta.cover?.let { files.saveCover(bookId, it) }
            return bookId
        }

        /** Removes a local book from the library; its file is left alone. */
        internal suspend fun forgetLocal(bookId: Long) {
            files.coverFile(bookId).delete()
            bookDao.delete(bookId)
        }

        /**
         * Deletes a local book and its file (also one in a book folder; book detail asks first); server books
         * only lose their downloaded copy.
         */
        suspend fun deleteLocal(bookId: Long) =
            withContext(io) {
                val row = bookDao.get(bookId) ?: return@withContext
                val downloadFolder = prefs.downloadFolder.first()
                row.files.mapNotNull { it.localUri }.forEach { files.deleteBookFile(it, downloadFolder) }
                if (row.book.source == BookSource.SERVER) {
                    row.files.forEach {
                        fileDao.setLocal(it.id, null, it.sizeBytes, it.partialMd5)
                        db.downloadDao().delete(it.id)
                    }
                } else {
                    files.coverFile(bookId).delete()
                    bookDao.delete(bookId)
                }
            }

        companion object {
            private const val PAGE_SIZE = 100
            private const val BATCH_BOOKS = 1000
            private const val LOG_EVERY_PAGES = 20
            private const val TAG = "GrimmLibrary"
            private const val SERVER_WAIT_MS = 5_000L

            /** The automatic refresh skips a library mirrored less than 30 minutes ago; pull-to-refresh never does. */
            const val STALE_AFTER_MS = 30 * 60 * 1000L
        }
    }

private fun List<LibraryDto>.toEntities(serverRowId: Long) =
    mapIndexed { i, l -> LibraryEntity(serverRowId, l.id, l.name, l.allowedFormats.joinToString(","), i) }
