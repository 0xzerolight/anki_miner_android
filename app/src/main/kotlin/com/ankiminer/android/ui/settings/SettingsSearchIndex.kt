package com.ankiminer.android.ui.settings

import androidx.annotation.StringRes
import com.ankiminer.android.R
import com.ankiminer.android.data.resources.ResourceFailureOrigin
import com.ankiminer.android.data.settings.LanguageScope
import com.ankiminer.android.vm.SetupUiState

internal data class SettingsSearchEntry(
    val id: String,
    val category: SettingsCategory,
    val cardKey: String,
    @StringRes val title: Int,
    @StringRes val detail: Int? = null,
    /**
     * Untranslated words that also find the entry, as desktop's `anchor_text` keywords do: a row's
     * old name, a term its options never spell out ("fps", "dedup"), or the languages' English names.
     * Matched only, never shown and never ranked: a hit here cannot outrank a title.
     */
    val keywords: List<String> = emptyList(),
)

/** Card keys emitted per category by `settingsCategoryContent`. */
internal val SETTINGS_CARD_KEYS: Map<SettingsCategory, Set<String>> =
    mapOf(
        SettingsCategory.ANKI to
            setOf("anki-deck-options", "anki-target", "anki-card-creation", "anki-operation"),
        SettingsCategory.MEDIA to setOf(MEDIA_SENTENCE_AUDIO_KEY, MEDIA_SCREENSHOT_KEY),
        SettingsCategory.RESOURCES to
            setOf(
                "dictionary-sources",
                "pitch-sources",
                "audio-sources",
                "frequency-sources",
                "dictionary-lookup",
            ),
        SettingsCategory.WORD_FILTERS to
            setOf(
                "filtering-options",
                "known-words-import",
                "word-lists",
            ),
        SettingsCategory.SENTENCES to setOf("subtitle-text", "sentence-options"),
        SettingsCategory.LANGUAGE to setOf(MINING_LANGUAGE_KEY, LANGUAGE_VARIANT_KEY),
        SettingsCategory.UI to setOf("ui-options"),
        SettingsCategory.DIAGNOSTICS to
            setOf(
                "diagnostic-runtime",
                "unidic",
                "diagnostic-logging",
                "settings-backup",
                "update-check",
                "reset-actions",
                "tester-diagnostics",
                "attributions",
            ),
    )

private fun entry(
    id: String,
    category: SettingsCategory,
    cardKey: String,
    @StringRes title: Int,
    @StringRes detail: Int? = null,
    keywords: List<String> = emptyList(),
): SettingsSearchEntry = SettingsSearchEntry(id, category, cardKey, title, detail, keywords)

internal val SETTINGS_SEARCH_INDEX: List<SettingsSearchEntry> =
    listOf(
        // Anki
        entry("anki.deck_name", SettingsCategory.ANKI, "anki-deck-options", R.string.settings_deck_name),
        entry(
            "anki.tags",
            SettingsCategory.ANKI,
            "anki-deck-options",
            R.string.settings_tags,
            R.string.settings_tags_help,
        ),
        entry("anki.target_deck", SettingsCategory.ANKI, "anki-deck-options", R.string.anki_deck_title),
        entry(
            "anki.note_type",
            SettingsCategory.ANKI,
            "anki-target",
            R.string.anki_note_type_title,
            R.string.anki_note_type_guidance,
        ),
        entry("anki.field_map", SettingsCategory.ANKI, "anki-target", R.string.settings_field_mapping),
        entry(
            "anki.card_type",
            SettingsCategory.ANKI,
            "anki-target",
            R.string.anki_card_type_title,
            R.string.anki_card_type_explainer,
        ),
        entry(
            "anki.card_type_marker",
            SettingsCategory.ANKI,
            "anki-target",
            R.string.anki_card_type_markers,
            R.string.anki_card_type_marker_field,
        ),
        // Both sit in the field map beside the rows they format, as on desktop.
        entry(
            "anki.reading_tone_color",
            SettingsCategory.ANKI,
            "anki-target",
            R.string.settings_reading_tone_color,
            keywords = listOf("Colour readings by tone", "pinyin", "jyutping"),
        ),
        entry(
            "anki.pitch_format",
            SettingsCategory.ANKI,
            "anki-target",
            R.string.settings_pitch_format,
            R.string.settings_pitch_format_help,
        ),
        entry(
            "anki.strict_card_order",
            SettingsCategory.ANKI,
            "anki-card-creation",
            R.string.settings_strict_card_order,
            R.string.settings_strict_card_order_help,
        ),

        // Media: Sentence audio
        entry("media.audio_format", SettingsCategory.MEDIA, MEDIA_SENTENCE_AUDIO_KEY, R.string.settings_audio_format),
        entry("media.audio_bitrate", SettingsCategory.MEDIA, MEDIA_SENTENCE_AUDIO_KEY, R.string.settings_audio_bitrate),
        entry("media.audio_padding", SettingsCategory.MEDIA, MEDIA_SENTENCE_AUDIO_KEY, R.string.settings_audio_padding),
        entry(
            "media.reading_tts",
            SettingsCategory.MEDIA,
            MEDIA_SENTENCE_AUDIO_KEY,
            R.string.settings_reading_tts,
            R.string.settings_reading_tts_help,
            // Desktop renamed the row from "Read aloud (manga, books)"; the old name still finds it.
            keywords = listOf("TTS", "Read aloud", "Spoken sentences for manga and books"),
        ),
        entry(
            "media.subtitle_offset",
            SettingsCategory.MEDIA,
            MEDIA_SENTENCE_AUDIO_KEY,
            R.string.settings_subtitle_offset,
        ),

        // Media: Screenshot
        entry(
            "media.screenshot_offset",
            SettingsCategory.MEDIA,
            MEDIA_SCREENSHOT_KEY,
            R.string.settings_screenshot_offset,
        ),
        entry(
            "media.animated_screenshots",
            SettingsCategory.MEDIA,
            MEDIA_SCREENSHOT_KEY,
            R.string.settings_animated_screenshots,
            R.string.settings_animated_screenshots_summary,
        ),
        entry(
            "media.animated_format",
            SettingsCategory.MEDIA,
            MEDIA_SCREENSHOT_KEY,
            R.string.settings_animated_format,
            R.string.settings_animated_format_help,
            keywords = listOf("AVIF", "WebP"),
        ),
        entry(
            "media.animated_clip_duration",
            SettingsCategory.MEDIA,
            MEDIA_SCREENSHOT_KEY,
            R.string.settings_animated_clip_duration,
            R.string.settings_animated_clip_duration_help,
            // The row folds in the old "Match audio length" checkbox.
            keywords = listOf("Clip Duration", "Match audio duration", "Match audio length"),
        ),
        entry(
            "media.animated_size",
            SettingsCategory.MEDIA,
            MEDIA_SCREENSHOT_KEY,
            R.string.settings_animated_size,
            R.string.settings_animated_quality_help,
            // The presets never spell these out; the size sets the frame rate and quality too.
            keywords = listOf("fps", "frame rate", "quality", "resolution"),
        ),

        // Dictionaries
        // The recommended set is installed from the panel's Add menu, so searching for it has to
        // land on that panel rather than on a card of its own.
        entry(
            "resources.recommended",
            SettingsCategory.RESOURCES,
            "dictionary-sources",
            R.string.recommended_resources_title,
        ),
        entry(
            "resources.jmdict",
            SettingsCategory.RESOURCES,
            "dictionary-sources",
            R.string.jmdict_resource_title,
            R.string.jmdict_resource_description,
        ),
        entry(
            "resources.dictionary_import",
            SettingsCategory.RESOURCES,
            "dictionary-sources",
            R.string.resource_panel_import_yomitan_zip,
        ),
        entry(
            "resources.pitch_import",
            SettingsCategory.RESOURCES,
            "pitch-sources",
            R.string.resource_panel_add_pitch,
        ),
        entry(
            "resources.dictionary_chain",
            SettingsCategory.RESOURCES,
            "dictionary-sources",
            R.string.resource_panel_dictionaries_heading,
        ),
        // The Updates block sits in the dictionary panel's footer, as desktop's sits on its page.
        entry(
            "resources.dictionary_updates",
            SettingsCategory.RESOURCES,
            "dictionary-sources",
            R.string.dictionary_updates_automatic,
            R.string.dictionary_updates_help,
        ),
        entry(
            "resources.pitch_chain",
            SettingsCategory.RESOURCES,
            "pitch-sources",
            R.string.resource_panel_pitch_heading,
        ),
        entry(
            "resources.lookup_test",
            SettingsCategory.RESOURCES,
            "dictionary-lookup",
            R.string.dictionary_test_title,
        ),

        // Audio packs
        entry("resources.audio_chain", SettingsCategory.RESOURCES, "audio-sources", R.string.resource_panel_audio_heading),
        entry("resources.audio_import", SettingsCategory.RESOURCES, "audio-sources", R.string.resource_panel_add_audio),
        entry(
            "resources.device_voice",
            SettingsCategory.RESOURCES,
            "audio-sources",
            R.string.settings_word_audio_device_voice,
            R.string.settings_word_audio_device_voice_help,
        ),

        // Frequency lists
        entry(
            "resources.frequency_chain",
            SettingsCategory.RESOURCES,
            "frequency-sources",
            R.string.resource_panel_frequency_heading,
        ),
        entry(
            "resources.frequency_import",
            SettingsCategory.RESOURCES,
            "frequency-sources",
            R.string.resource_panel_add_frequency,
        ),

        // Word filters
        entry(
            "word_filters.min_frequency",
            SettingsCategory.WORD_FILTERS,
            "filtering-options",
            R.string.settings_min_frequency,
            R.string.settings_frequency_band_help,
        ),
        entry(
            "word_filters.max_frequency",
            SettingsCategory.WORD_FILTERS,
            "filtering-options",
            R.string.settings_max_frequency,
            R.string.settings_frequency_band_help,
        ),
        entry(
            "word_filters.frequency_keep_unranked",
            SettingsCategory.WORD_FILTERS,
            "filtering-options",
            R.string.settings_frequency_keep_unranked,
        ),
        entry(
            "word_filters.known_words_db",
            SettingsCategory.WORD_FILTERS,
            "filtering-options",
            R.string.settings_known_words,
        ),
        entry(
            "word_filters.kana_variants",
            SettingsCategory.WORD_FILTERS,
            "filtering-options",
            R.string.settings_known_words_match_kana_variants,
            R.string.settings_known_words_match_kana_variants_help,
        ),
        entry(
            "word_filters.excluded_decks",
            SettingsCategory.WORD_FILTERS,
            "filtering-options",
            R.string.settings_excluded_decks,
        ),
        entry(
            "word_filters.wordsets",
            SettingsCategory.WORD_FILTERS,
            "filtering-options",
            R.string.settings_skip_names,
            R.string.settings_skip_names_help,
            keywords = listOf("Name Wordsets", "proper names"),
        ),
        entry(
            "word_filters.script_type",
            SettingsCategory.WORD_FILTERS,
            "filtering-options",
            R.string.settings_script_type,
            keywords = listOf("hiragana", "katakana", "kana"),
        ),
        entry(
            "word_filters.hangul_only",
            SettingsCategory.WORD_FILTERS,
            "filtering-options",
            R.string.settings_exclude_hangul_only,
            R.string.settings_exclude_hangul_only_help,
        ),
        entry(
            "word_filters.hanja_containing",
            SettingsCategory.WORD_FILTERS,
            "filtering-options",
            R.string.settings_exclude_hanja,
            R.string.settings_exclude_hanja_help,
        ),
        entry(
            "word_filters.reading_occurrence",
            SettingsCategory.WORD_FILTERS,
            "filtering-options",
            R.string.settings_reading_occurrence,
        ),
        entry("word_filters.workers", SettingsCategory.WORD_FILTERS, "filtering-options", R.string.settings_workers),
        entry(
            "word_filters.known_words_import",
            SettingsCategory.WORD_FILTERS,
            "known-words-import",
            R.string.known_words_import_title,
        ),
        entry(
            "word_filters.known_words_manage",
            SettingsCategory.WORD_FILTERS,
            "known-words-import",
            R.string.b3_known_words_manage,
        ),
        entry(
            "word_filters.blacklist",
            SettingsCategory.WORD_FILTERS,
            "word-lists",
            R.string.settings_use_blacklist,
            R.string.word_lists_format,
        ),
        entry(
            "word_filters.whitelist",
            SettingsCategory.WORD_FILTERS,
            "word-lists",
            R.string.settings_use_whitelist,
            R.string.word_list_whitelist_scope,
        ),

        // Language
        entry(
            "language.mining_language",
            SettingsCategory.LANGUAGE,
            MINING_LANGUAGE_KEY,
            R.string.language_settings_title,
            R.string.language_settings_help,
        ),
        entry(
            "language.script_variant",
            SettingsCategory.LANGUAGE,
            LANGUAGE_VARIANT_KEY,
            R.string.language_script_variant,
        ),

        // Sentences
        entry(
            "sentences.use_subtitle_regex",
            SettingsCategory.SENTENCES,
            "subtitle-text",
            R.string.settings_subtitle_cleanup,
            R.string.settings_subtitle_cleanup_help,
            keywords = listOf("Enable Subtitle Regex Filter", "clean subtitles", "speaker labels"),
        ),
        // The raw fields sit behind "Edit the pattern (advanced)", which the hit names.
        entry(
            "sentences.subtitle_regex",
            SettingsCategory.SENTENCES,
            "subtitle-text",
            R.string.settings_subtitle_regex,
            R.string.settings_subtitle_edit_pattern,
        ),
        entry(
            "sentences.subtitle_replacement",
            SettingsCategory.SENTENCES,
            "subtitle-text",
            R.string.settings_subtitle_replacement,
            R.string.settings_subtitle_edit_pattern,
        ),
        entry(
            "sentences.subtitle_presets",
            SettingsCategory.SENTENCES,
            "subtitle-text",
            R.string.settings_subtitle_presets,
        ),
        entry(
            "sentences.sentence_rule",
            SettingsCategory.SENTENCES,
            "sentence-options",
            R.string.settings_sentence_rule,
            keywords = listOf("dedup", "deduplicate", "i+1", "one card per sentence"),
        ),
        entry(
            "sentences.max_duration",
            SettingsCategory.SENTENCES,
            "sentence-options",
            R.string.settings_max_duration,
            R.string.settings_max_duration_help,
        ),
        entry(
            "sentences.max_characters",
            SettingsCategory.SENTENCES,
            "sentence-options",
            R.string.settings_max_characters,
            R.string.settings_max_characters_help,
        ),
        entry(
            "sentences.secondary_subtitle",
            SettingsCategory.SENTENCES,
            "sentence-options",
            R.string.settings_secondary_subtitle,
            R.string.settings_secondary_subtitle_help,
        ),
        entry(
            "sentences.merge_incomplete_cues",
            SettingsCategory.SENTENCES,
            "sentence-options",
            R.string.settings_merge_incomplete_cues,
            R.string.settings_merge_incomplete_cues_help,
        ),
        entry("sentences.bold_target", SettingsCategory.SENTENCES, "sentence-options", R.string.settings_bold_target),

        // UI
        entry("ui.theme", SettingsCategory.UI, "ui-options", R.string.settings_theme_mode),
        entry("ui.light_theme", SettingsCategory.UI, "ui-options", R.string.settings_theme_light_choice),
        entry("ui.dark_theme", SettingsCategory.UI, "ui-options", R.string.settings_theme_dark_choice),
        entry("ui.dynamic_color", SettingsCategory.UI, "ui-options", R.string.settings_theme_dynamic),
        entry("ui.setup_wizard", SettingsCategory.UI, "ui-options", R.string.settings_run_setup_wizard),

        // Diagnostics
        entry(
            "diagnostics.unidic",
            SettingsCategory.DIAGNOSTICS,
            "unidic",
            R.string.unidic_resource_title,
            R.string.unidic_resource_description,
        ),
        entry(
            "diagnostics.verbose_logging",
            SettingsCategory.DIAGNOSTICS,
            "diagnostic-logging",
            R.string.settings_verbose_logging,
            R.string.settings_verbose_logging_detail,
        ),
        entry(
            "diagnostics.settings_backup",
            SettingsCategory.DIAGNOSTICS,
            "settings-backup",
            R.string.settings_backup_section,
            R.string.settings_backup_detail,
        ),
        entry(
            "diagnostics.update_check",
            SettingsCategory.DIAGNOSTICS,
            "update-check",
            R.string.settings_update_check_enabled,
            R.string.settings_update_check_detail,
        ),
        entry(
            "diagnostics.reset",
            SettingsCategory.DIAGNOSTICS,
            "reset-actions",
            R.string.settings_reset_section,
        ),
        entry(
            "diagnostics.diagnostics_bundle",
            SettingsCategory.DIAGNOSTICS,
            "tester-diagnostics",
            R.string.settings_diagnostics_bundle,
            R.string.settings_diagnostics_bundle_privacy,
        ),
        entry(
            "diagnostics.attributions",
            SettingsCategory.DIAGNOSTICS,
            "attributions",
            R.string.settings_attributions,
        ),
    )

internal fun availableSettingsSearchEntries(
    entries: List<SettingsSearchEntry>,
    setup: SetupUiState,
    dynamicColorSupported: Boolean,
    language: LanguageSettingsState = LanguageSettingsState(),
): List<SettingsSearchEntry> =
    entries
        .filter { entry ->
            when (entry.id) {
                "resources.lookup_test" -> setup.dictionaries.any { it.isUsable }
                // The rows these find exist only for a language that has them.
                "resources.pitch_import", "resources.pitch_chain", "anki.pitch_format" -> language.showsPitch
                // The device voice speaks word audio only outside Japanese.
                "resources.device_voice" -> language.activeCode != LanguageScope.JAPANESE
                "language.script_variant" -> language.scriptVariants.isNotEmpty()
                "anki.reading_tone_color" -> language.showsToneColor
                "word_filters.kana_variants", "word_filters.script_type" -> language.showsKanaFilters
                "word_filters.hangul_only", "word_filters.hanja_containing" -> language.showsHangulFilters
                "word_filters.wordsets" -> language.showsNameWordsets
                "diagnostics.unidic" ->
                    !setup.tokenizerReady ||
                        setup.failure?.origin == ResourceFailureOrigin.UNIDIC
                "ui.dynamic_color" -> dynamicColorSupported
                else -> true
            }
        }.map { entry ->
            // The list shows native names alone; those and the English one ("Japanese") find it.
            if (entry.id == "language.mining_language") {
                entry.copy(
                    keywords = entry.keywords + language.profiles.flatMap { listOf(it.englishName, it.displayName) },
                )
            } else {
                entry
            }
        }
