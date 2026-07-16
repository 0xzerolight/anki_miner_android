package com.ankiminer.android.anki.journal

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build
import android.os.Looper
import androidx.annotation.RequiresApi
import com.ankiminer.android.anki.protocol.AnkiRequestDigest
import java.util.concurrent.atomic.AtomicBoolean

/** SQLite implementation; every public method is synchronous and must run off the main thread. */
internal class SqliteAnkiMutationStore(
    context: Context,
    databaseName: String = DEFAULT_DATABASE_NAME,
    private val clock: JournalClock = SystemJournalClock,
    private val crashHooks: JournalCrashHooks = NoOpJournalCrashHooks,
    private val enforceBackgroundThread: Boolean = true,
    private val capacityLimits: JournalCapacityLimits = JournalCapacityLimits.PRODUCTION,
) : SQLiteOpenHelper(context.applicationContext, databaseName, null, JournalSchema.VERSION), AnkiMutationStore {
    private val closed = AtomicBoolean(false)

    init {
        require(databaseName.isNotBlank()) { "databaseName must not be blank" }
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
        configureFullSynchronous(db)
    }

    override fun onCreate(db: SQLiteDatabase) = JournalSchema.create(db)

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw JournalCorruptionException(
            "Unsupported pre-release journal schema $oldVersion; expected fresh schema $newVersion",
        )
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw JournalCorruptionException("Journal schema downgrade $oldVersion -> $newVersion is unsupported")
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        // WAL initialization can reapply Android's default after onConfigure. Reassert it once the pool is open.
        configureFullSynchronous(db)
        requirePragma(db, "foreign_keys", "1")
        requirePragma(db, "synchronous", "2")
        if (!pragmaValue(db, "journal_mode").equals("wal", ignoreCase = true)) {
            throw JournalCorruptionException("Journal database did not open in WAL mode")
        }
        if (pragmaValue(db, "quick_check") != "ok") {
            throw JournalCorruptionException("Journal quick_check failed")
        }
        db.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
            if (cursor.moveToFirst()) throw JournalCorruptionException("Journal foreign-key check failed")
        }
        requireSchemaDefinitions(db)
        val inconsistentTargets =
            scalarLong(
                db,
                """SELECT count(*) FROM parents p WHERE p.has_target_expectation !=
                   EXISTS(SELECT 1 FROM target_expectations t WHERE t.parent_id = p.id)""".trimIndent(),
            )
        if (inconsistentTargets != 0L) throw JournalCorruptionException("Target expectation flag differs from normalized rows")
        val malformedRequestItems =
            scalarLong(
                db,
                """SELECT count(*) FROM parents p WHERE p.state NOT IN ('RESPONSE_ACKNOWLEDGED', 'ABANDONED') AND
                   p.request_item_count !=
                   (SELECT count(*) FROM parent_request_items i WHERE i.parent_id = p.id)""".trimIndent(),
            )
        if (malformedRequestItems != 0L) throw JournalCorruptionException("Parent request-item count differs from normalized rows")
        val malformedModels =
            scalarLong(
                db,
                """SELECT count(*) FROM target_expectations t WHERE
                   t.field_count != (SELECT count(*) FROM target_expectation_fields f WHERE f.parent_id = t.parent_id) OR
                   t.card_count != (SELECT count(*) FROM target_expectation_templates x WHERE x.parent_id = t.parent_id) OR
                   t.sort_field_index >= (SELECT count(*) FROM target_expectation_fields f WHERE f.parent_id = t.parent_id)""".trimIndent(),
            )
        if (malformedModels != 0L) throw JournalCorruptionException("Target expectation ordering/count is malformed")
        val malformedActiveNotes =
            scalarLong(
                db,
                """SELECT count(*) FROM parents p LEFT JOIN active_notes n ON n.parent_id = p.id WHERE
                   ((p.active_request_index IS NULL) != (n.parent_id IS NULL)) OR
                   (n.parent_id IS NOT NULL AND n.request_index != p.active_request_index) OR
                   (p.routing_phase IS NOT NULL AND p.routing_phase != 'NOTE_PENDING' AND p.active_note_id IS NULL) OR
                   (n.parent_id IS NOT NULL AND NOT EXISTS(
                       SELECT 1 FROM active_note_fields f WHERE f.parent_id = n.parent_id AND f.field_ordinal = 0)) OR
                   (n.parent_id IS NOT NULL AND n.field_count !=
                       (SELECT count(*) FROM active_note_fields f WHERE f.parent_id = n.parent_id)) OR
                   (n.parent_id IS NOT NULL AND n.tag_count !=
                       (SELECT count(*) FROM active_note_tags t WHERE t.parent_id = n.parent_id)) OR
                   (n.parent_id IS NOT NULL AND n.media_binding_count !=
                       (SELECT count(*) FROM active_note_media_bindings b WHERE b.parent_id = n.parent_id))""".trimIndent(),
            )
        if (malformedActiveNotes != 0L) throw JournalCorruptionException("Active-note normalized rows are malformed")
        val malformedFinalAudits =
            scalarLong(
                db,
                """SELECT count(*) FROM parents p WHERE
                   (p.state IN ('RESPONSE_ACKNOWLEDGED', 'ABANDONED') AND NOT EXISTS(
                       SELECT 1 FROM terminal_parent_audit a WHERE a.parent_id = p.id AND
                           a.final_state = p.state AND a.result_count = p.request_item_count AND
                           (SELECT count(*) FROM terminal_result_audit x
                               WHERE x.parent_id = p.id) = a.result_count AND
                           (SELECT COALESCE(sum(o.row_count), 0) FROM terminal_outcome_audit o
                               WHERE o.parent_id = p.id) = a.result_count AND
                           (SELECT COALESCE(sum(r.receipt_count), 0) FROM terminal_receipt_audit r
                               WHERE r.parent_id = p.id) <= a.child_count AND
                           ((p.operation_kind = 'VERIFY_TARGET' AND EXISTS(
                               SELECT 1 FROM terminal_target_audit t
                               JOIN terminal_result_audit x ON x.parent_id = t.parent_id AND x.request_index = 0
                               WHERE t.parent_id = p.id AND t.status_kind = x.status_kind)) OR
                            (p.operation_kind != 'VERIFY_TARGET' AND NOT EXISTS(
                               SELECT 1 FROM terminal_target_audit t WHERE t.parent_id = p.id))) AND
                           ((p.operation_kind = 'VERIFY_TARGET' AND
                               a.terminal_variant IN ('VERIFY_SUCCESS', 'VERIFY_ERROR')) OR
                            (p.operation_kind = 'STORE_MEDIA' AND a.terminal_variant = 'STORE_MEDIA_RESULT') OR
                            (p.operation_kind = 'CREATE_NOTES' AND a.terminal_variant = 'CREATE_NOTES_RESULT')))) OR
                   (p.state NOT IN ('RESPONSE_ACKNOWLEDGED', 'ABANDONED') AND EXISTS(
                       SELECT 1 FROM terminal_parent_audit a WHERE a.parent_id = p.id))""".trimIndent(),
            )
        if (malformedFinalAudits != 0L) throw JournalCorruptionException("Final parent compact audit is malformed")
        val malformedChildren =
            scalarLong(
                db,
                """SELECT count(*) FROM mutation_children c WHERE
                   ((c.operation_kind = 'DECK_CREATE') != EXISTS(SELECT 1 FROM deck_commands x WHERE x.child_id = c.id)) OR
                   ((c.operation_kind = 'MEDIA_INSERT') != EXISTS(SELECT 1 FROM media_commands x WHERE x.child_id = c.id)) OR
                   ((c.operation_kind = 'NOTE_INSERT') != EXISTS(SELECT 1 FROM note_commands x WHERE x.child_id = c.id)) OR
                   ((c.operation_kind = 'CARD_DECK_UPDATE') != EXISTS(SELECT 1 FROM card_commands x WHERE x.child_id = c.id))""".trimIndent(),
            )
        if (malformedChildren != 0L) throw JournalCorruptionException("Mutation child lacks exactly one typed command")
        val finalizedUnremediatedMedia =
            scalarLong(
                db,
                """SELECT count(*) FROM parents p JOIN media_claims c
                   ON c.run_id = p.run_id AND c.request_id = p.request_id
                   WHERE p.operation_kind = 'STORE_MEDIA' AND
                       p.state IN ('RESPONSE_ACKNOWLEDGED', 'ABANDONED') AND
                       c.state IN ('STORED', 'PRESENT_BYTES_VERIFIED') AND NOT EXISTS(
                           SELECT 1 FROM remediations r WHERE r.parent_id = p.id AND
                               r.claim_id = c.id AND r.kind = 'MEDIA_STORED_UNATTACHED' AND
                               r.state = 'OPEN')""".trimIndent(),
            )
        if (finalizedUnremediatedMedia != 0L) {
            throw JournalCorruptionException("Finalized stored media lacks unattached remediation")
        }
        val malformedStoredRemediations =
            scalarLong(
                db,
                """SELECT count(*) FROM remediations r
                   LEFT JOIN parents p ON p.id = r.parent_id
                   LEFT JOIN media_claims c ON c.id = r.claim_id
                   WHERE r.kind = 'MEDIA_STORED_UNATTACHED' AND (
                       p.id IS NULL OR c.id IS NULL OR p.operation_kind != 'STORE_MEDIA' OR
                       c.run_id != p.run_id OR c.request_id != p.request_id OR
                       (r.state = 'OPEN' AND c.state NOT IN ('STORED', 'PRESENT_BYTES_VERIFIED')) OR
                       (r.state = 'RESOLVED' AND c.state NOT IN (
                           'ATTACHED_VERIFIED', 'CLEANED_VERIFIED', 'ACKNOWLEDGED_BY_USER')))""".trimIndent(),
            )
        if (malformedStoredRemediations != 0L) {
            throw JournalCorruptionException("Stored-unattached remediation differs from its claim")
        }
    }

    override fun createParent(request: JournalRequest): ParentRecord {
        crashHooks.hit(JournalCrashPoint.BEFORE_PARENT_CREATE)
        val result =
            write { db ->
                requireRequestSelfConsistent(request)
                parentByKey(db, request.key)?.let { existing ->
                    requireParentMatchesRequest(db, existing, request)
                    return@write existing
                }
                val now = timestamp()
                val parentId =
                    db.insertOrThrow(
                        "parents",
                        null,
                        values(
                            "run_id" to request.key.runId,
                            "request_id" to request.key.requestId,
                            "operation_kind" to request.operation.name,
                            "digest_version" to request.digest.digestVersion,
                            "request_sha256" to request.digest.sha256,
                            "request_item_count" to request.itemIds.size,
                            "state" to ParentState.PREPARED.name,
                            "created_at_ms" to now,
                            "updated_at_ms" to now,
                        ),
                    )
                request.itemIds.forEachIndexed { index, itemId ->
                    db.insertOrThrow(
                        "parent_request_items",
                        null,
                        values("parent_id" to parentId, "request_index" to index, "item_id" to itemId),
                    )
                }
                parentById(db, parentId)
            }
        crashHooks.hit(JournalCrashPoint.AFTER_PARENT_CREATE)
        return result
    }

    override fun parent(key: ParentKey): ParentRecord? = read { parentByKey(it, key) }

    override fun requestItems(key: ParentKey): List<ParentRequestItem> =
        read { db ->
            val parent = parentByKey(db, key) ?: return@read emptyList()
            requestItemsByParent(db, parent.id)
        }

    override fun beginParent(key: ParentKey): ParentRecord =
        write { db ->
            val parent = requireParent(db, key)
            when (parent.state) {
                ParentState.PREPARED -> {
                    updateParentState(db, parent, ParentState.RUNNING)
                    parentById(db, parent.id)
                }
                ParentState.RUNNING -> parent
                else -> throw JournalInvariantViolation("Parent ${key.requestId} cannot begin from ${parent.state}")
            }
        }

    override fun storeTargetExpectation(key: ParentKey, expectation: DurableTargetExpectation): ParentRecord =
        write { db ->
            val parent = requireMutableParent(db, key)
            targetExpectationByParent(db, parent.id)?.let { existing ->
                if (existing != expectation) throw JournalInvariantViolation("Target expectation is immutable")
                return@write parent
            }
            insertTargetExpectation(db, parent, expectation)
            parentById(db, parent.id)
        }

    override fun targetExpectation(key: ParentKey): DurableTargetExpectation? =
        read { db -> parentByKey(db, key)?.let { targetExpectationByParent(db, it.id) } }

    override fun storeTargetSnapshot(key: ParentKey, snapshot: DurableTargetSnapshot): ParentRecord =
        write { db ->
            val parent = requireMutableParent(db, key)
            storeTargetSnapshotDb(db, parent, snapshot)
        }

    override fun targetSnapshot(key: ParentKey): DurableTargetSnapshot? =
        read { db -> parentByKey(db, key)?.let { targetSnapshotByParent(db, it.id) } }

    override fun materializeActiveNote(key: ParentKey, note: ActiveNoteMaterialization): ParentRecord {
        crashHooks.hit(JournalCrashPoint.BEFORE_ACTIVE_NOTE_MATERIALIZATION)
        val result =
            write { db ->
                val parent = requireMutableParent(db, key, ParentOperation.CREATE_NOTES)
                if (targetSnapshotByParent(db, parent.id) == null) {
                    throw JournalInvariantViolation("Active-note materialization requires an exact durable target")
                }
                val item = requestItem(db, parent.id, note.requestIndex)
                if (item.itemId != note.clientNoteId) throw JournalInvariantViolation("Active note identity is not request-aligned")
                activeNoteByParent(db, parent.id)?.let { existing ->
                    if (existing.materialization != note) throw JournalInvariantViolation("A different active note is already durable")
                    return@write parent
                }
                if (parent.activeRequestIndex != null || parent.routingPhase != null || parent.activeNoteId != null) {
                    throw JournalCorruptionException("Parent active-note scalars exist without normalized materialization")
                }
                val now = timestamp()
                db.insertOrThrow(
                    "active_notes",
                    null,
                    values(
                        "parent_id" to parent.id,
                        "request_index" to note.requestIndex,
                        "client_note_id" to note.clientNoteId,
                        "item_sha256" to note.itemSha256,
                        "joined_fields" to note.joinedFields,
                        "provider_tags_wire" to note.providerTagsWire,
                        "duplicate_key" to note.duplicateDecision.key,
                        "duplicate_first_field" to note.duplicateDecision.firstField,
                        "duplicate_occurrence" to note.duplicateDecision.occurrence,
                        "is_duplicate" to bool(note.duplicateDecision.duplicate),
                        "field_count" to note.orderedFields.size,
                        "tag_count" to note.normalizedTags.size,
                        "media_binding_count" to note.mediaBindings.size,
                        "created_at_ms" to now,
                        "updated_at_ms" to now,
                    ),
                )
                note.orderedFields.forEachIndexed { ordinal, field ->
                    db.insertOrThrow(
                        "active_note_fields",
                        null,
                        values(
                            "parent_id" to parent.id,
                            "field_ordinal" to ordinal,
                            "field_name" to field.name,
                            "field_value" to field.value,
                        ),
                    )
                }
                note.normalizedTags.forEachIndexed { ordinal, tag ->
                    db.insertOrThrow(
                        "active_note_tags",
                        null,
                        values("parent_id" to parent.id, "tag_ordinal" to ordinal, "tag" to tag),
                    )
                }
                note.mediaBindings.forEachIndexed { ordinal, binding ->
                    val claim = claimById(db, binding.claimId)
                    if (
                        claim.runId != key.runId || claim.assetId != binding.assetId ||
                        claim.actualFilename != binding.actualFilename || !claim.state.isUnresolved
                    ) {
                        throw JournalInvariantViolation("Active note media binding differs from durable claim")
                    }
                    db.insertOrThrow(
                        "active_note_media_bindings",
                        null,
                        values(
                            "parent_id" to parent.id,
                            "binding_ordinal" to ordinal,
                            "asset_id" to binding.assetId,
                            "actual_filename" to binding.actualFilename,
                            "claim_id" to binding.claimId,
                        ),
                    )
                }
                db.updateOrThrow(
                    "parents",
                    values(
                        "active_request_index" to note.requestIndex,
                        "routing_phase" to NoteRoutingPhase.NOTE_PENDING.name,
                        "updated_at_ms" to nextTimestamp(parent.updatedAtMs),
                    ),
                    "id = ?",
                    arrayOf(parent.id.toString()),
                )
                parentById(db, parent.id)
            }
        crashHooks.hit(JournalCrashPoint.AFTER_ACTIVE_NOTE_MATERIALIZATION)
        return result
    }

    override fun activeNote(key: ParentKey): ActiveNoteRecord? =
        read { db -> parentByKey(db, key)?.let { activeNoteByParent(db, it.id) } }

    override fun advanceNotePhase(key: ParentKey, requestIndex: Int, phase: NoteRoutingPhase): ParentRecord =
        write { db ->
            val parent = requireMutableParent(db, key, ParentOperation.CREATE_NOTES)
            if (parent.activeRequestIndex != requestIndex) throw JournalInvariantViolation("Wrong active note index")
            if (phase == NoteRoutingPhase.ROUTED) {
                throw JournalInvariantViolation("ROUTED is derived only from a complete verified routing-intent set")
            }
            val current = parent.routingPhase ?: throw JournalInvariantViolation("No active note phase")
            JournalStateMachine.requireNotePhaseTransition(current, phase)
            if (phase == NoteRoutingPhase.POSTCHECK_VERIFIED) {
                requireCompleteRoutingProof(db, parent)
            }
            db.updateOrThrow(
                "parents",
                values("routing_phase" to phase.name, "updated_at_ms" to nextTimestamp(parent.updatedAtMs)),
                "id = ?",
                arrayOf(parent.id.toString()),
            )
            parentById(db, parent.id)
        }

    override fun prepareChild(key: ParentKey, command: MutationCommand, mediaClaimId: Long?): ChildRecord {
        crashHooks.hit(JournalCrashPoint.BEFORE_CHILD_PREPARED)
        val child = write { db -> prepareChildInTransaction(db, requireMutableParent(db, key), command, mediaClaimId) }
        crashHooks.hit(JournalCrashPoint.AFTER_CHILD_PREPARED)
        return child
    }

    override fun recordProviderEntry(childId: Long, recoveryReissue: Boolean): ChildRecord {
        crashHooks.hit(JournalCrashPoint.BEFORE_PROVIDER_ENTRY_RECORDED)
        val child =
            write { db ->
                val current = childById(db, childId)
                if (current.state != ChildState.PREPARED) throw JournalInvariantViolation("Provider entry requires PREPARED child")
                val number = current.attemptCount + 1
                if (recoveryReissue != (number == 2)) {
                    throw JournalInvariantViolation("Only the second card attempt is a recovery reissue")
                }
                if (recoveryReissue && current.receipt != null) {
                    throw JournalInvariantViolation("A receipt-bearing card command must never be reissued")
                }
                db.insertOrThrow(
                    "provider_attempts",
                    null,
                    values(
                        "child_id" to childId,
                        "attempt_number" to number,
                        "recovery_reissue" to bool(recoveryReissue),
                        "entered_at_ms" to timestamp(),
                    ),
                )
                childById(db, childId)
            }
        crashHooks.hit(JournalCrashPoint.AFTER_PROVIDER_ENTRY_RECORDED)
        return child
    }

    override fun recordDeckReceipt(childId: Long, receipt: ProviderReceipt.Deck): ChildRecord {
        crashHooks.hit(JournalCrashPoint.BEFORE_DECK_RECEIPT_RECORDED)
        val child =
            write { db ->
                val current = childById(db, childId)
                requireReceiptTarget(current, receipt, allowExisting = false)
                insertDeckReceipt(db, childId, receipt)
                childById(db, childId)
            }
        crashHooks.hit(JournalCrashPoint.AFTER_DECK_RECEIPT_RECORDED)
        return child
    }

    override fun completeVerifiedDeck(
        childId: Long,
        snapshot: DurableTargetSnapshot,
        compactEvidence: String,
    ): ParentRecord {
        requireCompactEvidence(compactEvidence)
        crashHooks.hit(JournalCrashPoint.BEFORE_DECK_VERIFICATION_TRANSACTION)
        val parent =
            write { db ->
                val child = childById(db, childId)
                val command = child.command as? MutationCommand.CreateDeck
                    ?: throw JournalInvariantViolation("Verified deck outcome has a non-deck command")
                val expectation = targetExpectationByParent(db, child.parentId)
                    ?: throw JournalInvariantViolation("Verified deck outcome lost its target expectation")
                if (command.deckName != expectation.expectedDeckName || snapshot.expectation != expectation) {
                    throw JournalInvariantViolation("Verified deck differs from its frozen command or model")
                }
                val receipt = child.receipt
                if (receipt != null && (receipt !is ProviderReceipt.Deck || receipt.deckId != snapshot.deck.id)) {
                    throw JournalInvariantViolation("Deck receipt conflicts with the exact reconciled deck")
                }
                JournalStateMachine.requireChildCompletion(child, ChildState.POSTCONDITION_VERIFIED)
                val currentParent = parentById(db, child.parentId)
                if (
                    currentParent.state !in setOf(ParentState.PREPARED, ParentState.RUNNING) ||
                        currentParent.operation != ParentOperation.VERIFY_TARGET
                ) {
                    throw JournalInvariantViolation("Verified deck parent is not mutable verifyTarget work")
                }
                storeTargetSnapshotDb(db, currentParent, snapshot)
                updateChildTerminal(db, child, ChildState.POSTCONDITION_VERIFIED, compactEvidence)
                parentById(db, child.parentId)
            }
        crashHooks.hit(JournalCrashPoint.AFTER_DECK_VERIFICATION_TRANSACTION)
        return parent
    }

    override fun completeUncertainDeck(
        childId: Long,
        compactEvidence: String,
    ): ParentRecord {
        requireCompactEvidence(compactEvidence)
        crashHooks.hit(JournalCrashPoint.BEFORE_DECK_UNCERTAINTY_TRANSACTION)
        val parent =
            write { db ->
                val child = childById(db, childId)
                if (child.command !is MutationCommand.CreateDeck) {
                    throw JournalInvariantViolation("Uncertain deck outcome has a non-deck command")
                }
                if (targetSnapshotByParent(db, child.parentId) != null) {
                    throw JournalInvariantViolation("An exact target cannot also be deck-commit uncertain")
                }
                JournalStateMachine.requireChildCompletion(child, ChildState.COMMIT_UNCERTAIN)
                updateChildTerminal(db, child, ChildState.COMMIT_UNCERTAIN, compactEvidence)
                ensureRemediationDb(
                    db,
                    RemediationDraft(
                        parentId = child.parentId,
                        kind = RemediationKind.DECK_COMMIT_UNCERTAIN,
                        summary = "Deck creation could not be conclusively reconciled",
                        compactEvidence = compactEvidence,
                    ),
                )
                parentById(db, child.parentId)
            }
        crashHooks.hit(JournalCrashPoint.AFTER_DECK_UNCERTAINTY_TRANSACTION)
        return parent
    }

    override fun commitMediaReceipt(
        childId: Long,
        claimId: Long,
        receipt: ProviderReceipt.Media,
        compactEvidence: String,
    ): ParentRecord {
        requireCompactEvidence(compactEvidence)
        crashHooks.hit(JournalCrashPoint.BEFORE_MEDIA_RECEIPT_TRANSACTION)
        val parent =
            write { db ->
                val child = childById(db, childId)
                val insertReceipt = requireReceiptTarget(child, receipt, allowExisting = true)
                val command = child.command as? MutationCommand.StoreMedia
                    ?: throw JournalInvariantViolation("Media receipt has non-media command")
                if (child.mediaClaimId != claimId) throw JournalInvariantViolation("Media receipt claim mismatch")
                val claim = claimById(db, claimId)
                if (claim.state != MediaClaimState.PENDING || claim.assetId != command.assetId) {
                    throw JournalInvariantViolation("Media claim is not pending for this command")
                }
                val returnedLock =
                    MediaNamespaceLock(
                        MediaNamespaceOwner(claim.runId, claim.assetId),
                        receipt.actualFilename,
                        providerPrefix(claim.preferredName),
                    )
                requireNoClaimNamespaceCollisions(db, listOf(returnedLock))
                MediaNamespaceValidator.requireDisjoint(
                    boundedNamespaceLocks(db).filterNot { it.owner == returnedLock.owner } + returnedLock,
                )
                if (insertReceipt) insertMediaReceipt(db, childId, receipt)
                updateClaim(db, claim, MediaClaimState.STORED, receipt.actualFilename, compactEvidence)
                val parentRecord = parentById(db, child.parentId)
                appendResultDb(
                    db,
                    parentRecord,
                    AlignedResult.MediaStored(command.requestIndex, command.assetId, receipt.actualFilename, compactEvidence),
                )
                updateChildTerminal(db, child, ChildState.COMMIT_KNOWN, compactEvidence)
                parentById(db, child.parentId)
            }
        crashHooks.hit(JournalCrashPoint.AFTER_MEDIA_RECEIPT_TRANSACTION)
        return parent
    }

    override fun commitNoteReceipt(
        childId: Long,
        receipt: ProviderReceipt.Note,
        compactEvidence: String,
    ): ParentRecord {
        requireCompactEvidence(compactEvidence)
        crashHooks.hit(JournalCrashPoint.BEFORE_NOTE_RECEIPT_TRANSACTION)
        val parent =
            write { db ->
                val child = childById(db, childId)
                val insertReceipt = requireReceiptTarget(child, receipt, allowExisting = true)
                val command = child.command as? MutationCommand.InsertNote
                    ?: throw JournalInvariantViolation("Note receipt has non-note command")
                val parentRecord = parentById(db, child.parentId)
                if (
                    parentRecord.activeRequestIndex != command.requestIndex ||
                    parentRecord.routingPhase != NoteRoutingPhase.NOTE_PENDING
                ) {
                    throw JournalInvariantViolation("Note receipt does not match active materialization")
                }
                if (insertReceipt) insertNoteReceipt(db, childId, receipt)
                db.updateOrThrow(
                    "parents",
                    values(
                        "active_note_id" to receipt.noteId,
                        "routing_phase" to NoteRoutingPhase.NOTE_COMMIT_KNOWN.name,
                        "updated_at_ms" to nextTimestamp(parentRecord.updatedAtMs),
                    ),
                    "id = ?",
                    arrayOf(parentRecord.id.toString()),
                )
                updateChildTerminal(db, child, ChildState.COMMIT_KNOWN, compactEvidence)
                parentById(db, parentRecord.id)
            }
        crashHooks.hit(JournalCrashPoint.AFTER_NOTE_RECEIPT_TRANSACTION)
        return parent
    }

    override fun recordCardReceipt(childId: Long): ChildRecord {
        crashHooks.hit(JournalCrashPoint.BEFORE_CARD_RECEIPT_RECORDED)
        val child =
            write { db ->
                val current = childById(db, childId)
                requireReceiptTarget(current, ProviderReceipt.CardAffectedOne, allowExisting = false)
                db.insertOrThrow("card_receipts", null, values("child_id" to childId, "affected_count" to 1))
                childById(db, childId)
            }
        crashHooks.hit(JournalCrashPoint.AFTER_CARD_RECEIPT_RECORDED)
        return child
    }

    override fun completeChild(childId: Long, outcome: ChildState, compactEvidence: String): ChildRecord {
        requireCompactEvidence(compactEvidence)
        crashHooks.hit(JournalCrashPoint.BEFORE_TERMINAL_CHILD_COMMIT)
        val child =
            write { db ->
                val current = childById(db, childId)
                when (current.command.operation) {
                    ChildOperation.MEDIA_INSERT ->
                        throw JournalInvariantViolation("Media outcomes require the atomic media completion APIs")
                    ChildOperation.CARD_DECK_UPDATE ->
                        throw JournalInvariantViolation("Card outcomes require atomic routing-intent completion")
                    ChildOperation.NOTE_INSERT -> if (outcome !in setOf(ChildState.PROVEN_NOT_COMMITTED, ChildState.COMMIT_UNCERTAIN)) {
                        throw JournalInvariantViolation("Note insert without a receipt can only be proven absent or uncertain")
                    }
                    ChildOperation.DECK_CREATE ->
                        when (outcome) {
                            ChildState.COMMIT_KNOWN ->
                                throw JournalInvariantViolation("A deck receipt alone never proves the deck postcondition")
                            ChildState.POSTCONDITION_FAILED ->
                                throw JournalInvariantViolation("An entered deck without exact target proof is commit-uncertain")
                            ChildState.POSTCONDITION_VERIFIED -> if (targetSnapshotByParent(db, current.parentId) == null) {
                                throw JournalInvariantViolation("Verified deck postcondition requires an exact durable target snapshot")
                            }
                            else -> Unit
                        }
                }
                JournalStateMachine.requireChildCompletion(current, outcome)
                updateChildTerminal(db, current, outcome, compactEvidence)
                when {
                    current.command.operation == ChildOperation.DECK_CREATE && outcome == ChildState.COMMIT_UNCERTAIN ->
                        ensureRemediationDb(
                            db,
                            RemediationDraft(
                                parentId = current.parentId,
                                kind = RemediationKind.DECK_COMMIT_UNCERTAIN,
                                summary = "Deck creation could not be conclusively reconciled",
                                compactEvidence = compactEvidence,
                            ),
                        )
                    current.command.operation == ChildOperation.NOTE_INSERT && outcome == ChildState.COMMIT_UNCERTAIN ->
                        ensureRemediationDb(
                            db,
                            RemediationDraft(
                                parentId = current.parentId,
                                kind = RemediationKind.NOTE_COMMIT_UNCERTAIN,
                                summary = "Note provider commit could not be conclusively reconciled",
                                compactEvidence = compactEvidence,
                            ),
                        )
                }
                childById(db, childId)
            }
        crashHooks.hit(JournalCrashPoint.AFTER_TERMINAL_CHILD_COMMIT)
        return child
    }

    override fun completeMediaFailure(
        childId: Long,
        claimId: Long,
        childOutcome: ChildState,
        claimState: MediaClaimState,
        result: AlignedResult,
        compactEvidence: String,
    ): ParentRecord {
        requireCompactEvidence(compactEvidence)
        return write { db ->
            val child = childById(db, childId)
            val command = child.command as? MutationCommand.StoreMedia
                ?: throw JournalInvariantViolation("Media failure has non-media child")
            if (child.mediaClaimId != claimId || result.requestIndex != command.requestIndex || result.itemId != command.assetId) {
                throw JournalInvariantViolation("Media failure boundary identity mismatch")
            }
            if (result !is AlignedResult.MediaFailed && result !is AlignedResult.MediaUncertain) {
                if (result !is AlignedResult.MediaNotAttempted) {
                    throw JournalInvariantViolation("Media failure boundary requires failed, notAttempted, or uncertain row")
                }
            }
            JournalStateMachine.requireChildCompletion(child, childOutcome)
            val claim = claimById(db, claimId)
            JournalStateMachine.requireClaimTransition(claim.state, claimState)
            when (childOutcome) {
                ChildState.PROVEN_NOT_COMMITTED -> {
                    if (child.attemptCount != 0 || child.receipt != null ||
                        claimState !in setOf(MediaClaimState.CLEANED_VERIFIED, MediaClaimState.ACKNOWLEDGED_BY_USER) ||
                        result !is AlignedResult.MediaFailed && result !is AlignedResult.MediaNotAttempted
                    ) {
                        throw JournalInvariantViolation("Pre-entry media completion contradicts its durable evidence")
                    }
                    if (result is AlignedResult.MediaFailed && result.rowError.code != JournalErrorCode.MEDIA_STORE_FAILED) {
                        throw JournalInvariantViolation("Degradable media failure requires media_store_failed")
                    }
                }
                ChildState.COMMIT_UNCERTAIN -> {
                    if (child.attemptCount == 0 || child.receipt != null ||
                        claimState != MediaClaimState.COMMIT_UNCERTAIN || result !is AlignedResult.MediaUncertain
                    ) {
                        throw JournalInvariantViolation("Entered media uncertainty contradicts its durable evidence")
                    }
                }
                else -> throw JournalInvariantViolation("Media failure boundary has an impossible child outcome")
            }
            updateClaim(db, claim, claimState, null, compactEvidence)
            updateChildTerminal(db, child, childOutcome, compactEvidence)
            if (childOutcome == ChildState.COMMIT_UNCERTAIN) {
                ensureRemediationDb(
                    db,
                    RemediationDraft(
                        parentId = child.parentId,
                        claimId = claim.id,
                        kind = RemediationKind.MEDIA_COMMIT_UNCERTAIN,
                        summary = "Media provider commit could not be conclusively reconciled",
                        compactEvidence = compactEvidence,
                    ),
                )
            }
            appendResultDb(db, parentById(db, child.parentId), result)
            parentById(db, child.parentId)
        }
    }

    override fun createRoutingIntents(
        key: ParentKey,
        requestIndex: Int,
        intents: List<RoutingIntentDraft>,
    ): List<RoutingIntentRecord> {
        require(intents.isNotEmpty()) { "A materialized note must produce at least one card intent" }
        require(intents.all { it.requestIndex == requestIndex })
        require(intents.map { it.cardId }.distinct().size == intents.size)
        require(intents.map { it.ordinal }.distinct().size == intents.size)
        crashHooks.hit(JournalCrashPoint.BEFORE_CARD_INTENT_BATCH)
        val records =
            write { db ->
                val parent = requireMutableParent(db, key, ParentOperation.CREATE_NOTES)
                if (
                    parent.activeRequestIndex != requestIndex ||
                    parent.routingPhase != NoteRoutingPhase.CARDS_DISCOVERED ||
                    parent.activeNoteId == null
                ) {
                    throw JournalInvariantViolation("Card intents require discovered cards for the active note")
                }
                val target = targetSnapshotByParent(db, parent.id)
                    ?: throw JournalCorruptionException("Active note lost its verified target")
                if (intents.size != target.model.cardCount ||
                    intents.map { it.ordinal }.sorted() != target.model.templates.indices.toList()
                ) {
                    throw JournalInvariantViolation("Routing intents must exactly cover every durable template ordinal")
                }
                intents.forEach { intent ->
                    if (intent.noteId != parent.activeNoteId) throw JournalInvariantViolation("Routing note ID mismatch")
                    if (intent.targetDeckId != target.deck.id) throw JournalInvariantViolation("Routing target deck differs from durable target")
                    if (intent.ordinal !in target.model.templates.indices) {
                        throw JournalInvariantViolation("Routing ordinal is outside the durable model template range")
                    }
                    db.insertOrThrow(
                        "routing_intents",
                        null,
                        values(
                            "parent_id" to parent.id,
                            "request_index" to requestIndex,
                            "card_id" to intent.cardId,
                            "note_id" to intent.noteId,
                            "ordinal" to intent.ordinal,
                            "target_deck_id" to intent.targetDeckId,
                            "pre_update_deck_id" to intent.preUpdateDeckId,
                            "state" to RoutingIntentState.PENDING.name,
                            "created_at_ms" to timestamp(),
                            "updated_at_ms" to timestamp(),
                        ),
                    )
                }
                db.updateOrThrow(
                    "parents",
                    values("routing_phase" to NoteRoutingPhase.ROUTING.name, "updated_at_ms" to nextTimestamp(parent.updatedAtMs)),
                    "id = ?",
                    arrayOf(parent.id.toString()),
                )
                routingIntentsForNote(db, parent.id, requestIndex, requireNotNull(parent.activeNoteId))
            }
        crashHooks.hit(JournalCrashPoint.AFTER_CARD_INTENT_BATCH)
        return records
    }

    override fun prepareRoutingChild(intentId: Long): ChildRecord {
        crashHooks.hit(JournalCrashPoint.BEFORE_ROUTING_TRANSACTION)
        val result =
            write { db ->
                val intent = routingIntentById(db, intentId)
                if (intent.state != RoutingIntentState.PENDING || intent.childId != null) {
                    throw JournalInvariantViolation("Routing intent is not pending")
                }
                val parent = parentById(db, intent.parentId)
                val command =
                    MutationCommand.RouteCard(
                        intent.id,
                        intent.requestIndex,
                        intent.cardId,
                        intent.noteId,
                        intent.ordinal,
                        intent.targetDeckId,
                        intent.preUpdateDeckId,
                    )
                val child = prepareChildInTransaction(db, parent, command, null)
                db.updateOrThrow(
                    "routing_intents",
                    values(
                        "child_id" to child.id,
                        "state" to RoutingIntentState.UPDATE_PREPARED.name,
                        "updated_at_ms" to nextTimestamp(intent.updatedAtMs),
                    ),
                    "id = ?",
                    arrayOf(intent.id.toString()),
                )
                childById(db, child.id)
            }
        crashHooks.hit(JournalCrashPoint.AFTER_ROUTING_TRANSACTION)
        return result
    }

    override fun verifyRoutingIntentWithoutMutation(
        intentId: Long,
        compactEvidence: String,
    ): RoutingIntentRecord {
        requireCompactEvidence(compactEvidence)
        return write { db ->
            val intent = routingIntentById(db, intentId)
            if (intent.state != RoutingIntentState.PENDING || intent.childId != null) {
                throw JournalInvariantViolation("Only a childless PENDING routing intent can verify directly")
            }
            val parent = parentById(db, intent.parentId)
            val target = targetSnapshotByParent(db, parent.id)
                ?: throw JournalCorruptionException("Routing intent lost its verified target")
            if (
                parent.activeRequestIndex != intent.requestIndex || parent.activeNoteId != intent.noteId ||
                parent.routingPhase != NoteRoutingPhase.ROUTING || intent.targetDeckId != target.deck.id ||
                intent.ordinal !in target.model.templates.indices || intent.preUpdateDeckId != target.deck.id
            ) {
                throw JournalInvariantViolation("Direct routing verification is not exact-target evidence")
            }
            JournalStateMachine.requireRoutingTransition(intent.state, RoutingIntentState.VERIFIED)
            db.updateOrThrow(
                "routing_intents",
                values(
                    "state" to RoutingIntentState.VERIFIED.name,
                    "terminal_evidence" to compactEvidence,
                    "updated_at_ms" to nextTimestamp(intent.updatedAtMs),
                ),
                "id = ?",
                arrayOf(intent.id.toString()),
            )
            advanceRoutedIfComplete(db, parent)
            routingIntentById(db, intent.id)
        }
    }

    override fun completeRoutingChild(
        childId: Long,
        childOutcome: ChildState,
        intentOutcome: RoutingIntentState,
        compactEvidence: String,
    ): RoutingIntentRecord {
        requireCompactEvidence(compactEvidence)
        crashHooks.hit(JournalCrashPoint.BEFORE_ROUTING_TRANSACTION)
        val result =
            write { db ->
                val child = childById(db, childId)
                val command = child.command as? MutationCommand.RouteCard
                    ?: throw JournalInvariantViolation("Routing completion has non-card child")
                val intent = routingIntentById(db, command.intentId)
                if (intent.childId != childId) throw JournalCorruptionException("Routing intent lost child identity")
                JournalStateMachine.requireChildCompletion(child, childOutcome)
                JournalStateMachine.requireRoutingTransition(intent.state, intentOutcome)
                val legalPair =
                    when (intentOutcome) {
                        RoutingIntentState.VERIFIED -> childOutcome == ChildState.POSTCONDITION_VERIFIED
                        RoutingIntentState.FAILED -> childOutcome in setOf(
                            ChildState.PROVEN_NOT_COMMITTED,
                            ChildState.POSTCONDITION_FAILED,
                        )
                        RoutingIntentState.COMMIT_UNCERTAIN -> childOutcome == ChildState.COMMIT_UNCERTAIN
                        else -> false
                    }
                if (!legalPair) throw JournalInvariantViolation("Routing and child outcomes disagree")
                updateChildTerminal(db, child, childOutcome, compactEvidence)
                if (intentOutcome != RoutingIntentState.VERIFIED) {
                    ensureRemediationDb(
                        db,
                        RemediationDraft(
                            parentId = child.parentId,
                            kind = RemediationKind.CARD_ROUTING_FAILED,
                            summary = "Committed note card routing requires review",
                            compactEvidence = "noteId=${command.noteId};cardId=${command.cardId}",
                        ),
                    )
                }
                db.updateOrThrow(
                    "routing_intents",
                    values(
                        "state" to intentOutcome.name,
                        "terminal_evidence" to compactEvidence,
                        "updated_at_ms" to nextTimestamp(intent.updatedAtMs),
                    ),
                    "id = ?",
                    arrayOf(intent.id.toString()),
                )
                val parent = parentById(db, child.parentId)
                if (intentOutcome == RoutingIntentState.VERIFIED) advanceRoutedIfComplete(db, parent)
                routingIntentById(db, intent.id)
            }
        crashHooks.hit(JournalCrashPoint.AFTER_ROUTING_TRANSACTION)
        return result
    }

    private fun advanceRoutedIfComplete(db: SQLiteDatabase, parent: ParentRecord) {
        val requestIndex = parent.activeRequestIndex
            ?: throw JournalCorruptionException("Routing parent lost its active request index")
        val noteId = parent.activeNoteId
            ?: throw JournalCorruptionException("Routing parent lost its active note ID")
        val intents = routingIntentsForNote(db, parent.id, requestIndex, noteId)
        val target = targetSnapshotByParent(db, parent.id)
            ?: throw JournalCorruptionException("Routing parent lost its durable target")
        if (
            intents.size == target.model.cardCount &&
            intents.map { it.ordinal }.sorted() == target.model.templates.indices.toList() &&
            intents.all { it.state == RoutingIntentState.VERIFIED }
        ) {
            db.updateOrThrow(
                "parents",
                values("routing_phase" to NoteRoutingPhase.ROUTED.name, "updated_at_ms" to nextTimestamp(parent.updatedAtMs)),
                "id = ?",
                arrayOf(parent.id.toString()),
            )
        }
    }

    override fun completeVerifiedNote(
        key: ParentKey,
        requestIndex: Int,
        noteId: Long,
        compactEvidence: String,
    ): ParentRecord {
        require(noteId > 0)
        requireCompactEvidence(compactEvidence)
        crashHooks.hit(JournalCrashPoint.BEFORE_VERIFIED_NOTE_TRANSACTION)
        val result =
            write { db ->
                val parent = requireMutableParent(db, key, ParentOperation.CREATE_NOTES)
                completeVerifiedNoteDb(db, parent, requestIndex, noteId, compactEvidence)
            }
        crashHooks.hit(JournalCrashPoint.AFTER_VERIFIED_NOTE_TRANSACTION)
        return result
    }

    private fun completeVerifiedNoteDb(
        db: SQLiteDatabase,
        parent: ParentRecord,
        requestIndex: Int,
        noteId: Long,
        compactEvidence: String,
    ): ParentRecord {
        if (
            parent.activeRequestIndex != requestIndex || parent.activeNoteId != noteId ||
            parent.routingPhase != NoteRoutingPhase.POSTCHECK_VERIFIED
        ) {
            throw JournalInvariantViolation("Verified note completion does not match the active postcheck")
        }
        requireCompleteRoutingProof(db, parent)
        val active = activeNoteByParent(db, parent.id)
            ?: throw JournalCorruptionException("Verified note lost its durable materialization")
        if (active.materialization.requestIndex != requestIndex) {
            throw JournalCorruptionException("Verified note materialization index differs")
        }
        active.materialization.mediaBindings.forEach { binding ->
            val claim = claimById(db, binding.claimId)
            if (
                claim.runId != parent.key.runId || claim.assetId != binding.assetId ||
                claim.actualFilename != binding.actualFilename ||
                claim.state !in setOf(MediaClaimState.STORED, MediaClaimState.PRESENT_BYTES_VERIFIED)
            ) {
                throw JournalInvariantViolation("Verified note binding differs from its unresolved stored claim")
            }
        }
        val item = requestItem(db, parent.id, requestIndex)
        appendResultDb(
            db,
            parent,
            AlignedResult.NoteCreated(requestIndex, item.itemId, noteId, compactEvidence),
        )
        active.materialization.mediaBindings.forEach { binding ->
            val claim = claimById(db, binding.claimId)
            updateClaim(db, claim, MediaClaimState.ATTACHED_VERIFIED, claim.actualFilename, compactEvidence)
        }
        clearActiveNote(db, parent)
        return parentById(db, parent.id)
    }

    private fun requireCompleteRoutingProof(db: SQLiteDatabase, parent: ParentRecord) {
        val target = targetSnapshotByParent(db, parent.id)
            ?: throw JournalCorruptionException("Active note lost its durable target")
        val noteId = parent.activeNoteId ?: throw JournalInvariantViolation("Routing proof requires a known note ID")
        val requestIndex = parent.activeRequestIndex ?: throw JournalInvariantViolation("Routing proof requires an active item")
        val intents = routingIntentsForNote(db, parent.id, requestIndex, noteId)
        if (
            intents.size != target.model.cardCount ||
            intents.map { it.ordinal }.sorted() != target.model.templates.indices.toList() ||
            intents.any {
                it.requestIndex != requestIndex || it.noteId != noteId || it.targetDeckId != target.deck.id ||
                    it.state != RoutingIntentState.VERIFIED
            }
        ) {
            throw JournalInvariantViolation("Complete exact card-routing verification is missing")
        }
    }

    override fun appendAlignedResult(key: ParentKey, result: AlignedResult): ParentRecord =
        write { db ->
            val parent = requireMutableParent(db, key)
            if (result is AlignedResult.NoteCreated) {
                throw JournalInvariantViolation("Created notes require atomic verified-note and attachment completion")
            }
            appendResultDb(db, parent, result)
            parentById(db, parent.id)
        }

    override fun alignedResults(key: ParentKey): List<AlignedResult> =
        read { db -> parentByKey(db, key)?.let { alignedResultsByParent(db, it.id) }.orEmpty() }

    override fun markResultReady(request: JournalRequest, response: JournalResponse): ParentRecord {
        crashHooks.hit(JournalCrashPoint.BEFORE_RESULT_READY)
        val result =
            write { db ->
                requireRequestSelfConsistent(request)
                val parent = requireParent(db, request.key)
                requireParentMatchesRequest(db, parent, request)
                if (parent.state == ParentState.RESULT_READY) {
                    val replayed = responseByParent(db, parent)
                    if (replayed != response) throw JournalInvariantViolation("RESULT_READY response is immutable")
                    return@write parent
                }
                if (parent.state !in setOf(ParentState.PREPARED, ParentState.RUNNING)) {
                    throw JournalInvariantViolation("Parent cannot terminalize from ${parent.state}")
                }
                val prefix = alignedResultsByParent(db, parent.id)
                JournalStateMachine.validateTerminalResponse(request, response, prefix)
                val rows = terminalRows(response)
                if (rows.size != request.itemIds.size) throw JournalInvariantViolation("Terminal response lacks exact coverage")
                rows.drop(prefix.size).forEach { appendResultDb(db, parent, it) }
                requireDurableTerminalEvidence(db, parent, response)
                val metadata = metadataFor(parent.id, response)
                parentTerminalMetadata(db, parent.id)?.let { existing ->
                    if (existing != metadata) throw JournalInvariantViolation("Terminal metadata is immutable")
                } ?: insertTerminalMetadata(db, metadata)
                if (response is JournalResponse.VerifySuccess) {
                    val durableTarget = targetSnapshotByParent(db, parent.id)
                    if (durableTarget != response.target) throw JournalInvariantViolation("Verify result target differs from durable snapshot")
                }
                if (preparedChildForParent(db, parent.id) != null) {
                    throw JournalInvariantViolation("Cannot terminalize while a provider mutation is PREPARED")
                }
                updateParentState(db, parent, ParentState.RESULT_READY)
                parentById(db, parent.id)
            }
        crashHooks.hit(JournalCrashPoint.AFTER_RESULT_READY)
        return result
    }

    override fun replay(request: JournalRequest, liveRun: Boolean): ReplayResult =
        read { db ->
            requireRequestSelfConsistent(request)
            val parent = parentByKey(db, request.key) ?: return@read ReplayResult.Missing
            if (
                parent.operation != request.operation ||
                parent.digestVersion != request.digest.digestVersion ||
                parent.requestSha256 != request.digest.sha256
            ) {
                return@read ReplayResult.DigestMismatch
            }
            if (parent.state != ParentState.RESULT_READY) return@read ReplayResult.NotReplayable
            if (requestItemsByParent(db, parent.id).map { it.itemId } != request.itemIds) return@read ReplayResult.DigestMismatch
            if (!liveRun) return@read ReplayResult.LiveOwnerRequired
            ReplayResult.Ready(responseByParent(db, parent))
        }

    override fun cleanupRun(
        runId: String,
        acknowledgeAuthorized: Boolean,
        frozenDurableRequestIds: List<String>,
    ): RunCleanupResult {
        require(runId.isNotBlank())
        require(frozenDurableRequestIds.all { it.isNotBlank() })
        crashHooks.hit(JournalCrashPoint.BEFORE_RUN_CLEANUP)
        val result =
            write { db ->
                if (preparedChildForRun(db, runId) != null) {
                    throw JournalInvariantViolation("Run cleanup must wait for PREPARED mutation recovery")
                }
                val parents = parentsForRun(db, runId).filterNot { it.state.isFinalized }
                val readyIds = parents.filter { it.state == ParentState.RESULT_READY }.map { it.key.requestId }
                val duplicateFree = frozenDurableRequestIds.distinct().size == frozenDurableRequestIds.size
                val evidenceAccepted =
                    acknowledgeAuthorized && duplicateFree && frozenDurableRequestIds.toSet() == readyIds.toSet()
                val wasReady = parents.associate { it.id to (it.state == ParentState.RESULT_READY) }
                val terminalParents =
                    parents.map { parent ->
                        if (wasReady.getValue(parent.id)) parent else terminalizeOwnerlessDb(db, parent)
                    }
                val acknowledged = ArrayList<String>()
                val abandoned = ArrayList<String>()
                terminalParents.forEach { terminal ->
                    val responseWasReady = wasReady.getValue(terminal.id)
                    val finalState =
                        if (evidenceAccepted && responseWasReady) {
                            acknowledged += terminal.key.requestId
                            ParentState.RESPONSE_ACKNOWLEDGED
                        } else {
                            abandoned += terminal.key.requestId
                            ParentState.ABANDONED
                        }
                    finalizeAndScrub(db, terminal, finalState)
                }
                releaseRunCapabilitiesDb(db, runId)
                RunCleanupResult(acknowledged, abandoned, evidenceAccepted)
            }
        crashHooks.hit(JournalCrashPoint.AFTER_RUN_CLEANUP)
        return result
    }

    override fun abandonOwnerless(activeRunIds: Set<String>): List<ParentRecord> {
        require(activeRunIds.none(String::isBlank))
        return write { db ->
            val preparedParentId = preparedChildDb(db)?.parentId
            val candidates =
                recoveryParentsDb(db).filter {
                    it.key.runId !in activeRunIds && it.id != preparedParentId && !it.state.isFinalized
                }
            val terminalized = candidates.map { parent ->
                crashHooks.hit(JournalCrashPoint.BEFORE_OWNERLESS_TERMINALIZATION)
                val terminal = if (parent.state == ParentState.RESULT_READY) parent else terminalizeOwnerlessDb(db, parent)
                crashHooks.hit(JournalCrashPoint.AFTER_OWNERLESS_TERMINALIZATION)
                terminal
            }
            val finalized = terminalized.map { terminal ->
                finalizeAndScrub(db, terminal, ParentState.ABANDONED)
                parentById(db, terminal.id)
            }
            candidates.map { it.key.runId }.distinct().forEach { runId ->
                if (preparedChildForRun(db, runId) == null && parentsForRun(db, runId).all { it.state.isFinalized }) {
                    releaseRunCapabilitiesDb(db, runId)
                }
            }
            finalized
        }
    }

    override fun preparedChild(): ChildRecord? = read(::preparedChildDb)

    override fun recoveryParents(): List<ParentRecord> = read(::recoveryParentsDb)

    override fun recoveryInventory(): RecoveryInventory =
        read { db ->
            val child = preparedChildDb(db)
            val intent = child?.let { preparedRoutingIntent(db, it.id) }
            val expectation =
                child?.takeIf { it.command.operation == ChildOperation.DECK_CREATE }
                    ?.let { targetExpectationByParent(db, it.parentId) }
            RecoveryInventory(recoveryParentsDb(db), child, intent, expectation)
        }

    override fun acquireMediaLease(runId: String): MediaLeaseRecord {
        require(runId.isNotBlank())
        return write { db ->
            leaseByRun(db, runId)?.let {
                if (it.state != MediaLeaseState.ACTIVE) throw JournalInvariantViolation("Released run lease cannot be reacquired")
                return@write it
            }
            val activeLeaseUnused =
                db.query("media_leases", null, "state = 'ACTIVE'", null, null, null, null).use { cursor ->
                    cursor.singleOrNull({ row -> leaseFromCursor(db, row).unusedSlots }, "active media lease")
                }
            val unresolved =
                scalarLong(
                    db,
                    "SELECT count(*) FROM media_claims WHERE state IN ('PENDING','STORED','COMMIT_UNCERTAIN','PRESENT_BYTES_VERIFIED')",
                ).toInt()
            MediaCapacityPolicy.requireLeaseAdmission(
                unresolvedClaims = unresolved,
                activeLeaseUnusedSlots = activeLeaseUnused,
                requestedLeaseSlots = capacityLimits.leaseCapacity,
                globalLimit = capacityLimits.globalUnresolvedLimit,
            )
            val now = timestamp()
            val id =
                db.insertOrThrow(
                    "media_leases",
                    null,
                    values(
                        "run_id" to runId,
                        "capacity" to capacityLimits.leaseCapacity,
                        "state" to MediaLeaseState.ACTIVE.name,
                        "created_at_ms" to now,
                        "updated_at_ms" to now,
                    ),
                )
            leaseById(db, id)
        }
    }

    override fun mediaLease(runId: String): MediaLeaseRecord? = read { leaseByRun(it, runId) }

    override fun reserveMedia(runId: String, assets: List<MediaReservationDraft>): List<MediaReservationRecord> {
        require(runId.isNotBlank())
        require(assets.isNotEmpty())
        require(assets.map { it.assetId }.distinct().size == assets.size) { "asset IDs must be unique in a reservation batch" }
        return write { db ->
            val lease = leaseByRun(db, runId) ?: throw JournalInvariantViolation("Media lease is not acquired")
            if (lease.state != MediaLeaseState.ACTIVE) throw JournalInvariantViolation("Media lease is released")
            if (assets.size > lease.unusedSlots) throw JournalInvariantViolation("Per-run media namespace capacity exhausted")
            assets.forEach { validateMediaNames(it.requestedFilename, it.preferredName) }
            val existingLocks = boundedNamespaceLocks(db)
            val newLocks =
                assets.map {
                    MediaNamespaceLock(
                        MediaNamespaceOwner(runId, it.assetId),
                        it.requestedFilename,
                        providerPrefix(it.preferredName),
                    )
                }
            if (existingLocks.size + newLocks.size > capacityLimits.globalUnresolvedLimit) {
                throw JournalInvariantViolation("Global media namespace capacity exhausted")
            }
            requireNoClaimNamespaceCollisions(db, newLocks)
            MediaNamespaceValidator.requireDisjoint(existingLocks + newLocks)
            val now = timestamp()
            assets.map { asset ->
                val id =
                    db.insertOrThrow(
                        "media_reservations",
                        null,
                        values(
                            "lease_id" to lease.id,
                            "run_id" to runId,
                            "request_id" to asset.requestId,
                            "asset_id" to asset.assetId,
                            "requested_filename" to asset.requestedFilename,
                            "preferred_name" to asset.preferredName,
                            "provider_prefix" to providerPrefix(asset.preferredName),
                            "sha256" to asset.sha256,
                            "purpose" to asset.purpose.name,
                            "media_kind" to asset.mediaKind.name,
                            "state" to MediaReservationState.RESERVED.name,
                            "created_at_ms" to now,
                            "updated_at_ms" to now,
                        ),
                    )
                reservationById(db, id)
            }
        }
    }

    override fun releaseReservation(reservationId: Long): MediaReservationRecord =
        write { db ->
            val record = reservationById(db, reservationId)
            if (record.state != MediaReservationState.RESERVED) {
                throw JournalInvariantViolation("Only an unused reservation may be released")
            }
            db.updateOrThrow(
                "media_reservations",
                values("state" to MediaReservationState.RELEASED.name, "updated_at_ms" to nextTimestamp(record.updatedAtMs)),
                "id = ?",
                arrayOf(record.id.toString()),
            )
            reservationById(db, record.id)
        }

    override fun promoteReservation(
        key: ParentKey,
        reservationId: Long,
        command: MutationCommand.StoreMedia,
    ): MediaPromotion {
        crashHooks.hit(JournalCrashPoint.BEFORE_CHILD_PREPARED)
        val promotion =
            write { db ->
                val parent = requireMutableParent(db, key, ParentOperation.STORE_MEDIA)
                val reservation = reservationById(db, reservationId)
                val lease = leaseById(db, reservation.leaseId)
                if (
                    reservation.state != MediaReservationState.RESERVED || reservation.runId != key.runId ||
                    reservation.requestId != key.requestId || reservation.assetId != command.assetId ||
                    reservation.preferredName != command.preferredName || lease.state != MediaLeaseState.ACTIVE
                ) {
                    throw JournalInvariantViolation("Reservation does not match media command")
                }
                val unresolved = scalarLong(db, "SELECT count(*) FROM media_claims WHERE state IN ('PENDING','STORED','COMMIT_UNCERTAIN','PRESENT_BYTES_VERIFIED')")
                if (unresolved >= capacityLimits.globalUnresolvedLimit) {
                    throw JournalInvariantViolation("Global unresolved media-claim capacity exhausted")
                }
                val now = timestamp()
                val claimId =
                    db.insertOrThrow(
                        "media_claims",
                        null,
                        values(
                            "run_id" to reservation.runId,
                            "request_id" to reservation.requestId,
                            "asset_id" to reservation.assetId,
                            "requested_filename" to reservation.requestedFilename,
                            "preferred_name" to reservation.preferredName,
                            "provider_prefix" to providerPrefix(reservation.preferredName),
                            "sha256" to reservation.sha256,
                            "purpose" to reservation.purpose.name,
                            "media_kind" to reservation.mediaKind.name,
                            "state" to MediaClaimState.PENDING.name,
                            "created_at_ms" to now,
                            "updated_at_ms" to now,
                        ),
                    )
                db.updateOrThrow(
                    "media_reservations",
                    values(
                        "state" to MediaReservationState.PROMOTED.name,
                        "claim_id" to claimId,
                        "updated_at_ms" to nextTimestamp(reservation.updatedAtMs),
                    ),
                    "id = ?",
                    arrayOf(reservation.id.toString()),
                )
                val child = prepareChildInTransaction(db, parent, command, claimId)
                MediaPromotion(reservationById(db, reservation.id), claimById(db, claimId), child)
            }
        crashHooks.hit(JournalCrashPoint.AFTER_CHILD_PREPARED)
        return promotion
    }

    override fun transitionClaim(
        claimId: Long,
        state: MediaClaimState,
        actualFilename: String?,
        compactEvidence: String?,
    ): MediaClaimRecord {
        requireCompactEvidence(compactEvidence)
        if (state in setOf(MediaClaimState.STORED, MediaClaimState.COMMIT_UNCERTAIN, MediaClaimState.ATTACHED_VERIFIED)) {
            throw JournalInvariantViolation("Mutation-bearing claim states require their typed atomic completion API")
        }
        actualFilename?.let { validateMediaNames(it, it) }
        return write { db ->
            val claim = claimById(db, claimId)
            if (openStoredUnattachedRemediationDb(db, claim.id) != null) {
                if (state != MediaClaimState.PRESENT_BYTES_VERIFIED || !hasDurableNoteBindingDb(db, claim)) {
                    throw JournalInvariantViolation(
                        "Stored-unattached media requires its typed acknowledgement or exact note binding",
                    )
                }
            }
            JournalStateMachine.requireClaimTransition(claim.state, state)
            updateClaim(db, claim, state, actualFilename ?: claim.actualFilename, compactEvidence)
            claimById(db, claimId)
        }
    }

    override fun mediaClaim(key: ParentKey, assetId: String): MediaClaimRecord? {
        require(assetId.isNotBlank()) { "assetId must not be blank" }
        return read { db ->
            db.query(
                "media_claims",
                null,
                "run_id = ? AND asset_id = ?",
                arrayOf(key.runId, assetId),
                null,
                null,
                null,
            ).use { cursor ->
                cursor.singleOrNull(::claimFromCursor, "media claim run/asset identity")
            }?.takeIf { claim -> claim.requestId == key.requestId }
        }
    }

    override fun unresolvedClaims(): List<MediaClaimRecord> = read(::unresolvedClaimsDb)

    override fun releaseMediaLease(runId: String): MediaLeaseRecord? =
        write { db ->
            val lease = leaseByRun(db, runId) ?: return@write null
            if (lease.state == MediaLeaseState.RELEASED) return@write lease
            if (scalarLong(db, "SELECT count(*) FROM media_reservations WHERE lease_id = ? AND state = 'RESERVED'", arrayOf(lease.id.toString())) > 0) {
                throw JournalInvariantViolation("Lease retains unused reservations")
            }
            db.updateOrThrow(
                "media_leases",
                values("state" to MediaLeaseState.RELEASED.name, "updated_at_ms" to nextTimestamp(lease.updatedAtMs)),
                "id = ?",
                arrayOf(lease.id.toString()),
            )
            leaseById(db, lease.id)
        }

    override fun recordStaging(draft: StagingDraft): StagingRecord =
        write { db ->
            val now = timestamp()
            val id =
                db.insertOrThrow(
                    "staging_artifacts",
                    null,
                    values(
                        "run_id" to draft.runId,
                        "request_id" to draft.requestId,
                        "asset_id" to draft.assetId,
                        "relative_path" to draft.relativePath,
                        "content_uri" to draft.contentUri,
                        "package_name" to draft.packageName,
                        "size_bytes" to draft.sizeBytes,
                        "sha256" to draft.sha256,
                        "state" to StagingState.STAGED.name,
                        "created_at_ms" to now,
                        "updated_at_ms" to now,
                    ),
                )
            stagingById(db, id)
        }

    override fun transitionStaging(stagingId: Long, state: StagingState, compactEvidence: String?): StagingRecord {
        requireCompactEvidence(compactEvidence)
        return write { db ->
            val current = stagingById(db, stagingId)
            JournalStateMachine.requireStagingTransition(current.state, state)
            db.updateOrThrow(
                "staging_artifacts",
                values(
                    "state" to state.name,
                    "compact_evidence" to compactEvidence,
                    "updated_at_ms" to nextTimestamp(current.updatedAtMs),
                ),
                "id = ?",
                arrayOf(stagingId.toString()),
            )
            stagingById(db, stagingId)
        }
    }

    override fun stagingForRecovery(): List<StagingRecord> =
        read { db ->
            db.query(
                "staging_artifacts",
                null,
                null,
                null,
                null,
                null,
                "created_at_ms, id",
            ).use { it.mapRows(::stagingFromCursor) }
        }

    override fun completeStagingCleanup(stagingId: Long, compactEvidence: String) {
        requireCompactEvidence(compactEvidence)
        write { db ->
            val staging = stagingById(db, stagingId)
            if (staging.state != StagingState.CLEANED) {
                JournalStateMachine.requireStagingTransition(staging.state, StagingState.CLEANED)
                db.updateOrThrow(
                    "staging_artifacts",
                    values(
                        "state" to StagingState.CLEANED.name,
                        "compact_evidence" to compactEvidence,
                        "updated_at_ms" to nextTimestamp(staging.updatedAtMs),
                    ),
                    "id = ?",
                    arrayOf(stagingId.toString()),
                )
            }

            val attachedRemediations =
                db.query(
                    "remediations",
                    null,
                    "staging_id = ?",
                    arrayOf(stagingId.toString()),
                    null,
                    null,
                    "id",
                ).use { it.mapRows(::remediationFromCursor) }
            attachedRemediations.filter { it.state == RemediationState.OPEN }.forEach { remediation ->
                db.updateOrThrow(
                    "remediations",
                    values(
                        "state" to RemediationState.RESOLVED.name,
                        "compact_evidence" to compactEvidence,
                        "updated_at_ms" to nextTimestamp(remediation.updatedAtMs),
                    ),
                    "id = ?",
                    arrayOf(remediation.id.toString()),
                )
            }
            attachedRemediations.forEach { remediation ->
                val resolved = remediationById(db, remediation.id)
                if (resolved.state != RemediationState.RESOLVED) {
                    throw JournalInvariantViolation("Attached staging remediation did not resolve")
                }
                db.updateOrThrow(
                    "remediations",
                    values(
                        "staging_id" to null,
                        "updated_at_ms" to nextTimestamp(resolved.updatedAtMs),
                    ),
                    "id = ?",
                    arrayOf(resolved.id.toString()),
                )
            }
            db.delete("staging_artifacts", "id = ?", arrayOf(stagingId.toString())).requireOne("staging artifact delete")
        }
    }

    override fun removeCleanedStaging(stagingId: Long) {
        write { db ->
            val current = stagingById(db, stagingId)
            if (current.state != StagingState.CLEANED) throw JournalInvariantViolation("Only cleaned staging may be removed")
            db.execSQL(
                """UPDATE remediations SET staging_id = NULL, updated_at_ms = updated_at_ms + 1
                   WHERE staging_id = ? AND state = 'RESOLVED'""".trimIndent(),
                arrayOf(stagingId),
            )
            db.delete("staging_artifacts", "id = ?", arrayOf(stagingId.toString())).requireOne("staging artifact delete")
        }
    }

    override fun addRemediation(draft: RemediationDraft): RemediationRecord =
        write { db -> ensureRemediationDb(db, draft) }

    override fun openRemediations(): List<RemediationRecord> =
        read { db ->
            db.query(
                "remediations",
                null,
                "state = ?",
                arrayOf(RemediationState.OPEN.name),
                null,
                null,
                "created_at_ms, id",
            ).use { it.mapRows(::remediationFromCursor) }
        }

    override fun acknowledgeUnattachedMedia(
        remediationId: Long,
        compactEvidence: String,
    ): RemediationRecord {
        requireCompactEvidence(compactEvidence)
        return write { db ->
            val remediation = remediationById(db, remediationId)
            if (
                remediation.kind != RemediationKind.MEDIA_STORED_UNATTACHED ||
                    remediation.state != RemediationState.OPEN || remediation.claimId == null
            ) {
                throw JournalInvariantViolation("Remediation is not open stored-unattached media")
            }
            val claim = claimById(db, remediation.claimId)
            if (claim.state !in setOf(MediaClaimState.STORED, MediaClaimState.PRESENT_BYTES_VERIFIED)) {
                throw JournalInvariantViolation("Stored-unattached claim is no longer acknowledgeable")
            }
            updateClaim(
                db,
                claim,
                MediaClaimState.ACKNOWLEDGED_BY_USER,
                claim.actualFilename,
                compactEvidence,
            )
            val resolved = remediationById(db, remediationId)
            if (resolved.state != RemediationState.RESOLVED) {
                throw JournalInvariantViolation("Stored-unattached acknowledgement did not resolve remediation")
            }
            resolved
        }
    }

    override fun resolveRemediation(remediationId: Long, compactEvidence: String): RemediationRecord {
        requireCompactEvidence(compactEvidence)
        return write { db ->
            val current = remediationById(db, remediationId)
            if (current.state != RemediationState.OPEN) throw JournalInvariantViolation("Remediation is already resolved")
            if (current.kind == RemediationKind.MEDIA_STORED_UNATTACHED) {
                throw JournalInvariantViolation("Stored-unattached media requires typed acknowledgement")
            }
            db.updateOrThrow(
                "remediations",
                values(
                    "state" to RemediationState.RESOLVED.name,
                    "compact_evidence" to compactEvidence,
                    "updated_at_ms" to nextTimestamp(current.updatedAtMs),
                ),
                "id = ?",
                arrayOf(remediationId.toString()),
            )
            remediationById(db, remediationId)
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) super.close()
    }

    private fun prepareChildInTransaction(
        db: SQLiteDatabase,
        parent: ParentRecord,
        command: MutationCommand,
        mediaClaimId: Long?,
    ): ChildRecord {
        if (parent.state !in setOf(ParentState.PREPARED, ParentState.RUNNING)) {
            throw JournalInvariantViolation("Child parent is not mutable")
        }
        val expectedParentOperation =
            when (command.operation) {
                ChildOperation.DECK_CREATE -> ParentOperation.VERIFY_TARGET
                ChildOperation.MEDIA_INSERT -> ParentOperation.STORE_MEDIA
                ChildOperation.NOTE_INSERT, ChildOperation.CARD_DECK_UPDATE -> ParentOperation.CREATE_NOTES
            }
        if (parent.operation != expectedParentOperation) throw JournalInvariantViolation("Command operation does not match parent")
        when (command) {
            is MutationCommand.CreateDeck -> {
                val expectation = targetExpectationByParent(db, parent.id)
                    ?: throw JournalInvariantViolation("Deck creation requires a frozen target expectation")
                if (expectation.expectedDeckName != command.deckName) {
                    throw JournalInvariantViolation("Deck command differs from frozen target expectation")
                }
            }
            is MutationCommand.InsertNote -> {
                val target = targetSnapshotByParent(db, parent.id)
                    ?: throw JournalInvariantViolation("Note insertion requires an exact durable target")
                if (target.model.id != command.modelId) {
                    throw JournalInvariantViolation("Note command model differs from durable target")
                }
            }
            is MutationCommand.StoreMedia, is MutationCommand.RouteCard -> Unit
        }
        command.requestIndex?.let { index ->
            val requestItem = requestItem(db, parent.id, index)
            when (command) {
                is MutationCommand.StoreMedia -> if (requestItem.itemId != command.assetId) throw JournalInvariantViolation("Media command identity mismatch")
                is MutationCommand.InsertNote -> if (requestItem.itemId != command.clientNoteId) throw JournalInvariantViolation("Note command identity mismatch")
                is MutationCommand.RouteCard -> {
                    if (parent.activeRequestIndex != index) throw JournalInvariantViolation("Card command is not for active note")
                }
                is MutationCommand.CreateDeck -> error("deck has no request index")
            }
        }
        if ((command is MutationCommand.StoreMedia) != (mediaClaimId != null)) {
            throw JournalInvariantViolation("Only media commands require a claim")
        }
        if (command is MutationCommand.StoreMedia) {
            val claim = claimById(db, checkNotNull(mediaClaimId))
            if (claim.runId != parent.key.runId || claim.requestId != parent.key.requestId || claim.assetId != command.assetId) {
                throw JournalInvariantViolation("Media command claim identity mismatch")
            }
        }
        val sequence = scalarLong(db, "SELECT count(*) FROM mutation_children WHERE parent_id = ?", arrayOf(parent.id.toString())).toInt()
        val itemSha256 =
            if (command is MutationCommand.InsertNote) {
                val active = activeNoteByParent(db, parent.id)
                    ?: throw JournalInvariantViolation("Note child lacks canonical active-note materialization")
                if (
                    active.materialization.requestIndex != command.requestIndex ||
                    active.materialization.clientNoteId != command.clientNoteId ||
                    active.materialization.joinedFields != command.joinedFields ||
                    active.materialization.providerTagsWire != command.providerTagsWire
                ) {
                    throw JournalInvariantViolation("Note command differs from canonical materialization")
                }
                active.itemSha256
            } else {
                null
            }
        val now = timestamp()
        val id =
            db.insertOrThrow(
                "mutation_children",
                null,
                values(
                    "parent_id" to parent.id,
                    "sequence_number" to sequence,
                    "operation_kind" to command.operation.name,
                    "identity_key" to command.identityKey,
                    "request_index" to command.requestIndex,
                    "digest_version" to parent.digestVersion,
                    "request_sha256" to parent.requestSha256,
                    "item_sha256" to itemSha256,
                    "media_claim_id" to mediaClaimId,
                    "state" to ChildState.PREPARED.name,
                    "created_at_ms" to now,
                    "updated_at_ms" to now,
                ),
            )
        when (command) {
            is MutationCommand.CreateDeck -> db.insertOrThrow("deck_commands", null, values("child_id" to id, "deck_name" to command.deckName))
            is MutationCommand.StoreMedia ->
                db.insertOrThrow(
                    "media_commands",
                    null,
                    values(
                        "child_id" to id,
                        "asset_id" to command.assetId,
                        "file_uri" to command.fileUri,
                        "preferred_name" to command.preferredName,
                    ),
                )
            is MutationCommand.InsertNote ->
                db.insertOrThrow(
                    "note_commands",
                    null,
                    values(
                        "child_id" to id,
                        "client_note_id" to command.clientNoteId,
                        "model_id" to command.modelId,
                        "joined_fields" to command.joinedFields,
                        "provider_tags_wire" to command.providerTagsWire,
                    ),
                )
            is MutationCommand.RouteCard ->
                db.insertOrThrow(
                    "card_commands",
                    null,
                    values(
                        "child_id" to id,
                        "intent_id" to command.intentId,
                        "card_id" to command.cardId,
                        "note_id" to command.noteId,
                        "ordinal" to command.ordinal,
                        "target_deck_id" to command.targetDeckId,
                        "pre_update_deck_id" to command.preUpdateDeckId,
                    ),
                )
        }
        return childById(db, id)
    }

    private fun appendResultDb(db: SQLiteDatabase, parent: ParentRecord, result: AlignedResult) {
        JournalStateMachine.validateAlignedResult(parent.operation, result)
        val next = scalarLong(db, "SELECT count(*) FROM aligned_results WHERE parent_id = ?", arrayOf(parent.id.toString())).toInt()
        if (result.requestIndex != next) throw JournalInvariantViolation("Aligned result does not append at the next request index")
        val item = requestItem(db, parent.id, next)
        if (item.itemId != result.itemId) throw JournalInvariantViolation("Aligned result item identity mismatch")
        requireDurableResultProof(db, parent, result)
        db.insertOrThrow(
            "aligned_results",
            null,
            values(
                "parent_id" to parent.id,
                "request_index" to result.requestIndex,
                "item_id" to result.itemId,
                "status_kind" to result.status.name,
                "committed_id" to result.committedId,
                "actual_filename" to result.actualFilename,
                "error_code" to result.rowError?.code?.name,
                "error_message" to result.rowError?.message,
                "error_retryable" to result.rowError?.retryable?.let(::bool),
                "compact_evidence" to result.compactEvidence,
            ),
        )
    }

    private fun requireDurableResultProof(db: SQLiteDatabase, parent: ParentRecord, result: AlignedResult) {
        when (result) {
            is AlignedResult.TargetVerified -> {
                if (targetSnapshotByParent(db, parent.id) == null) throw JournalInvariantViolation("Verified target result lacks snapshot")
            }
            is AlignedResult.TargetFailed -> {
                val uncertainDeckCount =
                    scalarLong(
                        db,
                        """SELECT count(*) FROM mutation_children c WHERE c.parent_id = ? AND
                           c.operation_kind = 'DECK_CREATE' AND c.state = 'COMMIT_UNCERTAIN'""".trimIndent(),
                        arrayOf(parent.id.toString()),
                    )
                val isPostCommitUncertain =
                    result.rowError.code == JournalErrorCode.POST_COMMIT_UNCERTAIN && !result.rowError.retryable
                if (uncertainDeckCount != 0L && !isPostCommitUncertain) {
                    throw JournalInvariantViolation("Uncertain deck cannot be downgraded to a stable verify error")
                }
                if (result.rowError.code == JournalErrorCode.POST_COMMIT_UNCERTAIN &&
                    (result.rowError.retryable || uncertainDeckCount != 1L)
                ) {
                    throw JournalInvariantViolation("Post-commit deck uncertainty lacks entry-bearing child proof")
                }
            }
            is AlignedResult.MediaFailed -> {
                val contradictory =
                    scalarLong(
                        db,
                        """SELECT count(*) FROM mutation_children c WHERE c.parent_id = ? AND
                           c.request_index = ? AND c.identity_key = ? AND c.operation_kind = 'MEDIA_INSERT' AND
                           (c.state != 'PROVEN_NOT_COMMITTED' OR EXISTS(
                               SELECT 1 FROM provider_attempts a WHERE a.child_id = c.id) OR EXISTS(
                               SELECT 1 FROM media_receipts r WHERE r.child_id = c.id))""".trimIndent(),
                        arrayOf(parent.id.toString(), result.requestIndex.toString(), result.itemId),
                    )
                val children = mutationChildCount(db, parent.id, result.requestIndex, result.itemId, ChildOperation.MEDIA_INSERT)
                if (children > 1L || contradictory != 0L) {
                    throw JournalInvariantViolation("Failed media row hides entered or committed mutation evidence")
                }
            }
            is AlignedResult.MediaNotAttempted -> {
                val children = mutationChildCount(db, parent.id, result.requestIndex, result.itemId, ChildOperation.MEDIA_INSERT)
                val provenAbsent =
                    scalarLong(
                        db,
                        """SELECT count(*) FROM mutation_children c JOIN media_claims m ON m.id = c.media_claim_id
                           WHERE c.parent_id = ? AND c.request_index = ? AND c.identity_key = ? AND
                               c.operation_kind = 'MEDIA_INSERT' AND c.state = 'PROVEN_NOT_COMMITTED' AND
                               m.state IN ('CLEANED_VERIFIED', 'ACKNOWLEDGED_BY_USER') AND NOT EXISTS(
                                   SELECT 1 FROM provider_attempts a WHERE a.child_id = c.id) AND NOT EXISTS(
                                   SELECT 1 FROM media_receipts r WHERE r.child_id = c.id)""".trimIndent(),
                        arrayOf(parent.id.toString(), result.requestIndex.toString(), result.itemId),
                    )
                if (children > 0L && (children != 1L || provenAbsent != 1L)) {
                    throw JournalInvariantViolation("Not-attempted media row hides entered mutation evidence")
                }
            }
            is AlignedResult.MediaUncertain -> {
                val count =
                    scalarLong(
                        db,
                        """SELECT count(*) FROM mutation_children c JOIN media_claims m ON m.id = c.media_claim_id
                           WHERE c.parent_id = ? AND c.request_index = ? AND c.identity_key = ?
                             AND c.state = 'COMMIT_UNCERTAIN' AND m.state = 'COMMIT_UNCERTAIN'""".trimIndent(),
                        arrayOf(parent.id.toString(), result.requestIndex.toString(), result.itemId),
                    )
                if (count != 1L) throw JournalInvariantViolation("Uncertain media row lacks entry-bearing child and claim proof")
            }
            is AlignedResult.MediaStored -> {
                val proven =
                    scalarLong(
                        db,
                        """SELECT count(*) FROM mutation_children c
                           JOIN media_receipts r ON r.child_id = c.id
                           JOIN media_claims m ON m.id = c.media_claim_id
                           WHERE c.parent_id = ? AND c.request_index = ? AND c.identity_key = ?
                             AND c.state IN ('PREPARED','COMMIT_KNOWN') AND r.actual_filename = ?
                             AND m.state = 'STORED' AND m.actual_filename = ?""".trimIndent(),
                        arrayOf(parent.id.toString(), result.requestIndex.toString(), result.itemId, result.actualFilename, result.actualFilename),
                    ) == 1L
                if (!proven) throw JournalInvariantViolation("Stored media result lacks its atomic receipt proof")
            }
            is AlignedResult.NoteCreated -> {
                if (
                    parent.activeRequestIndex != result.requestIndex || parent.activeNoteId != result.committedId ||
                    parent.routingPhase != NoteRoutingPhase.POSTCHECK_VERIFIED ||
                    scalarLong(
                        db,
                        """SELECT count(*) FROM mutation_children c JOIN note_receipts r ON r.child_id = c.id
                           WHERE c.parent_id = ? AND c.request_index = ? AND c.state = 'COMMIT_KNOWN' AND r.note_id = ?""".trimIndent(),
                        arrayOf(parent.id.toString(), result.requestIndex.toString(), result.committedId.toString()),
                    ) != 1L
                ) {
                    throw JournalInvariantViolation("Created note result lacks receipt and postcheck proof")
                }
                requireCompleteRoutingProof(db, parent)
            }
            is AlignedResult.NoteCommittedFailed -> {
                val receiptCount =
                    scalarLong(
                        db,
                        """SELECT count(*) FROM mutation_children c JOIN note_receipts r ON r.child_id = c.id
                           WHERE c.parent_id = ? AND c.request_index = ? AND r.note_id = ?""".trimIndent(),
                        arrayOf(parent.id.toString(), result.requestIndex.toString(), result.committedId.toString()),
                    )
                if (receiptCount != 1L) throw JournalInvariantViolation("Committed-failed note lacks known-ID receipt proof")
                val uncertainCardCount =
                    scalarLong(
                        db,
                        """SELECT count(*) FROM mutation_children c JOIN card_commands x ON x.child_id = c.id
                           WHERE c.parent_id = ? AND c.request_index = ? AND c.state = 'COMMIT_UNCERTAIN' AND
                               x.note_id = ?""".trimIndent(),
                        arrayOf(parent.id.toString(), result.requestIndex.toString(), result.committedId.toString()),
                    )
                if (uncertainCardCount != 0L &&
                    (result.rowError.code != JournalErrorCode.POST_COMMIT_UNCERTAIN || result.rowError.retryable)
                ) {
                    throw JournalInvariantViolation("Uncertain card routing requires a non-retryable post-commit result")
                }
            }
            is AlignedResult.NoteUncertain -> {
                val count =
                    scalarLong(
                        db,
                        """SELECT count(*) FROM mutation_children c WHERE c.parent_id = ? AND
                           c.request_index = ? AND c.identity_key = ? AND c.operation_kind = 'NOTE_INSERT' AND
                           c.state = 'COMMIT_UNCERTAIN'""".trimIndent(),
                        arrayOf(parent.id.toString(), result.requestIndex.toString(), result.itemId),
                    )
                if (count != 1L) throw JournalInvariantViolation("Uncertain note row lacks entry-bearing child proof")
            }
            is AlignedResult.NoteDuplicate -> {
                if (mutationChildCount(db, parent.id, result.requestIndex, result.itemId, ChildOperation.NOTE_INSERT) != 0L) {
                    throw JournalInvariantViolation("Duplicate note row has durable insert evidence")
                }
            }
            is AlignedResult.NoteFailed -> {
                val contradictory =
                    scalarLong(
                        db,
                        """SELECT count(*) FROM mutation_children c WHERE c.parent_id = ? AND
                           c.request_index = ? AND c.identity_key = ? AND c.operation_kind = 'NOTE_INSERT' AND
                           (c.state != 'PROVEN_NOT_COMMITTED' OR EXISTS(
                               SELECT 1 FROM provider_attempts a WHERE a.child_id = c.id) OR EXISTS(
                               SELECT 1 FROM note_receipts r WHERE r.child_id = c.id))""".trimIndent(),
                        arrayOf(parent.id.toString(), result.requestIndex.toString(), result.itemId),
                    )
                val children = mutationChildCount(db, parent.id, result.requestIndex, result.itemId, ChildOperation.NOTE_INSERT)
                if (children > 1L || contradictory != 0L) {
                    throw JournalInvariantViolation("Failed note row hides entered or known-ID mutation evidence")
                }
            }
            is AlignedResult.NoteNotAttempted -> {
                if (mutationChildCount(db, parent.id, result.requestIndex, result.itemId, ChildOperation.NOTE_INSERT) != 0L) {
                    throw JournalInvariantViolation("Not-attempted note row has durable insert evidence")
                }
            }
        }
    }

    private fun mutationChildCount(
        db: SQLiteDatabase,
        parentId: Long,
        requestIndex: Int,
        itemId: String,
        operation: ChildOperation,
    ): Long =
        scalarLong(
            db,
            """SELECT count(*) FROM mutation_children WHERE parent_id = ? AND request_index = ? AND
               identity_key = ? AND operation_kind = ?""".trimIndent(),
            arrayOf(parentId.toString(), requestIndex.toString(), itemId, operation.name),
        )

    private fun clearActiveNote(db: SQLiteDatabase, parent: ParentRecord) {
        db.delete("active_notes", "parent_id = ?", arrayOf(parent.id.toString())).requireOne("active note clear")
        db.updateOrThrow(
            "parents",
            values(
                "active_request_index" to null,
                "active_note_id" to null,
                "routing_phase" to null,
                "updated_at_ms" to nextTimestamp(parent.updatedAtMs),
            ),
            "id = ?",
            arrayOf(parent.id.toString()),
        )
    }

    private fun terminalRows(response: JournalResponse): List<AlignedResult> =
        when (response) {
            is JournalResponse.VerifySuccess -> listOf(AlignedResult.TargetVerified())
            is JournalResponse.VerifyError -> listOf(AlignedResult.TargetFailed(response.error))
            is JournalResponse.StoreMedia -> response.results
            is JournalResponse.CreateNotes -> response.results
        }

    private fun requireDurableTerminalEvidence(
        db: SQLiteDatabase,
        parent: ParentRecord,
        response: JournalResponse,
    ) {
        val rows = terminalRows(response)
        val children = childrenForParent(db, parent.id)
        if (children.any { it.state == ChildState.PREPARED }) {
            throw JournalInvariantViolation("Terminal response retains a PREPARED mutation")
        }
        children.forEach { child ->
            when (child.command.operation) {
                ChildOperation.DECK_CREATE -> {
                    val row = rows.singleOrNull()
                        ?: throw JournalInvariantViolation("Deck child lacks singleton verify result")
                    when (child.state) {
                        ChildState.PROVEN_NOT_COMMITTED,
                        ChildState.COMMIT_UNCERTAIN,
                        -> if (row !is AlignedResult.TargetFailed) {
                            throw JournalInvariantViolation("Deck failure evidence is hidden by verify success")
                        }
                        ChildState.POSTCONDITION_FAILED ->
                            throw JournalInvariantViolation("Deck create cannot have a deterministic failed postcondition")
                        ChildState.POSTCONDITION_VERIFIED -> if (row !is AlignedResult.TargetVerified) {
                            throw JournalInvariantViolation("Verified deck postcondition is hidden by verify failure")
                        }
                        ChildState.COMMIT_KNOWN ->
                            throw JournalInvariantViolation("Deck attribution cannot terminalize without reconciliation")
                        ChildState.PREPARED -> error("checked above")
                    }
                    if (child.state == ChildState.COMMIT_UNCERTAIN) {
                        val error = (response as? JournalResponse.VerifyError)?.error
                        if (error?.code != JournalErrorCode.POST_COMMIT_UNCERTAIN || error.retryable) {
                            throw JournalInvariantViolation("Uncertain deck requires non-retryable post-commit error")
                        }
                    }
                }
                ChildOperation.MEDIA_INSERT -> {
                    val index = child.command.requestIndex
                        ?: throw JournalCorruptionException("Media child lost request index")
                    val row = rows[index]
                    val claimId = child.mediaClaimId ?: throw JournalCorruptionException("Media child lost claim")
                    val claim = claimById(db, claimId)
                    when (child.state) {
                        ChildState.PROVEN_NOT_COMMITTED -> {
                            if (child.attemptCount != 0 || child.receipt != null ||
                                row !is AlignedResult.MediaFailed && row !is AlignedResult.MediaNotAttempted ||
                                claim.state !in setOf(MediaClaimState.CLEANED_VERIFIED, MediaClaimState.ACKNOWLEDGED_BY_USER)
                            ) {
                                throw JournalInvariantViolation("Pre-entry media evidence contradicts terminal row")
                            }
                        }
                        ChildState.COMMIT_KNOWN -> {
                            val receipt = child.receipt as? ProviderReceipt.Media
                                ?: throw JournalInvariantViolation("Known media commit lacks receipt")
                            if (row !is AlignedResult.MediaStored || row.actualFilename != receipt.actualFilename ||
                                claim.state != MediaClaimState.STORED || claim.actualFilename != receipt.actualFilename
                            ) {
                                throw JournalInvariantViolation("Known media receipt is hidden by terminal response")
                            }
                        }
                        ChildState.COMMIT_UNCERTAIN -> if (
                            child.attemptCount == 0 || child.receipt != null || row !is AlignedResult.MediaUncertain ||
                            claim.state != MediaClaimState.COMMIT_UNCERTAIN
                        ) {
                            throw JournalInvariantViolation("Entered media uncertainty is hidden or downgraded")
                        }
                        ChildState.POSTCONDITION_VERIFIED,
                        ChildState.POSTCONDITION_FAILED,
                        -> throw JournalInvariantViolation("Media child has an impossible reconciled state")
                        ChildState.PREPARED -> error("checked above")
                    }
                }
                ChildOperation.NOTE_INSERT -> {
                    val index = child.command.requestIndex
                        ?: throw JournalCorruptionException("Note child lost request index")
                    val row = rows[index]
                    when (child.state) {
                        ChildState.PROVEN_NOT_COMMITTED -> if (
                            child.attemptCount != 0 || child.receipt != null || row !is AlignedResult.NoteFailed
                        ) {
                            throw JournalInvariantViolation("Pre-entry note evidence contradicts terminal row")
                        }
                        ChildState.COMMIT_KNOWN -> {
                            val receipt = child.receipt as? ProviderReceipt.Note
                                ?: throw JournalInvariantViolation("Known note commit lacks receipt")
                            val preservedId =
                                when (row) {
                                    is AlignedResult.NoteCreated -> row.committedId
                                    is AlignedResult.NoteCommittedFailed -> row.committedId
                                    else -> null
                                }
                            if (preservedId != receipt.noteId) {
                                throw JournalInvariantViolation("Known note receipt is hidden by failed/uncertain terminal row")
                            }
                        }
                        ChildState.COMMIT_UNCERTAIN -> if (
                            child.attemptCount == 0 || child.receipt != null || row !is AlignedResult.NoteUncertain
                        ) {
                            throw JournalInvariantViolation("Entered note uncertainty is hidden or downgraded")
                        }
                        ChildState.POSTCONDITION_VERIFIED,
                        ChildState.POSTCONDITION_FAILED,
                        -> throw JournalInvariantViolation("Note insert child has an impossible reconciled state")
                        ChildState.PREPARED -> error("checked above")
                    }
                }
                ChildOperation.CARD_DECK_UPDATE -> {
                    val index = child.command.requestIndex
                        ?: throw JournalCorruptionException("Card child lost request index")
                    val row = rows[index]
                    if (row is AlignedResult.NoteCreated && child.state != ChildState.POSTCONDITION_VERIFIED) {
                        throw JournalInvariantViolation("Created note hides incomplete card-routing evidence")
                    }
                    if (row !is AlignedResult.NoteCreated && row !is AlignedResult.NoteCommittedFailed) {
                        throw JournalInvariantViolation("Card mutation evidence lacks a known-ID note outcome")
                    }
                    if (child.state == ChildState.COMMIT_UNCERTAIN) {
                        val failure = row as? AlignedResult.NoteCommittedFailed
                            ?: throw JournalInvariantViolation("Uncertain card routing lacks committed-failed note evidence")
                        val topError = (response as? JournalResponse.CreateNotes)?.error
                        if (
                            failure.rowError.code != JournalErrorCode.POST_COMMIT_UNCERTAIN ||
                            failure.rowError.retryable || topError != failure.rowError
                        ) {
                            throw JournalInvariantViolation(
                                "Uncertain card routing requires matching non-retryable post-commit row and parent errors",
                            )
                        }
                    }
                }
            }
        }
        rows.forEachIndexed { index, row ->
            when (row) {
                is AlignedResult.MediaStored,
                is AlignedResult.MediaUncertain,
                -> if (children.none { it.command.operation == ChildOperation.MEDIA_INSERT && it.command.requestIndex == index }) {
                    throw JournalInvariantViolation("Media mutation result lacks a durable child")
                }
                is AlignedResult.NoteCreated,
                is AlignedResult.NoteCommittedFailed,
                is AlignedResult.NoteUncertain,
                -> if (children.none { it.command.operation == ChildOperation.NOTE_INSERT && it.command.requestIndex == index }) {
                    throw JournalInvariantViolation("Note mutation result lacks a durable insert child")
                }
                else -> Unit
            }
        }
    }

    private fun childrenForParent(db: SQLiteDatabase, parentId: Long): List<ChildRecord> =
        db.query(
            "mutation_children",
            null,
            "parent_id = ?",
            arrayOf(parentId.toString()),
            null,
            null,
            "sequence_number",
        ).use { it.mapRows { row -> childFromCursor(db, row) } }

    private fun metadataFor(parentId: Long, response: JournalResponse) =
        ParentTerminalMetadata(
            parentId,
            response.variant,
            when (response) {
                is JournalResponse.VerifySuccess -> null
                is JournalResponse.VerifyError -> response.error
                is JournalResponse.StoreMedia -> response.error
                is JournalResponse.CreateNotes -> response.error
            },
        )

    private fun insertTerminalMetadata(db: SQLiteDatabase, metadata: ParentTerminalMetadata) {
        db.insertOrThrow(
            "parent_terminal_metadata",
            null,
            values(
                "parent_id" to metadata.parentId,
                "variant_kind" to metadata.variant.name,
                "error_code" to metadata.topLevelError?.code?.name,
                "error_message" to metadata.topLevelError?.message,
                "error_retryable" to metadata.topLevelError?.retryable?.let(::bool),
            ),
        )
    }

    private fun responseByParent(db: SQLiteDatabase, parent: ParentRecord): JournalResponse {
        if (parent.state != ParentState.RESULT_READY) throw JournalCorruptionException("Parent is not replayable")
        val metadata = parentTerminalMetadata(db, parent.id)
            ?: throw JournalCorruptionException("RESULT_READY parent lacks metadata")
        val results = alignedResultsByParent(db, parent.id)
        val requestCount = requestItemsByParent(db, parent.id).size
        if (results.size != requestCount) throw JournalCorruptionException("RESULT_READY parent lacks exact result coverage")
        return when (metadata.variant) {
            TerminalVariant.VERIFY_SUCCESS -> {
                if (metadata.topLevelError != null || results != listOf(AlignedResult.TargetVerified())) {
                    throw JournalCorruptionException("Malformed verify-success journal rows")
                }
                JournalResponse.VerifySuccess(
                    parent.key,
                    targetSnapshotByParent(db, parent.id)
                        ?: throw JournalCorruptionException("Verify success lacks target snapshot"),
                )
            }
            TerminalVariant.VERIFY_ERROR -> {
                val error = metadata.topLevelError ?: throw JournalCorruptionException("Verify error lacks detail")
                if (results != listOf(AlignedResult.TargetFailed(error))) throw JournalCorruptionException("Verify error row disagrees")
                JournalResponse.VerifyError(parent.key, error)
            }
            TerminalVariant.STORE_MEDIA_RESULT -> JournalResponse.StoreMedia(parent.key, results, metadata.topLevelError)
            TerminalVariant.CREATE_NOTES_RESULT -> JournalResponse.CreateNotes(parent.key, results, metadata.topLevelError)
        }
    }

    private fun terminalizeOwnerlessDb(db: SQLiteDatabase, original: ParentRecord): ParentRecord {
        if (original.state == ParentState.RESULT_READY) return original
        if (original.state !in setOf(ParentState.PREPARED, ParentState.RUNNING)) {
            throw JournalInvariantViolation("Only unfinished ownerless work can be terminalized")
        }
        if (preparedChildForParent(db, original.id) != null) {
            throw JournalInvariantViolation("Ownerless PREPARED mutation must be recovered before terminalization")
        }
        var parent = original
        if (
            parent.operation == ParentOperation.CREATE_NOTES &&
            parent.routingPhase == NoteRoutingPhase.POSTCHECK_VERIFIED &&
            parent.activeRequestIndex != null && parent.activeNoteId != null &&
            alignedResultsByParent(db, parent.id).size == parent.activeRequestIndex
        ) {
            parent =
                completeVerifiedNoteDb(
                    db,
                    parent,
                    parent.activeRequestIndex,
                    parent.activeNoteId,
                    "ownerless recovery preserved completed note postcheck",
                )
        }
        val response =
            when (parent.operation) {
                ParentOperation.VERIFY_TARGET -> ownerlessVerifyResponse(db, parent)
                ParentOperation.STORE_MEDIA -> ownerlessMediaResponse(db, parent)
                ParentOperation.CREATE_NOTES -> ownerlessCreateResponse(db, parent)
            }
        val prefix = alignedResultsByParent(db, parent.id)
        val rows = terminalRows(response)
        if (prefix.size > rows.size || prefix != rows.take(prefix.size)) {
            throw JournalCorruptionException("Ownerless terminalization contradicts immutable aligned evidence")
        }
        rows.drop(prefix.size).forEach { appendResultDb(db, parent, it) }
        requireDurableTerminalEvidence(db, parent, response)
        val metadata = metadataFor(parent.id, response)
        parentTerminalMetadata(db, parent.id)?.let { existing ->
            if (existing != metadata) throw JournalCorruptionException("Ownerless terminal metadata differs")
        } ?: insertTerminalMetadata(db, metadata)
        updateParentState(db, parent, ParentState.RESULT_READY)
        return parentById(db, parent.id)
    }

    private fun ownerlessVerifyResponse(db: SQLiteDatabase, parent: ParentRecord): JournalResponse {
        targetSnapshotByParent(db, parent.id)?.let { return JournalResponse.VerifySuccess(parent.key, it) }
        val child = childrenForParent(db, parent.id).singleOrNull()
        val entered = child?.attemptCount?.let { it > 0 } == true
        val error = if (entered) postCommitOwnerlessError() else ownerlessStopError()
        if (entered) {
            ensureRemediationDb(
                db,
                RemediationDraft(
                    parentId = parent.id,
                    kind = RemediationKind.DECK_COMMIT_UNCERTAIN,
                    summary = "Deck creation could not be conclusively reconciled after owner loss",
                    compactEvidence = "request=${parent.key.requestId};digest=${parent.requestSha256}",
                ),
            )
        }
        return JournalResponse.VerifyError(parent.key, error)
    }

    private fun ownerlessMediaResponse(db: SQLiteDatabase, parent: ParentRecord): JournalResponse.StoreMedia {
        val items = requestItemsByParent(db, parent.id)
        val rows = alignedResultsByParent(db, parent.id).toMutableList()
        var error: JournalError? =
            when (val terminal = rows.firstOrNull { it is AlignedResult.MediaUncertain || it is AlignedResult.MediaNotAttempted }) {
                is AlignedResult.MediaUncertain -> postCommitOwnerlessError()
                is AlignedResult.MediaNotAttempted -> ownerlessStopError()
                else -> null
            }
        if (error == null && rows.size < items.size) {
            val index = rows.size
            val item = items[index]
            val child =
                childrenForParent(db, parent.id).singleOrNull {
                    it.command.operation == ChildOperation.MEDIA_INSERT && it.command.requestIndex == index
                }
            when (child?.state) {
                ChildState.COMMIT_UNCERTAIN -> {
                    rows += AlignedResult.MediaUncertain(index, item.itemId, "ownerless entered media mutation")
                    error = postCommitOwnerlessError()
                    val claimId = child.mediaClaimId ?: throw JournalCorruptionException("Uncertain media child lost claim")
                    ensureRemediationDb(
                        db,
                        RemediationDraft(
                            parentId = parent.id,
                            claimId = claimId,
                            kind = RemediationKind.MEDIA_COMMIT_UNCERTAIN,
                            summary = "Media provider commit could not be confirmed after owner loss",
                            compactEvidence = "asset=${item.itemId};request=${parent.key.requestId}",
                        ),
                    )
                }
                ChildState.PROVEN_NOT_COMMITTED -> {
                    rows +=
                        AlignedResult.MediaFailed(
                            index,
                            item.itemId,
                            mediaPreEntryFailure(),
                            "ownerless recovery proved provider was never entered",
                        )
                    if (index < items.lastIndex) error = ownerlessStopError()
                }
                null -> {
                    rows += AlignedResult.MediaNotAttempted(index, item.itemId)
                    error = ownerlessStopError()
                }
                ChildState.COMMIT_KNOWN ->
                    throw JournalCorruptionException("Atomic stored media receipt lacks its aligned row")
                ChildState.POSTCONDITION_VERIFIED,
                ChildState.POSTCONDITION_FAILED,
                -> throw JournalCorruptionException("Media child has impossible terminal state")
                ChildState.PREPARED -> error("blocked above")
            }
        }
        rows.filterIsInstance<AlignedResult.MediaUncertain>().forEach { uncertain ->
            val child =
                childrenForParent(db, parent.id).singleOrNull {
                    it.command.operation == ChildOperation.MEDIA_INSERT &&
                        it.command.requestIndex == uncertain.requestIndex && it.state == ChildState.COMMIT_UNCERTAIN
                } ?: throw JournalCorruptionException("Uncertain media row lost its entry-bearing child")
            val claimId = child.mediaClaimId ?: throw JournalCorruptionException("Uncertain media child lost claim")
            ensureRemediationDb(
                db,
                RemediationDraft(
                    parentId = parent.id,
                    claimId = claimId,
                    kind = RemediationKind.MEDIA_COMMIT_UNCERTAIN,
                    summary = "Media provider commit could not be confirmed after owner loss",
                    compactEvidence = "asset=${uncertain.itemId};request=${parent.key.requestId}",
                ),
            )
        }
        while (rows.size < items.size) {
            rows += AlignedResult.MediaNotAttempted(rows.size, items[rows.size].itemId)
        }
        return JournalResponse.StoreMedia(parent.key, rows, error)
    }

    private fun ownerlessCreateResponse(db: SQLiteDatabase, parent: ParentRecord): JournalResponse.CreateNotes {
        val items = requestItemsByParent(db, parent.id)
        val rows = alignedResultsByParent(db, parent.id).toMutableList()
        var error: JournalError? =
            when (val terminal = rows.firstOrNull {
                it is AlignedResult.NoteFailed || it is AlignedResult.NoteCommittedFailed ||
                    it is AlignedResult.NoteUncertain || it is AlignedResult.NoteNotAttempted
            }) {
                is AlignedResult.NoteFailed -> terminal.rowError
                is AlignedResult.NoteCommittedFailed -> terminal.rowError
                is AlignedResult.NoteUncertain -> postCommitOwnerlessError()
                is AlignedResult.NoteNotAttempted -> ownerlessStopError()
                else -> null
            }
        if (error == null && rows.size < items.size) {
            val index = rows.size
            val item = items[index]
            val child =
                childrenForParent(db, parent.id).singleOrNull {
                    it.command.operation == ChildOperation.NOTE_INSERT && it.command.requestIndex == index
                }
            when (child?.state) {
                ChildState.COMMIT_KNOWN -> {
                    val receipt = child.receipt as? ProviderReceipt.Note
                        ?: throw JournalCorruptionException("Known note child lost its typed receipt")
                    error = postCommitOwnerlessError()
                    rows +=
                        AlignedResult.NoteCommittedFailed(
                            index,
                            item.itemId,
                            receipt.noteId,
                            error,
                            "ownerless recovery preserved known note ID",
                        )
                    ensureRemediationDb(
                        db,
                        RemediationDraft(
                            parentId = parent.id,
                            kind = RemediationKind.NOTE_COMMITTED_FAILED,
                            summary = "A committed note requires review because postchecks did not finish",
                            compactEvidence =
                                "noteId=${receipt.noteId};item=${item.itemId};phase=${parent.routingPhase};digest=${parent.requestSha256}",
                        ),
                    )
                    if (routingIntents(db, parent.id).any { it.state != RoutingIntentState.VERIFIED }) {
                        ensureRemediationDb(
                            db,
                            RemediationDraft(
                                parentId = parent.id,
                                kind = RemediationKind.CARD_ROUTING_FAILED,
                                summary = "Committed note card routing requires review",
                                compactEvidence = "noteId=${receipt.noteId};item=${item.itemId}",
                            ),
                        )
                    }
                }
                ChildState.COMMIT_UNCERTAIN -> {
                    error = postCommitOwnerlessError()
                    rows += AlignedResult.NoteUncertain(index, item.itemId, "ownerless entered note mutation")
                    ensureRemediationDb(
                        db,
                        RemediationDraft(
                            parentId = parent.id,
                            kind = RemediationKind.NOTE_COMMIT_UNCERTAIN,
                            summary = "Note provider commit could not be confirmed after owner loss",
                            compactEvidence = "item=${item.itemId};digest=${parent.requestSha256}",
                        ),
                    )
                }
                ChildState.PROVEN_NOT_COMMITTED, null -> {
                    error = ownerlessStopError()
                    rows += AlignedResult.NoteFailed(index, item.itemId, error, "ownerless before note commit")
                }
                ChildState.POSTCONDITION_VERIFIED,
                ChildState.POSTCONDITION_FAILED,
                -> throw JournalCorruptionException("Note insert child has impossible terminal state")
                ChildState.PREPARED -> error("blocked above")
            }
        }
        rows.filterIsInstance<AlignedResult.NoteUncertain>().forEach { uncertain ->
            val child =
                childrenForParent(db, parent.id).singleOrNull {
                    it.command.operation == ChildOperation.NOTE_INSERT &&
                        it.command.requestIndex == uncertain.requestIndex && it.state == ChildState.COMMIT_UNCERTAIN
                } ?: throw JournalCorruptionException("Uncertain note row lost its entry-bearing child")
            ensureRemediationDb(
                db,
                RemediationDraft(
                    parentId = parent.id,
                    kind = RemediationKind.NOTE_COMMIT_UNCERTAIN,
                    summary = "Note provider commit could not be confirmed after owner loss",
                    compactEvidence = "item=${uncertain.itemId};digest=${parent.requestSha256}",
                ),
            )
        }
        while (rows.size < items.size) {
            rows += AlignedResult.NoteNotAttempted(rows.size, items[rows.size].itemId)
        }
        return JournalResponse.CreateNotes(parent.key, rows, error)
    }

    private fun ownerlessStopError() =
        JournalError(JournalErrorCode.CANCELLED, "Run owner ended before the operation completed", retryable = false)

    private fun mediaPreEntryFailure() =
        JournalError(
            JournalErrorCode.MEDIA_STORE_FAILED,
            "Media provider was never entered before the run owner ended",
            retryable = false,
        )

    private fun postCommitOwnerlessError() =
        JournalError(
            JournalErrorCode.POST_COMMIT_UNCERTAIN,
            "Provider mutation committed but its complete postcondition could not be observed",
            retryable = false,
        )

    private fun releaseRunCapabilitiesDb(db: SQLiteDatabase, runId: String) {
        db.execSQL(
            """UPDATE media_reservations SET state = 'RELEASED', updated_at_ms = updated_at_ms + 1
               WHERE run_id = ? AND state = 'RESERVED'""".trimIndent(),
            arrayOf(runId),
        )
        db.execSQL(
            """UPDATE media_leases SET state = 'RELEASED', updated_at_ms = updated_at_ms + 1
               WHERE run_id = ? AND state = 'ACTIVE'""".trimIndent(),
            arrayOf(runId),
        )
        db.execSQL(
            """UPDATE staging_artifacts SET state = 'CLEANUP_PENDING',
               compact_evidence = 'run cleanup scheduled durable staging cleanup',
               updated_at_ms = updated_at_ms + 1
               WHERE run_id = ? AND state IN ('STAGED', 'GRANTED')""".trimIndent(),
            arrayOf(runId),
        )
    }

    private fun ensureRemediationDb(db: SQLiteDatabase, draft: RemediationDraft): RemediationRecord {
        val identityClauses = ArrayList<String>(4)
        val identityArgs = ArrayList<String>(4)
        fun exactNullableId(column: String, value: Long?) {
            if (value == null) {
                identityClauses += "$column IS NULL"
            } else {
                identityClauses += "$column = CAST(? AS INTEGER)"
                identityArgs += value.toString()
            }
        }
        exactNullableId("parent_id", draft.parentId)
        exactNullableId("claim_id", draft.claimId)
        if (draft.stagingId == null) {
            identityClauses += "staging_id IS NULL AND staging_subject_id IS NULL"
        } else {
            identityClauses += "COALESCE(staging_id, staging_subject_id) = CAST(? AS INTEGER)"
            identityArgs += draft.stagingId.toString()
        }
        identityClauses += "kind = ?"
        identityArgs += draft.kind.name
        val matches =
            db.query(
                "remediations",
                null,
                identityClauses.joinToString(" AND "),
                identityArgs.toTypedArray(),
                null,
                null,
                null,
            ).use { it.mapRows(::remediationFromCursor) }
        if (matches.size > 1) throw JournalCorruptionException("Remediation identity is not unique")
        matches.singleOrNull()?.let { existing ->
            if (existing.state != RemediationState.OPEN) {
                throw JournalInvariantViolation("A resolved remediation cannot be reopened")
            }
            return existing
        }
        val now = timestamp()
        val id =
            db.insertOrThrow(
                "remediations",
                null,
                values(
                    "parent_id" to draft.parentId,
                    "claim_id" to draft.claimId,
                    "staging_id" to draft.stagingId,
                    "staging_subject_id" to draft.stagingId,
                    "kind" to draft.kind.name,
                    "state" to RemediationState.OPEN.name,
                    "summary" to draft.summary,
                    "compact_evidence" to draft.compactEvidence,
                    "created_at_ms" to now,
                    "updated_at_ms" to now,
                ),
            )
        return remediationById(db, id)
    }

    private fun finalizeAndScrub(db: SQLiteDatabase, parent: ParentRecord, finalState: ParentState) {
        if (finalState !in setOf(ParentState.RESPONSE_ACKNOWLEDGED, ParentState.ABANDONED)) {
            throw JournalInvariantViolation("Invalid cleanup state")
        }
        if (preparedChildForParent(db, parent.id) != null) throw JournalInvariantViolation("Cannot scrub PREPARED mutation")
        if (parent.state != ParentState.RESULT_READY) {
            throw JournalInvariantViolation("Cleanup must terminalize exact aligned evidence before scrubbing")
        }
        ensureUnattachedMediaRemediationsDb(db, parent, finalState)
        val variant = parentTerminalMetadata(db, parent.id)?.variant
        val resultCount = scalarLong(db, "SELECT count(*) FROM aligned_results WHERE parent_id = ?", arrayOf(parent.id.toString()))
        val childCount = scalarLong(db, "SELECT count(*) FROM mutation_children WHERE parent_id = ?", arrayOf(parent.id.toString()))
        val finalTimestamp = nextTimestamp(parent.updatedAtMs)
        db.insertOrThrow(
            "terminal_parent_audit",
            null,
            values(
                "parent_id" to parent.id,
                "final_state" to finalState.name,
                "terminal_variant" to variant?.name,
                "result_count" to resultCount,
                "child_count" to childCount,
                "finalized_at_ms" to finalTimestamp,
                ),
            )
        db.execSQL(
            """INSERT INTO terminal_result_audit (
                   parent_id, request_index, item_id, status_kind, committed_id, actual_filename,
                   error_code, error_retryable, compact_evidence)
               SELECT parent_id, request_index, item_id, status_kind, committed_id, actual_filename,
                   error_code, error_retryable, compact_evidence
               FROM aligned_results WHERE parent_id = ?""".trimIndent(),
            arrayOf(parent.id),
        )
        if (parent.operation == ParentOperation.VERIFY_TARGET) {
            db.execSQL(
                """INSERT INTO terminal_target_audit (
                       parent_id, status_kind, expected_deck_name, model_id, model_name,
                       verified_deck_id, verified_deck_name, returned_deck_id)
                   SELECT p.id, r.status_kind, t.expected_deck_name, t.model_id, t.model_name,
                       v.deck_id, v.deck_name,
                       (SELECT x.deck_id FROM mutation_children c JOIN deck_receipts x ON x.child_id = c.id
                           WHERE c.parent_id = p.id AND c.operation_kind = 'DECK_CREATE')
                   FROM parents p
                   JOIN aligned_results r ON r.parent_id = p.id AND r.request_index = 0
                   LEFT JOIN target_expectations t ON t.parent_id = p.id
                   LEFT JOIN verified_target_decks v ON v.parent_id = p.id
                   WHERE p.id = ? AND p.operation_kind = 'VERIFY_TARGET'""".trimIndent(),
                arrayOf(parent.id),
            )
        }
        db.rawQuery(
            "SELECT status_kind, count(*) FROM aligned_results WHERE parent_id = ? GROUP BY status_kind",
            arrayOf(parent.id.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                db.insertOrThrow(
                    "terminal_outcome_audit",
                    null,
                    values(
                        "parent_id" to parent.id,
                        "status_kind" to cursor.requiredString(0),
                        "row_count" to cursor.requiredLong(1),
                    ),
                )
            }
        }
        val receiptUnion =
            """SELECT c.operation_kind, count(*) FROM mutation_children c WHERE c.parent_id = ? AND (
                EXISTS(SELECT 1 FROM deck_receipts r WHERE r.child_id = c.id) OR
                EXISTS(SELECT 1 FROM media_receipts r WHERE r.child_id = c.id) OR
                EXISTS(SELECT 1 FROM note_receipts r WHERE r.child_id = c.id) OR
                EXISTS(SELECT 1 FROM card_receipts r WHERE r.child_id = c.id)) GROUP BY c.operation_kind""".trimIndent()
        db.rawQuery(receiptUnion, arrayOf(parent.id.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                db.insertOrThrow(
                    "terminal_receipt_audit",
                    null,
                    values(
                        "parent_id" to parent.id,
                        "operation_kind" to cursor.requiredString(0),
                        "receipt_count" to cursor.requiredLong(1),
                    ),
                )
            }
        }
        db.updateOrThrow(
            "parents",
            values(
                "state" to finalState.name,
                "active_request_index" to null,
                "active_note_id" to null,
                "routing_phase" to null,
                "has_target_expectation" to 0,
                "updated_at_ms" to finalTimestamp,
            ),
            "id = ?",
            arrayOf(parent.id.toString()),
        )
        db.delete("card_commands", "child_id IN (SELECT id FROM mutation_children WHERE parent_id = ?)", arrayOf(parent.id.toString()))
        db.delete("routing_intents", "parent_id = ?", arrayOf(parent.id.toString()))
        db.delete("mutation_children", "parent_id = ?", arrayOf(parent.id.toString()))
        db.delete("active_notes", "parent_id = ?", arrayOf(parent.id.toString()))
        db.delete("target_expectations", "parent_id = ?", arrayOf(parent.id.toString()))
        db.delete("parent_terminal_metadata", "parent_id = ?", arrayOf(parent.id.toString()))
        db.delete("aligned_results", "parent_id = ?", arrayOf(parent.id.toString()))
        db.delete("parent_request_items", "parent_id = ?", arrayOf(parent.id.toString()))
    }

    private fun ensureUnattachedMediaRemediationsDb(
        db: SQLiteDatabase,
        parent: ParentRecord,
        finalState: ParentState,
    ) {
        if (parent.operation != ParentOperation.STORE_MEDIA) return
        val claims =
            db.query(
                "media_claims",
                null,
                "run_id = ? AND request_id = ? AND state IN (?, ?)",
                arrayOf(
                    parent.key.runId,
                    parent.key.requestId,
                    MediaClaimState.STORED.name,
                    MediaClaimState.PRESENT_BYTES_VERIFIED.name,
                ),
                null,
                null,
                "created_at_ms, id",
            ).use { it.mapRows(::claimFromCursor) }
        claims.forEach { claim ->
            ensureRemediationDb(
                db,
                RemediationDraft(
                    parentId = parent.id,
                    claimId = claim.id,
                    kind = RemediationKind.MEDIA_STORED_UNATTACHED,
                    summary = "Stored Anki media was not attached to a verified note",
                    compactEvidence =
                        "finalState=${finalState.name};request=${parent.key.requestId};" +
                            "asset=${claim.assetId};actual=${claim.actualFilename}",
                ),
            )
        }
    }

    private fun requireRequestSelfConsistent(request: JournalRequest) {
        val recomputed = AnkiRequestDigest.compute(request.protocolRequest)
        if (recomputed != request.digest) throw JournalInvariantViolation("Journal request digest changed after validation")
        val rebuilt = JournalRequest.from(request.protocolRequest)
        if (rebuilt.key != request.key || rebuilt.operation != request.operation || rebuilt.itemIds != request.itemIds) {
            throw JournalInvariantViolation("Journal request identity changed after validation")
        }
    }

    private fun requireParentMatchesRequest(db: SQLiteDatabase, parent: ParentRecord, request: JournalRequest) {
        if (
            parent.operation != request.operation || parent.digestVersion != request.digest.digestVersion ||
            parent.requestSha256 != request.digest.sha256 ||
            requestItemsByParent(db, parent.id).map { it.itemId } != request.itemIds
        ) {
            throw JournalInvariantViolation("Existing parent request identity differs")
        }
    }

    private fun parentByKey(db: SQLiteDatabase, key: ParentKey): ParentRecord? =
        db.query("parents", null, "run_id = ? AND request_id = ?", arrayOf(key.runId, key.requestId), null, null, null)
            .use { cursor -> cursor.singleOrNull(::parentFromCursor, "parent key") }

    private fun parentById(db: SQLiteDatabase, id: Long): ParentRecord =
        db.query("parents", null, "id = ?", arrayOf(id.toString()), null, null, null)
            .use { it.requireSingle(::parentFromCursor, "parent $id") }

    private fun parentFromCursor(cursor: Cursor): ParentRecord {
        val record =
            ParentRecord(
                id = cursor.long("id"),
                key = ParentKey(cursor.string("run_id"), cursor.string("request_id")),
                operation = cursor.enum("operation_kind"),
                digestVersion = cursor.int("digest_version"),
                requestSha256 = cursor.string("request_sha256"),
                state = cursor.enum("state"),
                activeRequestIndex = cursor.nullableInt("active_request_index"),
                activeNoteId = cursor.nullableLong("active_note_id"),
                routingPhase = cursor.nullableEnum<NoteRoutingPhase>("routing_phase"),
                hasTargetExpectation = cursor.boolean("has_target_expectation"),
                createdAtMs = cursor.long("created_at_ms"),
                updatedAtMs = cursor.long("updated_at_ms"),
            )
        if (record.digestVersion != AnkiRequestDigest.VERSION) throw JournalCorruptionException("Unsupported durable digest version")
        requireSha256(record.requestSha256, "durable request digest")
        return record
    }

    private fun requestItemsByParent(db: SQLiteDatabase, parentId: Long): List<ParentRequestItem> =
        db.query(
            "parent_request_items",
            null,
            "parent_id = ?",
            arrayOf(parentId.toString()),
            null,
            null,
            "request_index",
        ).use { cursor ->
            cursor.mapRows {
                ParentRequestItem(it.long("parent_id"), it.int("request_index"), it.string("item_id"))
            }.also { rows ->
                if (rows.map { it.requestIndex } != rows.indices.toList()) throw JournalCorruptionException("Request item indexes are not contiguous")
            }
        }

    private fun requestItem(db: SQLiteDatabase, parentId: Long, index: Int): ParentRequestItem =
        db.query(
            "parent_request_items",
            null,
            "parent_id = ? AND request_index = ?",
            arrayOf(parentId.toString(), index.toString()),
            null,
            null,
            null,
        ).use { cursor ->
            cursor.requireSingle(
                { ParentRequestItem(it.long("parent_id"), it.int("request_index"), it.string("item_id")) },
                "request item",
            )
        }

    private fun storeTargetSnapshotDb(
        db: SQLiteDatabase,
        parent: ParentRecord,
        snapshot: DurableTargetSnapshot,
    ): ParentRecord {
        val expectation = targetExpectationByParent(db, parent.id)
        if (expectation == null) {
            insertTargetExpectation(db, parent, snapshot.expectation)
        } else if (expectation != snapshot.expectation) {
            throw JournalInvariantViolation("Verified target differs from frozen pre-target expectation")
        }
        targetSnapshotByParent(db, parent.id)?.let { existing ->
            if (existing != snapshot) throw JournalInvariantViolation("Verified target deck is immutable")
            return parentById(db, parent.id)
        }
        db.insertOrThrow(
            "verified_target_decks",
            null,
            values(
                "parent_id" to parent.id,
                "deck_id" to snapshot.deck.id,
                "deck_name" to snapshot.deck.name,
                "deck_dynamic" to bool(snapshot.deck.dynamic),
            ),
        )
        return parentById(db, parent.id)
    }

    private fun insertTargetExpectation(
        db: SQLiteDatabase,
        parent: ParentRecord,
        expectation: DurableTargetExpectation,
    ) {
        db.insertOrThrow(
            "target_expectations",
            null,
            values(
                "parent_id" to parent.id,
                "expected_deck_name" to expectation.expectedDeckName,
                "model_id" to expectation.model.id,
                "model_name" to expectation.model.name,
                "model_type" to expectation.model.type,
                "field_count" to expectation.model.fieldNames.size,
                "card_count" to expectation.model.cardCount,
                "sort_field_index" to expectation.model.sortFieldIndex,
                "effective_default_deck_id" to expectation.model.effectiveDefaultDeckId,
                "css" to expectation.model.css,
                "latex_pre" to expectation.model.latexPre,
                "latex_post" to expectation.model.latexPost,
            ),
        )
        expectation.model.fieldNames.forEachIndexed { ordinal, field ->
            db.insertOrThrow(
                "target_expectation_fields",
                null,
                values("parent_id" to parent.id, "field_ordinal" to ordinal, "field_name" to field),
            )
        }
        expectation.model.templates.forEach { template ->
            db.insertOrThrow(
                "target_expectation_templates",
                null,
                values(
                    "parent_id" to parent.id,
                    "template_ordinal" to template.ordinal,
                    "model_id" to template.modelId,
                    "template_name" to template.name,
                    "question_format" to template.questionFormat,
                    "answer_format" to template.answerFormat,
                    "browser_question_format" to template.browserQuestionFormat,
                    "browser_answer_format" to template.browserAnswerFormat,
                ),
            )
        }
        db.updateOrThrow(
            "parents",
            values("has_target_expectation" to 1, "updated_at_ms" to nextTimestamp(parent.updatedAtMs)),
            "id = ?",
            arrayOf(parent.id.toString()),
        )
    }

    private fun targetExpectationByParent(db: SQLiteDatabase, parentId: Long): DurableTargetExpectation? =
        db.query("target_expectations", null, "parent_id = ?", arrayOf(parentId.toString()), null, null, null).use { cursor ->
            cursor.singleOrNull(
                { row ->
                    val fields =
                        db.query("target_expectation_fields", null, "parent_id = ?", arrayOf(parentId.toString()), null, null, "field_ordinal")
                            .use { it.mapRows { field -> field.string("field_name") } }
                    val templates =
                        db.query("target_expectation_templates", null, "parent_id = ?", arrayOf(parentId.toString()), null, null, "template_ordinal")
                            .use { templateCursor ->
                                templateCursor.mapRows {
                                    DurableTemplateSnapshot(
                                        modelId = it.long("model_id"),
                                        ordinal = it.int("template_ordinal"),
                                        name = it.string("template_name"),
                                        questionFormat = it.string("question_format"),
                                        answerFormat = it.string("answer_format"),
                                        browserQuestionFormat = it.nullableString("browser_question_format"),
                                        browserAnswerFormat = it.nullableString("browser_answer_format"),
                                    )
                                }
                            }
                    DurableTargetExpectation(
                        row.string("expected_deck_name"),
                        DurableModelSnapshot(
                            id = row.long("model_id"),
                            name = row.string("model_name"),
                            type = row.int("model_type"),
                            fieldNames = fields,
                            cardCount = row.int("card_count"),
                            sortFieldIndex = row.int("sort_field_index"),
                            effectiveDefaultDeckId = row.long("effective_default_deck_id"),
                            css = row.string("css"),
                            latexPre = row.nullableString("latex_pre"),
                            latexPost = row.nullableString("latex_post"),
                            templates = templates,
                        ),
                    )
                },
                "target expectation",
            )
        }

    private fun targetSnapshotByParent(db: SQLiteDatabase, parentId: Long): DurableTargetSnapshot? {
        val expectation = targetExpectationByParent(db, parentId) ?: return null
        val deck =
            db.query("verified_target_decks", null, "parent_id = ?", arrayOf(parentId.toString()), null, null, null).use {
                it.singleOrNull(
                    { row -> DurableDeckSnapshot(row.long("deck_id"), row.string("deck_name"), row.boolean("deck_dynamic")) },
                    "verified target deck",
                )
            } ?: return null
        if (deck.name != expectation.expectedDeckName) throw JournalCorruptionException("Verified deck differs from expectation")
        return DurableTargetSnapshot(deck, expectation.model)
    }

    private fun activeNoteByParent(db: SQLiteDatabase, parentId: Long): ActiveNoteRecord? =
        db.query("active_notes", null, "parent_id = ?", arrayOf(parentId.toString()), null, null, null).use { cursor ->
            cursor.singleOrNull(
                { row ->
                    val fields =
                        db.query("active_note_fields", null, "parent_id = ?", arrayOf(parentId.toString()), null, null, "field_ordinal")
                            .use { it.mapRows { field -> OrderedNoteField(field.string("field_name"), field.string("field_value")) } }
                    val tags =
                        db.query("active_note_tags", null, "parent_id = ?", arrayOf(parentId.toString()), null, null, "tag_ordinal")
                            .use { it.mapRows { tag -> tag.string("tag") } }
                    val bindings =
                        db.query("active_note_media_bindings", null, "parent_id = ?", arrayOf(parentId.toString()), null, null, "binding_ordinal")
                            .use {
                                it.mapRows { binding ->
                                    DurableMediaBinding(
                                        binding.string("asset_id"),
                                        binding.string("actual_filename"),
                                        binding.long("claim_id"),
                                    )
                                }
                            }
                    ActiveNoteRecord(
                        parentId,
                        ActiveNoteMaterialization(
                            requestIndex = row.int("request_index"),
                            clientNoteId = row.string("client_note_id"),
                            orderedFields = fields,
                            joinedFields = row.string("joined_fields"),
                            normalizedTags = tags,
                            providerTagsWire = row.string("provider_tags_wire"),
                            duplicateDecision = DurableDuplicateDecision(
                                row.string("duplicate_key"),
                                row.string("duplicate_first_field"),
                                row.int("duplicate_occurrence"),
                                row.boolean("is_duplicate"),
                            ),
                            mediaBindings = bindings,
                        ),
                        row.string("item_sha256"),
                        row.long("created_at_ms"),
                        row.long("updated_at_ms"),
                    )
                },
                "active note",
            )
        }

    private fun childById(db: SQLiteDatabase, id: Long): ChildRecord =
        db.query("mutation_children", null, "id = ?", arrayOf(id.toString()), null, null, null)
            .use { it.requireSingle({ row -> childFromCursor(db, row) }, "child $id") }

    private fun childFromCursor(db: SQLiteDatabase, row: Cursor): ChildRecord {
        val id = row.long("id")
        val operation: ChildOperation = row.enum("operation_kind")
        val requestIndex = row.nullableInt("request_index")
        val command: MutationCommand =
            when (operation) {
                ChildOperation.DECK_CREATE ->
                    db.query("deck_commands", null, "child_id = ?", arrayOf(id.toString()), null, null, null).use {
                        it.requireSingle({ command -> MutationCommand.CreateDeck(command.string("deck_name")) }, "deck command")
                    }
                ChildOperation.MEDIA_INSERT ->
                    db.query("media_commands", null, "child_id = ?", arrayOf(id.toString()), null, null, null).use {
                        it.requireSingle(
                            { command ->
                                MutationCommand.StoreMedia(
                                    checkNotNull(requestIndex),
                                    command.string("asset_id"),
                                    command.string("file_uri"),
                                    command.string("preferred_name"),
                                )
                            },
                            "media command",
                        )
                    }
                ChildOperation.NOTE_INSERT ->
                    db.query("note_commands", null, "child_id = ?", arrayOf(id.toString()), null, null, null).use {
                        it.requireSingle(
                            { command ->
                                MutationCommand.InsertNote(
                                    checkNotNull(requestIndex),
                                    command.string("client_note_id"),
                                    command.long("model_id"),
                                    command.string("joined_fields"),
                                    command.string("provider_tags_wire"),
                                )
                            },
                            "note command",
                        )
                    }
                ChildOperation.CARD_DECK_UPDATE ->
                    db.query("card_commands", null, "child_id = ?", arrayOf(id.toString()), null, null, null).use {
                        it.requireSingle(
                            { command ->
                                MutationCommand.RouteCard(
                                    command.long("intent_id"),
                                    checkNotNull(requestIndex),
                                    command.long("card_id"),
                                    command.long("note_id"),
                                    command.int("ordinal"),
                                    command.long("target_deck_id"),
                                    command.long("pre_update_deck_id"),
                                )
                            },
                            "card command",
                        )
                    }
            }
        val attempts =
            db.query("provider_attempts", null, "child_id = ?", arrayOf(id.toString()), null, null, "attempt_number").use {
                it.mapRows { attempt ->
                    ProviderAttempt(id, attempt.int("attempt_number"), attempt.boolean("recovery_reissue"), attempt.long("entered_at_ms"))
                }
            }
        if (attempts.map { it.attemptNumber } != (1..attempts.size).toList()) throw JournalCorruptionException("Provider attempts are not contiguous")
        val receipt = receiptForChild(db, id, operation)
        return ChildRecord(
            id = id,
            parentId = row.long("parent_id"),
            sequence = row.int("sequence_number"),
            digestVersion = row.int("digest_version"),
            requestSha256 = row.string("request_sha256"),
            itemSha256 = row.nullableString("item_sha256"),
            command = command,
            mediaClaimId = row.nullableLong("media_claim_id"),
            state = row.enum("state"),
            attempts = attempts,
            receipt = receipt,
            terminalEvidence = row.nullableString("terminal_evidence"),
            createdAtMs = row.long("created_at_ms"),
            updatedAtMs = row.long("updated_at_ms"),
        )
    }

    private fun receiptForChild(db: SQLiteDatabase, childId: Long, operation: ChildOperation): ProviderReceipt? =
        when (operation) {
            ChildOperation.DECK_CREATE ->
                db.query("deck_receipts", null, "child_id = ?", arrayOf(childId.toString()), null, null, null).use {
                    it.singleOrNull({ row -> ProviderReceipt.Deck(row.long("deck_id"), row.string("content_uri")) }, "deck receipt")
                }
            ChildOperation.MEDIA_INSERT ->
                db.query("media_receipts", null, "child_id = ?", arrayOf(childId.toString()), null, null, null).use {
                    it.singleOrNull({ row -> ProviderReceipt.Media(row.string("actual_filename"), row.string("file_uri")) }, "media receipt")
                }
            ChildOperation.NOTE_INSERT ->
                db.query("note_receipts", null, "child_id = ?", arrayOf(childId.toString()), null, null, null).use {
                    it.singleOrNull({ row -> ProviderReceipt.Note(row.long("note_id"), row.string("content_uri")) }, "note receipt")
                }
            ChildOperation.CARD_DECK_UPDATE ->
                db.query("card_receipts", null, "child_id = ?", arrayOf(childId.toString()), null, null, null).use {
                    it.singleOrNull({ ProviderReceipt.CardAffectedOne }, "card receipt")
                }
        }

    /** Returns true when the typed receipt still needs insertion. */
    private fun requireReceiptTarget(
        child: ChildRecord,
        receipt: ProviderReceipt,
        allowExisting: Boolean,
    ): Boolean {
        if (child.state != ChildState.PREPARED || child.command.operation != receipt.operation || child.attemptCount == 0) {
            throw JournalInvariantViolation("Typed receipt is not attributable exactly once")
        }
        val existing = child.receipt ?: return true
        if (!allowExisting || existing != receipt) {
            throw JournalInvariantViolation("Typed receipt is not attributable exactly once")
        }
        return false
    }

    private fun insertDeckReceipt(db: SQLiteDatabase, childId: Long, receipt: ProviderReceipt.Deck) {
        db.insertOrThrow("deck_receipts", null, values("child_id" to childId, "deck_id" to receipt.deckId, "content_uri" to receipt.contentUri))
    }

    private fun insertMediaReceipt(db: SQLiteDatabase, childId: Long, receipt: ProviderReceipt.Media) {
        validateMediaNames(receipt.actualFilename, receipt.actualFilename)
        db.insertOrThrow(
            "media_receipts",
            null,
            values("child_id" to childId, "actual_filename" to receipt.actualFilename, "file_uri" to receipt.fileUri),
        )
    }

    private fun insertNoteReceipt(db: SQLiteDatabase, childId: Long, receipt: ProviderReceipt.Note) {
        db.insertOrThrow("note_receipts", null, values("child_id" to childId, "note_id" to receipt.noteId, "content_uri" to receipt.contentUri))
    }

    private fun updateChildTerminal(db: SQLiteDatabase, child: ChildRecord, state: ChildState, evidence: String) {
        db.updateOrThrow(
            "mutation_children",
            values(
                "state" to state.name,
                "terminal_evidence" to evidence,
                "updated_at_ms" to nextTimestamp(child.updatedAtMs),
            ),
            "id = ?",
            arrayOf(child.id.toString()),
        )
    }

    private fun routingIntentById(db: SQLiteDatabase, id: Long): RoutingIntentRecord =
        db.query("routing_intents", null, "id = ?", arrayOf(id.toString()), null, null, null)
            .use { it.requireSingle(::routingIntentFromCursor, "routing intent $id") }

    private fun routingIntents(db: SQLiteDatabase, parentId: Long): List<RoutingIntentRecord> =
        db.query("routing_intents", null, "parent_id = ?", arrayOf(parentId.toString()), null, null, "ordinal, id")
            .use { it.mapRows(::routingIntentFromCursor) }

    private fun routingIntentsForNote(
        db: SQLiteDatabase,
        parentId: Long,
        requestIndex: Int,
        noteId: Long,
    ): List<RoutingIntentRecord> =
        db.query(
            "routing_intents",
            null,
            "parent_id = ? AND request_index = ? AND note_id = ?",
            arrayOf(parentId.toString(), requestIndex.toString(), noteId.toString()),
            null,
            null,
            "ordinal, id",
        ).use { it.mapRows(::routingIntentFromCursor) }

    private fun routingIntentFromCursor(row: Cursor) =
        RoutingIntentRecord(
            row.long("id"),
            row.long("parent_id"),
            row.int("request_index"),
            row.long("card_id"),
            row.long("note_id"),
            row.int("ordinal"),
            row.long("target_deck_id"),
            row.long("pre_update_deck_id"),
            row.nullableLong("child_id"),
            row.enum("state"),
            row.nullableString("terminal_evidence"),
            row.long("created_at_ms"),
            row.long("updated_at_ms"),
        )

    private fun alignedResultsByParent(db: SQLiteDatabase, parentId: Long): List<AlignedResult> =
        db.query("aligned_results", null, "parent_id = ?", arrayOf(parentId.toString()), null, null, "request_index")
            .use { cursor -> cursor.mapRows(::alignedResultFromCursor) }

    private fun alignedResultFromCursor(row: Cursor): AlignedResult {
        val index = row.int("request_index")
        val itemId = row.string("item_id")
        val status: AlignedStatus = row.enum("status_kind")
        val evidence = row.nullableString("compact_evidence")
        val error = row.errorOrNull()
        return when (status) {
            AlignedStatus.VERIFIED -> AlignedResult.TargetVerified(index, itemId)
            AlignedStatus.STORED -> AlignedResult.MediaStored(index, itemId, row.string("actual_filename"), evidence)
            AlignedStatus.FAILED -> {
                val detail = error ?: throw JournalCorruptionException("Failed row lacks error")
                when (parentOperationForResult(row)) {
                    ParentOperation.VERIFY_TARGET -> AlignedResult.TargetFailed(detail, index, itemId)
                    ParentOperation.STORE_MEDIA -> AlignedResult.MediaFailed(index, itemId, detail, evidence)
                    ParentOperation.CREATE_NOTES -> AlignedResult.NoteFailed(index, itemId, detail, evidence)
                }
            }
            AlignedStatus.UNCERTAIN ->
                when (parentOperationForResult(row)) {
                    ParentOperation.STORE_MEDIA -> AlignedResult.MediaUncertain(index, itemId, evidence)
                    ParentOperation.CREATE_NOTES -> AlignedResult.NoteUncertain(index, itemId, evidence)
                    else -> throw JournalCorruptionException("Verify result cannot be uncertain")
                }
            AlignedStatus.NOT_ATTEMPTED ->
                when (parentOperationForResult(row)) {
                    ParentOperation.STORE_MEDIA -> AlignedResult.MediaNotAttempted(index, itemId)
                    ParentOperation.CREATE_NOTES -> AlignedResult.NoteNotAttempted(index, itemId)
                    else -> throw JournalCorruptionException("Verify result cannot be notAttempted")
                }
            AlignedStatus.CREATED -> AlignedResult.NoteCreated(index, itemId, row.long("committed_id"), evidence)
            AlignedStatus.DUPLICATE -> AlignedResult.NoteDuplicate(index, itemId)
            AlignedStatus.COMMITTED_FAILED ->
                AlignedResult.NoteCommittedFailed(
                    index,
                    itemId,
                    row.long("committed_id"),
                    error ?: throw JournalCorruptionException("Committed-failed row lacks error"),
                    evidence,
                )
        }
    }

    /** aligned_results has no operation column; exact parent lookup is deliberate and fail-closed. */
    private fun parentOperationForResult(row: Cursor): ParentOperation {
        val parentId = row.long("parent_id")
        val db = readableDatabase
        return db.rawQuery("SELECT operation_kind FROM parents WHERE id = ?", arrayOf(parentId.toString())).use {
            enumValueOf(it.requireSingle({ cursor -> cursor.requiredString(0) }, "result parent operation"))
        }
    }

    private fun parentTerminalMetadata(db: SQLiteDatabase, parentId: Long): ParentTerminalMetadata? =
        db.query("parent_terminal_metadata", null, "parent_id = ?", arrayOf(parentId.toString()), null, null, null).use {
            it.singleOrNull(
                { row -> ParentTerminalMetadata(parentId, row.enum("variant_kind"), row.errorOrNull()) },
                "parent terminal metadata",
            )
        }

    private fun parentsForRun(db: SQLiteDatabase, runId: String): List<ParentRecord> =
        db.query("parents", null, "run_id = ?", arrayOf(runId), null, null, "created_at_ms, id")
            .use { cursor -> cursor.mapRows(::parentFromCursor) }

    private fun recoveryParentsDb(db: SQLiteDatabase): List<ParentRecord> =
        db.query(
            "parents",
            null,
            "state NOT IN (?, ?)",
            arrayOf(ParentState.RESPONSE_ACKNOWLEDGED.name, ParentState.ABANDONED.name),
            null,
            null,
            "created_at_ms, id",
        ).use { cursor -> cursor.mapRows(::parentFromCursor) }

    private fun preparedChildDb(db: SQLiteDatabase): ChildRecord? =
        db.query("mutation_children", null, "state = ?", arrayOf(ChildState.PREPARED.name), null, null, null).use {
            it.singleOrNull({ row -> childFromCursor(db, row) }, "global PREPARED child")
        }

    private fun preparedChildForParent(db: SQLiteDatabase, parentId: Long): ChildRecord? =
        db.query(
            "mutation_children",
            null,
            "parent_id = ? AND state = ?",
            arrayOf(parentId.toString(), ChildState.PREPARED.name),
            null,
            null,
            null,
        ).use { it.singleOrNull({ row -> childFromCursor(db, row) }, "parent PREPARED child") }

    private fun preparedChildForRun(db: SQLiteDatabase, runId: String): ChildRecord? =
        db.rawQuery(
            """SELECT c.* FROM mutation_children c JOIN parents p ON p.id = c.parent_id
               WHERE p.run_id = ? AND c.state = 'PREPARED'""".trimIndent(),
            arrayOf(runId),
        ).use { it.singleOrNull({ row -> childFromCursor(db, row) }, "run PREPARED child") }

    private fun preparedRoutingIntent(db: SQLiteDatabase, childId: Long): RoutingIntentRecord? =
        db.query(
            "routing_intents",
            null,
            "child_id = ? AND state = ?",
            arrayOf(childId.toString(), RoutingIntentState.UPDATE_PREPARED.name),
            null,
            null,
            null,
        ).use { it.singleOrNull(::routingIntentFromCursor, "PREPARED routing intent") }

    private fun leaseByRun(db: SQLiteDatabase, runId: String): MediaLeaseRecord? =
        db.query("media_leases", null, "run_id = ?", arrayOf(runId), null, null, null).use {
            it.singleOrNull({ row -> leaseFromCursor(db, row) }, "media lease")
        }

    private fun leaseById(db: SQLiteDatabase, id: Long): MediaLeaseRecord =
        db.query("media_leases", null, "id = ?", arrayOf(id.toString()), null, null, null).use {
            it.requireSingle({ row -> leaseFromCursor(db, row) }, "media lease $id")
        }

    private fun leaseFromCursor(db: SQLiteDatabase, row: Cursor): MediaLeaseRecord {
        val id = row.long("id")
        val capacity = row.int("capacity")
        val used =
            scalarLong(
                db,
                "SELECT count(*) FROM media_reservations WHERE lease_id = ? AND state IN ('RESERVED','PROMOTED')",
                arrayOf(id.toString()),
            ).toInt()
        return MediaLeaseRecord(
            id,
            row.string("run_id"),
            capacity,
            MediaCapacityPolicy.unusedSlots(capacity, used),
            row.enum("state"),
            row.long("created_at_ms"),
            row.long("updated_at_ms"),
        )
    }

    private fun reservationById(db: SQLiteDatabase, id: Long): MediaReservationRecord =
        db.query("media_reservations", null, "id = ?", arrayOf(id.toString()), null, null, null).use {
            it.requireSingle(::reservationFromCursor, "media reservation $id")
        }

    private fun reservationFromCursor(row: Cursor) =
        MediaReservationRecord(
            row.long("id"),
            row.long("lease_id"),
            row.string("run_id"),
            row.string("request_id"),
            row.string("asset_id"),
            row.string("requested_filename"),
            row.string("preferred_name"),
            row.string("sha256"),
            row.enum("purpose"),
            row.enum("media_kind"),
            row.enum("state"),
            row.nullableLong("claim_id"),
            row.long("created_at_ms"),
            row.long("updated_at_ms"),
        )

    private fun claimById(db: SQLiteDatabase, id: Long): MediaClaimRecord =
        db.query("media_claims", null, "id = ?", arrayOf(id.toString()), null, null, null).use {
            it.requireSingle(::claimFromCursor, "media claim $id")
        }

    private fun openStoredUnattachedRemediationDb(
        db: SQLiteDatabase,
        claimId: Long,
    ): RemediationRecord? =
        db.query(
            "remediations",
            null,
            "claim_id = ? AND kind = ? AND state = ?",
            arrayOf(
                claimId.toString(),
                RemediationKind.MEDIA_STORED_UNATTACHED.name,
                RemediationState.OPEN.name,
            ),
            null,
            null,
            null,
        ).use { it.singleOrNull(::remediationFromCursor, "open stored-unattached remediation") }

    private fun hasDurableNoteBindingDb(
        db: SQLiteDatabase,
        claim: MediaClaimRecord,
    ): Boolean =
        scalarLong(
            db,
            """SELECT count(*) FROM active_note_media_bindings b
               JOIN active_notes n ON n.parent_id = b.parent_id
               JOIN parents p ON p.id = n.parent_id
               WHERE b.claim_id = ? AND b.asset_id = ? AND b.actual_filename = ? AND
                   p.run_id = ? AND p.state IN ('PREPARED', 'RUNNING')""".trimIndent(),
            arrayOf(
                claim.id.toString(),
                claim.assetId,
                checkNotNull(claim.actualFilename),
                claim.runId,
            ),
        ) == 1L

    private fun unresolvedClaimsDb(db: SQLiteDatabase): List<MediaClaimRecord> =
        db.query(
            "media_claims",
            null,
            "state IN ('PENDING','STORED','COMMIT_UNCERTAIN','PRESENT_BYTES_VERIFIED')",
            null,
            null,
            null,
            "created_at_ms, id",
        ).use { it.mapRows(::claimFromCursor) }

    private fun claimFromCursor(row: Cursor) =
        MediaClaimRecord(
            row.long("id"),
            row.string("run_id"),
            row.string("request_id"),
            row.string("asset_id"),
            row.string("requested_filename"),
            row.string("preferred_name"),
            row.string("sha256"),
            row.enum("purpose"),
            row.enum("media_kind"),
            row.nullableString("actual_filename"),
            row.enum("state"),
            row.nullableString("compact_evidence"),
            row.long("created_at_ms"),
            row.long("updated_at_ms"),
        )

    private fun updateClaim(
        db: SQLiteDatabase,
        claim: MediaClaimRecord,
        state: MediaClaimState,
        actualFilename: String?,
        evidence: String?,
    ) {
        db.updateOrThrow(
            "media_claims",
            values(
                "state" to state.name,
                "actual_filename" to actualFilename,
                "compact_evidence" to evidence,
                "updated_at_ms" to nextTimestamp(claim.updatedAtMs),
            ),
            "id = ?",
            arrayOf(claim.id.toString()),
        )
    }

    /** Bounded by the unresolved-capacity contract; resolved ownership is queried separately. */
    private fun boundedNamespaceLocks(db: SQLiteDatabase): List<MediaNamespaceLock> {
        val reservations =
            db.rawQuery(
                "SELECT run_id, asset_id, requested_filename, provider_prefix FROM media_reservations WHERE state = 'RESERVED'",
                null,
            ).use { cursor ->
                cursor.mapRows {
                    MediaNamespaceLock(
                        MediaNamespaceOwner(it.requiredString(0), it.requiredString(1)),
                        it.requiredString(2),
                        it.requiredString(3),
                    )
                }
            }
        val claims =
            db.rawQuery(
                """SELECT run_id, asset_id, COALESCE(actual_filename, requested_filename), provider_prefix
                   FROM media_claims WHERE state IN ('PENDING','STORED','COMMIT_UNCERTAIN','PRESENT_BYTES_VERIFIED')""".trimIndent(),
                null,
            ).use { cursor ->
                cursor.mapRows {
                    MediaNamespaceLock(
                        MediaNamespaceOwner(it.requiredString(0), it.requiredString(1)),
                        it.requiredString(2),
                        it.requiredString(3),
                    )
                }
            }
        return reservations + claims
    }

    /**
     * Resolved claim history is intentionally unbounded and must not be passed to the 16k
     * in-memory validator. Indexed lookups select only possible collisions, then the shared exact
     * validator decides each candidate pair.
     */
    private fun requireNoClaimNamespaceCollisions(
        db: SQLiteDatabase,
        candidates: List<MediaNamespaceLock>,
    ) {
        val directExpression = "COALESCE(actual_filename, requested_filename)"
        candidates.forEach { candidate ->
            val clauses = ArrayList<String>()
            val arguments = ArrayList<String>()

            clauses += "$directExpression = ?"
            arguments += candidate.directFilename

            fun addExactPrefixes(value: String?) {
                value ?: return
                val prefixes = unicodePrefixes(value)
                clauses += "provider_prefix IN (${prefixes.joinToString(",") { "?" }})"
                arguments += prefixes
            }

            fun addPrefixRange(column: String, prefix: String) {
                val upper = lexicalPrefixUpperBound(prefix)
                if (upper == null) {
                    clauses += "$column >= ?"
                    arguments += prefix
                } else {
                    clauses += "($column >= ? AND $column < ?)"
                    arguments += prefix
                    arguments += upper
                }
            }

            addExactPrefixes(candidate.providerPrefix)
            addExactPrefixes(filenameStem(candidate.directFilename))
            addPrefixRange("provider_prefix", candidate.providerPrefix)
            addPrefixRange(directExpression, candidate.providerPrefix)

            db.rawQuery(
                """SELECT run_id, asset_id, $directExpression, provider_prefix
                   FROM media_claims
                   WHERE state != 'CLEANED_VERIFIED' AND (${clauses.joinToString(" OR ")})""".trimIndent(),
                arguments.toTypedArray(),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val existing =
                        MediaNamespaceLock(
                            MediaNamespaceOwner(cursor.requiredString(0), cursor.requiredString(1)),
                            cursor.requiredString(2),
                            cursor.requiredString(3),
                        )
                    if (existing.owner != candidate.owner) {
                        MediaNamespaceValidator.requireDisjoint(listOf(existing, candidate))
                    }
                }
            }
        }
    }

    private fun unicodePrefixes(value: String): List<String> =
        buildList {
            var end = 0
            while (end < value.length) {
                end += Character.charCount(Character.codePointAt(value, end))
                add(value.substring(0, end))
            }
        }

    /** Smallest valid Unicode string above every string beginning with [value], under BINARY order. */
    private fun lexicalPrefixUpperBound(value: String): String? {
        var end = value.length
        while (end > 0) {
            val start = value.offsetByCodePoints(end, -1)
            val codePoint = Character.codePointAt(value, start)
            if (codePoint < Character.MAX_CODE_POINT) {
                var next = codePoint + 1
                if (next in Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code) {
                    next = Character.MAX_SURROGATE.code + 1
                }
                return value.substring(0, start) + String(Character.toChars(next))
            }
            end = start
        }
        return null
    }

    private fun filenameStem(filename: String): String? {
        val dot = filename.lastIndexOf('.')
        return if (dot > 0 && dot < filename.lastIndex) filename.substring(0, dot) else null
    }

    private fun stagingById(db: SQLiteDatabase, id: Long): StagingRecord =
        db.query("staging_artifacts", null, "id = ?", arrayOf(id.toString()), null, null, null).use {
            it.requireSingle(::stagingFromCursor, "staging artifact $id")
        }

    private fun stagingFromCursor(row: Cursor) =
        StagingRecord(
            row.long("id"),
            row.string("run_id"),
            row.string("request_id"),
            row.string("asset_id"),
            row.string("relative_path"),
            row.string("content_uri"),
            row.string("package_name"),
            row.long("size_bytes"),
            row.string("sha256"),
            row.enum("state"),
            row.nullableString("compact_evidence"),
            row.long("created_at_ms"),
            row.long("updated_at_ms"),
        )

    private fun remediationById(db: SQLiteDatabase, id: Long): RemediationRecord =
        db.query("remediations", null, "id = ?", arrayOf(id.toString()), null, null, null).use {
            it.requireSingle(::remediationFromCursor, "remediation $id")
        }

    private fun remediationFromCursor(row: Cursor) =
        RemediationRecord(
            row.long("id"),
            row.nullableLong("parent_id"),
            row.nullableLong("claim_id"),
            row.nullableLong("staging_id"),
            row.nullableLong("staging_subject_id"),
            row.enum("kind"),
            row.enum("state"),
            row.string("summary"),
            row.nullableString("compact_evidence"),
            row.long("created_at_ms"),
            row.long("updated_at_ms"),
        )

    private fun requireParent(db: SQLiteDatabase, key: ParentKey): ParentRecord =
        parentByKey(db, key) ?: throw JournalInvariantViolation("Unknown parent ${key.requestId}")

    private fun requireMutableParent(
        db: SQLiteDatabase,
        key: ParentKey,
        operation: ParentOperation? = null,
    ): ParentRecord {
        val parent = requireParent(db, key)
        if (parent.state !in setOf(ParentState.PREPARED, ParentState.RUNNING)) throw JournalInvariantViolation("Parent is not mutable")
        if (operation != null && parent.operation != operation) throw JournalInvariantViolation("Wrong parent operation")
        return parent
    }

    private fun updateParentState(db: SQLiteDatabase, parent: ParentRecord, state: ParentState) {
        JournalStateMachine.requireParentTransition(parent.state, state)
        db.updateOrThrow(
            "parents",
            values("state" to state.name, "updated_at_ms" to nextTimestamp(parent.updatedAtMs)),
            "id = ?",
            arrayOf(parent.id.toString()),
        )
    }

    private fun validateMediaNames(directFilename: String, preferredName: String) {
        listOf(directFilename, preferredName).forEach { value ->
            require(value.isNotBlank()) { "media name must not be blank" }
            require(value != "." && value != ".." && '/' !in value && '\\' !in value && '\u0000' !in value) {
                "media name must be a safe basename"
            }
            requireStrictUtf8Bound(value, 255, "media filename")
        }
    }

    private fun providerPrefix(preferredName: String): String = "${preferredName}_"

    private fun timestamp(): Long = clock.nowEpochMillis().also { require(it >= 0) { "Journal clock must be non-negative" } }

    private fun nextTimestamp(previous: Long): Long = maxOf(timestamp(), previous + 1)

    private fun <T> read(block: (SQLiteDatabase) -> T): T {
        checkAccess()
        return block(readableDatabase)
    }

    private fun <T> write(block: (SQLiteDatabase) -> T): T {
        checkAccess()
        val db = writableDatabase
        db.beginTransactionNonExclusive()
        return try {
            val value = block(db)
            db.setTransactionSuccessful()
            value
        } finally {
            db.endTransaction()
        }
    }

    private fun checkAccess() {
        check(!closed.get()) { "Journal is closed" }
        if (enforceBackgroundThread && Looper.myLooper() == Looper.getMainLooper()) {
            throw IllegalStateException("Anki mutation journal must not run on the main thread")
        }
    }

    companion object {
        const val DEFAULT_DATABASE_NAME = "anki_mutations.db"
    }
}

private fun values(vararg entries: Pair<String, Any?>): ContentValues =
    ContentValues(entries.size).apply {
        entries.forEach { (key, value) ->
            when (value) {
                null -> putNull(key)
                is String -> put(key, value)
                is Int -> put(key, value)
                is Long -> put(key, value)
                is Boolean -> put(key, if (value) 1 else 0)
                else -> error("Unsupported ContentValues type ${value::class}")
            }
        }
    }

private fun bool(value: Boolean) = if (value) 1 else 0

private fun SQLiteDatabase.updateOrThrow(
    table: String,
    values: ContentValues,
    whereClause: String,
    whereArgs: Array<String>,
) {
    update(table, values, whereClause, whereArgs).requireOne("$table update")
}

private fun Int.requireOne(label: String) {
    if (this != 1) throw JournalCorruptionException("$label affected $this rows")
}

private fun scalarLong(
    db: SQLiteDatabase,
    sql: String,
    args: Array<String>? = null,
): Long = db.rawQuery(sql, args).use { it.requireSingle({ row -> row.requiredLong(0) }, "scalar query") }

private fun pragmaValue(db: SQLiteDatabase, pragma: String): String =
    db.rawQuery("PRAGMA $pragma", null).use {
        it.requireSingle(
            { row ->
                when (row.getType(0)) {
                    Cursor.FIELD_TYPE_STRING, Cursor.FIELD_TYPE_INTEGER -> row.getString(0)
                    else -> throw JournalCorruptionException("PRAGMA $pragma returned an invalid type")
                }
            },
            "PRAGMA $pragma",
        )
    }

private fun requirePragma(db: SQLiteDatabase, pragma: String, expected: String) {
    val actual = pragmaValue(db, pragma)
    if (actual != expected) throw JournalCorruptionException("PRAGMA $pragma=$actual, expected $expected")
}

private fun configureFullSynchronous(db: SQLiteDatabase) {
    val policy = JournalSqliteDurabilityPolicy.synchronousConfiguration(Build.VERSION.SDK_INT)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        check(policy == SqliteSynchronousConfiguration.ALL_CONNECTIONS)
        Api30SqliteDurability.configureFullSynchronous(db)
    } else {
        check(policy == SqliteSynchronousConfiguration.PRIMARY_CONNECTION)
        // Before API 30 SQLiteOpenHelper exposes no per-connection hook. WAL writes use this connection.
        db.execSQL("PRAGMA synchronous=FULL")
    }
}

@RequiresApi(Build.VERSION_CODES.R)
private object Api30SqliteDurability {
    fun configureFullSynchronous(db: SQLiteDatabase) {
        db.execPerConnectionSQL("PRAGMA synchronous=FULL", null)
    }
}

private fun requireSchemaDefinitions(db: SQLiteDatabase) {
    val actual =
        db.rawQuery(
            """SELECT type, name, sql FROM sqlite_master
               WHERE type IN ('table', 'index', 'trigger') AND name NOT LIKE 'sqlite_%' AND name != 'android_metadata'"""
                .trimIndent(),
            null,
        ).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    val type = cursor.requiredString(0)
                    val name = cursor.requiredString(1)
                    val sql = cursor.requiredString(2)
                    val key = "$type:$name"
                    if (put(key, JournalSchema.definitionHash(sql)) != null) {
                        throw JournalCorruptionException("Duplicate sqlite_master definition for $key")
                    }
                }
            }
        }
    val expected = JournalSchema.expectedDefinitionHashes
    if (actual != expected) {
        val mismatched = (actual.keys intersect expected.keys).filter { actual[it] != expected[it] }.sorted()
        throw JournalCorruptionException(
            "Journal schema definition fingerprint differs: missing=${(expected.keys - actual.keys).sorted()}, " +
                "unexpected=${(actual.keys - expected.keys).sorted()}, mismatched=$mismatched",
        )
    }
}

private inline fun <T> Cursor.mapRows(mapper: (Cursor) -> T): List<T> = buildList {
    while (moveToNext()) add(mapper(this@mapRows))
}

private inline fun <T> Cursor.singleOrNull(mapper: (Cursor) -> T, label: String): T? {
    if (!moveToFirst()) return null
    val value = mapper(this)
    if (moveToNext()) throw JournalCorruptionException("$label has more than one row")
    return value
}

private inline fun <T> Cursor.requireSingle(mapper: (Cursor) -> T, label: String): T =
    singleOrNull(mapper, label) ?: throw JournalCorruptionException("$label is missing")

private fun Cursor.column(name: String): Int =
    getColumnIndex(name).also { if (it < 0) throw JournalCorruptionException("Missing column $name") }

private fun Cursor.requiredString(index: Int): String {
    if (getType(index) != Cursor.FIELD_TYPE_STRING) throw JournalCorruptionException("Expected TEXT at column $index")
    return getString(index)
}

private fun Cursor.requiredLong(index: Int): Long {
    if (getType(index) != Cursor.FIELD_TYPE_INTEGER) throw JournalCorruptionException("Expected INTEGER at column $index")
    return getLong(index)
}

private fun Cursor.string(name: String) = requiredString(column(name))
private fun Cursor.long(name: String) = requiredLong(column(name))

private fun Cursor.int(name: String): Int {
    val value = long(name)
    if (value !in Int.MIN_VALUE..Int.MAX_VALUE) throw JournalCorruptionException("$name is outside Int range")
    return value.toInt()
}

private fun Cursor.nullableString(name: String): String? {
    val index = column(name)
    return if (isNull(index)) null else requiredString(index)
}

private fun Cursor.nullableLong(name: String): Long? {
    val index = column(name)
    return if (isNull(index)) null else requiredLong(index)
}

private fun Cursor.nullableInt(name: String): Int? = nullableLong(name)?.let {
    if (it !in Int.MIN_VALUE..Int.MAX_VALUE) throw JournalCorruptionException("$name is outside Int range")
    it.toInt()
}

private fun Cursor.boolean(name: String): Boolean =
    when (val value = long(name)) {
        0L -> false
        1L -> true
        else -> throw JournalCorruptionException("$name is not a Boolean: $value")
    }

private inline fun <reified T : Enum<T>> Cursor.enum(name: String): T =
    try {
        enumValueOf(string(name))
    } catch (error: IllegalArgumentException) {
        throw JournalCorruptionException("Unknown ${T::class.simpleName} in $name", error)
    }

private inline fun <reified T : Enum<T>> Cursor.nullableEnum(name: String): T? =
    nullableString(name)?.let {
        try {
            enumValueOf<T>(it)
        } catch (error: IllegalArgumentException) {
            throw JournalCorruptionException("Unknown ${T::class.simpleName} in $name", error)
        }
    }

private fun Cursor.errorOrNull(): JournalError? {
    val code = nullableString("error_code") ?: return null
    val message = nullableString("error_message") ?: throw JournalCorruptionException("Partial error triple")
    val retryable = nullableLong("error_retryable") ?: throw JournalCorruptionException("Partial error triple")
    val parsed =
        try {
            JournalErrorCode.valueOf(code)
        } catch (error: IllegalArgumentException) {
            throw JournalCorruptionException("Unknown journal error code", error)
        }
    return JournalError(
        parsed,
        message,
        when (retryable) {
            0L -> false
            1L -> true
            else -> throw JournalCorruptionException("Invalid error retryable value")
        },
    )
}
