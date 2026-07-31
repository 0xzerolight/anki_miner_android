package com.ankiminer.android.anki.journal

import com.ankiminer.android.anki.protocol.VerifyTargetRequest

internal object JournalStateMachine {
    fun requireParentTransition(from: ParentState, to: ParentState) {
        val legal =
            when (from) {
                ParentState.PREPARED -> to in setOf(ParentState.RUNNING, ParentState.RESULT_READY)
                ParentState.RUNNING -> to == ParentState.RESULT_READY
                ParentState.RESULT_READY -> to in setOf(ParentState.RESPONSE_ACKNOWLEDGED, ParentState.ABANDONED)
                ParentState.RESPONSE_ACKNOWLEDGED, ParentState.ABANDONED -> false
            }
        if (!legal) throw JournalInvariantViolation("Illegal parent transition: $from -> $to")
    }

    fun requireChildCompletion(child: ChildRecord, outcome: ChildState) {
        if (child.state != ChildState.PREPARED || !outcome.isTerminal) {
            throw JournalInvariantViolation("Illegal child transition: ${child.state} -> $outcome")
        }
        val entered = child.attemptCount > 0
        when (outcome) {
            ChildState.PREPARED -> error("terminal outcome required")
            ChildState.PROVEN_NOT_COMMITTED -> {
                if (entered || child.receipt != null) {
                    throw JournalInvariantViolation("PROVEN_NOT_COMMITTED requires no provider entry or receipt")
                }
            }
            ChildState.COMMIT_KNOWN -> {
                if (!entered || child.receipt?.operation != child.command.operation) {
                    throw JournalInvariantViolation("COMMIT_KNOWN requires a matching typed receipt")
                }
            }
            ChildState.POSTCONDITION_VERIFIED -> {
                if (!entered || child.command.operation !in setOf(ChildOperation.DECK_CREATE, ChildOperation.CARD_DECK_UPDATE)) {
                    throw JournalInvariantViolation("Only entered deck/card commands have reconcilable postconditions")
                }
            }
            ChildState.POSTCONDITION_FAILED -> {
                if (!entered || child.command.operation != ChildOperation.CARD_DECK_UPDATE) {
                    throw JournalInvariantViolation("Only an entered card update has a deterministic failed postcondition")
                }
            }
            ChildState.COMMIT_UNCERTAIN -> {
                if (!entered) throw JournalInvariantViolation("Commit-bearing outcome requires provider entry")
            }
        }
    }

    fun requireNotePhaseTransition(from: NoteRoutingPhase, to: NoteRoutingPhase) {
        val expected =
            when (from) {
                NoteRoutingPhase.NOTE_PENDING -> NoteRoutingPhase.NOTE_COMMIT_KNOWN
                NoteRoutingPhase.NOTE_COMMIT_KNOWN -> NoteRoutingPhase.NOTE_READBACK_VERIFIED
                NoteRoutingPhase.NOTE_READBACK_VERIFIED -> NoteRoutingPhase.CARDS_DISCOVERED
                NoteRoutingPhase.CARDS_DISCOVERED -> NoteRoutingPhase.ROUTING
                NoteRoutingPhase.ROUTING -> NoteRoutingPhase.ROUTED
                NoteRoutingPhase.ROUTED -> NoteRoutingPhase.POSTCHECK_VERIFIED
                NoteRoutingPhase.POSTCHECK_VERIFIED -> null
            }
        if (to != expected) throw JournalInvariantViolation("Illegal note phase transition: $from -> $to")
    }

    fun requireRoutingTransition(from: RoutingIntentState, to: RoutingIntentState) {
        val legal =
            when (from) {
                RoutingIntentState.PENDING ->
                    to in setOf(RoutingIntentState.UPDATE_PREPARED, RoutingIntentState.VERIFIED, RoutingIntentState.FAILED)
                RoutingIntentState.UPDATE_PREPARED -> to.isTerminal
                RoutingIntentState.VERIFIED, RoutingIntentState.FAILED, RoutingIntentState.COMMIT_UNCERTAIN -> false
            }
        if (!legal) throw JournalInvariantViolation("Illegal routing transition: $from -> $to")
    }

    fun requireClaimTransition(from: MediaClaimState, to: MediaClaimState) {
        val resolved =
            setOf(
                MediaClaimState.ATTACHED_VERIFIED,
                MediaClaimState.CLEANED_VERIFIED,
                MediaClaimState.ACKNOWLEDGED_BY_USER,
            )
        val legal =
            when (from) {
                MediaClaimState.PENDING ->
                    to in setOf(MediaClaimState.STORED, MediaClaimState.COMMIT_UNCERTAIN, MediaClaimState.PRESENT_BYTES_VERIFIED) ||
                        to in setOf(MediaClaimState.CLEANED_VERIFIED, MediaClaimState.ACKNOWLEDGED_BY_USER)
                MediaClaimState.STORED, MediaClaimState.COMMIT_UNCERTAIN ->
                    to == MediaClaimState.PRESENT_BYTES_VERIFIED ||
                        to in setOf(MediaClaimState.CLEANED_VERIFIED, MediaClaimState.ACKNOWLEDGED_BY_USER) ||
                        (from == MediaClaimState.STORED && to == MediaClaimState.ATTACHED_VERIFIED)
                MediaClaimState.PRESENT_BYTES_VERIFIED -> to in resolved
                MediaClaimState.ATTACHED_VERIFIED,
                MediaClaimState.CLEANED_VERIFIED,
                MediaClaimState.ACKNOWLEDGED_BY_USER,
                -> false
            }
        if (!legal) throw JournalInvariantViolation("Illegal media claim transition: $from -> $to")
    }

    fun requireStagingTransition(from: StagingState, to: StagingState) {
        val legal =
            when (from) {
                StagingState.STAGED ->
                    to in setOf(StagingState.GRANTED, StagingState.CLEANUP_PENDING, StagingState.CLEANED, StagingState.QUARANTINED)
                StagingState.GRANTED ->
                    to in setOf(StagingState.CLEANUP_PENDING, StagingState.CLEANED, StagingState.QUARANTINED)
                StagingState.CLEANUP_PENDING -> to in setOf(StagingState.CLEANED, StagingState.QUARANTINED)
                StagingState.QUARANTINED -> to == StagingState.CLEANED
                StagingState.CLEANED -> false
            }
        if (!legal) throw JournalInvariantViolation("Illegal staging transition: $from -> $to")
    }

    fun validateTerminalResponse(
        request: JournalRequest,
        response: JournalResponse,
        immutablePrefix: List<AlignedResult>,
    ) {
        if (response.key != request.key || response.operation != request.operation) {
            throw JournalInvariantViolation("Terminal response does not match its request")
        }
        when (response) {
            is JournalResponse.VerifySuccess -> {
                requireOperation(request, ParentOperation.VERIFY_TARGET)
                if (immutablePrefix.isNotEmpty()) throw JournalInvariantViolation("verifyTarget has no prewritten result prefix")
                val typedRequest = request.protocolRequest as? VerifyTargetRequest
                    ?: throw JournalInvariantViolation("verifyTarget journal request lost its typed payload")
                if (
                    response.target.deck.name != typedRequest.deckName ||
                    response.target.model.name != typedRequest.modelName ||
                    !response.target.model.fieldNames.containsAll(typedRequest.requiredFields)
                ) {
                    throw JournalInvariantViolation("Verified target contradicts the typed request")
                }
            }
            is JournalResponse.VerifyError -> {
                requireOperation(request, ParentOperation.VERIFY_TARGET)
                if (immutablePrefix.isNotEmpty()) throw JournalInvariantViolation("verifyTarget has no prewritten result prefix")
            }
            is JournalResponse.StoreMedia -> {
                requireOperation(request, ParentOperation.STORE_MEDIA)
                validateAlignedRows(request, response.results, immutablePrefix)
                validateMediaOutcome(response.results, response.error)
            }
            is JournalResponse.CreateNotes -> {
                requireOperation(request, ParentOperation.CREATE_NOTES)
                validateAlignedRows(request, response.results, immutablePrefix)
                validateNoteOutcome(response.results, response.error)
            }
        }
    }

    fun validateAlignedResult(operation: ParentOperation, result: AlignedResult) {
        if (result.requestIndex < 0 || result.itemId.isBlank()) {
            throw JournalInvariantViolation("Aligned result identity is invalid")
        }
        requireCompactEvidence(result.compactEvidence)
        val legal =
            when (operation) {
                ParentOperation.VERIFY_TARGET ->
                    result is AlignedResult.TargetVerified || result is AlignedResult.TargetFailed
                ParentOperation.STORE_MEDIA ->
                    result is AlignedResult.MediaStored ||
                        result is AlignedResult.MediaFailed ||
                        result is AlignedResult.MediaUncertain ||
                        result is AlignedResult.MediaNotAttempted
                ParentOperation.CREATE_NOTES ->
                    result is AlignedResult.NoteCreated ||
                        result is AlignedResult.NoteDuplicate ||
                        result is AlignedResult.NoteFailed ||
                        result is AlignedResult.NoteCommittedFailed ||
                        result is AlignedResult.NoteUncertain ||
                        result is AlignedResult.NoteNotAttempted
            }
        if (!legal) throw JournalInvariantViolation("Aligned result shape does not match $operation")
    }

    private fun validateAlignedRows(
        request: JournalRequest,
        rows: List<AlignedResult>,
        immutablePrefix: List<AlignedResult>,
    ) {
        if (rows.size != request.itemIds.size) throw JournalInvariantViolation("Terminal result count is not request-aligned")
        rows.forEachIndexed { index, row ->
            validateAlignedResult(request.operation, row)
            if (row.requestIndex != index || row.itemId != request.itemIds[index]) {
                throw JournalInvariantViolation("Terminal result order or item identity changed")
            }
        }
        if (immutablePrefix.size > rows.size || immutablePrefix != rows.take(immutablePrefix.size)) {
            throw JournalInvariantViolation("Terminalization attempted to overwrite durable mutation evidence")
        }
    }

    private fun validateMediaOutcome(rows: List<AlignedResult>, error: JournalError?) {
        var terminal: AlignedResult? = null
        var storedSeen = false
        val storedNames = HashSet<String>()
        for (row in rows) {
            if (terminal != null) {
                if (row !is AlignedResult.MediaNotAttempted) throw JournalInvariantViolation("Media result suffix is not strict")
                continue
            }
            when (row) {
                is AlignedResult.MediaStored -> {
                    storedSeen = true
                    if (!storedNames.add(row.actualFilename)) {
                        throw JournalInvariantViolation("Stored media filenames must be unique")
                    }
                }
                is AlignedResult.MediaFailed -> {
                    if (row.rowError.code != JournalErrorCode.MEDIA_STORE_FAILED) {
                        throw JournalInvariantViolation("Failed media requires media_store_failed")
                    }
                }
                is AlignedResult.MediaUncertain -> terminal = row
                is AlignedResult.MediaNotAttempted -> terminal = row
                else -> throw JournalInvariantViolation("Non-media row in media response")
            }
        }
        if ((terminal != null) != (error != null)) {
            throw JournalInvariantViolation("Media top-level error does not match its stop/uncertainty carrier")
        }
        when (terminal) {
            is AlignedResult.MediaUncertain -> requirePostCommitUncertain(error)
            is AlignedResult.MediaNotAttempted -> {
                if (error?.code == JournalErrorCode.POST_COMMIT_UNCERTAIN) {
                    throw JournalInvariantViolation("Media post-commit uncertainty requires an uncertain row")
                }
            }
            null -> Unit
            else -> error("unreachable terminal row")
        }
        if (storedSeen && error?.retryable == true) {
            throw JournalInvariantViolation("A media result after a known write cannot be retryable")
        }
        if (error?.code == JournalErrorCode.POST_COMMIT_UNCERTAIN && terminal !is AlignedResult.MediaUncertain) {
            throw JournalInvariantViolation("Media post-commit uncertainty lacks an uncertain row")
        }
    }

    private fun validateNoteOutcome(rows: List<AlignedResult>, error: JournalError?) {
        var terminal: AlignedResult? = null
        var knownCommitSeen = false
        val committedIds = HashSet<Long>()
        for (row in rows) {
            if (terminal != null) {
                if (row !is AlignedResult.NoteNotAttempted) throw JournalInvariantViolation("Create result suffix is not strict")
                continue
            }
            when (row) {
                is AlignedResult.NoteCreated -> {
                    knownCommitSeen = true
                    if (!committedIds.add(row.committedId)) {
                        throw JournalInvariantViolation("Committed note IDs must be unique")
                    }
                }
                is AlignedResult.NoteDuplicate -> Unit
                is AlignedResult.NoteFailed,
                is AlignedResult.NoteCommittedFailed,
                is AlignedResult.NoteUncertain,
                -> {
                    terminal = row
                    if (row is AlignedResult.NoteCommittedFailed) {
                        knownCommitSeen = true
                        if (!committedIds.add(row.committedId)) {
                            throw JournalInvariantViolation("Committed note IDs must be unique")
                        }
                    }
                }
                is AlignedResult.NoteNotAttempted -> throw JournalInvariantViolation("Create notAttempted lacks a terminal predecessor")
                else -> throw JournalInvariantViolation("Non-note row in create response")
            }
        }
        when (val active = terminal) {
            null -> if (error != null) throw JournalInvariantViolation("Create top-level error has no terminal row")
            is AlignedResult.NoteFailed -> requireMatchingError(active.rowError, error)
            is AlignedResult.NoteCommittedFailed -> {
                requireMatchingError(active.rowError, error)
                if (error?.retryable == true) {
                    throw JournalInvariantViolation("A known committed failure cannot be retryable")
                }
                if (error?.code == JournalErrorCode.CANCELLED) {
                    throw JournalInvariantViolation("A known committed failure cannot be clean cancellation")
                }
            }
            is AlignedResult.NoteUncertain -> requirePostCommitUncertain(error)
            else -> error("unreachable terminal row")
        }
        if (knownCommitSeen && error?.retryable == true) {
            throw JournalInvariantViolation("A note result after a known write cannot be retryable")
        }
        if (
            error?.code == JournalErrorCode.POST_COMMIT_UNCERTAIN &&
            terminal !is AlignedResult.NoteUncertain && terminal !is AlignedResult.NoteCommittedFailed
        ) {
            throw JournalInvariantViolation("Note post-commit uncertainty lacks a row-local carrier")
        }
    }

    private fun requireMatchingError(row: JournalError, top: JournalError?) {
        if (top != row) throw JournalInvariantViolation("Row-local and top-level errors do not match")
    }

    private fun requirePostCommitUncertain(error: JournalError?) {
        if (error?.code != JournalErrorCode.POST_COMMIT_UNCERTAIN || error.retryable) {
            throw JournalInvariantViolation("Uncertain result requires non-retryable post_commit_uncertain")
        }
    }

    private fun requireOperation(request: JournalRequest, operation: ParentOperation) {
        if (request.operation != operation) throw JournalInvariantViolation("Wrong terminal response operation")
    }
}

internal data class MediaNamespaceOwner(val runId: String, val assetId: String)

internal data class MediaNamespaceLock(
    val owner: MediaNamespaceOwner,
    val directFilename: String,
    val providerPrefix: String,
) {
    init {
        require(directFilename.isNotBlank() && providerPrefix.isNotBlank())
    }
}

/** O(n log n) exact-name and prefix/stem collision validation for at most 16,000 locks. */
internal object MediaNamespaceValidator {
    /**
     * Returns only incoming owners involved in a collision. Existing locks are never decisions;
     * incoming-to-incoming collision components reject all members so admission is order-independent.
     */
    fun refuseCandidates(
        existing: List<MediaNamespaceLock>,
        candidates: List<MediaNamespaceLock>,
    ): Map<MediaNamespaceOwner, MediaAdmissionViolation> {
        require(candidates.map { it.owner }.distinct().size == candidates.size)
        val refused = linkedMapOf<MediaNamespaceOwner, MediaAdmissionViolation>()
        candidates.forEach { candidate ->
            for (held in existing) {
                try {
                    requireDisjoint(listOf(held, candidate))
                } catch (failure: MediaAdmissionViolation) {
                    refused.putIfAbsent(candidate.owner, failure)
                    break
                }
            }
        }
        candidates.forEachIndexed { leftIndex, left ->
            for (rightIndex in leftIndex + 1 until candidates.size) {
                val right = candidates[rightIndex]
                try {
                    requireDisjoint(listOf(left, right))
                } catch (failure: MediaAdmissionViolation) {
                    refused.putIfAbsent(left.owner, failure)
                    refused.putIfAbsent(right.owner, failure)
                }
            }
        }
        return refused
    }

    fun requireDisjoint(locks: List<MediaNamespaceLock>) {
        if (locks.size > GLOBAL_UNRESOLVED_CLAIM_LIMIT) {
            throw MediaAdmissionViolation(
                MediaAdmissionRefusal.GLOBAL_NAMESPACE_CAPACITY,
                "Global media namespace capacity exhausted",
            )
        }
        val direct = locks.sortedWith(compareBy(MediaNamespaceLock::directFilename, { it.owner.runId }, { it.owner.assetId }))
        for (index in 1 until direct.size) {
            val left = direct[index - 1]
            val right = direct[index]
            if (left.directFilename == right.directFilename && left.owner != right.owner) {
                throw MediaAdmissionViolation(
                    MediaAdmissionRefusal.DIRECT_NAME_COLLISION,
                    "Media direct-name namespace collision",
                    collisionDetail(left.owner, right.owner),
                )
            }
        }

        val events =
            buildList {
                for (lock in locks) {
                    add(NamespaceEvent(lock.providerPrefix, isPrefix = true, lock.owner))
                    filenameStem(lock.directFilename)?.let { add(NamespaceEvent(it, isPrefix = false, lock.owner)) }
                }
            }.sortedWith(
                compareBy<NamespaceEvent>({ it.text }, { if (it.isPrefix) 0 else 1 }, { it.owner.runId }, { it.owner.assetId }),
            )
        val activePrefixes = ArrayList<NamespaceEvent>()
        val activeOwners = HashMap<MediaNamespaceOwner, Int>()
        for (event in events) {
            while (activePrefixes.isNotEmpty() && !event.text.startsWith(activePrefixes.last().text)) {
                val removed = activePrefixes.removeAt(activePrefixes.lastIndex)
                val count = checkNotNull(activeOwners[removed.owner]) - 1
                if (count == 0) activeOwners.remove(removed.owner) else activeOwners[removed.owner] = count
            }
            if (activeOwners.isNotEmpty() && (activeOwners.size > 1 || event.owner !in activeOwners)) {
                // The colliding pair is what a report needs: "one of these fifty assets overlapped"
                // is not actionable, and the names themselves must never leave the device.
                throw MediaAdmissionViolation(
                    MediaAdmissionRefusal.PROVIDER_NAMESPACE_OVERLAP,
                    "Media provider namespace overlaps another owner",
                    collisionDetail(activePrefixes.last().owner, event.owner) +
                        ";kind=" + (if (event.isPrefix) "prefix" else "stem") +
                        ";under=" + (if (activeOwners.size > 1) "nested" else "single"),
                )
            }
            if (event.isPrefix) {
                activePrefixes += event
                activeOwners[event.owner] = (activeOwners[event.owner] ?: 0) + 1
            }
        }
    }

    private data class NamespaceEvent(
        val text: String,
        val isPrefix: Boolean,
        val owner: MediaNamespaceOwner,
    )

    /**
     * Names the two colliding assets by their opaque wire identities.
     *
     * `sameRun` matters: it separates a batch that collided with itself from one that collided with
     * an earlier run's retained claim, and those have entirely different causes.
     */
    private fun collisionDetail(
        held: MediaNamespaceOwner,
        incoming: MediaNamespaceOwner,
    ): String =
        "held=${held.assetId};incoming=${incoming.assetId};sameRun=${held.runId == incoming.runId}"

    private fun filenameStem(filename: String): String? {
        val dot = filename.lastIndexOf('.')
        return if (dot > 0 && dot < filename.lastIndex) filename.substring(0, dot) else null
    }
}
