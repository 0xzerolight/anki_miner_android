package com.ankiminer.android.anki.provider

import com.ankiminer.android.anki.protocol.AnkiJsonCodec
import com.ankiminer.android.anki.protocol.AnkiOperation
import com.ankiminer.android.anki.protocol.StoreMediaRequest
import com.ankiminer.android.anki.protocol.StoreMediaResult
import com.ankiminer.android.anki.protocol.StoredMedia
import com.ankiminer.android.anki.protocol.VerifyTargetResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AnkiProviderCallbacksTest {
    @Test
    fun `registration checks worker and closed recovery gate before registry admission`() {
        var guardCalls = 0
        var recoveryCalls = 0
        var open = false
        val gate =
            object : AnkiStartupRecoveryGate {
                override fun ensureRecovered() {
                    recoveryCalls += 1
                    open = true
                }

                override fun isOpen(): Boolean = open
            }
        val admitted =
            harness(
                register = false,
                guard = WorkerThreadGuard { guardCalls += 1 },
                startupRecoveryGate = gate,
            )

        assertTrue(admitted.callbacks.registerRun(RUN_ID))
        assertEquals(1, guardCalls)
        assertEquals(1, recoveryCalls)

        val rejectedRegistry = AnkiRunStateRegistry()
        val rejected =
            harness(
                register = false,
                registry = rejectedRegistry,
                guard = WorkerThreadGuard { error("main thread") },
            )
        assertFalse(rejected.callbacks.registerRun(RUN_ID))
        assertEquals(
            com.ankiminer.android.anki.protocol.ReleaseState.ABSENT,
            rejectedRegistry.release(RUN_ID, true),
        )
    }

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
                ProviderFailureKind.MUTATION_FAILED to "write_failed",
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
    fun `note mutation remains unavailable until its durable service is wired`() {
        val harness = harness()

        val notes = harness.callbacks.ankiCreateNotes(createNotesEnvelope())

        assertTrue(notes.contains("\"operation\":\"createNotes\""))
        assertTrue(notes.contains("\"code\":\"unsupported_operation\""))
        assertTrue(harness.gateway.queries.isEmpty())
    }

    @Test
    fun `media callback passes the exact owner and request then admits after exact encoding`() {
        val cleanup = mutableListOf<Set<String>?>()
        val registry = AnkiRunStateRegistry { _, ids -> cleanup += ids }
        val acknowledgement = MediaAcknowledgement(ASSET_ID, "clip.mp3", 41L)
        val outcome = storedMediaOutcome(listOf(acknowledgement))
        var callbackOwner: AnkiRunStateRegistry.RunOwner? = null
        var callbackRequest: StoreMediaRequest? = null
        val media = FakeMediaMutationService { owner, request ->
            callbackOwner = owner
            callbackRequest = request
            outcome
        }
        var encodeCalls = 0
        val encoder =
            AnkiProviderResponseEncoder { response, request ->
                encodeCalls += 1
                assertEquals(outcome.result, response)
                assertFalse(outcome.result === response)
                assertTrue(callbackOwner != null)
                assertSame(callbackRequest, request)
                assertNull(registry.mediaAcknowledgement(requireNotNull(callbackOwner), ASSET_ID))
                AnkiJsonCodec.encodeResponse(response, request)
            }
        val harness = harness(registry = registry, encoder = encoder, mediaMutations = media)
        val expectedRequest =
            AnkiJsonCodec.decodeRequest(storeMediaEnvelope(), AnkiOperation.STORE_MEDIA)
                as StoreMediaRequest

        val encoded = harness.callbacks.ankiStoreMedia(storeMediaEnvelope())

        assertEquals(expectedRequest, callbackRequest)
        assertEquals(RUN_ID, callbackOwner?.runId)
        assertEquals(1, media.calls)
        assertEquals(1, encodeCalls)
        assertTrue(encoded.contains("\"type\":\"anki.storemedia.result\""))
        assertTrue(encoded.contains("\"status\":\"stored\""))
        registry.withOwner(RUN_ID) { observer ->
            assertEquals(acknowledgement, registry.mediaAcknowledgement(observer, ASSET_ID))
        }
        assertEquals(
            com.ankiminer.android.anki.protocol.ReleaseState.RELEASED,
            registry.release(RUN_ID, true),
        )
        assertEquals(listOf(setOf(REQUEST_ID)), cleanup)
    }

    @Test
    fun `media admission mismatches quarantine without installing any acknowledgement`() {
        val valid = MediaAcknowledgement(ASSET_ID, "clip.mp3", 41L)
        val cases =
            listOf(
                Triple("missing", storeMediaEnvelope(), storedMediaOutcome(emptyList())),
                Triple(
                    "extra",
                    storeMediaEnvelope(),
                    storedMediaOutcome(
                        listOf(valid, MediaAcknowledgement(OTHER_ASSET_ID, "other.mp3", 42L)),
                    ),
                ),
                Triple(
                    "name mismatch",
                    storeMediaEnvelope(),
                    storedMediaOutcome(listOf(MediaAcknowledgement(ASSET_ID, "clip_renamed.mp3", 41L))),
                ),
                Triple("duplicate", storeMediaEnvelope(), storedMediaOutcome(listOf(valid, valid))),
                Triple(
                    "non-positive claim",
                    storeMediaEnvelope(),
                    storedMediaOutcome(listOf(valid.copy(durableClaimId = 0L))),
                ),
                Triple(
                    "duplicate claim",
                    storeMediaEnvelope(includeSecondAsset = true),
                    storedTwoMediaOutcome(
                        listOf(
                            valid,
                            MediaAcknowledgement(OTHER_ASSET_ID, "image.webp", valid.durableClaimId),
                        ),
                    ),
                ),
            )

        cases.forEach { (label, rawRequest, outcome) ->
            val cleanup = mutableListOf<Set<String>?>()
            val registry = AnkiRunStateRegistry { _, ids -> cleanup += ids }
            val media = FakeMediaMutationService { _, _ -> outcome }
            var mediaEncodes = 0
            val encoder =
                AnkiProviderResponseEncoder { response, request ->
                    if (response is StoreMediaResult) {
                        mediaEncodes += 1
                        assertEquals(outcome.result, response)
                    }
                    AnkiJsonCodec.encodeResponse(response, request)
                }
            val harness = harness(registry = registry, mediaMutations = media, encoder = encoder)

            registry.withOwner(RUN_ID) { observer ->
                val encoded = harness.callbacks.ankiStoreMedia(rawRequest)
                assertTrue("$label should fail safely", encoded.contains("\"code\":\"internal_error\""))
                assertNull(registry.mediaAcknowledgement(observer, ASSET_ID))
                assertNull(registry.mediaAcknowledgement(observer, OTHER_ASSET_ID))
            }
            assertEquals(label, 1, mediaEncodes)
            assertEquals(
                label,
                com.ankiminer.android.anki.protocol.ReleaseState.RELEASED,
                registry.release(RUN_ID, true),
            )
            assertEquals(label, listOf(null), cleanup)
        }
    }

    @Test
    fun `media response encoding failure quarantines and admits no durable state`() {
        val cleanup = mutableListOf<Set<String>?>()
        val registry = AnkiRunStateRegistry { _, ids -> cleanup += ids }
        val acknowledgement = MediaAcknowledgement(ASSET_ID, "clip.mp3", 41L)
        val outcome = storedMediaOutcome(listOf(acknowledgement))
        var encodeCalls = 0
        val encoder =
            AnkiProviderResponseEncoder { response, request ->
                encodeCalls += 1
                if (response is StoreMediaResult) {
                    assertEquals(outcome.result, response)
                    assertFalse(outcome.result === response)
                    error("injected media encoding failure")
                }
                AnkiJsonCodec.encodeResponse(response, request)
            }
        val harness =
            harness(
                registry = registry,
                encoder = encoder,
                mediaMutations = FakeMediaMutationService { _, _ -> outcome },
            )

        registry.withOwner(RUN_ID) { observer ->
            val encoded = harness.callbacks.ankiStoreMedia(storeMediaEnvelope())
            assertTrue(encoded.contains("\"code\":\"internal_error\""))
            assertNull(registry.mediaAcknowledgement(observer, ASSET_ID))
        }
        assertEquals(2, encodeCalls)
        assertEquals(
            com.ankiminer.android.anki.protocol.ReleaseState.RELEASED,
            registry.release(RUN_ID, true),
        )
        assertEquals(listOf(null), cleanup)
    }

    @Test
    fun `release between media encoding and admission installs neither response nor claim`() {
        val cleanup = mutableListOf<Set<String>?>()
        val registry = AnkiRunStateRegistry { _, ids -> cleanup += ids }
        val acknowledgement = MediaAcknowledgement(ASSET_ID, "clip.mp3", 41L)
        val outcome = storedMediaOutcome(listOf(acknowledgement))
        val enteredEncoding = CountDownLatch(1)
        val finishEncoding = CountDownLatch(1)
        var encodeCalls = 0
        val encoder =
            AnkiProviderResponseEncoder { response, request ->
                encodeCalls += 1
                if (response is StoreMediaResult) {
                    assertEquals(outcome.result, response)
                    assertFalse(outcome.result === response)
                    enteredEncoding.countDown()
                    check(finishEncoding.await(5, TimeUnit.SECONDS))
                }
                AnkiJsonCodec.encodeResponse(response, request)
            }
        val harness =
            harness(
                registry = registry,
                encoder = encoder,
                mediaMutations = FakeMediaMutationService { _, _ -> outcome },
            )
        var encoded = ""

        registry.withOwner(RUN_ID) { observer ->
            val callback = thread { encoded = harness.callbacks.ankiStoreMedia(storeMediaEnvelope()) }
            assertTrue(enteredEncoding.await(5, TimeUnit.SECONDS))
            assertEquals(
                com.ankiminer.android.anki.protocol.ReleaseState.DEFERRED,
                registry.release(RUN_ID, true),
            )
            finishEncoding.countDown()
            callback.join(5_000)

            assertFalse(callback.isAlive)
            assertTrue(encoded.contains("\"code\":\"internal_error\""))
            assertNull(registry.mediaAcknowledgement(observer, ASSET_ID))
        }
        assertEquals(2, encodeCalls)
        assertEquals(listOf(null), cleanup)
    }

    @Test
    fun `worker guard runs before decode for valid malformed wrong type BOM and oversized input`() {
        var guardCalls = 0
        val registry = AnkiRunStateRegistry()
        assertTrue(registry.register(RUN_ID, AnkiCancellation.NONE))
        val harness =
            harness(
                register = false,
                registry = registry,
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
    fun `verify response encoding failure quarantines a previously installed target`() {
        val cleanup = mutableListOf<Set<String>?>()
        val registry = AnkiRunStateRegistry { _, ids -> cleanup += ids }
        val encoder =
            AnkiProviderResponseEncoder { response, request ->
                if (request.requestId == SECOND_REQUEST_ID) error("injected verify encode failure")
                AnkiJsonCodec.encodeResponse(response, request)
            }
        val harness = harness(registry = registry, encoder = encoder)
        harness.gateway.queryHandler = targetQueryHandler()
        assertTrue(harness.callbacks.ankiVerifyTarget(verifyEnvelope()).contains("\"deckId\":20"))
        registry.withOwner(RUN_ID) { owner -> assertTrue(registry.target(owner) != null) }

        val failed = harness.callbacks.ankiVerifyTarget(verifyEnvelope(SECOND_REQUEST_ID))

        assertTrue(failed.contains("\"code\":\"internal_error\""))
        val queryCount = harness.gateway.queries.size
        val quarantined = harness.callbacks.ankiVerifyTarget(verifyEnvelope(THIRD_REQUEST_ID))
        assertTrue(quarantined.contains("\"code\":\"invalid_request\""))
        assertEquals(queryCount, harness.gateway.queries.size)
        assertThrows(RunStateConflictException::class.java) {
            registry.withOwner(RUN_ID) { }
        }
        assertEquals(
            com.ankiminer.android.anki.protocol.ReleaseState.RELEASED,
            registry.release(RUN_ID, true),
        )
        assertEquals(listOf(null), cleanup)
    }

    @Test
    fun `release between target encoding and atomic admission installs neither target nor receipt`() {
        val cleanup = mutableListOf<Set<String>?>()
        val registry = AnkiRunStateRegistry { _, ids -> cleanup += ids }
        val firstEncoding = CountDownLatch(1)
        val allowFirstEncoding = CountDownLatch(1)
        val fallbackEncoding = CountDownLatch(1)
        val allowFallbackEncoding = CountDownLatch(1)
        var encodingCalls = 0
        val encoder =
            AnkiProviderResponseEncoder { response, request ->
                encodingCalls += 1
                when (encodingCalls) {
                    1 -> {
                        firstEncoding.countDown()
                        check(allowFirstEncoding.await(5, TimeUnit.SECONDS))
                    }
                    2 -> {
                        fallbackEncoding.countDown()
                        check(allowFallbackEncoding.await(5, TimeUnit.SECONDS))
                    }
                }
                AnkiJsonCodec.encodeResponse(response, request)
            }
        val harness = harness(registry = registry, encoder = encoder)
        harness.gateway.queryHandler = targetQueryHandler()
        val observerOwner = AtomicReference<AnkiRunStateRegistry.RunOwner?>()
        val observerReady = CountDownLatch(1)
        val finishObserver = CountDownLatch(1)
        val observer =
            thread {
                registry.withOwner(RUN_ID) { owner ->
                    observerOwner.set(owner)
                    observerReady.countDown()
                    check(finishObserver.await(5, TimeUnit.SECONDS))
                }
            }
        assertTrue(observerReady.await(5, TimeUnit.SECONDS))
        var result = ""
        val verify = thread { result = harness.callbacks.ankiVerifyTarget(verifyEnvelope()) }
        assertTrue(firstEncoding.await(5, TimeUnit.SECONDS))

        assertEquals(
            com.ankiminer.android.anki.protocol.ReleaseState.DEFERRED,
            registry.release(RUN_ID, true),
        )
        allowFirstEncoding.countDown()
        assertTrue(fallbackEncoding.await(5, TimeUnit.SECONDS))
        assertTrue(registry.target(requireNotNull(observerOwner.get())) == null)
        allowFallbackEncoding.countDown()
        verify.join(5_000)
        assertFalse(verify.isAlive)
        assertTrue(result.contains("\"code\":\"internal_error\""))

        finishObserver.countDown()
        observer.join(5_000)
        assertFalse(observer.isAlive)
        assertEquals(2, encodingCalls)
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
        mediaMutations: FakeMediaMutationService = FakeMediaMutationService(),
        encoder: AnkiProviderResponseEncoder =
            AnkiProviderResponseEncoder(AnkiJsonCodec::encodeResponse),
        startupRecoveryGate: AnkiStartupRecoveryGate = OpenAnkiStartupRecoveryGate,
    ): Harness {
        val gateway = FakeAnkiProviderGateway()
        val reads = AnkiProviderReadService(gateway, registry, OpaqueTokenFactory { prefix -> "$prefix${"a".repeat(32)}" })
        val targetVerifier =
            AnkiTargetVerifier { owner, _, request ->
                val target = reads.readExistingTarget(owner, request)
                TargetVerificationOutcome(
                    response =
                        VerifyTargetResult(
                            runId = request.runId,
                            requestId = request.requestId,
                            deckId = target.deck.id,
                            modelId = target.model.id,
                            fieldNames = target.model.fieldNames,
                            deckCreated = false,
                        ),
                    durable = true,
                    targetForAdmission = target,
                    replayed = false,
                )
            }
        val callbacks =
            AnkiProviderCallbacks(
                registry = registry,
                reads = reads,
                targetVerifier = targetVerifier,
                mediaMutations = mediaMutations,
                workerThreadGuard = guard,
                startupRecoveryGate = startupRecoveryGate,
                responseEncoder = encoder,
            )
        if (register) assertTrue(callbacks.registerRun(RUN_ID, cancellation))
        return Harness(gateway, callbacks)
    }

    private data class Harness(
        val gateway: FakeAnkiProviderGateway,
        val callbacks: AnkiProviderCallbacks,
    )

    private fun storedMediaOutcome(acknowledgements: List<MediaAcknowledgement>) =
        StoreMediaMutationOutcome(
            result =
                StoreMediaResult(
                    runId = RUN_ID,
                    requestId = REQUEST_ID,
                    results = listOf(StoredMedia(ASSET_ID, "clip.mp3")),
                    error = null,
                ),
            mediaAcknowledgements = acknowledgements,
            replayed = false,
        )

    private fun storedTwoMediaOutcome(acknowledgements: List<MediaAcknowledgement>) =
        StoreMediaMutationOutcome(
            result =
                StoreMediaResult(
                    runId = RUN_ID,
                    requestId = REQUEST_ID,
                    results =
                        listOf(
                            StoredMedia(ASSET_ID, "clip.mp3"),
                            StoredMedia(OTHER_ASSET_ID, "image.webp"),
                        ),
                    error = null,
                ),
            mediaAcknowledgements = acknowledgements,
            replayed = false,
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

    private fun verifyEnvelope(requestId: String = REQUEST_ID): String =
        envelope(
            "anki.verifytarget.request",
            """{"runId":"$RUN_ID","requestId":"$requestId","deckName":"Mining","modelName":"Mining","requiredFields":["Expression"]}""",
        )

    private fun knownEnvelope(requestId: String = REQUEST_ID): String =
        envelope(
            "anki.scanfirstfields.request",
            """{"runId":"$RUN_ID","requestId":"$requestId","scope":{"kind":"knownVocabulary","excludedDecks":[],"cursor":null,"limits":{"maxScannedNotes":256,"maxTotalScannedNotes":100000,"maxItems":256,"maxItemUtf8Bytes":65536,"maxTotalUtf8Bytes":262144}}}""",
        )

    private fun storeMediaEnvelope(includeSecondAsset: Boolean = false): String {
        val secondAsset =
            if (includeSecondAsset) {
                "," +
                    """{"assetId":"$OTHER_ASSET_ID","sourcePath":"/tmp/image.webp","preferredName":"image","requestedFilename":"image.webp","purpose":"card","mediaKind":"image","expectedSizeBytes":3,"expectedSha256":"${"b".repeat(64)}"}"""
            } else {
                ""
            }
        return envelope(
            "anki.storemedia.request",
            """{"runId":"$RUN_ID","requestId":"$REQUEST_ID","assets":[{"assetId":"$ASSET_ID","sourcePath":"/tmp/clip.mp3","preferredName":"clip","requestedFilename":"clip.mp3","purpose":"card","mediaKind":"audio","expectedSizeBytes":3,"expectedSha256":"${"a".repeat(64)}"}$secondAsset],"limits":{"maxAssets":50,"maxAssetBytes":67108864,"maxTotalBytes":67108864}}""",
        )
    }

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
        const val THIRD_REQUEST_ID = "anki_33333333333333333333333333333333"
        const val ASSET_ID = "asset_11111111111111111111111111111111"
        const val OTHER_ASSET_ID = "asset_22222222222222222222222222222222"
    }
}

private class FakeMediaMutationService(
    var handler: (AnkiRunStateRegistry.RunOwner, StoreMediaRequest) -> StoreMediaMutationOutcome =
        { _, _ -> error("Unexpected media mutation") },
) : MediaMutationService {
    var calls: Int = 0
        private set

    override fun store(
        owner: AnkiRunStateRegistry.RunOwner,
        request: StoreMediaRequest,
    ): StoreMediaMutationOutcome {
        calls += 1
        return handler(owner, request)
    }
}
