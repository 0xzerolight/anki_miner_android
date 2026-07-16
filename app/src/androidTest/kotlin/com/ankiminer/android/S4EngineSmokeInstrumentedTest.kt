package com.ankiminer.android

import android.content.Context
import android.os.Debug
import android.os.ParcelFileDescriptor.AutoCloseInputStream
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun stageDictionary(expectedHash: String): File {
        val parent = File(context.filesDir, "test-assets").apply { mkdirs() }
        val destination = File(parent, "unidic-$expectedHash")
        if (destination.isDirectory) return destination
        val staging = File(parent, ".unidic-$expectedHash.staging")
        staging.deleteRecursively()
        check(staging.mkdirs())
        val prefix = staging.canonicalPath + File.separator
        var entries = 0
        var bytes = 0L
        val names = mutableSetOf<String>()
        val descriptor =
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "cat ${BuildConfig.TOKENIZER_TEST_UNIDIC_ARCHIVE}",
            )
        ZipInputStream(AutoCloseInputStream(descriptor).buffered()).use { archive ->
            while (true) {
                val entry = archive.nextEntry ?: break
                check(entry.name.isNotBlank() && names.add(entry.name))
                val target = File(staging, entry.name).canonicalFile
                check(target.path.startsWith(prefix))
                if (entry.isDirectory) {
                    check(target.mkdirs() || target.isDirectory)
                } else {
                    target.parentFile?.let { check(it.mkdirs() || it.isDirectory) }
                    FileOutputStream(target).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = archive.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            bytes += count
                            check(bytes <= 512L * 1024 * 1024)
                        }
                    }
                }
                entries += 1
                check(entries <= 512)
                archive.closeEntry()
            }
        }
        check(entries > 0) { "external UniDic ZIP is missing" }
        check(staging.renameTo(destination))
        return destination
    }

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
        val dicdir = stageDictionary(expectedDictionaryHash)

        val wasStarted = Python.isStarted()
        if (arguments.getString(EXPECT_FRESH_PROCESS_ARGUMENT) == "true") {
            assertFalse("S4 selector did not start in a fresh app process", wasStarted)
        }
        val startNanos = SystemClock.elapsedRealtimeNanos()
        if (!wasStarted) Python.start(AndroidPlatform(context))
        val pythonStartMs = (SystemClock.elapsedRealtimeNanos() - startNanos) / 1_000_000.0
        val python = Python.getInstance()
        val probe = python.getModule("s4_engine_smoke")
        val preflight = JSONObject(probe.callAttr("preflight").toString())
        assertEquals(0, preflight.getJSONArray("engine_modules_before").length())
        assertEquals(0, preflight.getJSONArray("engine_modules_after").length())
        assertEquals("bootstrap_required", preflight.getString("require_initialized_failure"))

        python.getModule("android_bridge.bootstrap")
            .callAttr("initialize", context.filesDir.absolutePath)
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
                .put("cold_process", !wasStarted)
                .put("python_start_ms", pythonStartMs)
                .put("total_pss_kib", memory.totalPss)
                .put("python", pythonMetrics)
        val renderedMetrics = "S4_EMULATOR_METRICS ${metrics}"
        Log.i(METRICS_TAG, renderedMetrics)
        println(renderedMetrics)
    }
}
