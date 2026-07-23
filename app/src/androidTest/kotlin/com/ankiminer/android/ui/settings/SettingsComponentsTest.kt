package com.ankiminer.android.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ankiminer.android.data.settings.ResourceChainSelection
import com.ankiminer.android.ui.theme.AnkiMinerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SettingsComponentsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun nextMovesToFollowingNumericFieldAndDoneClearsFocus() {
        var first by mutableStateOf("")
        var second by mutableStateOf("")
        composeRule.setContent {
            AnkiMinerTheme {
                Column {
                    NumericField(
                        value = first,
                        onChange = { first = it },
                        label = "First value",
                        supporting = "Optional",
                        imeAction = ImeAction.Next,
                    )
                    NumericField(
                        value = second,
                        onChange = { second = it },
                        label = "Final value",
                        supporting = "Optional",
                        imeAction = ImeAction.Done,
                    )
                }
            }
        }

        val firstField = composeRule.onNode(hasText("First value") and hasSetTextAction())
        val finalField = composeRule.onNode(hasText("Final value") and hasSetTextAction())
        firstField.performClick().assertIsFocused()
        firstField.performImeAction()
        finalField.assertIsFocused()
        finalField.performImeAction()
        finalField.assertIsNotFocused()
    }

    @Test
    fun fieldErrorReplacesSupportingTextAtTheEditedField() {
        composeRule.setContent {
            AnkiMinerTheme {
                NumericField(
                    value = ".",
                    onChange = {},
                    label = "Audio padding",
                    supporting = "Recommended: 0.25",
                    error = "Complete or clear this number",
                )
            }
        }

        composeRule.onNodeWithText("Complete or clear this number").assertIsDisplayed()
        composeRule.onNodeWithText("Recommended: 0.25").assertDoesNotExist()
    }

    @Test
    fun malformedPastedNumberRemainsVisibleForFieldValidation() {
        var value by mutableStateOf("")
        composeRule.setContent {
            AnkiMinerTheme {
                NumericField(
                    value = value,
                    onChange = { value = it },
                    label = "Workers",
                    supporting = "Recommended: 4",
                    integer = true,
                    error =
                        value
                            .takeIf { it.isNotEmpty() && it.toIntOrNull() == null }
                            ?.let { "Enter a whole number" },
                )
            }
        }

        composeRule
            .onNode(hasText("Workers") and hasSetTextAction())
            .performTextReplacement("33 workers")

        composeRule.onNodeWithText("33 workers").assertIsDisplayed()
        composeRule.onNodeWithText("Enter a whole number").assertIsDisplayed()
    }

    @Test
    fun adaptiveChoiceSelectorUsesVerticalRadioRowsBelowCompactBreakpoint() {
        setChoiceSelector(width = 359.dp, fontScale = 1f)

        composeRule
            .onAllNodes(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup),
                useUnmergedTree = true,
            ).assertCountEquals(1)
        composeRule
            .onAllNodes(
                SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton),
            ).assertCountEquals(3)
        val tops =
            OPTIONS.map { label ->
                composeRule
                    .onNodeWithText(label)
                    .assertHeightIsAtLeast(48.dp)
                    .fetchSemanticsNode()
                    .boundsInRoot
                    .top
            }
        assertTrue(tops.zipWithNext().all { (first, second) -> second > first })
    }

    @Test
    fun adaptiveChoiceSelectorKeepsWideNormalTextInOneSegmentedRow() {
        setChoiceSelector(width = 360.dp, fontScale = 1f)

        val tops =
            OPTIONS.map { label ->
                composeRule
                    .onNodeWithText(label)
                    .assertHeightIsAtLeast(48.dp)
                    .fetchSemanticsNode()
                    .boundsInRoot
                    .top
            }
        tops.drop(1).forEach { top -> assertEquals(tops.first(), top, 0.5f) }
    }

    @Test
    fun adaptiveChoiceSelectorStacksAtLargeFontEvenWhenWide() {
        setChoiceSelector(width = 600.dp, fontScale = 1.3f)

        val tops =
            OPTIONS.map { label ->
                composeRule.onNodeWithText(label).fetchSemanticsNode().boundsInRoot.top
            }
        assertTrue(tops.zipWithNext().all { (first, second) -> second > first })
    }

    @Test
    fun pairedResourceActionsStackAndKeepTouchTargetsAtCompactWidth() {
        composeRule.setContent {
            AnkiMinerTheme {
                Box(Modifier.width(359.dp)) {
                    ResourceChainEditor(
                        choices = listOf(ResourceChainSelection("source")),
                        labels = mapOf("source" to "Source"),
                        emptyMessage = "None",
                        onChange = {},
                    )
                }
            }
        }

        val moveUp =
            composeRule
                .onNodeWithText("Move up")
                .assertHeightIsAtLeast(48.dp)
                .fetchSemanticsNode()
                .boundsInRoot
        val moveDown =
            composeRule
                .onNodeWithText("Move down")
                .assertHeightIsAtLeast(48.dp)
                .fetchSemanticsNode()
                .boundsInRoot
        assertTrue(moveDown.top > moveUp.top)
    }

    private fun setChoiceSelector(
        width: Dp,
        fontScale: Float,
    ) {
        composeRule.setContent {
            val baseDensity = LocalDensity.current.density
            CompositionLocalProvider(
                LocalDensity provides Density(baseDensity, fontScale),
            ) {
                AnkiMinerTheme {
                    Box(Modifier.width(width)) {
                        AdaptiveChoiceSelector(
                            values = OPTIONS,
                            selected = OPTIONS.first(),
                            label = { it },
                            onSelect = {},
                        )
                    }
                }
            }
        }
    }

    private companion object {
        val OPTIONS = listOf("First", "Second", "Third")
    }
}
