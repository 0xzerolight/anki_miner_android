package com.ankiminer.android.data.settings

import com.ankiminer.android.engine.BridgeJsonValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsTest {
    @Test
    fun untouchedEngineFieldsRemainAbsentWhileAndroidConstraintsAreExplicit() {
        val snapshot = EngineSettingsSnapshotMapper.map(AppSettings(), emptyList())

        assertEquals(
            setOf("dictionary_chain", "expression_audio_chain", "screenshot_animated"),
            snapshot.settings.keys,
        )
        assertEquals(BridgeJsonValue.ArrayValue(emptyList()), snapshot.settings["dictionary_chain"])
        assertEquals(false, snapshot.androidTtsEnabled)
        assertFalse(snapshot.settings.containsKey("anki_deck_name"))
        assertFalse(snapshot.settings.containsKey("max_parallel_workers"))
    }

    @Test
    fun snapshotFreezesInstalledDictionariesAndOptInJishoInOrder() {
        val snapshot =
            EngineSettingsSnapshotMapper.map(
                AppSettings(deckName = "Japanese", jishoEnabled = true),
                listOf("jitendex", "custom-one"),
            )

        assertEquals(BridgeJsonValue.Text("Japanese"), snapshot.settings["anki_deck_name"])
        val chain = snapshot.settings.getValue("dictionary_chain") as BridgeJsonValue.ArrayValue
        assertEquals(3, chain.values.size)
        val first = chain.values[0] as BridgeJsonValue.ObjectValue
        val last = chain.values[2] as BridgeJsonValue.ObjectValue
        assertEquals(BridgeJsonValue.Text("jitendex"), first.values["dict_id"])
        assertEquals(BridgeJsonValue.Text("jisho"), last.values["kind"])
        assertEquals(BridgeJsonValue.Null, last.values["dict_id"])
        assertEquals(BridgeJsonValue.Decimal(1.0), snapshot.settings["jisho_delay"])
    }

    @Test
    fun validationRejectsNoncanonicalAnkiIdentityAndUnsafeWorkerCount() {
        assertThrows(InvalidAppSettingException::class.java) {
            AppSettingsValidator.validate(AppSettings(deckName = " Anki"))
        }
        assertThrows(InvalidAppSettingException::class.java) {
            AppSettingsValidator.validate(AppSettings(maxParallelWorkers = 33))
        }
        assertTrue(AppSettingsValidator.validate(AppSettings(tags = "")).tags!!.isEmpty())
    }

    @Test
    fun editableNumbersDistinguishBlankDefaultsFromIncompleteTokens() {
        assertEquals(null, AppSettingsDraftParser.optionalDouble(""))
        assertEquals(null, AppSettingsDraftParser.optionalInt(""))
        assertFalse(AppSettingsDraftParser.isOptionalDouble("."))
        assertFalse(AppSettingsDraftParser.isOptionalDouble("-"))
        assertFalse(AppSettingsDraftParser.isOptionalInt("1.5"))
        assertThrows(InvalidAppSettingException::class.java) {
            AppSettingsDraftParser.optionalDouble(".")
        }
    }

    @Test
    fun explicitEmptyTagsRemainDifferentFromDesktopDefault() {
        val defaultSnapshot = EngineSettingsSnapshotMapper.map(AppSettings(tags = null), emptyList())
        val noTagsSnapshot = EngineSettingsSnapshotMapper.map(AppSettings(tags = ""), emptyList())

        assertFalse(defaultSnapshot.settings.containsKey("anki_tags"))
        assertEquals(BridgeJsonValue.Text(""), noTagsSnapshot.settings["anki_tags"])
    }
}
