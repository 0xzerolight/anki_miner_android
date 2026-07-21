package com.ankiminer.android.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsScreenModelTest {
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
