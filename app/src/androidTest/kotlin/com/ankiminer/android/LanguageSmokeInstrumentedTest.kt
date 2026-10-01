package com.ankiminer.android

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chaquo.python.PyObject
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

private const val LANGUAGE_SMOKE_TAG = "AnkiMinerLanguageSmoke"

/** The debug harness: `app/src/debug/python/language_smoke_instrumented.py`. */
internal fun languageSmokeHarness(): PyObject =
    PythonInstrumentationRuntime.awaitReady().getModule("language_smoke_instrumented")

internal fun recordLanguageSmoke(event: String, payload: JSONObject) {
    val line = "ANKI_MINER_LANGUAGE_SMOKE $event $payload"
    Log.i(LANGUAGE_SMOKE_TAG, line)
    println(line)
}

/** Why a harness `smoke` result fails, or null when its rows match the expected ones. */
internal fun languageSmokeMismatch(result: JSONObject): String? {
    val code = result.getString("code")
    val tokens = result.getJSONArray("tokens").toString()
    val expected = result.optJSONArray("expected")?.toString()
    return when {
        !result.isNull("unavailable_reason") ->
            "$code: unavailable: ${result.getString("unavailable_reason")}"
        tokens != expected -> "$code: tokens $tokens, expected $expected"
        else -> null
    }
}

/**
 * Each data-free profile's `smoke_sentence` through the packaged engine, against the rows the host
 * engine produces (the harness holds both, with their provenance). One looping test, so the API 26
 * lane's source-derived count stays one per method; every code's failure is reported together.
 */
@RunWith(AndroidJUnit4::class)
class LanguageSmokeInstrumentedTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun dataFreeLanguagesTokeniseTheirSmokeSentences() {
        val harness = languageSmokeHarness()
        val codes = harness["CI_CODES"]!!.asList().map { it.toString() }
        val failures = mutableListOf<String>()
        for (code in codes) {
            try {
                val result = JSONObject(harness.callAttr("smoke", code).toString())
                recordLanguageSmoke("tokens", result)
                languageSmokeMismatch(result)?.let(failures::add)
            } catch (error: Exception) {
                failures += "$code: ${error.javaClass.simpleName}: ${error.message}"
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())

        // pythainlp may unpack its word lists on first import: they must stay inside the app's own
        // data directory, and the engine's read-only guard must keep it from making a data dir.
        val footprint =
            JSONObject(harness.callAttr("thai_footprint", context.filesDir.absolutePath).toString())
        recordLanguageSmoke("thai_footprint", footprint)
        val appData = File(context.applicationInfo.dataDir).canonicalPath + File.separator
        val packageDir = File(footprint.getString("package_dir")).canonicalPath
        assertTrue("pythainlp lives outside app data: $packageDir", packageDir.startsWith(appData))
        assertTrue(
            "pythainlp made a data directory: ${footprint.getJSONArray("pythainlp_data_dirs")}",
            footprint.getJSONArray("pythainlp_data_dirs").length() == 0,
        )
    }
}
