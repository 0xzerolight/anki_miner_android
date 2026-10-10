package com.ankiminer.android.ui.mining

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import com.ankiminer.android.mining.AnkiWriteState
import com.ankiminer.android.mining.NotMinedGroup
import com.ankiminer.android.mining.NotMinedReason
import com.ankiminer.android.mining.ProcessingResult
import com.ankiminer.android.ui.theme.AnkiMinerTheme
import org.junit.Rule
import org.junit.Test

class NotMinedSectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun aTenThousandWordReportRendersCappedAndTheSearchFindsAWordPastTheCap() {
        val known = (1..10_000).map { "word-%05d".format(it) }
        val result =
            ProcessingResult(
                totalWordsFound = 10_003,
                newWordsFound = 2,
                cardsCreated = 1,
                errors = emptyList(),
                elapsedTime = 2.5,
                comprehensionPercentage = 99.9,
                cardIds = listOf(10),
                videoFile = "episode.mkv",
                subtitleFile = "episode.srt",
                minedForms = listOf("食べる"),
                ankiWriteState = AnkiWriteState.NOTE_WRITE_CONFIRMED,
                failureIsTransient = false,
                notMined =
                    listOf(
                        NotMinedGroup(NotMinedReason.KNOWN, known),
                        NotMinedGroup(NotMinedReason.NO_DEFINITION, listOf("𠮟る")),
                    ),
                minedFormsLanguage = "ja",
            )
        composeRule.setContent {
            AnkiMinerTheme {
                LazyColumn(Modifier.testTag("results")) {
                    miningResultItems(
                        headline = MiningResultHeadline.NotesAdded(1, null),
                        result = result,
                        failed = false,
                        detailsExpanded = true,
                        testTag = "summary",
                        keyPrefix = "result",
                        onToggleDetails = {},
                    )
                }
            }
        }

        composeRule.onNodeWithTag("results").performScrollToNode(hasTestTag(NOT_MINED_TEST_TAG))
        composeRule.onNodeWithText("Not mined: 10001 words").assertExists()
        val capped = known.take(MAX_RESULT_SUMMARY_ITEMS).joinToString()
        composeRule
            .onNodeWithTag("${NOT_MINED_TEST_TAG}_known")
            .assertTextEquals("Already known (10000): $capped, +9900 more")
        composeRule
            .onNodeWithTag("${NOT_MINED_TEST_TAG}_no_definition")
            .assertTextEquals("No dictionary entry — Settings → Resources (1): 𠮟る")

        composeRule.onNodeWithTag(NOT_MINED_SEARCH_TEST_TAG).performTextInput("WORD-09999")

        composeRule.onNodeWithTag("${NOT_MINED_TEST_TAG}_known").assertTextEquals("Already known (1): word-09999")
        composeRule.onNodeWithTag("${NOT_MINED_TEST_TAG}_no_definition").assertDoesNotExist()
    }
}
