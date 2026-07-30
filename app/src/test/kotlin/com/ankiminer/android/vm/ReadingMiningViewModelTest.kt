package com.ankiminer.android.vm

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.ankiminer.android.MainDispatcherRule
import com.ankiminer.android.data.RuntimeWorkCoordinator
import com.ankiminer.android.media.SafBroker
import com.ankiminer.android.media.SafDocument
import com.ankiminer.android.mining.CurationCandidate
import com.ankiminer.android.mining.CurationPage
import com.ankiminer.android.mining.CurationRequest
import com.ankiminer.android.mining.CurationSelection
import com.ankiminer.android.mining.CurationSentence
import com.ankiminer.android.mining.CurationSessionState
import com.ankiminer.android.mining.MiningCancellationToken
import com.ankiminer.android.mining.MiningProgress
import com.ankiminer.android.mining.MiningRunState
import com.ankiminer.android.reading.ReadingMiningInput
import com.ankiminer.android.reading.ReadingMiningRepository
import com.ankiminer.android.reading.ReadingSourceSelection
import com.ankiminer.android.ui.reading.ReadingDocumentSelectionError
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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReadingMiningViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun savedMokuroPairRestoresSequentiallyAndRevalidatesBothUris() =
        runTest(mainDispatcherRule.dispatcher) {
            val savedState = SavedStateHandle()
            SavedDocumentSelectionStore(savedState, "readingMining.source").save(
                document("content://test/restored-book.mokuro", "stale-book.txt"),
            )
            SavedDocumentSelectionStore(savedState, "readingMining.archive").save(
                document("content://test/restored-book.cbz", "stale-book.zip"),
            )
            val broker = ImmediateSafBroker()

            val viewModel =
                ReadingMiningViewModel(
                    repository = RecordingReadingRepository(),
                    safBroker = broker,
                    savedStateHandle = savedState,
                )
            runCurrent()

            assertEquals(
                listOf(
                    "content://test/restored-book.mokuro",
                    "content://test/restored-book.cbz",
                ),
                broker.retainedUris,
            )
            assertEquals("restored-book.mokuro", viewModel.uiState.value.source.document?.displayName)
            assertEquals("restored-book.cbz", viewModel.uiState.value.archive.document?.displayName)
            assertTrue(viewModel.uiState.value.canStart)
        }

    @Test
    fun revokedSavedSourceGrantSurfacesAccessErrorAndClearsSavedPair() =
        runTest(mainDispatcherRule.dispatcher) {
            val savedState = SavedStateHandle()
            val sourceStore =
                SavedDocumentSelectionStore(savedState, "readingMining.source")
            val archiveStore =
                SavedDocumentSelectionStore(savedState, "readingMining.archive")
            sourceStore.save(
                document("content://test/revoked-book.mokuro", "revoked-book.mokuro"),
            )
            archiveStore.save(
                document("content://test/revoked-book.cbz", "revoked-book.cbz"),
            )
            val broker = ControlledSafBroker()

            val viewModel =
                ReadingMiningViewModel(
                    repository = RecordingReadingRepository(),
                    safBroker = broker,
                    savedStateHandle = savedState,
                )
            runCurrent()
            broker.fail("content://test/revoked-book.mokuro")
            runCurrent()

            assertEquals(
                ReadingDocumentSelectionError.SOURCE_ACCESS,
                viewModel.uiState.value.source.error,
            )
            assertFalse(viewModel.uiState.value.source.isResolving)
            assertNull(viewModel.uiState.value.source.document)
            assertNull(sourceStore.restore())
            assertNull(archiveStore.restore())
            assertNull(savedState.get<String>("readingMining.source.uri"))
            assertNull(savedState.get<String>("readingMining.source.displayName"))
            assertNull(savedState.get<String>("readingMining.archive.uri"))
            assertNull(savedState.get<String>("readingMining.archive.displayName"))
        }

    @Test
    fun subtitleStartUsesSingleSourceAndTrimmedOptionalSeriesName() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = RecordingReadingRepository()
            val viewModel = ReadingMiningViewModel(repository, ImmediateSafBroker())

            viewModel.onSourcePicked("content://test/episode.SRT")
            runCurrent()
            viewModel.onSubtitleSeriesNameChanged("  Show name  ")

            assertTrue(viewModel.uiState.value.canStart)
            viewModel.start()
            runCurrent()

            val input = repository.startedInputs.single()
            assertTrue(input.selection is ReadingSourceSelection.Single)
            assertEquals("Show name", input.subtitleSeriesName)
            assertEquals("episode.SRT", input.selection.documents().single().displayName)
        }

    @Test
    fun mokuroRejectsWrongArchiveAndStartsWithMatchingPair() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = RecordingReadingRepository()
            val broker = ImmediateSafBroker()
            val viewModel = ReadingMiningViewModel(repository, broker)

            viewModel.onSourcePicked("content://test/book.mokuro")
            runCurrent()
            assertTrue(viewModel.uiState.value.canStart)

            viewModel.onArchivePicked("content://test/other.cbz")
            runCurrent()
            assertEquals(
                ReadingDocumentSelectionError.ARCHIVE_NAME,
                viewModel.uiState.value.archive.error,
            )
            assertEquals(listOf("content://test/other.cbz"), broker.releasedUris)

            viewModel.onArchivePicked("content://test/book.ZIP")
            runCurrent()
            assertTrue(viewModel.uiState.value.canStart)
            viewModel.start()
            runCurrent()

            val pair =
                repository.startedInputs.single().selection as
                    ReadingSourceSelection.MokuroArchivePair
            assertEquals("book.mokuro", pair.sidecar.displayName)
            assertEquals("book.ZIP", pair.archive.displayName)
        }

    @Test
    fun replacingMokuroWithTextReleasesItsArchive() =
        runTest(mainDispatcherRule.dispatcher) {
            val broker = ImmediateSafBroker()
            val viewModel = ReadingMiningViewModel(RecordingReadingRepository(), broker)
            viewModel.onSourcePicked("content://test/book.mokuro")
            runCurrent()
            viewModel.onArchivePicked("content://test/book.cbz")
            runCurrent()

            viewModel.onSourcePicked("content://test/novel.txt")
            runCurrent()

            assertEquals("novel.txt", viewModel.uiState.value.source.document?.displayName)
            assertNull(viewModel.uiState.value.archive.document)
            assertTrue(viewModel.uiState.value.canStart)
            assertEquals(
                listOf("content://test/book.mokuro", "content://test/book.cbz"),
                broker.eventualReleaseUris,
            )
        }

    @Test
    fun stalePickerCompletionCannotPublishOrLeakItsGrant() =
        runTest(mainDispatcherRule.dispatcher) {
            val broker = ControlledSafBroker()
            val viewModel = ReadingMiningViewModel(RecordingReadingRepository(), broker)

            viewModel.onSourcePicked("content://test/old.txt")
            runCurrent()
            viewModel.onSourcePicked("content://test/new.epub")
            runCurrent()
            broker.succeed("content://test/new.epub")
            broker.succeed("content://test/old.txt")
            runCurrent()

            assertEquals("new.epub", viewModel.uiState.value.source.document?.displayName)
            assertEquals(listOf("content://test/old.txt"), broker.releasedUris)
        }

    @Test
    fun pagedCurationKeepsOnlyCurrentDraftAndSubmitsExactPageIndex() =
        runTest(mainDispatcherRule.dispatcher) {
            val first = curationRequest(page = CurationPage(0, 2, 0, 2))
            val secondCandidate =
                first.candidates.single().copy(
                    candidateId = "candidate-2",
                    minedForm = "見る",
                )
            val second =
                first.copy(
                    candidates = listOf(secondCandidate),
                    page = CurationPage(1, 2, 1, 2),
                )
            val repository = RecordingReadingRepository(MiningRunState.Curating(first))
            val viewModel = ReadingMiningViewModel(repository, ImmediateSafBroker())
            runCurrent()

            viewModel.setCandidateSelected(first.candidates.single().candidateId, false)
            runCurrent()
            assertEquals(0, viewModel.uiState.value.curation?.selectedCount)
            repository.transitionTo(MiningRunState.Curating(second))
            runCurrent()

            assertEquals(1, viewModel.uiState.value.curation?.candidates?.size)
            assertEquals(
                "candidate-2",
                viewModel.uiState.value.curation
                    ?.candidates
                    ?.single()
                    ?.candidateId,
            )
            assertEquals(1, viewModel.uiState.value.curation?.selectedCount)
            viewModel.confirmCuration()
            runCurrent()

            assertEquals(1L, repository.confirmedPageIndex)
            assertEquals("candidate-2", repository.confirmedSelection?.single()?.candidateId)
        }

    @Test
    fun pagedReadingCurationCarriesSubmittedCountAcrossRequestIdsInSameRun() =
        runTest(mainDispatcherRule.dispatcher) {
            val first =
                curationRequest(page = CurationPage(0, 2, 0, 2))
                    .copy(requestId = "page-1")
            val second =
                first.copy(
                    requestId = "page-2",
                    page = CurationPage(1, 2, 1, 2),
                )
            val repository = RecordingReadingRepository(MiningRunState.Curating(first))
            val viewModel = ReadingMiningViewModel(repository, ImmediateSafBroker())
            runCurrent()

            viewModel.confirmCuration()
            runCurrent()
            repository.transitionTo(MiningRunState.Curating(second))
            runCurrent()

            assertEquals(1, viewModel.uiState.value.curation?.previousPageSelectedCount)
            assertTrue(viewModel.uiState.value.curation?.hasSelectionToLose == true)
        }

    @Test
    fun recreatedReadingViewModelRestoresExactCurationDraftFromProcessRepository() =
        runTest(mainDispatcherRule.dispatcher) {
            val base = curationRequest(page = null)
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
            val repository = RecordingReadingRepository(MiningRunState.Curating(request))
            val first = ReadingMiningViewModel(repository, ImmediateSafBroker())
            runCurrent()

            first.setCandidateSelected(candidate.candidateId, false)
            first.selectSentence(candidate.candidateId, alternate.sentenceId)
            runCurrent()

            val recreated = ReadingMiningViewModel(repository, ImmediateSafBroker())
            runCurrent()

            assertEquals(0, recreated.uiState.value.curation?.selectedCount)
            assertEquals(
                alternate.sentenceId,
                recreated.uiState.value.curation?.sentenceIds?.get(candidate.candidateId),
            )
        }

    @Test
    fun deselectAllConfirmsEmptyPageInsteadOfCancellingRun() =
        runTest(mainDispatcherRule.dispatcher) {
            val request = curationRequest(page = CurationPage(0, 2, 0, 2))
            val repository = RecordingReadingRepository(MiningRunState.Curating(request))
            val viewModel = ReadingMiningViewModel(repository, ImmediateSafBroker())
            runCurrent()

            viewModel.setSelectionForPage(false)
            viewModel.confirmCuration()
            runCurrent()

            assertEquals(emptyList<CurationSelection>(), repository.confirmedSelection)
            assertEquals(emptyList<String>(), repository.cancelledRunIds)
        }

    @Test
    fun teardownTransfersBothPairGrantsToMatchingProcessRun() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = RecordingReadingRepository(detachResult = true)
            val broker = ImmediateSafBroker()
            val store = ViewModelStore()
            val viewModel =
                ViewModelProvider.create(
                    store,
                    factory(repository, broker),
                )[ReadingMiningViewModel::class.java]
            viewModel.onSourcePicked("content://test/book.mokuro")
            runCurrent()
            viewModel.onArchivePicked("content://test/book.cbz")
            runCurrent()
            viewModel.start()
            runCurrent()

            store.clear()

            assertEquals(emptyList<String>(), broker.eventualReleaseUris)
            val pair =
                repository.detachedInputs.single().selection as
                    ReadingSourceSelection.MokuroArchivePair
            assertEquals("content://test/book.mokuro", pair.sidecar.uri)
            assertEquals("content://test/book.cbz", pair.archive.uri)
        }

    @Test
    fun teardownReleasesTextOnlyMokuroWhenRepositoryDoesNotClaimIt() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = RecordingReadingRepository(detachResult = false)
            val broker = ImmediateSafBroker()
            val store = ViewModelStore()
            val viewModel =
                ViewModelProvider.create(
                    store,
                    factory(repository, broker),
                )[ReadingMiningViewModel::class.java]
            viewModel.onSourcePicked("content://test/book.mokuro")
            runCurrent()

            store.clear()

            assertEquals(listOf("content://test/book.mokuro"), broker.eventualReleaseUris)
            assertTrue(
                repository.detachedInputs.single().selection is ReadingSourceSelection.Single,
            )
        }

    @Test
    fun startingCancellationUsesTokenAndWaitsForRepositoryAcknowledgement() =
        runTest(mainDispatcherRule.dispatcher) {
            val token = MiningCancellationToken("cancel_0123456789abcdef0123456789abcdef")
            val repository =
                RecordingReadingRepository(
                    initialState =
                        MiningRunState.Starting(
                            runId = null,
                            progress = MiningProgress(0, 0, "Preparing"),
                            cancellationToken = token,
                        ),
                    acknowledgeCancellationImmediately = false,
                )
            val viewModel = ReadingMiningViewModel(repository, ImmediateSafBroker())

            viewModel.cancel()
            runCurrent()

            assertEquals(listOf(token), repository.cancelledTokens)
            assertTrue(viewModel.uiState.value.cancelPending)

            repository.transitionTo(MiningRunState.Cancelled(null, null))
            runCurrent()

            assertFalse(viewModel.uiState.value.cancelPending)
        }

    @Test
    fun resourceWorkDisablesReadingStartUntilLeaseClears() =
        runTest(mainDispatcherRule.dispatcher) {
            val runtimeWork = MutableStateFlow<RuntimeWorkCoordinator.Kind?>(null)
            val repository = RecordingReadingRepository()
            val viewModel = ReadingMiningViewModel(repository, ImmediateSafBroker(), runtimeWork)
            viewModel.onSourcePicked("content://test/novel.txt")
            runCurrent()
            assertTrue(viewModel.uiState.value.canStart)

            runtimeWork.value = RuntimeWorkCoordinator.Kind.RESOURCE
            runCurrent()
            assertFalse(viewModel.uiState.value.canStart)
            viewModel.start()
            runCurrent()
            assertTrue(repository.startedInputs.isEmpty())

            runtimeWork.value = null
            runCurrent()
            assertTrue(viewModel.uiState.value.canStart)
        }

    @Test
    fun pendingReadingCurationStillAllowsPromptCancellation() =
        runTest(mainDispatcherRule.dispatcher) {
            val request = curationRequest(page = null)
            val confirmGate = CompletableDeferred<Unit>()
            val repository =
                RecordingReadingRepository(
                    initialState = MiningRunState.Curating(request),
                    confirmGate = confirmGate,
                )
            val viewModel = ReadingMiningViewModel(repository, ImmediateSafBroker())
            runCurrent()

            viewModel.confirmCuration()
            viewModel.cancel()
            runCurrent()

            assertEquals(listOf(request.runId), repository.cancelledRunIds)
            assertTrue(repository.state.value is MiningRunState.Cancelled)
            confirmGate.complete(Unit)
            runCurrent()
            assertTrue(repository.state.value is MiningRunState.Cancelled)
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

        fun succeed(uri: String) {
            requireNotNull(pending.remove(uri)).resume(
                SafDocument(
                    uri = uri,
                    displayName = uri.substringAfterLast('/'),
                    mimeType = null,
                    sizeBytes = null,
                ),
            )
        }

        fun fail(uri: String) {
            requireNotNull(pending.remove(uri)).resumeWithException(
                IllegalStateException("revoked"),
            )
        }
    }

    private class RecordingReadingRepository(
        initialState: MiningRunState = MiningRunState.Idle,
        private val detachResult: Boolean = false,
        private val confirmGate: CompletableDeferred<Unit>? = null,
        private val acknowledgeCancellationImmediately: Boolean = true,
    ) : ReadingMiningRepository {
        private val mutableState = MutableStateFlow(initialState)
        override val state: StateFlow<MiningRunState> = mutableState.asStateFlow()
        private var savedCurationSessionState: CurationSessionState? = null

        val startedInputs = mutableListOf<ReadingMiningInput>()
        val detachedInputs = mutableListOf<ReadingMiningInput>()
        val cancelledTokens = mutableListOf<MiningCancellationToken>()
        val cancelledRunIds = mutableListOf<String>()
        var confirmedPageIndex: Long? = null
            private set
        var confirmedSelection: List<CurationSelection>? = null
            private set

        override fun curationSessionState(): CurationSessionState? = savedCurationSessionState

        override fun saveCurationSessionState(state: CurationSessionState) {
            savedCurationSessionState = state
        }

        override fun clearCurationSessionState(runId: String?) {
            if (runId == null || savedCurationSessionState?.runId == runId) {
                savedCurationSessionState = null
            }
        }

        override fun detachActiveSources(input: ReadingMiningInput): Boolean {
            detachedInputs += input
            return detachResult
        }

        override suspend fun startReading(input: ReadingMiningInput) {
            startedInputs += input
            mutableState.value =
                MiningRunState.Starting(
                    runId = "run",
                    progress = MiningProgress(0, 0, "Preparing"),
                )
        }

        override suspend fun confirmCuration(
            runId: String,
            requestId: String,
            selection: List<CurationSelection>,
            pageIndex: Long?,
        ) {
            confirmedPageIndex = pageIndex
            confirmedSelection = selection
            confirmGate?.await()
            if (mutableState.value is MiningRunState.Curating) {
                mutableState.value = MiningRunState.Running(runId, MiningProgress(0, 0, "Running"))
            }
        }

        override suspend fun cancel(runId: String) {
            cancelledRunIds += runId
            if (acknowledgeCancellationImmediately) {
                mutableState.value = MiningRunState.Cancelled(runId, null)
            }
        }

        override suspend fun cancel(token: MiningCancellationToken) {
            cancelledTokens += token
            if (acknowledgeCancellationImmediately) {
                mutableState.value = MiningRunState.Cancelled(null, null)
            }
        }

        override suspend fun reset() {
            mutableState.value = MiningRunState.Idle
        }

        fun transitionTo(state: MiningRunState) {
            mutableState.value = state
        }
    }

    private fun ReadingSourceSelection.documents(): List<SafDocument> =
        when (this) {
            is ReadingSourceSelection.Single -> listOf(document)
            is ReadingSourceSelection.MokuroArchivePair -> listOf(sidecar, archive)
        }

    private fun factory(
        repository: ReadingMiningRepository,
        broker: SafBroker,
    ): ReadingMiningViewModel.Factory =
        ReadingMiningViewModel.Factory(
            repository = repository,
            safBroker = broker,
            savedStateHandleFactory = { SavedStateHandle() },
        )

    private companion object {
        fun document(
            uri: String,
            displayName: String,
        ): SafDocument = SafDocument(uri, displayName, mimeType = null, sizeBytes = null)

        fun curationRequest(page: CurationPage?): CurationRequest {
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
                            candidateId = "candidate-1",
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
                page = page,
            )
        }
    }
}
