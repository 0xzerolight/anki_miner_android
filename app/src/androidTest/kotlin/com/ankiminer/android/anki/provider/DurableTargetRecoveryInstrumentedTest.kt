package com.ankiminer.android.anki.provider

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ankiminer.android.anki.journal.JournalRequest
import com.ankiminer.android.anki.journal.MediaReservationDraft
import com.ankiminer.android.anki.journal.MutationCommand
import com.ankiminer.android.anki.journal.ProviderReceipt
import com.ankiminer.android.anki.journal.RemediationKind
import com.ankiminer.android.anki.journal.SqliteAnkiMutationStore
import com.ankiminer.android.anki.protocol.MediaAsset
import com.ankiminer.android.anki.protocol.StoreMediaRequest
import com.ankiminer.android.anki.protocol.VerifyTargetRequest
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DurableTargetRecoveryInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun pre_entry_deck_work_is_proven_not_committed_without_provider_access() =
        withStore { store ->
            val request = request()
            store.createParent(request)
            store.beginParent(request.key)
            store.storeTargetExpectation(request.key, target().model.toDurableExpectation("Mining"))
            store.prepareChild(request.key, MutationCommand.CreateDeck("Mining"))
            val gateway = FakeAnkiProviderGateway()
            gateway.queryHandler = { query, _ -> error("provider must not be queried: $query") }
            gateway.createDeckHandler = { error("recovery must never create") }
            val gate = gate(store, gateway)

            gate.ensureRecovered()

            assertTrue(gate.isOpen())
            assertTrue(gateway.queries.isEmpty())
            assertTrue(gateway.deckCommands.isEmpty())
            assertInventoryDrained(store)
        }

    @Test
    fun entered_deck_is_reconciled_by_exact_reads_but_never_installed_or_recreated() =
        withStore { store ->
            val request = request()
            val target = target()
            store.createParent(request)
            store.beginParent(request.key)
            store.storeTargetExpectation(request.key, target.model.toDurableExpectation("Mining"))
            val child = store.prepareChild(request.key, MutationCommand.CreateDeck("Mining"))
            store.recordProviderEntry(child.id)
            store.recordDeckReceipt(
                child.id,
                ProviderReceipt.Deck(
                    target.deck.id,
                    "content://com.ichi2.anki.flashcards/decks/${target.deck.id}",
                ),
            )
            val gateway = FakeAnkiProviderGateway()
            gateway.queryHandler = exactTargetHandler(deckExists = true)
            gateway.createDeckHandler = { error("recovery must never create") }
            val gate = gate(store, gateway)

            gate.ensureRecovered()

            assertTrue(gate.isOpen())
            assertTrue(gateway.deckCommands.isEmpty())
            assertEquals(
                listOf(
                    ProviderEndpoint.MODEL_BY_ID,
                    ProviderEndpoint.MODEL_TEMPLATES,
                    ProviderEndpoint.DECKS,
                    ProviderEndpoint.DECK_BY_ID,
                ),
                gateway.queries.map { it.endpoint },
            )
            assertInventoryDrained(store)
            assertTrue(store.openRemediations().isEmpty())

            val registry = AnkiRunStateRegistry(startupAdmission = gate)
            assertTrue(registry.register(RUN_ID, AnkiCancellation.NONE))
            registry.withOwner(RUN_ID) { owner -> assertNull(registry.target(owner)) }
        }

    @Test
    fun inconclusive_entered_deck_becomes_remediated_uncertainty_and_gate_can_open() =
        withStore { store ->
            val request = request()
            store.createParent(request)
            store.beginParent(request.key)
            store.storeTargetExpectation(request.key, target().model.toDurableExpectation("Mining"))
            val child = store.prepareChild(request.key, MutationCommand.CreateDeck("Mining"))
            store.recordProviderEntry(child.id)
            val gateway = FakeAnkiProviderGateway()
            gateway.queryHandler = exactTargetHandler(deckExists = false)
            gateway.createDeckHandler = { error("recovery must never create") }
            val gate = gate(store, gateway)

            assertFalse(gate.isOpen())
            gate.ensureRecovered()

            assertTrue(gate.isOpen())
            assertTrue(gateway.deckCommands.isEmpty())
            assertEquals(
                listOf(RemediationKind.DECK_COMMIT_UNCERTAIN),
                store.openRemediations().map { it.kind },
            )
            assertInventoryDrained(store)
        }

    @Test
    fun concurrent_registration_serializes_recovery_once_then_admits_one_run() =
        withStore { store ->
            val request = request()
            store.createParent(request)
            store.beginParent(request.key)
            store.storeTargetExpectation(request.key, target().model.toDurableExpectation("Mining"))
            val child = store.prepareChild(request.key, MutationCommand.CreateDeck("Mining"))
            store.recordProviderEntry(child.id)
            val firstQuery = CountDownLatch(1)
            val allowRecovery = CountDownLatch(1)
            val queryCount = AtomicInteger()
            val delegate = exactTargetHandler(deckExists = true)
            val gateway = FakeAnkiProviderGateway()
            gateway.queryHandler = { query, cancellation ->
                if (queryCount.incrementAndGet() == 1) {
                    firstQuery.countDown()
                    check(allowRecovery.await(5, TimeUnit.SECONDS))
                }
                delegate(query, cancellation)
            }
            gateway.createDeckHandler = { error("recovery must never create") }
            val gate = gate(store, gateway)
            val registry =
                AnkiRunStateRegistry(
                    startupAdmission = gate,
                    cleanup = JournalAnkiRunCleanup(store),
                )
            val reads = AnkiProviderReadService(gateway, registry)
            val callbacks =
                AnkiProviderCallbacks(
                    registry = registry,
                    reads = reads,
                    targetVerifier =
                        DurableTargetVerifier(
                            gateway,
                            registry,
                            AnkiMutationTargetVerificationJournal(store),
                        ),
                    mediaMutations = MediaMutationService { _, _ -> error("media mutation is not expected") },
                    workerThreadGuard = WorkerThreadGuard { },
                    startupRecoveryGate = gate,
                )
            val results = Collections.synchronizedList(mutableListOf<Boolean>())
            val failures = Collections.synchronizedList(mutableListOf<Throwable>())
            val done = CountDownLatch(8)
            repeat(8) {
                thread {
                    try {
                        results += callbacks.registerRun(RUN_ID)
                    } catch (error: Throwable) {
                        failures += error
                    } finally {
                        done.countDown()
                    }
                }
            }
            assertTrue(firstQuery.await(5, TimeUnit.SECONDS))

            allowRecovery.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS))

            assertTrue(failures.isEmpty())
            assertEquals(1, results.count { it })
            assertEquals(7, results.count { !it })
            assertTrue(gate.isOpen())
            assertEquals(3, queryCount.get())
            assertTrue(gateway.deckCommands.isEmpty())
            assertInventoryDrained(store)
        }

    @Test
    fun non_target_prepared_child_keeps_registration_closed_without_provider_mutation() =
        withStore { store ->
            val request =
                JournalRequest.from(
                    StoreMediaRequest(
                        runId = RUN_ID,
                        requestId = REQUEST_ID,
                        assets =
                            listOf(
                                MediaAsset(
                                    assetId = ASSET_ID,
                                    sourcePath = "/tmp/audio.mp3",
                                    preferredName = "audio",
                                    requestedFilename = "audio.mp3",
                                    purpose = com.ankiminer.android.anki.protocol.MediaPurpose.CARD,
                                    mediaKind = com.ankiminer.android.anki.protocol.MediaKind.AUDIO,
                                    expectedSizeBytes = 3L,
                                    expectedSha256 = SHA,
                                ),
                            ),
                    ),
                )
            store.createParent(request)
            store.beginParent(request.key)
            store.acquireMediaLease(RUN_ID)
            val reservation =
                store.reserveMedia(
                    RUN_ID,
                    listOf(
                        MediaReservationDraft(
                            requestId = REQUEST_ID,
                            assetId = ASSET_ID,
                            requestedFilename = "audio.mp3",
                            preferredName = "audio",
                            sha256 = SHA,
                            purpose = com.ankiminer.android.anki.journal.MediaPurpose.CARD,
                            mediaKind = com.ankiminer.android.anki.journal.MediaKind.AUDIO,
                        ),
                    ),
                ).single()
            val promotion =
                store.promoteReservation(
                    request.key,
                    reservation.id,
                    MutationCommand.StoreMedia(
                        requestIndexValue = 0,
                        assetId = ASSET_ID,
                        fileUri = "content://com.ankiminer.files/audio.mp3",
                        preferredName = "audio",
                    ),
                )
            val gateway = FakeAnkiProviderGateway()
            gateway.queryHandler = { query, _ -> error("provider must not be queried: $query") }
            gateway.createDeckHandler = { error("recovery must never create") }
            val gate = gate(store, gateway)
            val registry = AnkiRunStateRegistry(startupAdmission = gate)
            val reads = AnkiProviderReadService(gateway, registry)
            val callbacks =
                AnkiProviderCallbacks(
                    registry = registry,
                    reads = reads,
                    targetVerifier =
                        DurableTargetVerifier(
                            gateway,
                            registry,
                            AnkiMutationTargetVerificationJournal(store),
                        ),
                    mediaMutations = MediaMutationService { _, _ -> error("media mutation is not expected") },
                    workerThreadGuard = WorkerThreadGuard { },
                    startupRecoveryGate = gate,
                )

            assertFalse(callbacks.registerRun(RUN_ID))
            assertFalse(gate.isOpen())
            assertEquals(promotion.child.id, store.recoveryInventory().preparedChild?.id)
            assertTrue(gateway.queries.isEmpty())
            assertTrue(gateway.deckCommands.isEmpty())
        }

    private fun gate(
        store: SqliteAnkiMutationStore,
        gateway: FakeAnkiProviderGateway,
    ) =
        JournalBackedTargetRecoveryGate(
            store,
            gateway,
            WorkerThreadGuard { },
            MediaStagingRecovery { AnkiMediaRecoveryReport(0, 0, 0) },
        )

    private fun exactTargetHandler(
        deckExists: Boolean,
    ): (ProviderQuery, AnkiCancellation) -> ProviderCursor? = { query, _ ->
        when (query.endpoint) {
            ProviderEndpoint.MODEL_BY_ID ->
                FakeProviderCursor(query.projection, listOf(modelRow()))
            ProviderEndpoint.MODEL_TEMPLATES ->
                FakeProviderCursor(query.projection, listOf(templateRow()))
            ProviderEndpoint.DECKS, ProviderEndpoint.DECK_BY_ID ->
                FakeProviderCursor(
                    query.projection,
                    if (deckExists) listOf(deckRow()) else emptyList(),
                )
            else -> error("unexpected query $query")
        }
    }

    private fun assertInventoryDrained(store: SqliteAnkiMutationStore) {
        val inventory = store.recoveryInventory()
        assertNull(inventory.preparedChild)
        assertTrue(inventory.unfinishedParents.isEmpty())
    }

    private inline fun withStore(block: (SqliteAnkiMutationStore) -> Unit) {
        val name = "target-recovery-${System.nanoTime()}.db"
        try {
            SqliteAnkiMutationStore(
                context,
                name,
                enforceBackgroundThread = false,
            ).use(block)
        } finally {
            context.deleteDatabase(name)
        }
    }

    private fun request() =
        JournalRequest.from(
            VerifyTargetRequest(
                runId = RUN_ID,
                requestId = REQUEST_ID,
                deckName = "Mining",
                modelName = "Mining",
                requiredFields = listOf("Expression"),
            ),
        )

    private fun target() =
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

    private companion object {
        const val RUN_ID = "run_11111111111111111111111111111111"
        const val REQUEST_ID = "anki_11111111111111111111111111111111"
        const val ASSET_ID = "asset_11111111111111111111111111111111"
        const val SHA = "0000000000000000000000000000000000000000000000000000000000000000"
    }
}

private class FakeAnkiProviderGateway : AnkiProviderGateway {
    var queryHandler: (ProviderQuery, AnkiCancellation) -> ProviderCursor? = { query, _ ->
        FakeProviderCursor(query.projection, emptyList())
    }
    var createDeckHandler: (AnkiProviderMutationCommand.CreateDeck) -> String? = { null }
    val queries = mutableListOf<ProviderQuery>()
    val deckCommands = mutableListOf<AnkiProviderMutationCommand.CreateDeck>()

    override fun accessStatus(): ProviderAccessStatus =
        ProviderAccessStatus.Available("com.ichi2.anki", 2, 20240000)

    override fun query(
        query: ProviderQuery,
        cancellation: AnkiCancellation,
    ): ProviderCursor? {
        queries += query
        return queryHandler(query, cancellation)
    }

    override fun fieldChecksum(firstField: String): Long =
        firstField.hashCode().toLong() and 0xffff_ffffL

    override fun createDeck(command: AnkiProviderMutationCommand.CreateDeck): String? {
        deckCommands += command
        return createDeckHandler(command)
    }

    override fun storeMedia(command: AnkiProviderMutationCommand.StoreMedia): String? = null

    override fun insertNote(command: AnkiProviderMutationCommand.InsertNote): String? = null

    override fun routeCard(command: AnkiProviderMutationCommand.RouteCard): Int = 0
}

private class FakeProviderCursor(
    override val projection: List<ProviderColumn>,
    private val rows: List<Map<ProviderColumn, ProviderCell>>,
) : ProviderCursor {
    private var index = -1

    override fun moveToNext(): Boolean {
        index += 1
        return index < rows.size
    }

    override fun cell(column: ProviderColumn): ProviderCell =
        rows[index][column] ?: error("missing fake provider column $column")

    override fun close() = Unit
}

private fun modelRow(): Map<ProviderColumn, ProviderCell> =
    mapOf(
        ProviderColumn.MODEL_ID to ProviderCell.Integer(10L),
        ProviderColumn.MODEL_NAME to ProviderCell.Text("Mining"),
        ProviderColumn.MODEL_FIELD_NAMES to ProviderCell.Text("Expression\u001fMeaning"),
        ProviderColumn.MODEL_CARD_COUNT to ProviderCell.Integer(1L),
        ProviderColumn.MODEL_CSS to ProviderCell.Text("css"),
        ProviderColumn.MODEL_DEFAULT_DECK_ID to ProviderCell.Integer(1L),
        ProviderColumn.MODEL_SORT_FIELD_INDEX to ProviderCell.Integer(0L),
        ProviderColumn.MODEL_TYPE to ProviderCell.Integer(0L),
        ProviderColumn.MODEL_LATEX_POST to ProviderCell.Text("post"),
        ProviderColumn.MODEL_LATEX_PRE to ProviderCell.Text("pre"),
    )

private fun templateRow(): Map<ProviderColumn, ProviderCell> =
    mapOf(
        ProviderColumn.TEMPLATE_MODEL_ID to ProviderCell.Integer(10L),
        ProviderColumn.TEMPLATE_ORDINAL to ProviderCell.Integer(0L),
        ProviderColumn.TEMPLATE_NAME to ProviderCell.Text("Card 1"),
        ProviderColumn.TEMPLATE_QUESTION_FORMAT to ProviderCell.Text("{{Expression}}"),
        ProviderColumn.TEMPLATE_ANSWER_FORMAT to ProviderCell.Text("{{Meaning}}"),
        ProviderColumn.TEMPLATE_BROWSER_QUESTION_FORMAT to ProviderCell.Null,
        ProviderColumn.TEMPLATE_BROWSER_ANSWER_FORMAT to ProviderCell.Null,
    )

private fun deckRow(): Map<ProviderColumn, ProviderCell> =
    mapOf(
        ProviderColumn.DECK_ID to ProviderCell.Integer(20L),
        ProviderColumn.DECK_NAME to ProviderCell.Text("Mining"),
        ProviderColumn.DECK_DYNAMIC to ProviderCell.Integer(0L),
    )
