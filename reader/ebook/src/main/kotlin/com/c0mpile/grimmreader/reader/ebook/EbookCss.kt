package com.c0mpile.grimmreader.reader.ebook

import com.c0mpile.grimmreader.core.model.ReaderPrefs

/** Reader colours applied inside the book (the chrome around it is themed by Compose). */
data class PageColors(
    val background: String,
    val text: String,
    val link: String,
    /** CSS `filter` for images on the page (E-ink grayscale, Night warm), or null. */
    val imageFilter: String? = null,
)

/** CSS injected into every section, following the web reader's strategy (font size on html, colours, layout). */
object EbookCss {
    fun build(
        prefs: ReaderPrefs,
        colors: PageColors,
    ): String {
        val font = ReaderFonts.resolve(prefs.fontFamily)
        return buildString {
            font?.let { append(fontFaces(it)) }
            append("html { font-size: ${prefs.fontSize}px !important; ")
            append("color: ${colors.text} !important; background: ${colors.background} !important; }")
            append(" body { background: transparent !important; color: inherit !important; }")
            append(" p, li, blockquote, dd { line-height: ${prefs.lineHeight} !important; ")
            append("text-align: ${if (prefs.justify) "justify" else "start"} !important; ")
            append("hyphens: ${if (prefs.hyphenate) "auto" else "manual"} !important; }")
            if (font != null) append(" body, p, li, div, span { font-family: \"${font.cssFamily}\", ${font.generic} !important; }")
            append(" a:link, a:visited { color: ${colors.link} !important; }")
            colors.imageFilter?.let { append(" img, svg, video { filter: $it !important; }") }
        }
    }

    /** Absolute URLs: sections are blob: documents, against which relative URLs don't resolve. */
    private fun fontFaces(font: ReaderFont): String =
        font.faces.joinToString("") { face ->
            "@font-face { font-family: \"${font.cssFamily}\"; src: url(\"$ORIGIN/assets/fonts/${face.file}\"); " +
                "font-weight: ${face.weight}; font-style: ${if (face.italic) "italic" else "normal"}; font-display: block; } "
        }
}
