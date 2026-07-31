package com.ankiminer.android.ui.navigation

import com.ankiminer.android.vm.NavigationWorkflowState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnkiMinerNavigationTest {
    @Test
    fun activeWorkflowRemainsVisibleWhenExternalReadinessRefreshes() {
        assertFalse(
            miningWorkflowVisible(
                setupReady = false,
                workflow = NavigationWorkflowState.IDLE,
            ),
        )
        assertTrue(
            miningWorkflowVisible(
                setupReady = true,
                workflow = NavigationWorkflowState.IDLE,
            ),
        )
        assertTrue(
            miningWorkflowVisible(
                setupReady = false,
                workflow = NavigationWorkflowState.RUNNING,
            ),
        )
        assertTrue(
            miningWorkflowVisible(
                setupReady = false,
                workflow = NavigationWorkflowState.IDLE,
                hasRetainedRun = true,
            ),
        )
    }

    @Test
    fun compactNavigationRequiresBothNarrowWidthAndLargeText() {
        assertFalse(compactNavigation(widthDp = 320, fontScale = 1.0f))
        assertFalse(compactNavigation(widthDp = 400, fontScale = 2.0f))
        assertTrue(compactNavigation(widthDp = 320, fontScale = 1.3f))
        assertTrue(compactNavigation(widthDp = 320, fontScale = 2.0f))
    }

    @Test
    fun activeWorkflowDestinationPrefersReviewThenRunning() {
        assertEquals(
            AnkiMinerDestination.VIDEO,
            activeWorkflowDestination(
                video = NavigationWorkflowState.REVIEW,
                reading = NavigationWorkflowState.RUNNING,
            ),
        )
        assertEquals(
            AnkiMinerDestination.READING,
            activeWorkflowDestination(
                video = NavigationWorkflowState.IDLE,
                reading = NavigationWorkflowState.RUNNING,
            ),
        )
        assertEquals(
            null,
            activeWorkflowDestination(
                video = NavigationWorkflowState.IDLE,
                reading = NavigationWorkflowState.IDLE,
            ),
        )
    }
}
