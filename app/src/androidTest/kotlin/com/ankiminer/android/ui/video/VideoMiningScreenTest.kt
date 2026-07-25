package com.ankiminer.android.ui.video

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
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
import androidx.compose.ui.test.performTextInput
import com.ankiminer.android.media.SafDocument
import com.ankiminer.android.mining.CurationCandidate
import com.ankiminer.android.mining.CurationPage
import com.ankiminer.android.mining.CurationRequest
import com.ankiminer.android.mining.CurationSentence
import com.ankiminer.android.mining.MiningFailure
import com.ankiminer.android.mining.MiningProgress
import com.ankiminer.android.mining.MiningRunState
import com.ankiminer.android.mining.ProcessingResult
import com.ankiminer.android.ui.mining.CURATION_SEARCH_TEST_TAG
import com.ankiminer.android.ui.mining.MINING_FAILURE_TEST_TAG
import com.ankiminer.android.ui.mining.MINING_PHASE_HEADING_TEST_TAG
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
        composeRule
            .onNodeWithTag(VideoMiningTestTags.CONTENT)
            .performScrollToNode(hasTestTag(VideoMiningTestTags.START))
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
        // START is asserted last: scrolling to it can dispose the slot actions above.
        composeRule
            .onNodeWithTag(VideoMiningTestTags.CONTENT)
            .performScrollToNode(hasTestTag(VideoMiningTestTags.START))
        composeRule.onNodeWithTag(VideoMiningTestTags.START).assertIsNotEnabled()
    }

    @Test
    fun resolvingVideoShowsSafeValidatedFilename() {
        setScreen(
            state =
                VideoMiningUiState(
                    video =
                        DocumentSlotState(
                            document("video", "episode.mkv"),
                            isResolving = true,
                        ),
                ),
        )

        composeRule.onNodeWithText("Reading video: episode.mkv…").assertExists()
    }

    @Test
    fun resolvingVideoUsesGenericCopyForUntrustedFilename() {
        setScreen(
            state =
                VideoMiningUiState(
                    video =
                        DocumentSlotState(
                            document("video", "/private/provider/episode.mkv"),
                            isResolving = true,
                        ),
                ),
        )

        composeRule.onNodeWithText("Reading video…").assertExists()
        composeRule.onNodeWithText("/private/provider", substring = true).assertDoesNotExist()
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
                curation = curationState(request),
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
    fun wholeCandidateHeaderTogglesAndDeselectionCollapsesSentences() {
        val request = request()
        val candidate = request.candidates.first()
        var state by
            mutableStateOf(
                VideoMiningUiState(
                    runState = MiningRunState.Curating(request),
                    curation = curationState(request),
                ),
            )
        composeRule.setContent {
            AnkiMinerTheme {
                ScreenUnderTest(
                    state = state,
                    onToggleCandidate = { candidateId ->
                        val curation = requireNotNull(state.curation)
                        state =
                            state.copy(
                                curation =
                                    curation.copy(
                                        selectedCandidateIds =
                                            curation.selectedCandidateIds - candidateId,
                                        focusedCandidateId = null,
                                    ),
                            )
                    },
                )
            }
        }
        val sentenceTag =
            VideoMiningTestTags.sentence(candidate.candidateId, candidate.defaultSentenceId)

        composeRule.onNodeWithTag(sentenceTag).assertExists()
        composeRule.onNodeWithTag(VideoMiningTestTags.candidate(candidate.candidateId)).performClick()

        composeRule.onNodeWithTag(sentenceTag).assertDoesNotExist()
        composeRule.onNodeWithText("1 of 2 selected").assertExists()
    }

    @Test
    fun zeroSelectionCancelsImmediatelyButSelectedCurationRequiresConfirmation() {
        val request = request()
        var cancelCount = 0
        var state by
            mutableStateOf(
                VideoMiningUiState(
                    runState = MiningRunState.Curating(request),
                    curation =
                        curationState(
                            request = request,
                            selectedCandidateIds = emptySet(),
                            focusedCandidateId = null,
                        ),
                ),
            )
        composeRule.setContent {
            AnkiMinerTheme {
                ScreenUnderTest(
                    state = state,
                    onCancel = { cancelCount += 1 },
                )
            }
        }

        composeRule.onNodeWithTag(VideoMiningTestTags.CANCEL).performClick()
        composeRule.runOnIdle { assertEquals(1, cancelCount) }

        composeRule.runOnIdle { state = state.copy(curation = curationState(request)) }
        composeRule.onNodeWithTag(VideoMiningTestTags.CANCEL).performClick()
        composeRule.onNodeWithText("Cancel mining?").assertIsDisplayed()
        composeRule.onNodeWithText("Keep mining").performClick()
        composeRule.runOnIdle { assertEquals(1, cancelCount) }

        composeRule.onNodeWithTag(VideoMiningTestTags.CANCEL).performClick()
        composeRule.onNodeWithText("Cancel run").performClick()
        composeRule.runOnIdle { assertEquals(2, cancelCount) }
    }

    @Test
    fun priorPageSelectionRequiresCancellationConfirmationWhenCurrentPageIsEmpty() {
        val request = request().copy(page = CurationPage(1, 2, 2, 4))
        var cancelled = false
        setScreen(
            state =
                VideoMiningUiState(
                    runState = MiningRunState.Curating(request),
                    curation =
                        curationState(
                            request = request,
                            selectedCandidateIds = emptySet(),
                            focusedCandidateId = null,
                            previousPageSelectedCount = 1,
                        ),
                ),
            onCancel = { cancelled = true },
        )

        composeRule.onNodeWithTag(VideoMiningTestTags.CANCEL).performClick()

        composeRule.onNodeWithText("Cancel mining?").assertIsDisplayed()
        composeRule.runOnIdle { assertFalse(cancelled) }
    }

    @Test
    fun pendingCancellationFreezesCurationChoicesAndConfirmation() {
        val request = request()
        setScreen(
            state =
                VideoMiningUiState(
                    runState = MiningRunState.Curating(request),
                    curation = curationState(request),
                    cancelPending = true,
                ),
        )

        composeRule.onNodeWithTag(VideoMiningTestTags.SELECT_ALL).assertIsNotEnabled()
        composeRule
            .onNodeWithTag(VideoMiningTestTags.candidate(request.candidates.first().candidateId))
            .assertIsNotEnabled()
        composeRule.onNodeWithTag(VideoMiningTestTags.CONFIRM_CURATION).assertIsNotEnabled()
        composeRule.onNodeWithText("Cancelling…").assertIsDisplayed()
    }

    @Test
    fun pendingPageSubmissionKeepsCancelEnabled() {
        val request = request()
        setScreen(
            state =
                VideoMiningUiState(
                    runState = MiningRunState.Curating(request, pageSubmissionPending = true),
                    curationPending = true,
                    curation = curationState(request),
                ),
        )

        composeRule.onNodeWithTag(VideoMiningTestTags.CANCEL).assertIsEnabled()
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
                    curation = curationState(request),
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
        composeRule.onNodeWithTag(VideoMiningTestTags.CONFIRM_CURATION).assertIsDisplayed()

        composeRule.runOnIdle {
            assertEquals(candidateId to sentences.last().sentenceId, selection)
        }
    }

    @Test
    fun searchFiltersCandidateHeadersWithoutComposingOffscreenCandidates() {
        val candidates =
            (0 until 100).map { index ->
                candidate(
                    id = "candidate-$index",
                    form = if (index == 99) "懐かしい" else "語彙$index",
                    sentences = listOf(sentence("sentence-$index", "Sentence $index")),
                ).copy(frequencyRank = index.toLong())
            }
        val request = CurationRequest("run", "request-search", candidates)
        setScreen(
            state =
                VideoMiningUiState(
                    runState = MiningRunState.Curating(request),
                    curation = curationState(request),
                ),
        )
        val tailTag = VideoMiningTestTags.candidate(candidates.last().candidateId)

        composeRule.onNodeWithTag(tailTag).assertDoesNotExist()
        composeRule.onNodeWithTag(CURATION_SEARCH_TEST_TAG).performTextInput("懐かしい")

        composeRule.onNodeWithTag(tailTag).assertIsDisplayed()
        composeRule
            .onNodeWithTag(VideoMiningTestTags.candidate(candidates.first().candidateId))
            .assertDoesNotExist()
    }

    @Test
    fun nonFinalCurationPageShowsPositionAndNextPageAction() {
        val request = request().copy(page = CurationPage(0, 2, 0, 4))
        setScreen(
            state =
                VideoMiningUiState(
                    runState = MiningRunState.Curating(request),
                    curation = curationState(request),
                ),
        )

        composeRule.onNodeWithText("Page 1 of 2 · items 1–2 of 4").assertExists()
        composeRule.onNodeWithText("Next (2)").assertExists()
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
        composeRule
            .onNodeWithTag(VideoMiningTestTags.CONTENT)
            .performScrollToNode(hasTestTag(VideoMiningTestTags.RESET))
        composeRule.onNodeWithTag(VideoMiningTestTags.RESET).performClick()
        composeRule.runOnIdle {
            assertTrue(retried)
            assertTrue(reset)
        }
    }

    @Test
    fun terminalFailureSuppressesMatchingCommandErrorAndKeepsDiagnosticsBehindDetails() {
        setScreen(
            state =
                VideoMiningUiState(
                    video = DocumentSlotState(document("video", "episode.mkv")),
                    subtitle = DocumentSlotState(document("subtitle", "episode.srt")),
                    runState =
                        MiningRunState.Failed(
                            runId = "run",
                            failure = MiningFailure("Protocol detail 37", retryable = true),
                            result = null,
                        ),
                    commandError = MiningCommandError.START,
                ),
        )

        composeRule.onAllNodesWithTag(MINING_FAILURE_TEST_TAG).assertCountEquals(1)
        composeRule.onNodeWithText("Protocol detail 37").assertDoesNotExist()
        composeRule.onNodeWithText("Details").performClick()
        composeRule.onNodeWithText("Protocol detail 37").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").assertExists()
        composeRule.onNodeWithText("Start over").assertExists()
    }

    @Test
    fun curationCommandFailureStaysVisibleInFixedFooterWhileListIsDeep() {
        val candidates =
            (0 until 100).map { index ->
                candidate(
                    id = "candidate-$index",
                    form = "語彙$index",
                    sentences = listOf(sentence("sentence-$index", "Sentence $index")),
                )
            }
        val request = CurationRequest("run", "request-deep", candidates)
        setScreen(
            state =
                VideoMiningUiState(
                    runState = MiningRunState.Curating(request),
                    curation = curationState(request),
                    commandError = MiningCommandError.CURATION,
                ),
        )

        composeRule
            .onNodeWithTag(VideoMiningTestTags.CONTENT)
            .performScrollToNode(
                hasTestTag(VideoMiningTestTags.candidate(candidates.last().candidateId)),
            )

        composeRule.onAllNodesWithTag(MINING_FAILURE_TEST_TAG).assertCountEquals(1)
        composeRule
            .onNodeWithText("Your vocabulary choices could not be submitted.")
            .assertIsDisplayed()
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
        composeRule.onNodeWithText("100 of 100 · 100%").assertExists()
    }

    @Test
    fun progressHasOneConciseLiveRegionAndOneContinuousCount() {
        setScreen(
            state =
                VideoMiningUiState(
                    runState =
                        MiningRunState.Running(
                            "run",
                            MiningProgress(
                                current = 47,
                                total = 100,
                                description = "Analyzing subtitles",
                            ),
                        ),
                ),
        )

        composeRule
            .onAllNodes(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion),
            ).assertCountEquals(1)
        composeRule.onNodeWithText("47 of 100 · 47%").assertExists()
        composeRule.onNodeWithText("Mining in progress").assertExists()
    }

    @Test
    fun curationExposesPaneFocusedHeadingAndOneSelectionAnnouncement() {
        val request = request()
        setScreen(
            state =
                VideoMiningUiState(
                    runState = MiningRunState.Curating(request),
                    curation = curationState(request),
                ),
        )

        composeRule
            .onAllNodes(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.PaneTitle,
                    "Word Curation",
                ),
                useUnmergedTree = true,
            ).assertCountEquals(1)
        composeRule
            .onNodeWithTag(MINING_PHASE_HEADING_TEST_TAG, useUnmergedTree = true)
            .assertIsFocused()
            .assert(
                SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit),
            )
        composeRule
            .onAllNodes(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Polite,
                ),
                useUnmergedTree = true,
            ).assertCountEquals(1)
    }

    @Test
    fun pageAndFailureTransitionsResetDeepScrollToTheHeading() {
        val candidates =
            (0 until 100).map { index ->
                candidate(
                    id = "candidate-$index",
                    form = "語彙$index",
                    sentences = listOf(sentence("sentence-$index", "Sentence $index")),
                )
            }
        val firstRequest =
            CurationRequest(
                runId = "run",
                requestId = "request-scroll",
                candidates = candidates,
                page = CurationPage(0, 2, 0, 200),
            )
        lateinit var listState: LazyListState
        var state by
            mutableStateOf(
                VideoMiningUiState(
                    runState = MiningRunState.Curating(firstRequest),
                    curation = curationState(firstRequest),
                ),
            )
        composeRule.setContent {
            AnkiMinerTheme {
                listState = rememberLazyListState()
                ScreenUnderTest(state = state, listState = listState)
            }
        }

        composeRule
            .onNodeWithTag(VideoMiningTestTags.CONTENT)
            .performScrollToNode(
                hasTestTag(VideoMiningTestTags.candidate(candidates.last().candidateId)),
            )
        val secondRequest =
            firstRequest.copy(page = CurationPage(1, 2, 100, 200))
        composeRule.runOnIdle {
            state =
                state.copy(
                    runState = MiningRunState.Curating(secondRequest),
                    curation = curationState(secondRequest),
                )
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(0, listState.firstVisibleItemIndex) }
        composeRule.onNodeWithText("Word Curation").assertIsDisplayed()

        composeRule
            .onNodeWithTag(VideoMiningTestTags.CONTENT)
            .performScrollToNode(
                hasTestTag(VideoMiningTestTags.candidate(candidates.last().candidateId)),
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

    @Test
    fun legacyProcessingResultNamesArePresentedAsAnkiNotes() {
        setScreen(
            state =
                VideoMiningUiState(
                    runState = MiningRunState.Success("run", result()),
                ),
        )

        composeRule.onNodeWithText("Created").assertExists()
        composeRule.onNodeWithText("Anki notes created: 2").assertDoesNotExist()
        composeRule.onNodeWithText("Anki note IDs: 10, 11").assertDoesNotExist()
        composeRule.onNodeWithText("Details").performClick()
        composeRule.onNodeWithText("Anki note IDs: 10, 11").assertExists()
        composeRule.onNodeWithText("Anki card IDs", substring = true).assertDoesNotExist()
    }

    @Test
    fun hugeProcessingResultIsCappedBySharedSummary() {
        val result = hugeResult()
        setScreen(
            state =
                VideoMiningUiState(
                    runState = MiningRunState.Success("run", result),
                ),
        )

        composeRule
            .onNodeWithTag(VideoMiningTestTags.CONTENT)
            .performScrollToNode(hasTestTag(VideoMiningTestTags.RESULT))
        composeRule
            .onNodeWithText("• error-1")
            .assertExists()
        composeRule.onNodeWithText("• error-3").assertExists()
        composeRule.onNodeWithText("• error-4").assertDoesNotExist()
        composeRule
            .onNodeWithText("Mined forms:", substring = true)
            .assertDoesNotExist()
        composeRule.onNodeWithText("Anki note IDs:", substring = true).assertDoesNotExist()

        composeRule.onNodeWithText("Details").performClick()
        composeRule
            .onNodeWithTag(VideoMiningTestTags.CONTENT)
            .performScrollToNode(hasText("• error-50"))
        composeRule.onNodeWithText("• error-50").assertExists()
        composeRule.onNodeWithText("• error-51").assertDoesNotExist()
        composeRule
            .onNodeWithTag(VideoMiningTestTags.CONTENT)
            .performScrollToNode(hasText("+75 more"))
        composeRule.onNodeWithText("+75 more").assertExists()
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
        onCancel: () -> Unit = {},
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
                    onCancel = onCancel,
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
        onToggleCandidate: (String) -> Unit = {},
        listState: LazyListState = rememberLazyListState(),
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
            onToggleCandidate = onToggleCandidate,
            onSelectAllCandidates = onSelectAllCandidates,
            onSelectSentence = onSelectSentence,
            onConfirmCuration = onConfirmCuration,
            onCancel = onCancel,
            onRetry = onRetry,
            onReset = onReset,
            modifier = Modifier.testTag(VideoMiningTestTags.SCREEN),
            listState = listState,
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

    private fun curationState(
        request: CurationRequest,
        selectedCandidateIds: Set<String> =
            request.candidates.mapTo(linkedSetOf(), CurationCandidate::candidateId),
        focusedCandidateId: String? = selectedCandidateIds.firstOrNull(),
        previousPageSelectedCount: Int = 0,
    ): CurationUiState =
        CurationUiState(
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

    private fun hugeResult(): ProcessingResult =
        result().copy(
            minedForms = (1..250).map { "form-$it" },
            cardIds = (1L..250L).toList(),
            errors = (1..125).map { "error-$it" },
        )
}
