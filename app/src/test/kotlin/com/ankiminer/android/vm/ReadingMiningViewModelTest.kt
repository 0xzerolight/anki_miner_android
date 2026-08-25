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
import com.ankiminer.android.engine.DefinitionEntry
import com.ankiminer.android.media.AndroidSafBroker
import com.ankiminer.android.media.ProviderIoCancellation
import com.ankiminer.android.media.SafAccessException
import com.ankiminer.android.media.SafAccessFailureKind
import com.ankiminer.android.media.SafBroker
import com.ankiminer.android.media.SafDocument
import com.ankiminer.android.media.SafProviderAccess
import com.ankiminer.android.media.SafSelectionInventory
import com.ankiminer.android.media.SafSelectionPersistenceException
import com.ankiminer.android.media.SafSelectionRecord
import com.ankiminer.android.media.SafSelectionSlot
import com.ankiminer.android.media.TransientSafSelectionInventory
import com.ankiminer.android.mining.AnkiWriteState
import com.ankiminer.android.mining.CurationCandidate
import com.ankiminer.android.mining.CurationPage
import com.ankiminer.android.mining.CurationPageImageBinding
import com.ankiminer.android.mining.CurationRequest
import com.ankiminer.android.mining.CurationSelection
import com.ankiminer.android.mining.CurationSentence
import com.ankiminer.android.mining.CurationSessionState
import com.ankiminer.android.mining.MiningCancellationToken
import com.ankiminer.android.mining.MiningFailure
import com.ankiminer.android.mining.MiningProgress
import com.ankiminer.android.mining.MiningRunState
import com.ankiminer.android.mining.ProcessingResult
import com.ankiminer.android.reading.ReadingMiningInput
import com.ankiminer.android.reading.ReadingMiningRepository
import com.ankiminer.android.reading.ReadingSourceSelection
import com.ankiminer.android.ui.reading.CurationPageImageUiState
import com.ankiminer.android.ui.reading.ReadingDocumentSelectionError
import com.ankiminer.android.ui.reading.ReadingSourceKindUi
import com.ankiminer.android.ui.reading.ReadingSourceMode
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
class ReadingMiningViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun focusingACandidateLooksUpItsDefinition() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = RecordingReadingRepository()
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
                ReadingMiningViewModel(
                    repository,
                    ImmediateSafBroker(),
                    definitionLookup = lookup,
                )
            val original = curationRequest(page = null)
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
            val repository = RecordingReadingRepository()
            val lookup =
                DefinitionLookupService { _, term, _ ->
                    Result.success(DefinitionResult(term, term, emptyList()))
                }
            val viewModel =
                ReadingMiningViewModel(
                    repository,
                    ImmediateSafBroker(),
                    definitionLookup = lookup,
                )
            val request = curationRequest(page = null)
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
            val repository = RecordingReadingRepository()
            val lookup =
                DefinitionLookupService { _, _, _ ->
                    Result.failure(IllegalStateException("boom"))
                }
            val viewModel =
                ReadingMiningViewModel(
                    repository,
                    ImmediateSafBroker(),
                    definitionLookup = lookup,
                )
            val request = curationRequest(page = null)
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
            val repository = RecordingReadingRepository()
            val lookups = mutableListOf<String>()
            val lookup =
                DefinitionLookupService { _, term, _ ->
                    lookups += term
                    Result.success(DefinitionResult(term, term, emptyList()))
                }
            val viewModel =
                ReadingMiningViewModel(
                    repository,
                    ImmediateSafBroker(),
                    definitionLookup = lookup,
                )
            val collection =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    viewModel.uiState.collect {}
                }
            val request = curationRequest(page = null)

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
            ReadingMiningViewModel.Factory(
                repository = RecordingReadingRepository(),
                safBroker = ImmediateSafBroker(),
                definitionLookup = lookup,
                savedStateHandleFactory = { SavedStateHandle() },
            )

        assertNotNull(factory.create(ReadingMiningViewModel::class.java, CreationExtras.Empty))
    }

    @Test
    fun switchingSourceModesPreservesPickedFileAndPasteDraft() =
        runTest(mainDispatcherRule.dispatcher) {
            val broker = ImmediateSafBroker()
            val viewModel = ReadingMiningViewModel(RecordingReadingRepository(), broker)
            viewModel.onSourcePicked("content://test/novel.txt")
            runCurrent()
            val picked = requireNotNull(viewModel.uiState.value.source.document)

            viewModel.onSourceModeChanged(ReadingSourceMode.PASTED_TEXT)
            viewModel.onPastedTextChanged("本文。")
            runCurrent()

            assertEquals(ReadingSourceMode.PASTED_TEXT, viewModel.uiState.value.sourceMode)
            assertEquals(picked, viewModel.uiState.value.source.document)
            assertNull(viewModel.uiState.value.sourceKind)
            assertEquals("本文。", viewModel.uiState.value.pastedText)
            assertTrue(broker.releasedUris.isEmpty())
            assertTrue(broker.eventualReleaseUris.isEmpty())

            viewModel.onSourceModeChanged(ReadingSourceMode.FILE)
            runCurrent()

            assertEquals(ReadingSourceMode.FILE, viewModel.uiState.value.sourceMode)
            assertEquals(picked, viewModel.uiState.value.source.document)
            assertEquals(ReadingSourceKindUi.TXT, viewModel.uiState.value.sourceKind)
            assertEquals("本文。", viewModel.uiState.value.pastedText)
        }

    @Test
    fun blankPastedTextCannotStart() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = RecordingReadingRepository()
            val viewModel = ReadingMiningViewModel(repository, ImmediateSafBroker())
            viewModel.onSourceModeChanged(ReadingSourceMode.PASTED_TEXT)

            listOf("", " \n\t ").forEach { text ->
                viewModel.onPastedTextChanged(text)
                runCurrent()

                assertFalse(viewModel.uiState.value.canStart)
                viewModel.start()
                runCurrent()
            }

            assertTrue(repository.startedInputs.isEmpty())
        }

    @Test
    fun pastedTextStartPassesRawUntrimmedText() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = RecordingReadingRepository()
            val viewModel = ReadingMiningViewModel(repository, ImmediateSafBroker())
            val raw = "  本文。  "
            viewModel.onSourceModeChanged(ReadingSourceMode.PASTED_TEXT)
            viewModel.onPastedTextChanged(raw)
            runCurrent()

            viewModel.start()
            runCurrent()

            assertEquals(
                ReadingSourceSelection.PastedText(raw),
                repository.startedInputs.single().selection,
            )
            assertNull(repository.startedInputs.single().subtitleSeriesName)
        }

    @Test
    fun pastedTextClampsByCodePointAndClearsTruncationOnShorterEdit() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel =
                ReadingMiningViewModel(RecordingReadingRepository(), ImmediateSafBroker())
            val emoji = "😀"
            val oversized = "a".repeat(199_999) + emoji + "b"

            viewModel.onPastedTextChanged(oversized)
            runCurrent()

            val clamped = viewModel.uiState.value.pastedText
            assertEquals(200_000, clamped.codePointCount(0, clamped.length))
            assertTrue(clamped.endsWith(emoji))
            assertTrue(viewModel.uiState.value.pastedTextTruncated)

            viewModel.onPastedTextChanged("short $emoji")
            runCurrent()

            assertEquals("short $emoji", viewModel.uiState.value.pastedText)
            assertFalse(viewModel.uiState.value.pastedTextTruncated)

            viewModel.onPastedTextChanged(oversized)
            viewModel.clearPastedText()
            runCurrent()

            assertEquals("", viewModel.uiState.value.pastedText)
            assertFalse(viewModel.uiState.value.pastedTextTruncated)
        }

    @Test
    fun pastedTextNeverReachesSavedStateHandle() =
        runTest(mainDispatcherRule.dispatcher) {
            val savedState = SavedStateHandle()
            val viewModel =
                ReadingMiningViewModel(
                    repository = RecordingReadingRepository(),
                    safBroker = ImmediateSafBroker(),
                    savedStateHandle = savedState,
                )
            val distinctive = "private-clipboard-value-934ae769"

            viewModel.onSourceModeChanged(ReadingSourceMode.PASTED_TEXT)
            viewModel.onPastedTextChanged(distinctive)
            runCurrent()

            assertEquals(distinctive, viewModel.uiState.value.pastedText)
            assertTrue(
                savedState.keys().none { key ->
                    savedState.get<Any?>(key)?.toString()?.contains(distinctive) == true
                },
            )
        }

    @Test
    fun pastedTextIsRetainedAfterSuccessfulRun() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = RecordingReadingRepository()
            val viewModel = ReadingMiningViewModel(repository, ImmediateSafBroker())
            viewModel.onSourceModeChanged(ReadingSourceMode.PASTED_TEXT)
            viewModel.onPastedTextChanged("成功後も残る")
            viewModel.start()
            runCurrent()

            repository.transitionTo(MiningRunState.Success("run", result()))
            runCurrent()

            assertEquals("成功後も残る", viewModel.uiState.value.pastedText)
        }

    @Test
    fun pastedTextIsRetainedAfterFailedRun() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = RecordingReadingRepository()
            val viewModel = ReadingMiningViewModel(repository, ImmediateSafBroker())
            viewModel.onSourceModeChanged(ReadingSourceMode.PASTED_TEXT)
            viewModel.onPastedTextChanged("失敗後も残る")
            viewModel.start()
            runCurrent()

            repository.transitionTo(
                MiningRunState.Failed(
                    runId = "run",
                    failure = MiningFailure("failed", retryable = true),
                    result = null,
                ),
            )
            runCurrent()

            assertEquals("失敗後も残る", viewModel.uiState.value.pastedText)
        }

    @Test
    fun pastedTextEditsAreIgnoredWhileRunIsActive() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = RecordingReadingRepository()
            val viewModel = ReadingMiningViewModel(repository, ImmediateSafBroker())
            viewModel.onSourceModeChanged(ReadingSourceMode.PASTED_TEXT)
            viewModel.onPastedTextChanged("original")
            repository.transitionTo(MiningRunState.Running("run", MiningProgress(1, 2, "Running")))
            runCurrent()

            viewModel.onPastedTextChanged("replacement")
            viewModel.clearPastedText()
            runCurrent()

            assertEquals("original", viewModel.uiState.value.pastedText)
        }

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
    fun archiveResultDuringSourceRestoreReplacesSavedArchiveInsteadOfDisappearing() =
        runTest(mainDispatcherRule.dispatcher) {
            val savedState = SavedStateHandle()
            SavedDocumentSelectionStore(savedState, "readingMining.source").save(
                document("content://test/book.mokuro", "book.mokuro"),
            )
            SavedDocumentSelectionStore(savedState, "readingMining.archive").save(
                document("content://test/old/book.cbz", "book.cbz"),
            )
            val broker = ControlledSafBroker()
            val viewModel =
                ReadingMiningViewModel(
                    repository = RecordingReadingRepository(),
                    safBroker = broker,
                    savedStateHandle = savedState,
                )
            runCurrent()

            viewModel.onArchivePicked("content://test/new/book.cbz")
            broker.succeed("content://test/book.mokuro")
            runCurrent()

            assertTrue(broker.isPendingActive("content://test/new/book.cbz"))
            assertFalse(broker.isPendingActive("content://test/old/book.cbz"))

            broker.succeed("content://test/new/book.cbz")
            runCurrent()

            assertEquals(
                "book.cbz",
                viewModel.uiState.value.archive.document?.displayName,
            )
            assertEquals(listOf("content://test/old/book.cbz"), broker.releasedUris)
        }

    @Test
    fun freshNonIdleReadingViewModelRestoresDurablePairForLaterReset() =
        runTest(mainDispatcherRule.dispatcher) {
            val inventory = TransientSafSelectionInventory()
            inventory.putSelection(
                SafSelectionSlot.READING_SOURCE,
                SafSelectionRecord("content://test/book.mokuro", "book.mokuro"),
            )
            inventory.putSelection(
                SafSelectionSlot.READING_ARCHIVE,
                SafSelectionRecord("content://test/book.cbz", "book.cbz"),
            )
            val repository =
                RecordingReadingRepository(
                    MiningRunState.Running("run", MiningProgress(1, 2, "Running")),
                )
            val viewModel =
                ReadingMiningViewModel(
                    repository = repository,
                    safBroker = ImmediateSafBroker(),
                    selectionInventory = inventory,
                    selectionIoDispatcher = mainDispatcherRule.dispatcher,
                )
            runCurrent()

            assertEquals("book.mokuro", viewModel.uiState.value.source.document?.displayName)
            assertEquals("book.cbz", viewModel.uiState.value.archive.document?.displayName)
            assertFalse(viewModel.uiState.value.canStart)

            repository.transitionTo(MiningRunState.Cancelled("run", null))
            viewModel.reset()
            runCurrent()

            assertEquals(MiningRunState.Idle, repository.state.value)
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
            broker.fail(
                "content://test/revoked-book.mokuro",
                SafAccessException(
                    SafAccessFailureKind.PERMISSION_REVOKED,
                    "revoked",
                ),
            )
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
    fun revokedSubtitleRestoreClearsLiveSeriesBeforeReplacementSubtitleStarts() =
        runTest(mainDispatcherRule.dispatcher) {
            val savedState = SavedStateHandle()
            SavedDocumentSelectionStore(savedState, "readingMining.source").save(
                document("content://test/old-episode.srt", "old-episode.srt"),
            )
            val seriesStore =
                SavedTextValueStore(savedState, "readingMining.subtitleSeriesName")
            seriesStore.save("Series A")
            val repository = RecordingReadingRepository()
            val broker = ControlledSafBroker()
            val viewModel =
                ReadingMiningViewModel(
                    repository = repository,
                    safBroker = broker,
                    savedStateHandle = savedState,
                )
            runCurrent()

            broker.fail(
                "content://test/old-episode.srt",
                SafAccessException(
                    SafAccessFailureKind.PERMISSION_REVOKED,
                    "revoked",
                ),
            )
            runCurrent()

            assertEquals("", viewModel.uiState.value.subtitleSeriesName)
            assertEquals("", seriesStore.restore())

            viewModel.onSourcePicked("content://test/new-episode.srt")
            runCurrent()
            broker.succeed("content://test/new-episode.srt")
            runCurrent()
            viewModel.start()
            runCurrent()

            assertNull(repository.startedInputs.single().subtitleSeriesName)
        }

    @Test
    fun missingSavedSourceClearsOrphanLiveSubtitleSeries() =
        runTest(mainDispatcherRule.dispatcher) {
            val savedState = SavedStateHandle()
            SavedTextValueStore(savedState, "readingMining.subtitleSeriesName").save("Series A")

            val viewModel =
                ReadingMiningViewModel(
                    repository = RecordingReadingRepository(),
                    safBroker = ImmediateSafBroker(),
                    savedStateHandle = savedState,
                )
            runCurrent()

            assertEquals("", viewModel.uiState.value.subtitleSeriesName)
        }

    @Test
    fun transientMokuroProviderFailurePreservesSourceAndArchiveForRetry() =
        runTest(mainDispatcherRule.dispatcher) {
            val sourceUri = "content://test/book.mokuro"
            val archiveUri = "content://test/book.cbz"
            val inventory = TransientSafSelectionInventory()
            inventory.putSelection(
                SafSelectionSlot.READING_SOURCE,
                SafSelectionRecord(sourceUri, "book.mokuro"),
            )
            inventory.putSelection(
                SafSelectionSlot.READING_ARCHIVE,
                SafSelectionRecord(archiveUri, "book.cbz"),
            )
            val broker = ControlledSafBroker()
            val first =
                ReadingMiningViewModel(
                    repository = RecordingReadingRepository(),
                    safBroker = broker,
                    selectionInventory = inventory,
                    selectionIoDispatcher = mainDispatcherRule.dispatcher,
                )
            runCurrent()

            broker.fail(
                sourceUri,
                SafAccessException(
                    SafAccessFailureKind.PROVIDER_UNAVAILABLE,
                    "provider updating",
                ),
            )
            runCurrent()

            assertEquals(
                ReadingDocumentSelectionError.SOURCE_ACCESS,
                first.uiState.value.source.error,
            )
            assertEquals(sourceUri, inventory.selection(SafSelectionSlot.READING_SOURCE)?.uri)
            assertEquals(archiveUri, inventory.selection(SafSelectionSlot.READING_ARCHIVE)?.uri)

            val recreated =
                ReadingMiningViewModel(
                    repository = RecordingReadingRepository(),
                    safBroker = ImmediateSafBroker(),
                    selectionInventory = inventory,
                    selectionIoDispatcher = mainDispatcherRule.dispatcher,
                )
            runCurrent()

            assertEquals("book.mokuro", recreated.uiState.value.source.document?.displayName)
            assertEquals("book.cbz", recreated.uiState.value.archive.document?.displayName)
        }

    @Test
    fun replacingTransientlyUnavailableSavedSourceReleasesItsDurableGrant() =
        runTest(mainDispatcherRule.dispatcher) {
            val oldUri = "content://test/temporarily-unavailable.txt"
            val newUri = "content://test/replacement.epub"
            val inventory = TransientSafSelectionInventory()
            inventory.putSelection(
                SafSelectionSlot.READING_SOURCE,
                SafSelectionRecord(oldUri, "temporarily-unavailable.txt"),
            )
            val broker = ControlledSafBroker()
            val viewModel =
                ReadingMiningViewModel(
                    repository = RecordingReadingRepository(),
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

            viewModel.onSourcePicked(newUri)
            runCurrent()
            broker.succeed(newUri)
            runCurrent()

            assertEquals(newUri, inventory.selection(SafSelectionSlot.READING_SOURCE)?.uri)
            assertEquals("replacement.epub", viewModel.uiState.value.source.document?.displayName)
            assertEquals(listOf(oldUri), broker.releasedUris)
        }

    @Test
    fun transientSubtitleProviderFailurePreservesSeriesForRetry() =
        runTest(mainDispatcherRule.dispatcher) {
            val sourceUri = "content://test/episode.srt"
            val inventory = TransientSafSelectionInventory()
            inventory.putSelection(
                SafSelectionSlot.READING_SOURCE,
                SafSelectionRecord(sourceUri, "episode.srt"),
            )
            inventory.putText(SafSelectionSlot.READING_SUBTITLE_SERIES, "Series A")
            val broker = ControlledSafBroker()
            ReadingMiningViewModel(
                repository = RecordingReadingRepository(),
                safBroker = broker,
                selectionInventory = inventory,
                selectionIoDispatcher = mainDispatcherRule.dispatcher,
            )
            runCurrent()

            broker.fail(
                sourceUri,
                SafAccessException(
                    SafAccessFailureKind.PROVIDER_UNAVAILABLE,
                    "provider updating",
                ),
            )
            runCurrent()

            assertEquals(sourceUri, inventory.selection(SafSelectionSlot.READING_SOURCE)?.uri)
            assertEquals("Series A", inventory.text(SafSelectionSlot.READING_SUBTITLE_SERIES))

            val recreated =
                ReadingMiningViewModel(
                    repository = RecordingReadingRepository(),
                    safBroker = ImmediateSafBroker(),
                    selectionInventory = inventory,
                    selectionIoDispatcher = mainDispatcherRule.dispatcher,
                )
            runCurrent()

            assertEquals("episode.srt", recreated.uiState.value.source.document?.displayName)
            assertEquals("Series A", recreated.uiState.value.subtitleSeriesName)
        }

    @Test
    fun rejectedRestoreClearsDurableOwnerBeforePlatformGrantRelease() =
        runTest(mainDispatcherRule.dispatcher) {
            val uri = "content://test/renamed-source"
            val events = mutableListOf<String>()
            val inventory = RecordingSelectionInventory(events)
            inventory.putSelection(
                SafSelectionSlot.READING_SOURCE,
                SafSelectionRecord(uri, "book.mokuro"),
            )
            events.clear()
            val broker =
                AndroidSafBroker(
                    providerAccess =
                        RenamingProviderAccess(
                            grants = setOf(uri),
                            resolved = SafDocument(uri, "renamed.bin", null, null),
                            events = events,
                        ),
                    ioDispatcher = mainDispatcherRule.dispatcher,
                    selectionInventory = inventory,
                )

            val viewModel =
                ReadingMiningViewModel(
                    repository = RecordingReadingRepository(),
                    safBroker = broker,
                    selectionInventory = inventory,
                    selectionIoDispatcher = mainDispatcherRule.dispatcher,
                )
            val restoredState =
                viewModel.uiState.first {
                    it.source.error == ReadingDocumentSelectionError.SOURCE_TYPE
                }

            assertEquals(
                ReadingDocumentSelectionError.SOURCE_TYPE,
                restoredState.source.error,
            )
            assertNull(inventory.selection(SafSelectionSlot.READING_SOURCE))
            assertTrue(events.indexOf("clear:READING_SOURCE") >= 0)
            assertTrue(events.indexOf("release:$uri") > events.indexOf("clear:READING_SOURCE"))
        }

    @Test
    fun failedReadingInventoryCommitReleasesNewlyAcquiredGrant() =
        runTest(mainDispatcherRule.dispatcher) {
            val inventory = FailOnceSelectionInventory()
            val broker = ImmediateSafBroker()
            val viewModel =
                ReadingMiningViewModel(
                    repository = RecordingReadingRepository(),
                    safBroker = broker,
                    selectionInventory = inventory,
                    selectionIoDispatcher = mainDispatcherRule.dispatcher,
                )

            viewModel.onSourcePicked("content://test/new-book.epub")
            runCurrent()

            assertEquals(
                ReadingDocumentSelectionError.SOURCE_ACCESS,
                viewModel.uiState.value.source.error,
            )
            assertNull(viewModel.uiState.value.source.document)
            assertNull(inventory.selection(SafSelectionSlot.READING_SOURCE))
            assertEquals(listOf("content://test/new-book.epub"), broker.releasedUris)
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
                listOf("content://test/book.mokuro"),
                broker.eventualReleaseUris,
            )
            assertEquals(listOf("content://test/book.cbz"), broker.releasedUris)
        }

    @Test
    fun archiveClearFailureStillReleasesTheReplacedSourceGrant() =
        runTest(mainDispatcherRule.dispatcher) {
            val inventory = FailArchiveClearSelectionInventory()
            val broker = ImmediateSafBroker()
            val viewModel =
                ReadingMiningViewModel(
                    repository = RecordingReadingRepository(),
                    safBroker = broker,
                    selectionInventory = inventory,
                    selectionIoDispatcher = mainDispatcherRule.dispatcher,
                )
            viewModel.onSourcePicked("content://test/book.mokuro")
            runCurrent()
            viewModel.onArchivePicked("content://test/book.cbz")
            runCurrent()
            inventory.failArchiveClear = true

            viewModel.onSourcePicked("content://test/novel.txt")
            runCurrent()

            assertEquals("novel.txt", viewModel.uiState.value.source.document?.displayName)
            assertEquals(
                listOf("content://test/book.mokuro"),
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

            val recreated = ReadingMiningViewModel(repository, ImmediateSafBroker())
            runCurrent()

            assertEquals(1, recreated.uiState.value.curation?.previousPageSelectedCount)
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
    fun confirmingSendsTheStagedKnownMarks() =
        runTest(mainDispatcherRule.dispatcher) {
            val request = curationRequest(page = null)
            val repository = RecordingReadingRepository(MiningRunState.Curating(request))
            val viewModel = ReadingMiningViewModel(repository, ImmediateSafBroker())
            runCurrent()
            val candidateId = request.candidates.first().candidateId

            viewModel.markCandidateKnown(candidateId, known = true)
            viewModel.confirmCuration()
            runCurrent()

            assertEquals(listOf(candidateId), repository.confirmedKnownCandidateIds)
            assertTrue(repository.confirmedSelection.orEmpty().none { it.candidateId == candidateId })
        }

    @Test
    fun curatingWithAPageImageBindingSurfacesTheArchivePath() =
        runTest(mainDispatcherRule.dispatcher) {
            val request = curationRequest(page = null)
            val repository =
                RecordingReadingRepository(
                    MiningRunState.Curating(
                        request,
                        pageImage = CurationPageImageBinding(archivePath = "/staged/manga.zip"),
                    ),
                )
            val viewModel = ReadingMiningViewModel(repository, ImmediateSafBroker())
            runCurrent()

            assertEquals(
                CurationPageImageUiState(archivePath = "/staged/manga.zip"),
                viewModel.uiState.value.curation?.pageImage,
            )
        }

    @Test
    fun curatingWithoutAPageImageBindingLeavesItNull() =
        runTest(mainDispatcherRule.dispatcher) {
            val request = curationRequest(page = null)
            val repository = RecordingReadingRepository(MiningRunState.Curating(request))
            val viewModel = ReadingMiningViewModel(repository, ImmediateSafBroker())
            runCurrent()

            assertNull(viewModel.uiState.value.curation?.pageImage)
        }

    @Test
    fun cancellingDiscardsTheStagedKnownMarks() =
        runTest(mainDispatcherRule.dispatcher) {
            val request = curationRequest(page = null)
            val repository = RecordingReadingRepository(MiningRunState.Curating(request))
            val viewModel = ReadingMiningViewModel(repository, ImmediateSafBroker())
            runCurrent()

            viewModel.markCandidateKnown(request.candidates.first().candidateId, known = true)
            viewModel.cancel()
            runCurrent()

            assertTrue(repository.confirmedKnownCandidateIds.isEmpty())
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
    fun teardownReleasesInactiveFileGrantDuringPastedTextRun() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = RecordingReadingRepository(detachResult = true)
            val broker = ImmediateSafBroker()
            val store = ViewModelStore()
            val viewModel =
                ViewModelProvider.create(
                    store,
                    factory(repository, broker),
                )[ReadingMiningViewModel::class.java]
            viewModel.onSourcePicked("content://test/novel.txt")
            runCurrent()
            viewModel.onSourceModeChanged(ReadingSourceMode.PASTED_TEXT)
            viewModel.onPastedTextChanged("本文。")
            viewModel.start()
            runCurrent()

            store.clear()

            assertEquals(
                ReadingSourceSelection.PastedText("本文。"),
                repository.detachedInputs.single().selection,
            )
            assertEquals(listOf("content://test/novel.txt"), broker.eventualReleaseUris)
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

        fun isPendingActive(uri: String): Boolean = pending[uri]?.isActive == true

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

        fun fail(
            uri: String,
            failure: Exception = IllegalStateException("revoked"),
        ) {
            requireNotNull(pending.remove(uri)).resumeWithException(
                failure,
            )
        }
    }

    private class RenamingProviderAccess(
        private val grants: Set<String>,
        private val resolved: SafDocument,
        private val events: MutableList<String>,
    ) : SafProviderAccess {
        override fun persistedReadGrantUris(
            cancellation: ProviderIoCancellation,
        ): List<String> = grants.toList()

        override fun resolveDocument(
            uri: String,
            cancellation: ProviderIoCancellation,
        ): SafDocument = resolved

        override fun takeReadGrant(uri: String) {
            events += "take:$uri"
        }

        override fun releaseReadGrant(uri: String) {
            events += "release:$uri"
        }
    }

    private open class RecordingSelectionInventory(
        private val events: MutableList<String> = mutableListOf(),
    ) : SafSelectionInventory {
        protected val selections = mutableMapOf<SafSelectionSlot, SafSelectionRecord>()
        private val text = mutableMapOf<SafSelectionSlot, String>()

        override fun selection(slot: SafSelectionSlot): SafSelectionRecord? = selections[slot]

        override fun putSelection(
            slot: SafSelectionSlot,
            selection: SafSelectionRecord?,
        ) {
            events += if (selection == null) "clear:$slot" else "save:$slot"
            if (selection == null) selections.remove(slot) else selections[slot] = selection
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

    private class FailOnceSelectionInventory : RecordingSelectionInventory() {
        private var failNextSave = true

        override fun putSelection(
            slot: SafSelectionSlot,
            selection: SafSelectionRecord?,
        ) {
            super.putSelection(slot, selection)
            if (selection != null && failNextSave) {
                failNextSave = false
                throw SafSelectionPersistenceException("injected commit failure")
            }
        }
    }

    private class FailArchiveClearSelectionInventory : RecordingSelectionInventory() {
        var failArchiveClear = false

        override fun putSelections(selections: Map<SafSelectionSlot, SafSelectionRecord?>) {
            if (
                failArchiveClear &&
                selections.containsKey(SafSelectionSlot.READING_ARCHIVE) &&
                selections[SafSelectionSlot.READING_ARCHIVE] == null
            ) {
                throw SafSelectionPersistenceException("injected archive clear failure")
            }
            selections.forEach(::putSelection)
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
        var confirmedKnownCandidateIds: List<String> = emptyList()
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
            knownCandidateIds: List<String>,
        ) {
            confirmedPageIndex = pageIndex
            confirmedSelection = selection
            confirmedKnownCandidateIds = knownCandidateIds
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
            is ReadingSourceSelection.PastedText -> emptyList()
        }

    private fun factory(
        repository: ReadingMiningRepository,
        broker: SafBroker,
    ): ReadingMiningViewModel.Factory =
        ReadingMiningViewModel.Factory(
            repository = repository,
            safBroker = broker,
            definitionLookup = NO_DEFINITION_LOOKUP,
            savedStateHandleFactory = { SavedStateHandle() },
        )

    private companion object {
        val NO_DEFINITION_LOOKUP =
            DefinitionLookupService { _, term, _ ->
                Result.success(DefinitionResult(term, term, emptyList()))
            }

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

        fun result(): ProcessingResult =
            ProcessingResult(
                totalWordsFound = 3,
                newWordsFound = 2,
                cardsCreated = 1,
                errors = emptyList(),
                elapsedTime = 1.5,
                comprehensionPercentage = 80.0,
                cardIds = listOf(42),
                videoFile = "pasted.text",
                subtitleFile = "",
                minedForms = listOf("本文"),
                ankiWriteState = AnkiWriteState.NOTE_WRITE_CONFIRMED,
                failureIsTransient = false,
            )
    }
}
