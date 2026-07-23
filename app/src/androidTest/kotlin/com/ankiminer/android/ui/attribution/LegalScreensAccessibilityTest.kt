package com.ankiminer.android.ui.attribution

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.ankiminer.android.ui.theme.AnkiMinerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LegalScreensAccessibilityTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun noticesExposeStructuredHeadingsWithoutMarkdownChrome() {
        composeRule.setContent {
            AnkiMinerTheme {
                NoticesScreen()
            }
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule
                .onAllNodes(
                    SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
                .isNotEmpty()
        }

        composeRule.onNodeWithText("# Third-party notices").assertDoesNotExist()
        composeRule.onNodeWithText("```", substring = true).assertDoesNotExist()
        assertTrue(
            composeRule
                .onAllNodes(
                    SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
                .size >= 3,
        )
        val longestTextNode =
            composeRule
                .onAllNodes(
                    SemanticsMatcher.keyIsDefined(SemanticsProperties.Text),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
                .flatMap { node -> node.config[SemanticsProperties.Text] }
                .maxOfOrNull { text -> text.length }
                ?: 0
        assertTrue(longestTextNode <= MAX_NOTICE_BLOCK_CHARS + 2)
    }

    @Test
    fun attributionSectionsAreHeadingNavigable() {
        var noticesOpened = 0
        composeRule.setContent {
            AnkiMinerTheme {
                AttributionScreen(onOpenNotices = { noticesOpened += 1 })
            }
        }

        assertTrue(
            composeRule
                .onAllNodes(
                    SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
                .size >= 5,
        )
        composeRule
            .onAllNodesWithText("View third-party notices")
            .onFirst()
            .performClick()
        composeRule.runOnIdle { assertEquals(1, noticesOpened) }
    }
}
