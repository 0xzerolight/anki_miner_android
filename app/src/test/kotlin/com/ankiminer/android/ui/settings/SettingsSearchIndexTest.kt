package com.ankiminer.android.ui.settings

import com.ankiminer.android.R
import com.ankiminer.android.data.resources.InstalledDictionary
import com.ankiminer.android.vm.SetupUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchIndexTest {
    @Test
    fun `every id is unique`() {
        val ids = SETTINGS_SEARCH_INDEX.map { it.id }

        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `every entry points at a card its own category emits`() {
        SETTINGS_SEARCH_INDEX.forEach { entry ->
            assertTrue(
                entry.id,
                entry.cardKey in SETTINGS_CARD_KEYS.getValue(entry.category),
            )
        }
    }

    @Test
    fun `every category is represented`() {
        assertEquals(
            SettingsCategory.entries.toSet(),
            SETTINGS_SEARCH_INDEX.map { it.category }.toSet(),
        )
    }

    @Test
    fun `an id names its category so a moved entry is visible in review`() {
        SETTINGS_SEARCH_INDEX.forEach { entry ->
            assertTrue(
                entry.id,
                entry.id.startsWith("${entry.category.name.lowercase()}."),
            )
        }
    }

    @Test
    fun `custom dictionary search has no removed slot-picker detail`() {
        val custom = SETTINGS_SEARCH_INDEX.single { it.id == "dictionaries.custom" }

        assertEquals(null, custom.detail)
    }

    @Test
    fun `tags search detail explains that blank means no tags`() {
        val tags = SETTINGS_SEARCH_INDEX.single { it.id == "anki.tags" }

        assertEquals(R.string.settings_tags_help, tags.detail)
    }

    @Test
    fun `target deck search points to the card containing the deck field`() {
        val targetDeck = SETTINGS_SEARCH_INDEX.single { it.id == "anki.target_deck" }

        assertEquals("anki-deck-options", targetDeck.cardKey)
    }

    @Test
    fun `theme search uses the displayed theme mode label`() {
        val theme = SETTINGS_SEARCH_INDEX.single { it.id == "ui.theme" }

        assertEquals(R.string.settings_theme_mode, theme.title)
    }

    @Test
    fun `conditional entries follow the controls emitted for current state`() {
        val unavailable =
            availableSettingsSearchEntries(
                entries = SETTINGS_SEARCH_INDEX,
                setup = SetupUiState(uniDicInstalled = true),
                dynamicColorSupported = false,
            ).map(SettingsSearchEntry::id)

        assertFalse("diagnostics.unidic" in unavailable)
        assertFalse("dictionaries.lookup_test" in unavailable)
        assertFalse("ui.dynamic_color" in unavailable)

        val available =
            availableSettingsSearchEntries(
                entries = SETTINGS_SEARCH_INDEX,
                setup =
                    SetupUiState(
                        uniDicInstalled = false,
                        dictionaries = listOf(installedDictionary()),
                    ),
                dynamicColorSupported = true,
            ).map(SettingsSearchEntry::id)

        assertTrue("diagnostics.unidic" in available)
        assertTrue("dictionaries.lookup_test" in available)
        assertTrue("ui.dynamic_color" in available)
    }

    private fun installedDictionary() =
        InstalledDictionary(
            slotId = "jmdict",
            occupied = true,
            valid = true,
            sourceName = "JMdict",
            sourceRevision = "1",
            format = "yomitan",
            entryCount = 1,
            schemaOk = true,
            embeddedAttribution = emptyMap(),
            catalogResourceId = "jmdict",
            attribution = emptyList(),
            rebuildSourcePath = null,
        )
}
