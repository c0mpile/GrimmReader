package com.c0mpile.grimmreader.core.data.notebook

import com.c0mpile.grimmreader.api.grimmory.GrimmoryUrls
import com.c0mpile.grimmreader.api.grimmory.NotebookEntryDto
import com.c0mpile.grimmreader.core.common.IoDispatcher
import com.c0mpile.grimmreader.core.data.library.parseInstant
import com.c0mpile.grimmreader.core.data.server.ServerSession
import com.c0mpile.grimmreader.core.database.dao.BookDao
import com.c0mpile.grimmreader.core.model.NotebookBook
import com.c0mpile.grimmreader.core.model.NotebookEntry
import com.c0mpile.grimmreader.core.model.NotebookEntryType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Grimmory notebook (highlights, notes and bookmarks across books), read from the server when shown.
 * Read-only: the server's entries carry no position, so they cannot open the reader at the passage.
 */
@Singleton
class NotebookRepository
    @Inject
    constructor(
        private val session: ServerSession,
        private val bookDao: BookDao,
        @IoDispatcher private val io: CoroutineDispatcher,
    ) {
        suspend fun books(search: String? = null): Result<List<NotebookBook>> =
            withContext(io) {
                runCatching {
                    val server = session.server.value ?: throw IOException("No server")
                    val api = session.api() ?: throw IOException("No server")
                    val base = session.baseUrl()
                    val all =
                        pages { page ->
                            api.notebookBooks(page, PAGE_SIZE, search?.trim()?.ifEmpty { null }).let {
                                it.content to
                                    it.hasNext
                            }
                        }
                    all.map { dto ->
                        NotebookBook(
                            serverBookId = dto.bookId,
                            localBookId = bookDao.byServerId(server.id, dto.bookId)?.id,
                            title = dto.bookTitle?.ifBlank { null } ?: "#${dto.bookId}",
                            authors = dto.authors,
                            entries = dto.noteCount,
                            coverUri = base?.let { GrimmoryUrls.thumbnail(it, dto.bookId, dto.coverUpdatedOn).toString() },
                        )
                    }
                }
            }

        suspend fun entries(serverBookId: Long): Result<List<NotebookEntry>> =
            withContext(io) {
                runCatching {
                    val api = session.api() ?: throw IOException("No server")
                    pages { page ->
                        api.notebookEntries(serverBookId, page, PAGE_SIZE).let { it.content to it.hasNext }
                    }.map { it.toDomain() }
                }
            }

        /** Sequential pages, capped so a huge notebook cannot keep the screen loading forever. */
        private suspend fun <T> pages(fetch: suspend (Int) -> Pair<List<T>, Boolean>): List<T> {
            val all = mutableListOf<T>()
            var page = 0
            do {
                val (items, more) = fetch(page)
                all += items
                page++
            } while (more && page < MAX_PAGES)
            return all
        }

        private fun NotebookEntryDto.toDomain() =
            NotebookEntry(
                id = id,
                type = NotebookEntryType.entries.firstOrNull { it.name == type?.uppercase() } ?: NotebookEntryType.OTHER,
                text = text?.trim()?.ifEmpty { null },
                note = note?.trim()?.ifEmpty { null },
                color = color,
                chapter = chapterTitle?.trim()?.ifEmpty { null },
                createdAt = parseInstant(createdAt),
            )

        private companion object {
            const val PAGE_SIZE = 50
            const val MAX_PAGES = 40
        }
    }
