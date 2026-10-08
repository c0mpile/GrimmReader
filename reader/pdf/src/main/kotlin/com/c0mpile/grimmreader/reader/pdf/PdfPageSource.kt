package com.c0mpile.grimmreader.reader.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import com.c0mpile.grimmreader.core.files.BookHandle
import com.c0mpile.grimmreader.core.model.ReadingDirection
import com.c0mpile.grimmreader.reader.paged.BoundedDecoder
import com.c0mpile.grimmreader.reader.paged.PageSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Password-protected PDFs, which the framework renderer cannot open. */
class ProtectedPdfException : IOException("This PDF is password-protected")

/**
 * Pages of a local PDF, rendered by the framework's [PdfRenderer] (no dependencies). The renderer allows one
 * open page at a time and must not be closed mid-render, so every call holds [lock].
 */
class PdfPageSource private constructor(
    private val fd: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
) : PageSource {
    private val lock = Any()
    private var closed = false

    override val pageCount: Int = renderer.pageCount
    override val readingDirection = ReadingDirection.LTR

    override suspend fun decode(
        index: Int,
        maxSize: IntSize,
    ): ImageBitmap? =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                if (closed || index !in 0 until pageCount) return@withContext null
                runCatching { render(renderer, index, maxSize).asImageBitmap() }.getOrNull()
            }
        }

    override fun close() =
        synchronized(lock) {
            if (closed) return
            closed = true
            renderer.close()
            fd.close()
        }

    companion object {
        /** Throws [ProtectedPdfException] for encrypted files and [IOException] for anything unreadable. */
        fun open(book: BookHandle): PdfPageSource {
            val fd = book.descriptor()
            val renderer =
                try {
                    PdfRenderer(fd)
                } catch (e: SecurityException) {
                    fd.close()
                    throw ProtectedPdfException().apply { initCause(e) }
                } catch (e: IOException) {
                    fd.close()
                    throw e
                }
            return PdfPageSource(fd, renderer)
        }

        /** Renders page [index] on white (PDFs assume paper), scaled to fit [maxSize]. */
        fun render(
            renderer: PdfRenderer,
            index: Int,
            maxSize: IntSize,
        ): Bitmap =
            renderer.openPage(index).use { page ->
                val size = renderSize(page.width, page.height, maxSize)
                Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(Color.WHITE)
                    page.render(this, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }

        /**
         * Pixel size for a page of [width]×[height] points: as large as fits [maxSize] (vector pages scale up
         * cleanly), capped at [BoundedDecoder.MAX_PIXELS] so a huge page size cannot exhaust memory.
         */
        fun renderSize(
            width: Int,
            height: Int,
            maxSize: IntSize,
        ): IntSize {
            val w = width.coerceAtLeast(1).toDouble()
            val h = height.coerceAtLeast(1).toDouble()
            var scale = min(maxSize.width.coerceAtLeast(1) / w, maxSize.height.coerceAtLeast(1) / h)
            val pixels = w * scale * h * scale
            if (pixels > BoundedDecoder.MAX_PIXELS) scale *= sqrt(BoundedDecoder.MAX_PIXELS / pixels)
            return IntSize((w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1))
        }
    }
}
