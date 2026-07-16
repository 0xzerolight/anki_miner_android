package com.ankiminer.android.mining

import kotlinx.coroutines.flow.StateFlow

interface MiningRepository {
    val state: StateFlow<MiningRunState>

    /**
     * Atomically transfer the two selection-owned SAF references for [input] to a matching live
     * coordinator run during ViewModel teardown.
     *
     * The video and subtitle entries are counts, not a URI set: both slots may refer to the same
     * document. Returning true makes the repository responsible for releasing both references
     * after its worker and cleanup finish. Returning false leaves both references with the caller.
     */
    fun detachActiveSources(input: VideoMiningInput): Boolean = false

    /**
     * Accept one video job and return after it reaches a stable observable state.
     *
     * A long-lived implementation must install the matching active ownership record before this
     * method first suspends. That makes teardown linearizable: [detachActiveSources] either sees
     * the accepted run or permanently wins before the run can take ownership.
     */
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

    /** Cancel an accepted run before Python has assigned its run ID. */
    suspend fun cancel(token: MiningCancellationToken) {
        throw MiningCommandException("The mining run cannot be cancelled before registration")
    }

    /** Clear a terminal state before starting another run. */
    suspend fun reset()
}

class MiningCommandException(message: String) : IllegalStateException(message)
