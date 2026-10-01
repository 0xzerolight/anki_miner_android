package com.ankiminer.android.ui.mining

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ankiminer.android.R
import com.ankiminer.android.mining.CurationPageContext
import com.ankiminer.android.player.CurationPreviewPlayer
import com.ankiminer.android.reading.CurationPageImageDecoder
import com.ankiminer.android.ui.theme.AnkiMinerTokens
import com.ankiminer.android.ui.video.CurationPlayerUiState
import kotlinx.coroutines.delay
import java.io.File

internal const val CURATION_SEEK_DEBOUNCE_MS = 150L

/**
 * Binding, pause-on-stop and the focus seek for the run's one preview player. They belong to the
 * curation phase, not to the inline frame, which comes and goes with its row and with scrolling.
 */
@Composable
internal fun CurationPlayerEffects(
    player: CurationPreviewPlayer,
    videoPath: String,
    audioTrackOverride: Long?,
    focusedCandidateId: String?,
    selectedSentenceId: String?,
    seekTarget: Double?,
) {
    val videoUri = remember(videoPath) { Uri.fromFile(File(videoPath)) }
    LaunchedEffect(player, videoUri, audioTrackOverride) {
        player.bind(videoUri, audioTrackOverride)
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
    LaunchedEffect(focusedCandidateId, selectedSentenceId, seekTarget) {
        delay(CURATION_SEEK_DEBOUNCE_MS)
        player.seekTo(seekTarget ?: return@LaunchedEffect)
    }
}

/** The focused word's frame (Video) or play bar (Audio), inline in its expanded row; nothing is pinned. */
@Composable
internal fun CurationInlinePreview(
    containerColor: Color,
    player: CurationPreviewPlayer,
    playerState: CurationPlayerUiState,
    maxSurfaceHeight: Dp,
    cuesUnavailableTestTag: String,
    modifier: Modifier = Modifier,
) {
    val videoUri = remember(playerState.videoPath) { Uri.fromFile(File(playerState.videoPath)) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = containerColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column {
            HorizontalDivider()
            CurationVideoPreview(
                player = player,
                videoUri = videoUri,
                cues = playerState.cues.takeUnless { playerState.cuesUnavailable }.orEmpty(),
                overlayOffsetSeconds = 0.0,
                audioOnly = playerState.audioOnly,
                audioTrackOverride = playerState.audioTrackOverride,
                maxSurfaceHeight = maxSurfaceHeight,
                notice =
                    if (playerState.cuesUnavailable) {
                        { CuesUnavailableNotice(cuesUnavailableTestTag) }
                    } else {
                        null
                    },
                modifier =
                    Modifier.padding(
                        horizontal = AnkiMinerTokens.Space.group,
                        vertical = AnkiMinerTokens.Space.line,
                    ),
            )
        }
    }
}

@Composable
private fun CuesUnavailableNotice(testTag: String) {
    Text(
        text = stringResource(R.string.curation_preview_cues_unavailable),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = AnkiMinerTokens.Space.related,
                    vertical = AnkiMinerTokens.Space.micro,
                ).testTag(testTag),
        color = MaterialTheme.colorScheme.error,
        maxLines = 1,
        style = MaterialTheme.typography.bodySmall,
    )
}

/** The manga page the focused sentence came from, inline in its expanded row. */
@Composable
internal fun CurationInlinePageImage(
    containerColor: Color,
    archivePath: String,
    pageContext: CurationPageContext,
    decoder: CurationPageImageDecoder,
    maxContentHeight: Dp,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = containerColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column {
            HorizontalDivider()
            CurationPageImagePane(
                archivePath = archivePath,
                pageContext = pageContext,
                decoder = decoder,
                maxContentHeight = maxContentHeight,
                modifier =
                    Modifier.padding(
                        horizontal = AnkiMinerTokens.Space.group,
                        vertical = AnkiMinerTokens.Space.line,
                    ),
            )
        }
    }
}
