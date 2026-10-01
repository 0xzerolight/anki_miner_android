package com.ankiminer.android.ui.wizard

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.ankiminer.android.anki.provider.AnkiProviderReadiness
import com.ankiminer.android.data.anki.AnkiSetupFailure
import com.ankiminer.android.data.anki.AnkiSetupFailureOrigin
import com.ankiminer.android.data.resources.ResourceFailure
import com.ankiminer.android.data.resources.ResourceFailureAction
import com.ankiminer.android.data.resources.ResourceFailureOrigin
import com.ankiminer.android.data.resources.ResourceFailureRetry
import com.ankiminer.android.ui.theme.AnkiMinerTheme
import com.ankiminer.android.vm.SetupUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class OnboardingWizardBehaviorTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun dismissedSkipConfirmationDoesNotFinishButConfirmedSkipDoes() {
        var finished = 0
        var backDispatcher: OnBackPressedDispatcher? = null
        composeRule.setContent {
            AnkiMinerTheme {
                backDispatcher =
                    LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
                OnboardingWizardContent(
                    state = SetupUiState(wizardSeen = false),
                    step = WizardStep.WELCOME,
                    callbacks =
                        OnboardingWizardCallbacks(
                            onFinished = { finished += 1 },
                        ),
                )
            }
        }

        composeRule.onNodeWithText("Skip for now").performClick()
        composeRule.onNodeWithText("Skip setup?").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(1, finished) }

        composeRule.runOnIdle { finished = 0 }
        composeRule.runOnUiThread {
            (backDispatcher ?: error("no back dispatcher in composition")).onBackPressed()
        }
        composeRule.onNodeWithText("Skip setup?").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.onNodeWithText("Skip setup?").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(0, finished) }

        composeRule.runOnUiThread {
            (backDispatcher ?: error("no back dispatcher in composition")).onBackPressed()
        }
        composeRule.onNodeWithText("Skip setup").performClick()
        composeRule.runOnIdle { assertEquals(1, finished) }
    }

    @Test
    fun incompleteFinalPageNeverClaimsReadyToMine() {
        composeRule.setContent {
            AnkiMinerTheme {
                OnboardingWizardContent(
                    state = SetupUiState(),
                    step = WizardStep.DONE,
                    callbacks = OnboardingWizardCallbacks(),
                )
            }
        }

        composeRule.onNodeWithText("Setup incomplete").assertIsDisplayed()
        composeRule.onNodeWithText("Ready to mine").assertDoesNotExist()
    }

    @Test
    fun failuresRenderOnlyAtTheirOriginStepAndKeepTheirAction() {
        var state by mutableStateOf(SetupUiState())
        var step by mutableStateOf(WizardStep.WELCOME)
        var resourceRetries = 0
        var ankiRetries = 0
        composeRule.setContent {
            AnkiMinerTheme {
                OnboardingWizardContent(
                    state = state,
                    step = step,
                    callbacks =
                        OnboardingWizardCallbacks(
                            onInstallRequiredResources = { resourceRetries += 1 },
                            onRefresh = { ankiRetries += 1 },
                        ),
                )
            }
        }

        composeRule.runOnIdle {
            state =
                SetupUiState(
                    failure =
                        ResourceFailure(
                            code = "unidic",
                            message = "UniDic failed",
                            retryable = true,
                            origin = ResourceFailureOrigin.UNIDIC,
                            retry = ResourceFailureRetry(ResourceFailureAction.RETRY),
                        ),
                )
            step = WizardStep.DICTIONARY
        }
        composeRule.onNodeWithText("UniDic failed").assertDoesNotExist()
        composeRule.runOnIdle { step = WizardStep.TOKENIZER }
        composeRule.onNodeWithText("UniDic failed").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()
        composeRule.runOnIdle { assertEquals(1, resourceRetries) }

        composeRule.runOnIdle {
            state =
                SetupUiState(
                    ankiFailure =
                        AnkiSetupFailure(
                            code = "anki",
                            message = "Anki failed",
                            origin = AnkiSetupFailureOrigin.TARGET,
                        ),
                )
            step = WizardStep.ANKIDROID_NOTE_TYPE
        }
        composeRule.onNodeWithText("Anki failed").assertDoesNotExist()
        composeRule.runOnIdle { step = WizardStep.ANKIDROID }
        composeRule.onNodeWithText("Anki failed").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()
        composeRule.runOnIdle { assertEquals(1, ankiRetries) }

    }

    @Test
    fun missingAnkiDroidShowsOneSentenceAndNoProviderError() {
        var installs = 0
        composeRule.setContent {
            AnkiMinerTheme {
                OnboardingWizardContent(
                    state =
                        SetupUiState(
                            anki = AnkiProviderReadiness.NotInstalled,
                            ankiFailure =
                                AnkiSetupFailure(
                                    code = "provider_unavailable",
                                    message = "AnkiDroid is not available",
                                    origin = AnkiSetupFailureOrigin.TARGET,
                                ),
                        ),
                    step = WizardStep.ANKIDROID,
                    callbacks = OnboardingWizardCallbacks(onInstallAnkiDroid = { installs += 1 }),
                )
            }
        }

        composeRule.onNodeWithText("AnkiDroid is not available").assertDoesNotExist()
        composeRule
            .onNodeWithText("Anki Miner adds cards through AnkiDroid. Install it, then come back.")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Install AnkiDroid").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(1, installs) }
    }

    @Test
    fun stepChangeSetsPaneTitleAndMovesFocusToTheAppBarHeadingOnce() {
        var step by mutableStateOf(WizardStep.WELCOME)
        composeRule.setContent {
            AnkiMinerTheme {
                OnboardingWizardContent(
                    state = SetupUiState(),
                    step = step,
                    callbacks = OnboardingWizardCallbacks(),
                )
            }
        }

        composeRule.runOnIdle { step = WizardStep.ANKIDROID }
        composeRule.waitForIdle()

        composeRule
            .onAllNodes(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.PaneTitle,
                    "AnkiDroid",
                ),
                useUnmergedTree = true,
            ).assertCountEquals(1)
        composeRule
            .onNodeWithTag(WIZARD_STEP_HEADING_TEST_TAG, useUnmergedTree = true)
            .assertIsFocused()
            .assert(
                SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit),
            )
    }
}
