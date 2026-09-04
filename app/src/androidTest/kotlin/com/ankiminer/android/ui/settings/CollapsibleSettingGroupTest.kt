package com.ankiminer.android.ui.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.ankiminer.android.R
import com.ankiminer.android.ui.theme.AnkiMinerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CollapsibleSettingGroupTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val summary = context.getString(R.string.settings_disclosure_summary, "Decks", 2, 7)
    private val expandedLabel = context.getString(R.string.disclosure_expanded)
    private val collapsedLabel = context.getString(R.string.disclosure_collapsed)

    private fun setContent(forceOpen: Boolean = false) {
        composeRule.setContent {
            AnkiMinerTheme {
                CollapsibleSettingGroup(
                    title = "Decks",
                    selectedCount = 2,
                    totalCount = 7,
                    forceOpen = forceOpen,
                ) {
                    Text("Deck row")
                }
            }
        }
    }

    private fun stateDescriptionOfHeader(): String? =
        composeRule
            .onNodeWithText(summary)
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.StateDescription)

    @Test
    fun contentIsHiddenUntilTheHeaderIsTapped() {
        setContent()

        composeRule.onNodeWithText(summary).assertIsDisplayed()
        composeRule.onNodeWithText("Deck row").assertDoesNotExist()
        assertEquals(collapsedLabel, stateDescriptionOfHeader())

        composeRule.onNodeWithText(summary).performClick()

        composeRule.onNodeWithText("Deck row").assertIsDisplayed()
        assertEquals(expandedLabel, stateDescriptionOfHeader())
    }

    @Test
    fun tappingAnExpandedHeaderCollapsesItAgain() {
        setContent()

        composeRule.onNodeWithText(summary).performClick()
        composeRule.onNodeWithText("Deck row").assertIsDisplayed()

        composeRule.onNodeWithText(summary).performClick()
        composeRule.onNodeWithText("Deck row").assertDoesNotExist()
    }

    @Test
    fun forceOpenShowsContentAndLocksTheHeader() {
        setContent(forceOpen = true)

        composeRule.onNodeWithText("Deck row").assertIsDisplayed()
        composeRule.onNodeWithText(summary).assertIsNotEnabled()
        assertEquals(expandedLabel, stateDescriptionOfHeader())
    }

    @Test
    fun hoistedStateDrivesVisibilityInsteadOfTheGroupsOwnState() {
        var expanded by mutableStateOf(false)
        composeRule.setContent {
            AnkiMinerTheme {
                CollapsibleSettingGroup(
                    title = "Decks",
                    selectedCount = 2,
                    totalCount = 7,
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                ) {
                    Text("Deck row")
                }
            }
        }

        composeRule.onNodeWithText("Deck row").assertDoesNotExist()

        composeRule.onNodeWithText(summary).performClick()
        composeRule.waitForIdle()

        assertTrue(expanded)
        composeRule.onNodeWithText("Deck row").assertIsDisplayed()
    }

    @Test
    fun hoistedStateOpensTheGroupWithoutAClick() {
        composeRule.setContent {
            AnkiMinerTheme {
                CollapsibleSettingGroup(
                    title = "Decks",
                    selectedCount = 2,
                    totalCount = 7,
                    expanded = true,
                    onExpandedChange = {},
                ) {
                    Text("Deck row")
                }
            }
        }

        composeRule.onNodeWithText("Deck row").assertIsDisplayed()
        assertEquals(expandedLabel, stateDescriptionOfHeader())
    }
}
