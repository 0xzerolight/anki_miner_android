package com.ankiminer.android.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNode
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.input.ImeAction
import com.ankiminer.android.ui.theme.AnkiMinerTheme
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
}
