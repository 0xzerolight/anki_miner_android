package com.ankiminer.android.service

import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MiningForegroundSessionTest {
    @Test
    fun `foreground progress carries counts only`() {
        // Static fields are compiler-generated (Compose adds `$stable`); only instance state matters.
        val fieldTypes =
            MiningForegroundProgress::class.java.declaredFields
                .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
                .associate { it.name to it.type }

        assertEquals(
            "MiningForegroundProgress must not gain a text channel; engine descriptions name " +
                "mined terms and notifications can appear on a locked device",
            mapOf(
                "completed" to Integer::class.java,
                "total" to Integer::class.java,
            ),
            fieldTypes,
        )
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
