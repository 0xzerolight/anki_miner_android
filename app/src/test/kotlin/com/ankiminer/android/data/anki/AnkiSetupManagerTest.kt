package com.ankiminer.android.data.anki

import com.ankiminer.android.runStartupRecoverySequence
import com.ankiminer.android.anki.provider.AnkiCancellation
import com.ankiminer.android.anki.provider.AnkiReadFailure
import com.ankiminer.android.anki.provider.AnkiPendingRemediation
import com.ankiminer.android.anki.provider.AnkiRemediationInventory
import com.ankiminer.android.anki.provider.AnkiRemediationType
import com.ankiminer.android.anki.provider.ModelSummary
import com.ankiminer.android.anki.provider.NoteTypeSetupStatus
import com.ankiminer.android.data.RuntimeWorkCoordinator
import com.ankiminer.android.localization.testStringResourceResolver
import java.util.ArrayDeque
import java.util.concurrent.Executor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
                        compactEvidence = null,
                        createdAtMs = 1L,
                        updatedAtMs = 1L,
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
        assertEquals(1, backend.inventoryCalls)
    }

    @Test
    fun `typed provider discovery failure keeps its remediation taxonomy`() {
        val typedFailure =
            AnkiReadFailure(
                code = com.ankiminer.android.anki.protocol.AnkiErrorCode.TIMEOUT,
                retryable = true,
                stableMessage = "The AnkiDroid read timed out",
                providerErrorReason =
                    com.ankiminer.android.anki.provider.NoteTypeProviderErrorReason.TIMEOUT,
            )
        val manager =
            ProcessAnkiSetupManager(
                FakeBackend(listFailure = typedFailure),
                Executor(Runnable::run),
                RuntimeWorkCoordinator(),
                testStringResourceResolver,
            )

        manager.refresh("Lapis", mapOf("word" to "Expression"))

        val status = manager.state.value.noteTypeStatus as NoteTypeSetupStatus.ProviderError
        assertEquals(com.ankiminer.android.anki.protocol.AnkiErrorCode.TIMEOUT, status.code)
        assertEquals(
            com.ankiminer.android.anki.provider.NoteTypeProviderErrorReason.TIMEOUT,
            status.reason,
        )
        assertEquals(true, status.retryable)
        assertEquals("The AnkiDroid read timed out", status.stableMessage)
        assertEquals("timeout", requireNotNull(manager.state.value.failure).code)
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
        assertEquals(1, backend.listCalls)
    }

    @Test
    fun `queued refresh keeps newest target and await completes after publication`() =
        runTest {
            val executor = QueuedExecutor()
            val backend = FakeBackend()
            val manager =
                ProcessAnkiSetupManager(
                    backend,
                    executor,
                    RuntimeWorkCoordinator(),
                    testStringResourceResolver,
                )

            manager.refresh("Old", mapOf("word" to "Old field"))
            val newest =
                async {
                    manager.refreshAndAwait(
                        "Newest",
                        mapOf("word" to "Expression"),
                        cardTypeMarkerField = "IsClickCard",
                    )
                }
            runCurrent()

            assertEquals(1, executor.queued.size)
            executor.runNext()
            assertEquals(1, executor.queued.size)
            executor.runNext()
            newest.await()

            assertEquals(2, backend.listCalls)
            assertEquals("Newest", backend.lastNoteType)
            assertEquals(mapOf("word" to "Expression"), backend.lastFieldMap)
            assertEquals("IsClickCard", backend.lastCardTypeMarkerField)
            assertNull(manager.state.value.operation)
        }

    @Test
    fun `startup sequence never admits mining before recovery inventory refresh completes`() =
        runTest {
            val setupGate = CompletableDeferred<Unit>()
            val events = mutableListOf<String>()

            val startup =
                launch {
                    runStartupRecoverySequence(
                        recoverResources = { events += "resources" },
                        refreshSetup = {
                            events += "setup-start"
                            setupGate.await()
                            events += "setup-published"
                        },
                        refreshAdmission = { events += "admission" },
                    )
                }
            runCurrent()

            assertEquals(listOf("resources", "setup-start"), events)
            setupGate.complete(Unit)
            advanceUntilIdle()
            startup.join()

            assertEquals(
                listOf("resources", "setup-start", "setup-published", "admission"),
                events,
            )
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
        private val failList: Boolean = false,
        private val failInventory: Boolean = false,
        private val listFailure: RuntimeException? = null,
    ) : AnkiSetupBackend {
        var listCalls = 0
        var inventoryCalls = 0
        var verifyCalls = 0
        var lastNoteType: String? = null
        var lastFieldMap: Map<String, String> = emptyMap()
        var lastCardTypeMarkerField: String? = null

        override fun listNoteTypes(cancellation: AnkiCancellation): List<ModelSummary> {
            listCalls += 1
            listFailure?.let { throw it }
            if (failList) error("provider unavailable")
            return noteTypes
        }

        override fun listDeckNames(cancellation: AnkiCancellation): List<String> = deckNames

        override fun verifyNoteType(
            noteType: String?,
            fieldMap: Map<String, String>,
            cardTypeMarkerField: String?,
            cancellation: AnkiCancellation,
        ): NoteTypeSetupStatus {
            verifyCalls += 1
            lastNoteType = noteType
            lastFieldMap = fieldMap
            lastCardTypeMarkerField = cardTypeMarkerField
            return status
        }

        override fun remediationInventory(
            cancellation: AnkiCancellation,
        ): AnkiRemediationInventory {
            inventoryCalls += 1
            if (failInventory) error("journal unavailable")
            return remediations
        }

    }

    private fun pendingRemediation() =
        AnkiPendingRemediation(
            id = 5L,
            type = AnkiRemediationType.MEDIA_COMMIT_UNCERTAIN,
            compactEvidence = null,
            createdAtMs = 1L,
            updatedAtMs = 1L,
        )
}
