package com.ankiminer.android.ui.wizard

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import com.ankiminer.android.anki.provider.AnkiProviderReadiness
import com.ankiminer.android.data.anki.AnkiSetupFailure
import com.ankiminer.android.data.anki.AnkiSetupFailureOrigin
import com.ankiminer.android.data.resources.FrozenResourceCatalog
import com.ankiminer.android.data.resources.RecommendedResourceAction
import com.ankiminer.android.data.resources.RecommendedResourceItem
import com.ankiminer.android.data.resources.RecommendedResourcePlan
import com.ankiminer.android.data.resources.ResourceFailure
import com.ankiminer.android.data.resources.ResourceFailureAction
import com.ankiminer.android.data.resources.ResourceFailureOrigin
import com.ankiminer.android.data.resources.ResourceFailureRetry
import com.ankiminer.android.data.resources.ResourceStartupReadiness
import com.ankiminer.android.engine.ContentDirection
import com.ankiminer.android.engine.LanguageProfileInfo
import com.ankiminer.android.engine.LanguageUnavailableReason
import com.ankiminer.android.ui.theme.AnkiMinerTheme
import com.ankiminer.android.vm.SetupUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
                    step = WizardStep.LANGUAGE,
                    callbacks =
                        OnboardingWizardCallbacks(
                            onFinished = { finished += 1 },
                        ),
                )
            }
        }

        composeRule.onNodeWithText("Skip for now").performScrollTo().performClick()
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
                    step = WizardStep.READY,
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
        var step by mutableStateOf(WizardStep.LANGUAGE)
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
            step = WizardStep.ANKIDROID
        }
        composeRule.onNodeWithText("UniDic failed").assertDoesNotExist()
        composeRule.runOnIdle { step = WizardStep.DOWNLOADS }
        composeRule.onNodeWithText("UniDic failed").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performScrollTo().performClick()
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
            step = WizardStep.DOWNLOADS
        }
        composeRule.onNodeWithText("Anki failed").assertDoesNotExist()
        composeRule.runOnIdle { step = WizardStep.ANKIDROID }
        composeRule.onNodeWithText("Anki failed").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performScrollTo().performClick()
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
        var step by mutableStateOf(WizardStep.LANGUAGE)
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

    @Test
    fun languagePageSwitchesAtOnceAndDefersADownloadToNext() {
        val switched = mutableListOf<String>()
        val downloads = mutableListOf<String>()
        var stepped: WizardStep? = null
        composeRule.setContent {
            AnkiMinerTheme {
                OnboardingWizardContent(
                    state = SetupUiState(resourceStartup = ResourceStartupReadiness.READY),
                    step = WizardStep.LANGUAGE,
                    language =
                        WizardLanguageState(
                            profiles =
                                listOf(
                                    profile("ja", "日本語", "Japanese"),
                                    profile("de", "Deutsch", "German"),
                                    profile("ko", "한국어", "Korean", LanguageUnavailableReason.DATA_REQUIRED),
                                ),
                            downloadBytes = { 4_475_945L },
                        ),
                    callbacks =
                        OnboardingWizardCallbacks(
                            onStep = { stepped = it },
                            onSwitchLanguage = { switched += it },
                            onDownloadAndSwitchLanguage = { downloads += it },
                        ),
                )
            }
        }

        composeRule.onNodeWithText("日本語 — Japanese").performClick()
        composeRule.onNodeWithText("Deutsch — German").performClick()
        composeRule.onNodeWithText("日本語 — Japanese").performClick()
        composeRule.onNodeWithText("한국어 — Korean (download)").performClick()
        composeRule.onNodeWithText("needs a one-time download", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(listOf<String>(), downloads) }

        composeRule.onNodeWithText("Next").performClick()

        composeRule.runOnIdle {
            assertEquals(listOf("de"), switched)
            assertEquals(listOf("ko"), downloads)
            assertEquals(WizardStep.DOWNLOADS, stepped)
        }
    }

    @Test
    fun downloadsPageOffersOneDownloadAndNextNeverWaits() {
        var installs = 0
        var stepped: WizardStep? = null
        var state by mutableStateOf(SetupUiState(resourceStartup = ResourceStartupReadiness.READY))
        composeRule.setContent {
            AnkiMinerTheme {
                OnboardingWizardContent(
                    state = state,
                    step = WizardStep.DOWNLOADS,
                    callbacks =
                        OnboardingWizardCallbacks(
                            onStep = { stepped = it },
                            onInstallRequiredResources = { installs += 1 },
                        ),
                )
            }
        }

        // One footer action and no footer Back: Back is the app-bar arrow (D2).
        composeRule.onAllNodesWithText("Back").assertCountEquals(0)
        composeRule.onNodeWithText("Download and install").performScrollTo().performClick()
        composeRule.onNodeWithText("Next").performClick()
        composeRule.runOnIdle {
            assertEquals(1, installs)
            assertEquals(WizardStep.ANKIDROID, stepped)
        }
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.runOnIdle { assertEquals(WizardStep.LANGUAGE, stepped) }

        composeRule.runOnIdle {
            state =
                state.copy(
                    uniDicInstalled = true,
                    recommendedPlan =
                        RecommendedResourcePlan(
                            FrozenResourceCatalog.value.recommendedResources.map {
                                RecommendedResourceItem(it, RecommendedResourceAction.SKIP)
                            },
                        ),
                )
        }
        composeRule.onNodeWithText("Installed").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Repair").assertDoesNotExist()
    }

    @Test
    fun ankiDroidPageWaitsForAPendingLanguageDownload() {
        composeRule.setContent {
            AnkiMinerTheme {
                OnboardingWizardContent(
                    state =
                        SetupUiState(
                            resourceStartup = ResourceStartupReadiness.READY,
                            anki = AnkiProviderReadiness.Ready(apiSpecVersion = 7, versionCode = 1L),
                        ),
                    step = WizardStep.ANKIDROID,
                    language =
                        WizardLanguageState(
                            profiles = listOf(profile("ko", "한국어", "Korean", LanguageUnavailableReason.DATA_REQUIRED)),
                            downloadingCode = "ko",
                        ),
                    callbacks = OnboardingWizardCallbacks(),
                )
            }
        }

        composeRule
            .onNodeWithText("Pick the deck and note type for Korean once its download finishes.")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Target deck").assertDoesNotExist()
    }

    @Test
    fun languageDownloadLostToProcessDeathStillHoldsTheAnkiPage() {
        val downloads = mutableListOf<String>()
        var step by mutableStateOf(WizardStep.LANGUAGE)
        var language by
            mutableStateOf(
                WizardLanguageState(
                    profiles =
                        listOf(
                            profile("ja", "日本語", "Japanese"),
                            profile("ko", "한국어", "Korean", LanguageUnavailableReason.DATA_REQUIRED),
                        ),
                ),
            )
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            AnkiMinerTheme {
                OnboardingWizardContent(
                    state =
                        SetupUiState(
                            resourceStartup = ResourceStartupReadiness.READY,
                            anki = AnkiProviderReadiness.Ready(apiSpecVersion = 7, versionCode = 1L),
                        ),
                    step = step,
                    language = language,
                    callbacks =
                        OnboardingWizardCallbacks(
                            onStep = { step = it },
                            onDownloadAndSwitchLanguage = { code ->
                                downloads += code
                                language = language.copy(downloadingCode = code)
                            },
                        ),
                )
            }
        }

        composeRule.onNodeWithText("日本語 — Japanese").performClick()
        composeRule.onNodeWithText("한국어 — Korean (download)").performClick()
        composeRule.onNodeWithText("Next").performClick()
        composeRule.runOnIdle { assertEquals(listOf("ko"), downloads) }

        // Android kills the process mid-download: the in-memory download is gone, the step survives.
        composeRule.runOnIdle {
            step = WizardStep.ANKIDROID
            language = language.copy(downloadingCode = null)
        }
        restorationTester.emulateSavedInstanceStateRestore()

        // The deck and note type would land in Japanese; the page still waits for Korean.
        composeRule.onNodeWithText("Target deck").assertDoesNotExist()
        composeRule.onNodeWithText("Korean", substring = true).performScrollTo().assertIsDisplayed()

        // Downloads offers Korean again, never Japanese's set.
        composeRule.runOnIdle { step = WizardStep.DOWNLOADS }
        composeRule.onNodeWithText("Download and switch").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(listOf("ko", "ko"), downloads) }
    }

    @Test
    fun languagePageKeepsHeadingAndSkipUsableAtDoubleFontOn320dp() {
        var finished = 0
        composeRule.setContent {
            val base = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(base, 2f)) {
                AnkiMinerTheme {
                    Box(Modifier.requiredWidth(320.dp)) {
                        OnboardingWizardContent(
                            state = SetupUiState(resourceStartup = ResourceStartupReadiness.READY),
                            step = WizardStep.LANGUAGE,
                            language = WizardLanguageState(profiles = listOf(profile("ja", "日本語", "Japanese"))),
                            callbacks = OnboardingWizardCallbacks(onFinished = { finished += 1 }),
                        )
                    }
                }
            }
        }

        // With Skip in the app bar the heading kept about 120dp and read "What ar…".
        assertTrue(composeRule.onNodeWithTag(WIZARD_STEP_HEADING_TEST_TAG).getUnclippedBoundsInRoot().width >= 200.dp)
        composeRule.onNodeWithText("Next").assertIsDisplayed()
        composeRule.onNodeWithText("Skip for now").performScrollTo().assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(1, finished) }
    }

    private fun profile(
        code: String,
        native: String,
        english: String,
        reason: LanguageUnavailableReason? = null,
    ) = LanguageProfileInfo(
        code = code,
        displayName = native,
        englishName = english,
        unavailableReason = reason,
        scriptVariants = emptyList(),
        contentDirection = ContentDirection.LTR,
        contentLanguage = code,
        speechLanguage = code,
        audioTrackCodes = emptyList(),
        capabilities = emptySet(),
        requiresUnidic = code == "ja",
        scopedDefaults = emptyMap(),
        extraCardFields = emptyList(),
    )
}
