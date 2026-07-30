package com.ankiminer.android.anki.provider

import com.ankiminer.android.anki.generated.AnkiLimitsV1
import java.io.Closeable

/** Project-owned description of the small AnkiDroid provider surface we use. */
internal enum class ProviderEndpoint {
    NOTES_BROWSER,
    NOTES_V2,
    NOTE_BY_ID,
    MODELS,
    MODEL_BY_ID,
    MODEL_TEMPLATES,
    DECKS,
    DECK_BY_ID,
    CARDS,
    CARD_BY_ID,
    CARDS_FOR_NOTE,
}

internal enum class ProviderColumn {
    NOTE_ID,
    NOTE_MODEL_ID,
    NOTE_FIELDS,
    NOTE_TAGS,
    NOTE_CHECKSUM,
    MODEL_ID,
    MODEL_NAME,
    MODEL_FIELD_NAMES,
    MODEL_CARD_COUNT,
    MODEL_CSS,
    MODEL_DEFAULT_DECK_ID,
    MODEL_SORT_FIELD_INDEX,
    MODEL_TYPE,
    MODEL_LATEX_POST,
    MODEL_LATEX_PRE,
    TEMPLATE_MODEL_ID,
    TEMPLATE_ORDINAL,
    TEMPLATE_NAME,
    TEMPLATE_QUESTION_FORMAT,
    TEMPLATE_ANSWER_FORMAT,
    TEMPLATE_BROWSER_QUESTION_FORMAT,
    TEMPLATE_BROWSER_ANSWER_FORMAT,
    DECK_ID,
    DECK_NAME,
    DECK_DYNAMIC,
    CARD_ID,
    CARD_NOTE_ID,
    CARD_ORDINAL,
    CARD_DECK_ID,
}

internal sealed interface ProviderSelection {
    /** Exact deck scope compiled to Anki browser syntax only inside the production gateway. */
    data class ExcludedDeck(val deckName: String) : ProviderSelection

    /** Deck-tree browser scope; callers must inspect returned card deck IDs for exactness. */
    data class CardsInDeck(val deckName: String) : ProviderSelection

    /** Exact positive note ID compiled to the global cards browser query inside the gateway. */
    data class CardsForNote(val noteId: Long) : ProviderSelection

    /** Parameterized v2 notes-table ID lookup. */
    data class NoteIds(val ids: List<Long>) : ProviderSelection

    /** Parameterized v2 notes-table duplicate lookup. */
    data class DuplicateChecksums(
        val modelId: Long,
        val checksums: List<Long>,
    ) : ProviderSelection
}

internal enum class ProviderOrder {
    NOTE_ID_ASCENDING,
}

internal data class ProviderQuery(
    val endpoint: ProviderEndpoint,
    val endpointId: Long? = null,
    val projection: List<ProviderColumn>,
    val selection: ProviderSelection? = null,
    val sortOrder: ProviderOrder? = null,
) {
    init {
        require(ProviderQueryShapes.isAllowed(this)) {
            "provider query is outside the pinned read-only contract"
        }
    }
}

internal object ProviderQueryShapes {
    val NOTE_ID_PROJECTION = listOf(ProviderColumn.NOTE_ID)
    val NOTE_PAGE_PROJECTION = listOf(ProviderColumn.NOTE_ID, ProviderColumn.NOTE_FIELDS)
    val DUPLICATE_PROJECTION =
        listOf(
            ProviderColumn.NOTE_ID,
            ProviderColumn.NOTE_FIELDS,
            ProviderColumn.NOTE_CHECKSUM,
        )
    val NOTE_SNAPSHOT_PROJECTION =
        listOf(
            ProviderColumn.NOTE_ID,
            ProviderColumn.NOTE_MODEL_ID,
            ProviderColumn.NOTE_FIELDS,
            ProviderColumn.NOTE_TAGS,
        )
    val MODEL_PROJECTION =
        listOf(
            ProviderColumn.MODEL_ID,
            ProviderColumn.MODEL_NAME,
            ProviderColumn.MODEL_FIELD_NAMES,
            ProviderColumn.MODEL_CARD_COUNT,
            ProviderColumn.MODEL_CSS,
            ProviderColumn.MODEL_DEFAULT_DECK_ID,
            ProviderColumn.MODEL_SORT_FIELD_INDEX,
            ProviderColumn.MODEL_TYPE,
            ProviderColumn.MODEL_LATEX_POST,
            ProviderColumn.MODEL_LATEX_PRE,
        )
    val TEMPLATE_PROJECTION =
        listOf(
            ProviderColumn.TEMPLATE_MODEL_ID,
            ProviderColumn.TEMPLATE_ORDINAL,
            ProviderColumn.TEMPLATE_NAME,
            ProviderColumn.TEMPLATE_QUESTION_FORMAT,
            ProviderColumn.TEMPLATE_ANSWER_FORMAT,
            ProviderColumn.TEMPLATE_BROWSER_QUESTION_FORMAT,
            ProviderColumn.TEMPLATE_BROWSER_ANSWER_FORMAT,
        )
    val DECK_PROJECTION =
        listOf(
            ProviderColumn.DECK_ID,
            ProviderColumn.DECK_NAME,
            ProviderColumn.DECK_DYNAMIC,
        )
    val CARD_ID_PROJECTION = listOf(ProviderColumn.CARD_ID)
    val CARD_NOTE_DECK_PROJECTION =
        listOf(
            ProviderColumn.CARD_NOTE_ID,
            ProviderColumn.CARD_DECK_ID,
        )
    val CARD_IDENTITY_PROJECTION =
        listOf(
            ProviderColumn.CARD_ID,
            ProviderColumn.CARD_NOTE_ID,
            ProviderColumn.CARD_ORDINAL,
            ProviderColumn.CARD_DECK_ID,
        )

    fun isAllowed(query: ProviderQuery): Boolean {
        if (query.projection.isEmpty() || query.projection.distinct().size != query.projection.size) {
            return false
        }
        if (query.endpointId != null && query.endpointId <= 0L) return false
        return when (query.endpoint) {
            ProviderEndpoint.NOTES_BROWSER ->
                query.endpointId == null &&
                    query.projection == NOTE_ID_PROJECTION &&
                    query.selection is ProviderSelection.ExcludedDeck &&
                    query.selection.deckName.isValidDeckName() &&
                    query.sortOrder == null
            ProviderEndpoint.NOTES_V2 -> notesV2Allowed(query)
            ProviderEndpoint.NOTE_BY_ID ->
                query.endpointId != null &&
                    query.projection == NOTE_SNAPSHOT_PROJECTION &&
                    query.selection == null &&
                    query.sortOrder == null
            ProviderEndpoint.MODELS ->
                query.endpointId == null &&
                    query.projection == MODEL_PROJECTION &&
                    query.selection == null &&
                    query.sortOrder == null
            ProviderEndpoint.MODEL_BY_ID ->
                query.endpointId != null &&
                    query.projection == MODEL_PROJECTION &&
                    query.selection == null &&
                    query.sortOrder == null
            ProviderEndpoint.MODEL_TEMPLATES ->
                query.endpointId != null &&
                    query.projection == TEMPLATE_PROJECTION &&
                    query.selection == null &&
                    query.sortOrder == null
            ProviderEndpoint.DECKS ->
                query.endpointId == null &&
                    query.projection == DECK_PROJECTION &&
                    query.selection == null &&
                    query.sortOrder == null
            ProviderEndpoint.DECK_BY_ID ->
                query.endpointId != null &&
                    query.projection == DECK_PROJECTION &&
                    query.selection == null &&
                    query.sortOrder == null
            ProviderEndpoint.CARDS ->
                query.endpointId == null &&
                    when (val selection = query.selection) {
                        is ProviderSelection.CardsForNote ->
                            query.projection == CARD_ID_PROJECTION && selection.noteId > 0L
                        is ProviderSelection.CardsInDeck ->
                            query.projection == CARD_NOTE_DECK_PROJECTION &&
                                selection.deckName.isValidDeckName()
                        else -> false
                    } &&
                    query.sortOrder == null
            ProviderEndpoint.CARD_BY_ID ->
                query.endpointId != null &&
                    query.projection == CARD_IDENTITY_PROJECTION &&
                    query.selection == null &&
                    query.sortOrder == null
            ProviderEndpoint.CARDS_FOR_NOTE ->
                query.endpointId != null &&
                    query.projection == CARD_IDENTITY_PROJECTION &&
                    query.selection == null &&
                    query.sortOrder == null
        }
    }

    private fun notesV2Allowed(query: ProviderQuery): Boolean {
        if (query.endpointId != null) return false
        return when (val selection = query.selection) {
            null ->
                query.projection == NOTE_ID_PROJECTION &&
                    query.sortOrder == ProviderOrder.NOTE_ID_ASCENDING
            is ProviderSelection.NoteIds ->
                query.projection == NOTE_PAGE_PROJECTION &&
                    query.sortOrder == null &&
                    selection.ids.size in 1..MAX_NOTE_PAGE_IDS &&
                    selection.ids.isStrictlyIncreasingPositive()
            is ProviderSelection.DuplicateChecksums ->
                query.projection == DUPLICATE_PROJECTION &&
                    query.sortOrder == ProviderOrder.NOTE_ID_ASCENDING &&
                    selection.modelId > 0L &&
                    selection.checksums.size in 1..MAX_DUPLICATE_CHECKSUMS &&
                    selection.checksums.isStrictlyIncreasingNonNegative()
            is ProviderSelection.ExcludedDeck,
            is ProviderSelection.CardsInDeck,
            is ProviderSelection.CardsForNote,
            -> false
        }
    }

    private fun List<Long>.isStrictlyIncreasingPositive(): Boolean =
        isNotEmpty() && first() > 0L && zipWithNext().all { (left, right) -> left < right }

    private fun List<Long>.isStrictlyIncreasingNonNegative(): Boolean =
        isNotEmpty() &&
            first() in 0L..MAX_FIELD_CHECKSUM &&
            zipWithNext().all { (left, right) ->
                left < right && right <= MAX_FIELD_CHECKSUM
            }

    private fun String.isValidDeckName(): Boolean =
        try {
            ProviderSnapshotValidation.validateDeck(DeckSnapshot(1L, this, dynamic = false))
            true
        } catch (_: InvalidTargetSnapshotException) {
            false
        }

    private const val MAX_NOTE_PAGE_IDS = AnkiLimitsV1.ScanFirstFields.KNOWN_PAGE_MAX_ITEM_COUNT
    private const val MAX_DUPLICATE_CHECKSUMS =
        AnkiLimitsV1.ScanFirstFields.DUPLICATE_CANDIDATE_MAX_ITEM_COUNT
    private const val MAX_FIELD_CHECKSUM = 0xffff_ffffL
}

internal sealed interface ProviderCell {
    data object Null : ProviderCell

    data class Integer(val value: Long) : ProviderCell

    data class Text(val value: String) : ProviderCell
}

/**
 * A one-use provider cursor. Implementations must reject reads before the first successful [moveToNext]
 * and after close. Callers always close it on success and failure.
 */
internal interface ProviderCursor : Closeable {
    val projection: List<ProviderColumn>

    fun moveToNext(): Boolean

    fun cell(column: ProviderColumn): ProviderCell
}

internal sealed interface ProviderAccessStatus {
    data class Available(
        val packageName: String,
        val apiSpecVersion: Int,
        val versionCode: Long?,
    ) : ProviderAccessStatus

    data object Absent : ProviderAccessStatus

    data object ApiDisabled : ProviderAccessStatus

    data class Incompatible(val apiSpecVersion: Int?) : ProviderAccessStatus

    data object PermissionRequired : ProviderAccessStatus
}

internal enum class ProviderFailureKind {
    API_DISABLED,
    PERMISSION_REQUIRED,
    PROVIDER_UNAVAILABLE,
    QUERY_FAILED,
    MUTATION_FAILED,
    TIMEOUT,
    CANCELLED,
}

/** Mutations have no cancellation or timeout after durable provider entry, and never query. */
internal fun ProviderFailureKind.normalizedForMutationBoundary(): ProviderFailureKind =
    when (this) {
        ProviderFailureKind.API_DISABLED,
        ProviderFailureKind.PERMISSION_REQUIRED,
        ProviderFailureKind.PROVIDER_UNAVAILABLE,
        ProviderFailureKind.MUTATION_FAILED,
        -> this
        ProviderFailureKind.QUERY_FAILED,
        ProviderFailureKind.TIMEOUT,
        ProviderFailureKind.CANCELLED,
        -> ProviderFailureKind.MUTATION_FAILED
    }

internal class ProviderGatewayException(
    val kind: ProviderFailureKind,
    cause: Throwable? = null,
) : RuntimeException(kind.name, cause)

internal fun interface CancellationRegistration : Closeable {
    override fun close()
}

/** Cancellation that can both be polled between rows and wired to ContentResolver's signal. */
internal interface AnkiCancellation {
    fun isCancelled(): Boolean

    fun invokeOnCancellation(listener: () -> Unit): CancellationRegistration

    companion object {
        val NONE: AnkiCancellation =
            object : AnkiCancellation {
                override fun isCancelled(): Boolean = false

                override fun invokeOnCancellation(listener: () -> Unit): CancellationRegistration =
                    CancellationRegistration { }
            }
    }
}

internal fun interface WorkerThreadGuard {
    fun checkWorkerThread()
}

internal interface AnkiProviderGateway {
    /** Re-evaluated before every provider operation; callers must not cache it. */
    fun accessStatus(): ProviderAccessStatus

    /** Synchronous and worker-thread-only. The returned cursor, when non-null, is caller-owned. */
    fun query(
        query: ProviderQuery,
        cancellation: AnkiCancellation,
    ): ProviderCursor?

    /**
     * Raw commit boundary, not a service API. A journal owner must preflight access and durably
     * record provider entry before calling it. Cancellation is deliberately absent after entry.
     */
    fun createDeck(command: AnkiProviderMutationCommand.CreateDeck): String?

    /** Raw media commit boundary; the returned URI is validated only after durable entry. */
    fun storeMedia(command: AnkiProviderMutationCommand.StoreMedia): String?

    /** Raw note commit boundary; the returned URI is validated only after durable entry. */
    fun insertNote(command: AnkiProviderMutationCommand.InsertNote): String?

    /** Raw routing commit boundary after durable entry; recovery owns the affected count. */
    fun routeCard(command: AnkiProviderMutationCommand.RouteCard): Int

    /** Exact pinned AnkiDroid v2 field-checksum implementation. */
    fun fieldChecksum(firstField: String): Long
}
