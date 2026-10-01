package com.ankiminer.android.ui.mining

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.ankiminer.android.mining.MiningProgress
import com.ankiminer.android.ui.theme.AnkiMinerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MiningPhaseScaffoldTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun theMineBarCarriesTheOneForwardAction() {
        var mined = false
        composeRule.setContent {
            AnkiMinerTheme {
                MiningBottomBar(
                    MiningBottomBarState.Mine(enabled = true, testTag = "mine", onMine = { mined = true }),
                    commandErrorMessage = null,
                    onDismissCommandError = {},
                )
            }
        }

        composeRule.onNodeWithTag("mine").assertTextEquals("Mine").performClick()
        composeRule.runOnIdle { assertTrue(mined) }
    }

    @Test
    fun cancelHoldsItsPlaceWhileTheStageChanges() {
        var progress by mutableStateOf(MiningProgress(current = 0, total = 0, description = "Copying video"))
        composeRule.setContent {
            AnkiMinerTheme {
                // Review focus: 320dp at 2x text, the progress + Cancel bar stays one row.
                CompositionLocalProvider(
                    LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f),
                ) {
                    Box(Modifier.requiredWidth(320.dp)) {
                        MiningBottomBar(
                            MiningBottomBarState.Progress(
                                progress,
                                canCancel = true,
                                cancelPending = false,
                                progressTestTag = "progress",
                                cancelTestTag = "cancel",
                                onCancel = {},
                            ),
                            commandErrorMessage = null,
                            onDismissCommandError = {},
                        )
                    }
                }
            }
        }

        val before = composeRule.onNodeWithTag("cancel").fetchSemanticsNode().boundsInRoot
        composeRule.runOnIdle {
            progress = MiningProgress(current = 3, total = 10, description = "Parsing subtitles")
        }
        composeRule.onNodeWithText("3 of 10").assertExists()
        val after = composeRule.onNodeWithTag("cancel").fetchSemanticsNode().boundsInRoot
        assertEquals(before.top, after.top, 0.5f)
        assertEquals(before.left, after.left, 0.5f)
        val panel = composeRule.onNodeWithTag("progress").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "progress and Cancel must share one row",
            after.top < panel.bottom && panel.top < after.bottom,
        )
    }

    @Test
    fun aFailedRunsCauseSitsAboveTheInputs() {
        composeRule.setContent {
            AnkiMinerTheme {
                LazyColumn {
                    miningFailureBannerItem(message = "Subtitle timing could not be read", key = "failure")
                    item { Text("inputs", Modifier.testTag("inputs")) }
                }
            }
        }

        val banner = composeRule.onNodeWithTag(MINING_FAILURE_TEST_TAG).fetchSemanticsNode().boundsInRoot
        val inputs = composeRule.onNodeWithTag("inputs").fetchSemanticsNode().boundsInRoot
        assertTrue(banner.bottom <= inputs.top)
    }
}
