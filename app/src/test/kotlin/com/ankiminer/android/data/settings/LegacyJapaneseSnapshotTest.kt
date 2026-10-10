package com.ankiminer.android.data.settings

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.ankiminer.android.engine.BridgeJsonValue
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonGenerator
import java.io.StringWriter
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A Japanese user who upgrades and never switches language must mine exactly as before: no
 * profile default (Lapis, the jmdict chain) reaches their settings, and the run request carries the
 * same snapshot bytes. The goldens were captured from the tree before mining languages existed (`1489b030`),
 * then changed once on purpose by the desktop v3.8.0 re-pin: Jisho is gone (no entry, no
 * `jisho_delay`, even with `jisho_enabled` stored) and the field map names the blank Language key.
 */
class LegacyJapaneseSnapshotTest {
    @Test
    fun `an upgraded Japanese store with no stash sends the snapshot it sent before languages`() {
        assertEquals(POPULATED_GOLDEN, encodedRun(POPULATED_STORE))
    }

    @Test
    fun `a fresh store sends the snapshot it sent before languages`() {
        assertEquals(FRESH_GOLDEN, encodedRun(preferencesOf()))
    }

    private fun encodedRun(store: Preferences): String {
        val settings =
            DataStoreAppSettingsRepository.decodePreferences(
                DataStoreAppSettingsRepository.migratePreferences(store),
            )
        val snapshot =
            EngineSettingsSnapshotMapper.map(
                settings,
                installedDictionaryIds = listOf("jitendex", "jmdict", "new-dict"),
                installedFrequencyIds = listOf("jpdb"),
                installedPitchIds = listOf("kanjium"),
                installedAudioPackIds = listOf("nhk16"),
                availableWordsetIds = AppSettings.DEFAULT_ENABLED_WORDSETS,
                blacklistPath = "/data/files/word_lists/blacklist.txt",
                whitelistPath = "/data/files/word_lists/whitelist.txt",
                avifNameable = true,
            )
        // Canonical JSON of the snapshot as built, sorted the way the run encoder sorts it. The run
        // encoder itself is not used because it refuses the blank note type of a fresh store.
        val output = StringWriter()
        JsonFactory().createGenerator(output).use { generator ->
            generator.writeStartObject()
            generator.writeFieldName("settings")
            write(generator, BridgeJsonValue.ObjectValue(snapshot.settings))
            generator.writeFieldName("androidTtsEnabled")
            snapshot.androidTtsEnabled?.let(generator::writeBoolean) ?: generator.writeNull()
            generator.writeEndObject()
        }
        return output.toString()
    }

    private fun write(
        generator: JsonGenerator,
        value: BridgeJsonValue,
    ) {
        when (value) {
            BridgeJsonValue.Null -> generator.writeNull()
            is BridgeJsonValue.Bool -> generator.writeBoolean(value.value)
            is BridgeJsonValue.Integer -> generator.writeNumber(value.value)
            is BridgeJsonValue.Decimal -> generator.writeNumber(value.value)
            is BridgeJsonValue.Text -> generator.writeString(value.value)
            is BridgeJsonValue.ArrayValue -> {
                generator.writeStartArray()
                value.values.forEach { write(generator, it) }
                generator.writeEndArray()
            }
            is BridgeJsonValue.ObjectValue -> {
                generator.writeStartObject()
                value.values.toSortedMap().forEach { (key, child) ->
                    generator.writeFieldName(key)
                    write(generator, child)
                }
                generator.writeEndObject()
            }
        }
    }

    private companion object {
        /** A schema-3 store as the previous release wrote it: no language and nothing parked. */
        val POPULATED_STORE =
            preferencesOf(
                intPreferencesKey("settings_schema_version") to 3,
                stringPreferencesKey("wordset_defaults_policy") to "preserved-existing-v1",
                booleanPreferencesKey("setup_wizard_seen") to true,
                stringPreferencesKey("theme_mode") to "light",
                stringPreferencesKey("deck_name") to "Japanese",
                stringPreferencesKey("excluded_decks_v1") to "deck-list-v1\nJapanese::Known\n",
                stringPreferencesKey("note_type") to "Lapis",
                stringPreferencesKey("field_map_v1") to "field-map-v1\nword=Expression\nsentence=Sentence\n",
                stringPreferencesKey("card_type") to "click",
                stringPreferencesKey("card_type_marker_field") to "IsClickCard",
                stringPreferencesKey("tags") to "mined japanese",
                doublePreferencesKey("audio_padding_seconds") to 0.25,
                doublePreferencesKey("screenshot_offset_seconds") to 0.5,
                booleanPreferencesKey("screenshot_animated_enabled") to true,
                doublePreferencesKey("screenshot_animated_duration_seconds") to 2.5,
                intPreferencesKey("screenshot_animated_quality") to 40,
                booleanPreferencesKey("screenshot_animated_match_audio") to false,
                doublePreferencesKey("subtitle_offset_seconds") to -0.3,
                stringPreferencesKey("audio_format") to "opus",
                intPreferencesKey("audio_bitrate_kbps") to 96,
                stringPreferencesKey("subtitle_regex_filter") to """\(.*?\)""",
                stringPreferencesKey("subtitle_regex_replacement") to "",
                booleanPreferencesKey("use_subtitle_regex_filter") to true,
                booleanPreferencesKey("use_blacklist") to true,
                booleanPreferencesKey("use_whitelist") to false,
                booleanPreferencesKey("use_known_words_database") to true,
                booleanPreferencesKey("exclude_hiragana_only") to true,
                booleanPreferencesKey("exclude_katakana_only") to false,
                booleanPreferencesKey("bold_target") to true,
                booleanPreferencesKey("deduplicate_sentences") to false,
                booleanPreferencesKey("use_i_plus_one") to true,
                doublePreferencesKey("max_sentence_duration_seconds") to 8.0,
                intPreferencesKey("max_sentence_characters") to 48,
                intPreferencesKey("reading_minimum_occurrence") to 2,
                intPreferencesKey("max_frequency_rank") to 9000,
                intPreferencesKey("min_frequency_rank") to 500,
                booleanPreferencesKey("frequency_keep_unranked") to true,
                booleanPreferencesKey("known_words_match_kana_variants") to false,
                booleanPreferencesKey("strict_card_order") to true,
                booleanPreferencesKey("merge_incomplete_cues") to true,
                booleanPreferencesKey("secondary_subtitle_enabled") to true,
                stringPreferencesKey("pitch_category_format") to "romaji",
                intPreferencesKey("max_parallel_workers") to 3,
                stringPreferencesKey("dictionary_sources_v1") to "resource-selection-v1\n+jitendex\n-jmdict\n",
                stringPreferencesKey("frequency_sources_v1") to "resource-selection-v1\n+jpdb\n",
                stringPreferencesKey("pitch_sources_v1") to "resource-selection-v1\n+kanjium\n",
                stringPreferencesKey("audio_packs_v1") to "resource-selection-v1\n+nhk16\n",
                stringPreferencesKey("enabled_wordsets_v2") to "enabled-wordsets-v1\nsurnames\n",
                booleanPreferencesKey("reading_tts_enabled") to true,
                booleanPreferencesKey("jisho_enabled") to true,
            )

        const val POPULATED_GOLDEN =
            """{"settings":{"anki_deck_name":"Japanese","anki_fields":{"audio":"","definition":"","expression_audio":"","expression_furigana":"","expression_reading":"","frequency":"","frequency_sort":"","glossary":"","language":"","picture":"","pitch_category":"","pitch_graph":"","pitch_position":"","pitch_text":"","sentence":"Sentence","sentence_furigana":"","sentence_reading":"","sentence_translation":"","source":"","word":"Expression"},"anki_note_type":"Lapis","anki_tags":"mined japanese","audio_bitrate":96,"audio_format":"opus","audio_padding":0.25,"blacklist_path":"/data/files/word_lists/blacklist.txt","bold_target_in_sentence":true,"card_type":"click","card_type_marker_fields":{"audio":"","click":"IsClickCard","sentence":"","word_and_sentence":""},"deduplicate_sentences":false,"dictionary_chain":[{"dict_id":"jitendex","enabled":true,"kind":"indexed"},{"dict_id":"jmdict","enabled":false,"kind":"indexed"},{"dict_id":"new-dict","enabled":true,"kind":"indexed"}],"exclude_hiragana_only_words":true,"exclude_katakana_only_words":false,"excluded_decks":["Japanese::Known"],"excluded_wordsets":["surnames"],"expression_audio_chain":[{"enabled":true,"kind":"pack","pack_id":"nhk16"}],"frequency_chain":[{"enabled":true,"source_id":"jpdb"}],"frequency_keep_unranked":true,"known_words_match_kana_variants":false,"max_frequency_rank":9000,"max_parallel_workers":3,"max_sentence_chars":48,"max_sentence_duration_seconds":8.0,"merge_incomplete_cues":true,"min_frequency_rank":500,"pitch_category_format":"romaji","pitch_chain":[{"enabled":true,"source_id":"kanjium"}],"reading_min_occurrence":2,"screenshot_animated":true,"screenshot_animated_clip_duration":2.5,"screenshot_animated_format":"avif","screenshot_animated_match_audio":false,"screenshot_animated_quality":40,"screenshot_offset":0.5,"strict_card_order":true,"subtitle_offset":-0.3,"subtitle_regex_filter":"\\(.*?\\)","subtitle_regex_replacement":"","use_blacklist":true,"use_i_plus_one_filter":true,"use_known_words_db":true,"use_subtitle_regex_filter":true,"use_whitelist":false},"androidTtsEnabled":true}"""
        const val FRESH_GOLDEN =
            """{"settings":{"anki_deck_name":"Anki Miner","anki_fields":{"audio":"","definition":"","expression_audio":"","expression_furigana":"","expression_reading":"","frequency":"","frequency_sort":"","glossary":"","language":"","picture":"","pitch_category":"","pitch_graph":"","pitch_position":"","pitch_text":"","sentence":"","sentence_furigana":"","sentence_reading":"","sentence_translation":"","source":"","word":""},"anki_note_type":"","anki_tags":"auto-mined","card_type":"","card_type_marker_fields":{"audio":"","click":"","sentence":"","word_and_sentence":""},"deduplicate_sentences":false,"dictionary_chain":[{"dict_id":"jitendex","enabled":true,"kind":"indexed"},{"dict_id":"jmdict","enabled":true,"kind":"indexed"},{"dict_id":"new-dict","enabled":true,"kind":"indexed"}],"excluded_decks":[],"excluded_wordsets":["surnames","given-names","place-names","org-product"],"expression_audio_chain":[{"enabled":true,"kind":"pack","pack_id":"nhk16"}],"frequency_chain":[{"enabled":true,"source_id":"jpdb"}],"pitch_chain":[{"enabled":true,"source_id":"kanjium"}],"screenshot_animated":false},"androidTtsEnabled":false}"""
    }
}
