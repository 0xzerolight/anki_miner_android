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
 * The per-word audio trim row: a continuous range slider over the clip's travel, a play/stop
 * button that previews exactly the in-flight window, and an explicit reset.
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
    onWindowChange: (CurationClipWindow) -> Unit,
    onReset: () -> Unit,
    onPlay: (ClipWindowSeconds) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // remember(state.window) re-seeds `live` whenever the source window changes (a new candidate,
    // a new sentence, an expansion, a reset) and only then - mid-drag the slider owns its own
    // value, which is what keeps it from fighting the recomposition.
    var live by remember(state.window) { mutableStateOf(state.window) }
    val rangeDescription = stringResource(R.string.curation_clip_range_description)
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
                RangeSlider(
                    value = live.startSeconds.toFloat()..live.endSeconds.toFloat(),
                    onValueChange = { range ->
                        // Which handle moved is read against the window this composable last
                        // wrote, not against the slider: the slider has already moved itself by
                        // the time it reports.
                        val movedStart = range.start.toDouble() != live.startSeconds
                        live =
                            coerceClipWindow(
                                start = range.start.toDouble(),
                                end = range.endInclusive.toDouble(),
                                travelStart = state.travelStartSeconds,
                                travelEnd = state.travelEndSeconds,
                                movedStart = movedStart,
                            )
                    },
                    onValueChangeFinished = {
                        onWindowChange(CurationClipWindow(live.startSeconds, live.endSeconds))
                    },
                    valueRange = state.travelStartSeconds.toFloat()..state.travelEndSeconds.toFloat(),
                    enabled = enabled,
                    modifier =
                        Modifier.weight(1f).testTag(sliderTestTag)
                            .semantics { contentDescription = rangeDescription },
                )
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
                IconButton(
                    onClick = onReset,
                    enabled = enabled && state.overridden,
                    modifier = Modifier.minimumInteractiveComponentSize().testTag(resetTestTag),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_refresh),
                        contentDescription = stringResource(R.string.curation_clip_reset),
                    )
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
