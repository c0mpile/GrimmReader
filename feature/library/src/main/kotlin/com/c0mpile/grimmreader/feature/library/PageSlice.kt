package com.c0mpile.grimmreader.feature.library

/** One page of a longer list; [page] is zero-based and already clamped to the last page. */
internal data class PageSlice<T>(
    val items: List<T>,
    val page: Int,
    val pageCount: Int,
    val total: Int,
)

/** The [page]th run of [size] items; a page past the end (the list shrank) shows the last one. */
internal fun <T> List<T>.pageOf(
    page: Int,
    size: Int,
): PageSlice<T> {
    val pageCount = maxOf(1, (this.size + size - 1) / size)
    val at = page.coerceIn(0, pageCount - 1)
    return PageSlice(subList(minOf(at * size, this.size), minOf((at + 1) * size, this.size)), at, pageCount, this.size)
}
