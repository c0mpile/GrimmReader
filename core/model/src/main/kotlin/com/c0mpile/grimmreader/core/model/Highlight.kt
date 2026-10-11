package com.c0mpile.grimmreader.core.model

/** A highlighted passage of an ebook: [cfi] is the range, [color] a `#RRGGBB` string. */
data class Highlight(
    val id: Long,
    val cfi: String,
    val text: String,
    val color: String,
    val chapter: String?,
    val percent: Float?,
    /** Epoch ms. */
    val createdAt: Long = 0,
)

/** The colours offered for highlights; the first is the web's default. */
val HIGHLIGHT_COLORS: List<String> = listOf("#FACC15", "#4ADE80", "#38BDF8", "#F472B6", "#FB923C")
