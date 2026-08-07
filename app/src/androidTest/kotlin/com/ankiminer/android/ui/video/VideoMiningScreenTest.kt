package com.ankiminer.android.ui.video

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.ankiminer.android.R
import com.ankiminer.android.dictionary.CurationDefinition
import com.ankiminer.android.engine.DefinitionEntry
import com.ankiminer.android.engine.SubtitleCue
import com.ankiminer.android.media.SafDocument
import com.ankiminer.android.mining.AnkiWriteState
import com.ankiminer.android.mining.CurationCandidate
import com.ankiminer.android.mining.CurationPage
import com.ankiminer.android.mining.CurationRequest
import com.ankiminer.android.mining.CurationSentence
import com.ankiminer.android.mining.MiningFailure
import com.ankiminer.android.mining.MiningProgress
import com.ankiminer.android.mining.MiningRunState
import com.ankiminer.android.mining.ProcessingResult
import com.ankiminer.android.player.CurationPreviewPlayer
import com.ankiminer.android.player.FakeCurationPreviewPlayer
import com.ankiminer.android.ui.mining.CurationPlayerTestTags
import com.ankiminer.android.ui.mining.CURATION_BULK_TEST_TAG
import com.ankiminer.android.ui.mining.CURATION_FILTER_TEST_TAG
import com.ankiminer.android.ui.mining.CURATION_SEARCH_TEST_TAG
import com.ankiminer.android.ui.mining.CURATION_SORT_TEST_TAG
import com.ankiminer.android.ui.mining.CURATION_TOOLS_TOGGLE_TEST_TAG
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
    fun timingTestRequiresBothSourcesIdleRunAndValidOffset() {
        var opened = false
        setScreen(
            state =
                VideoMiningUiState(
                    video = DocumentSlotState(document("video", "episode.mkv")),
                    subtitle = DocumentSlotState(document("subtitle", "episode.srt")),
                ),
            onTestTiming = { opened = true },
        )

        composeRule
            .onNodeWithTag(VideoMiningTestTags.CONTENT)
            .performScrollToNode(hasTestTag(VideoMiningTestTags.TEST_TIMING))
        composeRule.onNodeWithTag(VideoMiningTestTags.TEST_TIMING).assertIsEnabled().performClick()

        composeRule.runOnIdle { assertTrue(opened) }
    }

    @Test
    fun invalidOffsetDisablesTimingTest() {
        setScreen(
            state =
                VideoMiningUiState(
                    video = DocumentSlotState(document("video", "episode.mkv")),
                    subtitle = DocumentSlotState(document("subtitle", "episode.srt")),
                    subtitleOffsetDraft = "invalid",
                    subtitleOffsetDraftInvalid = true,
                ),
        )
        composeRule
            .onNodeWithTag(VideoMiningTestTags.CONTENT)
            .performScrollToNode(hasTestTag(VideoMiningTestTags.TEST_TIMING))
        composeRule.onNodeWithTag(VideoMiningTestTags.TEST_TIMING).assertIsNotEnabled()
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
        var bulkChange: Pair<List<String>, Boolean>? = null
        var sentenceSelection: Pair<String, String>? = null
        var confirmed = false
        val state =
            VideoMiningUiState(
                runState = MiningRunState.Curating(request),
                curation = curationState(request),
            )

        setScreen(
            state = state,
            onSetSelectionForVisible = { ids, selected -> bulkChange = ids to selected },
            onSelectSentence = { candidateId, sentenceId ->
                sentenceSelection = candidateId to sentenceId
            },
            onConfirmCuration = { confirmed = true },
        )

        composeRule.onNodeWithTag(CURATION_BULK_TEST_TAG).performClick()
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
            // Scoped to the visible projection, not silently to the whole page. Compared as a set:
            // the projection is ordered by the active sort, which is not part of this contract.
            assertEquals(
                request.candidates.mapTo(mutableSetOf()) { it.candidateId },
                bulkChange?.first?.toSet(),
            )
            assertEquals(false, bulkChange?.second)
            assertEquals(
                request.candidates.first().candidateId to alternate.sentenceId,
                sentenceSelection,
            )
            assertTrue(confirmed)
        }
    }

    @Test
    fun curationPlayerBindsBeforeSeekingAndReleasesWhenThePlayerStateDisappears() {
        val request = request()
        val fake = FakeCurationPreviewPlayer()
        val videoPath = "/cache/episode.mkv"
        var state by
            mutableStateOf(
                VideoMiningUiState(
                    runState = MiningRunState.Curating(request),
                    curation =
                        curationState(request).copy(
                            player = CurationPlayerUiState(videoPath, emptyList(), false),
                        ),
                ),
            )
        composeRule.setContent {
            AnkiMinerTheme {
                ScreenUnderTest(
                    state = state,
                    playerFactory = { fake },
                )
            }
        }

        composeRule.onNodeWithTag(CurationPlayerTestTags.SURFACE).assertIsDisplayed()
        // The initial focus seek is debounced on the real clock; waitForIdle does not wait it out.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            fake.events.any { it.startsWith("seekTo:") }
        }
        composeRule.runOnIdle {
            assertEquals(1, fake.boundUris.size)
            assertEquals(videoPath, fake.boundUris.single().path)
            val bindIndex = fake.events.indexOfFirst { it.startsWith("bind:") }
            val seekIndex = fake.events.indexOfFirst { it.startsWith("seekTo:") }
            assertTrue(bindIndex >= 0)
            assertTrue(seekIndex > bindIndex)
            state =
                state.copy(
                    curation = requireNotNull(state.curation).copy(player = null),
                )
        }

        composeRule.onNodeWithTag(CurationPlayerTestTags.SURFACE).assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(1, fake.releaseCount) }
    }

    @Test
    fun focusAndSentenceSelectionSeekThroughTheSamePlayer() {
        val base = request()
        val first =
            base.candidates.first().copy(
                sentences =
                    base.candidates.first().sentences.mapIndexed { index, sentence ->
                        sentence.copy(startTime = if (index == 0) 1.5 else 4.5)
                    },
            )
        val second =
            base.candidates.last().copy(
                sentences =
                    base.candidates.last().sentences.map { sentence ->
                        sentence.copy(startTime = 8.25)
                    },
            )
        val request = base.copy(candidates = listOf(first, second))
        val fake = FakeCurationPreviewPlayer()
        var state by
            mutableStateOf(
                VideoMiningUiState(
                    runState = MiningRunState.Curating(request),
                    curation =
                        curationState(request).copy(
                            player =
                                CurationPlayerUiState(
                                    "/cache/episode.mkv",
                                    emptyList(),
                                    false,
                                ),
                        ),
                ),
            )
        composeRule.setContent {
            AnkiMinerTheme {
                ScreenUnderTest(
                    state = state,
                    onFocusCandidate = { candidateId ->
                        state =
                            state.copy(
                                curation =
                                    requireNotNull(state.curation).copy(
                                        focusedCandidateId = candidateId,
                                    ),
                            )
                    },
                    onSelectSentence = { candidateId, sentenceId ->
                        val curation = requireNotNull(state.curation)
                        state =
                            state.copy(
                                curation =
                                    curation.copy(
                                        sentenceIds = curation.sentenceIds + (candidateId to sentenceId),
                                    ),
                            )
                    },
                    playerFactory = { fake },
                )
            }
        }
        // The seek is debounced, so idle is not enough: let the seek for the initially focused
        // candidate land before clearing, or it fires mid-assertion below and is read as this
        // click's seek.
        composeRule.waitUntil(timeoutMillis = 5_000) { fake.seekToCalls.isNotEmpty() }
        composeRule.runOnIdle { fake.seekToCalls.clear() }

        composeRule
            .onNodeWithTag(VideoMiningTestTags.CONTENT)
            .performScrollToNode(hasTestTag(VideoMiningTestTags.candidate(second.candidateId)))
        composeRule.onNodeWithTag(VideoMiningTestTags.candidate(second.candidateId)).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { fake.seekToCalls.isNotEmpty() }
        composeRule.runOnIdle {
            assertEquals(second.sentences.single().startTime, fake.seekToCalls.last(), 0.0)
            fake.seekToCalls.clear()
        }

        composeRule
            .onNodeWithTag(VideoMiningTestTags.CONTENT)
            .performScrollToNode(hasTestTag(VideoMiningTestTags.candidate(first.candidateId)))
        composeRule.onNodeWithTag(VideoMiningTestTags.candidate(first.candidateId)).performClick()
        // Let the focus seek land before clearing, or it would fire mid-assertion below.
        composeRule.waitUntil(timeoutMillis = 5_000) { fake.seekToCalls.isNotEmpty() }
        composeRule.runOnIdle { fake.seekToCalls.clear() }
        val alternate = first.sentences.last()
        composeRule
            .onNodeWithTag(VideoMiningTestTags.CONTENT)
            .performScrollToNode(
                hasTestTag(VideoMiningTestTags.sentence(first.candidateId, alternate.sentenceId)),
            )
        composeRule
            .onNodeWithTag(VideoMiningTestTags.sentence(first.candidateId, alternate.sentenceId))
            .performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { fake.seekToCalls.isNotEmpty() }

        composeRule.runOnIdle {
            assertEquals(listOf(alternate.startTime), fake.seekToCalls)
            assertEquals(1, fake.boundUris.size)
        }
    }

    @Test
    fun curationPlayerCanCollapseAndTogglePlayback() {
        val request = request()
        val fake = FakeCurationPreviewPlayer()
        setScreen(
            state =
                VideoMiningUiState(
                    runState = MiningRunState.Curating(request),
                    curation =
                        curationState(request).copy(
                            player =
                                CurationPlayerUiState(
                                    "/cache/episode.mkv",
                                    emptyList(),
                                    false,
                                ),
                        ),
                ),
            playerFactory = { fake },
        )

        composeRule.onNodeWithTag(CurationPlayerTestTags.PLAY_PAUSE).performClick()
        composeRule.runOnIdle {
            assertEquals(1, fake.togglePlayPauseCount)
            assertTrue(fake.isPlaying.value)
        }
        composeRule.onNodeWithTag(CurationPlayerTestTags.PLAY_PAUSE).performClick()
        composeRule.runOnIdle { assertFalse(fake.isPlaying.value) }

        composeRule.onNodeWithTag(CurationPlayerTestTags.COLLAPSE).performClick()
        composeRule.onNodeWithTag(CurationPlayerTestTags.SURFACE).assertDoesNotExist()
        composeRule.onNodeWithTag(CurationPlayerTestTags.PLAY_PAUSE).assertDoesNotExist()
        composeRule.onNodeWithTag(CurationPlayerTestTags.COLLAPSE).performClick()
        composeRule.onNodeWithTag(CurationPlayerTestTags.SURFACE).assertIsDisplayed()
    }

    @Test
    fun unavailableCuesShowANoticeWithoutDisablingPlayerControls() {
        val request = request()
        val fake = FakeCurationPreviewPlayer()
        setScreen(
            state =
                VideoMiningUiState(
                    runState = MiningRunState.Curating(request),
                    curation =
                        curationState(request).copy(
                            player =
                                CurationPlayerUiState(
                                    videoPath = "/cache/episode.mkv",
                                    cues = listOf(SubtitleCue(0.0, 1.0, "unused")),
                                    cuesUnavailable = true,
                                ),
                        ),
                ),
            playerFactory = { fake },
        )

        composeRule
            .onNodeWithTag(VideoMiningTestTags.CUES_UNAVAILABLE)
            .assertIsDisplayed()
        composeRule.onNodeWithText("unused").assertDoesNotExist()
        composeRule
            .onNodeWithTag(CurationPlayerTestTags.PLAY_PAUSE)
            .assertIsEnabled()
            .performClick()
        composeRule.runOnIdle { assertEquals(1, fake.togglePlayPauseCount) }
    }

    @Test
    fun rowTapOpensDetailAndCheckboxAloneChangesInclusion() {
        val request = request()
        val candidate = request.candidates.first()
        var state by
            mutableStateOf(
                VideoMiningUiState(
                    runState = MiningRunState.Curating(request),
                    curation = curationState(request, focusedCandidateId = null),
                ),
            )
        composeRule.setContent {
            AnkiMinerTheme {
                ScreenUnderTest(
                    state = state,
                    onFocusCandidate = { candidateId ->
                        val curation = requireNotNull(state.curation)
                        state = state.copy(curation = curation.copy(focusedCandidateId = candidateId))
                    },
                    onSetCandidateSelected = { candidateId, selected ->
                        val curation = requireNotNull(state.curation)
                        state =
                            state.copy(
                                curation =
                                    curation.copy(
                                        selectedCandidateIds =
                                            if (selected) {
                                                curation.selectedCandidateIds + candidateId
                                            } else {
                                                curation.selectedCandidateIds - candidateId
                                            },
                                    ),
                            )
                    },
                )
            }
        }
        val sentenceTag =
            VideoMiningTestTags.sentence(candidate.candidateId, candidate.defaultSentenceId)

        // Every row and counter is scrolled to before it is read: on a short viewport the list
        // holds only part of the page, and an item scrolled away is disposed, not merely hidden.
        fun scrollTo(matcher: SemanticsMatcher) =
            composeRule.onNodeWithTag(VideoMiningTestTags.CONTENT).performScrollToNode(matcher)

        // Inspecting must not exclude: the row opens the detail and leaves the count alone.
        composeRule.onNodeWithTag(sentenceTag).assertDoesNotExist()
        scrollTo(hasTestTag(VideoMiningTestTags.candidate(candidate.candidateId)))
        composeRule.onNodeWithTag(VideoMiningTestTags.candidate(candidate.candidateId)).performClick()
        scrollTo(hasTestTag(sentenceTag))
        composeRule.onNodeWithTag(sentenceTag).assertExists()

        composeRule.onNodeWithText("2 of 2 selected").assertExists()

        // The checkbox excludes, and the detail stays open.
        scrollTo(hasTestTag(VideoMiningTestTags.candidateToggle(candidate.candidateId)))
        composeRule
            .onNodeWithTag(VideoMiningTestTags.candidateToggle(candidate.candidateId))
            .performClick()
        composeRule.onNodeWithText("1 of 2 selected").assertExists()
        scrollTo(hasTestTag(sentenceTag))
        composeRule.onNodeWithTag(sentenceTag).assertExists()

        // Tapping the open header collapses its detail without changing inclusion.
        scrollTo(hasTestTag(VideoMiningTestTags.candidate(candidate.candidateId)))
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

        composeRule.onNodeWithTag(CURATION_BULK_TEST_TAG).assertIsNotEnabled()
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
                    curation = curationState(request, focusedCandidateId = candidateId),
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
    fun filterMenuNarrowsTheCandidateProjection() {
        val candidates =
            listOf(
                candidate("candidate-kept", "食べる", listOf(sentence("s-0", "Sentence 0"))),
                candidate("candidate-dropped", "斡旋", listOf(sentence("s-1", "Sentence 1"))),
            )
        val request = CurationRequest("run", "request-filter", candidates)
        setScreen(
            state =
                VideoMiningUiState(
                    runState = MiningRunState.Curating(request),
                    curation =
                        curationState(
                            request,
                            selectedCandidateIds = setOf("candidate-kept"),
                        ),
                ),
        )

        composeRule.onNodeWithTag(CURATION_FILTER_TEST_TAG).performClick()
        composeRule.onNodeWithText("Excluded").performClick()

        composeRule
            .onNodeWithTag(VideoMiningTestTags.candidate("candidate-dropped"))
            .assertIsDisplayed()
        composeRule
            .onNodeWithTag(VideoMiningTestTags.candidate("candidate-kept"))
            .assertDoesNotExist()
    }

    @Test
    fun sortMenuReordersTheCandidateProjection() {
        // Frequency rank and occurrence count run in opposite directions, so the tail of one
        // ordering is the head of the other and a composed/not-composed check is enough.
        val candidates =
            (0 until 100).map { index ->
                candidate(
                    id = "candidate-$index",
                    form = "語彙$index",
                    sentences = listOf(sentence("sentence-$index", "Sentence $index")),
                ).copy(
                    frequencyRank = index.toLong(),
                    occurrenceCount = index.toLong(),
                )
            }
        val request = CurationRequest("run", "request-sort", candidates)
        lateinit var listState: LazyListState
        composeRule.setContent {
            AnkiMinerTheme {
                listState = rememberLazyListState()
                ScreenUnderTest(
                    state =
                        VideoMiningUiState(
                            runState = MiningRunState.Curating(request),
                            curation = curationState(request),
                        ),
                    listState = listState,
                )
            }
        }
        val tailTag = VideoMiningTestTags.candidate("candidate-99")

        composeRule.onNodeWithTag(tailTag).assertDoesNotExist()
        composeRule.onNodeWithTag(CURATION_SORT_TEST_TAG).performClick()
        composeRule.onNodeWithText("Occurrences").performClick()

        composeRule.runOnIdle {
            assertEquals(
                "candidate:candidate-99",
                listState.layoutInfo.visibleItemsInfo.first().key,
            )
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
    fun toolsToggleCollapsesAndRestoresSearchAndFilterControls() {
        val request = request()
        setScreen(
            state =
                VideoMiningUiState(
                    runState = MiningRunState.Curating(request),
                    curation = curationState(request),
                ),
        )

        composeRule.onNodeWithTag(CURATION_SEARCH_TEST_TAG).assertExists()
        composeRule.onNodeWithTag(CURATION_TOOLS_TOGGLE_TEST_TAG).performClick()
        composeRule.onNodeWithTag(CURATION_SEARCH_TEST_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(CURATION_FILTER_TEST_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(CURATION_SORT_TEST_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(CURATION_BULK_TEST_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(CURATION_TOOLS_TOGGLE_TEST_TAG).performClick()
        composeRule.onNodeWithTag(CURATION_SEARCH_TEST_TAG).assertExists()
    }

    @Test
    fun collapsingToolsKeepsTheActiveSearchQueryApplied() {
        val candidates =
            listOf(
                candidate("candidate-match", "懐かしい", listOf(sentence("s-0", "Sentence 0"))),
                candidate("candidate-other", "食べる", listOf(sentence("s-1", "Sentence 1"))),
            )
        val request = CurationRequest("run", "request-collapse", candidates)
        setScreen(
            state =
                VideoMiningUiState(
                    runState = MiningRunState.Curating(request),
                    curation = curationState(request),
                ),
        )

        composeRule.onNodeWithTag(CURATION_SEARCH_TEST_TAG).performTextInput("懐かしい")
        composeRule
            .onNodeWithTag(VideoMiningTestTags.candidate("candidate-other"))
            .assertDoesNotExist()

        composeRule.onNodeWithTag(CURATION_TOOLS_TOGGLE_TEST_TAG).performClick()

        composeRule
            .onNodeWithTag(VideoMiningTestTags.candidate("candidate-match"))
            .assertIsDisplayed()
        composeRule
            .onNodeWithTag(VideoMiningTestTags.candidate("candidate-other"))
            .assertDoesNotExist()
    }

    @Test
    fun curationFooterActionsSitSideBySideOnNarrowScreens() {
        val request = request()
        composeRule.setContent {
            AnkiMinerTheme {
                Box(Modifier.width(320.dp)) {
                    ScreenUnderTest(
                        state =
                            VideoMiningUiState(
                                runState = MiningRunState.Curating(request),
                                curation = curationState(request),
                            ),
                    )
                }
            }
        }

        val confirm =
            composeRule
                .onNodeWithTag(VideoMiningTestTags.CONFIRM_CURATION)
                .fetchSemanticsNode()
                .boundsInRoot
        val cancel =
            composeRule
                .onNodeWithTag(VideoMiningTestTags.CANCEL)
                .fetchSemanticsNode()
                .boundsInRoot
        assertEquals(confirm.top, cancel.top, 1f)
        assertTrue("cancel should sit left of confirm", cancel.right <= confirm.left)
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

    @Test
    fun definitionPaneShowsForTheExpandedCandidate() {
        val request = request()
        setScreen(
            state =
                VideoMiningUiState(
                    runState = MiningRunState.Curating(request),
                    curation = curationState(request, definition = CurationDefinition.Missing),
                ),
        )

        composeRule
            .onNodeWithTag(VideoMiningTestTags.CONTENT)
            .performScrollToNode(hasTestTag(VideoMiningTestTags.DEFINITION))
        composeRule.onNodeWithTag(VideoMiningTestTags.DEFINITION).assertExists()
    }

    @Test
    fun definitionPaneNamesTheWordItActuallyMatched() {
        val request = request()
        val firstMinedForm = request.candidates.first().minedForm
        val expected =
            InstrumentationRegistry.getInstrumentation().targetContext.getString(
                R.string.definition_fallback,
                firstMinedForm,
                "遣る",
            )
        setScreen(
            state =
                VideoMiningUiState(
                    runState = MiningRunState.Curating(request),
                    curation =
                        curationState(
                            request,
                            definition =
                                CurationDefinition.Loaded(
                                    "遣る",
                                    listOf(DefinitionEntry("Jitendex", "<div/>")),
                                ),
                        ),
                ),
        )

        composeRule
            .onNodeWithTag(VideoMiningTestTags.CONTENT)
            .performScrollToNode(hasTestTag(VideoMiningTestTags.DEFINITION))
        composeRule.onNodeWithText(expected).assertExists()
    }

    @Test
    fun definitionPaneIsAbsentWithoutADefinition() {
        val request = request()
        setScreen(
            state =
                VideoMiningUiState(
                    runState = MiningRunState.Curating(request),
                    curation = curationState(request, definition = null),
                ),
        )

        composeRule.onNodeWithTag(VideoMiningTestTags.DEFINITION).assertDoesNotExist()
    }

    @Test
    fun markingKnownDisablesTheCandidateCheckbox() {
        val request = request()
        val firstCandidateId = request.candidates.first().candidateId
        setScreen(
            state =
                VideoMiningUiState(
                    runState = MiningRunState.Curating(request),
                    curation =
                        curationState(
                            request,
                            knownCandidateIds = setOf(firstCandidateId),
                        ),
                ),
        )

        composeRule
            .onNodeWithTag(VideoMiningTestTags.CONTENT)
            .performScrollToNode(hasTestTag(VideoMiningTestTags.candidateToggle(firstCandidateId)))
        composeRule
            .onNodeWithTag(VideoMiningTestTags.candidateToggle(firstCandidateId))
            .assertIsNotEnabled()
    }

    @Test
    fun knownActionReportsTheMark() {
        val request = request()
        val firstCandidateId = request.candidates.first().candidateId
        var marked: Pair<String, Boolean>? = null
        setScreen(
            state =
                VideoMiningUiState(
                    runState = MiningRunState.Curating(request),
                    curation = curationState(request),
                ),
            onMarkCandidateKnown = { id, known -> marked = id to known },
        )

        composeRule
            .onNodeWithTag(VideoMiningTestTags.CONTENT)
            .performScrollToNode(hasTestTag(VideoMiningTestTags.candidateKnown(firstCandidateId)))
        composeRule.onNodeWithTag(VideoMiningTestTags.candidateKnown(firstCandidateId)).performClick()

        composeRule.runOnIdle { assertEquals(firstCandidateId to true, marked) }
    }

    @Test
    fun copySentenceCopiesTheSelectedAlternative() {
        val request = request()
        val first = request.candidates.first()
        val alternate = first.sentences.last()
        setScreen(
            state =
                VideoMiningUiState(
                    runState = MiningRunState.Curating(request),
                    curation =
                        curationState(
                            request,
                            sentenceIds =
                                request.candidates.associate { candidate ->
                                    candidate.candidateId to
                                        if (candidate.candidateId == first.candidateId) {
                                            alternate.sentenceId
                                        } else {
                                            candidate.defaultSentenceId
                                        }
                                },
                        ),
                ),
        )

        composeRule
            .onNodeWithTag(VideoMiningTestTags.CONTENT)
            .performScrollToNode(
                hasTestTag(VideoMiningTestTags.candidateCopySentence(first.candidateId)),
            )
        composeRule
            .onNodeWithTag(VideoMiningTestTags.candidateCopySentence(first.candidateId))
            .performClick()
        composeRule.waitForIdle()

        assertEquals(alternate.sentence, clipboardText())
    }

    private fun setScreen(
        state: VideoMiningUiState,
        onPickVideo: () -> Unit = {},
        onPickSubtitle: () -> Unit = {},
        onStart: () -> Unit = {},
        onTestTiming: () -> Unit = {},
        onMarkCandidateKnown: (String, Boolean) -> Unit = { _, _ -> },
        onSetSelectionForVisible: (List<String>, Boolean) -> Unit = { _, _ -> },
        onSelectSentence: (String, String) -> Unit = { _, _ -> },
        onConfirmCuration: () -> Unit = {},
        onCancel: () -> Unit = {},
        playerFactory: (Context) -> CurationPreviewPlayer = { FakeCurationPreviewPlayer() },
    ) {
        composeRule.setContent {
            AnkiMinerTheme {
                ScreenUnderTest(
                    state = state,
                    onPickVideo = onPickVideo,
                    onPickSubtitle = onPickSubtitle,
                    onStart = onStart,
                    onTestTiming = onTestTiming,
                    onMarkCandidateKnown = onMarkCandidateKnown,
                    onSetSelectionForVisible = onSetSelectionForVisible,
                    onSelectSentence = onSelectSentence,
                    onConfirmCuration = onConfirmCuration,
                    onCancel = onCancel,
                    playerFactory = playerFactory,
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
        onTestTiming: () -> Unit = {},
        onMarkCandidateKnown: (String, Boolean) -> Unit = { _, _ -> },
        onSetSelectionForVisible: (List<String>, Boolean) -> Unit = { _, _ -> },
        onSelectSentence: (String, String) -> Unit = { _, _ -> },
        onConfirmCuration: () -> Unit = {},
        onCancel: () -> Unit = {},
        onRetry: () -> Unit = {},
        onReset: () -> Unit = {},
        onFocusCandidate: (String?) -> Unit = {},
        onSetCandidateSelected: (String, Boolean) -> Unit = { _, _ -> },
        playerFactory: (Context) -> CurationPreviewPlayer = { FakeCurationPreviewPlayer() },
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
            onTestTiming = onTestTiming,
            onFocusCandidate = onFocusCandidate,
            onSetCandidateSelected = onSetCandidateSelected,
            onMarkCandidateKnown = onMarkCandidateKnown,
            onSetSelectionForVisible = onSetSelectionForVisible,
            onSetSelectionForPage = {},
            onReconcileFocus = { _, _ -> },
            onSelectSentence = onSelectSentence,
            onConfirmCuration = onConfirmCuration,
            onCancel = onCancel,
            onRetry = onRetry,
            onReset = onReset,
            playerFactory = playerFactory,
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
        knownCandidateIds: Set<String> = emptySet(),
        selectedCandidateIds: Set<String> =
            request.candidates.mapTo(linkedSetOf(), CurationCandidate::candidateId) -
                knownCandidateIds,
        sentenceIds: Map<String, String> =
            request.candidates.associate { it.candidateId to it.defaultSentenceId },
        focusedCandidateId: String? = selectedCandidateIds.firstOrNull(),
        previousPageSelectedCount: Int = 0,
        definition: CurationDefinition? = null,
    ): CurationUiState =
        CurationUiState(
            runId = request.runId,
            requestId = request.requestId,
            candidates = request.candidates,
            selectedCandidateIds = selectedCandidateIds,
            knownCandidateIds = knownCandidateIds,
            sentenceIds = sentenceIds,
            focusedCandidateId = focusedCandidateId,
            previousPageSelectedCount = previousPageSelectedCount,
            page = request.page,
            definition = definition,
        )

    private fun clipboardText(): String? =
        composeRule.runOnUiThread {
            InstrumentationRegistry.getInstrumentation().targetContext
                .getSystemService(ClipboardManager::class.java)
                .primaryClip
                ?.getItemAt(0)
                ?.text
                ?.toString()
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
            ankiWriteState = AnkiWriteState.NOTE_WRITE_CONFIRMED,
            failureIsTransient = false,
        )

    private fun hugeResult(): ProcessingResult =
        result().copy(
            minedForms = (1..250).map { "form-$it" },
            ankiWriteState = AnkiWriteState.NOTE_WRITE_CONFIRMED,
            failureIsTransient = false,
            cardIds = (1L..250L).toList(),
            errors = (1..125).map { "error-$it" },
        )
}
