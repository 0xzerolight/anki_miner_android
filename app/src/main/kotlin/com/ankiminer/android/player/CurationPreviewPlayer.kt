package com.ankiminer.android.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import com.ankiminer.android.engine.SubtitleCue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface CurationPreviewPlayer {
    val media3Player: Player?
    val isPlaying: StateFlow<Boolean>
    val positionSeconds: StateFlow<Double>

    fun tick()

    fun bind(uri: Uri)

    fun seekTo(seconds: Double)

    fun seekAndPlay(seconds: Double)

    fun togglePlayPause()

    fun pause()

    fun release()
}

@OptIn(UnstableApi::class)
class ExoCurationPreviewPlayer(
    context: Context,
) : CurationPreviewPlayer {
    private val exo =
        ExoPlayer.Builder(context)
            .setSeekParameters(SeekParameters.EXACT)
            .build()

    override val media3Player: Player
        get() = exo

    private var boundUri: Uri? = null
    private val mutableIsPlaying = MutableStateFlow(false)
    private val mutablePositionSeconds = MutableStateFlow(0.0)

    override val isPlaying: StateFlow<Boolean> = mutableIsPlaying.asStateFlow()
    override val positionSeconds: StateFlow<Double> = mutablePositionSeconds.asStateFlow()

    init {
        exo.addListener(
            object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    mutableIsPlaying.value = isPlaying
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

    private fun millis(seconds: Double): Long =
        (seconds * MILLIS_PER_SECOND).toLong().coerceAtLeast(0L)

    private companion object {
        const val MILLIS_PER_SECOND = 1_000.0
    }
}

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
