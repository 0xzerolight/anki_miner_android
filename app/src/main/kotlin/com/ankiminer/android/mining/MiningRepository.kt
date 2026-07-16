package com.ankiminer.android.mining

import kotlinx.coroutines.flow.StateFlow

interface MiningRepository {
    val state: StateFlow<MiningRunState>

    /**
     * True only when an active job coordinator outlives its ViewModel and accepts ownership of the
     * selected SAF grants. The coordinator must eventually release those exact ownership counts.
     */
    val ownsActiveSourcesAfterViewModelCleared: Boolean
        get() = false

    /** Accept one video job and return after it reaches a stable observable state. */
    suspend fun startVideo(input: VideoMiningInput)

    /**
     * Confirm curation. An empty list is a successful zero-selection response and must remain
     * distinct from [cancel], which maps to Python's null curation response.
     */
    suspend fun confirmCuration(
        runId: String,
        requestId: String,
        selection: List<CurationSelection>,
    )

    /** Cancel the whole run, including a worker parked for curation. */
    suspend fun cancel(runId: String)

    /** Clear a terminal state before starting another run. */
    suspend fun reset()
}

class MiningCommandException(message: String) : IllegalStateException(message)
