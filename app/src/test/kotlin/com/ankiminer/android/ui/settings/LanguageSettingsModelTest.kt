package com.ankiminer.android.ui.settings

import androidx.compose.runtime.saveable.SaverScope
import com.ankiminer.android.data.resources.InstalledAudioPack
import com.ankiminer.android.data.resources.ResourceManagerState
import com.ankiminer.android.data.settings.LanguageProfileFixtures
import com.ankiminer.android.vm.SetupUiState
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageSettingsModelTest {
    private val profiles = LanguageProfileFixtures.all

    @Test
    fun `a switch routes to the note type only once it lands without one`() {
        assertEquals(NoteTypeRoute.WAIT, noteTypeRouteAfterSwitch(null, "he", null))
        // The settings still hold the outgoing language: the switch has not landed.
        assertEquals(NoteTypeRoute.WAIT, noteTypeRouteAfterSwitch("he", "ja", "Lapis"))
        // Every non-Japanese first visit starts without a note type.
        assertEquals(NoteTypeRoute.ROUTE, noteTypeRouteAfterSwitch("he", "he", null))
        assertEquals(NoteTypeRoute.ROUTE, noteTypeRouteAfterSwitch("he", "he", ""))
        // A return visit restores the parked note type.
        assertEquals(NoteTypeRoute.DONE, noteTypeRouteAfterSwitch("ja", "ja", "Lapis"))
    }

    @Test
    fun `a note-type jump runs once and not again after the screen is restored`() {
        val jumps = NoteTypeJumpRequests()
        assertFalse(jumps.take())

        jumps.request()
        assertTrue(jumps.take())
        assertFalse(jumps.take())

        // Returning to Settings or recreating the activity restores the saved counters.
        val saved = with(NoteTypeJumpRequests.Saver) { SaverScope { true }.save(jumps) }
        val restored = NoteTypeJumpRequests.Saver.restore(requireNotNull(saved))!!
        assertFalse(restored.take())

        restored.request()
        assertTrue(restored.take())
    }

    @Test
    fun `pitch and jisho are Japanese and tone colour is nobody's yet`() {
        val japanese = LanguageSettingsState(activeCode = "ja", profiles = profiles)
        val hebrew = LanguageSettingsState(activeCode = "he", profiles = profiles)

        assertTrue(japanese.showsPitch)
        assertTrue(japanese.offersJisho)
        assertFalse(japanese.showsToneColor)
        assertFalse(hebrew.showsPitch)
        assertFalse(hebrew.offersJisho)
        assertFalse(hebrew.showsToneColor)
        // Before the bridge answers, Japanese keeps its pitch card.
        assertTrue(LanguageSettingsState().showsPitch)
        assertFalse(LanguageSettingsState(activeCode = "he").showsPitch)
    }

    @Test
    fun `a tone colour profile shows the toggle`() {
        val tonal =
            LanguageProfileFixtures.hebrew.copy(code = "zh", capabilities = setOf("tone_color"))

        assertTrue(LanguageSettingsState(activeCode = "zh", profiles = listOf(tonal)).showsToneColor)
    }

    @Test
    fun `an open known-words preview or a running operation blocks a switch`() {
        assertTrue(LanguageSettingsState(profiles = profiles).switchAllowed)
        assertFalse(LanguageSettingsState(profiles = profiles, knownWordsPreviewOpen = true).switchAllowed)
        assertFalse(LanguageSettingsState(profiles = profiles, busy = true).switchAllowed)
        assertFalse(LanguageSettingsState(profiles = profiles, downloadingCode = "ar").switchAllowed)
    }

    @Test
    fun `languages are listed by the interface language's names`() {
        val english = orderedLanguageChoices(profiles, Locale.ENGLISH, "he").map { it.code }
        assertEquals(listOf("ar", "he", "ja"), english)
        assertEquals("Hebrew", languageDisplayName(LanguageProfileFixtures.hebrew, Locale.ENGLISH))
        assertEquals("Hebräisch", languageDisplayName(LanguageProfileFixtures.hebrew, Locale.GERMAN))
    }

    @Test
    fun `with no profiles the active language alone is listed by its names`() {
        val only = orderedLanguageChoices(emptyList(), Locale.ENGLISH, "he").single()

        assertEquals("he", only.code)
        assertEquals("Hebrew", languageDisplayName(only, Locale.ENGLISH))
        assertEquals("עברית", only.displayName)
        assertEquals(null, only.unavailableReason)
    }

    @Test
    fun `other languages' slots are named per panel`() {
        val hebrewPack = InstalledAudioPack("forvo-he", "Forvo", "ajt", 1, true, language = "he")
        val japanesePack = InstalledAudioPack("jpod", "JapanesePod", "ajt", 1, true)
        val state = ResourceManagerState(audioPacks = listOf(hebrewPack, japanesePack))

        assertEquals(listOf("JapanesePod" to "ja"), state.otherLanguageSlots("he").audio)
        assertEquals(listOf("Forvo" to "he"), state.otherLanguageSlots("ja").audio)
    }

    @Test
    fun `search hides the pitch and jisho rows of a language without them`() {
        val hebrew = LanguageSettingsState(activeCode = "he", profiles = profiles)
        val ids =
            availableSettingsSearchEntries(SETTINGS_SEARCH_INDEX, SetupUiState(), false, hebrew).map { it.id }

        assertFalse("resources.pitch_chain" in ids)
        assertFalse("resources.jisho" in ids)
        assertTrue("language.mining_language" in ids)
        assertFalse("language.reading_tone_color" in ids)
    }

    @Test
    fun `search finds the device voice only where it speaks`() {
        fun ids(code: String) =
            availableSettingsSearchEntries(
                SETTINGS_SEARCH_INDEX,
                SetupUiState(),
                false,
                LanguageSettingsState(activeCode = code, profiles = profiles),
            ).map { it.id }

        assertTrue("resources.device_voice" in ids("he"))
        assertFalse("resources.device_voice" in ids("ja"))
    }
}
