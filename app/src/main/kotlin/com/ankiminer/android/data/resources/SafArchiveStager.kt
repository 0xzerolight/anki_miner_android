package com.ankiminer.android.data.resources

import android.content.ContentResolver
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

internal class SafArchiveStager(
    private val resolver: ContentResolver,
    private val stagingRoot: File,
    private val availableBytes: (File) -> Long = { it.usableSpace },
) {
    fun stage(
        source: Uri,
        operationId: String,
        cancellation: ResourceCancellationSignal,
        fileSuffix: String = ".zip",
        maximumBytes: Long = DEFAULT_MAXIMUM_BYTES,
        sourceLabel: String = "resource",
        onProgress: (Long, Long) -> Unit,
    ): StagedArchive {
        require(source.scheme == ContentResolver.SCHEME_CONTENT)
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
            val digest = MessageDigest.getInstance("SHA-256")
            val input = resolver.openInputStream(source)
                ?: throw ResourceDownloadException("import_source_unavailable", "The selected $sourceLabel cannot be opened")
            var total = 0L
            input.use { stream ->
                FileOutputStream(destination, false).use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        cancellation.check()
                        val count = stream.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > maximumBytes) {
                            throw ResourceDownloadException("resource_archive_too_large", "The selected $sourceLabel exceeds its size limit")
                        }
                        if (available - total < FREE_SPACE_RESERVE_BYTES) {
                            throw ResourceStorageException(total + FREE_SPACE_RESERVE_BYTES, available)
                        }
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        onProgress(total, 0)
                    }
                    output.fd.sync()
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
        } catch (failure: Exception) {
            destination.delete()
            throw failure
        }
    }

    private companion object {
        const val BUFFER_BYTES = 256 * 1024
        const val DEFAULT_MAXIMUM_BYTES = 1024L * 1024 * 1024
        const val MAXIMUM_SUPPORTED_BYTES = 2L * 1024 * 1024 * 1024
        const val FREE_SPACE_RESERVE_BYTES = 32L * 1024 * 1024
        val FILE_SUFFIX = Regex("\\.[a-z0-9]{1,8}")
    }
}
