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
        onProgress: (Long, Long) -> Unit,
    ): StagedArchive {
        require(source.scheme == ContentResolver.SCHEME_CONTENT)
        if (!stagingRoot.exists() && !stagingRoot.mkdirs()) {
            throw ResourceDownloadException("import_staging_failed", "Could not create private import staging")
        }
        val destination = File(stagingRoot, "$operationId-custom.zip")
        destination.delete()
        try {
            val available = availableBytes(stagingRoot)
            if (available < FREE_SPACE_RESERVE_BYTES) {
                throw ResourceStorageException(FREE_SPACE_RESERVE_BYTES, available)
            }
            val digest = MessageDigest.getInstance("SHA-256")
            val input = resolver.openInputStream(source)
                ?: throw ResourceDownloadException("import_source_unavailable", "The selected dictionary cannot be opened")
            var total = 0L
            input.use { stream ->
                FileOutputStream(destination, false).use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        cancellation.check()
                        val count = stream.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_ARCHIVE_BYTES) {
                            throw ResourceDownloadException("resource_archive_too_large", "Custom dictionary exceeds 1 GiB")
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
            if (total <= 0) throw ResourceDownloadException("resource_archive_mismatch", "Custom dictionary is empty")
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
        const val MAX_ARCHIVE_BYTES = 1024L * 1024 * 1024
        const val FREE_SPACE_RESERVE_BYTES = 32L * 1024 * 1024
    }
}
