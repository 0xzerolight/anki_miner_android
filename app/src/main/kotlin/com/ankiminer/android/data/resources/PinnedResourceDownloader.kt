package com.ankiminer.android.data.resources

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

internal class ResourceCancellationSignal {
    private val cancelled = AtomicBoolean(false)

    fun cancel() {
        cancelled.set(true)
    }

    fun check() {
        if (cancelled.get()) {
            throw ResourceDownloadException("resource_operation_cancelled", "Resource operation was cancelled")
        }
    }
}

internal data class StagedArchive(
    val file: File,
    val sha256: String,
    val sizeBytes: Long,
)

internal fun interface DownloadConnectionFactory {
    fun open(url: String, offset: Long): HttpURLConnection
}

internal class HttpsDownloadConnectionFactory : DownloadConnectionFactory {
    override fun open(url: String, offset: Long): HttpURLConnection {
        var current = requireHttps(url)
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val connection =
                (current.toURL().openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    useCaches = false
                    setRequestProperty("Accept-Encoding", "identity")
                    setRequestProperty("User-Agent", "AnkiMinerAndroid/1 resource-installer")
                    if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
                }
            val status = connection.responseCode
            if (status !in REDIRECT_CODES) return connection
            if (redirectCount == MAX_REDIRECTS) {
                connection.disconnect()
                throw ResourceDownloadException("download_redirect_limit", "Resource download redirected too many times")
            }
            val location = connection.getHeaderField("Location")
                ?: run {
                    connection.disconnect()
                    throw ResourceDownloadException("download_redirect_invalid", "Resource download returned an invalid redirect")
                }
            val resolved = requireHttps(current.resolve(location).toString())
            connection.disconnect()
            current = resolved
        }
        throw AssertionError("redirect loop exhausted")
    }

    private fun requireHttps(value: String): URI {
        val uri =
            try {
                URI(value)
            } catch (failure: Exception) {
                throw ResourceDownloadException("download_url_invalid", "Resource URL is invalid", failure)
            }
        if (
            uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null ||
                uri.fragment != null
        ) {
            throw ResourceDownloadException("download_url_invalid", "Resource URL must use HTTPS")
        }
        return uri
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 20_000
        const val READ_TIMEOUT_MS = 45_000
        const val MAX_REDIRECTS = 5
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}

internal class PinnedResourceDownloader(
    private val stagingRoot: File,
    private val connections: DownloadConnectionFactory = HttpsDownloadConnectionFactory(),
    private val availableBytes: (File) -> Long = { it.usableSpace },
) {
    fun download(
        archive: ResourceArchive,
        cancellation: ResourceCancellationSignal,
        onProgress: (Long, Long, ResourceOperationPhase) -> Unit,
    ): StagedArchive {
        require(archive.sizeBytes > 0)
        require(archive.sha256.matches(SHA_256))
        preparePrivateRoot()
        val partial = File(stagingRoot, "${archive.sha256}.part")
        val ready = File(stagingRoot, "${archive.sha256}.ready")
        partial.delete()
        ready.delete()
        try {
            checkFreeSpace(archive.sizeBytes)
            var lastFailure: IOException? = null
            repeat(MAX_ATTEMPTS) {
                cancellation.check()
                try {
                    val actual = transferAttempt(archive, partial, cancellation, onProgress)
                    if (actual != archive.sha256) {
                        throw ResourceDownloadException(
                            "resource_archive_mismatch",
                            "Downloaded resource did not match its catalog SHA-256",
                        )
                    }
                    if (!partial.renameTo(ready)) {
                        throw ResourceDownloadException("download_publish_failed", "Could not publish the verified private archive")
                    }
                    return StagedArchive(ready, actual, archive.sizeBytes)
                } catch (failure: ResourceDownloadException) {
                    if (failure.stableCode !in RETRYABLE_CODES) throw failure
                    lastFailure = failure
                } catch (failure: IOException) {
                    lastFailure = failure
                }
            }
            throw ResourceDownloadException(
                "download_retry_exhausted",
                "Resource download failed after $MAX_ATTEMPTS bounded attempts",
                lastFailure,
            )
        } catch (failure: Exception) {
            partial.delete()
            ready.delete()
            throw failure
        }
    }

    private fun transferAttempt(
        archive: ResourceArchive,
        partial: File,
        cancellation: ResourceCancellationSignal,
        onProgress: (Long, Long, ResourceOperationPhase) -> Unit,
    ): String {
        var offset = partial.length()
        if (offset > archive.sizeBytes) {
            partial.delete()
            offset = 0
        }
        val digest = MessageDigest.getInstance("SHA-256")
        if (offset > 0) {
            FileInputStream(partial).use { input ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    cancellation.check()
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                    onProgress(input.channel.position(), archive.sizeBytes, ResourceOperationPhase.VERIFYING)
                }
            }
        }
        val connection = connections.open(archive.url, offset)
        try {
            val status = connection.responseCode
            val append = status == HttpURLConnection.HTTP_PARTIAL && offset > 0
            if (status == HttpURLConnection.HTTP_OK && offset > 0) {
                offset = 0
                digest.reset()
            } else if (append) {
                validateContentRange(connection.getHeaderField("Content-Range"), offset, archive.sizeBytes)
            } else if (status != HttpURLConnection.HTTP_OK) {
                val code = if (status in RETRYABLE_HTTP) "download_http_retryable" else "download_http_rejected"
                throw ResourceDownloadException(code, "Resource host returned HTTP $status")
            }
            val contentLength = connection.contentLengthLong
            val expectedRemaining = archive.sizeBytes - offset
            if (contentLength > expectedRemaining) {
                throw ResourceDownloadException("resource_archive_mismatch", "Resource response exceeds its catalog size")
            }
            if (contentLength >= 0L && contentLength < expectedRemaining) {
                throw ResourceDownloadException("download_incomplete", "Resource response is shorter than its catalog size")
            }
            FileOutputStream(partial, append).use { output ->
                if (!append) output.channel.truncate(0)
                BufferedInputStream(connection.inputStream, BUFFER_BYTES).use { input ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var total = offset
                    onProgress(total, archive.sizeBytes, ResourceOperationPhase.DOWNLOADING)
                    while (true) {
                        cancellation.check()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > archive.sizeBytes) {
                            throw ResourceDownloadException("resource_archive_mismatch", "Resource response exceeds its catalog size")
                        }
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        onProgress(total, archive.sizeBytes, ResourceOperationPhase.DOWNLOADING)
                    }
                    output.fd.sync()
                }
            }
            if (partial.length() != archive.sizeBytes) {
                throw ResourceDownloadException("download_incomplete", "Resource response ended before its catalog size")
            }
            onProgress(archive.sizeBytes, archive.sizeBytes, ResourceOperationPhase.VERIFYING)
            return digest.digest().joinToString("") { "%02x".format(it) }
        } finally {
            connection.disconnect()
        }
    }

    private fun validateContentRange(value: String?, offset: Long, total: Long) {
        val match = value?.let(CONTENT_RANGE::matchEntire)
            ?: throw ResourceDownloadException("download_resume_invalid", "Resource host returned an invalid range")
        val start = match.groupValues[1].toLongOrNull()
        val end = match.groupValues[2].toLongOrNull()
        val responseTotal = match.groupValues[3].toLongOrNull()
        if (
            start != offset ||
                end != total - 1 ||
                responseTotal != total
        ) {
            throw ResourceDownloadException("download_resume_invalid", "Resource host returned the wrong byte range")
        }
    }

    private fun preparePrivateRoot() {
        if (!stagingRoot.exists() && !stagingRoot.mkdirs()) {
            throw ResourceDownloadException("download_staging_failed", "Could not create private resource staging")
        }
        if (!stagingRoot.isDirectory) {
            throw ResourceDownloadException("download_staging_failed", "Private resource staging is unavailable")
        }
    }

    private fun checkFreeSpace(required: Long) {
        val available = availableBytes(stagingRoot)
        if (available < required + FREE_SPACE_RESERVE_BYTES) {
            throw ResourceStorageException(required + FREE_SPACE_RESERVE_BYTES, available)
        }
    }

    companion object {
        private const val BUFFER_BYTES = 256 * 1024
        private const val MAX_ATTEMPTS = 3
        private const val FREE_SPACE_RESERVE_BYTES = 32L * 1024 * 1024
        private val SHA_256 = Regex("[0-9a-f]{64}")
        private val CONTENT_RANGE = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)")
        private val RETRYABLE_HTTP = setOf(408, 425, 429, 500, 502, 503, 504)
        private val RETRYABLE_CODES = setOf("download_http_retryable", "download_incomplete")
    }
}
