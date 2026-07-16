package com.ankiminer.android.mining

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FakeMiningRepositoryTest {
    @Test
    fun emptyCurationIsAConfirmedZeroCardSuccess() =
        runTest {
            val repository =
                FakeMiningRepository(
                    stepDelayMillis = 0,
                    terminalOutcomes = listOf(FakeMiningRepository.TerminalOutcome.SUCCESS),
                    workScope = this,
                )

            repository.startVideo(input())
            val request = (repository.state.value as MiningRunState.Curating).request
            repository.confirmCuration(request.runId, request.requestId, emptyList())
            runCurrent()

            val success = repository.state.value as MiningRunState.Success
            assertNotNull(repository.confirmedSelection)
            assertTrue(repository.confirmedSelection.orEmpty().isEmpty())
            assertEquals(0L, success.result.cardsCreated)
            assertEquals(emptyList<String>(), success.result.minedForms)
        }

    @Test
    fun cancellingAtCurationRemainsDistinctFromEmptyConfirmation() =
        runTest {
            val repository = FakeMiningRepository(stepDelayMillis = 0, workScope = this)

            repository.startVideo(input())
            val request = (repository.state.value as MiningRunState.Curating).request
            repository.cancel(request.runId)

            assertTrue(repository.state.value is MiningRunState.Cancelled)
            assertNull(repository.confirmedSelection)
            assertEquals(1, repository.cancelCount)
        }

    @Test
    fun resultUsesTheOpaqueSubmittedCandidateIdsAndAlternateSentence() =
        runTest {
            val repository = FakeMiningRepository(stepDelayMillis = 0, workScope = this)

            repository.startVideo(input())
            val request = (repository.state.value as MiningRunState.Curating).request
            val selected = request.candidates.last()
            val sentence = selected.sentences.last()
            repository.confirmCuration(
                request.runId,
                request.requestId,
                listOf(CurationSelection(selected.candidateId, sentence.sentenceId)),
            )
            runCurrent()

            val success = repository.state.value as MiningRunState.Success
            assertEquals(listOf(selected.minedForm), success.result.minedForms)
            assertEquals(
                listOf(CurationSelection(selected.candidateId, sentence.sentenceId)),
                repository.confirmedSelection,
            )
        }

    @Test
    fun runningWorkCanBeCancelledAfterConfirmationReturns() =
        runTest {
            val repository = FakeMiningRepository(stepDelayMillis = 1, workScope = this)

            repository.startVideo(input())
            val request = (repository.state.value as MiningRunState.Curating).request
            repository.confirmCuration(request.runId, request.requestId, emptyList())

            assertTrue(repository.state.value is MiningRunState.Running)
            repository.cancel(request.runId)
            runCurrent()

            assertTrue(repository.state.value is MiningRunState.Cancelled)
            assertEquals(1, repository.cancelCount)
        }

    private fun input(): VideoMiningInput =
        VideoMiningInput(
            video = MiningSource("content://test/video", "video.mkv"),
            subtitle = MiningSource("content://test/subtitle", "subtitle.srt"),
        )
}
