package com.c0mpile.grimmreader.feature.library

import com.c0mpile.grimmreader.core.model.Book
import com.c0mpile.grimmreader.core.model.BookSort
import com.c0mpile.grimmreader.core.model.BookSource
import com.c0mpile.grimmreader.core.model.LibraryFilter
import com.c0mpile.grimmreader.core.model.LibraryScope
import com.c0mpile.grimmreader.core.model.LocalLibrary
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

/**
 * The books of one on-device [library] under [filter]. A library linked to a server (and a server being linked)
 * has a server side and a device side; any other shows what is on the device. [bookFolders] are the watch folders
 * of the on-device libraries: a server book reading a file from one is on the device from the user's own copy,
 * not a download.
 *
 * A device file counts as the server's book when it has the same content (partial MD5) or, since the server
 * does not tell the hash of a file never downloaded, the same title and an author in common. Such a file is
 * not [LibraryFilter.DEVICE] and its server book is not [LibraryFilter.MISSING]; in [LibraryFilter.ALL] the
 * pair shows once, as the file on the device.
 */
internal fun List<Book>.inLibrary(
    library: LocalLibrary,
    filter: LibraryFilter,
    hasServer: Boolean,
    bookFolders: Collection<String>,
): List<Book> {
    val serverId = library.serverLibraryId
    if (!hasServer || serverId == null) return inScope(LibraryScope.Local(library.id))
    val server = filter { it.source == BookSource.SERVER && (it.libraryId == serverId || it.localLibraryId == library.id) }
    val device = filter { it.source != BookSource.SERVER && it.localLibraryId == library.id }
    val pairs = Pairs(server, device)
    return when (filter) {
        LibraryFilter.SERVER -> server
        LibraryFilter.DEVICE -> device.filter { it.id !in pairs.device }
        LibraryFilter.DOWNLOADED -> server.filter { it.isDownload(bookFolders) }
        LibraryFilter.MISSING -> server.filter { it.primaryFile?.isAvailableOffline != true && it.id !in pairs.server }
        LibraryFilter.ALL -> server.filter { it.id !in pairs.server } + device
    }
}

/** A server book whose file the app downloaded (app storage, or the download folder), not one read from a watch folder. */
private fun Book.isDownload(bookFolders: Collection<String>): Boolean {
    val uri = primaryFile?.localUri ?: return false
    return !uri.startsWith("content://") || bookFolders.none { uri.startsWith("$it/document/") }
}

/** Which server books have a copy among the device books and which device books are a server book (by id). */
private class Pairs(
    server: List<Book>,
    device: List<Book>,
) {
    val server = mutableSetOf<Long>()
    val device = mutableSetOf<Long>()

    init {
        val byTitle = server.groupBy { looseKey(it.title) }
        val byHash = server.filter { it.primaryFile?.partialMd5 != null }.groupBy { it.primaryFile?.partialMd5 }
        for (local in device) {
            val candidates = byHash[local.primaryFile?.partialMd5].orEmpty() + byTitle[looseKey(local.title)].orEmpty()
            for (remote in candidates.filter { isSameBook(it, local) }) {
                this.server += remote.id
                this.device += local.id
            }
        }
    }
}

private fun isSameBook(
    a: Book,
    b: Book,
): Boolean {
    val hashA = a.primaryFile?.partialMd5
    val hashB = b.primaryFile?.partialMd5
    if (hashA != null && hashB != null) return hashA == hashB
    val title = looseKey(a.title)
    if (title.isEmpty() || title != looseKey(b.title)) return false
    val authorsA = a.authors.map(::looseKey).filter { it.isNotEmpty() }
    val authorsB = b.authors.map(::looseKey).filter { it.isNotEmpty() }
    return authorsA.isEmpty() || authorsB.isEmpty() || authorsA.any { it in authorsB }
}

private fun looseKey(s: String): String = fold(s).filter { it.isLetterOrDigit() }

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
