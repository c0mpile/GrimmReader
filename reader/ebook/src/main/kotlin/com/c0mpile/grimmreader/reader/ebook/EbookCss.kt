package com.c0mpile.grimmreader.reader.ebook

import com.c0mpile.grimmreader.core.model.ReaderPrefs

/** Reader colours applied inside the book (the chrome around it is themed by Compose). */
data class PageColors(
    val background: String,
    val text: String,
    val link: String,
    /** E-ink pages: images in grayscale with a little more contrast. */
    val grayscaleImages: Boolean = false,
)

/** CSS injected into every section, following the web reader's strategy (font size on html, colours, layout). */
object EbookCss {
    fun build(
        prefs: ReaderPrefs,
        colors: PageColors,
    ): String {
        val family =
            when (prefs.fontFamily.lowercase()) {
                "publisher" -> null
                "sans", "inter" -> "sans-serif"
                else -> "serif"
            }
        return buildString {
            append("html { font-size: ${prefs.fontSize}px !important; ")
            append("color: ${colors.text} !important; background: ${colors.background} !important; }")
            append(" body { background: transparent !important; color: inherit !important; }")
            append(" p, li, blockquote, dd { line-height: ${prefs.lineHeight} !important; ")
            append("text-align: ${if (prefs.justify) "justify" else "start"} !important; ")
            append("hyphens: ${if (prefs.hyphenate) "auto" else "manual"} !important; }")
            if (family != null) append(" body, p, li, div, span { font-family: $family !important; }")
            append(" a:link, a:visited { color: ${colors.link} !important; }")
            if (colors.grayscaleImages) append(" img, svg, video { filter: grayscale(1) contrast(1.15) !important; }")
        }
    }
}
