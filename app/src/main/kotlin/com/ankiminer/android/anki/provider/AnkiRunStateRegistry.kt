package com.ankiminer.android.anki.provider

import com.ankiminer.android.anki.generated.AnkiLimitsV1
import com.ankiminer.android.anki.protocol.AnkiValidators
import com.ankiminer.android.anki.protocol.DuplicateCandidate
import com.ankiminer.android.anki.protocol.KnownVocabularyCursor
import com.ankiminer.android.anki.protocol.ReleaseState
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

internal data class MediaAcknowledgement(
    val assetId: String,
    val actualFilename: String,
    val durableClaimId: Long,
)

internal data class DuplicateBaseline(
    val token: String,
    val target: TargetSnapshot,
    val firstFieldName: String,
    val scopeDeckId: Long?,
    val candidates: List<DuplicateCandidate>,
    val occurrences: List<Int>,
    val providerNoteIds: List<Set<Long>>,
)

internal data class KnownTraversalInitialization(
    internal val runId: String,
    internal val ownerId: Long,
    internal val generation: Long,
    val scope: List<String>,
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

internal class AnkiRunStateRegistry(
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
                state.terminalReceiptFailure = true
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

    fun installTarget(
        owner: RunOwner,
        target: TargetSnapshot,
    ) {
        synchronized(lock) {
            val state = requireOwnerLocked(owner)
            val prior = state.target
            if (prior != null && prior != target) throw RunStateConflictException()
            state.target = target
        }
    }

    fun beginKnownTraversal(
        owner: RunOwner,
        scope: List<String>,
    ): KnownTraversalInitialization =
        synchronized(lock) {
            val state = requireOwnerLocked(owner)
            if (state.knownTraversal != null || state.knownInitialization != null) {
                throw RunStateConflictException()
            }
            val generation = nextGeneration++
            state.knownInitialization = generation
            KnownTraversalInitialization(state.runId, owner.ownerId, generation, scope.toList())
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
        scope: List<String>,
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
        synchronized(lock) {
            val state = requireOwnerLocked(owner)
            requireBaselineProbe(state, owner, probe)
            if (state.target != baseline.target) throw RunStateConflictException()
            state.baselineProbe = null
            state.duplicateBaseline = baseline
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

    fun recordMediaAcknowledgement(
        owner: RunOwner,
        acknowledgement: MediaAcknowledgement,
    ) {
        synchronized(lock) {
            val state = requireOwnerLocked(owner)
            val prior = state.mediaAcknowledgements[acknowledgement.assetId]
            if (prior != null && prior != acknowledgement) throw RunStateConflictException()
            if (
                prior == null &&
                    state.mediaAcknowledgements.size >= AnkiLimitsV1.CreateCall.MAX_MEDIA_REFERENCE_COUNT
            ) {
                throw RunStateCapacityException()
            }
            if (
                state.mediaAcknowledgements.values.any { existing ->
                    existing.assetId != acknowledgement.assetId &&
                        existing.actualFilename == acknowledgement.actualFilename
                }
            ) {
                throw RunStateConflictException()
            }
            state.mediaAcknowledgements[acknowledgement.assetId] = acknowledgement
        }
    }

    fun mediaAcknowledgement(
        owner: RunOwner,
        assetId: String,
    ): MediaAcknowledgement? =
        synchronized(lock) { requireOwnerLocked(owner).mediaAcknowledgements[assetId] }

    /** Called only after durable RESULT_READY terminalization and canonical response encoding. */
    fun retainDurableTerminalResponse(
        owner: RunOwner,
        requestId: String,
    ) {
        synchronized(lock) {
            val state = requireOwnerLocked(owner)
            if (!AnkiValidators.isValidRequestId(requestId)) {
                state.terminalReceiptFailure = true
                throw RunStateConflictException()
            }
            if (state.releaseRequested) {
                state.terminalReceiptFailure = true
                throw RunStateConflictException()
            }
            if (!state.durableResponseIds.add(requestId)) {
                state.terminalReceiptFailure = true
                throw RunStateConflictException()
            }
            if (state.durableResponseIds.size > MAX_TERMINAL_RESPONSE_RECEIPTS) {
                state.durableResponseIds.remove(requestId)
                state.terminalReceiptFailure = true
                throw RunStateCapacityException()
            }
        }
    }

    fun markTerminalResponseFailure(owner: RunOwner) {
        synchronized(lock) { requireOwnerLocked(owner).terminalReceiptFailure = true }
    }

    private fun admit(runId: String): RunOwner =
        synchronized(lock) {
            val state = states[runId] ?: throw RunNotRegisteredException()
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
        if (state.cleanupStarted) return null
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
        val scope: List<String>,
        val noteIds: List<Long>,
    ) {
        val generation = nextGeneration++
        var nextIndex = 0
        var expectedCursor: KnownVocabularyCursor? = null
        var pageInFlight = false
    }

    private class RunState(
        val runId: String,
        val cancellation: AnkiCancellation,
    ) {
        val owners = HashSet<Long>()
        var target: TargetSnapshot? = null
        var knownInitialization: Long? = null
        var knownTraversal: KnownTraversal? = null
        var duplicateBaseline: DuplicateBaseline? = null
        var baselineProbe: Long? = null
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
    }
}

internal class RunNotRegisteredException : RuntimeException()

internal class RunReleasingException : RuntimeException()

internal class RunCancelledException : RuntimeException()

internal class InvalidCapabilityException : RuntimeException()

internal class RunStateConflictException : RuntimeException()

internal class RunStateCapacityException : RuntimeException()
