package com.ankiminer.android.tts

import android.speech.tts.TextToSpeech
import com.ankiminer.android.data.settings.AppSettings
import com.ankiminer.android.data.settings.EngineSettingsSnapshotMapper
import com.ankiminer.android.data.settings.ResourceChainSelection
import com.ankiminer.android.engine.BridgeJsonValue
import com.ankiminer.android.engine.MiningConfigSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceVoiceWordAudioTest {
    @Test
    fun aRunUsesTheDeviceVoiceOnlyWhenWordAudioIsMappedAndTheVoiceIsEnabled() {
        assertTrue(snapshot(mapped = "WordAudio", voice = voiceEntry()).usesDeviceVoice())
        assertTrue(snapshot(mapped = "WordAudio", voice = voiceEntry(enabled = true)).usesDeviceVoice())
        assertFalse(snapshot(mapped = "", voice = voiceEntry()).usesDeviceVoice())
        assertFalse(snapshot(mapped = "WordAudio", voice = voiceEntry(enabled = false)).usesDeviceVoice())
        assertFalse(snapshot(mapped = "WordAudio", voice = null).usesDeviceVoice())
        assertFalse(MiningConfigSnapshot(emptyMap()).usesDeviceVoice())
    }

    @Test
    fun theMapperGivesEveryLanguageButJapaneseTheDeviceVoiceAfterItsPacks() {
        val hebrew =
            AppSettings(
                language = "he",
                fieldMap = mapOf("expression_audio" to "WordAudio"),
                audioPacks = listOf(ResourceChainSelection("forvo-he", enabled = true)),
            )
        val mapped = EngineSettingsSnapshotMapper.map(hebrew, emptyList(), installedAudioPackIds = listOf("forvo-he"))
        val kinds =
            (mapped.settings.getValue("expression_audio_chain") as BridgeJsonValue.ArrayValue).values.map {
                ((it as BridgeJsonValue.ObjectValue).values.getValue("kind") as BridgeJsonValue.Text).value
            }
        assertEquals(listOf("pack", "android_tts"), kinds)
        assertTrue(mapped.usesDeviceVoice())

        val japanese = EngineSettingsSnapshotMapper.map(hebrew.copy(language = "ja"), emptyList())
        assertFalse(japanese.usesDeviceVoice())
    }

    @Test
    fun theCardSaysAVoiceIsReadyOnlyWhenAnOfflineOneIsInstalled() {
        assertEquals(DeviceVoiceStatus.AVAILABLE, deviceVoiceStatus(TextToSpeech.LANG_AVAILABLE, hasOfflineVoice = true))
        assertEquals(
            DeviceVoiceStatus.AVAILABLE,
            deviceVoiceStatus(TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE, hasOfflineVoice = true),
        )
        assertEquals(
            DeviceVoiceStatus.MISSING_DATA,
            deviceVoiceStatus(TextToSpeech.LANG_COUNTRY_AVAILABLE, hasOfflineVoice = false),
        )
        assertEquals(DeviceVoiceStatus.MISSING_DATA, deviceVoiceStatus(TextToSpeech.LANG_MISSING_DATA, hasOfflineVoice = false))
        assertEquals(DeviceVoiceStatus.UNSUPPORTED, deviceVoiceStatus(TextToSpeech.LANG_NOT_SUPPORTED, hasOfflineVoice = false))
    }

    private fun voiceEntry(enabled: Boolean? = null): BridgeJsonValue =
        BridgeJsonValue.ObjectValue(
            buildMap {
                put("kind", BridgeJsonValue.Text("android_tts"))
                enabled?.let { put("enabled", BridgeJsonValue.Bool(it)) }
            },
        )

    private fun snapshot(
        mapped: String,
        voice: BridgeJsonValue?,
    ): MiningConfigSnapshot =
        MiningConfigSnapshot(
            mapOf(
                "language" to BridgeJsonValue.Text("he"),
                "anki_fields" to BridgeJsonValue.ObjectValue(mapOf("expression_audio" to BridgeJsonValue.Text(mapped))),
                "expression_audio_chain" to BridgeJsonValue.ArrayValue(listOfNotNull(voice)),
            ),
        )
}
