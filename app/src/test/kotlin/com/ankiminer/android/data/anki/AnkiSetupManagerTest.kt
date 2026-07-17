package com.ankiminer.android.data.anki

import com.ankiminer.android.anki.provider.AnkiCancellation
import com.ankiminer.android.anki.provider.AnkiMinerModelProvisioningResult
import com.ankiminer.android.anki.provider.AnkiMinerModelReadyOrigin
import com.ankiminer.android.anki.provider.AnkiRemediationCommand
import com.ankiminer.android.anki.provider.AnkiRemediationInventory
import com.ankiminer.android.data.RuntimeWorkCoordinator
import java.util.ArrayDeque
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnkiSetupManagerTest {
    @Test
    fun `read only refresh remains available while mining owns mutation exclusion`() {
        val coordinator = RuntimeWorkCoordinator()
        val mining = requireNotNull(coordinator.tryAcquire(RuntimeWorkCoordinator.Kind.MINING))
        val backend = FakeBackend()
        val manager = ProcessAnkiSetupManager(backend, Executor(Runnable::run), coordinator)

        manager.refresh()

        assertEquals(AnkiMinerModelProvisioningResult.Missing, manager.state.value.model)
        assertEquals(1, backend.inspectCalls)
        assertNull(manager.state.value.failure)
        mining.close()
    }

    @Test
    fun `model provisioning fails closed while another runtime mutation is active`() {
        val coordinator = RuntimeWorkCoordinator()
        val mining = requireNotNull(coordinator.tryAcquire(RuntimeWorkCoordinator.Kind.MINING))
        val backend = FakeBackend()
        val manager = ProcessAnkiSetupManager(backend, Executor(Runnable::run), coordinator)

        manager.provisionModel()

        assertEquals("runtime_busy", requireNotNull(manager.state.value.failure).code)
        assertEquals(0, backend.provisionCalls)
        assertNull(manager.state.value.operation)
        mining.close()
    }

    @Test
    fun `one queued setup write owns the process lease and duplicate commands are ignored`() {
        val coordinator = RuntimeWorkCoordinator()
        val executor = QueuedExecutor()
        val backend = FakeBackend()
        val manager = ProcessAnkiSetupManager(backend, executor, coordinator)

        manager.provisionModel()
        manager.reconcileInterruptedWork()

        assertEquals(AnkiSetupOperation.PROVISIONING_MODEL, manager.state.value.operation)
        assertEquals(RuntimeWorkCoordinator.Kind.ANKI_SETUP, coordinator.activeKind())
        assertEquals(1, executor.queued.size)
        executor.runNext()

        assertEquals(1, backend.provisionCalls)
        assertEquals(0, backend.reconcileCalls)
        assertTrue(manager.state.value.model is AnkiMinerModelProvisioningResult.Ready)
        assertNull(manager.state.value.operation)
        assertNull(coordinator.activeKind())
    }

    @Test
    fun `backend failure is UI safe and always releases setup exclusion`() {
        val coordinator = RuntimeWorkCoordinator()
        val backend = FakeBackend(failProvision = true)
        val manager = ProcessAnkiSetupManager(backend, Executor(Runnable::run), coordinator)

        manager.provisionModel()

        assertEquals("anki_setup_failed", requireNotNull(manager.state.value.failure).code)
        assertNull(manager.state.value.operation)
        assertNull(coordinator.activeKind())
    }

    private class QueuedExecutor : Executor {
        val queued = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            queued.addLast(command)
        }

        fun runNext() = queued.removeFirst().run()
    }

    private class FakeBackend(
        private val failProvision: Boolean = false,
    ) : AnkiSetupBackend {
        var inspectCalls = 0
        var provisionCalls = 0
        var reconcileCalls = 0

        override fun inspectModel(cancellation: AnkiCancellation): AnkiMinerModelProvisioningResult {
            inspectCalls += 1
            return AnkiMinerModelProvisioningResult.Missing
        }

        override fun provisionModel(cancellation: AnkiCancellation): AnkiMinerModelProvisioningResult {
            provisionCalls += 1
            if (failProvision) error("provider detail must not reach UI")
            return AnkiMinerModelProvisioningResult.Ready(
                modelId = 9L,
                origin = AnkiMinerModelReadyOrigin.PROVISIONED,
            )
        }

        override fun remediationInventory(
            cancellation: AnkiCancellation,
        ): AnkiRemediationInventory = AnkiRemediationInventory(emptyList())

        override fun reconcileInterruptedWork(
            cancellation: AnkiCancellation,
        ): AnkiRemediationInventory {
            reconcileCalls += 1
            return AnkiRemediationInventory(emptyList())
        }

        override fun performRemediation(
            command: AnkiRemediationCommand,
            cancellation: AnkiCancellation,
        ): AnkiRemediationInventory = AnkiRemediationInventory(emptyList())
    }
}
