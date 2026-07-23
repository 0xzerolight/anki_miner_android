package com.ankiminer.android.ui.settings

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodes
import com.ankiminer.android.ui.theme.AnkiMinerTheme
import com.ankiminer.android.vm.SetupUiState
import org.junit.Rule
import org.junit.Test

class SystemStatusCardSemanticsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun onlySummaryIsAPoliteLiveRegion() {
        composeRule.setContent {
            AnkiMinerTheme {
                SystemStatusCard(
                    state = SetupUiState(),
                    onRefresh = {},
                    onRequestPermissions = {},
                    onOpenAppSettings = {},
                    onInstallAnkiDroid = {},
                    onOpenAnkiDroid = {},
                )
            }
        }

        composeRule
            .onAllNodes(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Polite,
                ),
                useUnmergedTree = true,
            ).assertCountEquals(1)
    }
}
