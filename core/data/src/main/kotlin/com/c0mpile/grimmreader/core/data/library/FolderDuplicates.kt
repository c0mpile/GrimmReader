package com.c0mpile.grimmreader.core.data.library

import com.c0mpile.grimmreader.core.database.dao.BookDao
import com.c0mpile.grimmreader.core.database.dao.BookFileDao
import com.c0mpile.grimmreader.core.database.dao.ReadingPositionDao
import com.c0mpile.grimmreader.core.database.entity.BookFileEntity
import com.c0mpile.grimmreader.core.datastore.AppPreferences
import com.c0mpile.grimmreader.core.files.DocumentStore
import com.c0mpile.grimmreader.core.files.LocalFileStore
import com.c0mpile.grimmreader.core.model.BookSource
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * One library entry per book file: when a folder book has the same content (partial MD5) as a copy the app
 * owns (an imported file or a server download in app storage, or a download it saved to the download folder),
 * the folder file is kept and the app's copy deleted. A server book stays the entry and now reads the folder
 * file, so its sync keeps working; between two local entries the one read most recently stays. Files in book
 * folders are the user's and are never deleted here, even when two of them are identical.
 */
class FolderDuplicates
    @Inject
    constructor(
        private val prefs: AppPreferences,
        private val bookDao: BookDao,
        private val fileDao: BookFileDao,
        private val positionDao: ReadingPositionDao,
        private val files: LocalFileStore,
    ) {
        /** Merges the duplicates of the folder books under [folders]; returns how many entries were dropped. */
        suspend fun merge(folders: Collection<String>): Int {
            val bookFolders = prefs.bookFolders.first()
            val downloadFolder = prefs.downloadFolder.first()
            var merged = 0
            val folderFiles =
                fileDao.documents().filter { file ->
                    file.partialMd5 != null && folders.any { DocumentStore.inTree(file.localUri!!, it) } && isLocal(file)
                }
            for (folderFile in folderFiles) {
                val copy =
                    fileDao
                        .byPartialMd5(folderFile.partialMd5!!)
                        .firstOrNull { it.id != folderFile.id && isAppCopy(it, bookFolders, downloadFolder) }
                        ?: continue
                val copyIsServer = bookDao.get(copy.bookId)?.book?.source == BookSource.SERVER
                if (copyIsServer || lastRead(copy.bookId) >= lastRead(folderFile.bookId)) {
                    fileDao.setLocal(copy.id, folderFile.localUri, folderFile.sizeBytes, folderFile.partialMd5)
                    forget(folderFile.bookId)
                } else {
                    forget(copy.bookId)
                }
                files.deleteBookFile(copy.localUri!!)
                merged++
            }
            return merged
        }

        /** A file the app made: in app storage, or saved to the download folder (unless that is a book folder too). */
        private fun isAppCopy(
            file: BookFileEntity,
            bookFolders: Collection<String>,
            downloadFolder: String?,
        ): Boolean {
            val uri = file.localUri ?: return false
            if (!DocumentStore.isDocument(uri)) return true
            return downloadFolder != null && DocumentStore.inTree(uri, downloadFolder) && bookFolders.none { DocumentStore.inTree(uri, it) }
        }

        /** Drops a library entry; its file is handled by the caller. */
        private suspend fun forget(bookId: Long) {
            files.coverFile(bookId).delete()
            bookDao.delete(bookId)
        }

        private suspend fun isLocal(file: BookFileEntity) = bookDao.get(file.bookId)?.book?.source == BookSource.LOCAL

        private suspend fun lastRead(bookId: Long): Long = positionDao.get(bookId)?.localUpdatedAt ?: 0L
    }
