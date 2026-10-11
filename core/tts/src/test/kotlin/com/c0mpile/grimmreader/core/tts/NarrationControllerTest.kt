package com.c0mpile.grimmreader.core.tts

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.c0mpile.grimmreader.core.datastore.AppPreferences
import com.c0mpile.grimmreader.core.datastore.SpeechProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

private class FakeSpeech : SpeechOutput {
    val shared = MutableSharedFlow<SpeechEvent>(extraBufferCapacity = 64)
    override val events: Flow<SpeechEvent> = shared.asSharedFlow()
    val queued = mutableListOf<Pair<String, String>>()
    var stops = 0

    override suspend fun enqueue(
        text: String,
        id: String,
    ) {
        queued += text to id
    }

    override fun stop() {
        stops++
    }

    override fun releaseLater() = Unit

    fun idOf(text: String) = queued.last { it.first == text }.second
}

private class FakeSource(
    private val book: List<NarrationChunk>,
) : NarrationSource {
    var cursor = 0
    var spokenNow: NarrationChunk? = null
    var cleared = false
    val begun = mutableListOf<Pair<String?, Boolean>>()
    val points = mutableListOf<NarrationPoint?>()

    override suspend fun begin(
        from: String?,
        after: Boolean,
        at: NarrationPoint?,
    ): Boolean {
        begun += from to after
        points += at
        if (from != null) cursor = book.indexOfFirst { it.locator == from } + if (after) 1 else 0
        return book.isNotEmpty()
    }

    override suspend fun next(count: Int): List<NarrationChunk> = book.drop(cursor).take(count).also { cursor += it.size }

    override fun spoken(chunk: NarrationChunk) {
        spokenNow = chunk
    }

    override fun clearSpoken() {
        cleared = true
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class NarrationControllerTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun passage(
        n: Int,
        section: Int = 0,
    ) = NarrationChunk("Passage $n.", "cfi$n", section)

    private class Rig(
        val scope: TestScope,
        val speech: FakeSpeech,
        val controller: NarrationController,
        val prefs: AppPreferences,
    )

    private fun TestScope.rig(): Rig {
        val prefs =
            AppPreferences(
                PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "app.preferences_pb") },
            )
        val speech = FakeSpeech()
        val controller =
            NarrationController(
                ApplicationProvider.getApplicationContext<Context>(),
                speech,
                prefs,
                CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
            )
        return Rig(this, speech, controller, prefs)
    }

    private suspend fun Rig.systemOn() {
        prefs.setSpeechProvider(SpeechProvider.SYSTEM)
        scope.runCurrent()
    }

    @Test fun queuesPassagesAheadSoTheVoiceNeverWaits() =
        runTest {
            val r = rig()
            r.systemOn()
            val source = FakeSource((1..30).map { passage(it) })
            r.controller.attach(this, source, NarrationMeta("Book"))
            r.controller.start()
            runCurrent()
            assertEquals(NarrationStatus.PLAYING, r.controller.state.value.status)
            // Several passages are with the engine before the first has started.
            assertTrue(r.speech.queued.size >= 8)
            assertEquals(
                "Passage 1.",
                r.speech.queued
                    .first()
                    .first,
            )
            r.controller.stop()
        }

    @Test fun aPointedWordStartsTheReadingThereEvenWhileReading() =
        runTest {
            val r = rig()
            r.systemOn()
            val source = FakeSource((1..30).map { passage(it) })
            r.controller.attach(this, source, NarrationMeta("Book"))
            r.controller.start()
            runCurrent()
            assertEquals(listOf<NarrationPoint?>(null), source.points)
            r.controller.start(NarrationPoint(12f, 34f))
            runCurrent()
            assertEquals(NarrationPoint(12f, 34f), source.points.last())
            assertEquals(NarrationStatus.PLAYING, r.controller.state.value.status)
            r.controller.stop()
        }

    @Test fun aRebuiltReaderOfTheSameBookKeepsTheNarrationGoing() =
        runTest {
            val r = rig()
            r.systemOn()
            r.controller.attach(7L, FakeSource((1..30).map { passage(it) }), NarrationMeta("Book"))
            r.controller.start()
            runCurrent()
            r.controller.detach(7L)
            r.controller.attach(7L, FakeSource((1..30).map { passage(it) }), NarrationMeta("Book"))
            runCurrent()
            assertEquals(NarrationStatus.PLAYING, r.controller.state.value.status)
            // Another book ends it.
            r.controller.attach(8L, FakeSource((1..30).map { passage(it) }), NarrationMeta("Other"))
            assertEquals(NarrationStatus.IDLE, r.controller.state.value.status)
        }

    @Test fun markingFollowsWhatIsSpokenAndFinishingTheBookStops() =
        runTest {
            val r = rig()
            r.systemOn()
            val source = FakeSource((1..3).map { passage(it) })
            r.controller.attach(this, source, NarrationMeta("Book"))
            r.controller.start()
            runCurrent()
            for (n in 1..3) {
                val id = r.speech.idOf("Passage $n.")
                r.speech.shared.emit(SpeechEvent.Started(id))
                runCurrent()
                assertEquals("cfi$n", source.spokenNow?.locator)
                r.speech.shared.emit(SpeechEvent.Done(id))
                runCurrent()
            }
            assertEquals(NarrationStatus.IDLE, r.controller.state.value.status)
            assertEquals("End of the book.", r.controller.state.value.message)
            assertTrue(source.cleared)
        }

    @Test fun pauseResumesFromTheSentenceThatWasInterrupted() =
        runTest {
            val r = rig()
            r.systemOn()
            r.controller.attach(this, FakeSource((1..6).map { passage(it) }), NarrationMeta("Book"))
            r.controller.start()
            runCurrent()
            val first = r.speech.idOf("Passage 1.")
            r.speech.shared.emit(SpeechEvent.Started(first))
            r.speech.shared.emit(SpeechEvent.Done(first))
            runCurrent()
            r.controller.pause()
            assertEquals(NarrationStatus.PAUSED, r.controller.state.value.status)
            r.speech.queued.clear()
            r.controller.resume()
            runCurrent()
            // Passage 1 was done; passage 2 was queued, not finished: it is spoken again first.
            assertEquals(
                "Passage 2.",
                r.speech.queued
                    .first()
                    .first,
            )
            r.controller.stop()
        }

    @Test fun eventsOfAFlushedQueueAreIgnored() =
        runTest {
            val r = rig()
            r.systemOn()
            r.controller.attach(this, FakeSource((1..6).map { passage(it) }), NarrationMeta("Book"))
            r.controller.start()
            runCurrent()
            val stale = r.speech.idOf("Passage 1.")
            r.controller.pause()
            r.controller.resume()
            runCurrent()
            r.speech.shared.emit(SpeechEvent.Done(stale))
            runCurrent()
            // The old queue's "done" must not drop passage 1 from the new one.
            r.speech.queued.clear()
            r.controller.pause()
            r.controller.resume()
            runCurrent()
            assertEquals(
                "Passage 1.",
                r.speech.queued
                    .first()
                    .first,
            )
            r.controller.stop()
        }

    @Test fun skippingMovesOverPassagesBothWays() =
        runTest {
            val r = rig()
            r.systemOn()
            val source = FakeSource((1..8).map { passage(it) })
            r.controller.attach(this, source, NarrationMeta("Book"))
            r.controller.start()
            runCurrent()
            r.speech.queued.clear()
            r.controller.skip(forward = true)
            runCurrent()
            assertEquals(
                "Passage 2.",
                r.speech.queued
                    .first()
                    .first,
            )
            assertEquals("cfi2", source.spokenNow?.locator)
            r.speech.queued.clear()
            r.controller.skip(forward = false)
            runCurrent()
            assertEquals(
                "Passage 1.",
                r.speech.queued
                    .first()
                    .first,
            )
            r.controller.stop()
        }

    @Test fun sleepTimerPausesWhenItRunsOut() =
        runTest {
            val r = rig()
            r.systemOn()
            r.controller.attach(this, FakeSource((1..6).map { passage(it) }), NarrationMeta("Book"))
            r.controller.start()
            runCurrent()
            r.controller.sleepTimer.setMinutes(1)
            runCurrent()
            assertNotNull(
                r.controller.state.value.sleep
                    ?.remainingMs,
            )
            r.controller.sleepTimer.cancel()
            assertNull(r.controller.state.value.sleep)
            r.controller.stop()
        }

    @Test fun endOfChapterTimerStopsBeforeTheNextSection() =
        runTest {
            val r = rig()
            r.systemOn()
            val book = listOf(passage(1, 0), passage(2, 0), passage(3, 1), passage(4, 1))
            r.controller.attach(this, FakeSource(book), NarrationMeta("Book"))
            r.controller.start()
            runCurrent()
            r.controller.sleepTimer.setEndOfChapter()
            for (n in 1..2) {
                val id = r.speech.idOf("Passage $n.")
                r.speech.shared.emit(SpeechEvent.Started(id))
                r.speech.shared.emit(SpeechEvent.Done(id))
                runCurrent()
            }
            assertEquals(NarrationStatus.PAUSED, r.controller.state.value.status)
            assertNull(r.controller.state.value.sleep)
            r.controller.stop()
        }

    @Test fun refusesWhenSystemSpeechIsOff() =
        runTest {
            val r = rig()
            r.prefs.setSpeechProvider(SpeechProvider.ELEVENLABS)
            runCurrent()
            r.controller.attach(this, FakeSource(listOf(passage(1))), NarrationMeta("Book"))
            r.controller.start()
            runCurrent()
            assertEquals(NarrationStatus.IDLE, r.controller.state.value.status)
            assertTrue(
                r.controller.state.value.message!!
                    .contains("System text-to-speech"),
            )
        }

    @Test fun aRebuiltPageContinuesAfterTheLastQueuedPassage() =
        runTest {
            val r = rig()
            r.systemOn()
            val book = (1..30).map { passage(it) }
            r.controller.attach(this, FakeSource(book), NarrationMeta("Book"))
            r.controller.start()
            runCurrent()
            r.controller.detach(this)
            val again = FakeSource(book)
            r.controller.attach(this, again, NarrationMeta("Book"))
            advanceTimeBy(1)
            runCurrent()
            val (from, after) = again.begun.single()
            assertTrue(after)
            assertNotNull(from)
            r.controller.stop()
        }
}
