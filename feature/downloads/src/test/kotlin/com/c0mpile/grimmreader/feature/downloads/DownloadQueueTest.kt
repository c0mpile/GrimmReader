package com.c0mpile.grimmreader.feature.downloads

import com.c0mpile.grimmreader.core.database.entity.DownloadEntity
import com.c0mpile.grimmreader.core.database.entity.DownloadState
import com.c0mpile.grimmreader.core.database.entity.DownloadWithBook
import com.c0mpile.grimmreader.core.model.BookFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadQueueTest {
    private fun row(
        fileId: Long,
        state: DownloadState,
        done: Long = 0,
        total: Long? = null,
        updatedAt: Long = 0,
        error: String? = null,
    ) = DownloadItem(
        DownloadWithBook(
            download =
                DownloadEntity(
                    id = fileId,
                    bookFileId = fileId,
                    state = state,
                    bytesDone = done,
                    bytesTotal = total,
                    error = error,
                    updatedAt = updatedAt,
                ),
            bookId = fileId,
            title = "ebook sample $fileId",
            serverRowId = null,
            serverBookId = null,
            coverUpdatedOn = null,
            format = BookFormat.EPUB,
        ),
    )

    @Test fun runningComeFirstThenWaitingAndFinishedNewestFirst() {
        val queue =
            DownloadQueue.of(
                listOf(
                    row(1, DownloadState.QUEUED),
                    row(2, DownloadState.DONE, updatedAt = 10),
                    row(3, DownloadState.RUNNING),
                    row(4, DownloadState.FAILED),
                    row(5, DownloadState.DONE, updatedAt = 20),
                    row(6, DownloadState.QUEUED),
                ),
            )
        assertEquals(listOf(3L, 1L, 6L), queue.active.map { it.bookId })
        assertEquals(listOf(4L), queue.failed.map { it.bookId })
        assertEquals(listOf(5L, 2L), queue.finished.map { it.bookId })
        assertTrue(DownloadQueue.of(emptyList()).isEmpty)
    }

    @Test fun statusShowsProgressWhenTheSizeIsKnown() {
        val mb = 1_048_576L
        assertEquals("EPUB · Downloading · 10.0 MB of 40.0 MB · 25 %", statusText(row(1, DownloadState.RUNNING, 10 * mb, 40 * mb)))
        assertEquals("EPUB · Downloading · 3.0 MB", statusText(row(1, DownloadState.RUNNING, 3 * mb)))
        assertEquals("EPUB · Waiting", statusText(row(1, DownloadState.QUEUED)))
        assertEquals("EPUB · Failed (HTTP 503)", statusText(row(1, DownloadState.FAILED, error = "HTTP 503")))
        assertEquals("EPUB · 40.0 MB", statusText(row(1, DownloadState.DONE, 40 * mb, 40 * mb)))
        assertEquals(0.25f, fraction(row(1, DownloadState.RUNNING, 10 * mb, 40 * mb)))
        assertNull(fraction(row(1, DownloadState.RUNNING, 3 * mb)))
    }
}
