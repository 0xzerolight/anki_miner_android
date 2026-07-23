package com.ankiminer.android.ui.settings

import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.ankiminer.android.data.resources.KnownWordsInventory
import com.ankiminer.android.data.resources.KnownWordsPage
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
        composeRule.setContent {
            AnkiMinerTheme {
                KnownWordsManagerScreen(
                    state =
                        SetupUiState(
                            knownWordsPage =
                                KnownWordsPage(
                                    query = "猫",
                                    offset = 0,
                                    totalCount = 101,
                                    words = listOf("猫"),
                                    hasMore = true,
                                ),
                        ),
                    callbacks =
                        KnownWordsManagerCallbacks(
                            onSearch = { searches += 1 },
                            onLoadMore = { loads += 1 },
                        ),
                )
            }
        }

        composeRule.onNodeWithText("Search").performClick()
        composeRule.onNodeWithText("Load more").performClick()
        composeRule.runOnIdle {
            assertEquals(1, searches)
            assertEquals(1, loads)
        }
    }
}
