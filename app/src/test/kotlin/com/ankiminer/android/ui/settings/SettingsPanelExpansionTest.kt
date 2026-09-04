package com.ankiminer.android.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsPanelExpansionTest {
    @Test
    fun everyPanelStartsCollapsed() {
        val expansion = SettingsPanelExpansion()

        assertFalse(expansion.isExpanded("audio-sources"))
        assertEquals(emptyList<String>(), expansion.expandedKeys())
    }

    @Test
    fun expandingOnePanelLeavesTheOthersClosed() {
        val expansion = SettingsPanelExpansion()

        expansion.expand("frequency-sources")

        assertTrue(expansion.isExpanded("frequency-sources"))
        assertFalse(expansion.isExpanded("audio-sources"))
    }

    @Test
    fun collapsingDropsThePanelFromTheSavedSet() {
        val expansion = SettingsPanelExpansion(listOf("audio-sources", "pitch-sources"))

        expansion.setExpanded("audio-sources", false)

        assertEquals(listOf("pitch-sources"), expansion.expandedKeys())
    }

    @Test
    fun aRestoredHolderOpensTheSamePanels() {
        val saved = SettingsPanelExpansion(listOf("dictionary-sources"))

        val restored = SettingsPanelExpansion(saved.expandedKeys())

        assertTrue(restored.isExpanded("dictionary-sources"))
        assertFalse(restored.isExpanded("pitch-sources"))
    }
}
