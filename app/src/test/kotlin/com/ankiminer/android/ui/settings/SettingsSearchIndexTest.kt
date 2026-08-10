package com.ankiminer.android.ui.settings

import com.ankiminer.android.R
import org.junit.Assert.assertEquals
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
}
