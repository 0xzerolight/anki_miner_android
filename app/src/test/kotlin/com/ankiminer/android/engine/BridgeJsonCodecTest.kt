package com.ankiminer.android.engine

import com.ankiminer.android.mining.CurationCandidate
import com.ankiminer.android.mining.CurationPage
import com.ankiminer.android.mining.CurationRequest
import com.ankiminer.android.mining.CurationSelection
import com.ankiminer.android.mining.CurationSentence
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonToken
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private const val DEFINE_RUN_ID = "run_00000000000000000000000000000000"

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
        val raw = BridgeJsonCodec.encodeVideoRun(videoRequest(audioOnly = false))
        assertEquals(
            "{\"schemaVersion\":1,\"type\":\"mining.video.run\",\"payload\":{\"videoPath\":\"/proc/self/fd/8\",\"subtitlePath\":\"/cache/subtitle.SRT\",\"episodeName\":\"Episode 1\",\"seriesName\":\"Series\",\"sourceLabel\":null,\"audioTrackOverride\":null,\"audioOnly\":false,\"cacheDir\":\"/cache\",\"nativeLibraryDir\":\"/native\",\"configSnapshot\":{\"settings\":{},\"androidTtsEnabled\":false}}}",
            raw,
        )
        assertTrue(BridgeJsonCodec.decode(raw) is BridgeMessage.VideoRun)
    }

    @Test
    fun `video run encoder writes audio only as a JSON boolean`() {
        val raw = BridgeJsonCodec.encodeVideoRun(videoRequest(audioOnly = true))
        var audioOnlyFound = false

        JsonFactory().createParser(raw).use { parser ->
            while (parser.nextToken() != null) {
                if (parser.currentToken() == JsonToken.FIELD_NAME && parser.currentName() == "audioOnly") {
                    assertEquals(JsonToken.VALUE_TRUE, parser.nextToken())
                    audioOnlyFound = true
                }
            }
        }

        assertTrue(audioOnlyFound)
    }

    @Test
    fun `video run encoder emits the exact payload key set`() {
        val raw = BridgeJsonCodec.encodeVideoRun(videoRequest(audioOnly = false))
        val payloadKeys = linkedSetOf<String>()

        JsonFactory().createParser(raw).use { parser ->
            assertEquals(JsonToken.START_OBJECT, parser.nextToken())
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                assertEquals(JsonToken.FIELD_NAME, parser.currentToken())
                val fieldName = parser.currentName()
                parser.nextToken()
                if (fieldName != "payload") {
                    parser.skipChildren()
                    continue
                }
                assertEquals(JsonToken.START_OBJECT, parser.currentToken())
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    assertEquals(JsonToken.FIELD_NAME, parser.currentToken())
                    payloadKeys += parser.currentName()
                    parser.nextToken()
                    parser.skipChildren()
                }
            }
        }

        assertEquals(
            setOf(
                "videoPath",
                "subtitlePath",
                "episodeName",
                "seriesName",
                "sourceLabel",
                "audioTrackOverride",
                "audioOnly",
                "cacheDir",
                "nativeLibraryDir",
                "configSnapshot",
            ),
            payloadKeys,
        )
    }

    @Test
    fun `video run encode decode round trip preserves both audio only values`() {
        listOf(true, false).forEach { audioOnly ->
            val request = videoRequest(audioOnly)

            assertEquals(
                BridgeMessage.VideoRun(request),
                BridgeJsonCodec.decode(BridgeJsonCodec.encodeVideoRun(request)),
            )
        }
    }

    @Test
    fun `video run decoder rejects a missing audio only field`() {
        val fixture =
            fixtures("contracts/mining_protocol_v1.json", "invalid")
                .first { it.name == "video request missing required audio only" }

        assertThrows(BridgeProtocolException::class.java) {
            BridgeJsonCodec.decode(fixture.message)
        }
    }

    @Test
    fun `text reading run encoder preserves text suffix and typed nulls`() {
        val raw = BridgeJsonCodec.encodeReadingRun(readingRequest())

        assertEquals(
            "{\"schemaVersion\":1,\"type\":\"mining.reading.run\",\"payload\":{\"sourceKind\":\"text\",\"sourcePath\":\"/cache/pasted.text\",\"imageArchivePath\":null,\"seriesName\":null,\"cacheDir\":\"/cache\",\"nativeLibraryDir\":\"/native\",\"configSnapshot\":{\"settings\":{},\"androidTtsEnabled\":false}}}",
            raw,
        )
    }

    @Test
    fun `text reading run encode decode round trip preserves request`() {
        val request = readingRequest()

        assertEquals(
            request,
            (BridgeJsonCodec.decode(BridgeJsonCodec.encodeReadingRun(request)) as BridgeMessage.ReadingRun).request,
        )
    }

    @Test
    fun `text reading run rejects a txt source suffix`() {
        assertThrows(BridgeProtocolException::class.java) {
            BridgeJsonCodec.encodeReadingRun(readingRequest(sourcePath = "/cache/pasted.txt"))
        }
    }

    @Test
    fun `txt reading run rejects a text source suffix`() {
        assertThrows(BridgeProtocolException::class.java) {
            BridgeJsonCodec.encodeReadingRun(
                readingRequest(
                    sourceKind = ReadingMiningSourceKind.TXT,
                    sourcePath = "/cache/pasted.text",
                ),
            )
        }
    }

    @Test
    fun `text reading run rejects subtitle and mokuro metadata`() {
        listOf(
            readingRequest(seriesName = "Series"),
            readingRequest(imageArchivePath = "/cache/pasted.zip"),
        ).forEach { request ->
            assertThrows(BridgeProtocolException::class.java) {
                BridgeJsonCodec.encodeReadingRun(request)
            }
        }
    }

    @Test
    fun `text reading run rejects a source outside cache dir`() {
        assertThrows(BridgeProtocolException::class.java) {
            BridgeJsonCodec.encodeReadingRun(readingRequest(sourcePath = "/outside/pasted.text"))
        }
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
    fun `encodes known candidate ids`() {
        val request = curationRequest()
        val candidateId = request.candidates.single().candidateId
        val raw =
            BridgeJsonCodec.encodeCurationResponse(
                request,
                emptyList(),
                knownCandidateIds = listOf(candidateId),
            )

        assertTrue(raw.contains("\"knownCandidateIds\":[\"$candidateId\"]"))
        val decoded = BridgeJsonCodec.decode(raw) as BridgeMessage.CurationResponse
        assertEquals(listOf(candidateId), decoded.knownCandidateIds)
    }

    @Test
    fun `omits known candidate ids when empty`() {
        val raw =
            BridgeJsonCodec.encodeCurationResponse(
                curationRequest(),
                emptyList(),
                knownCandidateIds = emptyList(),
            )

        assertFalse(raw.contains("knownCandidateIds"))
    }

    @Test
    fun `rejects a known candidate id outside the request`() {
        assertThrows(BridgeProtocolException::class.java) {
            BridgeJsonCodec.encodeCurationResponse(
                curationRequest(),
                emptyList(),
                knownCandidateIds = listOf("candidate_${"f".repeat(32)}"),
            )
        }
    }

    @Test
    fun `rejects a candidate that is both selected and known`() {
        val request = curationRequest()
        val candidateId = request.candidates.single().candidateId

        assertThrows(BridgeProtocolException::class.java) {
            BridgeJsonCodec.encodeCurationResponse(
                request,
                listOf(CurationSelection(candidateId, null)),
                knownCandidateIds = listOf(candidateId),
            )
        }
    }

    @Test
    fun `rejects duplicate known candidate ids`() {
        val request = curationRequest()
        val candidateId = request.candidates.single().candidateId

        assertThrows(BridgeProtocolException::class.java) {
            BridgeJsonCodec.encodeCurationResponse(
                request,
                emptyList(),
                knownCandidateIds = listOf(candidateId, candidateId),
            )
        }
    }

    @Test
    fun `decodes a curation response without known candidate ids`() {
        val request = curationRequest()
        val raw =
            """{"schemaVersion":1,"type":"curation.response","payload":{"runId":"${request.runId}","requestId":"${request.requestId}","selection":[]}}"""

        assertEquals(
            emptyList<String>(),
            (BridgeJsonCodec.decode(raw) as BridgeMessage.CurationResponse).knownCandidateIds,
        )
    }

    @Test
    fun `decodes a curation response with known candidate ids`() {
        val request = curationRequest()
        val candidateId = request.candidates.single().candidateId
        val raw =
            """{"schemaVersion":1,"type":"curation.response","payload":{"runId":"${request.runId}","requestId":"${request.requestId}","selection":[],"knownCandidateIds":["$candidateId"]}}"""

        assertEquals(
            listOf(candidateId),
            (BridgeJsonCodec.decode(raw) as BridgeMessage.CurationResponse).knownCandidateIds,
        )
    }

    @Test
    fun `rejects a curation response with duplicate known candidate ids`() {
        val request = curationRequest()
        val candidateId = request.candidates.single().candidateId
        val raw =
            """{"schemaVersion":1,"type":"curation.response","payload":{"runId":"${request.runId}","requestId":"${request.requestId}","selection":[],"knownCandidateIds":["$candidateId","$candidateId"]}}"""

        assertThrows(BridgeProtocolException::class.java) { BridgeJsonCodec.decode(raw) }
    }

    @Test
    fun `rejects a decoded candidate that is both selected and known`() {
        val request = curationRequest()
        val candidateId = request.candidates.single().candidateId
        val raw =
            """{"schemaVersion":1,"type":"curation.response","payload":{"runId":"${request.runId}","requestId":"${request.requestId}","selection":[{"candidateId":"$candidateId"}],"knownCandidateIds":["$candidateId"]}}"""

        assertThrows(BridgeProtocolException::class.java) { BridgeJsonCodec.decode(raw) }
    }

    @Test
    fun `still rejects an unknown field on a curation response`() {
        val request = curationRequest()
        val raw =
            """{"schemaVersion":1,"type":"curation.response","payload":{"runId":"${request.runId}","requestId":"${request.requestId}","selection":[],"nope":1}}"""

        assertThrows(BridgeProtocolException::class.java) { BridgeJsonCodec.decode(raw) }
    }

    @Test
    fun `decodes a curation page response without known candidate ids`() {
        val request = curationRequest()
        val raw =
            """{"schemaVersion":1,"type":"curation.page.response","payload":{"runId":"${request.runId}","requestId":"${request.requestId}","pageIndex":0,"selection":[]}}"""

        assertEquals(
            emptyList<String>(),
            (BridgeJsonCodec.decode(raw) as BridgeMessage.CurationPageResponse).knownCandidateIds,
        )
    }

    @Test
    fun `decodes a curation page response with known candidate ids`() {
        val request = curationRequest()
        val candidateId = request.candidates.single().candidateId
        val raw =
            """{"schemaVersion":1,"type":"curation.page.response","payload":{"runId":"${request.runId}","requestId":"${request.requestId}","pageIndex":0,"selection":[],"knownCandidateIds":["$candidateId"]}}"""

        assertEquals(
            listOf(candidateId),
            (BridgeJsonCodec.decode(raw) as BridgeMessage.CurationPageResponse).knownCandidateIds,
        )
    }

    @Test
    fun `rejects a curation page response with duplicate known candidate ids`() {
        val request = curationRequest()
        val candidateId = request.candidates.single().candidateId
        val raw =
            """{"schemaVersion":1,"type":"curation.page.response","payload":{"runId":"${request.runId}","requestId":"${request.requestId}","pageIndex":0,"selection":[],"knownCandidateIds":["$candidateId","$candidateId"]}}"""

        assertThrows(BridgeProtocolException::class.java) { BridgeJsonCodec.decode(raw) }
    }

    @Test
    fun `rejects a decoded page candidate that is both selected and known`() {
        val request = curationRequest()
        val candidateId = request.candidates.single().candidateId
        val raw =
            """{"schemaVersion":1,"type":"curation.page.response","payload":{"runId":"${request.runId}","requestId":"${request.requestId}","pageIndex":0,"selection":[{"candidateId":"$candidateId"}],"knownCandidateIds":["$candidateId"]}}"""

        assertThrows(BridgeProtocolException::class.java) { BridgeJsonCodec.decode(raw) }
    }

    @Test
    fun `still rejects an unknown field on a curation page response`() {
        val request = curationRequest()
        val raw =
            """{"schemaVersion":1,"type":"curation.page.response","payload":{"runId":"${request.runId}","requestId":"${request.requestId}","pageIndex":0,"selection":[],"nope":1}}"""

        assertThrows(BridgeProtocolException::class.java) { BridgeJsonCodec.decode(raw) }
    }

    @Test
    fun `paged curation preserves metadata and uses page-specific control messages`() {
        val base = curationRequest()
        val page = CurationPage(pageIndex = 1, pageCount = 2, candidateStart = 1, totalCandidates = 2)
        val request = base.copy(page = page)
        val candidate = request.candidates.single()
        val sentence = candidate.sentences.single()
        val rawRequest =
            """{"schemaVersion":1,"type":"curation.page.request","payload":{"runId":"${request.runId}","requestId":"${request.requestId}","pageIndex":1,"pageCount":2,"candidateStart":1,"totalCandidates":2,"candidates":[{"candidateId":"${candidate.candidateId}","minedForm":"猫","surface":"猫","lemma":"猫","reading":"ネコ","expressionReading":"ねこ","partOfSpeech":null,"frequencyRank":12,"occurrenceCount":2,"defaultSentenceId":"${candidate.defaultSentenceId}","sentences":[{"sentenceId":"${sentence.sentenceId}","sentence":"猫だ。","sentenceFurigana":"猫[ねこ]だ。","sentenceReading":"ねこだ。","startTime":1.0,"endTime":2.0,"duration":1.0}]}]}}"""

        assertEquals(request, (BridgeJsonCodec.decode(rawRequest) as BridgeMessage.CurationNeeded).request)
        val encoded =
            BridgeJsonCodec.encodeCurationResponse(
                request,
                listOf(CurationSelection(candidate.candidateId, null)),
            )
        val response = BridgeJsonCodec.decode(encoded) as BridgeMessage.CurationPageResponse
        assertEquals(page.pageIndex, response.pageIndex)
        assertEquals(candidate.candidateId, response.selection?.single()?.candidateId)

        val acknowledgement =
            BridgeJsonCodec.decode(
                """{"schemaVersion":1,"type":"curation.page.accepted","payload":{"runId":"${request.runId}","requestId":"${request.requestId}","pageIndex":1,"finalPage":true}}""",
            ) as BridgeMessage.CurationPageAccepted
        assertEquals(1L, acknowledgement.pageIndex)
        assertTrue(acknowledgement.finalPage)
    }

    @Test
    fun `paged curation rejects inconsistent bounds and oversized envelopes`() {
        val request = curationRequest()
        val candidate = request.candidates.single()
        val sentence = candidate.sentences.single()
        val inconsistent =
            """{"schemaVersion":1,"type":"curation.page.request","payload":{"runId":"${request.runId}","requestId":"${request.requestId}","pageIndex":0,"pageCount":2,"candidateStart":1,"totalCandidates":2,"candidates":[{"candidateId":"${candidate.candidateId}","minedForm":"猫","surface":"猫","lemma":"猫","reading":"ネコ","expressionReading":"ねこ","partOfSpeech":null,"frequencyRank":12,"occurrenceCount":2,"defaultSentenceId":"${candidate.defaultSentenceId}","sentences":[{"sentenceId":"${sentence.sentenceId}","sentence":"猫だ。","sentenceFurigana":"","sentenceReading":"","startTime":1.0,"endTime":2.0,"duration":1.0}]}]}}"""
        assertEquals(
            BridgeProtocolCategory.INVALID_VALUE,
            protocolFailure { BridgeJsonCodec.decode(inconsistent) }.category,
        )

        val oversized =
            inconsistent.replace(
                "\"sentence\":\"猫だ。\"",
                "\"sentence\":\"${"猫".repeat(BridgeJsonCodec.MAX_CURATION_PAGE_UTF8_BYTES)}\"",
            )
        assertEquals(
            BridgeProtocolCategory.INPUT_TOO_LARGE,
            protocolFailure { BridgeJsonCodec.decode(oversized) }.category,
        )
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
    fun `terminal error carries an optional fault id without making it mandatory`() {
        val runId = "run_${"a".repeat(32)}"
        fun terminal(error: String) =
            """{"schemaVersion":1,"type":"mining.terminal","payload":{"runId":"$runId","outcome":"failed","result":null,"error":$error}}"""

        val withFault =
            BridgeJsonCodec.decode(
                terminal("""{"code":"internal_error","message":"Internal mining failure","faultId":"f0123abcd"}"""),
            ) as BridgeMessage.Terminal
        assertEquals("f0123abcd", withFault.error?.faultId)

        val withoutFault =
            BridgeJsonCodec.decode(
                terminal("""{"code":"internal_error","message":"Internal mining failure"}"""),
            ) as BridgeMessage.Terminal
        assertEquals(null, withoutFault.error?.faultId)

        assertEquals(
            BridgeProtocolCategory.INVALID_VALUE,
            protocolFailure {
                BridgeJsonCodec.decode(terminal("""{"code":"internal_error","message":"x","faultId":"nope"}"""))
            }.category,
        )
        assertEquals(
            BridgeProtocolCategory.INVALID_PAYLOAD,
            protocolFailure {
                BridgeJsonCodec.decode(terminal("""{"code":"internal_error","message":"x","retryable":false}"""))
            }.category,
        )
    }

    @Test
    fun `terminals that are not faults still decode without a fault id`() {
        // The regression guard for the accepted-key-sets idiom: an exact key set including faultId
        // would make it mandatory and break cancel, a core shipping path.
        val runId = "run_${"a".repeat(32)}"
        val cancelled =
            """{"schemaVersion":1,"type":"mining.terminal","payload":{"runId":"$runId","outcome":"cancelled","result":null,"error":{"code":"cancelled","message":"Mining was cancelled"}}}"""
        val cleanupFailed =
            fixtures("contracts/mining_protocol_v1.json", "valid").first { it.name == "cleanup failure retains result" }

        val decodedCancel = BridgeJsonCodec.decode(cancelled) as BridgeMessage.Terminal
        assertEquals("cancelled", decodedCancel.error?.code)
        assertEquals(null, decodedCancel.error?.faultId)

        val decodedCleanup = BridgeJsonCodec.decode(cleanupFailed.message) as BridgeMessage.Terminal
        assertEquals("cleanup_failed", decodedCleanup.error?.code)
        assertEquals(null, decodedCleanup.error?.faultId)
    }

    @Test
    fun `bridge error accepts a fault id beside an optional request type`() {
        fun bridgeError(payload: String) = """{"schemaVersion":1,"type":"bridge.error","payload":$payload}"""

        val full =
            BridgeJsonCodec.decode(
                bridgeError(
                    """{"code":"internal_error","message":"Internal bridge failure","requestType":"job.cancel","faultId":"f0123abcd"}""",
                ),
            ) as BridgeMessage.Error
        assertEquals("f0123abcd", full.faultId)
        assertEquals("job.cancel", full.requestType)

        val withoutRequestType =
            BridgeJsonCodec.decode(
                bridgeError("""{"code":"internal_error","message":"Internal bridge failure","faultId":"f0123abcd"}"""),
            ) as BridgeMessage.Error
        assertEquals("f0123abcd", withoutRequestType.faultId)
        assertEquals(null, withoutRequestType.requestType)

        assertEquals(
            null,
            (BridgeJsonCodec.decode(bridgeError("""{"code":"internal_error","message":"x"}""")) as BridgeMessage.Error).faultId,
        )
        assertEquals(
            BridgeProtocolCategory.INVALID_VALUE,
            protocolFailure { BridgeJsonCodec.decode(bridgeError("""{"code":"x","message":"x","faultId":"f0123abc"}""")) }.category,
        )
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
    fun `diagnostics log level messages round-trip and reject levels outside the vocabulary`() {
        val request = BridgeJsonCodec.decode(BridgeJsonCodec.encodeDiagnosticsLogLevelSet("debug"))
        val applied =
            BridgeJsonCodec.decode(
                """{"schemaVersion":1,"type":"diagnostics.loglevel.applied","payload":{"level":"info"}}""",
            )

        assertEquals(BridgeMessage.DiagnosticsLogLevelSet("debug"), request)
        assertEquals(BridgeMessage.DiagnosticsLogLevelApplied("info"), applied)

        // Only reachable through decode: encode() self-validates its own output, so the encoder
        // can never present the codec with a level it did not write.
        val rejected =
            listOf(
                """{"schemaVersion":1,"type":"diagnostics.loglevel.set","payload":{"level":"trace"}}""",
                """{"schemaVersion":1,"type":"diagnostics.loglevel.set","payload":{"level":"DEBUG"}}""",
                """{"schemaVersion":1,"type":"diagnostics.loglevel.applied","payload":{"level":"warning"}}""",
                """{"schemaVersion":1,"type":"diagnostics.loglevel.set","payload":{"level":1}}""",
                """{"schemaVersion":1,"type":"diagnostics.loglevel.set","payload":{}}""",
                """{"schemaVersion":1,"type":"diagnostics.loglevel.set","payload":{"level":"info","extra":1}}""",
            )
        rejected.forEach { raw ->
            assertThrows(raw, BridgeProtocolException::class.java) { BridgeJsonCodec.decode(raw) }
        }
    }

    @Test
    fun `encodes a dictionary define request`() {
        val raw = BridgeJsonCodec.encodeDictionaryDefineRequest(DEFINE_RUN_ID, "殺る", "遣る")
        assertEquals(
            BridgeMessage.DictionaryDefineRequest(DEFINE_RUN_ID, "殺る", "遣る"),
            BridgeJsonCodec.decode(raw),
        )
    }

    @Test
    fun `encodes a dictionary define request without a fallback`() {
        val raw = BridgeJsonCodec.encodeDictionaryDefineRequest(DEFINE_RUN_ID, "猫", null)
        assertEquals(
            BridgeMessage.DictionaryDefineRequest(DEFINE_RUN_ID, "猫", null),
            BridgeJsonCodec.decode(raw),
        )
    }

    @Test
    fun `decodes a dictionary define result`() {
        val raw =
            """{"schemaVersion":1,"type":"dictionary.define.result","payload":{"runId":"$DEFINE_RUN_ID","term":"殺る","matchedTerm":"遣る","entries":[{"source":"Jitendex","html":"<div>to do</div>"}]}}"""
        assertEquals(
            BridgeMessage.DictionaryDefineResult(
                runId = DEFINE_RUN_ID,
                term = "殺る",
                matchedTerm = "遣る",
                entries = listOf(DefinitionEntry("Jitendex", "<div>to do</div>")),
            ),
            BridgeJsonCodec.decode(raw),
        )
    }

    @Test
    fun `decodes an empty dictionary define result`() {
        val raw =
            """{"schemaVersion":1,"type":"dictionary.define.result","payload":{"runId":"$DEFINE_RUN_ID","term":"ぬぬぬ","matchedTerm":"ぬぬぬ","entries":[]}}"""
        val decoded = BridgeJsonCodec.decode(raw) as BridgeMessage.DictionaryDefineResult
        assertTrue(decoded.entries.isEmpty())
    }

    @Test
    fun `rejects a dictionary define result with an unknown field`() {
        val raw =
            """{"schemaVersion":1,"type":"dictionary.define.result","payload":{"runId":"$DEFINE_RUN_ID","term":"猫","matchedTerm":"猫","entries":[],"extra":1}}"""
        assertThrows(BridgeProtocolException::class.java) { BridgeJsonCodec.decode(raw) }
    }

    @Test
    fun `rejects a dictionary define result for another run`() {
        val other = "run_${"1".repeat(32)}"
        val raw =
            """{"schemaVersion":1,"type":"dictionary.define.result","payload":{"runId":"$other","term":"猫","matchedTerm":"猫","entries":[]}}"""
        assertEquals(
            BridgeProtocolCategory.STALE_RUN,
            protocolFailure { BridgeJsonCodec.decode(raw, DEFINE_RUN_ID) }.category,
        )
    }

    @Test
    fun `encodes a subtitle cues request with a null run id`() {
        val raw = BridgeJsonCodec.encodeSubtitleCuesRequest(null, "/cache/subtitle.srt")
        assertEquals(
            """{"schemaVersion":1,"type":"subtitle.cues","payload":{"runId":null,"subtitlePath":"/cache/subtitle.srt"}}""",
            raw,
        )
        assertEquals(
            BridgeMessage.SubtitleCuesRequest(null, "/cache/subtitle.srt"),
            BridgeJsonCodec.decode(raw),
        )
    }

    @Test
    fun `decodes a subtitle cues result`() {
        val raw =
            """{"schemaVersion":1,"type":"subtitle.cues.result","payload":{"runId":"$DEFINE_RUN_ID","subtitlePath":"/cache/subtitle.srt","cues":[{"start":1.25,"end":2.5,"text":"猫だ。"},{"start":3,"end":3,"text":"犬だ。"}]}}"""
        assertEquals(
            BridgeMessage.SubtitleCuesResult(
                runId = DEFINE_RUN_ID,
                subtitlePath = "/cache/subtitle.srt",
                cues =
                    listOf(
                        SubtitleCue(1.25, 2.5, "猫だ。"),
                        SubtitleCue(3.0, 3.0, "犬だ。"),
                    ),
            ),
            BridgeJsonCodec.decode(raw),
        )
    }

    @Test
    fun `rejects a subtitle cue without an end time`() {
        val raw =
            """{"schemaVersion":1,"type":"subtitle.cues.result","payload":{"runId":null,"subtitlePath":"/cache/subtitle.srt","cues":[{"start":1.25,"text":"猫だ。"}]}}"""
        assertThrows(BridgeProtocolException::class.java) { BridgeJsonCodec.decode(raw) }
    }

    @Test
    fun `rejects subtitle cues with non-finite times`() {
        val rejected =
            listOf(
                """{"schemaVersion":1,"type":"subtitle.cues.result","payload":{"runId":null,"subtitlePath":"/cache/subtitle.srt","cues":[{"start":1e9999,"end":2.5,"text":"猫だ。"}]}}""",
                """{"schemaVersion":1,"type":"subtitle.cues.result","payload":{"runId":null,"subtitlePath":"/cache/subtitle.srt","cues":[{"start":1.25,"end":1e9999,"text":"猫だ。"}]}}""",
            )
        rejected.forEach { raw ->
            assertEquals(
                BridgeProtocolCategory.NON_FINITE_NUMBER,
                protocolFailure { BridgeJsonCodec.decode(raw) }.category,
            )
        }
    }

    @Test
    fun `rejects a subtitle cue ending before it starts`() {
        val raw =
            """{"schemaVersion":1,"type":"subtitle.cues.result","payload":{"runId":null,"subtitlePath":"/cache/subtitle.srt","cues":[{"start":2.5,"end":1.25,"text":"猫だ。"}]}}"""
        assertThrows(BridgeProtocolException::class.java) { BridgeJsonCodec.decode(raw) }
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

    @Test
    fun `accepts animated screenshot settings within the supported ranges`() {
        val decoded =
            BridgeJsonCodec.decode(
                videoRunWithSettings(
                    """"screenshot_animated":true,"screenshot_animated_format":"webp",""" +
                        """"screenshot_animated_clip_duration":2.0,"screenshot_animated_quality":30""",
                ),
            ) as BridgeMessage.VideoRun

        assertEquals(
            setOf(
                "screenshot_animated",
                "screenshot_animated_format",
                "screenshot_animated_clip_duration",
                "screenshot_animated_quality",
            ),
            decoded.request.configSnapshot.settings.keys,
        )
    }

    @Test
    fun `rejects animated screenshot settings outside the supported ranges`() {
        val rejected =
            listOf(
                """"screenshot_animated_format":"gif"""",
                """"screenshot_animated_clip_duration":12.0""",
                """"screenshot_animated_clip_duration":0.1""",
                """"screenshot_animated_quality":101""",
                """"screenshot_animated_quality":-1""",
            )
        rejected.forEach { setting ->
            assertEquals(
                setting,
                BridgeProtocolCategory.INVALID_VALUE,
                protocolFailure { BridgeJsonCodec.decode(videoRunWithSettings(setting)) }.category,
            )
        }
    }

    private fun videoRunWithSettings(settings: String): String =
        """{"schemaVersion":1,"type":"mining.video.run","payload":{"videoPath":"/proc/self/fd/8",""" +
            """"subtitlePath":"/cache/subtitle.SRT","episodeName":"Episode 1","seriesName":"Series",""" +
            """"sourceLabel":null,"audioTrackOverride":null,"audioOnly":false,"cacheDir":"/cache",""" +
            """"nativeLibraryDir":"/native","configSnapshot":{"settings":{$settings},""" +
            """"androidTtsEnabled":false}}}"""

    private fun videoRequest(audioOnly: Boolean): VideoMiningWireRequest =
        VideoMiningWireRequest(
            videoPath = "/proc/self/fd/8",
            subtitlePath = "/cache/subtitle.SRT",
            episodeName = "Episode 1",
            seriesName = "Series",
            sourceLabel = null,
            audioTrackOverride = null,
            audioOnly = audioOnly,
            cacheDir = "/cache",
            nativeLibraryDir = "/native",
            configSnapshot = MiningConfigSnapshot(emptyMap(), androidTtsEnabled = false),
        )

    private fun readingRequest(
        sourceKind: ReadingMiningSourceKind = ReadingMiningSourceKind.TEXT,
        sourcePath: String = "/cache/pasted.text",
        imageArchivePath: String? = null,
        seriesName: String? = null,
    ): ReadingMiningWireRequest =
        ReadingMiningWireRequest(
            sourceKind = sourceKind,
            sourcePath = sourcePath,
            imageArchivePath = imageArchivePath,
            seriesName = seriesName,
            cacheDir = "/cache",
            nativeLibraryDir = "/native",
            configSnapshot = MiningConfigSnapshot(emptyMap(), androidTtsEnabled = false),
        )

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
