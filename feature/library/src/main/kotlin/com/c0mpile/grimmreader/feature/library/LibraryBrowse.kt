package com.c0mpile.grimmreader.feature.library

import com.c0mpile.grimmreader.core.model.Book
import com.c0mpile.grimmreader.core.model.BookSort
import com.c0mpile.grimmreader.core.model.BookSource
import com.c0mpile.grimmreader.core.model.LibraryScope
import java.text.Collator
import java.util.Locale

/** Authors or series with their books, as shown in the Authors and Series views. */
data class BookGroup(
    val name: String,
    val books: List<Book>,
) {
    /** Cover of the first book that has one. */
    val coverUri: String? get() = books.firstNotNullOfOrNull { it.coverUri }
}

enum class GroupKind { AUTHOR, SERIES, COMIC_SERIES }

const val UNKNOWN_AUTHOR = "Unknown author"

private val collator: Collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.PRIMARY }

internal fun List<Book>.inScope(scope: LibraryScope): List<Book> =
    when (scope) {
        LibraryScope.All -> this
        LibraryScope.Unsorted -> filter { it.onDevice && it.localLibraryId == null }
        is LibraryScope.Local -> filter { it.onDevice && it.localLibraryId == scope.libraryId }
        is LibraryScope.Server -> filter { it.source == BookSource.SERVER && it.libraryId == scope.libraryId }
        LibraryScope.Unshelved -> filter { it.source == BookSource.SERVER && it.shelves.isEmpty() }
        is LibraryScope.Shelf -> filter { scope.shelfId in it.shelves }
        is LibraryScope.MagicShelf -> filter { scope.shelfId in it.magicShelves }
    }

/** Local books, and server books downloaded to this device. */
private val Book.onDevice: Boolean get() = source != BookSource.SERVER || primaryFile?.isAvailableOffline == true

/** Case- and accent-insensitive match on title, authors and series. */
internal fun List<Book>.matching(query: String): List<Book> {
    val q = query.trim()
    if (q.isEmpty()) return this
    return filter { b ->
        b.title.containsLoosely(q) || b.authors.any { it.containsLoosely(q) } || b.seriesName?.containsLoosely(q) == true
    }
}

internal fun List<Book>.sortedFor(sort: BookSort): List<Book> =
    when (sort) {
        BookSort.TITLE -> sortedWith(compareBy(collator) { it.title })
        BookSort.AUTHOR ->
            sortedWith(
                compareBy<Book> { it.authors.isEmpty() }
                    .thenBy(collator) { it.authors.firstOrNull().orEmpty() }
                    .then(seriesOrder)
                    .thenBy(collator) { it.title },
            )
        BookSort.ADDED -> sortedByDescending { it.addedAt }
        BookSort.RECENT -> sortedWith(compareByDescending<Book> { it.lastReadAt ?: 0L }.thenBy(collator) { it.title })
    }

/** Series name, then number, so a series reads in order; books outside a series come after. */
private val seriesOrder: Comparator<Book> =
    compareBy<Book> { it.seriesName.isNullOrBlank() }
        .thenBy(collator) { it.seriesName.orEmpty() }
        .thenBy { it.seriesNumber ?: Float.MAX_VALUE }

/** One group per author (a book with two authors is in both), sorted by name as shown; books by series then title. */
internal fun groupByAuthor(books: List<Book>): List<BookGroup> {
    val byAuthor = linkedMapOf<String, MutableList<Book>>()
    for (book in books) {
        val names =
            book.authors
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .ifEmpty { listOf(UNKNOWN_AUTHOR) }
        names.distinct().forEach { byAuthor.getOrPut(it) { mutableListOf() } += book }
    }
    return byAuthor
        .map { (name, list) -> BookGroup(name, list.sortedWith(seriesOrder.thenBy(collator) { it.title })) }
        .sortedWith(compareBy<BookGroup> { it.name == UNKNOWN_AUTHOR }.thenBy(collator) { it.name })
}

/**
 * Series of the books that are not comics. Comics are the books of the libraries marked as comics
 * ([comicLibraries], on-device library ids) and, outside any library, CBZ files. With no comics library
 * marked nothing is split off, so every series shows here.
 */
internal fun groupBySeries(
    books: List<Book>,
    comicLibraries: Set<Long> = emptySet(),
): List<BookGroup> = groupSeries(if (comicLibraries.isEmpty()) books else books.filterNot { it.isComic(comicLibraries) })

/** Series of comics, kept apart from the book series; empty until a library is marked as comics. */
internal fun groupByComicSeries(
    books: List<Book>,
    comicLibraries: Set<Long>,
): List<BookGroup> = if (comicLibraries.isEmpty()) emptyList() else groupSeries(books.filter { it.isComic(comicLibraries) })

private fun Book.isComic(comicLibraries: Set<Long>): Boolean =
    if (localLibraryId != null) localLibraryId in comicLibraries else primaryFile?.format?.isComic == true

private fun groupSeries(books: List<Book>): List<BookGroup> =
    books
        .filter { !it.seriesName.isNullOrBlank() }
        .groupBy { it.seriesName!!.trim() }
        .map { (name, list) ->
            BookGroup(name, list.sortedWith(compareBy<Book> { it.seriesNumber ?: Float.MAX_VALUE }.thenBy(collator) { it.title }))
        }.sortedWith(compareBy(collator) { it.name })

internal fun List<BookGroup>.matchingName(query: String): List<BookGroup> {
    val q = query.trim()
    return if (q.isEmpty()) this else filter { it.name.containsLoosely(q) }
}

private fun String.containsLoosely(query: String): Boolean = fold(this).contains(fold(query))

private fun fold(s: String): String =
    java.text.Normalizer
        .normalize(s, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase(Locale.ROOT)
