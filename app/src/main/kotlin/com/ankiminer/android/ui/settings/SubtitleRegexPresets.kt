package com.ankiminer.android.ui.settings

import androidx.annotation.StringRes
import com.ankiminer.android.R

/**
 * One built-in subtitle filter pattern the user can append to their own.
 *
 * Patterns are copied verbatim from desktop `SUBTITLE_REGEX_PRESETS`
 * (`gui/widgets/panels/filtering_settings_panel.py`) and are pinned by `SubtitleRegexPresetsTest`.
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
