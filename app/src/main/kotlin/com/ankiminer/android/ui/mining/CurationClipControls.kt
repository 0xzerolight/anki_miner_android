package com.ankiminer.android.ui.mining

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.ankiminer.android.R
import com.ankiminer.android.mining.CurationClipWindow
import com.ankiminer.android.ui.theme.AnkiMinerTokens

/**
 * The per-word audio trim row: a continuous range slider over the clip's travel with a
 * set-at-playhead button either side, a play/stop button that previews exactly the in-flight
 * window, and an explicit reset.
 *
 * Renders whenever the caller has a legal [state] to show - the player-and-timings gate lives in
 * the caller (mirrors [CurationExpansionControls]).
 *
 * The slider is continuous rather than stepped: the tick grid is 0.1s over a travel of several
 * seconds, and Material's step rendering would draw a tick mark per step. Quantisation happens in
 * the drag callback, via [coerceClipWindow].
 *
 * [live] holds a [ClipWindowSeconds], not a [CurationClipWindow]: a window seeded straight from
 * an untouched subtitle line can be longer than the wire type's 30s ceiling, and that type's
 * `init` block throws outside it. [onWindowChange] only fires from [onValueChangeFinished], after
 * [coerceClipWindow] has already legalised the drag - the one place a [CurationClipWindow] can be
 * built without risk. [onPlay] takes the raw [ClipWindowSeconds] for the same reason: playing the
 * window currently on screen must not require it to already be wire-legal.
 *
 * A button either side of the slider puts that end at the preview's playhead ([readPlayhead],
 * desktop's start-here/end-here): for the exact moment a drag keeps missing, found in the player
 * first. It never pauses: an end is set while listening, against the line still playing. The
 * result goes through [clipWindowAtPlayhead], which already keeps it wire-legal.
 */
@Composable
internal fun CurationClipControls(
    containerColor: Color,
    state: ClipWindowUiState,
    enabled: Boolean,
    playing: Boolean,
    sliderTestTag: String,
    playTestTag: String,
    resetTestTag: String,
    readoutTestTag: String,
    startHereTestTag: String,
    endHereTestTag: String,
    onWindowChange: (CurationClipWindow) -> Unit,
    onReset: () -> Unit,
    onPlay: (ClipWindowSeconds) -> Unit,
    onStop: () -> Unit,
    readPlayhead: () -> Double?,
    modifier: Modifier = Modifier,
) {
    // remember(state.window) re-seeds `live` whenever the source window changes (a new candidate,
    // a new sentence, an expansion, a reset) and only then - mid-drag the slider owns its own
    // value, which is what keeps it from fighting the recomposition.
    var live by remember(state.window) { mutableStateOf(state.window) }
    // The travel on screen, seated per default window (a new candidate, sentence or expansion) and
    // kept while it holds the state's window: a finished drag never moves it, a playhead set
    // re-seats it only when the new window leaves it (desktop set_word/set_edge/_seat).
    var seated by remember(state.defaultWindow) { mutableStateOf(state.travel) }
    val travel = retainedClipTravel(seated, state)
    val rangeDescription = stringResource(R.string.curation_clip_range_description)
    val setEdgeAtPlayhead: (Boolean) -> Unit = { end ->
        readPlayhead()?.let { playhead ->
            val (moved, movedTravel) = clipEdgeAtPlayhead(live, travel, playhead, end)
            live = moved
            seated = movedTravel
            if (moved != state.window) {
                onWindowChange(CurationClipWindow(moved.startSeconds, moved.endSeconds))
            }
        }
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = containerColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column {
            HorizontalDivider()
            Row(
                modifier =
                    Modifier.fillMaxWidth().padding(
                        horizontal = AnkiMinerTokens.Space.group,
                        vertical = AnkiMinerTokens.Space.line,
                    ),
                horizontalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { setEdgeAtPlayhead(false) },
                    enabled = enabled,
                    modifier = Modifier.minimumInteractiveComponentSize().testTag(startHereTestTag),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_clip_start_here),
                        contentDescription = stringResource(R.string.curation_clip_start_here),
                    )
                }
                RangeSlider(
                    value = live.startSeconds.toFloat()..live.endSeconds.toFloat(),
                    onValueChange = { range ->
                        live =
                            nextClipWindow(
                                live = live,
                                reported =
                                    ClipWindowSeconds(
                                        range.start.toDouble(),
                                        range.endInclusive.toDouble(),
                                    ),
                                travelStart = travel.startSeconds,
                                travelEnd = travel.endSeconds,
                            )
                    },
                    onValueChangeFinished = {
                        // A tap that lands on (or very near) the current thumb position drags
                        // through no net scaled change: RangeSliderState.onDrag then skips
                        // onValueChange, but gestureEndAction still fires onValueChangeFinished
                        // unconditionally right after. Without this guard `live` would still be
                        // the raw seed from state.window - which desktop's own seeding rule
                        // treats as never edited, and which can be legitimately longer than
                        // CurationClipWindow's 30s ceiling (an over-long subtitle line), so
                        // committing it here would throw. Only an actual coerced edit commits.
                        if (live != state.window) {
                            seated = travel
                            onWindowChange(CurationClipWindow(live.startSeconds, live.endSeconds))
                        }
                    },
                    valueRange = travel.startSeconds.toFloat()..travel.endSeconds.toFloat(),
                    enabled = enabled,
                    modifier =
                        Modifier.weight(1f).testTag(sliderTestTag)
                            .semantics { contentDescription = rangeDescription },
                )
                IconButton(
                    onClick = { setEdgeAtPlayhead(true) },
                    enabled = enabled,
                    modifier = Modifier.minimumInteractiveComponentSize().testTag(endHereTestTag),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_clip_end_here),
                        contentDescription = stringResource(R.string.curation_clip_end_here),
                    )
                }
                IconButton(
                    onClick = { if (playing) onStop() else onPlay(live) },
                    enabled = enabled,
                    modifier = Modifier.minimumInteractiveComponentSize().testTag(playTestTag),
                ) {
                    Icon(
                        painter =
                            painterResource(
                                if (playing) R.drawable.ic_stop else R.drawable.ic_play_arrow,
                            ),
                        contentDescription =
                            stringResource(
                                if (playing) {
                                    R.string.curation_clip_stop
                                } else {
                                    R.string.curation_clip_play
                                },
                            ),
                    )
                }
                if (state.overridden) {
                    IconButton(
                        onClick = onReset,
                        enabled = enabled,
                        modifier = Modifier.minimumInteractiveComponentSize().testTag(resetTestTag),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_refresh),
                            contentDescription = stringResource(R.string.curation_clip_reset),
                        )
                    }
                }
            }
            Text(
                text =
                    stringResource(
                        R.string.curation_clip_window,
                        live.startSeconds,
                        live.endSeconds,
                        live.lengthSeconds,
                    ),
                modifier =
                    Modifier.padding(
                        start = AnkiMinerTokens.Space.group,
                        end = AnkiMinerTokens.Space.group,
                        bottom = AnkiMinerTokens.Space.line,
                    ).testTag(readoutTestTag),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
