package com.c0mpile.grimmreader.core.data.library

import com.c0mpile.grimmreader.core.common.IoDispatcher
import com.c0mpile.grimmreader.core.data.server.ServerSession
import com.c0mpile.grimmreader.core.database.dao.BookDao
import com.c0mpile.grimmreader.core.database.entity.BookEntity
import com.c0mpile.grimmreader.core.files.BookHandle
import com.c0mpile.grimmreader.core.files.BookMetadataReader
import com.c0mpile.grimmreader.core.files.DocumentStore
import com.c0mpile.grimmreader.core.files.HtmlText
import com.c0mpile.grimmreader.core.model.BookFormat
import com.c0mpile.grimmreader.core.model.BookSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Subtitle, description, publisher and publication date, loaded when a book's drawer opens. The server's book list
 * has none of them, so a server book costs one `GET /app/books/{id}`, repeated at most every [SERVER_STALE_AFTER_MS];
 * a local book is read from its file once (books imported before these fields existed, or whose read failed).
 */
@Singleton
class BookDetails
    @Inject
    constructor(
        private val bookDao: BookDao,
        private val session: ServerSession,
        private val documents: DocumentStore,
        @IoDispatcher private val io: CoroutineDispatcher,
    ) {
        suspend fun load(
            bookId: Long,
            now: Long = System.currentTimeMillis(),
        ) = withContext(io) {
            val row = bookDao.get(bookId) ?: return@withContext
            val book = row.book
            val loadedAt = book.detailsLoadedAt
            if (book.source == BookSource.SERVER) {
                if (loadedAt == null || now - loadedAt >= SERVER_STALE_AFTER_MS) fromServer(book, now)
            } else if (loadedAt == null) {
                val file = row.files.firstOrNull { it.isPrimary && it.localUri != null } ?: row.files.firstOrNull { it.localUri != null }
                if (file != null) fromFile(book.id, file.localUri!!, file.format, now)
            }
        }

        private suspend fun fromServer(
            book: BookEntity,
            now: Long,
        ) {
            val serverBookId = book.serverBookId ?: return
            val server = session.server.value ?: return
            if (book.serverRowId != server.id) return
            val api = session.api() ?: return
            try {
                val detail = api.book(serverBookId)
                bookDao.setDetails(
                    book.id,
                    subtitle = detail.subtitle.clean(),
                    description = detail.description?.let(HtmlText::plain),
                    publisher = detail.publisher.clean(),
                    publishedDate = detail.publishedDate.clean(),
                    loadedAt = now,
                )
            } catch (_: IOException) {
                session.reportOffline(true)
            } catch (_: HttpException) {
                // Keep what is stored and try again next time.
            } catch (_: SerializationException) {
                // Same for a body the client cannot read.
            }
        }

        private suspend fun fromFile(
            bookId: Long,
            uri: String,
            format: BookFormat,
            now: Long,
        ) {
            val meta =
                runCatching {
                    (if (DocumentStore.isDocument(uri)) documents.open(uri) else BookHandle.open(File(uri))).use {
                        BookMetadataReader.read(it, format)
                    }
                }.getOrNull() ?: return
            bookDao.setDetails(bookId, subtitle = null, meta.description, meta.publisher, meta.publishedDate, loadedAt = now)
        }

        private fun String?.clean() = this?.trim()?.ifEmpty { null }

        companion object {
            /** Server metadata can be edited; a day-old copy is fetched again when the drawer opens. */
            const val SERVER_STALE_AFTER_MS = 24 * 60 * 60 * 1000L
        }
    }
