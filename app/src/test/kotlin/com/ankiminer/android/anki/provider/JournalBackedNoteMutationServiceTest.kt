package com.ankiminer.android.anki.provider

import com.ankiminer.android.anki.journal.ActiveNoteMaterialization
import com.ankiminer.android.anki.journal.ActiveNoteMaterializationRefused
import com.ankiminer.android.anki.journal.ActiveNoteTermination
import com.ankiminer.android.anki.journal.AlignedResult
import com.ankiminer.android.anki.journal.ChildState
import com.ankiminer.android.anki.journal.ChildlessRoutingOutcome
import com.ankiminer.android.anki.journal.JournalCorruptionException
import com.ankiminer.android.anki.journal.JournalRequest
import com.ankiminer.android.anki.journal.JournalResponse
import com.ankiminer.android.anki.journal.MutationCommand
import com.ankiminer.android.anki.journal.NoteRoutingPhase
import com.ankiminer.android.anki.journal.ParentKey
import com.ankiminer.android.anki.journal.ParentOperation
import com.ankiminer.android.anki.journal.ParentRecord
import com.ankiminer.android.anki.journal.ParentState
import com.ankiminer.android.anki.journal.PreparedRoutingFailure
import com.ankiminer.android.anki.journal.ProviderReceipt
import com.ankiminer.android.anki.journal.ReplayResult
import com.ankiminer.android.anki.journal.RoutingIntentDraft
import com.ankiminer.android.anki.journal.RoutingIntentRecord
import com.ankiminer.android.anki.journal.RoutingIntentState
import com.ankiminer.android.anki.protocol.CollectionCreateDuplicateScope
import com.ankiminer.android.anki.protocol.CommittedFailedNote
import com.ankiminer.android.anki.protocol.CreateDuplicateCandidate
import com.ankiminer.android.anki.protocol.CreateNote
import com.ankiminer.android.anki.protocol.CreateNotesRequest
import com.ankiminer.android.anki.protocol.CreatedNote
import com.ankiminer.android.anki.protocol.DuplicateCandidate
import com.ankiminer.android.anki.protocol.DuplicateNote
import com.ankiminer.android.anki.protocol.FailedNote
import com.ankiminer.android.anki.protocol.ReleaseState
import com.ankiminer.android.anki.protocol.UncertainNote
import com.ankiminer.android.anki.protocol.VerifyTargetRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalBackedNoteMutationServiceTest {
    @Test
    fun `exact note receipt readback and card routing produce one durable created row`() =
        withHarness { harness ->
            harness.reads.cards = listOf(CardIdentity(CARD_ID, NOTE_ID, 0, DEFAULT_DECK_ID))
            harness.provider.routeBlock = {
                harness.reads.cards = listOf(CardIdentity(CARD_ID, NOTE_ID, 0, TARGET.deck.id))
                1
            }

            val outcome = harness.service.create(harness.owner, harness.request())

            assertEquals(listOf(CreatedNote(CLIENT_NOTE_ID, NOTE_ID)), outcome.result.results)
            assertNull(outcome.result.error)
            assertFalse(outcome.replayed)
            assertEquals(1, harness.provider.insertCalls)
            assertEquals(1, harness.provider.routeCalls)
            assertEquals(1, harness.journal.noteReceiptCalls)
            assertEquals(1, harness.journal.cardReceiptCalls)
            assertEquals(NoteRoutingPhase.POSTCHECK_VERIFIED, harness.journal.phases.last())
            assertTrue(harness.journal.readyResponse?.results?.single() is AlignedResult.NoteCreated)
        }

    @Test
    fun `initial normalized duplicate is durable and never inserts`() =
        withHarness(initialMatchingNoteIds = setOf(91L)) { harness ->
            val outcome = harness.service.create(harness.owner, harness.request())

            assertEquals(listOf(DuplicateNote(CLIENT_NOTE_ID)), outcome.result.results)
            assertEquals(0, harness.provider.insertCalls)
            assertEquals(1, harness.reads.duplicateReads)
            assertTrue(harness.journal.readyResponse?.results?.single() is AlignedResult.NoteDuplicate)
        }

    @Test
    fun `fresh normalized duplicate closes the baseline race without inserting`() =
        withHarness { harness ->
            harness.reads.freshMatchingNoteIds = setOf(92L)

            val outcome = harness.service.create(harness.owner, harness.request())

            assertEquals(listOf(DuplicateNote(CLIENT_NOTE_ID)), outcome.result.results)
            assertEquals(0, harness.provider.insertCalls)
            assertEquals(1, harness.reads.duplicateReads)
        }

    @Test
    fun `cancellation at the provider-entry boundary proves no note write`() {
        val cancellation = MutableAnkiCancellation()
        withHarness(cancellation = cancellation) { harness ->
            harness.provider.preflightBlock = { cancellation.cancel() }

            val outcome = harness.service.create(harness.owner, harness.request())

            assertEquals(listOf(FailedNote(CLIENT_NOTE_ID)), outcome.result.results)
            assertEquals("cancelled", outcome.result.error?.code?.wireName)
            assertEquals(0, harness.provider.insertCalls)
            assertEquals(ChildState.PROVEN_NOT_COMMITTED, harness.journal.noteChildState)
            assertTrue(harness.journal.terminations.single() is ActiveNoteTermination.StablePreEntryFailure)
        }
    }

    @Test
    fun `cancellation after durable note insert cannot revoke mandatory card reconciliation`() {
        val cancellation = MutableAnkiCancellation()
        withHarness(cancellation = cancellation) { harness ->
            harness.reads.cards = listOf(CardIdentity(CARD_ID, NOTE_ID, 0, DEFAULT_DECK_ID))
            harness.provider.insertBlock = { cancellation.cancel() }
            harness.provider.routeBlock = {
                harness.reads.cards = listOf(CardIdentity(CARD_ID, NOTE_ID, 0, TARGET.deck.id))
                1
            }

            val outcome = harness.service.create(harness.owner, harness.request())

            assertTrue(cancellation.isCancelled())
            assertEquals(listOf(CreatedNote(CLIENT_NOTE_ID, NOTE_ID)), outcome.result.results)
            assertNull(outcome.result.error)
            assertEquals(1, harness.provider.insertCalls)
            assertEquals(1, harness.provider.routeCalls)
            assertTrue(harness.journal.readyResponse?.results?.single() is AlignedResult.NoteCreated)
        }
    }

    @Test
    fun `release winning before note provider entry is reported as cancellation`() =
        withHarness { harness ->
            assertEquals(ReleaseState.DEFERRED, harness.registry.release(RUN_ID, true))

            val outcome = harness.service.create(harness.owner, harness.request())

            assertEquals(listOf(FailedNote(CLIENT_NOTE_ID)), outcome.result.results)
            assertEquals("cancelled", outcome.result.error?.code?.wireName)
            assertEquals(0, harness.provider.insertCalls)
            assertEquals(ChildState.PROVEN_NOT_COMMITTED, harness.journal.noteChildState)
        }

    @Test
    fun `missing and invalid note receipts are uncertain and never blindly retried`() {
        listOf<String?>(null, "content://untrusted.example/notes/$NOTE_ID").forEach { rawReceipt ->
            withHarness { harness ->
                harness.provider.noteReceipt = rawReceipt

                val outcome = harness.service.create(harness.owner, harness.request())

                assertEquals(listOf(UncertainNote(CLIENT_NOTE_ID)), outcome.result.results)
                assertEquals(1, harness.provider.insertCalls)
                assertEquals(0, harness.provider.routeCalls)
                assertEquals(ChildState.COMMIT_UNCERTAIN, harness.journal.noteChildState)
                assertTrue(harness.journal.terminations.single() is ActiveNoteTermination.EnteredReceiptlessUnknown)
            }
        }
    }

    @Test
    fun `conditional template subset already in target completes without a routing write`() =
        withHarness { harness ->
            harness.reads.cards = listOf(CardIdentity(CARD_ID, NOTE_ID, 2, TARGET.deck.id))

            val outcome = harness.service.create(harness.owner, harness.request())

            assertEquals(listOf(CreatedNote(CLIENT_NOTE_ID, NOTE_ID)), outcome.result.results)
            assertEquals(0, harness.provider.routeCalls)
            assertEquals(1, harness.journal.childlessOutcomes.size)
            assertTrue(harness.journal.childlessOutcomes.single() is ChildlessRoutingOutcome.Verified)
            assertEquals(listOf(2), harness.journal.intents.map(RoutingIntentRecord::ordinal))
        }

    @Test
    fun `non count-one routing result is committed-failed uncertainty without retry`() =
        withHarness { harness ->
            harness.reads.cards = listOf(CardIdentity(CARD_ID, NOTE_ID, 0, DEFAULT_DECK_ID))
            harness.provider.routeBlock = { 0 }

            val outcome = harness.service.create(harness.owner, harness.request())

            assertTrue(outcome.result.results.single() is CommittedFailedNote)
            assertEquals(1, harness.provider.routeCalls)
            assertEquals(ChildState.COMMIT_UNCERTAIN, harness.journal.routingChildState)
            assertEquals(RoutingIntentState.COMMIT_UNCERTAIN, harness.journal.intents.single().state)
            assertEquals("post_commit_uncertain", outcome.result.error?.code?.wireName)
        }

    @Test
    fun `count-one routing with unreadable postcheck remains uncertain and is not reissued`() =
        withHarness { harness ->
            harness.reads.cards = listOf(CardIdentity(CARD_ID, NOTE_ID, 0, DEFAULT_DECK_ID))
            harness.provider.routeBlock = {
                harness.reads.failNextCardRead = true
                1
            }

            val outcome = harness.service.create(harness.owner, harness.request())

            assertTrue(outcome.result.results.single() is CommittedFailedNote)
            assertEquals(1, harness.provider.routeCalls)
            assertEquals(1, harness.journal.cardReceiptCalls)
            assertEquals(ChildState.COMMIT_UNCERTAIN, harness.journal.routingChildState)
        }

    @Test
    fun `ready replay bypasses baseline provider reads and every write`() {
        val request = request()
        val durable =
            JournalResponse.CreateNotes(
                ParentKey(request.runId, request.requestId),
                listOf(AlignedResult.NoteCreated(0, CLIENT_NOTE_ID, NOTE_ID, "replayed exact postcheck")),
                null,
            )
        withHarness(installBaseline = false) { harness ->
            harness.journal.replayResult = ReplayResult.Ready(durable)

            val outcome = harness.service.create(harness.owner, request)

            assertTrue(outcome.replayed)
            assertEquals(listOf(CreatedNote(CLIENT_NOTE_ID, NOTE_ID)), outcome.result.results)
            assertEquals(0, harness.reads.duplicateReads)
            assertEquals(0, harness.provider.insertCalls)
            assertEquals(0, harness.journal.beginCalls)
        }
    }

    @Test
    fun `after-commit note receipt crash seam resumes from durable state without reinsertion`() =
        withHarness { harness ->
            harness.reads.cards = listOf(CardIdentity(CARD_ID, NOTE_ID, 0, TARGET.deck.id))
            harness.journal.throwAfterNoteReceiptCommit = true

            val outcome = harness.service.create(harness.owner, harness.request())

            assertEquals(listOf(CreatedNote(CLIENT_NOTE_ID, NOTE_ID)), outcome.result.results)
            assertEquals(1, harness.provider.insertCalls)
            assertEquals(1, harness.journal.noteReceiptCalls)
        }

    @Test
    fun `after-created transaction crash seam returns the one durable created result`() =
        withHarness { harness ->
            harness.reads.cards = listOf(CardIdentity(CARD_ID, NOTE_ID, 0, TARGET.deck.id))
            harness.journal.throwAfterVerifiedCompletion = true

            val outcome = harness.service.create(harness.owner, harness.request())

            assertEquals(listOf(CreatedNote(CLIENT_NOTE_ID, NOTE_ID)), outcome.result.results)
            assertEquals(1, harness.provider.insertCalls)
            assertTrue(harness.journal.results(harness.journal.parentRecord.key).single() is AlignedResult.NoteCreated)
        }

    @Test
    fun `uncommitted note receipt journal failure propagates with provider capability retained for recovery`() =
        withHarness { harness ->
            harness.journal.throwBeforeNoteReceiptCommit = true

            assertThrows(IllegalStateException::class.java) {
                harness.service.create(harness.owner, harness.request())
            }

            assertEquals(1, harness.provider.insertCalls)
            assertNull(harness.journal.parentRecord.activeNoteId)
            assertEquals(NoteRoutingPhase.NOTE_PENDING, harness.journal.parentRecord.routingPhase)
        }

    @Test
    fun `typed materialization refusal becomes a row failure before provider entry`() =
        withHarness { harness ->
            harness.journal.materializeFailure =
                ActiveNoteMaterializationRefused("Active note media binding differs from durable claim")

            val outcome = harness.service.create(harness.owner, harness.request())

            assertTrue(outcome.result.results.single() is FailedNote)
            assertEquals(0, harness.provider.insertCalls)
            assertNull(harness.journal.parentRecord.activeRequestIndex)
        }

    @Test
    fun `materialization corruption propagates without writing an ordinary failed row`() =
        withHarness { harness ->
            val failure = JournalCorruptionException("materialization claim is missing")
            harness.journal.materializeFailure = failure

            assertEquals(
                failure,
                assertThrows(JournalCorruptionException::class.java) {
                    harness.service.create(harness.owner, harness.request())
                },
            )
            assertTrue(harness.journal.results(harness.journal.parentRecord.key).isEmpty())
            assertEquals(0, harness.provider.insertCalls)
        }

    private fun withHarness(
        initialMatchingNoteIds: Set<Long> = emptySet(),
        cancellation: MutableAnkiCancellation = MutableAnkiCancellation(),
        installBaseline: Boolean = true,
        block: (Harness) -> Unit,
    ) {
        val registry = AnkiRunStateRegistry()
        assertTrue(registry.register(RUN_ID, cancellation))
        registry.withOwner(RUN_ID) { owner ->
            installTarget(registry, owner)
            if (installBaseline) installBaseline(registry, owner, initialMatchingNoteIds)
            val journal = FakeNoteJournal()
            val reads = FakeNoteReads()
            val provider = FakeNoteProvider()
            block(
                Harness(
                    owner,
                    registry,
                    JournalBackedNoteMutationService(registry, journal, reads, provider),
                    journal,
                    reads,
                    provider,
                ),
            )
        }
    }

    private fun installTarget(
        registry: AnkiRunStateRegistry,
        owner: AnkiRunStateRegistry.RunOwner,
    ) {
        val request =
            VerifyTargetRequest(
                RUN_ID,
                TARGET_REQUEST_ID,
                TARGET.deck.name,
                TARGET.model.name,
                TARGET.model.fieldNames,
            )
        val reservation = registry.beginTargetVerification(owner, request)
        registry.commitDurableTargetResponse(owner, reservation, request.requestId, TARGET)
    }

    private fun installBaseline(
        registry: AnkiRunStateRegistry,
        owner: AnkiRunStateRegistry.RunOwner,
        initialMatchingNoteIds: Set<Long>,
    ) {
        val probe = registry.beginBaselineProbe(owner, null)
        registry.completeBaselineProbe(
            owner,
            probe,
            DuplicateBaseline(
                token = BASELINE_TOKEN,
                target = TARGET,
                firstFieldName = "Expression",
                scopeDeckId = null,
                candidates = listOf(DuplicateCandidate("猫", "猫")),
                occurrences = listOf(0),
                providerNoteIds = listOf(initialMatchingNoteIds),
                normalizedMatchingNoteIds = listOf(initialMatchingNoteIds),
            ),
        )
    }

    private data class Harness(
        val owner: AnkiRunStateRegistry.RunOwner,
        val registry: AnkiRunStateRegistry,
        val service: JournalBackedNoteMutationService,
        val journal: FakeNoteJournal,
        val reads: FakeNoteReads,
        val provider: FakeNoteProvider,
    ) {
        fun request(): CreateNotesRequest = JournalBackedNoteMutationServiceTest.request()
    }

    private class FakeNoteReads : NoteMutationReads {
        var freshMatchingNoteIds: Set<Long> = emptySet()
        var cards: List<CardIdentity> = listOf(CardIdentity(CARD_ID, NOTE_ID, 0, TARGET.deck.id))
        var duplicateReads = 0
        var failNextCardRead = false

        override fun readTargetBeforeEntry(
            owner: AnkiRunStateRegistry.RunOwner,
            expected: TargetSnapshot,
        ): TargetSnapshot = expected

        override fun readDuplicateBeforeEntry(
            owner: AnkiRunStateRegistry.RunOwner,
            target: TargetSnapshot,
            candidate: DuplicateCandidate,
            scopeDeckId: Long?,
        ): DuplicateRawSnapshot {
            duplicateReads += 1
            return DuplicateRawSnapshot(listOf(emptyList()), listOf(freshMatchingNoteIds))
        }

        override fun readTargetAfterEntry(expected: TargetSnapshot): TargetSnapshot = expected

        override fun readNoteAfterEntry(noteId: Long): NoteSnapshot =
            NoteSnapshot(noteId, TARGET.model.id, "猫\u001fcat", " mined ")

        override fun readCardsAfterEntry(noteId: Long, templateCount: Int): List<CardIdentity> = cards

        override fun readCardAfterEntry(cardId: Long): CardIdentity {
            if (failNextCardRead) {
                failNextCardRead = false
                throw AnkiReadFailure(
                    com.ankiminer.android.anki.protocol.AnkiErrorCode.QUERY_FAILED,
                    retryable = false,
                    stableMessage = "forced read failure",
                )
            }
            return cards.single { it.id == cardId }
        }
    }

    private class FakeNoteProvider : NoteMutationProvider {
        var noteReceipt: String? =
            "${NoteInsertReceiptValidator.NOTE_COLLECTION_URI}/$NOTE_ID"
        var insertCalls = 0
        var routeCalls = 0
        var preflightBlock: () -> Unit = {}
        var insertBlock: () -> Unit = {}
        var routeBlock: (AnkiProviderMutationCommand.RouteCard) -> Int = { 1 }

        override fun preflight(cancellation: AnkiCancellation) = preflightBlock()

        override fun insert(command: AnkiProviderMutationCommand.InsertNote): String? {
            insertCalls += 1
            insertBlock()
            return noteReceipt
        }

        override fun route(command: AnkiProviderMutationCommand.RouteCard): Int {
            routeCalls += 1
            return routeBlock(command)
        }
    }

    private class FakeNoteJournal : NoteMutationJournal {
        var replayResult: ReplayResult = ReplayResult.Missing
        var beginCalls = 0
        var noteReceiptCalls = 0
        var cardReceiptCalls = 0
        var noteChildState: ChildState? = null
        var routingChildState: ChildState? = null
        var throwAfterNoteReceiptCommit = false
        var throwBeforeNoteReceiptCommit = false
        var throwAfterVerifiedCompletion = false
        var materializeFailure: RuntimeException? = null
        val phases = mutableListOf<NoteRoutingPhase>()
        val terminations = mutableListOf<ActiveNoteTermination>()
        val childlessOutcomes = mutableListOf<ChildlessRoutingOutcome>()
        val intents = mutableListOf<RoutingIntentRecord>()
        var readyResponse: JournalResponse.CreateNotes? = null
        lateinit var parentRecord: ParentRecord
        private val rows = mutableListOf<AlignedResult>()
        private var materialization: ActiveNoteMaterialization? = null
        private var nextChildId = 100L

        override fun replay(request: JournalRequest): ReplayResult = replayResult

        override fun begin(request: JournalRequest, target: TargetSnapshot) {
            beginCalls += 1
            parentRecord =
                ParentRecord(
                    id = 1,
                    key = request.key,
                    operation = ParentOperation.CREATE_NOTES,
                    digestVersion = request.digest.digestVersion,
                    requestSha256 = request.digest.sha256,
                    state = ParentState.RUNNING,
                    activeRequestIndex = null,
                    activeNoteId = null,
                    routingPhase = null,
                    hasTargetExpectation = true,
                    createdAtMs = 1,
                    updatedAtMs = 1,
                )
        }

        override fun parent(key: ParentKey): ParentRecord? = parentRecord

        override fun append(key: ParentKey, row: AlignedResult) {
            rows += row
        }

        override fun materialize(key: ParentKey, note: ActiveNoteMaterialization) {
            materializeFailure?.let { throw it }
            materialization = note
            parentRecord = parentRecord.copy(
                activeRequestIndex = note.requestIndex,
                routingPhase = NoteRoutingPhase.NOTE_PENDING,
            )
        }

        override fun prepareNote(key: ParentKey, command: MutationCommand.InsertNote): Long = nextChildId++

        override fun recordProviderEntry(childId: Long, recoveryReissue: Boolean) = Unit

        override fun commitNoteReceipt(childId: Long, receipt: ProviderReceipt.Note, evidence: String) {
            noteReceiptCalls += 1
            if (throwBeforeNoteReceiptCommit) throw IllegalStateException("before note receipt commit")
            noteChildState = ChildState.COMMIT_KNOWN
            parentRecord = parentRecord.copy(
                activeNoteId = receipt.noteId,
                routingPhase = NoteRoutingPhase.NOTE_COMMIT_KNOWN,
            )
            if (throwAfterNoteReceiptCommit) throw IllegalStateException("after note receipt commit")
        }

        override fun advance(key: ParentKey, requestIndex: Int, phase: NoteRoutingPhase) {
            phases += phase
            parentRecord = parentRecord.copy(routingPhase = phase)
        }

        override fun createRoutingIntents(
            key: ParentKey,
            requestIndex: Int,
            drafts: List<RoutingIntentDraft>,
        ): List<RoutingIntentRecord> {
            intents +=
                drafts.mapIndexed { index, draft ->
                    RoutingIntentRecord(
                        id = index + 1L,
                        parentId = parentRecord.id,
                        requestIndex = requestIndex,
                        cardId = draft.cardId,
                        noteId = draft.noteId,
                        ordinal = draft.ordinal,
                        targetDeckId = draft.targetDeckId,
                        preUpdateDeckId = draft.preUpdateDeckId,
                        childId = null,
                        state = RoutingIntentState.PENDING,
                        terminalEvidence = null,
                        createdAtMs = 2,
                        updatedAtMs = 2,
                    )
                }
            parentRecord = parentRecord.copy(routingPhase = NoteRoutingPhase.ROUTING)
            return intents.toList()
        }

        override fun completeChildless(
            intentId: Long,
            outcome: ChildlessRoutingOutcome,
        ): RoutingIntentRecord {
            childlessOutcomes += outcome
            val state =
                when (outcome) {
                    is ChildlessRoutingOutcome.Verified -> RoutingIntentState.VERIFIED
                    is ChildlessRoutingOutcome.Failed -> RoutingIntentState.FAILED
                }
            return updateIntent(intentId, state, childId = null)
        }

        override fun prepareRoutingChild(intentId: Long): Long {
            val childId = nextChildId++
            updateIntent(intentId, RoutingIntentState.UPDATE_PREPARED, childId)
            return childId
        }

        override fun recordCardReceipt(childId: Long) {
            cardReceiptCalls += 1
        }

        override fun completeRouting(
            childId: Long,
            childState: ChildState,
            intentState: RoutingIntentState,
            evidence: String,
        ): RoutingIntentRecord {
            routingChildState = childState
            val intent = intents.single { it.childId == childId }
            return updateIntent(intent.id, intentState, childId)
        }

        override fun terminate(key: ParentKey, termination: ActiveNoteTermination) {
            terminations += termination
            when (termination) {
                is ActiveNoteTermination.StablePreEntryFailure -> {
                    noteChildState = ChildState.PROVEN_NOT_COMMITTED
                    rows += AlignedResult.NoteFailed(
                        termination.requestIndex,
                        CLIENT_NOTE_ID,
                        termination.error,
                        termination.compactEvidence,
                    )
                }
                is ActiveNoteTermination.EnteredReceiptlessUnknown -> {
                    noteChildState = ChildState.COMMIT_UNCERTAIN
                    rows += AlignedResult.NoteUncertain(
                        termination.requestIndex,
                        CLIENT_NOTE_ID,
                        termination.compactEvidence,
                    )
                }
                is ActiveNoteTermination.KnownNoteFailure -> {
                    when (val routing = termination.preparedRoutingFailure) {
                        is PreparedRoutingFailure.ProvenNotCommitted ->
                            routingChildState = ChildState.PROVEN_NOT_COMMITTED
                        is PreparedRoutingFailure.PostconditionFailed ->
                            routingChildState = ChildState.POSTCONDITION_FAILED
                        is PreparedRoutingFailure.CommitUncertain -> routingChildState = ChildState.COMMIT_UNCERTAIN
                        null -> Unit
                    }
                    rows += AlignedResult.NoteCommittedFailed(
                        termination.requestIndex,
                        CLIENT_NOTE_ID,
                        termination.noteId,
                        termination.error,
                        termination.compactEvidence,
                    )
                }
            }
            parentRecord = parentRecord.copy(activeRequestIndex = null, activeNoteId = null, routingPhase = null)
        }

        override fun completeVerified(key: ParentKey, requestIndex: Int, noteId: Long, evidence: String) {
            rows += AlignedResult.NoteCreated(requestIndex, CLIENT_NOTE_ID, noteId, evidence)
            parentRecord = parentRecord.copy(activeRequestIndex = null, activeNoteId = null, routingPhase = null)
            if (throwAfterVerifiedCompletion) throw IllegalStateException("after verified-note completion")
        }

        override fun results(key: ParentKey): List<AlignedResult> = rows.toList()

        override fun markReady(request: JournalRequest, response: JournalResponse.CreateNotes) {
            readyResponse = response
        }

        private fun updateIntent(
            intentId: Long,
            state: RoutingIntentState,
            childId: Long?,
        ): RoutingIntentRecord {
            val index = intents.indexOfFirst { it.id == intentId }
            val updated = intents[index].copy(
                childId = childId,
                state = state,
                terminalEvidence = if (state.isTerminal) "test evidence" else null,
                updatedAtMs = intents[index].updatedAtMs + 1,
            )
            intents[index] = updated
            if (intents.isNotEmpty() && intents.all { it.state == RoutingIntentState.VERIFIED }) {
                parentRecord = parentRecord.copy(routingPhase = NoteRoutingPhase.ROUTED)
            }
            return updated
        }
    }

    private companion object {
        const val RUN_ID = "run_00000000000000000000000000000001"
        const val TARGET_REQUEST_ID = "anki_00000000000000000000000000000001"
        const val REQUEST_ID = "anki_00000000000000000000000000000002"
        const val BASELINE_TOKEN = "baseline_00000000000000000000000000000001"
        const val CLIENT_NOTE_ID = "note_00000000000000000000000000000001"
        const val NOTE_ID = 500L
        const val CARD_ID = 501L
        const val DEFAULT_DECK_ID = 1L

        val TARGET =
            TargetSnapshot(
                deck = DeckSnapshot(20, "Mining", dynamic = false),
                model =
                    ModelSnapshot(
                        id = 10,
                        name = "Mining Model",
                        type = 0,
                        fieldNames = listOf("Expression", "Meaning"),
                        cardCount = 3,
                        sortFieldIndex = 0,
                        effectiveDefaultDeckId = DEFAULT_DECK_ID,
                        css = "",
                        latexPre = null,
                        latexPost = null,
                        templates =
                            List(3) { ordinal ->
                                TemplateSnapshot(
                                    10,
                                    ordinal,
                                    "Card ${ordinal + 1}",
                                    "{{Expression}}",
                                    "{{Meaning}}",
                                    null,
                                    null,
                                )
                            },
                    ),
            )

        fun request() =
            CreateNotesRequest(
                runId = RUN_ID,
                requestId = REQUEST_ID,
                deckName = TARGET.deck.name,
                modelName = TARGET.model.name,
                firstFieldName = "Expression",
                baselineToken = BASELINE_TOKEN,
                duplicateScope = CollectionCreateDuplicateScope,
                notes =
                    listOf(
                        CreateNote(
                            clientNoteId = CLIENT_NOTE_ID,
                            fields = mapOf("Expression" to "猫", "Meaning" to "cat"),
                            tags = listOf("mined"),
                            duplicateCandidate = CreateDuplicateCandidate("猫", "猫", 0),
                            mediaBindings = emptyList(),
                        ),
                    ),
            )
    }
}
