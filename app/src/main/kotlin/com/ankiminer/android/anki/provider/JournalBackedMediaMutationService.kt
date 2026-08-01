package com.ankiminer.android.anki.provider

import com.ankiminer.android.anki.journal.AlignedResult
import com.ankiminer.android.anki.journal.AnkiMutationStore
import com.ankiminer.android.anki.journal.ChildState
import com.ankiminer.android.anki.journal.JournalCorruptionException
import com.ankiminer.android.anki.journal.JournalError
import com.ankiminer.android.anki.journal.JournalErrorCode
import com.ankiminer.android.anki.journal.JournalInvariantViolation
import com.ankiminer.android.anki.journal.JournalRequest
import com.ankiminer.android.anki.journal.JournalResponse
import com.ankiminer.android.anki.journal.MediaAdmissionViolation
import com.ankiminer.android.anki.journal.MediaClaimRecord
import com.ankiminer.android.anki.journal.MediaClaimState
import com.ankiminer.android.anki.journal.MediaKind as JournalMediaKind
import com.ankiminer.android.anki.journal.MediaPromotion
import com.ankiminer.android.anki.journal.MediaPurpose as JournalMediaPurpose
import com.ankiminer.android.anki.journal.MediaReservationAdmission
import com.ankiminer.android.anki.journal.MediaReservationDraft
import com.ankiminer.android.anki.journal.MediaReservationRecord
import com.ankiminer.android.anki.journal.MediaReservationState
import com.ankiminer.android.anki.journal.MutationCommand
import com.ankiminer.android.anki.journal.ParentKey
import com.ankiminer.android.anki.journal.ProviderReceipt
import com.ankiminer.android.anki.journal.ReplayResult
import com.ankiminer.android.anki.journal.StagingRecord
import com.ankiminer.android.anki.protocol.AnkiErrorCode
import com.ankiminer.android.anki.protocol.AnkiErrorDetail
import com.ankiminer.android.anki.protocol.AnkiProtocolException
import com.ankiminer.android.anki.protocol.AnkiValidators
import com.ankiminer.android.anki.protocol.FailedMedia
import com.ankiminer.android.anki.protocol.MediaAsset
import com.ankiminer.android.anki.protocol.NotAttemptedMedia
import com.ankiminer.android.anki.protocol.StoreMediaRequest
import com.ankiminer.android.anki.protocol.StoreMediaResult
import com.ankiminer.android.anki.protocol.StoredMedia
import com.ankiminer.android.anki.protocol.UncertainMedia
import java.util.concurrent.atomic.AtomicBoolean
import com.ankiminer.android.diagnostics.compactFaultToken
import com.ankiminer.android.diagnostics.log.AppLog
import com.ankiminer.android.diagnostics.log.LogComponent
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.CancellationException

internal data class StoreMediaMutationOutcome(
    val result: StoreMediaResult,
    val mediaAcknowledgements: List<MediaAcknowledgement>,
    val replayed: Boolean,
)

internal fun interface MediaMutationService {
    fun store(
        owner: AnkiRunStateRegistry.RunOwner,
        request: StoreMediaRequest,
    ): StoreMediaMutationOutcome
}

/** The media service's complete durable surface, kept small enough for state-machine JVM tests. */
internal interface MediaMutationJournal {
    fun replay(request: JournalRequest): ReplayResult

    fun begin(request: JournalRequest)

    fun acquireLease(runId: String)

    fun reserve(
        runId: String,
        assets: List<MediaReservationDraft>,
    ): List<MediaReservationAdmission>

    fun releaseReservation(reservationId: Long)

    fun reservation(reservationId: Long): MediaReservationRecord?

    fun promote(
        key: ParentKey,
        reservationId: Long,
        command: MutationCommand.StoreMedia,
    ): MediaPromotion

    fun recordProviderEntry(childId: Long)

    fun commitReceipt(
        childId: Long,
        claimId: Long,
        receipt: ProviderReceipt.Media,
        evidence: String,
    )

    fun completeFailure(
        childId: Long,
        claimId: Long,
        childOutcome: ChildState,
        claimState: MediaClaimState,
        result: AlignedResult,
        evidence: String,
    )

    fun append(
        key: ParentKey,
        result: AlignedResult,
    )

    fun results(key: ParentKey): List<AlignedResult>

    fun claim(
        key: ParentKey,
        assetId: String,
    ): MediaClaimRecord?

    fun markResultReady(
        request: JournalRequest,
        response: JournalResponse.StoreMedia,
    )
}

internal class AnkiMutationMediaJournal(
    private val store: AnkiMutationStore,
) : MediaMutationJournal {
    override fun replay(request: JournalRequest): ReplayResult = store.replay(request, liveRun = true)

    override fun begin(request: JournalRequest) {
        store.createParent(request)
        store.beginParent(request.key)
    }

    override fun acquireLease(runId: String) {
        store.acquireMediaLease(runId)
    }

    override fun reserve(
        runId: String,
        assets: List<MediaReservationDraft>,
    ): List<MediaReservationAdmission> = store.reserveMediaIndependently(runId, assets)

    override fun releaseReservation(reservationId: Long) {
        store.releaseReservation(reservationId)
    }

    override fun reservation(reservationId: Long): MediaReservationRecord? =
        store.mediaReservation(reservationId)

    override fun promote(
        key: ParentKey,
        reservationId: Long,
        command: MutationCommand.StoreMedia,
    ): MediaPromotion = store.promoteReservation(key, reservationId, command)

    override fun recordProviderEntry(childId: Long) {
        store.recordProviderEntry(childId)
    }

    override fun commitReceipt(
        childId: Long,
        claimId: Long,
        receipt: ProviderReceipt.Media,
        evidence: String,
    ) {
        store.commitMediaReceipt(childId, claimId, receipt, evidence)
    }

    override fun completeFailure(
        childId: Long,
        claimId: Long,
        childOutcome: ChildState,
        claimState: MediaClaimState,
        result: AlignedResult,
        evidence: String,
    ) {
        store.completeMediaFailure(childId, claimId, childOutcome, claimState, result, evidence)
    }

    override fun append(
        key: ParentKey,
        result: AlignedResult,
    ) {
        store.appendAlignedResult(key, result)
    }

    override fun results(key: ParentKey): List<AlignedResult> = store.alignedResults(key)

    override fun claim(
        key: ParentKey,
        assetId: String,
    ): MediaClaimRecord? = store.mediaClaim(key, assetId)

    override fun markResultReady(
        request: JournalRequest,
        response: JournalResponse.StoreMedia,
    ) {
        store.markResultReady(request, response)
    }
}

internal interface MediaMutationStaging {
    fun stage(request: AnkiMediaStagingRequest): StagingRecord

    fun grantRead(record: StagingRecord): StagingRecord

    fun cleanup(record: StagingRecord): AnkiMediaCleanupOutcome

    fun recover(): AnkiMediaRecoveryReport
}

internal class PrivateMediaMutationStaging(
    private val staging: AnkiMediaStaging,
) : MediaMutationStaging {
    override fun stage(request: AnkiMediaStagingRequest): StagingRecord = staging.stage(request)

    override fun grantRead(record: StagingRecord): StagingRecord = staging.grantRead(record)

    override fun cleanup(record: StagingRecord): AnkiMediaCleanupOutcome = staging.cleanup(record)

    override fun recover(): AnkiMediaRecoveryReport = staging.recover()
}

internal interface MediaMutationProvider {
    fun preflight(cancellation: AnkiCancellation)

    fun store(command: AnkiProviderMutationCommand.StoreMedia): String?
}

internal class CheckedMediaMutationProvider(
    private val gateway: AnkiProviderGateway,
) : MediaMutationProvider {
    private val checked = CheckedProvider(gateway)

    override fun preflight(cancellation: AnkiCancellation) {
        checked.preflightMutation(cancellation)
    }

    override fun store(command: AnkiProviderMutationCommand.StoreMedia): String? = gateway.storeMedia(command)
}

/**
 * Performs one request-aligned media saga. The returned acknowledgements are not live run state;
 * callbacks admit them only after the RESULT_READY response has been canonically encoded.
 */
internal class JournalBackedMediaMutationService(
    private val registry: AnkiRunStateRegistry,
    private val journal: MediaMutationJournal,
    private val staging: MediaMutationStaging,
    private val provider: MediaMutationProvider,
) : MediaMutationService {
    private val stagingRecoveryRequired = AtomicBoolean(false)
    private val stagingRecoveryLock = Any()

    override fun store(
        owner: AnkiRunStateRegistry.RunOwner,
        request: StoreMediaRequest,
    ): StoreMediaMutationOutcome {
        require(owner.runId == request.runId) { "Media request belongs to a different run owner" }
        val durableRequest = JournalRequest.from(request)
        when (val replay = journal.replay(durableRequest)) {
            is ReplayResult.Ready -> return replayOutcome(request, replay.response)
            ReplayResult.Missing -> Unit
            ReplayResult.DigestMismatch -> throw mediaMutationConflict("The durable media request digest changed")
            ReplayResult.NotReplayable -> throw mediaMutationConflict("The durable media request is not replayable")
            ReplayResult.LiveOwnerRequired -> throw mediaMutationConflict("Media replay requires a live run owner")
        }

        ensureStagingRecovered()
        journal.begin(durableRequest)
        val admissions =
            try {
                journal.acquireLease(request.runId)
                journal.reserve(
                    request.runId,
                    request.assets.map { asset -> asset.toReservation(request.requestId) },
                )
            } catch (failure: JournalInvariantViolation) {
                return refuseBatchRowLocally(request, durableRequest, failure)
            }
        requireReservationBatch(request, admissions)
        val reservations =
            admissions.map { admission ->
                (admission as? MediaReservationAdmission.Reserved)?.reservation
            }
        val unusedReservations =
            reservations.filterNotNull().associateByTo(linkedMapOf(), MediaReservationRecord::id)

        for ((index, asset) in request.assets.withIndex()) {
            val admission = admissions[index]
            if (admission is MediaReservationAdmission.Refused) {
                appendAdmissionRefusal(durableRequest.key, index, asset, admission.failure)
                continue
            }
            val reservation = checkNotNull(reservations[index])
            try {
                ensureStagingRecovered()
            } catch (failure: PendingMediaStagingRecoveryException) {
                return degradeRemainingRowsLocally(
                    request = request,
                    durableRequest = durableRequest,
                    fromIndex = index,
                    admissions = admissions,
                    reservations = reservations,
                    unusedReservations = unusedReservations,
                    failure = failure,
                )
            }
            val staged =
                try {
                    staging.stage(asset.toStagingRequest(request, index))
                } catch (failure: AnkiMediaStagingException) {
                    stagingRecoveryRequired.set(true)
                    journal.releaseReservation(reservation.id)
                    unusedReservations.remove(reservation.id)
                    journal.append(
                        durableRequest.key,
                        AlignedResult.MediaFailed(
                            requestIndex = index,
                            itemId = asset.assetId,
                            rowError = rowLocalMediaFailure(failure),
                            compactEvidence = stagingEvidence(failure),
                        ),
                    )
                    continue
                }

            val granted =
                try {
                    staging.grantRead(staged)
                } catch (failure: AnkiMediaStagingException) {
                    stagingRecoveryRequired.set(true)
                    journal.releaseReservation(reservation.id)
                    unusedReservations.remove(reservation.id)
                    journal.append(
                        durableRequest.key,
                        AlignedResult.MediaFailed(
                            requestIndex = index,
                            itemId = asset.assetId,
                            rowError = rowLocalMediaFailure(failure),
                            compactEvidence = stagingEvidence(failure),
                        ),
                    )
                    continue
                }

            val promotion =
                try {
                    journal.promote(
                        durableRequest.key,
                        reservation.id,
                        MutationCommand.StoreMedia(
                            requestIndexValue = index,
                            assetId = asset.assetId,
                            fileUri = granted.contentUri,
                            preferredName = asset.preferredName,
                        ),
                    )
                } catch (failure: RuntimeException) {
                    var provenNotCommitted = false
                    try {
                        val durableReservation = journal.reservation(reservation.id)
                        provenNotCommitted =
                            durableReservation?.state == MediaReservationState.RESERVED &&
                            durableReservation.claimId == null &&
                            durableReservation.runId == reservation.runId &&
                            durableReservation.requestId == reservation.requestId &&
                            durableReservation.assetId == reservation.assetId
                    } catch (probeFailure: RuntimeException) {
                        failure.addSuppressed(probeFailure)
                    }
                    if (provenNotCommitted) {
                        try {
                            journal.releaseReservation(reservation.id)
                        } catch (releaseFailure: RuntimeException) {
                            failure.addSuppressed(releaseFailure)
                        } finally {
                            unusedReservations.remove(reservation.id)
                            cleanupPreservingOutcome(granted)
                        }
                    }
                    throw failure
                }
            unusedReservations.remove(reservation.id)
            val scope =
                ProviderMutationScope(
                    requestId = request.requestId,
                    operation = ProviderMutationOperation.MEDIA_INSERT,
                    durableChildId = promotion.child.id,
                    itemIdentity = asset.assetId,
                )
            var capability: ProviderEntryCapability? = null
            var authorized = false
            try {
                val activeCapability = registry.beginProviderEntry(owner, scope)
                capability = activeCapability
                try {
                    provider.preflight(registry.cancellation(owner))
                } catch (failure: AnkiReadFailure) {
                    val error = failure.toJournalError().withoutRetryAfterStoredPrefix(durableRequest.key)
                    return stopBeforeProviderEntry(
                        owner = owner,
                        request = request,
                        durableRequest = durableRequest,
                        currentIndex = index,
                        promotion = promotion,
                        capability = activeCapability,
                        scope = scope,
                        reservations = reservations,
                        unusedReservations = unusedReservations,
                        staged = granted,
                        error = error,
                    )
                }

                when (registry.authorizeProviderEntry(owner, activeCapability, scope)) {
                    ProviderEntryAuthorization.AUTHORIZED -> authorized = true
                    ProviderEntryAuthorization.CANCELLED -> {
                        val error = cancelledBeforeEntry().withoutRetryAfterStoredPrefix(durableRequest.key)
                        return stopBeforeProviderEntry(
                            owner,
                            request,
                            durableRequest,
                            index,
                            promotion,
                            activeCapability,
                            scope,
                            reservations,
                            unusedReservations,
                            granted,
                            error,
                        )
                    }
                    ProviderEntryAuthorization.RELEASING -> {
                        val error = releasingBeforeEntry().withoutRetryAfterStoredPrefix(durableRequest.key)
                        return stopBeforeProviderEntry(
                            owner,
                            request,
                            durableRequest,
                            index,
                            promotion,
                            activeCapability,
                            scope,
                            reservations,
                            unusedReservations,
                            granted,
                            error,
                        )
                    }
                    ProviderEntryAuthorization.QUARANTINED -> {
                        val error = quarantinedBeforeEntry().withoutRetryAfterStoredPrefix(durableRequest.key)
                        return stopBeforeProviderEntry(
                            owner,
                            request,
                            durableRequest,
                            index,
                            promotion,
                            activeCapability,
                            scope,
                            reservations,
                            unusedReservations,
                            granted,
                            error,
                        )
                    }
                }

                journal.recordProviderEntry(promotion.child.id)
                val rawReceipt =
                    try {
                        provider.store(
                            AnkiProviderMutationCommand.StoreMedia(
                                fileUri = granted.contentUri,
                                preferredName = asset.preferredName,
                            ),
                        ).also { receipt ->
                            if (receipt == null) {
                                AppLog.w(
                                    LogComponent.MEDIA,
                                    "media.store",
                                    Throwable("AnkiDroid returned a null media-store receipt"),
                                    "outcome" to "fail",
                                    "entry_id" to promotion.child.id,
                                    "media_key" to asset.assetId,
                                    "receipt" to "null",
                                )
                            }
                        }
                    } catch (_: CancellationException) {
                        null
                    } catch (failure: RuntimeException) {
                        AppLog.e(
                            LogComponent.MEDIA,
                            "media.store",
                            failure,
                            "outcome" to "fail",
                            "entry_id" to promotion.child.id,
                            "media_key" to asset.assetId,
                            "receipt" to "exception",
                        )
                        null
                    }
                val receipt =
                    MediaInsertReceiptValidator.validate(rawReceipt)?.takeIf { candidate ->
                        try {
                            AnkiValidators.validateProviderFilename(candidate.actualFilename, asset)
                            true
                        } catch (_: AnkiProtocolException) {
                            false
                        }
                    }
                if (receipt == null) {
                    reconcileUncertain(promotion, asset, rawReceipt)
                    registry.completeProviderEntry(owner, activeCapability, scope)
                    capability = null
                    cleanupPreservingOutcome(granted)
                    return finishAfterUncertainty(
                        request,
                        durableRequest,
                        index,
                        reservations,
                        unusedReservations,
                    )
                }

                val durableReceipt = ProviderReceipt.Media(receipt.actualFilename, receipt.fileUri)
                val receiptAccepted =
                    try {
                        journal.commitReceipt(
                            promotion.child.id,
                            promotion.claim.id,
                            durableReceipt,
                            acceptedReceiptEvidence(asset, receipt),
                        )
                        true
                    } catch (_: RuntimeException) {
                        receiptWasDurablyAccepted(durableRequest.key, index, asset, promotion, receipt)
                    }
                if (!receiptAccepted) {
                    reconcileUncertain(promotion, asset, rawReceipt)
                    registry.completeProviderEntry(owner, activeCapability, scope)
                    capability = null
                    cleanupPreservingOutcome(granted)
                    return finishAfterUncertainty(
                        request,
                        durableRequest,
                        index,
                        reservations,
                        unusedReservations,
                    )
                }

                registry.completeProviderEntry(owner, activeCapability, scope)
                capability = null
                cleanupPreservingOutcome(granted)
            } catch (failure: RunReleasingException) {
                if (authorized) throw failure
                val error = releasingBeforeEntry().withoutRetryAfterStoredPrefix(durableRequest.key)
                return stopBeforeProviderEntry(
                    owner,
                    request,
                    durableRequest,
                    index,
                    promotion,
                    capability,
                    scope,
                    reservations,
                    unusedReservations,
                    granted,
                    error,
                )
            } catch (failure: RunCancelledException) {
                if (authorized) throw failure
                val error = cancelledBeforeEntry().withoutRetryAfterStoredPrefix(durableRequest.key)
                return stopBeforeProviderEntry(
                    owner,
                    request,
                    durableRequest,
                    index,
                    promotion,
                    capability,
                    scope,
                    reservations,
                    unusedReservations,
                    granted,
                    error,
                )
            } catch (failure: RunStateConflictException) {
                if (authorized) throw failure
                val error = quarantinedBeforeEntry().withoutRetryAfterStoredPrefix(durableRequest.key)
                return stopBeforeProviderEntry(
                    owner,
                    request,
                    durableRequest,
                    index,
                    promotion,
                    capability,
                    scope,
                    reservations,
                    unusedReservations,
                    granted,
                    error,
                )
            } finally {
                // An authorized capability deliberately remains installed if durable reconciliation
                // itself fails. Owner cleanup must then wait for recovery instead of losing the seam.
                val pendingCapability = capability
                if (pendingCapability != null && !authorized) {
                    try {
                        registry.abortProviderEntry(owner, pendingCapability, scope)
                    } catch (_: RuntimeException) {
                        // The primary durable failure remains authoritative.
                    }
                }
            }
        }

        releaseUnusedReservations(unusedReservations)
        return finishResult(request, durableRequest, topLevelError = null, replayed = false)
    }

    /**
     * Refuses every asset row-locally when the journal will not admit the batch at all.
     *
     * Lease and namespace admission happen before any reservation exists, so there is nothing to
     * roll back and no provider entry to reconcile — the batch simply cannot be stored. Answering
     * with a top-level error made the whole mining run fail with an unattributable
     * `internal_error` (Issue #6); a `media_store_failed` row per asset is the same outcome the
     * staging failures above already produce, and Python treats it as recoverable, so the run
     * creates its notes without that media instead of creating nothing.
     *
     * `JournalInvariantViolation` only: a [com.ankiminer.android.anki.journal.JournalCorruptionException]
     * means the durable state itself is untrustworthy and must stay fatal.
     */
    private fun refuseBatchRowLocally(
        request: StoreMediaRequest,
        durableRequest: JournalRequest,
        failure: JournalInvariantViolation,
    ): StoreMediaMutationOutcome {
        val fault = compactFaultToken(failure)
        val admission = failure as? MediaAdmissionViolation
        val reason = admission?.refusal?.name ?: "UNCLASSIFIED"
        val detail = admission?.detail?.let { ";$it" }.orEmpty()
        val evidence = "providerEntry=false;admission=refused;reason=$reason$detail;fault=$fault"
        request.assets.forEachIndexed { index, asset ->
            journal.append(
                durableRequest.key,
                AlignedResult.MediaFailed(
                    requestIndex = index,
                    itemId = asset.assetId,
                    rowError = refusedBatchMediaFailure(reason, detail, fault),
                    compactEvidence = evidence,
                ),
            )
        }
        return finishResult(request, durableRequest, topLevelError = null, replayed = false)
    }

    /**
     * Degrades the unprocessed tail of the batch when staging recovery is still pending.
     *
     * [ensureStagingRecovered] guards every asset because a quarantined staged file must not be
     * followed by another stage into the same directory. Letting its refusal escape the loop
     * unwound `store()` with the durable parent still RUNNING: rows appended only for the assets
     * already processed, no `markResultReady`, and an unattributable `internal_error` on the wire.
     * One transient undeletable staged file therefore failed a whole mining run whose earlier media
     * had already reached AnkiDroid.
     *
     * The tail takes the same `media_store_failed` row the in-loop staging failures produce, which
     * Python treats as recoverable, so the run creates its notes without that media. Rows that were
     * already refused admission keep their own typed refusal — the reason is more precise than the
     * recovery one and the caller has no other place to learn it.
     *
     * The pre-`journal.begin` call keeps the hard throw: there is no durable batch to degrade yet.
     */
    private fun degradeRemainingRowsLocally(
        request: StoreMediaRequest,
        durableRequest: JournalRequest,
        fromIndex: Int,
        admissions: List<MediaReservationAdmission>,
        reservations: List<MediaReservationRecord?>,
        unusedReservations: MutableMap<Long, MediaReservationRecord>,
        failure: PendingMediaStagingRecoveryException,
    ): StoreMediaMutationOutcome {
        val rowError = pendingStagingRecoveryMediaFailure(compactFaultToken(failure))
        for (index in fromIndex..request.assets.lastIndex) {
            val asset = request.assets[index]
            val admission = admissions[index]
            if (admission is MediaReservationAdmission.Refused) {
                appendAdmissionRefusal(durableRequest.key, index, asset, admission.failure)
                continue
            }
            reservations[index]?.let { reservation ->
                if (unusedReservations.remove(reservation.id) != null) {
                    journal.releaseReservation(reservation.id)
                }
            }
            journal.append(
                durableRequest.key,
                AlignedResult.MediaFailed(
                    requestIndex = index,
                    itemId = asset.assetId,
                    rowError = rowError,
                    compactEvidence = PENDING_STAGING_RECOVERY_EVIDENCE,
                ),
            )
        }
        releaseUnusedReservations(unusedReservations)
        return finishResult(request, durableRequest, topLevelError = null, replayed = false)
    }

    private fun appendAdmissionRefusal(
        key: ParentKey,
        index: Int,
        asset: MediaAsset,
        failure: MediaAdmissionViolation,
    ) {
        val fault = compactFaultToken(failure)
        val detail = failure.detail?.let { ";$it" }.orEmpty()
        val reason = failure.refusal.name
        journal.append(
            key,
            AlignedResult.MediaFailed(
                requestIndex = index,
                itemId = asset.assetId,
                rowError = refusedBatchMediaFailure(reason, detail, fault),
                compactEvidence =
                    "providerEntry=false;admission=refused;reason=$reason$detail;fault=$fault",
            ),
        )
    }

    private fun stopBeforeProviderEntry(
        owner: AnkiRunStateRegistry.RunOwner,
        request: StoreMediaRequest,
        durableRequest: JournalRequest,
        currentIndex: Int,
        promotion: MediaPromotion,
        capability: ProviderEntryCapability?,
        scope: ProviderMutationScope,
        reservations: List<MediaReservationRecord?>,
        unusedReservations: MutableMap<Long, MediaReservationRecord>,
        staged: StagingRecord,
        error: JournalError,
    ): StoreMediaMutationOutcome {
        val evidence =
            "providerEntry=false;asset=${request.assets[currentIndex].assetId};code=${error.code.name}"
        journal.completeFailure(
            childId = promotion.child.id,
            claimId = promotion.claim.id,
            childOutcome = ChildState.PROVEN_NOT_COMMITTED,
            claimState = MediaClaimState.CLEANED_VERIFIED,
            result = AlignedResult.MediaNotAttempted(currentIndex, request.assets[currentIndex].assetId),
            evidence = evidence,
        )
        if (capability != null) registry.abortProviderEntry(owner, capability, scope)
        cleanupPreservingOutcome(staged)
        for (suffixIndex in currentIndex + 1..request.assets.lastIndex) {
            reservations[suffixIndex]?.let { reservation ->
                if (unusedReservations.remove(reservation.id) != null) {
                    journal.releaseReservation(reservation.id)
                }
            }
            journal.append(
                durableRequest.key,
                AlignedResult.MediaNotAttempted(suffixIndex, request.assets[suffixIndex].assetId),
            )
        }
        releaseUnusedReservations(unusedReservations)
        return finishResult(request, durableRequest, error, replayed = false)
    }

    private fun finishAfterUncertainty(
        request: StoreMediaRequest,
        durableRequest: JournalRequest,
        currentIndex: Int,
        reservations: List<MediaReservationRecord?>,
        unusedReservations: MutableMap<Long, MediaReservationRecord>,
    ): StoreMediaMutationOutcome {
        for (suffixIndex in currentIndex + 1..request.assets.lastIndex) {
            reservations[suffixIndex]?.let { reservation ->
                if (unusedReservations.remove(reservation.id) != null) {
                    journal.releaseReservation(reservation.id)
                }
            }
            journal.append(
                durableRequest.key,
                AlignedResult.MediaNotAttempted(suffixIndex, request.assets[suffixIndex].assetId),
            )
        }
        releaseUnusedReservations(unusedReservations)
        return finishResult(request, durableRequest, postCommitUncertain(), replayed = false)
    }

    private fun reconcileUncertain(
        promotion: MediaPromotion,
        asset: MediaAsset,
        rawReceipt: String?,
    ) {
        journal.completeFailure(
            childId = promotion.child.id,
            claimId = promotion.claim.id,
            childOutcome = ChildState.COMMIT_UNCERTAIN,
            claimState = MediaClaimState.COMMIT_UNCERTAIN,
            result =
                AlignedResult.MediaUncertain(
                    (promotion.child.command as MutationCommand.StoreMedia).requestIndex,
                    asset.assetId,
                ),
            evidence = uncertainReceiptEvidence(asset, rawReceipt),
        )
    }

    private fun receiptWasDurablyAccepted(
        key: ParentKey,
        requestIndex: Int,
        asset: MediaAsset,
        promotion: MediaPromotion,
        receipt: MediaInsertReceipt,
    ): Boolean {
        val claim =
            try {
                journal.claim(key, asset.assetId)
            } catch (_: RuntimeException) {
                return false
            }
        val row =
            try {
                journal.results(key).getOrNull(requestIndex)
            } catch (_: RuntimeException) {
                return false
            }
        return claim != null &&
            claim.matches(asset, key) &&
            claim.id == promotion.claim.id &&
            claim.state == MediaClaimState.STORED &&
            claim.actualFilename == receipt.actualFilename &&
            row == AlignedResult.MediaStored(
                requestIndex,
                asset.assetId,
                receipt.actualFilename,
                acceptedReceiptEvidence(asset, receipt),
            )
    }

    private fun finishResult(
        request: StoreMediaRequest,
        durableRequest: JournalRequest,
        topLevelError: JournalError?,
        replayed: Boolean,
    ): StoreMediaMutationOutcome {
        val rows = journal.results(durableRequest.key)
        val durableResponse = JournalResponse.StoreMedia(durableRequest.key, rows, topLevelError)
        journal.markResultReady(durableRequest, durableResponse)
        return outcomeFromDurable(request, durableResponse, replayed)
    }

    private fun replayOutcome(
        request: StoreMediaRequest,
        response: JournalResponse,
    ): StoreMediaMutationOutcome {
        val media = response as? JournalResponse.StoreMedia
            ?: throw mediaMutationConflict("The durable response operation does not match storeMedia")
        return outcomeFromDurable(request, media, replayed = true)
    }

    private fun outcomeFromDurable(
        request: StoreMediaRequest,
        response: JournalResponse.StoreMedia,
        replayed: Boolean,
    ): StoreMediaMutationOutcome {
        if (response.key != ParentKey(request.runId, request.requestId)) {
            throw mediaMutationConflict("The durable media response identity changed")
        }
        if (response.results.size != request.assets.size) {
            throw mediaMutationConflict("The durable media response is not request-aligned")
        }
        val acknowledgements = ArrayList<MediaAcknowledgement>()
        val protocolRows =
            response.results.mapIndexed { index, row ->
                val asset = request.assets[index]
                if (row.requestIndex != index || row.itemId != asset.assetId) {
                    throw mediaMutationConflict("The durable media row identity changed")
                }
                when (row) {
                    is AlignedResult.MediaStored -> {
                        val claim = journal.claim(response.key, row.itemId)
                            ?: throw mediaMutationConflict("A durable stored media row lost its claim")
                        if (
                            !claim.matches(asset, response.key) ||
                            claim.actualFilename != row.actualFilename ||
                            claim.state !in REPLAYABLE_MEDIA_CLAIM_STATES
                        ) {
                            throw mediaMutationConflict("A durable stored media claim conflicts with its response")
                        }
                        acknowledgements +=
                            MediaAcknowledgement(
                                assetId = row.itemId,
                                actualFilename = row.actualFilename,
                                durableClaimId = claim.id,
                            )
                        StoredMedia(row.itemId, row.actualFilename)
                    }
                    is AlignedResult.MediaFailed -> FailedMedia(row.itemId, row.rowError.toProtocolError())
                    is AlignedResult.MediaUncertain -> UncertainMedia(row.itemId)
                    is AlignedResult.MediaNotAttempted -> NotAttemptedMedia(row.itemId)
                    else -> throw mediaMutationConflict("A durable non-media row entered storeMedia")
                }
            }
        return StoreMediaMutationOutcome(
            result =
                StoreMediaResult(
                    runId = request.runId,
                    requestId = request.requestId,
                    results = protocolRows,
                    error = response.error?.toProtocolError(),
                ),
            mediaAcknowledgements = acknowledgements,
            replayed = replayed,
        )
    }

    private fun JournalError.withoutRetryAfterStoredPrefix(key: ParentKey): JournalError =
        if (retryable && journal.results(key).any { it is AlignedResult.MediaStored }) copy(retryable = false) else this

    private fun releaseUnusedReservations(unused: MutableMap<Long, MediaReservationRecord>) {
        val iterator = unused.entries.iterator()
        while (iterator.hasNext()) {
            val reservation = iterator.next().value
            journal.releaseReservation(reservation.id)
            iterator.remove()
        }
    }

    private fun cleanupPreservingOutcome(record: StagingRecord) {
        try {
            if (staging.cleanup(record) == AnkiMediaCleanupOutcome.QUARANTINED) {
                stagingRecoveryRequired.set(true)
            }
        } catch (_: RuntimeException) {
            // Staging owns durable quarantine/recovery; mutation evidence must never be rewritten.
            stagingRecoveryRequired.set(true)
        }
    }

    private fun ensureStagingRecovered() {
        if (!stagingRecoveryRequired.get()) return
        synchronized(stagingRecoveryLock) {
            if (!stagingRecoveryRequired.get()) return
            val report = staging.recover()
            if (report.cleanedRecords < 0 || report.quarantinedRecords < 0 || report.sweptOrphans < 0) {
                throw JournalCorruptionException("Media staging recovery returned an invalid report")
            }
            if (!report.isClean) throw PendingMediaStagingRecoveryException()
            stagingRecoveryRequired.set(false)
        }
    }
}

private fun MediaAsset.toReservation(requestId: String) =
    MediaReservationDraft(
        requestId = requestId,
        assetId = assetId,
        requestedFilename = requestedFilename,
        preferredName = preferredName,
        sha256 = expectedSha256,
        purpose = JournalMediaPurpose.valueOf(purpose.name),
        mediaKind = JournalMediaKind.valueOf(mediaKind.name),
    )

private fun MediaAsset.toStagingRequest(
    request: StoreMediaRequest,
    index: Int,
) =
    AnkiMediaStagingRequest(
        runId = request.runId,
        requestId = request.requestId,
        assetId = assetId,
        absoluteSourcePath = sourcePath,
        expectedSizeBytes = expectedSizeBytes,
        expectedSha256 = expectedSha256,
        aggregateRemainingBytes = request.assets.drop(index).sumOf(MediaAsset::expectedSizeBytes),
        extension = AnkiMediaExtensions.sanitizedExtension(requestedFilename, mediaKind),
    )

private fun requireReservationBatch(
    request: StoreMediaRequest,
    admissions: List<MediaReservationAdmission>,
) {
    if (admissions.size != request.assets.size) throw mediaMutationConflict("The media reservation batch is incomplete")
    admissions.forEachIndexed { index, admission ->
        val asset = request.assets[index]
        if (admission.assetId != asset.assetId) {
            throw mediaMutationConflict("The media reservation batch changed identity")
        }
        val reservation = (admission as? MediaReservationAdmission.Reserved)?.reservation ?: return@forEachIndexed
        if (
            reservation.runId != request.runId ||
            reservation.requestId != request.requestId ||
            reservation.assetId != asset.assetId ||
            reservation.requestedFilename != asset.requestedFilename ||
            reservation.preferredName != asset.preferredName ||
            reservation.sha256 != asset.expectedSha256 ||
            reservation.purpose.name != asset.purpose.name ||
            reservation.mediaKind.name != asset.mediaKind.name
        ) {
            throw mediaMutationConflict("The media reservation batch changed identity")
        }
    }
}

private fun MediaClaimRecord.matches(
    asset: MediaAsset,
    key: ParentKey,
): Boolean =
    runId == key.runId &&
        requestId == key.requestId &&
        assetId == asset.assetId &&
        requestedFilename == asset.requestedFilename &&
        preferredName == asset.preferredName &&
        sha256 == asset.expectedSha256 &&
        purpose.name == asset.purpose.name &&
        mediaKind.name == asset.mediaKind.name

private fun JournalError.toProtocolError() =
    AnkiErrorDetail(
        code = AnkiErrorCode.valueOf(code.name),
        message = message,
        retryable = retryable,
    )

private fun AnkiReadFailure.toJournalError() =
    JournalError(
        code = JournalErrorCode.valueOf(code.name),
        message = stableMessage,
        retryable = retryable,
    )

/**
 * Names WHY the asset could not be staged, inside the one field that reaches the user.
 *
 * The typed failure and its fault digest were already recorded in `compact_evidence`, but that column
 * lives in the app-private journal database: on a release build there is no way to read it, so a field
 * report of the "N media file(s) could not be stored in Anki" warning could not be attributed to a
 * cause at all. The message crosses the callback seam into the Python adapter, which logs it to
 * `anki_miner.log` — the file "Share engine log" exposes in every build.
 *
 * [compactFaultToken] exists for exactly this carrier and is bounded and PII-safe by construction
 * (see its own docs); [AnkiMediaStagingFailure] names are compile-time ASCII. The cause is digested
 * in preference to the wrapper because it names the throwing frame — which is the whole point, since
 * `PREPARATION_FAILED` alone spans `contentUriFor`, `createDestination`, and the source-approval gate.
 */
private fun rowLocalMediaFailure(failure: AnkiMediaStagingException) =
    JournalError(
        JournalErrorCode.MEDIA_STORE_FAILED,
        "The media asset could not be staged for AnkiDroid " +
            "(staging=${failure.failure.name} fault=${compactFaultToken(failure.cause ?: failure)})",
        retryable = false,
    )

/**
 * The batch-admission refusal counterpart.
 *
 * [reason] carries the diagnosis, not [fault]: R8 minifies the exception and frame names the digest
 * reports, so a release build renders the token as `t0 @ a.W:342`. The typed
 * [MediaAdmissionRefusal] name is a value and survives. The digest is retained for the
 * `UNCLASSIFIED` case, where it is the only thing left to go on.
 */
private fun refusedBatchMediaFailure(
    reason: String,
    detail: String,
    fault: String,
) = JournalError(
    JournalErrorCode.MEDIA_STORE_FAILED,
    "The media asset could not be staged for AnkiDroid " +
        "(admission=refused reason=$reason${detail.replace(';', ' ')} fault=$fault)",
    retryable = false,
)

/**
 * The staging-recovery counterpart, shaped like [rowLocalMediaFailure] so the two read alike.
 *
 * `RECOVERY_PENDING` is not an [AnkiMediaStagingFailure] value — no exception was thrown here.
 * Staging refused to hand out another private copy while a quarantined artifact is unresolved, and
 * that is the sentence the field report needs.
 */
private fun pendingStagingRecoveryMediaFailure(fault: String) =
    JournalError(
        JournalErrorCode.MEDIA_STORE_FAILED,
        "The media asset could not be staged for AnkiDroid " +
            "(staging=RECOVERY_PENDING fault=$fault)",
        retryable = false,
    )

private fun cancelledBeforeEntry() =
    JournalError(
        JournalErrorCode.CANCELLED,
        "The Anki media operation was cancelled before provider entry",
        retryable = false,
    )

private fun releasingBeforeEntry() =
    JournalError(
        JournalErrorCode.CANCELLED,
        "The Anki run was released before media provider entry",
        retryable = false,
    )

private fun quarantinedBeforeEntry() =
    JournalError(
        JournalErrorCode.INTERNAL_ERROR,
        "The Anki run was quarantined before media provider entry",
        retryable = false,
    )

private fun postCommitUncertain() =
    JournalError(
        JournalErrorCode.POST_COMMIT_UNCERTAIN,
        "AnkiDroid may have stored the media asset, but its result could not be accepted",
        retryable = false,
    )

private fun stagingEvidence(failure: AnkiMediaStagingException): String =
    "providerEntry=false;staging=${failure.failure.name}"

private fun acceptedReceiptEvidence(
    asset: MediaAsset,
    receipt: MediaInsertReceipt,
): String =
    "providerEntry=true;asset=${asset.assetId};actual=${receipt.actualFilename};receipt=canonical"

private fun uncertainReceiptEvidence(
    asset: MediaAsset,
    rawReceipt: String?,
): String =
    buildString(256) {
        append("providerEntry=true;asset=")
        append(asset.assetId)
        append(";receipt=")
        if (rawReceipt == null) {
            append("null-or-exception")
        } else {
            append("invalid;utf16Length=")
            append(rawReceipt.length)
            append(";utf16Sha256=")
            append(utf16Sha256(rawReceipt))
        }
    }

private fun utf16Sha256(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    value.forEach { character ->
        digest.update(ByteBuffer.allocate(Char.SIZE_BYTES).putChar(character).array())
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

private fun mediaMutationConflict(message: String) = IllegalStateException(message)

private const val PENDING_STAGING_RECOVERY_EVIDENCE = "providerEntry=false;staging=RECOVERY_PENDING"

private val REPLAYABLE_MEDIA_CLAIM_STATES =
    setOf(
        // STORED is the exact storeMedia terminal state. ATTACHED_VERIFIED preserves the same
        // immutable identity after createNotes has proved the attachment.
        MediaClaimState.STORED,
        MediaClaimState.ATTACHED_VERIFIED,
    )
