package com.ankiminer.android.data.settings

import com.ankiminer.android.anki.generated.AnkiLimitsV1
import com.ankiminer.android.anki.provider.AnkiFieldKeys
import com.ankiminer.android.anki.provider.AnkiMinerNoteModel
import com.ankiminer.android.engine.BridgeJsonValue
import com.ankiminer.android.ui.theme.ThemePalettes
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
    fun themeDefaultsToDarkAndWireCodecRoundTripsAllPersistedModes() {
        assertEquals(ThemeMode.DARK, AppSettings().theme)
        assertFalse(AppSettings().setupWizardSeen)
        ThemeMode.entries.forEach { mode ->
            assertEquals(mode, ThemeMode.fromWire(mode.wireValue))
        }
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromWire("system"))
        assertEquals(ThemeMode.DARK, ThemeMode.fromWire(null))
        assertEquals(ThemeMode.DARK, ThemeMode.fromWire("nonsense"))
    }

    @Test
    fun themePaletteDefaultsResolveFromTheGeneratedTable() {
        val settings = AppSettings()

        assertEquals("light", settings.lightThemeKey)
        assertEquals("dark", settings.darkThemeKey)
        assertFalse(settings.dynamicColorEnabled)
        assertTrue(settings.lightThemeKey in ThemePalettes.byKey)
        assertTrue(settings.darkThemeKey in ThemePalettes.byKey)
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
                "anki_tags",
                "dictionary_chain",
                "frequency_chain",
                "pitch_chain",
                "expression_audio_chain",
                "excluded_wordsets",
                "screenshot_animated",
                // Tags and Android's sentence-dedup default differ from nullable processing fields,
                // so both stay explicit while every other processing field remains absent.
                "deduplicate_sentences",
            ),
            snapshot.settings.keys,
        )
        assertEquals(BridgeJsonValue.Bool(false), snapshot.settings["deduplicate_sentences"])
        assertEquals(BridgeJsonValue.Text(EngineDefaults.TAGS), snapshot.settings["anki_tags"])
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
        // Every mode is named with a blank destination rather than omitted, so config_map's overlay
        // cannot reinstate the engine's JP Mining Note field names on a note type without them.
        val markers = snapshot.settings["card_type_marker_fields"] as BridgeJsonValue.ObjectValue
        assertEquals(
            CardType.entries.map { it.wireValue }.toSet(),
            markers.values.keys,
        )
        assertTrue(markers.values.values.all { it == BridgeJsonValue.Text("") })
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
        assertTrue(AppSettingsValidator.validate(AppSettings(tags = "")).tags.isEmpty())
    }

    @Test
    fun ankiNamesAcceptExactLimitsAndRejectTheNextCodePoint() {
        AppSettingsValidator.validate(
            AppSettings(
                deckName = "d".repeat(AnkiLimitsV1.Names.Deck.MAX_CODE_POINTS),
                noteType = "m".repeat(AnkiLimitsV1.Names.Model.MAX_CODE_POINTS),
                fieldMap =
                    mapOf(
                        "word" to "f".repeat(AnkiLimitsV1.Names.Field.MAX_CODE_POINTS),
                    ),
                cardTypeMarkerField =
                    "c".repeat(AnkiLimitsV1.Names.Field.MAX_CODE_POINTS),
            ),
        )

        listOf(
            AppSettings(
                deckName = "d".repeat(AnkiLimitsV1.Names.Deck.MAX_CODE_POINTS + 1),
            ),
            AppSettings(
                noteType = "m".repeat(AnkiLimitsV1.Names.Model.MAX_CODE_POINTS + 1),
            ),
            AppSettings(
                fieldMap =
                    mapOf(
                        "word" to "f".repeat(AnkiLimitsV1.Names.Field.MAX_CODE_POINTS + 1),
                    ),
            ),
            AppSettings(
                cardTypeMarkerField =
                    "c".repeat(AnkiLimitsV1.Names.Field.MAX_CODE_POINTS + 1),
            ),
        ).forEach { settings ->
            assertThrows(InvalidAppSettingException::class.java) {
                AppSettingsValidator.validate(settings)
            }
        }
    }

    @Test
    fun ankiNamesEnforceUtf8ByteLimitsIndependentlyOfCodePointCounts() {
        AppSettingsValidator.validate(
            AppSettings(
                deckName = "é".repeat(AnkiLimitsV1.Names.Deck.MAX_UTF8_BYTES / 2),
                noteType = "é".repeat(AnkiLimitsV1.Names.Model.MAX_UTF8_BYTES / 2),
                fieldMap =
                    mapOf(
                        "word" to "é".repeat(AnkiLimitsV1.Names.Field.MAX_UTF8_BYTES / 2),
                    ),
                cardTypeMarkerField =
                    "ø".repeat(AnkiLimitsV1.Names.Field.MAX_UTF8_BYTES / 2),
            ),
        )

        listOf(
            AppSettings(
                deckName = "é".repeat(AnkiLimitsV1.Names.Deck.MAX_UTF8_BYTES / 2 + 1),
            ),
            AppSettings(
                noteType = "é".repeat(AnkiLimitsV1.Names.Model.MAX_UTF8_BYTES / 2 + 1),
            ),
            AppSettings(
                fieldMap =
                    mapOf(
                        "word" to
                            "é".repeat(AnkiLimitsV1.Names.Field.MAX_UTF8_BYTES / 2 + 1),
                    ),
            ),
            AppSettings(
                cardTypeMarkerField =
                    "é".repeat(AnkiLimitsV1.Names.Field.MAX_UTF8_BYTES / 2 + 1),
            ),
        ).forEach { settings ->
            assertThrows(InvalidAppSettingException::class.java) {
                AppSettingsValidator.validate(settings)
            }
        }
    }

    @Test
    fun tagsMatchEngineSplitAndExactRequestLimits() {
        val maximumCount =
            List(AnkiLimitsV1.CreateNotes.MAX_TAG_COUNT_PER_NOTE) { index -> "tag$index" }
                .joinToString(" ")
        val maximumTag = "t".repeat(AnkiLimitsV1.CreateNotes.TAG_MAX_UTF8_BYTES)
        val maximumUtf8Tag = "é".repeat(AnkiLimitsV1.CreateNotes.TAG_MAX_UTF8_BYTES / 2)
        val maximumAggregate =
            List(
                AnkiLimitsV1.CreateNotes.TAGS_PER_NOTE_MAX_UTF8_BYTES /
                    AnkiLimitsV1.CreateNotes.TAG_MAX_UTF8_BYTES,
            ) { maximumTag }.joinToString(" ")

        listOf(
            maximumCount,
            maximumTag,
            maximumUtf8Tag,
            maximumAggregate,
            "one\u00a0two\u2007three\u202Ffour",
        )
            .forEach { tags -> AppSettingsValidator.validate(AppSettings(tags = tags)) }

        val tooMany =
            List(AnkiLimitsV1.CreateNotes.MAX_TAG_COUNT_PER_NOTE + 1) { index -> "tag$index" }
                .joinToString(" ")
        val oversizedTag = "t".repeat(AnkiLimitsV1.CreateNotes.TAG_MAX_UTF8_BYTES + 1)
        val oversizedUtf8Tag =
            "é".repeat(AnkiLimitsV1.CreateNotes.TAG_MAX_UTF8_BYTES / 2 + 1)
        val oversizedAggregate = "$maximumAggregate x"
        listOf(tooMany, oversizedTag, oversizedUtf8Tag, oversizedAggregate).forEach { tags ->
            assertThrows(InvalidAppSettingException::class.java) {
                AppSettingsValidator.validate(AppSettings(tags = tags))
            }
        }
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
        assertEquals(InvalidAppSettingCode.FIELD_MAP_CONFLICT, error.code)
        assertEquals(listOf("Sentence", "word, sentence"), error.arguments)
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
        assertTrue(AppSettingsDraftParser.isOptionalDouble("1,5", decimalSeparator = ','))
        assertEquals(
            1.5,
            AppSettingsDraftParser.optionalDouble("1,5", decimalSeparator = ',')!!,
            0.0,
        )
        // Persisted/draft values are rendered with Kotlin's invariant dot and remain editable on a
        // comma-decimal locale after a reload.
        assertTrue(AppSettingsDraftParser.isOptionalDouble("1.5", decimalSeparator = ','))
        assertFalse(AppSettingsDraftParser.isOptionalInt("1.5"))
        assertThrows(InvalidAppSettingException::class.java) {
            AppSettingsDraftParser.optionalDouble(".")
        }
        assertFalse(AppSettingsDraftParser.isOptionalDouble("1,5", decimalSeparator = '.'))
    }

    @Test
    fun defaultAndEmptyTagsAreAlwaysExplicit() {
        val defaultSnapshot = EngineSettingsSnapshotMapper.map(AppSettings(), emptyList())
        val noTagsSnapshot = EngineSettingsSnapshotMapper.map(AppSettings(tags = ""), emptyList())

        assertEquals(
            BridgeJsonValue.Text(EngineDefaults.TAGS),
            defaultSnapshot.settings["anki_tags"],
        )
        assertEquals(BridgeJsonValue.Text(""), noTagsSnapshot.settings["anki_tags"])
    }

    @Test
    fun cardTypeMarkerReachesOnlyTheActiveModeAndNeedsBothHalves() {
        val active =
            EngineSettingsSnapshotMapper.map(
                AppSettings(cardType = CardType.CLICK, cardTypeMarkerField = "IsClickCard"),
                emptyList(),
            )
        // A mode without a marker field would let config_map's overlay reinstate the engine's own
        // JP Mining Note names, so it is emitted as off.
        val halfConfigured =
            EngineSettingsSnapshotMapper.map(AppSettings(cardType = CardType.CLICK), emptyList())

        assertEquals(BridgeJsonValue.Text("click"), active.settings["card_type"])
        assertEquals(
            BridgeJsonValue.ObjectValue(
                mapOf(
                    "word_and_sentence" to BridgeJsonValue.Text(""),
                    "click" to BridgeJsonValue.Text("IsClickCard"),
                    "sentence" to BridgeJsonValue.Text(""),
                    "audio" to BridgeJsonValue.Text(""),
                ),
            ),
            active.settings["card_type_marker_fields"],
        )
        assertEquals(BridgeJsonValue.Text(""), halfConfigured.settings["card_type"])
        val markers =
            halfConfigured.settings["card_type_marker_fields"] as BridgeJsonValue.ObjectValue
        assertTrue(markers.values.values.all { it == BridgeJsonValue.Text("") })
    }

    @Test
    fun cardTypeMarkerCannotShareADestinationWithAMappedField() {
        assertThrows(InvalidAppSettingException::class.java) {
            AppSettingsValidator.validate(
                AppSettings(
                    fieldMap = mapOf("word" to "Expression", "sentence" to "IsClickCard"),
                    cardType = CardType.CLICK,
                    cardTypeMarkerField = "IsClickCard",
                ),
            )
        }
        AppSettingsValidator.validate(
            AppSettings(
                fieldMap = mapOf("word" to "Expression"),
                cardType = CardType.CLICK,
                cardTypeMarkerField = "IsClickCard",
            ),
        )
    }

    @Test
    fun wordListTogglesOnlyReachTheEngineWithAnInstalledFile() {
        val withFiles =
            EngineSettingsSnapshotMapper.map(
                AppSettings(useBlacklist = true, useWhitelist = true),
                emptyList(),
                blacklistPath = "/data/user/0/com.ankiminer.android/no_backup/w/blacklist.txt",
                whitelistPath = "/data/user/0/com.ankiminer.android/no_backup/w/whitelist.txt",
            )
        // The engine raises when an enabled list has no readable file, so a toggle without an
        // import must reach it switched off rather than pointing at nothing.
        val withoutFiles =
            EngineSettingsSnapshotMapper.map(
                AppSettings(useBlacklist = true, useWhitelist = true),
                emptyList(),
            )
        val untouched = EngineSettingsSnapshotMapper.map(AppSettings(), emptyList())

        assertEquals(BridgeJsonValue.Bool(true), withFiles.settings["use_blacklist"])
        assertEquals(
            BridgeJsonValue.Text("/data/user/0/com.ankiminer.android/no_backup/w/whitelist.txt"),
            withFiles.settings["whitelist_path"],
        )
        assertEquals(BridgeJsonValue.Bool(false), withoutFiles.settings["use_blacklist"])
        assertFalse(withoutFiles.settings.containsKey("blacklist_path"))
        assertFalse(untouched.settings.containsKey("use_whitelist"))
        assertFalse(untouched.settings.containsKey("whitelist_path"))
    }

    @Test
    fun subtitleRegexTrioIsEmittedOnlyWhenSet() {
        val snapshot =
            EngineSettingsSnapshotMapper.map(
                AppSettings(
                    subtitleRegexFilter = """\[[^\]]*\]""",
                    useSubtitleRegexFilter = true,
                ),
                emptyList(),
            )

        assertEquals(
            BridgeJsonValue.Text("""\[[^\]]*\]"""),
            snapshot.settings["subtitle_regex_filter"],
        )
        assertEquals(BridgeJsonValue.Bool(true), snapshot.settings["use_subtitle_regex_filter"])
        // An unset replacement inherits the engine's empty string, which already deletes the match.
        assertFalse(snapshot.settings.containsKey("subtitle_regex_replacement"))
    }

    @Test
    fun subtitleRegexValidationRejectsRunawayPatternsAndBadGroupReferences() {
        assertThrows(InvalidAppSettingException::class.java) {
            AppSettingsValidator.validate(AppSettings(subtitleRegexFilter = "(a+)+"))
        }
        assertThrows(InvalidAppSettingException::class.java) {
            AppSettingsValidator.validate(
                AppSettings(
                    subtitleRegexFilter = "a".repeat(SubtitleRegexCheck.MAX_PATTERN_CHARS + 1),
                ),
            )
        }
        assertThrows(InvalidAppSettingException::class.java) {
            AppSettingsValidator.validate(
                AppSettings(subtitleRegexFilter = "[0-9]", subtitleRegexReplacement = """\1"""),
            )
        }
        // Python-only syntax stays savable: the engine's own compiler is the authority.
        AppSettingsValidator.validate(AppSettings(subtitleRegexFilter = "(?P=name)"))
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
                        pitchSources =
                            listOf(
                                ResourceChainSelection("pitch-b", enabled = false),
                                ResourceChainSelection("removed", enabled = true),
                                ResourceChainSelection("pitch-a", enabled = true),
                            ),
                        audioPacks = listOf(ResourceChainSelection("audio-a", enabled = false)),
                        enabledWordsets = listOf("given-names"),
                        readingTtsEnabled = true,
                    ),
                installedDictionaryIds = listOf("jitendex", "custom"),
                installedFrequencyIds = listOf("freq-a", "freq-b", "freq-new"),
                installedPitchIds = listOf("pitch-a", "pitch-b", "pitch-new"),
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

        val pitch = snapshot.settings.getValue("pitch_chain") as BridgeJsonValue.ArrayValue
        assertEquals(
            listOf("pitch-b", "pitch-a", "pitch-new"),
            pitch.values.map { value ->
                ((value as BridgeJsonValue.ObjectValue).values.getValue("source_id") as BridgeJsonValue.Text).value
            },
        )
        assertEquals(
            listOf(false, true, true),
            pitch.values.map { value ->
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

    @Test
    fun animatedScreenshotsAreEmittedWithTheirTuningWhenEnabled() {
        val settings =
            EngineSettingsSnapshotMapper.map(
                AppSettings(
                    animatedScreenshotsEnabled = true,
                    animatedScreenshotDurationSeconds = 2.0,
                    animatedScreenshotQuality = 30,
                ),
                emptyList(),
            ).settings

        assertEquals(BridgeJsonValue.Bool(true), settings["screenshot_animated"])
        assertEquals(BridgeJsonValue.Decimal(2.0), settings["screenshot_animated_clip_duration"])
        assertEquals(BridgeJsonValue.Integer(30L), settings["screenshot_animated_quality"])
        assertEquals(BridgeJsonValue.Bool(false), settings["screenshot_animated_match_audio"])
    }

    @Test
    fun matchAudioReplacesTheClipLengthRatherThanRidingAlongsideIt() {
        // The engine computes the window from the subtitle plus the audio padding and never reads
        // the configured length, so emitting one would describe a run that does not happen.
        val settings =
            EngineSettingsSnapshotMapper.map(
                AppSettings(
                    animatedScreenshotsEnabled = true,
                    animatedScreenshotDurationSeconds = 2.0,
                    animatedScreenshotQuality = 30,
                    animatedScreenshotMatchAudio = true,
                ),
                emptyList(),
            ).settings

        assertEquals(BridgeJsonValue.Bool(true), settings["screenshot_animated_match_audio"])
        assertFalse("screenshot_animated_clip_duration" in settings)
        // Encoding quality is independent of the clip's time range.
        assertEquals(BridgeJsonValue.Integer(30L), settings["screenshot_animated_quality"])
    }

    @Test
    fun matchAudioIsNotEmittedWhileAnimatedScreenshotsAreOff() {
        val settings =
            EngineSettingsSnapshotMapper.map(
                AppSettings(
                    animatedScreenshotsEnabled = false,
                    animatedScreenshotMatchAudio = true,
                ),
                emptyList(),
            ).settings

        assertEquals(BridgeJsonValue.Bool(false), settings["screenshot_animated"])
        assertFalse("screenshot_animated_match_audio" in settings)
    }

    @Test
    fun matchAudioSuppressesAnOutOfRangeClipLengthInsteadOfRejectingTheWrite() {
        // The length field is disabled under match-audio, so a value left behind by an earlier edit
        // must not be able to block every settings write from a field the user cannot reach.
        val stale =
            AppSettings(
                animatedScreenshotsEnabled = true,
                animatedScreenshotDurationSeconds = 12.0,
                animatedScreenshotMatchAudio = true,
            )

        val settings = EngineSettingsSnapshotMapper.map(stale, emptyList()).settings

        assertEquals(BridgeJsonValue.Bool(true), settings["screenshot_animated_match_audio"])
        assertFalse("screenshot_animated_clip_duration" in settings)
    }

    @Test
    fun animatedScreenshotFormatFollowsDeviceMimeCapability() {
        fun formatFor(avifNameable: Boolean) =
            EngineSettingsSnapshotMapper.map(
                AppSettings(animatedScreenshotsEnabled = true),
                emptyList(),
                avifNameable = avifNameable,
            ).settings["screenshot_animated_format"]

        // API 26 cannot name a .avif file, so it must never be asked to store one.
        assertEquals(BridgeJsonValue.Text("webp"), formatFor(avifNameable = false))
        assertEquals(BridgeJsonValue.Text("avif"), formatFor(avifNameable = true))
    }

    @Test
    fun animatedScreenshotTuningIsOmittedWhenTheFeatureIsOff() {
        val settings = EngineSettingsSnapshotMapper.map(AppSettings(), emptyList()).settings

        assertEquals(BridgeJsonValue.Bool(false), settings["screenshot_animated"])
        assertFalse("screenshot_animated_format" in settings)
        assertFalse("screenshot_animated_clip_duration" in settings)
        assertFalse("screenshot_animated_quality" in settings)
    }

    @Test
    fun animatedScreenshotTuningOutsideTheSupportedRangeIsIgnoredWhileTheFeatureIsOff() {
        // The two fields are disabled when the toggle is off, so a value left behind by an earlier
        // edit would otherwise block every settings write with no way to reach the field and fix it.
        // Nothing is emitted while the feature is off either, so nothing invalid can reach the wire.
        val stale =
            AppSettings(
                animatedScreenshotsEnabled = false,
                animatedScreenshotDurationSeconds = 12.0,
                animatedScreenshotQuality = 101,
            )

        val settings = EngineSettingsSnapshotMapper.map(stale, emptyList()).settings

        assertEquals(BridgeJsonValue.Bool(false), settings["screenshot_animated"])
        assertFalse("screenshot_animated_clip_duration" in settings)
        assertFalse("screenshot_animated_quality" in settings)
    }

    @Test
    fun animatedScreenshotTuningOutsideTheSupportedRangeIsRejected() {
        listOf(
            AppSettings(animatedScreenshotsEnabled = true, animatedScreenshotDurationSeconds = 12.0),
            AppSettings(animatedScreenshotsEnabled = true, animatedScreenshotDurationSeconds = 0.1),
            AppSettings(animatedScreenshotsEnabled = true, animatedScreenshotQuality = 101),
            AppSettings(animatedScreenshotsEnabled = true, animatedScreenshotQuality = -1),
        ).forEach { candidate ->
            assertThrows(InvalidAppSettingException::class.java) {
                AppSettingsValidator.validate(candidate)
            }
        }
    }
}
