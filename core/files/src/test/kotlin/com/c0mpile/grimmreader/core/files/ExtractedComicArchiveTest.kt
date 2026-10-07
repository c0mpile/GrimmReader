package com.c0mpile.grimmreader.core.files

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** libarchive is native, so these tests read a zip through the same sequential interface. */
class ExtractedComicArchiveTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun archive(vararg entries: Pair<String, ByteArray>): File {
        val file = File(tmp.root, "comic.cb7")
        ZipOutputStream(file.outputStream()).use { out ->
            for ((path, bytes) in entries) {
                out.putNextEntry(ZipEntry(path))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return file
    }

    private val cache get() = File(tmp.root, "cache")

    @Test fun pagesInNaturalOrderUnderNumberedNames() =
        runTest {
            val file =
                archive(
                    "p10.png" to byteArrayOf(10),
                    "ComicInfo.xml" to byteArrayOf(0),
                    "../../escape/p2.png" to byteArrayOf(2),
                    "sub/.hidden.png" to byteArrayOf(9),
                    "p1.jpg" to byteArrayOf(1),
                )
            val comic = ExtractedComicArchive.open(file, cache, ::ZipSequential)
            assertEquals(listOf("../../escape/p2.png", "p1.jpg", "p10.png"), (0 until comic.pageCount).map(comic::pageName))
            assertArrayEquals(byteArrayOf(2), comic.open(0).use { it.readBytes() })
            assertFalse(File(tmp.root, "escape").exists())
            val stored =
                cache
                    .walk()
                    .filter { it.isFile }
                    .map { it.name }
                    .toSet()
            assertEquals(setOf("0.page", "1.page", "2.page", "index"), stored)
        }

    @Test fun secondOpenUsesTheCache() =
        runTest {
            val file = archive("a.png" to byteArrayOf(1), "b.png" to byteArrayOf(2))
            ExtractedComicArchive.open(file, cache, ::ZipSequential)
            var reopened = 0
            val reopen: (File) -> SequentialArchive = {
                reopened++
                ZipSequential(it)
            }
            val comic = ExtractedComicArchive.open(file, cache, reopen)
            assertEquals(0, reopened)
            assertEquals(2, comic.pageCount)
        }

    @Test fun oversizedPagesAreDropped() =
        runTest {
            val big = ByteArray((ExtractedComicArchive.MAX_PAGE_BYTES + 1).toInt())
            val file = archive("a.png" to byteArrayOf(1), "big.png" to big, "c.png" to byteArrayOf(3))
            val comic = ExtractedComicArchive.open(file, cache, ::ZipSequential)
            assertEquals(listOf("a.png", "c.png"), (0 until comic.pageCount).map(comic::pageName))
        }

    @Test fun decompressionBombsAreRejected() =
        runTest {
            val mib = ByteArray(1 shl 20)
            val pages = (ExtractedComicArchive.MAX_PAGE_BYTES shr 20).toInt() + 8
            val file = archive(*Array(pages) { "p$it.png" to mib })
            val failed = runCatching { ExtractedComicArchive.open(file, cache, ::ZipSequential) }.exceptionOrNull()
            assertTrue(failed is IOException)
            assertTrue("partial extraction is removed", cache.listFiles().orEmpty().isEmpty())
        }

    @Test fun failedExtractionIsRetriedNextTime() =
        runTest {
            val file = archive("a.png" to byteArrayOf(1))
            val failed =
                runCatching {
                    ExtractedComicArchive.open(file, cache, openArchive = { throw IOException("broken") })
                }.exceptionOrNull()
            assertTrue(failed is IOException)
            assertEquals(1, ExtractedComicArchive.open(file, cache, ::ZipSequential).pageCount)
        }
}
