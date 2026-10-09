package com.c0mpile.grimmreader.feature.library

import com.c0mpile.grimmreader.core.model.Book
import com.c0mpile.grimmreader.core.model.BookFile
import com.c0mpile.grimmreader.core.model.BookFormat
import com.c0mpile.grimmreader.core.model.BookSort
import com.c0mpile.grimmreader.core.model.BookSource
import com.c0mpile.grimmreader.core.model.LibraryScope
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryBrowseTest {
    private fun book(
        id: Long,
        title: String,
        authors: List<String> = emptyList(),
        series: String? = null,
        number: Float? = null,
        library: Long? = 1,
        localLibrary: Long? = null,
        source: BookSource = BookSource.SERVER,
        downloaded: Boolean = false,
        added: Long = 0,
        read: Long? = null,
        format: BookFormat = BookFormat.EPUB,
    ) = Book(
        id = id,
        source = source,
        title = title,
        authors = authors,
        seriesName = series,
        seriesNumber = number,
        libraryId = library,
        localLibraryId = localLibrary,
        addedAt = added,
        lastReadAt = read,
        files = listOf(BookFile(id, id, format, localUri = if (downloaded) "/f" else null)),
    )

    private val books =
        listOf(
            book(1, "Zeta", listOf("Émile Ash"), added = 3),
            book(2, "Alpha", listOf("Bea Cole", "Émile Ash"), series = "Saga", number = 2f, read = 50),
            book(3, "Beta", series = "Saga", number = 1f, library = 2),
            book(4, "Gamma", listOf("Bea Cole"), library = 2, localLibrary = 20, downloaded = true, added = 9),
            book(5, "Local", listOf("Dee"), library = null, localLibrary = 20, source = BookSource.LOCAL),
        )

    @Test fun scopes() {
        assertEquals(listOf(1L, 2L), books.inScope(LibraryScope.Server(1)).map { it.id })
        assertEquals(listOf(3L, 4L), books.inScope(LibraryScope.Server(2)).map { it.id })
        // A local library: its own books plus downloads of the server library it mirrors; not-downloaded server books stay out.
        assertEquals(listOf(4L, 5L), books.inScope(LibraryScope.Local(20)).map { it.id })
        val loose = book(6, "Loose", library = null, source = BookSource.LOCAL)
        assertEquals(listOf(6L), (books + loose).inScope(LibraryScope.Unsorted).map { it.id })
        assertEquals(5, books.inScope(LibraryScope.All).size)
    }

    @Test fun searchIgnoresCaseAndAccentsAndCoversAuthorsAndSeries() {
        assertEquals(listOf(1L, 2L), books.matching("emile").map { it.id })
        assertEquals(listOf(2L, 3L), books.matching("SAGA").map { it.id })
        assertEquals(listOf(4L), books.matching(" gam ").map { it.id })
        assertEquals(5, books.matching("").size)
    }

    @Test fun sorts() {
        assertEquals(listOf("Alpha", "Beta", "Gamma", "Local", "Zeta"), books.sortedFor(BookSort.TITLE).map { it.title })
        // By first author, a series in order, books without authors last.
        assertEquals(listOf("Alpha", "Gamma", "Local", "Zeta", "Beta"), books.sortedFor(BookSort.AUTHOR).map { it.title })
        assertEquals(listOf(4L, 1L), books.sortedFor(BookSort.ADDED).take(2).map { it.id })
        assertEquals(2L, books.sortedFor(BookSort.RECENT).first().id)
    }

    @Test fun authorsGroupEveryCoAuthorAndPutUnknownLast() {
        val groups = groupByAuthor(books)
        assertEquals(listOf("Bea Cole", "Dee", "Émile Ash", UNKNOWN_AUTHOR), groups.map { it.name })
        assertEquals(listOf("Alpha", "Gamma"), groups[0].books.map { it.title })
        assertEquals(listOf(1L, 2L), groups[2].books.map { it.id }.sorted())
        assertEquals(listOf(3L), groups[3].books.map { it.id })
    }

    @Test fun seriesAreInReadingOrder() {
        val groups = groupBySeries(books)
        assertEquals(listOf("Saga"), groups.map { it.name })
        assertEquals(listOf("Beta", "Alpha"), groups.single().books.map { it.title })
        assertEquals(listOf("Saga"), groups.matchingName("sag").map { it.name })
    }

    @Test fun comicSeriesFollowTheLibraryMediaType() {
        val comics =
            listOf(
                book(10, "Issue 2", series = "Saga", number = 2f, localLibrary = 30, format = BookFormat.CBZ),
                book(11, "Issue 1", series = "Saga", number = 1f, localLibrary = 30, format = BookFormat.CBZ),
                book(12, "Hero", series = "Heroes", localLibrary = null, format = BookFormat.CBZ),
                book(13, "Odd", series = "Odd", localLibrary = 20, format = BookFormat.CBZ),
            )
        val all = books + comics
        // No comics library marked: nothing is split off.
        assertEquals(listOf("Heroes", "Odd", "Saga"), groupBySeries(all).map { it.name })
        assertEquals(emptyList<String>(), groupByComicSeries(all, emptySet()).map { it.name })
        // Library 30 is comics: its books and loose CBZ files are comics; a CBZ in a books library stays a book.
        assertEquals(listOf("Odd", "Saga"), groupBySeries(all, setOf(30L)).map { it.name })
        assertEquals(
            listOf(2L, 3L),
            groupBySeries(all, setOf(30L))
                .last()
                .books
                .map { it.id }
                .sorted(),
        )
        val comicGroups = groupByComicSeries(all, setOf(30L))
        assertEquals(listOf("Heroes", "Saga"), comicGroups.map { it.name })
        assertEquals(listOf("Issue 1", "Issue 2"), comicGroups.last().books.map { it.title })
    }
}
