package com.ankiminer.android.mining

import androidx.compose.runtime.Immutable
import com.ankiminer.android.data.settings.EngineDefaults
import com.ankiminer.android.media.SafSelectionSlot

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

/**
 * The engine dataclass default for `subtitle_offset`, kept under its mining-side name for the
 * per-run offset call sites. The value itself lives in
 * [com.ankiminer.android.data.settings.EngineDefaults]; this is an alias, not a second mirror.
 *
 * Guarded twice over: the Python side's test_cues_engine_default_offset_is_zero, and
 * test_engine_defaults_mirror against `AnkiMinerConfig()`.
 */
const val ENGINE_DEFAULT_SUBTITLE_OFFSET = EngineDefaults.SUBTITLE_OFFSET_SECONDS

data class VideoMiningInput(
    val video: MiningSource,
    val subtitle: MiningSource,
    /** Per-run override; null keeps the global setting or the engine default. */
    val subtitleOffsetOverride: Double? = null,
    /** Per-run override; null keeps the global setting or the engine default. */
    val audioTrackOverride: Long? = null,
) {
    init {
        subtitleOffsetOverride?.let { require(it.isFinite()) }
        audioTrackOverride?.let { require(it >= 0) }
    }
}

/** What [MiningProgress.current] counts, so a byte count never renders as an item count. */
enum class MiningProgressUnit {
    ITEMS,
    BYTES,
}

data class MiningProgress(
    val current: Long,
    val total: Long,
    val description: String,
    val unit: MiningProgressUnit = MiningProgressUnit.ITEMS,
    val stage: MiningStage? = null,
    /** Highest whole-run fraction already published; the bar never renders below it. */
    val fractionFloor: Float = 0f,
    /** The stage's progress cycle completed, so a zero-total band still fills. */
    val stageComplete: Boolean = false,
) {
    init {
        require(total >= 0)
        require(current >= 0)
        // current > total no longer throws: an engine counting slip is display noise,
        // not a protocol fault. The fraction clamps instead.
    }

    /**
     * Whole-run completion.
     *
     * The engine stopped blending its stages into one percentage, so each stage
     * restarts the item counts. Composing the within-stage fraction inside the
     * stage's own band keeps a single monotonic bar instead of one that resets
     * several times per run. Without a stage the raw item fraction is all there
     * is.
     *
     * A stage also runs several counted sub-cycles, each restarting at zero, so
     * the result never falls below [fractionFloor] and never exceeds its band:
     * an item count overrunning its total clamps, and [stageComplete] fills the
     * band a finished zero-total cycle would otherwise leave empty. Indeterminate
     * stays indeterminate — a zero-total unstaged cycle returns null whatever the
     * floor, because a number there would render a determinate bar for work of
     * unknown size.
     */
    val fraction: Float?
        get() {
            val within =
                when {
                    stageComplete -> 1f
                    total == 0L -> null
                    else -> (current.toFloat() / total.toFloat()).coerceAtMost(1f)
                }
            val stage = stage ?: return within?.let { maxOf(it, fractionFloor) }
            val band = 1f / stage.total
            val raw = (stage.index - 1) * band + (within ?: 0f) * band
            return maxOf(raw, fractionFloor)
        }
}

/** One numbered pipeline stage, as announced by the engine. */
data class MiningStage(
    val index: Int,
    val total: Int,
    val name: String,
) {
    init {
        require(index in 1..total)
        require(total >= 1)
    }
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
    val ankiWriteState: AnkiWriteState,
    val failureIsTransient: Boolean,
)

/**
 * What a finished run can prove about whether Anki notes were written.
 *
 * The engine fails closed to [NOTE_WRITE_UNCERTAIN] whenever it cannot tell, so
 * only [NO_NOTE_WRITE] means nothing reached the collection. Android never sets
 * [ProcessingResult.failureIsTransient]: the engine's classifier recognizes only
 * an AnkiConnect transport failure, and the ContentProvider seam never raises
 * through that path — so no automatic retry may be built on it here.
 */
enum class AnkiWriteState(val wireValue: String) {
    NO_NOTE_WRITE("no_note_write"),
    NOTE_WRITE_UNCERTAIN("note_write_uncertain"),
    NOTE_WRITE_CONFIRMED("note_write_confirmed"),
    ;

    companion object {
        fun fromWire(value: String): AnkiWriteState? = entries.firstOrNull { it.wireValue == value }
    }
}

/** A mokuro page-image crop box, in source-image pixel coordinates. */
@Immutable
data class CurationBlockBox(
    val xMin: Long,
    val yMin: Long,
    val xMax: Long,
    val yMax: Long,
)

/**
 * Where a manga curation sentence was read from, for the reading-lane page preview.
 *
 * Optional on [CurationSentence]: present only for manga runs, and always all three fields
 * together — the bridge never sends a partial page context.
 */
@Immutable
data class CurationPageContext(
    val imageEntry: String,
    val blockBox: CurationBlockBox,
    val locationLabel: String,
) {
    init {
        require(imageEntry.isNotEmpty()) { "imageEntry must not be empty" }
    }
}

@Immutable
data class CurationSentence(
    val sentenceId: String,
    val sentence: String,
    val sentenceFurigana: String,
    val sentenceReading: String,
    val startTime: Double,
    val endTime: Double,
    val duration: Double,
    val pageContext: CurationPageContext? = null,
) {
    init {
        require(sentenceId.isNotBlank())
    }
}

@Immutable
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

/**
 * "Add previous/next subtitle line" intent for one candidate. A `(0, 0)` entry never exists —
 * no expansion is the absence of the map entry, so equality and persistence stay canonical.
 */
@Immutable
data class CurationLineExpansion(
    val linesBefore: Int,
    val linesAfter: Int,
) {
    init {
        require(linesBefore >= 0)
        require(linesAfter >= 0)
        require(linesBefore > 0 || linesAfter > 0)
    }
}

/** Shortest clip the trim control will produce; matches the engine's own MIN_CLIP_SECONDS. */
const val MIN_CLIP_SECONDS = 0.2

/** Mirrors the desktop curator's clip ceiling; the engine itself applies no cap. */
const val MAX_CLIP_SECONDS = 30.0

// A trim control quantises its output onto a 0.1s tick grid (ticks / 10.0), and the raw
// double subtraction of two such values cannot express an inclusive 0.2s floor - e.g.
// 1.2 - 1.0 == 0.19999999999999996. The bounds check below rounds to the tick grid instead
// of comparing the raw subtraction, so tick counts are derived from the named bounds rather
// than re-stated as literals.
private val MIN_CLIP_TICKS = Math.round(MIN_CLIP_SECONDS * 10.0)
private val MAX_CLIP_TICKS = Math.round(MAX_CLIP_SECONDS * 10.0)

/**
 * A user-trimmed audio window, in absolute seconds on the source timeline.
 *
 * The engine consumes this verbatim - `resolve_audio_window` adds no padding on top of an
 * override - so whatever padding the window should carry is already inside these two numbers.
 */
@Immutable
data class CurationClipWindow(
    val startSeconds: Double,
    val endSeconds: Double,
) {
    init {
        require(startSeconds.isFinite() && endSeconds.isFinite())
        require(startSeconds >= 0.0)
        val lengthTicks = Math.round((endSeconds - startSeconds) * 10.0)
        require(lengthTicks in MIN_CLIP_TICKS..MAX_CLIP_TICKS)
    }

    val lengthSeconds: Double get() = endSeconds - startSeconds
}

data class CurationSelection(
    val candidateId: String,
    val sentenceId: String?,
    val linesBefore: Int = 0,
    val linesAfter: Int = 0,
    val clip: CurationClipWindow? = null,
) {
    init {
        require(candidateId.isNotBlank())
        require(sentenceId == null || sentenceId.isNotBlank())
        require(linesBefore >= 0)
        require(linesAfter >= 0)
    }
}

/**
 * Compact user intent for one process-owned curation request.
 *
 * Candidate payloads remain in [CurationRequest]. This snapshot only keeps identities and the
 * cross-page count needed to recreate an Activity-scoped ViewModel without changing selections.
 */
data class CurationSessionState(
    val runId: String,
    val requestId: String,
    val pageIndex: Long?,
    val selectedCandidateIds: Set<String>,
    val sentenceIds: Map<String, String>,
    val focusedCandidateId: String?,
    val previousPageSelectedCount: Int,
    val knownCandidateIds: Set<String> = emptySet(),
    val lineExpansions: Map<String, CurationLineExpansion> = emptyMap(),
    val clipOverrides: Map<String, CurationClipWindow> = emptyMap(),
) {
    init {
        require(runId.isNotBlank())
        require(requestId.isNotBlank())
        require(previousPageSelectedCount >= 0)
    }
}

data class MiningFailure(
    val message: String,
    val retryable: Boolean,
    /**
     * Opaque key joining this failure to the Python traceback in the exported log. Defaulted so a
     * Kotlin-side failure, which has no Python traceback to point at, constructs unchanged.
     */
    val faultId: String? = null,
    /**
     * Stable snake_case name of what failed, for diagnostics only — never shown to the user, and no
     * UI copy may be derived from it.
     *
     * [message] is localized and is the only account of the failure the UI shows, so on a non-English
     * device the failure is described in a language the maintainer may not read; two different
     * failures can also resolve to the same string. This is the locale-independent half. It is a
     * code, never a throwable: this class is compared by value in the JVM tests.
     */
    val diagnostic: String? = null,
) {
    init {
        require(message.isNotBlank())
    }
}

/**
 * Media the curation UI may preview. Both paths are the run's staged cache copies,
 * alive until the input owner closes in finishRun. Null on the reading lane.
 */
data class CurationMediaBinding(
    val videoPath: String,
    val subtitlePath: String,
    val audioOnly: Boolean = false,
    /** Per-run override; null keeps the global setting or the engine default. */
    val audioTrackOverride: Long? = null,
)

/** The manga archive a curation pane can render page crops from. Null on non-manga lanes. */
data class CurationPageImageBinding(
    val archivePath: String,
)

/** Which media mining lane a repository/ViewModel pair serves. */
internal enum class MiningLane(
    val runKind: MiningRunKind,
    val audioOnly: Boolean,
    val seriesLabel: String,
    val documentSlot: SafSelectionSlot,
    val subtitleSlot: SafSelectionSlot,
    val savedStateKeyPrefix: String,
) {
    VIDEO(
        MiningRunKind.VIDEO,
        false,
        "Local video",
        SafSelectionSlot.VIDEO,
        SafSelectionSlot.VIDEO_SUBTITLE,
        "videoMining",
    ),
    AUDIO(
        MiningRunKind.AUDIO,
        true,
        "Local audio",
        SafSelectionSlot.AUDIO,
        SafSelectionSlot.AUDIO_SUBTITLE,
        "audioMining",
    ),
}

sealed interface MiningRunState {
    data object Idle : MiningRunState

    data class Starting(
        val runId: String?,
        val progress: MiningProgress?,
        val cancellationToken: MiningCancellationToken? = null,
        val cancellationPending: Boolean = false,
    ) : MiningRunState

    data class Curating(
        val request: CurationRequest,
        val pageSubmissionPending: Boolean = false,
        val cancellationPending: Boolean = false,
        val media: CurationMediaBinding? = null,
        val pageImage: CurationPageImageBinding? = null,
    ) : MiningRunState

    data class Running(
        val runId: String,
        val progress: MiningProgress,
        val cancellationPending: Boolean = false,
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

val MiningRunState.cancellationPending: Boolean
    get() =
        when (this) {
            is MiningRunState.Starting -> cancellationPending
            is MiningRunState.Curating -> cancellationPending
            is MiningRunState.Running -> cancellationPending
            else -> false
        }

val MiningRunState.isTerminal: Boolean
    get() =
        this is MiningRunState.Success ||
            this is MiningRunState.Cancelled ||
            this is MiningRunState.Failed
