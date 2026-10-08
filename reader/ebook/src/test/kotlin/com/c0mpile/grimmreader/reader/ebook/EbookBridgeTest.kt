package com.c0mpile.grimmreader.reader.ebook

import com.c0mpile.grimmreader.core.model.ReaderPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EbookBridgeTest {
    @Test fun relocateCarriesCfiAndPercent() {
        val e = parse("""{"t":"relocate","cfi":"epubcfi(/6/16!/4,/88,/104/1:114)","fraction":0.0918,"href":"OEBPS/c.xhtml","toc":"5"}""")
        e as EbookEvent.Relocated
        assertEquals("epubcfi(/6/16!/4,/88,/104/1:114)", e.locator.cfi)
        assertEquals(9.18f, e.locator.percent, 0.001f)
        assertTrue(e.hasPosition)
    }

    @Test fun coverPageHasNoPosition() {
        val e = parse("""{"t":"relocate","cfi":"epubcfi(/6/2!/4)","fraction":null}""") as EbookEvent.Relocated
        assertFalse(e.hasPosition)
    }

    @Test fun readyAndGarbage() {
        assertEquals(
            listOf(TocEntry("One", "a.xhtml"), TocEntry("1.1", "a.xhtml#s", depth = 1)),
            (
                parse(
                    """{"t":"ready","toc":[{"label":"One","href":"a.xhtml"},{"label":" 1.1 ","href":"a.xhtml#s","depth":1}]}""",
                ) as EbookEvent.Ready
            ).toc,
        )
        assertNull(parse("not json"))
        assertNull(parse("""{"t":"relocate"}"""))
    }

    @Test fun cssFollowsPrefs() {
        val css =
            EbookCss.build(
                ReaderPrefs(fontSize = 22, justify = false, fontFamily = "Sans"),
                PageColors("#000000", "#FFFFFF", "#FF8904"),
            )
        assertTrue(css.contains("font-size: 22px"))
        assertTrue(css.contains("text-align: start"))
        assertTrue(css.contains("sans-serif"))
    }

    @Test fun cspBlocksForeignAndInlineScripts() {
        val directives = READER_CSP.split(';').map { it.trim() }.associate { it.substringBefore(' ') to it.substringAfter(' ') }
        assertEquals("'self'", directives["script-src"])
        assertEquals("'none'", directives["object-src"])
        assertFalse(READER_CSP.contains("unsafe-eval"))
        val html = java.io.File("src/main/assets/reader/reader.html").readText()
        assertFalse("inline scripts would be blocked by our own CSP", Regex("<script(?![^>]*\\bsrc=)[^>]*>").containsMatchIn(html))
        assertTrue(html.contains(READER_CSP))
    }

    @Test fun onlyOwnBlobFramesAndTheReaderMayNavigate() {
        val origin = "https://appassets.androidplatform.net"
        assertTrue(isAllowedNavigation("blob:$origin/0b1c-section", mainFrame = false))
        assertTrue(isAllowedNavigation("about:blank", mainFrame = false))
        assertFalse(isAllowedNavigation("https://example.com/", mainFrame = false))
        assertFalse(isAllowedNavigation("blob:https://example.com/x", mainFrame = false))
        assertTrue(isAllowedNavigation("$origin/assets/reader/reader.html?name=x", mainFrame = true))
        assertFalse(isAllowedNavigation("blob:$origin/x", mainFrame = true))
        assertFalse(isAllowedNavigation("https://example.com/", mainFrame = true))
    }

    @Test fun bookmarkOnTheVisiblePageIsReported() {
        val moved = parse("""{"t":"relocate","cfi":"epubcfi(/6/4!/4,/2/1:0,/8/1:9)","fraction":0.1,"bookmark":"epubcfi(/6/4!/4/2/1:0)"}""")
        assertEquals("epubcfi(/6/4!/4/2/1:0)", (moved as EbookEvent.Relocated).bookmark)
        assertEquals(EbookEvent.BookmarkHere(null), parse("""{"t":"bookmark","cfi":null}"""))
    }

    @Test fun readyCarriesSectionStartsInsideTheBook() {
        val e = parse("""{"t":"ready","toc":[],"sections":[0,0.25,"x",0.5,1.0000001]}""") as EbookEvent.Ready
        assertEquals(listOf(0.25f, 0.5f), e.sections)
    }

    @Test fun searchHitsProgressAndDone() {
        val hits =
            parse(
                """{"t":"search","id":3,"label":"Chapter 2","items":[{"cfi":"epubcfi(/6/4!/4/2,/1:3,/1:8)","pre":"a ","match":"word","post":" b"},{"pre":"no cfi"}]}""",
            ) as EbookEvent.Search
        assertEquals(3, hits.id)
        assertEquals("Chapter 2", hits.label)
        assertEquals(listOf(SearchHit("epubcfi(/6/4!/4/2,/1:3,/1:8)", "a ", "word", " b")), hits.hits)
        assertFalse(hits.done)
        assertEquals(0.5f, (parse("""{"t":"search","id":3,"progress":0.5}""") as EbookEvent.Search).progress)
        assertTrue((parse("""{"t":"search","id":3,"done":true}""") as EbookEvent.Search).done)
        assertNull(parse("""{"t":"search","items":[]}"""))
    }

    @Test fun lookupCarriesTheWordOnly() {
        assertEquals(EbookEvent.Lookup("serendipity"), parse("""{"t":"lookup","word":" serendipity "}"""))
        assertNull(parse("""{"t":"lookup","word":""}"""))
        assertNull(parse("""{"t":"lookup"}"""))
        assertNull(parse("""{"t":"lookup","word":"${"x".repeat(65)}"}"""))
    }
}
