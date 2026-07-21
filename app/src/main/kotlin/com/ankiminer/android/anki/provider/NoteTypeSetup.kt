package com.ankiminer.android.anki.provider

import com.ankiminer.android.anki.protocol.AnkiErrorCode

/** A lightweight, UI-safe summary of a user AnkiDroid note type (model), read for selection. */
internal data class ModelSummary(
    val id: Long,
    val name: String,
    val fieldNames: List<String>,
)

internal enum class NoteTypeProviderErrorReason {
    API_DISABLED,
    API_INCOMPATIBLE,
    API_DISABLED_OR_INCOMPATIBLE,
    PERMISSION_REQUIRED,
    PROVIDER_UNAVAILABLE,
    PROVIDER_BECAME_UNAVAILABLE,
    QUERY_FAILED,
    TIMEOUT,
    CANCELLED,
    UNKNOWN,
}

/**
 * Setup-time status of the user-selected Anki note type + field mapping.
 *
 * This is a detect/verify status only — Anki Miner never creates a note type. It drives the setup
 * UI and `targetReady`, but the authoritative mining gate stays the run-admission probe plus the
 * Python `verify_card_target` re-check, which are not short-circuited by this value.
 */
internal sealed interface NoteTypeSetupStatus {
    /** No note type has been chosen yet. */
    data object NotSelected : NoteTypeSetupStatus

    /** The chosen note type name no longer exists in AnkiDroid. */
    data object NoteTypeMissing : NoteTypeSetupStatus

    /** One or more mapped fields (by logical key) are absent from the chosen note type. */
    data class FieldsMissing(val keys: List<String>) : NoteTypeSetupStatus

    /** The map violates required field ownership or lets values overwrite one destination. */
    data class FieldMapInvalid(
        val destination: String,
        val logicalKeys: List<String>,
    ) : NoteTypeSetupStatus

    /**
     * The word field is not the note type's first field. AnkiDroid dedup keys on the first field,
     * so mining would silently mis-dedup or fail; block until the mapping/note type is corrected.
     */
    data object FirstFieldMismatch : NoteTypeSetupStatus

    /** AnkiDroid could not be read while checking the note type. */
    data class ProviderError(
        val reason: NoteTypeProviderErrorReason,
        val code: AnkiErrorCode,
        val retryable: Boolean,
        val stableMessage: String,
    ) : NoteTypeSetupStatus

    /** The note type exists, destinations are unique, every mapped field exists, and word is field[0]. */
    data class Verified(val modelId: Long) : NoteTypeSetupStatus
}
