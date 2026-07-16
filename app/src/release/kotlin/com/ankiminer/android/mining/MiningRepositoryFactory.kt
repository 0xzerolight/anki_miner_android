package com.ankiminer.android.mining

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal object MiningRepositoryFactory {
    fun create(): MiningRepository = UnavailableMiningRepository()
}

/** Release fails closed until the production bridge-backed repository lands. */
private class UnavailableMiningRepository : MiningRepository {
    private val mutableState = MutableStateFlow<MiningRunState>(MiningRunState.Idle)
    override val state: StateFlow<MiningRunState> = mutableState.asStateFlow()

    override suspend fun startVideo(input: VideoMiningInput) {
        if (mutableState.value != MiningRunState.Idle) {
            throw MiningCommandException("A mining run is already active")
        }
        mutableState.value = MiningRunState.Starting(runId = null, progress = null)
        mutableState.value =
            MiningRunState.Failed(
                runId = null,
                failure =
                    MiningFailure(
                        message = "The production mining backend is not available yet",
                        retryable = false,
                    ),
                result = null,
            )
    }

    override suspend fun confirmCuration(
        runId: String,
        requestId: String,
        selection: List<CurationSelection>,
    ) {
        throw MiningCommandException("The production mining backend is unavailable")
    }

    override suspend fun cancel(runId: String) {
        throw MiningCommandException("The production mining backend is unavailable")
    }

    override suspend fun reset() {
        if (!mutableState.value.isTerminal) {
            throw MiningCommandException("Only a terminal mining run can be reset")
        }
        mutableState.value = MiningRunState.Idle
    }
}
