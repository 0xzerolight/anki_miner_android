package com.ankiminer.android.ui.attribution

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
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
                NoticesScreen(modifier = Modifier.testTag(NOTICES_CONTENT_TEST_TAG))
            }
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule
                .onAllNodesWithText("NOTICE.md", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        assertNoticeHeading("Bundled notice documents")
        assertNoticeHeading("NOTICE.md")
        assertNoticeHeading("Third-party notices")
        composeRule.onNodeWithText("# Third-party notices").assertDoesNotExist()
        composeRule.onNodeWithText("```", substring = true).assertDoesNotExist()
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
                AttributionScreen(
                    onOpenNotices = { noticesOpened += 1 },
                    modifier = Modifier.testTag(ATTRIBUTION_CONTENT_TEST_TAG),
                )
            }
        }

        listOf(
            "UniDic tokenizer data",
            "unidic-lite — MIT",
            "UniDic 2.1.2 — BSD 3-Clause",
            "App icon",
            "Installed dictionaries",
            "Jisho network privacy notice",
            "Privacy and data handling",
            "Source and third-party notices",
        ).forEach(::assertAttributionHeading)
        composeRule
            .onNodeWithTag(ATTRIBUTION_CONTENT_TEST_TAG)
            .performScrollToNode(hasText("View third-party notices"))
        composeRule
            .onAllNodesWithText("View third-party notices")
            .onFirst()
            .performClick()
        composeRule.runOnIdle { assertEquals(1, noticesOpened) }
    }

    private fun assertNoticeHeading(text: String) {
        composeRule
            .onNodeWithTag(NOTICES_CONTENT_TEST_TAG)
            .performScrollToNode(hasText(text))
        composeRule
            .onNodeWithText(text, useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
    }

    private fun assertAttributionHeading(text: String) {
        composeRule
            .onNodeWithTag(ATTRIBUTION_CONTENT_TEST_TAG)
            .performScrollToNode(hasText(text))
        composeRule
            .onNodeWithText(text, useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
    }

    private companion object {
        const val ATTRIBUTION_CONTENT_TEST_TAG = "attribution_content"
        const val NOTICES_CONTENT_TEST_TAG = "notices_content"
    }
}
