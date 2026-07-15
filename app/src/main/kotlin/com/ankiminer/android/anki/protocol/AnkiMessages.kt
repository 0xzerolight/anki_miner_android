package com.ankiminer.android.anki.protocol

import com.ankiminer.android.anki.generated.AnkiLimitsV1

internal enum class AnkiOperation(
    val wireName: String,
    val requestType: String,
    val resultType: String,
    val requestEnvelopeMaxUtf8Bytes: Int,
    val resultEnvelopeMaxUtf8Bytes: Int,
) {
    VERIFY_TARGET(
        wireName = "verifyTarget",
        requestType = "anki.verifytarget.request",
        resultType = "anki.verifytarget.result",
        requestEnvelopeMaxUtf8Bytes = AnkiLimitsV1.VerifyTarget.REQUEST_ENVELOPE_MAX_UTF8_BYTES,
        resultEnvelopeMaxUtf8Bytes = AnkiLimitsV1.VerifyTarget.RESULT_ENVELOPE_MAX_UTF8_BYTES,
    ),
    SCAN_FIRST_FIELDS(
        wireName = "scanFirstFields",
        requestType = "anki.scanfirstfields.request",
        resultType = "anki.scanfirstfields.result",
        requestEnvelopeMaxUtf8Bytes = AnkiLimitsV1.ScanFirstFields.REQUEST_ENVELOPE_MAX_UTF8_BYTES,
        resultEnvelopeMaxUtf8Bytes = AnkiLimitsV1.ScanFirstFields.RESULT_ENVELOPE_MAX_UTF8_BYTES,
    ),
    STORE_MEDIA(
        wireName = "storeMedia",
        requestType = "anki.storemedia.request",
        resultType = "anki.storemedia.result",
        requestEnvelopeMaxUtf8Bytes = AnkiLimitsV1.StoreMedia.REQUEST_ENVELOPE_MAX_UTF8_BYTES,
        resultEnvelopeMaxUtf8Bytes = AnkiLimitsV1.StoreMedia.RESULT_ENVELOPE_MAX_UTF8_BYTES,
    ),
    CREATE_NOTES(
        wireName = "createNotes",
        requestType = "anki.createnotes.request",
        resultType = "anki.createnotes.result",
        requestEnvelopeMaxUtf8Bytes = AnkiLimitsV1.CreateNotes.REQUEST_ENVELOPE_MAX_UTF8_BYTES,
        resultEnvelopeMaxUtf8Bytes = AnkiLimitsV1.CreateNotes.RESULT_ENVELOPE_MAX_UTF8_BYTES,
    ),
    RELEASE_RUN_STATE(
        wireName = "releaseRunState",
        requestType = "anki.releaserunstate.request",
        resultType = "anki.releaserunstate.result",
        requestEnvelopeMaxUtf8Bytes = AnkiLimitsV1.ReleaseRunState.REQUEST_ENVELOPE_MAX_UTF8_BYTES,
        resultEnvelopeMaxUtf8Bytes = AnkiLimitsV1.ReleaseRunState.RESULT_ENVELOPE_MAX_UTF8_BYTES,
    ),
}

internal sealed interface AnkiRequest {
    val runId: String
    val requestId: String
    val operation: AnkiOperation
}

internal data class VerifyTargetRequest(
    override val runId: String,
    override val requestId: String,
    val deckName: String,
    val modelName: String,
    val requiredFields: List<String>,
) : AnkiRequest {
    override val operation: AnkiOperation = AnkiOperation.VERIFY_TARGET
}

internal sealed interface ScanScope

internal data class KnownVocabularyCursor(
    val ordinal: Long,
    val token: String,
)

internal data class KnownVocabularyScope(
    val excludedDecks: List<String>,
    val cursor: KnownVocabularyCursor?,
) : ScanScope

internal data class DuplicateCandidate(
    val key: String,
    val firstField: String,
)

internal data class DuplicateScanScope(
    val modelName: String,
    val firstFieldName: String,
    val deckName: String?,
    val candidates: List<DuplicateCandidate>,
    val occurrences: List<Int>,
    val invalidateBaselineToken: String?,
) : ScanScope

internal data class ScanFirstFieldsRequest(
    override val runId: String,
    override val requestId: String,
    val scope: ScanScope,
) : AnkiRequest {
    override val operation: AnkiOperation = AnkiOperation.SCAN_FIRST_FIELDS
}

internal enum class MediaPurpose(val wireName: String) {
    CARD("card"),
    DICTIONARY("dictionary"),
}

internal enum class MediaKind(val wireName: String) {
    AUDIO("audio"),
    IMAGE("image"),
}

internal data class MediaAsset(
    val assetId: String,
    val sourcePath: String,
    val preferredName: String,
    val requestedFilename: String,
    val purpose: MediaPurpose,
    val mediaKind: MediaKind,
    val expectedSizeBytes: Long,
    val expectedSha256: String,
)

internal data class StoreMediaRequest(
    override val runId: String,
    override val requestId: String,
    val assets: List<MediaAsset>,
) : AnkiRequest {
    override val operation: AnkiOperation = AnkiOperation.STORE_MEDIA
}

internal data class CreateDuplicateCandidate(
    val key: String,
    val firstField: String,
    val occurrence: Int,
)

internal data class MediaBinding(
    val assetId: String,
    val actualFilename: String,
)

internal data class CreateNote(
    val clientNoteId: String,
    val fields: Map<String, String>,
    val tags: List<String>,
    val duplicateCandidate: CreateDuplicateCandidate,
    val mediaBindings: List<MediaBinding>,
)

internal sealed interface CreateDuplicateScope

internal data object CollectionCreateDuplicateScope : CreateDuplicateScope

internal data class ExactDeckCreateDuplicateScope(
    val deckName: String,
) : CreateDuplicateScope

internal data class CreateNotesRequest(
    override val runId: String,
    override val requestId: String,
    val deckName: String,
    val modelName: String,
    val firstFieldName: String,
    val baselineToken: String,
    val duplicateScope: CreateDuplicateScope,
    val notes: List<CreateNote>,
) : AnkiRequest {
    override val operation: AnkiOperation = AnkiOperation.CREATE_NOTES
}

internal data class ReleaseRunStateRequest(
    override val runId: String,
    override val requestId: String,
    val acknowledgeTerminalResponses: Boolean,
) : AnkiRequest {
    override val operation: AnkiOperation = AnkiOperation.RELEASE_RUN_STATE
}

internal sealed interface AnkiResponse {
    val runId: String
    val requestId: String
    val operation: AnkiOperation
    val messageType: String
}

internal data class VerifyTargetResult(
    override val runId: String,
    override val requestId: String,
    val deckId: Long,
    val modelId: Long,
    val fieldNames: List<String>,
    val deckCreated: Boolean,
) : AnkiResponse {
    override val operation: AnkiOperation = AnkiOperation.VERIFY_TARGET
    override val messageType: String = operation.resultType
}

internal data class KnownVocabularyResult(
    override val runId: String,
    override val requestId: String,
    val firstFields: List<String>,
    val scannedNotes: Int,
    val nextCursor: KnownVocabularyCursor?,
) : AnkiResponse {
    override val operation: AnkiOperation = AnkiOperation.SCAN_FIRST_FIELDS
    override val messageType: String = operation.resultType
}

internal data class RawFirstFieldHit(
    val noteId: Long,
    val firstField: String,
)

internal data class DuplicateLookupResult(
    override val runId: String,
    override val requestId: String,
    val rawFirstFieldHits: List<List<RawFirstFieldHit>>,
    val baselineToken: String,
) : AnkiResponse {
    override val operation: AnkiOperation = AnkiOperation.SCAN_FIRST_FIELDS
    override val messageType: String = operation.resultType
}

internal enum class AnkiErrorCode(val wireName: String) {
    API_DISABLED("api_disabled"),
    PERMISSION_REQUIRED("permission_required"),
    NOTE_TYPE_NOT_FOUND("note_type_not_found"),
    FIELD_MISSING("field_missing"),
    FIELD_MAPPING_INVALID("field_mapping_invalid"),
    TARGET_INVALID("target_invalid"),
    PROVIDER_UNAVAILABLE("provider_unavailable"),
    QUERY_FAILED("query_failed"),
    WRITE_FAILED("write_failed"),
    TIMEOUT("timeout"),
    CANCELLED("cancelled"),
    MEDIA_STORE_FAILED("media_store_failed"),
    POST_COMMIT_UNCERTAIN("post_commit_uncertain"),
    INVALID_REQUEST("invalid_request"),
    UNSUPPORTED_OPERATION("unsupported_operation"),
    INTERNAL_ERROR("internal_error"),
}

internal data class AnkiErrorDetail(
    val code: AnkiErrorCode,
    val message: String,
    val retryable: Boolean,
)

internal sealed interface MediaStoreRow {
    val assetId: String
    val status: String
}

internal data class StoredMedia(
    override val assetId: String,
    val actualFilename: String,
) : MediaStoreRow {
    override val status: String = "stored"
}

internal data class FailedMedia(
    override val assetId: String,
    val error: AnkiErrorDetail,
) : MediaStoreRow {
    override val status: String = "failed"
}

internal data class UncertainMedia(override val assetId: String) : MediaStoreRow {
    override val status: String = "uncertain"
}

internal data class NotAttemptedMedia(override val assetId: String) : MediaStoreRow {
    override val status: String = "notAttempted"
}

internal data class StoreMediaResult(
    override val runId: String,
    override val requestId: String,
    val results: List<MediaStoreRow>,
    val error: AnkiErrorDetail?,
) : AnkiResponse {
    override val operation: AnkiOperation = AnkiOperation.STORE_MEDIA
    override val messageType: String = operation.resultType
}

internal sealed interface CreateNoteRow {
    val clientNoteId: String
    val status: String
}

internal data class CreatedNote(
    override val clientNoteId: String,
    val noteId: Long,
) : CreateNoteRow {
    override val status: String = "created"
}

internal data class DuplicateNote(override val clientNoteId: String) : CreateNoteRow {
    override val status: String = "duplicate"
}

internal data class FailedNote(override val clientNoteId: String) : CreateNoteRow {
    override val status: String = "failed"
}

internal data class CommittedFailedNote(
    override val clientNoteId: String,
    val noteId: Long,
) : CreateNoteRow {
    override val status: String = "committedFailed"
}

internal data class UncertainNote(override val clientNoteId: String) : CreateNoteRow {
    override val status: String = "uncertain"
}

internal data class NotAttemptedNote(override val clientNoteId: String) : CreateNoteRow {
    override val status: String = "notAttempted"
}

internal data class CreateNotesResult(
    override val runId: String,
    override val requestId: String,
    val results: List<CreateNoteRow>,
    val error: AnkiErrorDetail?,
) : AnkiResponse {
    override val operation: AnkiOperation = AnkiOperation.CREATE_NOTES
    override val messageType: String = operation.resultType
}

internal enum class ReleaseState(val wireName: String) {
    RELEASED("released"),
    DEFERRED("deferred"),
    ABSENT("absent"),
}

internal data class ReleaseRunStateResult(
    override val runId: String,
    override val requestId: String,
    val state: ReleaseState,
) : AnkiResponse {
    override val operation: AnkiOperation = AnkiOperation.RELEASE_RUN_STATE
    override val messageType: String = operation.resultType
}

internal data class AnkiErrorResult(
    override val runId: String,
    override val requestId: String,
    override val operation: AnkiOperation,
    val code: AnkiErrorCode,
    val message: String,
    val retryable: Boolean,
) : AnkiResponse {
    override val messageType: String = "anki.error"
}
