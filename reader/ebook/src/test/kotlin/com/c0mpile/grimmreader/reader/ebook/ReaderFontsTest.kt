package com.c0mpile.grimmreader.reader.ebook

import com.c0mpile.grimmreader.core.model.ReaderPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReaderFontsTest {
    private val colors = PageColors("#000000", "#FFFFFF", "#FF8904")
    private val assets = File("src/main/assets/fonts")

    @Test fun catalogAndBundledFilesMatch() {
        val referenced = ReaderFonts.all.flatMap { font -> font.faces.map { it.file } }.toSet()
        val bundled =
            assets
                .listFiles()
                .orEmpty()
                .map { it.name }
                .toSet()
        assertEquals(bundled, referenced)
        assertEquals(
            ReaderFonts.all.size,
            ReaderFonts.all
                .map { it.id.lowercase() }
                .toSet()
                .size,
        )
    }

    @Test fun storedIdsResolve() {
        assertNull(ReaderFonts.resolve("Publisher"))
        assertEquals("EB Garamond", ReaderFonts.resolve("eb garamond")?.id)
        assertEquals("Literata", ReaderFonts.resolve(ReaderPrefs().fontFamily)?.id)
        // Older builds stored generic choices.
        assertEquals("Literata", ReaderFonts.resolve("Serif")?.id)
        assertEquals("Roboto", ReaderFonts.resolve("Sans")?.id)
        assertEquals("Literata", ReaderFonts.resolve("Removed Font")?.id)
    }

    @Test fun fontsLoadFromTheAppOriginOnly() {
        val css = EbookCss.build(ReaderPrefs(fontFamily = "Charter"), colors)
        val urls = Regex("""url\("([^"]+)"\)""").findAll(css).map { it.groupValues[1] }.toList()
        assertEquals(4, urls.size)
        assertTrue(urls.all { it.startsWith("https://appassets.androidplatform.net/assets/fonts/charter-") })
        assertTrue(css.contains("font-family: \"grimm-charter\", serif !important"))
        assertTrue(css.contains("font-weight: 700; font-style: italic"))
    }

    @Test fun variableFontsDeclareTheirWeightRange() {
        val css = EbookCss.build(ReaderPrefs(fontFamily = "Source Sans 3"), colors)
        assertTrue(css.contains("source-sans-3-italic.ttf\"); font-weight: 200 900; font-style: italic"))
        assertTrue(css.contains("\"grimm-source-sans-3\", sans-serif"))
    }

    @Test fun publisherKeepsTheBookFonts() {
        val css = EbookCss.build(ReaderPrefs(fontFamily = "Publisher"), colors)
        assertFalse(css.contains("@font-face"))
        assertFalse(css.contains("font-family"))
    }
}
