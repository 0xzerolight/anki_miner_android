package com.ankiminer.android.ui.video

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.ankiminer.android.R
import com.ankiminer.android.engine.AudioTrackInfo
import com.ankiminer.android.ui.theme.AnkiMinerTokens
import com.ankiminer.android.ui.theme.radioActionColors

@Composable
internal fun AudioTrackPickerDialog(
    state: AudioTrackPickerState,
    onSelect: (Long?) -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    val multiTrack = state.tracks.size >= 2
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.audio_tracks_title)) },
        text = {
            when {
                state.tracks.isEmpty() -> Text(stringResource(R.string.audio_tracks_none))
                state.tracks.size == 1 ->
                    Column(verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related)) {
                        Text(stringResource(R.string.audio_tracks_single))
                        Text(trackLabel(state.tracks.first()))
                    }
                else ->
                    LazyColumn(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .testTag(VideoMiningTestTags.AUDIO_TRACK_PICKER)
                                .selectableGroup(),
                        verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.line),
                    ) {
                        item(key = "auto") {
                            AudioTrackPickerRow(
                                label = autoLabel(state),
                                selected = state.selectedAudioIndex == null,
                                tag = VideoMiningTestTags.audioTrackRow(null),
                                onSelect = { onSelect(null) },
                            )
                        }
                        state.tracks.forEach { track ->
                            item(key = track.audioIndex) {
                                AudioTrackPickerRow(
                                    label = trackLabel(track),
                                    selected = state.selectedAudioIndex == track.audioIndex,
                                    tag = VideoMiningTestTags.audioTrackRow(track.audioIndex),
                                    onSelect = { onSelect(track.audioIndex) },
                                )
                            }
                        }
                    }
            }
        },
        confirmButton = {
            if (multiTrack) {
                TextButton(
                    onClick = onApply,
                    modifier = Modifier.testTag(VideoMiningTestTags.AUDIO_TRACK_PICKER_APPLY),
                ) {
                    Text(stringResource(R.string.audio_tracks_apply))
                }
            }
        },
        dismissButton = {
            if (multiTrack) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag(VideoMiningTestTags.AUDIO_TRACK_PICKER_CANCEL),
                ) {
                    Text(stringResource(R.string.cancel))
                }
            } else {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag(VideoMiningTestTags.AUDIO_TRACK_PICKER_CLOSE),
                ) {
                    Text(stringResource(R.string.audio_tracks_close))
                }
            }
        },
    )
}

@Composable
private fun AudioTrackPickerRow(
    label: String,
    selected: Boolean,
    tag: String,
    onSelect: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = AnkiMinerTokens.Layout.minTouchTarget)
                .selectable(
                    selected = selected,
                    role = Role.RadioButton,
                    onClick = onSelect,
                ).testTag(tag)
                .padding(
                    horizontal = AnkiMinerTokens.Space.group,
                    vertical = AnkiMinerTokens.Space.line,
                ),
        horizontalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            colors = radioActionColors(),
        )
        Text(
            text = label,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun autoLabel(state: AudioTrackPickerState): String {
    val autoTrack = state.tracks.firstOrNull { it.audioIndex == state.autoAudioIndex }
    return if (autoTrack != null) {
        stringResource(
            R.string.audio_tracks_auto_detected,
            autoTrack.audioIndex + 1,
            autoTrack.languageTag ?: "und",
        )
    } else {
        stringResource(R.string.audio_tracks_auto_none)
    }
}

@Composable
private fun trackLabel(track: AudioTrackInfo): String {
    val base =
        stringResource(
            R.string.audio_tracks_track_label,
            track.audioIndex + 1,
            track.languageTag ?: "und",
            (track.codec ?: "?").uppercase(),
        )
    val withChannels = channelWord(track.channels)?.let { "$base $it" } ?: base
    return if (!track.title.isNullOrBlank()) "$withChannels (${track.title})" else withChannels
}

/** Desktop parity: `audio_tracks_dialog._format_channels`. "5.1"/"7.1" are numeric literals, not English. */
@Composable
private fun channelWord(channels: Long?): String? =
    when (channels) {
        null -> null
        1L -> stringResource(R.string.audio_tracks_channels_mono)
        2L -> stringResource(R.string.audio_tracks_channels_stereo)
        6L -> "5.1"
        8L -> "7.1"
        else -> stringResource(R.string.audio_tracks_channels_n, channels)
    }
