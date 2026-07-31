package com.ankiminer.android.diagnostics

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

internal sealed interface BundleShedding {
    data object None : BundleShedding

    data class Shrink(
        val rank: Int,
        val floorBytes: Long,
    ) : BundleShedding

    data class Drop(val rank: Int) : BundleShedding
}

internal data class BundleEntrySpec(
    val name: String,
    val capBytes: Long,
    val redacted: Boolean,
    val required: Boolean = false,
    val shedding: BundleShedding = BundleShedding.None,
)

internal data class BundleSource(
    val name: String,
    val capBytes: Long,
    val redacted: Boolean,
    val required: Boolean = false,
    val shedding: BundleShedding = BundleShedding.None,
    val priorOmittedBytes: Long = 0,
    val priorOmittedLines: Long = 0,
    val unavailable: Boolean = false,
    val open: () -> InputStream?,
)

internal fun BundleEntrySpec.logcatSource(logcat: LogcatCaptureResult): BundleSource =
    BundleSource(
        name = name,
        capBytes = capBytes,
        redacted = redacted,
        required = required,
        shedding = shedding,
        priorOmittedBytes = logcat.omittedBytes,
        priorOmittedLines = logcat.omittedLines,
        unavailable = logcat.status == LogcatCaptureStatus.UNAVAILABLE,
        open = { ByteArrayInputStream(logcat.text.toByteArray(StandardCharsets.UTF_8)) },
    )

internal fun interface LineRedactor {
    fun redact(line: String): String
}

internal object DiagnosticsBundleSpec {
    const val TOTAL_BUDGET_BYTES = 6L * 1024 * 1024

    val entries =
        listOf(
            BundleEntrySpec(
                name = "logs/anki_miner.log",
                capBytes = 1024L * 1024,
                redacted = true,
                shedding = BundleShedding.Shrink(rank = 4, floorBytes = 256L * 1024),
            ),
            BundleEntrySpec(
                name = "logs/anki_miner.log.1",
                capBytes = 1024L * 1024,
                redacted = true,
                shedding = BundleShedding.Drop(rank = 2),
            ),
            BundleEntrySpec(
                name = "logs/app.log",
                capBytes = 512L * 1024,
                redacted = true,
            ),
            BundleEntrySpec(
                name = "logs/app.log.1",
                capBytes = 512L * 1024,
                redacted = true,
                shedding = BundleShedding.Drop(rank = 3),
            ),
            BundleEntrySpec(
                name = "logcat/logcat.txt",
                capBytes = 2L * 1024 * 1024,
                redacted = true,
                shedding = BundleShedding.Shrink(rank = 1, floorBytes = 512L * 1024),
            ),
            BundleEntrySpec(
                name = "diagnostics.txt",
                capBytes = 4L * 1024,
                redacted = false,
                required = true,
            ),
            BundleEntrySpec(
                name = "system/exit-reasons.txt",
                capBytes = 64L * 1024,
                redacted = true,
            ),
            BundleEntrySpec(
                name = "redaction.txt",
                capBytes = 64L * 1024,
                redacted = false,
            ),
        )

    fun fileName(
        capturedAt: Instant,
        versionName: String,
        sourceCommit: String,
    ): String {
        val timestamp = FILE_TIME.format(capturedAt)
        val version = safeSegment(versionName, maxLength = 64)
        val commit = safeSegment(sourceCommit, maxLength = 7)
        return "anki-miner-diagnostics-$timestamp-$version-$commit.zip"
    }

    private fun safeSegment(
        raw: String,
        maxLength: Int,
    ): String {
        val filtered =
            buildString {
                raw.forEach { character ->
                    append(
                        if (
                            character in 'a'..'z' ||
                            character in 'A'..'Z' ||
                            character in '0'..'9' ||
                            character == '.' ||
                            character == '-' ||
                            character == '_'
                        ) {
                            character
                        } else {
                            '_'
                        },
                    )
                }
            }.trim('.', '-', '_')
                .take(maxLength)
        return filtered.ifEmpty { "unknown" }
    }

    private val FILE_TIME =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC)
}
