package com.ankiminer.android.diagnostics

import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

internal data class LogcatReadResult(
    val tail: LogTailResult,
    val exitCode: Int?,
    val timedOut: Boolean,
)

internal fun interface LogcatCommandReader {
    suspend fun read(
        command: List<String>,
        timeoutMillis: Long,
        maxBytes: Long,
    ): LogcatReadResult
}

internal enum class LogcatCaptureStatus(val manifestValue: String) {
    CAPTURED("captured"),
    TIMEOUT("timeout"),
    UNAVAILABLE("unavailable"),
}

internal data class LogcatCaptureResult(
    val text: String,
    val status: LogcatCaptureStatus,
    val exitCode: Int?,
    val omittedBytes: Long,
    val omittedLines: Long,
)

internal class LogcatCapture(
    private val reader: LogcatCommandReader = ProcessLogcatCommandReader(),
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    suspend fun capture(): LogcatCaptureResult {
        var lastExitCode: Int? = null
        for (command in LogcatCommand.candidates()) {
            val read =
                try {
                    reader.read(command, timeoutMillis, maxBytes)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    continue
                }
            lastExitCode = read.exitCode
            if (read.timedOut && read.tail.text.isNotEmpty()) {
                return LogcatCaptureResult(
                    text = read.tail.text,
                    status = LogcatCaptureStatus.TIMEOUT,
                    exitCode = read.exitCode,
                    omittedBytes = read.tail.omittedBytes,
                    omittedLines = read.tail.omittedLines,
                )
            }
            if (read.exitCode != 0) continue
            if (read.tail.text.isNotEmpty()) {
                return LogcatCaptureResult(
                    text = read.tail.text,
                    status = LogcatCaptureStatus.CAPTURED,
                    exitCode = read.exitCode,
                    omittedBytes = read.tail.omittedBytes,
                    omittedLines = read.tail.omittedLines,
                )
            }
        }
        return LogcatCaptureResult(
            text = "",
            status = LogcatCaptureStatus.UNAVAILABLE,
            exitCode = lastExitCode,
            omittedBytes = 0,
            omittedLines = 0,
        )
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 10_000L
        const val DEFAULT_MAX_BYTES = 2L * 1024 * 1024
    }
}

internal class ProcessLogcatCommandReader(
    private val start: (List<String>) -> Process = { command ->
        ProcessBuilder(command).redirectErrorStream(true).start()
    },
) : LogcatCommandReader {
    override suspend fun read(
        command: List<String>,
        timeoutMillis: Long,
        maxBytes: Long,
    ): LogcatReadResult =
        coroutineScope {
            val process = start(command)
            var timedOut = false
            try {
                process.outputStream.close()
                val reading = async(Dispatchers.IO) { LogTail.of(process.inputStream, maxBytes) }
                val completed =
                    withContext(Dispatchers.IO) {
                        process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
                    }
                if (!completed) {
                    timedOut = true
                    tearDown(process)
                }
                val tail = reading.await()
                LogcatReadResult(
                    tail = tail,
                    exitCode = if (timedOut) null else process.exitValue(),
                    timedOut = timedOut,
                )
            } finally {
                if (!timedOut) tearDown(process)
            }
        }

    private suspend fun tearDown(process: Process) {
        withContext(NonCancellable + Dispatchers.IO) {
            bestEffort { process.destroyForcibly() }
            bestEffort { process.inputStream.close() }
            bestEffort { process.errorStream.close() }
            bestEffort { process.outputStream.close() }
            bestEffort { process.waitFor(TEARDOWN_MILLIS, TimeUnit.MILLISECONDS) }
        }
    }

    private fun bestEffort(action: () -> Unit) {
        try {
            action()
        } catch (_: Throwable) {
            // Every teardown operation is independent; one broken stream must not skip the rest.
        }
    }

    private companion object {
        const val TEARDOWN_MILLIS = 1_000L
    }
}
