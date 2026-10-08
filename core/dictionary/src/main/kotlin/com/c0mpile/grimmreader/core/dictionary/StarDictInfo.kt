package com.c0mpile.grimmreader.core.dictionary

/** A StarDict `.ifo` file: the dictionary's name and how its `.idx`, `.syn` and `.dict` files are laid out. */
internal data class StarDictInfo(
    val name: String,
    val wordCount: Int,
    val idxFileSize: Long,
    /** 32 or 64: the width of the offsets in the `.idx` file. */
    val offsetBits: Int = OFFSET_32,
    /** When set, every entry holds exactly these fields in this order, without type letters. */
    val sameTypeSequence: String? = null,
) {
    companion object {
        private const val MAGIC = "StarDict's dict ifo file"
        private const val BOM = "\uFEFF"
        const val OFFSET_32 = 32
        const val OFFSET_64 = 64

        /** Null when [text] is not a usable `.ifo` (wrong magic line, no name, no word count). */
        fun parse(text: String): StarDictInfo? {
            val lines = text.removePrefix(BOM).lines().map { it.trim() }
            if (lines.firstOrNull() != MAGIC) return null
            val values =
                lines
                    .drop(1)
                    .mapNotNull { line ->
                        val eq = line.indexOf('=')
                        if (eq > 0) line.substring(0, eq).trim() to line.substring(eq + 1).trim() else null
                    }.toMap()
            val name = values["bookname"]?.takeIf { it.isNotEmpty() } ?: return null
            val words = values["wordcount"]?.toIntOrNull()?.takeIf { it >= 0 } ?: return null
            val bits = values["idxoffsetbits"]?.toIntOrNull() ?: OFFSET_32
            if (bits != OFFSET_32 && bits != OFFSET_64) return null
            return StarDictInfo(
                name = name,
                wordCount = words,
                idxFileSize = values["idxfilesize"]?.toLongOrNull() ?: -1,
                offsetBits = bits,
                sameTypeSequence = values["sametypesequence"]?.takeIf { it.isNotEmpty() },
            )
        }
    }
}
