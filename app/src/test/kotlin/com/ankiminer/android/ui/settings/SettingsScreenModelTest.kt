package com.ankiminer.android.ui.settings

import com.ankiminer.android.data.anki.AnkiSetupFailureOrigin
import com.ankiminer.android.data.resources.ResourceFailureOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsScreenModelTest {
    @Test
    fun categoryOrderMatchesTheApprovedSettingsInformationArchitecture() {
        assertEquals(
            listOf(
                SettingsCategory.ANKI,
                SettingsCategory.MEDIA,
                SettingsCategory.DICTIONARIES,
                SettingsCategory.AUDIO,
                SettingsCategory.FREQUENCY,
                SettingsCategory.FILTERING,
                SettingsCategory.UI,
                SettingsCategory.DIAGNOSTICS,
            ),
            SettingsCategory.entries,
        )
    }

    @Test
    fun stableFailureOriginsRouteToOneSettingsCategory() {
        assertEquals(SettingsCategory.DIAGNOSTICS, settingsCategoryFor(ResourceFailureOrigin.SETUP))
        assertEquals(SettingsCategory.DIAGNOSTICS, settingsCategoryFor(ResourceFailureOrigin.UNIDIC))
        assertEquals(
            SettingsCategory.DICTIONARIES,
            settingsCategoryFor(ResourceFailureOrigin.CATALOG_DICTIONARY),
        )
        assertEquals(
            SettingsCategory.DICTIONARIES,
            settingsCategoryFor(ResourceFailureOrigin.CUSTOM_DICTIONARY),
        )
        assertEquals(
            SettingsCategory.DICTIONARIES,
            settingsCategoryFor(ResourceFailureOrigin.PITCH),
        )
        assertEquals(
            SettingsCategory.DICTIONARIES,
            settingsCategoryFor(ResourceFailureOrigin.DICTIONARY_LOOKUP),
        )
        assertEquals(SettingsCategory.AUDIO, settingsCategoryFor(ResourceFailureOrigin.AUDIO))
        assertEquals(
            SettingsCategory.FREQUENCY,
            settingsCategoryFor(ResourceFailureOrigin.FREQUENCY),
        )
        assertEquals(
            SettingsCategory.FILTERING,
            settingsCategoryFor(ResourceFailureOrigin.KNOWN_WORDS),
        )
        assertEquals(
            SettingsCategory.FILTERING,
            settingsCategoryFor(ResourceFailureOrigin.WORD_LIST),
        )
        assertEquals(SettingsCategory.ANKI, settingsCategoryFor(AnkiSetupFailureOrigin.TARGET))
        assertEquals(SettingsCategory.ANKI, settingsCategoryFor(AnkiSetupFailureOrigin.RECOVERY))
        // These are constants only because every conditional card is emitted after the last
        // deep-link target in its category. dictionary-lookup precedes dictionary-inventory for
        // exactly that reason; swapping them back makes this index depend on inventory visibility.
        // SETUP has no owning card; it renders in the shared header, which is lazy item 0.
        assertEquals(0, settingsCardIndexFor(ResourceFailureOrigin.SETUP))
        // Diagnostics: diagnostic-runtime(2), unidic(3).
        assertEquals(3, settingsCardIndexFor(ResourceFailureOrigin.UNIDIC))
        assertEquals(2, settingsCardIndexFor(ResourceFailureOrigin.CATALOG_DICTIONARY))
        assertEquals(3, settingsCardIndexFor(ResourceFailureOrigin.CUSTOM_DICTIONARY))
        assertEquals(4, settingsCardIndexFor(ResourceFailureOrigin.PITCH))
        assertEquals(6, settingsCardIndexFor(ResourceFailureOrigin.DICTIONARY_LOOKUP))
        assertEquals(3, settingsCardIndexFor(ResourceFailureOrigin.AUDIO))
        assertEquals(3, settingsCardIndexFor(ResourceFailureOrigin.FREQUENCY))
        assertEquals(3, settingsCardIndexFor(ResourceFailureOrigin.KNOWN_WORDS))
        // Filtering: word-lists sits after known-words-import, ahead of the conditional
        // filtering-import-result card.
        assertEquals(4, settingsCardIndexFor(ResourceFailureOrigin.WORD_LIST))
        assertEquals(3, settingsCardIndexFor(AnkiSetupFailureOrigin.TARGET))
        assertEquals(4, settingsCardIndexFor(AnkiSetupFailureOrigin.RECOVERY))
    }

    @Test
    fun savedExcludedDeckAbsentFromLiveDiscoveryRemainsCheckedAndVisible() {
        val choices =
            excludedDeckChoices(
                availableDecks = listOf("Live", "Shared"),
                excludedDecks = listOf("Saved::Gone", "Shared"),
            )

        assertEquals(listOf("Live", "Saved::Gone", "Shared"), choices.map { it.name })
        val saved = choices.single { it.name == "Saved::Gone" }
        assertTrue(saved.checked)
        assertFalse(saved.discovered)
    }
}
