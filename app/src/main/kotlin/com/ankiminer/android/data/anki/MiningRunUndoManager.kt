package com.ankiminer.android.data.anki

import com.ankiminer.android.anki.provider.AnkiCancellation
import com.ankiminer.android.data.RuntimeWorkCoordinator
import com.ankiminer.android.diagnostics.log.AppLog
import com.ankiminer.android.diagnostics.log.LogComponent
import java.util.concurrent.Executor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The worker-only note delete behind the undo, kept as a seam so the manager is JVM-testable. */
internal fun interface MiningRunUndoBackend {
    fun deleteNotes(
        noteIds: List<Long>,
        cancellation: AnkiCancellation,
    ): Int
}

/** Takes the run's mined forms back out of the known-words list. */
internal fun interface MinedWordsReverter {
    suspend fun removeMinedWords(words: List<String>): Boolean
}

internal data class UndoneRunReceipt(
    val runId: String,
    val deletedNotes: Int,
    val knownWordsReverted: Boolean,
)

internal sealed interface UndoRunOutcome {
    data class Undone(val receipt: UndoneRunReceipt) : UndoRunOutcome

    data object Busy : UndoRunOutcome

    data object DeleteFailed : UndoRunOutcome
}

internal interface MiningRunUndoManager {
    val undoneRuns: StateFlow<Map<String, UndoneRunReceipt>>

    val undoActive: StateFlow<Boolean>

    suspend fun undoRun(
        runId: String,
        noteIds: List<Long>,
        minedForms: List<String>,
    ): UndoRunOutcome
}

/**
 * Undoes one finished mining run: the run's notes are deleted from AnkiDroid, then the words it
 * mined are taken back out of the known-words list.
 *
 * Process-owned, so an Activity recreation cannot start a second undo of the same run, and the
 * delete itself runs on [executor] behind a [CompletableDeferred]: a caller coroutine cancelled
 * mid-delete abandons only its own await, never a half-finished delete that nothing recorded.
 *
 * The two phases hold different process leases and must not overlap. The delete holds
 * [RuntimeWorkCoordinator.Kind.ANKI_SETUP] and releases it in the executor's `finally`; the revert
 * acquires [RuntimeWorkCoordinator.Kind.RESOURCE] for itself inside the resource manager, and the
 * coordinator admits one lease at a time, so holding the first across the second would make the
 * revert fail every time.
 */
internal class ProcessMiningRunUndoManager(
    private val backend: MiningRunUndoBackend,
    private val executor: Executor,
    private val runtimeWorkCoordinator: RuntimeWorkCoordinator,
    private val reverter: MinedWordsReverter,
) : MiningRunUndoManager {
    private val mutableUndoneRuns = MutableStateFlow<Map<String, UndoneRunReceipt>>(emptyMap())
    override val undoneRuns: StateFlow<Map<String, UndoneRunReceipt>> = mutableUndoneRuns.asStateFlow()

    private val mutableUndoActive = MutableStateFlow(false)
    override val undoActive: StateFlow<Boolean> = mutableUndoActive.asStateFlow()

    private val monitor = Any()
    private var active = false

    override suspend fun undoRun(
        runId: String,
        noteIds: List<Long>,
        minedForms: List<String>,
    ): UndoRunOutcome {
        mutableUndoneRuns.value[runId]?.let { recorded ->
            AppLog.i(
                LogComponent.ANKI,
                UNDO_OP,
                "outcome" to "skip",
                "reason" to "already_undone",
                "runId" to runId,
            )
            return UndoRunOutcome.Undone(recorded)
        }
        val claimed =
            synchronized(monitor) {
                if (active) {
                    false
                } else {
                    active = true
                    true
                }
            }
        if (!claimed) {
            AppLog.i(
                LogComponent.ANKI,
                UNDO_OP,
                "outcome" to "skip",
                "reason" to "undo_in_flight",
                "runId" to runId,
            )
            return UndoRunOutcome.Busy
        }
        mutableUndoActive.value = true
        try {
            val deleted =
                when (val phase = deleteNotes(runId, noteIds)) {
                    is DeletePhase.Deleted -> phase.deletedNotes
                    DeletePhase.LeaseBusy -> return UndoRunOutcome.Busy
                    is DeletePhase.Failed -> {
                        AppLog.w(
                            LogComponent.ANKI,
                            UNDO_OP,
                            phase.failure,
                            "outcome" to "fail",
                            "reason" to "delete_failed",
                            "runId" to runId,
                            "notes" to noteIds.size,
                        )
                        return UndoRunOutcome.DeleteFailed
                    }
                }
            val reverted = reverter.removeMinedWords(minedForms)
            val receipt = UndoneRunReceipt(runId, deletedNotes = deleted, knownWordsReverted = reverted)
            recordReceipt(receipt)
            AppLog.i(
                LogComponent.ANKI,
                UNDO_OP,
                "outcome" to "ok",
                "runId" to runId,
                "notes" to noteIds.size,
                "deleted" to deleted,
                "words" to minedForms.size,
                "reverted" to reverted,
            )
            return UndoRunOutcome.Undone(receipt)
        } finally {
            // A cancelled caller drops the single-flight claim while its delete may still be on the
            // executor. That is safe: the executor holds the setup lease until the delete finishes,
            // so the next undo is refused by the coordinator rather than overlapping this one.
            mutableUndoActive.value = false
            synchronized(monitor) { active = false }
        }
    }

    /**
     * Runs the delete on [executor] and awaits its verdict. The receipt is written by the executor
     * itself, before the caller is told anything, so a cancelled caller still leaves the run marked
     * undone rather than deleting notes nobody accounted for. The lease is released in the same
     * `finally`, which is what lets the revert phase take its own lease afterwards.
     */
    private suspend fun deleteNotes(
        runId: String,
        noteIds: List<Long>,
    ): DeletePhase {
        val lease = runtimeWorkCoordinator.tryAcquire(RuntimeWorkCoordinator.Kind.ANKI_SETUP)
        if (lease == null) {
            AppLog.i(
                LogComponent.ANKI,
                UNDO_OP,
                "outcome" to "skip",
                "reason" to "runtime_busy",
                "runId" to runId,
            )
            return DeletePhase.LeaseBusy
        }
        val completion = CompletableDeferred<DeletePhase>()
        try {
            executor.execute {
                var phase: DeletePhase = DeletePhase.Failed(IllegalStateException("undo delete did not complete"))
                try {
                    val deleted = backend.deleteNotes(noteIds, AnkiCancellation.NONE)
                    recordReceipt(
                        UndoneRunReceipt(runId, deletedNotes = deleted, knownWordsReverted = false),
                    )
                    phase = DeletePhase.Deleted(deleted)
                } catch (failure: RuntimeException) {
                    phase = DeletePhase.Failed(failure)
                } finally {
                    // Nested so a throwing lease.close() (stale-lease check, RuntimeWorkCoordinator.kt)
                    // still lets the deferred complete instead of leaving the caller awaiting forever.
                    try {
                        lease.close()
                    } finally {
                        completion.complete(phase)
                    }
                }
            }
        } catch (failure: RuntimeException) {
            lease.close()
            completion.complete(DeletePhase.Failed(failure))
        }
        return completion.await()
    }

    /** Insertion-ordered so the cap drops the oldest run; re-recording a run keeps its position. */
    private fun recordReceipt(receipt: UndoneRunReceipt) {
        mutableUndoneRuns.update { current ->
            val next = LinkedHashMap(current)
            next[receipt.runId] = receipt
            while (next.size > RECEIPT_CAP) {
                next.remove(next.keys.first())
            }
            next
        }
    }

    private sealed interface DeletePhase {
        data class Deleted(val deletedNotes: Int) : DeletePhase

        data object LeaseBusy : DeletePhase

        data class Failed(val failure: RuntimeException) : DeletePhase
    }

    private companion object {
        const val UNDO_OP = "undo.run"

        /** Enough receipts for the runs a session can still have on screen, not a history. */
        const val RECEIPT_CAP = 8
    }
}
