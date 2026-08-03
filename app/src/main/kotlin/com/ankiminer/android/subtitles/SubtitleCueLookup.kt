package com.ankiminer.android.subtitles

import com.ankiminer.android.engine.BridgeJsonCodec
import com.ankiminer.android.engine.BridgeMessage
import com.ankiminer.android.engine.PyBridge
import com.ankiminer.android.engine.SubtitleCue
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

fun interface SubtitleCueLookupService {
    suspend fun cues(
        runId: String?,
        subtitlePath: String,
    ): Result<List<SubtitleCue>>
}

class BridgeSubtitleCueLookupService(
    private val bridge: PyBridge,
    private val executor: Executor,
) : SubtitleCueLookupService {
    override suspend fun cues(
        runId: String?,
        subtitlePath: String,
    ): Result<List<SubtitleCue>> =
        suspendCancellableCoroutine { continuation ->
            executor.execute {
                if (!continuation.isActive) return@execute
                val outcome =
                    runCatching {
                        val raw =
                            bridge.dispatch(
                                BridgeJsonCodec.encodeSubtitleCuesRequest(
                                    runId,
                                    subtitlePath,
                                ),
                                null,
                            )
                        val message =
                            BridgeJsonCodec.decode(
                                raw,
                                expectedRunId = runId,
                            )
                        check(message is BridgeMessage.SubtitleCuesResult) {
                            "Unexpected reply to subtitle.cues"
                        }
                        check(message.runId == runId) {
                            "subtitle.cues echoed another run"
                        }
                        check(message.subtitlePath == subtitlePath) {
                            "subtitle.cues echoed another subtitlePath"
                        }
                        message.cues
                    }
                if (continuation.isActive) continuation.resume(outcome)
            }
        }
}
