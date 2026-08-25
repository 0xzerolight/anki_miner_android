package com.ankiminer.android.tracks

import com.ankiminer.android.engine.AudioTrackInfo
import com.ankiminer.android.engine.PyBridge
import java.util.concurrent.Executor
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val VIDEO_PATH = "/cache/video.mp4"
private const val NATIVE_LIBRARY_DIR = "/data/app/native"

class AudioTrackLookupTest {
    private val direct = Executor { it.run() }

    private fun result(
        videoPath: String = VIDEO_PATH,
        autoAudioIndex: String = "1",
    ): String =
        """{"schemaVersion":1,"type":"media.audiotracks.result","payload":{"videoPath":"$videoPath","autoAudioIndex":$autoAudioIndex,"tracks":[{"audioIndex":0,"globalIndex":1,"languageTag":"jpn","title":"Japanese","codec":"aac","channels":2,"isDefault":true}]}}"""

    @Test
    fun `returns decoded audio track list`() =
        runTest {
            val service = BridgeAudioTrackLookupService(PyBridge { _, _ -> result() }, direct, NATIVE_LIBRARY_DIR)
            val tracks = service.tracks(VIDEO_PATH).getOrThrow()
            assertEquals(
                AudioTrackList(
                    autoAudioIndex = 1,
                    tracks =
                        listOf(
                            AudioTrackInfo(
                                audioIndex = 0,
                                globalIndex = 1,
                                languageTag = "jpn",
                                title = "Japanese",
                                codec = "aac",
                                channels = 2,
                                isDefault = true,
                            ),
                        ),
                ),
                tracks,
            )
        }

    @Test
    fun `a reply echoing another video path is rejected`() =
        runTest {
            val bridge = PyBridge { _, _ -> result(videoPath = "/cache/other.mp4") }
            assertTrue(
                BridgeAudioTrackLookupService(bridge, direct, NATIVE_LIBRARY_DIR)
                    .tracks(VIDEO_PATH)
                    .isFailure,
            )
        }

    @Test
    fun `a probe-failed error becomes AudioTrackProbeFailedException`() =
        runTest {
            val bridge =
                PyBridge { _, _ ->
                    """{"schemaVersion":1,"type":"bridge.error","payload":{"code":"audio_tracks_probe_failed","message":"could not probe","requestType":"media.audiotracks"}}"""
                }
            val failure =
                BridgeAudioTrackLookupService(bridge, direct, NATIVE_LIBRARY_DIR)
                    .tracks(VIDEO_PATH)
                    .exceptionOrNull()
            assertTrue(failure is AudioTrackProbeFailedException)
        }

    @Test
    fun `other error codes become a generic failure`() =
        runTest {
            val bridge =
                PyBridge { _, _ ->
                    """{"schemaVersion":1,"type":"bridge.error","payload":{"code":"internal_error","message":"boom","requestType":"media.audiotracks"}}"""
                }
            val failure =
                BridgeAudioTrackLookupService(bridge, direct, NATIVE_LIBRARY_DIR)
                    .tracks(VIDEO_PATH)
                    .exceptionOrNull()
            assertTrue(failure != null && failure !is AudioTrackProbeFailedException)
        }
}
