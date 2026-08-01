package com.ankiminer.android.ui.navigation

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.ankiminer.android.R
import com.ankiminer.android.ui.mining.RuntimeConflictNotice
import com.ankiminer.android.ui.theme.AnkiMinerTheme
import com.ankiminer.android.vm.NavigationWorkflowState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AppShellNavigationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun fs200CompactNavigationSwitchesTabsAndReturnsToActiveCuration() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val videoDescription = context.getString(R.string.nav_video_description)
        val readingDescription = context.getString(R.string.nav_reading_description)
        val settingsDescription = context.getString(R.string.nav_settings_description)
        val returnLabel = context.getString(R.string.return_to_active_run)

        composeRule.setContent {
            val baseDensity = LocalDensity.current.density
            var destination by remember { mutableStateOf(AnkiMinerDestination.READING) }
            CompositionLocalProvider(LocalDensity provides Density(baseDensity, 2f)) {
                AnkiMinerTheme(darkTheme = false) {
                    AnkiMinerAppShell(
                        currentDestination = destination,
                        videoWorkflow = NavigationWorkflowState.REVIEW,
                        readingWorkflow = NavigationWorkflowState.IDLE,
                        snackbarHostState = remember { SnackbarHostState() },
                        onDestinationSelected = { destination = it },
                        onNavigateBack = {},
                        modifier = Modifier.width(320.dp).height(640.dp),
                    ) { shellModifier ->
                        when (destination) {
                            AnkiMinerDestination.VIDEO ->
                                Text(
                                    text = stringResource(R.string.curation_title),
                                    modifier = shellModifier,
                                )
                            AnkiMinerDestination.READING ->
                                RuntimeConflictNotice(
                                    text = stringResource(R.string.runtime_work_mining_active),
                                    onReturnToActiveRun = {
                                        destination = AnkiMinerDestination.VIDEO
                                    },
                                    modifier = shellModifier,
                                )
                            else -> Text(destination.name, modifier = shellModifier)
                        }
                    }
                }
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.nav_video)).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(settingsDescription).performClick()
        composeRule.onNodeWithText(AnkiMinerDestination.SETTINGS.name).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(readingDescription).performClick()
        composeRule.onNodeWithText(returnLabel).performClick()
        composeRule.onNodeWithText(context.getString(R.string.curation_title)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(videoDescription).assertIsDisplayed()
    }

    @Test
    fun overlayLeavesNoShellDestinationReachableBehindIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settingsLabel = context.getString(R.string.nav_settings)
        val overlayVisible = mutableStateOf(false)
        var selections = 0

        composeRule.setContent {
            AnkiMinerTheme(darkTheme = false) {
                AnkiMinerAppShell(
                    currentDestination = AnkiMinerDestination.VIDEO,
                    snackbarHostState = remember { SnackbarHostState() },
                    onDestinationSelected = { selections += 1 },
                    modifier = Modifier.width(400.dp).height(800.dp),
                    overlay =
                        if (!overlayVisible.value) {
                            null
                        } else {
                            { Text(OVERLAY_TEXT) }
                        },
                ) { shellModifier ->
                    Text(SHELL_CONTENT_TEXT, modifier = shellModifier)
                }
            }
        }

        composeRule.onNodeWithText(SHELL_CONTENT_TEXT).assertIsDisplayed()
        composeRule.onNodeWithText(settingsLabel).assertIsDisplayed()

        composeRule.runOnIdle { overlayVisible.value = true }

        composeRule.onNodeWithText(OVERLAY_TEXT).assertIsDisplayed()
        // Not merely covered: gone from the semantics tree, so TalkBack can neither traverse to
        // the navigation items nor fire an accessibility action on one.
        composeRule.onNodeWithText(SHELL_CONTENT_TEXT).assertDoesNotExist()
        composeRule.onNodeWithText(settingsLabel).assertDoesNotExist()
        assertEquals(0, selections)
    }

    private companion object {
        const val OVERLAY_TEXT = "overlay owns the window"
        const val SHELL_CONTENT_TEXT = "shell content behind the overlay"
    }
}
