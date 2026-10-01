package com.ankiminer.android.tts

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The real Android TextToSpeech speaking a non-Japanese word offline, through the production
 * synthesizer the word-audio callback uses.
 *
 * Local only: hosted emulator images carry no offline voices, so the API 26 lane lists this test
 * as UNEXECUTED. Install an offline Hebrew voice in the emulator's speech settings to run it.
 */
class DeviceVoiceSynthesisInstrumentedTest {
    @Test
    fun anInstalledOfflineVoiceSpeaksAHebrewWord() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(
            "no offline Hebrew voice on this device",
            runBlocking { probeDeviceVoice(context, "he") } == DeviceVoiceStatus.AVAILABLE,
        )
        val synthesizer = AndroidSentenceAudioSynthesizerFactory(context).open()
        try {
            val spoken = synthesizer.synthesize("שלום", "he") { false }

            assertEquals(SentenceAudioOutcome.READY, spoken.outcome)
            assertTrue(requireNotNull(spoken.file).length() > 0)
        } finally {
            synthesizer.close()
        }
    }
}
