package com.ankiminer.android.mining

enum class RuntimeWorkConflict {
    MINING,
    RESOURCE,
    ANKI_SETUP,
}

@JvmInline
value class MiningCancellationToken(val value: String) {
    init {
        require(TOKEN.matches(value))
    }

    private companion object {
        val TOKEN = Regex("cancel_[0-9a-f]{32}")
    }
}

data class MiningSource(
    val uri: String,
    val displayName: String,
) {
    init {
        require(uri.isNotBlank())
        require(displayName.isNotBlank())
    }
}

data class VideoMiningInput(
    val video: MiningSource,
    val subtitle: MiningSource,
)

data class MiningProgress(
    val current: Long,
    val total: Long,
    val description: String,
) {
    init {
        require(total >= 0)
        require(current >= 0)
        require(total == 0L || current <= total)
    }

    val fraction: Float?
        get() = if (total == 0L) null else current.toFloat() / total.toFloat()
}

/**
 * Exact camel-case mirror of the desktop ProcessingResult bridge payload.
 *
 * The legacy wire names are misleading: `cardsCreated` counts successful Anki note inserts and
 * `cardIds` contains the corresponding note IDs. Keep those field names for bridge compatibility;
 * user-facing copy must describe notes, because one note may generate multiple cards.
 */
data class ProcessingResult(
    val totalWordsFound: Long,
    val newWordsFound: Long,
    val cardsCreated: Long,
    val errors: List<String>,
    val elapsedTime: Double,
    val comprehensionPercentage: Double,
    val cardIds: List<Long>,
    val videoFile: String,
    val subtitleFile: String,
    val minedForms: List<String>,
)

data class CurationSentence(
    val sentenceId: String,
    val sentence: String,
    val sentenceFurigana: String,
    val sentenceReading: String,
    val startTime: Double,
    val endTime: Double,
    val duration: Double,
) {
    init {
        require(sentenceId.isNotBlank())
    }
}

data class CurationCandidate(
    val candidateId: String,
    val minedForm: String,
    val surface: String,
    val lemma: String,
    val reading: String,
    val expressionReading: String,
    val partOfSpeech: String?,
    val frequencyRank: Long?,
    val occurrenceCount: Long,
    val defaultSentenceId: String,
    val sentences: List<CurationSentence>,
) {
    init {
        require(candidateId.isNotBlank())
        require(defaultSentenceId.isNotBlank())
        require(occurrenceCount >= 0)
        require(sentences.isNotEmpty())
        require(sentences.map { it.sentenceId }.toSet().size == sentences.size)
        require(sentences.any { it.sentenceId == defaultSentenceId })
    }
}

const val CURATION_PAGE_MAX_CANDIDATES = 100

data class CurationPage(
    val pageIndex: Long,
    val pageCount: Long,
    val candidateStart: Long,
    val totalCandidates: Long,
) {
    init {
        require(pageIndex >= 0)
        require(pageCount >= 2)
        require(pageIndex < pageCount)
        require(candidateStart >= 0)
        require(totalCandidates >= 2)
        require(pageCount <= totalCandidates)
    }
}

data class CurationRequest(
    val runId: String,
    val requestId: String,
    val candidates: List<CurationCandidate>,
    val page: CurationPage? = null,
) {
    init {
        require(runId.isNotBlank())
        require(requestId.isNotBlank())
        require(candidates.size <= CURATION_PAGE_MAX_CANDIDATES)
        require(candidates.map { it.candidateId }.toSet().size == candidates.size)
        page?.let { metadata ->
            require(candidates.isNotEmpty())
            require(metadata.candidateStart >= metadata.pageIndex)
            require(metadata.pageIndex != 0L || metadata.candidateStart == 0L)
            val candidateCount = candidates.size.toLong()
            require(metadata.candidateStart <= metadata.totalCandidates - candidateCount)
            val candidateEnd = metadata.candidateStart + candidateCount
            val remainingPages = metadata.pageCount - metadata.pageIndex - 1
            val remainingCandidates = metadata.totalCandidates - candidateEnd
            require(remainingCandidates >= remainingPages)
            if (metadata.pageIndex == metadata.pageCount - 1) {
                require(candidateEnd == metadata.totalCandidates)
            } else {
                require(candidateEnd < metadata.totalCandidates)
            }
        }
    }

    val isFinalPage: Boolean
        get() = page?.let { it.pageIndex == it.pageCount - 1 } ?: true
}

data class CurationSelection(
    val candidateId: String,
    val sentenceId: String?,
) {
    init {
        require(candidateId.isNotBlank())
        require(sentenceId == null || sentenceId.isNotBlank())
    }
}

data class MiningFailure(
    val message: String,
    val retryable: Boolean,
) {
    init {
        require(message.isNotBlank())
    }
}

sealed interface MiningRunState {
    data object Idle : MiningRunState

    data class Starting(
        val runId: String?,
        val progress: MiningProgress?,
        val cancellationToken: MiningCancellationToken? = null,
    ) : MiningRunState

    data class Curating(
        val request: CurationRequest,
        val pageSubmissionPending: Boolean = false,
    ) : MiningRunState

    data class Running(
        val runId: String,
        val progress: MiningProgress,
    ) : MiningRunState

    data class Success(
        val runId: String,
        val result: ProcessingResult,
    ) : MiningRunState

    data class Cancelled(
        val runId: String?,
        val result: ProcessingResult?,
    ) : MiningRunState

    data class Failed(
        val runId: String?,
        val failure: MiningFailure,
        val result: ProcessingResult?,
    ) : MiningRunState
}

val MiningRunState.runId: String?
    get() =
        when (this) {
            MiningRunState.Idle -> null
            is MiningRunState.Starting -> runId
            is MiningRunState.Curating -> request.runId
            is MiningRunState.Running -> runId
            is MiningRunState.Success -> runId
            is MiningRunState.Cancelled -> runId
            is MiningRunState.Failed -> runId
        }

val MiningRunState.cancellationToken: MiningCancellationToken?
    get() = (this as? MiningRunState.Starting)?.cancellationToken

val MiningRunState.isTerminal: Boolean
    get() =
        this is MiningRunState.Success ||
            this is MiningRunState.Cancelled ||
            this is MiningRunState.Failed
