package com.ankiminer.android.tracks

import com.ankiminer.android.engine.AudioTrackInfo
import com.ankiminer.android.engine.BridgeJsonCodec
import com.ankiminer.android.engine.BridgeMessage
import com.ankiminer.android.engine.PyBridge
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

private const val AUDIO_TRACKS_PROBE_FAILED_CODE = "audio_tracks_probe_failed"

fun interface AudioTrackLookupService {
    suspend fun tracks(videoPath: String): Result<AudioTrackList>
}

data class AudioTrackList(
    val autoAudioIndex: Long?,
    val tracks: List<AudioTrackInfo>,
)

class AudioTrackProbeFailedException :
    IllegalStateException("The file could not be probed for audio tracks")

class BridgeAudioTrackLookupService(
    private val bridge: PyBridge,
    private val executor: Executor,
    private val nativeLibraryDir: String,
) : AudioTrackLookupService {
    override suspend fun tracks(videoPath: String): Result<AudioTrackList> =
        suspendCancellableCoroutine { continuation ->
            executor.execute {
                if (!continuation.isActive) return@execute
                val outcome =
                    runCatching {
                        val raw =
                            bridge.dispatch(
                                BridgeJsonCodec.encodeAudioTracksRequest(videoPath, nativeLibraryDir),
                                null,
                            )
                        val message = BridgeJsonCodec.decode(raw)
                        if (message is BridgeMessage.Error && message.code == AUDIO_TRACKS_PROBE_FAILED_CODE) {
                            throw AudioTrackProbeFailedException()
                        }
                        check(message is BridgeMessage.AudioTracksResult) {
                            "Unexpected reply to media.audiotracks"
                        }
                        check(message.videoPath == videoPath) {
                            "media.audiotracks echoed another videoPath"
                        }
                        AudioTrackList(message.autoAudioIndex, message.tracks)
                    }
                if (continuation.isActive) continuation.resume(outcome)
            }
        }
}
