package com.ankiminer.android.data.resources

import android.content.ContentResolver
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import com.ankiminer.android.media.CancellableProviderIo
import com.ankiminer.android.media.ProviderIoCancellation
import com.ankiminer.android.media.ProviderIoCancelledException
import com.ankiminer.android.media.ProviderIoTimeoutException
import com.ankiminer.android.media.combine
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

internal interface ResourceArchiveStager {
    fun stage(
        sourceUri: String,
        operationId: String,
        cancellation: ResourceCancellationSignal,
        fileSuffix: String = ".zip",
        maximumBytes: Long = 1024L * 1024 * 1024,
        sourceLabel: String = "resource",
        onProgress: (Long, Long) -> Unit,
    ): StagedArchive
}

internal fun interface ResourceDocumentWriter {
    fun open(uri: String): OutputStream?

    fun open(
        uri: String,
        cancellationSignal: CancellationSignal,
    ): OutputStream? = open(uri)

    fun delete(uri: String): Boolean = false
}

internal class AndroidResourceDocumentWriter(
    private val resolver: ContentResolver,
) : ResourceDocumentWriter {
    override fun open(uri: String): OutputStream? =
        resolver.openOutputStream(Uri.parse(uri), "wt")

    override fun open(
        uri: String,
        cancellationSignal: CancellationSignal,
    ): OutputStream? =
        resolver
            .openFileDescriptor(Uri.parse(uri), "wt", cancellationSignal)
            ?.let(ParcelFileDescriptor::AutoCloseOutputStream)

    override fun delete(uri: String): Boolean =
        resolver.delete(Uri.parse(uri), null, null) > 0
}

internal fun interface ResourceInputOpener {
    /**
     * Opens [uri], a `content://` string. Kept as a String rather than a parsed
     * [Uri] so staging can be exercised by JVM unit tests, where `Uri.parse` is a
     * throwing stub.
     */
    fun open(
        uri: String,
        cancellation: ProviderIoCancellation,
    ): InputStream
}

private class AndroidResourceInputOpener(
    private val resolver: ContentResolver,
) : ResourceInputOpener {
    override fun open(
        uri: String,
        cancellation: ProviderIoCancellation,
    ): InputStream =
        CancellableProviderIo.open(cancellation) { signal ->
            val descriptor =
                resolver.openAssetFileDescriptor(Uri.parse(uri), "r", signal)
                    ?: throw FileNotFoundException("DocumentsProvider returned no descriptor for $uri")
            try {
                descriptor.createInputStream()
            } catch (failure: Throwable) {
                try {
                    descriptor.close()
                } catch (closeFailure: Throwable) {
                    failure.addSuppressed(closeFailure)
                }
                throw failure
            }
        }
}

internal class SafArchiveStager(
    private val inputOpener: ResourceInputOpener,
    private val stagingRoot: File,
    private val availableBytes: (File) -> Long = { it.usableSpace },
    private val providerIoScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val providerIoTimeoutMillis: Long = PROVIDER_IO_TIMEOUT_MILLIS,
) : ResourceArchiveStager {
    constructor(
        resolver: ContentResolver,
        stagingRoot: File,
        availableBytes: (File) -> Long = { it.usableSpace },
    ) : this(
        inputOpener = AndroidResourceInputOpener(resolver),
        stagingRoot = stagingRoot,
        availableBytes = availableBytes,
    )

    override fun stage(
        sourceUri: String,
        operationId: String,
        cancellation: ResourceCancellationSignal,
        fileSuffix: String,
        maximumBytes: Long,
        sourceLabel: String,
        onProgress: (Long, Long) -> Unit,
    ): StagedArchive {
        require(sourceUri.startsWith("${ContentResolver.SCHEME_CONTENT}://"))
        require(FILE_SUFFIX.matches(fileSuffix))
        require(maximumBytes in 1..MAXIMUM_SUPPORTED_BYTES)
        require(sourceLabel.isNotBlank() && sourceLabel.length <= 64)
        if (!stagingRoot.exists() && !stagingRoot.mkdirs()) {
            throw ResourceDownloadException("import_staging_failed", "Could not create private import staging")
        }
        val destination = File(stagingRoot, "$operationId-custom$fileSuffix")
        destination.delete()
        try {
            val available = availableBytes(stagingRoot)
            if (available < FREE_SPACE_RESERVE_BYTES) {
                throw ResourceStorageException(FREE_SPACE_RESERVE_BYTES, available)
            }
            cancellation.check()
            return runBlocking {
                CancellableProviderIo.execute(
                    scope = providerIoScope,
                    timeoutMillis = providerIoTimeoutMillis,
                ) { deadlineCancellation ->
                    copyProviderInput(
                        source = sourceUri,
                        destination = destination,
                        cancellation = cancellation.combine(deadlineCancellation),
                        maximumBytes = maximumBytes,
                        available = available,
                        sourceLabel = sourceLabel,
                        onProgress = onProgress,
                    )
                }
            }
        } catch (failure: Exception) {
            destination.delete()
            if (failure is ProviderIoCancelledException) {
                cancellation.check()
                throw sourceUnavailable(sourceLabel, failure)
            }
            if (failure is ProviderIoTimeoutException || failure is FileNotFoundException) {
                throw sourceUnavailable(sourceLabel, failure)
            }
            throw failure
        }
    }

    private fun copyProviderInput(
        source: String,
        destination: File,
        cancellation: ProviderIoCancellation,
        maximumBytes: Long,
        available: Long,
        sourceLabel: String,
        onProgress: (Long, Long) -> Unit,
    ): StagedArchive {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        CancellableProviderIo.useResource(
            cancellation = cancellation,
            open = { inputOpener.open(source, cancellation) },
        ) { stream ->
            FileOutputStream(destination, false).use { output ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    throwIfCancelled(cancellation)
                    val count = stream.read(buffer)
                    throwIfCancelled(cancellation)
                    if (count < 0) break
                    total += count
                    if (total > maximumBytes) {
                        throw archiveTooLarge(sourceLabel, total, maximumBytes)
                    }
                    if (available - total < FREE_SPACE_RESERVE_BYTES) {
                        throw ResourceStorageException(total + FREE_SPACE_RESERVE_BYTES, available)
                    }
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                    onProgress(total, 0)
                }
                output.fd.sync()
                throwIfCancelled(cancellation)
            }
        }
        if (total <= 0) {
            throw ResourceDownloadException(
                "resource_archive_mismatch",
                "The selected $sourceLabel is empty",
            )
        }
        return StagedArchive(
            file = destination,
            sha256 = digest.digest().joinToString("") { "%02x".format(it) },
            sizeBytes = total,
        )
    }

    private fun throwIfCancelled(cancellation: ProviderIoCancellation) {
        if (cancellation.isCancelled()) throw ProviderIoCancelledException()
    }

    private fun sourceUnavailable(
        sourceLabel: String,
        cause: Throwable,
    ) = ResourceDownloadException(
        "import_source_unavailable",
        "The selected $sourceLabel cannot be opened",
        cause,
        formatArguments = listOf(sourceLabel),
    )

    private companion object {
        const val BUFFER_BYTES = 256 * 1024
        const val MAXIMUM_SUPPORTED_BYTES = AUDIO_ARCHIVE_CEILING_BYTES
        const val FREE_SPACE_RESERVE_BYTES = ARCHIVE_BUDGET_RESERVE_BYTES
        const val PROVIDER_IO_TIMEOUT_MILLIS = 60_000L
        val FILE_SUFFIX = Regex("\\.[a-z0-9]{1,8}")
    }
}
