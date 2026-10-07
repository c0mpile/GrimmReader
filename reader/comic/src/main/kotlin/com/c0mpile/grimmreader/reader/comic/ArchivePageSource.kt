package com.c0mpile.grimmreader.reader.comic

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import com.c0mpile.grimmreader.core.files.ComicArchive
import com.c0mpile.grimmreader.core.files.ExtractedComicArchive
import com.c0mpile.grimmreader.core.files.LibarchiveArchive
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

/** Pages of a local comic archive: CBZ read in place, CB7 and CBR extracted once into [extractDir]. */
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
            // Reads are thread-safe, but decoding many pages at once only wastes memory.
            lock.withLock {
                runCatching { BoundedDecoder.decode({ archive.open(index) }, maxSize)?.asImageBitmap() }.getOrNull()
            }
        }

    override fun close() = archive.close()

    companion object {
        /** Null when [format] is not a comic. [onProgress] gets 0..1 while a CB7 or CBR is extracted. */
        suspend fun open(
            file: File,
            format: BookFormat,
            direction: ReadingDirection?,
            extractDir: File,
            onProgress: (Float) -> Unit = {},
        ): ArchivePageSource? {
            val archive =
                when (format) {
                    BookFormat.CBZ -> withContext(Dispatchers.IO) { ZipComicArchive(file) }
                    BookFormat.CB7, BookFormat.CBR ->
                        withContext(Dispatchers.IO) { ExtractedComicArchive.open(file, extractDir, ::LibarchiveArchive, onProgress) }
                    else -> return null
                }
            return ArchivePageSource(archive, direction ?: ReadingDirection.LTR)
        }
    }
}
