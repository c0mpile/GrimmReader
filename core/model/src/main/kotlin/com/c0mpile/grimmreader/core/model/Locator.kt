package com.c0mpile.grimmreader.core.model

/**
 * A reading position. The same type is used with and without a server, so local and synced books
 * share one code path. Percentages are 0–100, as the Grimmory API uses them.
 */
sealed interface Locator {
    val percent: Float

    /** foliate range or point CFI, plus the section href it falls in. */
    data class Epub(
        val cfi: String,
        val href: String?,
        override val percent: Float,
        val contentSourcePercent: Float? = null,
    ) : Locator

    /** Comics and PDF: 1-based page number. */
    data class Page(
        val page: Int,
        val pageCount: Int,
    ) : Locator {
        /** Web rounding: one decimal place. */
        override val percent: Float get() = if (pageCount > 0) Math.round(page * PER_MILLE / pageCount) / TENTHS else 0f

        private companion object {
            const val PER_MILLE = 1000f
            const val TENTHS = 10f
        }
    }
}
