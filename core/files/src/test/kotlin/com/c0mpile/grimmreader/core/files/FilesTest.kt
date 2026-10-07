package com.c0mpile.grimmreader.core.files

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.c0mpile.grimmreader.core.common.PartialMd5
import com.c0mpile.grimmreader.core.model.BookFormat
import com.c0mpile.grimmreader.core.model.ReadingDirection
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class FilesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)

    private fun zip(
        name: String,
        vararg entries: Pair<String, ByteArray>,
    ): File {
        val file = File(tmp.root, name)
        ZipOutputStream(file.outputStream()).use { out ->
            for ((path, bytes) in entries) {
                val entry = ZipEntry(path)
                if (path == "mimetype") {
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.crc = CRC32().apply { update(bytes) }.value
                }
                out.putNextEntry(entry)
                out.write(bytes)
                out.closeEntry()
            }
        }
        return file
    }

    private val container =
        "<container><rootfiles>" +
            "<rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/>" +
            "</rootfiles></container>"

    private val opf =
        "<package xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><metadata>" +
            "<dc:title>Sample Ebook</dc:title><dc:creator>Ada Example</dc:creator></metadata><manifest>" +
            "<item id=\"c\" href=\"images/cover%20art.png\" media-type=\"image/png\" properties=\"cover-image\"/>" +
            "</manifest></package>"

    private fun epub() =
        zip(
            "sample.bin",
            "mimetype" to "application/epub+zip".toByteArray(),
            "META-INF/container.xml" to container.toByteArray(),
            "OEBPS/content.opf" to opf.toByteArray(),
            "OEBPS/images/cover art.png" to png,
        )

    @Test fun sniffsByContentNotExtension() {
        assertEquals(BookFormat.EPUB, FormatSniffer.sniff(epub(), "whatever.cbr"))
        assertEquals(BookFormat.CBZ, FormatSniffer.sniff(zip("comic.cbr", "001.jpg" to png), "comic.cbr"))
        assertEquals(BookFormat.PDF, FormatSniffer.sniff(File(tmp.root, "a").apply { writeText("%PDF-1.7\n") }))
        // CBZ only: RAR and 7z are rejected even when named like a CBZ.
        val rar = File(tmp.root, "r.cbz").apply { writeBytes("Rar!\u001A\u0007\u0001\u0000".toByteArray(Charsets.ISO_8859_1)) }
        assertEquals(null, FormatSniffer.sniff(rar, "r.cbz"))
        val sevenZip = File(tmp.root, "s.cbz").apply { writeBytes(byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C, 0, 4)) }
        assertEquals(null, FormatSniffer.sniff(sevenZip, "s.cbz"))
        val mobi = ByteArray(80).also { "BOOKMOBI".toByteArray().copyInto(it, 60) }
        assertEquals(BookFormat.MOBI, FormatSniffer.sniff(File(tmp.root, "m").apply { writeBytes(mobi) }))
        assertEquals(BookFormat.FB2, FormatSniffer.sniff(File(tmp.root, "f").apply { writeText("<?xml version=\"1.0\"?><FictionBook>") }))
        assertEquals(null, FormatSniffer.sniff(File(tmp.root, "x.m4b").apply { writeText("....ftypM4B") }, "x.m4b"))
    }

    @Test fun epubMetadataAndCover() {
        val meta = BookMetadataReader.read(epub(), BookFormat.EPUB)
        assertEquals("Sample Ebook", meta.title)
        assertEquals(listOf("Ada Example"), meta.authors)
        assertArrayEquals(png, meta.cover)
    }

    @Test fun cbzPagesInNaturalOrderAndComicInfo() {
        val comic =
            zip(
                "c.cbz",
                "p10.jpg" to byteArrayOf(10),
                "p2.jpg" to byteArrayOf(2),
                "P1.png" to byteArrayOf(1),
                "__MACOSX/._p1.jpg" to byteArrayOf(0),
                "notes.txt" to byteArrayOf(9),
                "ComicInfo.xml" to
                    (
                        "<ComicInfo><Series>Sample Series</Series><Number>3</Number><Writer>A. Writer, B. Writer</Writer>" +
                            "<Manga>YesAndRightToLeft</Manga></ComicInfo>"
                    ).toByteArray(),
            )
        ZipComicArchive(comic).use { archive ->
            assertEquals(
                listOf("P1.png", "p2.jpg", "p10.jpg"),
                (0 until archive.pageCount).map(archive::pageName).filterNot { it.contains("MACOSX") },
            )
        }
        val meta = BookMetadataReader.read(comic, BookFormat.CBZ)
        assertEquals("Sample Series #3", meta.title)
        assertEquals(listOf("A. Writer", "B. Writer"), meta.authors)
        assertEquals(ReadingDirection.RTL, meta.readingDirection)
    }

    @Test fun filePartialMd5MatchesByteVersion() {
        val bytes = ByteArray(300_000) { (it * 7).toByte() }
        val file = File(tmp.root, "b").apply { writeBytes(bytes) }
        assertEquals(PartialMd5.compute(bytes), LocalFileStore.partialMd5(file))
    }

    @Test fun oversizedEntriesAreSkippedNotLoaded() {
        val bomb = ByteArray((ReadLimits.XML_BYTES + 1).toInt())
        val comic = zip("big.cbz", "001.jpg" to png, "ComicInfo.xml" to bomb)
        val meta = BookMetadataReader.read(comic, BookFormat.CBZ)
        assertEquals(null, meta.title)
        assertArrayEquals(png, meta.cover)
    }
}
