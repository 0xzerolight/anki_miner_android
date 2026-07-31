package com.ankiminer.android.media

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.ankiminer.android.diagnostics.log.AppLog
import com.ankiminer.android.diagnostics.log.LogComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SafDocument(
    val uri: String,
    val displayName: String,
    val mimeType: String?,
    val sizeBytes: Long?,
) {
    init {
        require(uri.isNotBlank())
        require(displayName.isNotBlank())
        require(sizeBytes == null || sizeBytes >= 0)
    }
}

interface SafBroker {
    /** Remove process-orphaned grants off the main thread before another selection is needed. */
    suspend fun reconcileStartup() {}

    /** Persist read access and resolve display metadata without blocking the caller's thread. */
    suspend fun retainReadAccess(uri: String): SafDocument

    /**
     * Release one selection-owned reference to a previously retained URI.
     *
     * The production application graph must share one broker across UI and job coordination. A
     * live/retryable job owns its selected documents, so callers must not release them until that
     * ownership ends. Process-death recovery must reconcile platform grants against its durable
     * job/selection inventory before releasing otherwise orphaned grants.
     */
    suspend fun releaseReadAccess(uri: String)

    /** Schedule final-owner release from lifecycle teardown, where suspension is unavailable. */
    fun releaseReadAccessEventually(uri: String)
}

class SafAccessException(message: String, cause: Throwable? = null) : Exception(message, cause)

internal class AndroidSafBroker(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val selectionInventory: SafSelectionInventory =
        AndroidSafSelectionInventory(context),
) : SafBroker {
    private val resolver: ContentResolver = context.applicationContext.contentResolver
    private val grantMonitor = Any()
    private val grantLedger = SafGrantLedger()
    private val cleanupScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private val startupReconciler =
        OrphanedSafGrantReconciler(
            object : PersistedSafGrantAccess {
                override fun readGrantUris(): List<String> =
                    resolver.persistedUriPermissions
                        .filter { it.isReadPermission }
                        .map { it.uri.toString() }

                override fun releaseReadGrant(uri: String) {
                    try {
                        resolver.releasePersistableUriPermission(
                            Uri.parse(uri),
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    } catch (_: SecurityException) {
                        // The system or provider already removed it.
                    }
                }
            },
            selectionInventory,
        )

    override suspend fun reconcileStartup() {
        val result =
            withContext(ioDispatcher) {
                synchronized(grantMonitor) { startupReconciler.reconcile() }
            }
        AppLog.i(
            LogComponent.SAF,
            "startup.reconcile",
            "outcome" to "ok",
            "retained" to result.retained,
            "released" to result.released,
            "orphaned" to result.orphaned,
        )
    }

    override suspend fun retainReadAccess(uri: String): SafDocument =
        withContext(ioDispatcher) {
            val parsed = Uri.parse(uri)
            if (parsed.scheme != ContentResolver.SCHEME_CONTENT) {
                throw SafAccessException("The selected document is not a content URI")
            }

            synchronized(grantMonitor) {
                startupReconciler.reconcile()
                // Resolve metadata before persisting so a broken provider cannot consume a grant
                // which the caller never receives ownership of.
                val metadata = queryMetadata(parsed)
                val mimeType =
                    try {
                        resolver.getType(parsed)?.takeIf(String::isNotBlank)
                    } catch (failure: RuntimeException) {
                        throw SafAccessException("Could not read document type", failure)
                    }
                try {
                    resolver.takePersistableUriPermission(
                        parsed,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                } catch (failure: SecurityException) {
                    throw SafAccessException(
                        "The document provider did not grant durable read access",
                        failure,
                    )
                }
                grantLedger.retain(uri)

                SafDocument(
                    uri = uri,
                    displayName =
                        metadata.displayName
                            ?: parsed.lastPathSegment?.takeIf(String::isNotBlank)
                            ?: "Selected document",
                    mimeType = mimeType,
                    sizeBytes = metadata.sizeBytes,
                )
            }
        }

    override fun releaseReadAccessEventually(uri: String) {
        cleanupScope.launch {
            try {
                releaseReadAccess(uri)
            } catch (_: Exception) {
                // A later process-start reconciliation gets another chance at an uncertain release.
            }
        }
    }

    override suspend fun releaseReadAccess(uri: String) =
        withContext(ioDispatcher) {
            val parsed = Uri.parse(uri)
            if (parsed.scheme != ContentResolver.SCHEME_CONTENT) return@withContext
            synchronized(grantMonitor) {
                val durablyOwned = uri in selectionInventory.ownedUris()
                if (!grantLedger.release(uri)) return@synchronized
                if (durablyOwned) return@synchronized
                try {
                    resolver.releasePersistableUriPermission(
                        parsed,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                } catch (_: SecurityException) {
                    // The provider or system already removed the grant; there is no quota leak.
                }
            }
        }

    private fun queryMetadata(uri: Uri): DocumentMetadata {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return try {
            resolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use DocumentMetadata()
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                DocumentMetadata(
                    displayName =
                        if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                            cursor.getString(nameIndex)?.takeIf(String::isNotBlank)
                        } else {
                            null
                        },
                    sizeBytes =
                        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                            cursor.getLong(sizeIndex).takeIf { it >= 0 }
                        } else {
                            null
                        },
                )
            } ?: DocumentMetadata()
        } catch (failure: RuntimeException) {
            throw SafAccessException("Could not read document metadata", failure)
        }
    }

    private data class DocumentMetadata(
        val displayName: String? = null,
        val sizeBytes: Long? = null,
    )
}

internal interface PersistedSafGrantAccess {
    fun readGrantUris(): List<String>

    fun releaseReadGrant(uri: String)
}

internal data class SafStartupReconciliation(
    val retained: Int,
    val released: Int,
    val orphaned: Int,
)

/** One-shot process-start reconciliation; a failed pass remains retryable on the next retain. */
internal class OrphanedSafGrantReconciler(
    private val access: PersistedSafGrantAccess,
    private val selectionInventory: SafSelectionInventory = TransientSafSelectionInventory(),
) {
    private var result: SafStartupReconciliation? = null

    fun reconcile(): SafStartupReconciliation {
        result?.let { return it }
        val grants = access.readGrantUris().toSet()
        val ownedBefore = selectionInventory.ownedUris()
        selectionInventory.pruneMissingGrants(grants)
        val ownedUris = selectionInventory.ownedUris()
        val releasedUris = grants.filterNot(ownedUris::contains)
        releasedUris.forEach(access::releaseReadGrant)
        return SafStartupReconciliation(
            retained = grants.count(ownedUris::contains),
            released = releasedUris.size,
            orphaned = ownedBefore.count { it !in ownedUris },
        ).also { result = it }
    }

    internal fun isReconciled(): Boolean = result != null
}

/** Reference counts selection ownership so same-URI slots and replace/clear races stay safe. */
internal class SafGrantLedger {
    private val counts = mutableMapOf<String, Int>()

    fun retain(uri: String) {
        require(uri.isNotBlank())
        counts[uri] = Math.addExact(counts[uri] ?: 0, 1)
    }

    /** Returns true only when the final locally owned reference should release the platform grant. */
    fun release(uri: String): Boolean {
        val count = counts[uri] ?: return false
        check(count > 0)
        if (count > 1) {
            counts[uri] = count - 1
            return false
        }
        counts.remove(uri)
        return true
    }

    internal fun referenceCount(uri: String): Int = counts[uri] ?: 0
}
