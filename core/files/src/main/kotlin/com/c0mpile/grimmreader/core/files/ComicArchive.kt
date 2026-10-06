package com.c0mpile.grimmreader.core.files

import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/** Random access to the page images of a comic archive, in reading order. */
interface ComicArchive : Closeable {
    val pageCount: Int

    fun open(index: Int): InputStream

    fun pageName(index: Int): String
}

/** CBZ via java.util.zip (random access, no full extraction). */
class ZipComicArchive(
    file: File,
) : ComicArchive {
    private val zip = ZipFile(file)
    private val pages =
        zip
            .entries()
            .asSequence()
            .filter { !it.isDirectory && isImage(it.name) && !it.name.substringAfterLast('/').startsWith(".") }
            .map { it.name }
            .sortedWith(NaturalOrder)
            .toList()

    override val pageCount: Int get() = pages.size

    override fun open(index: Int): InputStream = zip.getInputStream(zip.getEntry(pages[index]))

    override fun pageName(index: Int): String = pages[index]

    override fun close() = zip.close()

    /** Raw bytes of ComicInfo.xml, if present. */
    fun comicInfo(): ByteArray? = zip.getEntry("ComicInfo.xml")?.let { e -> zip.getInputStream(e).use { it.readBytes() } }

    companion object {
        private val IMAGE = setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "bmp")

        fun isImage(name: String) = name.substringAfterLast('.', "").lowercase() in IMAGE
    }
}

/** "page2" before "page10"; case-insensitive. */
object NaturalOrder : Comparator<String> {
    private val chunk = Regex("\\d+|\\D+")

    override fun compare(
        a: String,
        b: String,
    ): Int {
        val x = chunk.findAll(a.lowercase()).map { it.value }.toList()
        val y = chunk.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(x.size, y.size)) {
            val p = x[i]
            val q = y[i]
            val c =
                if (p[0].isDigit() && q[0].isDigit()) {
                    p
                        .trimStart('0')
                        .length
                        .compareTo(q.trimStart('0').length)
                        .takeIf { it != 0 }
                        ?: p.trimStart('0').compareTo(q.trimStart('0'))
                } else {
                    p.compareTo(q)
                }
            if (c != 0) return c
        }
        return x.size.compareTo(y.size)
    }
}
