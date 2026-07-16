package com.ankiminer.android.tokenizer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ankiminer.android.BuildConfig
import com.ankiminer.android.PythonInstrumentationRuntime
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

private const val EXPECTED_TAGGER_PATH_ARGUMENT = "ankiMinerExpectedTokenizerPath"
private const val ENGINE_SHARED_TAGGER = "engine_shared_tagger"

@RunWith(AndroidJUnit4::class)
class MecabNativeTokenizerInstrumentedTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun readGolden(): String =
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("engine-v1.json")
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }

    private fun assertTaggerPath(result: JSONObject) {
        val taggerPath = result.getString("tagger_path")
        val fallbackPath = "debug_direct_fallback_after_s1a"
        assertTrue(
            "unexpected S1b instrumentation path: $taggerPath",
            taggerPath == ENGINE_SHARED_TAGGER || taggerPath == fallbackPath,
        )
        assertEquals(
            if (taggerPath == ENGINE_SHARED_TAGGER) "s1b" else "s1a",
            result.getString("selected_backend"),
        )
        InstrumentationRegistry.getArguments()
            .getString(EXPECTED_TAGGER_PATH_ARGUMENT)
            ?.let { expected -> assertEquals(expected, taggerPath) }
    }

    @Test
    fun nativeBoundaryRejectsIncompleteArgvWithoutExposingHandles() {
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                MecabNativeTokenizer.tokenize(
                    "猫".toByteArray(Charsets.UTF_8),
                    arrayOf("anki_miner", "-C"),
                )
            }

        assertTrue(error.message.orEmpty().contains("mecab_new argv"))
    }

    @Test
    fun externalUniDicMatchesAllGoldensThroughPythonKotlinAndJni() {
        val goldenJson = readGolden()
        val golden = JSONObject(goldenJson)
        val expectedHash =
            golden.getJSONObject("provenance")
                .getJSONObject("data")
                .getJSONObject("assets_sha256")
                .getString("unidic_dicdir")
        val dicdir =
            PythonInstrumentationRuntime.stageExternalUniDic(
                expectedHash,
                BuildConfig.S1B_TEST_UNIDIC_ARCHIVE,
            )
        val python = PythonInstrumentationRuntime.awaitReady()

        val result =
            JSONObject(
                python.getModule("tokenizer_s1b_instrumented")
                    .callAttr("run", goldenJson, dicdir.absolutePath)
                    .toString(),
            )

        assertEquals(
            golden.getJSONObject("cases").getJSONArray("tokenization").length(),
            result.getInt("case_count"),
        )
        assertEquals(
            golden.getJSONArray("unidic_feature_fields").length(),
            result.getInt("feature_field_count"),
        )
        assertEquals(26, result.getInt("feature_field_count"))
        assertEquals(expectedHash, result.getString("dictionary_sha256"))
        assertTrue(result.getInt("unknown_count") > 0)
        assertEquals(1, result.getInt("oov_utf16_start"))
        assertEquals(7, result.getInt("oov_utf16_end"))
        assertTrue(result.getBoolean("sys_dic_mapped"))
        assertTrue(result.getBoolean("matrix_mapped"))
        assertTaggerPath(result)
    }
}
