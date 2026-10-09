package com.ankiminer.android.anki.provider

import com.ankiminer.android.anki.journal.AnkiMutationStore
import com.ankiminer.android.anki.journal.ChildOperation
import com.ankiminer.android.anki.journal.ChildState
import com.ankiminer.android.anki.journal.DurableTargetSnapshot
import com.ankiminer.android.anki.journal.MutationCommand
import com.ankiminer.android.anki.journal.ParentOperation
import com.ankiminer.android.anki.journal.RecoveryInventory
import com.ankiminer.android.anki.journal.RoutingIntentState
import com.ankiminer.android.anki.journal.testChild
import com.ankiminer.android.anki.journal.testParent
import com.ankiminer.android.anki.journal.testRoutingIntent
import com.ankiminer.android.anki.protocol.AnkiErrorCode
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Startup recovery against a scripted journal: the real store needs Android SQLite, and these
 * cases only need the inventory recovery reads and the one outcome it may write.
 */
class JournalBackedTargetRecoveryGateTest {
    @Test
    fun `entered card recovery keeps the gate closed while AnkiDroid cannot be read`() {
        accessLosses().forEach { (expected, loseAccess) ->
            val store = ScriptedRecoveryStore(cardInventory())
            val gateway = FakeAnkiProviderGateway().apply(loseAccess)
            val gate = gate(store, gateway)

            val failure = assertThrows(AnkiReadFailure::class.java) { gate.ensureRecovered() }

            assertEquals(expected, failure.code)
            assertFalse("$expected", gate.isOpen())
            assertEquals("$expected", listOf("recoveryInventory"), store.calls)
            assertTrue("$expected", gateway.cardCommands.isEmpty())
        }
    }

    @Test
    fun `entered deck recovery keeps the gate closed while AnkiDroid cannot be read`() {
        accessLosses().forEach { (expected, loseAccess) ->
            val store = ScriptedRecoveryStore(deckInventory())
            val gateway = FakeAnkiProviderGateway().apply(loseAccess)
            val gate = gate(store, gateway)

            val failure = assertThrows(AnkiReadFailure::class.java) { gate.ensureRecovered() }

            assertEquals(expected, failure.code)
            assertFalse("$expected", gate.isOpen())
            assertEquals("$expected", listOf("recoveryInventory"), store.calls)
            assertTrue("$expected", gateway.deckCommands.isEmpty())
        }
    }

    @Test
    fun `a card AnkiDroid answers for but no longer has still resolves as uncertain`() {
        val store = ScriptedRecoveryStore(cardInventory())
        val gateway = FakeAnkiProviderGateway()
        gateway.queryHandler = { query, _ -> FakeProviderCursor(query.projection, emptyList()) }
        val gate = gate(store, gateway)

        gate.ensureRecovered()

        assertTrue(gate.isOpen())
        assertEquals(
            listOf<Pair<Any?, Any?>>(ChildState.COMMIT_UNCERTAIN to RoutingIntentState.COMMIT_UNCERTAIN),
            store.routingOutcomes,
        )
        assertTrue(gateway.cardCommands.isEmpty())
    }

    @Test
    fun `entered deck recovery verifies only the exact spelling the journal froze`() {
        // Current builds freeze AnkiDroid's spelling before entry, so the created deck matches it.
        val frozen = ScriptedRecoveryStore(deckInventory("Japanese::Mining"))
        val frozenGate = gate(frozen, FakeAnkiProviderGateway().apply { queryHandler = deckHandler("Japanese::Mining") })

        frozenGate.ensureRecovered()

        assertTrue(frozenGate.isOpen())
        assertEquals("Japanese::Mining", frozen.verifiedTarget?.deck?.name)

        // An older build froze the typed spelling. The journal schema pins the verified deck name to
        // the frozen one (verified_target_deck_guard), so that create stays uncertain, as before.
        val typed = ScriptedRecoveryStore(deckInventory("japanese :: mining"))
        val typedGate = gate(typed, FakeAnkiProviderGateway().apply { queryHandler = deckHandler("Japanese::Mining") })

        typedGate.ensureRecovered()

        assertTrue(typedGate.isOpen())
        assertTrue("completeUncertainDeck" in typed.calls)
        assertEquals(null, typed.verifiedTarget)
    }

    private fun deckHandler(deckName: String): (ProviderQuery, AnkiCancellation) -> ProviderCursor? =
        { query, _ ->
            when (query.endpoint) {
                ProviderEndpoint.MODEL_BY_ID -> FakeProviderCursor(query.projection, listOf(modelRow()))
                ProviderEndpoint.MODEL_TEMPLATES -> FakeProviderCursor(query.projection, listOf(templateRow()))
                ProviderEndpoint.DECKS, ProviderEndpoint.DECK_BY_ID ->
                    FakeProviderCursor(query.projection, listOf(deckRow(20L, deckName)))
                else -> error("unexpected query $query")
            }
        }

    private fun gate(
        store: ScriptedRecoveryStore,
        gateway: FakeAnkiProviderGateway,
    ) = JournalBackedTargetRecoveryGate(
        store = store.store,
        gateway = gateway,
        workerThreadGuard = WorkerThreadGuard { },
        mediaStagingRecovery = MediaStagingRecovery { AnkiMediaRecoveryReport(0, 0, 0) },
    )

    private fun accessLosses(): List<Pair<AnkiErrorCode, FakeAnkiProviderGateway.() -> Unit>> =
        listOf(
            AnkiErrorCode.PERMISSION_REQUIRED to { status = ProviderAccessStatus.PermissionRequired },
            AnkiErrorCode.PROVIDER_UNAVAILABLE to { status = ProviderAccessStatus.Absent },
            AnkiErrorCode.API_DISABLED to { status = ProviderAccessStatus.ApiDisabled },
            AnkiErrorCode.TIMEOUT to { queryHandler = { _, _ -> throw ProviderGatewayException(ProviderFailureKind.TIMEOUT) } },
            AnkiErrorCode.PROVIDER_UNAVAILABLE to {
                queryHandler = { _, _ -> throw ProviderGatewayException(ProviderFailureKind.PROVIDER_UNAVAILABLE) }
            },
            AnkiErrorCode.CANCELLED to { queryHandler = { _, _ -> throw ProviderGatewayException(ProviderFailureKind.CANCELLED) } },
        )

    private fun cardInventory(): RecoveryInventory {
        val parent = testParent(operation = ParentOperation.CREATE_NOTES)
        val child = testChild(parentId = parent.id, operation = ChildOperation.CARD_DECK_UPDATE, attemptCount = 1)
        return RecoveryInventory(listOf(parent), child, testRoutingIntent(parentId = parent.id, childId = child.id))
    }

    private fun deckInventory(deckName: String = "Mining"): RecoveryInventory {
        val parent = testParent(operation = ParentOperation.VERIFY_TARGET)
        val child =
            testChild(parentId = parent.id, operation = ChildOperation.DECK_CREATE, attemptCount = 1)
                .copy(command = MutationCommand.CreateDeck(deckName))
        return RecoveryInventory(listOf(parent), child, null, MODEL.toDurableExpectation(deckName))
    }

    private class ScriptedRecoveryStore(private var inventory: RecoveryInventory) {
        val calls = mutableListOf<String>()
        val routingOutcomes = mutableListOf<Pair<Any?, Any?>>()
        var verifiedTarget: DurableTargetSnapshot? = null
        val store: AnkiMutationStore =
            Proxy.newProxyInstance(
                AnkiMutationStore::class.java.classLoader,
                arrayOf(AnkiMutationStore::class.java),
                InvocationHandler { _, method, args ->
                    calls += method.name
                    when (method.name) {
                        "recoveryInventory" -> inventory
                        "abandonOwnerless" -> emptyList<Any>()
                        "completeRoutingChild" -> {
                            routingOutcomes += args!![1] to args[2]
                            inventory = DRAINED
                            null
                        }
                        "completeUncertainDeck" -> {
                            inventory = DRAINED
                            null
                        }
                        "completeVerifiedDeck" -> {
                            verifiedTarget = args!![1] as DurableTargetSnapshot
                            inventory = DRAINED
                            null
                        }
                        else -> throw AssertionError("unexpected store call ${method.name}")
                    }
                },
            ) as AnkiMutationStore
    }

    private companion object {
        val DRAINED = RecoveryInventory(emptyList(), null, null)

        val MODEL =
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
            )
    }
}
