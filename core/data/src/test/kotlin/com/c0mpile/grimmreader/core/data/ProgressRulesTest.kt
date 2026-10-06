package com.c0mpile.grimmreader.core.data

import com.c0mpile.grimmreader.api.grimmory.EpubProgressDto
import com.c0mpile.grimmreader.api.grimmory.PageProgressDto
import com.c0mpile.grimmreader.api.grimmory.ProgressDto
import com.c0mpile.grimmreader.core.data.library.formatOf
import com.c0mpile.grimmreader.core.data.library.readStatusOf
import com.c0mpile.grimmreader.core.data.progress.LocatorCodec
import com.c0mpile.grimmreader.core.data.progress.ProgressRepository.Companion.decide
import com.c0mpile.grimmreader.core.data.progress.ProgressRepository.Companion.payload
import com.c0mpile.grimmreader.core.data.progress.ProgressRepository.Companion.toLocator
import com.c0mpile.grimmreader.core.database.entity.ReadingPositionEntity
import com.c0mpile.grimmreader.core.model.BookFormat
import com.c0mpile.grimmreader.core.model.Locator
import com.c0mpile.grimmreader.core.model.ReadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ProgressRulesTest {
    private val cfiA = "epubcfi(/6/16!/4,/88,/104/1:114)"
    private val cfiB = "epubcfi(/6/42!/4,/212/1:157,/268/1:68)"

    private fun local(
        locator: Locator,
        seenAt: Long?,
        dirty: Boolean,
    ) = ReadingPositionEntity(1, LocatorCodec.encode(locator), locator.percent, localUpdatedAt = 50, serverSeenAt = seenAt, dirty = dirty)

    @Test fun codecRoundTrip() {
        val epub = Locator.Epub(cfiA, "OEBPS/ch7.xhtml", 9.18f)
        assertEquals(epub, LocatorCodec.decode(LocatorCodec.encode(epub)))
        assertEquals(Locator.Page(42, 373), LocatorCodec.decode(LocatorCodec.encode(Locator.Page(42, 373))))
        assertNull(LocatorCodec.decode("garbage"))
    }

    @Test fun offerRemoteOnlyWhenItMovedAndDiffers() {
        val remote = Locator.Epub(cfiB, null, 44.787f)
        // Nothing local yet: take the server position.
        assertNotNull(decide(null, remote, 100))
        // Local changes not yet pushed win.
        assertNull(decide(local(Locator.Epub(cfiA, null, 9f), seenAt = 10, dirty = true), remote, 100))
        // Server did not move since we reconciled.
        assertNull(decide(local(Locator.Epub(cfiA, null, 9f), seenAt = 100, dirty = false), remote, 100))
        // Same CFI with a different percentage (other screen size) is not a conflict.
        assertNull(decide(local(Locator.Epub(cfiB, null, 44.3f), seenAt = 10, dirty = false), remote, 100))
        // Moved elsewhere on another device.
        assertNotNull(decide(local(Locator.Epub(cfiA, null, 9f), seenAt = 10, dirty = false), remote, 100))
    }

    @Test fun payloadMatchesTheWeb() {
        val epub = payload(Locator.Epub(cfiA, "OEBPS/ch7.xhtml", 9.18f), serverFileId = 501, format = BookFormat.EPUB)
        assertEquals(cfiA, epub.fileProgress?.positionData)
        assertEquals(cfiA, epub.epubProgress?.cfi)
        val comic = payload(Locator.Page(42, 373), serverFileId = 502, format = BookFormat.CBZ)
        assertEquals("42", comic.fileProgress?.positionData)
        assertEquals(PageProgressDto(42, 11.3f), comic.cbxProgress)
        assertNull(comic.pdfProgress)
        val pdf = payload(Locator.Page(3, 10), serverFileId = null, format = BookFormat.PDF)
        assertNull(pdf.fileProgress)
        assertEquals(3, pdf.pdfProgress?.page)
    }

    @Test fun serverProgressBecomesLocator() {
        assertEquals(
            Locator.Epub(cfiB, "x", 44.787f),
            ProgressDto(epubProgress = EpubProgressDto(cfiB, "x", 44.787f)).toLocator(BookFormat.EPUB),
        )
        assertEquals(Locator.Page(187, 373), ProgressDto(cbxProgress = PageProgressDto(187, 50.1f)).toLocator(BookFormat.CBZ))
    }

    @Test fun formatAndStatusMapping() {
        assertEquals(BookFormat.CBZ, formatOf("CBX", "x.cbz"))
        assertEquals(BookFormat.CBR, formatOf("CBX", "x.cbr"))
        assertEquals(BookFormat.MOBI, formatOf("AZW3", null))
        assertNull(formatOf("AUDIOBOOK", "x.m4b"))
        assertEquals(ReadStatus.READING, readStatusOf("RE_READING"))
        assertEquals(ReadStatus.UNREAD, readStatusOf(null))
    }
}
