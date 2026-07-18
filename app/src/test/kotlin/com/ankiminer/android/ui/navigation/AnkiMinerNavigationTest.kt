package com.ankiminer.android.ui.navigation

import com.ankiminer.android.mining.MiningProgress
import com.ankiminer.android.mining.MiningRunState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnkiMinerNavigationTest {
    @Test
    fun activeWorkflowRemainsVisibleWhenExternalReadinessRefreshes() {
        assertFalse(
            miningWorkflowVisible(setupReady = false, runState = MiningRunState.Idle),
        )
        assertTrue(
            miningWorkflowVisible(setupReady = true, runState = MiningRunState.Idle),
        )
        assertTrue(
            miningWorkflowVisible(
                setupReady = false,
                MiningRunState.Running("run", MiningProgress(0, 1, "Working")),
            ),
        )
    }
}
