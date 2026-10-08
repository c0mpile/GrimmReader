package com.c0mpile.grimmreader.core.dictionary

import java.util.Locale

/**
 * Forms of a word from the page to try in order: as written, lower-case, capitalized, then base forms by
 * stripping common English endings ("running" → "run", "studies" → "study"). Dictionaries usually list only
 * base forms; other languages need a dictionary with a `.syn` file for their inflections.
 */
internal object WordForms {
    fun of(raw: String): List<String> {
        val word = clean(raw)
        if (word.isEmpty()) return emptyList()
        val lower = word.lowercase(Locale.ROOT)
        val forms = linkedSetOf(word, lower, lower.replaceFirstChar { it.titlecase(Locale.ROOT) })
        val base = lower.removeSuffix("'s").removeSuffix("s'")
        forms += base
        if (base.all { it in 'a'..'z' || it == '-' }) forms += stems(base)
        return forms.filter { it.length >= MIN_LENGTH || it == word }.toList()
    }

    /** Typographic apostrophes made plain; punctuation and quotes around the word removed. */
    fun clean(raw: String): String =
        raw
            .replace('’', '\'')
            .replace('ʼ', '\'')
            .trim()
            .trim { !it.isLetterOrDigit() }
            .take(MAX_LENGTH)

    private fun stems(w: String): List<String> =
        buildList {
            for ((suffix, replacements) in RULES) {
                if (strips(w, suffix)) {
                    val stem = w.dropLast(suffix.length)
                    replacements.forEach { add(stem + it) }
                    // "stopped" → "stop", "running" → "run"
                    if (suffix in DOUBLING && endsDoubled(stem)) {
                        add(stem.dropLast(1))
                    }
                }
            }
        }

    /** "-s" is not stripped from "class" or "bus". */
    private fun strips(
        w: String,
        suffix: String,
    ): Boolean {
        val fits = w.length > suffix.length + 1 && w.endsWith(suffix)
        return fits && !(suffix == "s" && (w.endsWith("ss") || w.endsWith("us")))
    }

    private fun endsDoubled(stem: String): Boolean {
        val doubled = stem.length >= 2 && stem.last() == stem[stem.length - 2]
        return doubled && stem.last() !in "aeiouls"
    }

    private val RULES =
        listOf(
            "iest" to listOf("y"),
            "ier" to listOf("y"),
            "ies" to listOf("y"),
            "ied" to listOf("y"),
            "ily" to listOf("y"),
            "ves" to listOf("f", "fe"),
            "ing" to listOf("", "e"),
            "est" to listOf("", "e"),
            "es" to listOf(""),
            "ed" to listOf("", "e"),
            "er" to listOf("", "e"),
            "ly" to listOf(""),
            "s" to listOf(""),
        )
    private val DOUBLING = setOf("ing", "ed", "er", "est")
    private const val MIN_LENGTH = 2
    private const val MAX_LENGTH = 64
}
