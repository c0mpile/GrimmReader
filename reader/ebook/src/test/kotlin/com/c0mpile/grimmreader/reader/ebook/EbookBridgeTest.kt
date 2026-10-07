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
            listOf(TocEntry("One", "a.xhtml")),
            (parse("""{"t":"ready","toc":[{"label":"One","href":"a.xhtml"}]}""") as EbookEvent.Ready).toc,
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
}
