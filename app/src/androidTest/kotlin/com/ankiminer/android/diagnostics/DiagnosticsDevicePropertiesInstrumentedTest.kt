package com.ankiminer.android.diagnostics

import android.content.Context
import android.util.Log
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

private const val EVIDENCE_TAG = "AnkiMinerDiagnostics"

@RunWith(AndroidJUnit4::class)
class DiagnosticsDevicePropertiesInstrumentedTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun logcatCaptureReadsOwnLineOrReportsUnavailable() = runBlocking {
        val probe = "ANKI_MINER_LOGCAT_CAPTURE_PROBE=${System.nanoTime()}"
        Log.i(EVIDENCE_TAG, probe)
        var winningCommand: List<String>? = null
        val reader = ProcessLogcatCommandReader()
        val capture =
            LogcatCapture(
                reader =
                    LogcatCommandReader { command, timeoutMillis, maxBytes ->
                        reader.read(command, timeoutMillis, maxBytes).also { read ->
                            if (read.tail.text.isNotEmpty()) winningCommand = command
                        }
                    },
            )

        val result = capture.capture()
        val candidate = winningCommand?.joinToString(" ") ?: "unavailable"
        val evidence =
            "ANKI_MINER_LOGCAT_CAPTURE status=${result.status.manifestValue} candidate=$candidate"
        Log.i(EVIDENCE_TAG, evidence)
        println(evidence)

        assertTrue(
            "logcat capture must return text or status=unavailable; candidate=$candidate",
            result.text.isNotEmpty() || result.status == LogcatCaptureStatus.UNAVAILABLE,
        )
        if (result.status != LogcatCaptureStatus.UNAVAILABLE) {
            assertTrue(
                "logcat capture omitted its own probe; candidate=$candidate",
                result.text.contains(probe),
            )
        }
    }

    @Test
    fun diagnosticsFileProviderResolvesStagedBundleAsZip() {
        val stagingDirectory = File(context.cacheDir, DiagnosticsBundleJanitor.DIRECTORY_NAME)
        assertTrue(
            "diagnostics staging directory is unavailable",
            stagingDirectory.mkdirs() || stagingDirectory.isDirectory,
        )
        val stagedBundle = File(stagingDirectory, "device-property-${System.nanoTime()}.zip")
        try {
            FileOutputStream(stagedBundle).use { output ->
                ZipOutputStream(output).use { archive ->
                    archive.putNextEntry(ZipEntry("diagnostics.txt"))
                    archive.write("device property".toByteArray())
                    archive.closeEntry()
                }
            }

            val uri =
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.diagnostics",
                    stagedBundle,
                )

            assertEquals("${context.packageName}.diagnostics", uri.authority)
            assertEquals("application/zip", context.contentResolver.getType(uri))
            val evidence =
                "ANKI_MINER_DIAGNOSTICS_URI authority=${uri.authority} type=" +
                    context.contentResolver.getType(uri)
            Log.i(EVIDENCE_TAG, evidence)
            println(evidence)
        } finally {
            stagedBundle.delete()
        }
    }
}
