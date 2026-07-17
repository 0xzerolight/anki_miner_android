package com.ankiminer.android.ui.video

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
import com.ankiminer.android.mining.MiningProgress
import com.ankiminer.android.mining.MiningRunState
import com.ankiminer.android.mining.ProcessingResult
import com.ankiminer.android.ui.theme.AnkiMinerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class VideoMiningScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun setupUsesExplicitVideoAndSubtitleActionsBeforeStart() {
        var pickedVideo = false
        var pickedSubtitle = false
        var started = false
        val state =
            VideoMiningUiState(
                video = DocumentSlotState(document("video", "episode.mkv")),
                subtitle = DocumentSlotState(document("subtitle", "episode.srt")),
            )

        setScreen(
            state = state,
            onPickVideo = { pickedVideo = true },
            onPickSubtitle = { pickedSubtitle = true },
            onStart = { started = true },
        )

        composeRule.onNodeWithTag(VideoMiningTestTags.PICK_VIDEO).performClick()
        composeRule.onNodeWithTag(VideoMiningTestTags.PICK_SUBTITLE).performClick()
        composeRule.onNodeWithTag(VideoMiningTestTags.START).assertIsEnabled().performClick()

        composeRule.runOnIdle {
            assertTrue(pickedVideo)
            assertTrue(pickedSubtitle)
            assertTrue(started)
        }
    }

    @Test
    fun pendingStartFreezesEverySelectedDocumentAction() {
        setScreen(
            state =
                VideoMiningUiState(
                    video = DocumentSlotState(document("video", "episode.mkv")),
                    subtitle = DocumentSlotState(document("subtitle", "episode.srt")),
                    startPending = true,
                ),
        )

        composeRule.onNodeWithTag(VideoMiningTestTags.PICK_VIDEO).assertIsNotEnabled()
        composeRule.onNodeWithTag(VideoMiningTestTags.CLEAR_VIDEO).assertIsNotEnabled()
        composeRule.onNodeWithTag(VideoMiningTestTags.PICK_SUBTITLE).assertIsNotEnabled()
        composeRule.onNodeWithTag(VideoMiningTestTags.CLEAR_SUBTITLE).assertIsNotEnabled()
        composeRule.onNodeWithTag(VideoMiningTestTags.START).assertIsNotEnabled()
    }

    @Test
    fun curationSupportsDeselectAllAlternateSentenceAndEmptyConfirmation() {
        val request = request()
        val alternate = request.candidates.first().sentences.last()
        var selectAllValue: Boolean? = null
        var sentenceSelection: Pair<String, String>? = null
        var confirmed = false
        val state =
            VideoMiningUiState(
                runState = MiningRunState.Curating(request),
                curation =
                    CurationUiState(
                        runId = request.runId,
                        requestId = request.requestId,
                        candidates =
                            request.candidates.map {
                                CurationCandidateUiState(
                                    candidate = it,
                                    selected = true,
                                    sentenceId = it.defaultSentenceId,
                                )
                            },
                    ),
            )

        setScreen(
            state = state,
            onSelectAllCandidates = { selectAllValue = it },
            onSelectSentence = { candidateId, sentenceId ->
                sentenceSelection = candidateId to sentenceId
            },
            onConfirmCuration = { confirmed = true },
        )

        composeRule.onNodeWithTag(VideoMiningTestTags.SELECT_ALL).performClick()
        composeRule
            .onNodeWithTag(VideoMiningTestTags.CONTENT)
            .performScrollToNode(
                hasTestTag(
                    VideoMiningTestTags.sentence(
                        request.candidates.first().candidateId,
                        alternate.sentenceId,
                    ),
                ),
            )
        composeRule
            .onNodeWithTag(
                VideoMiningTestTags.sentence(
                    request.candidates.first().candidateId,
                    alternate.sentenceId,
                ),
            ).performClick()
        composeRule
            .onNodeWithTag(VideoMiningTestTags.CONTENT)
            .performScrollToNode(hasTestTag(VideoMiningTestTags.CONFIRM_CURATION))
        composeRule.onNodeWithTag(VideoMiningTestTags.CONFIRM_CURATION).performClick()

        composeRule.runOnIdle {
            assertEquals(false, selectAllValue)
            assertEquals(
                request.candidates.first().candidateId to alternate.sentenceId,
                sentenceSelection,
            )
            assertTrue(confirmed)
        }
    }

    @Test
    fun pendingCancellationFreezesCurationChoicesAndConfirmation() {
        val request = request()
        setScreen(
            state =
                VideoMiningUiState(
                    runState = MiningRunState.Curating(request),
                    curation =
                        CurationUiState(
                            runId = request.runId,
                            requestId = request.requestId,
                            candidates =
                                request.candidates.map {
                                    CurationCandidateUiState(
                                        candidate = it,
                                        selected = true,
                                        sentenceId = it.defaultSentenceId,
                                    )
                                },
                        ),
                    cancelPending = true,
                ),
        )

        composeRule.onNodeWithTag(VideoMiningTestTags.SELECT_ALL).assertIsNotEnabled()
        composeRule
            .onNodeWithTag(
                VideoMiningTestTags.candidateToggle(request.candidates.first().candidateId),
            ).assertIsNotEnabled()
        composeRule
            .onNodeWithTag(VideoMiningTestTags.CONTENT)
            .performScrollToNode(hasTestTag(VideoMiningTestTags.CONFIRM_CURATION))
        composeRule.onNodeWithTag(VideoMiningTestTags.CONFIRM_CURATION).assertIsNotEnabled()
    }

    @Test
    fun longSentenceListIsVirtualizedAndTailSelectionKeepsCompositeIdentity() {
        val candidateId = "candidate-long"
        val sentences =
            (0 until 120).map { index ->
                sentence("sentence-$index", "Sentence number $index")
            }
        val candidate = candidate(candidateId, "長い", sentences)
        val request =
            CurationRequest(
                runId = "run",
                requestId = "request",
                candidates = listOf(candidate),
            )
        var selection: Pair<String, String>? = null
        setScreen(
            state =
                VideoMiningUiState(
                    runState = MiningRunState.Curating(request),
                    curation =
                        CurationUiState(
                            runId = request.runId,
                            requestId = request.requestId,
                            candidates =
                                listOf(
                                    CurationCandidateUiState(
                                        candidate = candidate,
                                        selected = true,
                                        sentenceId = candidate.defaultSentenceId,
                                    ),
                                ),
                        ),
                ),
            onSelectSentence = { selectedCandidateId, sentenceId ->
                selection = selectedCandidateId to sentenceId
            },
        )
        val tailTag = VideoMiningTestTags.sentence(candidateId, sentences.last().sentenceId)

        composeRule.onNodeWithTag(tailTag).assertDoesNotExist()
        composeRule
            .onNodeWithTag(VideoMiningTestTags.CONTENT)
            .performScrollToNode(hasTestTag(tailTag))
        composeRule.onNodeWithTag(tailTag).performClick()

        composeRule.runOnIdle {
            assertEquals(candidateId to sentences.last().sentenceId, selection)
        }
    }

    @Test
    fun nonFinalCurationPageShowsPositionAndNextPageAction() {
        val request = request().copy(page = CurationPage(0, 2, 0, 4))
        setScreen(
            state =
                VideoMiningUiState(
                    runState = MiningRunState.Curating(request),
                    curation =
                        CurationUiState(
                            runId = request.runId,
                            requestId = request.requestId,
                            candidates =
                                request.candidates.map {
                                    CurationCandidateUiState(
                                        candidate = it,
                                        selected = true,
                                        sentenceId = it.defaultSentenceId,
                                    )
                                },
                            page = request.page,
                        ),
                ),
        )

        composeRule.onNodeWithText("Page 1 of 2 · items 1–2 of 4").assertExists()
        composeRule
            .onNodeWithTag(VideoMiningTestTags.CONTENT)
            .performScrollToNode(hasTestTag(VideoMiningTestTags.CONFIRM_CURATION))
        composeRule.onNodeWithText("Continue to next page with 2 selected on this page").assertExists()
    }

    @Test
    fun progressAndEveryTerminalOutcomeExposeTheCorrectActions() {
        var cancelled = false
        var retried = false
        var reset = false
        var state by
            mutableStateOf(
                VideoMiningUiState(
                    runState = MiningRunState.Starting(runId = null, progress = null),
                ),
            )
        composeRule.setContent {
            AnkiMinerTheme {
                ScreenUnderTest(
                    state = state,
                    onCancel = { cancelled = true },
                    onRetry = { retried = true },
                    onReset = { reset = true },
                )
            }
        }

        composeRule.onNodeWithTag(VideoMiningTestTags.PROGRESS).assertExists()
        composeRule.onNodeWithTag(VideoMiningTestTags.CANCEL).assertDoesNotExist()

        composeRule.runOnIdle {
            state =
                state.copy(
                    runState =
                        MiningRunState.Running(
                            "run",
                            MiningProgress(current = 0, total = 0, description = "Working"),
                        ),
                )
        }
        composeRule.onNodeWithTag(VideoMiningTestTags.CANCEL).performClick()
        composeRule.runOnIdle { assertTrue(cancelled) }

        composeRule.runOnIdle {
            state = state.copy(runState = MiningRunState.Success("run", result()))
        }
        composeRule.onNodeWithTag(VideoMiningTestTags.RESULT).assertExists()

        composeRule.runOnIdle {
            state =
                state.copy(
                    runState = MiningRunState.Cancelled("run", result()),
                )
        }
        composeRule.onNodeWithTag(VideoMiningTestTags.RESULT).assertExists()

        composeRule.runOnIdle {
            state =
                state.copy(
                    video = DocumentSlotState(document("video", "episode.mkv")),
                    subtitle = DocumentSlotState(document("subtitle", "episode.srt")),
                    runState =
                        MiningRunState.Failed(
                            runId = "run",
                            failure = MiningFailure("Scripted failure", retryable = true),
                            result = result(),
                        ),
                )
        }
        composeRule
            .onNodeWithTag(VideoMiningTestTags.CONTENT)
            .performScrollToNode(hasTestTag(VideoMiningTestTags.RETRY))
        composeRule.onNodeWithTag(VideoMiningTestTags.RETRY).performClick()
        composeRule.onNodeWithTag(VideoMiningTestTags.RESET).performClick()
        composeRule.runOnIdle {
            assertTrue(retried)
            assertTrue(reset)
        }
    }

    @Test
    fun resultUsesRetainedNamesWithoutExposingEnginePaths() {
        val rawVideoPath = "/proc/self/fd/41"
        val rawSubtitlePath = "/data/user/0/com.ankiminer.android/cache/run/subtitle.srt"
        val result =
            result().copy(
                videoFile = rawVideoPath,
                subtitleFile = rawSubtitlePath,
            )
        val state =
            VideoMiningUiState(
                video = DocumentSlotState(document("video", "Retained video.mkv")),
                subtitle = DocumentSlotState(document("subtitle", "Retained subtitles.srt")),
                runState = MiningRunState.Success("run", result),
            )

        setScreen(state = state)

        composeRule.onNodeWithText("Video: Retained video.mkv").assertExists()
        composeRule.onNodeWithText("Subtitles: Retained subtitles.srt").assertExists()
        composeRule.onNodeWithText(rawVideoPath, substring = true).assertDoesNotExist()
        composeRule.onNodeWithText(rawSubtitlePath, substring = true).assertDoesNotExist()
    }

    @Test
    fun missingRetainedNamesNeverFallBackToPrivateEnginePaths() {
        val result =
            result().copy(
                videoFile = "/proc/self/fd/41",
                subtitleFile = "/data/user/0/com.ankiminer.android/cache/run/private-title.srt",
            )

        setScreen(
            state =
                VideoMiningUiState(
                    runState = MiningRunState.Success("run", result),
                ),
        )

        composeRule.onNodeWithText("Video: Unknown file").assertExists()
        composeRule.onNodeWithText("Subtitles: Unknown file").assertExists()
        composeRule.onNodeWithText("/proc/self/fd", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("private-title.srt", substring = true).assertDoesNotExist()
    }

    @Test
    fun blankTerminalProgressDescriptionIsOmittedWhileProgressRemainsVisible() {
        setScreen(
            state =
                VideoMiningUiState(
                    runState =
                        MiningRunState.Running(
                            "run",
                            MiningProgress(current = 100, total = 100, description = ""),
                        ),
                ),
        )

        composeRule.onNodeWithTag(VideoMiningTestTags.PROGRESS).assertExists()
        composeRule.onNodeWithText("100 of 100").assertExists()
    }

    @Test
    fun legacyProcessingResultNamesArePresentedAsAnkiNotes() {
        setScreen(
            state =
                VideoMiningUiState(
                    runState = MiningRunState.Success("run", result()),
                ),
        )

        composeRule.onNodeWithText("Anki notes created: 2").assertExists()
        composeRule.onNodeWithText("Anki note IDs: 10, 11").assertExists()
        composeRule.onNodeWithText("Anki card IDs", substring = true).assertDoesNotExist()
    }

    @Test
    fun cancelledRunWithCreatedNotesLabelsItsResultAsPartial() {
        setScreen(
            state =
                VideoMiningUiState(
                    runState = MiningRunState.Cancelled("run", result()),
                ),
        )

        composeRule
            .onNodeWithTag(VideoMiningTestTags.CONTENT)
            .performScrollToNode(hasText("Notes added before the run stopped"))
        composeRule.onNodeWithText("Notes added before the run stopped").assertExists()
    }

    private fun setScreen(
        state: VideoMiningUiState,
        onPickVideo: () -> Unit = {},
        onPickSubtitle: () -> Unit = {},
        onStart: () -> Unit = {},
        onSelectAllCandidates: (Boolean) -> Unit = {},
        onSelectSentence: (String, String) -> Unit = { _, _ -> },
        onConfirmCuration: () -> Unit = {},
    ) {
        composeRule.setContent {
            AnkiMinerTheme {
                ScreenUnderTest(
                    state = state,
                    onPickVideo = onPickVideo,
                    onPickSubtitle = onPickSubtitle,
                    onStart = onStart,
                    onSelectAllCandidates = onSelectAllCandidates,
                    onSelectSentence = onSelectSentence,
                    onConfirmCuration = onConfirmCuration,
                )
            }
        }
    }

    @Suppress("LongParameterList")
    @androidx.compose.runtime.Composable
    private fun ScreenUnderTest(
        state: VideoMiningUiState,
        onPickVideo: () -> Unit = {},
        onPickSubtitle: () -> Unit = {},
        onStart: () -> Unit = {},
        onSelectAllCandidates: (Boolean) -> Unit = {},
        onSelectSentence: (String, String) -> Unit = { _, _ -> },
        onConfirmCuration: () -> Unit = {},
        onCancel: () -> Unit = {},
        onRetry: () -> Unit = {},
        onReset: () -> Unit = {},
    ) {
        VideoMiningScreen(
            state = state,
            onPickVideo = onPickVideo,
            onPickSubtitle = onPickSubtitle,
            onClearVideo = {},
            onClearSubtitle = {},
            onDismissDocumentError = {},
            onDismissCommandError = {},
            onStart = onStart,
            onToggleCandidate = {},
            onSelectAllCandidates = onSelectAllCandidates,
            onSelectSentence = onSelectSentence,
            onConfirmCuration = onConfirmCuration,
            onCancel = onCancel,
            onRetry = onRetry,
            onReset = onReset,
            modifier = Modifier.testTag(VideoMiningTestTags.SCREEN),
        )
    }

    private fun request(): CurationRequest {
        val firstSentences =
            listOf(
                sentence("sentence-1", "魚を食べる。"),
                sentence("sentence-2", "朝ご飯を食べる。"),
            )
        val secondSentences = listOf(sentence("sentence-3", "懐かしい歌だ。"))
        return CurationRequest(
            runId = "run",
            requestId = "request",
            candidates =
                listOf(
                    candidate("candidate-1", "食べる", firstSentences),
                    candidate("candidate-2", "懐かしい", secondSentences),
                ),
        )
    }

    private fun candidate(
        id: String,
        form: String,
        sentences: List<CurationSentence>,
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
            defaultSentenceId = sentences.first().sentenceId,
            sentences = sentences,
        )

    private fun sentence(
        id: String,
        text: String,
    ): CurationSentence =
        CurationSentence(
            sentenceId = id,
            sentence = text,
            sentenceFurigana = text,
            sentenceReading = text,
            startTime = 0.0,
            endTime = 1.0,
            duration = 1.0,
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
            videoFile = "episode.mkv",
            subtitleFile = "episode.srt",
            minedForms = listOf("食べる", "懐かしい"),
        )
}
