package com.ankiminer.android.media

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ankiminer.android.mining.CurationSelection
import com.ankiminer.android.mining.MiningCancellationToken
import com.ankiminer.android.mining.MiningLane
import com.ankiminer.android.mining.MiningRepository
import com.ankiminer.android.mining.MiningRunState
import com.ankiminer.android.mining.VideoMiningInput
import com.ankiminer.android.reading.ReadingMiningInput
import com.ankiminer.android.reading.ReadingMiningRepository
import com.ankiminer.android.vm.MediaMiningViewModel
import com.ankiminer.android.vm.ReadingMiningViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SafSelectionProcessDeathInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Polls [condition] on the main thread until it holds or the budget runs out. */
    private fun awaitRestored(condition: () -> Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.uptimeMillis() + RESTORE_TIMEOUT_MILLIS
        while (SystemClock.uptimeMillis() < deadline) {
            var satisfied = false
            instrumentation.runOnMainSync { satisfied = condition() }
            if (satisfied) return
            Thread.sleep(25)
        }
        instrumentation.waitForIdleSync()
    }

    @Test
    fun freshViewModelsRestoreDurableSelectionsStartThenClearAllGrants() {
        val inventory =
            AndroidSafSelectionInventory(
                context = context,
                preferencesName = "saf-selection-process-death-test",
            ).also(::clearInventory)
        inventory.putSelection(
            SafSelectionSlot.VIDEO,
            SafSelectionRecord(VIDEO_URI, "episode.mkv"),
        )
        inventory.putSelection(
            SafSelectionSlot.VIDEO_SUBTITLE,
            SafSelectionRecord(SUBTITLE_URI, "episode.srt"),
        )
        inventory.putSelection(
            SafSelectionSlot.READING_SOURCE,
            SafSelectionRecord(READING_URI, "reading.srt"),
        )
        inventory.putText(SafSelectionSlot.READING_SUBTITLE_SERIES, "Restored series")
        val broker = RecordingSafBroker()
        val videoRepository = RecordingVideoRepository()
        val readingRepository = RecordingReadingRepository()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var video: MediaMiningViewModel
        lateinit var reading: ReadingMiningViewModel

        instrumentation.runOnMainSync {
            video =
                MediaMiningViewModel(
                    repository = videoRepository,
                    safBroker = broker,
                    lane = MiningLane.VIDEO,
                    savedStateHandle = SavedStateHandle(),
                    selectionInventory = inventory,
                )
            reading =
                ReadingMiningViewModel(
                    repository = readingRepository,
                    safBroker = broker,
                    savedStateHandle = SavedStateHandle(),
                    selectionInventory = inventory,
                )
        }
        instrumentation.waitForIdleSync()
        // Restoration publishes only after a durable write on the IO dispatcher, which
        // waitForIdleSync does not cover — it waits on the main looper alone.
        awaitRestored {
            video.uiState.value.video.document?.displayName != null &&
                video.uiState.value.subtitle.document?.displayName != null &&
                reading.uiState.value.source.document?.displayName != null
        }

        instrumentation.runOnMainSync {
            assertEquals("episode.mkv", video.uiState.value.video.document?.displayName)
            assertEquals("episode.srt", video.uiState.value.subtitle.document?.displayName)
            assertEquals("reading.srt", reading.uiState.value.source.document?.displayName)
            assertEquals("Restored series", reading.uiState.value.subtitleSeriesName)
            assertTrue(video.uiState.value.canStart)
            assertTrue(reading.uiState.value.canStart)
            assertEquals(
                setOf(VIDEO_URI, SUBTITLE_URI, READING_URI),
                broker.retainedUris.toSet(),
            )

            video.start()
            reading.start()
        }
        instrumentation.waitForIdleSync()

        instrumentation.runOnMainSync {
            assertEquals(1, videoRepository.started.size)
            assertEquals("Restored series", readingRepository.started.single().subtitleSeriesName)

            video.clearVideo()
            video.clearSubtitle()
            reading.clearSource()
        }
        instrumentation.waitForIdleSync()

        assertEquals(
            setOf(VIDEO_URI, SUBTITLE_URI, READING_URI),
            broker.eventualReleases.toSet(),
        )
        assertTrue(inventory.ownedUris().isEmpty())
        assertEquals(null, inventory.text(SafSelectionSlot.READING_SUBTITLE_SERIES))
        clearInventory(inventory)
    }

    private fun clearInventory(inventory: SafSelectionInventory) {
        SafSelectionSlot.entries.forEach { slot ->
            inventory.putSelection(slot, null)
            inventory.putText(slot, null)
        }
    }

    private class RecordingSafBroker : SafBroker {
        val retainedUris = mutableListOf<String>()
        val eventualReleases = mutableListOf<String>()

        override suspend fun retainReadAccess(uri: String): SafDocument {
            retainedUris += uri
            return SafDocument(
                uri = uri,
                displayName =
                    when (uri) {
                        VIDEO_URI -> "episode.mkv"
                        SUBTITLE_URI -> "episode.srt"
                        READING_URI -> "reading.srt"
                        else -> error("unexpected URI: $uri")
                    },
                mimeType = null,
                sizeBytes = null,
            )
        }

        override suspend fun releaseReadAccess(uri: String) {
            eventualReleases += uri
        }

        override fun releaseReadAccessEventually(uri: String) {
            eventualReleases += uri
        }
    }

    private class RecordingVideoRepository : MiningRepository {
        override val state: StateFlow<MiningRunState> = MutableStateFlow(MiningRunState.Idle)
        val started = mutableListOf<VideoMiningInput>()
        var confirmedKnownCandidateIds: List<String> = emptyList()

        override suspend fun startVideo(input: VideoMiningInput) {
            started += input
        }

        override suspend fun confirmCuration(
            runId: String,
            requestId: String,
            selection: List<CurationSelection>,
            pageIndex: Long?,
            knownCandidateIds: List<String>,
        ) {
            confirmedKnownCandidateIds = knownCandidateIds
        }

        override suspend fun cancel(runId: String) = Unit

        override suspend fun cancel(token: MiningCancellationToken) = Unit

        override suspend fun reset() = Unit
    }

    private class RecordingReadingRepository : ReadingMiningRepository {
        override val state: StateFlow<MiningRunState> = MutableStateFlow(MiningRunState.Idle)
        val started = mutableListOf<ReadingMiningInput>()
        var confirmedKnownCandidateIds: List<String> = emptyList()

        override suspend fun startReading(input: ReadingMiningInput) {
            started += input
        }

        override suspend fun confirmCuration(
            runId: String,
            requestId: String,
            selection: List<CurationSelection>,
            pageIndex: Long?,
            knownCandidateIds: List<String>,
        ) {
            confirmedKnownCandidateIds = knownCandidateIds
        }

        override suspend fun cancel(runId: String) = Unit

        override suspend fun cancel(token: MiningCancellationToken) = Unit

        override suspend fun reset() = Unit
    }

    private companion object {
        const val RESTORE_TIMEOUT_MILLIS = 10_000L
        const val VIDEO_URI = "content://provider/video"
        const val SUBTITLE_URI = "content://provider/subtitle"
        const val READING_URI = "content://provider/reading"
    }
}
