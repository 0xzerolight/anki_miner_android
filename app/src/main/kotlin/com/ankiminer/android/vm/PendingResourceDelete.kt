package com.ankiminer.android.vm

import com.ankiminer.android.data.resources.InstalledResourceKind

/**
 * A resource removal held until the user confirms it.
 *
 * Persisted through saved state like [PendingResourceReplace], and for the same reason: the card
 * that raised the dialog can be recreated under it, and a delete keeps no backup, so the wrong
 * target surviving a rotation is unrecoverable.
 *
 * @param identity the installed slot id, which is what the engine and the priority chain key on.
 * @param installedLabel the display name, so the dialog names what is about to go.
 */
internal data class PendingResourceDelete(
    val kind: InstalledResourceKind,
    val identity: String,
    val installedLabel: String,
)
