package com.c0mpile.grimmreader.core.model

/** A bookmark at an ebook position ([cfi]) or a 1-based [page] (comics and PDF). */
data class Bookmark(
    val id: Long,
    val cfi: String?,
    val page: Int?,
    val title: String,
    /** 0..100 when known (made on this device). */
    val percent: Float?,
    /** Epoch ms. */
    val createdAt: Long = 0,
)
