package com.ankiminer.android

import android.content.Context
import android.os.Debug
import android.os.ParcelFileDescriptor.AutoCloseInputStream
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Device directory holding the pinned `language-data` archives under their catalog URL names. */
private const val LANGUAGE_DATA_DIR_ARGUMENT = "ankiMinerLanguageDataDir"

/** Optional comma list narrowing the codes; one code per process gives a clean PSS peak. */
private const val LANGUAGE_CODES_ARGUMENT = "ankiMinerLanguageSmokeCodes"
private const val PSS_SAMPLE_INTERVAL_MS = 200L

/**
 * Local only (UNEXECUTED on the CI lane): the languages whose tagger reads downloaded data.
 *
 * Push the pinned archives first, then select the directory:
 * `adb push morphology_db_calima-msa-r13-0.4.0.zip hazm-0.12.1-py3-none-any.whl
 * /data/local/tmp/anki-miner-language-data/` and
 * `-e ankiMinerLanguageDataDir /data/local/tmp/anki-miner-language-data`.
 * Each archive installs through the production bridge path; the test logs tokens and peak PSS.
 */
@RunWith(AndroidJUnit4::class)
class LanguageDataSmokeInstrumentedTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun downloadedLanguageDataTokenisesAndRecordsPeakPss() {
        val arguments = InstrumentationRegistry.getArguments()
        val archiveDir = arguments.getString(LANGUAGE_DATA_DIR_ARGUMENT)
        assumeTrue("needs pushed language-data archives", archiveDir != null)
        val harness = languageSmokeHarness()
        val localCodes = harness["LOCAL_RESOURCES"]!!.asMap().keys.map { it.toString() }
        val codes =
            arguments.getString(LANGUAGE_CODES_ARGUMENT)
                ?.split(',')
                ?.map(String::trim)
                ?.filter(String::isNotEmpty)
                ?: localCodes
        assertTrue("unknown local codes: $codes", codes.isNotEmpty() && localCodes.containsAll(codes))
        val failures = mutableListOf<String>()
        for (code in codes) {
            val archive = File(context.cacheDir, harness.callAttr("archive_name", code).toString())
            try {
                copyFromShell("$archiveDir/${archive.name}", archive)
                val baselineKib = Debug.getPss()
                val (result, peakKib) =
                    sampledPeakPss {
                        harness.callAttr("install", code, archive.absolutePath)
                        JSONObject(harness.callAttr("smoke", code).toString())
                    }
                recordLanguageSmoke(
                    "local",
                    JSONObject()
                        .put("code", code)
                        .put("tokens", result.getJSONArray("tokens"))
                        .put("baseline_pss_kib", baselineKib)
                        .put("peak_pss_kib", peakKib)
                        .put("dumpsys_total_pss_kib", dumpsysTotalPssKib())
                        .put("api", android.os.Build.VERSION.SDK_INT)
                        .put("abi", android.os.Build.SUPPORTED_ABIS.first()),
                )
                languageSmokeMismatch(result)?.let(failures::add)
                harness.callAttr("evict", code)
            } catch (error: Exception) {
                failures += "$code: ${error.javaClass.simpleName}: ${error.message}"
            } finally {
                archive.delete()
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    /** The app cannot read /data/local/tmp; the shell user can, so stream the archive through it. */
    private fun copyFromShell(source: String, destination: File) {
        val descriptor =
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("cat $source")
        AutoCloseInputStream(descriptor).use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        }
        check(destination.length() > 0) { "no archive at $source" }
    }

    private fun <T> sampledPeakPss(block: () -> T): Pair<T, Long> {
        val peak = AtomicLong(Debug.getPss())
        val running = AtomicBoolean(true)
        val sampler =
            thread(name = "language-smoke-pss") {
                while (running.get()) {
                    peak.accumulateAndGet(Debug.getPss()) { a, b -> maxOf(a, b) }
                    Thread.sleep(PSS_SAMPLE_INTERVAL_MS)
                }
            }
        try {
            val value = block()
            peak.accumulateAndGet(Debug.getPss()) { a, b -> maxOf(a, b) }
            return value to peak.get()
        } finally {
            running.set(false)
            sampler.join()
        }
    }

    /** `dumpsys meminfo`'s TOTAL PSS for this process, read while the tagger is resident. */
    private fun dumpsysTotalPssKib(): Long? {
        val descriptor =
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("dumpsys meminfo ${context.packageName}")
        val output = AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        return Regex("""(?m)^\s*TOTAL(?: PSS:)?\s+(\d+)""").find(output)
            ?.groupValues?.get(1)?.toLong()
    }
}
