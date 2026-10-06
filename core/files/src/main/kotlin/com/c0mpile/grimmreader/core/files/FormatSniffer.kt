package com.c0mpile.grimmreader.core.files

import com.c0mpile.grimmreader.core.model.BookFormat
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipFile

/**
 * Detects the real format from the file's bytes; extensions and server content types lie (the server labels
 * CBZ files `application/x-cbr`, page images are always `image/jpeg`). Falls back to the extension.
 */
object FormatSniffer {
    private const val HEADER_BYTES = 128
    private const val MOBI_MAGIC_OFFSET = 60
    private const val MOBI_MAGIC = "BOOKMOBI"
    private val SEVEN_ZIP = byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)

    fun sniff(
        file: File,
        nameHint: String? = null,
    ): BookFormat? {
        val header = ByteArray(HEADER_BYTES)
        val n = RandomAccessFile(file, "r").use { it.read(header) }
        val fromBytes =
            when {
                n <= 0 -> null
                header.startsWith("%PDF") -> BookFormat.PDF
                header.startsWith("PK\u0003\u0004") -> sniffZip(file)
                header.startsWith(SEVEN_ZIP) -> BookFormat.CB7
                header.startsWith("Rar!\u001A\u0007") -> BookFormat.CBR
                n >= MOBI_MAGIC_OFFSET + MOBI_MAGIC.length &&
                    String(header, MOBI_MAGIC_OFFSET, MOBI_MAGIC.length, Charsets.ISO_8859_1) == MOBI_MAGIC -> BookFormat.MOBI
                String(header, 0, n, Charsets.UTF_8).contains("<FictionBook") -> BookFormat.FB2
                else -> null
            }
        return fromBytes ?: nameHint?.substringAfterLast('.', "")?.let(BookFormat::fromExtension)
    }

    private fun sniffZip(file: File): BookFormat =
        ZipFile(file).use { zip ->
            val mimetype =
                zip.getEntry("mimetype")?.let { e ->
                    zip.getInputStream(e).use { it.readBounded(ReadLimits.SNIFF_BYTES)?.decodeToString()?.trim() }
                }
            when {
                mimetype == "application/epub+zip" || zip.getEntry("META-INF/container.xml") != null -> BookFormat.EPUB
                else -> BookFormat.CBZ
            }
        }

    private fun ByteArray.startsWith(prefix: String) = startsWith(prefix.toByteArray(Charsets.ISO_8859_1))

    private fun ByteArray.startsWith(prefix: ByteArray) = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}
