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
    private val mutableFailure = MutableStateFlow<PreviewFailure?>(null)
    private var boundUri: Uri? = null

    override val isPlaying: StateFlow<Boolean> = mutableIsPlaying.asStateFlow()
    override val positionSeconds: StateFlow<Double> = mutablePositionSeconds.asStateFlow()
    override val failure: StateFlow<PreviewFailure?> = mutableFailure.asStateFlow()

    val boundUris = mutableListOf<Uri>()
    val seekToCalls = mutableListOf<Double>()
    val seekAndPlayCalls = mutableListOf<Double>()
    val events = mutableListOf<String>()
    var togglePlayPauseCount = 0
        private set
    var pauseCount = 0
        private set
    var releaseCount = 0
        private set
    var retryCount = 0
        private set

    /** Failure to surface after a bind, like the real player's onTracksChanged would. */
    var failureOnBind: PreviewFailure? = null

    fun emitFailure(failure: PreviewFailure?) {
        mutableFailure.value = failure
    }

    override fun tick() = Unit

    override fun bind(uri: Uri) {
        if (boundUri == uri) return
        boundUri = uri
        boundUris += uri
        events += "bind:$uri"
        mutableFailure.value = failureOnBind
    }

    override fun seekTo(seconds: Double) {
        seekToCalls += seconds
        events += "seekTo:$seconds"
        mutablePositionSeconds.value = seconds.coerceAtLeast(0.0)
        mutableIsPlaying.value = false
    }

    override fun seekAndPlay(seconds: Double) {
        seekAndPlayCalls += seconds
        events += "seekAndPlay:$seconds"
        mutablePositionSeconds.value = seconds.coerceAtLeast(0.0)
        mutableIsPlaying.value = true
    }

    override fun togglePlayPause() {
        togglePlayPauseCount += 1
        events += "togglePlayPause"
        mutableIsPlaying.value = !mutableIsPlaying.value
    }

    override fun pause() {
        pauseCount += 1
        events += "pause"
        mutableIsPlaying.value = false
    }

    override fun release() {
        releaseCount += 1
        events += "release"
        mutableIsPlaying.value = false
    }

    override fun retry() {
        retryCount += 1
        events += "retry"
        boundUri = null
        mutableFailure.value = null
    }
}
