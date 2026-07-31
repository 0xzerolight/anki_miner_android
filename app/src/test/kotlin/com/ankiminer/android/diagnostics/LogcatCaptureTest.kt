package com.ankiminer.android.diagnostics

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LogcatCaptureTest {
    @Test
    fun `candidate commands preserve the compatibility fallback order`() {
        assertEquals(
            listOf(
                listOf(
                    "/system/bin/logcat",
                    "-d",
                    "-v",
                    "threadtime",
                    "-v",
                    "year",
                    "-v",
                    "UTC",
                    "-b",
                    "main,crash",
                    "-t",
                    "50000",
                ),
                listOf(
                    "/system/bin/logcat",
                    "-d",
                    "-v",
                    "threadtime",
                    "-b",
                    "main,crash",
                    "-t",
                    "50000",
                ),
                listOf(
                    "/system/bin/logcat",
                    "-d",
                    "-v",
                    "threadtime",
                    "-b",
                    "main",
                    "-t",
                    "50000",
                ),
                listOf(
                    "logcat",
                    "-d",
                    "-v",
                    "threadtime",
                    "-b",
                    "main",
                    "-t",
                    "50000",
                ),
            ),
            LogcatCommand.candidates(),
        )
    }

    @Test
    fun `empty output falls through to the first candidate with content`() =
        runTest {
            val seen = mutableListOf<List<String>>()
            val reader =
                LogcatCommandReader { command, _, maxBytes ->
                    seen += command
                    val bytes =
                        if (seen.size == 1) ByteArray(0) else "recent logcat\n".toByteArray()
                    LogcatReadResult(
                        tail = LogTail.of(ByteArrayInputStream(bytes), maxBytes),
                        exitCode = 0,
                        timedOut = false,
                    )
                }

            val result = LogcatCapture(reader).capture()

            assertEquals(LogcatCaptureStatus.CAPTURED, result.status)
            assertEquals("recent logcat\n", result.text)
            assertEquals(0, result.exitCode)
            assertEquals(LogcatCommand.candidates().take(2), seen)
        }

    @Test
    fun `nonzero output falls through to a successful fallback`() =
        runTest {
            val seen = mutableListOf<List<String>>()
            val reader =
                LogcatCommandReader { command, _, maxBytes ->
                    seen += command
                    val success = seen.size == 2
                    val bytes =
                        if (success) "recent logcat\n".toByteArray() else "logcat: bad option\n".toByteArray()
                    LogcatReadResult(
                        tail = LogTail.of(ByteArrayInputStream(bytes), maxBytes),
                        exitCode = if (success) 0 else 1,
                        timedOut = false,
                    )
                }

            val result = LogcatCapture(reader).capture()

            assertEquals(LogcatCaptureStatus.CAPTURED, result.status)
            assertEquals("recent logcat\n", result.text)
            assertEquals(0, result.exitCode)
            assertEquals(LogcatCommand.candidates().take(2), seen)
        }

    @Test
    fun `nonzero output from every candidate is unavailable`() =
        runTest {
            var attempts = 0
            val reader =
                LogcatCommandReader { _, _, maxBytes ->
                    attempts++
                    LogcatReadResult(
                        tail =
                            LogTail.of(
                                ByteArrayInputStream("logcat: rejected options\n".toByteArray()),
                                maxBytes,
                            ),
                        exitCode = 2,
                        timedOut = false,
                    )
                }

            val result = LogcatCapture(reader).capture()

            assertEquals(LogcatCaptureStatus.UNAVAILABLE, result.status)
            assertEquals("", result.text)
            assertEquals(2, result.exitCode)
            assertEquals(LogcatCommand.candidates().size, attempts)
        }

    @Test
    fun `timeout keeps partial bytes and reports timeout status`() =
        runTest {
            val reader =
                LogcatCommandReader { _, _, maxBytes ->
                    LogcatReadResult(
                        tail =
                            LogTail.of(
                                ByteArrayInputStream("partial before kill\n".toByteArray()),
                                maxBytes,
                            ),
                        exitCode = null,
                        timedOut = true,
                    )
                }

            val result = LogcatCapture(reader).capture()

            assertEquals(LogcatCaptureStatus.TIMEOUT, result.status)
            assertEquals("partial before kill\n", result.text)
            assertNull(result.exitCode)
        }

    @Test
    fun `throwing readers exhaust candidates and return unavailable`() =
        runTest {
            var attempts = 0
            val reader =
                LogcatCommandReader { _, _, _ ->
                    attempts++
                    throw IOException("missing logcat")
                }

            val result = LogcatCapture(reader).capture()

            assertEquals(LogcatCaptureStatus.UNAVAILABLE, result.status)
            assertEquals("", result.text)
            assertNull(result.exitCode)
            assertEquals(LogcatCommand.candidates().size, attempts)
        }

    @Test
    fun `stdin close failure still performs every process teardown step`() =
        runTest {
            val process = StdinCloseFailingProcess()
            val reader = ProcessLogcatCommandReader(start = { process })

            try {
                reader.read(listOf("logcat"), timeoutMillis = 1_000, maxBytes = 1024)
                fail("expected stdin close failure")
            } catch (error: IOException) {
                assertEquals("stdin close failed", error.message)
            }

            assertTrue(process.forciblyDestroyed)
            assertTrue(process.inputClosed)
            assertTrue(process.errorClosed)
            assertEquals(2, process.outputCloseAttempts)
            assertEquals(1, process.timedWaits)
        }

    private class StdinCloseFailingProcess : Process() {
        var forciblyDestroyed = false
            private set
        var inputClosed = false
            private set
        var errorClosed = false
            private set
        var outputCloseAttempts = 0
            private set
        var timedWaits = 0
            private set
        private var alive = true

        private val input = closeTrackingStream { inputClosed = true }
        private val error = closeTrackingStream { errorClosed = true }
        private val output =
            object : OutputStream() {
                override fun write(value: Int) = Unit

                override fun close() {
                    outputCloseAttempts++
                    throw IOException("stdin close failed")
                }
            }

        override fun getOutputStream(): OutputStream = output

        override fun getInputStream(): InputStream = input

        override fun getErrorStream(): InputStream = error

        override fun waitFor(): Int = 0

        override fun waitFor(
            timeout: Long,
            unit: TimeUnit,
        ): Boolean {
            timedWaits++
            return true
        }

        override fun exitValue(): Int = 0

        override fun destroy() {
            alive = false
        }

        override fun destroyForcibly(): Process {
            forciblyDestroyed = true
            alive = false
            return this
        }

        override fun isAlive(): Boolean = alive

        private fun closeTrackingStream(closed: () -> Unit): InputStream =
            object : ByteArrayInputStream(ByteArray(0)) {
                override fun close() {
                    closed()
                    super.close()
                }
            }
    }
}
