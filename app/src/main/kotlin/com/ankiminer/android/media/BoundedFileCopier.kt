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
) {
    init {
        require(maxBytes > 0L) { "Copy limit must be positive" }
        require(freeSpaceReserveBytes >= 0L) { "Free-space reserve must not be negative" }
        require(bufferBytes in 1..MAX_BUFFER_BYTES) { "Copy buffer size is invalid" }
    }

    private companion object {
        const val DEFAULT_BUFFER_BYTES = 256 * 1024
        const val MAX_BUFFER_BYTES = 1024 * 1024
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

internal fun interface FileCopyCancellation {
    fun isCancelled(): Boolean

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
) : IOException(message)

internal class FileCopyCancelledException :
    BoundedFileCopyException("Private file copy was cancelled")

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
 * space is checked both before opening the source and before every write so the configured reserve
 * is not deliberately consumed.
 */
internal class BoundedFileCopier(
    private val availableBytes: (File) -> Long = { it.usableSpace },
) {
    fun copy(
        openSource: () -> InputStream,
        destination: File,
        knownSizeBytes: Long?,
        policy: BoundedFileCopyPolicy,
        cancellation: FileCopyCancellation = FileCopyCancellation.NONE,
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
                openSource().use { input ->
                    FileOutputStream(destination, false).use { output ->
                        val buffer = ByteArray(policy.bufferBytes)
                        var copied = 0L
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
                            checkAvailableStorage(
                                storageRoot,
                                requiredStorageBytes(count.toLong(), policy.freeSpaceReserveBytes),
                            )
                            checkCancellation(cancellation)
                            output.write(buffer, 0, count)
                            copied = next
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
            cleanupFailedDestination(destination, failure)
            throw failure
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

    private fun checkCancellation(cancellation: FileCopyCancellation) {
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
