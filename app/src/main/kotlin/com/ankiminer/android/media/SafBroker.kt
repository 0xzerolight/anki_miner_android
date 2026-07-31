package com.ankiminer.android.media

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.ankiminer.android.diagnostics.log.AppLog
import com.ankiminer.android.diagnostics.log.LogComponent
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

/**
 * Split selection acquisition used by [com.ankiminer.android.vm.SafSelectionOwnershipTransaction].
 *
 * Metadata resolution remains cancellable. Only the permission-to-ledger ownership handoff runs
 * non-cancellably, so every acquired grant is either published or available to a finally release.
 */
internal interface SafSelectionAccessBroker : SafBroker {
    suspend fun resolveReadAccess(uri: String): SafDocument

    suspend fun acquireResolvedReadAccess(document: SafDocument)
}

internal enum class SafAccessFailureKind {
    INVALID_URI,
    PERMISSION_REVOKED,
    PROVIDER_UNAVAILABLE,
}

internal class SafAccessException(
    val kind: SafAccessFailureKind,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

internal interface SafProviderAccess {
    fun persistedReadGrantUris(cancellation: ProviderIoCancellation): List<String>

    fun resolveDocument(
        uri: String,
        cancellation: ProviderIoCancellation,
    ): SafDocument

    fun takeReadGrant(uri: String)

    fun releaseReadGrant(uri: String)
}

internal class AndroidSafBroker(
    private val providerAccess: SafProviderAccess,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val selectionInventory: SafSelectionInventory = TransientSafSelectionInventory(),
    private val providerIoTimeoutMillis: Long = DEFAULT_PROVIDER_IO_TIMEOUT_MILLIS,
) : SafSelectionAccessBroker {
    constructor(
        context: Context,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        selectionInventory: SafSelectionInventory = AndroidSafSelectionInventory(context),
    ) : this(
        AndroidSafProviderAccess(context.applicationContext.contentResolver),
        ioDispatcher,
        selectionInventory,
    )

    private val grantLedger = SafGrantLedger()
    private val cleanupScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val uriLocks = SafUriLockRegistry()

    private val startupReconciler =
        OrphanedSafGrantReconciler(
            object : PersistedSafGrantAccess {
                override fun readGrantUris(): List<String> =
                    providerAccess.persistedReadGrantUris(ProviderIoCancellation.NONE)

                override fun releaseReadGrant(uri: String) {
                    releaseOrphanedReadGrant(uri)
                }

                override fun readGrantUris(
                    cancellation: ProviderIoCancellation,
                ): List<String> = providerAccess.persistedReadGrantUris(cancellation)

                override fun releaseReadGrant(
                    uri: String,
                    cancellation: ProviderIoCancellation,
                ) {
                    if (cancellation.isCancelled()) throw ProviderIoCancelledException()
                    releaseOrphanedReadGrant(uri)
                    if (cancellation.isCancelled()) throw ProviderIoCancelledException()
                }
            },
            selectionInventory,
        )

    override suspend fun reconcileStartup() {
        val result =
            CancellableProviderIo.execute(
                scope = cleanupScope,
                timeoutMillis = providerIoTimeoutMillis,
            ) { cancellation ->
                startupReconciler.reconcile(cancellation)
            }
        result?.let {
            AppLog.i(
                LogComponent.SAF,
                "startup.reconcile",
                "outcome" to "ok",
                "retained" to it.retained,
                "released" to it.released,
                "orphaned" to it.orphaned,
            )
        }
    }

    override suspend fun resolveReadAccess(uri: String): SafDocument {
        return try {
            reconcileStartup()
            CancellableProviderIo.execute(
                scope = cleanupScope,
                timeoutMillis = providerIoTimeoutMillis,
            ) { cancellation ->
                providerAccess.resolveDocument(uri, cancellation)
            }
        } catch (failure: ProviderIoTimeoutException) {
            throw SafAccessException(
                SafAccessFailureKind.PROVIDER_UNAVAILABLE,
                "Could not read document metadata",
                failure,
            )
        } catch (failure: ProviderIoCancelledException) {
            throw SafAccessException(
                SafAccessFailureKind.PROVIDER_UNAVAILABLE,
                "Could not read document metadata",
                failure,
            )
        }
    }

    override suspend fun acquireResolvedReadAccess(document: SafDocument) {
        withContext(ioDispatcher + NonCancellable) {
            uriLocks.withLock(document.uri) {
                try {
                    providerAccess.takeReadGrant(document.uri)
                } catch (failure: SecurityException) {
                    throw SafAccessException(
                        SafAccessFailureKind.PERMISSION_REVOKED,
                        "The document provider did not grant durable read access",
                        failure,
                    )
                }
                try {
                    grantLedger.retain(document.uri)
                } catch (failure: Throwable) {
                    try {
                        providerAccess.releaseReadGrant(document.uri)
                    } catch (rollbackFailure: Exception) {
                        failure.addSuppressed(rollbackFailure)
                    }
                    throw failure
                }
            }
        }
    }

    override suspend fun retainReadAccess(uri: String): SafDocument {
        var acquired: SafDocument? = null
        try {
            val resolved = resolveReadAccess(uri)
            withContext(NonCancellable) {
                acquireResolvedReadAccess(resolved)
                acquired = resolved
            }
            currentCoroutineContext().ensureActive()
            return resolved.also { acquired = null }
        } finally {
            acquired?.let { document ->
                try {
                    withContext(NonCancellable) {
                        releaseReadAccess(document.uri)
                    }
                } catch (_: Exception) {
                    releaseReadAccessEventually(document.uri)
                }
            }
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
            if (!uri.startsWith(CONTENT_URI_PREFIX)) return@withContext
            uriLocks.withLock(uri) {
                val durablyOwned = uri in selectionInventory.ownedUris()
                when (grantLedger.referenceCount(uri)) {
                    0 -> {
                        if (!durablyOwned) providerAccess.releaseReadGrant(uri)
                    }
                    1 -> {
                        if (durablyOwned) {
                            check(grantLedger.release(uri))
                        } else {
                            // Commit the ledger removal only after the provider release succeeds.
                            providerAccess.releaseReadGrant(uri)
                            check(grantLedger.release(uri))
                        }
                    }
                    else -> check(!grantLedger.release(uri))
                }
            }
        }

    private fun releaseOrphanedReadGrant(uri: String) {
        uriLocks.withLock(uri) {
            if (grantLedger.referenceCount(uri) != 0) return@withLock
            if (uri in selectionInventory.ownedUris()) return@withLock
            providerAccess.releaseReadGrant(uri)
        }
    }

    private companion object {
        const val DEFAULT_PROVIDER_IO_TIMEOUT_MILLIS = 30_000L
        const val CONTENT_URI_PREFIX = "content://"
    }
}

private class AndroidSafProviderAccess(
    private val resolver: ContentResolver,
) : SafProviderAccess {
    override fun persistedReadGrantUris(
        cancellation: ProviderIoCancellation,
    ): List<String> {
        if (cancellation.isCancelled()) throw ProviderIoCancelledException()
        val uris =
            resolver.persistedUriPermissions
                .filter { it.isReadPermission }
                .map { it.uri.toString() }
        if (cancellation.isCancelled()) throw ProviderIoCancelledException()
        return uris
    }

    override fun resolveDocument(
        uri: String,
        cancellation: ProviderIoCancellation,
    ): SafDocument {
        val parsed = Uri.parse(uri)
        if (parsed.scheme != ContentResolver.SCHEME_CONTENT) {
            throw SafAccessException(
                SafAccessFailureKind.INVALID_URI,
                "The selected document is not a content URI",
            )
        }
        val metadata = queryMetadata(parsed, cancellation)
        val mimeType =
            try {
                if (cancellation.isCancelled()) throw ProviderIoCancelledException()
                resolver.getType(parsed)?.takeIf(String::isNotBlank).also {
                    if (cancellation.isCancelled()) throw ProviderIoCancelledException()
                }
            } catch (failure: ProviderIoCancelledException) {
                throw failure
            } catch (failure: SecurityException) {
                throw SafAccessException(
                    SafAccessFailureKind.PERMISSION_REVOKED,
                    "Document read permission was revoked",
                    failure,
                )
            } catch (failure: RuntimeException) {
                throw SafAccessException(
                    SafAccessFailureKind.PROVIDER_UNAVAILABLE,
                    "Could not read document type",
                    failure,
                )
            }
        return SafDocument(
            uri = uri,
            displayName =
                metadata.displayName
                    ?: parsed.lastPathSegment?.takeIf(String::isNotBlank)
                    ?: "Selected document",
            mimeType = mimeType,
            sizeBytes = metadata.sizeBytes,
        )
    }

    override fun takeReadGrant(uri: String) {
        resolver.takePersistableUriPermission(
            Uri.parse(uri),
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
    }

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

    private fun queryMetadata(
        uri: Uri,
        cancellation: ProviderIoCancellation,
    ): DocumentMetadata {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return try {
            CancellableProviderIo.withCancellationSignal(cancellation) { signal ->
                resolver.query(uri, projection, null, null, null, signal)?.use { cursor ->
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
            }
        } catch (failure: ProviderIoCancelledException) {
            throw failure
        } catch (failure: SecurityException) {
            throw SafAccessException(
                SafAccessFailureKind.PERMISSION_REVOKED,
                "Document read permission was revoked",
                failure,
            )
        } catch (failure: RuntimeException) {
            throw SafAccessException(
                SafAccessFailureKind.PROVIDER_UNAVAILABLE,
                "Could not read document metadata",
                failure,
            )
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

    fun readGrantUris(cancellation: ProviderIoCancellation): List<String> = readGrantUris()

    fun releaseReadGrant(
        uri: String,
        cancellation: ProviderIoCancellation,
    ) = releaseReadGrant(uri)
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
    private val reconciled = AtomicBoolean()
    private val commitMonitor = Any()

    fun reconcile(
        cancellation: ProviderIoCancellation = ProviderIoCancellation.NONE,
    ): SafStartupReconciliation? {
        if (reconciled.get()) return null
        val grants = access.readGrantUris(cancellation).toSet()
        val plan =
            synchronized(commitMonitor) {
                if (reconciled.get()) return null
                val ownedBefore = selectionInventory.ownedUris()
                selectionInventory.pruneMissingGrants(grants)
                val ownedUris = selectionInventory.ownedUris()
                reconciled.set(true)
                val releaseUris = grants.filterNot(ownedUris::contains)
                SafStartupReconciliation(
                    retained = grants.count(ownedUris::contains),
                    released = releaseUris.size,
                    orphaned = ownedBefore.count { it !in ownedUris },
                ) to releaseUris
            }
        try {
            plan.second.forEach { uri ->
                access.releaseReadGrant(uri, cancellation)
            }
        } catch (failure: Throwable) {
            reconciled.set(false)
            throw failure
        }
        return plan.first
    }

    internal fun isReconciled(): Boolean = reconciled.get()
}

/** Reference counts selection ownership so same-URI slots and replace/clear races stay safe. */
internal class SafGrantLedger {
    private val monitor = Any()
    private val counts = mutableMapOf<String, Int>()

    fun retain(uri: String) {
        require(uri.isNotBlank())
        synchronized(monitor) {
            counts[uri] = Math.addExact(counts[uri] ?: 0, 1)
        }
    }

    /** Returns true only when the final locally owned reference should release the platform grant. */
    fun release(uri: String): Boolean =
        synchronized(monitor) {
            val count = counts[uri] ?: return@synchronized false
            check(count > 0)
            if (count > 1) {
                counts[uri] = count - 1
                return@synchronized false
            }
            counts.remove(uri)
            true
        }

    internal fun referenceCount(uri: String): Int = synchronized(monitor) { counts[uri] ?: 0 }
}

/** Exact per-URI serialization without retaining every URI ever selected. */
private class SafUriLockRegistry {
    private data class Entry(
        val monitor: Any = Any(),
        var users: Int = 0,
    )

    private val registryMonitor = Any()
    private val entries = mutableMapOf<String, Entry>()

    fun <T> withLock(
        uri: String,
        block: () -> T,
    ): T {
        val entry =
            synchronized(registryMonitor) {
                entries.getOrPut(uri, ::Entry).also { it.users += 1 }
            }
        try {
            return synchronized(entry.monitor) { block() }
        } finally {
            synchronized(registryMonitor) {
                entry.users -= 1
                check(entry.users >= 0)
                if (entry.users == 0) {
                    check(entries[uri] === entry)
                    entries.remove(uri)
                }
            }
        }
    }
}
