package com.ankiminer.android.ui.mining

import androidx.compose.runtime.Immutable
import com.ankiminer.android.engine.SubtitleCue
import com.ankiminer.android.mining.CurationSentence
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Mirrors the desktop curator's clip ceiling; the engine itself applies no cap. */
const val MAX_CLIP_SECONDS = 30.0

/** Matches the engine materializer's cue-match tolerance, not the desktop dialog's looser 0.05. */
private const val CUE_MATCH_TOLERANCE_SECONDS = 1e-3

private const val CUE_JOINER = " "

@Immutable
data class ExpansionPreview(
    val sentence: String,
    val startTime: Double,
    val endTime: Double,
    val duration: Double,
    val canExpandPrev: Boolean,
    val canExpandNext: Boolean,
)

/**
 * Merged-window preview and button gating for "+ previous/next line", computed from the same
 * offset-applied cue list the engine's materializer parses, so the preview matches the card the
 * confirmed counts will produce. Returns null when no cue matches the sentence (text equality
 * first, nearest start within tolerance) - the caller hides the controls; the engine would keep
 * the original line for the same reason.
 */
fun expansionPreview(
    cues: List<SubtitleCue>,
    sentence: CurationSentence,
    linesBefore: Int,
    linesAfter: Int,
    audioPaddingSeconds: Double,
): ExpansionPreview? {
    val index = findCueIndex(cues, sentence.startTime, sentence.sentence) ?: return null
    val window = mergeCueWindow(cues, index, linesBefore, linesAfter)
    val padding = 2 * audioPaddingSeconds
    val canExpandPrev =
        index - (linesBefore + 1) >= 0 &&
            mergeCueWindow(cues, index, linesBefore + 1, linesAfter).span + padding <= MAX_CLIP_SECONDS
    val canExpandNext =
        index + linesAfter + 1 < cues.size &&
            mergeCueWindow(cues, index, linesBefore, linesAfter + 1).span + padding <= MAX_CLIP_SECONDS
    return ExpansionPreview(
        sentence = window.text,
        startTime = window.start,
        endTime = window.end,
        duration = window.span,
        canExpandPrev = canExpandPrev,
        canExpandNext = canExpandNext,
    )
}

private data class MergedCueWindow(
    val start: Double,
    val end: Double,
    val text: String,
) {
    val span: Double get() = end - start
}

private fun findCueIndex(
    cues: List<SubtitleCue>,
    startTime: Double,
    sentence: String,
): Int? {
    fun dist(index: Int) = abs(max(0.0, cues[index].startSeconds) - startTime)

    val textHits = cues.indices.filter { cues[it].text == sentence }
    val pool = textHits.ifEmpty { cues.indices.toList() }
    val best = pool.minByOrNull(::dist) ?: return null
    return best.takeIf { dist(it) <= CUE_MATCH_TOLERANCE_SECONDS }
}

private fun mergeCueWindow(
    cues: List<SubtitleCue>,
    index: Int,
    prevCount: Int,
    nextCount: Int,
): MergedCueWindow {
    val lo = max(0, index - prevCount)
    val hi = min(cues.size - 1, index + nextCount)
    val merged = cues.subList(lo, hi + 1)
    return MergedCueWindow(
        start = merged.minOf(SubtitleCue::startSeconds),
        end = merged.maxOf(SubtitleCue::endSeconds),
        text = merged.joinToString(CUE_JOINER, transform = SubtitleCue::text),
    )
}
