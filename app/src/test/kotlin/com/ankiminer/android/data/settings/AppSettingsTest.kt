package com.ankiminer.android.data.settings

import com.ankiminer.android.anki.provider.AnkiFieldKeys
import com.ankiminer.android.anki.provider.AnkiMinerNoteModel
import com.ankiminer.android.engine.BridgeJsonValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsTest {
    @Test
    fun freshSettingsEnableAllBundledNameWordsets() {
        assertEquals(
            listOf("surnames", "given-names", "place-names", "org-product"),
            AppSettings().enabledWordsets,
        )
    }

    @Test
    fun themeDefaultsToDarkAndWireCodecRoundTrips() {
        assertEquals(ThemeMode.DARK, AppSettings().theme)
        assertFalse(AppSettings().setupWizardSeen)
        ThemeMode.entries.forEach { mode ->
            assertEquals(mode, ThemeMode.fromWire(mode.wireValue))
        }
        assertEquals(ThemeMode.DARK, ThemeMode.fromWire(null))
        assertEquals(ThemeMode.DARK, ThemeMode.fromWire("solarized"))
        assertEquals(ThemeMode.DARK, ThemeMode.fromWire(""))
    }

    @Test
    fun unconfiguredAnkiTargetFailsClosedWithAndroidConstraintsAlwaysExplicit() {
        val snapshot = EngineSettingsSnapshotMapper.map(AppSettings(), emptyList())

        assertEquals(
            setOf(
                "anki_deck_name",
                "excluded_decks",
                "anki_note_type",
                "anki_fields",
                "card_type_marker_fields",
                "card_type",
                "dictionary_chain",
                "frequency_chain",
                "expression_audio_chain",
                "excluded_wordsets",
                "screenshot_animated",
            ),
            snapshot.settings.keys,
        )
        assertEquals(BridgeJsonValue.ArrayValue(emptyList()), snapshot.settings["dictionary_chain"])
        assertEquals(false, snapshot.androidTtsEnabled)
        assertEquals(
            BridgeJsonValue.Text(AnkiMinerNoteModel.DEFAULT_DECK_NAME),
            snapshot.settings["anki_deck_name"],
        )
        // No first-party fallback: an unset note type stays blank so mining can never silently
        // inject "Anki Miner" as the target.
        assertEquals(BridgeJsonValue.Text(""), snapshot.settings["anki_note_type"])
        assertEquals(BridgeJsonValue.Text(""), snapshot.settings["card_type"])
        assertEquals(
            BridgeJsonValue.ObjectValue(emptyMap()),
            snapshot.settings["card_type_marker_fields"],
        )
        val fields = snapshot.settings["anki_fields"] as BridgeJsonValue.ObjectValue
        assertEquals(18, AnkiFieldKeys.ALL.size)
        assertEquals(AnkiFieldKeys.ALL.toSet(), fields.values.keys)
        assertTrue(fields.values.values.all { it == BridgeJsonValue.Text("") })
        assertFalse(snapshot.settings.containsKey("max_parallel_workers"))
    }

    @Test
    fun userNoteTypeAndFieldMapPassThroughWithUnmappedKeysBlank() {
        val snapshot =
            EngineSettingsSnapshotMapper.map(
                AppSettings(
                    noteType = "Lapis",
                    fieldMap = mapOf("word" to "Expression", "sentence" to "Sentence"),
                ),
                emptyList(),
            )

        assertEquals(BridgeJsonValue.Text("Lapis"), snapshot.settings["anki_note_type"])
        val fields = snapshot.settings["anki_fields"] as BridgeJsonValue.ObjectValue
        assertEquals(AnkiFieldKeys.ALL.toSet(), fields.values.keys)
        assertEquals(BridgeJsonValue.Text("Expression"), fields.values["word"])
        assertEquals(BridgeJsonValue.Text("Sentence"), fields.values["sentence"])
        val unmapped = AnkiFieldKeys.ALL.filterNot { it == "word" || it == "sentence" }
        assertTrue(unmapped.all { fields.values[it] == BridgeJsonValue.Text("") })
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
    fun validationRejectsDuplicateNonEmptyFieldDestinations() {
        val error =
            assertThrows(InvalidAppSettingException::class.java) {
                AppSettingsValidator.validate(
                    AppSettings(
                        noteType = "Lapis",
                        fieldMap = mapOf("word" to "Sentence", "sentence" to "Sentence"),
                    ),
                )
            }

        assertTrue(error.message.orEmpty().contains("Sentence"))
        assertTrue(error.message.orEmpty().contains("word"))
        assertTrue(error.message.orEmpty().contains("sentence"))
        assertEquals(
            AppSettings(fieldMap = mapOf("word" to "", "sentence" to "")),
            AppSettingsValidator.validate(
                AppSettings(fieldMap = mapOf("word" to "", "sentence" to "")),
            ),
        )
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

    @Test
    fun snapshotFreezesReadingAndLocalResourceChoicesWithoutNetworkAudio() {
        val snapshot =
            EngineSettingsSnapshotMapper.map(
                rawSettings =
                    AppSettings(
                        readingMinimumOccurrence = 2,
                        maxFrequencyRank = 15_000,
                        pitchCategoryFormat = PitchCategoryFormat.ROMAJI,
                        dictionarySources =
                            listOf(
                                ResourceChainSelection("custom", enabled = false),
                                ResourceChainSelection("removed", enabled = true),
                            ),
                        frequencySources =
                            listOf(
                                ResourceChainSelection("freq-b", enabled = false),
                                ResourceChainSelection("removed", enabled = true),
                                ResourceChainSelection("freq-a", enabled = true),
                            ),
                        audioPacks = listOf(ResourceChainSelection("audio-a", enabled = false)),
                        enabledWordsets = listOf("given-names"),
                        readingTtsEnabled = true,
                    ),
                installedDictionaryIds = listOf("jitendex", "custom"),
                installedFrequencyIds = listOf("freq-a", "freq-b", "freq-new"),
                installedAudioPackIds = listOf("audio-a", "audio-new"),
                availableWordsetIds = listOf("given-names", "place-names"),
            )

        assertEquals(BridgeJsonValue.Integer(2), snapshot.settings["reading_min_occurrence"])
        assertEquals(BridgeJsonValue.Integer(15_000), snapshot.settings["max_frequency_rank"])
        assertEquals(BridgeJsonValue.Text("romaji"), snapshot.settings["pitch_category_format"])
        assertTrue(snapshot.androidTtsEnabled == true)

        val dictionaries =
            snapshot.settings.getValue("dictionary_chain") as BridgeJsonValue.ArrayValue
        assertEquals(
            listOf("custom", "jitendex"),
            dictionaries.values.map { value ->
                ((value as BridgeJsonValue.ObjectValue).values.getValue("dict_id") as BridgeJsonValue.Text).value
            },
        )
        assertEquals(
            listOf(false, true),
            dictionaries.values.map { value ->
                ((value as BridgeJsonValue.ObjectValue).values.getValue("enabled") as BridgeJsonValue.Bool).value
            },
        )

        val frequency = snapshot.settings.getValue("frequency_chain") as BridgeJsonValue.ArrayValue
        assertEquals(
            listOf("freq-b", "freq-a", "freq-new"),
            frequency.values.map { value ->
                ((value as BridgeJsonValue.ObjectValue).values.getValue("source_id") as BridgeJsonValue.Text).value
            },
        )
        assertEquals(
            listOf(false, true, true),
            frequency.values.map { value ->
                ((value as BridgeJsonValue.ObjectValue).values.getValue("enabled") as BridgeJsonValue.Bool).value
            },
        )

        val audio = snapshot.settings.getValue("expression_audio_chain") as BridgeJsonValue.ArrayValue
        assertEquals(
            listOf("audio-a", "audio-new"),
            audio.values.map { value ->
                ((value as BridgeJsonValue.ObjectValue).values.getValue("pack_id") as BridgeJsonValue.Text).value
            },
        )
        assertTrue(
            audio.values.all { value ->
                ((value as BridgeJsonValue.ObjectValue).values.getValue("kind") as BridgeJsonValue.Text).value == "pack"
            },
        )
        assertEquals(
            BridgeJsonValue.ArrayValue(listOf(BridgeJsonValue.Text("given-names"))),
            snapshot.settings["excluded_wordsets"],
        )
    }

    @Test
    fun snapshotEmitsSelectedDeckExclusionsAndEnabledWordsets() {
        val snapshot =
            EngineSettingsSnapshotMapper.map(
                rawSettings =
                    AppSettings(
                        excludedDecks = listOf("Japanese::Known", "Mining"),
                        enabledWordsets = listOf("surnames", "place-names"),
                    ),
                installedDictionaryIds = emptyList(),
                availableWordsetIds =
                    listOf("surnames", "given-names", "place-names", "org-product"),
            )

        assertEquals(
            BridgeJsonValue.ArrayValue(
                listOf(BridgeJsonValue.Text("Japanese::Known"), BridgeJsonValue.Text("Mining")),
            ),
            snapshot.settings["excluded_decks"],
        )
        assertEquals(
            BridgeJsonValue.ArrayValue(
                listOf(BridgeJsonValue.Text("surnames"), BridgeJsonValue.Text("place-names")),
            ),
            snapshot.settings["excluded_wordsets"],
        )
    }

    @Test
    fun resourceSettingsRejectDuplicateUnsafeAndNetworkChoices() {
        assertThrows(InvalidAppSettingException::class.java) {
            AppSettingsValidator.validate(
                AppSettings(
                    frequencySources =
                        listOf(
                            ResourceChainSelection("freq"),
                            ResourceChainSelection("freq", enabled = false),
                        ),
                ),
            )
        }
        assertThrows(InvalidAppSettingException::class.java) {
            AppSettingsValidator.validate(
                AppSettings(audioPacks = listOf(ResourceChainSelection("jpod101"))),
            )
        }
        assertThrows(InvalidAppSettingException::class.java) {
            AppSettingsValidator.validate(AppSettings(enabledWordsets = listOf("../escape")))
        }
        assertThrows(InvalidAppSettingException::class.java) {
            AppSettingsValidator.validate(AppSettings(excludedDecks = listOf("Known", "Known")))
        }
        assertThrows(InvalidAppSettingException::class.java) {
            AppSettingsValidator.validate(AppSettings(excludedDecks = listOf("x".repeat(1025))))
        }
    }

    @Test
    fun resourceSelectionPreferenceEncodingIsCanonicalBoundedAndStrict() {
        val choices =
            listOf(
                ResourceChainSelection("first", enabled = true),
                ResourceChainSelection("second-pack", enabled = false),
            )

        val encoded = ResourceSelectionPreferenceCodec.encode(choices)

        assertEquals("resource-selection-v1\n+first\n-second-pack\n", encoded)
        assertEquals(choices, ResourceSelectionPreferenceCodec.decode(encoded))
        assertEquals(emptyList<ResourceChainSelection>(), ResourceSelectionPreferenceCodec.decode(null))
        assertThrows(InvalidAppSettingException::class.java) {
            ResourceSelectionPreferenceCodec.decode("resource-selection-v1\n+duplicate\n-duplicate\n")
        }
        assertThrows(InvalidAppSettingException::class.java) {
            ResourceSelectionPreferenceCodec.decode("resource-selection-v1\n+../escape\n")
        }
    }
}
