package com.ankiminer.android.vm

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.ankiminer.android.MainDispatcherRule
import com.ankiminer.android.media.SafBroker
import com.ankiminer.android.media.SafDocument
import com.ankiminer.android.mining.CurationCandidate
import com.ankiminer.android.mining.CurationRequest
import com.ankiminer.android.mining.CurationSelection
import com.ankiminer.android.mining.CurationSentence
import com.ankiminer.android.mining.FakeMiningRepository
import com.ankiminer.android.mining.MiningFailure
import com.ankiminer.android.mining.MiningProgress
import com.ankiminer.android.mining.MiningRepository
import com.ankiminer.android.mining.MiningRunState
import com.ankiminer.android.mining.ProcessingResult
import com.ankiminer.android.mining.VideoMiningInput
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VideoMiningViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

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
            viewModel.toggleCandidate(request.candidates.last().candidateId)
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
    fun deselectAllConfirmsEmptyListInsteadOfCancellingRun() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeMiningRepository(stepDelayMillis = 0, workScope = this)
            val viewModel = VideoMiningViewModel(repository, ImmediateSafBroker())
            selectDocuments(viewModel)
            runCurrent()
            viewModel.start()
            runCurrent()

            viewModel.selectAllCandidates(false)
            viewModel.confirmCuration()
            runCurrent()

            assertEquals(emptyList<CurationSelection>(), repository.confirmedSelection)
            assertEquals(0L, (repository.state.value as MiningRunState.Success).result.cardsCreated)
            assertEquals(0, repository.cancelCount)
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
            viewModel.toggleCandidate(request.candidates.first().candidateId)
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
    fun pendingCurationConfirmationRejectsCancellation() =
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
            assertEquals(0, repository.cancelCalls)
            assertTrue(viewModel.uiState.value.curationPending)

            confirmGate.complete(Unit)
            runCurrent()
            assertTrue(repository.state.value is MiningRunState.Running)
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
                    VideoMiningViewModel.Factory(RecordingRepository(), broker),
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
                    VideoMiningViewModel.Factory(RecordingRepository(), broker),
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
                    VideoMiningViewModel.Factory(repository, broker),
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
                    VideoMiningViewModel.Factory(repository, broker),
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
                    VideoMiningViewModel.Factory(repository, broker),
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
                    VideoMiningViewModel.Factory(repository, broker),
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

        fun fail(uri: String) {
            requireNotNull(pending.remove(uri)).resumeWithException(IllegalStateException("stale"))
        }
    }

    private class RecordingRepository(
        initialState: MiningRunState = MiningRunState.Idle,
        private val resetGate: CompletableDeferred<Unit>? = null,
        private val startGate: CompletableDeferred<Unit>? = null,
        private val cancelGate: CompletableDeferred<Unit>? = null,
        private val confirmGate: CompletableDeferred<Unit>? = null,
        private val detachActiveSourcesResult: Boolean = false,
    ) : MiningRepository {
        private val mutableState = MutableStateFlow(initialState)
        override val state: StateFlow<MiningRunState> = mutableState.asStateFlow()

        var startCalls = 0
            private set
        var resetCalls = 0
            private set
        var cancelCalls = 0
            private set
        var confirmCalls = 0
            private set
        val detachedInputs = mutableListOf<VideoMiningInput>()

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
        ) {
            confirmCalls += 1
            confirmGate?.await()
            mutableState.value =
                MiningRunState.Running(runId, MiningProgress(0, 0, "Running"))
        }

        override suspend fun cancel(runId: String) {
            cancelCalls += 1
            cancelGate?.await()
            mutableState.value = MiningRunState.Cancelled(runId, null)
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
            )
    }
}
