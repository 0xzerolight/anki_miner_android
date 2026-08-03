package com.ankiminer.android.player

import android.net.Uri
import androidx.media3.common.Player
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeCurationPreviewPlayer : CurationPreviewPlayer {
    override val media3Player: Player? = null

    private val mutableIsPlaying = MutableStateFlow(false)
    private val mutablePositionSeconds = MutableStateFlow(0.0)
    private var boundUri: Uri? = null

    override val isPlaying: StateFlow<Boolean> = mutableIsPlaying.asStateFlow()
    override val positionSeconds: StateFlow<Double> = mutablePositionSeconds.asStateFlow()

    val boundUris = mutableListOf<Uri>()
    val seekToCalls = mutableListOf<Double>()
    val seekAndPlayCalls = mutableListOf<Double>()
    var togglePlayPauseCount = 0
        private set
    var pauseCount = 0
        private set
    var releaseCount = 0
        private set

    override fun tick() = Unit

    override fun bind(uri: Uri) {
        if (boundUri == uri) return
        boundUri = uri
        boundUris += uri
    }

    override fun seekTo(seconds: Double) {
        seekToCalls += seconds
        mutablePositionSeconds.value = seconds.coerceAtLeast(0.0)
        mutableIsPlaying.value = false
    }

    override fun seekAndPlay(seconds: Double) {
        seekAndPlayCalls += seconds
        mutablePositionSeconds.value = seconds.coerceAtLeast(0.0)
        mutableIsPlaying.value = true
    }

    override fun togglePlayPause() {
        togglePlayPauseCount += 1
        mutableIsPlaying.value = !mutableIsPlaying.value
    }

    override fun pause() {
        pauseCount += 1
        mutableIsPlaying.value = false
    }

    override fun release() {
        releaseCount += 1
        mutableIsPlaying.value = false
    }
}
