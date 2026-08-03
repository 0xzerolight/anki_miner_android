package com.ankiminer.android.ui.mining

import com.ankiminer.android.engine.SubtitleCue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimingPreviewStateTest {
    private val cues = listOf(SubtitleCue(1.0, 2.0, "cue"))

    @Test
    fun nudgeAccumulatesAndCancelsUnshiftedPreview() {
        val state = state().toggleUnshifted()

        val nudged =
            state
                .nudge(TimingPreviewState.NUDGE_SECONDS)
                .nudge(TimingPreviewState.NUDGE_SECONDS)
                .nudge(-TimingPreviewState.NUDGE_SECONDS)

        assertEquals(1.3, nudged.workingOffset, 0.000_001)
        assertFalse(nudged.previewingUnshifted)
    }

    @Test
    fun toggleFlipsPreviewOffsetAfterSeveralNudges() {
        val nudged =
            state()
                .nudge(TimingPreviewState.NUDGE_SECONDS)
                .nudge(TimingPreviewState.NUDGE_SECONDS)
                .nudge(TimingPreviewState.NUDGE_SECONDS)

        assertEquals(1.5, nudged.previewOffset, 0.000_001)

        val unshifted = nudged.toggleUnshifted()
        assertTrue(unshifted.previewingUnshifted)
        assertEquals(0.0, unshifted.previewOffset, 0.0)

        val shiftedAgain = unshifted.toggleUnshifted()
        assertFalse(shiftedAgain.previewingUnshifted)
        assertEquals(1.5, shiftedAgain.previewOffset, 0.000_001)
    }

    @Test
    fun setWorkingPreservesUnshiftedPreview() {
        val changed = state().toggleUnshifted().setWorking(-0.75)

        assertEquals(-0.75, changed.workingOffset, 0.0)
        assertTrue(changed.previewingUnshifted)
        assertEquals(0.0, changed.previewOffset, 0.0)
    }

    @Test
    fun previewOffsetUsesWorkingValueUnlessUnshiftedIsSelected() {
        val state = state().copy(workingOffset = -1.25)

        assertEquals(-1.25, state.previewOffset, 0.0)
        assertEquals(0.0, state.toggleUnshifted().previewOffset, 0.0)
    }

    private fun state(): TimingPreviewState =
        TimingPreviewState(
            initialOffset = 1.2,
            workingOffset = 1.2,
            previewingUnshifted = false,
            cues = cues,
            selectedCueIndex = null,
        )
}
