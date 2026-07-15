package com.ankiminer.android.anki.journal

import com.ankiminer.android.anki.protocol.AnkiRequest
import com.ankiminer.android.anki.protocol.AnkiRequestDigest
import com.ankiminer.android.anki.protocol.CreateNotesRequest
import com.ankiminer.android.anki.protocol.StoreMediaRequest
import com.ankiminer.android.anki.protocol.VerifyTargetRequest
import java.nio.CharBuffer
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal const val MEDIA_LEASE_CAPACITY = 8_000
internal const val GLOBAL_UNRESOLVED_CLAIM_LIMIT = 16_000
internal const val MAX_COMPACT_EVIDENCE_UTF8_BYTES = 4_096
internal const val MAX_REMEDIATION_SUMMARY_UTF8_BYTES = 1_024
internal const val MAX_ERROR_MESSAGE_UTF8_BYTES = 1_024

internal data class ParentKey(
    val runId: String,
    val requestId: String,
) {
    init {
        require(runId.isNotBlank()) { "runId must not be blank" }
        require(requestId.isNotBlank()) { "requestId must not be blank" }
    }
}

internal enum class ParentOperation {
    VERIFY_TARGET,
    STORE_MEDIA,
    CREATE_NOTES,
}

/** Normalized durable request identity and exact aligned item order. */
internal class JournalRequest private constructor(
    val protocolRequest: AnkiRequest,
    val key: ParentKey,
    val operation: ParentOperation,
    val digest: AnkiRequestDigest,
    val itemIds: List<String>,
) {
    init {
        require(itemIds.isNotEmpty()) { "journal request items must not be empty" }
        require(itemIds.all { it.isNotBlank() }) { "journal request item IDs must not be blank" }
        require(itemIds.distinct().size == itemIds.size) { "journal request item IDs must be unique" }
        require(operation != ParentOperation.VERIFY_TARGET || itemIds == listOf(TARGET_ITEM_ID)) {
            "verifyTarget must use its singleton target item"
        }
    }

    companion object {
        const val TARGET_ITEM_ID = "target"

        fun from(request: AnkiRequest): JournalRequest {
            val operation: ParentOperation
            val items: List<String>
            when (request) {
                is VerifyTargetRequest -> {
                    operation = ParentOperation.VERIFY_TARGET
                    items = listOf(TARGET_ITEM_ID)
                }
                is StoreMediaRequest -> {
                    operation = ParentOperation.STORE_MEDIA
                    items = request.assets.map { it.assetId }
                }
                is CreateNotesRequest -> {
                    operation = ParentOperation.CREATE_NOTES
                    items = request.notes.map { it.clientNoteId }
                }
                else -> throw IllegalArgumentException("Only mutation-bearing Anki requests are journaled")
            }
            return JournalRequest(
                protocolRequest = request,
                key = ParentKey(request.runId, request.requestId),
                operation = operation,
                digest = AnkiRequestDigest.compute(request),
                itemIds = items.toList(),
            )
        }
    }
}

internal data class ParentRequestItem(
    val parentId: Long,
    val requestIndex: Int,
    val itemId: String,
)

internal enum class ParentState {
    PREPARED,
    RUNNING,
    RESULT_READY,
    RESPONSE_ACKNOWLEDGED,
    ABANDONED,
    ;

    val isReplayable: Boolean
        get() = this == RESULT_READY

    val isFinalized: Boolean
        get() = this == RESPONSE_ACKNOWLEDGED || this == ABANDONED
}

internal enum class NoteRoutingPhase {
    NOTE_PENDING,
    NOTE_COMMIT_KNOWN,
    NOTE_READBACK_VERIFIED,
    CARDS_DISCOVERED,
    ROUTING,
    ROUTED,
    POSTCHECK_VERIFIED,
}

internal data class ParentRecord(
    val id: Long,
    val key: ParentKey,
    val operation: ParentOperation,
    val digestVersion: Int,
    val requestSha256: String,
    val state: ParentState,
    val activeRequestIndex: Int?,
    val activeNoteId: Long?,
    val routingPhase: NoteRoutingPhase?,
    val hasTargetExpectation: Boolean,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

internal data class DurableTemplateSnapshot(
    val modelId: Long,
    val ordinal: Int,
    val name: String,
    val questionFormat: String,
    val answerFormat: String,
    val browserQuestionFormat: String?,
    val browserAnswerFormat: String?,
) {
    init {
        require(modelId > 0) { "template model ID must be positive" }
        require(ordinal >= 0) { "template ordinal must be non-negative" }
        require(name.isNotBlank()) { "template name must not be blank" }
    }
}

internal data class DurableModelSnapshot(
    val id: Long,
    val name: String,
    val type: Int,
    val fieldNames: List<String>,
    val cardCount: Int,
    val sortFieldIndex: Int,
    val effectiveDefaultDeckId: Long,
    val css: String,
    val latexPre: String?,
    val latexPost: String?,
    val templates: List<DurableTemplateSnapshot>,
) {
    init {
        require(id > 0) { "model ID must be positive" }
        require(name.isNotBlank()) { "model name must not be blank" }
        require(fieldNames.isNotEmpty() && fieldNames.all { it.isNotBlank() }) {
            "model fields must not be empty"
        }
        require(fieldNames.distinct().size == fieldNames.size) { "model fields must be unique" }
        require(cardCount > 0 && templates.size == cardCount) { "template count must equal card count" }
        require(sortFieldIndex in fieldNames.indices) { "sort field index is invalid" }
        require(effectiveDefaultDeckId > 0) { "effective default deck ID must be positive" }
        require(templates.map { it.ordinal } == templates.indices.toList()) {
            "template ordinals must be contiguous"
        }
        require(templates.all { it.modelId == id }) { "template model IDs must match" }
    }
}

internal data class DurableDeckSnapshot(
    val id: Long,
    val name: String,
    val dynamic: Boolean,
) {
    init {
        require(id > 0) { "deck ID must be positive" }
        require(name.isNotBlank()) { "deck name must not be blank" }
        require(!dynamic) { "filtered decks are not durable mutation targets" }
    }
}

internal data class DurableTargetSnapshot(
    val deck: DurableDeckSnapshot,
    val model: DurableModelSnapshot,
) {
    val expectation: DurableTargetExpectation
        get() = DurableTargetExpectation(deck.name, model)
}

/** Full pre-create comparison state, durable before a missing-deck provider entry. */
internal data class DurableTargetExpectation(
    val expectedDeckName: String,
    val model: DurableModelSnapshot,
) {
    init {
        require(expectedDeckName.isNotBlank()) { "expected deck name must not be blank" }
    }
}

internal data class OrderedNoteField(
    val name: String,
    val value: String,
) {
    init {
        require(name.isNotBlank()) { "field name must not be blank" }
    }
}

internal data class DurableDuplicateDecision(
    val key: String,
    val firstField: String,
    val occurrence: Int,
    val duplicate: Boolean,
) {
    init {
        require(key.isNotBlank()) { "duplicate key must not be blank" }
        require(occurrence >= 0) { "duplicate occurrence must be non-negative" }
    }
}

internal data class DurableMediaBinding(
    val assetId: String,
    val actualFilename: String,
    val claimId: Long,
) {
    init {
        require(assetId.isNotBlank()) { "assetId must not be blank" }
        require(actualFilename.isNotBlank()) { "actual filename must not be blank" }
        require(claimId > 0) { "claim ID must be positive" }
    }
}

internal data class ActiveNoteMaterialization(
    val requestIndex: Int,
    val clientNoteId: String,
    val orderedFields: List<OrderedNoteField>,
    val joinedFields: String,
    val normalizedTags: List<String>,
    val providerTagsWire: String,
    val duplicateDecision: DurableDuplicateDecision,
    val mediaBindings: List<DurableMediaBinding>,
) {
    init {
        require(requestIndex >= 0) { "requestIndex must be non-negative" }
        require(clientNoteId.isNotBlank()) { "clientNoteId must not be blank" }
        require(orderedFields.isNotEmpty()) { "ordered fields must not be empty" }
        require(orderedFields.map { it.name }.distinct().size == orderedFields.size) {
            "ordered field names must be unique"
        }
        require(normalizedTags.distinct().size == normalizedTags.size) { "tags must be unique" }
        require(mediaBindings.map { it.assetId }.distinct().size == mediaBindings.size) {
            "media binding asset IDs must be unique"
        }
    }

    /** Internally derived identity for the exact provider-bound materialization, never caller supplied. */
    val itemSha256: String
        get() = ActiveNoteMaterializationDigest.compute(this)
}

internal data class ActiveNoteRecord(
    val parentId: Long,
    val materialization: ActiveNoteMaterialization,
    val itemSha256: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
) {
    init {
        requireSha256(itemSha256, "active-note item digest")
        require(itemSha256 == materialization.itemSha256) { "active-note item digest mismatch" }
    }
}

internal enum class ChildOperation {
    DECK_CREATE,
    MEDIA_INSERT,
    NOTE_INSERT,
    CARD_DECK_UPDATE,
}

internal sealed interface MutationCommand {
    val operation: ChildOperation
    val identityKey: String
    val requestIndex: Int?

    data class CreateDeck(val deckName: String) : MutationCommand {
        init {
            require(deckName.isNotBlank()) { "deck name must not be blank" }
        }

        override val operation = ChildOperation.DECK_CREATE
        override val identityKey = deckName
        override val requestIndex: Int? = null
    }

    data class StoreMedia(
        val requestIndexValue: Int,
        val assetId: String,
        val fileUri: String,
        val preferredName: String,
    ) : MutationCommand {
        init {
            require(requestIndexValue >= 0) { "request index must be non-negative" }
            require(assetId.isNotBlank() && fileUri.isNotBlank() && preferredName.isNotBlank())
        }

        override val operation = ChildOperation.MEDIA_INSERT
        override val identityKey = assetId
        override val requestIndex: Int = requestIndexValue
    }

    data class InsertNote(
        val requestIndexValue: Int,
        val clientNoteId: String,
        val modelId: Long,
        val joinedFields: String,
        val providerTagsWire: String,
    ) : MutationCommand {
        init {
            require(requestIndexValue >= 0) { "request index must be non-negative" }
            require(clientNoteId.isNotBlank()) { "client note ID must not be blank" }
            require(modelId > 0) { "model ID must be positive" }
        }

        override val operation = ChildOperation.NOTE_INSERT
        override val identityKey = clientNoteId
        override val requestIndex: Int = requestIndexValue
    }

    data class RouteCard(
        val intentId: Long,
        val requestIndexValue: Int,
        val cardId: Long,
        val noteId: Long,
        val ordinal: Int,
        val targetDeckId: Long,
        val preUpdateDeckId: Long,
    ) : MutationCommand {
        init {
            require(intentId > 0 && cardId > 0 && noteId > 0) { "routing IDs must be positive" }
            require(requestIndexValue >= 0 && ordinal >= 0) { "routing indexes must be non-negative" }
            require(targetDeckId > 0 && preUpdateDeckId > 0) { "routing deck IDs must be positive" }
        }

        override val operation = ChildOperation.CARD_DECK_UPDATE
        override val identityKey = cardId.toString()
        override val requestIndex: Int = requestIndexValue
    }
}

internal sealed interface ProviderReceipt {
    val operation: ChildOperation

    data class Deck(val deckId: Long, val contentUri: String) : ProviderReceipt {
        init {
            require(deckId > 0 && contentUri.isNotBlank())
        }

        override val operation = ChildOperation.DECK_CREATE
    }

    data class Media(val actualFilename: String, val fileUri: String) : ProviderReceipt {
        init {
            require(actualFilename.isNotBlank() && fileUri.isNotBlank())
        }

        override val operation = ChildOperation.MEDIA_INSERT
    }

    data class Note(val noteId: Long, val contentUri: String) : ProviderReceipt {
        init {
            require(noteId > 0 && contentUri.isNotBlank())
        }

        override val operation = ChildOperation.NOTE_INSERT
    }

    data object CardAffectedOne : ProviderReceipt {
        override val operation = ChildOperation.CARD_DECK_UPDATE
    }
}

internal data class ProviderAttempt(
    val childId: Long,
    val attemptNumber: Int,
    val recoveryReissue: Boolean,
    val enteredAtMs: Long,
)

internal enum class ChildState {
    PREPARED,
    PROVEN_NOT_COMMITTED,
    COMMIT_KNOWN,
    POSTCONDITION_VERIFIED,
    POSTCONDITION_FAILED,
    COMMIT_UNCERTAIN,
    ;

    val isTerminal: Boolean
        get() = this != PREPARED
}

internal data class ChildRecord(
    val id: Long,
    val parentId: Long,
    val sequence: Int,
    val digestVersion: Int,
    val requestSha256: String,
    val itemSha256: String?,
    val command: MutationCommand,
    val mediaClaimId: Long?,
    val state: ChildState,
    val attempts: List<ProviderAttempt>,
    val receipt: ProviderReceipt?,
    val terminalEvidence: String?,
    val createdAtMs: Long,
    val updatedAtMs: Long,
) {
    init {
        itemSha256?.let { requireSha256(it, "child item digest") }
        require((command.operation == ChildOperation.NOTE_INSERT) == (itemSha256 != null)) {
            "Only note children carry an item digest"
        }
    }

    val attemptCount: Int
        get() = attempts.size
}

/** Length-prefixed, domain-separated materialization digest used only for local mutation recovery. */
private object ActiveNoteMaterializationDigest {
    private const val DOMAIN = "com.ankiminer.android.anki.note-materialization"
    private const val VERSION = 1

    fun compute(note: ActiveNoteMaterialization): String {
        val digest = MessageDigest.getInstance("SHA-256")
        putString(digest, DOMAIN)
        putLong(digest, VERSION.toLong())
        putLong(digest, note.requestIndex.toLong())
        putString(digest, note.clientNoteId)
        putLong(digest, note.orderedFields.size.toLong())
        note.orderedFields.forEach {
            putString(digest, it.name)
            putString(digest, it.value)
        }
        putString(digest, note.joinedFields)
        putLong(digest, note.normalizedTags.size.toLong())
        note.normalizedTags.forEach { putString(digest, it) }
        putString(digest, note.providerTagsWire)
        putString(digest, note.duplicateDecision.key)
        putString(digest, note.duplicateDecision.firstField)
        putLong(digest, note.duplicateDecision.occurrence.toLong())
        putLong(digest, if (note.duplicateDecision.duplicate) 1 else 0)
        putLong(digest, note.mediaBindings.size.toLong())
        note.mediaBindings.forEach {
            putString(digest, it.assetId)
            putString(digest, it.actualFilename)
            putLong(digest, it.claimId)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun putString(digest: MessageDigest, value: String) {
        val encoder =
            StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        val encoded =
            try {
                encoder.encode(CharBuffer.wrap(value))
            } catch (error: Exception) {
                throw IllegalArgumentException("Materialization contains an invalid Unicode scalar", error)
            }
        putLong(digest, encoded.remaining().toLong())
        digest.update(encoded)
    }

    private fun putLong(digest: MessageDigest, value: Long) {
        digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(value).array())
    }
}

internal enum class RoutingIntentState {
    PENDING,
    UPDATE_PREPARED,
    VERIFIED,
    FAILED,
    COMMIT_UNCERTAIN,
    ;

    val isTerminal: Boolean
        get() = this == VERIFIED || this == FAILED || this == COMMIT_UNCERTAIN
}

internal data class RoutingIntentDraft(
    val requestIndex: Int,
    val cardId: Long,
    val noteId: Long,
    val ordinal: Int,
    val targetDeckId: Long,
    val preUpdateDeckId: Long,
) {
    init {
        require(requestIndex >= 0 && ordinal >= 0)
        require(cardId > 0 && noteId > 0 && targetDeckId > 0 && preUpdateDeckId > 0)
    }
}

internal data class RoutingIntentRecord(
    val id: Long,
    val parentId: Long,
    val requestIndex: Int,
    val cardId: Long,
    val noteId: Long,
    val ordinal: Int,
    val targetDeckId: Long,
    val preUpdateDeckId: Long,
    val childId: Long?,
    val state: RoutingIntentState,
    val terminalEvidence: String?,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

internal enum class JournalErrorCode {
    API_DISABLED,
    PERMISSION_REQUIRED,
    NOTE_TYPE_NOT_FOUND,
    FIELD_MISSING,
    FIELD_MAPPING_INVALID,
    TARGET_INVALID,
    PROVIDER_UNAVAILABLE,
    QUERY_FAILED,
    WRITE_FAILED,
    TIMEOUT,
    CANCELLED,
    MEDIA_STORE_FAILED,
    POST_COMMIT_UNCERTAIN,
    INVALID_REQUEST,
    UNSUPPORTED_OPERATION,
    INTERNAL_ERROR,
}

internal data class JournalError(
    val code: JournalErrorCode,
    val message: String,
    val retryable: Boolean,
) {
    init {
        require(message.isNotBlank()) { "error message must not be blank" }
        requireStrictUtf8Bound(message, MAX_ERROR_MESSAGE_UTF8_BYTES, "error message")
    }
}

internal enum class AlignedStatus {
    VERIFIED,
    STORED,
    FAILED,
    UNCERTAIN,
    NOT_ATTEMPTED,
    CREATED,
    DUPLICATE,
    COMMITTED_FAILED,
}

internal sealed interface AlignedResult {
    val requestIndex: Int
    val itemId: String
    val status: AlignedStatus
    val committedId: Long?
    val actualFilename: String?
    val rowError: JournalError?
    val compactEvidence: String?

    data class TargetVerified(
        override val requestIndex: Int = 0,
        override val itemId: String = JournalRequest.TARGET_ITEM_ID,
    ) : AlignedResult {
        override val status = AlignedStatus.VERIFIED
        override val committedId: Long? = null
        override val actualFilename: String? = null
        override val rowError: JournalError? = null
        override val compactEvidence: String? = null
    }

    data class TargetFailed(
        override val rowError: JournalError,
        override val requestIndex: Int = 0,
        override val itemId: String = JournalRequest.TARGET_ITEM_ID,
    ) : AlignedResult {
        override val status = AlignedStatus.FAILED
        override val committedId: Long? = null
        override val actualFilename: String? = null
        override val compactEvidence: String? = null
    }

    data class MediaStored(
        override val requestIndex: Int,
        override val itemId: String,
        override val actualFilename: String,
        override val compactEvidence: String? = null,
    ) : AlignedResult {
        override val status = AlignedStatus.STORED
        override val committedId: Long? = null
        override val rowError: JournalError? = null
    }

    data class MediaFailed(
        override val requestIndex: Int,
        override val itemId: String,
        override val rowError: JournalError,
        override val compactEvidence: String? = null,
    ) : AlignedResult {
        override val status = AlignedStatus.FAILED
        override val committedId: Long? = null
        override val actualFilename: String? = null
    }

    data class MediaUncertain(
        override val requestIndex: Int,
        override val itemId: String,
        override val compactEvidence: String? = null,
    ) : AlignedResult {
        override val status = AlignedStatus.UNCERTAIN
        override val committedId: Long? = null
        override val actualFilename: String? = null
        override val rowError: JournalError? = null
    }

    data class MediaNotAttempted(
        override val requestIndex: Int,
        override val itemId: String,
    ) : AlignedResult {
        override val status = AlignedStatus.NOT_ATTEMPTED
        override val committedId: Long? = null
        override val actualFilename: String? = null
        override val rowError: JournalError? = null
        override val compactEvidence: String? = null
    }

    data class NoteCreated(
        override val requestIndex: Int,
        override val itemId: String,
        override val committedId: Long,
        override val compactEvidence: String? = null,
    ) : AlignedResult {
        init {
            require(committedId > 0)
        }

        override val status = AlignedStatus.CREATED
        override val actualFilename: String? = null
        override val rowError: JournalError? = null
    }

    data class NoteDuplicate(
        override val requestIndex: Int,
        override val itemId: String,
    ) : AlignedResult {
        override val status = AlignedStatus.DUPLICATE
        override val committedId: Long? = null
        override val actualFilename: String? = null
        override val rowError: JournalError? = null
        override val compactEvidence: String? = null
    }

    data class NoteFailed(
        override val requestIndex: Int,
        override val itemId: String,
        override val rowError: JournalError,
        override val compactEvidence: String? = null,
    ) : AlignedResult {
        override val status = AlignedStatus.FAILED
        override val committedId: Long? = null
        override val actualFilename: String? = null
    }

    data class NoteCommittedFailed(
        override val requestIndex: Int,
        override val itemId: String,
        override val committedId: Long,
        override val rowError: JournalError,
        override val compactEvidence: String? = null,
    ) : AlignedResult {
        init {
            require(committedId > 0)
        }

        override val status = AlignedStatus.COMMITTED_FAILED
        override val actualFilename: String? = null
    }

    data class NoteUncertain(
        override val requestIndex: Int,
        override val itemId: String,
        override val compactEvidence: String? = null,
    ) : AlignedResult {
        override val status = AlignedStatus.UNCERTAIN
        override val committedId: Long? = null
        override val actualFilename: String? = null
        override val rowError: JournalError? = null
    }

    data class NoteNotAttempted(
        override val requestIndex: Int,
        override val itemId: String,
    ) : AlignedResult {
        override val status = AlignedStatus.NOT_ATTEMPTED
        override val committedId: Long? = null
        override val actualFilename: String? = null
        override val rowError: JournalError? = null
        override val compactEvidence: String? = null
    }
}

internal enum class TerminalVariant {
    VERIFY_SUCCESS,
    VERIFY_ERROR,
    STORE_MEDIA_RESULT,
    CREATE_NOTES_RESULT,
}

internal sealed interface JournalResponse {
    val key: ParentKey
    val operation: ParentOperation
    val variant: TerminalVariant

    data class VerifySuccess(
        override val key: ParentKey,
        val target: DurableTargetSnapshot,
    ) : JournalResponse {
        override val operation = ParentOperation.VERIFY_TARGET
        override val variant = TerminalVariant.VERIFY_SUCCESS
        val deckCreated: Boolean = false
    }

    data class VerifyError(
        override val key: ParentKey,
        val error: JournalError,
    ) : JournalResponse {
        override val operation = ParentOperation.VERIFY_TARGET
        override val variant = TerminalVariant.VERIFY_ERROR
    }

    data class StoreMedia(
        override val key: ParentKey,
        val results: List<AlignedResult>,
        val error: JournalError?,
    ) : JournalResponse {
        override val operation = ParentOperation.STORE_MEDIA
        override val variant = TerminalVariant.STORE_MEDIA_RESULT
    }

    data class CreateNotes(
        override val key: ParentKey,
        val results: List<AlignedResult>,
        val error: JournalError?,
    ) : JournalResponse {
        override val operation = ParentOperation.CREATE_NOTES
        override val variant = TerminalVariant.CREATE_NOTES_RESULT
    }
}

internal data class ParentTerminalMetadata(
    val parentId: Long,
    val variant: TerminalVariant,
    val topLevelError: JournalError?,
)

internal sealed interface ReplayResult {
    data object Missing : ReplayResult
    data object DigestMismatch : ReplayResult
    data object NotReplayable : ReplayResult
    data object LiveOwnerRequired : ReplayResult
    data class Ready(val response: JournalResponse) : ReplayResult
}

internal enum class MediaLeaseState { ACTIVE, RELEASED }

internal class JournalCapacityLimits private constructor(
    val leaseCapacity: Int,
    val globalUnresolvedLimit: Int,
) {
    init {
        require(leaseCapacity in 1..MEDIA_LEASE_CAPACITY)
        require(globalUnresolvedLimit in leaseCapacity..GLOBAL_UNRESOLVED_CLAIM_LIMIT)
    }

    companion object {
        val PRODUCTION = JournalCapacityLimits(MEDIA_LEASE_CAPACITY, GLOBAL_UNRESOLVED_CLAIM_LIMIT)
        internal fun forTests(leaseCapacity: Int, globalLimit: Int) =
            JournalCapacityLimits(leaseCapacity, globalLimit)
    }
}

internal object MediaCapacityPolicy {
    fun requireLeaseAdmission(
        unresolvedClaims: Int,
        activeLeaseUnusedSlots: Int?,
        requestedLeaseSlots: Int,
        globalLimit: Int,
    ) {
        require(unresolvedClaims >= 0 && (activeLeaseUnusedSlots == null || activeLeaseUnusedSlots >= 0))
        require(requestedLeaseSlots > 0 && globalLimit > 0)
        if (activeLeaseUnusedSlots != null) {
            throw JournalInvariantViolation("Only one active media lease is permitted")
        }
        val admitted = unresolvedClaims.toLong() + requestedLeaseSlots.toLong()
        if (admitted > globalLimit) {
            throw JournalInvariantViolation("Global unresolved media capacity cannot admit a full run lease")
        }
    }

    fun unusedSlots(capacity: Int, reservedOrPromotedSlots: Int): Int {
        require(capacity > 0 && reservedOrPromotedSlots >= 0)
        if (reservedOrPromotedSlots > capacity) throw JournalCorruptionException("Media lease capacity is overdrawn")
        return capacity - reservedOrPromotedSlots
    }
}

internal enum class SqliteSynchronousConfiguration {
    PRIMARY_CONNECTION,
    ALL_CONNECTIONS,
}

internal object JournalSqliteDurabilityPolicy {
    fun synchronousConfiguration(apiLevel: Int): SqliteSynchronousConfiguration {
        require(apiLevel >= 1)
        return if (apiLevel >= 30) {
            SqliteSynchronousConfiguration.ALL_CONNECTIONS
        } else {
            SqliteSynchronousConfiguration.PRIMARY_CONNECTION
        }
    }
}

internal data class MediaLeaseRecord(
    val id: Long,
    val runId: String,
    val capacity: Int,
    val unusedSlots: Int,
    val state: MediaLeaseState,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

internal enum class MediaPurpose { CARD, DICTIONARY }
internal enum class MediaKind { AUDIO, IMAGE }

internal data class MediaReservationDraft(
    val requestId: String,
    val assetId: String,
    val requestedFilename: String,
    val preferredName: String,
    val sha256: String,
    val purpose: MediaPurpose,
    val mediaKind: MediaKind,
) {
    init {
        require(requestId.isNotBlank() && assetId.isNotBlank())
        require(requestedFilename.isNotBlank() && preferredName.isNotBlank())
        requireSha256(sha256, "media digest")
    }
}

internal enum class MediaReservationState { RESERVED, PROMOTED, RELEASED }

internal data class MediaReservationRecord(
    val id: Long,
    val leaseId: Long,
    val runId: String,
    val requestId: String,
    val assetId: String,
    val requestedFilename: String,
    val preferredName: String,
    val sha256: String,
    val purpose: MediaPurpose,
    val mediaKind: MediaKind,
    val state: MediaReservationState,
    val claimId: Long?,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

internal enum class MediaClaimState {
    PENDING,
    STORED,
    COMMIT_UNCERTAIN,
    PRESENT_BYTES_VERIFIED,
    ATTACHED_VERIFIED,
    CLEANED_VERIFIED,
    ACKNOWLEDGED_BY_USER,
    ;

    val isUnresolved: Boolean
        get() = this in setOf(PENDING, STORED, COMMIT_UNCERTAIN, PRESENT_BYTES_VERIFIED)
}

internal data class MediaClaimRecord(
    val id: Long,
    val runId: String,
    val requestId: String,
    val assetId: String,
    val requestedFilename: String,
    val preferredName: String,
    val sha256: String,
    val purpose: MediaPurpose,
    val mediaKind: MediaKind,
    val actualFilename: String?,
    val state: MediaClaimState,
    val compactEvidence: String?,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

internal data class MediaPromotion(
    val reservation: MediaReservationRecord,
    val claim: MediaClaimRecord,
    val child: ChildRecord,
)

internal enum class StagingState { STAGED, GRANTED, CLEANUP_PENDING, CLEANED, QUARANTINED }

internal data class StagingDraft(
    val runId: String,
    val requestId: String,
    val assetId: String,
    val relativePath: String,
    val contentUri: String,
    val packageName: String,
    val sizeBytes: Long,
    val sha256: String,
) {
    init {
        require(runId.isNotBlank() && requestId.isNotBlank() && assetId.isNotBlank())
        require(relativePath.isNotBlank() && !relativePath.startsWith('/'))
        require(contentUri.isNotBlank() && packageName.isNotBlank())
        require(sizeBytes >= 0)
        requireSha256(sha256, "staging digest")
    }
}

internal data class StagingRecord(
    val id: Long,
    val runId: String,
    val requestId: String,
    val assetId: String,
    val relativePath: String,
    val contentUri: String,
    val packageName: String,
    val sizeBytes: Long,
    val sha256: String,
    val state: StagingState,
    val compactEvidence: String?,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

internal enum class RemediationKind {
    DECK_COMMIT_UNCERTAIN,
    MEDIA_COMMIT_UNCERTAIN,
    NOTE_COMMIT_UNCERTAIN,
    NOTE_COMMITTED_FAILED,
    CARD_ROUTING_FAILED,
    STAGING_QUARANTINED,
    CAPACITY_EXHAUSTED,
}

internal enum class RemediationState { OPEN, RESOLVED }

internal data class RemediationDraft(
    val parentId: Long? = null,
    val claimId: Long? = null,
    val stagingId: Long? = null,
    val kind: RemediationKind,
    val summary: String,
    val compactEvidence: String? = null,
) {
    init {
        require(parentId != null || claimId != null || stagingId != null)
        require(parentId == null || parentId > 0)
        require(claimId == null || claimId > 0)
        require(stagingId == null || stagingId > 0)
        require(summary.isNotBlank())
        requireStrictUtf8Bound(summary, MAX_REMEDIATION_SUMMARY_UTF8_BYTES, "remediation summary")
        compactEvidence?.let {
            require(it.isNotBlank())
            requireStrictUtf8Bound(it, MAX_COMPACT_EVIDENCE_UTF8_BYTES, "compact evidence")
        }
    }
}

internal data class RemediationRecord(
    val id: Long,
    val parentId: Long?,
    val claimId: Long?,
    val stagingId: Long?,
    /** Immutable staging identity retained after the cleaned artifact row is removed. */
    val stagingSubjectId: Long?,
    val kind: RemediationKind,
    val state: RemediationState,
    val summary: String,
    val compactEvidence: String?,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

internal data class RunCleanupResult(
    val acknowledgedRequestIds: List<String>,
    val abandonedRequestIds: List<String>,
    val evidenceAccepted: Boolean,
)

internal class JournalInvariantViolation(message: String) : IllegalStateException(message)

internal class JournalCorruptionException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

private val LOWER_HEX_SHA256 = Regex("[0-9a-f]{64}")

internal fun requireSha256(value: String, name: String) {
    require(LOWER_HEX_SHA256.matches(value)) { "$name must be a lowercase SHA-256 digest" }
}

internal fun requireStrictUtf8Bound(value: String, maxBytes: Int, name: String) {
    require(maxBytes >= 0)
    val encoder =
        StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
    val bytes =
        try {
            encoder.encode(CharBuffer.wrap(value)).remaining()
        } catch (error: Exception) {
            throw IllegalArgumentException("$name must contain valid Unicode scalars", error)
        }
    require(bytes <= maxBytes) { "$name exceeds $maxBytes UTF-8 bytes" }
}

internal fun requireCompactEvidence(value: String?, name: String = "compact evidence") {
    require(value?.isNotBlank() != false) { "$name must not be blank" }
    value?.let { requireStrictUtf8Bound(it, MAX_COMPACT_EVIDENCE_UTF8_BYTES, name) }
}
