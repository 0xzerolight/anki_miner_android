package com.ankiminer.android.player

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlayerMessage
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

    fun bind(uri: Uri, audioTrackOverride: Long? = null)

    fun seekTo(seconds: Double)

    fun seekAndPlay(seconds: Double)

    /** Plays exactly [startSeconds, endSeconds], stopping itself at the out point. */
    fun playRange(startSeconds: Double, endSeconds: Double)

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
    private var audioTrackOverride: Long? = null
    private var pendingRangeStop: PlayerMessage? = null
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
                    selectPreferredAudio(tracks)
                    val failure = previewFailureFor(tracks, audioTrackOverride)
                    if (failure != null) {
                        val format = firstFormatOfType(tracks, C.TRACK_TYPE_VIDEO)
                            ?: firstFormatOfType(tracks, C.TRACK_TYPE_AUDIO)
                        AppLog.i(
                            LogComponent.MEDIA,
                            "curation_preview_track_unsupported",
                            "outcome" to "fail",
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
                        "outcome" to "fail",
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

    override fun bind(uri: Uri, audioTrackOverride: Long?) {
        cancelRangeStop()
        if (boundUri == uri && this.audioTrackOverride == audioTrackOverride) return
        boundUri = uri
        this.audioTrackOverride = audioTrackOverride
        mutableFailure.value = null
        // Overrides are keyed by the previous item's TrackGroup; drop them so the new item
        // starts from defaults and picks its own track in onTracksChanged.
        exo.trackSelectionParameters =
            exo.trackSelectionParameters
                .buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                .build()
        exo.setMediaItem(MediaItem.fromUri(uri))
        exo.prepare()
        exo.playWhenReady = false
    }

    /**
     * Forces the preview onto the same audio track the engine will mine from.
     *
     * Left to itself the `DefaultTrackSelector` decides a dual-audio file on
     * `localeLanguageScore` — derived from `Util.getSystemLanguageCodes()` — because every
     * higher-ranked criterion (preferred language, default-selection flag, role flags) ties
     * between two equivalent tracks. An English phone therefore previewed the English dub while
     * the mined clip came from the Japanese track. An explicit override outranks all scoring.
     *
     * Re-applying is idempotent: the second `onTracksChanged` sees the override already in the
     * parameters and returns, which also stops the loop when the chosen track is one no renderer
     * can decode (there `isTrackSelected` would stay false forever).
     */
    private fun selectPreferredAudio(tracks: Tracks) {
        val desired = preferredAudioGroup(tracks, audioTrackOverride) ?: return
        val override = TrackSelectionOverride(desired.mediaTrackGroup, 0)
        if (exo.trackSelectionParameters.overrides[desired.mediaTrackGroup] == override) return

        AppLog.i(
            LogComponent.MEDIA,
            "curation_preview_audio_track_selected",
            "outcome" to "ok",
            "override" to audioTrackOverride,
            "language" to desired.getTrackFormat(0).language,
            "audioGroups" to tracks.groups.count { it.type == C.TRACK_TYPE_AUDIO },
        )
        exo.trackSelectionParameters =
            exo.trackSelectionParameters
                .buildUpon()
                .setOverrideForType(override)
                .build()
    }

    override fun seekTo(seconds: Double) {
        cancelRangeStop()
        exo.playWhenReady = false
        exo.seekTo(millis(seconds))
        tick()
    }

    override fun seekAndPlay(seconds: Double) {
        cancelRangeStop()
        exo.seekTo(millis(seconds))
        exo.playWhenReady = true
        tick()
    }

    /**
     * Plays just the clip window, deliberately separate from the scene transport below the video:
     * that one plays the scene, this one plays the card's audio.
     *
     * The stop rides a [PlayerMessage] rather than the composable's 100 ms position tick, so it
     * fires on the playback clock and does not depend on the preview pane still being composed.
     */
    override fun playRange(startSeconds: Double, endSeconds: Double) {
        cancelRangeStop()
        exo.seekTo(millis(startSeconds))
        pendingRangeStop =
            exo.createMessage { _, _ -> exo.playWhenReady = false }
                .setLooper(Looper.getMainLooper())
                .setPosition(millis(endSeconds))
                .setDeleteAfterDelivery(true)
                .send()
        exo.playWhenReady = true
        tick()
    }

    private fun cancelRangeStop() {
        pendingRangeStop?.cancel()
        pendingRangeStop = null
    }

    override fun togglePlayPause() {
        cancelRangeStop()
        // isPlaying stays false while buffering, so use playWhenReady to let a second tap cancel
        // pending playback.
        exo.playWhenReady = !exo.playWhenReady
    }

    override fun pause() {
        cancelRangeStop()
        exo.playWhenReady = false
    }

    override fun release() {
        cancelRangeStop()
        exo.release()
    }

    /**
     * Recovers from a [PreviewFailure]. Decoder-init failures are often transient (another app
     * holding the hardware codec), and a `PlaybackException` leaves the player IDLE where only
     * `prepare()` resumes it. Unsupported tracks leave the player READY, so stop them first to
     * force a real re-prepare while their honest failure stays visible.
     */
    override fun retry() {
        cancelRangeStop()
        when (mutableFailure.value) {
            is PreviewFailure.PlaybackFailed -> {
                boundUri = null
                mutableFailure.value = null
                exo.prepare()
            }
            is PreviewFailure.VideoTrackUnsupported,
            is PreviewFailure.AudioTrackUnsupported,
            -> {
                exo.stop()
                exo.prepare()
            }
            null -> Unit
        }
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
 * Video stays type-level so absent video is valid for audio-only media. Audio checks the exact
 * engine-preferred group, so a supported dub cannot mask an unsupported Japanese track.
 */
@OptIn(UnstableApi::class)
fun previewFailureFor(tracks: Tracks, audioTrackOverride: Long? = null): PreviewFailure? {
    if (!tracks.isTypeSupportedOrEmpty(C.TRACK_TYPE_VIDEO)) {
        return PreviewFailure.VideoTrackUnsupported(
            codecLabel(firstFormatOfType(tracks, C.TRACK_TYPE_VIDEO)),
        )
    }
    val preferredAudio = preferredAudioGroup(tracks, audioTrackOverride)
    if (preferredAudio != null && !preferredAudio.isTrackSupported(0)) {
        return PreviewFailure.AudioTrackUnsupported(
            codecLabel(preferredAudio.getTrackFormat(0)),
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
