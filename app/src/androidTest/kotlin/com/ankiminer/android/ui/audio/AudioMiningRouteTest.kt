package com.ankiminer.android.ui.audio

import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
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
 * Route-level regression: [AudioMiningRoute] wires its own six audio-track-picker params into the
 * shared [com.ankiminer.android.ui.video.VideoMiningScreen] separately from
 * [com.ankiminer.android.ui.video.VideoMiningRoute]. A previous regression left those params at
 * their screen defaults on this route, so the button rendered (enabled state is state-driven) but
 * tapping it was a silent no-op and the dialog never showed.
 */
class AudioMiningRouteTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun tappingAudioTracksInTheAudioRouteReachesTheViewModelAndOpensThePickerDialog() {
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

        composeRule
            .onNodeWithTag(VideoMiningTestTags.CONTENT)
            .performScrollToNode(hasTestTag(VideoMiningTestTags.AUDIO_TRACKS))
        composeRule.onNodeWithTag(VideoMiningTestTags.AUDIO_TRACKS).performClick()

        composeRule.onNodeWithTag(VideoMiningTestTags.AUDIO_TRACK_PICKER).assertIsDisplayed()
    }

    private fun factory(
        document: SafDocument,
        tracks: List<AudioTrackInfo>,
    ) = MediaMiningViewModel.Factory(
        repository = FakeMiningRepository(),
        safBroker = FakeSafBroker(document),
        lane = MiningLane.AUDIO,
        definitionLookup =
            DefinitionLookupService { _, _, _ ->
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
