package com.ankiminer.android.ui.mining

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaMiningLabelsTest {
    @Test
    fun bothLanesCallTheTimedTextSubtitles() {
        assertEquals(MediaMiningLabels.VIDEO.transcriptLabel, MediaMiningLabels.AUDIO.transcriptLabel)
        assertEquals(MediaMiningLabels.VIDEO.subtitleOffsetLabel, MediaMiningLabels.AUDIO.subtitleOffsetLabel)
    }
}
