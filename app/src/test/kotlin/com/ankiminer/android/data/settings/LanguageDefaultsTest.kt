package com.ankiminer.android.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the Sentences and Word filters rows show for a language-scoped setting the user never set. */
class LanguageDefaultsTest {
    @Test
    fun `an unset scoped setting shows the loaded profile's default, not Japanese's`() {
        val shown = LanguageDefaults.orJapanese(LanguageDefaults.forLanguage("he", LanguageProfileFixtures.all))

        assertEquals("he", shown.code)
        // A he run filters subtitles with the profile's pattern while the toggle is unset.
        assertTrue(shown.useSubtitleRegexFilter)
        assertNotEquals(EngineDefaults.USE_SUBTITLE_REGEX_FILTER, shown.useSubtitleRegexFilter)
        assertTrue(shown.subtitleRegexFilter.isNotEmpty())
    }

    @Test
    fun `japanese, and a language whose profile is still loading, show the engine defaults`() {
        assertEquals(
            LanguageDefaults.JAPANESE,
            LanguageDefaults.orJapanese(LanguageDefaults.forLanguage("ja", LanguageProfileFixtures.all)),
        )
        assertEquals(
            LanguageDefaults.JAPANESE,
            LanguageDefaults.orJapanese(LanguageDefaults.forLanguage("he", emptyList())),
        )
        assertEquals(EngineDefaults.USE_SUBTITLE_REGEX_FILTER, LanguageDefaults.JAPANESE.useSubtitleRegexFilter)
        assertEquals(EngineDefaults.SUBTITLE_REGEX_FILTER, LanguageDefaults.JAPANESE.subtitleRegexFilter)
    }
}
