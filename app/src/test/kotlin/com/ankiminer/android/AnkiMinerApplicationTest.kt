package com.ankiminer.android

import java.io.File
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class AnkiMinerApplicationTest {
    @Test
    fun `update storage failures stop at the application boundary`() =
        runTest {
            var reachedAfterFailure = false

            runUpdateOperation("update.test") {
                throw IOException("update store unavailable")
            }
            reachedAfterFailure = true

            assertTrue(reachedAfterFailure)
        }

    @Test(expected = IllegalStateException::class)
    fun `unexpected update failures still escape the application boundary`() =
        runTest {
            runUpdateOperation("update.test") {
                throw IllegalStateException("unexpected coordinator failure")
            }
        }

    @Test
    fun `every application update entry point uses the storage failure boundary`() {
        val source =
            File(
                projectRoot(),
                "app/src/main/kotlin/com/ankiminer/android/AnkiMinerApplication.kt",
            ).readText()
        val startup =
            source.section("override fun onCreate()", "internal fun refreshMiningAdmission()")
        val setEnabled =
            source.section("internal fun setUpdateCheckEnabled", "internal fun checkForUpdates")
        val checkNow =
            source.section("internal fun checkForUpdates", "internal fun skipAvailableUpdate")
        val skip =
            source.section("internal fun skipAvailableUpdate", "internal fun refreshExternalReadiness")

        assertTrue(startup, startup.contains("runUpdateOperation"))
        assertTrue(setEnabled, setEnabled.contains("runUpdateOperation"))
        assertTrue(checkNow, checkNow.contains("runUpdateOperation"))
        assertTrue(skip, skip.contains("runUpdateOperation"))
    }

    private fun String.section(
        start: String,
        end: String,
    ): String = substring(indexOf(start), indexOf(end))

    private fun projectRoot(): File {
        var cursor = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (!File(cursor, "settings.gradle.kts").isFile) {
            cursor = cursor.parentFile ?: error("could not find project root")
        }
        return cursor
    }
}
