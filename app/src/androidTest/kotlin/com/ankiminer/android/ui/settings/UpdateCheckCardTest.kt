package com.ankiminer.android.ui.settings

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import com.ankiminer.android.data.update.AvailableUpdate
import com.ankiminer.android.data.update.UpdateCheckUiState
import com.ankiminer.android.ui.theme.AnkiMinerTheme
import org.junit.Rule
import org.junit.Test

class UpdateCheckCardTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun setDiagnostics(updateCheck: UpdateCheckUiState) {
        val recorder = SettingsCardIndexRecorder()
        composeRule.setContent {
            AnkiMinerTheme {
                SettingsCategoryLayout(
                    selectedCategory = SettingsCategory.DIAGNOSTICS,
                    onSelectedCategory = {},
                    recorder = recorder,
                    header = {},
                ) { category ->
                    if (category == SettingsCategory.DIAGNOSTICS) {
                        settingsCard(category, recorder, "update-check") {
                            UpdateCheckSection(
                                updateCheck = updateCheck,
                                onEnabledChange = {},
                                onCheck = {},
                                onSkip = {},
                            )
                        }
                    }
                }
            }
        }
    }

    private fun scrollTo(text: String) {
        composeRule
            .onNodeWithTag(SettingsCategoryTestTags.LIST)
            .performScrollToNode(hasText(text))
    }

    @Test
    fun availableUpdateShowsReleaseAndSkipActions() {
        setDiagnostics(
            UpdateCheckUiState(
                available = AvailableUpdate("0.5.0", "https://github.com/x"),
            ),
        )

        scrollTo("Version 0.5.0 is available")
        composeRule.onNodeWithText("Version 0.5.0 is available").assertExists()
        scrollTo("View release")
        composeRule.onNodeWithText("View release").assertIsEnabled()
        scrollTo("Skip this version")
        composeRule.onNodeWithText("Skip this version").assertIsEnabled()
    }

    @Test
    fun completedCheckWithoutUpdateShowsNewestReleaseOnly() {
        setDiagnostics(
            UpdateCheckUiState(
                available = null,
                lastCheckedAtMillis = 1L,
            ),
        )

        scrollTo("This is the newest release.")
        composeRule.onNodeWithText("This is the newest release.").assertExists()
        scrollTo("This is the newest release.")
        composeRule.onNodeWithText("Skip this version").assertDoesNotExist()
    }
}
