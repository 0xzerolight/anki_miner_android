package com.ankiminer.android.data.resources

import com.ankiminer.android.media.ProviderIoCancellation
import com.ankiminer.android.media.ProviderIoCancellationController
import com.ankiminer.android.media.ProviderIoCancellationRegistration
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.max
import kotlin.math.min

internal class ResourceCancellationSignal : ProviderIoCancellation {
    private val delegate = ProviderIoCancellationController()

    fun cancel() {
        delegate.cancel()
    }

    override fun isCancelled(): Boolean = delegate.isCancelled()

    override fun invokeOnCancellation(
        listener: () -> Unit,
    ): ProviderIoCancellationRegistration = delegate.invokeOnCancellation(listener)

    fun check() {
        if (isCancelled()) {
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

internal class HttpsDownloadConnectionFactory(
    private val connectionOpener: (URL) -> HttpURLConnection = {
        it.openConnection() as HttpURLConnection
    },
) : DownloadConnectionFactory {
    override fun open(url: String, offset: Long): HttpURLConnection {
        var current = requireHttps(url)
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val connection = connectionOpener(current.toURL())
            var handedOff = false
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.useCaches = false
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.setRequestProperty("User-Agent", "AnkiMinerAndroid/1 resource-installer")
                if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
                val status = connection.responseCode
                if (status !in REDIRECT_CODES) {
                    handedOff = true
                    return connection
                }
                if (redirectCount == MAX_REDIRECTS) {
                    throw ResourceDownloadException("download_redirect_limit", "Resource download redirected too many times")
                }
                val location =
                    connection.getHeaderField("Location")
                        ?.takeIf { it.isNotBlank() }
                        ?: throw ResourceDownloadException(
                            "download_redirect_invalid",
                            "Resource download returned an invalid redirect",
                        )
                current = resolveRedirect(current, location)
            } finally {
                if (!handedOff) connection.disconnect()
            }
        }
        throw AssertionError("redirect loop exhausted")
    }

    private fun resolveRedirect(
        current: URI,
        location: String,
    ): URI =
        try {
            requireHttps(
                current.resolve(location).toString(),
                stableCode = "download_redirect_invalid",
                message = "Resource download returned an invalid redirect",
            )
        } catch (failure: ResourceDownloadException) {
            throw failure
        } catch (failure: Exception) {
            throw ResourceDownloadException(
                "download_redirect_invalid",
                "Resource download returned an invalid redirect",
                failure,
            )
        }

    private fun requireHttps(
        value: String,
        stableCode: String = "download_url_invalid",
        message: String = "Resource URL is invalid",
    ): URI {
        val uri =
            try {
                URI(value)
            } catch (failure: Exception) {
                throw ResourceDownloadException(stableCode, message, failure)
            }
        if (
            uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null ||
                uri.fragment != null
        ) {
            throw ResourceDownloadException(stableCode, message)
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

internal fun interface ResourceRetryDelay {
    fun await(
        delayMillis: Long,
        cancellation: ResourceCancellationSignal,
    )
}

private object CancellationAwareRetryDelay : ResourceRetryDelay {
    override fun await(
        delayMillis: Long,
        cancellation: ResourceCancellationSignal,
    ) {
        var remaining = delayMillis
        while (remaining > 0L) {
            cancellation.check()
            val slice = min(remaining, RETRY_CANCELLATION_SLICE_MILLIS)
            try {
                Thread.sleep(slice)
            } catch (failure: InterruptedException) {
                Thread.currentThread().interrupt()
                cancellation.check()
                throw ResourceDownloadException(
                    "download_retry_exhausted",
                    "Resource retry delay was interrupted",
                    failure,
                )
            }
            remaining -= slice
        }
        cancellation.check()
    }

    private const val RETRY_CANCELLATION_SLICE_MILLIS = 100L
}

internal class PinnedResourceDownloader(
    private val stagingRoot: File,
    private val connections: DownloadConnectionFactory = HttpsDownloadConnectionFactory(),
    private val availableBytes: (File) -> Long = { it.usableSpace },
    private val retryDelay: ResourceRetryDelay = CancellationAwareRetryDelay,
    private val retryJitterMillis: (Long) -> Long = { maximum ->
        if (maximum <= 0L) 0L else ThreadLocalRandom.current().nextLong(maximum + 1)
    },
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    private val writeChunk: (FileOutputStream, ByteArray, Int) -> Unit = { output, bytes, count ->
        output.write(bytes, 0, count)
    },
    private val syncOutput: (FileOutputStream) -> Unit = { it.fd.sync() },
) {
    /**
     * Reconciles durable download state against the frozen catalog. Only direct, regular files
     * with an exact catalog-derived name and a plausible size survive process restart.
     */
    fun reconcile(archives: Collection<ResourceArchive>) {
        archives.forEach(::requireValidArchive)
        preparePrivateRoot()
        val expected = archives.associateBy { it.sha256 }
        val validReady = mutableSetOf<String>()
        val entries =
            stagingRoot.listFiles()?.toList()
                ?: throw ResourceDownloadException(
                    "download_staging_failed",
                    "Could not inspect private resource staging",
                )

        entries.forEach { entry ->
            val stagedName = STAGED_FILE.matchEntire(entry.name)
            val archive = stagedName?.groupValues?.get(1)?.let(expected::get)
            val suffix = stagedName?.groupValues?.get(2)
            if (
                archive == null ||
                suffix !in STAGED_SUFFIXES ||
                !isDirectRegularFile(entry)
            ) {
                deleteEntry(entry)
            } else if (suffix == READY_SUFFIX) {
                if (entry.length() == archive.sizeBytes && sha256(entry) == archive.sha256) {
                    validReady += archive.sha256
                } else {
                    deleteEntry(entry)
                }
            } else if (entry.length() <= 0L || entry.length() > archive.sizeBytes) {
                deleteEntry(entry)
            }
        }

        validReady.forEach { sha256 ->
            deleteEntry(File(stagingRoot, "$sha256.$PART_SUFFIX"))
        }
    }

    fun discard(archive: ResourceArchive) {
        requireValidArchive(archive)
        preparePrivateRoot()
        deleteEntry(File(stagingRoot, "${archive.sha256}.$PART_SUFFIX"))
        deleteEntry(File(stagingRoot, "${archive.sha256}.$READY_SUFFIX"))
    }

    fun download(
        archive: ResourceArchive,
        cancellation: ResourceCancellationSignal,
        onProgress: (Long, Long, ResourceOperationPhase) -> Unit,
    ): StagedArchive {
        requireValidArchive(archive)
        preparePrivateRoot()
        val partial = File(stagingRoot, "${archive.sha256}.$PART_SUFFIX")
        val ready = File(stagingRoot, "${archive.sha256}.$READY_SUFFIX")
        try {
            reconcileArchiveFiles(archive, partial, ready)
            if (ready.exists()) {
                cancellation.check()
                val actual =
                    sha256(ready, cancellation) { current ->
                        onProgress(current, archive.sizeBytes, ResourceOperationPhase.VERIFYING)
                    }
                if (actual == archive.sha256) {
                    deleteEntry(partial)
                    return StagedArchive(ready, actual, archive.sizeBytes)
                }
                deleteEntry(ready)
            }

            var lastFailure: IOException? = null
            repeat(MAX_ATTEMPTS) { attempt ->
                cancellation.check()
                checkFreeSpace((archive.sizeBytes - partial.length()).coerceAtLeast(0L))
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
                    if (attempt < MAX_ATTEMPTS - 1) {
                        awaitRetry(attempt, failure.retryAfterMillis, cancellation)
                    }
                } catch (failure: LocalDownloadIOException) {
                    throw classifyLocalFailure(archive, partial, failure.localCause)
                } catch (failure: IOException) {
                    lastFailure = failure
                    if (attempt < MAX_ATTEMPTS - 1) {
                        awaitRetry(attempt, retryAfterMillis = null, cancellation)
                    }
                }
            }
            throw ResourceDownloadException(
                "download_retry_exhausted",
                "Resource download failed after $MAX_ATTEMPTS bounded attempts",
                lastFailure,
                formatArguments = listOf(MAX_ATTEMPTS),
            )
        } catch (failure: Exception) {
            if (!shouldPreservePartial(failure)) {
                deleteEntry(partial)
                deleteEntry(ready)
            } else if (partial.length() == 0L) {
                deleteEntry(partial)
            }
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
            try {
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
            } catch (failure: ResourceDownloadException) {
                throw failure
            } catch (failure: IOException) {
                throw LocalDownloadIOException(failure)
            }
        }
        if (offset == archive.sizeBytes) {
            syncPartial(partial)
            onProgress(offset, archive.sizeBytes, ResourceOperationPhase.VERIFYING)
            return digest.digest().joinToString("") { "%02x".format(it) }
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
                throw ResourceDownloadException(
                    code,
                    "Resource host returned HTTP $status",
                    formatArguments = listOf(status),
                    retryAfterMillis =
                        if (status == 429 || status == HttpURLConnection.HTTP_UNAVAILABLE) {
                            parseRetryAfter(connection.getHeaderField("Retry-After"))
                        } else {
                            null
                        },
                )
            }
            val contentLength = connection.contentLengthLong
            val expectedRemaining = archive.sizeBytes - offset
            if (contentLength > expectedRemaining) {
                throw ResourceDownloadException("resource_archive_mismatch", "Resource response exceeds its catalog size")
            }
            if (contentLength >= 0L && contentLength < expectedRemaining) {
                throw ResourceDownloadException("download_incomplete", "Resource response is shorter than its catalog size")
            }
            val output = localIo { FileOutputStream(partial, append) }
            var primaryFailure: Throwable? = null
            try {
                if (!append) localIo { output.channel.truncate(0) }
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
                        localIo { writeChunk(output, buffer, count) }
                        digest.update(buffer, 0, count)
                        onProgress(total, archive.sizeBytes, ResourceOperationPhase.DOWNLOADING)
                    }
                    localIo { syncOutput(output) }
                }
            } catch (failure: Throwable) {
                primaryFailure = failure
                throw failure
            } finally {
                try {
                    output.close()
                } catch (closeFailure: IOException) {
                    if (primaryFailure == null) {
                        throw LocalDownloadIOException(closeFailure)
                    }
                    if (primaryFailure !== closeFailure) {
                        primaryFailure?.addSuppressed(closeFailure)
                    }
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
        if (Files.isSymbolicLink(stagingRoot.toPath()) || !stagingRoot.isDirectory) {
            throw ResourceDownloadException("download_staging_failed", "Private resource staging is unavailable")
        }
    }

    private fun checkFreeSpace(required: Long) {
        if (required <= 0L) return
        val available = availableBytes(stagingRoot)
        if (available < required + FREE_SPACE_RESERVE_BYTES) {
            throw ResourceStorageException(required + FREE_SPACE_RESERVE_BYTES, available)
        }
    }

    private fun awaitRetry(
        attempt: Int,
        retryAfterMillis: Long?,
        cancellation: ResourceCancellationSignal,
    ) {
        val exponential = min(RETRY_BASE_DELAY_MILLIS shl attempt, RETRY_MAX_DELAY_MILLIS)
        val maximumJitter = exponential / 2
        val jitter = retryJitterMillis(maximumJitter).coerceIn(0L, maximumJitter)
        val delay =
            max(exponential + jitter, retryAfterMillis ?: 0L)
                .coerceAtMost(RETRY_MAX_DELAY_MILLIS)
        retryDelay.await(delay, cancellation)
    }

    private fun parseRetryAfter(value: String?): Long? {
        val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val seconds = text.toLongOrNull()
        if (seconds != null) {
            if (seconds < 0L) return null
            return min(seconds, RETRY_MAX_DELAY_MILLIS / 1000) * 1000
        }
        return try {
            (ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() -
                currentTimeMillis())
                .coerceIn(0L, RETRY_MAX_DELAY_MILLIS)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private fun classifyLocalFailure(
        archive: ResourceArchive,
        partial: File,
        failure: IOException,
    ): IOException {
        val remaining = (archive.sizeBytes - partial.length()).coerceAtLeast(0L)
        val required = remaining + FREE_SPACE_RESERVE_BYTES
        val available = availableBytes(stagingRoot).coerceAtLeast(0L)
        if (available < required || isStorageExhaustion(failure)) {
            return ResourceStorageException(required, available, failure)
        }
        return ResourceDownloadException(
            "download_staging_failed",
            "Could not write private resource staging",
            failure,
        )
    }

    private fun syncPartial(partial: File) {
        localIo {
            FileOutputStream(partial, true).use { output ->
                syncOutput(output)
            }
        }
    }

    private fun isStorageExhaustion(failure: IOException): Boolean {
        var current: Throwable? = failure
        while (current != null) {
            val message = current.message?.lowercase(Locale.ROOT).orEmpty()
            if (
                "enospc" in message ||
                "no space left on device" in message ||
                "edquot" in message ||
                "disk quota exceeded" in message ||
                "quota exceeded" in message
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private inline fun <T> localIo(block: () -> T): T =
        try {
            block()
        } catch (failure: ResourceDownloadException) {
            throw failure
        } catch (failure: IOException) {
            throw LocalDownloadIOException(failure)
        }

    private fun reconcileArchiveFiles(
        archive: ResourceArchive,
        partial: File,
        ready: File,
    ) {
        if (partial.exists() || Files.isSymbolicLink(partial.toPath())) {
            if (!isDirectRegularFile(partial) || partial.length() > archive.sizeBytes) {
                deleteEntry(partial)
            }
        }
        if (ready.exists() || Files.isSymbolicLink(ready.toPath())) {
            if (!isDirectRegularFile(ready) || ready.length() != archive.sizeBytes) {
                deleteEntry(ready)
            }
        }
    }

    private fun isDirectRegularFile(file: File): Boolean {
        if (Files.isSymbolicLink(file.toPath()) || !file.isFile) return false
        return try {
            file.canonicalFile.parentFile == stagingRoot.canonicalFile
        } catch (_: IOException) {
            false
        }
    }

    private fun deleteEntry(file: File) {
        val path = file.toPath()
        if (!file.exists() && !Files.isSymbolicLink(path)) return
        val deleted =
            if (file.isDirectory && !Files.isSymbolicLink(path)) {
                file.listFiles()?.forEach(::deleteEntry)
                file.delete()
            } else {
                file.delete()
            }
        if (!deleted && (file.exists() || Files.isSymbolicLink(path))) {
            throw ResourceDownloadException(
                "download_staging_failed",
                "Could not reconcile private resource staging",
            )
        }
    }

    private fun sha256(
        file: File,
        cancellation: ResourceCancellationSignal? = null,
        onProgress: (Long) -> Unit = {},
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                cancellation?.check()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
                onProgress(input.channel.position())
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun requireValidArchive(archive: ResourceArchive) {
        require(archive.sizeBytes > 0)
        require(archive.sha256.matches(SHA_256))
    }

    private fun shouldPreservePartial(failure: Exception): Boolean =
        failure is ResourceStorageException ||
            (failure is ResourceDownloadException && failure.stableCode in PRESERVE_PARTIAL_CODES)

    companion object {
        private const val BUFFER_BYTES = 256 * 1024
        private const val MAX_ATTEMPTS = 3
        private const val FREE_SPACE_RESERVE_BYTES = 32L * 1024 * 1024
        private const val RETRY_BASE_DELAY_MILLIS = 500L
        private const val RETRY_MAX_DELAY_MILLIS = 60_000L
        private val SHA_256 = Regex("[0-9a-f]{64}")
        private val STAGED_FILE = Regex("([0-9a-f]{64})\\.(part|ready)")
        private val CONTENT_RANGE = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)")
        private val RETRYABLE_HTTP = setOf(408, 425, 429, 500, 502, 503, 504)
        private val RETRYABLE_CODES = setOf("download_http_retryable", "download_incomplete")
        private val PRESERVE_PARTIAL_CODES = setOf("download_retry_exhausted")
        private const val PART_SUFFIX = "part"
        private const val READY_SUFFIX = "ready"
        private val STAGED_SUFFIXES = setOf(PART_SUFFIX, READY_SUFFIX)
    }

    private class LocalDownloadIOException(
        val localCause: IOException,
    ) : IOException(localCause.message, localCause)
}
