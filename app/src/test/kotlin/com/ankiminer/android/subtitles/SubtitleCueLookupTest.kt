package com.ankiminer.android.subtitles

import com.ankiminer.android.diagnostics.log.AppLog
import com.ankiminer.android.diagnostics.log.LogContext
import com.ankiminer.android.diagnostics.log.LogLevel
import com.ankiminer.android.diagnostics.log.NoOpSink
import com.ankiminer.android.diagnostics.log.RecordingLogSink
import com.ankiminer.android.engine.PyBridge
import com.ankiminer.android.engine.SubtitleCue
import com.ankiminer.android.engine.emitDispatchEntry
import java.util.concurrent.Executor
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val RUN_ID = "run_00000000000000000000000000000000"
private const val STALE_RUN_ID = "run_11111111111111111111111111111111"
private const val SUBTITLE_PATH = "/cache/subtitle.srt"

class SubtitleCueLookupTest {
    private val direct = Executor { it.run() }
    private val recorded = RecordingLogSink()

    @Before
    fun installRecordingSink() {
        AppLog.setMinLevel(LogLevel.DEBUG)
        AppLog.install(NoOpSink)
        AppLog.install(recorded)
    }

    @After
    fun detachRecordingSink() {
        LogContext.setRunId(null)
        AppLog.setMinLevel(LogLevel.INFO)
        AppLog.install(NoOpSink)
    }

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

    @Test
    fun `bridge boundary logs carry the lookup run id`() =
        runTest {
            val bridge =
                PyBridge { raw, _ ->
                    emitDispatchEntry("subtitle.cues", raw)
                    result()
                }

            BridgeSubtitleCueLookupService(bridge, direct).cues(RUN_ID, SUBTITLE_PATH).getOrThrow()

            val record = recorded.records.single { it.contains("op=dispatch") }
            assertTrue(record, record.contains(" D run=$RUN_ID c=bridge op=dispatch "))
        }

    @Test
    fun `a lookup without a run id clears stale executor context`() =
        runTest {
            val bridge =
                PyBridge { raw, _ ->
                    emitDispatchEntry("subtitle.cues", raw)
                    result(runId = null)
                }
            LogContext.setRunId(STALE_RUN_ID)

            BridgeSubtitleCueLookupService(bridge, direct).cues(null, SUBTITLE_PATH).getOrThrow()

            val record = recorded.records.single { it.contains("op=dispatch") }
            assertTrue(record, record.contains(" D run=- c=bridge op=dispatch "))
            assertEquals(STALE_RUN_ID, LogContext.runId())
        }
}
