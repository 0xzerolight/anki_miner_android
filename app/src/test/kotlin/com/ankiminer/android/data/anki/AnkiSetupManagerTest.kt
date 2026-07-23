package com.ankiminer.android.data.anki

import com.ankiminer.android.anki.provider.AnkiCancellation
import com.ankiminer.android.anki.provider.AnkiPendingRemediation
import com.ankiminer.android.anki.provider.AnkiRemediationCommand
import com.ankiminer.android.anki.provider.AnkiRemediationInventory
import com.ankiminer.android.anki.provider.AnkiRemediationSummary
import com.ankiminer.android.anki.provider.AnkiRemediationType
import com.ankiminer.android.anki.provider.ModelSummary
import com.ankiminer.android.anki.provider.NoteTypeSetupStatus
import com.ankiminer.android.data.RuntimeWorkCoordinator
import com.ankiminer.android.localization.testStringResourceResolver
import java.util.ArrayDeque
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnkiSetupManagerTest {
    @Test
    fun `read only refresh publishes note types status and remediations while mining owns exclusion`() {
        val coordinator = RuntimeWorkCoordinator()
        val mining = requireNotNull(coordinator.tryAcquire(RuntimeWorkCoordinator.Kind.MINING))
        val available = listOf(ModelSummary(24L, "Lapis", listOf("Expression", "Sentence")))
        val remediations =
            AnkiRemediationInventory(
                listOf(
                    AnkiPendingRemediation(
                        id = 5L,
                        type = AnkiRemediationType.MEDIA_COMMIT_UNCERTAIN,
                        summaryReason = AnkiRemediationSummary.MEDIA_COMMIT_UNCERTAIN,
                        title = "Media save needs review",
                        summary = "Review the media write",
                        compactEvidence = null,
                        createdAtMs = 1L,
                        updatedAtMs = 1L,
                        availableActions = emptySet(),
                    ),
                ),
            )
        val backend =
            FakeBackend(
                noteTypes = available,
                deckNames = listOf("Default", "Japanese", "Japanese::Known"),
                status = NoteTypeSetupStatus.Verified(modelId = 24L),
                remediations = remediations,
            )
        val manager =
            ProcessAnkiSetupManager(backend, Executor(Runnable::run), coordinator, testStringResourceResolver)

        manager.refresh("Lapis", mapOf("word" to "Expression"))

        val state = manager.state.value
        assertEquals(available, state.availableNoteTypes)
        assertEquals(listOf("Default", "Japanese", "Japanese::Known"), state.availableDeckNames)
        assertEquals(NoteTypeSetupStatus.Verified(24L), state.noteTypeStatus)
        assertEquals(remediations, state.remediations)
        assertEquals(AnkiRecoveryInventoryStatus.AVAILABLE, state.recoveryInventoryStatus)
        assertEquals(1, backend.listCalls)
        assertEquals(1, backend.verifyCalls)
        assertEquals("Lapis", backend.lastNoteType)
        assertEquals(mapOf("word" to "Expression"), backend.lastFieldMap)
        assertNull(state.failure)
        assertNull(state.operation)
        mining.close()
    }

    @Test
    fun `provider discovery failure keeps local remediation inventory visible`() {
        val remediations = AnkiRemediationInventory(listOf(pendingRemediation()))
        val backend = FakeBackend(remediations = remediations, failList = true)
        val manager =
            ProcessAnkiSetupManager(
                backend,
                Executor(Runnable::run),
                RuntimeWorkCoordinator(),
                testStringResourceResolver,
            )

        manager.refresh("Lapis", mapOf("word" to "Expression"))

        assertEquals(remediations, manager.state.value.remediations)
        assertEquals(
            AnkiRecoveryInventoryStatus.AVAILABLE,
            manager.state.value.recoveryInventoryStatus,
        )
        val failure = requireNotNull(manager.state.value.failure)
        assertEquals("anki_provider_unavailable", failure.code)
        assertEquals(AnkiSetupFailureOrigin.TARGET, failure.origin)
        assertEquals(AnkiSetupFailureAction.RETRY, failure.action)
        assertEquals(1, backend.inventoryCalls)
    }

    @Test
    fun `local inventory failure does not hide provider discovery`() {
        val available = listOf(ModelSummary(24L, "Lapis", listOf("Expression")))
        val backend =
            FakeBackend(
                noteTypes = available,
                status = NoteTypeSetupStatus.Verified(24L),
                failInventory = true,
            )
        val manager =
            ProcessAnkiSetupManager(
                backend,
                Executor(Runnable::run),
                RuntimeWorkCoordinator(),
                testStringResourceResolver,
            )

        manager.refresh("Lapis", mapOf("word" to "Expression"))

        assertEquals(available, manager.state.value.availableNoteTypes)
        assertEquals(NoteTypeSetupStatus.Verified(24L), manager.state.value.noteTypeStatus)
        assertEquals(
            AnkiRecoveryInventoryStatus.UNAVAILABLE,
            manager.state.value.recoveryInventoryStatus,
        )
        assertEquals(
            "anki_recovery_inventory_unavailable",
            requireNotNull(manager.state.value.recoveryFailure).code,
        )
        assertEquals(
            AnkiSetupFailureOrigin.RECOVERY,
            requireNotNull(manager.state.value.recoveryFailure).origin,
        )
        assertEquals(1, backend.listCalls)
    }

    @Test
    fun `explicit setup writes fail closed while another runtime mutation is active`() {
        val coordinator = RuntimeWorkCoordinator()
        val mining = requireNotNull(coordinator.tryAcquire(RuntimeWorkCoordinator.Kind.MINING))
        val backend = FakeBackend()
        val manager =
            ProcessAnkiSetupManager(backend, Executor(Runnable::run), coordinator, testStringResourceResolver)

        manager.reconcileInterruptedWork()

        assertEquals("runtime_busy", requireNotNull(manager.state.value.failure).code)
        assertEquals(
            AnkiSetupFailureOrigin.RECOVERY,
            requireNotNull(manager.state.value.failure).origin,
        )
        assertEquals(
            AnkiSetupFailureAction.RESOLVE,
            requireNotNull(manager.state.value.failure).action,
        )
        assertEquals(0, backend.reconcileCalls)
        assertNull(manager.state.value.operation)

        manager.performRemediation(AnkiRemediationCommand.RetryStagingCleanup(7L))

        assertEquals("runtime_busy", requireNotNull(manager.state.value.failure).code)
        assertEquals(0, backend.performCalls)
        assertNull(manager.state.value.operation)
        mining.close()
    }

    @Test
    fun `one queued setup write owns the process lease and duplicate commands are ignored`() {
        val coordinator = RuntimeWorkCoordinator()
        val executor = QueuedExecutor()
        val backend = FakeBackend()
        val manager = ProcessAnkiSetupManager(backend, executor, coordinator, testStringResourceResolver)

        manager.reconcileInterruptedWork()
        manager.performRemediation(AnkiRemediationCommand.RetryStagingCleanup(7L))

        assertEquals(AnkiSetupOperation.RECONCILING, manager.state.value.operation)
        assertEquals(RuntimeWorkCoordinator.Kind.ANKI_SETUP, coordinator.activeKind.value)
        assertEquals(1, executor.queued.size)
        executor.runNext()

        assertEquals(1, backend.reconcileCalls)
        assertEquals(0, backend.performCalls)
        assertNull(manager.state.value.operation)
        assertNull(coordinator.activeKind.value)
    }

    @Test
    fun `backend failure is UI safe and always releases setup exclusion`() {
        val coordinator = RuntimeWorkCoordinator()
        val backend = FakeBackend(failReconcile = true)
        val manager =
            ProcessAnkiSetupManager(backend, Executor(Runnable::run), coordinator, testStringResourceResolver)

        manager.reconcileInterruptedWork()

        assertEquals("anki_setup_failed", requireNotNull(manager.state.value.failure).code)
        assertNull(manager.state.value.operation)
        assertNull(coordinator.activeKind.value)
    }

    private class QueuedExecutor : Executor {
        val queued = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            queued.addLast(command)
        }

        fun runNext() = queued.removeFirst().run()
    }

    private class FakeBackend(
        private val noteTypes: List<ModelSummary> = emptyList(),
        private val deckNames: List<String> = emptyList(),
        private val status: NoteTypeSetupStatus = NoteTypeSetupStatus.NotSelected,
        private val remediations: AnkiRemediationInventory = AnkiRemediationInventory(emptyList()),
        private val failReconcile: Boolean = false,
        private val failList: Boolean = false,
        private val failInventory: Boolean = false,
    ) : AnkiSetupBackend {
        var listCalls = 0
        var inventoryCalls = 0
        var verifyCalls = 0
        var reconcileCalls = 0
        var performCalls = 0
        var lastNoteType: String? = null
        var lastFieldMap: Map<String, String> = emptyMap()

        override fun listNoteTypes(cancellation: AnkiCancellation): List<ModelSummary> {
            listCalls += 1
            if (failList) error("provider unavailable")
            return noteTypes
        }

        override fun listDeckNames(cancellation: AnkiCancellation): List<String> = deckNames

        override fun verifyNoteType(
            noteType: String?,
            fieldMap: Map<String, String>,
            cancellation: AnkiCancellation,
        ): NoteTypeSetupStatus {
            verifyCalls += 1
            lastNoteType = noteType
            lastFieldMap = fieldMap
            return status
        }

        override fun remediationInventory(
            cancellation: AnkiCancellation,
        ): AnkiRemediationInventory {
            inventoryCalls += 1
            if (failInventory) error("journal unavailable")
            return remediations
        }

        override fun reconcileInterruptedWork(
            cancellation: AnkiCancellation,
        ): AnkiRemediationInventory {
            reconcileCalls += 1
            if (failReconcile) error("provider detail must not reach UI")
            return remediations
        }

        override fun performRemediation(
            command: AnkiRemediationCommand,
            cancellation: AnkiCancellation,
        ): AnkiRemediationInventory {
            performCalls += 1
            return remediations
        }
    }

    private fun pendingRemediation() =
        AnkiPendingRemediation(
            id = 5L,
            type = AnkiRemediationType.MEDIA_COMMIT_UNCERTAIN,
            summaryReason = AnkiRemediationSummary.MEDIA_COMMIT_UNCERTAIN,
            title = "Media save needs review",
            summary = "Review the media write",
            compactEvidence = null,
            createdAtMs = 1L,
            updatedAtMs = 1L,
            availableActions = emptySet(),
        )
}
