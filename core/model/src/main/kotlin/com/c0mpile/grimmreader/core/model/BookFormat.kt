package com.c0mpile.grimmreader.core.model

/** Readable formats. Audiobooks are never opened by this app. */
enum class BookFormat(
    val extensions: Set<String>,
) {
    EPUB(setOf("epub")),
    MOBI(setOf("mobi", "azw", "azw3", "kf8")),
    FB2(setOf("fb2")),
    PDF(setOf("pdf")),
    CBZ(setOf("cbz", "zip")),
    ;

    val isComic get() = this == CBZ

    val isReflowable get() = this == EPUB || this == MOBI || this == FB2

    companion object {
        fun fromExtension(ext: String): BookFormat? = entries.firstOrNull { ext.lowercase() in it.extensions }
    }
}
