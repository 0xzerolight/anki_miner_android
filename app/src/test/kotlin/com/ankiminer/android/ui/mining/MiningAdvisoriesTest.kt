package com.ankiminer.android.ui.mining

import com.ankiminer.android.data.resources.InstalledAudioPack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiningAdvisoriesTest {
    private val usable = InstalledAudioPack("nhk16", "nhk16", "nhk16", entryCount = 100, contentAvailable = true)
    private val broken = InstalledAudioPack("broken", "broken", "ajt", entryCount = 0, contentAvailable = false)

    @Test
    fun unmappedSentenceAudioWarnsOnEveryLane() {
        assertEquals(
            SentenceAudioAdvisory.UNMAPPED,
            miningFieldAdvisories(emptyMap(), emptyList(), audioLane = false).sentenceAudio,
        )
        assertEquals(
            SentenceAudioAdvisory.UNMAPPED,
            miningFieldAdvisories(emptyMap(), emptyList(), audioLane = true).sentenceAudio,
        )
        assertEquals(
            SentenceAudioAdvisory.NONE,
            miningFieldAdvisories(mapOf("audio" to "Audio"), emptyList(), audioLane = true).sentenceAudio,
        )
    }

    @Test
    fun anAudioRunWithPictureButNoAudioKeepsTheCoverArtWarning() {
        assertEquals(
            SentenceAudioAdvisory.UNMAPPED_COVER_ART_ONLY,
            miningFieldAdvisories(mapOf("picture" to "Picture"), emptyList(), audioLane = true).sentenceAudio,
        )
        assertEquals(
            SentenceAudioAdvisory.UNMAPPED,
            miningFieldAdvisories(mapOf("picture" to "Picture"), emptyList(), audioLane = false).sentenceAudio,
        )
    }

    @Test
    fun wordAudioWarnsOnlyWhenAUsablePackCouldFillIt() {
        assertTrue(miningFieldAdvisories(emptyMap(), listOf(usable), audioLane = false).wordAudioUnmapped)
        assertFalse(miningFieldAdvisories(emptyMap(), emptyList(), audioLane = false).wordAudioUnmapped)
        assertFalse(
            miningFieldAdvisories(
                mapOf("expression_audio" to "WordAudio"),
                listOf(usable),
                audioLane = false,
            ).wordAudioUnmapped,
        )
        val unusable = miningFieldAdvisories(emptyMap(), listOf(broken), audioLane = false)
        assertFalse(unusable.wordAudioUnmapped)
        assertTrue(unusable.unusableAudioPack)
    }
}
