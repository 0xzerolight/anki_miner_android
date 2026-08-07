package com.ankiminer.android.ui.navigation

import com.ankiminer.android.mining.MiningRunState
import com.ankiminer.android.ui.mining.TimingPreviewState
import androidx.compose.ui.unit.dp
import com.ankiminer.android.vm.NavigationWorkflowState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnkiMinerNavigationTest {
    @Test
    fun bottomBarDestinationsFollowMediaReadingSettingsOrder() {
        assertEquals(
            listOf(
                AnkiMinerDestination.VIDEO,
                AnkiMinerDestination.AUDIO,
                AnkiMinerDestination.READING,
                AnkiMinerDestination.SETTINGS,
            ),
            AnkiMinerDestination.entries.filter { it.showsBottomBar },
        )
    }

    @Test
    fun bottomBarDestinationsHaveIconsAndContentDescriptions() {
        AnkiMinerDestination.entries.filter { it.showsBottomBar }.forEach { destination ->
            assertTrue(destination.name, destination.icon != null && destination.icon != 0)
            assertTrue(
                destination.name,
                destination.contentDescription != null && destination.contentDescription != 0,
            )
        }
    }

    @Test
    fun destinationRoutesAreUnique() {
        val routes = AnkiMinerDestination.entries.map { it.route }

        assertEquals(routes.size, routes.toSet().size)
    }

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
    fun compactBottomBarHeightAppliesOnlyBelowLargeFontScale() {
        assertEquals(64.dp, compactBottomBarHeight(fontScale = 1.0f))
        assertEquals(64.dp, compactBottomBarHeight(fontScale = 1.29f))
        assertNull(compactBottomBarHeight(fontScale = 1.3f))
        assertNull(compactBottomBarHeight(fontScale = 2.0f))
    }

    @Test
    fun activeWorkflowDestinationPrefersReviewThenLaneOrder() {
        assertEquals(
            AnkiMinerDestination.VIDEO,
            activeWorkflowDestination(
                video = NavigationWorkflowState.REVIEW,
                audio = NavigationWorkflowState.RUNNING,
                reading = NavigationWorkflowState.RUNNING,
            ),
        )
        assertEquals(
            AnkiMinerDestination.AUDIO,
            activeWorkflowDestination(
                video = NavigationWorkflowState.RUNNING,
                audio = NavigationWorkflowState.REVIEW,
                reading = NavigationWorkflowState.IDLE,
            ),
        )
        assertEquals(
            AnkiMinerDestination.READING,
            activeWorkflowDestination(
                video = NavigationWorkflowState.IDLE,
                audio = NavigationWorkflowState.RUNNING,
                reading = NavigationWorkflowState.REVIEW,
            ),
        )
        assertEquals(
            AnkiMinerDestination.VIDEO,
            activeWorkflowDestination(
                video = NavigationWorkflowState.REVIEW,
                audio = NavigationWorkflowState.REVIEW,
                reading = NavigationWorkflowState.REVIEW,
            ),
        )
        assertEquals(
            AnkiMinerDestination.AUDIO,
            activeWorkflowDestination(
                video = NavigationWorkflowState.IDLE,
                audio = NavigationWorkflowState.REVIEW,
                reading = NavigationWorkflowState.REVIEW,
            ),
        )
        assertEquals(
            AnkiMinerDestination.VIDEO,
            activeWorkflowDestination(
                video = NavigationWorkflowState.RUNNING,
                audio = NavigationWorkflowState.RUNNING,
                reading = NavigationWorkflowState.RUNNING,
            ),
        )
        assertEquals(
            AnkiMinerDestination.AUDIO,
            activeWorkflowDestination(
                video = NavigationWorkflowState.IDLE,
                audio = NavigationWorkflowState.RUNNING,
                reading = NavigationWorkflowState.RUNNING,
            ),
        )
        assertEquals(
            null,
            activeWorkflowDestination(
                video = NavigationWorkflowState.IDLE,
                audio = NavigationWorkflowState.IDLE,
                reading = NavigationWorkflowState.IDLE,
            ),
        )
    }

    @Test
    fun timingPreviewOwnerPrefersVideoThenAudio() {
        val preview =
            TimingPreviewState(
                initialOffset = 0.0,
                workingOffset = 0.0,
                previewingUnshifted = false,
                cues = emptyList(),
                selectedCueIndex = null,
            )

        assertEquals(
            AnkiMinerDestination.VIDEO,
            activeTimingPreviewOwner(video = preview, audio = null),
        )
        assertEquals(
            AnkiMinerDestination.AUDIO,
            activeTimingPreviewOwner(video = null, audio = preview),
        )
        assertEquals(
            AnkiMinerDestination.VIDEO,
            activeTimingPreviewOwner(video = preview, audio = preview),
        )
        assertEquals(null, activeTimingPreviewOwner(video = null, audio = null))
    }

    @Test
    fun audioForegroundRunRoutesToAudioDestination() {
        assertEquals(
            AnkiMinerDestination.AUDIO,
            notificationRunDestination(
                notificationRunId = "audio-cancel_0123456789abcdef0123456789abcdef",
                video = MiningRunState.Idle,
                audio = MiningRunState.Starting(runId = null, progress = null),
                reading = MiningRunState.Idle,
            ),
        )
    }
}
