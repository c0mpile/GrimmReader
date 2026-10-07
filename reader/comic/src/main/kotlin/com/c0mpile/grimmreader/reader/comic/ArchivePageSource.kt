package com.c0mpile.grimmreader.reader.comic

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import com.c0mpile.grimmreader.core.files.ComicArchive
import com.c0mpile.grimmreader.core.files.ZipComicArchive
import com.c0mpile.grimmreader.core.model.BookFormat
import com.c0mpile.grimmreader.core.model.ReadingDirection
import com.c0mpile.grimmreader.reader.paged.BoundedDecoder
import com.c0mpile.grimmreader.reader.paged.PageSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Pages of a local CBZ comic, read in place (CBZ is the only comic format). */
class ArchivePageSource private constructor(
    private val archive: ComicArchive,
    override val readingDirection: ReadingDirection,
) : PageSource {
    private val lock = Mutex()

    override val pageCount: Int get() = archive.pageCount

    override suspend fun decode(
        index: Int,
        maxSize: IntSize,
    ): ImageBitmap? =
        withContext(Dispatchers.IO) {
            // ZipFile is thread-safe for reads, but decoding many pages at once only wastes memory.
            lock.withLock {
                runCatching { BoundedDecoder.decode({ archive.open(index) }, maxSize)?.asImageBitmap() }.getOrNull()
            }
        }

    override fun close() = archive.close()

    companion object {
        /** Null when [format] is not a comic. */
        fun open(
            file: File,
            format: BookFormat,
            direction: ReadingDirection?,
        ): ArchivePageSource? =
            when (format) {
                BookFormat.CBZ -> ArchivePageSource(ZipComicArchive(file), direction ?: ReadingDirection.LTR)
                else -> null
            }
    }
}
