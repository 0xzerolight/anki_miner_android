package com.ankiminer.android.ui.settings

import androidx.annotation.StringRes
import com.ankiminer.android.R
import com.ankiminer.android.data.resources.ResourceFailureOrigin
import com.ankiminer.android.vm.SetupUiState

internal data class SettingsSearchEntry(
    val id: String,
    val category: SettingsCategory,
    val cardKey: String,
    @StringRes val title: Int,
    @StringRes val detail: Int? = null,
)

/** Card keys emitted per category by `settingsCategoryContent`. */
internal val SETTINGS_CARD_KEYS: Map<SettingsCategory, Set<String>> =
    mapOf(
        SettingsCategory.ANKI to
            setOf("anki-deck-options", "anki-target", "anki-recovery", "anki-operation"),
        SettingsCategory.MEDIA to setOf("media-options", "subtitle-text"),
        SettingsCategory.DICTIONARIES to
            setOf(
                "catalog-dictionaries",
                "custom-dictionary",
                "pitch",
                "dictionary-chain",
                "dictionary-lookup",
                "dictionary-inventory",
            ),
        SettingsCategory.AUDIO to setOf("audio-chain", "audio-import", "reading-audio"),
        SettingsCategory.FREQUENCY to setOf("frequency-chain", "frequency-import"),
        SettingsCategory.FILTERING to
            setOf(
                "filtering-options",
                "known-words-import",
                "word-lists",
                "filtering-import-result",
            ),
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
): SettingsSearchEntry = SettingsSearchEntry(id, category, cardKey, title, detail)

internal val SETTINGS_SEARCH_INDEX: List<SettingsSearchEntry> =
    listOf(
        // Anki
        entry("anki.deck_name", SettingsCategory.ANKI, "anki-deck-options", R.string.settings_deck_name),
        entry("anki.excluded_decks", SettingsCategory.ANKI, "anki-deck-options", R.string.settings_excluded_decks),
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
            R.string.anki_card_type_marker_field,
            R.string.anki_card_type_marker_missing,
        ),

        // Media
        entry("media.audio_padding", SettingsCategory.MEDIA, "media-options", R.string.settings_audio_padding),
        entry("media.screenshot_offset", SettingsCategory.MEDIA, "media-options", R.string.settings_screenshot_offset),
        entry(
            "media.animated_screenshots",
            SettingsCategory.MEDIA,
            "media-options",
            R.string.settings_animated_screenshots,
            R.string.settings_animated_screenshots_summary,
        ),
        entry(
            "media.animated_match_audio",
            SettingsCategory.MEDIA,
            "media-options",
            R.string.settings_animated_match_audio,
            R.string.settings_animated_match_audio_help,
        ),
        entry(
            "media.animated_clip_duration",
            SettingsCategory.MEDIA,
            "media-options",
            R.string.settings_animated_clip_duration,
            R.string.settings_animated_clip_duration_help,
        ),
        entry(
            "media.animated_quality",
            SettingsCategory.MEDIA,
            "media-options",
            R.string.settings_animated_quality,
            R.string.settings_animated_quality_help,
        ),
        entry("media.subtitle_offset", SettingsCategory.MEDIA, "media-options", R.string.settings_subtitle_offset),
        entry("media.audio_bitrate", SettingsCategory.MEDIA, "media-options", R.string.settings_audio_bitrate),
        entry("media.audio_format", SettingsCategory.MEDIA, "media-options", R.string.settings_audio_format),
        entry("media.subtitle_regex", SettingsCategory.MEDIA, "subtitle-text", R.string.settings_subtitle_regex),
        entry(
            "media.subtitle_replacement",
            SettingsCategory.MEDIA,
            "subtitle-text",
            R.string.settings_subtitle_replacement,
        ),
        entry(
            "media.use_subtitle_regex",
            SettingsCategory.MEDIA,
            "subtitle-text",
            R.string.settings_use_subtitle_regex,
        ),
        entry("media.subtitle_presets", SettingsCategory.MEDIA, "subtitle-text", R.string.settings_subtitle_presets),

        // Dictionaries
        entry(
            "dictionaries.jitendex",
            SettingsCategory.DICTIONARIES,
            "catalog-dictionaries",
            R.string.jitendex_resource_title,
            R.string.jitendex_resource_description,
        ),
        entry(
            "dictionaries.jmdict",
            SettingsCategory.DICTIONARIES,
            "catalog-dictionaries",
            R.string.jmdict_resource_title,
            R.string.jmdict_resource_description,
        ),
        entry(
            "dictionaries.custom",
            SettingsCategory.DICTIONARIES,
            "custom-dictionary",
            R.string.custom_dictionary_title,
        ),
        entry("dictionaries.pitch_import", SettingsCategory.DICTIONARIES, "pitch", R.string.pitch_import_title),
        entry(
            "dictionaries.chain",
            SettingsCategory.DICTIONARIES,
            "dictionary-chain",
            R.string.settings_dictionary_chain,
        ),
        entry(
            "dictionaries.jisho",
            SettingsCategory.DICTIONARIES,
            "dictionary-chain",
            R.string.settings_jisho,
            R.string.settings_jisho_disclosure,
        ),
        entry(
            "dictionaries.pitch_chain",
            SettingsCategory.DICTIONARIES,
            "dictionary-chain",
            R.string.settings_pitch_chain,
        ),
        entry(
            "dictionaries.pitch_format",
            SettingsCategory.DICTIONARIES,
            "dictionary-chain",
            R.string.settings_pitch_format,
        ),
        entry(
            "dictionaries.lookup_test",
            SettingsCategory.DICTIONARIES,
            "dictionary-lookup",
            R.string.dictionary_test_title,
        ),

        // Audio
        entry("audio.pack_chain", SettingsCategory.AUDIO, "audio-chain", R.string.settings_audio_pack_chain),
        entry("audio.pack_import", SettingsCategory.AUDIO, "audio-import", R.string.audio_pack_import_title),
        entry(
            "audio.reading_tts",
            SettingsCategory.AUDIO,
            "reading-audio",
            R.string.settings_reading_tts,
            R.string.settings_reading_audio,
        ),

        // Frequency
        entry("frequency.chain", SettingsCategory.FREQUENCY, "frequency-chain", R.string.settings_frequency_chain),
        entry("frequency.import", SettingsCategory.FREQUENCY, "frequency-import", R.string.frequency_import_title),

        // Filtering
        entry(
            "filtering.known_words_db",
            SettingsCategory.FILTERING,
            "filtering-options",
            R.string.settings_known_words,
        ),
        entry(
            "filtering.exclude_hiragana",
            SettingsCategory.FILTERING,
            "filtering-options",
            R.string.settings_exclude_hiragana,
        ),
        entry(
            "filtering.exclude_katakana",
            SettingsCategory.FILTERING,
            "filtering-options",
            R.string.settings_exclude_katakana,
        ),
        entry("filtering.bold_target", SettingsCategory.FILTERING, "filtering-options", R.string.settings_bold_target),
        entry("filtering.deduplicate", SettingsCategory.FILTERING, "filtering-options", R.string.settings_deduplicate),
        entry("filtering.i_plus_one", SettingsCategory.FILTERING, "filtering-options", R.string.settings_i_plus_one),
        entry(
            "filtering.sentence_length",
            SettingsCategory.FILTERING,
            "filtering-options",
            R.string.settings_sentence_length,
        ),
        entry(
            "filtering.max_duration",
            SettingsCategory.FILTERING,
            "filtering-options",
            R.string.settings_max_duration,
        ),
        entry(
            "filtering.max_characters",
            SettingsCategory.FILTERING,
            "filtering-options",
            R.string.settings_max_characters,
        ),
        entry(
            "filtering.reading_occurrence",
            SettingsCategory.FILTERING,
            "filtering-options",
            R.string.settings_reading_occurrence,
        ),
        entry(
            "filtering.max_frequency",
            SettingsCategory.FILTERING,
            "filtering-options",
            R.string.settings_max_frequency,
        ),
        entry("filtering.workers", SettingsCategory.FILTERING, "filtering-options", R.string.settings_workers),
        entry("filtering.wordsets", SettingsCategory.FILTERING, "filtering-options", R.string.settings_wordsets),
        entry(
            "filtering.known_words_import",
            SettingsCategory.FILTERING,
            "known-words-import",
            R.string.known_words_import_title,
        ),
        entry(
            "filtering.known_words_manage",
            SettingsCategory.FILTERING,
            "known-words-import",
            R.string.b3_known_words_manage,
        ),
        entry(
            "filtering.blacklist",
            SettingsCategory.FILTERING,
            "word-lists",
            R.string.settings_use_blacklist,
            R.string.word_lists_format,
        ),
        entry(
            "filtering.whitelist",
            SettingsCategory.FILTERING,
            "word-lists",
            R.string.settings_use_whitelist,
            R.string.word_list_whitelist_scope,
        ),

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
            R.string.settings_share_diagnostics_bundle,
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
): List<SettingsSearchEntry> =
    entries.filter { entry ->
        when (entry.id) {
            "dictionaries.lookup_test" -> setup.dictionaries.any { it.isUsable }
            "diagnostics.unidic" ->
                !setup.uniDicInstalled ||
                    setup.failure?.origin == ResourceFailureOrigin.UNIDIC
            "ui.dynamic_color" -> dynamicColorSupported
            else -> true
        }
    }
