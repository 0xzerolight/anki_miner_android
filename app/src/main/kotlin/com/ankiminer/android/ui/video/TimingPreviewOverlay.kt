package com.ankiminer.android.ui.video

import android.content.Context
import android.net.Uri
import android.system.Os
import android.system.OsConstants
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ankiminer.android.R
import com.ankiminer.android.data.settings.AppSettingsDraftParser
import com.ankiminer.android.player.CurationPreviewPlayer
import com.ankiminer.android.player.ExoCurationPreviewPlayer
import com.ankiminer.android.player.shiftedWindow
import com.ankiminer.android.ui.mining.CurationVideoPreview
import com.ankiminer.android.ui.mining.TimingPreviewState
import com.ankiminer.android.ui.settings.NumericField
import com.ankiminer.android.ui.theme.AnkiMinerTokens
import com.ankiminer.android.ui.theme.forwardButtonColors
import com.ankiminer.android.ui.theme.outlinedActionButtonColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun TimingPreviewOverlay(
    state: TimingPreviewState,
    videoUri: Uri,
    onSelectCue: (Int) -> Unit,
    onNudge: (Double) -> Unit,
    onSetWorking: (Double) -> Unit,
    onToggleUnshifted: () -> Unit,
    onApply: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    playerFactory: (Context) -> CurationPreviewPlayer = { ExoCurationPreviewPlayer(it) },
    seekabilityProbe: suspend (Context, Uri) -> Boolean = ::isSeekableVideoSource,
) {
    val context = LocalContext.current
    val player = remember { playerFactory(context) }
    var seekable by remember(videoUri) { mutableStateOf<Boolean?>(null) }
    var offsetDraft by
        remember(state.initialOffset) {
            mutableStateOf(editableOffset(state.workingOffset))
        }
    var collapsed by remember { mutableStateOf(false) }
    val title = stringResource(R.string.timing_preview_title)

    DisposableEffect(player) {
        onDispose { player.release() }
    }
    LaunchedEffect(videoUri) {
        seekable = seekabilityProbe(context, videoUri)
    }
    LaunchedEffect(state.workingOffset) {
        val parsed = parsedOffset(offsetDraft)
        if (parsed == null || parsed != state.workingOffset) {
            offsetDraft = editableOffset(state.workingOffset)
        }
    }
    BackHandler(onBack = onCancel)

    Surface(
        modifier =
            modifier
                .fillMaxSize()
                .testTag(VideoMiningTestTags.TIMING_PREVIEW)
                .semantics { paneTitle = title },
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(AnkiMinerTokens.Space.content),
            verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related),
        ) {
            Text(
                text = title,
                modifier =
                    Modifier
                        .semantics { heading() }
                        .testTag(VideoMiningTestTags.TIMING_PREVIEW_TITLE),
                style = MaterialTheme.typography.headlineSmall,
            )
            TimingPreviewVideo(
                seekable = seekable,
                player = player,
                videoUri = videoUri,
                state = state,
                collapsed = collapsed,
                onToggleCollapsed = { collapsed = !collapsed },
            )
            LazyColumn(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .testTag(VideoMiningTestTags.TIMING_PREVIEW_CONTENT),
                verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related),
            ) {
                item(key = "readout") {
                    Text(
                        text =
                            if (state.previewingUnshifted) {
                                stringResource(R.string.timing_preview_readout_unshifted)
                            } else {
                                stringResource(
                                    R.string.timing_preview_readout_offset,
                                    state.previewOffset,
                                )
                            },
                        modifier =
                            Modifier.testTag(VideoMiningTestTags.TIMING_PREVIEW_READOUT),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                item(key = "nudges") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement =
                            Arrangement.spacedBy(AnkiMinerTokens.Space.related),
                    ) {
                        NudgeButton(
                            label = stringResource(R.string.timing_preview_nudge_earlier),
                            testTag = VideoMiningTestTags.TIMING_PREVIEW_NUDGE_EARLIER,
                            modifier = Modifier.weight(1f),
                        ) {
                            val updated = state.nudge(-TimingPreviewState.NUDGE_SECONDS)
                            offsetDraft = editableOffset(updated.workingOffset)
                            onNudge(-TimingPreviewState.NUDGE_SECONDS)
                            seekSelectedCue(player, updated, seekable == true)
                        }
                        NudgeButton(
                            label = stringResource(R.string.timing_preview_nudge_later),
                            testTag = VideoMiningTestTags.TIMING_PREVIEW_NUDGE_LATER,
                            modifier = Modifier.weight(1f),
                        ) {
                            val updated = state.nudge(TimingPreviewState.NUDGE_SECONDS)
                            offsetDraft = editableOffset(updated.workingOffset)
                            onNudge(TimingPreviewState.NUDGE_SECONDS)
                            seekSelectedCue(player, updated, seekable == true)
                        }
                    }
                }
                item(key = "toggle") {
                    OutlinedButton(
                        onClick = {
                            val updated = state.toggleUnshifted()
                            onToggleUnshifted()
                            seekSelectedCue(player, updated, seekable == true)
                        },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag(VideoMiningTestTags.TIMING_PREVIEW_TOGGLE),
                        colors = outlinedActionButtonColors(),
                    ) {
                        Text(stringResource(R.string.timing_preview_toggle_unshifted))
                    }
                }
                item(key = "offset") {
                    NumericField(
                        value = offsetDraft,
                        onChange = { value ->
                            offsetDraft = value
                            parsedOffset(value)?.let(onSetWorking)
                        },
                        label = stringResource(R.string.timing_preview_offset_label),
                        allowNegative = true,
                        error =
                            stringResource(R.string.b3_validation_numeric_incomplete)
                                .takeIf { parsedOffset(offsetDraft) == null },
                        modifier =
                            Modifier.testTag(
                                VideoMiningTestTags.TIMING_PREVIEW_OFFSET_FIELD,
                            ),
                    )
                }
                itemsIndexed(
                    items = state.cues,
                    key = { index, _ -> index },
                ) { index, cue ->
                    OutlinedButton(
                        onClick = {
                            onSelectCue(index)
                            if (seekable == true) {
                                player.seekAndPlay(shiftedWindow(cue, state.previewOffset).start)
                            }
                        },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .testTag(VideoMiningTestTags.timingPreviewCue(index)),
                        colors = outlinedActionButtonColors(),
                    ) {
                        Text(cue.text)
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related),
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier =
                        Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag(VideoMiningTestTags.TIMING_PREVIEW_CANCEL),
                    colors = outlinedActionButtonColors(),
                ) {
                    Text(stringResource(R.string.timing_preview_cancel))
                }
                Button(
                    onClick = onApply,
                    enabled = parsedOffset(offsetDraft) != null,
                    modifier =
                        Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag(VideoMiningTestTags.TIMING_PREVIEW_APPLY),
                    colors = forwardButtonColors(),
                ) {
                    Text(stringResource(R.string.timing_preview_apply))
                }
            }
        }
    }
}

@Composable
private fun TimingPreviewVideo(
    seekable: Boolean?,
    player: CurationPreviewPlayer,
    videoUri: Uri,
    state: TimingPreviewState,
    collapsed: Boolean,
    onToggleCollapsed: () -> Unit,
) {
    when (seekable) {
        null ->
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        true ->
            CurationVideoPreview(
                player = player,
                videoUri = videoUri,
                cues = state.cues,
                overlayOffsetSeconds = state.previewOffset,
                collapsed = collapsed,
                onToggleCollapsed = onToggleCollapsed,
            )
        false ->
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .testTag(VideoMiningTestTags.TIMING_PREVIEW_UNAVAILABLE),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.timing_preview_unavailable),
                    modifier = Modifier.padding(AnkiMinerTokens.Space.content),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
    }
}

@Composable
private fun NudgeButton(
    label: String,
    testTag: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier =
            modifier
                .heightIn(min = 48.dp)
                .testTag(testTag),
        colors = outlinedActionButtonColors(),
    ) {
        Text(label)
    }
}

private fun seekSelectedCue(
    player: CurationPreviewPlayer,
    state: TimingPreviewState,
    seekable: Boolean,
) {
    if (!seekable) return
    val cue = state.selectedCueIndex?.let(state.cues::get) ?: return
    player.seekAndPlay(shiftedWindow(cue, state.previewOffset).start)
}

private fun parsedOffset(value: String): Double? =
    if (value.isNotEmpty() && AppSettingsDraftParser.isOptionalDouble(value)) {
        AppSettingsDraftParser.optionalDouble(value)
    } else {
        null
    }

private fun editableOffset(value: Double): String = value.toString()

private suspend fun isSeekableVideoSource(
    context: Context,
    uri: Uri,
): Boolean =
    withContext(Dispatchers.IO) {
        try {
            val descriptor =
                context.contentResolver.openFileDescriptor(uri, "r")
                    ?: return@withContext false
            descriptor.use {
                Os.lseek(it.fileDescriptor, 1L, OsConstants.SEEK_SET)
                Os.lseek(it.fileDescriptor, 0L, OsConstants.SEEK_SET)
            }
            true
        } catch (_: Exception) {
            false
        }
    }
