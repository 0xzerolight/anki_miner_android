package com.ankiminer.android.anki.provider

import com.ankiminer.android.anki.protocol.AnkiErrorCode
import com.ankiminer.android.anki.protocol.AnkiErrorResult
import com.ankiminer.android.anki.protocol.AnkiJsonCodec
import com.ankiminer.android.anki.protocol.AnkiOperation
import com.ankiminer.android.anki.protocol.AnkiProtocolException
import com.ankiminer.android.anki.protocol.AnkiRequest
import com.ankiminer.android.anki.protocol.AnkiResponse
import com.ankiminer.android.anki.protocol.AnkiValidators
import com.ankiminer.android.anki.protocol.CreateNotesRequest
import com.ankiminer.android.anki.protocol.ReleaseRunStateRequest
import com.ankiminer.android.anki.protocol.ReleaseRunStateResult
import com.ankiminer.android.anki.protocol.ScanFirstFieldsRequest
import com.ankiminer.android.anki.protocol.StoreMediaRequest
import com.ankiminer.android.anki.protocol.StoreMediaResult
import com.ankiminer.android.anki.protocol.StoredMedia
import com.ankiminer.android.anki.protocol.VerifyTargetRequest
import com.ankiminer.android.diagnostics.AnkiFaultRecorder
import com.ankiminer.android.diagnostics.compactFaultToken
import com.ankiminer.android.diagnostics.log.AppLog
import com.ankiminer.android.diagnostics.log.LogComponent
import java.util.Collections

internal fun interface AnkiProviderResponseEncoder {
    fun encode(
        response: AnkiResponse,
        request: AnkiRequest,
    ): String
}

/** Frozen media admission candidate, validated only after its exact result encodes successfully. */
internal class DurableMediaAdmission private constructor(
    val exactResult: StoreMediaResult,
    private val acknowledgements: List<MediaAcknowledgement>,
) {
    fun validateAfterEncoding(
        request: AnkiRequest,
        response: AnkiResponse,
    ): List<MediaAcknowledgement> {
        if (response !== exactResult) throw IllegalStateException("The encoded durable media result changed identity")
        AnkiValidators.validateResponseForRequest(exactResult, request)
        requireBijection(exactResult, acknowledgements)
        return acknowledgements
    }

    companion object {
        fun freeze(
            result: StoreMediaResult,
            acknowledgements: List<MediaAcknowledgement>,
        ): DurableMediaAdmission =
            DurableMediaAdmission(
                exactResult =
                    result.copy(
                        results = Collections.unmodifiableList(ArrayList(result.results)),
                    ),
                acknowledgements = Collections.unmodifiableList(ArrayList(acknowledgements)),
            )

        private fun requireBijection(
            result: StoreMediaResult,
            acknowledgements: List<MediaAcknowledgement>,
        ) {
            val storedRows = result.results.filterIsInstance<StoredMedia>()
            if (
                storedRows.map(StoredMedia::assetId).distinct().size != storedRows.size ||
                storedRows.map(StoredMedia::actualFilename).distinct().size != storedRows.size
            ) {
                throw IllegalStateException("The durable media result contains duplicate stored identities")
            }
            if (
                acknowledgements.any { it.durableClaimId <= 0L } ||
                acknowledgements.map(MediaAcknowledgement::assetId).distinct().size != acknowledgements.size ||
                acknowledgements.map(MediaAcknowledgement::actualFilename).distinct().size != acknowledgements.size ||
                acknowledgements.map(MediaAcknowledgement::durableClaimId).distinct().size != acknowledgements.size
            ) {
                throw IllegalStateException("The durable media acknowledgements contain duplicate or invalid identities")
            }

            val storedByAsset = storedRows.associate { it.assetId to it.actualFilename }
            val acknowledgedByAsset = acknowledgements.associate { it.assetId to it.actualFilename }
            if (storedByAsset != acknowledgedByAsset) {
                throw IllegalStateException("The durable media result and acknowledgements are not bijective")
            }
        }
    }
}

/** Frozen createNotes result admitted only after exact request validation and canonical encoding. */
internal class DurableNoteAdmission private constructor(
    val exactResult: com.ankiminer.android.anki.protocol.CreateNotesResult,
) {
    fun validateAfterEncoding(request: AnkiRequest, response: AnkiResponse) {
        if (response !== exactResult) throw IllegalStateException("The encoded durable note result changed identity")
        AnkiValidators.validateResponseForRequest(exactResult, request)
    }

    companion object {
        fun freeze(result: com.ankiminer.android.anki.protocol.CreateNotesResult) =
            DurableNoteAdmission(
                result.copy(results = Collections.unmodifiableList(ArrayList(result.results))),
            )
    }
}

/** Synchronous EngineCallbacks-facing dispatcher. Every method is called by the parked Python worker. */
internal class AnkiProviderCallbacks(
    private val registry: AnkiRunStateRegistry,
    private val reads: AnkiProviderReadService,
    private val targetVerifier: AnkiTargetVerifier,
    private val mediaMutations: MediaMutationService,
    private val noteMutations: NoteMutationService =
        NoteMutationService { _, _ ->
            throw AnkiReadFailure(
                AnkiErrorCode.UNSUPPORTED_OPERATION,
                retryable = false,
                stableMessage = "Anki note mutation is not configured",
            )
        },
    private val workerThreadGuard: WorkerThreadGuard,
    private val startupRecoveryGate: AnkiStartupRecoveryGate = OpenAnkiStartupRecoveryGate,
    private val responseEncoder: AnkiProviderResponseEncoder =
        AnkiProviderResponseEncoder(AnkiJsonCodec::encodeResponse),
) {
    fun registerRun(
        runId: String,
        cancellation: AnkiCancellation = AnkiCancellation.NONE,
    ): Boolean {
        if (!AnkiValidators.isValidRunId(runId)) return false
        try {
            workerThreadGuard.checkWorkerThread()
            if (!startupRecoveryGate.isOpen()) {
                startupRecoveryGate.ensureRecovered()
            }
        } catch (_: RuntimeException) {
            return false
        }
        return registry.register(runId, cancellation)
    }

    fun ankiVerifyTarget(rawRequest: String): String =
        dispatchOwned(AnkiOperation.VERIFY_TARGET, rawRequest) { request, owner ->
            val typed = request as VerifyTargetRequest
            val reservation = registry.beginTargetVerification(owner, typed)
            try {
                val outcome = targetVerifier.verify(owner, reservation, typed)
                if (outcome.durable) {
                    OwnedResponse(
                        outcome.response,
                        DurableTargetCommit(reservation, outcome.targetForAdmission),
                    )
                } else {
                    registry.abortTargetVerification(owner, reservation)
                    OwnedResponse(outcome.response)
                }
            } catch (error: RuntimeException) {
                registry.abortTargetVerification(owner, reservation)
                throw error
            }
        }

    fun ankiScanFirstFields(rawRequest: String): String =
        dispatchOwned(AnkiOperation.SCAN_FIRST_FIELDS, rawRequest) { request, owner ->
            val typed = request as ScanFirstFieldsRequest
            OwnedResponse(reads.scanFirstFields(owner, typed))
        }

    fun ankiStoreMedia(rawRequest: String): String =
        dispatchOwned(AnkiOperation.STORE_MEDIA, rawRequest) { request, owner ->
            val typed = request as StoreMediaRequest
            val outcome = mediaMutations.store(owner, typed)
            val admission = DurableMediaAdmission.freeze(outcome.result, outcome.mediaAcknowledgements)
            OwnedResponse(
                response = admission.exactResult,
                durableMedia = admission,
            )
        }

    fun ankiCreateNotes(rawRequest: String): String =
        dispatchOwned(AnkiOperation.CREATE_NOTES, rawRequest) { request, owner ->
            val outcome = noteMutations.create(owner, request as CreateNotesRequest)
            val admission = DurableNoteAdmission.freeze(outcome.result)
            OwnedResponse(
                response = admission.exactResult,
                durableNote = admission,
            )
        }

    fun ankiReleaseRunState(rawRequest: String): String =
        dispatchUnowned(AnkiOperation.RELEASE_RUN_STATE, rawRequest) { request ->
            val typed = request as ReleaseRunStateRequest
            ReleaseRunStateResult(
                runId = typed.runId,
                requestId = typed.requestId,
                state = registry.release(typed.runId, typed.acknowledgeTerminalResponses),
            )
        }

    /** Callback-independent coordinator fallback. It must never acknowledge terminal responses. */
    fun releaseRunStateFallback(runId: String) = registry.release(runId, false)

    private fun dispatchOwned(
        operation: AnkiOperation,
        rawRequest: String,
        handler: (AnkiRequest, AnkiRunStateRegistry.RunOwner) -> OwnedResponse,
    ): String {
        guardFailure(operation)?.let { return it }
        val request =
            when (val decoded = decode(operation, rawRequest)) {
                is RequestDecode.Success -> decoded.request
                is RequestDecode.Failure ->
                    return AnkiJsonCodec.encodeProtocolError(operation, decoded.error)
            }
        return try {
            registry.withOwner(request.runId) { owner ->
                val handled =
                    try {
                        handler(request, owner)
                    } catch (failure: RuntimeException) {
                        OwnedResponse(failureResponse(request, failure))
                    }
                encodeOwned(owner, request, handled)
            }
        } catch (failure: RuntimeException) {
            encodeUnowned(request, failureResponse(request, failure))
        }
    }

    private fun dispatchUnowned(
        operation: AnkiOperation,
        rawRequest: String,
        handler: (AnkiRequest) -> AnkiResponse,
    ): String {
        guardFailure(operation)?.let { return it }
        val request =
            when (val decoded = decode(operation, rawRequest)) {
                is RequestDecode.Success -> decoded.request
                is RequestDecode.Failure ->
                    return AnkiJsonCodec.encodeProtocolError(operation, decoded.error)
            }
        return encodeUnowned(request, responseFor(request) { handler(request) })
    }

    private fun guardFailure(operation: AnkiOperation): String? =
        try {
            workerThreadGuard.checkWorkerThread()
            null
        } catch (failure: RuntimeException) {
            AppLog.e(
                LogComponent.ANKI,
                operation.wireName,
                failure,
                "outcome" to "fail",
                "wire_run" to PLACEHOLDER_RUN_ID,
            )
            fixedErrorEnvelope(
                runId = PLACEHOLDER_RUN_ID,
                requestId = PLACEHOLDER_REQUEST_ID,
                operation = operation,
                message = "The Anki callback must run on its worker thread",
            )
        }

    private fun decode(
        operation: AnkiOperation,
        rawRequest: String,
    ): RequestDecode =
        try {
            RequestDecode.Success(AnkiJsonCodec.decodeRequest(rawRequest, operation))
        } catch (error: AnkiProtocolException) {
            RequestDecode.Failure(error)
        }

    private sealed interface RequestDecode {
        data class Success(val request: AnkiRequest) : RequestDecode

        data class Failure(val error: AnkiProtocolException) : RequestDecode
    }

    private fun responseFor(
        request: AnkiRequest,
        handler: () -> AnkiResponse,
    ): AnkiResponse =
        try {
            handler()
        } catch (failure: RuntimeException) {
            failureResponse(request, failure)
        }

    /**
     * Maps a handler failure to a stable typed error, or to the digested catch-all below.
     *
     * What can reach the catch-all, audited against Issue #6 (reads working, zero notes created):
     *
     * * `JournalInvariantViolation` from media lease/namespace admission — the one shape reachable on
     *   ordinary content, because media names are content-addressed and an earlier run's unresolved
     *   claim keeps its namespace family. `JournalBackedMediaMutationService.refuseBatchRowLocally`
     *   now degrades it to `media_store_failed`, so it no longer stops a run.
     * * `JournalInvariantViolation` / `JournalCorruptionException` from the note saga, `promote`,
     *   `append`, `markResultReady`, and the `results()` reads — deliberately fatal: durable evidence
     *   is either trustworthy or the run must stop.
     * * `IllegalArgumentException` from the sealed provider commands, e.g. `CreateDeck`'s exact
     *   deck-name check. Only reachable when the target deck must be created.
     * * `IllegalStateException` from the `mediaMutationConflict` / `noteMutationConflict` helpers when
     *   a durable replay disagrees with its live request.
     *
     * Anything new that lands here is a design gap, not a category: give it a typed branch above, or
     * degrade it where it is raised. The token in the message is what tells the two apart in the field.
     */
    private fun failureResponse(
        request: AnkiRequest,
        failure: RuntimeException,
    ): AnkiResponse =
        when (failure) {
            is AnkiReadFailure -> {
                AppLog.e(
                    LogComponent.ANKI,
                    request.operation.wireName,
                    failure.cause ?: failure,
                    "outcome" to "fail",
                    "code" to failure.code.wireName,
                )
                request.error(failure.code, failure.stableMessage, failure.retryable)
            }
            is RunNotRegisteredException ->
                request.error(
                    AnkiErrorCode.INVALID_REQUEST,
                    "The Anki run is not registered",
                    retryable = false,
                )
            is RunReleasingException ->
                request.error(
                    AnkiErrorCode.CANCELLED,
                    "The Anki run is already releasing",
                    retryable = false,
                )
            is RunCancelledException ->
                request.error(
                    AnkiErrorCode.CANCELLED,
                    "The Anki operation was cancelled",
                    retryable = false,
                )
            is InvalidCapabilityException ->
                request.error(
                    AnkiErrorCode.INVALID_REQUEST,
                    "The Anki capability is invalid or already consumed",
                    retryable = false,
                )
            is RunStateConflictException ->
                request.error(
                    AnkiErrorCode.INVALID_REQUEST,
                    "The Anki request conflicts with live run state",
                    retryable = false,
                )
            is RunStateCapacityException ->
                request.error(
                    AnkiErrorCode.INTERNAL_ERROR,
                    "The bounded Anki run state is full",
                    retryable = false,
                )
            is TargetVerificationInProgressException ->
                request.error(
                    AnkiErrorCode.INVALID_REQUEST,
                    "Another Anki target verification is already active",
                    retryable = false,
                )
            else ->
                request.error(
                    AnkiErrorCode.INTERNAL_ERROR,
                    unattributableFailureMessage(request.operation, failure),
                    retryable = false,
                )
        }

    /**
     * The stable sentence plus a bounded PII-safe fault token, with a copy in [AnkiFaultRecorder].
     *
     * This arm catches every `RuntimeException` the typed branches above do not name — in practice
     * journal invariant violations raised several layers down. Answering with the sentence alone made
     * the failure unattributable: no log, no digest, nothing on the wire, so a field report of this
     * shape (Issue #6) could not be traced to a throw site. The token carries the exception class and
     * topmost frame only, never the exception message.
     */
    private fun unattributableFailureMessage(
        operation: AnkiOperation,
        failure: RuntimeException,
    ): String {
        val token = compactFaultToken(failure)
        AnkiFaultRecorder.record(operation.wireName, token)
        return "$UNATTRIBUTABLE_FAILURE (${operation.wireName}: $token)"
    }

    private fun encodeOwned(
        owner: AnkiRunStateRegistry.RunOwner,
        request: AnkiRequest,
        handled: OwnedResponse,
    ): String {
        val durableTarget = handled.durableTarget
        val durableMedia = handled.durableMedia
        val durableNote = handled.durableNote
        val encoded =
            try {
                responseEncoder.encode(handled.response, request)
            } catch (_: RuntimeException) {
                registry.markTerminalResponseFailure(owner)
                durableTarget?.let { registry.abortTargetVerification(owner, it.reservation) }
                return encodeUnowned(
                    request,
                    request.error(
                        AnkiErrorCode.INTERNAL_ERROR,
                        "The Anki provider response failed validation",
                        retryable = false,
                    ),
                )
            }
        if (durableTarget == null && durableMedia == null && durableNote == null) return encoded
        try {
            if (durableTarget != null) {
                registry.commitDurableTargetResponse(
                    owner = owner,
                    reservation = durableTarget.reservation,
                    requestId = request.requestId,
                    target = durableTarget.target,
                )
            } else if (durableMedia != null) {
                val acknowledgements =
                    durableMedia.validateAfterEncoding(request, handled.response)
                registry.commitDurableMutationResponse(
                    owner = owner,
                    requestId = request.requestId,
                    mediaAcknowledgements = acknowledgements,
                )
            } else {
                checkNotNull(durableNote).validateAfterEncoding(request, handled.response)
                registry.retainDurableTerminalResponse(owner, request.requestId)
            }
        } catch (_: RuntimeException) {
            if (durableTarget != null) {
                registry.abortTargetVerification(owner, durableTarget.reservation)
            } else {
                try {
                    registry.markTerminalResponseFailure(owner)
                } catch (_: RuntimeException) {
                    // The durable admission failure remains authoritative.
                }
            }
            return encodeUnowned(
                request,
                request.error(
                    AnkiErrorCode.INTERNAL_ERROR,
                    "The durable Anki response could not be admitted",
                    retryable = false,
                ),
            )
        }
        return encoded
    }

    private fun encodeUnowned(
        request: AnkiRequest,
        response: AnkiResponse,
    ): String =
        try {
            responseEncoder.encode(response, request)
        } catch (_: RuntimeException) {
            fixedErrorEnvelope(
                runId = request.runId,
                requestId = request.requestId,
                operation = request.operation,
                message = "The Anki provider response failed validation",
            )
        }

    private fun fixedErrorEnvelope(
        runId: String,
        requestId: String,
        operation: AnkiOperation,
        message: String,
    ): String =
        buildString(256) {
            append("{\"schemaVersion\":1,\"type\":\"anki.error\",\"payload\":{\"runId\":\"")
            append(runId)
            append("\",\"requestId\":\"")
            append(requestId)
            append("\",\"operation\":\"")
            append(operation.wireName)
            append("\",\"code\":\"internal_error\",\"message\":\"")
            append(message)
            append("\",\"retryable\":false}}")
        }

    private fun AnkiRequest.error(
        code: AnkiErrorCode,
        message: String,
        retryable: Boolean,
    ) =
        AnkiErrorResult(
            runId = runId,
            requestId = requestId,
            operation = operation,
            code = code,
            message = message,
            retryable = retryable,
        )

    private companion object {
        const val PLACEHOLDER_RUN_ID = "run_00000000000000000000000000000000"
        const val PLACEHOLDER_REQUEST_ID = "anki_00000000000000000000000000000000"
        const val UNATTRIBUTABLE_FAILURE = "The Anki provider operation failed safely"
    }

    private data class DurableTargetCommit(
        val reservation: TargetVerificationReservation,
        val target: TargetSnapshot?,
    )

    private data class OwnedResponse(
        val response: AnkiResponse,
        val durableTarget: DurableTargetCommit? = null,
        val durableMedia: DurableMediaAdmission? = null,
        val durableNote: DurableNoteAdmission? = null,
    ) {
        init {
            require(listOfNotNull(durableTarget, durableMedia, durableNote).size <= 1) {
                "One response cannot carry multiple durable admissions"
            }
        }
    }
}
