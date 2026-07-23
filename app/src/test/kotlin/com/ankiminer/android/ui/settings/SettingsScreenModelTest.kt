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
                SettingsCategory.SETUP,
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
        assertEquals(SettingsCategory.SETUP, settingsCategoryFor(ResourceFailureOrigin.SETUP))
        assertEquals(SettingsCategory.SETUP, settingsCategoryFor(ResourceFailureOrigin.UNIDIC))
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
        assertEquals(SettingsCategory.ANKI, settingsCategoryFor(AnkiSetupFailureOrigin.TARGET))
        assertEquals(SettingsCategory.ANKI, settingsCategoryFor(AnkiSetupFailureOrigin.RECOVERY))
        assertEquals(3, settingsCardIndexFor(ResourceFailureOrigin.CUSTOM_DICTIONARY))
        assertEquals(4, settingsCardIndexFor(ResourceFailureOrigin.PITCH))
        assertEquals(7, settingsCardIndexFor(ResourceFailureOrigin.DICTIONARY_LOOKUP))
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
