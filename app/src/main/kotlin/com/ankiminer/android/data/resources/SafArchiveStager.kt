package com.ankiminer.android.data.resources

import android.content.ContentProviderClient
import android.content.ContentResolver
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.ankiminer.android.diagnostics.log.AppLog
import com.ankiminer.android.diagnostics.log.LogComponent
import com.ankiminer.android.media.CancellableProviderIo
import com.ankiminer.android.media.ProviderIoCancellation
import com.ankiminer.android.media.ProviderIoCancelledException
import com.ankiminer.android.media.ProviderIoDeadlineScheduler
import com.ankiminer.android.media.ProviderIoTimeoutException
import com.ankiminer.android.media.RealProviderIoDeadlineScheduler
import com.ankiminer.android.media.combine
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

internal interface ResourceArchiveStager {
    suspend fun readLeadingBytes(
        sourceUri: String,
        maximumBytes: Int,
    ): ByteArray

    fun stage(
        sourceUri: String,
        operationId: String,
        cancellation: ResourceCancellationSignal,
        fileSuffix: String = ".zip",
        maximumBytes: Long = 1024L * 1024 * 1024,
        sourceLabel: String = "resource",
        onProgress: (Long, Long) -> Unit,
    ): StagedArchive

    fun stageAudioArchive(
        sourceUri: String,
        operationId: String,
        cancellation: ResourceCancellationSignal,
        maximumBytes: Long = AUDIO_ARCHIVE_CEILING_BYTES,
        sourceLabel: String = "audio-pack archive",
        onProgress: (Long, Long) -> Unit,
    ): StagedAudioArchive
}

internal enum class AudioArchiveReadMode(val wireValue: String) {
    RAW("raw"),
    ASSET_FALLBACK("asset_fallback"),
}

internal enum class AudioArchiveContainer(val wireValue: String) {
    ZIP("zip"),
    XZ("xz"),
    GZIP("gzip"),
    TAR("tar"),
}

internal data class StagedAudioArchive(
    val archive: StagedArchive,
    val readMode: AudioArchiveReadMode,
    val container: AudioArchiveContainer,
)

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

    /** Raw provider bytes. Null means this provider only exposes an asset representation. */
    fun openRaw(
        uri: String,
        cancellation: ProviderIoCancellation,
    ): InputStream? = null

    /**
     * What the provider claims the document weighs, or null when it declines to say.
     *
     * Cloud providers legitimately report nothing, so null is "no agreement to check" rather
     * than a fault. Used only to catch a copy that ended early.
     */
    fun reportedSizeBytes(uri: String): Long? = null
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
                    ?: throw FileNotFoundException("DocumentsProvider returned no asset descriptor")
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

    override fun openRaw(
        uri: String,
        cancellation: ProviderIoCancellation,
    ): InputStream? {
        var opened: InputStream? = null
        try {
            return CancellableProviderIo.withCancellationSignal(cancellation) { signal ->
                val parsed = Uri.parse(uri)
                // ContentResolver.openFileDescriptor("r") delegates to the typed-asset path.
                // Call ContentProvider.openFile through its client so this is the provider's raw
                // representation even when openTypedAssetFile returns transformed bytes.
                val client =
                    resolver.acquireContentProviderClient(parsed)
                        ?: return@withCancellationSignal null
                val descriptor =
                    try {
                        client.openFile(parsed, "r", signal)
                    } catch (failure: Throwable) {
                        try {
                            client.close()
                        } catch (closeFailure: Throwable) {
                            failure.addSuppressed(closeFailure)
                        }
                        throw failure
                    }
                if (descriptor == null) {
                    client.close()
                    return@withCancellationSignal null
                }
                try {
                    ProviderClientInputStream(
                        ParcelFileDescriptor.AutoCloseInputStream(descriptor),
                        client,
                    ).also { opened = it }
                } catch (failure: Throwable) {
                    try {
                        descriptor.close()
                    } catch (closeFailure: Throwable) {
                        failure.addSuppressed(closeFailure)
                    }
                    try {
                        client.close()
                    } catch (closeFailure: Throwable) {
                        failure.addSuppressed(closeFailure)
                    }
                    throw failure
                }
            }
        } catch (failure: Throwable) {
            try {
                opened?.close()
            } catch (closeFailure: Throwable) {
                failure.addSuppressed(closeFailure)
            }
            throw failure
        }
    }

    /**
     * A best-effort read of `OpenableColumns.SIZE`. Any provider failure answers null, because a
     * size this is only used to cross-check must never be the thing that fails an import.
     */
    override fun reportedSizeBytes(uri: String): Long? =
        runCatching {
            resolver
                .query(Uri.parse(uri), arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    val column = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (column < 0 || cursor.isNull(column)) return@use null
                    cursor.getLong(column).takeIf { it >= 0 }
                }
        }.onFailure { failure ->
            AppLog.ignored(
                LogComponent.RESOURCES,
                "archive.stage.reported_size",
                "provider refused the size column",
                failure,
            )
        }.getOrNull()
}

/** Keeps the provider's stable reference alive until all bytes from its descriptor are consumed. */
private class ProviderClientInputStream(
    stream: InputStream,
    private val client: ContentProviderClient,
) : FilterInputStream(stream) {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            super.close()
        } finally {
            client.close()
        }
    }
}

internal class SafArchiveStager(
    private val inputOpener: ResourceInputOpener,
    private val stagingRoot: File,
    private val availableBytes: (File) -> Long = { it.usableSpace },
    private val providerIoScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val providerIoTimeoutMillis: Long = PROVIDER_IO_TIMEOUT_MILLIS,
    private val providerIoScheduler: ProviderIoDeadlineScheduler = RealProviderIoDeadlineScheduler,
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

    override suspend fun readLeadingBytes(
        sourceUri: String,
        maximumBytes: Int,
    ): ByteArray {
        require(sourceUri.startsWith("${ContentResolver.SCHEME_CONTENT}://"))
        require(maximumBytes in 1..MAXIMUM_PREFIX_BYTES)
        return CancellableProviderIo.execute(
            scope = providerIoScope,
            timeoutMillis = providerIoTimeoutMillis,
            scheduler = providerIoScheduler,
        ) { deadline ->
            CancellableProviderIo.useResource(
                cancellation = deadline,
                open = { inputOpener.open(sourceUri, deadline) },
            ) { stream ->
                val prefix = ByteArray(maximumBytes)
                var count = 0
                while (count < prefix.size) {
                    throwIfCancelled(deadline)
                    val read = stream.read(prefix, count, prefix.size - count)
                    throwIfCancelled(deadline)
                    if (read <= 0) break
                    count += read
                    deadline.rearm()
                }
                prefix.copyOf(count)
            }
        }
    }

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
        val reportedBytes = inputOpener.reportedSizeBytes(sourceUri)
        try {
            val available = availableBytes(stagingRoot)
            if (available < FREE_SPACE_RESERVE_BYTES) {
                throw ResourceStorageException(FREE_SPACE_RESERVE_BYTES, available)
            }
            cancellation.check()
            val staged =
                runBlocking {
                    CancellableProviderIo.execute(
                        scope = providerIoScope,
                        timeoutMillis = providerIoTimeoutMillis,
                        scheduler = providerIoScheduler,
                    ) { deadline ->
                        copyProviderInput(
                            source = sourceUri,
                            destination = destination,
                            cancellation = cancellation.combine(deadline),
                            maximumBytes = maximumBytes,
                            available = available,
                            sourceLabel = sourceLabel,
                            onProviderProgress = deadline::rearm,
                            onProgress = onProgress,
                        )
                    }
                }
            // A provider that ended the stream early leaves a short but structurally plausible
            // file, and the engine can only report it as a corrupt archive. Catching the
            // truncation here is what tells those two causes apart in the field.
            if (reportedBytes != null && reportedBytes != staged.sizeBytes) {
                throw ResourceDownloadException(
                    "resource_archive_mismatch",
                    "The selected $sourceLabel did not copy completely",
                )
            }
            logArchiveStage(sourceUri, operationId, sourceLabel, "ok", reportedBytes, staged.sizeBytes)
            return staged
        } catch (failure: Exception) {
            destination.delete()
            logArchiveStage(
                sourceUri,
                operationId,
                sourceLabel,
                if (failure is ProviderIoCancelledException) "skip" else "fail",
                reportedBytes,
                stagedBytes = null,
            )
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

    /**
     * The generic-path twin of `ResourceManager.logAudioArchiveStage`.
     *
     * Without it a dictionary import that fails leaves only the engine's
     * "archive is corrupt" message, which reads the same whether the file was bad or the copy
     * ended early. Reported-versus-staged bytes is the field that separates them.
     */
    private fun logArchiveStage(
        sourceUri: String,
        operationId: String,
        sourceLabel: String,
        outcome: String,
        reportedBytes: Long?,
        stagedBytes: Long?,
    ) {
        AppLog.i(
            LogComponent.RESOURCES,
            "resource.archive.stage",
            "outcome" to outcome,
            "operation" to operationId,
            "kind" to sourceLabel.replace(' ', '_'),
            "authority" to normalizedProviderAuthority(sourceUri),
            "reported_bytes" to (reportedBytes ?: "unknown"),
            "staged_bytes" to (stagedBytes ?: "unknown"),
            "size_agreement" to
                when {
                    reportedBytes == null || stagedBytes == null -> "unknown"
                    reportedBytes == stagedBytes -> "match"
                    else -> "mismatch"
                },
        )
    }

    override fun stageAudioArchive(
        sourceUri: String,
        operationId: String,
        cancellation: ResourceCancellationSignal,
        maximumBytes: Long,
        sourceLabel: String,
        onProgress: (Long, Long) -> Unit,
    ): StagedAudioArchive {
        require(sourceUri.startsWith("${ContentResolver.SCHEME_CONTENT}://"))
        require(maximumBytes in 1..MAXIMUM_SUPPORTED_BYTES)
        require(sourceLabel.isNotBlank() && sourceLabel.length <= 64)
        if (!stagingRoot.exists() && !stagingRoot.mkdirs()) {
            throw ResourceDownloadException("import_staging_failed", "Could not create private import staging")
        }
        val destination = File(stagingRoot, "$operationId-audio.bin")
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
                    scheduler = providerIoScheduler,
                ) { deadline ->
                    copyAudioProviderInput(
                        source = sourceUri,
                        destination = destination,
                        cancellation = cancellation.combine(deadline),
                        maximumBytes = maximumBytes,
                        available = available,
                        sourceLabel = sourceLabel,
                        onProviderProgress = deadline::rearm,
                        onProgress = onProgress,
                    )
                }
            }
        } catch (failure: Exception) {
            destination.delete()
            if (failure is ProviderIoCancelledException) {
                cancellation.check()
                throw sourceUnavailable(sourceLabel)
            }
            if (failure is ResourceDownloadException || failure is ResourceStorageException) {
                throw failure
            }
            // Provider exceptions may embed the URI or display name. Audio failures are reported
            // through stable codes and bounded metadata, never by retaining provider text.
            throw sourceUnavailable(sourceLabel)
        }
    }

    private fun copyProviderInput(
        source: String,
        destination: File,
        cancellation: ProviderIoCancellation,
        maximumBytes: Long,
        available: Long,
        sourceLabel: String,
        onProviderProgress: () -> Unit,
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
                    // Delivered bytes and end of stream are both provider progress. The deadline
                    // bounds a stalled provider, never a long transfer or the closing sync.
                    if (count != 0) onProviderProgress()
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

    private fun copyAudioProviderInput(
        source: String,
        destination: File,
        cancellation: ProviderIoCancellation,
        maximumBytes: Long,
        available: Long,
        sourceLabel: String,
        onProviderProgress: () -> Unit,
        onProgress: (Long, Long) -> Unit,
    ): StagedAudioArchive {
        return CancellableProviderIo.useResource(
            cancellation = cancellation,
            open = { openAudioInput(source, cancellation) },
        ) { input ->
            val prefix = readPrefix(input.stream, cancellation, onProviderProgress)
            val container = classifyAudioArchive(prefix, input.readMode)
            if (prefix.size.toLong() > maximumBytes) {
                throw archiveTooLarge(sourceLabel, prefix.size.toLong(), maximumBytes)
            }
            if (available - prefix.size < FREE_SPACE_RESERVE_BYTES) {
                throw ResourceStorageException(prefix.size + FREE_SPACE_RESERVE_BYTES, available)
            }

            val digest = MessageDigest.getInstance("SHA-256")
            var total = prefix.size.toLong()
            FileOutputStream(destination, false).use { output ->
                output.write(prefix)
                digest.update(prefix)
                onProgress(total, 0)
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    throwIfCancelled(cancellation)
                    val count = input.stream.read(buffer)
                    throwIfCancelled(cancellation)
                    if (count != 0) onProviderProgress()
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
            StagedAudioArchive(
                archive =
                    StagedArchive(
                        file = destination,
                        sha256 = digest.digest().joinToString("") { "%02x".format(it) },
                        sizeBytes = total,
                    ),
                readMode = input.readMode,
                container = container,
            )
        }
    }

    private fun openAudioInput(
        source: String,
        cancellation: ProviderIoCancellation,
    ): OpenedAudioInput {
        val raw =
            try {
                inputOpener.openRaw(source, cancellation)
            } catch (failure: FileNotFoundException) {
                if (cancellation.isCancelled()) throw ProviderIoCancelledException(failure)
                null
            }
        if (raw != null) return OpenedAudioInput(raw, AudioArchiveReadMode.RAW)
        if (cancellation.isCancelled()) throw ProviderIoCancelledException()
        return OpenedAudioInput(
            inputOpener.open(source, cancellation),
            AudioArchiveReadMode.ASSET_FALLBACK,
        )
    }

    private fun readPrefix(
        stream: InputStream,
        cancellation: ProviderIoCancellation,
        onProviderProgress: () -> Unit,
    ): ByteArray {
        val prefix = ByteArray(AUDIO_PREFIX_BYTES)
        var count = 0
        while (count < prefix.size) {
            throwIfCancelled(cancellation)
            val read = stream.read(prefix, count, prefix.size - count)
            throwIfCancelled(cancellation)
            if (read != 0) onProviderProgress()
            if (read < 0) break
            if (read == 0) continue
            count += read
        }
        return prefix.copyOf(count)
    }

    private fun classifyAudioArchive(
        prefix: ByteArray,
        readMode: AudioArchiveReadMode,
    ): AudioArchiveContainer {
        if (
            prefix.size >= 4 &&
                prefix[0] == 0x50.toByte() &&
                prefix[1] == 0x4b.toByte() &&
                prefix[2] == 0x03.toByte() &&
                prefix[3] == 0x04.toByte()
        ) {
            return AudioArchiveContainer.ZIP
        }
        if (prefix.startsWith(XZ_SIGNATURE)) return AudioArchiveContainer.XZ
        if (prefix.startsWith(GZIP_SIGNATURE)) return AudioArchiveContainer.GZIP
        if (
            prefix.size >= TAR_MAGIC_OFFSET + TAR_MAGIC.size &&
                prefix.copyOfRange(TAR_MAGIC_OFFSET, TAR_MAGIC_OFFSET + TAR_MAGIC.size)
                    .contentEquals(TAR_MAGIC)
        ) {
            return AudioArchiveContainer.TAR
        }
        if (readMode == AudioArchiveReadMode.ASSET_FALLBACK) {
            throw ResourceDownloadException(
                "resource_archive_provider_representation",
                "The provider did not expose the selected audio-pack archive",
            )
        }
        throw ResourceDownloadException(
            "resource_archive_unrecognized",
            "The selected audio-pack file is not a supported archive",
        )
    }

    private data class OpenedAudioInput(
        val stream: InputStream,
        val readMode: AudioArchiveReadMode,
    ) : Closeable {
        override fun close() = stream.close()
    }

    private fun throwIfCancelled(cancellation: ProviderIoCancellation) {
        if (cancellation.isCancelled()) throw ProviderIoCancelledException()
    }

    private fun sourceUnavailable(
        sourceLabel: String,
        cause: Throwable? = null,
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
        const val MAXIMUM_PREFIX_BYTES = 64
        const val AUDIO_PREFIX_BYTES = 265
        const val TAR_MAGIC_OFFSET = 257
        val XZ_SIGNATURE = byteArrayOf(0xfd.toByte(), 0x37, 0x7a, 0x58, 0x5a, 0x00)
        val GZIP_SIGNATURE = byteArrayOf(0x1f, 0x8b.toByte())
        val TAR_MAGIC = "ustar".encodeToByteArray()
        val FILE_SUFFIX = Regex("\\.[a-z0-9]{1,8}")
    }
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
