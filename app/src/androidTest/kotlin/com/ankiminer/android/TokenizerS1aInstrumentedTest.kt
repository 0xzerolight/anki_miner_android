package com.ankiminer.android

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

private const val EXPECTED_TAGGER_PATH_ARGUMENT = "ankiMinerExpectedTokenizerPath"
private const val ENGINE_SHARED_TAGGER = "engine_shared_tagger"

@RunWith(AndroidJUnit4::class)
class TokenizerS1aInstrumentedTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun golden(): String =
        InstrumentationRegistry.getInstrumentation().context.assets.open("engine-v1.json")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun assertTaggerPath(result: JSONObject) {
        val taggerPath = result.getString("tagger_path")
        val fallbackPath = "debug_direct_fallback_after_s1b"
        assertTrue(
            "unexpected S1a instrumentation path: $taggerPath",
            taggerPath == ENGINE_SHARED_TAGGER || taggerPath == fallbackPath,
        )
        assertEquals(
            if (taggerPath == ENGINE_SHARED_TAGGER) "s1a" else "s1b",
            result.getString("selected_backend"),
        )
        InstrumentationRegistry.getArguments()
            .getString(EXPECTED_TAGGER_PATH_ARGUMENT)
            ?.let { expected -> assertEquals(expected, taggerPath) }
    }

    @Test
    fun externalUniDicMatchesDesktopGoldens() {
        assumeTrue("S1a wheels are not enabled", BuildConfig.S1A_SPIKE_ENABLED)
        val goldenJson = golden()
        val golden = JSONObject(goldenJson)
        val expectedHash =
            golden.getJSONObject("provenance").getJSONObject("data")
                .getJSONObject("assets_sha256").getString("unidic_dicdir")
        val dicdir = PythonInstrumentationRuntime.stageExternalUniDic(expectedHash)
        val python = PythonInstrumentationRuntime.awaitReady()
        val result =
            JSONObject(
                python.getModule("tokenizer_s1a_instrumented")
                    .callAttr(
                        "run",
                        goldenJson,
                        dicdir.absolutePath,
                        context.applicationInfo.nativeLibraryDir,
                    ).toString(),
            )
        assertEquals(26, result.getInt("feature_field_count"))
        assertEquals(expectedHash, result.getString("dictionary_sha256"))
        assertTrue(result.getInt("case_count") > 0)
        assertTrue(result.getInt("unknown_count") > 0)
        assertEquals("*", result.getString("raw_oov_pos3"))
        assertTrue(result.getBoolean("raw_oov_lform_is_none"))
        assertTaggerPath(result)
        val evidence =
            JSONObject()
                .put("assertion_count", result.getInt("case_count"))
                .put(
                    "corpus_sha256",
                    golden.getJSONObject("provenance").getJSONObject("data")
                        .getString("corpus_sha256"),
                )
                .put("passed", true)
                .put("test_class", TokenizerS1aInstrumentedTest::class.java.name)
        val renderedEvidence = "ANKI_MINER_S1A_PARITY=$evidence"
        Log.i("AnkiMinerS1aParity", renderedEvidence)
        println(renderedEvidence)
    }
}
