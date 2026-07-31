package com.ankiminer.android.vm

import com.ankiminer.android.data.resources.FrequencySourceFormat
import com.ankiminer.android.data.resources.PitchAccentSourceFormat

/** Which import flow raised a pending replace, so the dialog can word itself correctly. */
internal enum class ResourceReplaceKind {
    CATALOG_DICTIONARY,
    CUSTOM_DICTIONARY,
    FREQUENCY,
    PITCH,
    AUDIO_PACK,
}

/**
 * An import that would overwrite something already installed, held until the user confirms.
 *
 * Detecting the collision here rather than letting Python's occupancy guard reject it means the
 * question is asked before the file is staged, copied, validated and indexed - and before a failure
 * that `retryResourceFailure` cannot retry.
 *
 * The record carries the picked [uri] because the dialog is raised *after* the file picker returns.
 * Deciding first would need a latched "already confirmed" flag, which goes stale in an obvious way:
 * confirm, cancel the picker, come back later with a different file, and the latch silently
 * authorises overwriting with it.
 *
 * @param identity the id actually written, which for a name-matched source is the *existing* id
 *   rather than the one derived from the typed name.
 * @param repair set when the slot is occupied but unusable, which changes the dialog's wording from
 *   replace to repair.
 */
internal data class PendingResourceReplace(
    val kind: ResourceReplaceKind,
    val identity: String,
    val installedLabel: String,
    /** Null for catalog dictionaries, which download rather than import a picked file. */
    val uri: String? = null,
    /** Snapshotted before the picker opens so confirmation cannot use restored draft defaults. */
    val sourceName: String? = null,
    val frequencyFormat: FrequencySourceFormat? = null,
    val pitchFormat: PitchAccentSourceFormat? = null,
    val repair: Boolean = false,
)
