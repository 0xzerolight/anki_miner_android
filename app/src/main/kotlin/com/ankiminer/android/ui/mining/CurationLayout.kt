package com.ankiminer.android.ui.mining

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min
import kotlin.math.roundToInt

/** Share of the curation pane a video frame or manga page may take; the rest stays the word list. */
internal const val CURATION_MEDIA_HEIGHT_FRACTION = 0.35f

/** Below this a frame is a strip, not a picture; landscape phones land here. */
internal val CurationMediaMinHeight = 96.dp

/**
 * Tallest a curation media surface may draw in a pane [paneHeight] tall. A full-width 16:9 frame
 * has no height bound of its own: landscape showed zero rows, 320x640 about 30dp of list.
 */
internal fun curationMediaMaxHeight(paneHeight: Dp): Dp =
    (paneHeight * CURATION_MEDIA_HEIGHT_FRACTION).coerceAtLeast(CurationMediaMinHeight)

/**
 * Full width at [aspectRatio], but never taller than [maxHeight]; the content letterboxes inside.
 *
 * `fillMaxWidth().heightIn(max).aspectRatio()` does not do this: when no size satisfies both the
 * exact width and the cap, `aspectRatio` falls back to the unconstrained full-width size and the
 * surface draws past the cap.
 */
internal fun Modifier.cappedAspectRatio(
    aspectRatio: Float,
    maxHeight: Dp,
): Modifier =
    fillMaxWidth().layout { measurable, constraints ->
        val cap = if (maxHeight == Dp.Unspecified) Constraints.Infinity else maxHeight.roundToPx()
        val width =
            if (constraints.hasBoundedWidth) {
                constraints.maxWidth
            } else {
                (cap * aspectRatio).roundToInt()
            }
        val height =
            min((width / aspectRatio).roundToInt(), cap)
                .coerceIn(constraints.minHeight, constraints.maxHeight)
        val placeable = measurable.measure(Constraints.fixed(width, height))
        layout(width, height) { placeable.place(0, 0) }
    }

internal val CurationDefinitionMinHeight = 96.dp
private val CurationDefinitionCap = 260.dp

/** At most half the pane, so the list keeps somewhere to take a swipe. */
internal fun curationDefinitionMaxHeight(paneHeight: Dp): Dp =
    (paneHeight * 0.5f).coerceIn(CurationDefinitionMinHeight, CurationDefinitionCap)

private const val CURATION_TOOLS_OPEN_MIN_CANDIDATES = 11
internal const val CURATION_COMPACT_WINDOW_HEIGHT_DP = 720

/** Search and sort start folded when the page fits anyway or the window is short (phones, landscape). */
internal fun curationToolsStartExpanded(
    candidateCount: Int,
    windowHeightDp: Int,
): Boolean =
    candidateCount >= CURATION_TOOLS_OPEN_MIN_CANDIDATES && windowHeightDp >= CURATION_COMPACT_WINDOW_HEIGHT_DP

/** Where a candidate header sits in a run of flush rows; an expanded candidate breaks the run. */
internal enum class CurationRowPosition {
    ONLY,
    FIRST,
    MIDDLE,
    LAST,
    ;

    val startsRun: Boolean get() = this == ONLY || this == FIRST
    val endsRun: Boolean get() = this == ONLY || this == LAST
}

internal fun curationRowPositions(
    visibleCandidateIds: List<String>,
    expandedCandidateId: String?,
): List<CurationRowPosition> =
    visibleCandidateIds.indices.map { index ->
        val id = visibleCandidateIds[index]
        val starts =
            index == 0 || id == expandedCandidateId || visibleCandidateIds[index - 1] == expandedCandidateId
        val ends =
            index == visibleCandidateIds.lastIndex ||
                id == expandedCandidateId ||
                visibleCandidateIds[index + 1] == expandedCandidateId
        when {
            starts && ends -> CurationRowPosition.ONLY
            starts -> CurationRowPosition.FIRST
            ends -> CurationRowPosition.LAST
            else -> CurationRowPosition.MIDDLE
        }
    }
