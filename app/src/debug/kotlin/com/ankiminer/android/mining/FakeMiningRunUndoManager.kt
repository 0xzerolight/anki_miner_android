package com.ankiminer.android.mining

import com.ankiminer.android.data.anki.MiningRunUndoManager
import com.ankiminer.android.data.anki.UndoRunOutcome
import com.ankiminer.android.data.anki.UndoneRunReceipt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Deterministic debug/test double for [MiningRunUndoManager]: records every [undoRun] call and
 * plays back a scripted [outcomeForRun] instead of touching AnkiDroid, so UiAudit and other debug
 * flows can exercise the undo affordance without a real provider.
 */
internal class FakeMiningRunUndoManager(
    initialUndoneRuns: Map<String, UndoneRunReceipt> = emptyMap(),
    private val outcomeForRun: (String) -> UndoRunOutcome = { runId ->
        UndoRunOutcome.Undone(UndoneRunReceipt(runId, deletedNotes = 0, knownWordsReverted = true))
    },
) : MiningRunUndoManager {
    internal data class UndoCall(
        val runId: String,
        val noteIds: List<Long>,
        val minedForms: List<String>,
    )

    private val mutableUndoneRuns = MutableStateFlow(initialUndoneRuns)
    override val undoneRuns: StateFlow<Map<String, UndoneRunReceipt>> = mutableUndoneRuns.asStateFlow()

    private val mutableUndoActive = MutableStateFlow(false)
    override val undoActive: StateFlow<Boolean> = mutableUndoActive.asStateFlow()

    private val mutableCalls = mutableListOf<UndoCall>()
    internal val calls: List<UndoCall> get() = mutableCalls

    override suspend fun undoRun(
        runId: String,
        noteIds: List<Long>,
        minedForms: List<String>,
    ): UndoRunOutcome {
        mutableCalls += UndoCall(runId, noteIds, minedForms)
        mutableUndoActive.value = true
        val outcome = outcomeForRun(runId)
        if (outcome is UndoRunOutcome.Undone) {
            mutableUndoneRuns.value = mutableUndoneRuns.value + (outcome.receipt.runId to outcome.receipt)
        }
        mutableUndoActive.value = false
        return outcome
    }
}
