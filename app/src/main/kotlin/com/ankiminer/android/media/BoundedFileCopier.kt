package com.ankiminer.android.media

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/** Limits for one private, caller-owned staging copy. */
internal data class BoundedFileCopyPolicy(
    val maxBytes: Long,
    val freeSpaceReserveBytes: Long,
    val bufferBytes: Int = DEFAULT_BUFFER_BYTES,
    /**
     * Minimum copied bytes between two checkpoints. A checkpoint publishes progress and re-probes
     * free space; between them the copy only reads and writes.
     *
     * Doing both per buffer means doing both every 256 KiB. Every progress event travels the whole
     * progress path — the coordinator's global monitor, a state emission, then a foreground
     * notification rebuild behind an ActivityManager round trip — and a 2 GiB source produced
     * roughly 8,200 of them. The first and the final progress events are always delivered.
     */
    val checkpointIntervalBytes: Long = DEFAULT_CHECKPOINT_INTERVAL_BYTES,
) {
    init {
        require(maxBytes > 0L) { "Copy limit must be positive" }
        require(freeSpaceReserveBytes >= 0L) { "Free-space reserve must not be negative" }
        require(bufferBytes in 1..MAX_BUFFER_BYTES) { "Copy buffer size is invalid" }
        require(checkpointIntervalBytes > 0L) { "Checkpoint interval must be positive" }
    }

    private companion object {
        const val DEFAULT_BUFFER_BYTES = 256 * 1024
        const val MAX_BUFFER_BYTES = 1024 * 1024
        const val DEFAULT_CHECKPOINT_INTERVAL_BYTES = 1024L * 1024
    }
}

internal data class BoundedFileCopyProgress(
    val copiedBytes: Long,
    val expectedBytes: Long?,
) {
    init {
        require(copiedBytes >= 0L)
        require(expectedBytes == null || expectedBytes >= 0L)
    }
}

internal fun interface FileCopyCancellation : ProviderIoCancellation {
    companion object {
        val NONE = FileCopyCancellation { false }
    }
}

internal fun interface FileCopyProgressListener {
    fun onProgress(progress: BoundedFileCopyProgress)

    companion object {
        val NONE = FileCopyProgressListener { }
    }
}

internal sealed class BoundedFileCopyException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

internal class FileCopyCancelledException(cause: Throwable? = null) :
    BoundedFileCopyException("Private file copy was cancelled", cause)

internal class FileCopyLimitExceededException(
    val maxBytes: Long,
    val observedBytes: Long,
) : BoundedFileCopyException("Selected file exceeds the private copy limit")

internal class FileCopyStorageException(
    val requiredBytes: Long,
    val availableBytes: Long,
) : BoundedFileCopyException("Not enough private storage to copy the selected file")

internal class FileCopySizeMismatchException(
    val expectedBytes: Long,
    val actualBytes: Long,
) : BoundedFileCopyException("Selected file size changed while it was being copied")

/**
 * Copies an input into a private destination without allowing an unbounded cache write.
 *
 * [destination] must be a private staging file owned by the caller. It is always removed when
 * the operation fails, including preflight and progress-listener failures. A known source size is
 * checked before the source stream is opened; unknown sizes are bounded while streaming. Usable
 * space is checked before opening the source and again whenever the bytes a probe pre-authorized
 * run out, so the configured reserve is not deliberately consumed.
 *
 * Progress is coalesced onto [BoundedFileCopyPolicy.checkpointIntervalBytes]; the first event and
 * the event carrying the final byte count are always delivered.
 */
internal class BoundedFileCopier(
    private val availableBytes: (File) -> Long = { it.usableSpace },
) {
    fun copy(
        openSource: () -> InputStream,
        destination: File,
        knownSizeBytes: Long?,
        policy: BoundedFileCopyPolicy,
        cancellation: ProviderIoCancellation = FileCopyCancellation.NONE,
        progressListener: FileCopyProgressListener = FileCopyProgressListener.NONE,
    ): Long {
        require(knownSizeBytes == null || knownSizeBytes >= 0L) {
            "Known source size must not be negative"
        }
        require(!destination.isDirectory) { "Private copy destination must not be a directory" }

        try {
            checkCancellation(cancellation)
            if (knownSizeBytes != null && knownSizeBytes > policy.maxBytes) {
                throw FileCopyLimitExceededException(policy.maxBytes, knownSizeBytes)
            }

            val storageRoot = destination.absoluteFile.parentFile ?: destination.absoluteFile
            val preflightRequired =
                requiredStorageBytes(knownSizeBytes ?: 0L, policy.freeSpaceReserveBytes)
            checkAvailableStorage(storageRoot, preflightRequired)
            progressListener.onProgress(BoundedFileCopyProgress(0L, knownSizeBytes))
            checkCancellation(cancellation)

            val copiedBytes =
                CancellableProviderIo.useResource(cancellation, openSource) { input ->
                    FileOutputStream(destination, false).use { output ->
                        val buffer = ByteArray(policy.bufferBytes)
                        var copied = 0L
                        var reportedBytes = 0L
                        var authorizedBytes = 0L
                        while (true) {
                            checkCancellation(cancellation)
                            val count = input.read(buffer)
                            checkCancellation(cancellation)
                            if (count < 0) break
                            if (count == 0) continue

                            val next = checkedTotal(copied, count)
                            if (next > policy.maxBytes) {
                                throw FileCopyLimitExceededException(policy.maxBytes, next)
                            }
                            if (knownSizeBytes != null && next > knownSizeBytes) {
                                throw FileCopySizeMismatchException(knownSizeBytes, next)
                            }
                            if (count > authorizedBytes) {
                                // One probe authorizes a whole interval, and demands the reserve on
                                // top of it, so no byte is written into unverified space. Bounding
                                // the interval by what is still copyable keeps a small source from
                                // demanding free space it will never use.
                                val remaining = (knownSizeBytes ?: policy.maxBytes) - copied
                                val chunk =
                                    maxOf(
                                        minOf(policy.checkpointIntervalBytes, remaining),
                                        count.toLong(),
                                    )
                                checkAvailableStorage(
                                    storageRoot,
                                    requiredStorageBytes(chunk, policy.freeSpaceReserveBytes),
                                )
                                authorizedBytes = chunk
                            }
                            checkCancellation(cancellation)
                            output.write(buffer, 0, count)
                            authorizedBytes -= count
                            copied = next
                            if (copied - reportedBytes >= policy.checkpointIntervalBytes) {
                                reportedBytes = copied
                                progressListener.onProgress(
                                    BoundedFileCopyProgress(copied, knownSizeBytes),
                                )
                            }
                        }
                        if (copied != reportedBytes) {
                            // The determinate bar has to arrive at its total even when the last
                            // buffers fell inside one coalescing interval.
                            progressListener.onProgress(
                                BoundedFileCopyProgress(copied, knownSizeBytes),
                            )
                        }
                        if (knownSizeBytes != null && copied != knownSizeBytes) {
                            throw FileCopySizeMismatchException(knownSizeBytes, copied)
                        }
                        checkCancellation(cancellation)
                        output.fd.sync()
                        checkCancellation(cancellation)
                        copied
                    }
                }

            if (!destination.isFile || destination.length() != copiedBytes) {
                throw IOException("Private file copy did not produce the expected destination")
            }
            return copiedBytes
        } catch (failure: Throwable) {
            val reported =
                if (failure is ProviderIoCancelledException) {
                    FileCopyCancelledException(failure)
                } else {
                    failure
                }
            cleanupFailedDestination(destination, reported)
            throw reported
        }
    }

    private fun checkAvailableStorage(
        storageRoot: File,
        requiredBytes: Long,
    ) {
        val available = availableBytes(storageRoot).coerceAtLeast(0L)
        if (available < requiredBytes) {
            throw FileCopyStorageException(requiredBytes, available)
        }
    }

    private fun cleanupFailedDestination(
        destination: File,
        primaryFailure: Throwable,
    ) {
        try {
            if (destination.exists() && !destination.delete()) {
                primaryFailure.addSuppressed(
                    IOException("Could not remove failed private copy: ${destination.absolutePath}"),
                )
            }
        } catch (cleanupFailure: Exception) {
            primaryFailure.addSuppressed(cleanupFailure)
        }
    }

    private fun checkCancellation(cancellation: ProviderIoCancellation) {
        if (cancellation.isCancelled()) throw FileCopyCancelledException()
    }

    private fun checkedTotal(
        copiedBytes: Long,
        count: Int,
    ): Long =
        try {
            Math.addExact(copiedBytes, count.toLong())
        } catch (_: ArithmeticException) {
            Long.MAX_VALUE
        }

    private fun requiredStorageBytes(
        copyBytes: Long,
        reserveBytes: Long,
    ): Long =
        try {
            Math.addExact(copyBytes, reserveBytes)
        } catch (_: ArithmeticException) {
            Long.MAX_VALUE
        }
}
