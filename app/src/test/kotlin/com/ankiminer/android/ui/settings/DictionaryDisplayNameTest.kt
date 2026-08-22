package com.ankiminer.android.ui.settings

import com.ankiminer.android.data.resources.InstalledDictionary
import org.junit.Assert.assertEquals
import org.junit.Test

class DictionaryDisplayNameTest {
    @Test
    fun `an installed slot resolves to its imported title`() {
        assertEquals(
            "Jitendex.org [2025-06-23]",
            dictionaryDisplayName("jitendex", listOf(dictionary("jitendex", name = "Jitendex.org [2025-06-23]"))),
        )
    }

    @Test
    fun `a missing slot falls back to the slot id`() {
        assertEquals(
            "custom-a1b2c3d4",
            dictionaryDisplayName("custom-a1b2c3d4", listOf(dictionary("jitendex", name = "Jitendex"))),
        )
    }

    @Test
    fun `a blank title falls back to the slot id`() {
        assertEquals("jitendex", dictionaryDisplayName("jitendex", listOf(dictionary("jitendex", name = " "))))
    }

    private fun dictionary(
        slotId: String,
        name: String,
    ) = InstalledDictionary(
        slotId = slotId,
        occupied = true,
        valid = true,
        sourceName = name,
        sourceRevision = "1",
        format = "yomitan",
        entryCount = 1_000L,
        schemaOk = true,
        embeddedAttribution = emptyMap(),
        catalogResourceId = slotId,
        attribution = emptyList(),
        rebuildSourcePath = null,
    )
}
