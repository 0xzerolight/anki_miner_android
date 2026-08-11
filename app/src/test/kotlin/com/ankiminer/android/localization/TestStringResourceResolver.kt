package com.ankiminer.android.localization

import com.ankiminer.android.R
import java.util.Locale

internal val testStringResourceResolver =
    StringResourceResolver { resourceId, formatArguments ->
        val localizedArguments =
            localizeFormatArguments(formatArguments, Locale.ROOT) { nestedResourceId ->
                when (nestedResourceId) {
                    R.string.b3_settings_category_audio -> "Audio"
                    R.string.wizard_dictionary_title -> "Dictionary"
                    R.string.b3_settings_category_frequency -> "Frequency"
                    R.string.pitch_import_title -> "Import pitch accent"
                    R.string.known_words_import_title -> "Import known words"
                    R.string.word_lists_title -> "Word lists"
                    else -> "resource:$nestedResourceId"
                }
            }
        when (resourceId) {
            R.string.mining_failure_background_start_unsafe ->
                "Background mining did not start safely"
            R.string.mining_failure_background_stopped ->
                "Background mining stopped unexpectedly"
            R.string.mining_failure_terminal_disagreement ->
                "Python terminal callback and return value disagreed"
            R.string.mining_failure_anki_cleanup_incomplete ->
                "Anki cleanup remained incomplete"
            R.string.mining_failure_restart_required ->
                "Restart the app before starting another mining run"
            R.string.mining_failure_tokenizer_required ->
                "Install the Japanese tokenizer resource before mining"
            R.string.mining_admission_recovery_required ->
                "Anki recovery must be resolved before another mining run"
            R.string.mining_notice_no_definition ->
                "No dictionary entry for ${localizedArguments[0]} word(s), " +
                    "so no card was made: ${localizedArguments[1]}"
            R.string.setup_default_frequency_name -> "Imported frequency"
            R.string.setup_default_pitch_name -> "Imported pitch accent"
            else ->
                if (localizedArguments.isEmpty()) {
                    "resource:$resourceId"
                } else {
                    "resource:$resourceId:" + localizedArguments.joinToString(",")
                }
        }
    }
