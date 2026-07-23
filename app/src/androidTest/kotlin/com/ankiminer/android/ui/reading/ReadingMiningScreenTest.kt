package com.ankiminer.android.ui.reading

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.ankiminer.android.media.SafDocument
import com.ankiminer.android.mining.CurationCandidate
import com.ankiminer.android.mining.CurationPage
import com.ankiminer.android.mining.CurationRequest
import com.ankiminer.android.mining.CurationSentence
import com.ankiminer.android.mining.MiningFailure
import com.ankiminer.android.mining.MiningRunState
import com.ankiminer.android.mining.ProcessingResult
import com.ankiminer.android.ui.mining.MINING_FAILURE_TEST_TAG
import com.ankiminer.android.ui.theme.AnkiMinerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ReadingMiningScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun validReadingSourceCanBeSelectedAndStarted() {
        var pickedSource = false
        var started = false

        setScreen(
            state =
                ReadingMiningUiState(
                    source = ReadingDocumentSlotState(document("source", "book.epub")),
                    sourceKind = ReadingSourceKindUi.EPUB,
                ),
            onPickSource = { pickedSource = true },
            onStart = { started = true },
        )

        composeRule.onNodeWithTag(ReadingMiningTestTags.PICK_SOURCE).performClick()
        composeRule
            .onNodeWithTag(ReadingMiningTestTags.CONTENT)
            .performScrollToNode(hasTestTag(ReadingMiningTestTags.START))
        composeRule.onNodeWithTag(ReadingMiningTestTags.START).assertIsEnabled().performClick()

        composeRule.runOnIdle {
            assertTrue(pickedSource)
            assertTrue(started)
        }
    }

    @Test
    fun resolvingReadingSourceShowsSafeValidatedFilename() {
        setScreen(
            state =
                ReadingMiningUiState(
                    source =
                        ReadingDocumentSlotState(
                            document("source", "novel.epub"),
                            isResolving = true,
                        ),
                ),
        )

        composeRule.onNodeWithText("Reading document: novel.epub…").assertExists()
    }

    @Test
    fun resolvingReadingSourceUsesGenericCopyWithoutMetadata() {
        setScreen(
            state =
                ReadingMiningUiState(
                    source = ReadingDocumentSlotState(isResolving = true),
                ),
        )

        composeRule.onNodeWithText("Reading document…").assertExists()
    }

    @Test
    fun mokuroArchiveRequiresAValidSameStemArchive() {
        val mismatchMessage =
            "The image archive and .mokuro sidecar must have the same filename " +
                "before their extensions."
        var pickedArchive = false
        var state by
            mutableStateOf(
                ReadingMiningUiState(
                    source = ReadingDocumentSlotState(document("source", "volume-01.mokuro")),
                    archive = ReadingDocumentSlotState(document("archive", "volume-01.cbz")),
                    sourceKind = ReadingSourceKindUi.MOKURO,
                ),
            )
        composeRule.setContent {
            AnkiMinerTheme {
                ScreenUnderTest(
                    state = state,
                    onPickArchive = { pickedArchive = true },
                )
            }
        }

        composeRule
            .onNodeWithTag(ReadingMiningTestTags.CONTENT)
            .performScrollToNode(hasTestTag(ReadingMiningTestTags.PICK_ARCHIVE))
        composeRule.onNodeWithTag(ReadingMiningTestTags.PICK_ARCHIVE).performClick()
        composeRule
            .onNodeWithTag(ReadingMiningTestTags.CONTENT)
            .performScrollToNode(hasTestTag(ReadingMiningTestTags.START))
        composeRule.onNodeWithTag(ReadingMiningTestTags.START).assertIsEnabled()
        composeRule.runOnIdle { assertTrue(pickedArchive) }

        composeRule.runOnIdle {
            state =
                state.copy(
                    archive =
                        ReadingDocumentSlotState(
                            document("archive", "different-volume.zip"),
                            error = ReadingDocumentSelectionError.ARCHIVE_NAME,
                        ),
                )
        }
        composeRule
            .onNodeWithTag(ReadingMiningTestTags.CONTENT)
            .performScrollToNode(hasText(mismatchMessage))
        composeRule.onNodeWithText(mismatchMessage).assertExists()
        composeRule
            .onNodeWithTag(ReadingMiningTestTags.CONTENT)
            .performScrollToNode(hasTestTag(ReadingMiningTestTags.START))
        composeRule.onNodeWithTag(ReadingMiningTestTags.START).assertIsNotEnabled()
    }

    @Test
    fun finalPagedCurationExplainsSavedPagesAndFinalAction() {
        val request = request(page = CurationPage(1, 2, 2, 4))
        setScreen(
            state =
                ReadingMiningUiState(
                    runState = MiningRunState.Curating(request),
                    curation = curationState(request),
                ),
        )

        composeRule.onNodeWithText("Page 2 of 2 · items 3–4 of 4").assertExists()
        composeRule
            .onNodeWithText("Selections from earlier pages are already saved.")
            .assertExists()
        composeRule.onNodeWithText("Finish (2)").assertIsDisplayed()
    }

    @Test
    fun terminalResultUsesRetainedNamesWithoutExposingEnginePaths() {
        val rawSourcePath = "/data/user/0/com.ankiminer.android/cache/run/private.mokuro"
        val rawArchivePath = "/proc/self/fd/57"
        val result =
            result().copy(
                videoFile = rawArchivePath,
                subtitleFile = rawSourcePath,
            )
        setScreen(
            state =
                ReadingMiningUiState(
                    source = ReadingDocumentSlotState(document("source", "Retained source.mokuro")),
                    archive = ReadingDocumentSlotState(document("archive", "Retained source.cbz")),
                    sourceKind = ReadingSourceKindUi.MOKURO,
                    runState = MiningRunState.Success("run", result),
                ),
        )

        composeRule
            .onNodeWithTag(ReadingMiningTestTags.CONTENT)
            .performScrollToNode(hasTestTag(ReadingMiningTestTags.RESULT))
        composeRule.onNodeWithText("Reading source: Retained source.mokuro").assertExists()
        composeRule.onNodeWithText("Mokuro image archive: Retained source.cbz").assertExists()
        composeRule.onNodeWithText(rawSourcePath, substring = true).assertDoesNotExist()
        composeRule.onNodeWithText(rawArchivePath, substring = true).assertDoesNotExist()
    }

    @Test
    fun hugeProcessingResultIsCappedBySharedSummary() {
        val result = hugeResult()
        setScreen(
            state =
                ReadingMiningUiState(
                    runState = MiningRunState.Success("run", result),
                ),
        )

        composeRule
            .onNodeWithTag(ReadingMiningTestTags.CONTENT)
            .performScrollToNode(hasTestTag(ReadingMiningTestTags.RESULT))
        composeRule
            .onNodeWithText("• error-1")
            .assertExists()
        composeRule.onNodeWithText("• error-3").assertExists()
        composeRule.onNodeWithText("• error-4").assertDoesNotExist()

        composeRule.onNodeWithText("Details").performClick()
        composeRule
            .onNodeWithTag(ReadingMiningTestTags.CONTENT)
            .performScrollToNode(hasText("• error-50"))
        composeRule.onNodeWithText("• error-50").assertExists()
        composeRule.onNodeWithText("• error-51").assertDoesNotExist()
        composeRule
            .onNodeWithTag(ReadingMiningTestTags.CONTENT)
            .performScrollToNode(hasText("+75 more"))
        composeRule.onNodeWithText("+75 more").assertExists()
    }

    @Test
    fun pendingReadingPageSubmissionKeepsCancelEnabled() {
        val request = request(CurationPage(0, 2, 0, 3))
        setScreen(
            state =
                ReadingMiningUiState(
                    runState = MiningRunState.Curating(request, pageSubmissionPending = true),
                    curationPending = true,
                    curation = curationState(request),
                ),
        )

        composeRule.onNodeWithTag(ReadingMiningTestTags.CANCEL).assertIsEnabled()
    }

    @Test
    fun selectedReadingCurationRequiresConfirmationBeforeCancellation() {
        val request = request(CurationPage(0, 2, 0, 4))
        var cancelled = false

        composeRule.setContent {
            AnkiMinerTheme {
                ReadingMiningScreen(
                    state =
                        ReadingMiningUiState(
                            runState = MiningRunState.Curating(request),
                            curation = curationState(request),
                        ),
                    onPickSource = {},
                    onPickArchive = {},
                    onClearSource = {},
                    onClearArchive = {},
                    onSeriesNameChanged = {},
                    onDismissDocumentError = {},
                    onDismissCommandError = {},
                    onStart = {},
                    onToggleCandidate = {},
                    onSelectAllCandidates = {},
                    onSelectSentence = { _, _ -> },
                    onConfirmCuration = {},
                    onCancel = { cancelled = true },
                    onRetry = {},
                    onReset = {},
                )
            }
        }

        composeRule.onNodeWithTag(ReadingMiningTestTags.CANCEL).performClick()
        composeRule.runOnIdle { assertEquals(false, cancelled) }
        composeRule.onNodeWithText("Cancel run").performClick()
        composeRule.runOnIdle { assertEquals(true, cancelled) }
    }

    @Test
    fun terminalReadingFailureSuppressesCommandError() {
        setScreen(
            state =
                ReadingMiningUiState(
                    source = ReadingDocumentSlotState(document("source", "book.epub")),
                    sourceKind = ReadingSourceKindUi.EPUB,
                    runState =
                        MiningRunState.Failed(
                            runId = "run",
                            failure = MiningFailure("Private protocol detail", retryable = true),
                            result = null,
                        ),
                    commandError = ReadingMiningCommandError.START,
                ),
        )

        composeRule.onAllNodesWithTag(MINING_FAILURE_TEST_TAG).assertCountEquals(1)
        composeRule.onNodeWithText("Private protocol detail").assertDoesNotExist()
        composeRule.onNodeWithText("Details").performClick()
        composeRule.onNodeWithText("Private protocol detail").assertExists()
    }

    @Test
    fun readingFailureTransitionResetsDeepCurationScroll() {
        val candidates =
            (0 until 100).map { index ->
                candidate(
                    id = "candidate-$index",
                    form = "語彙$index",
                    sentenceId = "sentence-$index",
                    text = "Sentence $index",
                )
            }
        val request =
            CurationRequest(
                runId = "run",
                requestId = "reading-scroll",
                candidates = candidates,
            )
        lateinit var listState: LazyListState
        var state by
            mutableStateOf(
                ReadingMiningUiState(
                    runState = MiningRunState.Curating(request),
                    curation = curationState(request),
                ),
            )
        composeRule.setContent {
            AnkiMinerTheme {
                listState = rememberLazyListState()
                ScreenUnderTest(state = state, listState = listState)
            }
        }

        composeRule
            .onNodeWithTag(ReadingMiningTestTags.CONTENT)
            .performScrollToNode(
                hasTestTag(ReadingMiningTestTags.candidate(candidates.last().candidateId)),
            )
        composeRule.runOnIdle {
            state =
                state.copy(
                    runState =
                        MiningRunState.Failed(
                            runId = "run",
                            failure = MiningFailure("details", retryable = false),
                            result = null,
                        ),
                    curation = null,
                )
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle { assertEquals(0, listState.firstVisibleItemIndex) }
        composeRule
            .onNodeWithText("This mining run stopped before it could finish.")
            .assertIsDisplayed()
    }

    private fun setScreen(
        state: ReadingMiningUiState,
        onPickSource: () -> Unit = {},
        onStart: () -> Unit = {},
    ) {
        composeRule.setContent {
            AnkiMinerTheme {
                ScreenUnderTest(
                    state = state,
                    onPickSource = onPickSource,
                    onStart = onStart,
                )
            }
        }
    }

    @Suppress("LongParameterList")
    @androidx.compose.runtime.Composable
    private fun ScreenUnderTest(
        state: ReadingMiningUiState,
        onPickSource: () -> Unit = {},
        onPickArchive: () -> Unit = {},
        onStart: () -> Unit = {},
        listState: LazyListState = rememberLazyListState(),
    ) {
        ReadingMiningScreen(
            state = state,
            onPickSource = onPickSource,
            onPickArchive = onPickArchive,
            onClearSource = {},
            onClearArchive = {},
            onSeriesNameChanged = {},
            onDismissDocumentError = {},
            onDismissCommandError = {},
            onStart = onStart,
            onToggleCandidate = {},
            onSelectAllCandidates = {},
            onSelectSentence = { _, _ -> },
            onConfirmCuration = {},
            onCancel = {},
            onRetry = {},
            onReset = {},
            listState = listState,
        )
    }

    private fun request(page: CurationPage): CurationRequest =
        CurationRequest(
            runId = "run",
            requestId = "request",
            candidates =
                listOf(
                    candidate("candidate-1", "食べる", "sentence-1", "魚を食べる。"),
                    candidate("candidate-2", "懐かしい", "sentence-2", "懐かしい歌だ。"),
                ),
            page = page,
        )

    private fun curationState(
        request: CurationRequest,
        selectedCandidateIds: Set<String> =
            request.candidates.mapTo(linkedSetOf(), CurationCandidate::candidateId),
        focusedCandidateId: String? = selectedCandidateIds.firstOrNull(),
        previousPageSelectedCount: Int = 0,
    ): ReadingCurationUiState =
        ReadingCurationUiState(
            runId = request.runId,
            requestId = request.requestId,
            candidates = request.candidates,
            selectedCandidateIds = selectedCandidateIds,
            sentenceIds =
                request.candidates.associate {
                    it.candidateId to it.defaultSentenceId
                },
            focusedCandidateId = focusedCandidateId,
            previousPageSelectedCount = previousPageSelectedCount,
            page = request.page,
        )

    private fun candidate(
        id: String,
        form: String,
        sentenceId: String,
        text: String,
    ): CurationCandidate =
        CurationCandidate(
            candidateId = id,
            minedForm = form,
            surface = form,
            lemma = form,
            reading = form,
            expressionReading = form,
            partOfSpeech = null,
            frequencyRank = null,
            occurrenceCount = 1,
            defaultSentenceId = sentenceId,
            sentences =
                listOf(
                    CurationSentence(
                        sentenceId = sentenceId,
                        sentence = text,
                        sentenceFurigana = text,
                        sentenceReading = text,
                        startTime = 0.0,
                        endTime = 0.0,
                        duration = 0.0,
                    ),
                ),
        )

    private fun document(
        id: String,
        displayName: String,
    ): SafDocument =
        SafDocument(
            uri = "content://test/$id",
            displayName = displayName,
            mimeType = null,
            sizeBytes = null,
        )

    private fun result(): ProcessingResult =
        ProcessingResult(
            totalWordsFound = 12,
            newWordsFound = 2,
            cardsCreated = 2,
            errors = emptyList(),
            elapsedTime = 2.5,
            comprehensionPercentage = 75.0,
            cardIds = listOf(10, 11),
            videoFile = "archive.cbz",
            subtitleFile = "source.mokuro",
            minedForms = listOf("食べる", "懐かしい"),
        )

    private fun hugeResult(): ProcessingResult =
        result().copy(
            minedForms = (1..250).map { "form-$it" },
            cardIds = (1L..250L).toList(),
            errors = (1..125).map { "error-$it" },
        )
}
