package com.ankiminer.android.diagnostics.log

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
        AppLog.d(LogComponent.MINING, "word.scored") {
            arrayOf("word" to "猫", "outcome" to "ok")
        }

        assertFalse(AppLog.debugEnabled)
        assertEquals(emptyList<String>(), recorded.records)

        AppLog.setMinLevel(LogLevel.DEBUG)
        AppLog.d(LogComponent.MINING, "word.scored") {
            arrayOf("word" to "猫", "outcome" to "ok")
        }

        assertTrue(AppLog.debugEnabled)
        assertTrue(recorded.records.single().contains(" D run=- c=mining op=word.scored word=\"猫\""))
    }

    @Test
    fun `the debug lambda is never invoked while debug is off`() {
        var evaluated = 0

        AppLog.d(LogComponent.MINING, "word.scored") {
            evaluated++
            arrayOf("n" to evaluated, "outcome" to "ok")
        }
        assertEquals(0, evaluated)

        AppLog.setMinLevel(LogLevel.DEBUG)
        AppLog.d(LogComponent.MINING, "word.scored") {
            evaluated++
            arrayOf("n" to evaluated, "outcome" to "ok")
        }
        assertEquals(1, evaluated)
    }

    @Test
    fun `install replays everything the pre-install buffer captured, in order`() {
        AppLog.install(PreInstallBufferSink())
        AppLog.i(LogComponent.BOOTSTRAP, "python.start", "outcome" to "ok")
        AppLog.e(
            LogComponent.BOOTSTRAP,
            "python.initialize",
            IOException("no such file"),
            "outcome" to "fail",
        )
        AppLog.i(LogComponent.BOOTSTRAP, "python.home", "outcome" to "ok")

        val replayed = RecordingLogSink()
        AppLog.install(replayed)

        assertEquals(
            listOf("python.start", "python.initialize", "python.home"),
            replayed.records.map(::opOf),
        )
        assertTrue(replayed.records[1].contains("java.io.IOException: no such file"))
    }

    @Test
    fun `a record that captured the pre-install sink before install is still replayed`() {
        AppLog.install(PreInstallBufferSink())
        val rendering = CountDownLatch(1)
        val continueRendering = CountDownLatch(1)
        val emitting =
            Thread {
                AppLog.i(
                    LogComponent.BOOTSTRAP,
                    "python.initialize",
                    "detail" to
                        object {
                            override fun toString(): String {
                                rendering.countDown()
                                continueRendering.await(5, TimeUnit.SECONDS)
                                return "late record"
                            }
                        },
                    "outcome" to "ok",
                )
            }
        emitting.start()

        try {
            assertTrue(rendering.await(5, TimeUnit.SECONDS))
            val replayed = RecordingLogSink()
            AppLog.install(replayed)
            continueRendering.countDown()
            emitting.join(5_000)

            assertFalse(emitting.isAlive)
            assertEquals(listOf("python.initialize"), replayed.records.map(::opOf))
        } finally {
            continueRendering.countDown()
            emitting.join(5_000)
        }
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
        AppLog.i(LogComponent.MINING, "before", "outcome" to "ok")
        LogContext.withRunId("run_ab12cd34") {
            AppLog.i(LogComponent.MINING, "outer", "outcome" to "ok")
            LogContext.withRunId("run_ef56ab78") {
                AppLog.i(LogComponent.MINING, "inner", "outcome" to "ok")
            }
            AppLog.i(LogComponent.MINING, "restored", "outcome" to "ok")
        }
        AppLog.i(LogComponent.MINING, "after", "outcome" to "ok")

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

        AppLog.e(
            LogComponent.SAF,
            "job.close",
            reported,
            "fault" to "f7a3c91e",
            "outcome" to "fail",
        )

        val record = recorded.records.single()
        val lines = record.lines()
        assertTrue(lines.first().endsWith(" E run=- c=saf op=job.close fault=f7a3c91e outcome=fail"))
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

        AppLog.e(LogComponent.BRIDGE, "dispatch", first, "outcome" to "fail")

        val record = recorded.records.single()
        assertEquals(2, record.split("Caused by: ").size - 1)
        assertTrue(record.contains("[circular] java.lang.RuntimeException"))
    }

    @Test
    fun `a stack deeper than the frame cap is truncated exactly once`() {
        val deep = RuntimeException("deep")
        deep.stackTrace =
            Array(250) { index -> StackTraceElement("Deep", "frame$index", "Deep.kt", index) }

        AppLog.e(LogComponent.BRIDGE, "dispatch", deep, "outcome" to "fail")

        val record = recorded.records.single()
        assertEquals(200, record.split("\n\t    at ").size - 1)
        assertEquals(1, record.split("... frames truncated").size - 1)
    }

    @Test
    fun `the timestamp length constant still matches what is rendered`() {
        // LogcatSink indexes the level character past this constant to pick a logcat priority; a
        // format change that nobody mirrored here silently downgrades every logcat line to INFO.
        AppLog.w(LogComponent.DIAG, "probe", IOException("probe"), "outcome" to "fail")

        val record = recorded.records.single()
        assertTrue(
            record.take(TIMESTAMP_LENGTH)
                .matches(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z""")),
        )
        assertEquals(' ', record[TIMESTAMP_LENGTH])
        assertEquals(LogLevel.WARN.code, record[TIMESTAMP_LENGTH + 1])
    }

    @Test
    fun `a value carrying quotes, newlines and control characters stays on one line`() {
        AppLog.i(
            LogComponent.MEDIA,
            "probe",
            "detail" to "he said \"go\"\r\nsecond\u0007 line\\here",
            "outcome" to "ok",
        )

        val record = recorded.records.single()
        assertEquals(1, record.lines().size)
        assertFalse(record.contains('\r'))
        assertTrue(record.contains("detail=\""))
        assertTrue(record.contains("\\\"go\\\""))
        assertTrue(record.contains("\\nsecond line"))
        assertTrue(record.contains("line\\\\here"))
        assertFalse(record.contains('\u0007'))
    }

    @Test
    fun `a state transition names the machine as its op`() {
        AppLog.state(
            LogComponent.MINING,
            "phase",
            from = "PROMOTING",
            to = "RUNNING",
            "ms" to 142,
            "outcome" to "ok",
        )

        assertTrue(
            recorded.records.single()
                .endsWith(" I run=- c=mining op=phase from=PROMOTING to=RUNNING ms=142 outcome=ok"),
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
        assertTrue(recorded.records[1].contains("at=exit outcome=fail ms="))
    }

    @Test
    fun `boundary returns the block result and is silent while debug is off`() {
        assertEquals(7, AppLog.boundary(LogComponent.MINING, "tokenize") { 7 })
        assertEquals(emptyList<String>(), recorded.records)
    }

    @Test
    fun `ignored failures always carry the ignored outcome`() {
        AppLog.setMinLevel(LogLevel.DEBUG)

        AppLog.ignored(
            LogComponent.SAF,
            "job.close",
            "aggregate_owns_failure",
            IOException("close failed"),
        )

        val record = recorded.records.single()
        assertTrue(record.contains(" D run=- c=saf op=job.close outcome=ignored reason=aggregate_owns_failure"))
        assertTrue(record.contains("java.io.IOException: close failed"))
    }

    @Test
    fun `a failure whose message cannot be read is reported, not rethrown`() {
        // The real shape: a Chaquopy PyException whose JNI-backed getMessage() fails because the
        // interpreter is already dead — which is the failure being logged. Propagating out of here
        // would skip the caller's own error handling, and PythonRuntimeBootstrapGate would never
        // complete its future.
        AppLog.e(
            LogComponent.BOOTSTRAP,
            "python.initialize",
            UnreadableFailure(),
            "outcome" to "fail",
        )

        val record = recorded.records.single()
        assertTrue(record.lines().drop(1).all { it.startsWith('\t') })
        assertTrue(record.contains(" E run=- c=bootstrap op=python.initialize outcome=fail unrenderable="))
        assertTrue(record.contains("UnreadableFailure"))
        assertTrue(record.contains("renderFault=java.lang.IllegalStateException"))
    }

    @Test
    fun `an unrenderable failure keeps the active run id`() {
        LogContext.withRunId("run_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa") {
            AppLog.e(
                LogComponent.BRIDGE,
                "dispatch",
                UnreadableFailure(),
                "outcome" to "fail",
            )
        }

        assertEquals(
            "run_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            runOf(recorded.records.single()),
        )
    }

    private class UnreadableFailure : RuntimeException() {
        override val message: String
            get() = throw IllegalStateException("interpreter is gone")
    }

    private fun opOf(record: String): String = fieldOf(record, "op")

    private fun runOf(record: String): String = fieldOf(record, "run")

    private fun fieldOf(
        record: String,
        key: String,
    ): String = record.lines().first().substringAfter(" $key=").substringBefore(' ')
}
