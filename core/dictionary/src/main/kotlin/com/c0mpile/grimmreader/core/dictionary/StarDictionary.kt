package com.c0mpile.grimmreader.core.dictionary

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/** One article: the headword as the dictionary spells it and its fields (StarDict type letter and text). */
internal data class Article(
    val word: String,
    val fields: List<Pair<Char, String>>,
)

/**
 * An installed StarDict dictionary: `name.ifo`, `name.idx`, optional `name.syn` (other forms pointing at an
 * entry) and `name.dict` or `name.dict.dz`, all in [dir].
 */
internal class StarDictionary private constructor(
    val info: StarDictInfo,
    private val index: WordList,
    private val synonyms: WordList?,
    private val data: DictData,
) : Closeable {
    private val offsetBytes = info.offsetBits / BYTE_BITS

    /** Articles whose headword or synonym equals [word] ignoring ASCII case; each article once. */
    fun lookup(word: String): List<Article> {
        val entries = LinkedHashSet<Int>()
        index.find(word).forEach { entries += it }
        synonyms?.let { syn ->
            syn.find(word).forEach { i ->
                syn
                    .int(i, 0)
                    .toInt()
                    .takeIf { it in 0 until index.size }
                    ?.let(entries::add)
            }
        }
        return entries.mapNotNull { article(it) }
    }

    private fun article(entry: Int): Article? {
        val offset = if (offsetBytes == LONG_BYTES) index.long(entry, 0) else index.int(entry, 0)
        val size = index.int(entry, offsetBytes)
        if (size > DictData.MAX_ARTICLE_BYTES) return null
        val bytes = runCatching { data.read(offset, size.toInt()) }.getOrNull() ?: return null
        return Article(index.word(entry), fields(bytes, info.sameTypeSequence))
    }

    override fun close() = data.close()

    companion object {
        private const val BYTE_BITS = 8
        private const val LONG_BYTES = 8
        private const val INT_BYTES = 4

        private fun <T> T?.orFail(what: String): T = this ?: throw IOException(what)

        /** Throws [IOException] when a file is missing or damaged. */
        fun open(dir: File): StarDictionary {
            val ifo = dir.listFiles { f -> f.name.endsWith(".ifo") }?.singleOrNull().orFail("No .ifo")
            val stem = ifo.name.removeSuffix(".ifo")
            val info = StarDictInfo.parse(ifo.readText()).orFail("Bad .ifo")
            val idx = File(dir, "$stem.idx").takeIf { it.isFile }.orFail("No .idx")
            val dict =
                listOf("$stem.dict.dz", "$stem.dict").map { File(dir, it) }.firstOrNull { it.isFile }.orFail("No .dict")
            val index = WordList.open(idx, info.offsetBits / BYTE_BITS + INT_BYTES)
            val syn = File(dir, "$stem.syn").takeIf { it.isFile }?.let { WordList.open(it, INT_BYTES) }
            return StarDictionary(info, index, syn, DictData.open(dict))
        }

        /**
         * Splits an article into fields. With a same-type sequence the type letters are implied and the last
         * field runs to the end; otherwise each field starts with its letter. Lower-case types are
         * NUL-terminated text, upper-case ones (sounds, pictures) carry a 32-bit size and are skipped.
         */
        fun fields(
            bytes: ByteArray,
            sameTypeSequence: String?,
        ): List<Pair<Char, String>> {
            val buf = ByteBuffer.wrap(bytes)
            val out = mutableListOf<Pair<Char, String>>()
            if (sameTypeSequence != null) {
                sameTypeSequence.forEachIndexed { i, type ->
                    readField(buf, type, last = i == sameTypeSequence.lastIndex)?.let { out += type to it }
                }
            } else {
                while (buf.hasRemaining()) {
                    val type = (buf.get().toInt() and BYTE_MASK).toChar()
                    readField(buf, type, last = false)?.let { out += type to it }
                }
            }
            return out
        }

        private const val BYTE_MASK = 0xFF

        /** The field's text, or null for binary fields; leaves [buf] after the field. */
        private fun readField(
            buf: ByteBuffer,
            type: Char,
            last: Boolean,
        ): String? {
            if (!buf.hasRemaining()) return null
            if (type.isUpperCase()) {
                val size =
                    if (last) {
                        buf.remaining()
                    } else if (buf.remaining() >= INT_BYTES) {
                        buf.int
                    } else {
                        0
                    }
                buf.position(minOf(buf.limit(), buf.position() + maxOf(0, size)))
                return null
            }
            val start = buf.position()
            var end = start
            if (last) {
                end = buf.limit()
            } else {
                while (end < buf.limit() && buf.get(end).toInt() != 0) end++
            }
            val text = String(buf.array(), start, end - start, Charsets.UTF_8)
            buf.position(minOf(buf.limit(), end + 1))
            return text.trimEnd('\u0000')
        }
    }
}
