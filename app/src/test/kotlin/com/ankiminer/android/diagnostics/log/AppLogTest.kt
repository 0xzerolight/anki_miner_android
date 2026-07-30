package com.ankiminer.android.diagnostics.log

import java.io.IOException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AppLogTest {
    private val recorded = RecordingLogSink()

    @Before
    fun installRecordingSink() {
        AppLog.setMinLevel(LogLevel.INFO)
        // Two installs: the first discards whatever a previous test class left in the pre-install
        // buffer, so the second starts from a known-empty recorder.
        AppLog.install(NoOpSink)
        AppLog.install(recorded)
    }

    @After
    fun detachRecordingSink() {
        AppLog.setMinLevel(LogLevel.INFO)
        AppLog.install(NoOpSink)
    }

    @Test
    fun `debug records are suppressed until the minimum level admits them`() {
        AppLog.d(LogComponent.MINING, "word.scored") { arrayOf("word" to "猫") }

        assertFalse(AppLog.debugEnabled)
        assertEquals(emptyList<String>(), recorded.records)

        AppLog.setMinLevel(LogLevel.DEBUG)
        AppLog.d(LogComponent.MINING, "word.scored") { arrayOf("word" to "猫") }

        assertTrue(AppLog.debugEnabled)
        assertTrue(recorded.records.single().contains(" D run=- c=mining op=word.scored word=\"猫\""))
    }

    @Test
    fun `the debug lambda is never invoked while debug is off`() {
        var evaluated = 0

        AppLog.d(LogComponent.MINING, "word.scored") {
            evaluated++
            arrayOf("n" to evaluated)
        }
        assertEquals(0, evaluated)

        AppLog.setMinLevel(LogLevel.DEBUG)
        AppLog.d(LogComponent.MINING, "word.scored") {
            evaluated++
            arrayOf("n" to evaluated)
        }
        assertEquals(1, evaluated)
    }

    @Test
    fun `install replays everything the pre-install buffer captured, in order`() {
        AppLog.install(PreInstallBufferSink())
        AppLog.i(LogComponent.BOOTSTRAP, "python.start")
        AppLog.e(LogComponent.BOOTSTRAP, "python.initialize", IOException("no such file"))
        AppLog.i(LogComponent.BOOTSTRAP, "python.home")

        val replayed = RecordingLogSink()
        AppLog.install(replayed)

        assertEquals(
            listOf("python.start", "python.initialize", "python.home"),
            replayed.records.map(::opOf),
        )
        assertTrue(replayed.records[1].contains("java.io.IOException: no such file"))
    }

    @Test
    fun `the pre-install buffer keeps the newest records once it is full`() {
        val buffer = PreInstallBufferSink(capacity = 2)
        buffer.write("first")
        buffer.write("second")
        buffer.write("third")

        assertEquals(listOf("second", "third"), buffer.drain())
        assertEquals(emptyList<String>(), buffer.drain())
    }

    @Test
    fun `nested run ids restore the outer value and an absent id renders as a dash`() {
        AppLog.i(LogComponent.MINING, "before")
        LogContext.withRunId("run_ab12cd34") {
            AppLog.i(LogComponent.MINING, "outer")
            LogContext.withRunId("run_ef56ab78") { AppLog.i(LogComponent.MINING, "inner") }
            AppLog.i(LogComponent.MINING, "restored")
        }
        AppLog.i(LogComponent.MINING, "after")

        assertEquals(
            listOf("-", "run_ab12cd34", "run_ef56ab78", "run_ab12cd34", "-"),
            recorded.records.map(::runOf),
        )
        assertNull(LogContext.runId())
    }

    @Test
    fun `failures render their cause chain and suppressed entries on continuation lines`() {
        val suppressed = IOException("closing /tree/文書.txt failed")
        val aggregate = IOException("release failed")
        aggregate.addSuppressed(suppressed)
        val reported = IllegalStateException("job cleanup", aggregate)

        AppLog.e(LogComponent.SAF, "job.close", reported, "fault" to "f7a3c91e")

        val record = recorded.records.single()
        val lines = record.lines()
        assertTrue(lines.first().endsWith(" E run=- c=saf op=job.close fault=f7a3c91e"))
        assertTrue(lines.drop(1).all { it.startsWith("\t") })
        assertTrue(record.contains("\tjava.lang.IllegalStateException: job cleanup"))
        assertTrue(record.contains("\tCaused by: java.io.IOException: release failed"))
        assertTrue(record.contains("\tSuppressed: java.io.IOException: closing /tree/文書.txt failed"))
        assertTrue(record.contains("\n\t    at "))
    }

    @Test
    fun `a cyclic cause chain terminates instead of looping`() {
        val first = RuntimeException("first")
        val second = RuntimeException("second", first)
        first.initCause(second)

        AppLog.e(LogComponent.BRIDGE, "dispatch", first)

        val record = recorded.records.single()
        assertEquals(2, record.split("Caused by: ").size - 1)
        assertTrue(record.contains("[circular] java.lang.RuntimeException"))
    }

    @Test
    fun `a value carrying quotes, newlines and control characters stays on one line`() {
        AppLog.w(
            LogComponent.MEDIA,
            "probe",
            null,
            "detail" to "he said \"go\"\nsecond\u0007 line\\here",
        )

        val record = recorded.records.single()
        assertEquals(1, record.lines().size)
        assertTrue(record.contains("detail=\""))
        assertTrue(record.contains("\\\"go\\\""))
        assertTrue(record.contains("\\nsecond line"))
        assertTrue(record.contains("line\\\\here"))
        assertFalse(record.contains('\u0007'))
    }

    @Test
    fun `a state transition names the machine as its op`() {
        AppLog.state(LogComponent.MINING, "phase", from = "PROMOTING", to = "RUNNING", "ms" to 142)

        assertTrue(
            recorded.records.single()
                .endsWith(" I run=- c=mining op=phase from=PROMOTING to=RUNNING ms=142"),
        )
    }

    @Test
    fun `boundary rethrows the original failure unchanged`() {
        AppLog.setMinLevel(LogLevel.DEBUG)
        val thrown = IllegalArgumentException("bad word")

        val caught =
            try {
                AppLog.boundary(LogComponent.MINING, "tokenize") { throw thrown }
            } catch (failure: IllegalArgumentException) {
                failure
            }

        assertEquals(thrown, caught)
        assertEquals(listOf("tokenize", "tokenize"), recorded.records.map(::opOf))
        assertTrue(recorded.records[0].contains("at=enter"))
        assertTrue(recorded.records[1].contains("at=exit outcome=error ms="))
    }

    @Test
    fun `boundary returns the block result and is silent while debug is off`() {
        assertEquals(7, AppLog.boundary(LogComponent.MINING, "tokenize") { 7 })
        assertEquals(emptyList<String>(), recorded.records)
    }

    private fun opOf(record: String): String = fieldOf(record, "op")

    private fun runOf(record: String): String = fieldOf(record, "run")

    private fun fieldOf(
        record: String,
        key: String,
    ): String = record.lines().first().substringAfter(" $key=").substringBefore(' ')
}
