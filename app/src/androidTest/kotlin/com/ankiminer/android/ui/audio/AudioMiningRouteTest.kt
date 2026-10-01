package com.ankiminer.android.ui.audio

import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ankiminer.android.dictionary.DefinitionLookupService
import com.ankiminer.android.engine.AudioTrackInfo
import com.ankiminer.android.media.SafBroker
import com.ankiminer.android.media.SafDocument
import com.ankiminer.android.mining.FakeMiningRepository
import com.ankiminer.android.mining.MiningLane
import com.ankiminer.android.tracks.AudioTrackList
import com.ankiminer.android.tracks.AudioTrackProbeOpener
import com.ankiminer.android.ui.video.VideoMiningTestTags
import com.ankiminer.android.vm.MediaMiningViewModel
import org.junit.Rule
import org.junit.Test

/**
 * Route-level check: [AudioMiningRoute] shares [com.ankiminer.android.ui.video.VideoMiningScreen]
 * with the Video lane, but an audio file is a single stream, so the Audio tracks picker was a dead
 * end there. The Audio route must never offer it, even with a file chosen.
 */
class AudioMiningRouteTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun theAudioRouteNeverShowsAudioTracks() {
        val document =
            SafDocument(
                uri = "content://test/audio",
                displayName = "episode.mp3",
                mimeType = null,
                sizeBytes = null,
            )
        val tracks =
            listOf(
                track(audioIndex = 0, languageTag = "jpn"),
                track(audioIndex = 1, languageTag = "eng"),
            )
        lateinit var viewModel: MediaMiningViewModel

        composeRule.setContent {
            viewModel =
                viewModel(factory = remember { factory(document = document, tracks = tracks) })
            AudioMiningRoute(viewModel = viewModel)
        }
        composeRule.runOnIdle { viewModel.onVideoPicked(document.uri) }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(VideoMiningTestTags.PICK_VIDEO).assertExists()
        composeRule.onNodeWithTag(VideoMiningTestTags.AUDIO_TRACKS).assertDoesNotExist()
    }

    private fun factory(
        document: SafDocument,
        tracks: List<AudioTrackInfo>,
    ) = MediaMiningViewModel.Factory(
        repository = FakeMiningRepository(),
        safBroker = FakeSafBroker(document),
        lane = MiningLane.AUDIO,
        definitionLookup =
            DefinitionLookupService { _, _, _, _ ->
                Result.failure(UnsupportedOperationException("unused by route wiring test"))
            },
        audioTrackProbeOpener =
            AudioTrackProbeOpener { _ ->
                Result.success(AudioTrackList(autoAudioIndex = 0, tracks = tracks))
            },
        savedStateHandleFactory = { SavedStateHandle() },
    )

    private fun track(
        audioIndex: Long,
        languageTag: String?,
    ): AudioTrackInfo =
        AudioTrackInfo(
            audioIndex = audioIndex,
            globalIndex = audioIndex,
            languageTag = languageTag,
            title = null,
            codec = "aac",
            channels = 2,
            isDefault = false,
        )

    private class FakeSafBroker(private val document: SafDocument) : SafBroker {
        override suspend fun retainReadAccess(uri: String): SafDocument = document

        override suspend fun releaseReadAccess(uri: String) = Unit

        override fun releaseReadAccessEventually(uri: String) = Unit
    }
}
