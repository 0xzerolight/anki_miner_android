package com.ankiminer.android.ui.mining

import com.ankiminer.android.data.settings.LanguageProfileFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiningContentStyleTest {
    @Test
    fun `hebrew renders right to left in its own locale with its own track codes`() {
        val hebrew = MiningContentStyle.forLanguage("he", LanguageProfileFixtures.all)

        assertTrue(hebrew.rtl)
        assertEquals("he", hebrew.contentLanguage)
        assertEquals(listOf("he", "heb", "hebrew", "iw"), hebrew.audioTrackCodes)
        assertEquals("<div lang=\"he\" dir=\"rtl\">x</div>", hebrew.wrapDefinitionHtml("x"))
    }

    @Test
    fun `japanese keeps its direction and its engine track codes before the profiles load`() {
        val japanese = MiningContentStyle.forLanguage("ja", emptyList())

        assertFalse(japanese.rtl)
        assertEquals(setOf("jpn", "ja", "japanese", "jp"), japanese.audioTrackCodes.toSet())
        assertEquals("<div lang=\"ja\">x</div>", japanese.wrapDefinitionHtml("x"))
    }
}
