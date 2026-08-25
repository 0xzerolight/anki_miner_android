package com.ankiminer.android.ui.video

import com.ankiminer.android.media.SafDocument
import com.ankiminer.android.mining.MiningProgress
import com.ankiminer.android.mining.MiningRunState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoMiningUiStateTest {
    @Test
    fun timingPreviewIsDisabledWhileSubtitleReplacementResolves() {
        val stable = stateWithCommittedSources()

        assertTrue(stable.canTestTiming)
        assertFalse(
            stable.copy(
                subtitle = stable.subtitle.copy(isResolving = true),
            ).canTestTiming,
        )
    }

    @Test
    fun timingPreviewIsDisabledWhileVideoReplacementResolves() {
        val stable = stateWithCommittedSources()

        assertTrue(stable.canTestTiming)
        assertFalse(
            stable.copy(
                video = stable.video.copy(isResolving = true),
            ).canTestTiming,
        )
    }

    @Test
    fun canPickAudioTracksRequiresOnlyAnIdleUnresolvedVideoDocument() {
        assertTrue(stateWithVideoOnly().canPickAudioTracks)
    }

    @Test
    fun canPickAudioTracksIsFalseWhenRunIsNotIdle() {
        val base = stateWithVideoOnly()
        assertFalse(
            base.copy(
                runState = MiningRunState.Running("run", MiningProgress(0, 0, "x")),
            ).canPickAudioTracks,
        )
    }

    @Test
    fun canPickAudioTracksIsFalseWithoutAVideoDocument() {
        val base = stateWithVideoOnly()
        assertFalse(base.copy(video = DocumentSlotState()).canPickAudioTracks)
    }

    @Test
    fun canPickAudioTracksIsFalseWhileVideoIsResolving() {
        val base = stateWithVideoOnly()
        assertFalse(base.copy(video = base.video.copy(isResolving = true)).canPickAudioTracks)
    }

    @Test
    fun canPickAudioTracksIsFalseWhileStartIsPending() {
        val base = stateWithVideoOnly()
        assertFalse(base.copy(startPending = true).canPickAudioTracks)
    }

    @Test
    fun canPickAudioTracksIsFalseWhileResetIsPending() {
        val base = stateWithVideoOnly()
        assertFalse(base.copy(resetPending = true).canPickAudioTracks)
    }

    @Test
    fun canPickAudioTracksIsFalseWhileAudioTrackProbeIsPending() {
        val base = stateWithVideoOnly()
        assertFalse(base.copy(audioTrackProbePending = true).canPickAudioTracks)
    }

    @Test
    fun canPickAudioTracksIsFalseWhileTimingPreviewIsPending() {
        val base = stateWithVideoOnly()
        assertFalse(base.copy(timingPreviewPending = true).canPickAudioTracks)
    }

    @Test
    fun canStartAndCanTestTimingAreFalseWhileAudioTrackProbeIsPending() {
        val stable = stateWithCommittedSources()
        assertTrue(stable.canStart)
        assertTrue(stable.canTestTiming)

        val probing = stable.copy(audioTrackProbePending = true)
        assertFalse(probing.canStart)
        assertFalse(probing.canTestTiming)
    }

    private fun stateWithVideoOnly() =
        VideoMiningUiState(
            video = DocumentSlotState(document("content://test/video", "video.mkv")),
        )

    private fun stateWithCommittedSources() =
        VideoMiningUiState(
            video = DocumentSlotState(document("content://test/old-video", "old-video.mkv")),
            subtitle =
                DocumentSlotState(
                    document("content://test/old-subtitle", "old-subtitle.srt"),
                ),
        )

    private fun document(
        uri: String,
        displayName: String,
    ) = SafDocument(uri, displayName, mimeType = null, sizeBytes = null)
}
