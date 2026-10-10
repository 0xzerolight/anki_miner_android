package com.ankiminer.android.ui.settings

import androidx.annotation.StringRes
import com.ankiminer.android.R

/**
 * One built-in subtitle filter pattern the user can append to their own.
 *
 * Patterns are copied verbatim from desktop `SUBTITLE_REGEX_PRESETS`
 * (`gui/widgets/panels/sentences_settings_panel.py`) and are pinned by `SubtitleRegexPresetsTest`.
 * They are also the pieces the cleanup box adds for a language without a pattern of its own.
 * They are Python `re` source, so they must not be rewritten into `java.util.regex` idioms.
 */
internal data class SubtitleRegexPreset(
    @param:StringRes val label: Int,
    val pattern: String,
)

internal val SUBTITLE_REGEX_PRESETS =
    listOf(
        SubtitleRegexPreset(R.string.settings_subtitle_preset_parens, """\([^)]*\)|（[^）]*）"""),
        SubtitleRegexPreset(R.string.settings_subtitle_preset_brackets, """\[[^\]]*\]|［[^］]*］"""),
        SubtitleRegexPreset(R.string.settings_subtitle_preset_music, """[♪♬♫#～〜]+"""),
        SubtitleRegexPreset(R.string.settings_subtitle_preset_speaker, """^[^「『:：]+[:：]\s*"""),
        // A dash that opens a speaker turn ("- Hi. - Hello."): at the line start or after a sentence
        // terminator. Hyphenated words and a mid-sentence dash are untouched.
        SubtitleRegexPreset(
            R.string.settings_subtitle_preset_dialogue_dash,
            """(?:^|(?<=[.!?…]\s))[-–—]\s+""",
        ),
    )

/**
 * Desktop's `_append_preset`: an empty field takes the pattern outright, a pattern already present
 * is a no-op (so a double tap cannot duplicate an alternative), and anything else is appended as one
 * more alternation branch.
 */
internal fun appendSubtitleRegexPreset(
    current: String,
    pattern: String,
): String =
    when {
        current.isEmpty() -> pattern
        current.contains(pattern) -> current
        else -> "$current|$pattern"
    }
