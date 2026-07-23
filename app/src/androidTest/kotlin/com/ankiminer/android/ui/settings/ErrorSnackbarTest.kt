package com.ankiminer.android.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.ankiminer.android.ui.theme.AnkiMinerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test

/** Batch 3 contract: failures remain at their origin; snackbar is only a linked summary. */
class ErrorSnackbarTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun originFailureRemainsVisibleAcrossIdenticalRecompositionsUntilDismissed() {
        val message = "Dictionary archive contains an oversized file"
        var current by mutableStateOf<String?>(message)
        var unrelatedRevision by mutableStateOf(0)

        composeRule.setContent {
            AnkiMinerTheme {
                Box {
                    unrelatedRevision
                    current?.let { failure ->
                        InlineFailureContainer(
                            message = failure,
                            actionLabel = "Choose another",
                            onAction = {},
                            onDismiss = { current = null },
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithText(message).assertIsDisplayed()
        repeat(10) {
            composeRule.runOnIdle { unrelatedRevision += 1 }
            composeRule.onNodeWithText(message).assertIsDisplayed()
        }

        composeRule.onNodeWithText("Dismiss").performClick()
        composeRule.onNodeWithText(message).assertDoesNotExist()
    }

    @Test
    fun linkedSnackbarViewActionDoesNotClearPersistentOriginFailure() {
        val message = "Known-word import failed"
        var current by mutableStateOf<String?>(message)
        var viewed = 0
        val hostState = SnackbarHostState()

        composeRule.setContent {
            AnkiMinerTheme {
                MessageSnackbarEffect(
                    message = current,
                    hostState = hostState,
                    actionLabel = "View",
                    onAction = { viewed += 1 },
                )
                Scaffold(snackbarHost = { SnackbarHost(hostState) }) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {}
                }
            }
        }

        composeRule.onNodeWithText(message).assertIsDisplayed()
        composeRule.onNodeWithText("View").performClick()
        composeRule.runOnIdle {
            assertEquals(1, viewed)
            assertNotNull(current)
        }
    }
}
