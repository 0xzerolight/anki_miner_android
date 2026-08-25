package com.ankiminer.android.vm

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.CreationExtras
import com.ankiminer.android.MainDispatcherRule
import com.ankiminer.android.data.RuntimeWorkCoordinator
import com.ankiminer.android.data.resources.InstalledAudioPack
import com.ankiminer.android.dictionary.CurationDefinition
import com.ankiminer.android.dictionary.DefinitionLookupService
import com.ankiminer.android.dictionary.DefinitionResult
import com.ankiminer.android.diagnostics.log.AppLog
import com.ankiminer.android.diagnostics.log.LogLevel
import com.ankiminer.android.diagnostics.log.NoOpSink
import com.ankiminer.android.diagnostics.log.RecordingLogSink
import com.ankiminer.android.engine.AudioTrackInfo
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
import com.ankiminer.android.mining.ENGINE_DEFAULT_SUBTITLE_OFFSET
import com.ankiminer.android.mining.FakeMiningRepository
import com.ankiminer.android.mining.MiningFailure
import com.ankiminer.android.mining.MiningLane
import com.ankiminer.android.mining.MiningProgress
import com.ankiminer.android.mining.MiningRepository
import com.ankiminer.android.mining.MiningRunState
import com.ankiminer.android.mining.ProcessingResult
import com.ankiminer.android.mining.VideoMiningInput
import com.ankiminer.android.subtitles.SubtitleCueLookupService
import com.ankiminer.android.timing.TimingPreviewBusyException
import com.ankiminer.android.timing.TimingPreviewOpener
import com.ankiminer.android.timing.TimingPreviewSession
import com.ankiminer.android.tracks.AudioTrackList
import com.ankiminer.android.tracks.AudioTrackProbeBusyException
import com.ankiminer.android.tracks.AudioTrackProbeFailedException
import com.ankiminer.android.tracks.AudioTrackProbeOpener
import com.ankiminer.android.ui.video.AudioTrackPickerError
import com.ankiminer.android.ui.video.DocumentSelectionError
import com.ankiminer.android.ui.video.TimingPreviewError
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.TestScope
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
class MediaMiningViewModelTest {
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
                mediaViewModel(
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
            viewModel.focusCandidate(request.candidates.first().candidateId)

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
                mediaViewModel(
                    repository,
                    ImmediateSafBroker(),
                    definitionLookup = lookup,
                )
            val request = curationRequest()
            repository.transitionTo(MiningRunState.Curating(request))
            val collection =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    viewModel.uiState.collect {}
                }
            viewModel.focusCandidate(request.candidates.first().candidateId)

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
                mediaViewModel(
                    repository,
                    ImmediateSafBroker(),
                    definitionLookup = lookup,
                )
            val request = curationRequest()
            repository.transitionTo(MiningRunState.Curating(request))
            val collection =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    viewModel.uiState.collect {}
                }
            viewModel.focusCandidate(request.candidates.first().candidateId)

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
                mediaViewModel(
                    repository,
                    ImmediateSafBroker(),
                    definitionLookup = lookup,
                )
            val collection =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    viewModel.uiState.collect {}
                }
            val request = curationRequest()

            repository.transitionTo(MiningRunState.Curating(request))
            viewModel.focusCandidate(request.candidates.first().candidateId)
            advanceUntilIdle()
            repository.transitionTo(MiningRunState.Idle)
            advanceUntilIdle()
            assertNull(viewModel.uiState.value.curation)
            repository.transitionTo(MiningRunState.Curating(request))
            viewModel.focusCandidate(request.candidates.first().candidateId)
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
            MediaMiningViewModel.Factory(
                repository = RecordingRepository(),
                safBroker = ImmediateSafBroker(),
                lane = MiningLane.VIDEO,
                definitionLookup = lookup,
                savedStateHandleFactory = { SavedStateHandle() },
            )

        assertNotNull(factory.create(MediaMiningViewModel::class.java, CreationExtras.Empty))
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
                mediaViewModel(
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
    fun curatingAudioMediaMarksThePlayerAudioOnly() =
        runTest(mainDispatcherRule.dispatcher) {
            val request = curationRequest()
            val media =
                CurationMediaBinding(
                    videoPath = "/cache/episode.m4b",
                    subtitlePath = "/cache/episode.srt",
                    audioOnly = true,
                )
            val repository =
                RecordingRepository(MiningRunState.Curating(request, media = media))
            val viewModel =
                mediaViewModel(
                    repository = repository,
                    safBroker = ImmediateSafBroker(),
                    lane = MiningLane.AUDIO,
                )

            runCurrent()

            assertTrue(requireNotNull(viewModel.uiState.value.curation?.player).audioOnly)
        }

    @Test
    fun curationPlayerStateCarriesTheRunsAudioTrackOverride() =
        runTest(mainDispatcherRule.dispatcher) {
            val request = curationRequest()
            val media =
                CurationMediaBinding(
                    videoPath = "/cache/video.mkv",
                    subtitlePath = "/cache/subtitle.srt",
                    audioTrackOverride = 1L,
                )
            val repository =
                RecordingRepository(MiningRunState.Curating(request, media = media))
            val viewModel =
                mediaViewModel(
                    repository = repository,
                    safBroker = ImmediateSafBroker(),
                )

            runCurrent()

            assertEquals(1L, viewModel.uiState.value.curation?.player?.audioTrackOverride)
        }

    @Test
    fun failedCueLookupLeavesThePlayerUsableWithoutCues() =
        runTest(mainDispatcherRule.dispatcher) {
            val request = curationRequest()
            val media = CurationMediaBinding("/cache/video.mkv", "/cache/subtitle.srt")
            val repository =
                RecordingRepository(MiningRunState.Curating(request, media = media))
            val viewModel =
                mediaViewModel(
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
                mediaViewModel(
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
                mediaViewModel(
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
                mediaViewModel(
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
            val viewModel = mediaViewModel(repository, ImmediateSafBroker())
            val observed = mutableListOf<NavigationWorkflowState>()
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
            collection.cancel()
        }

    @Test
    fun savedIdleSelectionsRestoreByRevalidatingBothUris() =
        runTest(mainDispatcherRule.dispatcher) {
            val savedState = SavedStateHandle()
            SavedDocumentSelectionStore(savedState, "videoMining.document").save(
                document("content://test/restored-video.mkv", "stale-video-name.mkv"),
            )
            SavedDocumentSelectionStore(savedState, "videoMining.subtitle").save(
                document("content://test/restored-subtitle.srt", "stale-subtitle-name.srt"),
            )
            val broker = ImmediateSafBroker()

            val viewModel =
                mediaViewModel(
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

            val rejectedState = SavedStateHandle()
            val rejectedStore =
                SavedDocumentSelectionStore(rejectedState, "videoMining.subtitle")
            rejectedStore.save(
                document("content://test/restored-transcript.txt", "stale-transcript.srt"),
            )
            val rejectedBroker = ImmediateSafBroker()

            val rejectedViewModel =
                mediaViewModel(
                    repository = RecordingRepository(),
                    safBroker = rejectedBroker,
                    savedStateHandle = rejectedState,
                )
            runCurrent()

            assertEquals(
                DocumentSelectionError.SUBTITLE,
                rejectedViewModel.uiState.value.subtitle.error,
            )
            assertNull(rejectedViewModel.uiState.value.subtitle.document)
            assertNull(rejectedStore.restore())
            assertNull(rejectedState.get<String>("videoMining.subtitle.uri"))
            assertNull(rejectedState.get<String>("videoMining.subtitle.displayName"))
            assertEquals(
                listOf("content://test/restored-transcript.txt"),
                rejectedBroker.retainedUris,
            )
            assertEquals(
                listOf("content://test/restored-transcript.txt"),
                rejectedBroker.releasedUris,
            )
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
                mediaViewModel(
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
                SavedDocumentSelectionStore(savedState, "videoMining.document")
            selectionStore.save(
                document("content://test/revoked-video.mkv", "revoked-video.mkv"),
            )
            val broker = ControlledSafBroker()

            val viewModel =
                mediaViewModel(
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
            assertNull(savedState.get<String>("videoMining.document.uri"))
            assertNull(savedState.get<String>("videoMining.document.displayName"))
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
                mediaViewModel(
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
                mediaViewModel(
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
    fun replacingTransientlyUnavailableSavedVideoReleasesItsDurableGrant() =
        runTest(mainDispatcherRule.dispatcher) {
            val oldUri = "content://test/temporarily-unavailable.mkv"
            val newUri = "content://test/replacement.mkv"
            val inventory = TransientSafSelectionInventory()
            inventory.putSelection(
                SafSelectionSlot.VIDEO,
                SafSelectionRecord(oldUri, "temporarily-unavailable.mkv"),
            )
            val broker = ControlledSafBroker()
            val viewModel =
                mediaViewModel(
                    repository = RecordingRepository(),
                    safBroker = broker,
                    selectionInventory = inventory,
                    selectionIoDispatcher = mainDispatcherRule.dispatcher,
                )
            runCurrent()
            broker.fail(
                oldUri,
                SafAccessException(
                    SafAccessFailureKind.PROVIDER_UNAVAILABLE,
                    "provider updating",
                ),
            )
            runCurrent()

            viewModel.onVideoPicked(newUri)
            runCurrent()
            broker.succeed(newUri, "replacement.mkv")
            runCurrent()

            assertEquals(newUri, inventory.selection(SafSelectionSlot.VIDEO)?.uri)
            assertEquals("replacement.mkv", viewModel.uiState.value.video.document?.displayName)
            assertEquals(listOf(oldUri), broker.releasedUris)
        }

    @Test
    fun failedVideoInventoryCommitReleasesNewlyAcquiredGrant() =
        runTest(mainDispatcherRule.dispatcher) {
            val inventory = FailOnceSelectionInventory()
            val broker = ImmediateSafBroker()
            val viewModel =
                mediaViewModel(
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
    fun unsupportedSourceAndTranscriptExtensionsAreRejectedWithoutPersistence() =
        runTest(mainDispatcherRule.dispatcher) {
            val audioInventory = TransientSafSelectionInventory()
            val audioBroker = ImmediateSafBroker()
            val audioViewModel =
                mediaViewModel(
                    repository = RecordingRepository(),
                    safBroker = audioBroker,
                    selectionInventory = audioInventory,
                    selectionIoDispatcher = mainDispatcherRule.dispatcher,
                    lane = MiningLane.AUDIO,
                )

            audioViewModel.onVideoPicked("content://test/episode.mkv")
            runCurrent()

            assertEquals(
                DocumentSelectionError.AUDIO_TYPE,
                audioViewModel.uiState.value.video.error,
            )
            assertNull(audioViewModel.uiState.value.video.document)
            assertNull(audioInventory.selection(SafSelectionSlot.AUDIO))
            assertEquals(listOf("content://test/episode.mkv"), audioBroker.releasedUris)

            listOf(MiningLane.VIDEO, MiningLane.AUDIO).forEach { lane ->
                val inventory = TransientSafSelectionInventory()
                val broker = ImmediateSafBroker()
                val viewModel =
                    mediaViewModel(
                        repository = RecordingRepository(),
                        safBroker = broker,
                        selectionInventory = inventory,
                        selectionIoDispatcher = mainDispatcherRule.dispatcher,
                        lane = lane,
                    )
                val uri = "content://test/${lane.name}-transcript.txt"

                viewModel.onSubtitlePicked(uri)
                runCurrent()

                assertEquals(
                    DocumentSelectionError.SUBTITLE,
                    viewModel.uiState.value.subtitle.error,
                )
                assertNull(viewModel.uiState.value.subtitle.document)
                assertNull(inventory.selection(lane.subtitleSlot))
                assertEquals(listOf(uri), broker.releasedUris)
            }
        }

    @Test
    fun audioLaneAcceptsEveryDesktopAudioExtension() =
        runTest(mainDispatcherRule.dispatcher) {
            val inventory = TransientSafSelectionInventory()
            val viewModel =
                mediaViewModel(
                    repository = RecordingRepository(),
                    safBroker = ImmediateSafBroker(),
                    selectionInventory = inventory,
                    selectionIoDispatcher = mainDispatcherRule.dispatcher,
                    lane = MiningLane.AUDIO,
                )

            assertEquals(
                setOf("m4b", "mp3", "m4a", "aac", "ogg", "opus", "flac", "wav"),
                AUDIO_EXTENSIONS,
            )

            AUDIO_EXTENSIONS.forEach { extension ->
                viewModel.onVideoPicked("content://test/episode.$extension")
                runCurrent()

                assertNull(extension, viewModel.uiState.value.video.error)
                assertEquals(
                    "episode.$extension",
                    viewModel.uiState.value.video.document?.displayName,
                )
            }
        }

    @Test
    fun audioLanePersistsOnlyToItsOwnDocumentSlot() =
        runTest(mainDispatcherRule.dispatcher) {
            val existingVideo =
                SafSelectionRecord(
                    uri = "content://test/existing-video.mkv",
                    displayName = "existing-video.mkv",
                )
            val inventory =
                TransientSafSelectionInventory().also {
                    it.putSelection(SafSelectionSlot.VIDEO, existingVideo)
                }
            val viewModel =
                mediaViewModel(
                    repository = RecordingRepository(),
                    safBroker = ImmediateSafBroker(),
                    selectionInventory = inventory,
                    selectionIoDispatcher = mainDispatcherRule.dispatcher,
                    lane = MiningLane.AUDIO,
                )

            viewModel.onVideoPicked("content://test/episode.opus")
            runCurrent()

            assertEquals(
                "content://test/episode.opus",
                inventory.selection(SafSelectionSlot.AUDIO)?.uri,
            )
            assertEquals(existingVideo, inventory.selection(SafSelectionSlot.VIDEO))
        }

    @Test
    fun audioLaneWarnsWhenAudioFieldIsUnmappedAndPictureFieldIsMapped() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel =
                mediaViewModel(
                    repository = RecordingRepository(),
                    safBroker = ImmediateSafBroker(),
                    fieldMap = flowOf(mapOf("picture" to "Picture")),
                    lane = MiningLane.AUDIO,
                )

            runCurrent()

            assertTrue(viewModel.uiState.value.audioFieldUnmapped)
        }

    @Test
    fun audioLaneDoesNotWarnWhenBothMediaFieldsAreUnmapped() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel =
                mediaViewModel(
                    repository = RecordingRepository(),
                    safBroker = ImmediateSafBroker(),
                    fieldMap = flowOf(emptyMap()),
                    lane = MiningLane.AUDIO,
                )

            runCurrent()

            assertFalse(viewModel.uiState.value.audioFieldUnmapped)
        }

    @Test
    fun audioLaneDoesNotWarnWhenBothMediaFieldsAreMapped() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel =
                mediaViewModel(
                    repository = RecordingRepository(),
                    safBroker = ImmediateSafBroker(),
                    fieldMap =
                        flowOf(
                            mapOf(
                                "audio" to "Audio",
                                "picture" to "Picture",
                            ),
                        ),
                    lane = MiningLane.AUDIO,
                )

            runCurrent()

            assertFalse(viewModel.uiState.value.audioFieldUnmapped)
        }

    @Test
    fun videoLaneDoesNotWarnWhenAudioFieldIsUnmappedAndPictureFieldIsMapped() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel =
                mediaViewModel(
                    repository = RecordingRepository(),
                    safBroker = ImmediateSafBroker(),
                    fieldMap = flowOf(mapOf("picture" to "Picture")),
                    lane = MiningLane.VIDEO,
                )

            runCurrent()

            assertFalse(viewModel.uiState.value.audioFieldUnmapped)
        }

    @Test
    fun warnsWhenExpressionAudioUnmappedAndUsablePackInstalled() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel =
                mediaViewModel(
                    repository = RecordingRepository(),
                    safBroker = ImmediateSafBroker(),
                    fieldMap = flowOf(mapOf("word" to "Word")),
                    audioPacks = flowOf(listOf(usableAudioPack())),
                    lane = MiningLane.VIDEO,
                )

            runCurrent()

            assertTrue(viewModel.uiState.value.expressionAudioFieldUnmapped)
        }

    @Test
    fun audioLaneAlsoWarnsWhenExpressionAudioUnmapped() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel =
                mediaViewModel(
                    repository = RecordingRepository(),
                    safBroker = ImmediateSafBroker(),
                    fieldMap = flowOf(mapOf("word" to "Word")),
                    audioPacks = flowOf(listOf(usableAudioPack())),
                    lane = MiningLane.AUDIO,
                )

            runCurrent()

            assertTrue(viewModel.uiState.value.expressionAudioFieldUnmapped)
        }

    @Test
    fun doesNotWarnWhenExpressionAudioUnmappedWithoutInstalledPacks() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel =
                mediaViewModel(
                    repository = RecordingRepository(),
                    safBroker = ImmediateSafBroker(),
                    fieldMap = flowOf(mapOf("word" to "Word")),
                    audioPacks = flowOf(emptyList()),
                    lane = MiningLane.VIDEO,
                )

            runCurrent()

            assertFalse(viewModel.uiState.value.expressionAudioFieldUnmapped)
        }

    @Test
    fun doesNotWarnWhenExpressionAudioMapped() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel =
                mediaViewModel(
                    repository = RecordingRepository(),
                    safBroker = ImmediateSafBroker(),
                    fieldMap = flowOf(mapOf("expression_audio" to "WordAudio")),
                    audioPacks = flowOf(listOf(usableAudioPack())),
                    lane = MiningLane.VIDEO,
                )

            runCurrent()

            assertFalse(viewModel.uiState.value.expressionAudioFieldUnmapped)
        }

    @Test
    fun doesNotWarnWhenInstalledPackIsUnusableForUnmappedField() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel =
                mediaViewModel(
                    repository = RecordingRepository(),
                    safBroker = ImmediateSafBroker(),
                    fieldMap = flowOf(mapOf("word" to "Word")),
                    audioPacks = flowOf(listOf(unusableAudioPack())),
                    lane = MiningLane.VIDEO,
                )

            runCurrent()

            // An unusable pack cannot produce audio, so it must not trigger the
            // unmapped-field advisory -- it triggers the unusable-pack one instead.
            assertFalse(viewModel.uiState.value.expressionAudioFieldUnmapped)
            assertTrue(viewModel.uiState.value.unusableAudioPackInstalled)
        }

    @Test
    fun warnsWhenAnInstalledAudioPackIsUnusable() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel =
                mediaViewModel(
                    repository = RecordingRepository(),
                    safBroker = ImmediateSafBroker(),
                    audioPacks = flowOf(listOf(usableAudioPack(), unusableAudioPack())),
                    lane = MiningLane.VIDEO,
                )

            runCurrent()

            assertTrue(viewModel.uiState.value.unusableAudioPackInstalled)
        }

    @Test
    fun doesNotWarnWhenAllInstalledPacksAreUsable() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel =
                mediaViewModel(
                    repository = RecordingRepository(),
                    safBroker = ImmediateSafBroker(),
                    audioPacks = flowOf(listOf(usableAudioPack())),
                    lane = MiningLane.VIDEO,
                )

            runCurrent()

            assertFalse(viewModel.uiState.value.unusableAudioPackInstalled)
        }

    @Test
    fun doesNotWarnWithNoPacksInstalled() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel =
                mediaViewModel(
                    repository = RecordingRepository(),
                    safBroker = ImmediateSafBroker(),
                    audioPacks = flowOf(emptyList()),
                    lane = MiningLane.VIDEO,
                )

            runCurrent()

            assertFalse(viewModel.uiState.value.unusableAudioPackInstalled)
        }

    @Test
    fun videoLaneStillAcceptsVideoExtension() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel =
                mediaViewModel(
                    repository = RecordingRepository(),
                    safBroker = ImmediateSafBroker(),
                    lane = MiningLane.VIDEO,
                )

            viewModel.onVideoPicked("content://test/episode.mkv")
            runCurrent()

            assertNull(viewModel.uiState.value.video.error)
            assertEquals("episode.mkv", viewModel.uiState.value.video.document?.displayName)
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
            val viewModel = mediaViewModel(repository, ImmediateSafBroker())
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
    fun startPassesPerRunSubtitleOffsetOverride() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = RecordingRepository()
            val viewModel = mediaViewModel(repository, ImmediateSafBroker())
            selectDocuments(viewModel)
            viewModel.setSubtitleOffsetDraft("1.5")
            runCurrent()

            viewModel.start()
            runCurrent()

            assertEquals(1.5, repository.startedInputs.single().subtitleOffsetOverride!!, 0.0)
        }

    @Test
    fun effectiveSubtitleOffsetPrefersDraftThenGlobalThenEngineDefault() =
        runTest(mainDispatcherRule.dispatcher) {
            val globalOffset = MutableStateFlow<Double?>(0.25)
            val viewModel =
                mediaViewModel(
                    repository = RecordingRepository(),
                    safBroker = ImmediateSafBroker(),
                    effectiveSubtitleOffset = globalOffset,
                )
            runCurrent()
            assertEquals(0.25, viewModel.uiState.value.effectiveSubtitleOffset, 0.0)

            viewModel.setSubtitleOffsetDraft("1.5")
            runCurrent()
            assertEquals(1.5, viewModel.uiState.value.effectiveSubtitleOffset, 0.0)

            viewModel.setSubtitleOffsetDraft("")
            globalOffset.value = null
            runCurrent()
            assertEquals(
                ENGINE_DEFAULT_SUBTITLE_OFFSET,
                viewModel.uiState.value.effectiveSubtitleOffset,
                0.0,
            )
        }

    @Test
    fun blankSubtitleOffsetUsesGlobalOrEngineDefault() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = RecordingRepository()
            val viewModel = mediaViewModel(repository, ImmediateSafBroker())
            selectDocuments(viewModel)
            viewModel.setSubtitleOffsetDraft("")
            runCurrent()

            viewModel.start()
            runCurrent()

            assertNull(repository.startedInputs.single().subtitleOffsetOverride)
        }

    @Test
    fun malformedOrNonFiniteSubtitleOffsetBlocksStart() =
        runTest(mainDispatcherRule.dispatcher) {
            listOf("abc", "1e309").forEach { draft ->
                val repository = RecordingRepository()
                val viewModel = mediaViewModel(repository, ImmediateSafBroker())
                selectDocuments(viewModel)
                viewModel.setSubtitleOffsetDraft(draft)
                runCurrent()

                assertTrue(viewModel.uiState.value.subtitleOffsetDraftInvalid)
                assertFalse(viewModel.uiState.value.canStart)
                viewModel.start()
                runCurrent()

                assertEquals(0, repository.startCalls)
            }
        }

    @Test
    fun retryKeepsPerRunSubtitleOffsetOverride() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = RecordingRepository()
            val viewModel = mediaViewModel(repository, ImmediateSafBroker())
            selectDocuments(viewModel)
            viewModel.setSubtitleOffsetDraft("1.5")
            repository.transitionTo(
                MiningRunState.Failed(
                    runId = "run",
                    failure = MiningFailure("retry", retryable = true),
                    result = result(),
                ),
            )
            runCurrent()

            viewModel.retry()
            runCurrent()

            assertEquals(1.5, repository.startedInputs.single().subtitleOffsetOverride!!, 0.0)
        }

    @Test
    fun subtitleOffsetDraftRestoresFromSavedStateHandle() =
        runTest(mainDispatcherRule.dispatcher) {
            val savedState = SavedStateHandle()
            val original =
                mediaViewModel(
                    repository = RecordingRepository(),
                    safBroker = ImmediateSafBroker(),
                    savedStateHandle = savedState,
                )
            original.setSubtitleOffsetDraft("1.5")

            val restored =
                mediaViewModel(
                    repository = RecordingRepository(),
                    safBroker = ImmediateSafBroker(),
                    savedStateHandle = savedState,
                )
            runCurrent()

            assertEquals("1.5", restored.uiState.value.subtitleOffsetDraft)
        }

    @Test
    fun oversizedSubtitleOffsetPasteIsBoundedInUiAndSavedStateForBothLanes() =
        runTest(mainDispatcherRule.dispatcher) {
            val bounded = "1".repeat(63) + "😀"
            val oversized = bounded + "2".repeat(300_000)

            listOf(MiningLane.VIDEO, MiningLane.AUDIO).forEach { lane ->
                val savedState = SavedStateHandle()
                val viewModel =
                    mediaViewModel(
                        repository = RecordingRepository(),
                        safBroker = ImmediateSafBroker(),
                        savedStateHandle = savedState,
                        lane = lane,
                    )

                viewModel.setSubtitleOffsetDraft(oversized)
                runCurrent()

                assertEquals(bounded, viewModel.uiState.value.subtitleOffsetDraft)
                assertEquals(
                    bounded,
                    savedState.get<String>("${lane.savedStateKeyPrefix}.subtitleOffsetDraft"),
                )
            }
        }

    @Test
    fun oversizedRestoredSubtitleOffsetDraftIsBoundedAndRepublished() =
        runTest(mainDispatcherRule.dispatcher) {
            val bounded = "1".repeat(63) + "😀"
            val key = "videoMining.subtitleOffsetDraft"
            val savedState = SavedStateHandle(mapOf(key to bounded + "2".repeat(300_000)))

            val restored =
                mediaViewModel(
                    repository = RecordingRepository(),
                    safBroker = ImmediateSafBroker(),
                    savedStateHandle = savedState,
                )
            runCurrent()

            assertEquals(bounded, restored.uiState.value.subtitleOffsetDraft)
            assertEquals(bounded, savedState.get<String>(key))
        }

    @Test
    fun audioLaneSavedStateKeysDoNotUseTheVideoLanePrefix() =
        runTest(mainDispatcherRule.dispatcher) {
            val savedState = SavedStateHandle()
            val viewModel =
                mediaViewModel(
                    repository = RecordingRepository(),
                    safBroker = ImmediateSafBroker(),
                    savedStateHandle = savedState,
                    lane = MiningLane.AUDIO,
                )

            viewModel.onVideoPicked("content://test/episode.m4b")
            viewModel.onSubtitlePicked("content://test/episode.srt")
            viewModel.setSubtitleOffsetDraft("1.5")
            runCurrent()

            assertTrue(
                savedState.keys().containsAll(
                    setOf(
                        "audioMining.document.uri",
                        "audioMining.document.displayName",
                        "audioMining.subtitle.uri",
                        "audioMining.subtitle.displayName",
                        "audioMining.subtitleOffsetDraft",
                    ),
                ),
            )
            assertFalse(savedState.keys().any { it.startsWith("videoMining.") })
        }

    @Test
    fun resetClearsSubtitleOffsetDraft() =
        runTest(mainDispatcherRule.dispatcher) {
            val savedState = SavedStateHandle()
            val repository = RecordingRepository()
            val viewModel =
                mediaViewModel(
                    repository = repository,
                    safBroker = ImmediateSafBroker(),
                    savedStateHandle = savedState,
                )
            viewModel.setSubtitleOffsetDraft("1.5")
            repository.transitionTo(MiningRunState.Success("run", result()))
            runCurrent()

            viewModel.reset()
            runCurrent()

            assertEquals("", viewModel.uiState.value.subtitleOffsetDraft)
            val restored =
                mediaViewModel(
                    repository = RecordingRepository(),
                    safBroker = ImmediateSafBroker(),
                    savedStateHandle = savedState,
                )
            runCurrent()
            assertEquals("", restored.uiState.value.subtitleOffsetDraft)
        }

    @Test
    fun openingTimingPreviewLoadsUnshiftedCuesAndSeedsTheEffectiveOffset() =
        runTest(mainDispatcherRule.dispatcher) {
            val cue = SubtitleCue(1.0, 2.0, "猫だ。")
            val openedSubtitles = mutableListOf<SafDocument>()
            val opener =
                TimingPreviewOpener { subtitle ->
                    openedSubtitles += subtitle
                    Result.success(TimingPreviewSession(listOf(cue)) {})
                }
            val globalOffset = MutableStateFlow<Double?>(0.25)
            val viewModel =
                mediaViewModel(
                    repository = RecordingRepository(),
                    safBroker = ImmediateSafBroker(),
                    timingPreviewOpener = opener,
                    timingPreviewCleanupDispatcher = mainDispatcherRule.dispatcher,
                    effectiveSubtitleOffset = globalOffset,
                )
            selectDocuments(viewModel)
            viewModel.setSubtitleOffsetDraft("1.5")
            runCurrent()

            viewModel.openTimingPreview()
            runCurrent()

            assertEquals("subtitle.SRT", openedSubtitles.single().displayName)
            val state = requireNotNull(viewModel.timingPreviewState.value)
            assertEquals(1.5, state.initialOffset, 0.0)
            assertEquals(1.5, state.workingOffset, 0.0)
            assertEquals(listOf(cue), state.cues)
        }

    @Test
    fun timingPreviewDoesNotOpenWhileAReplacementTranscriptIsResolving() =
        runTest(mainDispatcherRule.dispatcher) {
            val broker = ControlledSafBroker()
            val openedSubtitles = mutableListOf<SafDocument>()
            val viewModel =
                mediaViewModel(
                    repository = RecordingRepository(),
                    safBroker = broker,
                    timingPreviewOpener =
                        TimingPreviewOpener { subtitle ->
                            openedSubtitles += subtitle
                            Result.success(TimingPreviewSession(emptyList()) {})
                        },
                    timingPreviewCleanupDispatcher = mainDispatcherRule.dispatcher,
                )
            viewModel.onVideoPicked("content://test/video.mkv")
            viewModel.onSubtitlePicked("content://test/old.srt")
            runCurrent()
            broker.succeed("content://test/video.mkv", "video.mkv")
            broker.succeed("content://test/old.srt", "old.srt")
            runCurrent()
            assertEquals("old.srt", viewModel.uiState.value.subtitle.document?.displayName)

            viewModel.onSubtitlePicked("content://test/replacement.srt")
            runCurrent()
            assertTrue(viewModel.uiState.value.subtitle.isResolving)

            viewModel.openTimingPreview()
            runCurrent()

            assertTrue(openedSubtitles.isEmpty())
            assertNull(viewModel.timingPreviewState.value)

            broker.succeed("content://test/replacement.srt", "replacement.srt")
            advanceUntilIdle()
        }

    @Test
    fun applyingTimingPreviewWritesDraftClearsOverlayAndClosesSession() =
        runTest(mainDispatcherRule.dispatcher) {
            var closeCount = 0
            val viewModel =
                timingPreviewViewModel(
                    session = TimingPreviewSession(emptyList()) { closeCount += 1 },
                )
            selectDocuments(viewModel)
            runCurrent()
            viewModel.openTimingPreview()
            runCurrent()
            viewModel.setTimingPreviewWorkingOffset(1.75)

            viewModel.applyTimingPreview()
            runCurrent()

            assertEquals("1.75", viewModel.uiState.value.subtitleOffsetDraft)
            assertNull(viewModel.timingPreviewState.value)
            assertEquals(1, closeCount)
        }

    @Test
    fun timingPreviewFailureExposesStableErrorWithoutOpeningOverlay() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel =
                mediaViewModel(
                    repository = RecordingRepository(),
                    safBroker = ImmediateSafBroker(),
                    timingPreviewOpener =
                        TimingPreviewOpener {
                            Result.failure(TimingPreviewBusyException())
                        },
                    timingPreviewCleanupDispatcher = mainDispatcherRule.dispatcher,
                )
            selectDocuments(viewModel)
            runCurrent()

            viewModel.openTimingPreview()
            runCurrent()

            assertNull(viewModel.timingPreviewState.value)
            assertEquals(TimingPreviewError.BUSY, viewModel.uiState.value.timingPreviewError)
            assertFalse(viewModel.uiState.value.timingPreviewPending)
        }

    @Test
    fun cancellingTimingPreviewDiscardsWorkingOffsetAndClosesSession() =
        runTest(mainDispatcherRule.dispatcher) {
            var closeCount = 0
            val viewModel =
                timingPreviewViewModel(
                    session = TimingPreviewSession(emptyList()) { closeCount += 1 },
                )
            selectDocuments(viewModel)
            viewModel.setSubtitleOffsetDraft("0.5")
            runCurrent()
            viewModel.openTimingPreview()
            runCurrent()
            viewModel.setTimingPreviewWorkingOffset(2.0)

            viewModel.closeTimingPreview()
            runCurrent()

            assertEquals("0.5", viewModel.uiState.value.subtitleOffsetDraft)
            assertNull(viewModel.timingPreviewState.value)
            assertEquals(1, closeCount)
        }

    @Test
    fun viewModelTeardownClosesAnOpenTimingPreviewSession() =
        runTest(mainDispatcherRule.dispatcher) {
            var closeCount = 0
            val store = ViewModelStore()
            val factory =
                MediaMiningViewModel.Factory(
                    repository = RecordingRepository(),
                    safBroker = ImmediateSafBroker(),
                    lane = MiningLane.VIDEO,
                    definitionLookup = NO_DEFINITION_LOOKUP,
                    timingPreviewOpener =
                        TimingPreviewOpener {
                            Result.success(
                                TimingPreviewSession(emptyList()) { closeCount += 1 },
                            )
                        },
                    timingPreviewCleanupDispatcher = mainDispatcherRule.dispatcher,
                    savedStateHandleFactory = { SavedStateHandle() },
                )
            val viewModel =
                ViewModelProvider.create(store, factory)[MediaMiningViewModel::class.java]
            selectDocuments(viewModel)
            runCurrent()
            viewModel.openTimingPreview()
            runCurrent()

            store.clear()
            runCurrent()

            assertEquals(1, closeCount)
        }

    @Test
    fun applyingAudioTrackPickerFromATwoTrackProbeSetsOverrideAndStartCarriesIt() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = RecordingRepository()
            val opener = FakeAudioTrackProbeOpener()
            val viewModel =
                mediaViewModel(repository, ImmediateSafBroker(), audioTrackProbeOpener = opener)
            selectDocuments(viewModel)
            runCurrent()

            applyAudioTrackOverride(viewModel, opener, twoTrackList(), selected = 2L)

            assertEquals(2L, viewModel.uiState.value.audioTrackOverride)
            assertNull(viewModel.audioTrackPickerState.value)

            viewModel.start()
            runCurrent()

            assertEquals(2L, repository.startedInputs.single().audioTrackOverride)
        }

    @Test
    fun dismissingAudioTrackPickerLeavesPreviousOverrideUntouched() =
        runTest(mainDispatcherRule.dispatcher) {
            val opener = FakeAudioTrackProbeOpener()
            val viewModel =
                mediaViewModel(RecordingRepository(), ImmediateSafBroker(), audioTrackProbeOpener = opener)
            selectDocuments(viewModel)
            runCurrent()
            applyAudioTrackOverride(viewModel, opener, twoTrackList(), selected = 2L)
            assertEquals(2L, viewModel.uiState.value.audioTrackOverride)

            viewModel.openAudioTrackPicker()
            runCurrent()
            opener.complete(Result.success(twoTrackList()))
            runCurrent()
            viewModel.selectAudioTrack(1L)
            viewModel.dismissAudioTrackPicker()
            runCurrent()

            assertEquals(2L, viewModel.uiState.value.audioTrackOverride)
            assertNull(viewModel.audioTrackPickerState.value)
        }

    @Test
    fun probeResultWithFewerThanTwoTracksNullsOverrideImmediately() =
        runTest(mainDispatcherRule.dispatcher) {
            val opener = FakeAudioTrackProbeOpener()
            val viewModel =
                mediaViewModel(RecordingRepository(), ImmediateSafBroker(), audioTrackProbeOpener = opener)
            selectDocuments(viewModel)
            runCurrent()
            applyAudioTrackOverride(viewModel, opener, twoTrackList(), selected = 2L)
            assertEquals(2L, viewModel.uiState.value.audioTrackOverride)

            viewModel.openAudioTrackPicker()
            runCurrent()
            val singleTrack = AudioTrackList(autoAudioIndex = 0L, tracks = listOf(audioTrack(0L)))
            opener.complete(Result.success(singleTrack))
            runCurrent()

            assertEquals(listOf(audioTrack(0L)), viewModel.audioTrackPickerState.value?.tracks)
            assertNull(viewModel.uiState.value.audioTrackOverride)
        }

    @Test
    fun staleOverridePreselectsAutoWhenReprobedTracksNoLongerContainIt() =
        runTest(mainDispatcherRule.dispatcher) {
            val opener = FakeAudioTrackProbeOpener()
            val viewModel =
                mediaViewModel(RecordingRepository(), ImmediateSafBroker(), audioTrackProbeOpener = opener)
            selectDocuments(viewModel)
            runCurrent()
            val threeTrack =
                AudioTrackList(autoAudioIndex = 0L, tracks = listOf(audioTrack(0L), audioTrack(3L)))
            applyAudioTrackOverride(viewModel, opener, threeTrack, selected = 3L)
            assertEquals(3L, viewModel.uiState.value.audioTrackOverride)

            viewModel.openAudioTrackPicker()
            runCurrent()
            opener.complete(Result.success(twoTrackList()))
            runCurrent()

            assertNull(viewModel.audioTrackPickerState.value?.selectedAudioIndex)
            assertEquals(3L, viewModel.uiState.value.audioTrackOverride)
        }

    @Test
    fun repickingVideoNullsAudioTrackOverride() =
        runTest(mainDispatcherRule.dispatcher) {
            val opener = FakeAudioTrackProbeOpener()
            val viewModel =
                mediaViewModel(RecordingRepository(), ImmediateSafBroker(), audioTrackProbeOpener = opener)
            selectDocuments(viewModel)
            runCurrent()
            applyAudioTrackOverride(viewModel, opener, twoTrackList(), selected = 2L)
            assertEquals(2L, viewModel.uiState.value.audioTrackOverride)

            viewModel.onVideoPicked("content://test/video2")
            runCurrent()

            assertNull(viewModel.uiState.value.audioTrackOverride)
        }

    @Test
    fun clearingVideoNullsAudioTrackOverride() =
        runTest(mainDispatcherRule.dispatcher) {
            val opener = FakeAudioTrackProbeOpener()
            val viewModel =
                mediaViewModel(RecordingRepository(), ImmediateSafBroker(), audioTrackProbeOpener = opener)
            selectDocuments(viewModel)
            runCurrent()
            applyAudioTrackOverride(viewModel, opener, twoTrackList(), selected = 2L)
            assertEquals(2L, viewModel.uiState.value.audioTrackOverride)

            viewModel.clearVideo()
            runCurrent()

            assertNull(viewModel.uiState.value.audioTrackOverride)
        }

    @Test
    fun runSuccessClearsAudioTrackOverride() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = RecordingRepository()
            val opener = FakeAudioTrackProbeOpener()
            val viewModel =
                mediaViewModel(repository, ImmediateSafBroker(), audioTrackProbeOpener = opener)
            selectDocuments(viewModel)
            runCurrent()
            applyAudioTrackOverride(viewModel, opener, twoTrackList(), selected = 2L)

            repository.transitionTo(MiningRunState.Success("run", result()))
            runCurrent()

            assertNull(viewModel.uiState.value.audioTrackOverride)
        }

    @Test
    fun runFailedKeepsAudioTrackOverrideAndRetryResendsIt() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = RecordingRepository()
            val opener = FakeAudioTrackProbeOpener()
            val viewModel =
                mediaViewModel(repository, ImmediateSafBroker(), audioTrackProbeOpener = opener)
            selectDocuments(viewModel)
            runCurrent()
            applyAudioTrackOverride(viewModel, opener, twoTrackList(), selected = 2L)

            repository.transitionTo(
                MiningRunState.Failed(
                    runId = "run",
                    failure = MiningFailure(message = "boom", retryable = true),
                    result = null,
                ),
            )
            runCurrent()
            assertEquals(2L, viewModel.uiState.value.audioTrackOverride)

            viewModel.retry()
            runCurrent()

            assertEquals(2L, repository.startedInputs.last().audioTrackOverride)
        }

    @Test
    fun runCancelledKeepsAudioTrackOverride() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = RecordingRepository()
            val opener = FakeAudioTrackProbeOpener()
            val viewModel =
                mediaViewModel(repository, ImmediateSafBroker(), audioTrackProbeOpener = opener)
            selectDocuments(viewModel)
            runCurrent()
            applyAudioTrackOverride(viewModel, opener, twoTrackList(), selected = 2L)

            repository.transitionTo(MiningRunState.Cancelled(runId = "run", result = null))
            runCurrent()

            assertEquals(2L, viewModel.uiState.value.audioTrackOverride)
        }

    @Test
    fun audioTrackProbeBusyExceptionMapsToBusyError() =
        runTest(mainDispatcherRule.dispatcher) {
            val opener = FakeAudioTrackProbeOpener()
            val viewModel =
                mediaViewModel(RecordingRepository(), ImmediateSafBroker(), audioTrackProbeOpener = opener)
            selectDocuments(viewModel)
            runCurrent()

            viewModel.openAudioTrackPicker()
            runCurrent()
            opener.complete(Result.failure(AudioTrackProbeBusyException()))
            runCurrent()

            assertEquals(AudioTrackPickerError.BUSY, viewModel.uiState.value.audioTrackPickerError)
            assertNull(viewModel.audioTrackPickerState.value)
            assertFalse(viewModel.uiState.value.audioTrackProbePending)
        }

    @Test
    fun otherAudioTrackProbeFailureMapsToProbeError() =
        runTest(mainDispatcherRule.dispatcher) {
            val opener = FakeAudioTrackProbeOpener()
            val viewModel =
                mediaViewModel(RecordingRepository(), ImmediateSafBroker(), audioTrackProbeOpener = opener)
            selectDocuments(viewModel)
            runCurrent()

            viewModel.openAudioTrackPicker()
            runCurrent()
            opener.complete(Result.failure(AudioTrackProbeFailedException()))
            runCurrent()

            assertEquals(AudioTrackPickerError.PROBE, viewModel.uiState.value.audioTrackPickerError)
        }

    @Test
    fun audioTrackPickerResultDroppedWhenRepositoryBecomesActiveWhileProbing() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = RecordingRepository()
            val opener = FakeAudioTrackProbeOpener()
            val viewModel =
                mediaViewModel(repository, ImmediateSafBroker(), audioTrackProbeOpener = opener)
            selectDocuments(viewModel)
            runCurrent()

            viewModel.openAudioTrackPicker()
            runCurrent()
            assertTrue(viewModel.uiState.value.audioTrackProbePending)

            // Simulates the repository becoming active through a path outside this ViewModel's
            // own (now-guarded) start()/retry() calls, e.g. an interrupted-run resume.
            repository.transitionTo(
                MiningRunState.Starting("run", MiningProgress(0, 3, "Starting")),
            )
            runCurrent()

            opener.complete(Result.success(twoTrackList()))
            runCurrent()

            assertNull(viewModel.audioTrackPickerState.value)
            assertFalse(viewModel.uiState.value.audioTrackProbePending)
        }

    @Test
    fun startIsRefusedWhileAudioTrackProbeIsPending() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = RecordingRepository()
            val opener = FakeAudioTrackProbeOpener()
            val viewModel =
                mediaViewModel(repository, ImmediateSafBroker(), audioTrackProbeOpener = opener)
            selectDocuments(viewModel)
            runCurrent()

            viewModel.openAudioTrackPicker()
            runCurrent()
            assertTrue(viewModel.uiState.value.audioTrackProbePending)

            viewModel.start()
            runCurrent()

            assertEquals(0, repository.startCalls)
        }

    @Test
    fun openAudioTrackPickerSurfacesBusyInsteadOfSilentlyRefusingDuringRuntimeWork() =
        runTest(mainDispatcherRule.dispatcher) {
            val runtimeWork = MutableStateFlow<RuntimeWorkCoordinator.Kind?>(null)
            val opener = FakeAudioTrackProbeOpener()
            val viewModel =
                mediaViewModel(
                    repository = RecordingRepository(),
                    safBroker = ImmediateSafBroker(),
                    runtimeWorkState = runtimeWork,
                    audioTrackProbeOpener = opener,
                )
            selectDocuments(viewModel)
            runCurrent()

            runtimeWork.value = RuntimeWorkCoordinator.Kind.MINING
            runCurrent()

            viewModel.openAudioTrackPicker()
            runCurrent()

            assertTrue(viewModel.uiState.value.audioTrackProbePending)
            assertEquals(1, opener.probedVideos.size)

            opener.complete(Result.failure(AudioTrackProbeBusyException()))
            runCurrent()

            assertEquals(AudioTrackPickerError.BUSY, viewModel.uiState.value.audioTrackPickerError)
            assertFalse(viewModel.uiState.value.audioTrackProbePending)
        }

    @Test
    fun openAudioTrackPickerIsRefusedWhileTimingPreviewIsPendingOrOpen() =
        runTest(mainDispatcherRule.dispatcher) {
            val timingGate = CompletableDeferred<Result<TimingPreviewSession>>()
            val audioOpener = FakeAudioTrackProbeOpener()
            val viewModel =
                mediaViewModel(
                    repository = RecordingRepository(),
                    safBroker = ImmediateSafBroker(),
                    timingPreviewOpener = TimingPreviewOpener { timingGate.await() },
                    timingPreviewCleanupDispatcher = mainDispatcherRule.dispatcher,
                    audioTrackProbeOpener = audioOpener,
                )
            selectDocuments(viewModel)
            runCurrent()

            viewModel.openTimingPreview()
            runCurrent()
            assertTrue(viewModel.uiState.value.timingPreviewPending)

            viewModel.openAudioTrackPicker()
            runCurrent()
            assertTrue(audioOpener.probedVideos.isEmpty())
            assertFalse(viewModel.uiState.value.audioTrackProbePending)

            timingGate.complete(Result.success(TimingPreviewSession(emptyList()) {}))
            runCurrent()
            assertNotNull(viewModel.timingPreviewState.value)

            // Timing preview overlay is now open (no longer merely pending) — still refused.
            viewModel.openAudioTrackPicker()
            runCurrent()
            assertTrue(audioOpener.probedVideos.isEmpty())
        }

    @Test
    fun openTimingPreviewIsRefusedWhileAudioTrackPickerIsPendingOrOpen() =
        runTest(mainDispatcherRule.dispatcher) {
            val opener = FakeAudioTrackProbeOpener()
            val openedSubtitles = mutableListOf<SafDocument>()
            val viewModel =
                mediaViewModel(
                    repository = RecordingRepository(),
                    safBroker = ImmediateSafBroker(),
                    timingPreviewOpener =
                        TimingPreviewOpener { subtitle ->
                            openedSubtitles += subtitle
                            Result.success(TimingPreviewSession(emptyList()) {})
                        },
                    timingPreviewCleanupDispatcher = mainDispatcherRule.dispatcher,
                    audioTrackProbeOpener = opener,
                )
            selectDocuments(viewModel)
            runCurrent()

            viewModel.openAudioTrackPicker()
            runCurrent()
            assertTrue(viewModel.uiState.value.audioTrackProbePending)

            viewModel.openTimingPreview()
            runCurrent()
            assertTrue(openedSubtitles.isEmpty())

            opener.complete(Result.success(twoTrackList()))
            runCurrent()
            assertNotNull(viewModel.audioTrackPickerState.value)

            // Picker dialog is now open (probe no longer pending) — still refused.
            viewModel.openTimingPreview()
            runCurrent()
            assertTrue(openedSubtitles.isEmpty())
        }

    @Test
    fun repickingAndClearingVideoAreRefusedWhileAudioTrackProbeIsPendingOrPickerIsOpen() =
        runTest(mainDispatcherRule.dispatcher) {
            val opener = FakeAudioTrackProbeOpener()
            val viewModel =
                mediaViewModel(RecordingRepository(), ImmediateSafBroker(), audioTrackProbeOpener = opener)
            selectDocuments(viewModel)
            runCurrent()
            val originalVideoUri = viewModel.uiState.value.video.document?.uri

            viewModel.openAudioTrackPicker()
            runCurrent()
            assertTrue(viewModel.uiState.value.audioTrackProbePending)

            viewModel.onVideoPicked("content://test/other-video")
            runCurrent()
            assertEquals(originalVideoUri, viewModel.uiState.value.video.document?.uri)

            viewModel.clearVideo()
            runCurrent()
            assertEquals(originalVideoUri, viewModel.uiState.value.video.document?.uri)

            opener.complete(Result.success(twoTrackList()))
            runCurrent()
            assertNotNull(viewModel.audioTrackPickerState.value)

            // Picker dialog is now open (probe no longer pending) — repick/clear still refused.
            viewModel.onVideoPicked("content://test/other-video")
            runCurrent()
            assertEquals(originalVideoUri, viewModel.uiState.value.video.document?.uri)

            viewModel.clearVideo()
            runCurrent()
            assertEquals(originalVideoUri, viewModel.uiState.value.video.document?.uri)
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
                val viewModel = mediaViewModel(repository, ImmediateSafBroker())
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
            val viewModel = mediaViewModel(repository, ImmediateSafBroker())
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
            val viewModel = mediaViewModel(repository, ImmediateSafBroker())
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
            val viewModel = mediaViewModel(repository, ImmediateSafBroker())
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
            val viewModel = mediaViewModel(repository, ImmediateSafBroker())
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
            val viewModel = mediaViewModel(repository, ImmediateSafBroker())
            runCurrent()

            viewModel.confirmCuration()
            runCurrent()
            repository.transitionTo(MiningRunState.Curating(second))
            runCurrent()

            assertEquals(1, viewModel.uiState.value.curation?.previousPageSelectedCount)
            assertTrue(viewModel.uiState.value.curation?.hasSelectionToLose == true)

            val recreated = mediaViewModel(repository, ImmediateSafBroker())
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
            val first = mediaViewModel(repository, ImmediateSafBroker())
            runCurrent()

            first.setCandidateSelected(candidate.candidateId, false)
            first.selectSentence(candidate.candidateId, alternate.sentenceId)
            runCurrent()

            val recreated = mediaViewModel(repository, ImmediateSafBroker())
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
            val viewModel = mediaViewModel(repository, ImmediateSafBroker())
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
            val viewModel = mediaViewModel(repository, ImmediateSafBroker())
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
            val viewModel = mediaViewModel(repository, ImmediateSafBroker())
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
            val viewModel = mediaViewModel(repository, ImmediateSafBroker())
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
            val viewModel = mediaViewModel(repository, ImmediateSafBroker())
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
            val viewModel = mediaViewModel(repository, ImmediateSafBroker(), runtimeWork)
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
            val viewModel = mediaViewModel(repository, broker)
            selectDocuments(viewModel)
            runCurrent()

            viewModel.start()
            viewModel.clearVideo()
            viewModel.onSubtitlePicked("content://test/replacement")
            runCurrent()

            assertEquals("video", viewModel.uiState.value.video.document?.displayName)
            assertEquals("subtitle.SRT", viewModel.uiState.value.subtitle.document?.displayName)
            assertEquals(
                listOf("content://test/video", "content://test/subtitle.SRT"),
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
            val viewModel = mediaViewModel(repository, ImmediateSafBroker())
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
            val viewModel = mediaViewModel(repository, ImmediateSafBroker())
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
            val viewModel = mediaViewModel(repository, ImmediateSafBroker())
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
            val viewModel = mediaViewModel(RecordingRepository(), broker)

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
            val viewModel = mediaViewModel(RecordingRepository(), broker)

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
            val viewModel = mediaViewModel(RecordingRepository(), broker)

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
            val viewModel = mediaViewModel(RecordingRepository(), broker)

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
            val viewModel = mediaViewModel(repository, broker)
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
                )[MediaMiningViewModel::class.java]
            selectDocuments(viewModel)
            runCurrent()

            viewModel.clearVideo()
            store.clear()

            assertEquals(
                listOf("content://test/video", "content://test/subtitle.SRT"),
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
                )[MediaMiningViewModel::class.java]
            selectDocuments(viewModel)
            runCurrent()

            store.clear()

            assertEquals(
                listOf("content://test/video", "content://test/subtitle.SRT"),
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
                )[MediaMiningViewModel::class.java]
            selectDocuments(viewModel)
            runCurrent()
            viewModel.start()
            runCurrent()
            assertTrue(repository.state.value is MiningRunState.Curating)

            store.clear()

            assertEquals(
                listOf("content://test/video", "content://test/subtitle.SRT"),
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
                )[MediaMiningViewModel::class.java]
            selectDocuments(viewModel)
            runCurrent()
            viewModel.start()
            runCurrent()
            assertTrue(repository.state.value is MiningRunState.Starting)

            store.clear()

            assertEquals(emptyList<String>(), broker.eventualReleaseUris)
            assertEquals(1, repository.detachedInputs.size)
            assertEquals("content://test/video", repository.detachedInputs.single().video.uri)
            assertEquals(
                "content://test/subtitle.SRT",
                repository.detachedInputs.single().subtitle.uri,
            )
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
                )[MediaMiningViewModel::class.java]
            viewModel.onVideoPicked("content://test/shared.srt")
            viewModel.onSubtitlePicked("content://test/shared.srt")
            runCurrent()

            store.clear()

            assertEquals(
                listOf("content://test/shared.srt", "content://test/shared.srt"),
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
                )[MediaMiningViewModel::class.java]
            selectDocuments(viewModel)
            runCurrent()
            repository.transitionTo(MiningRunState.Success("run", result()))
            runCurrent()

            store.clear()

            assertEquals(emptyList<String>(), broker.eventualReleaseUris)
            assertEquals(1, repository.detachedInputs.size)
        }

    private fun selectDocuments(viewModel: MediaMiningViewModel) {
        viewModel.onVideoPicked("content://test/video")
        viewModel.onSubtitlePicked("content://test/subtitle.SRT")
    }

    private fun factory(
        repository: MiningRepository,
        broker: SafBroker,
        lane: MiningLane = MiningLane.VIDEO,
    ): MediaMiningViewModel.Factory =
        MediaMiningViewModel.Factory(
            repository = repository,
            safBroker = broker,
            lane = lane,
            definitionLookup = NO_DEFINITION_LOOKUP,
            savedStateHandleFactory = { SavedStateHandle() },
        )

    private fun timingPreviewViewModel(session: TimingPreviewSession): MediaMiningViewModel =
        mediaViewModel(
            repository = RecordingRepository(),
            safBroker = ImmediateSafBroker(),
            timingPreviewOpener = TimingPreviewOpener { Result.success(session) },
            timingPreviewCleanupDispatcher = mainDispatcherRule.dispatcher,
        )

    private fun mediaViewModel(
        repository: MiningRepository,
        safBroker: SafBroker,
        runtimeWorkState: StateFlow<RuntimeWorkCoordinator.Kind?> = MutableStateFlow(null),
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
        selectionInventory: SafSelectionInventory? = null,
        selectionIoDispatcher: CoroutineDispatcher = Dispatchers.IO,
        definitionLookup: DefinitionLookupService? = null,
        cueLookup: SubtitleCueLookupService = NO_CUE_LOOKUP,
        effectiveSubtitleOffset: Flow<Double?> = flowOf(null),
        fieldMap: Flow<Map<String, String>> = flowOf(emptyMap()),
        audioPacks: Flow<List<InstalledAudioPack>> = flowOf(emptyList()),
        timingPreviewOpener: TimingPreviewOpener? = null,
        timingPreviewCleanupDispatcher: CoroutineDispatcher = Dispatchers.IO,
        audioTrackProbeOpener: AudioTrackProbeOpener? = null,
        lane: MiningLane = MiningLane.VIDEO,
    ): MediaMiningViewModel =
        com.ankiminer.android.vm.MediaMiningViewModel(
            repository = repository,
            safBroker = safBroker,
            lane = lane,
            runtimeWorkState = runtimeWorkState,
            savedStateHandle = savedStateHandle,
            selectionInventory = selectionInventory,
            selectionIoDispatcher = selectionIoDispatcher,
            definitionLookup = definitionLookup,
            cueLookup = cueLookup,
            effectiveSubtitleOffset = effectiveSubtitleOffset,
            fieldMap = fieldMap,
            audioPacks = audioPacks,
            timingPreviewOpener = timingPreviewOpener,
            timingPreviewCleanupDispatcher = timingPreviewCleanupDispatcher,
            audioTrackProbeOpener = audioTrackProbeOpener,
        )

    /** Opens the picker, completes the probe with [tracks], selects, then applies. */
    private fun TestScope.applyAudioTrackOverride(
        viewModel: MediaMiningViewModel,
        opener: FakeAudioTrackProbeOpener,
        tracks: AudioTrackList,
        selected: Long?,
    ) {
        viewModel.openAudioTrackPicker()
        runCurrent()
        opener.complete(Result.success(tracks))
        runCurrent()
        viewModel.selectAudioTrack(selected)
        viewModel.applyAudioTrackPicker()
        runCurrent()
    }

    private fun twoTrackList() =
        AudioTrackList(autoAudioIndex = 1L, tracks = listOf(audioTrack(1L), audioTrack(2L)))

    private fun audioTrack(
        index: Long,
        isDefault: Boolean = false,
    ) = AudioTrackInfo(
        audioIndex = index,
        globalIndex = index,
        languageTag = null,
        title = null,
        codec = null,
        channels = null,
        isDefault = isDefault,
    )

    private fun usableAudioPack(packId: String = "nhk16") =
        InstalledAudioPack(
            packId = packId,
            sourceName = packId,
            format = "nhk16",
            entryCount = 100,
            contentAvailable = true,
        )

    private fun unusableAudioPack(packId: String = "broken") =
        InstalledAudioPack(
            packId = packId,
            sourceName = packId,
            format = "ajt",
            entryCount = 0,
            contentAvailable = false,
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

    /** Each [probe] call awaits the currently armed deferred, letting a test hold it in flight. */
    private class FakeAudioTrackProbeOpener : AudioTrackProbeOpener {
        val probedVideos = mutableListOf<SafDocument>()
        private var awaiting = CompletableDeferred<Result<AudioTrackList>>()

        fun complete(result: Result<AudioTrackList>) {
            awaiting.complete(result)
        }

        override suspend fun probe(video: SafDocument): Result<AudioTrackList> {
            probedVideos += video
            val result = awaiting.await()
            awaiting = CompletableDeferred()
            return result
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
        val startedInputs = mutableListOf<VideoMiningInput>()
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
            startedInputs += input
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

        val NO_CUE_LOOKUP =
            SubtitleCueLookupService { _, _ -> Result.success(emptyList()) }

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
