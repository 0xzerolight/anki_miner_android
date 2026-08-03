package com.ankiminer.android.subtitles

import com.ankiminer.android.engine.PyBridge
import com.ankiminer.android.engine.SubtitleCue
import java.util.concurrent.Executor
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val RUN_ID = "run_00000000000000000000000000000000"
private const val SUBTITLE_PATH = "/cache/subtitle.srt"

class SubtitleCueLookupTest {
    private val direct = Executor { it.run() }

    private fun result(
        runId: String? = RUN_ID,
        subtitlePath: String = SUBTITLE_PATH,
    ): String {
        val encodedRunId = runId?.let { "\"$it\"" } ?: "null"
        return """{"schemaVersion":1,"type":"subtitle.cues.result","payload":{"runId":$encodedRunId,"subtitlePath":"$subtitlePath","cues":[{"start":1.25,"end":2.5,"text":"猫だ。"}]}}"""
    }

    @Test
    fun `returns decoded cues`() =
        runTest {
            val service = BridgeSubtitleCueLookupService(PyBridge { _, _ -> result() }, direct)
            assertEquals(
                listOf(SubtitleCue(1.25, 2.5, "猫だ。")),
                service.cues(RUN_ID, SUBTITLE_PATH).getOrThrow(),
            )
        }

    @Test
    fun `an error envelope becomes a failed Result`() =
        runTest {
            val bridge =
                PyBridge { _, _ ->
                    """{"schemaVersion":1,"type":"bridge.error","payload":{"code":"subtitle_cues_parse_failed","message":"could not parse","requestType":"subtitle.cues"}}"""
                }
            assertTrue(BridgeSubtitleCueLookupService(bridge, direct).cues(RUN_ID, SUBTITLE_PATH).isFailure)
        }

    @Test
    fun `a reply echoing another subtitle path is rejected`() =
        runTest {
            val bridge = PyBridge { _, _ -> result(subtitlePath = "/cache/other.srt") }
            assertTrue(BridgeSubtitleCueLookupService(bridge, direct).cues(RUN_ID, SUBTITLE_PATH).isFailure)
        }

    @Test
    fun `a non-null response run id is rejected for a null request run id`() =
        runTest {
            val bridge = PyBridge { _, _ -> result(runId = RUN_ID) }
            assertTrue(BridgeSubtitleCueLookupService(bridge, direct).cues(null, SUBTITLE_PATH).isFailure)
        }
}
