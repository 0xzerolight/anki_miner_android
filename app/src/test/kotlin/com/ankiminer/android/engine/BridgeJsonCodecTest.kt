package com.ankiminer.android.engine

import com.ankiminer.android.mining.CurationCandidate
import com.ankiminer.android.mining.CurationRequest
import com.ankiminer.android.mining.CurationSelection
import com.ankiminer.android.mining.CurationSentence
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonToken
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeJsonCodecTest {
    @Test
    fun `all committed valid mining protocol fixtures decode`() {
        fixtures("contracts/mining_protocol_v1.json", "valid").forEach { fixture ->
            try {
                BridgeJsonCodec.decode(fixture.message)
            } catch (error: Throwable) {
                throw AssertionError("Valid fixture failed: ${fixture.name}", error)
            }
        }
    }

    @Test
    fun `all committed invalid mining protocol fixtures fail closed`() {
        fixtures("contracts/mining_protocol_v1.json", "invalid").forEach { fixture ->
            assertThrows("Invalid fixture decoded: ${fixture.name}", BridgeProtocolException::class.java) {
                BridgeJsonCodec.decode(fixture.message)
            }
        }
    }

    @Test
    fun `all committed valid engine event fixtures decode`() {
        fixtures("contracts/engine_events_v1.json", "valid").forEach { fixture ->
            try {
                BridgeJsonCodec.decode(fixture.message)
            } catch (error: Throwable) {
                throw AssertionError("Valid fixture failed: ${fixture.name}", error)
            }
        }
    }

    @Test
    fun `all committed invalid engine event fixtures fail closed`() {
        fixtures("contracts/engine_events_v1.json", "invalid").forEach { fixture ->
            assertThrows("Invalid fixture decoded: ${fixture.name}", BridgeProtocolException::class.java) {
                BridgeJsonCodec.decode(fixture.message)
            }
        }
    }

    @Test
    fun `all committed tokenizer protocol fixtures match the Kotlin codec`() {
        fixtures("contracts/tokenizer_protocol_v1.json", "valid").forEach { fixture ->
            try {
                BridgeJsonCodec.decode(fixture.message)
            } catch (error: Throwable) {
                throw AssertionError("Valid fixture failed: ${fixture.name}", error)
            }
        }
        fixtures("contracts/tokenizer_protocol_v1.json", "invalid").forEach { fixture ->
            assertThrows("Invalid fixture decoded: ${fixture.name}", BridgeProtocolException::class.java) {
                BridgeJsonCodec.decode(fixture.message)
            }
        }
    }

    @Test
    fun `fixed requests use compact deterministic envelopes`() {
        val runId = "run_${"a".repeat(32)}"
        val hash = "b".repeat(64)
        assertEquals(
            "{\"schemaVersion\":1,\"type\":\"bootstrap.initialize\",\"payload\":{\"filesDir\":\"/data/user/0/app/files\"}}",
            BridgeJsonCodec.encodeBootstrapInitialize("/data/user/0/app/files"),
        )
        assertEquals(
            "{\"schemaVersion\":1,\"type\":\"tokenizer.configure\",\"payload\":{\"dicDir\":\"/files/unidic\",\"resourceId\":\"unidic-lite-1\",\"treeSha256\":\"$hash\",\"backend\":\"s1a\"}}",
            BridgeJsonCodec.encodeTokenizerConfigure(
                TokenizerConfiguration("/files/unidic", "unidic-lite-1", hash),
            ),
        )
        assertEquals(
            "{\"schemaVersion\":1,\"type\":\"job.cancel\",\"payload\":{\"runId\":\"$runId\"}}",
            BridgeJsonCodec.encodeJobCancel(runId),
        )
        assertEquals(
            "{\"schemaVersion\":1,\"type\":\"job.registration.accepted\",\"payload\":{\"runId\":\"$runId\"}}",
            BridgeJsonCodec.encodeRegistrationAccepted(runId),
        )
    }

    @Test
    fun `video run encoder preserves subtitle suffix and typed nulls`() {
        val raw =
            BridgeJsonCodec.encodeVideoRun(
                VideoMiningWireRequest(
                    videoPath = "/proc/self/fd/8",
                    subtitlePath = "/cache/subtitle.SRT",
                    episodeName = "Episode 1",
                    seriesName = "Series",
                    sourceLabel = null,
                    audioTrackOverride = null,
                    cacheDir = "/cache",
                    nativeLibraryDir = "/native",
                    configSnapshot = MiningConfigSnapshot(emptyMap(), androidTtsEnabled = false),
                ),
            )
        assertEquals(
            "{\"schemaVersion\":1,\"type\":\"mining.video.run\",\"payload\":{\"videoPath\":\"/proc/self/fd/8\",\"subtitlePath\":\"/cache/subtitle.SRT\",\"episodeName\":\"Episode 1\",\"seriesName\":\"Series\",\"sourceLabel\":null,\"audioTrackOverride\":null,\"cacheDir\":\"/cache\",\"nativeLibraryDir\":\"/native\",\"configSnapshot\":{\"settings\":{},\"androidTtsEnabled\":false}}}",
            raw,
        )
        assertTrue(BridgeJsonCodec.decode(raw) is BridgeMessage.VideoRun)
    }

    @Test
    fun `curation request is typed and response validates candidate ownership`() {
        val request = curationRequest()
        val rawRequest =
            """{"schemaVersion":1,"type":"curation.request","payload":{"runId":"${request.runId}","requestId":"${request.requestId}","candidates":[{"candidateId":"${request.candidates.single().candidateId}","minedForm":"猫","surface":"猫","lemma":"猫","reading":"ネコ","expressionReading":"ねこ","partOfSpeech":null,"frequencyRank":12,"occurrenceCount":2,"defaultSentenceId":"${request.candidates.single().defaultSentenceId}","sentences":[{"sentenceId":"${request.candidates.single().defaultSentenceId}","sentence":"猫だ。","sentenceFurigana":"猫[ねこ]だ。","sentenceReading":"ねこだ。","startTime":1.0,"endTime":2.0,"duration":1.0}]}]}}"""
        assertEquals(request, (BridgeJsonCodec.decode(rawRequest) as BridgeMessage.CurationNeeded).request)

        val accepted = BridgeJsonCodec.encodeCurationResponse(request, listOf(CurationSelection(request.candidates.single().candidateId, null)))
        val response = BridgeJsonCodec.decode(accepted, request.runId, request.requestId)
        assertTrue(response is BridgeMessage.CurationResponse)

        assertThrows(BridgeProtocolException::class.java) {
            BridgeJsonCodec.encodeCurationResponse(
                request,
                listOf(CurationSelection("candidate_${"f".repeat(32)}", null)),
            )
        }
    }

    @Test
    fun `null and empty curation selections remain distinct`() {
        val request = curationRequest()
        val cancelled = BridgeJsonCodec.encodeCurationResponse(request, null)
        val skipped = BridgeJsonCodec.encodeCurationResponse(request, emptyList())
        assertEquals(null, (BridgeJsonCodec.decode(cancelled) as BridgeMessage.CurationResponse).selection)
        assertEquals(emptyList<CurationSelection>(), (BridgeJsonCodec.decode(skipped) as BridgeMessage.CurationResponse).selection)
    }

    @Test
    fun `expected opaque IDs reject stale control responses`() {
        val actualRun = "run_${"a".repeat(32)}"
        val staleRun = "run_${"b".repeat(32)}"
        val actualRequest = "curation_${"c".repeat(32)}"
        val staleRequest = "curation_${"d".repeat(32)}"
        val raw =
            """{"schemaVersion":1,"type":"curation.accepted","payload":{"runId":"$actualRun","requestId":"$actualRequest"}}"""

        assertEquals(BridgeProtocolCategory.STALE_RUN, protocolFailure { BridgeJsonCodec.decode(raw, staleRun, actualRequest) }.category)
        assertEquals(BridgeProtocolCategory.STALE_REQUEST, protocolFailure { BridgeJsonCodec.decode(raw, actualRun, staleRequest) }.category)
    }

    @Test
    fun `terminal keeps exact raw envelope for callback reconciliation`() {
        val fixture = fixtures("contracts/mining_protocol_v1.json", "valid").first { it.name == "successful terminal" }
        val terminal = BridgeJsonCodec.decode(fixture.message) as BridgeMessage.Terminal
        assertEquals(fixture.message, terminal.rawEnvelope)
    }

    @Test
    fun `malformed duplicate trailing BOM and surrogate inputs fail closed`() {
        val cases =
            listOf(
                """{"schemaVersion":1,"schemaVersion":1,"type":"progress.complete","payload":{"runId":"run_${"a".repeat(32)}"}}""",
                """{"schemaVersion":1,"type":"progress.complete","payload":{"runId":"run_${"a".repeat(32)}"}} true""",
                "\uFEFF{\"schemaVersion\":1,\"type\":\"bridge.error\",\"payload\":{\"code\":\"x\",\"message\":\"x\"}}",
                """{"schemaVersion":1,"type":"bridge.error","payload":{"code":"x","message":"\ud800"}}""",
                "{\"schemaVersion\":1,\"type\":\"bridge.error\",\"payload\":{\"code\":\"x\",\"message\":\"${'\uD800'}\"}}",
            )
        cases.forEach { raw -> assertThrows(BridgeProtocolException::class.java) { BridgeJsonCodec.decode(raw) } }
    }

    @Test
    fun `wire numeric domain rejects overflow nonfinite and overlong tokens`() {
        val runId = "run_${"a".repeat(32)}"
        val overflow = """{"schemaVersion":1,"type":"progress.start","payload":{"runId":"$runId","total":9223372036854775808,"description":"x"}}"""
        val nonfinite = """{"schemaVersion":1,"type":"progress.start","payload":{"runId":"$runId","total":1e9999,"description":"x"}}"""
        val overlong = """{"schemaVersion":${"1".repeat(1001)},"type":"progress.complete","payload":{"runId":"$runId"}}"""
        assertEquals(BridgeProtocolCategory.INTEGER_OUT_OF_RANGE, protocolFailure { BridgeJsonCodec.decode(overflow) }.category)
        assertEquals(BridgeProtocolCategory.NON_FINITE_NUMBER, protocolFailure { BridgeJsonCodec.decode(nonfinite) }.category)
        assertEquals(BridgeProtocolCategory.NUMERIC_TOKEN_TOO_LONG, protocolFailure { BridgeJsonCodec.decode(overlong) }.category)

        val mathematical = """{"schemaVersion":1.0,"type":"progress.start","payload":{"runId":"$runId","total":2.0,"description":"x"}}"""
        assertEquals(2L, (BridgeJsonCodec.decode(mathematical) as BridgeMessage.ProgressStart).total)
    }

    @Test
    fun `nesting and UTF-8 envelope ceilings are enforced before routing`() {
        val deep =
            """{"schemaVersion":1,"type":"bridge.error","payload":{"code":"x","message":${"[".repeat(130)}null${"]".repeat(130)}}}"""
        assertThrows(BridgeProtocolException::class.java) { BridgeJsonCodec.decode(deep) }

        val oversized = "x".repeat(BridgeJsonCodec.MAX_ENVELOPE_UTF8_BYTES + 1)
        assertEquals(BridgeProtocolCategory.INPUT_TOO_LARGE, protocolFailure { BridgeJsonCodec.decode(oversized) }.category)
    }

    private fun curationRequest(): CurationRequest {
        val runId = "run_${"a".repeat(32)}"
        val requestId = "curation_${"b".repeat(32)}"
        val candidateId = "candidate_${"c".repeat(32)}"
        val sentenceId = "sentence_${"d".repeat(32)}"
        return CurationRequest(
            runId,
            requestId,
            listOf(
                CurationCandidate(
                    candidateId = candidateId,
                    minedForm = "猫",
                    surface = "猫",
                    lemma = "猫",
                    reading = "ネコ",
                    expressionReading = "ねこ",
                    partOfSpeech = null,
                    frequencyRank = 12,
                    occurrenceCount = 2,
                    defaultSentenceId = sentenceId,
                    sentences =
                        listOf(
                            CurationSentence(
                                sentenceId,
                                "猫だ。",
                                "猫[ねこ]だ。",
                                "ねこだ。",
                                1.0,
                                2.0,
                                1.0,
                            ),
                        ),
                ),
            ),
        )
    }

    private fun protocolFailure(block: () -> Unit): BridgeProtocolException =
        assertThrows(BridgeProtocolException::class.java) { block() }

    private data class Fixture(
        val name: String,
        val message: String,
    )

    private fun fixtures(
        resource: String,
        section: String,
    ): List<Fixture> {
        val input = checkNotNull(javaClass.classLoader?.getResourceAsStream(resource))
        val parser = JsonFactory().createParser(input)
        parser.use {
            check(parser.nextToken() == JsonToken.START_OBJECT)
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                check(parser.currentToken() == JsonToken.FIELD_NAME)
                val field = parser.currentName()
                parser.nextToken()
                if (field != section) {
                    parser.skipChildren()
                    continue
                }
                check(parser.currentToken() == JsonToken.START_ARRAY)
                return buildList {
                    while (parser.nextToken() != JsonToken.END_ARRAY) {
                        check(parser.currentToken() == JsonToken.START_OBJECT)
                        var name: String? = null
                        var message: String? = null
                        while (parser.nextToken() != JsonToken.END_OBJECT) {
                            val caseField = parser.currentName()
                            parser.nextToken()
                            when (caseField) {
                                "name" -> name = parser.text
                                "message" -> {
                                    val output = ByteArrayOutputStream()
                                    JsonFactory().createGenerator(output).use { generator ->
                                        generator.copyCurrentStructure(parser)
                                    }
                                    message = output.toString(StandardCharsets.UTF_8.name())
                                }
                                else -> parser.skipChildren()
                            }
                        }
                        add(Fixture(checkNotNull(name), checkNotNull(message)))
                    }
                }
            }
        }
        error("Fixture section not found: $section")
    }
}
