package com.c0mpile.grimmreader.core.files

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.util.Xml
import com.c0mpile.grimmreader.core.model.BookFormat
import com.c0mpile.grimmreader.core.model.ReadingDirection
import org.apache.commons.compress.archivers.zip.ZipFile
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.time.LocalDate
import java.time.YearMonth

/** Metadata found inside a local file. Everything is optional; the file name is the last resort title. */
data class LocalMetadata(
    val title: String?,
    val authors: List<String> = emptyList(),
    val series: String? = null,
    val seriesNumber: Float? = null,
    val readingDirection: ReadingDirection? = null,
    /** Plain text ([HtmlText.plain] applied). */
    val description: String? = null,
    val publisher: String? = null,
    /** ISO date, year-month or year, as the file gives it. */
    val publishedDate: String? = null,
    /** Cover image bytes (any image format). */
    val cover: ByteArray? = null,
)

/**
 * Reads title, authors, description, publisher, date and cover from EPUB (OPF) and CBZ (ComicInfo.xml, first page)
 * files, and a PDF's first page.
 */
object BookMetadataReader {
    fun read(
        file: File,
        format: BookFormat,
    ): LocalMetadata = BookHandle.open(file).use { read(it, format) }

    fun read(
        book: BookHandle,
        format: BookFormat,
    ): LocalMetadata =
        runCatching {
            when (format) {
                BookFormat.EPUB -> epub(book)
                BookFormat.CBZ -> cbz(book)
                BookFormat.PDF -> LocalMetadata(title = null, cover = pdfCover(book))
                else -> LocalMetadata(title = null)
            }
        }.getOrElse { LocalMetadata(title = null) }

    private fun epub(book: BookHandle): LocalMetadata =
        book.zip().use { zip ->
            val opfPath = zip.text("META-INF/container.xml")?.let { attr(it, "rootfile", "full-path") } ?: return LocalMetadata(null)
            val opf = zip.text(opfPath) ?: return LocalMetadata(null)
            val parsed = parseOpf(opf.byteInputStream())
            val base = opfPath.substringBeforeLast('/', "")
            val coverHref = parsed.coverHref?.let { if (base.isEmpty()) it else "$base/$it" }
            val cover =
                coverHref?.let { zip.getEntry(it.decodePercent()) }?.let { e ->
                    zip.getInputStream(e).use { it.readBounded(ReadLimits.IMAGE_BYTES) }
                }
            LocalMetadata(
                title = parsed.title,
                authors = parsed.authors,
                description = parsed.description,
                publisher = parsed.publisher,
                publishedDate = parsed.date,
                cover = cover,
            )
        }

    private fun cbz(book: BookHandle): LocalMetadata =
        ZipComicArchive(book).use { archive ->
            val info = archive.comicInfo()?.let { parseComicInfo(it.inputStream()) }
            val cover = if (archive.pageCount > 0) archive.open(0).use { it.readBounded(ReadLimits.IMAGE_BYTES) } else null
            (info ?: LocalMetadata(title = null)).copy(cover = cover)
        }

    /** Page 1 rendered on white at cover height, as JPEG. */
    private fun pdfCover(book: BookHandle): ByteArray? =
        book.descriptor().use { fd ->
            PdfRenderer(fd).use { renderer ->
                if (renderer.pageCount == 0) return null
                renderer.openPage(0).use { page ->
                    val height = PDF_COVER_HEIGHT
                    val width = (height.toLong() * page.width / page.height.coerceAtLeast(1)).toInt().coerceIn(1, height * 2)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, PDF_COVER_QUALITY, it) }.toByteArray()
                }
            }
        }

    private const val PDF_COVER_HEIGHT = 840
    private const val PDF_COVER_QUALITY = 85

    private class Opf(
        val title: String?,
        val authors: List<String>,
        val coverHref: String?,
        val description: String?,
        val publisher: String?,
        val date: String?,
    )

    private fun parseOpf(input: InputStream): Opf {
        val parser = Xml.newPullParser().apply { setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false) }
        parser.setInput(input, null)
        val state = OpfState()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) state.onTag(parser)
        }
        return Opf(
            state.title,
            state.authors,
            state.coverByProperty ?: state.coverId?.let(state.items::get),
            state.description?.let(HtmlText::plain),
            state.publisher,
            state.date,
        )
    }

    private class OpfState {
        var title: String? = null
        val authors = mutableListOf<String>()
        var coverId: String? = null
        val items = mutableMapOf<String, String>()
        var coverByProperty: String? = null
        var description: String? = null
        var publisher: String? = null
        var date: String? = null

        fun onTag(parser: XmlPullParser) {
            when (parser.name.substringAfter(':')) {
                "title" -> if (title == null) title = parser.nextText().trim().ifEmpty { null }
                "description" -> if (description == null) description = parser.text()
                "publisher" -> if (publisher == null) publisher = parser.text()
                "date" -> if (date == null) date = parser.text()
                "creator" ->
                    parser
                        .nextText()
                        .trim()
                        .takeIf { it.isNotEmpty() }
                        ?.let(authors::add)
                "meta" -> if (parser.getAttributeValue(null, "name") == "cover") coverId = parser.getAttributeValue(null, "content")
                "item" -> onItem(parser)
            }
        }

        private fun onItem(parser: XmlPullParser) {
            val id = parser.getAttributeValue(null, "id")
            val href = parser.getAttributeValue(null, "href") ?: return
            if (id != null) items[id] = href
            if (parser.getAttributeValue(null, "properties")?.split(' ')?.contains("cover-image") == true) coverByProperty = href
        }

        /** The element's text; null when empty or when it holds markup instead of text (not allowed, but seen). */
        private fun XmlPullParser.text(): String? = runCatching { nextText().trim() }.getOrNull()?.ifEmpty { null }
    }

    private fun parseComicInfo(input: InputStream): LocalMetadata {
        val parser = Xml.newPullParser()
        parser.setInput(input, null)
        val values = mutableMapOf<String, String>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.depth == 2) values[parser.name] = parser.nextText().trim()
        }
        val title = values["Title"]?.ifEmpty { null } ?: values["Series"]?.let { s -> values["Number"]?.let { "$s #$it" } ?: s }
        val authors = listOfNotNull(values["Writer"]).flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
        val rtl = values["Manga"] == "YesAndRightToLeft"
        return LocalMetadata(
            title = title,
            authors = authors,
            series = values["Series"]?.ifEmpty { null },
            seriesNumber = values["Number"]?.toFloatOrNull(),
            readingDirection = if (rtl) ReadingDirection.RTL else null,
            description = values["Summary"]?.let(HtmlText::plain),
            publisher = values["Publisher"]?.ifEmpty { null },
            publishedDate = comicDate(values),
        )
    }

    private fun ZipFile.text(name: String): String? =
        getEntry(name)?.let { e -> getInputStream(e).use { it.readBounded(ReadLimits.XML_BYTES)?.decodeToString() } }

    private fun attr(
        xml: String,
        tag: String,
        name: String,
    ): String? = Regex("<$tag\\b[^>]*\\b$name=\"([^\"]+)\"").find(xml)?.groupValues?.get(1)

    private fun String.decodePercent(): String = java.net.URLDecoder.decode(replace("+", "%2B"), "UTF-8")
}

/** ComicInfo Year/Month/Day as an ISO date, year-month or year (Month and Day are often missing or 0). */
private fun comicDate(values: Map<String, String>): String? {
    val year = values["Year"]?.toIntOrNull()?.takeIf { it > 0 } ?: return null
    val month = values["Month"]?.toIntOrNull()
    val day = values["Day"]?.toIntOrNull()
    return runCatching { LocalDate.of(year, month!!, day!!).toString() }.getOrNull()
        ?: runCatching { YearMonth.of(year, month!!).toString() }.getOrNull()
        ?: year.toString()
}
