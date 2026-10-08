package com.c0mpile.grimmreader.core.dictionary

import com.github.luben.zstd.ZstdOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.zip.GZIPOutputStream

/** Plain JVM (not Robolectric): the archive formats the catalogue downloads come in. */
class UnpackerTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun sample(stem: String): List<File> =
        TestDictionary
            .write(
                tmp.newFolder(stem),
                stem,
                "Sample $stem",
                mapOf("dog" to "an animal"),
                dictZipChunk = 32,
            ).listFiles()!!
            .toList()

    private fun tar(
        files: List<File>,
        wrap: (OutputStream) -> OutputStream,
    ): ByteArray {
        val bytes = ByteArrayOutputStream()
        TarArchiveOutputStream(wrap(bytes)).use { out ->
            for (f in files + listOf(File(tmp.root, "res.png").apply { writeBytes(byteArrayOf(1, 2)) })) {
                out.putArchiveEntry(TarArchiveEntry("./${f.name}").apply { size = f.length() })
                f.inputStream().use { it.copyTo(out) }
                out.closeArchiveEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun unpack(
        name: String,
        bytes: ByteArray,
    ): List<String> {
        val staging = tmp.newFolder()
        DictionaryUnpacker(staging).add(name, bytes.inputStream())
        return staging.list()!!.sorted()
    }

    @Test fun unpacksTarXzAndTarZst() {
        assertEquals(
            listOf("xz.dict.dz", "xz.idx", "xz.ifo"),
            unpack("freedict-xx.stardict.tar.xz", tar(sample("xz")) { XZOutputStream(it, LZMA2Options()) }),
        )
        assertEquals(listOf("zst.dict.dz", "zst.idx", "zst.ifo"), unpack("xx-en.tar.zst", tar(sample("zst")) { ZstdOutputStream(it) }))
    }

    @Test fun inflatesGzippedIndexAndPlainGzipDict() {
        val files = sample("gz")
        val idx = files.first { it.name.endsWith(".idx") }
        val gzIdx = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(idx.readBytes()) } }.toByteArray()
        val plain = "article".toByteArray()
        val gzDict = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(plain) } }.toByteArray()
        val staging = tmp.newFolder()
        DictionaryUnpacker(staging).apply {
            add("gz.idx.gz", gzIdx.inputStream())
            add("gz.dict.dz", gzDict.inputStream())
        }
        assertTrue(File(staging, "gz.idx").readBytes().contentEquals(idx.readBytes()))
        assertTrue(File(staging, "gz.dict").readBytes().contentEquals(plain))
    }
}
