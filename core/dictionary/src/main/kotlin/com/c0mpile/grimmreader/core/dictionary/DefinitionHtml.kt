package com.c0mpile.grimmreader.core.dictionary

/**
 * Turns article fields into the small HTML subset Compose's `AnnotatedString.fromHtml` renders (b, i, u, br,
 * p, ul/li, blockquote, sub/sup, big/small). Dictionary data is untrusted: links, images, scripts and styles are
 * removed, so a definition can neither open anything nor load anything.
 */
internal object DefinitionHtml {
    fun of(fields: List<Pair<Char, String>>): String =
        fields
            .mapNotNull { (type, text) ->
                when (type) {
                    'h' -> sanitize(text)
                    'g' -> sanitize(newlines(text))
                    'x' -> sanitize(newlines(xdxf(text)))
                    'm', 'l', 'y', 'k', 'w' -> newlines(escape(text))
                    't' -> "[${escape(text)}]"
                    else -> null
                }?.trim()?.takeIf { it.isNotEmpty() }
            }.joinToString("<br>")

    /** XDXF article markup: headword bold, examples and abbreviations italic, cross-references underlined. */
    private fun xdxf(text: String): String =
        text
            .replace(Regex("<(/?)k\\b[^>]*>")) { "<${it.groupValues[1]}b>" }
            .replace(Regex("<(/?)(ex|abr|co)\\b[^>]*>")) { "<${it.groupValues[1]}i>" }
            .replace(Regex("<(/?)kref\\b[^>]*>")) { "<${it.groupValues[1]}u>" }
            .replace(Regex("<tr\\b[^>]*>"), "[")
            .replace("</tr>", "]")

    private val dropWithContent =
        Regex("<(script|style|head|title)\\b[^>]*>.*?</\\1\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val dropTag =
        Regex("</?(a|img|link|iframe|object|embed|audio|video|source|svg|math|form|input|button|font)\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val comment = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)

    /**
     * Repeated until nothing changes: one pass can leave a working tag behind when markup is nested to hide it
     * (`<<a>a href=…>` becomes `<a href=…>`). The renderer also ignores link clicks, so this is not the only guard.
     */
    fun sanitize(html: String): String {
        var current = html
        repeat(MAX_PASSES) {
            val next = current.replace(comment, "").replace(dropWithContent, "").replace(dropTag, "")
            if (next == current) return next
            current = next
        }
        // Still changing: give up on markup and keep the text.
        return current.replace("<", "&lt;").replace(">", "&gt;")
    }

    private const val MAX_PASSES = 8

    private fun newlines(text: String) = text.replace("\r\n", "\n").replace("\n", "<br>")

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
