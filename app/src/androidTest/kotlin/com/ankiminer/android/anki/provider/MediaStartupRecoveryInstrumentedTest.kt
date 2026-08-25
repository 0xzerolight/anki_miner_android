package com.ankiminer.android.anki.provider

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ankiminer.android.anki.journal.ActiveNoteMaterialization
import com.ankiminer.android.anki.journal.DurableDeckSnapshot
import com.ankiminer.android.anki.journal.DurableDuplicateDecision
import com.ankiminer.android.anki.journal.DurableMediaBinding
import com.ankiminer.android.anki.journal.DurableModelSnapshot
import com.ankiminer.android.anki.journal.DurableTargetSnapshot
import com.ankiminer.android.anki.journal.DurableTemplateSnapshot
import com.ankiminer.android.anki.journal.JournalResponse
import com.ankiminer.android.anki.journal.JournalCorruptionException
import com.ankiminer.android.anki.journal.JournalRequest
import com.ankiminer.android.anki.journal.MediaClaimState
import com.ankiminer.android.anki.journal.MediaReservationDraft
import com.ankiminer.android.anki.journal.MutationCommand
import com.ankiminer.android.anki.journal.OrderedNoteField
import com.ankiminer.android.anki.journal.ProviderReceipt
import com.ankiminer.android.anki.journal.RemediationKind
import com.ankiminer.android.anki.journal.SqliteAnkiMutationStore
import com.ankiminer.android.anki.journal.StagingDraft
import com.ankiminer.android.anki.journal.StagingState
import com.ankiminer.android.anki.protocol.MediaAsset
import com.ankiminer.android.anki.protocol.CollectionCreateDuplicateScope
import com.ankiminer.android.anki.protocol.CreateDuplicateCandidate
import com.ankiminer.android.anki.protocol.CreateNote
import com.ankiminer.android.anki.protocol.CreateNotesRequest
import com.ankiminer.android.anki.protocol.MediaBinding
import com.ankiminer.android.anki.protocol.MediaKind
import com.ankiminer.android.anki.protocol.MediaPurpose
import com.ankiminer.android.anki.protocol.StoreMediaRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaStartupRecoveryInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun preEntryMediaIsProvenNotCommittedBeforeGlobalStagingCleanup() =
        withStore { store, _ ->
            prepareMedia(store, entered = false)
            val gateway = NoWriteGateway()
            val staging = ScriptedStagingRecovery(store, cleanOutcomes = listOf(true))
            val gate = gate(store, gateway, staging)

            gate.ensureRecovered()

            assertTrue(gate.isOpen())
            assertTrue(gateway.mediaCommands.isEmpty())
            assertEquals(listOf(true), staging.journalDrainedBeforeCall)
            assertTrue(store.stagingForRecovery().isEmpty())
            assertInventoryDrained(store)
            assertTrue(store.recoveryInventory().unresolvedClaims.isEmpty())
        }

    @Test
    fun enteredReceiptlessMediaResolvesUncertainWithoutProviderReissue() =
        withStore { store, _ ->
            val fixture = prepareMedia(store, entered = true)
            val gateway = NoWriteGateway()
            val staging = ScriptedStagingRecovery(store, cleanOutcomes = listOf(true))
            val gate = gate(store, gateway, staging)

            gate.ensureRecovered()

            assertTrue(gate.isOpen())
            assertTrue(gateway.mediaCommands.isEmpty())
            assertTrue(store.recoveryInventory().unresolvedClaims.isEmpty())
            assertTrue(store.openRemediations().isEmpty())
            assertEquals(
                MediaClaimState.ACKNOWLEDGED_BY_USER,
                store.mediaClaim(fixture.request.key, ASSET_ID)?.state,
            )
            assertInventoryDrained(store)
        }

    @Test
    fun exactPersistedReceiptIsCommittedAndOrphanedMediaAutoResolves() =
        withStore { store, databaseName ->
            val fixture = prepareMedia(store, entered = true)
            insertRawMediaReceipt(
                databaseName,
                fixture.childId,
                actualFilename = REQUESTED_FILENAME,
                fileUri = "file:///$REQUESTED_FILENAME",
            )
            val gateway = NoWriteGateway()
            val staging = ScriptedStagingRecovery(store, cleanOutcomes = listOf(true))
            val gate = gate(store, gateway, staging)

            gate.ensureRecovered()

            assertTrue(gate.isOpen())
            assertTrue(gateway.mediaCommands.isEmpty())
            assertTrue(store.recoveryInventory().unresolvedClaims.isEmpty())
            assertTrue(store.openRemediations().isEmpty())
            val claim = requireNotNull(store.mediaClaim(fixture.request.key, ASSET_ID))
            assertEquals(MediaClaimState.ACKNOWLEDGED_BY_USER, claim.state)
            assertEquals(REQUESTED_FILENAME, claim.actualFilename)
            assertInventoryDrained(store)
        }

    @Test
    fun presentBytesClaimSurvivesLiveNoteBindingThenAutoResolvesWhenItsOwnerDies() =
        withStore { store, _ ->
            val fixture = prepareMedia(store, entered = true)
            store.commitMediaReceipt(
                childId = fixture.childId,
                claimId = fixture.claimId,
                receipt = ProviderReceipt.Media(REQUESTED_FILENAME, "file:///$REQUESTED_FILENAME"),
                compactEvidence = "instrumented exact provider receipt",
            )
            store.markResultReady(
                fixture.request,
                JournalResponse.StoreMedia(
                    fixture.request.key,
                    store.alignedResults(fixture.request.key),
                    error = null,
                ),
            )

            val noteRequest = noteRequest()
            store.createParent(noteRequest)
            store.beginParent(noteRequest.key)
            store.storeTargetSnapshot(noteRequest.key, targetSnapshot())
            store.materializeActiveNote(
                noteRequest.key,
                ActiveNoteMaterialization(
                    requestIndex = 0,
                    clientNoteId = CLIENT_NOTE_ID,
                    orderedFields =
                        listOf(
                            OrderedNoteField("Expression", "語"),
                            OrderedNoteField("Meaning", "word"),
                        ),
                    joinedFields = "語\u001fword",
                    normalizedTags = listOf("mined"),
                    providerTagsWire = "mined",
                    duplicateDecision = DurableDuplicateDecision("key-1", "語", 0, false),
                    mediaBindings =
                        listOf(
                            DurableMediaBinding(ASSET_ID, REQUESTED_FILENAME, fixture.claimId),
                        ),
                ),
            )
            store.prepareChild(
                noteRequest.key,
                MutationCommand.InsertNote(0, CLIENT_NOTE_ID, 11, "語\u001fword", "mined"),
            )

            // The media parent finalizes while a live note materialization still references the
            // claim's bytes: the sweep must leave the claim and its remediation for the resume.
            store.abandonOwnerless(emptySet())
            assertEquals(
                listOf(RemediationKind.MEDIA_STORED_UNATTACHED),
                store.openRemediations().map { it.kind },
            )
            store.transitionClaim(
                claimId = fixture.claimId,
                state = MediaClaimState.PRESENT_BYTES_VERIFIED,
                actualFilename = REQUESTED_FILENAME,
                compactEvidence = "instrumented provider bytes verified",
            )
            assertEquals(
                MediaClaimState.PRESENT_BYTES_VERIFIED,
                store.recoveryInventory().unresolvedClaims.single().state,
            )
            val gateway = NoWriteGateway()
            val staging = ScriptedStagingRecovery(store, cleanOutcomes = listOf(true))
            val gate = gate(store, gateway, staging)

            gate.ensureRecovered()

            assertTrue(gate.isOpen())
            assertTrue(gateway.mediaCommands.isEmpty())
            assertTrue(store.recoveryInventory().unresolvedClaims.isEmpty())
            assertTrue(store.openRemediations().isEmpty())
            assertEquals(
                MediaClaimState.ACKNOWLEDGED_BY_USER,
                store.mediaClaim(fixture.request.key, ASSET_ID)?.state,
            )
            assertInventoryDrained(store)
        }

    @Test
    fun invalidPersistedReceiptLeavesJournalAndStagingUntouched() =
        withStore { store, databaseName ->
            val fixture = prepareMedia(store, entered = true)
            insertRawMediaReceipt(
                databaseName,
                fixture.childId,
                actualFilename = "unrelated.mp3",
                fileUri = "file:///unrelated.mp3",
            )
            val gateway = NoWriteGateway()
            val staging = ScriptedStagingRecovery(store, cleanOutcomes = listOf(true))
            val gate = gate(store, gateway, staging)

            assertThrows(JournalCorruptionException::class.java) { gate.ensureRecovered() }

            assertFalse(gate.isOpen())
            assertTrue(gateway.mediaCommands.isEmpty())
            assertEquals(0, staging.calls)
            assertEquals(fixture.childId, store.recoveryInventory().preparedChild?.id)
            assertEquals(1, store.stagingForRecovery().size)
            assertTrue(store.openRemediations().isEmpty())
        }

    @Test
    fun persistedReceiptFilenameAndUriDisagreementLeavesRecoveryGateClosed() =
        withStore { store, databaseName ->
            val fixture = prepareMedia(store, entered = true)
            insertRawMediaReceipt(
                databaseName,
                fixture.childId,
                actualFilename = REQUESTED_FILENAME,
                fileUri = "file:///different.mp3",
            )
            val gateway = NoWriteGateway()
            val staging = ScriptedStagingRecovery(store, cleanOutcomes = listOf(true))
            val gate = gate(store, gateway, staging)

            assertThrows(JournalCorruptionException::class.java) { gate.ensureRecovered() }

            assertFalse(gate.isOpen())
            assertTrue(gateway.mediaCommands.isEmpty())
            assertEquals(0, staging.calls)
            assertEquals(fixture.childId, store.recoveryInventory().preparedChild?.id)
            assertEquals(1, store.stagingForRecovery().size)
            assertTrue(store.openRemediations().isEmpty())
        }

    @Test
    fun preparedMediaWithMismatchedStagingDigestLeavesRecoveryGateClosed() =
        withStore { store, _ ->
            val fixture = prepareMedia(store, entered = false, stagingSha = OTHER_SHA)
            val gateway = NoWriteGateway()
            val staging = ScriptedStagingRecovery(store, cleanOutcomes = listOf(true))
            val gate = gate(store, gateway, staging)

            assertThrows(JournalCorruptionException::class.java) { gate.ensureRecovered() }

            assertFalse(gate.isOpen())
            assertTrue(gateway.mediaCommands.isEmpty())
            assertEquals(0, staging.calls)
            assertEquals(fixture.childId, store.recoveryInventory().preparedChild?.id)
            assertEquals(OTHER_SHA, store.stagingForRecovery().single().sha256)
            assertTrue(store.openRemediations().isEmpty())
        }

    @Test
    fun uncleanStagingKeepsGateClosedAndASecondRecoveryRetriesCleanup() =
        withStore { store, _ ->
            prepareMedia(store, entered = false)
            val staging = ScriptedStagingRecovery(store, cleanOutcomes = listOf(false, true))
            val gate = gate(store, NoWriteGateway(), staging)

            assertThrows(PendingMediaStagingRecoveryException::class.java) {
                gate.ensureRecovered()
            }
            assertFalse(gate.isOpen())
            assertInventoryDrained(store)
            assertEquals(1, store.stagingForRecovery().size)

            gate.ensureRecovered()

            assertTrue(gate.isOpen())
            assertEquals(2, staging.calls)
            assertEquals(listOf(true, true), staging.journalDrainedBeforeCall)
            assertTrue(store.stagingForRecovery().isEmpty())
        }

    @Test
    fun danglingMediaCapabilityFailsTypedQuiescenceBeforeStagingRecovery() =
        withStore { store, _ ->
            store.acquireMediaLease(OTHER_RUN_ID)
            val staging = ScriptedStagingRecovery(store, cleanOutcomes = listOf(true))
            val gate = gate(store, NoWriteGateway(), staging)

            assertThrows(JournalCorruptionException::class.java) { gate.ensureRecovered() }

            assertFalse(gate.isOpen())
            assertEquals(0, staging.calls)
            assertEquals(listOf(OTHER_RUN_ID), store.recoveryInventory().activeMediaLeaseRunIds)
        }

    private fun prepareMedia(
        store: SqliteAnkiMutationStore,
        entered: Boolean,
        stagingSha: String = SHA,
    ): MediaFixture {
        val request =
            JournalRequest.from(
                StoreMediaRequest(
                    runId = RUN_ID,
                    requestId = REQUEST_ID,
                    assets =
                        listOf(
                            MediaAsset(
                                assetId = ASSET_ID,
                                sourcePath = "/tmp/$REQUESTED_FILENAME",
                                preferredName = PREFERRED_NAME,
                                requestedFilename = REQUESTED_FILENAME,
                                purpose = MediaPurpose.CARD,
                                mediaKind = MediaKind.AUDIO,
                                expectedSizeBytes = 3,
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
                        requestedFilename = REQUESTED_FILENAME,
                        preferredName = PREFERRED_NAME,
                        sha256 = SHA,
                        purpose = com.ankiminer.android.anki.journal.MediaPurpose.CARD,
                        mediaKind = com.ankiminer.android.anki.journal.MediaKind.AUDIO,
                    ),
                ),
            ).single()
        val staged =
            store.recordStaging(
                StagingDraft(
                    runId = RUN_ID,
                    requestId = REQUEST_ID,
                    assetId = ASSET_ID,
                    relativePath = "v1/${"a".repeat(64)}.stage",
                    contentUri = STAGING_URI,
                    packageName = ANKIDROID_PACKAGE,
                    sizeBytes = 3,
                    sha256 = stagingSha,
                ),
            )
        store.transitionStaging(staged.id, StagingState.GRANTED, "exact test grant")
        val promotion =
            store.promoteReservation(
                request.key,
                reservation.id,
                MutationCommand.StoreMedia(
                    requestIndexValue = 0,
                    assetId = ASSET_ID,
                    fileUri = STAGING_URI,
                    preferredName = PREFERRED_NAME,
                ),
            )
        if (entered) store.recordProviderEntry(promotion.child.id)
        return MediaFixture(request, promotion.child.id, promotion.claim.id)
    }

    private fun noteRequest(): JournalRequest =
        JournalRequest.from(
            CreateNotesRequest(
                runId = RUN_ID,
                requestId = NOTE_REQUEST_ID,
                deckName = "Mining",
                modelName = "Mining Model",
                firstFieldName = "Expression",
                baselineToken = BASELINE_ID,
                duplicateScope = CollectionCreateDuplicateScope,
                notes =
                    listOf(
                        CreateNote(
                            clientNoteId = CLIENT_NOTE_ID,
                            fields = linkedMapOf("Expression" to "語", "Meaning" to "word"),
                            tags = listOf("mined"),
                            duplicateCandidate = CreateDuplicateCandidate("key-1", "語", 0),
                            mediaBindings = listOf(MediaBinding(ASSET_ID, REQUESTED_FILENAME)),
                        ),
                    ),
            ),
        )

    private fun targetSnapshot() =
        DurableTargetSnapshot(
            deck = DurableDeckSnapshot(2, "Mining", dynamic = false),
            model =
                DurableModelSnapshot(
                    id = 11,
                    name = "Mining Model",
                    type = 0,
                    fieldNames = listOf("Expression", "Meaning"),
                    cardCount = 1,
                    sortFieldIndex = 0,
                    effectiveDefaultDeckId = 1,
                    css = ".card { color: black; }",
                    latexPre = null,
                    latexPost = "",
                    templates =
                        listOf(
                            DurableTemplateSnapshot(
                                modelId = 11,
                                ordinal = 0,
                                name = "Card 1",
                                questionFormat = "{{Expression}}",
                                answerFormat = "{{FrontSide}}<hr>{{Meaning}}",
                                browserQuestionFormat = null,
                                browserAnswerFormat = null,
                            ),
                        ),
                ),
        )

    private fun insertRawMediaReceipt(
        databaseName: String,
        childId: Long,
        actualFilename: String,
        fileUri: String,
    ) {
        context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).use { database ->
            database.execSQL(
                "INSERT INTO media_receipts(child_id, actual_filename, file_uri) VALUES (?, ?, ?)",
                arrayOf<Any>(childId, actualFilename, fileUri),
            )
        }
    }

    private fun gate(
        store: SqliteAnkiMutationStore,
        gateway: NoWriteGateway,
        staging: MediaStagingRecovery,
    ) =
        JournalBackedTargetRecoveryGate(
            store = store,
            gateway = gateway,
            workerThreadGuard = WorkerThreadGuard { },
            mediaStagingRecovery = staging,
        )

    private fun assertInventoryDrained(store: SqliteAnkiMutationStore) {
        val inventory = store.recoveryInventory()
        assertNull(inventory.preparedChild)
        assertTrue(inventory.unfinishedParents.isEmpty())
        assertTrue(inventory.activeMediaLeaseRunIds.isEmpty())
        assertTrue(inventory.reservedMediaReservationIds.isEmpty())
    }

    private inline fun withStore(block: (SqliteAnkiMutationStore, String) -> Unit) {
        val name = "media-startup-recovery-${System.nanoTime()}.db"
        try {
            SqliteAnkiMutationStore(
                context,
                name,
                enforceBackgroundThread = false,
            ).use { store -> block(store, name) }
        } finally {
            context.deleteDatabase(name)
        }
    }

    private data class MediaFixture(
        val request: JournalRequest,
        val childId: Long,
        val claimId: Long,
    )

    private companion object {
        const val RUN_ID = "run_11111111111111111111111111111111"
        const val OTHER_RUN_ID = "run_22222222222222222222222222222222"
        const val REQUEST_ID = "anki_11111111111111111111111111111111"
        const val NOTE_REQUEST_ID = "anki_22222222222222222222222222222222"
        const val ASSET_ID = "asset_11111111111111111111111111111111"
        const val CLIENT_NOTE_ID = "note_11111111111111111111111111111111"
        const val BASELINE_ID = "baseline_11111111111111111111111111111111"
        const val REQUESTED_FILENAME = "audio.mp3"
        const val PREFERRED_NAME = "audio"
        const val SHA = "0000000000000000000000000000000000000000000000000000000000000000"
        const val OTHER_SHA = "1111111111111111111111111111111111111111111111111111111111111111"
        const val STAGING_URI = "content://com.ankiminer.android.anki-media/anki_media_staging/v1/a.stage"
    }
}

private class ScriptedStagingRecovery(
    private val store: SqliteAnkiMutationStore,
    cleanOutcomes: List<Boolean>,
) : MediaStagingRecovery {
    private val outcomes = ArrayDeque(cleanOutcomes)
    var calls: Int = 0
        private set
    val journalDrainedBeforeCall = mutableListOf<Boolean>()

    init {
        require(cleanOutcomes.isNotEmpty())
    }

    override fun recover(): AnkiMediaRecoveryReport {
        calls += 1
        val inventory = store.recoveryInventory()
        journalDrainedBeforeCall +=
            inventory.preparedChild == null &&
            inventory.unfinishedParents.isEmpty() &&
            inventory.activeMediaLeaseRunIds.isEmpty() &&
            inventory.reservedMediaReservationIds.isEmpty()
        val clean = outcomes.removeFirst()
        if (!clean) return AnkiMediaRecoveryReport(0, 1, 0)
        val records = store.stagingForRecovery()
        records.forEach { record ->
            store.completeStagingCleanup(record.id, "instrumented recovery cleanup")
        }
        return AnkiMediaRecoveryReport(records.size, 0, 0)
    }
}

private class NoWriteGateway : AnkiProviderGateway {
    val mediaCommands = mutableListOf<AnkiProviderMutationCommand.StoreMedia>()

    override fun accessStatus(): ProviderAccessStatus =
        ProviderAccessStatus.Available("com.ichi2.anki", 2, 20240000)

    override fun query(
        query: ProviderQuery,
        cancellation: AnkiCancellation,
    ): ProviderCursor? = error("media startup recovery must not query the provider: $query")

    override fun fieldChecksum(firstField: String): Long =
        firstField.hashCode().toLong() and 0xffff_ffffL

    override fun createDeck(command: AnkiProviderMutationCommand.CreateDeck): String? =
        error("media startup recovery must not create a deck")

    override fun storeMedia(command: AnkiProviderMutationCommand.StoreMedia): String? {
        mediaCommands += command
        error("media startup recovery must not reissue a provider media write")
    }

    override fun insertNote(command: AnkiProviderMutationCommand.InsertNote): String? =
        error("media startup recovery must not insert a note")

    override fun routeCard(command: AnkiProviderMutationCommand.RouteCard): Int =
        error("media startup recovery must not route a card")

    override fun deleteNote(command: AnkiProviderMutationCommand.DeleteNote): Int =
        error("media startup recovery must not delete a note")
}
