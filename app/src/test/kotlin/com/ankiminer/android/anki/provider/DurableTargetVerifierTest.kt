package com.ankiminer.android.anki.provider

import com.ankiminer.android.anki.journal.ChildRecord
import com.ankiminer.android.anki.journal.ChildState
import com.ankiminer.android.anki.journal.DurableTargetExpectation
import com.ankiminer.android.anki.journal.DurableTargetSnapshot
import com.ankiminer.android.anki.journal.JournalRequest
import com.ankiminer.android.anki.journal.JournalResponse
import com.ankiminer.android.anki.journal.MutationCommand
import com.ankiminer.android.anki.journal.ParentKey
import com.ankiminer.android.anki.journal.ReplayResult
import com.ankiminer.android.anki.protocol.AnkiErrorCode
import com.ankiminer.android.anki.protocol.AnkiErrorResult
import com.ankiminer.android.anki.protocol.VerifyTargetRequest
import com.ankiminer.android.anki.protocol.VerifyTargetResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableTargetVerifierTest {
    @Test
    fun `existing exact target becomes durable without provider mutation`() {
        val fixture = fixture()
        fixture.gateway.queryHandler = targetHandler { true }

        val outcome = fixture.execute(request())

        val result = outcome.response as VerifyTargetResult
        assertEquals(20L, result.deckId)
        assertEquals(10L, result.modelId)
        assertFalse(result.deckCreated)
        assertTrue(outcome.durable)
        assertEquals(targetSnapshot(), outcome.targetForAdmission)
        assertTrue(fixture.gateway.deckCommands.isEmpty())
        assertEquals(
            listOf("replay", "begin", "storeTarget", "resultReady"),
            fixture.journal.calls,
        )
    }

    @Test
    fun `missing deck records entry once then reconciles exact strict receipt`() {
        var deckExists = false
        val fixture = fixture()
        fixture.gateway.queryHandler = targetHandler { deckExists }
        fixture.gateway.createDeckHandler = { command ->
            assertEquals("Mining", command.deckName)
            deckExists = true
            "content://com.ichi2.anki.flashcards/decks/20"
        }

        val outcome = fixture.execute(request())

        assertTrue(outcome.response is VerifyTargetResult)
        assertEquals(1, fixture.gateway.deckCommands.size)
        assertEquals(20L, fixture.journal.receipt?.deckId)
        assertEquals(targetSnapshot().toDurableSnapshot(), fixture.journal.verifiedTarget)
        assertEquals(
            listOf(
                "replay",
                "begin",
                "expectation",
                "prepareDeck",
                "recordEntry",
                "recordReceipt",
                "completeVerified",
                "resultReady",
            ),
            fixture.journal.calls,
        )
    }

    @Test
    fun `post-entry cancellation cannot interrupt exact reconciliation`() {
        val cancellation = MutableAnkiCancellation()
        var deckExists = false
        val hooks =
            object : TargetVerificationBoundaryHooks {
                override fun afterProviderEntry() = cancellation.cancel()
            }
        val fixture = fixture(cancellation, hooks)
        fixture.gateway.queryHandler = targetHandler { deckExists }
        fixture.gateway.createDeckHandler = {
            deckExists = true
            "content://com.ichi2.anki.flashcards/decks/20"
        }

        val outcome = fixture.execute(request())

        assertTrue(outcome.response is VerifyTargetResult)
        assertEquals(1, fixture.gateway.deckCommands.size)
        assertNotNull(fixture.journal.verifiedTarget)
        val postEntryQueries = fixture.gateway.queries.drop(3)
        assertTrue(postEntryQueries.isNotEmpty())
    }

    @Test
    fun `cancellation at the last pre-entry boundary proves no provider commit`() {
        val cancellation = MutableAnkiCancellation()
        val hooks =
            object : TargetVerificationBoundaryHooks {
                override fun beforeProviderEntry() = cancellation.cancel()
            }
        val fixture = fixture(cancellation, hooks)
        fixture.gateway.queryHandler = targetHandler { false }

        val outcome = fixture.execute(request())

        val error = outcome.response as AnkiErrorResult
        assertEquals(AnkiErrorCode.CANCELLED, error.code)
        assertFalse(error.retryable)
        assertTrue(fixture.gateway.deckCommands.isEmpty())
        assertTrue("recordEntry" !in fixture.journal.calls)
        assertTrue("completePreEntry" in fixture.journal.calls)
        assertTrue("resultReady" in fixture.journal.calls)
    }

    @Test
    fun `release before atomic provider-entry authorization proves no commit`() {
        val atBoundary = CountDownLatch(1)
        val continueEntry = CountDownLatch(1)
        val hooks =
            object : TargetVerificationBoundaryHooks {
                override fun beforeProviderEntry() {
                    atBoundary.countDown()
                    check(continueEntry.await(5, TimeUnit.SECONDS))
                }
            }
        val fixture = fixture(hooks = hooks)
        fixture.gateway.queryHandler = targetHandler { false }
        val failure = AtomicReference<Throwable?>()
        val worker =
            thread {
                try {
                    fixture.execute(request())
                } catch (error: Throwable) {
                    failure.set(error)
                }
            }
        assertTrue(atBoundary.await(5, TimeUnit.SECONDS))

        assertEquals(
            com.ankiminer.android.anki.protocol.ReleaseState.DEFERRED,
            fixture.registry.release(RUN_ID, acknowledgeTerminalResponses = true),
        )
        continueEntry.countDown()
        worker.join(5_000)

        assertFalse(worker.isAlive)
        assertTrue(failure.get() is RunReleasingException)
        assertTrue(fixture.gateway.deckCommands.isEmpty())
        assertTrue("recordEntry" !in fixture.journal.calls)
        assertTrue("completePreEntry" in fixture.journal.calls)
    }

    @Test
    fun `terminal failure before atomic provider-entry authorization proves no commit`() {
        val atBoundary = CountDownLatch(1)
        val continueEntry = CountDownLatch(1)
        val hooks =
            object : TargetVerificationBoundaryHooks {
                override fun beforeProviderEntry() {
                    atBoundary.countDown()
                    check(continueEntry.await(5, TimeUnit.SECONDS))
                }
            }
        val fixture = fixture(hooks = hooks)
        fixture.gateway.queryHandler = targetHandler { false }
        val failure = AtomicReference<Throwable?>()
        val worker =
            thread {
                try {
                    fixture.execute(request())
                } catch (error: Throwable) {
                    failure.set(error)
                }
            }
        assertTrue(atBoundary.await(5, TimeUnit.SECONDS))

        fixture.registry.withOwner(RUN_ID) { owner ->
            fixture.registry.markTerminalResponseFailure(owner)
        }
        continueEntry.countDown()
        worker.join(5_000)

        assertFalse(worker.isAlive)
        assertTrue(failure.get() is RunStateConflictException)
        assertTrue(fixture.gateway.deckCommands.isEmpty())
        assertTrue("recordEntry" !in fixture.journal.calls)
        assertTrue("completePreEntry" in fixture.journal.calls)
        assertTrue("resultReady" in fixture.journal.calls)
    }

    @Test
    fun `release after provider-entry authorization cannot interrupt reconciliation`() {
        val entered = CountDownLatch(1)
        val continueProvider = CountDownLatch(1)
        var deckExists = false
        val hooks =
            object : TargetVerificationBoundaryHooks {
                override fun afterProviderEntry() {
                    entered.countDown()
                    check(continueProvider.await(5, TimeUnit.SECONDS))
                }
            }
        val fixture = fixture(hooks = hooks)
        fixture.gateway.queryHandler = targetHandler { deckExists }
        fixture.gateway.createDeckHandler = {
            deckExists = true
            "content://com.ichi2.anki.flashcards/decks/20"
        }
        val failure = AtomicReference<Throwable?>()
        val worker =
            thread {
                try {
                    fixture.execute(request())
                } catch (error: Throwable) {
                    failure.set(error)
                }
            }
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        assertEquals(
            com.ankiminer.android.anki.protocol.ReleaseState.DEFERRED,
            fixture.registry.release(RUN_ID, acknowledgeTerminalResponses = true),
        )
        continueProvider.countDown()
        worker.join(5_000)

        assertFalse(worker.isAlive)
        assertTrue(failure.get() is RunReleasingException)
        assertEquals(1, fixture.gateway.deckCommands.size)
        assertTrue("completeVerified" in fixture.journal.calls)
        assertTrue("completeUncertain" !in fixture.journal.calls)
    }

    @Test
    fun `inconclusive entered create is durable nonretryable uncertainty`() {
        val fixture = fixture()
        fixture.gateway.queryHandler = targetHandler { false }
        fixture.gateway.createDeckHandler = { throw IllegalStateException("binder died") }

        val outcome = fixture.execute(request())

        val error = outcome.response as AnkiErrorResult
        assertEquals(AnkiErrorCode.POST_COMMIT_UNCERTAIN, error.code)
        assertFalse(error.retryable)
        assertEquals(1, fixture.gateway.deckCommands.size)
        assertTrue("recordEntry" in fixture.journal.calls)
        assertTrue("completeUncertain" in fixture.journal.calls)
        assertTrue("completePreEntry" !in fixture.journal.calls)
        assertTrue("resultReady" in fixture.journal.calls)
    }

    @Test
    fun `malformed receipt may succeed only through exact full reconciliation`() {
        var deckExists = false
        val fixture = fixture()
        fixture.gateway.queryHandler = targetHandler { deckExists }
        fixture.gateway.createDeckHandler = {
            deckExists = true
            "content://com.ichi2.anki.flashcards/decks/020?untrusted=true"
        }

        val outcome = fixture.execute(request())

        assertTrue(outcome.response is VerifyTargetResult)
        assertEquals(null, fixture.journal.receipt)
        assertNotNull(fixture.journal.verifiedTarget)
        assertTrue(fixture.journal.verifiedEvidence.orEmpty().contains("invalidReturnedUriSha256="))
    }

    @Test
    fun `null and thrown receipt paths may succeed through exact full reconciliation`() {
        listOf(false, true).forEach { throws ->
            var deckExists = false
            val fixture = fixture()
            fixture.gateway.queryHandler = targetHandler { deckExists }
            fixture.gateway.createDeckHandler = {
                deckExists = true
                if (throws) throw IllegalStateException("provider returned no usable receipt")
                null
            }

            val outcome = fixture.execute(request())

            assertTrue("throws=$throws", outcome.response is VerifyTargetResult)
            assertEquals("throws=$throws", null, fixture.journal.receipt)
            assertNotNull("throws=$throws", fixture.journal.verifiedTarget)
            assertTrue("throws=$throws", "completeUncertain" !in fixture.journal.calls)
        }
    }

    @Test
    fun `strict receipt ID must identify the same unique deck as the requested name`() {
        var deckExists = false
        val fixture = fixture()
        fixture.gateway.queryHandler = { query, _ ->
            when (query.endpoint) {
                ProviderEndpoint.MODELS, ProviderEndpoint.MODEL_BY_ID ->
                    FakeProviderCursor(query.projection, listOf(modelRow()))
                ProviderEndpoint.MODEL_TEMPLATES ->
                    FakeProviderCursor(query.projection, listOf(templateRow()))
                ProviderEndpoint.DECKS ->
                    FakeProviderCursor(
                        query.projection,
                        if (deckExists) listOf(deckRow()) else emptyList(),
                    )
                ProviderEndpoint.DECK_BY_ID ->
                    FakeProviderCursor(query.projection, listOf(deckRow(id = 99L, name = "Other")))
                else -> error("unexpected query $query")
            }
        }
        fixture.gateway.createDeckHandler = {
            deckExists = true
            "content://com.ichi2.anki.flashcards/decks/99"
        }

        val outcome = fixture.execute(request())

        assertEquals(
            AnkiErrorCode.POST_COMMIT_UNCERTAIN,
            (outcome.response as AnkiErrorResult).code,
        )
        assertTrue("completeUncertain" in fixture.journal.calls)
    }

    @Test
    fun `ready replay returns exact durable target without provider or journal mutation`() {
        val fixture = fixture()
        val durableRequest = JournalRequest.from(request())
        fixture.journal.ready[durableRequest.key] =
            JournalResponse.VerifySuccess(durableRequest.key, targetSnapshot().toDurableSnapshot())
        fixture.gateway.queryHandler = { query, _ -> error("provider must not be queried: $query") }

        val outcome = fixture.execute(request())

        assertTrue(outcome.replayed)
        assertEquals(targetSnapshot(), outcome.targetForAdmission)
        assertEquals(listOf("replay"), fixture.journal.calls)
        assertTrue(fixture.gateway.queries.isEmpty())
        assertTrue(fixture.gateway.deckCommands.isEmpty())
    }

    @Test
    fun `non-ready replay states fail closed before journal begin or provider access`() {
        listOf(
            ReplayResult.DigestMismatch,
            ReplayResult.NotReplayable,
            ReplayResult.LiveOwnerRequired,
        ).forEach { replay ->
            val journal = FakeTargetJournal().apply { replayOverride = replay }
            val fixture = fixture(journalOverride = journal)
            fixture.gateway.queryHandler = { query, _ -> error("provider must not be queried: $query") }

            val failure =
                org.junit.Assert.assertThrows(AnkiReadFailure::class.java) {
                    fixture.execute(request())
                }

            assertEquals(replay.toString(), AnkiErrorCode.INVALID_REQUEST, failure.code)
            assertFalse(replay.toString(), failure.retryable)
            assertEquals(replay.toString(), listOf("replay"), journal.calls)
            assertTrue(replay.toString(), fixture.gateway.queries.isEmpty())
            assertTrue(replay.toString(), fixture.gateway.deckCommands.isEmpty())
            assertEquals(replay.toString(), null, fixture.currentTarget())
        }
    }

    @Test
    fun `missing required field is durable field-missing before deck mutation`() {
        val fixture = fixture()
        fixture.gateway.queryHandler = targetHandler { false }

        val outcome = fixture.execute(request(requiredFields = listOf("Missing")))

        val error = outcome.response as AnkiErrorResult
        assertEquals(AnkiErrorCode.FIELD_MISSING, error.code)
        assertFalse(error.retryable)
        assertTrue(outcome.durable)
        assertEquals(null, outcome.targetForAdmission)
        assertTrue(fixture.gateway.deckCommands.isEmpty())
        assertEquals(listOf("replay", "begin", "resultReady"), fixture.journal.calls)
    }

    @Test
    fun `ambiguous malformed and dynamic targets are durable target-invalid without create`() {
        val handlers =
            listOf<(ProviderQuery, AnkiCancellation) -> ProviderCursor?>(
                { query, _ ->
                    when (query.endpoint) {
                        ProviderEndpoint.MODELS ->
                            FakeProviderCursor(query.projection, listOf(modelRow(), modelRow(id = 11L)))
                        else -> error("query must stop at ambiguous models: $query")
                    }
                },
                targetHandler(model = modelRow(type = 1L)) { true },
                targetHandler(deck = deckRow(dynamic = 1L)) { true },
            )
        handlers.forEachIndexed { index, handler ->
            val fixture = fixture()
            fixture.gateway.queryHandler = handler

            val outcome = fixture.execute(request())

            assertEquals(
                "case=$index",
                AnkiErrorCode.TARGET_INVALID,
                (outcome.response as AnkiErrorResult).code,
            )
            assertTrue("case=$index", outcome.durable)
            assertEquals("case=$index", null, outcome.targetForAdmission)
            assertTrue("case=$index", fixture.gateway.deckCommands.isEmpty())
            assertTrue("case=$index", "resultReady" in fixture.journal.calls)
        }
    }

    @Test
    fun `ready replay re-admits on a fresh registry but same-live duplicate fails closed`() {
        val journal = FakeTargetJournal()
        val durableRequest = JournalRequest.from(request())
        journal.ready[durableRequest.key] =
            JournalResponse.VerifySuccess(durableRequest.key, targetSnapshot().toDurableSnapshot())
        val cleanup = mutableListOf<Set<String>?>()
        val liveRegistry = AnkiRunStateRegistry { _, ids -> cleanup += ids }
        val live = fixture(registryOverride = liveRegistry, journalOverride = journal)
        live.gateway.queryHandler = { query, _ -> error("replay must not query: $query") }

        assertEquals(targetSnapshot(), live.execute(request()).targetForAdmission)
        assertEquals(targetSnapshot(), live.currentTarget())
        org.junit.Assert.assertThrows(RunStateConflictException::class.java) {
            live.execute(request())
        }
        org.junit.Assert.assertThrows(RunStateConflictException::class.java) {
            live.execute(request(SECOND_REQUEST_ID))
        }
        org.junit.Assert.assertThrows(RunStateConflictException::class.java) {
            live.currentTarget()
        }
        assertEquals(
            com.ankiminer.android.anki.protocol.ReleaseState.RELEASED,
            live.registry.release(RUN_ID, acknowledgeTerminalResponses = true),
        )
        assertEquals(listOf(null), cleanup)

        val fresh = fixture(journalOverride = journal)
        fresh.gateway.queryHandler = { query, _ -> error("fresh replay must not query: $query") }
        assertEquals(targetSnapshot(), fresh.execute(request()).targetForAdmission)
        assertEquals(targetSnapshot(), fresh.currentTarget())
    }

    @Test
    fun `installed target revalidates exact IDs and never recreates drift`() {
        var drift = false
        val fixture = fixture()
        fixture.gateway.queryHandler = { query, _ ->
            when (query.endpoint) {
                ProviderEndpoint.MODELS -> FakeProviderCursor(query.projection, listOf(modelRow()))
                ProviderEndpoint.MODEL_BY_ID ->
                    FakeProviderCursor(
                        query.projection,
                        listOf(modelRow(css = if (drift) "changed" else "css")),
                    )
                ProviderEndpoint.MODEL_TEMPLATES ->
                    FakeProviderCursor(query.projection, listOf(templateRow()))
                ProviderEndpoint.DECKS, ProviderEndpoint.DECK_BY_ID ->
                    FakeProviderCursor(query.projection, listOf(deckRow()))
                else -> error("unexpected query $query")
            }
        }
        fixture.execute(request())
        fixture.gateway.queries.clear()
        fixture.journal.calls.clear()
        drift = true

        val outcome = fixture.execute(request(SECOND_REQUEST_ID))

        assertEquals(AnkiErrorCode.TARGET_INVALID, (outcome.response as AnkiErrorResult).code)
        assertEquals(
            listOf(ProviderEndpoint.MODEL_BY_ID, ProviderEndpoint.MODEL_TEMPLATES),
            fixture.gateway.queries.map { it.endpoint },
        )
        assertTrue(fixture.gateway.deckCommands.isEmpty())
        assertTrue("resultReady" in fixture.journal.calls)
        assertEquals(null, fixture.currentTarget())
    }

    private fun fixture(
        cancellation: AnkiCancellation = AnkiCancellation.NONE,
        hooks: TargetVerificationBoundaryHooks = NoOpTargetVerificationBoundaryHooks,
        registryOverride: AnkiRunStateRegistry? = null,
        journalOverride: FakeTargetJournal? = null,
    ): Fixture {
        val gateway = FakeAnkiProviderGateway()
        val registry = registryOverride ?: AnkiRunStateRegistry()
        assertTrue(registry.register(RUN_ID, cancellation))
        val journal = journalOverride ?: FakeTargetJournal()
        return Fixture(
            gateway,
            registry,
            journal,
            DurableTargetVerifier(gateway, registry, journal, hooks),
        )
    }

    private data class Fixture(
        val gateway: FakeAnkiProviderGateway,
        val registry: AnkiRunStateRegistry,
        val journal: FakeTargetJournal,
        val verifier: DurableTargetVerifier,
    ) {
        fun currentTarget(): TargetSnapshot? =
            registry.withOwner(RUN_ID) { owner -> registry.target(owner) }

        fun execute(request: VerifyTargetRequest): TargetVerificationOutcome =
            registry.withOwner(RUN_ID) { owner ->
                val reservation = registry.beginTargetVerification(owner, request)
                try {
                    verifier.verify(owner, reservation, request).also { outcome ->
                        if (outcome.durable) {
                            registry.commitDurableTargetResponse(
                                owner,
                                reservation,
                                request.requestId,
                                outcome.targetForAdmission,
                            )
                        } else {
                            registry.abortTargetVerification(owner, reservation)
                        }
                    }
                } catch (error: RuntimeException) {
                    registry.abortTargetVerification(owner, reservation)
                    throw error
                }
            }
    }

    private class FakeTargetJournal : TargetVerificationJournal {
        val calls = mutableListOf<String>()
        val ready = mutableMapOf<ParentKey, JournalResponse>()
        var expectation: DurableTargetExpectation? = null
        var storedTarget: DurableTargetSnapshot? = null
        var verifiedTarget: DurableTargetSnapshot? = null
        var verifiedEvidence: String? = null
        var receipt: DeckCreateReceipt? = null
        var replayOverride: ReplayResult? = null

        override fun replay(request: JournalRequest): ReplayResult {
            calls += "replay"
            replayOverride?.let { return it }
            return ready[request.key]?.let(ReplayResult::Ready) ?: ReplayResult.Missing
        }

        override fun begin(request: JournalRequest) {
            calls += "begin"
        }

        override fun storeExpectation(
            key: ParentKey,
            expectation: DurableTargetExpectation,
        ) {
            calls += "expectation"
            this.expectation = expectation
        }

        override fun storeTarget(
            key: ParentKey,
            target: DurableTargetSnapshot,
        ) {
            calls += "storeTarget"
            storedTarget = target
        }

        override fun prepareDeck(
            key: ParentKey,
            deckName: String,
        ): ChildRecord {
            calls += "prepareDeck"
            return ChildRecord(
                id = 1L,
                parentId = 1L,
                sequence = 0,
                digestVersion = 1,
                requestSha256 = "0".repeat(64),
                itemSha256 = null,
                command = MutationCommand.CreateDeck(deckName),
                mediaClaimId = null,
                state = ChildState.PREPARED,
                attempts = emptyList(),
                receipt = null,
                terminalEvidence = null,
                createdAtMs = 1L,
                updatedAtMs = 1L,
            )
        }

        override fun recordEntry(childId: Long) {
            calls += "recordEntry"
        }

        override fun recordReceipt(
            childId: Long,
            receipt: DeckCreateReceipt,
        ) {
            calls += "recordReceipt"
            this.receipt = receipt
        }

        override fun completeVerifiedDeck(
            childId: Long,
            target: DurableTargetSnapshot,
            evidence: String,
        ) {
            calls += "completeVerified"
            verifiedTarget = target
            verifiedEvidence = evidence
        }

        override fun completeUncertainDeck(
            childId: Long,
            evidence: String,
        ) {
            calls += "completeUncertain"
        }

        override fun completePreEntryDeck(
            childId: Long,
            evidence: String,
        ) {
            calls += "completePreEntry"
        }

        override fun markResultReady(
            request: JournalRequest,
            response: JournalResponse,
        ) {
            calls += "resultReady"
            ready[request.key] = response
        }
    }

    private fun targetHandler(
        model: Map<ProviderColumn, ProviderCell> = modelRow(),
        deck: Map<ProviderColumn, ProviderCell> = deckRow(),
        deckExists: () -> Boolean,
    ): (ProviderQuery, AnkiCancellation) -> ProviderCursor? =
        { query, _ ->
            when (query.endpoint) {
                ProviderEndpoint.MODELS, ProviderEndpoint.MODEL_BY_ID ->
                    FakeProviderCursor(query.projection, listOf(model))
                ProviderEndpoint.MODEL_TEMPLATES ->
                    FakeProviderCursor(query.projection, listOf(templateRow()))
                ProviderEndpoint.DECKS, ProviderEndpoint.DECK_BY_ID ->
                    FakeProviderCursor(
                        query.projection,
                        if (deckExists()) listOf(deck) else emptyList(),
                    )
                else -> error("unexpected query $query")
            }
        }

    private fun targetSnapshot() =
        TargetSnapshot(
            deck = DeckSnapshot(20L, "Mining", dynamic = false),
            model =
                ModelSnapshot(
                    id = 10L,
                    name = "Mining",
                    type = 0,
                    fieldNames = listOf("Expression", "Meaning"),
                    cardCount = 1,
                    sortFieldIndex = 0,
                    effectiveDefaultDeckId = 1L,
                    css = "css",
                    latexPre = "pre",
                    latexPost = "post",
                    templates =
                        listOf(
                            TemplateSnapshot(
                                modelId = 10L,
                                ordinal = 0,
                                name = "Card 1",
                                questionFormat = "{{Expression}}",
                                answerFormat = "{{Meaning}}",
                                browserQuestionFormat = null,
                                browserAnswerFormat = null,
                            ),
                        ),
                ),
        )

    private fun request(
        requestId: String = REQUEST_ID,
        requiredFields: List<String> = listOf("Expression"),
    ) =
        VerifyTargetRequest(
            runId = RUN_ID,
            requestId = requestId,
            deckName = "Mining",
            modelName = "Mining",
            requiredFields = requiredFields,
        )

    private companion object {
        const val RUN_ID = "run_11111111111111111111111111111111"
        const val REQUEST_ID = "anki_11111111111111111111111111111111"
        const val SECOND_REQUEST_ID = "anki_22222222222222222222222222222222"
    }
}
