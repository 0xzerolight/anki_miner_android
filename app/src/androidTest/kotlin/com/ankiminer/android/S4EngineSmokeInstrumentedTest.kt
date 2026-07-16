package com.ankiminer.android

import android.content.Context
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaquo.python.Python
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

private const val RUN_S4_ARGUMENT = "ankiMinerRunS4"
private const val EXPECT_FRESH_PROCESS_ARGUMENT = "ankiMinerExpectedFreshProcess"
private const val METRICS_TAG = "AnkiMinerS4"

@RunWith(AndroidJUnit4::class)
class S4EngineSmokeInstrumentedTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun fixture(): String =
        InstrumentationRegistry.getInstrumentation().context.assets
            .open("s4-engine-smoke-v1.json")
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }

    @Test
    fun pinnedDesktopChainRunsThroughPackagedEngine() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(
            "S4 runs only through its fresh-process selector",
            arguments.getString(RUN_S4_ARGUMENT) == "true",
        )
        assertTrue(
            "S1a wheels are required for the selected S4 tokenizer",
            BuildConfig.S1A_SPIKE_ENABLED,
        )

        val fixtureJson = fixture()
        val fixture = JSONObject(fixtureJson)
        val expectedDictionaryHash =
            fixture.getJSONObject("provenance").getJSONObject("input")
                .getString("unidic_dicdir_sha256")
        val dicdir = PythonInstrumentationRuntime.stageExternalUniDic(expectedDictionaryHash)

        val pythonWasRunningBeforeAwait = Python.isStarted()
        val python = PythonInstrumentationRuntime.awaitReady()
        assertTrue("Application did not eagerly start Python", Python.isStarted())
        val probe = python.getModule("s4_engine_smoke")
        val preflight =
            JSONObject(probe.callAttr("preflight", context.filesDir.absolutePath).toString())
        assertEquals(0, preflight.getJSONArray("bootstrap_engine_modules_before").length())
        assertEquals(
            preflight.getJSONArray("engine_modules_before").toString(),
            preflight.getJSONArray("engine_modules_after").toString(),
        )
        assertTrue(
            preflight.getJSONArray("engine_modules_after").toString()
                .contains("anki_miner.config.paths"),
        )
        assertTrue(
            !preflight.getJSONArray("engine_modules_after").toString()
                .contains("anki_miner.orchestration"),
        )
        assertEquals(context.filesDir.canonicalPath, File(preflight.getString("home")).canonicalPath)
        val result =
            JSONObject(
                probe.callAttr(
                    "run",
                    fixtureJson,
                    dicdir.absolutePath,
                    context.filesDir.absolutePath,
                ).toString(),
            )

        assertEquals("CPython", result.getString("implementation"))
        assertEquals(
            listOf(3, 12, 12),
            result.getJSONArray("python").let { array ->
                List(array.length()) { array.getInt(it) }
            },
        )
        assertEquals(context.filesDir.canonicalPath, File(result.getString("home")).canonicalPath)
        assertEquals("s1a", result.getString("backend"))
        assertEquals("engine_shared_tagger", result.getString("tagger_path"))
        assertEquals("LockedTagger", result.getString("shared_tagger_type"))
        assertEquals(expectedDictionaryHash, result.getString("dictionary_sha256"))
        assertEquals(
            fixture.getJSONObject("provenance").getString("output_sha256"),
            result.getString("output_sha256"),
        )
        assertTrue(result.getInt("engine_module_count") > 0)
        val qt = result.getJSONObject("qt")
        assertEquals("Ready", qt.getString("plain"))
        assertEquals("2 cards", qt.getString("plural"))
        assertEquals("Step 1 of 5", qt.getString("positional"))

        val pythonMetrics = result.getJSONObject("metrics")
        listOf(
            "episode_processor_import_ms",
            "registration_ms",
            "tokenization_ms",
            "tokenizer_init_ms",
            "total_python_smoke_ms",
        ).forEach { key ->
            val value = pythonMetrics.getDouble(key)
            assertTrue("invalid diagnostic metric $key=$value", value.isFinite() && value >= 0.0)
        }
        val memory = Debug.MemoryInfo().also(Debug::getMemoryInfo)
        val metrics =
            JSONObject()
                .put("api", android.os.Build.VERSION.SDK_INT)
                .put("abi", android.os.Build.SUPPORTED_ABIS.first())
                .put(
                    "cold_process_selected",
                    arguments.getString(EXPECT_FRESH_PROCESS_ARGUMENT) == "true",
                )
                .put("python_running_before_test_await", pythonWasRunningBeforeAwait)
                .put("process_start_uptime_ms", android.os.Process.getStartUptimeMillis())
                .put("bootstrap_observed_uptime_ms", SystemClock.elapsedRealtime())
                .put("total_pss_kib", memory.totalPss)
                .put("python", pythonMetrics)
        val renderedMetrics = "S4_EMULATOR_METRICS ${metrics}"
        Log.i(METRICS_TAG, renderedMetrics)
        println(renderedMetrics)
    }
}
