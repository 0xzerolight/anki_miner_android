package com.ankiminer.android.engine

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
