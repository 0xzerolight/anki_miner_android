package com.ankiminer.android.mining

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Deterministic debug/test script which alternates successful and failed completed runs. */
internal class FakeMiningRepository(
    private val stepDelayMillis: Long = 350L,
    private val terminalOutcomes: List<TerminalOutcome> =
        listOf(TerminalOutcome.SUCCESS, TerminalOutcome.FAILURE),
    private val workScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : MiningRepository {
    internal enum class TerminalOutcome {
        SUCCESS,
        FAILURE,
    }

    private val mutableState = MutableStateFlow<MiningRunState>(MiningRunState.Idle)
    override val state: StateFlow<MiningRunState> = mutableState.asStateFlow()

    private var runSequence = 0L
    private var activeInput: VideoMiningInput? = null
    private var activeOutcome = TerminalOutcome.SUCCESS
    private var activeWorkJob: Job? = null
    @Volatile
    private var savedCurationSessionState: CurationSessionState? = null

    internal var confirmedSelection: List<CurationSelection>? = null
        private set

    internal var confirmedKnownCandidateIds: List<String> = emptyList()
        private set

    internal var cancelCount: Int = 0
        private set

    init {
        require(stepDelayMillis >= 0)
        require(terminalOutcomes.isNotEmpty())
    }

    override fun curationSessionState(): CurationSessionState? = savedCurationSessionState

    override fun saveCurationSessionState(state: CurationSessionState) {
        if (mutableState.value.runId == state.runId) {
            savedCurationSessionState = state
        }
    }

    override fun clearCurationSessionState(runId: String?) {
        if (runId == null || savedCurationSessionState?.runId == runId) {
            savedCurationSessionState = null
        }
    }

    override suspend fun startVideo(input: VideoMiningInput) {
        if (mutableState.value != MiningRunState.Idle) {
            throw MiningCommandException("A mining run is already active")
        }
        runSequence += 1
        val runId = "run_${runSequence.toString(16).padStart(32, '0')}"
        activeInput = input
        activeOutcome = terminalOutcomes[((runSequence - 1) % terminalOutcomes.size).toInt()]
        confirmedSelection = null
        confirmedKnownCandidateIds = emptyList()
        savedCurationSessionState = null

        mutableState.value =
            MiningRunState.Starting(
                runId = runId,
                progress = MiningProgress(0, 3, "Checking video and Anki target"),
            )
        if (!advanceStarting(runId, 1, "Parsing subtitles")) return
        if (!advanceStarting(runId, 2, "Filtering known vocabulary")) return
        if (!advanceStarting(runId, 3, "Preparing word curation")) return
        mutableState.value = MiningRunState.Curating(fakeCurationRequest(runId))
    }

    override suspend fun confirmCuration(
        runId: String,
        requestId: String,
        selection: List<CurationSelection>,
        pageIndex: Long?,
        knownCandidateIds: List<String>,
    ) {
        val curating = mutableState.value as? MiningRunState.Curating
            ?: throw MiningCommandException("No curation request is pending")
        if (
            curating.request.runId != runId ||
            curating.request.requestId != requestId ||
            curating.request.page?.pageIndex != pageIndex
        ) {
            throw MiningCommandException("The curation response is stale")
        }
        validateSelection(curating.request, selection)
        val acceptedSelection = selection.toList()
        val input = requireNotNull(activeInput)
        val outcome = activeOutcome
        val running =
            MiningRunState.Running(
                runId,
                MiningProgress(0, 3, "Extracting card media"),
            )
        if (!mutableState.compareAndSet(curating, running)) {
            throw MiningCommandException("The curation response is stale")
        }
        confirmedSelection = acceptedSelection
        confirmedKnownCandidateIds = knownCandidateIds.toList()
        val selectedForms =
            acceptedSelection.map { selected ->
                curating.request.candidates.single { it.candidateId == selected.candidateId }.minedForm
            }
        activeWorkJob =
            workScope.launch {
                completeRun(
                    runId = runId,
                    input = input,
                    selectedForms = selectedForms,
                    outcome = outcome,
                )
            }
    }

    private suspend fun completeRun(
        runId: String,
        input: VideoMiningInput,
        selectedForms: List<String>,
        outcome: TerminalOutcome,
    ) {
        if (!advanceRunning(runId, 1, "Looking up definitions")) return
        if (!advanceRunning(runId, 2, "Adding notes to Anki")) return
        if (!advanceRunning(runId, 3, "Finalizing mining result")) return

        val terminal =
            if (outcome == TerminalOutcome.FAILURE) {
                MiningRunState.Failed(
                    runId = runId,
                    failure =
                        MiningFailure(
                            message = "Debug script stopped after one partial note",
                            retryable = true,
                        ),
                    result =
                        result(
                            input = input,
                            selectedForms = selectedForms,
                            cardsCreated = minOf(1L, selectedForms.size.toLong()),
                            errors = listOf("Debug scripted failure"),
                        ),
                )
            } else {
                MiningRunState.Success(
                    runId = runId,
                    result =
                        result(
                            input = input,
                            selectedForms = selectedForms,
                            cardsCreated = selectedForms.size.toLong(),
                            errors = emptyList(),
                        ),
                )
            }
        while (true) {
            val running = mutableState.value as? MiningRunState.Running ?: return
            if (running.runId != runId) return
            if (mutableState.compareAndSet(running, terminal)) {
                savedCurationSessionState = null
                return
            }
        }
    }

    override suspend fun cancel(runId: String) {
        while (true) {
            val current = mutableState.value
            val currentRunId = current.runId
            if (currentRunId == null || currentRunId != runId || current.isTerminal) {
                throw MiningCommandException("The mining run cannot be cancelled")
            }
            val input = activeInput
            val cancelled =
                MiningRunState.Cancelled(
                    runId = runId,
                    result =
                        input?.let {
                            result(
                                input = it,
                                selectedForms = selectedFormsForConfirmedSelection(),
                                cardsCreated = 0,
                                errors = listOf("Processing cancelled by user"),
                            )
                        },
                )
            if (!mutableState.compareAndSet(current, cancelled)) continue
            cancelCount += 1
            activeWorkJob?.cancel()
            activeWorkJob = null
            savedCurationSessionState = null
            return
        }
    }

    override suspend fun reset() {
        if (!mutableState.value.isTerminal) {
            throw MiningCommandException("Only a terminal mining run can be reset")
        }
        activeWorkJob?.cancel()
        activeWorkJob = null
        activeInput = null
        confirmedSelection = null
        savedCurationSessionState = null
        mutableState.value = MiningRunState.Idle
    }

    private suspend fun advanceStarting(
        runId: String,
        current: Long,
        description: String,
    ): Boolean {
        delay(stepDelayMillis)
        val state = mutableState.value as? MiningRunState.Starting
        if (state?.runId != runId) return false
        mutableState.value =
            MiningRunState.Starting(runId, MiningProgress(current, 3, description))
        return true
    }

    private suspend fun advanceRunning(
        runId: String,
        current: Long,
        description: String,
    ): Boolean {
        delay(stepDelayMillis)
        while (true) {
            val state = mutableState.value as? MiningRunState.Running ?: return false
            if (state.runId != runId) return false
            val next = MiningRunState.Running(runId, MiningProgress(current, 3, description))
            if (mutableState.compareAndSet(state, next)) return true
        }
    }

    private fun validateSelection(
        request: CurationRequest,
        selection: List<CurationSelection>,
    ) {
        if (selection.map { it.candidateId }.toSet().size != selection.size) {
            throw MiningCommandException("A candidate was selected twice")
        }
        selection.forEach { item ->
            val candidate = request.candidates.singleOrNull { it.candidateId == item.candidateId }
                ?: throw MiningCommandException("The selected candidate is unknown")
            val sentenceId = item.sentenceId ?: candidate.defaultSentenceId
            if (candidate.sentences.none { it.sentenceId == sentenceId }) {
                throw MiningCommandException("The selected sentence is unknown")
            }
        }
    }

    private fun result(
        input: VideoMiningInput,
        selectedForms: List<String>,
        cardsCreated: Long,
        errors: List<String>,
    ): ProcessingResult =
        ProcessingResult(
            totalWordsFound = 14,
            newWordsFound = selectedForms.size.toLong(),
            cardsCreated = cardsCreated,
            errors = errors,
            elapsedTime = 2.4,
            comprehensionPercentage = 64.3,
            cardIds = (1L..cardsCreated).map { 9000L + it },
            videoFile = input.video.displayName,
            subtitleFile = input.subtitle.displayName,
            minedForms = selectedForms,
            ankiWriteState = AnkiWriteState.NOTE_WRITE_CONFIRMED,
            failureIsTransient = false,
        )

    private fun selectedFormsForConfirmedSelection(): List<String> =
        confirmedSelection.orEmpty().mapNotNull { selected ->
            FAKE_CANDIDATES.singleOrNull { it.candidateId == selected.candidateId }?.minedForm
        }

    private fun fakeCurationRequest(runId: String): CurationRequest =
        CurationRequest(
            runId = runId,
            requestId = "curation_${runSequence.toString(16).padStart(32, '0')}",
            candidates = FAKE_CANDIDATES,
        )

    private companion object {
        val FAKE_CANDIDATES =
            listOf(
                candidate(
                    ordinal = 1,
                    minedForm = "食べる",
                    reading = "たべる",
                    frequency = 612,
                    sentences = listOf("猫が魚を食べる。", "一緒に朝ご飯を食べる。"),
                ),
                candidate(
                    ordinal = 2,
                    minedForm = "懐かしい",
                    reading = "なつかしい",
                    frequency = 2840,
                    sentences = listOf("この歌は懐かしい。"),
                ),
                candidate(
                    ordinal = 3,
                    minedForm = "駆け出す",
                    reading = "かけだす",
                    frequency = null,
                    sentences = listOf("駅へ向かって駆け出した。"),
                ),
            )

        fun candidate(
            ordinal: Int,
            minedForm: String,
            reading: String,
            frequency: Long?,
            sentences: List<String>,
        ): CurationCandidate {
            val candidateId = "candidate_${ordinal.toString(16).padStart(32, '0')}"
            val sentenceModels =
                sentences.mapIndexed { index, sentence ->
                    CurationSentence(
                        sentenceId =
                            "sentence_${(ordinal * 10 + index).toString(16).padStart(32, '0')}",
                        sentence = sentence,
                        sentenceFurigana = sentence,
                        sentenceReading = sentence,
                        startTime = ordinal * 2.0 + index,
                        endTime = ordinal * 2.0 + index + 1.5,
                        duration = 1.5,
                    )
                }
            return CurationCandidate(
                candidateId = candidateId,
                minedForm = minedForm,
                surface = minedForm,
                lemma = minedForm,
                reading = reading,
                expressionReading = reading,
                partOfSpeech = "動詞",
                frequencyRank = frequency,
                occurrenceCount = ordinal.toLong(),
                defaultSentenceId = sentenceModels.first().sentenceId,
                sentences = sentenceModels,
            )
        }
    }
}
