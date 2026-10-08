package com.c0mpile.grimmreader.core.dictionary

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.Deflater

/** Builds small synthetic StarDict dictionaries for tests. */
internal object TestDictionary {
    /**
     * Writes `[stem].ifo/.idx/.dict(.dz)` (and `.syn` when [synonyms] is given) into [dir]. [articles] maps
     * headwords to their text; entries are sorted the way StarDict sorts them.
     */
    fun write(
        dir: File,
        stem: String,
        name: String,
        articles: Map<String, String>,
        synonyms: Map<String, String> = emptyMap(),
        type: Char = 'm',
        dictZipChunk: Int? = null,
    ): File {
        dir.mkdirs()
        val sorted = articles.entries.sortedWith(compareBy<Map.Entry<String, String>>({ it.key.lowercase() }, { it.key }))
        val data = ByteArrayOutputStream()
        val idx = ByteArrayOutputStream()
        DataOutputStream(idx).use { out ->
            for ((word, text) in sorted) {
                val bytes = text.encodeToByteArray()
                out.write(word.encodeToByteArray())
                out.write(0)
                out.writeInt(data.size())
                out.writeInt(bytes.size)
                data.write(bytes)
            }
        }
        File(dir, "$stem.idx").writeBytes(idx.toByteArray())
        if (synonyms.isNotEmpty()) {
            val syn = ByteArrayOutputStream()
            DataOutputStream(syn).use { out ->
                for ((form, target) in synonyms.entries.sortedWith(compareBy({ it.key.lowercase() }, { it.key }))) {
                    out.write(form.encodeToByteArray())
                    out.write(0)
                    out.writeInt(sorted.indexOfFirst { it.key == target })
                }
            }
            File(dir, "$stem.syn").writeBytes(syn.toByteArray())
        }
        if (dictZipChunk != null) {
            File(dir, "$stem.dict.dz").writeBytes(dictZip(data.toByteArray(), dictZipChunk))
        } else {
            File(dir, "$stem.dict").writeBytes(data.toByteArray())
        }
        File(dir, "$stem.ifo").writeText(
            """
            |StarDict's dict ifo file
            |version=2.4.2
            |bookname=$name
            |wordcount=${articles.size}
            |idxfilesize=${idx.size()}
            |sametypesequence=$type
            |
            """.trimMargin(),
        )
        return dir
    }

    /** dictzip: raw deflate flushed every [chunk] input bytes, chunk sizes in the gzip "RA" extra field. */
    fun dictZip(
        data: ByteArray,
        chunk: Int,
    ): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        val chunks = mutableListOf<ByteArray>()
        var at = 0
        while (at < data.size) {
            val n = minOf(chunk, data.size - at)
            deflater.setInput(data, at, n)
            at += n
            val out = ByteArrayOutputStream()
            val buf = ByteArray(chunk * 2 + 64)
            do {
                val got = deflater.deflate(buf, 0, buf.size, Deflater.FULL_FLUSH)
                out.write(buf, 0, got)
            } while (got == buf.size)
            chunks += out.toByteArray()
        }
        deflater.setInput(ByteArray(0))
        deflater.finish()
        val end = ByteArrayOutputStream()
        val buf = ByteArray(64)
        while (!deflater.finished()) end.write(buf, 0, deflater.deflate(buf))
        deflater.end()

        val ra = ByteArrayOutputStream()

        fun le16(
            out: ByteArrayOutputStream,
            v: Int,
        ) {
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
        }
        le16(ra, 1)
        le16(ra, chunk)
        le16(ra, chunks.size)
        chunks.forEach { le16(ra, it.size) }
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x1F, 0x8B.toByte(), 8, 4, 0, 0, 0, 0, 0, 3))
        le16(out, ra.size() + 4)
        out.write('R'.code)
        out.write('A'.code)
        le16(out, ra.size())
        out.write(ra.toByteArray())
        chunks.forEach { out.write(it) }
        out.write(end.toByteArray())
        val crc = CRC32().apply { update(data) }.value
        for (v in listOf(crc, data.size.toLong())) for (i in 0 until 4) out.write(((v shr (8 * i)) and 0xFF).toInt())
        return out.toByteArray()
    }
}
