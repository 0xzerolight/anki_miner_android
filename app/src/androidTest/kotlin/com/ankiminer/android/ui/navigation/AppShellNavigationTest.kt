package com.ankiminer.android.ui.navigation

import androidx.compose.foundation.focusable
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
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.ankiminer.android.R
import com.ankiminer.android.data.anki.AnkiSetupFailureOrigin
import com.ankiminer.android.ui.mining.RuntimeConflictNotice
import com.ankiminer.android.ui.settings.SettingsCategory
import com.ankiminer.android.ui.settings.settingsCardIndexFor
import com.ankiminer.android.ui.settings.settingsCategoryFor
import com.ankiminer.android.ui.theme.AnkiMinerTheme
import com.ankiminer.android.ui.theme.ThemePalettes
import com.ankiminer.android.vm.MiningReadinessAction
import com.ankiminer.android.vm.NavigationWorkflowState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AppShellNavigationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun tabDestinationsHideTheTopBarWhileSubScreensKeepIt() {
        val destination = mutableStateOf(AnkiMinerDestination.VIDEO)

        composeRule.setContent {
            AnkiMinerTheme(palette = ThemePalettes.Light) {
                AnkiMinerAppShell(
                    currentDestination = destination.value,
                    snackbarHostState = remember { SnackbarHostState() },
                    onDestinationSelected = {},
                    onNavigateBack = {},
                    modifier = Modifier.width(320.dp).height(640.dp),
                ) { shellModifier ->
                    Text(destination.value.name, modifier = shellModifier)
                }
            }
        }

        composeRule.onNodeWithTag(APP_TOP_BAR_TEST_TAG).assertDoesNotExist()

        composeRule.runOnIdle { destination.value = AnkiMinerDestination.SETTINGS }
        composeRule.onNodeWithTag(APP_TOP_BAR_TEST_TAG).assertDoesNotExist()

        composeRule.runOnIdle { destination.value = AnkiMinerDestination.KNOWN_WORDS_MANAGER }
        composeRule.onNodeWithTag(APP_TOP_BAR_TEST_TAG).assertIsDisplayed()
    }

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
                AnkiMinerTheme(palette = ThemePalettes.Light) {
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

    /**
     * Covers both routes an overlay has to close, because they fail independently: semantics
     * removal is what stops TalkBack, and focus-search refusal is what stops a hardware keyboard.
     * The semantics leg cannot stand in for the focus leg — a cleared subtree is still focusable,
     * and a focusable one is reachable by D-pad and Tab whether or not it has semantics.
     */
    @Test
    fun overlayLeavesShellUnreachableToBothAccessibilityAndFocusSearch() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settingsLabel = context.getString(R.string.nav_settings)
        val overlayVisible = mutableStateOf(false)
        val overlayFocus = FocusRequester()
        var shellFocused = false
        lateinit var focusManager: FocusManager

        composeRule.setContent {
            focusManager = LocalFocusManager.current
            AnkiMinerTheme(palette = ThemePalettes.Light) {
                AnkiMinerAppShell(
                    currentDestination = AnkiMinerDestination.VIDEO,
                    snackbarHostState = remember { SnackbarHostState() },
                    onDestinationSelected = {},
                    modifier = Modifier.width(400.dp).height(800.dp),
                    overlay =
                        if (!overlayVisible.value) {
                            null
                        } else {
                            {
                                Text(
                                    text = OVERLAY_TEXT,
                                    modifier = Modifier.focusRequester(overlayFocus).focusable(),
                                )
                            }
                        },
                ) { shellModifier ->
                    Text(
                        text = SHELL_CONTENT_TEXT,
                        modifier =
                            shellModifier
                                .onFocusChanged { if (it.isFocused) shellFocused = true }
                                .focusable(),
                    )
                }
            }
        }

        composeRule.onNodeWithText(SHELL_CONTENT_TEXT).assertIsDisplayed()
        composeRule.onNodeWithText(settingsLabel).assertIsDisplayed()
        // Without an overlay the same content is focus-reachable, so the assertion below that it
        // stops being reachable is about the overlay and not about an already-unfocusable subtree.
        // Stepped rather than a single move: the NavigationBar items are focusable too and the
        // traversal order between them and the body is Scaffold's business, not this test's.
        repeat(FOCUS_TRAVERSAL_STEPS) {
            composeRule.runOnIdle {
                if (!shellFocused) focusManager.moveFocus(FocusDirection.Next)
            }
        }
        composeRule.runOnIdle { assertTrue("shell was never focus-reachable", shellFocused) }

        composeRule.runOnIdle { overlayVisible.value = true }
        // The wizard does this with its own FocusRequester: a direct grant, not a focus search,
        // so it is unaffected by the entry refusal that then keeps search out.
        composeRule.runOnIdle { overlayFocus.requestFocus() }

        composeRule.onNodeWithText(OVERLAY_TEXT).assertIsDisplayed()
        // Not merely covered: gone from the semantics tree, so TalkBack can neither traverse to
        // the navigation items nor fire an accessibility action on one.
        composeRule.onNodeWithText(SHELL_CONTENT_TEXT).assertDoesNotExist()
        composeRule.onNodeWithText(settingsLabel).assertDoesNotExist()

        // Armed only now, so the focus the shell held while the overlay was appearing cannot be
        // mistaken for focus search getting back in.
        composeRule.runOnIdle { shellFocused = false }
        listOf(
            FocusDirection.Next,
            FocusDirection.Previous,
            FocusDirection.Up,
            FocusDirection.Down,
        ).forEach { direction ->
            repeat(FOCUS_TRAVERSAL_STEPS) {
                composeRule.runOnIdle { focusManager.moveFocus(direction) }
            }
        }
        composeRule.runOnIdle {
            assertFalse("focus search reached the shell behind the overlay", shellFocused)
        }
    }

    @Test
    fun readinessRemediationsTargetTheirAnkiSettingsCards() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var openedOrigin: AnkiSetupFailureOrigin? = null

        composeRule.setContent {
            AnkiMinerTheme(palette = ThemePalettes.Light) {
                MiningReadinessActions(
                    action = MiningReadinessAction.CHOOSE_NOTE_TYPE,
                    onRequestPermissions = {},
                    onInstallUniDic = {},
                    onInstallAnkiDroid = {},
                    onOpenAnkiDroid = {},
                    onCheckAgain = {},
                    onOpenSettings = { openedOrigin = it },
                    onImportDictionary = {},
                )
            }
        }

        composeRule
            .onNodeWithText(context.getString(R.string.readiness_choose_note_type))
            .performClick()
        composeRule.runOnIdle {
            assertTrue(openedOrigin == AnkiSetupFailureOrigin.TARGET)
            assertTrue(settingsCategoryFor(requireNotNull(openedOrigin)) == SettingsCategory.ANKI)
            assertTrue(settingsCardIndexFor(requireNotNull(openedOrigin)) == 3)
        }
    }

    private companion object {
        /** Enough Tab presses to cover the body plus all four navigation destinations. */
        const val FOCUS_TRAVERSAL_STEPS = 8
        const val OVERLAY_TEXT = "overlay owns the window"
        const val SHELL_CONTENT_TEXT = "shell content behind the overlay"
    }
}
