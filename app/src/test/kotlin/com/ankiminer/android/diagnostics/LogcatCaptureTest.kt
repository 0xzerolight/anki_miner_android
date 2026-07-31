package com.ankiminer.android.diagnostics

import java.io.ByteArrayInputStream
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
