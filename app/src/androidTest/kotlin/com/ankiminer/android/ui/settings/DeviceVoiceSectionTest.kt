package com.ankiminer.android.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
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
        composeRule.setContent {
            AnkiMinerTheme {
                DeviceVoiceSection(language) { _, code ->
                    probed += code
                    if (code == "he") DeviceVoiceStatus.MISSING_DATA else DeviceVoiceStatus.AVAILABLE
                }
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.settings_word_audio_device_voice_help)).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.settings_word_audio_voice_missing_data)).assertExists()

        language = "id"
        composeRule.waitForIdle()
        composeRule.onNodeWithText(context.getString(R.string.settings_word_audio_voice_available)).assertExists()
        assertEquals(listOf("he", "id"), probed)
    }
}
