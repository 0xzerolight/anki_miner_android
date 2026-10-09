package com.ankiminer.android.data.settings

import com.ankiminer.android.data.resources.InstalledDictionary
import com.ankiminer.android.data.resources.InstalledFrequencySource
import com.ankiminer.android.data.resources.InstalledPitchSource
import com.ankiminer.android.data.resources.ResourceManagerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsBackupCodecTest {
    private val populated =
        AppSettings(
            setupWizardSeen = true,
            theme = ThemeMode.LIGHT,
            deckName = "Mining",
            excludedDecks = listOf("Default"),
            noteType = "Lapis",
            fieldMap = mapOf("word" to "Word", "sentence" to "Sentence"),
            cardType = CardType.CLICK,
            cardTypeMarkerField = "IsClickCard",
            tags = "mined",
            audioPaddingSeconds = 0.25,
            screenshotOffsetSeconds = 0.5,
            subtitleOffsetSeconds = 0.1,
            audioFormat = AudioFormat.OPUS,
            audioBitrateKbps = 96,
            animatedScreenshotsEnabled = true,
            animatedScreenshotDurationSeconds = 2.0,
            animatedScreenshotQuality = 60,
            subtitleRegexFilter = "\\(.*?\\)",
            subtitleRegexReplacement = "",
            useSubtitleRegexFilter = true,
            useBlacklist = true,
            useWhitelist = false,
            useKnownWordsDatabase = true,
            excludeHiraganaOnly = true,
            excludeKatakanaOnly = false,
            boldTargetInSentence = true,
            deduplicateSentences = true,
            useIPlusOneFilter = true,
            maxSentenceDurationSeconds = 12.0,
            maxSentenceCharacters = 80,
            readingMinimumOccurrence = 2,
            maxFrequencyRank = 30000,
            minFrequencyRank = 500,
            frequencyKeepUnranked = true,
            knownWordsMatchKanaVariants = false,
            strictCardOrder = true,
            mergeIncompleteCues = true,
            secondarySubtitleEnabled = true,
            pitchCategoryFormat = PitchCategoryFormat.ROMAJI,
            maxParallelWorkers = 4,
            dictionarySources = listOf(ResourceChainSelection("jitendex", enabled = true)),
            frequencySources = listOf(ResourceChainSelection("bccwj", enabled = false)),
            pitchSources = listOf(ResourceChainSelection("kanjium", enabled = true)),
            audioPacks = listOf(ResourceChainSelection("nhk16", enabled = true)),
            enabledWordsets = listOf("surnames"),
            readingTtsEnabled = true,
            jishoEnabled = true,
        )

    @Test
    fun `every portable field survives a round trip`() {
        val applied =
            with(SettingsBackupCodec) {
                parse(SettingsBackupCodec.encode(populated, "0.4.1")).applyTo(AppSettings())
            }

        assertEquals(populated.copy(setupWizardSeen = false), applied.settings)
        assertEquals(emptyList<String>(), applied.rejectedKeys)
        assertEquals(emptyList<String>(), applied.ignoredKeys)
    }

    @Test
    fun `onboarding state never travels`() {
        val json = SettingsBackupCodec.encode(populated, "0.4.1")

        assertTrue("setup_wizard_seen" !in json)
        assertTrue("wordset_defaults_policy" !in json)
    }

    @Test
    fun `an absent key keeps the current value`() {
        val json =
            """{"ankiMinerAndroidSettings":1,"appVersion":"0.4.1","schemaVersion":2,""" +
                """"settings":{"theme_mode":"light"}}"""

        val applied = with(SettingsBackupCodec) { parse(json).applyTo(populated) }

        assertEquals(ThemeMode.LIGHT, applied.settings.theme)
        assertEquals("Mining", applied.settings.deckName)
        assertEquals("mined", applied.settings.tags)
        assertEquals(1, applied.appliedCount)
    }

    @Test
    fun `blank tags survive a portable backup round trip`() {
        val noTags = populated.copy(tags = "")

        val applied =
            with(SettingsBackupCodec) {
                parse(SettingsBackupCodec.encode(noTags, "0.4.1")).applyTo(AppSettings())
            }

        assertEquals("", applied.settings.tags)
    }

    @Test
    fun `current backup clears every nullable and collection field on a populated destination`() {
        val cleared = AppSettings(enabledWordsets = emptyList())

        val applied =
            with(SettingsBackupCodec) {
                parse(SettingsBackupCodec.encode(cleared, "0.5.0")).applyTo(populated)
            }

        assertEquals(cleared.copy(setupWizardSeen = true), applied.settings)
        assertEquals(SettingsBackupCodec.portableKeyNames.size, applied.appliedCount)
        assertEquals(emptyList<String>(), applied.rejectedKeys)
        assertEquals(emptyList<String>(), applied.ignoredKeys)
    }

    @Test
    fun `an unknown key is ignored, not fatal`() {
        val json =
            """{"ankiMinerAndroidSettings":1,"appVersion":"9.9.9","schemaVersion":99,""" +
                """"settings":{"theme_mode":"light","invented_future_key":true}}"""

        val applied = with(SettingsBackupCodec) { parse(json).applyTo(AppSettings()) }

        assertEquals(listOf("invented_future_key"), applied.ignoredKeys)
        assertEquals(ThemeMode.LIGHT, applied.settings.theme)
    }

    @Test
    fun `a value of the wrong JSON type is rejected and the current value survives`() {
        val json =
            """{"ankiMinerAndroidSettings":1,"appVersion":"0.4.1","schemaVersion":2,""" +
                """"settings":{"max_parallel_workers":"four"}}"""

        val applied = with(SettingsBackupCodec) { parse(json).applyTo(populated) }

        assertEquals(listOf("max_parallel_workers"), applied.rejectedKeys)
        assertEquals(4, applied.settings.maxParallelWorkers)
    }

    @Test
    fun `a value the validator refuses is quarantined, not thrown`() {
        val json =
            """{"ankiMinerAndroidSettings":1,"appVersion":"0.4.1","schemaVersion":2,""" +
                """"settings":{"max_parallel_workers":9999}}"""

        val applied = with(SettingsBackupCodec) { parse(json).applyTo(populated) }

        assertEquals(listOf("max_parallel_workers"), applied.rejectedKeys)
        assertEquals(4, applied.settings.maxParallelWorkers)
    }

    @Test
    fun `a file without the marker is not a backup`() {
        val failure =
            runCatching { SettingsBackupCodec.parse("""{"settings":{"theme_mode":"light"}}""") }
                .exceptionOrNull()

        assertEquals(
            SettingsBackupFailure.NOT_A_BACKUP,
            (failure as SettingsBackupException).reason,
        )
    }

    @Test
    fun `malformed JSON is reported as malformed`() {
        val failure = runCatching { SettingsBackupCodec.parse("{not json") }.exceptionOrNull()

        assertEquals(
            SettingsBackupFailure.MALFORMED,
            (failure as SettingsBackupException).reason,
        )
    }

    @Test
    fun `the codec key table matches the persisted key registry`() {
        val expected =
            DataStoreAppSettingsRepository.persistedPreferenceKeyNames -
                SettingsBackupCodec.NON_PORTABLE_KEY_NAMES

        assertEquals(expected, SettingsBackupCodec.portableKeyNames)
        assertTrue("use_sentence_length" !in SettingsBackupCodec.portableKeyNames)
    }

    @Test
    fun `a backup with resources is written as format 5`() {
        val json = SettingsBackupCodec.encode(populated, "0.7.0", ResourceManagerState())

        assertTrue(json, "\"ankiMinerAndroidSettings\" : 5" in json)
    }

    @Test
    fun `the mining language travels and the parked languages stay on this device`() {
        val hebrew =
            populated.copy(
                language = "he",
                fieldMap = mapOf("word" to "Word", "transliteration" to "Translit"),
                languageStash = mapOf("ja" to mapOf("deck_name" to "Japanese")),
            )
        val json = SettingsBackupCodec.encode(hebrew, "0.8.0", ResourceManagerState())

        val applied =
            with(SettingsBackupCodec) {
                parse(json).applyTo(AppSettings(), ResourceManagerState(), knownLanguages = setOf("ja", "he"))
            }

        assertTrue(json, "language_stash" !in json)
        assertEquals("he", applied.settings.language)
        assertEquals(hebrew.fieldMap, applied.settings.fieldMap)
        assertEquals(emptyMap<String, Map<String, Any?>>(), applied.settings.languageStash)
        assertEquals(null, applied.unknownLanguage)
    }

    @Test
    fun `an import never replaces this device's parked languages`() {
        val local = AppSettings(languageStash = mapOf("he" to mapOf("deck_name" to "Hebrew")))
        val json = SettingsBackupCodec.encode(populated, "0.8.0", ResourceManagerState())

        val applied = with(SettingsBackupCodec) { parse(json).applyTo(local, ResourceManagerState()) }

        assertEquals(local.languageStash, applied.settings.languageStash)
    }

    @Test
    fun `a language this build cannot mine keeps the current one and its settings`() {
        val json =
            """{"ankiMinerAndroidSettings":5,"appVersion":"9.9.9","schemaVersion":3,""" +
                """"settings":{"mining_language":"zh","deck_name":"Chinese","dictionary_sources_v1":null,""" +
                """"frequency_sources_v1":null,"pitch_sources_v1":null,"audio_packs_v1":null},""" +
                """"resourceChains":{"dictionary_sources_v1":[],"frequency_sources_v1":[],""" +
                """"pitch_sources_v1":[],"audio_packs_v1":[]}}"""
        val current = AppSettings(language = "he")

        val applied =
            with(SettingsBackupCodec) {
                parse(json).applyTo(current, ResourceManagerState(), knownLanguages = setOf("ja", "he"))
            }

        assertEquals("he", applied.settings.language)
        assertEquals("zh", applied.unknownLanguage)
        assertEquals(null, applied.settings.deckName)
        assertEquals(
            listOf(
                "mining_language",
                "deck_name",
                "dictionary_sources_v1",
                "frequency_sources_v1",
                "pitch_sources_v1",
                "audio_packs_v1",
            ),
            applied.rejectedKeys,
        )
    }

    @Test
    fun `a rejected language's card fields never land in the current language`() {
        val chinese =
            AppSettings(
                language = "zh",
                deckName = "Chinese",
                noteType = "Chinese Mining",
                fieldMap = mapOf("word" to "Hanzi", "expression_pinyin" to "Pinyin"),
                cardTypeMarkerField = "IsClick",
                theme = ThemeMode.DARK,
            )
        val hebrew =
            AppSettings(
                language = "he",
                deckName = "Hebrew",
                fieldMap = mapOf("word" to "Word", "transliteration" to "Translit"),
            )
        val json = SettingsBackupCodec.encode(chinese, "9.9.9", ResourceManagerState())

        val applied =
            with(SettingsBackupCodec) {
                parse(json).applyTo(hebrew, ResourceManagerState(), knownLanguages = setOf("ja", "he"))
            }

        assertEquals("he", applied.settings.language)
        assertEquals("Hebrew", applied.settings.deckName)
        assertEquals(hebrew.fieldMap, applied.settings.fieldMap)
        assertEquals(null, applied.settings.cardTypeMarkerField)
        assertEquals(ThemeMode.DARK, applied.settings.theme)
        assertEquals("zh", applied.unknownLanguage)
        assertEquals(
            setOf("mining_language") + LanguageScope.PREFERENCE_NAMES,
            applied.rejectedKeys.toSet(),
        )
    }

    @Test
    fun `a malformed language code is reported the same way`() {
        val json =
            """{"ankiMinerAndroidSettings":2,"appVersion":"9.9.9","schemaVersion":3,""" +
                """"settings":{"mining_language":"Klingon"}}"""

        val applied = with(SettingsBackupCodec) { parse(json).applyTo(AppSettings()) }

        assertEquals("ja", applied.settings.language)
        assertEquals("Klingon", applied.unknownLanguage)
    }

    @Test
    fun `a file from before languages keeps the current language`() {
        val json =
            """{"ankiMinerAndroidSettings":2,"appVersion":"0.5.0","schemaVersion":2,""" +
                """"settings":{"deck_name":"Mining"}}"""

        val applied = with(SettingsBackupCodec) { parse(json).applyTo(AppSettings(language = "he")) }

        assertEquals("he", applied.settings.language)
        assertEquals(null, applied.unknownLanguage)
    }

    @Test
    fun `a v3 backup with the length toggle absent imports its caps as zero`() {
        val applied = applyPinEraBackup(toggle = "null")

        assertEquals(0.0, applied.settings.maxSentenceDurationSeconds)
        assertEquals(0, applied.settings.maxSentenceCharacters)
        assertEquals(emptyList<String>(), applied.ignoredKeys)
        assertEquals(emptyList<String>(), applied.rejectedKeys)
    }

    @Test
    fun `a v3 backup with the length toggle off imports its caps as zero`() {
        val applied = applyPinEraBackup(toggle = "false")

        assertEquals(0.0, applied.settings.maxSentenceDurationSeconds)
        assertEquals(0, applied.settings.maxSentenceCharacters)
    }

    @Test
    fun `a v3 backup with the length toggle on keeps its caps`() {
        val applied = applyPinEraBackup(toggle = "true")

        assertEquals(12.0, applied.settings.maxSentenceDurationSeconds)
        assertEquals(80, applied.settings.maxSentenceCharacters)
        assertEquals(emptyList<String>(), applied.ignoredKeys)
    }

    @Test
    fun `a v2 backup folds the length toggle the same way`() {
        val json =
            """{"ankiMinerAndroidSettings":2,"appVersion":"0.5.0","schemaVersion":2,""" +
                """"settings":{"use_sentence_length":false,"max_sentence_duration_seconds":12.0,""" +
                """"max_sentence_characters":80}}"""

        val applied = with(SettingsBackupCodec) { parse(json).applyTo(AppSettings()) }

        assertEquals(0.0, applied.settings.maxSentenceDurationSeconds)
        assertEquals(0, applied.settings.maxSentenceCharacters)
    }

    @Test
    fun `a pin-era file silent on the length toggle applies its caps as written`() {
        // An absent key keeps the current value, and the current store has no toggle any more.
        val json =
            """{"ankiMinerAndroidSettings":2,"appVersion":"0.5.0","schemaVersion":2,""" +
                """"settings":{"max_sentence_duration_seconds":12.0}}"""

        val applied = with(SettingsBackupCodec) { parse(json).applyTo(AppSettings()) }

        assertEquals(12.0, applied.settings.maxSentenceDurationSeconds)
    }

    @Test
    fun `a format 4 backup has no length toggle to honour`() {
        val json =
            """{"ankiMinerAndroidSettings":4,"appVersion":"0.7.0","schemaVersion":3,""" +
                """"settings":{"use_sentence_length":false,"max_sentence_duration_seconds":12.0,""" +
                """"dictionary_sources_v1":null,"frequency_sources_v1":null,""" +
                """"pitch_sources_v1":null,"audio_packs_v1":null},""" +
                """"resourceChains":{"dictionary_sources_v1":[],"frequency_sources_v1":[],""" +
                """"pitch_sources_v1":[],"audio_packs_v1":[]}}"""

        val applied =
            with(SettingsBackupCodec) { parse(json).applyTo(AppSettings(), ResourceManagerState()) }

        assertEquals(12.0, applied.settings.maxSentenceDurationSeconds)
        assertEquals(listOf("use_sentence_length"), applied.ignoredKeys)
    }

    /** A format-3 export as the pin-era app wrote it: every portable key present, null when unset. */
    private fun applyPinEraBackup(toggle: String): AppliedSettingsBackup {
        val json =
            """{"ankiMinerAndroidSettings":3,"appVersion":"0.6.0","schemaVersion":2,""" +
                """"settings":{"use_sentence_length":$toggle,""" +
                """"max_sentence_duration_seconds":12.0,"max_sentence_characters":80,""" +
                """"dictionary_sources_v1":null,"frequency_sources_v1":null,""" +
                """"pitch_sources_v1":null,"audio_packs_v1":null},""" +
                """"resourceChains":{"dictionary_sources_v1":[],"frequency_sources_v1":[],""" +
                """"pitch_sources_v1":[],"audio_packs_v1":[]}}"""
        return with(SettingsBackupCodec) {
            parse(json).applyTo(AppSettings(), ResourceManagerState())
        }
    }

    @Test
    fun `portable chains follow semantic content across slot and filename changes`() {
        val sourceInventory =
            ResourceManagerState(
                dictionaries =
                    listOf(
                        dictionary("dictionary", "Shared title", entries = 10),
                        dictionary("dictionary-2", "Shared title", entries = 20),
                    ),
                frequencySources =
                    listOf(frequency("legacy-frequency", "old-frequency-name", entries = 30)),
                pitchSources =
                    listOf(pitch("legacy-pitch", "old-pitch-name", entries = 40)),
            )
        val restoredInventory =
            ResourceManagerState(
                dictionaries =
                    listOf(
                        dictionary("dictionary", "Shared title", entries = 20),
                        dictionary("dictionary-2", "Shared title", entries = 10),
                    ),
                frequencySources =
                    listOf(frequency("renamed-frequency", "new-frequency-name", entries = 30)),
                pitchSources =
                    listOf(pitch("renamed-pitch", "new-pitch-name", entries = 40)),
            )
        val source =
            AppSettings(
                dictionarySources =
                    listOf(
                        ResourceChainSelection("dictionary-2", enabled = true),
                        ResourceChainSelection("dictionary", enabled = false),
                    ),
                frequencySources =
                    listOf(ResourceChainSelection("legacy-frequency", enabled = false)),
                pitchSources = listOf(ResourceChainSelection("legacy-pitch", enabled = true)),
            )

        val applied =
            with(SettingsBackupCodec) {
                parse(encode(source, "0.5.0", sourceInventory))
                    .applyTo(AppSettings(), restoredInventory)
            }.settings

        assertEquals(
            listOf(
                ResourceChainSelection("dictionary", enabled = true),
                ResourceChainSelection("dictionary-2", enabled = false),
            ),
            applied.dictionarySources,
        )
        assertEquals(
            listOf(ResourceChainSelection("renamed-frequency", enabled = false)),
            applied.frequencySources,
        )
        assertEquals(
            listOf(ResourceChainSelection("renamed-pitch", enabled = true)),
            applied.pitchSources,
        )
    }

    @Test
    fun `ambiguous portable resource identities are left visible and disabled`() {
        val sourceInventory =
            ResourceManagerState(
                dictionaries =
                    listOf(
                        dictionary("dictionary", "Shared title", entries = 10),
                        dictionary("dictionary-2", "Shared title", entries = 10),
                    ),
            )
        val restoredInventory =
            ResourceManagerState(
                dictionaries =
                    listOf(
                        dictionary("dictionary", "Shared title", entries = 10),
                        dictionary("dictionary-2", "Shared title", entries = 10),
                    ),
            )
        val source =
            AppSettings(
                dictionarySources =
                    listOf(
                        ResourceChainSelection("dictionary-2", enabled = true),
                        ResourceChainSelection("dictionary", enabled = false),
                    ),
            )

        val applied =
            with(SettingsBackupCodec) {
                parse(encode(source, "0.5.0", sourceInventory))
                    .applyTo(AppSettings(), restoredInventory)
            }.settings

        assertEquals(
            listOf(
                ResourceChainSelection("dictionary", enabled = false),
                ResourceChainSelection("dictionary-2", enabled = false),
            ),
            applied.dictionarySources,
        )
    }

    private fun dictionary(
        id: String,
        title: String,
        entries: Long,
    ) =
        InstalledDictionary(
            slotId = id,
            occupied = true,
            valid = true,
            sourceName = title,
            sourceRevision = "same-revision",
            format = "yomitan",
            entryCount = entries,
            schemaOk = true,
            embeddedAttribution = emptyMap(),
            catalogResourceId = null,
            attribution = emptyList(),
            rebuildSourcePath = null,
        )

    private fun frequency(
        id: String,
        name: String,
        entries: Long,
    ) =
        InstalledFrequencySource(
            sourceId = id,
            sourceName = name,
            format = "yomitan",
            entryCount = entries,
            schemaOk = true,
            schemaVersion = 1,
            isCategorical = false,
            rebuildSourcePath = null,
        )

    private fun pitch(
        id: String,
        name: String,
        entries: Long,
    ) =
        InstalledPitchSource(
            sourceId = id,
            sourceName = name,
            sourceRevision = "same-revision",
            format = "yomitan",
            entryCount = entries,
            schemaOk = true,
            schemaVersion = 1,
            rebuildSourcePath = null,
        )
}
