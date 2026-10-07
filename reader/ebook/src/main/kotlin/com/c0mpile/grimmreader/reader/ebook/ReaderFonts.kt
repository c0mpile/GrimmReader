package com.c0mpile.grimmreader.reader.ebook

/** Groups of the font picker, in display order. */
enum class FontCategory(
    val label: String,
) {
    SANS("Sans-serif"),
    SERIF("Serif"),
    READING("Reading"),
}

/** One font file in `assets/fonts` (fetched by `scripts/fetch-reader-fonts`); [weight] is a CSS weight or range. */
data class FontFace(
    val file: String,
    val weight: String,
    val italic: Boolean = false,
)

/**
 * A bundled reader font. [id] is what [com.c0mpile.grimmreader.core.model.ReaderPrefs.fontFamily] stores; the
 * book's pages load the faces from the app's own origin, so the reader stays offline.
 */
data class ReaderFont(
    val id: String,
    val category: FontCategory,
    val generic: String,
    val faces: List<FontFace>,
) {
    /** The upright regular face, for previews outside the WebView. */
    val previewFile: String get() = faces.first { !it.italic }.file

    internal val cssFamily: String get() = "grimm-" + id.lowercase().replace(' ', '-')
}

object ReaderFonts {
    /** Keeps the book's own fonts. */
    const val PUBLISHER = "Publisher"

    val all: List<ReaderFont> =
        listOf(
            variable("Source Sans 3", FontCategory.SANS, "sans-serif", "source-sans-3", "200 900"),
            variable("Roboto", FontCategory.SANS, "sans-serif", "roboto", "100 900"),
            static("Lato", FontCategory.SANS, "sans-serif", "lato"),
            variable("Open Sans", FontCategory.SANS, "sans-serif", "open-sans", "300 800"),
            variable("Noto Sans", FontCategory.SANS, "sans-serif", "noto-sans", "100 900"),
            variable("Gelasio", FontCategory.SERIF, "serif", "gelasio", "400 700"),
            static("Merriweather", FontCategory.SERIF, "serif", "merriweather"),
            static("Crimson Text", FontCategory.SERIF, "serif", "crimson-text"),
            variable("EB Garamond", FontCategory.SERIF, "serif", "eb-garamond", "400 800"),
            variable("Source Serif 4", FontCategory.SERIF, "serif", "source-serif-4", "200 900"),
            static("Atkinson Hyperlegible", FontCategory.READING, "sans-serif", "atkinson-hyperlegible"),
            variable("Literata", FontCategory.READING, "serif", "literata", "200 900"),
            static("Charter", FontCategory.READING, "serif", "charter"),
        )

    /**
     * The font for a stored id, or null for [PUBLISHER]. Ids from older builds still resolve: "Serif" and
     * unknown names fall back to Literata (the default), "Sans"/"Inter" to Roboto (Android's sans-serif).
     */
    fun resolve(id: String): ReaderFont? =
        when {
            id.equals(PUBLISHER, ignoreCase = true) -> null
            id.equals("sans", ignoreCase = true) || id.equals("inter", ignoreCase = true) -> byId("Roboto")
            else -> all.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: byId("Literata")
        }

    private fun byId(id: String) = all.first { it.id == id }

    private fun variable(
        id: String,
        category: FontCategory,
        generic: String,
        file: String,
        weights: String,
    ) = ReaderFont(id, category, generic, listOf(FontFace("$file.ttf", weights), FontFace("$file-italic.ttf", weights, italic = true)))

    private fun static(
        id: String,
        category: FontCategory,
        generic: String,
        file: String,
    ) = ReaderFont(
        id,
        category,
        generic,
        listOf(
            FontFace("$file-regular.ttf", "400"),
            FontFace("$file-italic.ttf", "400", italic = true),
            FontFace("$file-bold.ttf", "700"),
            FontFace("$file-bold-italic.ttf", "700", italic = true),
        ),
    )
}
