package com.ankiminer.android.ui.navigation

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.ankiminer.android.R
import com.ankiminer.android.anki.provider.AnkiProviderReadiness
import com.ankiminer.android.anki.provider.AnkiRecoveryReadiness
import com.ankiminer.android.anki.provider.NoteTypeSetupStatus
import com.ankiminer.android.data.resources.FrozenResourceCatalog
import com.ankiminer.android.data.resources.RecommendedResourceAction
import com.ankiminer.android.data.resources.RecommendedResourceItem
import com.ankiminer.android.data.resources.RecommendedResourcePlan
import com.ankiminer.android.data.resources.ResourceOperationPhase
import com.ankiminer.android.data.resources.ResourceOperationProgress
import com.ankiminer.android.data.resources.ResourceStartupReadiness
import com.ankiminer.android.engine.PythonRuntimeReadiness
import com.ankiminer.android.mining.AnkiMiningTargetReadiness
import com.ankiminer.android.ui.theme.AnkiMinerTheme
import com.ankiminer.android.vm.SetupUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MiningReadinessNoticeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun readyExceptDictionary() =
        SetupUiState(
            python = PythonRuntimeReadiness.Ready("/runtime"),
            resourceStartup = ResourceStartupReadiness.READY,
            anki = AnkiProviderReadiness.Ready(apiSpecVersion = 7, versionCode = 1L),
            ankiRecovery = AnkiRecoveryReadiness.Ready,
            noteTypeStatus = NoteTypeSetupStatus.Verified(modelId = 1L),
            miningTarget = AnkiMiningTargetReadiness.Ready,
            uniDicInstalled = true,
        )

    @Test
    fun missingDictionaryInstallsInPlaceWithoutOpeningSettings() {
        var installs = 0
        var settingsOpened = 0
        composeRule.setContent {
            AnkiMinerTheme {
                MiningReadinessNotice(
                    // An actionable plan: the case where the in-place install has work to do.
                    state =
                        readyExceptDictionary().copy(
                            recommendedPlan =
                                RecommendedResourcePlan(
                                    FrozenResourceCatalog.value.recommendedResources.map {
                                        RecommendedResourceItem(it, RecommendedResourceAction.INSTALL)
                                    },
                                ),
                        ),
                    message = context.getString(R.string.readiness_dictionary_required),
                    onRequestPermissions = {},
                    onInstallUniDic = {},
                    onInstallAnkiDroid = {},
                    onOpenAnkiDroid = {},
                    onCheckAgain = {},
                    onOpenSettings = { settingsOpened += 1 },
                    onImportDictionary = { settingsOpened += 1 },
                    onInstallDictionary = { installs += 1 },
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.readiness_install_dictionary)).performClick()

        composeRule.runOnIdle {
            assertEquals(1, installs)
            assertEquals(0, settingsOpened)
        }
    }

    @Test
    fun aRunningDownloadShowsItsProgressOnceWithCancel() {
        var cancels = 0
        val inProgress = context.getString(R.string.readiness_resource_operation)
        composeRule.setContent {
            AnkiMinerTheme {
                MiningReadinessNotice(
                    state =
                        readyExceptDictionary().copy(
                            operation = ResourceOperationProgress("op", "JMdict", ResourceOperationPhase.DOWNLOADING, 5, 10),
                        ),
                    message = inProgress,
                    onRequestPermissions = {},
                    onInstallUniDic = {},
                    onInstallAnkiDroid = {},
                    onOpenAnkiDroid = {},
                    onCheckAgain = {},
                    onOpenSettings = {},
                    onImportDictionary = {},
                    onCancelOperation = { cancels += 1 },
                )
            }
        }

        composeRule.onNodeWithText("JMdict").assertIsDisplayed()
        composeRule.onAllNodesWithText(inProgress).assertCountEquals(1)
        composeRule.onNodeWithText(context.getString(R.string.cancel)).performClick()
        composeRule.runOnIdle { assertEquals(1, cancels) }
    }

    @Test
    fun noticeCountsWhatIsLeftAndContinuesSetup() {
        var continued = 0
        composeRule.setContent {
            AnkiMinerTheme {
                MiningReadinessNotice(
                    state = readyExceptDictionary().copy(anki = AnkiProviderReadiness.NotInstalled),
                    message = context.getString(R.string.readiness_dictionary_required),
                    onRequestPermissions = {},
                    onInstallUniDic = {},
                    onInstallAnkiDroid = {},
                    onOpenAnkiDroid = {},
                    onCheckAgain = {},
                    onOpenSettings = {},
                    onImportDictionary = {},
                    onContinueSetup = { continued += 1 },
                )
            }
        }

        composeRule.onNodeWithText("Finish setup (2 left)").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.readiness_continue_setup)).performClick()
        composeRule.runOnIdle { assertEquals(1, continued) }
    }
}
