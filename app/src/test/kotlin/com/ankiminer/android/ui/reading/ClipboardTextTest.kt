package com.ankiminer.android.ui.reading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClipboardTextTest {
    private val htmlMustNotBeRead: (String) -> CharSequence = { error("HTML must not be converted") }

    @Test
    fun `plain text wins over the HTML copy`() {
        assertEquals("猫を見る", clipItemPasteText("猫を見る", "<b>猫</b>を見る", htmlMustNotBeRead))
    }

    @Test
    fun `an HTML-only item pastes its plain text rather than its markup`() {
        val pasted = clipItemPasteText(null, "<b>猫</b>を見る") { it.replace(Regex("<[^>]+>"), "") }

        assertEquals("猫を見る", pasted)
    }

    @Test
    fun `a URI-only item has no paste text`() {
        assertNull(clipItemPasteText(null, null, htmlMustNotBeRead))
    }

    @Test
    fun `an empty item has no paste text`() {
        assertNull(clipItemPasteText("", null, htmlMustNotBeRead))
        assertNull(clipItemPasteText(null, "<p></p>") { "" })
    }
}
