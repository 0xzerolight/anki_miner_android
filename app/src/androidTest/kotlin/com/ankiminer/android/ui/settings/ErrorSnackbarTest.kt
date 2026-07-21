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
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.ankiminer.android.ui.theme.AnkiMinerTheme
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * Bug #3 regression guard: import/resource/save failures must surface as a transient
 * Material snackbar via [MessageSnackbarEffect] — the reusable seam that replaced the
 * inline failure cards rendered off-screen at the top of the settings scroll. Exercises
 * the seam directly (no scrolling), so the failure text appearing at all proves it is no
 * longer buried in a top-of-column card.
 */
class ErrorSnackbarTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun failureMessageSurfacesInTheSnackbarHostWithoutScrolling() {
        val message = "Dictionary archive contains an oversized file"
        var current by mutableStateOf<String?>(null)

        composeRule.setContent {
            AnkiMinerTheme {
                val hostState = remember { SnackbarHostState() }
                MessageSnackbarEffect(
                    message = current,
                    hostState = hostState,
                    onDismiss = { current = null },
                )
                Scaffold(snackbarHost = { SnackbarHost(hostState) }) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {}
                }
            }
        }

        // A null message shows nothing.
        composeRule.onNodeWithText(message).assertDoesNotExist()

        // Raising the message surfaces it in the snackbar host — a transient popup, not an
        // off-screen card that the user has to scroll to the very top to notice.
        composeRule.runOnIdle { current = message }
        composeRule.onNodeWithText(message).assertIsDisplayed()
    }

    @Test
    fun dismissedSnackbarClearsItsSourceAndCanRetrigger() {
        val message = "Frequency source import failed"
        var current by mutableStateOf<String?>(null)
        val hostState = SnackbarHostState()

        composeRule.setContent {
            AnkiMinerTheme {
                MessageSnackbarEffect(
                    message = current,
                    hostState = hostState,
                    onDismiss = { current = null },
                )
                Scaffold(snackbarHost = { SnackbarHost(hostState) }) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {}
                }
            }
        }

        composeRule.runOnIdle { current = message }
        composeRule.onNodeWithText(message).assertIsDisplayed()

        // Material owns timeout duration. Exercise this seam's contract through an explicit
        // dismissal instead of coupling the test to Material's virtual clock and accessibility
        // timeout policy.
        composeRule.runOnIdle {
            checkNotNull(hostState.currentSnackbarData).dismiss()
        }
        composeRule.runOnIdle { assertNull(current) }
        composeRule.onNodeWithText(message).assertDoesNotExist()

        // Clearing the source permits the same later failure to start a new snackbar.
        composeRule.runOnIdle { current = message }
        composeRule.onNodeWithText(message).assertIsDisplayed()
    }
}
