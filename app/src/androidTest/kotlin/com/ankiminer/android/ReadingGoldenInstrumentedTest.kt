package com.ankiminer.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

private const val RUN_READING_GOLDEN_ARGUMENT = "ankiMinerRunReadingGolden"

@RunWith(AndroidJUnit4::class)
class ReadingGoldenInstrumentedTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun fixture(): String =
        InstrumentationRegistry.getInstrumentation().context.assets
            .open("reading-v1.json")
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }

    @Test
    fun desktopReadingSourcesAndMokuroCardReplayThroughPackagedBridge() {
        assumeTrue(
            "Reading parity runs only through its isolated selector",
            InstrumentationRegistry.getArguments()
                .getString(RUN_READING_GOLDEN_ARGUMENT) == "true",
        )
        val fixtureJson = fixture()
        val expectedHash =
            JSONObject(fixtureJson).getJSONObject("provenance").getString("output_sha256")
        val python = PythonInstrumentationRuntime.awaitReady()
        val result =
            JSONObject(
                python.getModule("reading_golden_instrumented")
                    .callAttr("run", fixtureJson, context.filesDir.absolutePath)
                    .toString(),
            )

        assertEquals(expectedHash, result.getString("output_sha256"))
        assertEquals(4, result.getInt("source_count"))
        assertEquals(1, result.getInt("cards_created"))
        assertTrue(result.getBoolean("screenshot_verified"))
    }
}
