package com.ankiminer.android.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class MiningForegroundSessionTest {
    @Test
    fun `notification message replaces control and format characters`() {
        assertEquals(
            "phase  details",
            sanitizeNotificationProgressMessage("phase\n\u202edetails"),
        )
    }

    @Test
    fun `notification message truncation never splits a surrogate pair`() {
        val sanitized = sanitizeNotificationProgressMessage("a".repeat(511) + "\ud83d\ude80")

        assertEquals(511, sanitized.length)
        assertFalse(Character.isSurrogate(sanitized.last()))
    }

    @Test
    fun `determinate progress requires a complete valid pair`() {
        assertThrows(IllegalArgumentException::class.java) {
            MiningForegroundProgress(completed = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MiningForegroundProgress(completed = 2, total = 1)
        }

        assertEquals(MiningForegroundProgress(1, 2), MiningForegroundProgress(1, 2))
    }
}
