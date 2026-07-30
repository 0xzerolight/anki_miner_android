package com.ankiminer.android.anki.provider

import com.ankiminer.android.anki.journal.AlignedResult
import com.ankiminer.android.anki.journal.ChildRecord
import com.ankiminer.android.anki.journal.ChildState
import com.ankiminer.android.anki.journal.JournalCorruptionException
import com.ankiminer.android.anki.journal.JournalError
import com.ankiminer.android.anki.journal.JournalErrorCode
import com.ankiminer.android.anki.journal.JournalRequest
import com.ankiminer.android.anki.journal.JournalResponse
import com.ankiminer.android.anki.journal.MediaAdmissionRefusal
import com.ankiminer.android.anki.journal.MediaAdmissionViolation
import com.ankiminer.android.anki.journal.MediaClaimRecord
import com.ankiminer.android.anki.journal.MediaClaimState
import com.ankiminer.android.anki.journal.MediaKind as JournalMediaKind
import com.ankiminer.android.anki.journal.MediaPromotion
import com.ankiminer.android.anki.journal.MediaPurpose as JournalMediaPurpose
import com.ankiminer.android.anki.journal.MediaReservationDraft
import com.ankiminer.android.anki.journal.MediaReservationRecord
import com.ankiminer.android.anki.journal.MediaReservationState
import com.ankiminer.android.anki.journal.MutationCommand
import com.ankiminer.android.anki.journal.ParentKey
import com.ankiminer.android.anki.journal.ProviderAttempt
import com.ankiminer.android.anki.journal.ProviderReceipt
import com.ankiminer.android.anki.journal.ReplayResult
import com.ankiminer.android.anki.journal.StagingRecord
import com.ankiminer.android.anki.journal.StagingState
import com.ankiminer.android.anki.protocol.AnkiErrorCode
import com.ankiminer.android.anki.protocol.FailedMedia
import com.ankiminer.android.anki.protocol.MediaAsset
import com.ankiminer.android.anki.protocol.MediaKind
import com.ankiminer.android.anki.protocol.MediaPurpose
import com.ankiminer.android.anki.protocol.NotAttemptedMedia
import com.ankiminer.android.anki.protocol.StoreMediaRequest
import com.ankiminer.android.anki.protocol.StoredMedia
import com.ankiminer.android.anki.protocol.UncertainMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalBackedMediaMutationServiceTest {
    @Test
    fun `result-ready replay reconstructs exact stored acknowledgements without side effects`() {
        val request = request(2)
        val fixture = Fixture(request)
        fixture.journal.installReplay(
            request,
            rows =
                listOf(
                    AlignedResult.MediaStored(0, request.assets[0].assetId, "clip0.mp3", "accepted"),
                    AlignedResult.MediaFailed(1, request.assets[1].assetId, mediaFailure(), "stage failed"),
                ),
            claims =
                listOf(
                    claim(
                        id = 9001,
                        request = request,
                        asset = request.assets[0],
                        actualFilename = "clip0.mp3",
                        state = MediaClaimState.ATTACHED_VERIFIED,
                    ),
                ),
        )

        val outcome = fixture.execute()

        assertTrue(outcome.replayed)
        assertEquals(
            listOf(StoredMedia(request.assets[0].assetId, "clip0.mp3"), FailedMedia(request.assets[1].assetId, mediaFailure().toProtocol())),
            outcome.result.results,
        )
        assertEquals(
            listOf(MediaAcknowledgement(request.assets[0].assetId, "clip0.mp3", 9001)),
            outcome.mediaAcknowledgements,
        )
        assertEquals(listOf("replay"), fixture.events)
        assertEquals(0, fixture.staging.stageCalls)
        assertEquals(0, fixture.provider.storeCalls)
    }

    @Test
    fun `replay fails closed when a stored row loses or conflicts with its exact claim`() {
        val request = request(1)
        val missing = Fixture(request)
        missing.journal.installReplay(
            request,
            rows = listOf(AlignedResult.MediaStored(0, request.assets[0].assetId, "clip0.mp3")),
            claims = emptyList(),
        )
        assertThrows(IllegalStateException::class.java) { missing.execute() }

        val conflicting = Fixture(request)
        conflicting.journal.installReplay(
            request,
            rows = listOf(AlignedResult.MediaStored(0, request.assets[0].assetId, "clip0.mp3")),
            claims =
                listOf(
                    claim(
                        9002,
                        request,
                        request.assets[0].copy(expectedSha256 = "f".repeat(64)),
                        "clip0.mp3",
                        MediaClaimState.STORED,
                    ),
                ),
        )
        assertThrows(IllegalStateException::class.java) { conflicting.execute() }
    }

    @Test
    fun `staging is asked for the requested filename's real extension`() {
        // The requestedFilename -> MediaKind -> extension step lives only here, and every one of these
        // formats used to arrive with extension=null: staged as ".stage", whose MIME is
        // application/octet-stream, which AnkiDroid stores as ".bin" (Issue #2).
        //
        // .wav is the anchor case: Android offline TTS publishes reading-mode sentence audio as WAV,
        // so it needs no assumption about any server's behaviour. .ogg covers downloaded expression
        // audio (the extension comes from the localaudio server's Content-Type) and .svg covers
        // Yomitan dictionary media, which arrives as MediaKind.IMAGE with its own basename.
        val request =
            requestOfKinds(
                "wav" to MediaKind.AUDIO,
                "ogg" to MediaKind.AUDIO,
                "svg" to MediaKind.IMAGE,
            )
        val fixture = Fixture(request)
        fixture.provider.receipts += listOf("file:///media0.wav", "file:///media1.ogg", "file:///media2.svg")

        fixture.execute()

        assertEquals(listOf("wav", "ogg", "svg"), fixture.staging.stagedRequests.map { it.extension })
    }

    @Test
    fun `multi asset success reserves first and returns exact claim acknowledgements`() {
        val request = request(3)
        val fixture = Fixture(request)
        fixture.provider.receipts += listOf("file:///clip0.mp3", "file:///clip1.mp3", "file:///clip2.mp3")

        val outcome = fixture.execute()

        assertFalse(outcome.replayed)
        assertEquals(
            request.assets.mapIndexed { index, asset -> StoredMedia(asset.assetId, "clip$index.mp3") },
            outcome.result.results,
        )
        assertEquals(
            request.assets.mapIndexed { index, asset ->
                MediaAcknowledgement(asset.assetId, "clip$index.mp3", 1000L + index)
            },
            outcome.mediaAcknowledgements,
        )
        assertEquals(3, fixture.provider.storeCalls)
        assertTrue(fixture.events.indexOf("reserve:3") < fixture.events.indexOf("stage:0"))
        assertEquals(0, fixture.journal.unusedReservationCount)
        assertTrue(fixture.journal.leaseAcquired)
        assertFalse(fixture.journal.leaseReleased)
    }

    @Test
    fun `row local staging failure is nonretryable and later assets still store`() {
        val request = request(3)
        val fixture = Fixture(request)
        fixture.staging.failStage += 0
        fixture.provider.receipts += listOf("file:///clip1.mp3", "file:///clip2.mp3")

        val outcome = fixture.execute()

        val failed = outcome.result.results[0] as FailedMedia
        assertEquals(AnkiErrorCode.MEDIA_STORE_FAILED, failed.error.code)
        assertFalse(failed.error.retryable)
        assertEquals(
            listOf(
                failed,
                StoredMedia(request.assets[1].assetId, "clip1.mp3"),
                StoredMedia(request.assets[2].assetId, "clip2.mp3"),
            ),
            outcome.result.results,
        )
        assertEquals(2, fixture.provider.storeCalls)
        assertEquals(setOf(10L), fixture.journal.releasedReservations)
        assertEquals(0, fixture.journal.unusedReservationCount)
    }

    /**
     * The typed failure only ever reached `compact_evidence`, which lives in the app-private journal
     * database and so is unreadable on a release build. A field report of "N media file(s) could not
     * be stored in Anki" therefore named no cause at all. The row error is the one carrier that
     * crosses into the Python adapter, which already logs it per asset to the shareable engine log.
     */
    @Test
    fun `a row local failure names its typed staging failure and throw site without leaking messages`() {
        listOf("stage", "grant").forEach { site ->
            val request = request(1)
            val fixture = Fixture(request)
            if (site == "stage") fixture.staging.failStage += 0 else fixture.staging.failGrant += 0

            val error = (fixture.execute().result.results.single() as FailedMedia).error

            val expected =
                if (site == "stage") {
                    AnkiMediaStagingFailure.VERIFICATION_FAILED
                } else {
                    AnkiMediaStagingFailure.PERMISSION_FAILED
                }
            assertTrue(
                "$site row error should name the typed failure: ${error.message}",
                error.message.contains("staging=${expected.name}"),
            )
            assertTrue(
                "$site row error should name the throw site: ${error.message}",
                error.message.contains("fault=AnkiMediaStagingException @ "),
            )
            assertFalse(
                "$site row error must not carry the exception message",
                error.message.contains("staging failure"),
            )
        }
    }

    /** The cause is digested in preference to the wrapper, and its message never rides along. */
    @Test
    fun `a wrapped staging cause names the underlying exception and never its message`() {
        val request = request(1)
        val fixture = Fixture(request)
        fixture.staging.stageFailure =
            AnkiMediaStagingException(
                AnkiMediaStagingFailure.PREPARATION_FAILED,
                "Media staging could not be prepared",
                IllegalStateException("/storage/emulated/0/Movies/private name.mkv"),
            )

        val error = (fixture.execute().result.results.single() as FailedMedia).error

        assertTrue(
            "row error should name the cause: ${error.message}",
            error.message.contains("staging=PREPARATION_FAILED") &&
                error.message.contains("fault=IllegalStateException @ "),
        )
        assertFalse(
            "row error must never carry a user path",
            error.message.contains("private name.mkv") || error.message.contains("/storage/"),
        )
    }

    @Test
    fun `grant failure cleans its private copy releases reservation and continues`() {
        val request = request(2)
        val fixture = Fixture(request)
        fixture.staging.failGrant += 0
        fixture.provider.receipts += "file:///clip1.mp3"

        val outcome = fixture.execute()

        assertTrue(outcome.result.results[0] is FailedMedia)
        assertEquals(StoredMedia(request.assets[1].assetId, "clip1.mp3"), outcome.result.results[1])
        assertEquals(listOf(0, 1), fixture.staging.cleanupCalls)
        assertEquals(setOf(10L), fixture.journal.releasedReservations)
    }

    @Test
    fun `provider access stop makes current and suffix notAttempted without gateway entry`() {
        val request = request(3)
        val fixture = Fixture(request)
        fixture.provider.preflightFailure =
            AnkiReadFailure(
                AnkiErrorCode.PERMISSION_REQUIRED,
                retryable = false,
                stableMessage = "AnkiDroid permission is required",
            )

        val outcome = fixture.execute()

        assertEquals(request.assets.map { NotAttemptedMedia(it.assetId) }, outcome.result.results)
        assertEquals(AnkiErrorCode.PERMISSION_REQUIRED, outcome.result.error?.code)
        assertEquals(0, fixture.provider.storeCalls)
        assertEquals(ChildState.PROVEN_NOT_COMMITTED, fixture.journal.children.single().state)
        assertEquals(MediaClaimState.CLEANED_VERIFIED, fixture.journal.claims.values.single().state)
        assertEquals(setOf(11L, 12L), fixture.journal.releasedReservations)
        assertEquals(0, fixture.journal.unusedReservationCount)
    }

    @Test
    fun `cancellation at authorization denies entry and writes strict notAttempted suffix`() {
        val request = request(2)
        val cancellation = MutableAnkiCancellation()
        val fixture = Fixture(request, cancellation)
        fixture.provider.preflightHook = cancellation::cancel

        val outcome = fixture.execute()

        assertEquals(request.assets.map { NotAttemptedMedia(it.assetId) }, outcome.result.results)
        assertEquals(AnkiErrorCode.CANCELLED, outcome.result.error?.code)
        assertEquals(0, fixture.provider.storeCalls)
        assertFalse(fixture.events.any { it.startsWith("recordEntry") })
    }

    @Test
    fun `release at authorization denies entry and releases every suffix reservation`() {
        val request = request(3)
        val fixture = Fixture(request)
        fixture.provider.preflightHook = { fixture.registry.release(request.runId, acknowledgeTerminalResponses = true) }

        val outcome = fixture.execute()

        assertEquals(request.assets.map { NotAttemptedMedia(it.assetId) }, outcome.result.results)
        assertEquals(AnkiErrorCode.CANCELLED, outcome.result.error?.code)
        assertEquals(0, fixture.provider.storeCalls)
        assertEquals(setOf(11L, 12L), fixture.journal.releasedReservations)
    }

    @Test
    fun `quarantine at authorization denies entry without calling the gateway`() {
        val request = request(2)
        val fixture = Fixture(request)
        fixture.provider.preflightHook = { fixture.registry.markTerminalResponseFailure(fixture.activeOwner!!) }

        val outcome = fixture.execute()

        assertEquals(request.assets.map { NotAttemptedMedia(it.assetId) }, outcome.result.results)
        assertEquals(AnkiErrorCode.INTERNAL_ERROR, outcome.result.error?.code)
        assertEquals(0, fixture.provider.storeCalls)
    }

    @Test
    fun `known stored prefix forces a retryable access stop to nonretryable`() {
        val request = request(3)
        val fixture = Fixture(request)
        fixture.provider.receipts += "file:///clip0.mp3"
        fixture.provider.failPreflightAt = 1
        fixture.provider.preflightFailure =
            AnkiReadFailure(
                AnkiErrorCode.PROVIDER_UNAVAILABLE,
                retryable = true,
                stableMessage = "AnkiDroid became unavailable",
            )

        val outcome = fixture.execute()

        assertEquals(StoredMedia(request.assets[0].assetId, "clip0.mp3"), outcome.result.results[0])
        assertTrue(outcome.result.results[1] is NotAttemptedMedia)
        assertTrue(outcome.result.results[2] is NotAttemptedMedia)
        assertFalse(outcome.result.error!!.retryable)
        assertEquals(1, fixture.provider.storeCalls)
    }

    @Test
    fun `entry is durable immediately before the one raw provider call`() {
        val request = request(1)
        val fixture = Fixture(request)
        fixture.provider.receipts += "file:///clip0.mp3"

        fixture.execute()

        val relevant = fixture.events.filter { it.startsWith("preflight") || it.startsWith("recordEntry") || it.startsWith("store:") }
        assertEquals(listOf("preflight:0", "recordEntry:100", "store:clip0"), relevant)
        assertEquals(1, fixture.journal.children.single().attemptCount)
    }

    @Test
    fun `cancellation after authorization cannot revoke an entered success`() {
        val request = request(1)
        val cancellation = MutableAnkiCancellation()
        val fixture = Fixture(request, cancellation)
        fixture.provider.storeHook = cancellation::cancel
        fixture.provider.receipts += "file:///clip0.mp3"

        val outcome = fixture.execute()

        assertTrue(cancellation.isCancelled())
        assertEquals(listOf(StoredMedia(request.assets[0].assetId, "clip0.mp3")), outcome.result.results)
        assertEquals(ChildState.COMMIT_KNOWN, fixture.journal.children.single().state)
        assertEquals(1, fixture.provider.storeCalls)
    }

    @Test
    fun `null invalid and throwing provider receipts are uncertain once with strict suffix`() {
        val request = request(3)
        val variants =
            listOf<(FakeMediaProvider) -> Unit>(
                { it.receipts.add(null) },
                { it.receipts += "content://com.ichi2.anki.flashcards/media/1" },
                { it.storeFailure = ProviderGatewayException(ProviderFailureKind.MUTATION_FAILED) },
            )

        variants.forEach { configure ->
            val fixture = Fixture(request)
            configure(fixture.provider)

            val outcome = fixture.execute()

            assertTrue(outcome.result.results[0] is UncertainMedia)
            assertTrue(outcome.result.results[1] is NotAttemptedMedia)
            assertTrue(outcome.result.results[2] is NotAttemptedMedia)
            assertEquals(AnkiErrorCode.POST_COMMIT_UNCERTAIN, outcome.result.error?.code)
            assertFalse(outcome.result.error!!.retryable)
            assertEquals(1, fixture.provider.storeCalls)
            assertEquals(ChildState.COMMIT_UNCERTAIN, fixture.journal.children.single().state)
            assertEquals(MediaClaimState.COMMIT_UNCERTAIN, fixture.journal.claims.values.single().state)
            assertEquals(1, fixture.journal.uncertainCompletionCount)
        }
    }

    @Test
    fun `canonical receipt unrelated to the requested asset is uncertain and never accepted`() {
        val request = request(2)
        val fixture = Fixture(request)
        fixture.provider.receipts += "file:///unrelated.mp3"

        val outcome = fixture.execute()

        assertTrue(outcome.result.results[0] is UncertainMedia)
        assertTrue(outcome.result.results[1] is NotAttemptedMedia)
        assertEquals(AnkiErrorCode.POST_COMMIT_UNCERTAIN, outcome.result.error?.code)
        assertFalse(outcome.result.error!!.retryable)
        assertTrue(outcome.mediaAcknowledgements.isEmpty())
        assertEquals(1, fixture.provider.storeCalls)
        assertFalse(fixture.events.any { it.startsWith("commitReceipt:") })
        assertEquals(ChildState.COMMIT_UNCERTAIN, fixture.journal.children.single().state)
        assertEquals(MediaClaimState.COMMIT_UNCERTAIN, fixture.journal.claims.values.single().state)
    }

    @Test
    fun `receipt journal rejection becomes post commit uncertainty without retry`() {
        val request = request(2)
        val fixture = Fixture(request)
        fixture.provider.receipts += "file:///clip0.mp3"
        fixture.journal.commitMode = CommitMode.THROW_BEFORE

        val outcome = fixture.execute()

        assertTrue(outcome.result.results[0] is UncertainMedia)
        assertTrue(outcome.result.results[1] is NotAttemptedMedia)
        assertEquals(1, fixture.provider.storeCalls)
        assertEquals(1, fixture.journal.uncertainCompletionCount)
    }

    @Test
    fun `exception after atomic receipt commit observes durable success instead of downgrading it`() {
        val request = request(1)
        val fixture = Fixture(request)
        fixture.provider.receipts += "file:///clip0.mp3"
        fixture.journal.commitMode = CommitMode.THROW_AFTER

        val outcome = fixture.execute()

        assertEquals(listOf(StoredMedia(request.assets[0].assetId, "clip0.mp3")), outcome.result.results)
        assertEquals(0, fixture.journal.uncertainCompletionCount)
        assertEquals(ChildState.COMMIT_KNOWN, fixture.journal.children.single().state)
    }

    @Test
    fun `cleanup failure never overwrites a known provider outcome`() {
        val request = request(1)
        val fixture = Fixture(request)
        fixture.provider.receipts += "file:///clip0.mp3"
        fixture.staging.failCleanup += 0

        val outcome = fixture.execute()

        assertEquals(listOf(StoredMedia(request.assets[0].assetId, "clip0.mp3")), outcome.result.results)
        assertEquals(MediaClaimState.STORED, fixture.journal.claims.values.single().state)
        assertEquals(ChildState.COMMIT_KNOWN, fixture.journal.children.single().state)
        assertEquals(1, fixture.staging.cleanupCalls.size)
    }

    @Test
    fun `an unadmittable batch fails every row locally instead of stopping the run`() {
        // Namespace and lease admission run before any reservation exists, so the batch cannot be
        // stored at all. It used to propagate as a top-level internal_error, which stopped the whole
        // mining run with an unattributable message and created zero cards (Issue #6).
        listOf(
            "lease" to
                MediaAdmissionViolation(
                    MediaAdmissionRefusal.LEASE_ALREADY_ACTIVE_FOR_ANOTHER_RUN,
                    "Only one active media lease is permitted",
                ),
            "reserve" to
                MediaAdmissionViolation(
                    MediaAdmissionRefusal.DIRECT_NAME_COLLISION,
                    "Media direct-name namespace collision",
                ),
        ).forEach { (stage, failure) ->
            val request = request(2)
            val fixture = Fixture(request)
            if (stage == "lease") {
                fixture.journal.leaseFailure = failure
            } else {
                fixture.journal.reserveFailure = failure
            }

            val outcome = fixture.execute()

            assertEquals(
                request.assets.map { it.assetId },
                outcome.result.results.map { (it as FailedMedia).assetId },
            )
            outcome.result.results.forEach { row ->
                val error = (row as FailedMedia).error
                assertEquals(AnkiErrorCode.MEDIA_STORE_FAILED, error.code)
                assertFalse(error.retryable)
                val expected =
                    if (stage == "lease") {
                        MediaAdmissionRefusal.LEASE_ALREADY_ACTIVE_FOR_ANOTHER_RUN
                    } else {
                        MediaAdmissionRefusal.DIRECT_NAME_COLLISION
                    }
                assertTrue(
                    "$stage row error should name the typed refusal: ${error.message}",
                    error.message.contains("admission=refused") &&
                        error.message.contains("reason=${expected.name}"),
                )
                assertFalse(
                    "$stage row error must not carry the exception message",
                    error.message.contains("namespace collision") ||
                        error.message.contains("active media lease"),
                )
            }
            assertEquals(null, outcome.result.error)
            assertTrue(outcome.mediaAcknowledgements.isEmpty())
            assertEquals(0, fixture.staging.stageCalls)
            assertEquals(0, fixture.provider.storeCalls)
            assertTrue(
                "$stage evidence should name the typed refusal and the throw site",
                fixture.journal.appendedEvidence.all { evidence ->
                    evidence.contains("admission=refused") &&
                        evidence.contains("reason=") &&
                        evidence.contains("fault=MediaAdmissionViolation @ ")
                },
            )
            assertFalse(
                "$stage evidence must not carry the exception message",
                fixture.journal.appendedEvidence.any { it.contains("namespace collision") },
            )
        }
    }

    @Test
    fun `durable corruption still stops the run instead of degrading to a media failure`() {
        val request = request(1)
        val fixture = Fixture(request)
        fixture.journal.reserveFailure = JournalCorruptionException("Media lease capacity is overdrawn")

        assertThrows(JournalCorruptionException::class.java) { fixture.execute() }
    }

    private class Fixture(
        val request: StoreMediaRequest,
        cancellation: AnkiCancellation = AnkiCancellation.NONE,
    ) {
        val events = mutableListOf<String>()
        val registry = AnkiRunStateRegistry()
        val journal = FakeMediaJournal(events)
        val staging = FakeMediaStaging(events)
        val provider = FakeMediaProvider(events)
        private val service = JournalBackedMediaMutationService(registry, journal, staging, provider)
        var activeOwner: AnkiRunStateRegistry.RunOwner? = null

        init {
            check(registry.register(request.runId, cancellation))
        }

        fun execute(): StoreMediaMutationOutcome =
            registry.withOwner(request.runId) { owner ->
                activeOwner = owner
                try {
                    service.store(owner, request)
                } finally {
                    activeOwner = null
                }
            }
    }

    private class FakeMediaJournal(
        private val events: MutableList<String>,
    ) : MediaMutationJournal {
        private var ready: JournalResponse.StoreMedia? = null
        private var request: JournalRequest? = null
        private var nextReservationId = 10L
        private var nextChildId = 100L
        private var nextClaimId = 1000L
        private val reservations = linkedMapOf<Long, MediaReservationRecord>()
        val children = mutableListOf<ChildRecord>()
        val claims = linkedMapOf<String, MediaClaimRecord>()
        private val aligned = mutableListOf<AlignedResult>()
        val releasedReservations = linkedSetOf<Long>()
        var leaseAcquired = false
        var leaseReleased = false
        var leaseFailure: RuntimeException? = null
        var reserveFailure: RuntimeException? = null
        var commitMode = CommitMode.NORMAL
        var uncertainCompletionCount = 0

        val unusedReservationCount: Int
            get() = reservations.values.count { it.state == MediaReservationState.RESERVED }

        val appendedEvidence: List<String>
            get() =
                aligned
                    .filterIsInstance<AlignedResult.MediaFailed>()
                    .mapNotNull(AlignedResult.MediaFailed::compactEvidence)

        override fun replay(request: JournalRequest): ReplayResult {
            events += "replay"
            return ready?.let(ReplayResult::Ready) ?: ReplayResult.Missing
        }

        override fun begin(request: JournalRequest) {
            events += "begin"
            this.request = request
        }

        override fun acquireLease(runId: String) {
            events += "lease"
            leaseFailure?.let { throw it }
            leaseAcquired = true
        }

        override fun reserve(
            runId: String,
            assets: List<MediaReservationDraft>,
        ): List<MediaReservationRecord> {
            events += "reserve:${assets.size}"
            reserveFailure?.let { throw it }
            return assets.map { draft ->
                val id = nextReservationId++
                MediaReservationRecord(
                    id = id,
                    leaseId = 1,
                    runId = runId,
                    requestId = draft.requestId,
                    assetId = draft.assetId,
                    requestedFilename = draft.requestedFilename,
                    preferredName = draft.preferredName,
                    sha256 = draft.sha256,
                    purpose = draft.purpose,
                    mediaKind = draft.mediaKind,
                    state = MediaReservationState.RESERVED,
                    claimId = null,
                    createdAtMs = 1,
                    updatedAtMs = 1,
                ).also { reservations[id] = it }
            }
        }

        override fun releaseReservation(reservationId: Long) {
            events += "release:$reservationId"
            val current = reservations.getValue(reservationId)
            check(current.state == MediaReservationState.RESERVED)
            reservations[reservationId] = current.copy(state = MediaReservationState.RELEASED)
            releasedReservations += reservationId
        }

        override fun promote(
            key: ParentKey,
            reservationId: Long,
            command: MutationCommand.StoreMedia,
        ): MediaPromotion {
            events += "promote:${command.requestIndex}"
            val reservation = reservations.getValue(reservationId)
            check(reservation.state == MediaReservationState.RESERVED)
            val claim =
                MediaClaimRecord(
                    id = nextClaimId++,
                    runId = reservation.runId,
                    requestId = reservation.requestId,
                    assetId = reservation.assetId,
                    requestedFilename = reservation.requestedFilename,
                    preferredName = reservation.preferredName,
                    sha256 = reservation.sha256,
                    purpose = reservation.purpose,
                    mediaKind = reservation.mediaKind,
                    actualFilename = null,
                    state = MediaClaimState.PENDING,
                    compactEvidence = null,
                    createdAtMs = 1,
                    updatedAtMs = 1,
                )
            val child =
                ChildRecord(
                    id = nextChildId++,
                    parentId = 1,
                    sequence = command.requestIndex,
                    digestVersion = 1,
                    requestSha256 = "0".repeat(64),
                    itemSha256 = null,
                    command = command,
                    mediaClaimId = claim.id,
                    state = ChildState.PREPARED,
                    attempts = emptyList(),
                    receipt = null,
                    terminalEvidence = null,
                    createdAtMs = 1,
                    updatedAtMs = 1,
                )
            claims[claim.assetId] = claim
            children += child
            reservations[reservationId] =
                reservation.copy(
                    state = MediaReservationState.PROMOTED,
                    claimId = claim.id,
                )
            return MediaPromotion(reservations.getValue(reservationId), claim, child)
        }

        override fun recordProviderEntry(childId: Long) {
            events += "recordEntry:$childId"
            updateChild(childId) { child ->
                child.copy(
                    attempts = listOf(ProviderAttempt(childId, 1, recoveryReissue = false, enteredAtMs = 2)),
                    updatedAtMs = 2,
                )
            }
        }

        override fun commitReceipt(
            childId: Long,
            claimId: Long,
            receipt: ProviderReceipt.Media,
            evidence: String,
        ) {
            events += "commitReceipt:$childId"
            if (commitMode == CommitMode.THROW_BEFORE) throw IllegalStateException("receipt transaction rejected")
            val child = children.single { it.id == childId }
            val command = child.command as MutationCommand.StoreMedia
            updateClaim(claimId) {
                it.copy(
                    state = MediaClaimState.STORED,
                    actualFilename = receipt.actualFilename,
                    compactEvidence = evidence,
                )
            }
            aligned += AlignedResult.MediaStored(command.requestIndex, command.assetId, receipt.actualFilename, evidence)
            updateChild(childId) {
                it.copy(
                    state = ChildState.COMMIT_KNOWN,
                    receipt = receipt,
                    terminalEvidence = evidence,
                )
            }
            if (commitMode == CommitMode.THROW_AFTER) throw IllegalStateException("post-commit hook")
        }

        override fun completeFailure(
            childId: Long,
            claimId: Long,
            childOutcome: ChildState,
            claimState: MediaClaimState,
            result: AlignedResult,
            evidence: String,
        ) {
            events += "completeFailure:$childOutcome"
            if (childOutcome == ChildState.COMMIT_UNCERTAIN) uncertainCompletionCount += 1
            updateClaim(claimId) { it.copy(state = claimState, compactEvidence = evidence) }
            updateChild(childId) { it.copy(state = childOutcome, terminalEvidence = evidence) }
            aligned += result
        }

        override fun append(
            key: ParentKey,
            result: AlignedResult,
        ) {
            events += "append:${result.status}"
            check(result.requestIndex == aligned.size)
            aligned += result
        }

        override fun results(key: ParentKey): List<AlignedResult> = aligned.toList()

        override fun claim(
            key: ParentKey,
            assetId: String,
        ): MediaClaimRecord? = claims[assetId]

        override fun markResultReady(
            request: JournalRequest,
            response: JournalResponse.StoreMedia,
        ) {
            events += "resultReady"
            ready = response
        }

        fun installReplay(
            request: StoreMediaRequest,
            rows: List<AlignedResult>,
            claims: List<MediaClaimRecord>,
        ) {
            val journalRequest = JournalRequest.from(request)
            aligned.clear()
            aligned += rows
            this.claims.clear()
            claims.associateByTo(this.claims, MediaClaimRecord::assetId)
            ready = JournalResponse.StoreMedia(journalRequest.key, rows, error = null)
        }

        private fun updateChild(
            childId: Long,
            transform: (ChildRecord) -> ChildRecord,
        ) {
            val index = children.indexOfFirst { it.id == childId }
            check(index >= 0)
            children[index] = transform(children[index])
        }

        private fun updateClaim(
            claimId: Long,
            transform: (MediaClaimRecord) -> MediaClaimRecord,
        ) {
            val entry = claims.entries.single { it.value.id == claimId }
            claims[entry.key] = transform(entry.value)
        }
    }

    private class FakeMediaStaging(
        private val events: MutableList<String>,
    ) : MediaMutationStaging {
        var stageCalls = 0
        val stagedRequests = mutableListOf<AnkiMediaStagingRequest>()
        val failStage = mutableSetOf<Int>()
        val failGrant = mutableSetOf<Int>()
        val failCleanup = mutableSetOf<Int>()
        val cleanupCalls = mutableListOf<Int>()

        /** Set to throw an exact exception (e.g. one carrying a cause) from the first stage call. */
        var stageFailure: AnkiMediaStagingException? = null

        override fun stage(request: AnkiMediaStagingRequest): StagingRecord {
            val index = request.assetId.index()
            events += "stage:$index"
            stageCalls += 1
            stagedRequests += request
            stageFailure?.let { throw it }
            if (index in failStage) throw stagingFailure(AnkiMediaStagingFailure.VERIFICATION_FAILED)
            return record(request, index, StagingState.STAGED)
        }

        override fun grantRead(record: StagingRecord): StagingRecord {
            val index = record.assetId.index()
            events += "grant:$index"
            if (index in failGrant) {
                cleanupCalls += index
                throw stagingFailure(AnkiMediaStagingFailure.PERMISSION_FAILED)
            }
            return record.copy(state = StagingState.GRANTED)
        }

        override fun cleanup(record: StagingRecord): AnkiMediaCleanupOutcome {
            val index = record.assetId.index()
            events += "cleanup:$index"
            cleanupCalls += index
            if (index in failCleanup) throw stagingFailure(AnkiMediaStagingFailure.CLEANUP_FAILED)
            return AnkiMediaCleanupOutcome.CLEANED
        }

        private fun record(
            request: AnkiMediaStagingRequest,
            index: Int,
            state: StagingState,
        ) =
            StagingRecord(
                id = 500L + index,
                runId = request.runId,
                requestId = request.requestId,
                assetId = request.assetId,
                relativePath = "v1/${index.toString().padStart(64, '0')}.stage",
                contentUri = "content://test.anki-media/v1/$index.stage",
                packageName = ANKIDROID_PACKAGE,
                sizeBytes = request.expectedSizeBytes,
                sha256 = request.expectedSha256,
                state = state,
                compactEvidence = null,
                createdAtMs = 1,
                updatedAtMs = 1,
            )
    }

    private class FakeMediaProvider(
        private val events: MutableList<String>,
    ) : MediaMutationProvider {
        val receipts = mutableListOf<String?>()
        var preflightFailure: AnkiReadFailure? = null
        var failPreflightAt = 0
        var preflightCalls = 0
        var preflightHook: () -> Unit = {}
        var storeHook: () -> Unit = {}
        var storeFailure: RuntimeException? = null
        var storeCalls = 0

        override fun preflight(cancellation: AnkiCancellation) {
            events += "preflight:$preflightCalls"
            preflightHook()
            if (preflightCalls++ >= failPreflightAt) preflightFailure?.let { throw it }
        }

        override fun store(command: AnkiProviderMutationCommand.StoreMedia): String? {
            events += "store:${command.preferredName}"
            storeCalls += 1
            storeHook()
            storeFailure?.let { throw it }
            return if (receipts.isEmpty()) "file:///${command.preferredName}.mp3" else receipts.removeAt(0)
        }
    }

    private enum class CommitMode {
        NORMAL,
        THROW_BEFORE,
        THROW_AFTER,
    }

}

private fun request(count: Int): StoreMediaRequest =
    StoreMediaRequest(
        runId = MEDIA_RUN_ID,
        requestId = MEDIA_REQUEST_ID,
        assets =
            List(count) { index ->
                MediaAsset(
                    assetId = "asset_${index.toString(16).padStart(32, '0')}",
                    sourcePath = "/tmp/clip$index.mp3",
                    preferredName = "clip$index",
                    requestedFilename = "clip$index.mp3",
                    purpose = MediaPurpose.CARD,
                    mediaKind = MediaKind.AUDIO,
                    expectedSizeBytes = (index + 1).toLong(),
                    expectedSha256 = index.toString(16).padStart(64, '0'),
                )
            },
    )

/** A request whose assets carry the given `(suffix, kind)` pairs, one asset each. */
private fun requestOfKinds(vararg formats: Pair<String, MediaKind>): StoreMediaRequest =
    StoreMediaRequest(
        runId = MEDIA_RUN_ID,
        requestId = MEDIA_REQUEST_ID,
        assets =
            formats.mapIndexed { index, (suffix, kind) ->
                MediaAsset(
                    assetId = "asset_${index.toString(16).padStart(32, '0')}",
                    sourcePath = "/tmp/media$index.$suffix",
                    preferredName = "media$index",
                    requestedFilename = "media$index.$suffix",
                    purpose = MediaPurpose.CARD,
                    mediaKind = kind,
                    expectedSizeBytes = (index + 1).toLong(),
                    expectedSha256 = index.toString(16).padStart(64, '0'),
                )
            },
    )

private fun claim(
    id: Long,
    request: StoreMediaRequest,
    asset: MediaAsset,
    actualFilename: String,
    state: MediaClaimState,
) =
    MediaClaimRecord(
        id = id,
        runId = request.runId,
        requestId = request.requestId,
        assetId = asset.assetId,
        requestedFilename = asset.requestedFilename,
        preferredName = asset.preferredName,
        sha256 = asset.expectedSha256,
        purpose = JournalMediaPurpose.valueOf(asset.purpose.name),
        mediaKind = JournalMediaKind.valueOf(asset.mediaKind.name),
        actualFilename = actualFilename,
        state = state,
        compactEvidence = "accepted",
        createdAtMs = 1,
        updatedAtMs = 1,
    )

private fun mediaFailure() =
    JournalError(
        JournalErrorCode.MEDIA_STORE_FAILED,
        "The media asset could not be staged for AnkiDroid",
        retryable = false,
    )

private fun JournalError.toProtocol() =
    com.ankiminer.android.anki.protocol.AnkiErrorDetail(
        code = AnkiErrorCode.valueOf(code.name),
        message = message,
        retryable = retryable,
    )

private fun stagingFailure(failure: AnkiMediaStagingFailure) =
    AnkiMediaStagingException(failure, "staging failure")

private fun String.index(): Int = removePrefix("asset_").toInt(16)

private const val MEDIA_RUN_ID = "run_11111111111111111111111111111111"
private const val MEDIA_REQUEST_ID = "anki_11111111111111111111111111111111"
