package com.c0mpile.grimmreader.reader.comic

import androidx.compose.ui.unit.IntSize
import com.c0mpile.grimmreader.core.model.ReadingDirection
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

class StreamingPageSourceTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun fetchesEachPageOncePrefetchesAheadAndRetriesFailures() =
        runBlocking {
            val fetched = CopyOnWriteArrayList<Int>()
            var failFirst = true
            val source =
                StreamingPageSource(listOf(1, 2, 3, 4, 5, 6), ReadingDirection.LTR) { page ->
                    fetched += page
                    if (page == 1 && failFirst) {
                        failFirst = false
                        throw IOException("offline")
                    }
                    File(tmp.root, "p$page").apply { writeText("not an image") }
                }
            val size = IntSize(100, 100)

            source.decode(0, size)
            source.decode(0, size)
            withTimeout(5_000) { while (fetched.toSet() != setOf(1, 2, 3, 4)) delay(10) }
            source.decode(1, size)
            withTimeout(5_000) { while (5 !in fetched) delay(10) }

            // Page 1 failed once and was fetched again; prefetched pages were never fetched twice.
            assertEquals(2, fetched.count { it == 1 })
            assertEquals(listOf(2, 3, 4, 5), fetched.filter { it != 1 }.sorted())
            source.close()
        }

    @Test fun outOfRangePagesAreNotFetched() =
        runBlocking {
            val fetched = CopyOnWriteArrayList<Int>()
            val source =
                StreamingPageSource(listOf(1), ReadingDirection.LTR) {
                    fetched += it
                    File(tmp.root, "p")
                }
            assertEquals(null, source.decode(5, IntSize(10, 10)))
            assertEquals(emptyList<Int>(), fetched)
            source.close()
        }
}
