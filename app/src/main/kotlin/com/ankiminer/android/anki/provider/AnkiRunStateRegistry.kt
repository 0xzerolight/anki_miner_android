package com.ankiminer.android.anki.provider

import com.ankiminer.android.anki.generated.AnkiLimitsV1
import com.ankiminer.android.anki.protocol.AnkiProtocolException
import com.ankiminer.android.anki.protocol.AnkiValidators
import com.ankiminer.android.anki.protocol.CreateNotesResult
import com.ankiminer.android.anki.protocol.CreatedNote
import com.ankiminer.android.anki.protocol.DuplicateCandidate
import com.ankiminer.android.anki.protocol.KnownVocabularyCursor
import com.ankiminer.android.anki.protocol.ReleaseState
import com.ankiminer.android.anki.protocol.StoreMediaResult
import com.ankiminer.android.anki.protocol.StoredMedia
import com.ankiminer.android.anki.protocol.VerifyTargetRequest
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

internal fun interface OpaqueTokenFactory {
    fun nextToken(prefix: String): String
}

internal class SecureOpaqueTokenFactory : OpaqueTokenFactory {
    private val random = SecureRandom()

    override fun nextToken(prefix: String): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return buildString(prefix.length + 32) {
            append(prefix)
            for (byte in bytes) append(HEX[byte.toInt() and 0xff])
        }
    }

    private companion object {
        val HEX = Array(256) { value -> "%02x".format(value) }
    }
}

internal fun interface AnkiRunCleanup {
    /**
     * Called outside the registry lock. A null set means abandon; a non-null frozen set is exact
     * acknowledgement evidence. A failure leaves the run quarantined for an explicit retry.
     */
    fun cleanup(
        runId: String,
        durableResponseIds: Set<String>?,
    )
}

internal fun interface AnkiStartupAdmission {
    fun isOpen(): Boolean

    companion object {
        val OPEN = AnkiStartupAdmission { true }
    }
}

internal data class MediaAcknowledgement(
    val assetId: String,
    val actualFilename: String,
    val durableClaimId: Long,
)

internal enum class ProviderMutationOperation {
    MEDIA_INSERT,
    NOTE_INSERT,
    CARD_ROUTING,
}

internal data class ProviderMutationScope(
    val requestId: String,
    val operation: ProviderMutationOperation,
    val durableChildId: Long,
    val itemIdentity: String,
)

internal class ProviderEntryCapability internal constructor(
    internal val runId: String,
    internal val ownerId: Long,
    internal val generation: Long,
    val scope: ProviderMutationScope,
) {
    internal var lifecycle = ProviderEntryLifecycle.REGISTERED
}

internal enum class ProviderEntryAuthorization {
    AUTHORIZED,
    QUARANTINED,
    RELEASING,
    CANCELLED,
}

internal enum class ProviderEntryLifecycle {
    REGISTERED,
    AUTHORIZING,
    DENIED,
    AUTHORIZED,
    ABORTED,
    COMPLETED,
}

internal data class DuplicateBaseline(
    val token: String,
    val target: TargetSnapshot,
    val firstFieldName: String,
    val scopeDeckId: Long?,
    val candidates: List<DuplicateCandidate>,
    val occurrences: List<Int>,
    val providerNoteIds: List<Set<Long>>,
    val normalizedMatchingNoteIds: List<Set<Long>>,
)

internal data class KnownTraversalInitialization(
    internal val runId: String,
    internal val ownerId: Long,
    internal val generation: Long,
    val scope: KnownTraversalScope,
)

internal data class KnownTraversalScope(
    val excludedDecks: List<String>,
    val deckName: String?,
)

internal data class KnownPageLease(
    internal val runId: String,
    internal val ownerId: Long,
    internal val generation: Long,
    internal val startIndex: Int,
    val noteIds: List<Long>,
    val responseCursorOrdinal: Long,
    val hasMoreAfterPage: Boolean,
)

internal data class BaselineProbe(
    internal val runId: String,
    internal val ownerId: Long,
    internal val generation: Long,
)

internal class TargetVerificationReservation internal constructor(
    internal val runId: String,
    internal val ownerId: Long,
    internal val generation: Long,
    internal val request: VerifyTargetRequest,
    val installedTarget: TargetSnapshot?,
) {
    internal val completed = AtomicBoolean(false)
    internal val providerEntryAuthorized = AtomicBoolean(false)
}

internal enum class TargetProviderEntryAuthorization {
    AUTHORIZED,
    QUARANTINED,
    RELEASING,
    CANCELLED,
}

internal class AnkiRunStateRegistry(
    private val startupAdmission: AnkiStartupAdmission = AnkiStartupAdmission.OPEN,
    private val cleanup: AnkiRunCleanup = AnkiRunCleanup { _, _ -> },
) {
    private val lock = Any()
    private val states = HashMap<String, RunState>()
    private var nextOwnerId = 1L
    private var nextGeneration = 1L

    /** One mining run at a time is an engine invariant. */
    fun register(
        runId: String,
        cancellation: AnkiCancellation,
    ): Boolean =
        synchronized(lock) {
            if (!startupAdmission.isOpen()) return@synchronized false
            if (states.isNotEmpty() || states.containsKey(runId)) return@synchronized false
            states[runId] = RunState(runId, cancellation)
            true
        }

    fun <T> withOwner(
        runId: String,
        block: (RunOwner) -> T,
    ): T {
        val owner = admit(runId)
        try {
            return block(owner)
        } finally {
            owner.close()
        }
    }

    fun release(
        runId: String,
        acknowledgeTerminalResponses: Boolean,
    ): ReleaseState {
        val cleanupAction: CleanupAction?
        val result: ReleaseState
        synchronized(lock) {
            val state = states[runId] ?: return ReleaseState.ABSENT
            if (!state.releaseRequested) {
                state.releaseRequested = true
                state.releaseAcknowledgement = acknowledgeTerminalResponses
                if (acknowledgeTerminalResponses && !state.terminalReceiptFailure) {
                    state.frozenDurableResponseIds =
                        Collections.unmodifiableSet(HashSet(state.durableResponseIds))
                }
            } else {
                state.releaseAcknowledgement =
                    state.releaseAcknowledgement?.and(acknowledgeTerminalResponses)
                        ?: acknowledgeTerminalResponses
            }
            if (!acknowledgeTerminalResponses) {
                markTerminalFailureLocked(state)
            }
            cleanupAction = if (state.owners.isEmpty()) beginCleanupLocked(state) else null
            result = if (cleanupAction == null) ReleaseState.DEFERRED else ReleaseState.RELEASED
        }
        cleanupAction?.run()
        return result
    }

    fun cancellation(owner: RunOwner): AnkiCancellation =
        synchronized(lock) { requireOwnerLocked(owner).cancellation }

    fun target(owner: RunOwner): TargetSnapshot? =
        synchronized(lock) { requireOwnerLocked(owner).target }

    fun beginTargetVerification(
        owner: RunOwner,
        request: VerifyTargetRequest,
    ): TargetVerificationReservation =
        synchronized(lock) {
            val state = requireOwnerLocked(owner)
            if (state.terminalReceiptFailure) throw RunStateConflictException()
            if (state.releaseRequested) throw RunReleasingException()
            if (state.targetVerification != null) throw TargetVerificationInProgressException()
            val reservation =
                TargetVerificationReservation(
                    runId = state.runId,
                    ownerId = owner.ownerId,
                    generation = nextGeneration++,
                    request = request,
                    installedTarget = state.target,
                )
            state.targetVerification = reservation
            reservation
        }

    /**
     * Commits one canonically encoded durable response. All validation precedes both the ID insert
     * and target admission, so release/duplicate/conflict failure installs neither new value.
     */
    fun commitDurableTargetResponse(
        owner: RunOwner,
        reservation: TargetVerificationReservation,
        requestId: String,
        target: TargetSnapshot?,
    ) {
        synchronized(lock) {
            val state = requireOwnerLocked(owner)
            fun fail(error: RuntimeException): Nothing {
                markTerminalFailureLocked(state)
                throw error
            }
            if (state.terminalReceiptFailure) fail(RunStateConflictException())
            if (state.releaseRequested) fail(RunReleasingException())
            if (
                reservation.completed.get() ||
                    reservation.runId != state.runId ||
                    reservation.ownerId != owner.ownerId ||
                    state.targetVerification !== reservation
            ) {
                fail(InvalidCapabilityException())
            }
            if (!AnkiValidators.isValidRequestId(requestId)) fail(RunStateConflictException())
            if (requestId != reservation.request.requestId) fail(RunStateConflictException())
            if (requestId in state.durableResponseIds) fail(RunStateConflictException())
            if (state.durableResponseIds.size >= MAX_TERMINAL_RESPONSE_RECEIPTS) {
                fail(RunStateCapacityException())
            }
            if (state.target != reservation.installedTarget) fail(RunStateConflictException())
            if (target != null) {
                try {
                    ProviderSnapshotValidation.validateModel(target.model)
                    ProviderSnapshotValidation.validateDeck(target.deck)
                } catch (_: InvalidTargetSnapshotException) {
                    fail(RunStateConflictException())
                }
                if (
                    target.deck.name != reservation.request.deckName ||
                        target.model.name != reservation.request.modelName ||
                        !target.model.fieldNames.containsAll(reservation.request.requiredFields) ||
                        (state.target != null && state.target != target)
                ) {
                    fail(RunStateConflictException())
                }
            }

            state.durableResponseIds += requestId
            // A durable verify error quarantines any prior target in the same atomic admission.
            state.target = target
            state.targetVerification = null
            reservation.completed.set(true)
        }
    }

    fun abortTargetVerification(
        owner: RunOwner,
        reservation: TargetVerificationReservation,
    ) {
        if (reservation.completed.get()) return
        synchronized(lock) {
            if (reservation.completed.get()) return@synchronized
            val state = requireOwnerLocked(owner)
            if (state.targetVerification !== reservation) throw InvalidCapabilityException()
            state.targetVerification = null
            reservation.completed.set(true)
        }
    }

    /** Linearization point separating cancellable pre-entry work from mandatory reconciliation. */
    fun authorizeTargetProviderEntry(
        owner: RunOwner,
        reservation: TargetVerificationReservation,
    ): TargetProviderEntryAuthorization =
        synchronized(lock) {
            val state = requireOwnerLocked(owner)
            if (
                reservation.completed.get() ||
                    reservation.runId != state.runId ||
                    reservation.ownerId != owner.ownerId ||
                    state.targetVerification !== reservation ||
                    reservation.providerEntryAuthorized.get()
            ) {
                throw InvalidCapabilityException()
            }
            if (state.terminalReceiptFailure) {
                return@synchronized TargetProviderEntryAuthorization.QUARANTINED
            }
            if (state.releaseRequested) return@synchronized TargetProviderEntryAuthorization.RELEASING
            if (state.cancellation.isCancelled()) {
                return@synchronized TargetProviderEntryAuthorization.CANCELLED
            }
            reservation.providerEntryAuthorized.set(true)
            TargetProviderEntryAuthorization.AUTHORIZED
        }

    /**
     * Registers one exact durable child mutation. Provider preflight happens after registration;
     * authorization below is the only transition across the provider-entry boundary.
     */
    fun beginProviderEntry(
        owner: RunOwner,
        scope: ProviderMutationScope,
    ): ProviderEntryCapability {
        validateProviderMutationScope(owner.runId, scope)
        return synchronized(lock) {
            val state = requireOwnerLocked(owner)
            if (state.terminalReceiptFailure) throw RunStateConflictException()
            if (state.releaseRequested) throw RunReleasingException()
            if (state.providerEntry != null) throw ProviderEntryInProgressException()
            ProviderEntryCapability(
                runId = state.runId,
                ownerId = owner.ownerId,
                generation = nextGeneration++,
                scope = scope,
            ).also { state.providerEntry = it }
        }
    }

    /**
     * Authorizes exactly once. The cancellation callback is deliberately evaluated without the
     * registry lock; release and quarantine are checked on both sides of that callback.
     */
    fun authorizeProviderEntry(
        owner: RunOwner,
        capability: ProviderEntryCapability,
        scope: ProviderMutationScope,
    ): ProviderEntryAuthorization {
        val cancellation =
            synchronized(lock) {
                val state = requireProviderEntryLocked(owner, capability, scope)
                if (capability.lifecycle != ProviderEntryLifecycle.REGISTERED) {
                    throw InvalidCapabilityException()
                }
                when {
                    state.terminalReceiptFailure -> {
                        capability.lifecycle = ProviderEntryLifecycle.DENIED
                        return ProviderEntryAuthorization.QUARANTINED
                    }
                    state.releaseRequested -> {
                        capability.lifecycle = ProviderEntryLifecycle.DENIED
                        return ProviderEntryAuthorization.RELEASING
                    }
                    else -> {
                        capability.lifecycle = ProviderEntryLifecycle.AUTHORIZING
                        state.cancellation
                    }
                }
            }

        val cancelled =
            try {
                cancellation.isCancelled()
            } catch (error: RuntimeException) {
                synchronized(lock) {
                    val state = states[owner.runId]
                    if (
                        state != null &&
                            state.providerEntry === capability &&
                            capability.lifecycle == ProviderEntryLifecycle.AUTHORIZING
                    ) {
                        capability.lifecycle = ProviderEntryLifecycle.DENIED
                        markTerminalFailureLocked(state)
                    }
                }
                throw error
            }

        return synchronized(lock) {
            val state = requireProviderEntryLocked(owner, capability, scope)
            if (capability.lifecycle != ProviderEntryLifecycle.AUTHORIZING) {
                throw InvalidCapabilityException()
            }
            when {
                state.terminalReceiptFailure -> {
                    capability.lifecycle = ProviderEntryLifecycle.DENIED
                    ProviderEntryAuthorization.QUARANTINED
                }
                state.releaseRequested -> {
                    capability.lifecycle = ProviderEntryLifecycle.DENIED
                    ProviderEntryAuthorization.RELEASING
                }
                cancelled -> {
                    capability.lifecycle = ProviderEntryLifecycle.DENIED
                    ProviderEntryAuthorization.CANCELLED
                }
                else -> {
                    capability.lifecycle = ProviderEntryLifecycle.AUTHORIZED
                    ProviderEntryAuthorization.AUTHORIZED
                }
            }
        }
    }

    /**
     * Authorizes card routing needed to reconcile a note whose insert receipt is already durable.
     * User cancellation cannot revoke that post-commit obligation, but release and quarantine
     * still fail closed. This seam is deliberately unavailable to initial note/media inserts.
     */
    fun authorizeMandatoryReconciliationEntry(
        owner: RunOwner,
        capability: ProviderEntryCapability,
        scope: ProviderMutationScope,
    ): ProviderEntryAuthorization =
        synchronized(lock) {
            if (scope.operation != ProviderMutationOperation.CARD_ROUTING) {
                throw InvalidCapabilityException()
            }
            val state = requireProviderEntryLocked(owner, capability, scope)
            if (capability.lifecycle != ProviderEntryLifecycle.REGISTERED) {
                throw InvalidCapabilityException()
            }
            when {
                state.terminalReceiptFailure -> {
                    capability.lifecycle = ProviderEntryLifecycle.DENIED
                    ProviderEntryAuthorization.QUARANTINED
                }
                state.releaseRequested -> {
                    capability.lifecycle = ProviderEntryLifecycle.DENIED
                    ProviderEntryAuthorization.RELEASING
                }
                else -> {
                    capability.lifecycle = ProviderEntryLifecycle.AUTHORIZED
                    ProviderEntryAuthorization.AUTHORIZED
                }
            }
        }

    fun abortProviderEntry(
        owner: RunOwner,
        capability: ProviderEntryCapability,
        scope: ProviderMutationScope,
    ) {
        synchronized(lock) {
            val state = requireProviderEntryLocked(owner, capability, scope)
            if (
                capability.lifecycle != ProviderEntryLifecycle.REGISTERED &&
                    capability.lifecycle != ProviderEntryLifecycle.DENIED
            ) {
                throw InvalidCapabilityException()
            }
            state.providerEntry = null
            capability.lifecycle = ProviderEntryLifecycle.ABORTED
        }
    }

    /** Clears an authorized entry only after its provider outcome has been durably reconciled. */
    fun completeProviderEntry(
        owner: RunOwner,
        capability: ProviderEntryCapability,
        scope: ProviderMutationScope,
    ) {
        synchronized(lock) {
            val state = requireProviderEntryLocked(owner, capability, scope)
            if (capability.lifecycle != ProviderEntryLifecycle.AUTHORIZED) {
                throw InvalidCapabilityException()
            }
            state.providerEntry = null
            capability.lifecycle = ProviderEntryLifecycle.COMPLETED
        }
    }

    fun beginKnownTraversal(
        owner: RunOwner,
        scope: KnownTraversalScope,
    ): KnownTraversalInitialization =
        synchronized(lock) {
            val state = requireOwnerLocked(owner)
            if (state.knownTraversal != null || state.knownInitialization != null) {
                throw RunStateConflictException()
            }
            val generation = nextGeneration++
            state.knownInitialization = generation
            KnownTraversalInitialization(
                state.runId,
                owner.ownerId,
                generation,
                scope.copy(excludedDecks = scope.excludedDecks.toList()),
            )
        }

    fun finishKnownTraversalInitialization(
        owner: RunOwner,
        initialization: KnownTraversalInitialization,
        noteIds: List<Long>,
    ): KnownPageLease =
        synchronized(lock) {
            val state = requireOwnerLocked(owner)
            requireInitialization(state, owner, initialization)
            state.knownInitialization = null
            val traversal = KnownTraversal(initialization.scope, noteIds.toList())
            state.knownTraversal = traversal
            reserveKnownPageLocked(state, owner, traversal, requestedCursor = null)
        }

    fun abortKnownTraversalInitialization(
        owner: RunOwner,
        initialization: KnownTraversalInitialization,
    ) {
        synchronized(lock) {
            val state = requireOwnerLocked(owner)
            requireInitialization(state, owner, initialization)
            state.knownInitialization = null
        }
    }

    fun reserveKnownPage(
        owner: RunOwner,
        scope: KnownTraversalScope,
        requestedCursor: KnownVocabularyCursor,
    ): KnownPageLease =
        synchronized(lock) {
            val state = requireOwnerLocked(owner)
            val traversal = state.knownTraversal ?: throw InvalidCapabilityException()
            if (traversal.scope != scope || traversal.expectedCursor != requestedCursor) {
                throw InvalidCapabilityException()
            }
            reserveKnownPageLocked(state, owner, traversal, requestedCursor)
        }

    fun completeKnownPage(
        owner: RunOwner,
        lease: KnownPageLease,
        nextToken: String?,
    ): KnownVocabularyCursor? =
        synchronized(lock) {
            val state = requireOwnerLocked(owner)
            val traversal = requireKnownLease(state, owner, lease)
            if (lease.hasMoreAfterPage != (nextToken != null)) throw RunStateConflictException()
            traversal.pageInFlight = false
            traversal.nextIndex = lease.startIndex + lease.noteIds.size
            if (!lease.hasMoreAfterPage) {
                state.knownTraversal = null
                null
            } else {
                val token = nextToken ?: throw RunStateConflictException()
                val cursor = KnownVocabularyCursor(lease.responseCursorOrdinal, token)
                traversal.expectedCursor = cursor
                cursor
            }
        }

    fun abortKnownPage(
        owner: RunOwner,
        lease: KnownPageLease,
    ) {
        synchronized(lock) {
            val state = requireOwnerLocked(owner)
            requireKnownLease(state, owner, lease)
            state.knownTraversal = null
        }
    }

    fun beginBaselineProbe(
        owner: RunOwner,
        invalidateToken: String?,
    ): BaselineProbe =
        synchronized(lock) {
            val state = requireOwnerLocked(owner)
            if (state.baselineProbe != null) throw RunStateConflictException()
            val prior = state.duplicateBaseline
            if (prior?.token != invalidateToken || (prior == null && invalidateToken != null)) {
                throw InvalidCapabilityException()
            }
            state.duplicateBaseline = null
            val generation = nextGeneration++
            state.baselineProbe = generation
            BaselineProbe(state.runId, owner.ownerId, generation)
        }

    fun completeBaselineProbe(
        owner: RunOwner,
        probe: BaselineProbe,
        baseline: DuplicateBaseline,
    ) {
        val frozen = freezeDuplicateBaseline(baseline)
        synchronized(lock) {
            val state = requireOwnerLocked(owner)
            requireBaselineProbe(state, owner, probe)
            if (state.target != frozen.target) throw RunStateConflictException()
            state.baselineProbe = null
            state.duplicateBaseline = frozen
        }
    }

    fun abortBaselineProbe(
        owner: RunOwner,
        probe: BaselineProbe,
    ) {
        synchronized(lock) {
            val state = requireOwnerLocked(owner)
            requireBaselineProbe(state, owner, probe)
            state.baselineProbe = null
        }
    }

    fun consumeBaseline(
        owner: RunOwner,
        token: String,
    ): DuplicateBaseline =
        synchronized(lock) {
            val state = requireOwnerLocked(owner)
            val baseline = state.duplicateBaseline ?: throw InvalidCapabilityException()
            if (baseline.token != token) throw InvalidCapabilityException()
            state.duplicateBaseline = null
            baseline
        }

    fun mediaAcknowledgement(
        owner: RunOwner,
        assetId: String,
    ): MediaAcknowledgement? =
        synchronized(lock) { requireOwnerLocked(owner).mediaAcknowledgements[assetId] }

    /**
     * Atomically admits a canonically encoded RESULT_READY response and every media claim it
     * acknowledges. Input contract validation runs outside the registry lock; all state checks
     * precede either map or receipt mutation.
     */
    fun commitDurableMutationResponse(
        owner: RunOwner,
        requestId: String,
        mediaAcknowledgements: List<MediaAcknowledgement>,
    ) {
        try {
            validateMutationResponseInput(owner.runId, requestId, mediaAcknowledgements)
        } catch (error: RuntimeException) {
            synchronized(lock) { markTerminalFailureLocked(requireOwnerLocked(owner)) }
            if (error is RunStateCapacityException) throw error
            throw RunStateConflictException()
        }

        synchronized(lock) {
            val state = requireOwnerLocked(owner)
            fun fail(error: RuntimeException): Nothing {
                markTerminalFailureLocked(state)
                throw error
            }

            if (state.terminalReceiptFailure) fail(RunStateConflictException())
            if (state.releaseRequested) fail(RunReleasingException())
            if (state.providerEntry != null) fail(RunStateConflictException())
            if (requestId in state.durableResponseIds) fail(RunStateConflictException())
            if (state.durableResponseIds.size >= MAX_TERMINAL_RESPONSE_RECEIPTS) {
                fail(RunStateCapacityException())
            }

            val newAcknowledgements = ArrayList<MediaAcknowledgement>()
            for (acknowledgement in mediaAcknowledgements) {
                val prior = state.mediaAcknowledgements[acknowledgement.assetId]
                if (prior != null && prior != acknowledgement) fail(RunStateConflictException())
                if (
                    state.mediaAcknowledgements.values.any { existing ->
                        existing.assetId != acknowledgement.assetId &&
                            (existing.actualFilename == acknowledgement.actualFilename ||
                                existing.durableClaimId == acknowledgement.durableClaimId)
                    }
                ) {
                    fail(RunStateConflictException())
                }
                if (prior == null) newAcknowledgements += acknowledgement
            }
            if (
                state.mediaAcknowledgements.size + newAcknowledgements.size >
                    AnkiLimitsV1.CreateCall.MAX_MEDIA_REFERENCE_COUNT
            ) {
                fail(RunStateCapacityException())
            }

            state.durableResponseIds += requestId
            for (acknowledgement in newAcknowledgements) {
                state.mediaAcknowledgements[acknowledgement.assetId] = acknowledgement
            }
        }
    }

    /** Called only after durable RESULT_READY terminalization and canonical response encoding. */
    fun retainDurableTerminalResponse(
        owner: RunOwner,
        requestId: String,
    ) {
        synchronized(lock) {
            val state = requireOwnerLocked(owner)
            if (!AnkiValidators.isValidRequestId(requestId)) {
                markTerminalFailureLocked(state)
                throw RunStateConflictException()
            }
            if (state.releaseRequested) {
                markTerminalFailureLocked(state)
                throw RunStateConflictException()
            }
            if (!state.durableResponseIds.add(requestId)) {
                markTerminalFailureLocked(state)
                throw RunStateConflictException()
            }
            if (state.durableResponseIds.size > MAX_TERMINAL_RESPONSE_RECEIPTS) {
                state.durableResponseIds.remove(requestId)
                markTerminalFailureLocked(state)
                throw RunStateCapacityException()
            }
        }
    }

    fun markTerminalResponseFailure(owner: RunOwner) {
        synchronized(lock) { markTerminalFailureLocked(requireOwnerLocked(owner)) }
    }

    private fun markTerminalFailureLocked(state: RunState) {
        state.terminalReceiptFailure = true
        state.target = null
    }

    private fun admit(runId: String): RunOwner =
        synchronized(lock) {
            val state = states[runId] ?: throw RunNotRegisteredException()
            if (state.terminalReceiptFailure) throw RunStateConflictException()
            if (state.releaseRequested) throw RunReleasingException()
            if (state.cancellation.isCancelled()) throw RunCancelledException()
            val ownerId = nextOwnerId++
            state.owners += ownerId
            RunOwner(this, runId, ownerId)
        }

    private fun releaseOwner(owner: RunOwner) {
        val cleanupAction: CleanupAction?
        synchronized(lock) {
            val state = states[owner.runId] ?: return
            if (!state.owners.remove(owner.ownerId)) return
            cleanupAction =
                if (state.releaseRequested && state.owners.isEmpty()) {
                    beginCleanupLocked(state)
                } else {
                    null
                }
        }
        cleanupAction?.run()
    }

    private fun beginCleanupLocked(state: RunState): CleanupAction? {
        if (state.cleanupStarted || state.providerEntry != null) return null
        state.cleanupStarted = true
        val durableResponseIds =
            if (state.releaseAcknowledgement == true && !state.terminalReceiptFailure) {
                state.frozenDurableResponseIds
            } else {
                null
            }
        return CleanupAction(state, durableResponseIds)
    }

    private fun reserveKnownPageLocked(
        state: RunState,
        owner: RunOwner,
        traversal: KnownTraversal,
        requestedCursor: KnownVocabularyCursor?,
    ): KnownPageLease {
        if (traversal.pageInFlight) throw InvalidCapabilityException()
        traversal.pageInFlight = true
        traversal.expectedCursor = null
        val start = traversal.nextIndex
        val end = minOf(start + AnkiLimitsV1.ScanFirstFields.KNOWN_PAGE_MAX_ITEM_COUNT, traversal.noteIds.size)
        val ids = traversal.noteIds.subList(start, end).toList()
        val responseOrdinal = requestedCursor?.ordinal?.let(Math::incrementExact) ?: 1L
        return KnownPageLease(
            runId = state.runId,
            ownerId = owner.ownerId,
            generation = traversal.generation,
            startIndex = start,
            noteIds = ids,
            responseCursorOrdinal = responseOrdinal,
            hasMoreAfterPage = end < traversal.noteIds.size,
        )
    }

    private fun requireOwnerLocked(owner: RunOwner): RunState {
        val state = states[owner.runId] ?: throw RunNotRegisteredException()
        if (owner.ownerId !in state.owners || owner.closed.get()) throw InvalidCapabilityException()
        return state
    }

    private fun requireProviderEntryLocked(
        owner: RunOwner,
        capability: ProviderEntryCapability,
        scope: ProviderMutationScope,
    ): RunState {
        val state = requireOwnerLocked(owner)
        if (
            state.providerEntry !== capability ||
                capability.runId != state.runId ||
                capability.ownerId != owner.ownerId ||
                capability.scope != scope
        ) {
            throw InvalidCapabilityException()
        }
        return state
    }

    private fun validateProviderMutationScope(
        runId: String,
        scope: ProviderMutationScope,
    ) {
        try {
            if (!AnkiValidators.isValidRunId(runId) || !AnkiValidators.isValidRequestId(scope.requestId)) {
                throw InvalidCapabilityException()
            }
            if (scope.durableChildId <= 0L) throw InvalidCapabilityException()
            when (scope.operation) {
                ProviderMutationOperation.MEDIA_INSERT ->
                    AnkiValidators.validateResponse(
                        StoreMediaResult(
                            runId = runId,
                            requestId = scope.requestId,
                            results = listOf(StoredMedia(scope.itemIdentity, "scope-validation.bin")),
                            error = null,
                        ),
                    )
                ProviderMutationOperation.NOTE_INSERT ->
                    AnkiValidators.validateResponse(
                        CreateNotesResult(
                            runId = runId,
                            requestId = scope.requestId,
                            results = listOf(CreatedNote(scope.itemIdentity, noteId = 1L)),
                            error = null,
                        ),
                    )
                ProviderMutationOperation.CARD_ROUTING -> {
                    val cardId = scope.itemIdentity.toLongOrNull()
                    if (cardId == null || cardId <= 0L || cardId.toString() != scope.itemIdentity) {
                        throw InvalidCapabilityException()
                    }
                }
            }
        } catch (_: RuntimeException) {
            throw InvalidCapabilityException()
        }
    }

    private fun validateMutationResponseInput(
        runId: String,
        requestId: String,
        acknowledgements: List<MediaAcknowledgement>,
    ) {
        if (!AnkiValidators.isValidRunId(runId) || !AnkiValidators.isValidRequestId(requestId)) {
            throw RunStateConflictException()
        }
        if (acknowledgements.size > AnkiLimitsV1.StoreMedia.MAX_ASSET_COUNT) {
            throw RunStateCapacityException()
        }
        if (
            acknowledgements.any { it.durableClaimId <= 0L } ||
                acknowledgements.map { it.durableClaimId }.toSet().size != acknowledgements.size
        ) {
            throw RunStateConflictException()
        }
        acknowledgements.forEach { acknowledgement ->
            requireSafeCanonicalMediaName(
                acknowledgement.actualFilename,
                "acknowledged provider media filename",
                minimumScalarCount = 1,
            )
        }
        if (acknowledgements.isNotEmpty()) {
            AnkiValidators.validateResponse(
                StoreMediaResult(
                    runId = runId,
                    requestId = requestId,
                    results =
                        acknowledgements.map { acknowledgement ->
                            StoredMedia(
                                acknowledgement.assetId,
                                acknowledgement.actualFilename,
                            )
                        },
                    error = null,
                ),
            )
        }
    }

    private fun requireInitialization(
        state: RunState,
        owner: RunOwner,
        initialization: KnownTraversalInitialization,
    ) {
        if (
            initialization.runId != state.runId ||
                initialization.ownerId != owner.ownerId ||
                initialization.generation != state.knownInitialization
        ) {
            throw InvalidCapabilityException()
        }
    }

    private fun requireKnownLease(
        state: RunState,
        owner: RunOwner,
        lease: KnownPageLease,
    ): KnownTraversal {
        val traversal = state.knownTraversal ?: throw InvalidCapabilityException()
        if (
            lease.runId != state.runId ||
                lease.ownerId != owner.ownerId ||
                lease.generation != traversal.generation ||
                lease.startIndex != traversal.nextIndex ||
                !traversal.pageInFlight
        ) {
            throw InvalidCapabilityException()
        }
        return traversal
    }

    private fun requireBaselineProbe(
        state: RunState,
        owner: RunOwner,
        probe: BaselineProbe,
    ) {
        if (
            probe.runId != state.runId ||
                probe.ownerId != owner.ownerId ||
                probe.generation != state.baselineProbe
        ) {
            throw InvalidCapabilityException()
        }
    }

    internal class RunOwner internal constructor(
        private val registry: AnkiRunStateRegistry,
        internal val runId: String,
        internal val ownerId: Long,
    ) : AutoCloseable {
        internal val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) registry.releaseOwner(this)
        }
    }

    private inner class CleanupAction(
        private val state: RunState,
        private val durableResponseIds: Set<String>?,
    ) {
        fun run() {
            try {
                cleanup.cleanup(state.runId, durableResponseIds)
            } catch (error: RuntimeException) {
                synchronized(lock) {
                    if (states[state.runId] === state) state.cleanupStarted = false
                }
                throw error
            }
            synchronized(lock) {
                if (states[state.runId] === state) states.remove(state.runId)
            }
        }
    }

    private inner class KnownTraversal(
        val scope: KnownTraversalScope,
        val noteIds: List<Long>,
    ) {
        val generation = nextGeneration++
        var nextIndex = 0
        var expectedCursor: KnownVocabularyCursor? = null
        var pageInFlight = false
    }

    private fun freezeDuplicateBaseline(baseline: DuplicateBaseline): DuplicateBaseline {
        val count = baseline.candidates.size
        val occurrenceCount = baseline.occurrences.size
        if (
            count > AnkiLimitsV1.ScanFirstFields.DUPLICATE_CANDIDATE_MAX_ITEM_COUNT ||
                occurrenceCount > AnkiLimitsV1.ScanFirstFields.DUPLICATE_CANDIDATE_MAX_ITEM_COUNT ||
                baseline.providerNoteIds.any {
                    it.size > AnkiLimitsV1.ScanFirstFields.DUPLICATE_HIT_PER_CANDIDATE_MAX_ITEM_COUNT
                } ||
                baseline.normalizedMatchingNoteIds.any {
                    it.size > AnkiLimitsV1.ScanFirstFields.DUPLICATE_HIT_PER_CANDIDATE_MAX_ITEM_COUNT
                }
        ) {
            throw RunStateCapacityException()
        }
        if (
            count == 0 ||
                occurrenceCount == 0 ||
                baseline.providerNoteIds.size != count ||
                baseline.normalizedMatchingNoteIds.size != count ||
                baseline.candidates.distinct().size != count ||
                baseline.occurrences.any { it !in baseline.candidates.indices } ||
                baseline.occurrences.toSet() != baseline.candidates.indices.toSet() ||
                !BASELINE_TOKEN_PATTERN.matches(baseline.token) ||
                baseline.firstFieldName != baseline.target.model.fieldNames.firstOrNull() ||
                (baseline.scopeDeckId != null && baseline.scopeDeckId != baseline.target.deck.id)
        ) {
            throw RunStateConflictException()
        }
        try {
            ProviderSnapshotValidation.validateModel(baseline.target.model)
            ProviderSnapshotValidation.validateDeck(baseline.target.deck)
        } catch (_: InvalidTargetSnapshotException) {
            throw RunStateConflictException()
        }

        var rawTotal = 0
        var matchingTotal = 0
        for (index in baseline.candidates.indices) {
            val candidate = baseline.candidates[index]
            val normalized =
                try {
                    val keyStats = AnkiValidators.strictStats(candidate.key, "duplicate key")
                    val fieldStats =
                        AnkiValidators.strictStats(candidate.firstField, "duplicate first field")
                    if (
                        candidate.key.isEmpty() ||
                            candidate.firstField.isEmpty() ||
                            keyStats.scalarCount >
                            AnkiLimitsV1.ScanFirstFields.DUPLICATE_KEY_MAX_CODE_POINTS ||
                            fieldStats.scalarCount >
                            AnkiLimitsV1.ScanFirstFields.DUPLICATE_FIRST_FIELD_MAX_CODE_POINTS
                    ) {
                        throw RunStateConflictException()
                    }
                    DuplicateFirstFieldNormalizer.normalize(candidate.firstField)
                } catch (_: AnkiProtocolException) {
                    throw RunStateConflictException()
                } catch (_: IllegalArgumentException) {
                    throw RunStateConflictException()
                }
            if (normalized != candidate.key) throw RunStateConflictException()

            val raw = baseline.providerNoteIds[index]
            val matching = baseline.normalizedMatchingNoteIds[index]
            if (raw.any { it <= 0L } || matching.any { it <= 0L } || !raw.containsAll(matching)) {
                throw RunStateConflictException()
            }
            rawTotal = addBaselineCount(rawTotal, raw.size)
            matchingTotal = addBaselineCount(matchingTotal, matching.size)
        }
        if (
            rawTotal > AnkiLimitsV1.ScanFirstFields.DUPLICATE_HIT_TOTAL_MAX_ITEM_COUNT ||
                matchingTotal > AnkiLimitsV1.ScanFirstFields.DUPLICATE_HIT_TOTAL_MAX_ITEM_COUNT
        ) {
            throw RunStateCapacityException()
        }

        return baseline.copy(
            candidates = Collections.unmodifiableList(baseline.candidates.toList()),
            occurrences = Collections.unmodifiableList(baseline.occurrences.toList()),
            providerNoteIds =
                Collections.unmodifiableList(
                    baseline.providerNoteIds.map { ids ->
                        Collections.unmodifiableSet(LinkedHashSet(ids))
                    },
                ),
            normalizedMatchingNoteIds =
                Collections.unmodifiableList(
                    baseline.normalizedMatchingNoteIds.map { ids ->
                        Collections.unmodifiableSet(LinkedHashSet(ids))
                    },
                ),
        )
    }

    private fun addBaselineCount(
        current: Int,
        increment: Int,
    ): Int =
        try {
            Math.addExact(current, increment)
        } catch (_: ArithmeticException) {
            throw RunStateCapacityException()
        }

    private class RunState(
        val runId: String,
        val cancellation: AnkiCancellation,
    ) {
        val owners = HashSet<Long>()
        var target: TargetSnapshot? = null
        var targetVerification: TargetVerificationReservation? = null
        var knownInitialization: Long? = null
        var knownTraversal: KnownTraversal? = null
        var duplicateBaseline: DuplicateBaseline? = null
        var baselineProbe: Long? = null
        var providerEntry: ProviderEntryCapability? = null
        val mediaAcknowledgements = HashMap<String, MediaAcknowledgement>()
        val durableResponseIds = HashSet<String>()
        var frozenDurableResponseIds: Set<String>? = null
        var terminalReceiptFailure = false
        var releaseRequested = false
        var releaseAcknowledgement: Boolean? = null
        var cleanupStarted = false
    }

    private companion object {
        const val MAX_TERMINAL_RESPONSE_RECEIPTS = 8192
        val BASELINE_TOKEN_PATTERN = Regex("baseline_[0-9a-f]{32}")
    }
}

internal class RunNotRegisteredException : RuntimeException()

internal class RunReleasingException : RuntimeException()

internal class RunCancelledException : RuntimeException()

internal class InvalidCapabilityException : RuntimeException()

internal class RunStateConflictException : RuntimeException()

internal class RunStateCapacityException : RuntimeException()

internal class TargetVerificationInProgressException : RuntimeException()

internal class ProviderEntryInProgressException : RuntimeException()
