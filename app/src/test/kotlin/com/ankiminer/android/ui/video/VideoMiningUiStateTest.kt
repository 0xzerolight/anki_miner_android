package com.ankiminer.android.ui.video

import com.ankiminer.android.media.SafDocument
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
