package com.ankiminer.android.anki.provider

import com.ankiminer.android.anki.protocol.AnkiJsonCodec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnkiProviderCallbacksTest {
    @Test
    fun `dispatcher returns canonical correlated verify result`() {
        val harness = harness()
        harness.gateway.queryHandler = targetQueryHandler()

        assertEquals(
            """{"schemaVersion":1,"type":"anki.verifytarget.result","payload":{"runId":"$RUN_ID","requestId":"$REQUEST_ID","deckId":20,"modelId":10,"fieldNames":["Expression","Meaning"],"deckCreated":false}}""",
            harness.callbacks.ankiVerifyTarget(verifyEnvelope()),
        )
        assertEquals(3, harness.gateway.queries.size)
    }

    @Test
    fun `malformed unknown and wrong-operation requests never reach provider`() {
        val harness = harness()

        val malformed = harness.callbacks.ankiVerifyTarget("{")
        assertTrue(malformed.contains("\"type\":\"anki.error\""))
        assertTrue(malformed.contains("\"code\":\"invalid_request\""))
        assertTrue(malformed.contains("run_00000000000000000000000000000000"))

        val unknown =
            verifyEnvelope().replace(
                "\"requiredFields\":[\"Expression\"]",
                "\"requiredFields\":[\"Expression\"],\"unknown\":true",
            )
        val correlated = harness.callbacks.ankiVerifyTarget(unknown)
        assertTrue(correlated.contains("\"runId\":\"$RUN_ID\""))
        assertTrue(correlated.contains("\"requestId\":\"$REQUEST_ID\""))
        assertTrue(correlated.contains("\"code\":\"invalid_request\""))

        val wrongOperation = harness.callbacks.ankiVerifyTarget(releaseEnvelope())
        assertTrue(wrongOperation.contains("\"code\":\"invalid_request\""))
        assertTrue(harness.gateway.queries.isEmpty())
    }

    @Test
    fun `unknown and cancelled runs fail before provider while unknown release is absent`() {
        val unknown = harness(register = false)
        val unknownResult = unknown.callbacks.ankiVerifyTarget(verifyEnvelope())
        assertTrue(unknownResult.contains("\"code\":\"invalid_request\""))
        assertTrue(unknown.gateway.queries.isEmpty())
        assertTrue(unknown.callbacks.ankiReleaseRunState(releaseEnvelope()).contains("\"state\":\"absent\""))

        val cancellation = MutableAnkiCancellation().also(MutableAnkiCancellation::cancel)
        val cancelled = harness(cancellation = cancellation)
        val cancelledResult = cancelled.callbacks.ankiScanFirstFields(knownEnvelope())
        assertTrue(cancelledResult.contains("\"code\":\"cancelled\""))
        assertTrue(cancelled.gateway.queries.isEmpty())
    }

    @Test
    fun `availability permission loss query failure and timeout keep stable taxonomy`() {
        val statuses =
            listOf(
                ProviderAccessStatus.Absent to "provider_unavailable",
                ProviderAccessStatus.ApiDisabled to "api_disabled",
                ProviderAccessStatus.Incompatible(1) to "api_disabled",
                ProviderAccessStatus.PermissionRequired to "permission_required",
            )
        for ((status, code) in statuses) {
            val harness = harness()
            harness.gateway.status = status
            val result = harness.callbacks.ankiScanFirstFields(knownEnvelope())
            assertTrue(result.contains("\"code\":\"$code\""))
            assertTrue(result.contains("\"runId\":\"$RUN_ID\""))
            assertTrue(harness.gateway.queries.isEmpty())
        }

        val failures =
            listOf(
                ProviderFailureKind.API_DISABLED to "api_disabled",
                ProviderFailureKind.PERMISSION_REQUIRED to "permission_required",
                ProviderFailureKind.PROVIDER_UNAVAILABLE to "provider_unavailable",
                ProviderFailureKind.QUERY_FAILED to "query_failed",
                ProviderFailureKind.TIMEOUT to "timeout",
                ProviderFailureKind.CANCELLED to "cancelled",
            )
        for ((kind, code) in failures) {
            val harness = harness()
            harness.gateway.queryHandler = { _, _ -> throw ProviderGatewayException(kind) }
            val result = harness.callbacks.ankiScanFirstFields(knownEnvelope())
            assertTrue("$kind should map to $code", result.contains("\"code\":\"$code\""))

            val lazyHarness = harness()
            lazyHarness.gateway.queryHandler = { query, _ ->
                FakeProviderCursor(
                    projection = query.projection,
                    rows = emptyList(),
                    beforeMove = { throw ProviderGatewayException(kind) },
                )
            }
            val lazyResult = lazyHarness.callbacks.ankiScanFirstFields(knownEnvelope())
            assertTrue(
                "lazy $kind should map to $code",
                lazyResult.contains("\"code\":\"$code\""),
            )
        }
    }

    @Test
    fun `known fields enforce exact aggregate bound and invalid unicode maps to query failure`() {
        fun resultFor(fields: List<String>): String {
            val harness = harness()
            harness.gateway.queryHandler = knownRowsHandler(fields)
            return harness.callbacks.ankiScanFirstFields(knownEnvelope())
        }

        val exact = List(4) { "x".repeat(65_536) }
        assertTrue(resultFor(exact).contains("\"type\":\"anki.scanfirstfields.result\""))
        assertTrue(resultFor(exact + "x").contains("\"code\":\"query_failed\""))
        assertTrue(resultFor(listOf("\uD800")).contains("\"code\":\"query_failed\""))
    }

    @Test
    fun `read-only dispatcher explicitly rejects both mutation callbacks`() {
        val harness = harness()

        val media = harness.callbacks.ankiStoreMedia(storeMediaEnvelope())
        val notes = harness.callbacks.ankiCreateNotes(createNotesEnvelope())

        assertTrue(media.contains("\"operation\":\"storeMedia\""))
        assertTrue(media.contains("\"code\":\"unsupported_operation\""))
        assertTrue(notes.contains("\"operation\":\"createNotes\""))
        assertTrue(notes.contains("\"code\":\"unsupported_operation\""))
        assertTrue(harness.gateway.queries.isEmpty())
    }

    @Test
    fun `worker guard runs before decode for valid malformed wrong type BOM and oversized input`() {
        var guardCalls = 0
        val harness =
            harness(
                guard =
                    WorkerThreadGuard {
                        guardCalls += 1
                        error("main thread")
                    },
            )
        val requests =
            listOf(
                verifyEnvelope(),
                "{",
                releaseEnvelope(),
                "\uFEFF${verifyEnvelope()}",
                "x".repeat(65_537),
            )

        val results = requests.map(harness.callbacks::ankiVerifyTarget)

        assertEquals(requests.size, guardCalls)
        assertTrue(results.all { it.contains("\"code\":\"internal_error\"") })
        assertTrue(results.all { it.contains("run_00000000000000000000000000000000") })
        assertTrue(harness.gateway.queries.isEmpty())
    }

    @Test
    fun `owned callback keeps its owner through canonical response encoding`() {
        val cleanup = mutableListOf<Set<String>?>()
        val registry = AnkiRunStateRegistry { _, ids -> cleanup += ids }
        val enteredEncoding = CountDownLatch(1)
        val finishEncoding = CountDownLatch(1)
        val encoder =
            AnkiProviderResponseEncoder { response, request ->
                enteredEncoding.countDown()
                check(finishEncoding.await(5, TimeUnit.SECONDS))
                AnkiJsonCodec.encodeResponse(response, request)
            }
        val harness = harness(registry = registry, encoder = encoder)
        harness.gateway.queryHandler = knownRowsHandler(emptyList())
        var result = ""
        val worker = thread { result = harness.callbacks.ankiScanFirstFields(knownEnvelope()) }
        assertTrue(enteredEncoding.await(5, TimeUnit.SECONDS))

        assertEquals(
            com.ankiminer.android.anki.protocol.ReleaseState.DEFERRED,
            registry.release(RUN_ID, true),
        )
        finishEncoding.countDown()
        worker.join(5_000)

        assertFalse(worker.isAlive)
        assertTrue(result.contains("\"type\":\"anki.scanfirstfields.result\""))
        assertEquals(listOf(emptySet<String>()), cleanup)
    }

    @Test
    fun `encoding failure racing first true release forces abandonment`() {
        val cleanup = mutableListOf<Set<String>?>()
        val registry = AnkiRunStateRegistry { _, ids -> cleanup += ids }
        val enteredEncoding = CountDownLatch(1)
        val failEncoding = CountDownLatch(1)
        var calls = 0
        val encoder =
            AnkiProviderResponseEncoder { response, request ->
                calls += 1
                if (calls == 1) {
                    enteredEncoding.countDown()
                    check(failEncoding.await(5, TimeUnit.SECONDS))
                    error("injected encode failure")
                }
                AnkiJsonCodec.encodeResponse(response, request)
            }
        val harness = harness(registry = registry, encoder = encoder)
        harness.gateway.queryHandler = knownRowsHandler(emptyList())
        var result = ""
        val worker = thread { result = harness.callbacks.ankiScanFirstFields(knownEnvelope()) }
        assertTrue(enteredEncoding.await(5, TimeUnit.SECONDS))
        assertEquals(
            com.ankiminer.android.anki.protocol.ReleaseState.DEFERRED,
            registry.release(RUN_ID, true),
        )
        failEncoding.countDown()
        worker.join(5_000)

        assertFalse(worker.isAlive)
        assertTrue(result.contains("\"code\":\"internal_error\""))
        assertEquals(2, calls)
        assertEquals(listOf(null), cleanup)
    }

    @Test
    fun `release during a provider read is deferred then cleans once and rejects admission`() {
        val cleanup = mutableListOf<Set<String>?>()
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val registry = AnkiRunStateRegistry { _, ids -> cleanup += ids }
        val harness = harness(registry = registry)
        harness.gateway.queryHandler = { query, _ ->
            entered.countDown()
            unblock.await(5, TimeUnit.SECONDS)
            FakeProviderCursor(query.projection, emptyList())
        }
        var scanResult = ""
        val scanThread = thread {
            scanResult = harness.callbacks.ankiScanFirstFields(knownEnvelope())
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        val release = harness.callbacks.ankiReleaseRunState(releaseEnvelope())
        val rejected = harness.callbacks.ankiScanFirstFields(knownEnvelope(SECOND_REQUEST_ID))
        assertTrue(release.contains("\"state\":\"deferred\""))
        assertTrue(rejected.contains("\"code\":\"cancelled\""))
        unblock.countDown()
        scanThread.join(5_000)
        assertFalse(scanThread.isAlive)
        assertTrue(scanResult.contains("\"firstFields\":[]"))
        assertEquals(listOf(emptySet<String>()), cleanup)
        assertTrue(harness.callbacks.ankiReleaseRunState(releaseEnvelope(SECOND_REQUEST_ID)).contains("\"state\":\"absent\""))
    }

    @Test
    fun `fallback release never acknowledges terminal state`() {
        val cleanup = mutableListOf<Set<String>?>()
        val registry = AnkiRunStateRegistry { _, ids -> cleanup += ids }
        val harness = harness(registry = registry)

        assertEquals(
            com.ankiminer.android.anki.protocol.ReleaseState.RELEASED,
            harness.callbacks.releaseRunStateFallback(RUN_ID),
        )
        assertEquals(listOf(null), cleanup)
    }

    private fun harness(
        register: Boolean = true,
        cancellation: AnkiCancellation = AnkiCancellation.NONE,
        guard: WorkerThreadGuard = WorkerThreadGuard { },
        registry: AnkiRunStateRegistry = AnkiRunStateRegistry(),
        encoder: AnkiProviderResponseEncoder =
            AnkiProviderResponseEncoder(AnkiJsonCodec::encodeResponse),
    ): Harness {
        val gateway = FakeAnkiProviderGateway()
        val reads = AnkiProviderReadService(gateway, registry, OpaqueTokenFactory { prefix -> "$prefix${"a".repeat(32)}" })
        val callbacks = AnkiProviderCallbacks(registry, reads, guard, encoder)
        if (register) assertTrue(callbacks.registerRun(RUN_ID, cancellation))
        return Harness(gateway, callbacks)
    }

    private data class Harness(
        val gateway: FakeAnkiProviderGateway,
        val callbacks: AnkiProviderCallbacks,
    )

    private fun targetQueryHandler(): (ProviderQuery, AnkiCancellation) -> ProviderCursor? =
        { query, _ ->
            when (query.endpoint) {
                ProviderEndpoint.MODELS -> FakeProviderCursor(query.projection, listOf(modelRow()))
                ProviderEndpoint.MODEL_TEMPLATES -> FakeProviderCursor(query.projection, listOf(templateRow()))
                ProviderEndpoint.DECKS -> FakeProviderCursor(query.projection, listOf(deckRow()))
                else -> error("unexpected query $query")
            }
        }

    private fun knownRowsHandler(fields: List<String>): (ProviderQuery, AnkiCancellation) -> ProviderCursor? =
        { query, _ ->
            when {
                query.endpoint == ProviderEndpoint.NOTES_V2 && query.selection == null ->
                    FakeProviderCursor(
                        query.projection,
                        fields.indices.map { index ->
                            mapOf(ProviderColumn.NOTE_ID to integer(index + 1L))
                        },
                    )
                query.selection is ProviderSelection.NoteIds ->
                    FakeProviderCursor(
                        query.projection,
                        fields.mapIndexed { index, field ->
                            mapOf(
                                ProviderColumn.NOTE_ID to integer(index + 1L),
                                ProviderColumn.NOTE_FIELDS to text(field),
                            )
                        },
                    )
                else -> error("unexpected query $query")
            }
        }

    private fun verifyEnvelope(): String =
        envelope(
            "anki.verifytarget.request",
            """{"runId":"$RUN_ID","requestId":"$REQUEST_ID","deckName":"Mining","modelName":"Mining","requiredFields":["Expression"]}""",
        )

    private fun knownEnvelope(requestId: String = REQUEST_ID): String =
        envelope(
            "anki.scanfirstfields.request",
            """{"runId":"$RUN_ID","requestId":"$requestId","scope":{"kind":"knownVocabulary","excludedDecks":[],"cursor":null,"limits":{"maxScannedNotes":256,"maxTotalScannedNotes":100000,"maxItems":256,"maxItemUtf8Bytes":65536,"maxTotalUtf8Bytes":262144}}}""",
        )

    private fun storeMediaEnvelope(): String =
        envelope(
            "anki.storemedia.request",
            """{"runId":"$RUN_ID","requestId":"$REQUEST_ID","assets":[{"assetId":"asset_11111111111111111111111111111111","sourcePath":"/tmp/clip.mp3","preferredName":"clip","requestedFilename":"clip.mp3","purpose":"card","mediaKind":"audio","expectedSizeBytes":3,"expectedSha256":"${"a".repeat(64)}"}],"limits":{"maxAssets":50,"maxAssetBytes":67108864,"maxTotalBytes":67108864}}""",
        )

    private fun createNotesEnvelope(): String =
        envelope(
            "anki.createnotes.request",
            """{"runId":"$RUN_ID","requestId":"$REQUEST_ID","deckName":"Mining","modelName":"Mining","firstFieldName":"Expression","baselineToken":"baseline_11111111111111111111111111111111","duplicateScope":{"kind":"collection","limits":{"maxNoteIdsPerCandidate":100,"maxTotalNoteIds":1000}},"limits":{"maxNotes":100,"maxFieldsPerNote":64,"maxCardsPerNote":64,"maxFieldNameUtf8Bytes":256,"maxFieldValueUtf8Bytes":98304,"maxTagsPerNote":64,"maxTagUtf8Bytes":256,"maxTagsUtf8BytesPerNote":8192,"maxNoteContentUtf8Bytes":131072,"maxTotalContentUtf8Bytes":393216,"maxMediaBindingsPerNote":8000,"maxMediaBindingsTotal":8000,"maxEnvelopeUtf8Bytes":524288},"notes":[{"clientNoteId":"note_11111111111111111111111111111111","fields":{"Expression":"cat"},"tags":[],"duplicateCandidate":{"key":"cat","firstField":"cat","occurrence":0},"mediaBindings":[]}]}""",
        )

    private fun releaseEnvelope(requestId: String = REQUEST_ID): String =
        envelope(
            "anki.releaserunstate.request",
            """{"runId":"$RUN_ID","requestId":"$requestId","acknowledgeTerminalResponses":true}""",
        )

    private fun envelope(type: String, payload: String): String =
        """{"schemaVersion":1,"type":"$type","payload":$payload}"""

    private companion object {
        const val RUN_ID = "run_11111111111111111111111111111111"
        const val REQUEST_ID = "anki_11111111111111111111111111111111"
        const val SECOND_REQUEST_ID = "anki_22222222222222222222222222222222"
    }
}
