package com.ankiminer.android.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.ankiminer.android.data.anki.AnkiSetupFailureOrigin
import com.ankiminer.android.data.resources.KnownWordsFailureOperation
import com.ankiminer.android.data.resources.ResourceFailure
import com.ankiminer.android.data.resources.ResourceFailureAction
import com.ankiminer.android.data.resources.ResourceFailureOrigin
import com.ankiminer.android.data.resources.ResourceFailureRetry
import com.ankiminer.android.ui.theme.AnkiMinerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class FailureOriginRoutingTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun resourceAndAnkiFailuresExistOnlyInTheirMappedCategoryAndKeepWorkingAction() {
        val cases =
            listOf(
                "UniDic failed" to settingsCategoryFor(ResourceFailureOrigin.UNIDIC),
                "Dictionary failed" to
                    settingsCategoryFor(ResourceFailureOrigin.CATALOG_DICTIONARY),
                "Known words failed" to
                    settingsCategoryFor(ResourceFailureOrigin.KNOWN_WORDS),
                "Anki failed" to settingsCategoryFor(AnkiSetupFailureOrigin.TARGET),
                "Recovery failed" to settingsCategoryFor(AnkiSetupFailureOrigin.RECOVERY),
            )
        var selected by mutableStateOf(SettingsCategory.UI)
        var expected by mutableStateOf(SettingsCategory.SETUP)
        var message by mutableStateOf("not shown")
        var actions by mutableIntStateOf(0)

        composeRule.setContent {
            AnkiMinerTheme {
                SettingsCategoryLayout(
                    selectedCategory = selected,
                    onSelectedCategory = { selected = it },
                    header = {},
                ) { category ->
                    if (category == expected) {
                        settingsCard("failure") {
                            InlineFailureContainer(
                                message = message,
                                actionLabel = "Resolve test failure",
                                onAction = { actions += 1 },
                                onDismiss = {},
                            )
                        }
                    }
                }
            }
        }

        cases.forEachIndexed { index, (caseMessage, category) ->
            val wrongCategory =
                SettingsCategory.entries.first { candidate -> candidate != category }
            composeRule.runOnIdle {
                message = caseMessage
                expected = category
                selected = wrongCategory
            }
            composeRule.onNodeWithText(caseMessage).assertDoesNotExist()

            composeRule.runOnIdle { selected = category }
            composeRule.onNodeWithText(caseMessage).assertIsDisplayed()
            composeRule.onNodeWithText("Resolve test failure").performClick()
            composeRule.runOnIdle { assertEquals(index + 1, actions) }
        }

        assertEquals(
            KnownWordsFailureTarget.IMPORT,
            knownWordsFailureTarget(knownWordsFailure(KnownWordsFailureOperation.IMPORT)),
        )
        assertEquals(
            KnownWordsFailureTarget.IMPORT,
            knownWordsFailureTarget(knownWordsFailure(KnownWordsFailureOperation.PREVIEW)),
        )
        assertEquals(
            KnownWordsFailureTarget.EXPORT,
            knownWordsFailureTarget(knownWordsFailure(KnownWordsFailureOperation.EXPORT)),
        )
    }

    private fun knownWordsFailure(operation: KnownWordsFailureOperation): ResourceFailure =
        ResourceFailure(
            code = "known_words_failed",
            message = "Known words failed",
            retryable = false,
            origin = ResourceFailureOrigin.KNOWN_WORDS,
            retry = ResourceFailureRetry(ResourceFailureAction.CHOOSE_ANOTHER),
            knownWordsOperation = operation,
        )
}
