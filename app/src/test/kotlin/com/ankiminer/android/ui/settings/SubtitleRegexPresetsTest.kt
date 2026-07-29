package com.ankiminer.android.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleRegexPresetsTest {
    @Test
    fun presetPatternsMatchDesktopVerbatim() {
        // Desktop SUBTITLE_REGEX_PRESETS, gui/widgets/panels/filtering_settings_panel.py. Python
        // regex source: a "fix" toward java.util.regex idioms would silently change what the engine
        // strips.
        assertEquals(
            listOf(
                """\([^)]*\)|（[^）]*）""",
                """\[[^\]]*\]|［[^］]*］""",
                """[♪♬♫#～〜]+""",
                """^[^「『:：]+[:：]\s*""",
            ),
            SUBTITLE_REGEX_PRESETS.map { it.pattern },
        )
    }

    @Test
    fun appendingBuildsOneAlternationAndIgnoresRepeats() {
        val parens = SUBTITLE_REGEX_PRESETS[0].pattern
        val brackets = SUBTITLE_REGEX_PRESETS[1].pattern

        val first = appendSubtitleRegexPreset("", parens)
        val second = appendSubtitleRegexPreset(first, brackets)

        assertEquals(parens, first)
        assertEquals("$parens|$brackets", second)
        // A second tap on an already-present preset is a no-op, not a duplicated branch.
        assertEquals(second, appendSubtitleRegexPreset(second, parens))
    }
}
