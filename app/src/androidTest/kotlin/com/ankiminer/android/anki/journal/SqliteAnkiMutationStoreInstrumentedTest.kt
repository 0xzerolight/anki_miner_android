package com.ankiminer.android.anki.journal

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ankiminer.android.anki.protocol.CollectionCreateDuplicateScope
import com.ankiminer.android.anki.protocol.CreateDuplicateCandidate
import com.ankiminer.android.anki.protocol.CreateNote
import com.ankiminer.android.anki.protocol.CreateNotesRequest
import com.ankiminer.android.anki.protocol.MediaAsset
import com.ankiminer.android.anki.protocol.MediaBinding
import com.ankiminer.android.anki.protocol.StoreMediaRequest
import com.ankiminer.android.anki.protocol.VerifyTargetRequest
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SqliteAnkiMutationStoreInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun freshSchemaUsesWalForeignKeysNormalizedRowsAndStrictFingerprint() {
        val name = databaseName()
        try {
            val request = verifyRequest(1, 1)
            SqliteAnkiMutationStore(context, name, enforceBackgroundThread = false).use { store ->
                val parent = store.createParent(request)
                assertEquals("wal", pragma(store.writableDatabase, "journal_mode").lowercase())
                assertEquals("1", pragma(store.writableDatabase, "foreign_keys"))
                assertEquals("2", pragma(store.writableDatabase, "synchronous"))
                assertEquals(1, parent.digestVersion)
                assertEquals(listOf(JournalRequest.TARGET_ITEM_ID), store.requestItems(request.key).map { it.itemId })
                assertEquals(ParentState.RUNNING, store.beginParent(request.key).state)
            }

            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                val tables =
                    db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'", null).use {
                        buildSet { while (it.moveToNext()) add(it.getString(0)) }
                    } - "android_metadata"
                assertEquals(JournalSchema.requiredTables, tables)
                val parentColumns =
                    db.rawQuery("PRAGMA table_info(parents)", null).use {
                        buildSet { while (it.moveToNext()) add(it.getString(it.getColumnIndexOrThrow("name"))) }
                    }
                assertTrue(parentColumns.none { it.endsWith("_json") || it == "result_json" })
                db.execSQL("CREATE TABLE obsolete_json_scratch(payload TEXT)")
            }

            assertThrows(JournalCorruptionException::class.java) {
                SqliteAnkiMutationStore(context, name, enforceBackgroundThread = false).use { it.parent(request.key) }
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun versionOneAndTwoUpgradeLosslesslyBackfillsChildlessRoutingObservations() {
        listOf(1, 2).forEach { oldVersion ->
            val name = databaseName()
            val request = createRequest(200 + oldVersion, 1, 1)
            val providerNoteId = 20_000L + oldVersion
            var legacyStoredMedia: Pair<JournalRequest, MediaPromotion>? = null
            try {
                SqliteAnkiMutationStore(context, name, enforceBackgroundThread = false).use { store ->
                    prepareCommittedNote(store, request, providerNoteId)
                    store.advanceNotePhase(request.key, 0, NoteRoutingPhase.NOTE_READBACK_VERIFIED)
                    store.advanceNotePhase(request.key, 0, NoteRoutingPhase.CARDS_DISCOVERED)
                    val intent =
                        store.createRoutingIntents(
                            request.key,
                            0,
                            listOf(
                                RoutingIntentDraft(
                                    0,
                                    providerNoteId + 1,
                                    providerNoteId,
                                    0,
                                    targetDeckId = 2,
                                    preUpdateDeckId = 2,
                                ),
                            ),
                        ).single()
                    store.completeChildlessRoutingIntent(
                        intent.id,
                        ChildlessRoutingOutcome.Verified(
                            RoutingCardObservation(
                                intent.cardId,
                                intent.noteId,
                                intent.ordinal,
                                intent.targetDeckId,
                            ),
                            "legacy exact-target childless routing proof",
                        ),
                    )
                    assertEquals(NoteRoutingPhase.ROUTED, store.parent(request.key)?.routingPhase)
                    if (oldVersion == 1) {
                        legacyStoredMedia = readyStoredMedia(store, 300 + oldVersion, 1, 1)
                        val (mediaRequest, _) = requireNotNull(legacyStoredMedia)
                        assertTrue(
                            store.cleanupRun(
                                mediaRequest.key.runId,
                                acknowledgeAuthorized = true,
                                frozenDurableRequestIds = listOf(mediaRequest.key.requestId),
                            ).evidenceAccepted,
                        )
                    }
                }

                SQLiteDatabase.openDatabase(
                    context.getDatabasePath(name).path,
                    null,
                    SQLiteDatabase.OPEN_READWRITE,
                ).use { db ->
                    // v1/v2 stored direct VERIFIED routing in routing_intents. Remove only the v3
                    // normalized observation surface and retain all note/receipt/intent evidence.
                    db.execSQL("DROP TRIGGER routing_observations_delete_guard")
                    db.execSQL("DROP TRIGGER routing_observation_insert_guard")
                    db.execSQL("DROP TRIGGER routing_observation_update_forbidden")
                    db.execSQL("DROP TABLE routing_observations")
                    if (oldVersion == 1) {
                        // v1 allowed a finalized stored claim without the v2 remediation guards.
                        db.execSQL("DROP TRIGGER remediation_delete_forbidden")
                        assertEquals(
                            1,
                            db.delete(
                                "remediations",
                                "kind = ?",
                                arrayOf(RemediationKind.MEDIA_STORED_UNATTACHED.name),
                            ),
                        )
                        db.execSQL("DROP INDEX one_stored_unattached_remediation_per_claim")
                        db.execSQL("DROP TRIGGER stored_unattached_remediation_insert_guard")
                        db.execSQL("DROP TRIGGER stored_unattached_remediation_resolution_guard")
                        db.execSQL("DROP TRIGGER stored_unattached_claim_guard")
                        db.execSQL("DROP TRIGGER stored_unattached_claim_resolution")
                    }
                    db.execSQL("PRAGMA user_version = $oldVersion")
                }

                SqliteAnkiMutationStore(context, name, enforceBackgroundThread = false).use { reopened ->
                    assertEquals(JournalSchema.VERSION.toString(), pragma(reopened.writableDatabase, "user_version"))
                    val parent = reopened.parent(request.key)
                    assertEquals(providerNoteId, parent?.activeNoteId)
                    assertEquals(NoteRoutingPhase.ROUTED, parent?.routingPhase)
                    assertEquals(1L, count(reopened.writableDatabase, "active_notes"))
                    assertEquals(1L, count(reopened.writableDatabase, "note_commands"))
                    assertEquals(1L, count(reopened.writableDatabase, "note_receipts"))
                    assertEquals(1L, count(reopened.writableDatabase, "routing_intents"))
                    assertEquals(1L, count(reopened.writableDatabase, "routing_observations"))
                    reopened.writableDatabase.rawQuery(
                        """SELECT i.card_id, i.note_id, i.ordinal, i.target_deck_id,
                                  o.card_id, o.note_id, o.ordinal, o.deck_id
                           FROM routing_intents i JOIN routing_observations o ON o.intent_id = i.id""".trimIndent(),
                        null,
                    ).use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals(cursor.getLong(0), cursor.getLong(4))
                        assertEquals(cursor.getLong(1), cursor.getLong(5))
                        assertEquals(cursor.getInt(2), cursor.getInt(6))
                        assertEquals(cursor.getLong(3), cursor.getLong(7))
                        assertFalse(cursor.moveToNext())
                    }
                    legacyStoredMedia?.let { (mediaRequest, media) ->
                        val remediation =
                            reopened.openRemediations().single {
                                it.kind == RemediationKind.MEDIA_STORED_UNATTACHED
                            }
                        assertEquals(media.claim.id, remediation.claimId)
                        assertEquals(
                            MediaClaimState.STORED,
                            reopened.mediaClaim(mediaRequest.key, media.claim.assetId)?.state,
                        )
                    }
                }
            } finally {
                context.deleteDatabase(name)
            }
        }
    }

    @Test
    fun openingDatabaseRejectsMissingRequiredTrigger() {
        val name = databaseName()
        try {
            val request = verifyRequest(19, 1)
            SqliteAnkiMutationStore(context, name, enforceBackgroundThread = false).use { store ->
                store.createParent(request)
            }
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("DROP TRIGGER active_note_tag_insert_guard")
            }

            assertThrows(JournalCorruptionException::class.java) {
                SqliteAnkiMutationStore(context, name, enforceBackgroundThread = false).use { it.parent(request.key) }
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun openingDatabaseRejectsSameNameNoOpTrigger() {
        val name = databaseName()
        try {
            val request = verifyRequest(21, 1)
            SqliteAnkiMutationStore(context, name, enforceBackgroundThread = false).use { it.createParent(request) }
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("DROP TRIGGER active_note_tag_insert_guard")
                db.execSQL(
                    """CREATE TRIGGER active_note_tag_insert_guard BEFORE INSERT ON active_note_tags
                       BEGIN SELECT 1; END""".trimIndent(),
                )
            }

            assertThrows(JournalCorruptionException::class.java) {
                SqliteAnkiMutationStore(context, name, enforceBackgroundThread = false).use { it.parent(request.key) }
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun openingDatabaseRejectsSameNameWrongPartialIndex() {
        val name = databaseName()
        try {
            val request = verifyRequest(22, 1)
            SqliteAnkiMutationStore(context, name, enforceBackgroundThread = false).use { it.createParent(request) }
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("DROP INDEX one_active_media_lease")
                db.execSQL(
                    "CREATE UNIQUE INDEX one_active_media_lease ON media_leases((1)) WHERE state = 'RELEASED'",
                )
            }

            assertThrows(JournalCorruptionException::class.java) {
                SqliteAnkiMutationStore(context, name, enforceBackgroundThread = false).use { it.parent(request.key) }
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun verifyReplayIsReconstructedFromNormalizedSnapshotWithNullEmptyFidelity() =
        withStore { store ->
            val request = verifyRequest(2, 1)
            val snapshot = targetSnapshot()
            store.createParent(request)
            store.beginParent(request.key)
            store.storeTargetSnapshot(request.key, snapshot)
            store.markResultReady(request, JournalResponse.VerifySuccess(request.key, snapshot))

            assertEquals(
                ReplayResult.Ready(JournalResponse.VerifySuccess(request.key, snapshot)),
                store.replay(request, liveRun = true),
            )
            assertEquals(ReplayResult.LiveOwnerRequired, store.replay(request, liveRun = false))
            val loaded = store.targetSnapshot(request.key)
            assertNull(loaded?.model?.templates?.single()?.browserQuestionFormat)
            assertEquals("", loaded?.model?.templates?.single()?.browserAnswerFormat)

            val changed = JournalRequest.from(
                VerifyTargetRequest(
                    request.key.runId,
                    request.key.requestId,
                    "Mining",
                    "Mining Model",
                    listOf("Expression", "Meaning", "Extra"),
                ),
            )
            assertEquals(ReplayResult.DigestMismatch, store.replay(changed, liveRun = true))
        }

    @Test
    fun terminalizationRejectsReorderedTypedRequestAndPrefixOverwrite() =
        withStore { store ->
            val original = mediaRequest(3, 1, listOf(1, 2))
            store.createParent(original)
            store.beginParent(original.key)
            store.appendAlignedResult(
                original.key,
                AlignedResult.MediaFailed(
                    0,
                    original.itemIds[0],
                    JournalError(JournalErrorCode.MEDIA_STORE_FAILED, "staging failed", retryable = true),
                ),
            )
            val response =
                JournalResponse.StoreMedia(
                    original.key,
                    listOf(
                        AlignedResult.MediaFailed(
                            0,
                            original.itemIds[0],
                            JournalError(JournalErrorCode.MEDIA_STORE_FAILED, "different", retryable = true),
                        ),
                        AlignedResult.MediaNotAttempted(1, original.itemIds[1]),
                    ),
                    JournalError(JournalErrorCode.MEDIA_STORE_FAILED, "different", retryable = true),
                )
            assertThrows(JournalInvariantViolation::class.java) { store.markResultReady(original, response) }

            val reordered = mediaRequest(3, 1, listOf(2, 1))
            assertThrows(JournalInvariantViolation::class.java) {
                store.markResultReady(
                    reordered,
                    JournalResponse.StoreMedia(
                        reordered.key,
                        listOf(
                            AlignedResult.MediaFailed(
                                0,
                                reordered.itemIds[0],
                                JournalError(JournalErrorCode.MEDIA_STORE_FAILED, "failed", true),
                            ),
                            AlignedResult.MediaNotAttempted(1, reordered.itemIds[1]),
                        ),
                        JournalError(JournalErrorCode.MEDIA_STORE_FAILED, "failed", true),
                    ),
                )
            }
        }

    @Test
    fun mediaBeforeEntryRecoversAsProvenNotCommittedNeverUncertain() =
        withStore { store ->
            val prepared = prepareMedia(store, 4, 1, 1)
            val inventory = store.recoveryInventory()
            val action = AnkiMutationRecovery.plan(inventory).preparedMutation
            assertTrue(action is PreparedMutationRecovery.ProveNotCommitted)
            assertEquals(0, prepared.child.attemptCount)
            assertEquals(MediaClaimState.PENDING, prepared.claim.state)
        }

    @Test
    fun recoveryInventorySnapshotsEveryActiveDurableCapabilityAndRemediation() =
        withStore { store ->
            val prepared = prepareMedia(store, 52, 1, 1)
            val reserved =
                store.reserveMedia(
                    prepared.claim.runId,
                    listOf(reservation(ParentKey(prepared.claim.runId, requestId(2)), 2)),
                ).single()
            val remediation =
                store.addRemediation(
                    RemediationDraft(
                        parentId = prepared.child.parentId,
                        kind = RemediationKind.CAPACITY_EXHAUSTED,
                        summary = "Recovery inventory test remediation",
                        compactEvidence = "inventory snapshot remains actionable",
                    ),
                )

            val inventory = store.recoveryInventory()

            assertEquals(listOf(prepared.claim.runId), inventory.activeMediaLeaseRunIds)
            assertEquals(listOf(reserved.id), inventory.reservedMediaReservationIds)
            assertEquals(listOf(prepared.claim), inventory.unresolvedClaims)
            assertEquals(listOf(remediation), inventory.openRemediations)
        }

    @Test
    fun mediaEntryWithoutReceiptRecoversUncertainAndIsNeverReissued() =
        withStore { store ->
            val prepared = prepareMedia(store, 5, 1, 1)
            store.recordProviderEntry(prepared.child.id)
            val action = AnkiMutationRecovery.plan(store.recoveryInventory()).preparedMutation
            assertTrue(action is PreparedMutationRecovery.MarkMediaUncertain)
            assertEquals(prepared.claim.id, (action as PreparedMutationRecovery.MarkMediaUncertain).claimId)
        }

    @Test
    fun mediaClaimLookupUsesExactIdentityAndPersistsResolvedClaimsAcrossReopen() {
        val name = databaseName()
        val key = ParentKey(runId(51), requestId(1))
        lateinit var expected: MediaClaimRecord
        try {
            SqliteAnkiMutationStore(context, name, enforceBackgroundThread = false).use { store ->
                assertNull(store.mediaClaim(key, assetId(1)))
                val promoted = prepareMedia(store, 51, 1, 1)
                expected = promoted.claim

                assertEquals(expected, store.mediaClaim(key, assetId(1)))
                assertNull(store.mediaClaim(ParentKey(key.runId, requestId(2)), assetId(1)))
                assertNull(store.mediaClaim(ParentKey(runId(52), key.requestId), assetId(1)))
                assertNull(store.mediaClaim(key, assetId(2)))

                store.completeMediaFailure(
                    childId = promoted.child.id,
                    claimId = expected.id,
                    childOutcome = ChildState.PROVEN_NOT_COMMITTED,
                    claimState = MediaClaimState.CLEANED_VERIFIED,
                    result =
                        AlignedResult.MediaFailed(
                            0,
                            expected.assetId,
                            JournalError(JournalErrorCode.MEDIA_STORE_FAILED, "provider was never entered", false),
                            "provider was never entered",
                        ),
                    compactEvidence = "provider was never entered",
                )
                expected = requireNotNull(store.mediaClaim(key, assetId(1)))
                assertEquals(MediaClaimState.CLEANED_VERIFIED, expected.state)
                assertTrue(store.unresolvedClaims().isEmpty())
            }

            SqliteAnkiMutationStore(context, name, enforceBackgroundThread = false).use { reopened ->
                assertEquals(expected, reopened.mediaClaim(key, assetId(1)))
                assertEquals(MediaClaimState.CLEANED_VERIFIED, reopened.mediaClaim(key, assetId(1))?.state)
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun mediaReceiptBoundarySurvivesCrashAsOneCommittedTransaction() {
        val name = databaseName()
        try {
            val crash = CrashAt(JournalCrashPoint.AFTER_MEDIA_RECEIPT_TRANSACTION)
            val key: ParentKey
            SqliteAnkiMutationStore(
                context,
                name,
                crashHooks = crash,
                enforceBackgroundThread = false,
            ).use { store ->
                val prepared = prepareMedia(store, 6, 1, 1)
                key = ParentKey(prepared.claim.runId, prepared.claim.requestId)
                store.recordProviderEntry(prepared.child.id)
                assertThrows(SimulatedCrash::class.java) {
                    store.commitMediaReceipt(
                        prepared.child.id,
                        prepared.claim.id,
                        ProviderReceipt.Media("audio_1.mp3", "file:///audio_1.mp3"),
                        "safe provider return",
                    )
                }
            }

            SqliteAnkiMutationStore(context, name, enforceBackgroundThread = false).use { reopened ->
                assertNull(reopened.preparedChild())
                val row = reopened.alignedResults(key).single() as AlignedResult.MediaStored
                assertEquals("audio_1.mp3", row.actualFilename)
                val claim = reopened.unresolvedClaims().single()
                assertEquals(MediaClaimState.STORED, claim.state)
                assertEquals("audio_1.mp3", claim.actualFilename)
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun noteReceiptPersistsKnownIdPhaseAndChildAtomically() =
        withStore { store ->
            val request = createRequest(7, 1, 1)
            store.createParent(request)
            store.beginParent(request.key)
            store.storeTargetSnapshot(request.key, targetSnapshot())
            store.materializeActiveNote(request.key, materialization(request, 0))
            val child =
                store.prepareChild(
                    request.key,
                    MutationCommand.InsertNote(0, request.itemIds.single(), 11, "語\u001fword", "mined"),
                )
            store.recordProviderEntry(child.id)
            val parent =
                store.commitNoteReceipt(
                    child.id,
                    ProviderReceipt.Note(701, "content://com.ichi2.anki.flashcards/notes/701"),
                    "validated note URI",
                )

            assertEquals(701L, parent.activeNoteId)
            assertEquals(NoteRoutingPhase.NOTE_COMMIT_KNOWN, parent.routingPhase)
            assertEquals(ChildState.COMMIT_KNOWN, store.recoveryParents().single().let { p ->
                // The child is no longer globally PREPARED; raw state verifies the same atomic boundary.
                store.writableDatabase.rawQuery(
                    "SELECT state FROM mutation_children WHERE parent_id = ?",
                    arrayOf(p.id.toString()),
                ).use { cursor -> cursor.moveToFirst(); ChildState.valueOf(cursor.getString(0)) }
            })
        }

    @Test
    fun durableProviderReceiptsCompleteDefensivelyWithoutASecondReceiptWrite() =
        withStore { store ->
            val media = prepareMedia(store, 20, 1, 1)
            store.recordProviderEntry(media.child.id)
            store.writableDatabase.insertOrThrow(
                "media_receipts",
                null,
                ContentValues().apply {
                    put("child_id", media.child.id)
                    put("actual_filename", "audio_1.mp3")
                    put("file_uri", "file:///audio_1.mp3")
                },
            )
            val mediaParent =
                store.commitMediaReceipt(
                    media.child.id,
                    media.claim.id,
                    ProviderReceipt.Media("audio_1.mp3", "file:///audio_1.mp3"),
                    "receipt recovered before atomic promotion",
                )
            assertEquals(AlignedStatus.STORED, store.alignedResults(mediaParent.key).single().status)

            val notes = createRequest(20, 2, 1)
            store.createParent(notes)
            store.beginParent(notes.key)
            store.storeTargetSnapshot(notes.key, targetSnapshot())
            store.materializeActiveNote(notes.key, materialization(notes, 0))
            val noteChild =
                store.prepareChild(
                    notes.key,
                    MutationCommand.InsertNote(0, notes.itemIds.single(), 11, "語\u001fword", "mined"),
                )
            store.recordProviderEntry(noteChild.id)
            store.writableDatabase.insertOrThrow(
                "note_receipts",
                null,
                ContentValues().apply {
                    put("child_id", noteChild.id)
                    put("note_id", 2_001)
                    put("content_uri", "content://com.ichi2.anki.flashcards/notes/2001")
                },
            )
            val noteParent =
                store.commitNoteReceipt(
                    noteChild.id,
                    ProviderReceipt.Note(2_001, "content://com.ichi2.anki.flashcards/notes/2001"),
                    "receipt recovered before atomic promotion",
                )
            assertEquals(2_001L, noteParent.activeNoteId)
            assertEquals(NoteRoutingPhase.NOTE_COMMIT_KNOWN, noteParent.routingPhase)
        }

    @Test
    fun activeNoteAndNoteChildAreRejectedUntilTargetDeckIsExactlyVerified() =
        withStore { store ->
            val request = createRequest(17, 1, 1)
            store.createParent(request)
            store.beginParent(request.key)
            val note = materialization(request, 0)
            val command = MutationCommand.InsertNote(0, request.itemIds.single(), 11, "語\u001fword", "mined")

            assertThrows(JournalInvariantViolation::class.java) {
                store.materializeActiveNote(request.key, note)
            }
            assertThrows(JournalInvariantViolation::class.java) {
                store.prepareChild(request.key, command)
            }

            store.storeTargetExpectation(request.key, targetSnapshot().expectation)
            assertThrows(JournalInvariantViolation::class.java) {
                store.materializeActiveNote(request.key, note)
            }
            assertThrows(JournalInvariantViolation::class.java) {
                store.prepareChild(request.key, command)
            }

            store.storeTargetSnapshot(request.key, targetSnapshot())
            assertEquals(NoteRoutingPhase.NOTE_PENDING, store.materializeActiveNote(request.key, note).routingPhase)
            assertEquals(ChildState.PREPARED, store.prepareChild(request.key, command).state)
        }

    @Test
    fun deckReceiptStaysPreparedAndRecoveryOnlyReconciles() =
        withStore { store ->
            val request = verifyRequest(8, 1)
            store.createParent(request)
            store.beginParent(request.key)
            store.storeTargetExpectation(request.key, targetSnapshot().expectation)
            val child = store.prepareChild(request.key, MutationCommand.CreateDeck("Mining"))
            store.recordProviderEntry(child.id)
            val returned =
                store.recordDeckReceipt(
                    child.id,
                    ProviderReceipt.Deck(88, "content://com.ichi2.anki.flashcards/decks/88"),
                )
            assertEquals(ChildState.PREPARED, returned.state)
            assertTrue(returned.receipt is ProviderReceipt.Deck)
            val action = AnkiMutationRecovery.plan(store.recoveryInventory()).preparedMutation
            assertTrue(action is PreparedMutationRecovery.ReconcileDeck)
            action as PreparedMutationRecovery.ReconcileDeck
            assertNotNull(action.returnedReceipt)
            assertEquals(targetSnapshot().expectation, action.expectedTarget)
        }

    @Test
    fun cardReceiptIsDurableBeforeMandatoryRequeryAndBlocksRecoveryReissue() =
        withStore { store ->
            val request = createRequest(9, 1, 1)
            store.createParent(request)
            store.beginParent(request.key)
            store.storeTargetSnapshot(request.key, targetSnapshot())
            store.materializeActiveNote(request.key, materialization(request, 0))
            val noteChild = store.prepareChild(
                request.key,
                MutationCommand.InsertNote(0, request.itemIds.single(), 11, "語\u001fword", "mined"),
            )
            store.recordProviderEntry(noteChild.id)
            store.commitNoteReceipt(
                noteChild.id,
                ProviderReceipt.Note(901, "content://com.ichi2.anki.flashcards/notes/901"),
                "note returned",
            )
            store.advanceNotePhase(request.key, 0, NoteRoutingPhase.NOTE_READBACK_VERIFIED)
            store.advanceNotePhase(request.key, 0, NoteRoutingPhase.CARDS_DISCOVERED)
            assertThrows(JournalInvariantViolation::class.java) {
                store.createRoutingIntents(
                    request.key,
                    0,
                    listOf(RoutingIntentDraft(0, 902, 901, 0, 999, 1)),
                )
            }
            assertThrows(JournalInvariantViolation::class.java) {
                store.createRoutingIntents(
                    request.key,
                    0,
                    listOf(RoutingIntentDraft(0, 902, 901, 1, 2, 1)),
                )
            }
            val intent =
                store.createRoutingIntents(
                    request.key,
                    0,
                    listOf(RoutingIntentDraft(0, 902, 901, 0, 2, 1)),
                ).single()
            val child = store.prepareRoutingChild(intent.id)
            store.recordProviderEntry(child.id)
            store.recordCardReceipt(child.id)

            val action = AnkiMutationRecovery.plan(store.recoveryInventory()).preparedMutation
                as PreparedMutationRecovery.InspectCardRouting
            assertTrue(action.hasAffectedCountReceipt)
            assertEquals(
                CardRecoveryDisposition.COMMITTED_FAILED_EXTERNAL_DRIFT,
                AnkiMutationRecovery.decideCardRecovery(
                    CardRecoveryObservation.PRE_UPDATE_DECK,
                    action.hasAffectedCountReceipt,
                    action.child.attemptCount,
                ),
            )
        }

    @Test
    fun alreadyTargetedCardIntentVerifiesWithoutCreatingMutationChild() =
        withStore { store ->
            val request = createRequest(18, 1, 1)
            store.createParent(request)
            store.beginParent(request.key)
            store.storeTargetSnapshot(request.key, targetSnapshot())
            store.materializeActiveNote(request.key, materialization(request, 0))
            val noteChild = store.prepareChild(
                request.key,
                MutationCommand.InsertNote(0, request.itemIds.single(), 11, "語\u001fword", "mined"),
            )
            store.recordProviderEntry(noteChild.id)
            store.commitNoteReceipt(
                noteChild.id,
                ProviderReceipt.Note(1_801, "content://com.ichi2.anki.flashcards/notes/1801"),
                "note returned",
            )
            store.advanceNotePhase(request.key, 0, NoteRoutingPhase.NOTE_READBACK_VERIFIED)
            store.advanceNotePhase(request.key, 0, NoteRoutingPhase.CARDS_DISCOVERED)
            val intent =
                store.createRoutingIntents(
                    request.key,
                    0,
                    listOf(RoutingIntentDraft(0, 1_802, 1_801, 0, 2, 2)),
                ).single()

            val verified =
                store.completeChildlessRoutingIntent(
                    intent.id,
                    ChildlessRoutingOutcome.Verified(
                        RoutingCardObservation(intent.cardId, intent.noteId, intent.ordinal, intent.targetDeckId),
                        "exact card identity already in target deck",
                    ),
                )

            assertEquals(RoutingIntentState.VERIFIED, verified.state)
            assertNull(verified.childId)
            assertNull(store.preparedChild())
            assertEquals(NoteRoutingPhase.ROUTED, store.parent(request.key)?.routingPhase)
            store.advanceNotePhase(request.key, 0, NoteRoutingPhase.POSTCHECK_VERIFIED)
            val completed =
                store.completeVerifiedNote(
                    request.key,
                    0,
                    1_801,
                    "exact postcondition verified",
                )
            assertNull(completed.activeRequestIndex)
            assertNull(completed.activeNoteId)
            assertNull(completed.routingPhase)
            assertNull(store.activeNote(request.key))
        }

    @Test
    fun consecutiveCreatedNotesUseOnlyTheirOwnRoutingIntents() =
        withStore { store ->
            val request = createRequest(40, 1, 2)
            store.createParent(request)
            store.beginParent(request.key)
            store.storeTargetSnapshot(request.key, targetSnapshot())

            val noteIds = listOf(4_001L, 4_002L)
            noteIds.forEachIndexed { requestIndex, noteId ->
                val currentIntents = completeAlreadyTargetedNote(store, request, requestIndex, noteId)
                assertEquals(listOf(noteId + 100), currentIntents.map { it.cardId })
            }

            assertEquals(
                noteIds,
                store.alignedResults(request.key).map { (it as AlignedResult.NoteCreated).committedId },
            )
            assertNull(store.activeNote(request.key))
        }

    @Test
    fun ownerlessFinalizationPreservesKnownIdsAndFilenamesInCompactAuditAcrossReopen() {
        val name = databaseName()
        val request = createRequest(41, 1, 2)
        val mediaKey = ParentKey(runId(41), requestId(2))
        try {
            SqliteAnkiMutationStore(context, name, enforceBackgroundThread = false).use { store ->
                store.createParent(request)
                store.beginParent(request.key)
                store.storeTargetSnapshot(request.key, targetSnapshot())
                completeAlreadyTargetedNote(store, request, 0, 4_101)
                store.materializeActiveNote(request.key, materialization(request, 1))
                val media = prepareMedia(store, 41, 2, 1)
                assertEquals(mediaKey, ParentKey(media.claim.runId, media.claim.requestId))
                store.recordProviderEntry(media.child.id)
                store.commitMediaReceipt(
                    media.child.id,
                    media.claim.id,
                    ProviderReceipt.Media("audio_1.mp3", "file:///audio_1.mp3"),
                    "validated media filename",
                )

                assertEquals(2, store.abandonOwnerless(emptySet()).count { it.state == ParentState.ABANDONED })
                assertEquals(0L, count(store.writableDatabase, "aligned_results"))
                assertEquals(0L, count(store.writableDatabase, "parent_request_items"))
            }

            SqliteAnkiMutationStore(context, name, enforceBackgroundThread = false).use { reopened ->
                val parent = requireNotNull(reopened.parent(request.key))
                assertEquals(ParentState.ABANDONED, parent.state)
                assertEquals(request.digest.sha256, parent.requestSha256)
                assertEquals(3L, count(reopened.writableDatabase, "terminal_result_audit"))
                reopened.writableDatabase.rawQuery(
                    """SELECT item_id, status_kind, committed_id, actual_filename, error_code, compact_evidence
                       FROM terminal_result_audit WHERE parent_id = ? AND request_index = 0""".trimIndent(),
                    arrayOf(parent.id.toString()),
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(request.itemIds[0], cursor.getString(0))
                    assertEquals(AlignedStatus.CREATED.name, cursor.getString(1))
                    assertEquals(4_101L, cursor.getLong(2))
                    assertTrue(cursor.isNull(3))
                    assertTrue(cursor.isNull(4))
                    assertEquals("exact note 4101 postcondition", cursor.getString(5))
                    assertFalse(cursor.moveToNext())
                }
                reopened.writableDatabase.rawQuery(
                    """SELECT item_id, status_kind, error_code FROM terminal_result_audit
                       WHERE parent_id = ? AND request_index = 1""".trimIndent(),
                    arrayOf(parent.id.toString()),
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(request.itemIds[1], cursor.getString(0))
                    assertEquals(AlignedStatus.FAILED.name, cursor.getString(1))
                    assertEquals(JournalErrorCode.CANCELLED.name, cursor.getString(2))
                    assertFalse(cursor.moveToNext())
                }
                val mediaParent = requireNotNull(reopened.parent(mediaKey))
                reopened.writableDatabase.rawQuery(
                    """SELECT item_id, status_kind, actual_filename, compact_evidence
                       FROM terminal_result_audit WHERE parent_id = ? AND request_index = 0""".trimIndent(),
                    arrayOf(mediaParent.id.toString()),
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(assetId(1), cursor.getString(0))
                    assertEquals(AlignedStatus.STORED.name, cursor.getString(1))
                    assertEquals("audio_1.mp3", cursor.getString(2))
                    assertEquals("validated media filename", cursor.getString(3))
                    assertFalse(cursor.moveToNext())
                }
                assertThrows(SQLiteConstraintException::class.java) {
                    reopened.writableDatabase.update(
                        "terminal_result_audit",
                        ContentValues().apply { put("compact_evidence", "rewritten terminal audit") },
                        "parent_id = ? AND request_index = 0",
                        arrayOf(parent.id.toString()),
                    )
                }
                assertThrows(SQLiteConstraintException::class.java) {
                    reopened.writableDatabase.delete(
                        "terminal_result_audit",
                        "parent_id = ? AND request_index = 0",
                        arrayOf(parent.id.toString()),
                    )
                }
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun verifiedNoteCompletionAtomicallyCreatesResultAndResolvesExactMediaBinding() =
        withStore { store ->
            val promoted = prepareMedia(store, 23, 1, 1)
            store.recordProviderEntry(promoted.child.id)
            store.commitMediaReceipt(
                promoted.child.id,
                promoted.claim.id,
                ProviderReceipt.Media("audio_1.mp3", "file:///audio_1.mp3"),
                "exact provider media receipt",
            )
            val request = createRequest(23, 2, 1, MediaBinding(promoted.claim.assetId, "audio_1.mp3"))
            prepareCommittedNote(
                store,
                request,
                2_301,
                listOf(DurableMediaBinding(promoted.claim.assetId, "audio_1.mp3", promoted.claim.id)),
            )
            advanceToPostcheck(store, request, 2_301)

            store.completeVerifiedNote(request.key, 0, 2_301, "exact note and card postcheck")

            assertTrue(store.alignedResults(request.key).single() is AlignedResult.NoteCreated)
            assertEquals(MediaClaimState.ATTACHED_VERIFIED.name, claimState(store.writableDatabase, promoted.claim.id))
            assertEquals(
                MediaClaimState.ATTACHED_VERIFIED,
                store.mediaClaim(ParentKey(promoted.claim.runId, promoted.claim.requestId), promoted.claim.assetId)?.state,
            )
            assertNull(store.activeNote(request.key))
        }

    @Test
    fun verifiedNoteCompletionCrashHooksReopenAtEitherSideOfOneAtomicBoundary() {
        listOf(
            JournalCrashPoint.BEFORE_VERIFIED_NOTE_TRANSACTION to false,
            JournalCrashPoint.AFTER_VERIFIED_NOTE_TRANSACTION to true,
        ).forEachIndexed { offset, (point, committed) ->
            val name = databaseName()
            lateinit var request: JournalRequest
            var claimId = 0L
            try {
                SqliteAnkiMutationStore(
                    context,
                    name,
                    crashHooks = CrashAt(point),
                    enforceBackgroundThread = false,
                ).use { store ->
                    val promoted = prepareMedia(store, 24 + offset, 1, 1)
                    claimId = promoted.claim.id
                    store.recordProviderEntry(promoted.child.id)
                    store.commitMediaReceipt(
                        promoted.child.id,
                        promoted.claim.id,
                        ProviderReceipt.Media("audio_1.mp3", "file:///audio_1.mp3"),
                        "exact provider media receipt",
                    )
                    request =
                        createRequest(
                            24 + offset,
                            2,
                            1,
                            MediaBinding(promoted.claim.assetId, "audio_1.mp3"),
                        )
                    prepareCommittedNote(
                        store,
                        request,
                        2_401L + offset,
                        listOf(DurableMediaBinding(promoted.claim.assetId, "audio_1.mp3", promoted.claim.id)),
                    )
                    advanceToPostcheck(store, request, 2_401L + offset)
                    assertThrows(SimulatedCrash::class.java) {
                        store.completeVerifiedNote(request.key, 0, 2_401L + offset, "atomic verified note")
                    }
                }

                SqliteAnkiMutationStore(context, name, enforceBackgroundThread = false).use { reopened ->
                    assertEquals(committed, reopened.alignedResults(request.key).singleOrNull() is AlignedResult.NoteCreated)
                    assertEquals(
                        if (committed) MediaClaimState.ATTACHED_VERIFIED.name else MediaClaimState.STORED.name,
                        claimState(reopened.writableDatabase, claimId),
                    )
                    assertEquals(!committed, reopened.activeNote(request.key) != null)
                }
            } finally {
                context.deleteDatabase(name)
            }
        }
    }

    @Test
    fun ownerlessKnownNotePreservesItsIdRemediationAndCompactReceiptAuditAcrossReopen() {
        val name = databaseName()
        val request = createRequest(26, 1, 1)
        try {
            SqliteAnkiMutationStore(context, name, enforceBackgroundThread = false).use { store ->
                prepareCommittedNote(store, request, 2_601)
                assertEquals(ParentState.ABANDONED, store.abandonOwnerless(emptySet()).single().state)
            }

            SqliteAnkiMutationStore(context, name, enforceBackgroundThread = false).use { reopened ->
                assertEquals(ParentState.ABANDONED, reopened.parent(request.key)?.state)
                val remediation = reopened.openRemediations().single()
                assertEquals(RemediationKind.NOTE_COMMITTED_FAILED, remediation.kind)
                assertTrue(requireNotNull(remediation.compactEvidence).contains("noteId=2601"))
                assertEquals(1L, auditCount(reopened.writableDatabase, "terminal_parent_audit", "child_count"))
                assertEquals(
                    1L,
                    auditCount(
                        reopened.writableDatabase,
                        "terminal_receipt_audit",
                        "receipt_count",
                        "operation_kind = 'NOTE_INSERT'",
                    ),
                )
                assertEquals(
                    1L,
                    auditCount(
                        reopened.writableDatabase,
                        "terminal_outcome_audit",
                        "row_count",
                        "status_kind = 'COMMITTED_FAILED'",
                    ),
                )
                assertEquals(0L, count(reopened.writableDatabase, "aligned_results"))
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun ownerlessEnteredNoteAndMediaRemainUncertainWithVisibleRemediation() =
        withStore { store ->
            val note = createRequest(27, 1, 1)
            store.createParent(note)
            store.beginParent(note.key)
            store.storeTargetSnapshot(note.key, targetSnapshot())
            store.materializeActiveNote(note.key, materialization(note, 0))
            val noteChild =
                store.prepareChild(
                    note.key,
                    MutationCommand.InsertNote(0, note.itemIds.single(), 11, "語\u001fword", "mined"),
                )
            store.recordProviderEntry(noteChild.id)
            store.completeChild(noteChild.id, ChildState.COMMIT_UNCERTAIN, "provider entered without note receipt")
            assertEquals(RemediationKind.NOTE_COMMIT_UNCERTAIN, store.openRemediations().single().kind)

            val media = prepareMedia(store, 28, 1, 1)
            store.recordProviderEntry(media.child.id)
            store.completeMediaFailure(
                media.child.id,
                media.claim.id,
                ChildState.COMMIT_UNCERTAIN,
                MediaClaimState.COMMIT_UNCERTAIN,
                AlignedResult.MediaUncertain(0, media.claim.assetId, "provider entered without media receipt"),
                "provider entered without media receipt",
            )
            assertEquals(
                setOf(RemediationKind.NOTE_COMMIT_UNCERTAIN, RemediationKind.MEDIA_COMMIT_UNCERTAIN),
                store.openRemediations().map { it.kind }.toSet(),
            )

            val abandoned = store.abandonOwnerless(emptySet())
            assertEquals(2, abandoned.size)
            assertTrue(abandoned.all { it.state == ParentState.ABANDONED })
            assertEquals(
                setOf(RemediationKind.NOTE_COMMIT_UNCERTAIN, RemediationKind.MEDIA_COMMIT_UNCERTAIN),
                store.openRemediations().map { it.kind }.toSet(),
            )
            assertEquals(MediaClaimState.COMMIT_UNCERTAIN.name, claimState(store.writableDatabase, media.claim.id))
            assertEquals(MediaLeaseState.RELEASED, store.mediaLease(media.claim.runId)?.state)
            assertEquals(2L, count(store.writableDatabase, "terminal_parent_audit"))
        }

    @Test
    fun enteredDeckUncertaintyAtomicallyCreatesRemediationBeforeTerminalResult() =
        withStore { store ->
            val request = verifyRequest(39, 1)
            store.createParent(request)
            store.beginParent(request.key)
            store.storeTargetExpectation(request.key, targetSnapshot().expectation)
            val child = store.prepareChild(request.key, MutationCommand.CreateDeck("Mining"))
            store.recordProviderEntry(child.id)
            assertThrows(JournalInvariantViolation::class.java) {
                store.completeChild(child.id, ChildState.POSTCONDITION_FAILED, "non-exact deck observation")
            }
            assertThrows(SQLiteConstraintException::class.java) {
                store.writableDatabase.update(
                    "mutation_children",
                    ContentValues().apply {
                        put("state", ChildState.POSTCONDITION_FAILED.name)
                        put("terminal_evidence", "raw non-exact deck observation")
                        put("updated_at_ms", Long.MAX_VALUE - 1)
                    },
                    "id = ?",
                    arrayOf(child.id.toString()),
                )
            }
            store.completeChild(child.id, ChildState.COMMIT_UNCERTAIN, "deck provider entered without proof")
            assertEquals(RemediationKind.DECK_COMMIT_UNCERTAIN, store.openRemediations().single().kind)
            val stableError =
                JournalError(
                    JournalErrorCode.TARGET_INVALID,
                    "non-exact deck reconciliation is not deterministic failure proof",
                    retryable = false,
                )
            assertThrows(JournalInvariantViolation::class.java) {
                store.markResultReady(request, JournalResponse.VerifyError(request.key, stableError))
            }
            val error =
                JournalError(
                    JournalErrorCode.POST_COMMIT_UNCERTAIN,
                    "deck provider commit could not be observed",
                    retryable = false,
                )
            store.markResultReady(request, JournalResponse.VerifyError(request.key, error))
            assertEquals(ParentState.RESULT_READY, store.parent(request.key)?.state)
        }

    @Test
    fun enteredDeckVerifiesOnlyAfterExactTargetSnapshotIsDurable() =
        withStore { store ->
            val request = verifyRequest(42, 1)
            val target = targetSnapshot()
            store.createParent(request)
            store.beginParent(request.key)
            store.storeTargetExpectation(request.key, target.expectation)
            val child = store.prepareChild(request.key, MutationCommand.CreateDeck("Mining"))
            store.recordProviderEntry(child.id)

            assertThrows(JournalInvariantViolation::class.java) {
                store.completeChild(child.id, ChildState.POSTCONDITION_VERIFIED, "target observed but not durable")
            }
            store.storeTargetSnapshot(request.key, target)
            assertEquals(
                ChildState.POSTCONDITION_VERIFIED,
                store.completeChild(child.id, ChildState.POSTCONDITION_VERIFIED, "exact target snapshot reconciled").state,
            )
            store.markResultReady(request, JournalResponse.VerifySuccess(request.key, target))
            assertEquals(ParentState.RESULT_READY, store.parent(request.key)?.state)
        }

    @Test
    fun verifiedDeckTransactionNeverPersistsOnlyOneOfTargetAndChildOutcome() {
        listOf(
            JournalCrashPoint.BEFORE_DECK_VERIFICATION_TRANSACTION to false,
            JournalCrashPoint.AFTER_DECK_VERIFICATION_TRANSACTION to true,
        ).forEachIndexed { index, (point, committed) ->
            val name = databaseName()
            val request = verifyRequest(142 + index, 1)
            val target = targetSnapshot()
            val childId: Long
            try {
                SqliteAnkiMutationStore(context, name, enforceBackgroundThread = false).use { store ->
                    store.createParent(request)
                    store.beginParent(request.key)
                    store.storeTargetExpectation(request.key, target.expectation)
                    childId = store.prepareChild(request.key, MutationCommand.CreateDeck("Mining")).id
                    store.recordProviderEntry(childId)
                }
                SqliteAnkiMutationStore(
                    context,
                    name,
                    crashHooks = CrashAt(point),
                    enforceBackgroundThread = false,
                ).use { store ->
                    assertThrows(SimulatedCrash::class.java) {
                        store.completeVerifiedDeck(childId, target, "exact model and deck requery")
                    }
                }

                SqliteAnkiMutationStore(context, name, enforceBackgroundThread = false).use { reopened ->
                    assertEquals(if (committed) target else null, reopened.targetSnapshot(request.key))
                    assertEquals(
                        if (committed) ChildState.POSTCONDITION_VERIFIED else ChildState.PREPARED,
                        childState(reopened.writableDatabase, childId),
                    )
                }
            } finally {
                context.deleteDatabase(name)
            }
        }
    }

    @Test
    fun uncertainDeckTransactionNeverPersistsChildWithoutRemediation() {
        listOf(
            JournalCrashPoint.BEFORE_DECK_UNCERTAINTY_TRANSACTION to false,
            JournalCrashPoint.AFTER_DECK_UNCERTAINTY_TRANSACTION to true,
        ).forEachIndexed { index, (point, committed) ->
            val name = databaseName()
            val request = verifyRequest(144 + index, 1)
            val childId: Long
            try {
                SqliteAnkiMutationStore(context, name, enforceBackgroundThread = false).use { store ->
                    store.createParent(request)
                    store.beginParent(request.key)
                    store.storeTargetExpectation(request.key, targetSnapshot().expectation)
                    childId = store.prepareChild(request.key, MutationCommand.CreateDeck("Mining")).id
                    store.recordProviderEntry(childId)
                }
                SqliteAnkiMutationStore(
                    context,
                    name,
                    crashHooks = CrashAt(point),
                    enforceBackgroundThread = false,
                ).use { store ->
                    assertThrows(SimulatedCrash::class.java) {
                        store.completeUncertainDeck(childId, "entered create could not be reconciled")
                    }
                }

                SqliteAnkiMutationStore(context, name, enforceBackgroundThread = false).use { reopened ->
                    assertNull(reopened.targetSnapshot(request.key))
                    assertEquals(
                        if (committed) ChildState.COMMIT_UNCERTAIN else ChildState.PREPARED,
                        childState(reopened.writableDatabase, childId),
                    )
                    assertEquals(
                        if (committed) listOf(RemediationKind.DECK_COMMIT_UNCERTAIN) else emptyList(),
                        reopened.openRemediations().map { it.kind },
                    )
                }
            } finally {
                context.deleteDatabase(name)
            }
        }
    }

    @Test
    fun uncertainCardRoutingRequiresPostCommitUncertainRowAndParentError() =
        withStore { store ->
            val request = createRequest(43, 1, 1)
            val noteId = 4_301L
            store.createParent(request)
            store.beginParent(request.key)
            store.storeTargetSnapshot(request.key, targetSnapshot())
            store.materializeActiveNote(request.key, materialization(request, 0))
            val noteChild =
                store.prepareChild(
                    request.key,
                    MutationCommand.InsertNote(0, request.itemIds.single(), 11, "語\u001fword", "mined"),
                )
            store.recordProviderEntry(noteChild.id)
            store.commitNoteReceipt(
                noteChild.id,
                ProviderReceipt.Note(noteId, "content://com.ichi2.anki.flashcards/notes/$noteId"),
                "validated note receipt",
            )
            store.advanceNotePhase(request.key, 0, NoteRoutingPhase.NOTE_READBACK_VERIFIED)
            store.advanceNotePhase(request.key, 0, NoteRoutingPhase.CARDS_DISCOVERED)
            val intent =
                store.createRoutingIntents(
                    request.key,
                    0,
                    listOf(RoutingIntentDraft(0, 4_302, noteId, 0, 2, 1)),
                ).single()
            val cardChild = store.prepareRoutingChild(intent.id)
            store.recordProviderEntry(cardChild.id)
            store.completeRoutingChild(
                cardChild.id,
                ChildState.COMMIT_UNCERTAIN,
                RoutingIntentState.COMMIT_UNCERTAIN,
                "card postcondition could not be observed",
            )

            val stableError = JournalError(JournalErrorCode.TARGET_INVALID, "stable error cannot hide uncertainty", false)
            val stableResult =
                AlignedResult.NoteCommittedFailed(
                    0,
                    request.itemIds.single(),
                    noteId,
                    stableError,
                    "invalid stable classification",
                )
            assertThrows(JournalInvariantViolation::class.java) { store.appendAlignedResult(request.key, stableResult) }
            assertThrows(SQLiteConstraintException::class.java) {
                store.writableDatabase.insertOrThrow(
                    "aligned_results",
                    null,
                    ContentValues().apply {
                        put("parent_id", requireNotNull(store.parent(request.key)).id)
                        put("request_index", 0)
                        put("item_id", request.itemIds.single())
                        put("status_kind", AlignedStatus.COMMITTED_FAILED.name)
                        put("committed_id", noteId)
                        put("error_code", stableError.code.name)
                        put("error_message", stableError.message)
                        put("error_retryable", 0)
                        put("compact_evidence", "invalid raw stable classification")
                    },
                )
            }

            val uncertainty =
                JournalError(
                    JournalErrorCode.POST_COMMIT_UNCERTAIN,
                    "card postcondition could not be observed",
                    retryable = false,
                )
            val result =
                AlignedResult.NoteCommittedFailed(
                    0,
                    request.itemIds.single(),
                    noteId,
                    uncertainty,
                    "preserved uncertain card routing",
                )
            store.appendAlignedResult(request.key, result)
            store.markResultReady(request, JournalResponse.CreateNotes(request.key, listOf(result), uncertainty))
            assertEquals(ParentState.RESULT_READY, store.parent(request.key)?.state)
        }

    @Test
    fun cleanupNeverAcknowledgesUnfinishedOwnerlessParentAndAuditsExactUntouchedSuffix() =
        withStore { store ->
            val request = createRequest(29, 1, 2)
            store.createParent(request)
            store.beginParent(request.key)

            val cleanup = store.cleanupRun(request.key.runId, acknowledgeAuthorized = true, frozenDurableRequestIds = emptyList())

            assertTrue(cleanup.evidenceAccepted)
            assertTrue(cleanup.acknowledgedRequestIds.isEmpty())
            assertEquals(listOf(request.key.requestId), cleanup.abandonedRequestIds)
            assertEquals(ParentState.ABANDONED, store.parent(request.key)?.state)
            assertEquals(
                1L,
                auditCount(store.writableDatabase, "terminal_outcome_audit", "row_count", "status_kind = 'FAILED'"),
            )
            assertEquals(
                1L,
                auditCount(
                    store.writableDatabase,
                    "terminal_outcome_audit",
                    "row_count",
                    "status_kind = 'NOT_ATTEMPTED'",
                ),
            )
        }

    @Test
    fun promotedPreEntrySystemStopPersistsNotAttemptedProof() =
        withStore { store ->
            val request = mediaRequest(130, 1, listOf(7))
            val media = prepareMedia(store, 130, 1, 7)
            store.completeMediaFailure(
                media.child.id,
                media.claim.id,
                ChildState.PROVEN_NOT_COMMITTED,
                MediaClaimState.CLEANED_VERIFIED,
                AlignedResult.MediaNotAttempted(0, media.claim.assetId),
                "provider entry was denied before the raw media call",
            )
            val error =
                JournalError(
                    JournalErrorCode.CANCELLED,
                    "The run was cancelled before provider entry",
                    retryable = false,
                )
            val response = JournalResponse.StoreMedia(request.key, store.alignedResults(request.key), error)
            store.markResultReady(request, response)

            assertEquals(ChildState.PROVEN_NOT_COMMITTED, childState(store.writableDatabase, media.child.id))
            assertEquals(MediaClaimState.CLEANED_VERIFIED, store.mediaClaim(request.key, media.claim.assetId)?.state)
            assertEquals(ReplayResult.Ready(response), store.replay(request, liveRun = true))
        }

    @Test
    fun rawSqlRejectsNotAttemptedMediaWithUnresolvedClaimAndDuplicateChildIdentity() =
        withStore { store ->
            val media = prepareMedia(store, 131, 1, 8)
            val db = store.writableDatabase
            assertEquals(
                1,
                db.update(
                    "mutation_children",
                    ContentValues().apply {
                        put("state", ChildState.PROVEN_NOT_COMMITTED.name)
                        put("terminal_evidence", "raw pre-entry proof")
                        put("updated_at_ms", media.child.updatedAtMs + 1)
                    },
                    "id = ?",
                    arrayOf(media.child.id.toString()),
                ),
            )

            assertThrows(SQLiteConstraintException::class.java) {
                db.insertOrThrow(
                    "aligned_results",
                    null,
                    ContentValues().apply {
                        put("parent_id", media.child.parentId)
                        put("request_index", 0)
                        put("item_id", media.claim.assetId)
                        put("status_kind", AlignedStatus.NOT_ATTEMPTED.name)
                    },
                )
            }

            assertThrows(SQLiteConstraintException::class.java) {
                db.insertOrThrow(
                    "mutation_children",
                    null,
                    ContentValues().apply {
                        put("parent_id", media.child.parentId)
                        put("sequence_number", 1)
                        put("operation_kind", ChildOperation.MEDIA_INSERT.name)
                        put("identity_key", media.claim.assetId)
                        put("request_index", 0)
                        put("digest_version", media.child.digestVersion)
                        put("request_sha256", media.child.requestSha256)
                        put("media_claim_id", media.claim.id)
                        put("state", ChildState.PROVEN_NOT_COMMITTED.name)
                        put("terminal_evidence", "duplicate raw child")
                        put("created_at_ms", media.child.createdAtMs + 1)
                        put("updated_at_ms", media.child.updatedAtMs + 2)
                    },
                )
            }
        }

    @Test
    fun oneGlobalPreparedChildBlocksEveryOtherParentUntilRecoveryCompletes() =
        withStore { store ->
            val media = prepareMedia(store, 30, 1, 1)
            val verify = verifyRequest(31, 1)
            store.createParent(verify)
            store.beginParent(verify.key)
            store.storeTargetExpectation(verify.key, targetSnapshot().expectation)

            assertThrows(SQLiteConstraintException::class.java) {
                store.prepareChild(verify.key, MutationCommand.CreateDeck("Mining"))
            }
            assertEquals(media.child.id, store.preparedChild()?.id)

            store.completeMediaFailure(
                media.child.id,
                media.claim.id,
                ChildState.PROVEN_NOT_COMMITTED,
                MediaClaimState.CLEANED_VERIFIED,
                AlignedResult.MediaFailed(
                    0,
                    media.claim.assetId,
                    JournalError(JournalErrorCode.MEDIA_STORE_FAILED, "provider was never entered", false),
                    "provider was never entered",
                ),
                "provider was never entered",
            )
            assertEquals(ChildState.PREPARED, store.prepareChild(verify.key, MutationCommand.CreateDeck("Mining")).state)
        }

    @Test
    fun partialRoutingCannotBypassRoutedOrPostcheckPhases() =
        withStore { store ->
            val request = createRequest(32, 1, 1)
            val target = targetSnapshot(cardCount = 2)
            store.createParent(request)
            store.beginParent(request.key)
            store.storeTargetSnapshot(request.key, target)
            store.materializeActiveNote(request.key, materialization(request, 0))
            val noteChild =
                store.prepareChild(
                    request.key,
                    MutationCommand.InsertNote(0, request.itemIds.single(), 11, "語\u001fword", "mined"),
                )
            store.recordProviderEntry(noteChild.id)
            store.commitNoteReceipt(
                noteChild.id,
                ProviderReceipt.Note(3_201, "content://com.ichi2.anki.flashcards/notes/3201"),
                "validated note receipt",
            )
            store.advanceNotePhase(request.key, 0, NoteRoutingPhase.NOTE_READBACK_VERIFIED)
            store.advanceNotePhase(request.key, 0, NoteRoutingPhase.CARDS_DISCOVERED)
            assertThrows(SQLiteConstraintException::class.java) {
                store.writableDatabase.update(
                    "parents",
                    ContentValues().apply { put("routing_phase", NoteRoutingPhase.ROUTING.name) },
                    "run_id = ? AND request_id = ?",
                    arrayOf(request.key.runId, request.key.requestId),
                )
            }
            val intents =
                store.createRoutingIntents(
                    request.key,
                    0,
                    listOf(
                        RoutingIntentDraft(0, 3_202, 3_201, 0, 2, 2),
                        RoutingIntentDraft(0, 3_203, 3_201, 1, 2, 2),
                    ),
                )
            store.completeChildlessRoutingIntent(
                intents[0].id,
                ChildlessRoutingOutcome.Verified(
                    RoutingCardObservation(
                        intents[0].cardId,
                        intents[0].noteId,
                        intents[0].ordinal,
                        intents[0].targetDeckId,
                    ),
                    "first card exactly targeted",
                ),
            )
            assertThrows(SQLiteConstraintException::class.java) {
                store.writableDatabase.update(
                    "routing_intents",
                    ContentValues().apply {
                        put("terminal_evidence", "rewritten routing evidence")
                        put("updated_at_ms", Long.MAX_VALUE - 1)
                    },
                    "id = ?",
                    arrayOf(intents[0].id.toString()),
                )
            }
            assertEquals(NoteRoutingPhase.ROUTING, store.parent(request.key)?.routingPhase)
            assertThrows(JournalInvariantViolation::class.java) {
                store.advanceNotePhase(request.key, 0, NoteRoutingPhase.ROUTED)
            }
            assertThrows(SQLiteConstraintException::class.java) {
                store.writableDatabase.update(
                    "parents",
                    ContentValues().apply { put("routing_phase", NoteRoutingPhase.ROUTED.name) },
                    "run_id = ? AND request_id = ?",
                    arrayOf(request.key.runId, request.key.requestId),
                )
            }
            store.completeChildlessRoutingIntent(
                intents[1].id,
                ChildlessRoutingOutcome.Verified(
                    RoutingCardObservation(
                        intents[1].cardId,
                        intents[1].noteId,
                        intents[1].ordinal,
                        intents[1].targetDeckId,
                    ),
                    "second card exactly targeted",
                ),
            )
            assertEquals(NoteRoutingPhase.ROUTED, store.parent(request.key)?.routingPhase)
            store.advanceNotePhase(request.key, 0, NoteRoutingPhase.POSTCHECK_VERIFIED)
        }

    @Test
    fun reservationPromotionReleaseAndFullActiveLeaseUseNeutralScaledAccounting() =
        withStore(JournalCapacityLimits.forTests(3, 4)) { store ->
            val media = prepareMedia(store, 33, 1, 1)
            assertEquals(2, store.mediaLease(media.claim.runId)?.unusedSlots)
            val spare =
                store.reserveMedia(
                    media.claim.runId,
                    listOf(reservation(ParentKey(media.claim.runId, requestId(2)), 2)),
                ).single()
            assertEquals(1, store.mediaLease(media.claim.runId)?.unusedSlots)
            store.releaseReservation(spare.id)
            assertEquals(2, store.mediaLease(media.claim.runId)?.unusedSlots)
            store.recordProviderEntry(media.child.id)
            store.commitMediaReceipt(
                media.child.id,
                media.claim.id,
                ProviderReceipt.Media("audio_1.mp3", "file:///audio_1.mp3"),
                "known stored claim",
            )
            assertEquals(MediaLeaseState.RELEASED, store.releaseMediaLease(media.claim.runId)?.state)

            val secondRun = runId(34)
            assertEquals(3, store.acquireMediaLease(secondRun).unusedSlots)
            val full =
                store.reserveMedia(
                    secondRun,
                    (101..103).map {
                        reservation(ParentKey(secondRun, requestId(1)), it).copy(
                            requestedFilename = "second-$it.mp3",
                            preferredName = "second_$it",
                        )
                    },
                )
            assertEquals(0, store.mediaLease(secondRun)?.unusedSlots)
            assertThrows(JournalInvariantViolation::class.java) { store.acquireMediaLease(runId(35)) }
            full.forEach { store.releaseReservation(it.id) }
            assertEquals(MediaLeaseState.RELEASED, store.releaseMediaLease(secondRun)?.state)
            assertEquals(3, store.acquireMediaLease(runId(35)).unusedSlots)
        }

    @Test
    fun ownerlessTerminalizationCrashRollsBackBeforeAnyRemediationOrScrub() {
        val name = databaseName()
        val request = createRequest(36, 1, 1)
        try {
            SqliteAnkiMutationStore(
                context,
                name,
                crashHooks = CrashAt(JournalCrashPoint.AFTER_OWNERLESS_TERMINALIZATION),
                enforceBackgroundThread = false,
            ).use { store ->
                prepareCommittedNote(store, request, 3_601)
                assertThrows(SimulatedCrash::class.java) { store.abandonOwnerless(emptySet()) }
            }

            SqliteAnkiMutationStore(context, name, enforceBackgroundThread = false).use { reopened ->
                assertEquals(ParentState.RUNNING, reopened.parent(request.key)?.state)
                assertTrue(reopened.alignedResults(request.key).isEmpty())
                assertTrue(reopened.openRemediations().isEmpty())
                assertNotNull(reopened.activeNote(request.key))
                assertEquals(ParentState.ABANDONED, reopened.abandonOwnerless(emptySet()).single().state)
                assertEquals(RemediationKind.NOTE_COMMITTED_FAILED, reopened.openRemediations().single().kind)
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun durableNormalizedLedgersRejectRawIdentityMutationAndSilentDeletion() =
        withStore { store ->
            val media = prepareMedia(store, 37, 1, 1)
            val db = store.writableDatabase
            assertThrows(SQLiteConstraintException::class.java) {
                db.update(
                    "media_claims",
                    ContentValues().apply { put("sha256", "1".repeat(64)); put("updated_at_ms", 99_999) },
                    "id = ?",
                    arrayOf(media.claim.id.toString()),
                )
            }
            assertThrows(SQLiteConstraintException::class.java) {
                db.delete("media_claims", "id = ?", arrayOf(media.claim.id.toString()))
            }
            assertThrows(SQLiteConstraintException::class.java) {
                db.delete("media_reservations", "id = ?", arrayOf(media.reservation.id.toString()))
            }
            assertThrows(SQLiteConstraintException::class.java) {
                db.delete("media_leases", "id = ?", arrayOf(media.reservation.leaseId.toString()))
            }
            store.completeMediaFailure(
                media.child.id,
                media.claim.id,
                ChildState.PROVEN_NOT_COMMITTED,
                MediaClaimState.CLEANED_VERIFIED,
                AlignedResult.MediaFailed(
                    0,
                    media.claim.assetId,
                    JournalError(JournalErrorCode.MEDIA_STORE_FAILED, "provider was never entered", false),
                    "proven pre-entry failure",
                ),
                "proven pre-entry failure",
            )
            assertThrows(SQLiteConstraintException::class.java) {
                db.update(
                    "mutation_children",
                    ContentValues().apply { put("terminal_evidence", "rewritten child evidence") },
                    "id = ?",
                    arrayOf(media.child.id.toString()),
                )
            }
            assertThrows(SQLiteConstraintException::class.java) {
                db.update(
                    "media_claims",
                    ContentValues().apply {
                        put("compact_evidence", "rewritten claim evidence")
                        put("updated_at_ms", Long.MAX_VALUE - 1)
                    },
                    "id = ?",
                    arrayOf(media.claim.id.toString()),
                )
            }
            val staging =
                store.recordStaging(
                    StagingDraft(
                        runId(37),
                        requestId(2),
                        assetId(2),
                        "run/staged.bin",
                        "content://com.ankiminer.files/run/staged.bin",
                        "com.ichi2.anki",
                        3,
                        SHA,
                    ),
                )
            store.transitionStaging(staging.id, StagingState.CLEANUP_PENDING, "cleanup scheduled")
            assertThrows(SQLiteConstraintException::class.java) {
                db.update(
                    "staging_artifacts",
                    ContentValues().apply {
                        put("compact_evidence", "rewritten staging evidence")
                        put("updated_at_ms", Long.MAX_VALUE - 1)
                    },
                    "id = ?",
                    arrayOf(staging.id.toString()),
                )
            }

            val note = createRequest(38, 1, 1)
            store.createParent(note)
            store.beginParent(note.key)
            store.storeTargetSnapshot(note.key, targetSnapshot())
            store.materializeActiveNote(note.key, materialization(note, 0))
            val noteParent = requireNotNull(store.parent(note.key))
            assertThrows(SQLiteConstraintException::class.java) {
                db.update(
                    "active_note_fields",
                    ContentValues().apply { put("field_value", "changed") },
                    "parent_id = ?",
                    arrayOf(noteParent.id.toString()),
                )
            }
            assertThrows(SQLiteConstraintException::class.java) {
                db.delete("target_expectation_fields", "parent_id = ?", arrayOf(noteParent.id.toString()))
            }

            val remediation =
                store.addRemediation(
                    RemediationDraft(
                        claimId = media.claim.id,
                        kind = RemediationKind.MEDIA_COMMIT_UNCERTAIN,
                        summary = "manual review required",
                    ),
                )
            assertThrows(SQLiteConstraintException::class.java) {
                db.delete("remediations", "id = ?", arrayOf(remediation.id.toString()))
            }
        }

    @Test
    fun exactRunCleanupAcknowledgesOnlyDuplicateFreeExactDurableIdSetAndScrubs() =
        withStore { store ->
            val first = readyVerify(store, 10, 1)
            val second = readyVerify(store, 10, 2)
            val duplicateEvidence = listOf(first.key.requestId, first.key.requestId, second.key.requestId)
            val rejected = store.cleanupRun(first.key.runId, true, duplicateEvidence)
            assertFalse(rejected.evidenceAccepted)
            assertTrue(rejected.acknowledgedRequestIds.isEmpty())
            assertEquals(setOf(first.key.requestId, second.key.requestId), rejected.abandonedRequestIds.toSet())
            assertEquals(ParentState.ABANDONED, store.parent(first.key)?.state)
            assertEquals(0L, count(store.writableDatabase, "parent_request_items"))
            assertEquals(0L, count(store.writableDatabase, "target_expectations"))
            assertEquals(0L, count(store.writableDatabase, "aligned_results"))
            assertEquals(2L, count(store.writableDatabase, "terminal_parent_audit"))
        }

    @Test
    fun exactRunCleanupAcceptsOrderIndependentExactSetAndKeepsOnlyCompactAudit() =
        withStore { store ->
            val first = readyVerify(store, 11, 1)
            val second = readyVerify(store, 11, 2)
            val accepted =
                store.cleanupRun(
                    first.key.runId,
                    acknowledgeAuthorized = true,
                    frozenDurableRequestIds = listOf(second.key.requestId, first.key.requestId),
                )
            assertTrue(accepted.evidenceAccepted)
            assertEquals(setOf(first.key.requestId, second.key.requestId), accepted.acknowledgedRequestIds.toSet())
            assertTrue(accepted.abandonedRequestIds.isEmpty())
            assertEquals(ParentState.RESPONSE_ACKNOWLEDGED, store.parent(first.key)?.state)
            assertEquals(2L, count(store.writableDatabase, "terminal_outcome_audit"))
            assertEquals(0L, count(store.writableDatabase, "parent_terminal_metadata"))
        }

    @Test
    fun finalizedStoredMediaRequiresAtomicUnattachedAcknowledgementAcrossReopen() {
        val name = databaseName()
        var firstClaimId = 0L
        var secondClaimId = 0L
        try {
            SqliteAnkiMutationStore(context, name, enforceBackgroundThread = false).use { store ->
                val (acknowledgedRequest, acknowledged) = readyStoredMedia(store, 140, 1, 1)
                firstClaimId = acknowledged.claim.id
                assertTrue(
                    store.cleanupRun(
                        acknowledgedRequest.key.runId,
                        acknowledgeAuthorized = true,
                        frozenDurableRequestIds = listOf(acknowledgedRequest.key.requestId),
                    ).evidenceAccepted,
                )

                val (abandonedRequest, abandoned) = readyStoredMedia(store, 141, 1, 2)
                secondClaimId = abandoned.claim.id
                assertFalse(
                    store.cleanupRun(
                        abandonedRequest.key.runId,
                        acknowledgeAuthorized = false,
                        frozenDurableRequestIds = emptyList(),
                    ).evidenceAccepted,
                )

                val remediations = store.openRemediations().filter {
                    it.kind == RemediationKind.MEDIA_STORED_UNATTACHED
                }
                assertEquals(setOf(firstClaimId, secondClaimId), remediations.mapNotNull { it.claimId }.toSet())
                assertTrue(remediations.all { it.parentId != null && it.stagingId == null })
                assertThrows(JournalInvariantViolation::class.java) {
                    store.transitionClaim(
                        firstClaimId,
                        MediaClaimState.ACKNOWLEDGED_BY_USER,
                        compactEvidence = "generic transition must not bypass remediation",
                    )
                }
                val firstRemediation = remediations.single { it.claimId == firstClaimId }
                assertThrows(JournalInvariantViolation::class.java) {
                    store.resolveRemediation(firstRemediation.id, "generic resolution must not split state")
                }
                assertThrows(SQLiteConstraintException::class.java) {
                    store.writableDatabase.update(
                        "remediations",
                        ContentValues().apply {
                            put("state", RemediationState.RESOLVED.name)
                            put("compact_evidence", "invalid split resolution")
                            put("updated_at_ms", firstRemediation.updatedAtMs + 1)
                        },
                        "id = ?",
                        arrayOf(firstRemediation.id.toString()),
                    )
                }

                val secondRemediation = remediations.single { it.claimId == secondClaimId }
                assertEquals(
                    RemediationState.RESOLVED,
                    store.acknowledgeUnattachedMedia(
                        secondRemediation.id,
                        "user acknowledged the unattached provider media",
                    ).state,
                )
                assertEquals(MediaClaimState.ACKNOWLEDGED_BY_USER.name, claimState(store.writableDatabase, secondClaimId))

                val firstClaim = requireNotNull(store.mediaClaim(acknowledgedRequest.key, acknowledged.claim.assetId))
                assertEquals(
                    1,
                    store.writableDatabase.update(
                        "media_claims",
                        ContentValues().apply {
                            put("state", MediaClaimState.ACKNOWLEDGED_BY_USER.name)
                            put("compact_evidence", "raw acknowledgement remains atomically coupled")
                            put("updated_at_ms", firstClaim.updatedAtMs + 1)
                        },
                        "id = ?",
                        arrayOf(firstClaimId.toString()),
                    ),
                )
                assertTrue(store.openRemediations().none { it.kind == RemediationKind.MEDIA_STORED_UNATTACHED })
            }

            SqliteAnkiMutationStore(context, name, enforceBackgroundThread = false).use { reopened ->
                assertEquals(MediaClaimState.ACKNOWLEDGED_BY_USER.name, claimState(reopened.writableDatabase, firstClaimId))
                assertEquals(MediaClaimState.ACKNOWLEDGED_BY_USER.name, claimState(reopened.writableDatabase, secondClaimId))
                assertTrue(reopened.openRemediations().none { it.kind == RemediationKind.MEDIA_STORED_UNATTACHED })
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun cleanupTerminalizesLaterNoteProofBeforeClassifyingStoredMediaAsUnattached() =
        withStore { store ->
            val (mediaRequest, media) = readyStoredMedia(store, 142, 1, 1)
            val noteRequest =
                createRequest(
                    142,
                    2,
                    1,
                    MediaBinding(media.claim.assetId, "audio_1.mp3"),
                )
            prepareCommittedNote(
                store,
                noteRequest,
                14_201,
                listOf(DurableMediaBinding(media.claim.assetId, "audio_1.mp3", media.claim.id)),
            )
            advanceToPostcheck(store, noteRequest, 14_201)

            val cleanup =
                store.cleanupRun(
                    mediaRequest.key.runId,
                    acknowledgeAuthorized = true,
                    frozenDurableRequestIds = listOf(mediaRequest.key.requestId),
                )

            assertTrue(cleanup.evidenceAccepted)
            assertEquals(MediaClaimState.ATTACHED_VERIFIED, store.mediaClaim(mediaRequest.key, media.claim.assetId)?.state)
            assertEquals(
                0L,
                auditCount(
                    store.writableDatabase,
                    "remediations",
                    "1",
                    "kind = 'MEDIA_STORED_UNATTACHED'",
                ),
            )
        }

    @Test
    fun preparedNoteBindingCanResolveEarlierStoredUnattachedRemediationAcrossReopen() {
        val name = databaseName()
        var claimId = 0L
        try {
            SqliteAnkiMutationStore(context, name, enforceBackgroundThread = false).use { store ->
                val (mediaRequest, media) = readyStoredMedia(store, 143, 1, 1)
                claimId = media.claim.id
                val noteRequest = createRequest(143, 2, 1, MediaBinding(media.claim.assetId, "audio_1.mp3"))
                store.createParent(noteRequest)
                store.beginParent(noteRequest.key)
                store.storeTargetSnapshot(noteRequest.key, targetSnapshot())
                store.materializeActiveNote(
                    noteRequest.key,
                    materialization(
                        noteRequest,
                        0,
                        listOf(DurableMediaBinding(media.claim.assetId, "audio_1.mp3", media.claim.id)),
                    ),
                )
                val noteChild =
                    store.prepareChild(
                        noteRequest.key,
                        MutationCommand.InsertNote(0, noteRequest.itemIds.single(), 11, "語\u001fword", "mined"),
                    )

                assertEquals(
                    listOf(mediaRequest.key),
                    store.abandonOwnerless(emptySet()).map { it.key },
                )
                assertEquals(
                    RemediationKind.MEDIA_STORED_UNATTACHED,
                    store.openRemediations().single().kind,
                )

                store.recordProviderEntry(noteChild.id)
                store.commitNoteReceipt(
                    noteChild.id,
                    ProviderReceipt.Note(14_301, "content://com.ichi2.anki.flashcards/notes/14301"),
                    "validated resumed note receipt",
                )
                advanceToPostcheck(store, noteRequest, 14_301)
                store.completeVerifiedNote(noteRequest.key, 0, 14_301, "resumed exact attachment proof")

                assertEquals(MediaClaimState.ATTACHED_VERIFIED.name, claimState(store.writableDatabase, claimId))
                assertTrue(store.openRemediations().isEmpty())
            }

            SqliteAnkiMutationStore(context, name, enforceBackgroundThread = false).use { reopened ->
                assertEquals(MediaClaimState.ATTACHED_VERIFIED.name, claimState(reopened.writableDatabase, claimId))
                assertTrue(reopened.openRemediations().isEmpty())
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun rawSqlRejectsBadDigestNoncontiguousItemsBadStatusAndReceiptBeforeEntry() =
        withStore { store ->
            val db = store.writableDatabase
            assertThrows(SQLiteConstraintException::class.java) {
                db.insertOrThrow(
                    "parents",
                    null,
                    ContentValues().apply {
                        put("run_id", runId(12))
                        put("request_id", requestId(99))
                        put("operation_kind", ParentOperation.VERIFY_TARGET.name)
                        put("digest_version", 1)
                        put("request_sha256", "A".repeat(64))
                        put("state", ParentState.PREPARED.name)
                        put("created_at_ms", 1)
                        put("updated_at_ms", 1)
                    },
                )
            }
            val request = mediaRequest(12, 1, listOf(1))
            val parent = store.createParent(request)
            assertThrows(SQLiteConstraintException::class.java) {
                db.delete("parent_request_items", "parent_id = ?", arrayOf(parent.id.toString()))
            }
            assertThrows(SQLiteConstraintException::class.java) {
                db.insertOrThrow(
                    "parent_request_items",
                    null,
                    ContentValues().apply {
                        put("parent_id", parent.id)
                        put("request_index", 2)
                        put("item_id", assetId(2))
                    },
                )
            }
            assertThrows(SQLiteConstraintException::class.java) {
                db.insertOrThrow(
                    "aligned_results",
                    null,
                    ContentValues().apply {
                        put("parent_id", parent.id)
                        put("request_index", 0)
                        put("item_id", request.itemIds.single())
                        put("status_kind", AlignedStatus.CREATED.name)
                        put("committed_id", 5)
                    },
                )
            }

            store.acquireMediaLease(request.key.runId)
            val reservation = store.reserveMedia(request.key.runId, listOf(reservation(request.key, 1))).single()
            val promoted =
                store.promoteReservation(
                    request.key,
                    reservation.id,
                    MutationCommand.StoreMedia(0, request.itemIds.single(), "content://files/audio.mp3", "audio_1"),
                )
            assertThrows(SQLiteConstraintException::class.java) {
                db.insertOrThrow(
                    "media_receipts",
                    null,
                    ContentValues().apply {
                        put("child_id", promoted.child.id)
                        put("actual_filename", "audio_1.mp3")
                        put("file_uri", "file:///audio_1.mp3")
                    },
                )
            }
            store.recordProviderEntry(promoted.child.id)
            db.insertOrThrow(
                "media_receipts",
                null,
                ContentValues().apply {
                    put("child_id", promoted.child.id)
                    put("actual_filename", "audio_1.mp3")
                    put("file_uri", "file:///audio_1.mp3")
                },
            )
            assertThrows(SQLiteConstraintException::class.java) {
                db.update(
                    "media_receipts",
                    ContentValues().apply { put("file_uri", "file:///changed.mp3") },
                    "child_id = ?",
                    arrayOf(promoted.child.id.toString()),
                )
            }
            assertThrows(SQLiteConstraintException::class.java) {
                db.delete("media_receipts", "child_id = ?", arrayOf(promoted.child.id.toString()))
            }
        }

    @Test
    fun deckCreateCrashReopenMatrixKeepsEveryReceiptBoundaryRecoverable() {
        ReceiptCrashBoundary.entries.forEach { boundary ->
            val name = databaseName()
            val request = verifyRequest(50 + boundary.ordinal, 1)
            val childId: Long
            try {
                SqliteAnkiMutationStore(context, name, enforceBackgroundThread = false).use { store ->
                    store.createParent(request)
                    store.beginParent(request.key)
                    store.storeTargetExpectation(request.key, targetSnapshot().expectation)
                    childId = store.prepareChild(request.key, MutationCommand.CreateDeck("Mining")).id
                }

                SqliteAnkiMutationStore(
                    context,
                    name,
                    crashHooks =
                        CrashAt(
                            boundary.crashPoint(
                                JournalCrashPoint.BEFORE_DECK_RECEIPT_RECORDED,
                                JournalCrashPoint.AFTER_DECK_RECEIPT_RECORDED,
                            ),
                        ),
                    enforceBackgroundThread = false,
                ).use { store ->
                    expectCrashAtBoundary(
                        boundary,
                        providerEntry = { store.recordProviderEntry(childId) },
                        providerReceipt = {
                            store.recordDeckReceipt(
                                childId,
                                ProviderReceipt.Deck(5_000 + boundary.ordinal.toLong(), "content://anki/decks/5000"),
                            )
                        },
                    )
                }

                SqliteAnkiMutationStore(context, name, enforceBackgroundThread = false).use { reopened ->
                    val action = AnkiMutationRecovery.plan(reopened.recoveryInventory()).preparedMutation
                    when (boundary) {
                        ReceiptCrashBoundary.BEFORE_ENTRY -> {
                            action as PreparedMutationRecovery.ProveNotCommitted
                            assertEquals(0, action.child.attemptCount)
                            assertNull(action.child.receipt)
                        }
                        ReceiptCrashBoundary.AFTER_ENTRY,
                        ReceiptCrashBoundary.BEFORE_RECEIPT,
                        ReceiptCrashBoundary.AFTER_RECEIPT,
                        -> {
                            action as PreparedMutationRecovery.ReconcileDeck
                            assertEquals(1, action.child.attemptCount)
                            assertEquals(
                                boundary == ReceiptCrashBoundary.AFTER_RECEIPT,
                                action.returnedReceipt != null,
                            )
                            assertEquals(targetSnapshot().expectation, action.expectedTarget)
                        }
                    }
                    assertRejectedNonCardReissue(reopened, childId)
                }
            } finally {
                context.deleteDatabase(name)
            }
        }
    }

    @Test
    fun mediaInsertCrashReopenMatrixIsEitherPendingUncertainOrAtomicallyStored() {
        ReceiptCrashBoundary.entries.forEach { boundary ->
            val name = databaseName()
            val key: ParentKey
            val childId: Long
            val claimId: Long
            try {
                SqliteAnkiMutationStore(context, name, enforceBackgroundThread = false).use { store ->
                    val request = mediaRequest(60 + boundary.ordinal, 1, listOf(1, 2))
                    store.createParent(request)
                    store.beginParent(request.key)
                    store.acquireMediaLease(request.key.runId)
                    val reservation =
                        store.reserveMedia(request.key.runId, listOf(reservation(request.key, 1))).single()
                    val prepared =
                        store.promoteReservation(
                            request.key,
                            reservation.id,
                            MutationCommand.StoreMedia(
                                0,
                                request.itemIds.first(),
                                "content://com.ankiminer.files/audio-1.mp3",
                                "audio_1",
                            ),
                        )
                    key = ParentKey(prepared.claim.runId, prepared.claim.requestId)
                    childId = prepared.child.id
                    claimId = prepared.claim.id
                }

                SqliteAnkiMutationStore(
                    context,
                    name,
                    crashHooks =
                        CrashAt(
                            boundary.crashPoint(
                                JournalCrashPoint.BEFORE_MEDIA_RECEIPT_TRANSACTION,
                                JournalCrashPoint.AFTER_MEDIA_RECEIPT_TRANSACTION,
                            ),
                        ),
                    enforceBackgroundThread = false,
                ).use { store ->
                    expectCrashAtBoundary(
                        boundary,
                        providerEntry = { store.recordProviderEntry(childId) },
                        providerReceipt = {
                            store.commitMediaReceipt(
                                childId,
                                claimId,
                                ProviderReceipt.Media("audio_1.mp3", "file:///audio_1.mp3"),
                                "validated media receipt",
                            )
                        },
                    )
                }

                SqliteAnkiMutationStore(context, name, enforceBackgroundThread = false).use { reopened ->
                    val action = AnkiMutationRecovery.plan(reopened.recoveryInventory()).preparedMutation
                    when (boundary) {
                        ReceiptCrashBoundary.BEFORE_ENTRY -> {
                            action as PreparedMutationRecovery.ProveNotCommitted
                            assertEquals(0, action.child.attemptCount)
                            assertEquals(MediaClaimState.PENDING.name, claimState(reopened.writableDatabase, claimId))
                            assertTrue(reopened.alignedResults(key).isEmpty())
                        }
                        ReceiptCrashBoundary.AFTER_ENTRY,
                        ReceiptCrashBoundary.BEFORE_RECEIPT,
                        -> {
                            action as PreparedMutationRecovery.MarkMediaUncertain
                            assertEquals(1, action.child.attemptCount)
                            assertEquals(claimId, action.claimId)
                            assertEquals(MediaClaimState.PENDING.name, claimState(reopened.writableDatabase, claimId))
                            assertTrue(reopened.alignedResults(key).isEmpty())
                        }
                        ReceiptCrashBoundary.AFTER_RECEIPT -> {
                            assertNull(action)
                            assertNull(reopened.preparedChild())
                            assertEquals(MediaClaimState.STORED.name, claimState(reopened.writableDatabase, claimId))
                            val result = reopened.alignedResults(key).single() as AlignedResult.MediaStored
                            assertEquals("audio_1.mp3", result.actualFilename)
                        }
                    }
                    assertRejectedNonCardReissue(reopened, childId)
                    when (boundary) {
                        ReceiptCrashBoundary.BEFORE_ENTRY ->
                            reopened.completeMediaFailure(
                                childId,
                                claimId,
                                ChildState.PROVEN_NOT_COMMITTED,
                                MediaClaimState.CLEANED_VERIFIED,
                                AlignedResult.MediaFailed(
                                    0,
                                    assetId(1),
                                    JournalError(
                                        JournalErrorCode.MEDIA_STORE_FAILED,
                                        "provider was never entered",
                                        retryable = false,
                                    ),
                                    "provider was never entered",
                                ),
                                "provider was never entered",
                            )
                        ReceiptCrashBoundary.AFTER_ENTRY,
                        ReceiptCrashBoundary.BEFORE_RECEIPT,
                        ->
                            reopened.completeMediaFailure(
                                childId,
                                claimId,
                                ChildState.COMMIT_UNCERTAIN,
                                MediaClaimState.COMMIT_UNCERTAIN,
                                AlignedResult.MediaUncertain(0, assetId(1), "provider return was not durable"),
                                "provider return was not durable",
                            )
                        ReceiptCrashBoundary.AFTER_RECEIPT -> Unit
                    }

                    assertEquals(ParentState.ABANDONED, reopened.abandonOwnerless(emptySet()).single().state)
                    reopened.writableDatabase.rawQuery(
                        """SELECT status_kind, error_code FROM terminal_result_audit
                           WHERE parent_id = (SELECT id FROM parents WHERE run_id = ? AND request_id = ?)
                           ORDER BY request_index""".trimIndent(),
                        arrayOf(key.runId, key.requestId),
                    ).use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals(
                            when (boundary) {
                                ReceiptCrashBoundary.BEFORE_ENTRY -> AlignedStatus.FAILED.name
                                ReceiptCrashBoundary.AFTER_ENTRY,
                                ReceiptCrashBoundary.BEFORE_RECEIPT,
                                -> AlignedStatus.UNCERTAIN.name
                                ReceiptCrashBoundary.AFTER_RECEIPT -> AlignedStatus.STORED.name
                            },
                            cursor.getString(0),
                        )
                        if (boundary == ReceiptCrashBoundary.BEFORE_ENTRY) {
                            assertEquals(JournalErrorCode.MEDIA_STORE_FAILED.name, cursor.getString(1))
                        } else {
                            assertTrue(cursor.isNull(1))
                        }
                        assertTrue(cursor.moveToNext())
                        assertEquals(AlignedStatus.NOT_ATTEMPTED.name, cursor.getString(0))
                        assertTrue(cursor.isNull(1))
                        assertFalse(cursor.moveToNext())
                    }
                }
            } finally {
                context.deleteDatabase(name)
            }
        }
    }

    @Test
    fun noteInsertCrashReopenMatrixNeverLosesOrInventsTheProviderNoteId() {
        ReceiptCrashBoundary.entries.forEach { boundary ->
            val name = databaseName()
            val request = createRequest(70 + boundary.ordinal, 1, 1)
            val childId: Long
            val providerNoteId = 7_000L + boundary.ordinal
            try {
                SqliteAnkiMutationStore(context, name, enforceBackgroundThread = false).use { store ->
                    store.createParent(request)
                    store.beginParent(request.key)
                    store.storeTargetSnapshot(request.key, targetSnapshot())
                    store.materializeActiveNote(request.key, materialization(request, 0))
                    childId =
                        store.prepareChild(
                            request.key,
                            MutationCommand.InsertNote(0, request.itemIds.single(), 11, "語\u001fword", "mined"),
                        ).id
                }

                SqliteAnkiMutationStore(
                    context,
                    name,
                    crashHooks =
                        CrashAt(
                            boundary.crashPoint(
                                JournalCrashPoint.BEFORE_NOTE_RECEIPT_TRANSACTION,
                                JournalCrashPoint.AFTER_NOTE_RECEIPT_TRANSACTION,
                            ),
                        ),
                    enforceBackgroundThread = false,
                ).use { store ->
                    expectCrashAtBoundary(
                        boundary,
                        providerEntry = { store.recordProviderEntry(childId) },
                        providerReceipt = {
                            store.commitNoteReceipt(
                                childId,
                                ProviderReceipt.Note(providerNoteId, "content://anki/notes/$providerNoteId"),
                                "validated note receipt",
                            )
                        },
                    )
                }

                SqliteAnkiMutationStore(context, name, enforceBackgroundThread = false).use { reopened ->
                    val action = AnkiMutationRecovery.plan(reopened.recoveryInventory()).preparedMutation
                    val parent = requireNotNull(reopened.parent(request.key))
                    when (boundary) {
                        ReceiptCrashBoundary.BEFORE_ENTRY -> {
                            action as PreparedMutationRecovery.ProveNotCommitted
                            assertEquals(0, action.child.attemptCount)
                            assertNull(parent.activeNoteId)
                            assertEquals(NoteRoutingPhase.NOTE_PENDING, parent.routingPhase)
                        }
                        ReceiptCrashBoundary.AFTER_ENTRY,
                        ReceiptCrashBoundary.BEFORE_RECEIPT,
                        -> {
                            action as PreparedMutationRecovery.MarkNoteUncertain
                            assertEquals(1, action.child.attemptCount)
                            assertNull(parent.activeNoteId)
                            assertEquals(NoteRoutingPhase.NOTE_PENDING, parent.routingPhase)
                        }
                        ReceiptCrashBoundary.AFTER_RECEIPT -> {
                            assertNull(action)
                            assertNull(reopened.preparedChild())
                            assertEquals(providerNoteId, parent.activeNoteId)
                            assertEquals(NoteRoutingPhase.NOTE_COMMIT_KNOWN, parent.routingPhase)
                        }
                    }
                    assertRejectedNonCardReissue(reopened, childId)
                }
            } finally {
                context.deleteDatabase(name)
            }
        }
    }

    @Test
    fun cardUpdateCrashReopenMatrixAllowsExactlyOneReceiptlessRecoveryReissue() {
        ReceiptCrashBoundary.entries.forEach { boundary ->
            val name = databaseName()
            val request = createRequest(80 + boundary.ordinal, 1, 1)
            val providerNoteId = 8_000L + boundary.ordinal
            val childId: Long
            try {
                SqliteAnkiMutationStore(context, name, enforceBackgroundThread = false).use { store ->
                    prepareCommittedNote(store, request, providerNoteId)
                    store.advanceNotePhase(request.key, 0, NoteRoutingPhase.NOTE_READBACK_VERIFIED)
                    store.advanceNotePhase(request.key, 0, NoteRoutingPhase.CARDS_DISCOVERED)
                    val intent =
                        store.createRoutingIntents(
                            request.key,
                            0,
                            listOf(RoutingIntentDraft(0, providerNoteId + 1, providerNoteId, 0, 2, 1)),
                        ).single()
                    childId = store.prepareRoutingChild(intent.id).id
                }

                SqliteAnkiMutationStore(
                    context,
                    name,
                    crashHooks =
                        CrashAt(
                            boundary.crashPoint(
                                JournalCrashPoint.BEFORE_CARD_RECEIPT_RECORDED,
                                JournalCrashPoint.AFTER_CARD_RECEIPT_RECORDED,
                            ),
                        ),
                    enforceBackgroundThread = false,
                ).use { store ->
                    expectCrashAtBoundary(
                        boundary,
                        providerEntry = { store.recordProviderEntry(childId) },
                        providerReceipt = { store.recordCardReceipt(childId) },
                    )
                }

                SqliteAnkiMutationStore(context, name, enforceBackgroundThread = false).use { reopened ->
                    val action = AnkiMutationRecovery.plan(reopened.recoveryInventory()).preparedMutation
                    when (boundary) {
                        ReceiptCrashBoundary.BEFORE_ENTRY -> {
                            action as PreparedMutationRecovery.ProveNotCommitted
                            assertEquals(0, action.child.attemptCount)
                            assertThrows(JournalInvariantViolation::class.java) {
                                reopened.recordProviderEntry(childId, recoveryReissue = true)
                            }
                        }
                        ReceiptCrashBoundary.AFTER_ENTRY,
                        ReceiptCrashBoundary.BEFORE_RECEIPT,
                        -> {
                            action as PreparedMutationRecovery.InspectCardRouting
                            assertEquals(1, action.child.attemptCount)
                            assertFalse(action.hasAffectedCountReceipt)
                            assertEquals(
                                CardRecoveryDisposition.REISSUE_ONCE_THEN_REQUERY,
                                AnkiMutationRecovery.decideCardRecovery(
                                    CardRecoveryObservation.PRE_UPDATE_DECK,
                                    action.hasAffectedCountReceipt,
                                    action.child.attemptCount,
                                ),
                            )
                            assertEquals(
                                CardRecoveryDisposition.VERIFY_POSTCONDITION,
                                AnkiMutationRecovery.decideCardRecovery(
                                    CardRecoveryObservation.DESIRED_DECK,
                                    action.hasAffectedCountReceipt,
                                    action.child.attemptCount,
                                ),
                            )
                            assertEquals(
                                CardRecoveryDisposition.COMMITTED_FAILED_EXTERNAL_DRIFT,
                                AnkiMutationRecovery.decideCardRecovery(
                                    CardRecoveryObservation.THIRD_DECK,
                                    action.hasAffectedCountReceipt,
                                    action.child.attemptCount,
                                ),
                            )

                            assertEquals(2, reopened.recordProviderEntry(childId, recoveryReissue = true).attemptCount)
                            val afterReissue =
                                AnkiMutationRecovery.plan(reopened.recoveryInventory()).preparedMutation
                                    as PreparedMutationRecovery.InspectCardRouting
                            assertEquals(
                                CardRecoveryDisposition.COMMITTED_FAILED_UNCERTAIN,
                                AnkiMutationRecovery.decideCardRecovery(
                                    CardRecoveryObservation.PRE_UPDATE_DECK,
                                    afterReissue.hasAffectedCountReceipt,
                                    afterReissue.child.attemptCount,
                                ),
                            )
                            assertThrows(JournalInvariantViolation::class.java) {
                                reopened.recordProviderEntry(childId, recoveryReissue = true)
                            }
                        }
                        ReceiptCrashBoundary.AFTER_RECEIPT -> {
                            action as PreparedMutationRecovery.InspectCardRouting
                            assertEquals(1, action.child.attemptCount)
                            assertTrue(action.hasAffectedCountReceipt)
                            assertEquals(
                                CardRecoveryDisposition.COMMITTED_FAILED_EXTERNAL_DRIFT,
                                AnkiMutationRecovery.decideCardRecovery(
                                    CardRecoveryObservation.PRE_UPDATE_DECK,
                                    action.hasAffectedCountReceipt,
                                    action.child.attemptCount,
                                ),
                            )
                            assertThrows(JournalInvariantViolation::class.java) {
                                reopened.recordProviderEntry(childId, recoveryReissue = true)
                            }
                        }
                    }
                }
            } finally {
                context.deleteDatabase(name)
            }
        }
    }

    @Test
    fun resolvedNamespaceHistoryUsesIndexedCollisionProofOutsideTheUnresolvedCap() =
        withStore(JournalCapacityLimits.forTests(2, 4)) { store ->
            val attached = prepareMedia(store, 90, 1, 1)
            store.recordProviderEntry(attached.child.id)
            store.commitMediaReceipt(
                attached.child.id,
                attached.claim.id,
                ProviderReceipt.Media("audio_1.mp3", "file:///audio_1.mp3"),
                "known attached media",
            )
            val attachedNote = createRequest(90, 2, 1, MediaBinding(attached.claim.assetId, "audio_1.mp3"))
            prepareCommittedNote(
                store,
                attachedNote,
                9_001,
                listOf(DurableMediaBinding(attached.claim.assetId, "audio_1.mp3", attached.claim.id)),
            )
            advanceToPostcheck(store, attachedNote, 9_001)
            store.completeVerifiedNote(attachedNote.key, 0, 9_001, "exact attachment proof")
            store.releaseMediaLease(attached.claim.runId)

            repeat(4) { offset ->
                val acknowledged = prepareMedia(store, 91 + offset, 1, 2 + offset)
                store.completeMediaFailure(
                    acknowledged.child.id,
                    acknowledged.claim.id,
                    ChildState.PROVEN_NOT_COMMITTED,
                    MediaClaimState.ACKNOWLEDGED_BY_USER,
                    AlignedResult.MediaFailed(
                        0,
                        acknowledged.claim.assetId,
                        JournalError(JournalErrorCode.MEDIA_STORE_FAILED, "user accepted responsibility", false),
                        "user accepted responsibility",
                    ),
                    "user accepted responsibility",
                )
                store.releaseMediaLease(acknowledged.claim.runId)
            }

            val cleaned = prepareMedia(store, 95, 1, 6)
            store.completeMediaFailure(
                cleaned.child.id,
                cleaned.claim.id,
                ChildState.PROVEN_NOT_COMMITTED,
                MediaClaimState.CLEANED_VERIFIED,
                AlignedResult.MediaFailed(
                    0,
                    cleaned.claim.assetId,
                    JournalError(JournalErrorCode.MEDIA_STORE_FAILED, "absence verified", false),
                    "absence verified",
                ),
                "absence verified",
            )
            store.releaseMediaLease(cleaned.claim.runId)

            val freshRun = runId(96)
            val freshKey = ParentKey(freshRun, requestId(1))
            store.acquireMediaLease(freshRun)
            val disjoint =
                store.reserveMedia(
                    freshRun,
                    listOf(
                        reservation(freshKey, 100).copy(
                            requestedFilename = "fresh.mp3",
                            preferredName = "fresh",
                        ),
                    ),
                ).single()
            store.releaseReservation(disjoint.id)

            assertThrows(JournalInvariantViolation::class.java) {
                store.reserveMedia(
                    freshRun,
                    listOf(
                        reservation(freshKey, 101).copy(
                            requestedFilename = "audio_1.mp3",
                            preferredName = "fresh-direct",
                        ),
                    ),
                )
            }
            assertThrows(JournalInvariantViolation::class.java) {
                store.reserveMedia(
                    freshRun,
                    listOf(
                        reservation(freshKey, 102).copy(
                            requestedFilename = "audio_2_suffix.mp3",
                            preferredName = "fresh-prefix",
                        ),
                    ),
                )
            }
            assertThrows(JournalInvariantViolation::class.java) {
                store.reserveMedia(
                    freshRun,
                    listOf(
                        reservation(freshKey, 103).copy(
                            requestedFilename = "fresh-provider.mp3",
                            preferredName = "audio_3",
                        ),
                    ),
                )
            }

            val releasedName =
                store.reserveMedia(
                    freshRun,
                    listOf(
                        reservation(freshKey, 104).copy(
                            requestedFilename = cleaned.claim.requestedFilename,
                            preferredName = "cleaned-name-reuse",
                        ),
                    ),
                ).single()
            assertEquals(cleaned.claim.requestedFilename, releasedName.requestedFilename)
        }

    @Test
    fun providerPrefixesPreserveDotsAppendUnderscoreAndKeepLexicalSiblingsDisjoint() =
        withStore { store ->
            val run = runId(97)
            val key = ParentKey(run, requestId(1))
            store.acquireMediaLease(run)
            val reservations =
                store.reserveMedia(
                    run,
                    listOf(
                        reservation(key, 1).copy(requestedFilename = "cat-direct.mp3", preferredName = "cat"),
                        reservation(key, 2).copy(requestedFilename = "catalog-direct.mp3", preferredName = "catalog"),
                        reservation(key, 3).copy(requestedFilename = "voice-jp.mp3", preferredName = "voice.jp"),
                        reservation(key, 4).copy(requestedFilename = "voice-en.mp3", preferredName = "voice.en"),
                    ),
                )
            val prefixes =
                reservations.associate { reservation ->
                    reservation.preferredName to
                        store.writableDatabase.rawQuery(
                            "SELECT provider_prefix FROM media_reservations WHERE id = ?",
                            arrayOf(reservation.id.toString()),
                        ).use { cursor -> assertTrue(cursor.moveToFirst()); cursor.getString(0) }
                }
            assertEquals("cat_", prefixes.getValue("cat"))
            assertEquals("catalog_", prefixes.getValue("catalog"))
            assertEquals("voice.jp_", prefixes.getValue("voice.jp"))
            assertEquals("voice.en_", prefixes.getValue("voice.en"))
        }

    @Test
    fun fixedClockFinalizationUsesOneLogicalTimestampForAuditAndParent() {
        val name = databaseName()
        val request = verifyRequest(98, 1)
        try {
            SqliteAnkiMutationStore(
                context,
                name,
                clock = JournalClock { 100 },
                enforceBackgroundThread = false,
            ).use { store ->
                val target = targetSnapshot()
                store.createParent(request)
                store.beginParent(request.key)
                store.storeTargetSnapshot(request.key, target)
                store.markResultReady(request, JournalResponse.VerifySuccess(request.key, target))
                assertTrue(
                    store.cleanupRun(request.key.runId, true, listOf(request.key.requestId)).evidenceAccepted,
                )
                store.writableDatabase.rawQuery(
                    """SELECT p.updated_at_ms, a.finalized_at_ms FROM parents p
                       JOIN terminal_parent_audit a ON a.parent_id = p.id
                       WHERE p.run_id = ? AND p.request_id = ?""".trimIndent(),
                    arrayOf(request.key.runId, request.key.requestId),
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(cursor.getLong(0), cursor.getLong(1))
                    assertTrue(cursor.getLong(0) > 100)
                }
            }
            SqliteAnkiMutationStore(
                context,
                name,
                clock = JournalClock { 100 },
                enforceBackgroundThread = false,
            ).use { reopened ->
                assertEquals(ParentState.RESPONSE_ACKNOWLEDGED, reopened.parent(request.key)?.state)
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun targetAuditRetainsVerifiedAndUncertainDeckIdentityAcrossScrubAndReopen() {
        val name = databaseName()
        val verified = verifyRequest(99, 1)
        val uncertain = verifyRequest(99, 2)
        try {
            SqliteAnkiMutationStore(context, name, enforceBackgroundThread = false).use { store ->
                readyVerify(store, 99, 1)

                store.createParent(uncertain)
                store.beginParent(uncertain.key)
                store.storeTargetExpectation(uncertain.key, targetSnapshot().expectation)
                val child = store.prepareChild(uncertain.key, MutationCommand.CreateDeck("Mining"))
                store.recordProviderEntry(child.id)
                store.recordDeckReceipt(
                    child.id,
                    ProviderReceipt.Deck(9_902, "content://com.ichi2.anki.flashcards/decks/9902"),
                )
                store.completeChild(child.id, ChildState.COMMIT_UNCERTAIN, "deck identity could not be reconciled")
                val error =
                    JournalError(
                        JournalErrorCode.POST_COMMIT_UNCERTAIN,
                        "deck identity could not be reconciled",
                        retryable = false,
                    )
                store.markResultReady(uncertain, JournalResponse.VerifyError(uncertain.key, error))
                assertTrue(
                    store.cleanupRun(
                        verified.key.runId,
                        true,
                        listOf(verified.key.requestId, uncertain.key.requestId),
                    ).evidenceAccepted,
                )
            }

            SqliteAnkiMutationStore(context, name, enforceBackgroundThread = false).use { reopened ->
                val db = reopened.writableDatabase
                db.rawQuery(
                    """SELECT t.status_kind, t.expected_deck_name, t.model_id, t.model_name,
                              t.verified_deck_id, t.verified_deck_name, t.returned_deck_id
                       FROM terminal_target_audit t JOIN parents p ON p.id = t.parent_id
                       WHERE p.run_id = ? AND p.request_id = ?""".trimIndent(),
                    arrayOf(verified.key.runId, verified.key.requestId),
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(AlignedStatus.VERIFIED.name, cursor.getString(0))
                    assertEquals("Mining", cursor.getString(1))
                    assertEquals(11L, cursor.getLong(2))
                    assertEquals("Mining Model", cursor.getString(3))
                    assertEquals(2L, cursor.getLong(4))
                    assertEquals("Mining", cursor.getString(5))
                    assertTrue(cursor.isNull(6))
                }
                val uncertainParent = requireNotNull(reopened.parent(uncertain.key))
                db.rawQuery(
                    """SELECT status_kind, expected_deck_name, model_id, model_name,
                              verified_deck_id, verified_deck_name, returned_deck_id
                       FROM terminal_target_audit WHERE parent_id = ?""".trimIndent(),
                    arrayOf(uncertainParent.id.toString()),
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(AlignedStatus.FAILED.name, cursor.getString(0))
                    assertEquals("Mining", cursor.getString(1))
                    assertEquals(11L, cursor.getLong(2))
                    assertEquals("Mining Model", cursor.getString(3))
                    assertTrue(cursor.isNull(4))
                    assertTrue(cursor.isNull(5))
                    assertEquals(9_902L, cursor.getLong(6))
                }
                assertEquals(uncertain.digest.sha256, uncertainParent.requestSha256)
                assertThrows(SQLiteConstraintException::class.java) {
                    db.update(
                        "terminal_target_audit",
                        ContentValues().apply { put("returned_deck_id", 1) },
                        "parent_id = ?",
                        arrayOf(uncertainParent.id.toString()),
                    )
                }
                assertThrows(SQLiteConstraintException::class.java) {
                    db.delete("terminal_target_audit", "parent_id = ?", arrayOf(uncertainParent.id.toString()))
                }
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun oneItemPreEntryMediaRecoveryFinalizesAsFailedWithoutTopLevelStop() =
        withStore { store ->
            val prepared = prepareMedia(store, 100, 1, 1)
            val failure = JournalError(JournalErrorCode.MEDIA_STORE_FAILED, "provider was never entered", false)
            store.completeMediaFailure(
                prepared.child.id,
                prepared.claim.id,
                ChildState.PROVEN_NOT_COMMITTED,
                MediaClaimState.CLEANED_VERIFIED,
                AlignedResult.MediaFailed(0, prepared.claim.assetId, failure, "provider was never entered"),
                "provider was never entered",
            )
            assertEquals(ParentState.ABANDONED, store.abandonOwnerless(emptySet()).single().state)
            val parent = requireNotNull(store.parent(ParentKey(prepared.claim.runId, prepared.claim.requestId)))
            store.writableDatabase.rawQuery(
                """SELECT status_kind, error_code FROM terminal_result_audit
                   WHERE parent_id = ?""".trimIndent(),
                arrayOf(parent.id.toString()),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(AlignedStatus.FAILED.name, cursor.getString(0))
                assertEquals(JournalErrorCode.MEDIA_STORE_FAILED.name, cursor.getString(1))
                assertFalse(cursor.moveToNext())
            }
        }

    @Test
    fun productionLeaseIsExactlyEightThousandAndTestFixtureEnforcesSmallerBounds() {
        withStore { store ->
            assertEquals(MEDIA_LEASE_CAPACITY, store.acquireMediaLease(runId(13)).capacity)
        }
        withStore(JournalCapacityLimits.forTests(3, 4)) { store ->
            val runId = runId(14)
            store.acquireMediaLease(runId)
            val three = (1..3).map { reservation(ParentKey(runId, requestId(1)), it) }
            assertEquals(3, store.reserveMedia(runId, three).size)
            assertThrows(JournalInvariantViolation::class.java) {
                store.reserveMedia(runId, listOf(reservation(ParentKey(runId, requestId(1)), 4)))
            }
        }
    }

    @Test
    fun completeStagingCleanupResolvesDetachesDeletesAndPersistsAcrossReopen() {
        val name = databaseName()
        val stagingId: Long
        val remediationId: Long
        val secondRemediationId: Long
        val cleanupEvidence = "quarantined staging was removed safely"
        try {
            SqliteAnkiMutationStore(context, name, enforceBackgroundThread = false).use { store ->
                val staging =
                    store.recordStaging(
                        StagingDraft(
                            runId(15),
                            requestId(1),
                            assetId(1),
                            "run/asset.bin",
                            "content://com.ankiminer.files/run/asset.bin",
                            "com.ichi2.anki",
                            3,
                            SHA,
                        ),
                    )
                stagingId = staging.id
                val quarantined = store.transitionStaging(staging.id, StagingState.QUARANTINED, "cleanup failed")
                remediationId =
                    store.addRemediation(
                        RemediationDraft(
                            stagingId = quarantined.id,
                            kind = RemediationKind.STAGING_QUARANTINED,
                            summary = "Manual staging cleanup required",
                        ),
                    ).id
                secondRemediationId =
                    store.addRemediation(
                        RemediationDraft(
                            stagingId = quarantined.id,
                            kind = RemediationKind.CAPACITY_EXHAUSTED,
                            summary = "Second attached remediation",
                        ),
                    ).id
            }
            SqliteAnkiMutationStore(context, name, enforceBackgroundThread = false).use { store ->
                assertEquals(stagingId, store.stagingForRecovery().single().id)
                assertEquals(setOf(remediationId, secondRemediationId), store.openRemediations().map { it.id }.toSet())
                store.completeStagingCleanup(stagingId, cleanupEvidence)
                assertTrue(store.stagingForRecovery().isEmpty())
                assertTrue(store.openRemediations().isEmpty())
            }
            SqliteAnkiMutationStore(context, name, enforceBackgroundThread = false).use { reopened ->
                reopened.writableDatabase.rawQuery(
                    """SELECT state, staging_id, staging_subject_id, compact_evidence,
                              created_at_ms, updated_at_ms
                       FROM remediations WHERE id = ?""".trimIndent(),
                    arrayOf(remediationId.toString()),
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(RemediationState.RESOLVED.name, cursor.getString(0))
                    assertTrue(cursor.isNull(1))
                    assertEquals(stagingId, cursor.getLong(2))
                    assertEquals(cleanupEvidence, cursor.getString(3))
                    assertTrue(cursor.getLong(5) > cursor.getLong(4))
                }
                reopened.writableDatabase.rawQuery(
                    """SELECT count(*) FROM remediations
                       WHERE id IN (?, ?) AND state = 'RESOLVED' AND staging_id IS NULL AND
                             staging_subject_id = ? AND compact_evidence = ?""".trimIndent(),
                    arrayOf(
                        remediationId.toString(),
                        secondRemediationId.toString(),
                        stagingId.toString(),
                        cleanupEvidence,
                    ),
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(2L, cursor.getLong(0))
                }
                assertEquals(0L, count(reopened.writableDatabase, "staging_artifacts"))
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun completeStagingCleanupFinalizesAnInterruptedAlreadyCleanedRow() {
        val name = databaseName()
        val stagingId: Long
        val remediationId: Long
        val cleanupEvidence = "recovered interrupted staging cleanup"
        try {
            SqliteAnkiMutationStore(context, name, enforceBackgroundThread = false).use { store ->
                val staging =
                    store.recordStaging(
                        StagingDraft(
                            runId(16),
                            requestId(1),
                            assetId(1),
                            "run/interrupted.bin",
                            "content://com.ankiminer.files/run/interrupted.bin",
                            "com.ichi2.anki",
                            3,
                            SHA,
                        ),
                    )
                stagingId = staging.id
                val quarantined = store.transitionStaging(staging.id, StagingState.QUARANTINED, "cleanup failed")
                remediationId =
                    store.addRemediation(
                        RemediationDraft(
                            stagingId = quarantined.id,
                            kind = RemediationKind.STAGING_QUARANTINED,
                            summary = "Manual staging cleanup required",
                        ),
                    ).id
                store.transitionStaging(staging.id, StagingState.CLEANED, "artifact deleted before journal finalization")
            }
            SqliteAnkiMutationStore(context, name, enforceBackgroundThread = false).use { recovered ->
                assertEquals(StagingState.CLEANED, recovered.stagingForRecovery().single().state)
                assertEquals(remediationId, recovered.openRemediations().single().id)
                recovered.completeStagingCleanup(stagingId, cleanupEvidence)
                assertTrue(recovered.stagingForRecovery().isEmpty())
                assertTrue(recovered.openRemediations().isEmpty())
            }
            SqliteAnkiMutationStore(context, name, enforceBackgroundThread = false).use { reopened ->
                reopened.writableDatabase.rawQuery(
                    """SELECT state, staging_id, staging_subject_id, compact_evidence
                       FROM remediations WHERE id = ?""".trimIndent(),
                    arrayOf(remediationId.toString()),
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(RemediationState.RESOLVED.name, cursor.getString(0))
                    assertTrue(cursor.isNull(1))
                    assertEquals(stagingId, cursor.getLong(2))
                    assertEquals(cleanupEvidence, cursor.getString(3))
                }
                assertEquals(0L, count(reopened.writableDatabase, "staging_artifacts"))
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun mainThreadAccessIsRejectedBeforeOpeningDatabase() {
        val name = databaseName()
        try {
            SqliteAnkiMutationStore(context, name, enforceBackgroundThread = true).use { store ->
                val thrown = AtomicReference<Throwable?>()
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    try {
                        store.parent(ParentKey(runId(16), requestId(1)))
                    } catch (error: Throwable) {
                        thrown.set(error)
                    }
                }
                assertTrue(thrown.get() is IllegalStateException)
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    private fun readyVerify(store: SqliteAnkiMutationStore, run: Int, requestNumber: Int): JournalRequest {
        val request = verifyRequest(run, requestNumber)
        val target = targetSnapshot()
        store.createParent(request)
        store.beginParent(request.key)
        store.storeTargetSnapshot(request.key, target)
        store.markResultReady(request, JournalResponse.VerifySuccess(request.key, target))
        return request
    }

    private fun prepareCommittedNote(
        store: SqliteAnkiMutationStore,
        request: JournalRequest,
        providerNoteId: Long,
        mediaBindings: List<DurableMediaBinding> = emptyList(),
    ) {
        store.createParent(request)
        store.beginParent(request.key)
        store.storeTargetSnapshot(request.key, targetSnapshot())
        store.materializeActiveNote(request.key, materialization(request, 0, mediaBindings))
        val child =
            store.prepareChild(
                request.key,
                MutationCommand.InsertNote(0, request.itemIds.single(), 11, "語\u001fword", "mined"),
            )
        store.recordProviderEntry(child.id)
        store.commitNoteReceipt(
            child.id,
            ProviderReceipt.Note(
                providerNoteId,
                "content://com.ichi2.anki.flashcards/notes/$providerNoteId",
            ),
            "validated note receipt",
        )
    }

    private fun advanceToPostcheck(
        store: SqliteAnkiMutationStore,
        request: JournalRequest,
        providerNoteId: Long,
    ) {
        store.advanceNotePhase(request.key, 0, NoteRoutingPhase.NOTE_READBACK_VERIFIED)
        store.advanceNotePhase(request.key, 0, NoteRoutingPhase.CARDS_DISCOVERED)
        val intent =
            store.createRoutingIntents(
                request.key,
                0,
                listOf(RoutingIntentDraft(0, providerNoteId + 1, providerNoteId, 0, 2, 2)),
            ).single()
        store.completeChildlessRoutingIntent(
            intent.id,
            ChildlessRoutingOutcome.Verified(
                RoutingCardObservation(intent.cardId, intent.noteId, intent.ordinal, intent.targetDeckId),
                "card already exactly targeted",
            ),
        )
        store.advanceNotePhase(request.key, 0, NoteRoutingPhase.POSTCHECK_VERIFIED)
    }

    private fun completeAlreadyTargetedNote(
        store: SqliteAnkiMutationStore,
        request: JournalRequest,
        requestIndex: Int,
        providerNoteId: Long,
    ): List<RoutingIntentRecord> {
        store.materializeActiveNote(request.key, materialization(request, requestIndex))
        val noteChild =
            store.prepareChild(
                request.key,
                MutationCommand.InsertNote(
                    requestIndex,
                    request.itemIds[requestIndex],
                    11,
                    "語\u001fword",
                    "mined",
                ),
            )
        store.recordProviderEntry(noteChild.id)
        store.commitNoteReceipt(
            noteChild.id,
            ProviderReceipt.Note(
                providerNoteId,
                "content://com.ichi2.anki.flashcards/notes/$providerNoteId",
            ),
            "validated note receipt $requestIndex",
        )
        store.advanceNotePhase(request.key, requestIndex, NoteRoutingPhase.NOTE_READBACK_VERIFIED)
        store.advanceNotePhase(request.key, requestIndex, NoteRoutingPhase.CARDS_DISCOVERED)
        val cardId = providerNoteId + 100
        val intents =
            store.createRoutingIntents(
                request.key,
                requestIndex,
                listOf(RoutingIntentDraft(requestIndex, cardId, providerNoteId, 0, 2, 2)),
            )
        val intent = intents.single()
        store.completeChildlessRoutingIntent(
            intent.id,
            ChildlessRoutingOutcome.Verified(
                RoutingCardObservation(intent.cardId, intent.noteId, intent.ordinal, intent.targetDeckId),
                "card $cardId already exactly targeted",
            ),
        )
        assertEquals(NoteRoutingPhase.ROUTED, store.parent(request.key)?.routingPhase)
        store.advanceNotePhase(request.key, requestIndex, NoteRoutingPhase.POSTCHECK_VERIFIED)
        store.completeVerifiedNote(
            request.key,
            requestIndex,
            providerNoteId,
            "exact note $providerNoteId postcondition",
        )
        return intents
    }

    private fun prepareMedia(
        store: SqliteAnkiMutationStore,
        run: Int,
        requestNumber: Int,
        asset: Int,
    ): MediaPromotion {
        val request = mediaRequest(run, requestNumber, listOf(asset))
        val parent = store.createParent(request)
        store.beginParent(request.key)
        store.acquireMediaLease(request.key.runId)
        val reservation = store.reserveMedia(request.key.runId, listOf(reservation(request.key, asset))).single()
        return store.promoteReservation(
            request.key,
            reservation.id,
            MutationCommand.StoreMedia(
                0,
                request.itemIds.single(),
                "content://com.ankiminer.files/audio-$asset.mp3",
                "audio_$asset",
            ),
        ).also { assertEquals(parent.id, it.child.parentId) }
    }

    private fun readyStoredMedia(
        store: SqliteAnkiMutationStore,
        run: Int,
        requestNumber: Int,
        asset: Int,
    ): Pair<JournalRequest, MediaPromotion> {
        val request = mediaRequest(run, requestNumber, listOf(asset))
        val media = prepareMedia(store, run, requestNumber, asset)
        store.recordProviderEntry(media.child.id)
        store.commitMediaReceipt(
            media.child.id,
            media.claim.id,
            ProviderReceipt.Media("audio_${asset}.mp3", "file:///audio_${asset}.mp3"),
            "validated stored media receipt",
        )
        store.markResultReady(
            request,
            JournalResponse.StoreMedia(request.key, store.alignedResults(request.key), error = null),
        )
        return request to media
    }

    private fun verifyRequest(run: Int, request: Int): JournalRequest =
        JournalRequest.from(
            VerifyTargetRequest(
                runId(run),
                requestId(request),
                "Mining",
                "Mining Model",
                listOf("Expression", "Meaning"),
            ),
        )

    private fun mediaRequest(run: Int, request: Int, assets: List<Int>): JournalRequest =
        JournalRequest.from(
            StoreMediaRequest(
                runId(run),
                requestId(request),
                assets.map { index ->
                    MediaAsset(
                        assetId(index),
                        "/tmp/audio-$index.mp3",
                        "audio_$index",
                        "audio $index.mp3",
                        com.ankiminer.android.anki.protocol.MediaPurpose.CARD,
                        com.ankiminer.android.anki.protocol.MediaKind.AUDIO,
                        3,
                        SHA,
                    )
                },
            ),
        )

    private fun createRequest(
        run: Int,
        request: Int,
        notes: Int,
        mediaBinding: MediaBinding? = null,
    ): JournalRequest =
        JournalRequest.from(
            CreateNotesRequest(
                runId(run),
                requestId(request),
                "Mining",
                "Mining Model",
                "Expression",
                baselineId(run),
                CollectionCreateDuplicateScope,
                (1..notes).map { index ->
                    CreateNote(
                        noteId(index),
                        linkedMapOf("Expression" to "語", "Meaning" to "word"),
                        listOf("mined"),
                        CreateDuplicateCandidate("key-$index", "語", index - 1),
                        if (index == 1 && mediaBinding != null) listOf(mediaBinding) else emptyList(),
                    )
                },
            ),
        )

    private fun materialization(
        request: JournalRequest,
        index: Int,
        mediaBindings: List<DurableMediaBinding> = emptyList(),
    ) =
        ActiveNoteMaterialization(
            requestIndex = index,
            clientNoteId = request.itemIds[index],
            orderedFields = listOf(OrderedNoteField("Expression", "語"), OrderedNoteField("Meaning", "word")),
            joinedFields = "語\u001fword",
            normalizedTags = listOf("mined"),
            providerTagsWire = "mined",
            duplicateDecision = DurableDuplicateDecision("key-${index + 1}", "語", index, false),
            mediaBindings = mediaBindings,
        )

    private fun targetSnapshot(cardCount: Int = 1) =
        DurableTargetSnapshot(
            DurableDeckSnapshot(2, "Mining", dynamic = false),
            DurableModelSnapshot(
                id = 11,
                name = "Mining Model",
                type = 0,
                fieldNames = listOf("Expression", "Meaning"),
                cardCount = cardCount,
                sortFieldIndex = 0,
                effectiveDefaultDeckId = 1,
                css = ".card { color: black; }",
                latexPre = null,
                latexPost = "",
                templates =
                    List(cardCount) { ordinal ->
                        DurableTemplateSnapshot(
                            11,
                            ordinal,
                            "Card ${ordinal + 1}",
                            "{{Expression}}",
                            "{{FrontSide}}<hr>{{Meaning}}",
                            null,
                            "",
                        )
                    },
            ),
        )

    private fun reservation(key: ParentKey, asset: Int) =
        MediaReservationDraft(
            key.requestId,
            assetId(asset),
            "audio $asset.mp3",
            "audio_$asset",
            SHA,
            MediaPurpose.CARD,
            MediaKind.AUDIO,
        )

    private inline fun withStore(
        limits: JournalCapacityLimits = JournalCapacityLimits.PRODUCTION,
        block: (SqliteAnkiMutationStore) -> Unit,
    ) {
        val name = databaseName()
        try {
            SqliteAnkiMutationStore(
                context,
                name,
                enforceBackgroundThread = false,
                capacityLimits = limits,
            ).use(block)
        } finally {
            context.deleteDatabase(name)
        }
    }

    private fun count(db: SQLiteDatabase, table: String): Long =
        db.rawQuery("SELECT count(*) FROM $table", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun childState(db: SQLiteDatabase, childId: Long): ChildState =
        db.rawQuery(
            "SELECT state FROM mutation_children WHERE id = ?",
            arrayOf(childId.toString()),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            ChildState.valueOf(cursor.getString(0))
        }

    private fun auditCount(
        db: SQLiteDatabase,
        table: String,
        column: String,
        where: String = "1",
    ): Long =
        db.rawQuery("SELECT COALESCE(sum($column), 0) FROM $table WHERE $where", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun claimState(db: SQLiteDatabase, claimId: Long): String =
        db.rawQuery("SELECT state FROM media_claims WHERE id = ?", arrayOf(claimId.toString())).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun pragma(db: SQLiteDatabase, name: String): String =
        db.rawQuery("PRAGMA $name", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun databaseName() = "journal-${System.nanoTime()}.db"

    private enum class ReceiptCrashBoundary {
        BEFORE_ENTRY,
        AFTER_ENTRY,
        BEFORE_RECEIPT,
        AFTER_RECEIPT,
        ;

        fun crashPoint(
            beforeReceipt: JournalCrashPoint,
            afterReceipt: JournalCrashPoint,
        ): JournalCrashPoint =
            when (this) {
                BEFORE_ENTRY -> JournalCrashPoint.BEFORE_PROVIDER_ENTRY_RECORDED
                AFTER_ENTRY -> JournalCrashPoint.AFTER_PROVIDER_ENTRY_RECORDED
                BEFORE_RECEIPT -> beforeReceipt
                AFTER_RECEIPT -> afterReceipt
            }
    }

    private fun expectCrashAtBoundary(
        boundary: ReceiptCrashBoundary,
        providerEntry: () -> Unit,
        providerReceipt: () -> Unit,
    ) {
        when (boundary) {
            ReceiptCrashBoundary.BEFORE_ENTRY,
            ReceiptCrashBoundary.AFTER_ENTRY,
            -> assertThrows(SimulatedCrash::class.java) { providerEntry() }
            ReceiptCrashBoundary.BEFORE_RECEIPT,
            ReceiptCrashBoundary.AFTER_RECEIPT,
            -> {
                providerEntry()
                assertThrows(SimulatedCrash::class.java) { providerReceipt() }
            }
        }
    }

    private fun assertRejectedNonCardReissue(
        store: SqliteAnkiMutationStore,
        childId: Long,
    ) {
        assertThrows(RuntimeException::class.java) {
            store.recordProviderEntry(childId, recoveryReissue = true)
        }
    }

    private class SimulatedCrash : RuntimeException()

    private class CrashAt(private val target: JournalCrashPoint) : JournalCrashHooks {
        override fun hit(point: JournalCrashPoint) {
            if (point == target) throw SimulatedCrash()
        }
    }

    companion object {
        private const val SHA = "0000000000000000000000000000000000000000000000000000000000000000"

        private fun runId(value: Int) = "run_${value.toString(16).padStart(32, '0')}"
        private fun requestId(value: Int) = "anki_${value.toString(16).padStart(32, '0')}"
        private fun assetId(value: Int) = "asset_${value.toString(16).padStart(32, '0')}"
        private fun noteId(value: Int) = "note_${value.toString(16).padStart(32, '0')}"
        private fun baselineId(value: Int) = "baseline_${value.toString(16).padStart(32, '0')}"
    }
}
