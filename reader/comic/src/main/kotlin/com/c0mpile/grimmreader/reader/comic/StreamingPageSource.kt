package com.c0mpile.grimmreader.reader.comic

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import com.c0mpile.grimmreader.core.model.ReadingDirection
import com.c0mpile.grimmreader.reader.paged.BoundedDecoder
import com.c0mpile.grimmreader.reader.paged.PageSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

/**
 * Pages fetched one by one (from the server) through [fetch], which returns a local file for a 1-based page
 * number. Each page is fetched once; the next [PREFETCH] pages and the previous one are fetched ahead, at most
 * [PARALLEL] at a time, so the server sees a modest request rate.
 */
class StreamingPageSource(
    private val pageNumbers: List<Int>,
    override val readingDirection: ReadingDirection,
    private val fetch: suspend (page: Int) -> File,
) : PageSource {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val permits = Semaphore(PARALLEL)
    private val pages = HashMap<Int, Deferred<File>>()

    override val pageCount: Int get() = pageNumbers.size

    override suspend fun decode(
        index: Int,
        maxSize: IntSize,
    ): ImageBitmap? {
        if (index !in pageNumbers.indices) return null
        val file = page(index)
        prefetch(index)
        val path = runCatching { file.await() }.getOrNull() ?: return null
        return withContext(Dispatchers.IO) {
            runCatching { BoundedDecoder.decode({ FileInputStream(path) }, maxSize)?.asImageBitmap() }.getOrNull()
        }
    }

    private fun prefetch(index: Int) {
        for (next in (index - 1)..(index + PREFETCH)) {
            if (next != index && next in pageNumbers.indices) page(next)
        }
    }

    /** A failed fetch is forgotten, so turning back to the page retries it. */
    private fun page(index: Int): Deferred<File> =
        synchronized(pages) {
            pages[index]?.takeUnless { it.isCompleted && it.getCompletionExceptionOrNull() != null }
                ?: scope.async { permits.withPermit { fetch(pageNumbers[index]) } }.also { pages[index] = it }
        }

    override fun close() = scope.cancel()

    private companion object {
        const val PREFETCH = 3
        const val PARALLEL = 2
    }
}
