package com.ankiminer.android.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsScreenTest {
    @Test
    fun externalCategoryRequestClearsRestoredSearchBeforeTargetingTheCard() {
        val jump =
            externalSettingsCategoryJump(
                currentSearchQuery = "katakana",
                requestedCategory = SettingsCategory.FILTERING,
                requestedItemIndex = 4,
            )

        requireNotNull(jump)
        assertEquals("", jump.searchQuery)
        assertEquals("word-lists", jump.targetCardKey)
    }

    @Test
    fun absentExternalRequestProducesNoJump() {
        val jump =
            externalSettingsCategoryJump(
                currentSearchQuery = "katakana",
                requestedCategory = null,
                requestedItemIndex = 4,
            )

        assertNull(jump)
    }
}
