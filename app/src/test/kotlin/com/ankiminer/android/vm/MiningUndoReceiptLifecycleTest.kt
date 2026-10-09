package com.ankiminer.android.vm

import androidx.lifecycle.SavedStateHandle
import com.ankiminer.android.MainDispatcherRule
import com.ankiminer.android.data.RuntimeWorkCoordinator
import com.ankiminer.android.data.anki.MinedWordsReverter
import com.ankiminer.android.data.anki.MiningRunUndoBackend
import com.ankiminer.android.data.anki.MiningRunUndoManager
import com.ankiminer.android.data.anki.ProcessMiningRunUndoManager
import com.ankiminer.android.media.SafBroker
import com.ankiminer.android.media.SafDocument
import com.ankiminer.android.mining.AnkiWriteState
import com.ankiminer.android.mining.CurationSelection
import com.ankiminer.android.mining.MiningCommandException
import com.ankiminer.android.mining.MiningLane
import com.ankiminer.android.mining.MiningRepository
import com.ankiminer.android.mining.MiningRunState
import com.ankiminer.android.mining.ProcessingResult
import com.ankiminer.android.mining.VideoMiningInput
import com.ankiminer.android.reading.ReadingMiningInput
import com.ankiminer.android.reading.ReadingMiningRepository
import java.util.concurrent.Executor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * The Undo receipt across the lifetimes it straddles: the process-owned undo manager, the
 * process-scoped repository, and the Activity-scoped SavedStateHandle that outlives the process.
 * Process death is a rebuilt SavedStateHandle plus a fresh repository and a fresh undo manager.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MiningUndoReceiptLifecycleTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun aRelaunchedVideoViewModelDoesNotSaveAnUndoneRunAgain() =
        runTest(mainDispatcherRule.dispatcher) {
            val undo = UndoHarness()
            val repository = FakeMiningRepository(MiningRunState.Success("run", result()))
            val first = mediaViewModel(repository, SavedStateHandle(), undo.manager)
            runCurrent()
            first.requestUndo()
            first.confirmUndo()
            runCurrent()
            assertEquals(1, first.uiState.value.undoneNoteCount)

            // The Activity is finished and relaunched while the process lives: the process-scoped
            // repository still holds the finished run.
            val relaunched = SavedStateHandle()
            mediaViewModel(repository, relaunched, undo.manager)
            runCurrent()
            assertNull(MiningReceiptStore(relaunched, VIDEO_RECEIPT).restore())

            // Android then kills the process: a fresh repository and undo manager, the saved state kept.
            val restored =
                mediaViewModel(
                    FakeMiningRepository(MiningRunState.Idle),
                    relaunched.rebuilt(),
                    UndoHarness().manager,
                )
            runCurrent()
            assertNull(restored.uiState.value.restoredReceipt)
            assertFalse(restored.uiState.value.undoAvailable)
        }

    @Test
    fun aRelaunchedReadingViewModelDoesNotSaveAnUndoneRunAgain() =
        runTest(mainDispatcherRule.dispatcher) {
            val undo = UndoHarness()
            val repository = FakeReadingRepository(MiningRunState.Success("run", result()))
            val first = readingViewModel(repository, SavedStateHandle(), undo.manager)
            runCurrent()
            first.requestUndo()
            first.confirmUndo()
            runCurrent()
            assertEquals(1, first.uiState.value.undoneNoteCount)

            val relaunched = SavedStateHandle()
            readingViewModel(repository, relaunched, undo.manager)
            runCurrent()
            assertNull(MiningReceiptStore(relaunched, READING_RECEIPT).restore())

            val restored =
                readingViewModel(
                    FakeReadingRepository(MiningRunState.Idle),
                    relaunched.rebuilt(),
                    UndoHarness().manager,
                )
            runCurrent()
            assertNull(restored.uiState.value.restoredReceipt)
            assertFalse(restored.uiState.value.undoAvailable)
        }

    private fun mediaViewModel(
        repository: MiningRepository,
        savedStateHandle: SavedStateHandle,
        undoManager: MiningRunUndoManager?,
    ): MediaMiningViewModel =
        MediaMiningViewModel(
            repository = repository,
            safBroker = NoSafBroker,
            lane = MiningLane.VIDEO,
            savedStateHandle = savedStateHandle,
            selectionIoDispatcher = mainDispatcherRule.dispatcher,
            undoManager = undoManager,
        )

    private fun readingViewModel(
        repository: ReadingMiningRepository,
        savedStateHandle: SavedStateHandle,
        undoManager: MiningRunUndoManager?,
    ): ReadingMiningViewModel =
        ReadingMiningViewModel(
            repository = repository,
            safBroker = NoSafBroker,
            savedStateHandle = savedStateHandle,
            selectionIoDispatcher = mainDispatcherRule.dispatcher,
            undoManager = undoManager,
        )

    /** Rebuilt from the saved values, as Android restores it after the Activity or the process died. */
    private fun SavedStateHandle.rebuilt(): SavedStateHandle =
        SavedStateHandle(keys().associateWith { key -> get<Any?>(key) })

    /** The real process-owned undo manager over recording seams; one per simulated process. */
    private class UndoHarness(
        executor: Executor = Executor(Runnable::run),
        revertResults: List<Boolean> = emptyList(),
    ) {
        val deleteCalls = mutableListOf<List<Long>>()
        val revertCalls = mutableListOf<List<String>>()
        private val pendingRevertResults = ArrayDeque(revertResults)
        val manager: MiningRunUndoManager =
            ProcessMiningRunUndoManager(
                backend =
                    MiningRunUndoBackend { noteIds, _ ->
                        deleteCalls += noteIds
                        noteIds.size
                    },
                executor = executor,
                runtimeWorkCoordinator = RuntimeWorkCoordinator(),
                reverter =
                    object : MinedWordsReverter {
                        override suspend fun removeMinedWords(words: List<String>): Boolean {
                            revertCalls += words
                            return pendingRevertResults.removeFirstOrNull() ?: true
                        }
                    },
            )
    }

    private class FakeMiningRepository(
        initialState: MiningRunState,
    ) : MiningRepository {
        private val mutableState = MutableStateFlow(initialState)
        override val state: StateFlow<MiningRunState> = mutableState.asStateFlow()

        fun transitionTo(next: MiningRunState) {
            mutableState.value = next
        }

        override suspend fun startVideo(input: VideoMiningInput) {
            throw MiningCommandException("not used")
        }

        override suspend fun confirmCuration(
            runId: String,
            requestId: String,
            selection: List<CurationSelection>,
            pageIndex: Long?,
            knownCandidateIds: List<String>,
        ) = Unit

        override suspend fun cancel(runId: String) = Unit

        override suspend fun reset() {
            mutableState.value = MiningRunState.Idle
        }
    }

    private class FakeReadingRepository(
        initialState: MiningRunState,
    ) : ReadingMiningRepository {
        private val mutableState = MutableStateFlow(initialState)
        override val state: StateFlow<MiningRunState> = mutableState.asStateFlow()

        fun transitionTo(next: MiningRunState) {
            mutableState.value = next
        }

        override suspend fun startReading(input: ReadingMiningInput) {
            throw MiningCommandException("not used")
        }

        override suspend fun confirmCuration(
            runId: String,
            requestId: String,
            selection: List<CurationSelection>,
            pageIndex: Long?,
            knownCandidateIds: List<String>,
        ) = Unit

        override suspend fun cancel(runId: String) = Unit

        override suspend fun reset() {
            mutableState.value = MiningRunState.Idle
        }
    }

    private object NoSafBroker : SafBroker {
        override suspend fun retainReadAccess(uri: String): SafDocument = throw IllegalStateException("no picks here")

        override suspend fun releaseReadAccess(uri: String) = Unit

        override fun releaseReadAccessEventually(uri: String) = Unit
    }

    private companion object {
        const val VIDEO_RECEIPT = "videoMining.receipt"
        const val READING_RECEIPT = "readingMining.receipt"

        fun result(): ProcessingResult =
            ProcessingResult(
                totalWordsFound = 3,
                newWordsFound = 2,
                cardsCreated = 1,
                errors = emptyList(),
                elapsedTime = 1.5,
                comprehensionPercentage = 80.0,
                cardIds = listOf(42L),
                videoFile = "video.mkv",
                subtitleFile = "subtitle.srt",
                minedForms = listOf("食べる"),
                ankiWriteState = AnkiWriteState.NOTE_WRITE_CONFIRMED,
                failureIsTransient = false,
            )
    }
}
