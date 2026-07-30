package com.ankiminer.android.engine

import com.ankiminer.android.diagnostics.log.AppLog
import com.ankiminer.android.diagnostics.log.LogLevel
import com.ankiminer.android.diagnostics.log.NoOpSink
import com.ankiminer.android.diagnostics.log.RecordingLogSink
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PythonRuntimeBootstrapTest {
    @Test
    fun `enqueue is nonblocking and readiness becomes stable after the first task`() {
        val queued = AtomicReference<Runnable>()
        val initialized = AtomicBoolean(false)
        val runtime = Any()
        val gate = PythonRuntimeBootstrapGate<Any> { "/files" }

        gate.enqueueFirst(Executor(queued::set)) {
            initialized.set(true)
            runtime
        }

        assertFalse(initialized.get())
        assertEquals(PythonRuntimeReadiness.Pending, gate.readiness.value)
        queued.get().run()
        assertTrue(initialized.get())
        assertEquals(PythonRuntimeReadiness.Ready("/files"), gate.readiness.value)
        assertSame(runtime, gate.await { })
    }

    @Test
    fun `a failed diagnostics log level hop still leaves the runtime ready`() {
        // initialize() runs inside the gate's catch on a one-shot future, so a throw from the
        // preference read or from the dispatch would take Python down for the whole process --
        // over a setting whose only job is to make logs chattier.
        val queued = AtomicReference<Runnable>()
        val gate = PythonRuntimeBootstrapGate<String> { it }

        gate.enqueueFirst(Executor(queued::set)) {
            applyStoredPythonLogLevel(
                readVerbose = { throw IOException("datastore is unreadable") },
                dispatch = { throw AssertionError("dispatch must not be reached") },
            )
            applyStoredPythonLogLevel(
                readVerbose = { true },
                dispatch = { throw IllegalStateException("the runtime rejected the request") },
            )
            "/files"
        }
        try {
            queued.get().run()

            assertEquals(PythonRuntimeReadiness.Ready("/files"), gate.readiness.value)
            assertEquals("/files", gate.await { })
        } finally {
            // The second hop raised the process-wide Kotlin level before its dispatch failed.
            AppLog.setMinLevel(LogLevel.INFO)
        }
    }

    @Test
    fun `a log level the runtime did not apply is reported without throwing`() {
        val requests = mutableListOf<String>()
        val recorded = RecordingLogSink()
        AppLog.install(NoOpSink)
        AppLog.install(recorded)
        try {
            // Confirming a different level than the one requested is a failure, not a success: a
            // tester whose DEBUG request quietly landed as INFO collects a bundle with nothing
            // extra in it and no sign of why. Asserting only the encoded request would pass with
            // the confirmation check deleted, so the WARN record is the assertion that matters.
            applyPythonLogLevelSafely(LogLevel.DEBUG) { raw ->
                requests += raw
                """{"schemaVersion":1,"type":"diagnostics.loglevel.applied","payload":{"level":"info"}}"""
            }

            assertEquals(listOf(BridgeJsonCodec.encodeDiagnosticsLogLevelSet("debug")), requests)
            val record = recorded.records.single()
            assertTrue(record, record.contains(" W "))
            assertTrue(record, record.contains("c=diag op=python.loglevel level=DEBUG"))
        } finally {
            AppLog.install(NoOpSink)
        }
    }

    @Test
    fun `a confirmed log level is applied without a warning`() {
        val recorded = RecordingLogSink()
        AppLog.install(NoOpSink)
        AppLog.install(recorded)
        try {
            applyPythonLogLevelSafely(LogLevel.DEBUG) {
                """{"schemaVersion":1,"type":"diagnostics.loglevel.applied","payload":{"level":"debug"}}"""
            }

            assertEquals(emptyList<String>(), recorded.records)
        } finally {
            AppLog.install(NoOpSink)
        }
    }

    @Test
    fun `initialization failure is sticky and crosses the boundary generically`() {
        val queued = AtomicReference<Runnable>()
        val gate = PythonRuntimeBootstrapGate<Any> { error("unreachable") }
        gate.enqueueFirst(Executor(queued::set)) { error("private detail") }
        queued.get().run()

        assertEquals(PythonRuntimeReadiness.Failed, gate.readiness.value)
        val failure =
            try {
                gate.await { }
                throw AssertionError("failure expected")
            } catch (expected: PythonRuntimeUnavailableException) {
                expected
            }
        assertEquals("The embedded Python runtime is unavailable", failure.message)
        assertEquals("private detail", failure.cause?.message)
    }
}
