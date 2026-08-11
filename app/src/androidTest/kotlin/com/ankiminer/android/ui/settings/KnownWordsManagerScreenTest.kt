package com.ankiminer.android.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.ankiminer.android.data.resources.KnownWordsFailureOperation
import com.ankiminer.android.data.resources.KnownWordsInventory
import com.ankiminer.android.data.resources.KnownWordsPage
import com.ankiminer.android.data.resources.ResourceFailure
import com.ankiminer.android.data.resources.ResourceStartupReadiness
import com.ankiminer.android.data.resources.ResourceFailureAction
import com.ankiminer.android.data.resources.ResourceFailureOrigin
import com.ankiminer.android.data.resources.ResourceFailureRetry
import com.ankiminer.android.ui.theme.AnkiMinerTheme
import com.ankiminer.android.vm.SetupUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class KnownWordsManagerScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun thousandWordsRemainLazyAndTailWordCanBeRemoved() {
        val words = List(1_000) { index -> "word-$index" }
        var removed: String? = null
        composeRule.setContent {
            AnkiMinerTheme {
                KnownWordsManagerScreen(
                    state =
                        SetupUiState(
                            // READY: every control on this screen is gated on !state.busy,
                            // and a PENDING startup makes the whole screen inert.
                            resourceStartup = ResourceStartupReadiness.READY,
                            knownWords =
                                KnownWordsInventory(
                                    totalCount = 1_000,
                                    userCount = 1_000,
                                    ankiCount = 0,
                                    minedCount = 0,
                                    schemaOk = true,
                                ),
                            knownWordsPage =
                                KnownWordsPage(
                                    query = "",
                                    offset = 0,
                                    totalCount = 1_000,
                                    words = words,
                                    hasMore = false,
                                ),
                        ),
                    callbacks =
                        KnownWordsManagerCallbacks(
                            onRemove = { removed = it },
                        ),
                )
            }
        }

        composeRule.onNodeWithText("word-0").assertIsDisplayed()
        composeRule.onNodeWithText("word-500").assertDoesNotExist()

        composeRule
            .onNodeWithTag(KnownWordsManagerTestTags.LIST)
            .performScrollToNode(hasText("word-999"))
        composeRule.onNodeWithText("word-999").assertIsDisplayed()
        composeRule
            .onNodeWithTag(KnownWordsManagerTestTags.remove("word-999"))
            .performClick()
        composeRule.runOnIdle { assertEquals("word-999", removed) }
    }

    @Test
    fun loadMoreAndSearchUseSeparateCallbacks() {
        var searches = 0
        var loads = 0
        var imports = 0
        var exports = 0
        var failureOperation by mutableStateOf(KnownWordsFailureOperation.PREVIEW)
        composeRule.setContent {
            AnkiMinerTheme {
                KnownWordsManagerScreen(
                    state =
                        SetupUiState(
                            resourceStartup = ResourceStartupReadiness.READY,
                            knownWordsPage =
                                KnownWordsPage(
                                    query = "猫",
                                    offset = 0,
                                    totalCount = 101,
                                    words = listOf("猫"),
                                    hasMore = true,
                                ),
                            failure =
                                ResourceFailure(
                                    code = "known_words_failed",
                                    message = "Known words failed",
                                    retryable = false,
                                    origin = ResourceFailureOrigin.KNOWN_WORDS,
                                    retry =
                                        ResourceFailureRetry(
                                            ResourceFailureAction.CHOOSE_ANOTHER,
                                        ),
                                    knownWordsOperation = failureOperation,
                                ),
                        ),
                    callbacks =
                        KnownWordsManagerCallbacks(
                            onSearch = { searches += 1 },
                            onLoadMore = { loads += 1 },
                            onImport = { imports += 1 },
                            onExport = { exports += 1 },
                        ),
                )
            }
        }

        composeRule.onNodeWithText("Search").performClick()
        composeRule
            .onNodeWithTag(KnownWordsManagerTestTags.LIST)
            .performScrollToNode(hasText("Load more"))
        composeRule.onNodeWithText("Load more").performClick()
        composeRule.runOnIdle {
            assertEquals(1, searches)
            assertEquals(1, loads)
        }

        composeRule.onNodeWithText("Choose another").performClick()
        composeRule.runOnIdle {
            assertEquals(1, imports)
            assertEquals(0, exports)
            failureOperation = KnownWordsFailureOperation.IMPORT
        }
        composeRule.onNodeWithText("Choose another").performClick()
        composeRule.runOnIdle {
            assertEquals(2, imports)
            assertEquals(0, exports)
            failureOperation = KnownWordsFailureOperation.EXPORT
        }
        composeRule.onNodeWithText("Choose another").performClick()
        composeRule.runOnIdle {
            assertEquals(2, imports)
            assertEquals(1, exports)
        }
    }

    @Test
    fun resetConfirmationSurvivesStateRestoration() {
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            AnkiMinerTheme {
                KnownWordsManagerScreen(
                    state =
                        SetupUiState(
                            resourceStartup = ResourceStartupReadiness.READY,
                            knownWords =
                                KnownWordsInventory(
                                    totalCount = 1,
                                    userCount = 1,
                                    ankiCount = 0,
                                    minedCount = 0,
                                    schemaOk = true,
                                ),
                        ),
                )
            }
        }

        composeRule.onNodeWithText("Reset user list").performClick()
        composeRule
            .onNodeWithText(
                "Remove every word you added? Anki-cache rows are unchanged. " +
                    "This cannot be undone.",
            ).assertIsDisplayed()

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule
            .onNodeWithText(
                "Remove every word you added? Anki-cache rows are unchanged. " +
                    "This cannot be undone.",
            ).assertIsDisplayed()
    }
}
