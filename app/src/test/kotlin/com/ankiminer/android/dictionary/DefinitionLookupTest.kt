package com.ankiminer.android.dictionary

import com.ankiminer.android.engine.BridgeJsonCodec
import com.ankiminer.android.engine.DefinitionEntry
import com.ankiminer.android.engine.PyBridge
import java.util.concurrent.Executor
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val RUN_ID = "run_00000000000000000000000000000000"
private const val OTHER_RUN_ID = "run_11111111111111111111111111111111"

class DefinitionLookupTest {
    private val direct = Executor { it.run() }

    private fun result(
        runId: String = RUN_ID,
        term: String = "猫",
        matchedTerm: String = "猫",
        entries: String = """[{"source":"Jitendex","html":"<div>cat</div>"}]""",
    ) = """{"schemaVersion":1,"type":"dictionary.define.result","payload":{"runId":"$runId","term":"$term","matchedTerm":"$matchedTerm","entries":$entries}}"""

    @Test
    fun `returns decoded entries`() =
        runTest {
            val service = BridgeDefinitionLookupService(PyBridge { _, _ -> result() }, direct)
            val decoded = service.define(RUN_ID, "猫", null).getOrThrow()
            assertEquals("猫", decoded.matchedTerm)
            assertEquals(listOf(DefinitionEntry("Jitendex", "<div>cat</div>")), decoded.entries)
        }

    @Test
    fun `sends the term and the fallback`() =
        runTest {
            var sent: String? = null
            val bridge =
                PyBridge { raw, _ ->
                    sent = raw
                    result(term = "殺る", matchedTerm = "殺る", entries = "[]")
                }
            BridgeDefinitionLookupService(bridge, direct).define(RUN_ID, "殺る", "遣る")
            assertEquals(
                BridgeJsonCodec.encodeDictionaryDefineRequest(RUN_ID, "殺る", "遣る"),
                sent,
            )
        }

    @Test
    fun `a bridge failure becomes a failed Result`() =
        runTest {
            val bridge = PyBridge { _, _ -> throw IllegalStateException("boom") }
            assertTrue(BridgeDefinitionLookupService(bridge, direct).define(RUN_ID, "猫", null).isFailure)
        }

    @Test
    fun `an error envelope becomes a failed Result`() =
        runTest {
            val bridge =
                PyBridge { _, _ ->
                    """{"schemaVersion":1,"type":"bridge.error","payload":{"code":"definition_run_unknown","message":"no run","requestType":"dictionary.define"}}"""
                }
            assertTrue(BridgeDefinitionLookupService(bridge, direct).define(RUN_ID, "猫", null).isFailure)
        }

    @Test
    fun `a reply for another run is rejected`() =
        runTest {
            val bridge = PyBridge { _, _ -> result(runId = OTHER_RUN_ID) }
            assertTrue(BridgeDefinitionLookupService(bridge, direct).define(RUN_ID, "猫", null).isFailure)
        }

    @Test
    fun `a reply echoing another term is rejected`() =
        runTest {
            val bridge = PyBridge { _, _ -> result(term = "犬", matchedTerm = "犬") }
            assertTrue(BridgeDefinitionLookupService(bridge, direct).define(RUN_ID, "猫", null).isFailure)
        }

    @Test
    fun `a matched term outside the query is rejected`() =
        runTest {
            val bridge = PyBridge { _, _ -> result(matchedTerm = "鳥") }
            assertTrue(BridgeDefinitionLookupService(bridge, direct).define(RUN_ID, "猫", "犬").isFailure)
        }

    @Test
    fun `the bridge runs on the supplied executor`() =
        runTest {
            var ran = false
            val executor = Executor { task ->
                ran = true
                task.run()
            }
            BridgeDefinitionLookupService(PyBridge { _, _ -> result() }, executor)
                .define(RUN_ID, "猫", null)
            assertTrue(ran)
        }
}
