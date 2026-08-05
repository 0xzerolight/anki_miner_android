package com.ankiminer.android.ui.settings

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import com.ankiminer.android.ui.theme.AnkiMinerTheme
import com.ankiminer.android.vm.SettingsBackupState
import org.junit.Rule
import org.junit.Test

class SettingsBackupCardTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun diagnosticsOffersEnabledSettingsFileActions() {
        composeRule.setContent {
            AnkiMinerTheme {
                SettingsCategoryLayout(
                    selectedCategory = SettingsCategory.DIAGNOSTICS,
                    onSelectedCategory = {},
                    header = {},
                ) { category ->
                    if (category == SettingsCategory.DIAGNOSTICS) {
                        settingsCard("settings-backup") {
                            SettingsBackupSection(
                                backupState = SettingsBackupState.Idle,
                                onExportSettings = {},
                                onImportSettings = {},
                                onDismissBackupState = {},
                            )
                        }
                    }
                }
            }
        }

        composeRule
            .onNodeWithTag(SettingsCategoryTestTags.LIST)
            .performScrollToNode(hasText("Save settings to a file"))
        composeRule
            .onNodeWithText("Save settings to a file")
            .assertExists()
            .assertIsEnabled()
        composeRule
            .onNodeWithText("Load settings from a file")
            .assertExists()
            .assertIsEnabled()
    }
}
