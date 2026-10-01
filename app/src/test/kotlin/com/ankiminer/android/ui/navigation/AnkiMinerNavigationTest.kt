package com.ankiminer.android.ui.navigation

import com.ankiminer.android.anki.provider.AnkiProviderReadiness
import com.ankiminer.android.data.anki.AnkiSetupFailure
import com.ankiminer.android.data.anki.AnkiSetupFailureOrigin
import com.ankiminer.android.data.resources.ResourceFailureOrigin
import com.ankiminer.android.mining.MiningRunState
import com.ankiminer.android.ui.settings.SettingsCategory
import com.ankiminer.android.vm.SetupUiState
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
    fun compactNavigationFollowsLargeTextAtAnyWidth() {
        assertFalse(compactNavigation(fontScale = 1.0f))
        assertFalse(compactNavigation(fontScale = 1.29f))
        assertTrue(compactNavigation(fontScale = 1.3f))
        assertTrue(compactNavigation(fontScale = 2.0f))
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

    @Test
    fun linkedFailureIsSuppressedOnlyWhereTheScreenAlreadyShowsIt() {
        val settings = AnkiMinerDestination.SETTINGS
        val video = AnkiMinerDestination.VIDEO
        assertTrue(linkedFailureShownInPlace(settings, SettingsCategory.RESOURCES, ResourceFailureOrigin.PITCH, null, false))
        assertFalse(linkedFailureShownInPlace(settings, SettingsCategory.ANKI, ResourceFailureOrigin.PITCH, null, false))
        // SETUP renders in the header of every tab.
        assertTrue(linkedFailureShownInPlace(settings, SettingsCategory.UI, ResourceFailureOrigin.SETUP, null, false))
        assertTrue(linkedFailureShownInPlace(video, null, null, AnkiSetupFailureOrigin.TARGET, miningNoticeVisible = true))
        assertFalse(linkedFailureShownInPlace(video, null, null, AnkiSetupFailureOrigin.TARGET, miningNoticeVisible = false))
        assertFalse(linkedFailureShownInPlace(video, null, ResourceFailureOrigin.PITCH, null, miningNoticeVisible = true))
    }

    @Test
    fun aMissingOrUnallowedAnkiDroidRaisesNoLinkedSnackbar() {
        val failure = AnkiSetupFailure("provider_unavailable", "AnkiDroid is not available", AnkiSetupFailureOrigin.TARGET)
        listOf(
            AnkiProviderReadiness.NotInstalled,
            AnkiProviderReadiness.Uninitialized,
            AnkiProviderReadiness.PermissionDenied,
        ).forEach { readiness ->
            // Every Video, Audio, Reading and Settings surface already says what to do; the raw
            // provider error would only repeat it.
            assertNull("$readiness", linkedAnkiFailure(SetupUiState(anki = readiness, ankiFailure = failure)))
        }
        assertEquals(
            failure,
            linkedAnkiFailure(
                SetupUiState(anki = AnkiProviderReadiness.Ready(apiSpecVersion = 7, versionCode = 1L), ankiFailure = failure),
            ),
        )
    }
}
