package com.ankiminer.android

import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor.AutoCloseInputStream
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

private const val RUN_ACCEPTANCE_ARGUMENT = "ankiMinerRunS1aAcceptance"
private const val ACCEPTANCE_MODE_ARGUMENT = "ankiMinerS1aAcceptanceMode"
private const val ACCEPTANCE_NOVEL_PATH = "/data/local/tmp/anki-miner-s1a-novel.txt"
private const val ACCEPTANCE_TAG = "AnkiMinerS1aAcceptance"

@RunWith(AndroidJUnit4::class)
class S1aAcceptanceInstrumentedTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun golden(): String =
        InstrumentationRegistry.getInstrumentation().context.assets.open("engine-v1.json")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }

    @Test
    fun physicalArm64RuntimeProducesMachineVerifiableMeasurements() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(
            "physical S1a acceptance runs only through its explicit collector",
            arguments.getString(RUN_ACCEPTANCE_ARGUMENT) == "true",
        )
        assertTrue("S1a wheels are required", BuildConfig.S1A_SPIKE_ENABLED)
        assertEquals("arm64-v8a", Build.SUPPORTED_ABIS.first())
        val mode = requireNotNull(arguments.getString(ACCEPTANCE_MODE_ARGUMENT))
        assertTrue(
            "unsupported physical acceptance mode: $mode",
            mode == "cold" || mode == "workload",
        )

        val golden = JSONObject(golden())
        val expectedHash =
            golden.getJSONObject("provenance").getJSONObject("data")
                .getJSONObject("assets_sha256").getString("unidic_dicdir")
        val dicdir = PythonInstrumentationRuntime.stageExternalUniDic(expectedHash)
        val python = PythonInstrumentationRuntime.awaitReady()
        val initialized =
            JSONObject(
                python.getModule("s1a_acceptance_probe")
                    .callAttr(
                        "initialize",
                        dicdir.absolutePath,
                        expectedHash,
                        context.filesDir.absolutePath,
                    ).toString(),
            )
        assertEquals("s1a", initialized.getString("backend"))
        assertEquals("engine_shared_tagger", initialized.getString("tagger_path"))
        assertEquals(expectedHash, initialized.getString("dictionary_sha256"))
        assertEquals(context.filesDir.canonicalPath, File(initialized.getString("home")).canonicalPath)

        val coldInitMs = SystemClock.elapsedRealtime() - Process.getStartUptimeMillis()
        assertTrue("invalid cold initialization measurement", coldInitMs > 0)
        val coldEvidence =
            JSONObject()
                .put("cold_init_ms", coldInitMs.toDouble())
                .put("dictionary_sha256", expectedHash)
                .put("pid", Process.myPid())
                .put("process_start_uptime_ms", Process.getStartUptimeMillis())
        emit("ANKI_MINER_S1A_COLD", coldEvidence)

        if (mode == "workload") {
            val novel = stageNovel()
            val workload =
                JSONObject(
                    python.getModule("s1a_acceptance_probe")
                        .callAttr("measure_novel", novel.absolutePath)
                        .toString(),
                )
            assertTrue(workload.getInt("japanese_character_count") >= 50_000)
            assertTrue(workload.getLong("peak_rss_bytes") > 0L)
            emit("ANKI_MINER_S1A_WORKLOAD", workload)
        }
    }

    private fun stageNovel(): File {
        val destination = File(context.cacheDir, "s1a-acceptance-novel.txt")
        val descriptor =
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("cat $ACCEPTANCE_NOVEL_PATH")
        var bytes = 0L
        AutoCloseInputStream(descriptor).use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    bytes += count
                    check(bytes <= 32L * 1024 * 1024) {
                        "physical acceptance novel exceeds the 32 MiB staging limit"
                    }
                }
            }
        }
        check(bytes > 0) { "physical acceptance novel is missing" }
        return destination
    }

    private fun emit(
        marker: String,
        value: JSONObject,
    ) {
        val rendered = "$marker=$value"
        Log.i(ACCEPTANCE_TAG, rendered)
        println(rendered)
    }
}
