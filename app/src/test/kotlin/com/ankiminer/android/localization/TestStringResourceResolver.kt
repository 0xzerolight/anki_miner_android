package com.ankiminer.android.localization

import com.ankiminer.android.R

internal val testStringResourceResolver =
    StringResourceResolver { resourceId, formatArguments ->
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
                "No dictionary entry for ${formatArguments[0]} word(s), " +
                    "so no card was made: ${formatArguments[1]}"
            R.string.setup_default_frequency_name -> "Imported frequency"
            R.string.setup_default_pitch_name -> "Imported pitch accent"
            else ->
                if (formatArguments.isEmpty()) {
                    "resource:$resourceId"
                } else {
                    "resource:$resourceId:" + formatArguments.joinToString(",")
                }
        }
    }
