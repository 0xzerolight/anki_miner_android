package com.ankiminer.android.player

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import com.ankiminer.android.diagnostics.log.AppLog
import com.ankiminer.android.diagnostics.log.LogComponent
import com.ankiminer.android.engine.SubtitleCue
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A preview the player cannot render, surfaced instead of a silent black frame. */
sealed interface PreviewFailure {
    data class VideoTrackUnsupported(val codecLabel: String) : PreviewFailure

    data class AudioTrackUnsupported(val codecLabel: String) : PreviewFailure

    data class PlaybackFailed(val errorCodeName: String) : PreviewFailure
}

interface CurationPreviewPlayer {
    val media3Player: Player?
    val isPlaying: StateFlow<Boolean>
    val positionSeconds: StateFlow<Double>
    val failure: StateFlow<PreviewFailure?>

    fun tick()

    fun bind(uri: Uri)

    fun seekTo(seconds: Double)

    fun seekAndPlay(seconds: Double)

    fun togglePlayPause()

    fun pause()

    fun release()

    fun retry()
}

@OptIn(UnstableApi::class)
class ExoCurationPreviewPlayer(
    context: Context,
) : CurationPreviewPlayer {
    private val exo =
        ExoPlayer.Builder(
            context,
            // Hardware decoders stay first; nextlib's FFmpeg software renderers cover what the
            // device's MediaCodec cannot (Hi10P, 10-bit HEVC, VP9 profiles, DTS/TrueHD, ...).
            NextRenderersFactory(context)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                .setEnableDecoderFallback(true),
        )
            .setSeekParameters(SeekParameters.EXACT)
            .build()

    override val media3Player: Player
        get() = exo

    private var boundUri: Uri? = null
    private val mutableIsPlaying = MutableStateFlow(false)
    private val mutablePositionSeconds = MutableStateFlow(0.0)
    private val mutableFailure = MutableStateFlow<PreviewFailure?>(null)

    override val isPlaying: StateFlow<Boolean> = mutableIsPlaying.asStateFlow()
    override val positionSeconds: StateFlow<Double> = mutablePositionSeconds.asStateFlow()
    override val failure: StateFlow<PreviewFailure?> = mutableFailure.asStateFlow()

    init {
        exo.addListener(
            object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    mutableIsPlaying.value = isPlaying
                }

                override fun onTracksChanged(tracks: Tracks) {
                    val failure = previewFailureFor(tracks)
                    if (failure != null) {
                        val format = firstFormatOfType(tracks, C.TRACK_TYPE_VIDEO)
                            ?: firstFormatOfType(tracks, C.TRACK_TYPE_AUDIO)
                        AppLog.i(
                            LogComponent.MEDIA,
                            "curation_preview_track_unsupported",
                            "failure" to failure,
                            "mime" to format?.sampleMimeType,
                            "codecs" to format?.codecs,
                            "width" to format?.width,
                            "height" to format?.height,
                            "sdk" to Build.VERSION.SDK_INT,
                        )
                    }
                    mutableFailure.value = failure
                }

                override fun onPlayerError(error: PlaybackException) {
                    AppLog.w(
                        LogComponent.MEDIA,
                        "curation_preview_playback_failed",
                        error,
                        "code" to error.errorCodeName,
                        "sdk" to Build.VERSION.SDK_INT,
                    )
                    mutableFailure.value = previewFailureFor(error)
                }
            },
        )
    }

    override fun tick() {
        mutablePositionSeconds.value = exo.currentPosition / MILLIS_PER_SECOND
    }

    override fun bind(uri: Uri) {
        if (boundUri == uri) return
        boundUri = uri
        mutableFailure.value = null
        exo.setMediaItem(MediaItem.fromUri(uri))
        exo.prepare()
        exo.playWhenReady = false
    }

    override fun seekTo(seconds: Double) {
        exo.playWhenReady = false
        exo.seekTo(millis(seconds))
        tick()
    }

    override fun seekAndPlay(seconds: Double) {
        exo.seekTo(millis(seconds))
        exo.playWhenReady = true
        tick()
    }

    override fun togglePlayPause() {
        // isPlaying stays false while buffering, so use playWhenReady to let a second tap cancel
        // pending playback.
        exo.playWhenReady = !exo.playWhenReady
    }

    override fun pause() {
        exo.playWhenReady = false
    }

    override fun release() {
        exo.release()
    }

    /**
     * Recovers from a [PreviewFailure]. Decoder-init failures are often transient (another app
     * holding the hardware codec), and a `PlaybackException` leaves the player IDLE where only
     * `prepare()` resumes it. Clearing [boundUri] also lets a later [bind] of the same URI
     * re-prepare instead of early-returning against a dead player.
     */
    override fun retry() {
        boundUri = null
        mutableFailure.value = null
        exo.prepare()
    }

    private fun millis(seconds: Double): Long =
        (seconds * MILLIS_PER_SECOND).toLong().coerceAtLeast(0L)

    private companion object {
        const val MILLIS_PER_SECOND = 1_000.0
    }
}

/** First format of [trackType], for diagnostics; the failing group when playback is degraded. */
private fun firstFormatOfType(
    tracks: Tracks,
    trackType: Int,
): Format? =
    tracks.groups.firstOrNull { it.type == trackType }?.getTrackFormat(0)

/**
 * Mode-2 detection (`FORMAT_UNSUPPORTED_SUBTYPE`): the track selector silently deselects a track
 * no renderer handles — the player reaches `STATE_READY` with a black picture and no exception.
 * `isTypeSupportedOrEmpty` means "supported, or absent", so the audio-only lane never trips it.
 */
@OptIn(UnstableApi::class)
fun previewFailureFor(tracks: Tracks): PreviewFailure? {
    if (!tracks.isTypeSupportedOrEmpty(C.TRACK_TYPE_VIDEO)) {
        return PreviewFailure.VideoTrackUnsupported(
            codecLabel(firstFormatOfType(tracks, C.TRACK_TYPE_VIDEO)),
        )
    }
    if (!tracks.isTypeSupportedOrEmpty(C.TRACK_TYPE_AUDIO)) {
        return PreviewFailure.AudioTrackUnsupported(
            codecLabel(firstFormatOfType(tracks, C.TRACK_TYPE_AUDIO)),
        )
    }
    return null
}

/** Modes 1 and 3: container rejected at parse, or a selected track's decoder failed. */
fun previewFailureFor(error: PlaybackException): PreviewFailure =
    PreviewFailure.PlaybackFailed(error.errorCodeName)

private fun codecLabel(format: Format?): String =
    format?.codecs ?: format?.sampleMimeType ?: "unknown"

/**
 * Engine-parity shifted window (`subtitle_parser.py:1030-1031`). Start clamps to zero; end clamps
 * to the shifted start. Keep workbench seeks aligned by using this range's start.
 */
fun shiftedWindow(
    cue: SubtitleCue,
    offsetSeconds: Double,
): ClosedFloatingPointRange<Double> {
    val start = maxOf(0.0, cue.startSeconds + offsetSeconds)
    val end = maxOf(start, cue.endSeconds + offsetSeconds)
    return start..end
}

/** Desktop parity: overlapping cues resolve by the first match in source order. */
fun currentCue(
    cues: List<SubtitleCue>,
    positionSeconds: Double,
    offsetSeconds: Double,
): SubtitleCue? =
    cues.firstOrNull { cue -> positionSeconds in shiftedWindow(cue, offsetSeconds) }
