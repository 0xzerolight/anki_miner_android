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
import com.ankiminer.android.anki.protocol.VerifyTargetRequest

internal fun interface AnkiProviderResponseEncoder {
    fun encode(
        response: AnkiResponse,
        request: AnkiRequest,
    ): String
}

/** Synchronous EngineCallbacks-facing dispatcher. Every method is called by the parked Python worker. */
internal class AnkiProviderCallbacks(
    private val registry: AnkiRunStateRegistry,
    private val reads: AnkiProviderReadService,
    private val workerThreadGuard: WorkerThreadGuard,
    private val responseEncoder: AnkiProviderResponseEncoder =
        AnkiProviderResponseEncoder(AnkiJsonCodec::encodeResponse),
) {
    fun registerRun(
        runId: String,
        cancellation: AnkiCancellation = AnkiCancellation.NONE,
    ): Boolean = AnkiValidators.isValidRunId(runId) && registry.register(runId, cancellation)

    fun ankiVerifyTarget(rawRequest: String): String =
        dispatchOwned(AnkiOperation.VERIFY_TARGET, rawRequest) { request, owner ->
            val typed = request as VerifyTargetRequest
            reads.verifyTarget(owner, typed)
        }

    fun ankiScanFirstFields(rawRequest: String): String =
        dispatchOwned(AnkiOperation.SCAN_FIRST_FIELDS, rawRequest) { request, owner ->
            val typed = request as ScanFirstFieldsRequest
            reads.scanFirstFields(owner, typed)
        }

    fun ankiStoreMedia(rawRequest: String): String =
        dispatchOwned(AnkiOperation.STORE_MEDIA, rawRequest) { request, _ ->
            request as StoreMediaRequest
            throw AnkiReadFailure(
                AnkiErrorCode.UNSUPPORTED_OPERATION,
                retryable = false,
                stableMessage = "Anki media mutation is not available in the read-only provider phase",
            )
        }

    fun ankiCreateNotes(rawRequest: String): String =
        dispatchOwned(AnkiOperation.CREATE_NOTES, rawRequest) { request, _ ->
            request as CreateNotesRequest
            throw AnkiReadFailure(
                AnkiErrorCode.UNSUPPORTED_OPERATION,
                retryable = false,
                stableMessage = "Anki note mutation is not available in the read-only provider phase",
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
        handler: (AnkiRequest, AnkiRunStateRegistry.RunOwner) -> AnkiResponse,
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
                val response = responseFor(request) { handler(request, owner) }
                encodeOwned(owner, request, response)
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
        } catch (_: RuntimeException) {
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

    private fun failureResponse(
        request: AnkiRequest,
        failure: RuntimeException,
    ): AnkiResponse =
        when (failure) {
            is AnkiReadFailure ->
                request.error(failure.code, failure.stableMessage, failure.retryable)
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
            else ->
                request.error(
                    AnkiErrorCode.INTERNAL_ERROR,
                    "The Anki provider operation failed safely",
                    retryable = false,
                )
        }

    private fun encodeOwned(
        owner: AnkiRunStateRegistry.RunOwner,
        request: AnkiRequest,
        response: AnkiResponse,
    ): String =
        try {
            responseEncoder.encode(response, request)
        } catch (_: RuntimeException) {
            registry.markTerminalResponseFailure(owner)
            encodeUnowned(
                request,
                request.error(
                    AnkiErrorCode.INTERNAL_ERROR,
                    "The Anki provider response failed validation",
                    retryable = false,
                ),
            )
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
    }
}
