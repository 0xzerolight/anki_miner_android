package com.ankiminer.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnkiMinerApplicationLoggingTest {
    @Test
    fun `application startup collects diagnostics settings without blocking`() {
        val source =
            File(
                projectRoot(),
                "app/src/main/kotlin/com/ankiminer/android/AnkiMinerApplication.kt",
            ).readText()
        val onCreate =
            source.substring(
                source.indexOf("override fun onCreate()"),
                source.indexOf("internal fun refreshMiningAdmission()"),
            )

        assertFalse(onCreate, onCreate.contains("runBlocking"))
        assertTrue(
            onCreate,
            Regex(
                """applicationScope\.launch\s*\{\s*diagnosticsSettings\.verboseLogging""",
            ).containsMatchIn(onCreate),
        )
        assertTrue(onCreate, onCreate.contains("distinctUntilChanged().collect"))
    }

    private fun projectRoot(): File {
        var cursor = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (!File(cursor, "settings.gradle.kts").isFile) {
            cursor = cursor.parentFile ?: error("could not find project root")
        }
        return cursor
    }
}
