package com.ankiminer.android.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.platform.app.InstrumentationRegistry
import com.ankiminer.android.R
import com.ankiminer.android.tts.DeviceVoiceStatus
import com.ankiminer.android.ui.theme.AnkiMinerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The Word audio card's device-voice section says what the probe found, per mining language. */
class DeviceVoiceSectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun theSectionReportsEachLanguagesVoiceAsTheProbeFindsIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val probed = mutableListOf<String>()
        var language by mutableStateOf("he")
        var voiceDownloaded = false
        val owner =
            object : LifecycleOwner {
                override val lifecycle = LifecycleRegistry(this)
            }
        composeRule.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                AnkiMinerTheme {
                    DeviceVoiceSection(language) { _, code ->
                        probed += code
                        if (code == "he" && !voiceDownloaded) {
                            DeviceVoiceStatus.MISSING_DATA
                        } else {
                            DeviceVoiceStatus.AVAILABLE
                        }
                    }
                }
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.settings_word_audio_device_voice_help)).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.settings_word_audio_voice_missing_data)).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.settings_open_speech_services)).assertExists()

        // The user downloads the voice from the speech services and comes back.
        composeRule.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.STARTED }
        voiceDownloaded = true
        composeRule.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(context.getString(R.string.settings_word_audio_voice_available)).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.settings_open_speech_services)).assertDoesNotExist()

        language = "id"
        composeRule.waitForIdle()
        composeRule.onNodeWithText(context.getString(R.string.settings_word_audio_voice_available)).assertExists()
        assertEquals(listOf("he", "he", "id"), probed)
    }
}
