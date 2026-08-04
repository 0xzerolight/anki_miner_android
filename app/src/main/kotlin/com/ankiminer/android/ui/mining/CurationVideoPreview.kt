package com.ankiminer.android.ui.mining

import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import com.ankiminer.android.R
import com.ankiminer.android.engine.SubtitleCue
import com.ankiminer.android.player.CurationPreviewPlayer
import com.ankiminer.android.player.PreviewFailure
import com.ankiminer.android.player.currentCue
import com.ankiminer.android.ui.theme.AnkiMinerTokens
import kotlinx.coroutines.delay

object CurationPlayerTestTags {
    const val SURFACE = "curation_player_surface"
    const val VIDEO_FRAME = "curation_player_video_frame"
    const val PLAY_PAUSE = "curation_player_play_pause"
    const val COLLAPSE = "curation_player_collapse"
    const val OVERLAY = "curation_player_overlay"
    const val FAILURE_NOTICE = "curation_player_failure_notice"
    const val RETRY = "curation_player_retry"
}

@OptIn(UnstableApi::class)
@Composable
fun CurationVideoPreview(
    player: CurationPreviewPlayer,
    videoUri: Uri,
    cues: List<SubtitleCue>,
    overlayOffsetSeconds: Double,
    collapsed: Boolean,
    onToggleCollapsed: () -> Unit,
    audioOnly: Boolean = false,
    notice: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(player, videoUri) {
        player.bind(videoUri)
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, player) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) player.pause()
            }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val playing by player.isPlaying.collectAsState()
    val positionSeconds by player.positionSeconds.collectAsState()
    LaunchedEffect(playing) {
        if (playing) {
            while (true) {
                player.tick()
                delay(POSITION_TICK_MILLIS)
            }
        } else {
            player.tick()
        }
    }

    Card(modifier = modifier.fillMaxWidth()) {
        if (collapsed) {
            CollapsedPreviewBar(onToggleCollapsed)
        } else {
            Column {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .then(
                                if (audioOnly) {
                                    Modifier.height(AudioSurfaceHeight)
                                } else {
                                    Modifier.aspectRatio(VIDEO_ASPECT_RATIO)
                                },
                            )
                            .background(Color.Black)
                            .testTag(CurationPlayerTestTags.SURFACE),
                ) {
                    if (!audioOnly) {
                        val failure by player.failure.collectAsState()
                        ContentFrame(
                            player = player.media3Player,
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .testTag(CurationPlayerTestTags.VIDEO_FRAME),
                            // media3 covers the surface exactly when no video renders; the
                            // failure message belongs on that cover, not in a separate slot.
                            shutter = {
                                PreviewFailureShutter(
                                    failure = failure,
                                    onRetry = player::retry,
                                )
                            },
                        )
                    }
                    SubtitleOverlay(
                        text =
                            currentCue(
                                cues = cues,
                                positionSeconds = positionSeconds,
                                offsetSeconds = overlayOffsetSeconds,
                            )?.text.orEmpty(),
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
                notice?.invoke()
                PreviewControls(
                    playing = playing,
                    onTogglePlayPause = player::togglePlayPause,
                    onToggleCollapsed = onToggleCollapsed,
                )
            }
        }
    }
}

@Composable
private fun PreviewFailureShutter(
    failure: PreviewFailure?,
    onRetry: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        if (failure != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = failureMessage(failure),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier =
                        Modifier
                            .padding(horizontal = AnkiMinerTokens.Space.content)
                            .testTag(CurationPlayerTestTags.FAILURE_NOTICE),
                )
                TextButton(
                    onClick = onRetry,
                    modifier = Modifier.testTag(CurationPlayerTestTags.RETRY),
                ) {
                    Text(stringResource(R.string.curation_preview_retry))
                }
            }
        }
    }
}

@Composable
private fun failureMessage(failure: PreviewFailure): String =
    when (failure) {
        is PreviewFailure.VideoTrackUnsupported ->
            stringResource(R.string.curation_preview_video_unsupported, failure.codecLabel)
        is PreviewFailure.AudioTrackUnsupported ->
            stringResource(R.string.curation_preview_audio_unsupported, failure.codecLabel)
        is PreviewFailure.PlaybackFailed ->
            stringResource(R.string.curation_preview_playback_failed, failure.errorCodeName)
    }

@Composable
private fun SubtitleOverlay(
    text: String,
    modifier: Modifier = Modifier,
) {
    val overlayDescription = stringResource(R.string.curation_preview_overlay_description)
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = OVERLAY_SCRIM_ALPHA))
                .padding(
                    horizontal = AnkiMinerTokens.Space.content,
                    vertical = AnkiMinerTokens.Space.related,
                )
                .testTag(CurationPlayerTestTags.OVERLAY)
                .semantics { contentDescription = overlayDescription },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun PreviewControls(
    playing: Boolean,
    onTogglePlayPause: () -> Unit,
    onToggleCollapsed: () -> Unit,
) {
    val playPauseDescription =
        stringResource(
            if (playing) {
                R.string.curation_preview_pause_description
            } else {
                R.string.curation_preview_play_description
            },
        )
    val collapseDescription = stringResource(R.string.curation_preview_collapse_description)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = AnkiMinerTokens.Space.related),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onTogglePlayPause,
            modifier =
                Modifier
                    .testTag(CurationPlayerTestTags.PLAY_PAUSE)
                    .semantics { contentDescription = playPauseDescription },
        ) {
            PlayPauseGlyph(playing)
        }
        IconButton(
            onClick = onToggleCollapsed,
            modifier =
                Modifier
                    .testTag(CurationPlayerTestTags.COLLAPSE)
                    .semantics { contentDescription = collapseDescription },
        ) {
            ChevronGlyph(pointsUp = true)
        }
    }
}

@Composable
private fun CollapsedPreviewBar(onToggleCollapsed: () -> Unit) {
    val expandDescription = stringResource(R.string.curation_preview_expand_description)
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = AnkiMinerTokens.Layout.minTouchTarget),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(
            onClick = onToggleCollapsed,
            modifier =
                Modifier
                    .testTag(CurationPlayerTestTags.COLLAPSE)
                    .semantics { contentDescription = expandDescription },
        ) {
            ChevronGlyph(pointsUp = false)
        }
    }
}

@Composable
private fun PlayPauseGlyph(playing: Boolean) {
    val color = LocalContentColor.current
    Canvas(
        modifier =
            Modifier
                .size(PlayerIconSize)
                .clearAndSetSemantics {},
    ) {
        if (playing) {
            val barWidth = size.width * 0.22f
            drawRect(
                color = color,
                topLeft = Offset(size.width * 0.22f, size.height * 0.16f),
                size = Size(barWidth, size.height * 0.68f),
            )
            drawRect(
                color = color,
                topLeft = Offset(size.width * 0.56f, size.height * 0.16f),
                size = Size(barWidth, size.height * 0.68f),
            )
        } else {
            val path =
                Path().apply {
                    moveTo(size.width * 0.28f, size.height * 0.14f)
                    lineTo(size.width * 0.82f, size.height * 0.50f)
                    lineTo(size.width * 0.28f, size.height * 0.86f)
                    close()
                }
            drawPath(path = path, color = color)
        }
    }
}

@Composable
private fun ChevronGlyph(pointsUp: Boolean) {
    val color = LocalContentColor.current
    Canvas(
        modifier =
            Modifier
                .size(PlayerIconSize)
                .clearAndSetSemantics {},
    ) {
        val outsideY = size.height * if (pointsUp) 0.62f else 0.38f
        val centerY = size.height * if (pointsUp) 0.36f else 0.64f
        val strokeWidth = ChevronStrokeWidth.toPx()
        drawLine(
            color = color,
            start = Offset(size.width * 0.22f, outsideY),
            end = Offset(size.width * 0.50f, centerY),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(size.width * 0.50f, centerY),
            end = Offset(size.width * 0.78f, outsideY),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
    }
}

private const val VIDEO_ASPECT_RATIO = 16f / 9f
private const val POSITION_TICK_MILLIS = 100L
private const val OVERLAY_SCRIM_ALPHA = 0.68f
private val AudioSurfaceHeight = 160.dp
private val PlayerIconSize = 24.dp
private val ChevronStrokeWidth = 2.dp
