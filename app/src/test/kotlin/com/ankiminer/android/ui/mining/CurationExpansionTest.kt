package com.ankiminer.android.ui.mining

import com.ankiminer.android.engine.SubtitleCue
import com.ankiminer.android.mining.CurationSentence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CurationExpansionTest {
    private val cues =
        listOf(
            SubtitleCue(0.0, 2.0, "前の前"),
            SubtitleCue(3.0, 5.0, "前"),
            SubtitleCue(6.0, 8.0, "猫だ"),
            SubtitleCue(9.0, 11.0, "次"),
        )

    private fun sentence(
        text: String,
        startTime: Double,
        endTime: Double,
    ) = CurationSentence(
        sentenceId = "sentence_${"a".repeat(32)}",
        sentence = text,
        sentenceFurigana = "",
        sentenceReading = "",
        startTime = startTime,
        endTime = endTime,
        duration = endTime - startTime,
    )

    @Test
    fun zeroCountsReturnTheOwnCueWithBothDirectionsOpen() {
        val preview = expansionPreview(cues, sentence("猫だ", 6.0, 8.0), 0, 0, 0.3)!!

        assertEquals("猫だ", preview.sentence)
        assertEquals(6.0, preview.startTime, 0.0)
        assertEquals(8.0, preview.endTime, 0.0)
        assertEquals(2.0, preview.duration, 0.0)
        assertTrue(preview.canExpandPrev)
        assertTrue(preview.canExpandNext)
    }

    @Test
    fun countsMergeNeighboursAndAccumulate() {
        val one = expansionPreview(cues, sentence("猫だ", 6.0, 8.0), 1, 0, 0.3)!!
        assertEquals("前 猫だ", one.sentence)
        assertEquals(3.0, one.startTime, 0.0)
        assertEquals(8.0, one.endTime, 0.0)

        val both = expansionPreview(cues, sentence("猫だ", 6.0, 8.0), 2, 1, 0.3)!!
        assertEquals("前の前 前 猫だ 次", both.sentence)
        assertEquals(0.0, both.startTime, 0.0)
        assertEquals(11.0, both.endTime, 0.0)
        assertEquals(11.0, both.duration, 0.0)
    }

    @Test
    fun fileEdgesCloseTheirDirection() {
        val first = expansionPreview(cues, sentence("前の前", 0.0, 2.0), 0, 0, 0.3)!!
        assertFalse(first.canExpandPrev)
        assertTrue(first.canExpandNext)

        val last = expansionPreview(cues, sentence("次", 9.0, 11.0), 0, 0, 0.3)!!
        assertTrue(last.canExpandPrev)
        assertFalse(last.canExpandNext)
    }

    @Test
    fun countsBeyondTheEdgeClampWithoutError() {
        val preview = expansionPreview(cues, sentence("猫だ", 6.0, 8.0), 5, 0, 0.3)!!

        assertEquals("前の前 前 猫だ", preview.sentence)
        assertEquals(0.0, preview.startTime, 0.0)
        assertFalse(preview.canExpandPrev)
    }

    @Test
    fun clipCapClosesADirectionButExactBoundaryStaysOpen() {
        // Next cue ends far enough away that adding it busts the 30 s cap:
        // window would be 0.0..31.0 -> 31.0 + 0.6 > 30.
        val capped =
            listOf(
                SubtitleCue(0.0, 2.0, "猫だ"),
                SubtitleCue(30.0, 31.0, "次"),
            )
        val preview = expansionPreview(capped, sentence("猫だ", 0.0, 2.0), 0, 0, 0.3)!!
        assertFalse(preview.canExpandNext)
        assertFalse(preview.canExpandPrev)

        // Exact boundary: merged window 0.0..29.4 with padding 0.3 -> 29.4 + 0.6 == 30.0 stays open.
        val boundary =
            listOf(
                SubtitleCue(0.0, 2.0, "猫だ"),
                SubtitleCue(28.0, 29.4, "次"),
            )
        val boundaryPreview = expansionPreview(boundary, sentence("猫だ", 0.0, 2.0), 0, 0, 0.3)!!
        assertTrue(boundaryPreview.canExpandNext)
    }

    @Test
    fun duplicateCueTextResolvesToTheNearestStart() {
        val duplicated =
            listOf(
                SubtitleCue(0.0, 2.0, "同じ歌詞"),
                SubtitleCue(3.0, 5.0, "間"),
                SubtitleCue(100.0, 102.0, "同じ歌詞"),
            )
        val preview = expansionPreview(duplicated, sentence("同じ歌詞", 100.0, 102.0), 1, 0, 0.3)!!

        assertEquals("間 同じ歌詞", preview.sentence)
        assertEquals(3.0, preview.startTime, 0.0)
    }

    @Test
    fun noMatchWithinToleranceReturnsNull() {
        assertNull(expansionPreview(cues, sentence("存在しない", 50.0, 51.0), 1, 0, 0.3))
        assertNull(expansionPreview(cues, sentence("猫だ", 6.5, 8.0), 1, 0, 0.3))
        assertNull(expansionPreview(emptyList(), sentence("猫だ", 6.0, 8.0), 0, 0, 0.3))
    }
}
