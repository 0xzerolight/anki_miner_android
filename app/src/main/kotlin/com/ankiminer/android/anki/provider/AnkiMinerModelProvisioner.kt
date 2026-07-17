package com.ankiminer.android.anki.provider

import com.ankiminer.android.anki.protocol.AnkiErrorCode

internal enum class AnkiMinerModelReadyOrigin {
    EXISTING_EXACT,
    PROVISIONED,
    RECOVERED,
    JOURNALED,
}

internal enum class AnkiMinerModelConflictReason(val stableMessage: String) {
    SAME_NAME_MODEL_DIFFERS(
        "An Anki note type named Anki Miner already exists but does not match the first-party model",
    ),
    PROVIDER_MODEL_UNSUPPORTED(
        "The Anki Miner note type cannot be read safely from the installed AnkiDroid provider",
    ),
    JOURNAL_CONTRACT_CHANGED(
        "The saved Anki Miner model operation belongs to a different model version",
    ),
    JOURNALED_MODEL_ID_CHANGED(
        "The Anki Miner note type no longer has the identity recorded by setup",
    ),
}

internal enum class AnkiMinerModelRecoveryReason(val stableMessage: String) {
    JOURNAL_UNREADABLE("The Anki Miner model setup record could not be read safely"),
    JOURNAL_WRITE_FAILED("The Anki Miner model setup record could not be saved safely"),
    JOURNAL_CHANGED("Another Anki Miner model setup operation changed the setup record"),
    EXACT_COMPLETION_PENDING("An exact Anki Miner note type is awaiting journal reconciliation"),
    MODEL_CREATE_RECONCILIATION_PENDING(
        "A started Anki Miner note type creation is awaiting safe reconciliation",
    ),
    MODEL_CREATE_OUTCOME_UNCERTAIN(
        "AnkiDroid did not expose a conclusive result for the started note type creation",
    ),
    JOURNALED_MODEL_MISSING("The note type recorded by Anki Miner setup is no longer present"),
    JOURNALED_MODEL_CHANGED("The note type recorded by Anki Miner setup changed unexpectedly"),
    MODEL_RECEIPT_CONFLICT("AnkiDroid returned a model identity that conflicts with readback"),
    TEMPLATE_UPDATE_PENDING("The attributable Anki Miner template still needs to be finalized"),
    TEMPLATE_UPDATE_OUTCOME_UNCERTAIN(
        "AnkiDroid did not expose a conclusive result for the started template update",
    ),
    PROVIDER_READ_FAILED_AFTER_ENTRY(
        "AnkiDroid could not be read after a model setup write had already started",
    ),
}

internal sealed interface AnkiMinerModelProvisioningResult {
    data class Ready(
        val modelId: Long,
        val origin: AnkiMinerModelReadyOrigin,
    ) : AnkiMinerModelProvisioningResult {
        init {
            require(modelId > 0L) { "A ready model ID must be positive" }
        }
    }

    data object Missing : AnkiMinerModelProvisioningResult

    data class Conflict(val reason: AnkiMinerModelConflictReason) :
        AnkiMinerModelProvisioningResult {
        val stableMessage: String
            get() = reason.stableMessage
    }

    data class RecoveryRequired(val reason: AnkiMinerModelRecoveryReason) :
        AnkiMinerModelProvisioningResult {
        val stableMessage: String
            get() = reason.stableMessage
    }

    data class FailedBeforeEntry(
        val code: AnkiErrorCode,
        val retryable: Boolean,
        val stableMessage: String,
    ) : AnkiMinerModelProvisioningResult
}

internal interface AnkiMinerModelProvisioningBoundaryHooks {
    fun afterModelCreateEntry() = Unit

    fun afterModelCreateReturn() = Unit

    fun afterTemplateUpdateEntry() = Unit

    fun afterTemplateUpdateReturn() = Unit
}

internal object NoOpAnkiMinerModelProvisioningBoundaryHooks :
    AnkiMinerModelProvisioningBoundaryHooks

/**
 * Explicit, restart-safe first-party model provisioning. [inspect] never mutates either app or
 * provider state; callers must invoke [provision] only from a user-triggered setup action.
 */
internal class AnkiMinerModelProvisioner(
    private val gateway: AnkiProviderGateway,
    private val journal: AnkiMinerModelProvisioningJournal,
    private val boundaryHooks: AnkiMinerModelProvisioningBoundaryHooks =
        NoOpAnkiMinerModelProvisioningBoundaryHooks,
) {
    private val checkedProvider = CheckedProvider(gateway)
    private val snapshots = TargetSnapshotReader(checkedProvider)

    @Synchronized
    fun inspect(
        cancellation: AnkiCancellation = AnkiCancellation.NONE,
    ): AnkiMinerModelProvisioningResult {
        val record =
            try {
                journal.read()
            } catch (_: AnkiMinerModelJournalException) {
                return recovery(AnkiMinerModelRecoveryReason.JOURNAL_UNREADABLE)
            }
        contractConflict(record)?.let { return it }
        return when (val lookup = lookup(cancellation)) {
            is ModelLookup.Failed -> failureBeforeOrAfterEntry(lookup.failure, record)
            ModelLookup.Missing -> missingResult(record)
            is ModelLookup.Found -> inspectFound(record, lookup.snapshot)
        }
    }

    @Synchronized
    fun provision(
        cancellation: AnkiCancellation = AnkiCancellation.NONE,
    ): AnkiMinerModelProvisioningResult {
        var record =
            try {
                journal.read()
            } catch (_: AnkiMinerModelJournalException) {
                return recovery(AnkiMinerModelRecoveryReason.JOURNAL_UNREADABLE)
            }
        if (record != null && record.contractSha256 != AnkiMinerNoteModel.CONTRACT_SHA256) {
            if (record.phase != AnkiMinerModelProvisioningPhase.COMPLETE) {
                return conflict(AnkiMinerModelConflictReason.JOURNAL_CONTRACT_CHANGED)
            }
            return provisionAfterCompletedContract(record, cancellation)
        }

        when (val lookup = lookup(cancellation)) {
            is ModelLookup.Failed -> return failureBeforeOrAfterEntry(lookup.failure, record)
            is ModelLookup.Found -> return provisionFound(record, lookup.snapshot, cancellation)
            ModelLookup.Missing -> {
                if (record?.phase == AnkiMinerModelProvisioningPhase.COMPLETE) {
                    val prepared = freshPreparedRecord()
                    replace(record, prepared)?.let { return it }
                    record = prepared
                } else if (
                    record != null &&
                        record.phase != AnkiMinerModelProvisioningPhase.PREPARED
                ) {
                    return recovery(AnkiMinerModelRecoveryReason.JOURNALED_MODEL_MISSING)
                }
            }
        }

        if (record == null) {
            val prepared = freshPreparedRecord()
            replace(record, prepared)?.let { return it }
            record = prepared
        }
        return createModel(requireNotNull(record), cancellation)
    }

    /**
     * A COMPLETE record has no outstanding provider entry, so an explicit setup action can retire
     * that generation. This is deliberately limited to a missing model or a model which already
     * matches the current contract byte-for-byte; a differing same-name model is never changed.
     */
    private fun provisionAfterCompletedContract(
        completed: AnkiMinerModelProvisioningRecord,
        cancellation: AnkiCancellation,
    ): AnkiMinerModelProvisioningResult {
        return when (val lookup = lookup(cancellation)) {
            is ModelLookup.Failed -> freshGenerationFailure(lookup.failure)
            is ModelLookup.Found -> {
                if (!AnkiMinerNoteModel.matchesExactly(lookup.snapshot)) {
                    conflict(AnkiMinerModelConflictReason.SAME_NAME_MODEL_DIFFERS)
                } else {
                    val prepared = freshPreparedRecord()
                    replace(completed, prepared)?.let { return it }
                    ready(lookup.snapshot.id, AnkiMinerModelReadyOrigin.EXISTING_EXACT)
                }
            }
            ModelLookup.Missing -> {
                val prepared = freshPreparedRecord()
                replace(completed, prepared)?.let { return it }
                createModel(prepared, cancellation)
            }
        }
    }

    private fun provisionFound(
        record: AnkiMinerModelProvisioningRecord?,
        snapshot: ModelSnapshot,
        cancellation: AnkiCancellation,
    ): AnkiMinerModelProvisioningResult {
        if (AnkiMinerNoteModel.matchesExactly(snapshot)) {
            if (record == null || record.phase == AnkiMinerModelProvisioningPhase.PREPARED) {
                return ready(snapshot.id, AnkiMinerModelReadyOrigin.EXISTING_EXACT)
            }
            if (record.modelId != null && record.modelId != snapshot.id) {
                if (record.phase != AnkiMinerModelProvisioningPhase.COMPLETE) {
                    return conflict(AnkiMinerModelConflictReason.JOURNALED_MODEL_ID_CHANGED)
                }
                val prepared = freshPreparedRecord()
                replace(record, prepared)?.let { return it }
                return ready(snapshot.id, AnkiMinerModelReadyOrigin.EXISTING_EXACT)
            }
            if (record.phase == AnkiMinerModelProvisioningPhase.COMPLETE) {
                return ready(snapshot.id, AnkiMinerModelReadyOrigin.JOURNALED)
            }
            return complete(record, snapshot, AnkiMinerModelReadyOrigin.RECOVERED)
        }
        if (record == null || record.phase == AnkiMinerModelProvisioningPhase.PREPARED) {
            return conflict(AnkiMinerModelConflictReason.SAME_NAME_MODEL_DIFFERS)
        }
        return when (record.phase) {
            AnkiMinerModelProvisioningPhase.MODEL_CREATE_ENTERED -> {
                if (!AnkiMinerNoteModel.matchesControlledBase(snapshot)) {
                    recovery(AnkiMinerModelRecoveryReason.JOURNALED_MODEL_CHANGED)
                } else {
                    val reconciled = baseVerifiedRecord(snapshot)
                    replace(record, reconciled)?.let { return it }
                    finalizeTemplate(
                        record = reconciled,
                        snapshot = snapshot,
                        cancellation = cancellation,
                        origin = AnkiMinerModelReadyOrigin.RECOVERED,
                    )
                }
            }
            AnkiMinerModelProvisioningPhase.MODEL_BASE_VERIFIED,
            AnkiMinerModelProvisioningPhase.TEMPLATE_UPDATE_ENTERED,
            -> {
                if (!matchesJournaledBase(record, snapshot)) {
                    recovery(AnkiMinerModelRecoveryReason.JOURNALED_MODEL_CHANGED)
                } else {
                    finalizeTemplate(
                        record = record,
                        snapshot = snapshot,
                        cancellation = cancellation,
                        origin = AnkiMinerModelReadyOrigin.RECOVERED,
                    )
                }
            }
            AnkiMinerModelProvisioningPhase.COMPLETE ->
                recovery(AnkiMinerModelRecoveryReason.JOURNALED_MODEL_CHANGED)
            AnkiMinerModelProvisioningPhase.PREPARED ->
                conflict(AnkiMinerModelConflictReason.SAME_NAME_MODEL_DIFFERS)
        }
    }

    private fun createModel(
        prepared: AnkiMinerModelProvisioningRecord,
        cancellation: AnkiCancellation,
    ): AnkiMinerModelProvisioningResult {
        // Narrow the external-create race after PREPARED without pretending the provider offers
        // a uniqueness transaction. The entry journal still makes any remaining race recoverable.
        when (val lookup = lookup(cancellation)) {
            is ModelLookup.Failed -> return failureBeforeOrAfterEntry(lookup.failure, prepared)
            is ModelLookup.Found -> return provisionFound(prepared, lookup.snapshot, cancellation)
            ModelLookup.Missing -> Unit
        }
        try {
            checkedProvider.preflightMutation(cancellation)
        } catch (failure: AnkiReadFailure) {
            return failedBeforeEntry(failure)
        }
        val entered =
            AnkiMinerModelProvisioningRecord(
                contractSha256 = AnkiMinerNoteModel.CONTRACT_SHA256,
                phase = AnkiMinerModelProvisioningPhase.MODEL_CREATE_ENTERED,
            )
        replace(prepared, entered)?.let { return it }
        boundaryHooks.afterModelCreateEntry()
        val rawReceipt =
            try {
                gateway.createAnkiMinerModel(AnkiProviderMutationCommand.CreateAnkiMinerModel)
            } catch (_: RuntimeException) {
                null
            }
        boundaryHooks.afterModelCreateReturn()
        val receipt =
            ModelCreateReceiptValidator.validate(rawReceipt)
                ?: return recovery(AnkiMinerModelRecoveryReason.MODEL_CREATE_OUTCOME_UNCERTAIN)

        val snapshot =
            when (val lookup = lookup(AnkiCancellation.NONE)) {
                is ModelLookup.Failed ->
                    return recovery(AnkiMinerModelRecoveryReason.PROVIDER_READ_FAILED_AFTER_ENTRY)
                ModelLookup.Missing ->
                    return recovery(AnkiMinerModelRecoveryReason.MODEL_CREATE_OUTCOME_UNCERTAIN)
                is ModelLookup.Found -> lookup.snapshot
            }
        if (!AnkiMinerNoteModel.matchesControlledBase(snapshot)) {
            return recovery(AnkiMinerModelRecoveryReason.JOURNALED_MODEL_CHANGED)
        }
        if (receipt.modelId != snapshot.id) {
            return recovery(AnkiMinerModelRecoveryReason.MODEL_RECEIPT_CONFLICT)
        }
        if (AnkiMinerNoteModel.matchesExactly(snapshot)) {
            return complete(entered, snapshot, AnkiMinerModelReadyOrigin.PROVISIONED)
        }
        val reconciled = baseVerifiedRecord(snapshot)
        replace(entered, reconciled)?.let { return it }
        return finalizeTemplate(
            record = reconciled,
            snapshot = snapshot,
            cancellation = AnkiCancellation.NONE,
            origin = AnkiMinerModelReadyOrigin.PROVISIONED,
        )
    }

    private fun finalizeTemplate(
        record: AnkiMinerModelProvisioningRecord,
        snapshot: ModelSnapshot,
        cancellation: AnkiCancellation,
        origin: AnkiMinerModelReadyOrigin,
    ): AnkiMinerModelProvisioningResult {
        if (!matchesJournaledBase(record, snapshot)) {
            return recovery(AnkiMinerModelRecoveryReason.JOURNALED_MODEL_CHANGED)
        }
        try {
            checkedProvider.preflightMutation(cancellation)
        } catch (failure: AnkiReadFailure) {
            return failedBeforeEntry(failure)
        }
        val entered =
            if (record.phase == AnkiMinerModelProvisioningPhase.MODEL_BASE_VERIFIED) {
                record.copy(phase = AnkiMinerModelProvisioningPhase.TEMPLATE_UPDATE_ENTERED).also {
                    replace(record, it)?.let { failure -> return failure }
                }
            } else {
                record
            }
        boundaryHooks.afterTemplateUpdateEntry()
        val affected =
            try {
                gateway.updateAnkiMinerTemplate(
                    AnkiProviderMutationCommand.UpdateAnkiMinerTemplate(snapshot.id),
                )
            } catch (_: RuntimeException) {
                INVALID_AFFECTED_COUNT
            }
        boundaryHooks.afterTemplateUpdateReturn()
        if (!ModelTemplateUpdateReceiptValidator.validate(affected)) {
            return recovery(AnkiMinerModelRecoveryReason.TEMPLATE_UPDATE_OUTCOME_UNCERTAIN)
        }
        val readback =
            when (val lookup = lookup(AnkiCancellation.NONE)) {
                is ModelLookup.Found -> lookup.snapshot
                is ModelLookup.Failed, ModelLookup.Missing ->
                    return recovery(AnkiMinerModelRecoveryReason.TEMPLATE_UPDATE_OUTCOME_UNCERTAIN)
            }
        if (readback.id != snapshot.id || !AnkiMinerNoteModel.matchesExactly(readback)) {
            return recovery(AnkiMinerModelRecoveryReason.TEMPLATE_UPDATE_OUTCOME_UNCERTAIN)
        }
        return complete(entered, readback, origin)
    }

    private fun complete(
        current: AnkiMinerModelProvisioningRecord,
        snapshot: ModelSnapshot,
        origin: AnkiMinerModelReadyOrigin,
    ): AnkiMinerModelProvisioningResult {
        val completed =
            AnkiMinerModelProvisioningRecord(
                contractSha256 = AnkiMinerNoteModel.CONTRACT_SHA256,
                phase = AnkiMinerModelProvisioningPhase.COMPLETE,
                modelId = snapshot.id,
                snapshotSha256 = AnkiMinerNoteModel.snapshotSha256(snapshot),
            )
        replace(current, completed)?.let { return it }
        return ready(snapshot.id, origin)
    }

    private fun inspectFound(
        record: AnkiMinerModelProvisioningRecord?,
        snapshot: ModelSnapshot,
    ): AnkiMinerModelProvisioningResult {
        if (AnkiMinerNoteModel.matchesExactly(snapshot)) {
            if (record == null || record.phase == AnkiMinerModelProvisioningPhase.PREPARED) {
                return ready(snapshot.id, AnkiMinerModelReadyOrigin.EXISTING_EXACT)
            }
            if (record.modelId != null && record.modelId != snapshot.id) {
                return conflict(AnkiMinerModelConflictReason.JOURNALED_MODEL_ID_CHANGED)
            }
            return if (record.phase == AnkiMinerModelProvisioningPhase.COMPLETE) {
                ready(snapshot.id, AnkiMinerModelReadyOrigin.JOURNALED)
            } else {
                recovery(AnkiMinerModelRecoveryReason.EXACT_COMPLETION_PENDING)
            }
        }
        if (record == null || record.phase == AnkiMinerModelProvisioningPhase.PREPARED) {
            return conflict(AnkiMinerModelConflictReason.SAME_NAME_MODEL_DIFFERS)
        }
        return when (record.phase) {
            AnkiMinerModelProvisioningPhase.MODEL_CREATE_ENTERED ->
                if (AnkiMinerNoteModel.matchesControlledBase(snapshot)) {
                    recovery(AnkiMinerModelRecoveryReason.MODEL_CREATE_RECONCILIATION_PENDING)
                } else {
                    recovery(AnkiMinerModelRecoveryReason.JOURNALED_MODEL_CHANGED)
                }
            AnkiMinerModelProvisioningPhase.MODEL_BASE_VERIFIED,
            AnkiMinerModelProvisioningPhase.TEMPLATE_UPDATE_ENTERED,
            ->
                if (matchesJournaledBase(record, snapshot)) {
                    recovery(AnkiMinerModelRecoveryReason.TEMPLATE_UPDATE_PENDING)
                } else {
                    recovery(AnkiMinerModelRecoveryReason.JOURNALED_MODEL_CHANGED)
                }
            AnkiMinerModelProvisioningPhase.COMPLETE ->
                recovery(AnkiMinerModelRecoveryReason.JOURNALED_MODEL_CHANGED)
            AnkiMinerModelProvisioningPhase.PREPARED ->
                conflict(AnkiMinerModelConflictReason.SAME_NAME_MODEL_DIFFERS)
        }
    }

    private fun missingResult(
        record: AnkiMinerModelProvisioningRecord?,
    ): AnkiMinerModelProvisioningResult =
        if (record == null || record.phase == AnkiMinerModelProvisioningPhase.PREPARED) {
            AnkiMinerModelProvisioningResult.Missing
        } else {
            recovery(AnkiMinerModelRecoveryReason.JOURNALED_MODEL_MISSING)
        }

    private fun matchesJournaledBase(
        record: AnkiMinerModelProvisioningRecord,
        snapshot: ModelSnapshot,
    ): Boolean =
        record.modelId == snapshot.id &&
            AnkiMinerNoteModel.matchesControlledBase(snapshot) &&
            record.snapshotSha256 == AnkiMinerNoteModel.snapshotSha256(snapshot)

    private fun baseVerifiedRecord(snapshot: ModelSnapshot) =
        AnkiMinerModelProvisioningRecord(
            contractSha256 = AnkiMinerNoteModel.CONTRACT_SHA256,
            phase = AnkiMinerModelProvisioningPhase.MODEL_BASE_VERIFIED,
            modelId = snapshot.id,
            snapshotSha256 = AnkiMinerNoteModel.snapshotSha256(snapshot),
        )

    private fun freshPreparedRecord() =
        AnkiMinerModelProvisioningRecord(
            contractSha256 = AnkiMinerNoteModel.CONTRACT_SHA256,
            phase = AnkiMinerModelProvisioningPhase.PREPARED,
        )

    private fun lookup(cancellation: AnkiCancellation): ModelLookup =
        try {
            snapshots.readModelByNameOrNull(AnkiMinerNoteModel.MODEL_NAME, cancellation)?.let {
                ModelLookup.Found(it)
            } ?: ModelLookup.Missing
        } catch (failure: AnkiReadFailure) {
            ModelLookup.Failed(failure)
        }

    private fun contractConflict(
        record: AnkiMinerModelProvisioningRecord?,
    ): AnkiMinerModelProvisioningResult.Conflict? =
        record
            ?.takeIf { it.contractSha256 != AnkiMinerNoteModel.CONTRACT_SHA256 }
            ?.let { conflict(AnkiMinerModelConflictReason.JOURNAL_CONTRACT_CHANGED) }

    private fun failureBeforeOrAfterEntry(
        failure: AnkiReadFailure,
        record: AnkiMinerModelProvisioningRecord?,
    ): AnkiMinerModelProvisioningResult =
        if (record != null && record.phase != AnkiMinerModelProvisioningPhase.PREPARED) {
            recovery(AnkiMinerModelRecoveryReason.PROVIDER_READ_FAILED_AFTER_ENTRY)
        } else if (failure.code == AnkiErrorCode.TARGET_INVALID) {
            conflict(AnkiMinerModelConflictReason.PROVIDER_MODEL_UNSUPPORTED)
        } else {
            failedBeforeEntry(failure)
        }

    private fun replace(
        current: AnkiMinerModelProvisioningRecord?,
        updated: AnkiMinerModelProvisioningRecord,
    ): AnkiMinerModelProvisioningResult.RecoveryRequired? =
        try {
            journal.replace(current, updated)
            null
        } catch (_: AnkiMinerModelJournalStateChangedException) {
            recovery(AnkiMinerModelRecoveryReason.JOURNAL_CHANGED)
        } catch (_: AnkiMinerModelJournalException) {
            recovery(AnkiMinerModelRecoveryReason.JOURNAL_WRITE_FAILED)
        }

    private fun failedBeforeEntry(failure: AnkiReadFailure) =
        AnkiMinerModelProvisioningResult.FailedBeforeEntry(
            code = failure.code,
            retryable = failure.retryable,
            stableMessage = failure.stableMessage,
        )

    private fun freshGenerationFailure(
        failure: AnkiReadFailure,
    ): AnkiMinerModelProvisioningResult =
        if (failure.code == AnkiErrorCode.TARGET_INVALID) {
            conflict(AnkiMinerModelConflictReason.PROVIDER_MODEL_UNSUPPORTED)
        } else {
            failedBeforeEntry(failure)
        }

    private fun ready(
        modelId: Long,
        origin: AnkiMinerModelReadyOrigin,
    ) = AnkiMinerModelProvisioningResult.Ready(modelId, origin)

    private fun conflict(reason: AnkiMinerModelConflictReason) =
        AnkiMinerModelProvisioningResult.Conflict(reason)

    private fun recovery(reason: AnkiMinerModelRecoveryReason) =
        AnkiMinerModelProvisioningResult.RecoveryRequired(reason)

    private sealed interface ModelLookup {
        data class Found(val snapshot: ModelSnapshot) : ModelLookup

        data object Missing : ModelLookup

        data class Failed(val failure: AnkiReadFailure) : ModelLookup
    }

    private companion object {
        const val INVALID_AFFECTED_COUNT = Int.MIN_VALUE
    }
}
