package com.ankiminer.android.player

import com.ankiminer.android.engine.SubtitleCue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CueResolverTest {
    @Test
    fun `position inside cue returns cue`() {
        val cue = SubtitleCue(startSeconds = 1.0, endSeconds = 2.0, text = "first")

        assertEquals(cue, currentCue(listOf(cue), positionSeconds = 1.5, offsetSeconds = 0.0))
    }

    @Test
    fun `position in gap returns null`() {
        val cues =
            listOf(
                SubtitleCue(startSeconds = 1.0, endSeconds = 2.0, text = "first"),
                SubtitleCue(startSeconds = 3.0, endSeconds = 4.0, text = "second"),
            )

        assertNull(currentCue(cues, positionSeconds = 2.5, offsetSeconds = 0.0))
    }

    @Test
    fun `overlapping cues return first match`() {
        val first = SubtitleCue(startSeconds = 1.0, endSeconds = 3.0, text = "first")
        val second = SubtitleCue(startSeconds = 2.0, endSeconds = 4.0, text = "second")

        assertEquals(
            first,
            currentCue(listOf(first, second), positionSeconds = 2.5, offsetSeconds = 0.0),
        )
    }

    @Test
    fun `positive offset shifts cue window`() {
        val cue = SubtitleCue(startSeconds = 1.0, endSeconds = 2.0, text = "shifted")

        assertNull(currentCue(listOf(cue), positionSeconds = 1.5, offsetSeconds = 2.0))
        assertEquals(cue, currentCue(listOf(cue), positionSeconds = 3.5, offsetSeconds = 2.0))
    }

    @Test
    fun `negative offset clamps a partially negative window start to zero`() {
        val cue = SubtitleCue(startSeconds = 1.0, endSeconds = 2.0, text = "partial")

        assertEquals(0.0..0.5, shiftedWindow(cue, offsetSeconds = -1.5))
    }

    @Test
    fun `negative offset clamps a fully negative window to zero length`() {
        val cue = SubtitleCue(startSeconds = 1.0, endSeconds = 2.0, text = "before zero")

        assertEquals(0.0..0.0, shiftedWindow(cue, offsetSeconds = -3.0))
    }
}
