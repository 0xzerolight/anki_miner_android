package com.ankiminer.android.ui.settings

import com.ankiminer.android.R
import com.ankiminer.android.data.anki.AnkiSetupFailureOrigin
import com.ankiminer.android.data.resources.InstalledDictionary
import com.ankiminer.android.data.resources.ResourceFailureOrigin
import com.ankiminer.android.data.settings.LanguageProfileFixtures
import com.ankiminer.android.vm.SetupUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchIndexTest {
    @Test
    fun wordFiltersNoLongerEmitsTheImportReceiptCard() {
        assertEquals(
            setOf("filtering-options", "known-words-import", "word-lists"),
            SETTINGS_CARD_KEYS.getValue(SettingsCategory.WORD_FILTERS),
        )
    }

    @Test
    fun `every id is unique`() {
        val ids = SETTINGS_SEARCH_INDEX.map { it.id }

        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `every entry points at a card its own category emits`() {
        SETTINGS_SEARCH_INDEX.forEach { entry ->
            assertTrue(
                entry.id,
                entry.cardKey in SETTINGS_CARD_KEYS.getValue(entry.category),
            )
        }
    }

    @Test
    fun `every category is represented`() {
        assertEquals(
            SettingsCategory.entries.toSet(),
            SETTINGS_SEARCH_INDEX.map { it.category }.toSet(),
        )
    }

    @Test
    fun `an id names its category so a moved entry is visible in review`() {
        SETTINGS_SEARCH_INDEX.forEach { entry ->
            assertTrue(
                entry.id,
                entry.id.startsWith("${entry.category.name.lowercase()}."),
            )
        }
    }

    @Test
    fun `search no longer finds the retired Jisho row`() {
        assertFalse(SETTINGS_SEARCH_INDEX.any { it.id == "resources.jisho" })
    }

    @Test
    fun `dictionary updates search lands on the dictionary panel that holds the Updates block`() {
        val updates = SETTINGS_SEARCH_INDEX.single { it.id == "resources.dictionary_updates" }

        assertEquals(SettingsCategory.RESOURCES, updates.category)
        assertEquals("dictionary-sources", updates.cardKey)
        assertEquals(R.string.dictionary_updates_automatic, updates.title)
        assertEquals(R.string.dictionary_updates_help, updates.detail)
    }

    @Test
    fun `custom dictionary search has no removed slot-picker detail`() {
        val custom = SETTINGS_SEARCH_INDEX.single { it.id == "resources.dictionary_import" }

        assertEquals(null, custom.detail)
    }

    @Test
    fun `tags search detail explains that blank means no tags`() {
        val tags = SETTINGS_SEARCH_INDEX.single { it.id == "anki.tags" }

        assertEquals(R.string.settings_tags_help, tags.detail)
    }

    @Test
    fun `target deck search points to the card containing the deck field`() {
        val targetDeck = SETTINGS_SEARCH_INDEX.single { it.id == "anki.target_deck" }

        assertEquals("anki-deck-options", targetDeck.cardKey)
    }

    @Test
    fun `theme search uses the displayed theme mode label`() {
        val theme = SETTINGS_SEARCH_INDEX.single { it.id == "ui.theme" }

        assertEquals(R.string.settings_theme_mode, theme.title)
    }

    private fun assertEntriesOn(
        category: SettingsCategory,
        ids: List<String>,
    ) {
        val byId = SETTINGS_SEARCH_INDEX.associateBy { it.id }
        ids.forEach { id ->
            assertEquals(id, category, byId[id]?.category)
        }
    }

    @Test
    fun `sentence rows sit on Sentences, as on desktop`() {
        val ids = SETTINGS_SEARCH_INDEX.map { it.id }.toSet()

        assertEntriesOn(
            SettingsCategory.SENTENCES,
            listOf(
                "sentences.subtitle_regex",
                "sentences.subtitle_replacement",
                "sentences.use_subtitle_regex",
                "sentences.subtitle_presets",
                "sentences.sentence_rule",
                "sentences.max_duration",
                "sentences.max_characters",
                "sentences.secondary_subtitle",
                "sentences.merge_incomplete_cues",
                "sentences.bold_target",
            ),
        )
        // The length toggle is gone; its caps are the whole setting now.
        assertFalse(ids.any { it.endsWith("sentence_length") })
    }

    @Test
    fun `word filter and card creation rows sit where desktop puts them`() {
        assertEntriesOn(
            SettingsCategory.WORD_FILTERS,
            listOf(
                "word_filters.min_frequency",
                "word_filters.max_frequency",
                "word_filters.frequency_keep_unranked",
                "word_filters.kana_variants",
                "word_filters.excluded_decks",
            ),
        )
        assertEntriesOn(SettingsCategory.ANKI, listOf("anki.strict_card_order"))
        assertFalse(SETTINGS_SEARCH_INDEX.any { it.id == "anki.excluded_decks" })
    }

    @Test
    fun `media rows sit in desktop's two sections`() {
        assertEquals(
            setOf("media-sentence-audio", "media-screenshot"),
            SETTINGS_CARD_KEYS.getValue(SettingsCategory.MEDIA),
        )
        val sections = SETTINGS_SEARCH_INDEX.filter { it.category == SettingsCategory.MEDIA }.associate { it.id to it.cardKey }
        assertEquals(
            mapOf(
                "media.audio_format" to "media-sentence-audio",
                "media.audio_bitrate" to "media-sentence-audio",
                "media.audio_padding" to "media-sentence-audio",
                "media.reading_tts" to "media-sentence-audio",
                "media.subtitle_offset" to "media-sentence-audio",
                "media.screenshot_offset" to "media-screenshot",
                "media.animated_screenshots" to "media-screenshot",
                "media.animated_format" to "media-screenshot",
                "media.animated_clip_duration" to "media-screenshot",
                "media.animated_size" to "media-screenshot",
            ),
            sections,
        )
    }

    @Test
    fun `moved rows are found where they now live`() {
        val byId = SETTINGS_SEARCH_INDEX.associateBy { it.id }

        assertEquals("anki-target", byId.getValue("anki.reading_tone_color").cardKey)
        assertEquals("anki-target", byId.getValue("anki.pitch_format").cardKey)
        val tts = byId.getValue("media.reading_tts")
        assertEquals(R.string.settings_reading_tts, tts.title)
        // Desktop renamed the row; its old name still finds it.
        assertTrue("Read aloud" in tts.keywords)
        listOf(
            "language.reading_tone_color",
            "resources.pitch_format",
            "resources.reading_tts",
            "sentences.deduplicate",
            "sentences.i_plus_one",
            "word_filters.exclude_hiragana",
            "word_filters.exclude_katakana",
            "media.animated_match_audio",
            "media.animated_quality",
        ).forEach { retired -> assertFalse(retired, retired in byId) }
        assertEquals(
            setOf(MINING_LANGUAGE_KEY, LANGUAGE_VARIANT_KEY),
            SETTINGS_CARD_KEYS.getValue(SettingsCategory.LANGUAGE),
        )
    }

    @Test
    fun `folded combos keep desktop's search keywords`() {
        val byId = SETTINGS_SEARCH_INDEX.associateBy { it.id }

        assertTrue(byId.getValue("word_filters.script_type").keywords.containsAll(listOf("hiragana", "katakana")))
        assertTrue(byId.getValue("sentences.sentence_rule").keywords.containsAll(listOf("dedup", "deduplicate")))
        assertTrue("fps" in byId.getValue("media.animated_size").keywords)
        assertTrue("Match audio duration" in byId.getValue("media.animated_clip_duration").keywords)
    }

    @Test
    fun `language-gated rows are searchable only where they show`() {
        val profiles = LanguageProfileFixtures.all
        val tonal =
            LanguageProfileFixtures.hebrew
                .copy(code = "zh", capabilities = setOf("tone_color"))
        val korean =
            LanguageProfileFixtures.hebrew
                .copy(code = "ko", capabilities = setOf("hangul_filters", "hanja"))

        fun ids(state: LanguageSettingsState) =
            availableSettingsSearchEntries(SETTINGS_SEARCH_INDEX, SetupUiState(), false, state).map { it.id }

        val japanese = ids(LanguageSettingsState(activeCode = "ja", profiles = profiles))
        val chinese = ids(LanguageSettingsState(activeCode = "zh", profiles = listOf(tonal)))
        val hangul = ids(LanguageSettingsState(activeCode = "ko", profiles = listOf(korean)))

        assertTrue("anki.pitch_format" in japanese && "word_filters.script_type" in japanese)
        assertFalse("anki.reading_tone_color" in japanese || "word_filters.hangul_only" in japanese)
        assertTrue("anki.reading_tone_color" in chinese)
        assertFalse("anki.pitch_format" in chinese || "word_filters.script_type" in chinese)
        assertTrue(hangul.containsAll(listOf("word_filters.hangul_only", "word_filters.hanja_containing")))
        assertFalse("word_filters.script_type" in hangul)
    }

    @Test
    fun `the language list is found by every language's English name`() {
        val state =
            LanguageSettingsState(
                activeCode = "ja",
                profiles = LanguageProfileFixtures.all,
            )
        val list =
            availableSettingsSearchEntries(SETTINGS_SEARCH_INDEX, SetupUiState(), false, state)
                .single { it.id == "language.mining_language" }

        assertTrue(list.keywords.containsAll(listOf("Japanese", "Hebrew", "Arabic", "日本語", "עברית")))
    }

    @Test
    fun `failure deep links still land on constant card indices`() {
        // Media gained a card and Language lost one, but no failure origin points at either tab,
        // and the Resources and Word filters cards keep their order.
        ResourceFailureOrigin.entries.forEach { origin ->
            assertTrue(
                origin.name,
                settingsCategoryFor(origin) !in setOf(SettingsCategory.MEDIA, SettingsCategory.LANGUAGE),
            )
        }
        assertEquals(
            listOf(2, 3, 4, 5, 6, 3, 4),
            listOf(
                ResourceFailureOrigin.CATALOG_DICTIONARY,
                ResourceFailureOrigin.PITCH,
                ResourceFailureOrigin.AUDIO,
                ResourceFailureOrigin.FREQUENCY,
                ResourceFailureOrigin.DICTIONARY_LOOKUP,
                ResourceFailureOrigin.KNOWN_WORDS,
                ResourceFailureOrigin.WORD_LIST,
            ).map(::settingsCardIndexFor),
        )
        assertEquals(
            3,
            settingsCardIndexFor(AnkiSetupFailureOrigin.TARGET),
        )
    }

    @Test
    fun `conditional entries follow the controls emitted for current state`() {
        val unavailable =
            availableSettingsSearchEntries(
                entries = SETTINGS_SEARCH_INDEX,
                setup = SetupUiState(uniDicInstalled = true),
                dynamicColorSupported = false,
            ).map(SettingsSearchEntry::id)

        assertFalse("diagnostics.unidic" in unavailable)
        assertFalse("resources.lookup_test" in unavailable)
        assertFalse("ui.dynamic_color" in unavailable)

        val available =
            availableSettingsSearchEntries(
                entries = SETTINGS_SEARCH_INDEX,
                setup =
                    SetupUiState(
                        uniDicInstalled = false,
                        dictionaries = listOf(installedDictionary()),
                    ),
                dynamicColorSupported = true,
            ).map(SettingsSearchEntry::id)

        assertTrue("diagnostics.unidic" in available)
        assertTrue("resources.lookup_test" in available)
        assertTrue("ui.dynamic_color" in available)
    }

    private fun installedDictionary() =
        InstalledDictionary(
            slotId = "jmdict",
            occupied = true,
            valid = true,
            sourceName = "JMdict",
            sourceRevision = "1",
            format = "yomitan",
            entryCount = 1,
            schemaOk = true,
            embeddedAttribution = emptyMap(),
            catalogResourceId = "jmdict",
            attribution = emptyList(),
            rebuildSourcePath = null,
        )
}
