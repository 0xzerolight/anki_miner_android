package com.ankiminer.android.vm

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.CreationExtras
import com.ankiminer.android.MainDispatcherRule
import com.ankiminer.android.data.RuntimeWorkCoordinator
import com.ankiminer.android.dictionary.CurationDefinition
import com.ankiminer.android.dictionary.DefinitionLookupService
import com.ankiminer.android.dictionary.DefinitionResult
import com.ankiminer.android.diagnostics.TesterDiagnosticsShareAction
import com.ankiminer.android.diagnostics.log.AppLog
import com.ankiminer.android.diagnostics.log.LogLevel
import com.ankiminer.android.diagnostics.log.NoOpSink
import com.ankiminer.android.diagnostics.log.RecordingLogSink
import com.ankiminer.android.engine.DefinitionEntry
import com.ankiminer.android.engine.SubtitleCue
import com.ankiminer.android.media.SafAccessException
import com.ankiminer.android.media.SafAccessFailureKind
import com.ankiminer.android.media.SafBroker
import com.ankiminer.android.media.SafDocument
import com.ankiminer.android.media.SafSelectionRecord
import com.ankiminer.android.media.SafSelectionInventory
import com.ankiminer.android.media.SafSelectionPersistenceException
import com.ankiminer.android.media.SafSelectionSlot
import com.ankiminer.android.media.TransientSafSelectionInventory
import com.ankiminer.android.mining.AnkiWriteState
import com.ankiminer.android.mining.CurationCandidate
import com.ankiminer.android.mining.CurationMediaBinding
import com.ankiminer.android.mining.CurationPage
import com.ankiminer.android.mining.CurationRequest
import com.ankiminer.android.mining.CurationSelection
import com.ankiminer.android.mining.CurationSentence
import com.ankiminer.android.mining.CurationSessionState
import com.ankiminer.android.mining.FakeMiningRepository
import com.ankiminer.android.mining.MiningFailure
import com.ankiminer.android.mining.MiningProgress
import com.ankiminer.android.mining.MiningRepository
import com.ankiminer.android.mining.MiningRunState
import com.ankiminer.android.mining.ProcessingResult
import com.ankiminer.android.mining.VideoMiningInput
import com.ankiminer.android.subtitles.SubtitleCueLookupService
import com.ankiminer.android.ui.video.DocumentSelectionError
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VideoMiningViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun focusingACandidateLooksUpItsDefinition() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = RecordingRepository()
            val lookups = mutableListOf<Triple<String, String, String?>>()
            val lookup =
                DefinitionLookupService { runId, term, fallback ->
                    lookups += Triple(runId, term, fallback)
                    Result.success(
                        DefinitionResult(
                            term,
                            term,
                            listOf(DefinitionEntry("Jitendex", "<div/>")),
                        ),
                    )
                }
            val viewModel =
                VideoMiningViewModel(
                    repository,
                    ImmediateSafBroker(),
                    definitionLookup = lookup,
                )
            val original = curationRequest()
            val request =
                original.copy(
                    candidates =
                        original.candidates.map { candidate ->
                            candidate.copy(minedForm = "殺る", lemma = "遣る")
                        },
                )
            repository.transitionTo(MiningRunState.Curating(request))
            val collection =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    viewModel.uiState.collect {}
                }

            advanceUntilIdle()

            assertEquals(1, lookups.size)
            assertEquals(request.runId, lookups.single().first)
            assertEquals(request.candidates.first().minedForm, lookups.single().second)
            assertEquals(request.candidates.first().lemma, lookups.single().third)
            assertEquals(
                CurationDefinition.Loaded(
                    request.candidates.first().minedForm,
                    listOf(DefinitionEntry("Jitendex", "<div/>")),
                ),
                viewModel.uiState.value.curation?.definition,
            )
            collection.cancel()
        }

    @Test
    fun anEmptyDefinitionResultIsARealMiss() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = RecordingRepository()
            val lookup =
                DefinitionLookupService { _, term, _ ->
                    Result.success(DefinitionResult(term, term, emptyList()))
                }
            val viewModel =
                VideoMiningViewModel(
                    repository,
                    ImmediateSafBroker(),
                    definitionLookup = lookup,
                )
            repository.transitionTo(MiningRunState.Curating(curationRequest()))
            val collection =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    viewModel.uiState.collect {}
                }

            advanceUntilIdle()

            assertEquals(CurationDefinition.Missing, viewModel.uiState.value.curation?.definition)
            collection.cancel()
        }

    @Test
    fun aFailedDefinitionLookupDegradesToUnavailable() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = RecordingRepository()
            val lookup =
                DefinitionLookupService { _, _, _ ->
                    Result.failure(IllegalStateException("boom"))
                }
            val viewModel =
                VideoMiningViewModel(
                    repository,
                    ImmediateSafBroker(),
                    definitionLookup = lookup,
                )
            repository.transitionTo(MiningRunState.Curating(curationRequest()))
            val collection =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    viewModel.uiState.collect {}
                }

            advanceUntilIdle()

            assertEquals(
                CurationDefinition.Unavailable,
                viewModel.uiState.value.curation?.definition,
            )
            collection.cancel()
        }

    @Test
    fun aTerminalRunClearsTheDefinitionAndUnblocksTheNextRun() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = RecordingRepository()
            val lookups = mutableListOf<String>()
            val lookup =
                DefinitionLookupService { _, term, _ ->
                    lookups += term
                    Result.success(DefinitionResult(term, term, emptyList()))
                }
            val viewModel =
                VideoMiningViewModel(
                    repository,
                    ImmediateSafBroker(),
                    definitionLookup = lookup,
                )
            val collection =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    viewModel.uiState.collect {}
                }

            repository.transitionTo(MiningRunState.Curating(curationRequest()))
            advanceUntilIdle()
            repository.transitionTo(MiningRunState.Idle)
            advanceUntilIdle()
            assertNull(viewModel.uiState.value.curation)
            repository.transitionTo(MiningRunState.Curating(curationRequest()))
            advanceUntilIdle()

            assertEquals(2, lookups.size)
            collection.cancel()
        }

    @Test
    fun productionFactoryPassesTheDefinitionLookup() {
        val lookup =
            DefinitionLookupService { _, term, _ ->
                Result.success(DefinitionResult(term, term, emptyList()))
            }
        val factory =
            VideoMiningViewModel.Factory(
                repository = RecordingRepository(),
                safBroker = ImmediateSafBroker(),
                definitionLookup = lookup,
                savedStateHandleFactory = { SavedStateHandle() },
            )

        assertNotNull(factory.create(VideoMiningViewModel::class.java, CreationExtras.Empty))
    }

    @Test
    fun curatingMediaLoadsCuesIntoThePlayerOnce() =
        runTest(mainDispatcherRule.dispatcher) {
            val request = curationRequest()
            val media = CurationMediaBinding("/cache/video.mkv", "/cache/subtitle.srt")
            val cue = SubtitleCue(1.25, 2.5, "猫だ。")
            val calls = mutableListOf<Pair<String?, String>>()
            val lookup =
                SubtitleCueLookupService { runId, subtitlePath ->
                    calls += runId to subtitlePath
                    Result.success(listOf(cue))
                }
            val repository =
                RecordingRepository(MiningRunState.Curating(request, media = media))
            val viewModel =
                VideoMiningViewModel(
                    repository,
                    ImmediateSafBroker(),
                    cueLookup = lookup,
                )

            runCurrent()

            assertEquals(listOf(request.runId to media.subtitlePath), calls)
            assertEquals(media.videoPath, viewModel.uiState.value.curation?.player?.videoPath)
            assertEquals(listOf(cue), viewModel.uiState.value.curation?.player?.cues)
            assertFalse(viewModel.uiState.value.curation?.player?.cuesUnavailable == true)
        }

    @Test
    fun failedCueLookupLeavesThePlayerUsableWithoutCues() =
        runTest(mainDispatcherRule.dispatcher) {
            val request = curationRequest()
            val media = CurationMediaBinding("/cache/video.mkv", "/cache/subtitle.srt")
            val repository =
                RecordingRepository(MiningRunState.Curating(request, media = media))
            val viewModel =
                VideoMiningViewModel(
                    repository,
                    ImmediateSafBroker(),
                    cueLookup =
                        SubtitleCueLookupService { _, _ ->
                            Result.failure(IllegalStateException("unavailable"))
                        },
                )

            runCurrent()

            val player = requireNotNull(viewModel.uiState.value.curation?.player)
            assertEquals(media.videoPath, player.videoPath)
            assertEquals(emptyList<SubtitleCue>(), player.cues)
            assertTrue(player.cuesUnavailable)
        }

    @Test
    fun curatingWithoutMediaHasNoPlayerAndDoesNotLoadCues() =
        runTest(mainDispatcherRule.dispatcher) {
            var calls = 0
            val repository = RecordingRepository(MiningRunState.Curating(curationRequest()))
            val viewModel =
                VideoMiningViewModel(
                    repository,
                    ImmediateSafBroker(),
                    cueLookup =
                        SubtitleCueLookupService { _, _ ->
                            calls += 1
                            Result.success(emptyList())
                        },
                )

            runCurrent()

            assertNull(viewModel.uiState.value.curation?.player)
            assertEquals(0, calls)
        }

    @Test
    fun curationPageAdvanceDoesNotReloadCues() =
        runTest(mainDispatcherRule.dispatcher) {
            val first = curationRequest().copy(page = CurationPage(0, 2, 0, 2))
            val media = CurationMediaBinding("/cache/video.mkv", "/cache/subtitle.srt")
            var calls = 0
            val repository =
                RecordingRepository(MiningRunState.Curating(first, media = media))
            val viewModel =
                VideoMiningViewModel(
                    repository,
                    ImmediateSafBroker(),
                    cueLookup =
                        SubtitleCueLookupService { _, _ ->
                            calls += 1
                            Result.success(emptyList())
                        },
                )
            runCurrent()

            repository.transitionTo(
                MiningRunState.Curating(
                    first.copy(
                        requestId = "request-page-2",
                        page = CurationPage(1, 2, 1, 2),
                    ),
                    media = media,
                ),
            )
            runCurrent()

            assertEquals(1, calls)
            assertNotNull(viewModel.uiState.value.curation?.player)
        }

    @Test
    fun delayedCuesFromAnOldRunCannotLandUnderTheNextRun() =
        runTest(mainDispatcherRule.dispatcher) {
            val secondGate = CompletableDeferred<Unit>()
            val firstCue = SubtitleCue(1.0, 2.0, "first")
            val secondCue = SubtitleCue(3.0, 4.0, "second")
            val calls = mutableListOf<String?>()
            lateinit var firstContinuation: Continuation<Unit>
            val lookup =
                SubtitleCueLookupService { runId, _ ->
                    calls += runId
                    if (runId == "run-a") {
                        suspendCoroutine { continuation -> firstContinuation = continuation }
                    } else {
                        secondGate.await()
                    }
                    Result.success(listOf(if (runId == "run-a") firstCue else secondCue))
                }
            val repository = RecordingRepository()
            val viewModel =
                VideoMiningViewModel(
                    repository,
                    ImmediateSafBroker(),
                    cueLookup = lookup,
                )
            val firstRequest = curationRequest().copy(runId = "run-a", requestId = "request-a")
            val secondRequest = curationRequest().copy(runId = "run-b", requestId = "request-b")
            val firstMedia = CurationMediaBinding("/cache/a.mkv", "/cache/a.srt")
            val secondMedia = CurationMediaBinding("/cache/b.mkv", "/cache/b.srt")

            repository.transitionTo(MiningRunState.Curating(firstRequest, media = firstMedia))
            runCurrent()
            repository.transitionTo(MiningRunState.Curating(secondRequest, media = secondMedia))
            runCurrent()

            assertEquals(secondMedia.videoPath, viewModel.uiState.value.curation?.player?.videoPath)
            assertEquals(emptyList<SubtitleCue>(), viewModel.uiState.value.curation?.player?.cues)

            firstContinuation.resume(Unit)
            runCurrent()

            assertEquals(listOf("run-a", "run-b"), calls)
            assertEquals(emptyList<SubtitleCue>(), viewModel.uiState.value.curation?.player?.cues)

            secondGate.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf(secondCue), viewModel.uiState.value.curation?.player?.cues)
        }

    @Test
    fun navigationWorkflowIgnoresFineGrainedProgressUpdates() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = RecordingRepository()
            val viewModel = VideoMiningViewModel(repository, ImmediateSafBroker())
            val observed = mutableListOf<NavigationWorkflowState>()
            var diagnosticBuilds = 0
            val diagnostics =
                TesterDiagnosticsShareAction(
                    buildReport = {
                        diagnosticBuilds += 1
                        "report"
                    },
                    shareReport = {},
                )
            val collection =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    viewModel.navigationWorkflowState.collect { observed += it }
                }
            runCurrent()

            repository.transitionTo(
                MiningRunState.Running("run", MiningProgress(0, 100, "Starting")),
            )
            runCurrent()
            repeat(100) { progress ->
                repository.transitionTo(
                    MiningRunState.Running(
                        "run",
                        MiningProgress(progress.toLong(), 100, "Progress $progress"),
                    ),
                )
            }
            runCurrent()

            assertEquals(
                listOf(NavigationWorkflowState.IDLE, NavigationWorkflowState.RUNNING),
                observed,
            )
            assertEquals(0, diagnosticBuilds)

            diagnostics.share()

            assertEquals(1, diagnosticBuilds)
            collection.cancel()
        }

    @Test
    fun savedIdleSelectionsRestoreByRevalidatingBothUris() =
        runTest(mainDispatcherRule.dispatcher) {
            val savedState = SavedStateHandle()
            SavedDocumentSelectionStore(savedState, "videoMining.video").save(
                document("content://test/restored-video.mkv", "stale-video-name.mkv"),
            )
            SavedDocumentSelectionStore(savedState, "videoMining.subtitle").save(
                document("content://test/restored-subtitle.srt", "stale-subtitle-name.srt"),
            )
            val broker = ImmediateSafBroker()

            val viewModel =
                VideoMiningViewModel(
                    repository = RecordingRepository(),
                    safBroker = broker,
                    savedStateHandle = savedState,
                )
            runCurrent()

            assertEquals(
                listOf(
                    "content://test/restored-video.mkv",
                    "content://test/restored-subtitle.srt",
                ),
                broker.retainedUris,
            )
            assertEquals("restored-video.mkv", viewModel.uiState.value.video.document?.displayName)
            assertEquals(
                "restored-subtitle.srt",
                viewModel.uiState.value.subtitle.document?.displayName,
            )
            assertTrue(viewModel.uiState.value.canStart)
        }

    @Test
    fun freshNonIdleViewModelRestoresDurableSelectionsForLaterReset() =
        runTest(mainDispatcherRule.dispatcher) {
            val inventory = TransientSafSelectionInventory()
            inventory.putSelection(
                SafSelectionSlot.VIDEO,
                SafSelectionRecord("content://test/restored-video.mkv", "restored-video.mkv"),
            )
            inventory.putSelection(
                SafSelectionSlot.VIDEO_SUBTITLE,
                SafSelectionRecord(
                    "content://test/restored-subtitle.srt",
                    "restored-subtitle.srt",
                ),
            )
            val repository =
                RecordingRepository(
                    MiningRunState.Running("run", MiningProgress(1, 2, "Running")),
                )
            val viewModel =
                VideoMiningViewModel(
                    repository = repository,
                    safBroker = ImmediateSafBroker(),
                    selectionInventory = inventory,
                    selectionIoDispatcher = mainDispatcherRule.dispatcher,
                )
            runCurrent()

            assertEquals(
                "restored-video.mkv",
                viewModel.uiState.value.video.document?.displayName,
            )
            assertEquals(
                "restored-subtitle.srt",
                viewModel.uiState.value.subtitle.document?.displayName,
            )
            assertFalse(viewModel.uiState.value.canStart)

            repository.transitionTo(MiningRunState.Success("run", result()))
            viewModel.reset()
            runCurrent()

            assertEquals(MiningRunState.Idle, repository.state.value)
            assertTrue(viewModel.uiState.value.canStart)
        }

    @Test
    fun revokedSavedVideoGrantSurfacesAccessErrorAndClearsMetadata() =
        runTest(mainDispatcherRule.dispatcher) {
            val savedState = SavedStateHandle()
            val selectionStore =
                SavedDocumentSelectionStore(savedState, "videoMining.video")
            selectionStore.save(
                document("content://test/revoked-video.mkv", "revoked-video.mkv"),
            )
            val broker = ControlledSafBroker()

            val viewModel =
                VideoMiningViewModel(
                    repository = RecordingRepository(),
                    safBroker = broker,
                    savedStateHandle = savedState,
                )
            runCurrent()
            broker.fail(
                "content://test/revoked-video.mkv",
                SafAccessException(
                    SafAccessFailureKind.PERMISSION_REVOKED,
                    "revoked",
                ),
            )
            runCurrent()

            assertEquals(DocumentSelectionError.VIDEO, viewModel.uiState.value.video.error)
            assertFalse(viewModel.uiState.value.video.isResolving)
            assertNull(viewModel.uiState.value.video.document)
            assertNull(selectionStore.restore())
            assertNull(savedState.get<String>("videoMining.video.uri"))
            assertNull(savedState.get<String>("videoMining.video.displayName"))
        }

    @Test
    fun transientSavedVideoProviderFailurePreservesSelectionForRecreationRetry() =
        runTest(mainDispatcherRule.dispatcher) {
            val uri = "content://test/temporarily-unavailable.mkv"
            val inventory = TransientSafSelectionInventory()
            inventory.putSelection(
                SafSelectionSlot.VIDEO,
                SafSelectionRecord(uri, "temporarily-unavailable.mkv"),
            )
            val broker = ControlledSafBroker()
            val first =
                VideoMiningViewModel(
                    repository = RecordingRepository(),
                    safBroker = broker,
                    selectionInventory = inventory,
                    selectionIoDispatcher = mainDispatcherRule.dispatcher,
                )
            runCurrent()

            broker.fail(
                uri,
                SafAccessException(
                    SafAccessFailureKind.PROVIDER_UNAVAILABLE,
                    "provider updating",
                ),
            )
            runCurrent()

            assertEquals(DocumentSelectionError.VIDEO, first.uiState.value.video.error)
            assertEquals(uri, inventory.selection(SafSelectionSlot.VIDEO)?.uri)

            val recreated =
                VideoMiningViewModel(
                    repository = RecordingRepository(),
                    safBroker = ImmediateSafBroker(),
                    selectionInventory = inventory,
                    selectionIoDispatcher = mainDispatcherRule.dispatcher,
                )
            runCurrent()

            assertEquals(
                "temporarily-unavailable.mkv",
                recreated.uiState.value.video.document?.displayName,
            )
        }

    @Test
    fun failedVideoInventoryCommitReleasesNewlyAcquiredGrant() =
        runTest(mainDispatcherRule.dispatcher) {
            val inventory = FailOnceSelectionInventory()
            val broker = ImmediateSafBroker()
            val viewModel =
                VideoMiningViewModel(
                    repository = RecordingRepository(),
                    safBroker = broker,
                    selectionInventory = inventory,
                    selectionIoDispatcher = mainDispatcherRule.dispatcher,
                )

            viewModel.onVideoPicked("content://test/new-video.mkv")
            runCurrent()

            assertEquals(DocumentSelectionError.VIDEO, viewModel.uiState.value.video.error)
            assertNull(viewModel.uiState.value.video.document)
            assertNull(inventory.selection(SafSelectionSlot.VIDEO))
            assertEquals(listOf("content://test/new-video.mkv"), broker.releasedUris)
        }

    @Test
    fun defaultCurationSelectsEverythingAndKeepsAlternateSentenceIdentity() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository =
                FakeMiningRepository(
                    stepDelayMillis = 0,
                    terminalOutcomes = listOf(FakeMiningRepository.TerminalOutcome.SUCCESS),
                    workScope = this,
                )
            val viewModel = VideoMiningViewModel(repository, ImmediateSafBroker())
            selectDocuments(viewModel)
            runCurrent()

            assertTrue(viewModel.uiState.value.canStart)
            viewModel.start()
            runCurrent()

            val request = (repository.state.value as MiningRunState.Curating).request
            val first = request.candidates.first()
            val alternateSentence = first.sentences[1]
            assertEquals(request.candidates.size, viewModel.uiState.value.curation?.selectedCount)

            viewModel.selectSentence(first.candidateId, alternateSentence.sentenceId)
            viewModel.setCandidateSelected(request.candidates.last().candidateId, false)
            viewModel.confirmCuration()
            runCurrent()

            val success = repository.state.value as MiningRunState.Success
            assertEquals(request.candidates.size - 1L, success.result.cardsCreated)
            assertEquals(
                alternateSentence.sentenceId,
                repository.confirmedSelection
                    ?.single { it.candidateId == first.candidateId }
                    ?.sentenceId,
            )
        }

    @Test
    fun curationCommandLogCarriesRunId() =
        runTest(mainDispatcherRule.dispatcher) {
            val recorded = RecordingLogSink()
            AppLog.setMinLevel(LogLevel.INFO)
            AppLog.install(NoOpSink)
            AppLog.install(recorded)
            try {
                val request = curationRequest()
                val repository = RecordingRepository(MiningRunState.Curating(request))
                val viewModel = VideoMiningViewModel(repository, ImmediateSafBroker())
                runCurrent()

                viewModel.confirmCuration()
                runCurrent()

                val record =
                    recorded.records.single {
                        it.contains("c=ui op=command") && it.contains("command=curation")
                    }
                assertTrue(record, record.contains(" run=${request.runId} "))
                assertFalse(record, record.contains(" run=- "))
            } finally {
                AppLog.install(NoOpSink)
            }
        }

    @Test
    fun deselectAllConfirmsEmptyListInsteadOfCancellingRun() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeMiningRepository(stepDelayMillis = 0, workScope = this)
            val viewModel = VideoMiningViewModel(repository, ImmediateSafBroker())
            selectDocuments(viewModel)
            runCurrent()
            viewModel.start()
            runCurrent()

            viewModel.setSelectionForPage(false)
            viewModel.confirmCuration()
            runCurrent()

            assertEquals(emptyList<CurationSelection>(), repository.confirmedSelection)
            assertEquals(0L, (repository.state.value as MiningRunState.Success).result.cardsCreated)
            assertEquals(0, repository.cancelCount)
        }

    @Test
    fun confirmingSendsTheStagedKnownMarks() =
        runTest(mainDispatcherRule.dispatcher) {
            val request = curationRequest()
            val repository = RecordingRepository(MiningRunState.Curating(request))
            val viewModel = VideoMiningViewModel(repository, ImmediateSafBroker())
            runCurrent()
            val candidateId = request.candidates.first().candidateId

            viewModel.markCandidateKnown(candidateId, known = true)
            viewModel.confirmCuration()
            runCurrent()

            assertEquals(listOf(candidateId), repository.confirmedKnownCandidateIds)
            assertTrue(repository.confirmedSelection.orEmpty().none { it.candidateId == candidateId })
        }

    @Test
    fun cancellingDiscardsTheStagedKnownMarks() =
        runTest(mainDispatcherRule.dispatcher) {
            val request = curationRequest()
            val repository = RecordingRepository(MiningRunState.Curating(request))
            val viewModel = VideoMiningViewModel(repository, ImmediateSafBroker())
            runCurrent()

            viewModel.markCandidateKnown(request.candidates.first().candidateId, known = true)
            viewModel.cancel()
            runCurrent()

            assertTrue(repository.confirmedKnownCandidateIds.isEmpty())
        }

    @Test
    fun pagedCurationResetsDraftForSameRequestAndSubmitsExactPageIndex() =
        runTest(mainDispatcherRule.dispatcher) {
            val first = curationRequest().copy(page = CurationPage(0, 2, 0, 2))
            val firstCandidate = first.candidates.single()
            val secondSentence =
                firstCandidate.sentences.single().copy(sentenceId = "sentence-next")
            val secondCandidate =
                firstCandidate.copy(
                    candidateId = "candidate-next",
                    minedForm = "見る",
                    defaultSentenceId = secondSentence.sentenceId,
                    sentences = listOf(secondSentence),
                )
            val second =
                first.copy(
                    candidates = listOf(secondCandidate),
                    page = CurationPage(1, 2, 1, 2),
                )
            val repository = RecordingRepository(MiningRunState.Curating(first))
            val viewModel = VideoMiningViewModel(repository, ImmediateSafBroker())
            runCurrent()

            assertEquals(0L, viewModel.uiState.value.curation?.page?.pageIndex)
            viewModel.setCandidateSelected(firstCandidate.candidateId, false)
            runCurrent()
            assertEquals(0, viewModel.uiState.value.curation?.selectedCount)

            repository.transitionTo(MiningRunState.Curating(second))
            runCurrent()

            assertEquals(1L, viewModel.uiState.value.curation?.page?.pageIndex)
            assertEquals(1, viewModel.uiState.value.curation?.selectedCount)
            viewModel.confirmCuration()
            runCurrent()

            assertEquals(1L, repository.confirmedPageIndex)
            assertEquals(secondCandidate.candidateId, repository.confirmedSelection?.single()?.candidateId)
        }

    @Test
    fun pagedCurationCarriesSubmittedSelectionCountAcrossRequestIdsInSameRun() =
        runTest(mainDispatcherRule.dispatcher) {
            val first =
                curationRequest().copy(
                    requestId = "page-1",
                    page = CurationPage(0, 2, 0, 2),
                )
            val second =
                first.copy(
                    requestId = "page-2",
                    page = CurationPage(1, 2, 1, 2),
                )
            val repository = RecordingRepository(MiningRunState.Curating(first))
            val viewModel = VideoMiningViewModel(repository, ImmediateSafBroker())
            runCurrent()

            viewModel.confirmCuration()
            runCurrent()
            repository.transitionTo(MiningRunState.Curating(second))
            runCurrent()

            assertEquals(1, viewModel.uiState.value.curation?.previousPageSelectedCount)
            assertTrue(viewModel.uiState.value.curation?.hasSelectionToLose == true)

            val recreated = VideoMiningViewModel(repository, ImmediateSafBroker())
            runCurrent()

            assertEquals(1, recreated.uiState.value.curation?.previousPageSelectedCount)
        }

    @Test
    fun recreatedViewModelRestoresExactCurationDraftFromProcessRepository() =
        runTest(mainDispatcherRule.dispatcher) {
            val base = curationRequest()
            val candidate = base.candidates.single()
            val alternate =
                candidate.sentences.single().copy(
                    sentenceId = "alternate-sentence",
                    sentence = "魚を食べた。",
                )
            val request =
                base.copy(
                    candidates =
                        listOf(
                            candidate.copy(sentences = candidate.sentences + alternate),
                        ),
                )
            val repository = RecordingRepository(MiningRunState.Curating(request))
            val first = VideoMiningViewModel(repository, ImmediateSafBroker())
            runCurrent()

            first.setCandidateSelected(candidate.candidateId, false)
            first.selectSentence(candidate.candidateId, alternate.sentenceId)
            runCurrent()

            val recreated = VideoMiningViewModel(repository, ImmediateSafBroker())
            runCurrent()

            assertEquals(0, recreated.uiState.value.curation?.selectedCount)
            assertEquals(
                alternate.sentenceId,
                recreated.uiState.value.curation?.sentenceIds?.get(candidate.candidateId),
            )
        }

    @Test
    fun repositoryPageSubmissionPendingDisablesDuplicateConfirmation() =
        runTest(mainDispatcherRule.dispatcher) {
            val request = curationRequest().copy(page = CurationPage(0, 2, 0, 2))
            val repository =
                RecordingRepository(
                    MiningRunState.Curating(request, pageSubmissionPending = true),
                )
            val viewModel = VideoMiningViewModel(repository, ImmediateSafBroker())
            runCurrent()

            assertTrue(viewModel.uiState.value.curationPending)
            viewModel.confirmCuration()
            runCurrent()

            assertEquals(0, repository.confirmCalls)
        }

    @Test
    fun runningFakeRunCanBeCancelledAfterCurationCommandReturns() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository =
                FakeMiningRepository(
                    stepDelayMillis = 1,
                    terminalOutcomes = listOf(FakeMiningRepository.TerminalOutcome.SUCCESS),
                    workScope = this,
                )
            val viewModel = VideoMiningViewModel(repository, ImmediateSafBroker())
            selectDocuments(viewModel)
            runCurrent()
            viewModel.start()
            advanceUntilIdle()

            val request = (repository.state.value as MiningRunState.Curating).request
            viewModel.confirmCuration()
            runCurrent()

            assertTrue(repository.state.value is MiningRunState.Running)
            assertFalse(viewModel.uiState.value.curationPending)
            viewModel.cancel()
            runCurrent()

            val cancelled = repository.state.value as MiningRunState.Cancelled
            assertEquals(1, repository.cancelCount)
            assertFalse(viewModel.uiState.value.cancelPending)
            assertEquals(request.runId, cancelled.runId)
        }

    @Test
    fun resetClearsOnlyResetPendingAndReEnablesStart() =
        runTest(mainDispatcherRule.dispatcher) {
            val resetGate = CompletableDeferred<Unit>()
            val repository =
                RecordingRepository(
                    resetGate = resetGate,
                )
            val viewModel = VideoMiningViewModel(repository, ImmediateSafBroker())
            selectDocuments(viewModel)
            runCurrent()
            repository.transitionTo(MiningRunState.Success("run", result()))
            runCurrent()

            viewModel.reset()
            runCurrent()

            assertTrue(viewModel.uiState.value.resetPending)
            assertFalse(viewModel.uiState.value.startPending)
            resetGate.complete(Unit)
            runCurrent()

            assertEquals(MiningRunState.Idle, repository.state.value)
            assertFalse(viewModel.uiState.value.resetPending)
            assertFalse(viewModel.uiState.value.startPending)
            assertTrue(viewModel.uiState.value.canStart)
        }

    @Test
    fun retrySetsBothPendingFlagsBeforeLaunchAndRejectsDuplicates() =
        runTest(mainDispatcherRule.dispatcher) {
            val resetGate = CompletableDeferred<Unit>()
            val startGate = CompletableDeferred<Unit>()
            val repository =
                RecordingRepository(
                    resetGate = resetGate,
                    startGate = startGate,
                )
            val viewModel = VideoMiningViewModel(repository, ImmediateSafBroker())
            selectDocuments(viewModel)
            runCurrent()
            repository.transitionTo(
                MiningRunState.Failed(
                    runId = "run",
                    failure = MiningFailure("retry", retryable = true),
                    result = result(),
                ),
            )
            runCurrent()

            viewModel.retry()
            viewModel.retry()
            runCurrent()

            assertEquals(1, repository.resetCalls)
            assertEquals(0, repository.startCalls)
            assertTrue(viewModel.uiState.value.resetPending)
            assertTrue(viewModel.uiState.value.startPending)

            resetGate.complete(Unit)
            runCurrent()
            viewModel.retry()

            assertEquals(1, repository.resetCalls)
            assertEquals(1, repository.startCalls)
            assertFalse(viewModel.uiState.value.resetPending)
            assertTrue(viewModel.uiState.value.startPending)

            startGate.complete(Unit)
            runCurrent()
            assertFalse(viewModel.uiState.value.startPending)
        }

    @Test
    fun startAcquiresPendingGateBeforeLaunchingAndRejectsImmediateDuplicate() =
        runTest(mainDispatcherRule.dispatcher) {
            val startGate = CompletableDeferred<Unit>()
            val repository = RecordingRepository(startGate = startGate)
            val viewModel = VideoMiningViewModel(repository, ImmediateSafBroker())
            selectDocuments(viewModel)
            runCurrent()

            viewModel.start()
            viewModel.start()
            runCurrent()

            assertEquals(1, repository.startCalls)
            assertTrue(viewModel.uiState.value.startPending)
            startGate.complete(Unit)
            runCurrent()
            assertEquals(1, repository.startCalls)
            assertFalse(viewModel.uiState.value.startPending)
        }

    @Test
    fun conflictingRuntimeWorkDisablesStartUntilAuthoritativeLeaseClears() =
        runTest(mainDispatcherRule.dispatcher) {
            val runtimeWork = MutableStateFlow<RuntimeWorkCoordinator.Kind?>(null)
            val repository = RecordingRepository()
            val viewModel = VideoMiningViewModel(repository, ImmediateSafBroker(), runtimeWork)
            selectDocuments(viewModel)
            runCurrent()
            assertTrue(viewModel.uiState.value.canStart)

            runtimeWork.value = RuntimeWorkCoordinator.Kind.MINING
            runCurrent()
            assertFalse(viewModel.uiState.value.canStart)
            viewModel.start()
            runCurrent()
            assertEquals(0, repository.startCalls)

            runtimeWork.value = null
            runCurrent()
            assertTrue(viewModel.uiState.value.canStart)
        }

    @Test
    fun pendingStartKeepsCapturedSourceGrantsAndRejectsReplacement() =
        runTest(mainDispatcherRule.dispatcher) {
            val startGate = CompletableDeferred<Unit>()
            val repository = RecordingRepository(startGate = startGate)
            val broker = ImmediateSafBroker()
            val viewModel = VideoMiningViewModel(repository, broker)
            selectDocuments(viewModel)
            runCurrent()

            viewModel.start()
            viewModel.clearVideo()
            viewModel.onSubtitlePicked("content://test/replacement")
            runCurrent()

            assertEquals("video", viewModel.uiState.value.video.document?.displayName)
            assertEquals("subtitle", viewModel.uiState.value.subtitle.document?.displayName)
            assertEquals(
                listOf("content://test/video", "content://test/subtitle"),
                broker.retainedUris,
            )
            assertEquals(emptyList<String>(), broker.eventualReleaseUris)

            startGate.complete(Unit)
            runCurrent()
        }

    @Test
    fun pendingCancelRejectsCurationEditingAndConfirmation() =
        runTest(mainDispatcherRule.dispatcher) {
            val request = curationRequest()
            val cancelGate = CompletableDeferred<Unit>()
            val repository =
                RecordingRepository(
                    initialState = MiningRunState.Curating(request),
                    cancelGate = cancelGate,
                )
            val viewModel = VideoMiningViewModel(repository, ImmediateSafBroker())
            runCurrent()

            viewModel.cancel()
            viewModel.setCandidateSelected(request.candidates.first().candidateId, false)
            viewModel.confirmCuration()
            runCurrent()

            assertEquals(1, repository.cancelCalls)
            assertEquals(0, repository.confirmCalls)
            assertEquals(request.candidates.size, viewModel.uiState.value.curation?.selectedCount)
            assertTrue(viewModel.uiState.value.cancelPending)

            cancelGate.complete(Unit)
            runCurrent()
            assertTrue(repository.state.value is MiningRunState.Cancelled)
        }

    @Test
    fun cancelPendingWaitsForTerminalRepositoryAcknowledgement() =
        runTest(mainDispatcherRule.dispatcher) {
            val request = curationRequest()
            val repository =
                RecordingRepository(
                    initialState = MiningRunState.Curating(request),
                    acknowledgeCancellationImmediately = false,
                )
            val viewModel = VideoMiningViewModel(repository, ImmediateSafBroker())
            runCurrent()

            viewModel.cancel()
            runCurrent()

            assertEquals(1, repository.cancelCalls)
            assertTrue(viewModel.uiState.value.cancelPending)

            repository.transitionTo(MiningRunState.Cancelled(request.runId, null))
            runCurrent()

            assertFalse(viewModel.uiState.value.cancelPending)
        }

    @Test
    fun pendingCurationConfirmationStillAllowsPromptCancellation() =
        runTest(mainDispatcherRule.dispatcher) {
            val request = curationRequest()
            val confirmGate = CompletableDeferred<Unit>()
            val repository =
                RecordingRepository(
                    initialState = MiningRunState.Curating(request),
                    confirmGate = confirmGate,
                )
            val viewModel = VideoMiningViewModel(repository, ImmediateSafBroker())
            runCurrent()

            viewModel.confirmCuration()
            viewModel.cancel()
            runCurrent()

            assertEquals(1, repository.confirmCalls)
            assertEquals(1, repository.cancelCalls)
            assertTrue(repository.state.value is MiningRunState.Cancelled)

            confirmGate.complete(Unit)
            runCurrent()
            assertTrue(repository.state.value is MiningRunState.Cancelled)
        }

    @Test
    fun staleFailureCannotOverwriteNewerResolvedDocument() =
        runTest(mainDispatcherRule.dispatcher) {
            val broker = ControlledSafBroker()
            val viewModel = VideoMiningViewModel(RecordingRepository(), broker)

            viewModel.onVideoPicked("content://test/old")
            runCurrent()
            viewModel.onVideoPicked("content://test/new")
            runCurrent()
            assertTrue(broker.isPendingActive("content://test/old"))
            broker.succeed("content://test/new", "new.mkv")
            runCurrent()
            broker.fail("content://test/old")
            runCurrent()

            assertEquals("new.mkv", viewModel.uiState.value.video.document?.displayName)
            assertNull(viewModel.uiState.value.video.error)
            assertFalse(viewModel.uiState.value.video.isResolving)
        }

    @Test
    fun clearInvalidatesNonCooperativeDocumentCompletion() =
        runTest(mainDispatcherRule.dispatcher) {
            val broker = ControlledSafBroker()
            val viewModel = VideoMiningViewModel(RecordingRepository(), broker)

            viewModel.onSubtitlePicked("content://test/old-subtitle")
            runCurrent()
            viewModel.clearSubtitle()
            broker.succeed("content://test/old-subtitle", "old.srt")
            runCurrent()

            assertNull(viewModel.uiState.value.subtitle.document)
            assertNull(viewModel.uiState.value.subtitle.error)
            assertFalse(viewModel.uiState.value.subtitle.isResolving)
            assertEquals(listOf("content://test/old-subtitle"), broker.releasedUris)
        }

    @Test
    fun supersededCancellableAcquireStillTransfersAndReleasesItsGrant() =
        runTest(mainDispatcherRule.dispatcher) {
            val broker = ControlledSafBroker()
            val viewModel = VideoMiningViewModel(RecordingRepository(), broker)

            viewModel.onVideoPicked("content://test/old")
            runCurrent()
            viewModel.onVideoPicked("content://test/new")
            runCurrent()
            assertTrue(broker.isPendingActive("content://test/old"))
            broker.succeed("content://test/new", "new.mkv")
            broker.succeed("content://test/old", "old.mkv")
            runCurrent()

            assertEquals("new.mkv", viewModel.uiState.value.video.document?.displayName)
            assertEquals(listOf("content://test/old"), broker.releasedUris)
        }

    @Test
    fun replacingSelectionReleasesOnlyTheSupersededGrant() =
        runTest(mainDispatcherRule.dispatcher) {
            val broker = ImmediateSafBroker()
            val viewModel = VideoMiningViewModel(RecordingRepository(), broker)

            viewModel.onVideoPicked("content://test/old-video")
            runCurrent()
            viewModel.onVideoPicked("content://test/new-video")
            runCurrent()

            assertEquals("new-video", viewModel.uiState.value.video.document?.displayName)
            assertEquals(listOf("content://test/old-video"), broker.eventualReleaseUris)
        }

    @Test
    fun clearReleasesIdleSelectionButCannotReleaseAnActiveRunInput() =
        runTest(mainDispatcherRule.dispatcher) {
            val broker = ImmediateSafBroker()
            val repository = FakeMiningRepository(stepDelayMillis = 0, workScope = this)
            val viewModel = VideoMiningViewModel(repository, broker)
            selectDocuments(viewModel)
            runCurrent()

            viewModel.clearVideo()
            runCurrent()
            assertEquals(listOf("content://test/video"), broker.eventualReleaseUris)

            viewModel.onVideoPicked("content://test/video")
            runCurrent()
            viewModel.start()
            runCurrent()
            assertTrue(repository.state.value is MiningRunState.Curating)

            viewModel.clearVideo()
            runCurrent()
            assertEquals("video", viewModel.uiState.value.video.document?.displayName)
            assertEquals(listOf("content://test/video"), broker.eventualReleaseUris)
        }

    @Test
    fun clearTransfersReleaseBeforeImmediateViewModelTeardown() =
        runTest(mainDispatcherRule.dispatcher) {
            val broker = ImmediateSafBroker()
            val store = ViewModelStore()
            val viewModel =
                ViewModelProvider.create(
                    store,
                    factory(RecordingRepository(), broker),
                )[VideoMiningViewModel::class.java]
            selectDocuments(viewModel)
            runCurrent()

            viewModel.clearVideo()
            store.clear()

            assertEquals(
                listOf("content://test/video", "content://test/subtitle"),
                broker.eventualReleaseUris,
            )
        }

    @Test
    fun viewModelTeardownEventuallyReleasesRecoverableSelectionOwners() =
        runTest(mainDispatcherRule.dispatcher) {
            val broker = ImmediateSafBroker()
            val store = ViewModelStore()
            val viewModel =
                ViewModelProvider.create(
                    store,
                    factory(RecordingRepository(), broker),
                )[VideoMiningViewModel::class.java]
            selectDocuments(viewModel)
            runCurrent()

            store.clear()

            assertEquals(
                listOf("content://test/video", "content://test/subtitle"),
                broker.eventualReleaseUris,
            )
        }

    @Test
    fun viewModelTeardownReleasesActiveSourcesWhenRepositoryDoesNotOutliveUi() =
        runTest(mainDispatcherRule.dispatcher) {
            val broker = ImmediateSafBroker()
            val repository = FakeMiningRepository(stepDelayMillis = 0, workScope = this)
            val store = ViewModelStore()
            val viewModel =
                ViewModelProvider.create(
                    store,
                    factory(repository, broker),
                )[VideoMiningViewModel::class.java]
            selectDocuments(viewModel)
            runCurrent()
            viewModel.start()
            runCurrent()
            assertTrue(repository.state.value is MiningRunState.Curating)

            store.clear()

            assertEquals(
                listOf("content://test/video", "content://test/subtitle"),
                broker.eventualReleaseUris,
            )
        }

    @Test
    fun viewModelTeardownPreservesSourcesTransferredToLongLivedCoordinator() =
        runTest(mainDispatcherRule.dispatcher) {
            val broker = ImmediateSafBroker()
            val repository = RecordingRepository(detachActiveSourcesResult = true)
            val store = ViewModelStore()
            val viewModel =
                ViewModelProvider.create(
                    store,
                    factory(repository, broker),
                )[VideoMiningViewModel::class.java]
            selectDocuments(viewModel)
            runCurrent()
            viewModel.start()
            runCurrent()
            assertTrue(repository.state.value is MiningRunState.Starting)

            store.clear()

            assertEquals(emptyList<String>(), broker.eventualReleaseUris)
            assertEquals(1, repository.detachedInputs.size)
            assertEquals("content://test/video", repository.detachedInputs.single().video.uri)
            assertEquals("content://test/subtitle", repository.detachedInputs.single().subtitle.uri)
        }

    @Test
    fun teardownReleasesTwoSelectionCountsWhenBothSlotsUseSameUri() =
        runTest(mainDispatcherRule.dispatcher) {
            val broker = ImmediateSafBroker()
            val repository = RecordingRepository(detachActiveSourcesResult = false)
            val store = ViewModelStore()
            val viewModel =
                ViewModelProvider.create(
                    store,
                    factory(repository, broker),
                )[VideoMiningViewModel::class.java]
            viewModel.onVideoPicked("content://test/shared")
            viewModel.onSubtitlePicked("content://test/shared")
            runCurrent()

            store.clear()

            assertEquals(
                listOf("content://test/shared", "content://test/shared"),
                broker.eventualReleaseUris,
            )
            assertEquals(1, repository.detachedInputs.size)
        }

    @Test
    fun repositoryCanAcceptDetachAfterTerminalStateWasAlreadyPublished() =
        runTest(mainDispatcherRule.dispatcher) {
            val broker = ImmediateSafBroker()
            val repository =
                RecordingRepository(
                    detachActiveSourcesResult = true,
                )
            val store = ViewModelStore()
            val viewModel =
                ViewModelProvider.create(
                    store,
                    factory(repository, broker),
                )[VideoMiningViewModel::class.java]
            selectDocuments(viewModel)
            runCurrent()
            repository.transitionTo(MiningRunState.Success("run", result()))
            runCurrent()

            store.clear()

            assertEquals(emptyList<String>(), broker.eventualReleaseUris)
            assertEquals(1, repository.detachedInputs.size)
        }

    private fun selectDocuments(viewModel: VideoMiningViewModel) {
        viewModel.onVideoPicked("content://test/video")
        viewModel.onSubtitlePicked("content://test/subtitle")
    }

    private fun factory(
        repository: MiningRepository,
        broker: SafBroker,
    ): VideoMiningViewModel.Factory =
        VideoMiningViewModel.Factory(
            repository = repository,
            safBroker = broker,
            definitionLookup = NO_DEFINITION_LOOKUP,
            savedStateHandleFactory = { SavedStateHandle() },
        )

    private class ImmediateSafBroker : SafBroker {
        val retainedUris = mutableListOf<String>()
        val releasedUris = mutableListOf<String>()
        val eventualReleaseUris = mutableListOf<String>()

        override suspend fun retainReadAccess(uri: String): SafDocument {
            retainedUris += uri
            return SafDocument(
                uri = uri,
                displayName = uri.substringAfterLast('/'),
                mimeType = null,
                sizeBytes = null,
            )
        }

        override suspend fun releaseReadAccess(uri: String) {
            releasedUris += uri
        }

        override fun releaseReadAccessEventually(uri: String) {
            eventualReleaseUris += uri
        }
    }

    private class ControlledSafBroker : SafBroker {
        private val pending = ConcurrentHashMap<String, CancellableContinuation<SafDocument>>()
        val releasedUris = mutableListOf<String>()

        override suspend fun retainReadAccess(uri: String): SafDocument =
            suspendCancellableCoroutine { continuation -> pending[uri] = continuation }

        override suspend fun releaseReadAccess(uri: String) {
            releasedUris += uri
        }

        override fun releaseReadAccessEventually(uri: String) {
            releasedUris += uri
        }

        fun isPendingActive(uri: String): Boolean = pending[uri]?.isActive == true

        fun succeed(
            uri: String,
            displayName: String,
        ) {
            requireNotNull(pending.remove(uri)).resume(
                SafDocument(uri, displayName, mimeType = null, sizeBytes = null),
            )
        }

        fun fail(
            uri: String,
            failure: Exception = IllegalStateException("stale"),
        ) {
            requireNotNull(pending.remove(uri)).resumeWithException(failure)
        }
    }

    private class FailOnceSelectionInventory : SafSelectionInventory {
        private val selections = mutableMapOf<SafSelectionSlot, SafSelectionRecord>()
        private val text = mutableMapOf<SafSelectionSlot, String>()
        private var failNextSave = true

        override fun selection(slot: SafSelectionSlot): SafSelectionRecord? = selections[slot]

        override fun putSelection(
            slot: SafSelectionSlot,
            selection: SafSelectionRecord?,
        ) {
            if (selection == null) selections.remove(slot) else selections[slot] = selection
            if (selection != null && failNextSave) {
                failNextSave = false
                throw SafSelectionPersistenceException("injected commit failure")
            }
        }

        override fun text(slot: SafSelectionSlot): String? = text[slot]

        override fun putText(
            slot: SafSelectionSlot,
            value: String?,
        ) {
            if (value == null) text.remove(slot) else text[slot] = value
        }

        override fun ownedUris(): Set<String> =
            selections.values.mapTo(linkedSetOf(), SafSelectionRecord::uri)

        override fun pruneMissingGrants(grantedUris: Set<String>) {
            selections.entries.removeAll { it.value.uri !in grantedUris }
        }
    }

    private class RecordingRepository(
        initialState: MiningRunState = MiningRunState.Idle,
        private val resetGate: CompletableDeferred<Unit>? = null,
        private val startGate: CompletableDeferred<Unit>? = null,
        private val cancelGate: CompletableDeferred<Unit>? = null,
        private val confirmGate: CompletableDeferred<Unit>? = null,
        private val detachActiveSourcesResult: Boolean = false,
        private val acknowledgeCancellationImmediately: Boolean = true,
    ) : MiningRepository {
        private val mutableState = MutableStateFlow(initialState)
        override val state: StateFlow<MiningRunState> = mutableState.asStateFlow()
        private var savedCurationSessionState: CurationSessionState? = null

        var startCalls = 0
            private set
        var resetCalls = 0
            private set
        var cancelCalls = 0
            private set
        var confirmCalls = 0
            private set
        var confirmedPageIndex: Long? = null
            private set
        var confirmedSelection: List<CurationSelection>? = null
            private set
        var confirmedKnownCandidateIds: List<String> = emptyList()
            private set
        val detachedInputs = mutableListOf<VideoMiningInput>()

        override fun curationSessionState(): CurationSessionState? = savedCurationSessionState

        override fun saveCurationSessionState(state: CurationSessionState) {
            savedCurationSessionState = state
        }

        override fun clearCurationSessionState(runId: String?) {
            if (runId == null || savedCurationSessionState?.runId == runId) {
                savedCurationSessionState = null
            }
        }

        override fun detachActiveSources(input: VideoMiningInput): Boolean {
            detachedInputs += input
            return detachActiveSourcesResult
        }

        override suspend fun startVideo(input: VideoMiningInput) {
            startCalls += 1
            startGate?.await()
            mutableState.value =
                MiningRunState.Starting(
                    runId = "retry-run",
                    progress = MiningProgress(0, 0, "Starting"),
                )
        }

        override suspend fun confirmCuration(
            runId: String,
            requestId: String,
            selection: List<CurationSelection>,
            pageIndex: Long?,
            knownCandidateIds: List<String>,
        ) {
            confirmCalls += 1
            confirmedPageIndex = pageIndex
            confirmedSelection = selection
            confirmedKnownCandidateIds = knownCandidateIds
            confirmGate?.await()
            if (mutableState.value is MiningRunState.Curating) {
                mutableState.value =
                    MiningRunState.Running(runId, MiningProgress(0, 0, "Running"))
            }
        }

        override suspend fun cancel(runId: String) {
            cancelCalls += 1
            cancelGate?.await()
            if (acknowledgeCancellationImmediately) {
                mutableState.value = MiningRunState.Cancelled(runId, null)
            }
        }

        override suspend fun reset() {
            resetCalls += 1
            resetGate?.await()
            mutableState.value = MiningRunState.Idle
        }

        fun transitionTo(state: MiningRunState) {
            mutableState.value = state
        }
    }

    private companion object {
        val NO_DEFINITION_LOOKUP =
            DefinitionLookupService { _, term, _ ->
                Result.success(DefinitionResult(term, term, emptyList()))
            }

        fun document(
            uri: String,
            displayName: String,
        ): SafDocument = SafDocument(uri, displayName, mimeType = null, sizeBytes = null)

        fun curationRequest(): CurationRequest {
            val sentence =
                CurationSentence(
                    sentenceId = "sentence",
                    sentence = "魚を食べる。",
                    sentenceFurigana = "魚を食べる。",
                    sentenceReading = "さかなをたべる",
                    startTime = 0.0,
                    endTime = 1.0,
                    duration = 1.0,
                )
            return CurationRequest(
                runId = "run",
                requestId = "request",
                candidates =
                    listOf(
                        CurationCandidate(
                            candidateId = "candidate",
                            minedForm = "食べる",
                            surface = "食べる",
                            lemma = "食べる",
                            reading = "たべる",
                            expressionReading = "たべる",
                            partOfSpeech = "動詞",
                            frequencyRank = 10,
                            occurrenceCount = 1,
                            defaultSentenceId = sentence.sentenceId,
                            sentences = listOf(sentence),
                        ),
                    ),
            )
        }

        fun result(): ProcessingResult =
            ProcessingResult(
                totalWordsFound = 3,
                newWordsFound = 2,
                cardsCreated = 1,
                errors = emptyList(),
                elapsedTime = 1.5,
                comprehensionPercentage = 80.0,
                cardIds = listOf(42),
                videoFile = "video.mkv",
                subtitleFile = "subtitle.srt",
                minedForms = listOf("食べる"),
                ankiWriteState = AnkiWriteState.NOTE_WRITE_CONFIRMED,
                failureIsTransient = false,
            )
    }
}
