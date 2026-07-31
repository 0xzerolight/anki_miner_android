package com.ankiminer.android.vm

import com.ankiminer.android.media.SafAccessException
import com.ankiminer.android.media.SafAccessFailureKind

/** True only when the platform/provider proved that a saved SAF owner cannot recover. */
internal fun Throwable.provesPermanentSafAccessLoss(): Boolean =
    (this as? SafAccessException)?.kind.let { kind ->
        kind == SafAccessFailureKind.INVALID_URI ||
            kind == SafAccessFailureKind.PERMISSION_REVOKED
    }
