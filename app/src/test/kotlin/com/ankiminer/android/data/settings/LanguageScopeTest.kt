package com.ankiminer.android.data.settings

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.ankiminer.android.anki.provider.AnkiFieldKeys
import com.ankiminer.android.engine.BridgeJsonValue
import com.ankiminer.android.engine.MiningConfigSnapshot
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageScopeTest {
    @Test
    fun `the stash set is exactly the settings that move a language-scoped snapshot key`() {
        // The language itself is the scope, never a member of it (a switch away from Japanese moves
        // the Jisho entry, which is Japanese-only).
        val scoped =
            ALTERNATIVES.keys.filter { it != "language" }.filterTo(linkedSetOf()) { property ->
                BASES.any { base ->
                    ALTERNATIVES.getValue(property).any { candidate ->
                        val changed = changedSnapshotKeys(base, base.with(property, candidate))
                        changed.any(LanguageScope.ENGINE_FIELDS::contains)
                    }
                }
            }

        assertEquals(LanguageScope.SETTINGS.keys, scoped)
    }

    @Test
    fun `every property is exercised by a candidate that changes it`() {
        assertEquals(PROPERTIES.map(Field::getName).toSet(), ALTERNATIVES.keys)
        // A reconstruction that changes nothing must give back the same settings, which pins the
        // field order the reflective copy below relies on.
        BASES.forEach { base -> assertEquals(base, base.with("theme", base.theme)) }
        ALTERNATIVES.forEach { (property, candidates) ->
            assertTrue(
                "no candidate for $property differs from a base",
                BASES.any { base -> candidates.any { base.with(property, it) != base } },
            )
        }
    }

    @Test
    fun `each scoped setting is parked under the preference its entry names`() {
        LanguageScope.SETTINGS.forEach { (property, preference) ->
            BASES.forEach { base ->
                ALTERNATIVES.getValue(property).forEach { candidate ->
                    val changed = base.with(property, candidate)
                    if (changed != base) {
                        assertEquals(property, setOf(preference), changedPreferenceNames(base, changed))
                    }
                }
            }
        }
    }

    @Test
    fun `ja to he parks ja and starts he from its profile`() {
        val hebrew = JAPANESE_USER.switchLanguage(LanguageProfileFixtures.hebrew)

        assertEquals("he", hebrew.language)
        assertEquals(setOf("ja"), hebrew.languageStash.keys)
        // Global settings stay with the user, not the language.
        assertEquals(JAPANESE_USER.tags, hebrew.tags)
        assertEquals(JAPANESE_USER.maxParallelWorkers, hebrew.maxParallelWorkers)
        assertEquals(JAPANESE_USER.theme, hebrew.theme)
        // Nothing Japanese is left to resolve against the inventory.
        assertEquals(emptyList<ResourceChainSelection>(), hebrew.audioPacks)
        assertEquals(emptyList<ResourceChainSelection>(), hebrew.dictionarySources)
        assertFalse(hebrew.jishoEnabled)

        // The inventory the snapshot resolves against is the active language's own slots.
        val snapshot = EngineSettingsSnapshotMapper.map(hebrew, emptyList(), availableWordsetIds = WORDSETS)
        val defaults = LanguageProfileFixtures.hebrew.scopedDefaults

        assertEquals(BridgeJsonValue.Text("he"), snapshot.settings["language"])
        assertEquals(BridgeJsonValue.ArrayValue(emptyList()), snapshot.settings["dictionary_chain"])
        assertEquals(BridgeJsonValue.ArrayValue(emptyList()), snapshot.settings["expression_audio_chain"])
        assertFalse("jisho_delay" in snapshot.settings)
        // Every scoped key the snapshot carries is he's own default. The keys it leaves out —
        // the subtitle regex trio and the frequency band among them — resolve to he's defaults on
        // the bridge, which overlays the snapshot on he's first-visit config.
        assertTrue(listOf("subtitle_regex_filter", "use_subtitle_regex_filter").none(snapshot.settings::containsKey))
        assertTrue(listOf("min_frequency_rank", "max_frequency_rank").none(snapshot.settings::containsKey))
        (defaults.keys - "anki_fields").forEach { key ->
            snapshot.settings[key]?.let { sent -> assertEquals(key, defaults.getValue(key), sent) }
        }
        assertEquals(BridgeJsonValue.Text("Anki Miner"), snapshot.settings["anki_deck_name"])
        assertEquals(BridgeJsonValue.Text(""), snapshot.settings["anki_note_type"])
        // The field map stays the user's to fill once a note type is picked, as on a fresh install.
        val fields = (snapshot.settings.getValue("anki_fields") as BridgeJsonValue.ObjectValue).values
        assertTrue(fields.values.all { it == BridgeJsonValue.Text("") })
    }

    @Test
    fun `switching back restores ja exactly`() {
        val roundTrip =
            JAPANESE_USER
                .switchLanguage(LanguageProfileFixtures.hebrew)
                .switchLanguage(LanguageProfileFixtures.japanese)

        assertEquals(JAPANESE_USER, roundTrip.copy(languageStash = emptyMap()))
        assertEquals(setOf("he"), roundTrip.languageStash.keys)
    }

    @Test
    fun `a revisit restores the values chosen there, not the profile's`() {
        val hebrewChoices =
            JAPANESE_USER.switchLanguage(LanguageProfileFixtures.hebrew).copy(
                deckName = "Hebrew",
                noteType = "Hebrew note",
                fieldMap = mapOf("word" to "Word", "transliteration" to "Translit"),
                useSubtitleRegexFilter = false,
            )

        val back =
            hebrewChoices
                .switchLanguage(LanguageProfileFixtures.japanese)
                .switchLanguage(LanguageProfileFixtures.hebrew)

        assertEquals(hebrewChoices.copy(languageStash = emptyMap()), back.copy(languageStash = emptyMap()))
        assertEquals(setOf("ja"), back.languageStash.keys)
    }

    @Test
    fun `a first visit never inherits the outgoing language's values`() {
        val fresh = DataStoreAppSettingsRepository.parkScoped(AppSettings().switchLanguage(LanguageProfileFixtures.hebrew))

        LanguageScope.SETTINGS.keys.forEach { property ->
            BASES.forEach { base ->
                ALTERNATIVES.getValue(property).forEach { candidate ->
                    val visited = base.with(property, candidate).switchLanguage(LanguageProfileFixtures.hebrew)
                    assertEquals(property, fresh, DataStoreAppSettingsRepository.parkScoped(visited))
                }
            }
        }
    }

    @Test
    fun `a first visit to ja takes ja's profile chains and wordsets`() {
        val japanese = AppSettings(language = "he").switchLanguage(LanguageProfileFixtures.japanese)

        assertEquals(listOf(ResourceChainSelection("jmdict-english")), japanese.dictionarySources)
        assertEquals(AppSettings.DEFAULT_ENABLED_WORDSETS, japanese.enabledWordsets)
        // Jisho is in ja's profile chain but stays opt-in on Android.
        assertFalse(japanese.jishoEnabled)
        // Never the desktop Lapis default: the user picks the note type.
        assertEquals(null, japanese.noteType)
    }

    @Test
    fun `a parked snapshot is laid over the profile, so a setting it lacks takes the profile default`() {
        val settings =
            AppSettings(
                language = "he",
                languageStash = mapOf("ja" to mapOf("deck_name" to "Japanese", "jisho_enabled" to true)),
            )

        val japanese = settings.switchLanguage(LanguageProfileFixtures.japanese)

        assertEquals("Japanese", japanese.deckName)
        assertTrue(japanese.jishoEnabled)
        assertEquals(listOf(ResourceChainSelection("jmdict-english")), japanese.dictionarySources)
        assertEquals(AppSettings.DEFAULT_ENABLED_WORDSETS, japanese.enabledWordsets)
    }

    @Test
    fun `switching to the active language drops its stale parked entry and otherwise changes nothing`() {
        val stale = JAPANESE_USER.copy(languageStash = mapOf("ja" to mapOf("deck_name" to "Old")))

        assertEquals(JAPANESE_USER, stale.switchLanguage(LanguageProfileFixtures.japanese))
        assertEquals(JAPANESE_USER, JAPANESE_USER.switchLanguage(LanguageProfileFixtures.japanese))
    }

    @Test
    fun `switching overwrites a stale entry for the outgoing language with its live values`() {
        val stale = JAPANESE_USER.copy(languageStash = mapOf("ja" to mapOf("deck_name" to "Old")))

        val parked = stale.switchLanguage(LanguageProfileFixtures.hebrew).languageStash.getValue("ja")

        assertEquals(JAPANESE_USER.deckName, parked["deck_name"])
        assertEquals(LanguageScope.PREFERENCE_NAMES, parked.keys)
    }

    @Test
    fun `the language and its stash persist, and an absent language is Japanese with nothing parked`() {
        val hebrew = JAPANESE_USER.switchLanguage(LanguageProfileFixtures.hebrew)

        val decoded =
            DataStoreAppSettingsRepository.decodePreferences(
                DataStoreAppSettingsRepository.encodePreferences(hebrew, emptyPreferences()),
            )
        val legacy = DataStoreAppSettingsRepository.decodePreferences(preferencesOf())

        assertEquals(hebrew, decoded)
        assertEquals("ja", legacy.language)
        assertEquals(emptyMap<String, Map<String, Any?>>(), legacy.languageStash)
        assertFalse(DataStoreAppSettingsRepository.migrationRequired(
            DataStoreAppSettingsRepository.migratePreferences(preferencesOf()),
        ))
    }

    @Test
    fun `a corrupt stash is quarantined without touching the active language`() {
        val stored =
            DataStoreAppSettingsRepository.encodePreferences(AppSettings(language = "he"), emptyPreferences())
                .toMutablePreferences()
                .apply { this[stringPreferencesKey("language_stash_v1")] = """{"ja":[1]}""" }
                .toPreferences()

        val decoded = DataStoreAppSettingsRepository.decodeWithReport(stored)

        assertEquals("he", decoded.settings.language)
        assertEquals(emptyMap<String, Map<String, Any?>>(), decoded.settings.languageStash)
        assertEquals(setOf("language_stash_v1"), decoded.invalidKeys.mapTo(mutableSetOf()) { it.name })
    }

    @Test
    fun `the stash codec drops names outside the scoped set and rejects a bad code`() {
        val decoded =
            LanguageStashPreferenceCodec.decode("""{"ja":{"deck_name":"Japanese","theme_mode":"light"}}""")

        assertEquals(mapOf("ja" to mapOf<String, Any?>("deck_name" to "Japanese")), decoded)
        assertThrows(InvalidAppSettingException::class.java) {
            LanguageStashPreferenceCodec.decode("""{"Japanese":{}}""")
        }
        assertThrows(InvalidAppSettingException::class.java) {
            AppSettingsValidator.validate(AppSettings(language = "japanese"))
        }
    }

    @Test
    fun `another language maps its own card fields and Japanese still refuses them`() {
        val hebrew =
            AppSettings(language = "he", fieldMap = mapOf("word" to "Word", "transliteration" to "Translit"))

        val fields =
            (EngineSettingsSnapshotMapper.map(hebrew, emptyList()).settings.getValue("anki_fields")
                as BridgeJsonValue.ObjectValue).values

        assertEquals(AnkiFieldKeys.ALL.toSet() + "transliteration", fields.keys)
        assertEquals(BridgeJsonValue.Text("Translit"), fields["transliteration"])
        assertEquals(
            hebrew,
            DataStoreAppSettingsRepository.decodePreferences(
                DataStoreAppSettingsRepository.encodePreferences(hebrew, emptyPreferences()),
            ),
        )
        assertThrows(InvalidAppSettingException::class.java) {
            AppSettingsValidator.validate(hebrew.copy(language = "ja"))
        }
    }

    @Test
    fun `restoring mining defaults under he leaves the scoped processing settings to he's profile`() {
        val restored =
            JAPANESE_USER.switchLanguage(LanguageProfileFixtures.hebrew)
                .copy(useSubtitleRegexFilter = false, knownWordsMatchKanaVariants = true, maxFrequencyRank = 10)
                .restoreMiningDefaults()

        val snapshot = EngineSettingsSnapshotMapper.map(restored, emptyList())

        listOf("use_subtitle_regex_filter", "known_words_match_kana_variants", "max_frequency_rank")
            .forEach { key -> assertFalse(key, key in snapshot.settings) }
        assertNotEquals(EngineDefaults.KNOWN_WORDS_MATCH_KANA_VARIANTS, LanguageDefaults.from(LanguageProfileFixtures.hebrew).knownWordsMatchKanaVariants)
    }

    private fun changedSnapshotKeys(
        before: AppSettings,
        after: AppSettings,
    ): Set<String> {
        val left = map(before).settings
        val right = map(after).settings
        return (left.keys + right.keys).filterTo(mutableSetOf()) { left[it] != right[it] }
    }

    private fun changedPreferenceNames(
        before: AppSettings,
        after: AppSettings,
    ): Set<String> {
        val left = DataStoreAppSettingsRepository.encodePreferences(before, emptyPreferences()).asMap().mapKeys { it.key.name }
        val right = DataStoreAppSettingsRepository.encodePreferences(after, emptyPreferences()).asMap().mapKeys { it.key.name }
        return (left.keys + right.keys).filterTo(mutableSetOf()) { left[it] != right[it] }
    }

    private fun map(settings: AppSettings): MiningConfigSnapshot =
        EngineSettingsSnapshotMapper.map(
            settings,
            installedDictionaryIds = listOf("dict-a", "dict-b"),
            installedFrequencyIds = listOf("freq-a", "freq-b"),
            installedPitchIds = listOf("pitch-a", "pitch-b"),
            installedAudioPackIds = listOf("pack-a", "pack-b"),
            availableWordsetIds = WORDSETS,
            blacklistPath = "/data/blacklist.txt",
            whitelistPath = "/data/whitelist.txt",
        )

    private companion object {
        val WORDSETS = AppSettings.DEFAULT_ENABLED_WORDSETS

        val PROPERTIES: List<Field> =
            AppSettings::class.java.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) }
                .onEach { it.isAccessible = true }

        /** [AppSettings.copy] by property name, so every property can be varied by one loop. */
        fun AppSettings.with(
            property: String,
            value: Any?,
        ): AppSettings {
            val arguments = PROPERTIES.map { if (it.name == property) value else it.get(this) }
            val constructor =
                AppSettings::class.java.declaredConstructors.single { it.parameterCount == PROPERTIES.size }
            return constructor.newInstance(*arguments.toTypedArray()) as AppSettings
        }

        fun selection(
            id: String,
            enabled: Boolean = true,
        ) = ResourceChainSelection(id, enabled)

        /** Each condition a mapper branch depends on is on, so every property can reach the wire. */
        val RICH =
            AppSettings(
                deckName = "Deck",
                excludedDecks = listOf("Deck::Known"),
                noteType = "Lapis",
                fieldMap = mapOf("word" to "Expression"),
                cardType = CardType.CLICK,
                cardTypeMarkerField = "IsClickCard",
                animatedScreenshotsEnabled = true,
                animatedScreenshotMatchAudio = false,
                animatedScreenshotDurationSeconds = 2.5,
                animatedScreenshotQuality = 40,
                subtitleRegexFilter = "a+",
                subtitleRegexReplacement = "b",
                useSubtitleRegexFilter = true,
                useBlacklist = true,
                useWhitelist = true,
                dictionarySources = listOf(selection("dict-a")),
                frequencySources = listOf(selection("freq-a")),
                pitchSources = listOf(selection("pitch-a")),
                audioPacks = listOf(selection("pack-a")),
                jishoEnabled = true,
            )

        val BASES = listOf(AppSettings(), RICH)

        private val BOOLEANS = listOf(null, true, false)

        /** Values each property is changed to; every property must appear, which the test checks. */
        val ALTERNATIVES: Map<String, List<Any?>> =
            mapOf(
                "setupWizardSeen" to listOf(true, false),
                "theme" to listOf(ThemeMode.LIGHT, ThemeMode.SYSTEM),
                "lightThemeKey" to listOf("solarized-light"),
                "darkThemeKey" to listOf("catppuccin-mocha"),
                "dynamicColorEnabled" to listOf(true, false),
                "deckName" to listOf(null, "Other"),
                "excludedDecks" to listOf(emptyList<String>(), listOf("Known")),
                "noteType" to listOf(null, "Other note"),
                "fieldMap" to listOf(emptyMap<String, String>(), mapOf("sentence" to "Sentence")),
                "cardType" to listOf(null, CardType.SENTENCE),
                "cardTypeMarkerField" to listOf(null, "IsSentenceCard"),
                "tags" to listOf("", "other"),
                "audioPaddingSeconds" to listOf(null, 0.7),
                "screenshotOffsetSeconds" to listOf(null, 0.4),
                "subtitleOffsetSeconds" to listOf(null, 0.2),
                "audioFormat" to listOf(null, AudioFormat.OPUS),
                "audioBitrateKbps" to listOf(null, 64),
                "animatedScreenshotsEnabled" to listOf(true, false),
                "animatedScreenshotDurationSeconds" to listOf(null, 3.0),
                "animatedScreenshotQuality" to listOf(null, 50),
                "animatedScreenshotMatchAudio" to listOf(true, false),
                "subtitleRegexFilter" to listOf(null, "x+"),
                "subtitleRegexReplacement" to listOf(null, "y"),
                "useSubtitleRegexFilter" to BOOLEANS,
                "useBlacklist" to BOOLEANS,
                "useWhitelist" to BOOLEANS,
                "useKnownWordsDatabase" to BOOLEANS,
                "excludeHiraganaOnly" to BOOLEANS,
                "excludeKatakanaOnly" to BOOLEANS,
                "boldTargetInSentence" to BOOLEANS,
                "deduplicateSentences" to BOOLEANS,
                "useIPlusOneFilter" to BOOLEANS,
                "maxSentenceDurationSeconds" to listOf(null, 9.0),
                "maxSentenceCharacters" to listOf(null, 40),
                "readingMinimumOccurrence" to listOf(null, 3),
                "maxFrequencyRank" to listOf(null, 9000),
                "minFrequencyRank" to listOf(null, 100),
                "frequencyKeepUnranked" to BOOLEANS,
                "knownWordsMatchKanaVariants" to BOOLEANS,
                "scriptVariant" to listOf("simplified", ""),
                "readingToneColor" to BOOLEANS,
                "strictCardOrder" to BOOLEANS,
                "mergeIncompleteCues" to BOOLEANS,
                "secondarySubtitleEnabled" to listOf(true, false),
                "pitchCategoryFormat" to listOf(null, PitchCategoryFormat.ROMAJI),
                "maxParallelWorkers" to listOf(null, 2),
                "dictionarySources" to
                    listOf(emptyList<ResourceChainSelection>(), listOf(selection("dict-b"), selection("dict-a", false))),
                "frequencySources" to
                    listOf(emptyList<ResourceChainSelection>(), listOf(selection("freq-b"), selection("freq-a", false))),
                "pitchSources" to
                    listOf(emptyList<ResourceChainSelection>(), listOf(selection("pitch-b"), selection("pitch-a", false))),
                "audioPacks" to
                    listOf(emptyList<ResourceChainSelection>(), listOf(selection("pack-b"), selection("pack-a", false))),
                "enabledWordsets" to listOf(emptyList<String>(), listOf("place-names")),
                "readingTtsEnabled" to listOf(true, false),
                "jishoEnabled" to listOf(true, false),
                "language" to listOf("he"),
                "languageStash" to listOf(mapOf("he" to mapOf<String, Any?>("deck_name" to "Hebrew"))),
            )

        /** A configured Japanese user: every scoped setting differs from its unset value. */
        val JAPANESE_USER =
            AppSettings(
                setupWizardSeen = true,
                theme = ThemeMode.LIGHT,
                deckName = "Japanese",
                excludedDecks = listOf("Japanese::Known"),
                noteType = "Lapis",
                fieldMap = mapOf("word" to "Expression", "sentence" to "Sentence"),
                cardType = CardType.CLICK,
                cardTypeMarkerField = "IsClickCard",
                tags = "mined",
                subtitleRegexFilter = """\(.*?\)""",
                subtitleRegexReplacement = "",
                useSubtitleRegexFilter = true,
                useBlacklist = true,
                useWhitelist = false,
                excludeHiraganaOnly = true,
                excludeKatakanaOnly = false,
                maxFrequencyRank = 9000,
                minFrequencyRank = 500,
                frequencyKeepUnranked = true,
                knownWordsMatchKanaVariants = false,
                maxParallelWorkers = 3,
                dictionarySources = listOf(selection("jitendex")),
                frequencySources = listOf(selection("jpdb")),
                pitchSources = listOf(selection("kanjium")),
                audioPacks = listOf(selection("ja-pack")),
                enabledWordsets = listOf("surnames"),
                jishoEnabled = true,
            )
    }
}
