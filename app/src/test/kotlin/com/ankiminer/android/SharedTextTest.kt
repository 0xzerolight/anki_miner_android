package com.ankiminer.android

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedTextTest {
    @Test
    fun selectionMenuAndPlainTextShareAreAccepted() {
        assertEquals("猫", sharedTextFrom(Intent.ACTION_PROCESS_TEXT, "text/plain", "猫", null))
        assertEquals("犬", sharedTextFrom(Intent.ACTION_SEND, "text/plain", null, "犬"))
    }

    @Test
    fun otherIntentsAndBlankTextAreIgnored() {
        assertNull(sharedTextFrom(Intent.ACTION_SEND, "image/png", null, "x"))
        assertNull(sharedTextFrom(Intent.ACTION_MAIN, null, null, null))
        assertNull(sharedTextFrom(Intent.ACTION_PROCESS_TEXT, "text/plain", "  ", null))
    }
}
