package com.ankiminer.android.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import com.ankiminer.android.data.settings.AppSettings
import com.ankiminer.android.diagnostics.log.FileLogSink
import com.ankiminer.android.diagnostics.log.LogRedactor
import com.ankiminer.android.diagnostics.log.RedactionRulesFactory
import com.ankiminer.android.media.SafSelectionInventory
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Locale
import java.util.TimeZone
import java.util.zip.Deflater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class StagedBundle(
    val file: File,
    val uri: String,
    val sizeBytes: Long,
    val entries: List<BundleEntryResult>,
)

internal class DiagnosticsBundleStager(
    context: Context,
    private val fileLogSink: FileLogSink,
    private val inventory: SafSelectionInventory,
    private val logcatCapture: LogcatCapture = LogcatCapture(),
    private val capturedAt: () -> Instant = Instant::now,
    private val captureGate: DiagnosticsCaptureGate = DiagnosticsCaptureGate(),
) {
    private val context = context.applicationContext
    private val root =
        File(this.context.cacheDir, DiagnosticsBundleJanitor.DIRECTORY_NAME)
    private val pythonLogSnapshotter = PythonLogSnapshotter(this.context.filesDir)
    private val nameAllocator = DiagnosticsBundleNameAllocator(root)

    suspend fun stage(
        diagnostics: String,
        settings: AppSettings,
        verboseLogging: Boolean,
    ): StagedBundle =
        captureGate.run {
            withContext(Dispatchers.IO) {
                root.mkdirs()
                check(root.isDirectory) { "diagnostics bundle staging directory is unavailable" }
                val captured = capturedAt()
                val identity = currentTesterBuildIdentity()
                val destination =
                    nameAllocator.allocate(
                        captured,
                        identity.versionName,
                        identity.sourceCommit,
                    )
                val workspace = File(root, ".capture-${captured.toEpochMilli()}").apply { mkdirs() }
                val temporary = File.createTempFile(".building-", ".zip", root)
                try {
                    val python = pythonLogSnapshotter.snapshot(workspace)
                    val kotlin = fileLogSink.snapshot(File(workspace, "kotlin"))
                    val logcat = logcatCapture.capture()
                    val rules = RedactionRulesFactory.forExport(context, settings, inventory)
                    val redactor = LogRedactor(rules)
                    val sources =
                        sources(
                            diagnostics = diagnostics,
                            python = python,
                            kotlin = kotlin,
                            logcat = logcat,
                            exitReasons = exitReasons(),
                            redaction = { redactionReport(redactor) },
                        )
                    val writer =
                        DiagnosticsBundleWriter(
                            capturedAt = captured,
                            compressionLevel = Deflater.BEST_COMPRESSION,
                            totalBudgetBytes = DiagnosticsBundleSpec.TOTAL_BUDGET_BYTES,
                        )
                    val entries =
                        FileOutputStream(temporary).use { output ->
                            writer.write(
                                destination = output,
                                sources = sources,
                                redactor = LineRedactor(redactor::redact),
                                manifest = { results ->
                                    manifest(
                                        captured = captured,
                                        identity = identity,
                                        verboseLogging = verboseLogging,
                                        logcat = logcat,
                                        redactor = redactor,
                                        results = results,
                                    )
                                },
                                readme = { results, _ -> readme(results) },
                            )
                        }
                    if (!temporary.renameTo(destination)) {
                        throw IOException("diagnostics bundle publication failed")
                    }
                    destination.setLastModified(captured.toEpochMilli())
                    DiagnosticsBundleJanitor(root).clean()
                    StagedBundle(
                        file = destination,
                        uri =
                            FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.diagnostics",
                                destination,
                            ).toString(),
                        sizeBytes = destination.length(),
                        entries = entries,
                    )
                } finally {
                    temporary.delete()
                    workspace.deleteRecursively()
                }
            }
        }

    private fun sources(
        diagnostics: String,
        python: Map<String, File>,
        kotlin: List<File>,
        logcat: LogcatCaptureResult,
        exitReasons: String,
        redaction: () -> String,
    ): List<BundleSource> {
        val kotlinByName = kotlin.associateBy(File::getName)
        return DiagnosticsBundleSpec.entries.map { spec ->
            when (spec.name) {
                "logs/anki_miner.log" -> spec.fileSource(python["anki_miner.log"])
                "logs/anki_miner.log.1" -> spec.fileSource(python["anki_miner.log.1"])
                "logs/app.log" -> spec.fileSource(kotlinByName["anki_miner_app.log"])
                "logs/app.log.1" -> spec.fileSource(kotlinByName["anki_miner_app.log.1"])
                "logcat/logcat.txt" -> spec.logcatSource(logcat)
                "diagnostics.txt" -> spec.textSource(diagnostics)
                "system/exit-reasons.txt" -> spec.textSource(exitReasons)
                "redaction.txt" -> spec.dynamicTextSource(redaction)
                else -> error("unsupported diagnostics entry ${spec.name}")
            }
        }
    }

    private fun BundleEntrySpec.fileSource(file: File?): BundleSource =
        BundleSource(name, capBytes, redacted, required, shedding) {
            file?.takeIf(File::isFile)?.inputStream()
        }

    private fun BundleEntrySpec.textSource(
        text: String,
        priorOmittedBytes: Long = 0,
        priorOmittedLines: Long = 0,
    ): BundleSource =
        BundleSource(
            name = name,
            capBytes = capBytes,
            redacted = redacted,
            required = required,
            shedding = shedding,
            priorOmittedBytes = priorOmittedBytes,
            priorOmittedLines = priorOmittedLines,
            open = { ByteArrayInputStream(text.toByteArray(StandardCharsets.UTF_8)) },
        )

    private fun BundleEntrySpec.dynamicTextSource(text: () -> String): BundleSource =
        BundleSource(name, capBytes, redacted, required, shedding) {
            ByteArrayInputStream(text().toByteArray(StandardCharsets.UTF_8))
        }

    private fun manifest(
        captured: Instant,
        identity: TesterBuildIdentity,
        verboseLogging: Boolean,
        logcat: LogcatCaptureResult,
        redactor: LogRedactor,
        results: List<BundleEntryResult>,
    ): Map<String, String> =
        linkedMapOf<String, String>().apply {
            put("bundle.captured_at", captured.toString())
            putAll(DiagnosticsManifest.buildIdentityEntries(identity))
            put("android.release", Build.VERSION.RELEASE.orEmpty())
            put("android.security_patch", Build.VERSION.SECURITY_PATCH.orEmpty())
            put("device.brand", Build.BRAND.orEmpty())
            put("device.manufacturer", Build.MANUFACTURER.orEmpty())
            put("device.model", Build.MODEL.orEmpty())
            put("device.device", Build.DEVICE.orEmpty())
            put("device.product", Build.PRODUCT.orEmpty())
            put("device.hardware", Build.HARDWARE.orEmpty())
            put("locale", Locale.getDefault().toLanguageTag())
            put("timezone", TimeZone.getDefault().id)
            put("storage.files_free_bytes", context.filesDir.usableSpace.toString())
            put("ankidroid.version", ankiDroidVersion())
            put("verbose_logging", verboseLogging.toString())
            putAll(DiagnosticsManifest.logSinkEntries(fileLogSink.disabledBy))
            put("capture.logcat.status", logcat.status.manifestValue)
            put("capture.logcat.exit_code", logcat.exitCode?.toString() ?: "unavailable")
            put(
                "capture.python_rotation",
                "backup-first; detected rotation clears snapshots and retries " +
                    "${PythonLogSnapshotter.DEFAULT_MAX_ATTEMPTS} times; boundary records may duplicate",
            )
            redactor.tokenCounts().toSortedMap().forEach { (kind, count) ->
                put("redaction.$kind", count.toString())
            }
            results.forEach { result ->
                val prefix = "entry.${result.name}"
                put("$prefix.bytes", result.includedBytes.toString())
                put("$prefix.omitted_bytes", result.omittedBytes.toString())
                put("$prefix.omitted_lines", result.omittedLines.toString())
                put("$prefix.truncated", result.truncated.toString())
                put("$prefix.dropped", result.dropped.toString())
                put("$prefix.missing", result.missing.toString())
                put("$prefix.unavailable", result.unavailable.toString())
            }
        }

    private fun readme(results: List<BundleEntryResult>): String =
        buildString {
            appendLine("Anki Miner diagnostics bundle")
            appendLine()
            appendLine("Log bodies are tail-capped and redacted with a fresh per-bundle salt.")
            appendLine("Run IDs remain present by design so records can be correlated across files.")
            appendLine("Head truncation and end-of-entry markers make every retained boundary explicit.")
            appendLine("The 6 MiB uncompressed budget sheds entries in the order recorded by manifest.txt.")
            appendLine()
            appendLine("Deliberately excluded: Build.SERIAL, SSAID, accounts, IP/MAC addresses,")
            appendLine("and package inventory. AnkiDroid's version is the only peer-package lookup.")
            appendLine()
            appendLine("Entries recorded: ${results.size}")
        }

    private fun redactionReport(redactor: LogRedactor): String =
        buildString {
            appendLine("A fresh random salt was created for this bundle.")
            appendLine("Tokens are stable only within this archive and cannot be reversed without source text.")
            redactor.tokenCounts().toSortedMap().forEach { (kind, count) ->
                appendLine("$kind=$count")
            }
        }

    private fun exitReasons(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return "Historical process exit reasons are unavailable before Android 11 (API 30).\n"
        }
        val manager = context.getSystemService(ActivityManager::class.java)
            ?: return "Historical process exit reasons are unavailable.\n"
        val reasons =
            manager.getHistoricalProcessExitReasons(context.packageName, 0, 8)
        if (reasons.isEmpty()) return "No historical process exit reasons were reported.\n"
        return buildString {
            reasons.forEachIndexed { index, reason ->
                appendLine("exit[$index].timestamp=${Instant.ofEpochMilli(reason.timestamp)}")
                appendLine("exit[$index].reason=${reason.reason}")
                appendLine("exit[$index].status=${reason.status}")
                appendLine("exit[$index].importance=${reason.importance}")
                appendLine("exit[$index].description=${reason.description.orEmpty()}")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    appendLine("exit[$index].pss_kb=${reason.pss}")
                    appendLine("exit[$index].rss_kb=${reason.rss}")
                }
            }
        }
    }

    private fun ankiDroidVersion(): String =
        try {
            val info =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageInfo(
                        ANKIDROID_PACKAGE,
                        PackageManager.PackageInfoFlags.of(0),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(ANKIDROID_PACKAGE, 0)
                }
            val code =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    info.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    info.versionCode.toLong()
                }
            "${info.versionName.orEmpty()} ($code)"
        } catch (_: PackageManager.NameNotFoundException) {
            "not-installed"
        }

    private companion object {
        const val ANKIDROID_PACKAGE = "com.ichi2.anki"
    }
}

internal class DiagnosticsBundleNameAllocator(private val root: File) {
    fun allocate(
        capturedAt: Instant,
        versionName: String,
        sourceCommit: String,
    ): File {
        var namedAt = capturedAt
        while (true) {
            val candidate =
                File(
                    root,
                    DiagnosticsBundleSpec.fileName(namedAt, versionName, sourceCommit),
                )
            if (!candidate.exists()) return candidate
            namedAt = namedAt.plusSeconds(1)
        }
    }
}
