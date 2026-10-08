package com.c0mpile.grimmreader.core.files

import android.text.Html

/** Book descriptions are often HTML (server metadata, OPF `dc:description`); the app shows them as plain text. */
object HtmlText {
    private val BLANK_LINES = Regex("\n{3,}")
    private val SPACES = Regex("[ \t ]+")

    /** Tags dropped, entities decoded, paragraphs kept as blank lines; null when nothing is left. Images are never loaded. */
    fun plain(html: String): String? {
        val text =
            if ('<' in html || '&' in html) {
                Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString().replace("￼", "")
            } else {
                html
            }
        return text
            .lines()
            .joinToString("\n") { it.replace(SPACES, " ").trim() }
            .replace(BLANK_LINES, "\n\n")
            .trim()
            .ifEmpty { null }
    }
}
