package com.ankiminer.android.reading

import com.ankiminer.android.mining.AnkiWriteState
import com.ankiminer.android.mining.CurationCandidate
import com.ankiminer.android.mining.CurationRequest
import com.ankiminer.android.mining.CurationSelection
import com.ankiminer.android.mining.CurationSentence
import com.ankiminer.android.mining.MiningCommandException
import com.ankiminer.android.mining.MiningFailure
import com.ankiminer.android.mining.MiningProgress
import com.ankiminer.android.mining.MiningRunState
import com.ankiminer.android.mining.ProcessingResult
import com.ankiminer.android.mining.isTerminal
import com.ankiminer.android.mining.runId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Deterministic debug path used when the physical-device tokenizer receipt is unavailable. */
internal class FakeReadingMiningRepository : ReadingMiningRepository {
    private val mutableState = MutableStateFlow<MiningRunState>(MiningRunState.Idle)
    override val state: StateFlow<MiningRunState> = mutableState.asStateFlow()
    private var sequence = 0L

    override suspend fun startReading(input: ReadingMiningInput) {
        if (mutableState.value != MiningRunState.Idle) {
            throw MiningCommandException("A mining run is already active")
        }
        sequence += 1
        val runId = "run_${sequence.toString(16).padStart(32, '0')}"
        mutableState.value =
            MiningRunState.Curating(
                CurationRequest(
                    runId = runId,
                    requestId = "curation_${sequence.toString(16).padStart(32, '0')}",
                    candidates = FAKE_CANDIDATES,
                ),
            )
    }

    override suspend fun confirmCuration(
        runId: String,
        requestId: String,
        selection: List<CurationSelection>,
        pageIndex: Long?,
    ) {
        val current = mutableState.value as? MiningRunState.Curating
            ?: throw MiningCommandException("No curation request is pending")
        if (
            current.request.runId != runId ||
            current.request.requestId != requestId ||
            pageIndex != null
        ) {
            throw MiningCommandException("The curation response is stale")
        }
        val selected = selection.map { item ->
            current.request.candidates.singleOrNull { it.candidateId == item.candidateId }
                ?: throw MiningCommandException("The curation response is invalid")
        }
        mutableState.value =
            MiningRunState.Success(
                runId,
                ProcessingResult(
                    totalWordsFound = 24,
                    newWordsFound = selected.size.toLong(),
                    cardsCreated = selected.size.toLong(),
                    errors = emptyList(),
                    elapsedTime = 1.2,
                    comprehensionPercentage = 88.0,
                    cardIds = selected.indices.map { 12_001L + it },
                    videoFile = "",
                    subtitleFile = "debug-reading.txt",
                    minedForms = selected.map(CurationCandidate::minedForm),
                    ankiWriteState = AnkiWriteState.NOTE_WRITE_CONFIRMED,
                    failureIsTransient = false,
                ),
            )
    }

    override suspend fun cancel(runId: String) {
        if (mutableState.value.runId != runId || mutableState.value.isTerminal) {
            throw MiningCommandException("The mining run cannot be cancelled")
        }
        mutableState.value = MiningRunState.Cancelled(runId, null)
    }

    override suspend fun reset() {
        if (!mutableState.value.isTerminal) {
            throw MiningCommandException("Only a terminal mining run can be reset")
        }
        mutableState.value = MiningRunState.Idle
    }

    private companion object {
        val FAKE_CANDIDATES =
            listOf(
                candidate(1, "懐かしい", "なつかしい", "この景色は懐かしい。"),
                candidate(2, "読み進める", "よみすすめる", "夜まで本を読み進めた。"),
            )

        fun candidate(
            ordinal: Int,
            expression: String,
            reading: String,
            sentence: String,
        ): CurationCandidate {
            val candidateId = "candidate_${ordinal.toString(16).padStart(32, '0')}"
            val sentenceId = "sentence_${ordinal.toString(16).padStart(32, '0')}"
            return CurationCandidate(
                candidateId = candidateId,
                minedForm = expression,
                surface = expression,
                lemma = expression,
                reading = reading,
                expressionReading = reading,
                partOfSpeech = "debug",
                frequencyRank = null,
                occurrenceCount = 1,
                defaultSentenceId = sentenceId,
                sentences =
                    listOf(
                        CurationSentence(
                            sentenceId = sentenceId,
                            sentence = sentence,
                            sentenceFurigana = sentence,
                            sentenceReading = sentence,
                            startTime = 0.0,
                            endTime = 0.0,
                            duration = 0.0,
                        ),
                    ),
            )
        }
    }
}
