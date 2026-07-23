package com.ankiminer.android.vm

import com.ankiminer.android.mining.MiningRunState

/** Minimal workflow phase consumed by app navigation; excludes progress and candidate payloads. */
internal enum class NavigationWorkflowState {
    IDLE,
    RUNNING,
    REVIEW,
}

internal fun MiningRunState.toNavigationWorkflowState(): NavigationWorkflowState =
    when (this) {
        is MiningRunState.Curating -> NavigationWorkflowState.REVIEW
        is MiningRunState.Starting,
        is MiningRunState.Running,
        -> NavigationWorkflowState.RUNNING
        MiningRunState.Idle,
        is MiningRunState.Success,
        is MiningRunState.Cancelled,
        is MiningRunState.Failed,
        -> NavigationWorkflowState.IDLE
    }
