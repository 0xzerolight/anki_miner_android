package com.ankiminer.android.ui.mining

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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
