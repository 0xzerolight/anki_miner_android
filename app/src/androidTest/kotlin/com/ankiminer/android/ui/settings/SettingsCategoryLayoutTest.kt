package com.ankiminer.android.ui.settings

import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import com.ankiminer.android.ui.theme.AnkiMinerTheme
import org.junit.Rule
import org.junit.Test

class SettingsCategoryLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun onlySelectedCategoryExistsAndEachCategoryRestoresItsPosition() {
        var selected by mutableStateOf(SettingsCategory.ANKI)
        val rows = List(100) { it }

        composeRule.setContent {
            AnkiMinerTheme {
                SettingsCategoryLayout(
                    selectedCategory = selected,
                    onSelectedCategory = { selected = it },
                    header = {},
                ) { category ->
                    items(
                        items = rows,
                        key = { index -> "${category.name}-$index" },
                        contentType = { "row" },
                    ) { index ->
                        androidx.compose.material3.Text("${category.name} row $index")
                    }
                }
            }
        }

        composeRule.onNodeWithText("ANKI row 0").assertIsDisplayed()
        composeRule.onNodeWithText("MEDIA row 0").assertDoesNotExist()

        composeRule
            .onNodeWithTag(SettingsCategoryTestTags.LIST)
            .performScrollToIndex(52)
        composeRule.onNodeWithText("ANKI row 50").assertIsDisplayed()
        composeRule.onNodeWithText("Media").performClick()

        composeRule.onNodeWithText("ANKI row 50").assertDoesNotExist()
        composeRule
            .onNodeWithTag(SettingsCategoryTestTags.LIST)
            .performScrollToIndex(22)
        composeRule.onNodeWithText("MEDIA row 20").assertIsDisplayed()

        composeRule.onNodeWithText("Anki").performClick()
        composeRule.onNodeWithText("ANKI row 50").assertIsDisplayed()
        composeRule.onNodeWithText("MEDIA row 20").assertDoesNotExist()
    }
}
