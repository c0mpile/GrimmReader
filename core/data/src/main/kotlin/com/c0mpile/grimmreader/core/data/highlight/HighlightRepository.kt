package com.c0mpile.grimmreader.core.data.highlight

import com.c0mpile.grimmreader.core.common.IoDispatcher
import com.c0mpile.grimmreader.core.database.dao.HighlightDao
import com.c0mpile.grimmreader.core.database.entity.HighlightEntity
import com.c0mpile.grimmreader.core.model.Highlight
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Highlights of ebooks, kept in Room on this device (not synced with the server yet). */
@Singleton
class HighlightRepository
    @Inject
    constructor(
        private val highlights: HighlightDao,
        @IoDispatcher private val io: CoroutineDispatcher,
    ) {
        /** In reading order: by position, then oldest first. */
        fun observe(bookId: Long): Flow<List<Highlight>> =
            highlights.observe(bookId).map { rows ->
                rows
                    .sortedWith(compareBy<HighlightEntity, Float?>(nullsLast()) { it.percent }.thenBy { it.createdAt })
                    .map { Highlight(it.id, it.cfi, it.text, it.color, it.chapter, it.percent, it.createdAt) }
            }

        suspend fun add(
            bookId: Long,
            cfi: String,
            text: String,
            color: String,
            chapter: String?,
            percent: Float?,
        ): Long =
            withContext(io) {
                highlights.insert(
                    HighlightEntity(
                        bookId = bookId,
                        cfi = cfi,
                        text = text,
                        color = color,
                        chapter = chapter,
                        percent = percent,
                        createdAt = System.currentTimeMillis(),
                    ),
                )
            }

        suspend fun remove(id: Long) = withContext(io) { highlights.delete(id) }
    }
