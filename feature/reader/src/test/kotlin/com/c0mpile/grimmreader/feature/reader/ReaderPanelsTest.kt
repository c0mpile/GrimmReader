package com.c0mpile.grimmreader.feature.reader

import com.c0mpile.grimmreader.core.data.notebook.NotebookRepository
import com.c0mpile.grimmreader.reader.ebook.EbookEvent
import com.c0mpile.grimmreader.reader.ebook.SearchHit
import com.c0mpile.grimmreader.reader.ebook.TocEntry
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderPanelsTest {
    private val toc =
        listOf(
            TocEntry("Part one", "p1", 0),
            TocEntry("Chapter 1", "c1", 1),
            TocEntry("Scene", "s1", 2),
            TocEntry("Chapter 2", "c2", 1),
            TocEntry("Part two", "p2", 0),
        )

    @Test fun parentsFollowDepth() {
        assertArrayEquals(intArrayOf(-1, 0, 1, 0, -1), tocParents(toc))
    }

    @Test fun ancestorsOfANestedEntry() {
        val parents = tocParents(toc)
        assertEquals(setOf(1, 0), ancestors(parents, 2))
        assertEquals(emptySet<Int>(), ancestors(parents, 4))
        assertEquals(emptySet<Int>(), ancestors(parents, -1))
    }

    @Test fun searchKeepsOnlyTheLatestSearchsHits() {
        val panels = PanelData(TestScope(), mockk<NotebookRepository>())
        val old = panels.startSearch("old")
        val id = panels.startSearch("word")
        val hit = SearchHit("epubcfi(/6/2!/4/2/1:0)", "", "word", "")
        panels.onSearch(EbookEvent.Search(old, "Chapter 1", listOf(hit)))
        panels.onSearch(EbookEvent.Search(id, "Chapter 2", listOf(hit), progress = 0.5f))
        panels.onSearch(EbookEvent.Search(id, done = true))
        val search = panels.search.value
        assertEquals("word", search.query)
        assertEquals(listOf(SearchGroup("Chapter 2", listOf(hit))), search.groups)
        assertNull(search.progress)
        panels.clearSearch()
        assertEquals("", panels.search.value.query)
    }

    @Test fun localBooksHaveNoNotebook() {
        val panels = PanelData(TestScope(), mockk<NotebookRepository>())
        panels.loadAnnotations()
        assertEquals(Annotations.Unavailable(local = true), panels.annotations.value)
    }
}
