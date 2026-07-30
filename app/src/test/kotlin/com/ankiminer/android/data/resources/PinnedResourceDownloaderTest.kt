package com.ankiminer.android.data.resources

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PinnedResourceDownloaderTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun transientDisconnectResumesWithinBoundedOperationAndPublishesVerifiedFile() {
        val content = "immutable resource bytes".toByteArray()
        val seenOffsets = mutableListOf<Long>()
        val responses =
            ArrayDeque<HttpURLConnection>().apply {
                add(
                    FakeConnection(
                        code = HttpURLConnection.HTTP_OK,
                        input = DisconnectingInput(content, 4),
                        contentLength = content.size.toLong(),
                    ),
                )
                add(
                    FakeConnection(
                        code = HttpURLConnection.HTTP_PARTIAL,
                        input = ByteArrayInputStream(content.copyOfRange(4, content.size)),
                        contentLength = (content.size - 4).toLong(),
                        contentRange = "bytes 4-${content.lastIndex}/${content.size}",
                    ),
                )
            }
        val root = temporary.newFolder("staging")
        val downloader =
            PinnedResourceDownloader(
                stagingRoot = root,
                connections = DownloadConnectionFactory { _, offset ->
                    seenOffsets += offset
                    responses.removeFirst()
                },
                availableBytes = { Long.MAX_VALUE / 2 },
                retryDelay = IMMEDIATE_RETRY,
                retryJitterMillis = { 0 },
            )

        val staged = downloader.download(archive(content), ResourceCancellationSignal()) { _, _, _ -> }

        assertEquals(listOf(0L, 4L), seenOffsets)
        assertTrue(staged.file.name.endsWith(".ready"))
        assertTrue(content.contentEquals(staged.file.readBytes()))
    }

    @Test
    fun httpsFactoryDisconnectsConnectionWhenResponseAcquisitionThrows() {
        val connection =
            FakeConnection(
                code = 0,
                input = ByteArrayInputStream(byteArrayOf()),
                contentLength = 0,
                responseFailure = IOException("TLS response failed"),
            )
        val factory = HttpsDownloadConnectionFactory { connection }

        assertThrows(IOException::class.java) {
            factory.open("https://example.invalid/resource", 0)
        }

        assertEquals(1, connection.disconnectCalls.get())
    }

    @Test
    fun httpsFactoryDisconnectsRejectedAndMalformedRedirects() {
        listOf("http://example.invalid/plain", "https://[invalid").forEach { location ->
            val connection =
                FakeConnection(
                    code = HttpURLConnection.HTTP_MOVED_TEMP,
                    input = ByteArrayInputStream(byteArrayOf()),
                    contentLength = 0,
                    location = location,
                )
            val factory = HttpsDownloadConnectionFactory { connection }

            val failure =
                assertThrows(ResourceDownloadException::class.java) {
                    factory.open("https://example.invalid/resource", 0)
                }

            assertEquals("download_redirect_invalid", failure.stableCode)
            assertEquals(1, connection.disconnectCalls.get())
        }
    }

    @Test
    fun rateLimitHonoursRetryAfterBeforeSuccessfulRetry() {
        val content = "rate-limited resource".toByteArray()
        val responses =
            ArrayDeque<HttpURLConnection>().apply {
                add(
                    FakeConnection(
                        code = 429,
                        input = ByteArrayInputStream(byteArrayOf()),
                        contentLength = 0,
                        retryAfter = "2",
                    ),
                )
                add(
                    FakeConnection(
                        code = HttpURLConnection.HTTP_OK,
                        input = ByteArrayInputStream(content),
                        contentLength = content.size.toLong(),
                    ),
                )
            }
        val delays = mutableListOf<Long>()
        val downloader =
            PinnedResourceDownloader(
                stagingRoot = temporary.newFolder("retry-after"),
                connections = DownloadConnectionFactory { _, _ -> responses.removeFirst() },
                availableBytes = { Long.MAX_VALUE / 2 },
                retryDelay = ResourceRetryDelay { millis, _ -> delays += millis },
                retryJitterMillis = { 0 },
            )

        val staged =
            downloader.download(archive(content), ResourceCancellationSignal()) { _, _, _ -> }

        assertEquals(listOf(2_000L), delays)
        assertTrue(content.contentEquals(staged.file.readBytes()))
    }

    @Test
    fun retryableServiceFailureUsesExponentialBackoffAndBoundedJitter() {
        val content = "unavailable resource".toByteArray()
        val attempts = AtomicInteger()
        val delays = mutableListOf<Long>()
        val downloader =
            PinnedResourceDownloader(
                stagingRoot = temporary.newFolder("retry-jitter"),
                connections =
                    DownloadConnectionFactory { _, _ ->
                        attempts.incrementAndGet()
                        FakeConnection(
                            code = HttpURLConnection.HTTP_UNAVAILABLE,
                            input = ByteArrayInputStream(byteArrayOf()),
                            contentLength = 0,
                        )
                    },
                availableBytes = { Long.MAX_VALUE / 2 },
                retryDelay = ResourceRetryDelay { millis, _ -> delays += millis },
                retryJitterMillis = { maximum -> maximum },
            )

        val failure =
            assertThrows(ResourceDownloadException::class.java) {
                downloader.download(archive(content), ResourceCancellationSignal()) { _, _, _ -> }
            }

        assertEquals("download_retry_exhausted", failure.stableCode)
        assertEquals(3, attempts.get())
        assertEquals(listOf(750L, 1_500L), delays)
    }

    @Test
    fun cancellationDuringRetryDelayStopsBeforeAnotherRequest() {
        val content = "cancel retry".toByteArray()
        val attempts = AtomicInteger()
        val cancellation = ResourceCancellationSignal()
        val downloader =
            PinnedResourceDownloader(
                stagingRoot = temporary.newFolder("retry-cancel"),
                connections =
                    DownloadConnectionFactory { _, _ ->
                        attempts.incrementAndGet()
                        FakeConnection(
                            code = 429,
                            input = ByteArrayInputStream(byteArrayOf()),
                            contentLength = 0,
                        )
                    },
                availableBytes = { Long.MAX_VALUE / 2 },
                retryDelay =
                    ResourceRetryDelay { _, signal ->
                        signal.cancel()
                        signal.check()
                    },
                retryJitterMillis = { 0 },
            )

        val failure =
            assertThrows(ResourceDownloadException::class.java) {
                downloader.download(archive(content), cancellation) { _, _, _ -> }
            }

        assertEquals("resource_operation_cancelled", failure.stableCode)
        assertEquals(1, attempts.get())
    }

    @Test
    fun localWriteFailureAfterSpaceRaceIsTypedAndNotRetried() {
        val content = "storage race".toByteArray()
        val attempts = AtomicInteger()
        val spaceChecks = AtomicInteger()
        val root = temporary.newFolder("write-storage")
        val downloader =
            PinnedResourceDownloader(
                stagingRoot = root,
                connections =
                    DownloadConnectionFactory { _, _ ->
                        attempts.incrementAndGet()
                        FakeConnection(
                            code = HttpURLConnection.HTTP_OK,
                            input = ByteArrayInputStream(content),
                            contentLength = content.size.toLong(),
                        )
                    },
                availableBytes = {
                    if (spaceChecks.getAndIncrement() == 0) Long.MAX_VALUE / 2 else 0
                },
                writeChunk = { output, bytes, count ->
                    output.write(bytes, 0, minOf(2, count))
                    throw IOException("local write failed")
                },
            )

        val failure =
            assertThrows(ResourceStorageException::class.java) {
                downloader.download(archive(content), ResourceCancellationSignal()) { _, _, _ -> }
            }

        assertEquals(1, attempts.get())
        assertEquals(0L, failure.availableBytes)
        assertEquals(2L, root.listFiles().single().length())
    }

    @Test
    fun localSyncEdquotIsTypedAndNotRetried() {
        val content = "quota resource".toByteArray()
        val attempts = AtomicInteger()
        val root = temporary.newFolder("sync-storage")
        val downloader =
            PinnedResourceDownloader(
                stagingRoot = root,
                connections =
                    DownloadConnectionFactory { _, _ ->
                        attempts.incrementAndGet()
                        FakeConnection(
                            code = HttpURLConnection.HTTP_OK,
                            input = ByteArrayInputStream(content),
                            contentLength = content.size.toLong(),
                        )
                    },
                availableBytes = { Long.MAX_VALUE / 2 },
                syncOutput = { throw IOException("EDQUOT: Disk quota exceeded") },
            )

        assertThrows(ResourceStorageException::class.java) {
            downloader.download(archive(content), ResourceCancellationSignal()) { _, _, _ -> }
        }

        assertEquals(1, attempts.get())
        assertEquals(content.size.toLong(), root.listFiles().single().length())
    }

    @Test
    fun nonStorageLocalSyncFailureIsNotRetriedOrReportedAsNetwork() {
        val content = "local failure".toByteArray()
        val attempts = AtomicInteger()
        val root = temporary.newFolder("sync-local-failure")
        val downloader =
            PinnedResourceDownloader(
                stagingRoot = root,
                connections =
                    DownloadConnectionFactory { _, _ ->
                        attempts.incrementAndGet()
                        FakeConnection(
                            code = HttpURLConnection.HTTP_OK,
                            input = ByteArrayInputStream(content),
                            contentLength = content.size.toLong(),
                        )
                    },
                availableBytes = { Long.MAX_VALUE / 2 },
                syncOutput = { throw IOException("private file sync failed") },
            )

        val failure =
            assertThrows(ResourceDownloadException::class.java) {
                downloader.download(archive(content), ResourceCancellationSignal()) { _, _, _ -> }
            }

        assertEquals("download_staging_failed", failure.stableCode)
        assertEquals(1, attempts.get())
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun exhaustedNetworkFailurePreservesPartialButCancellationDeletesIt() {
        val content = "resource".toByteArray()
        val archive = archive(content)
        val networkRoot = temporary.newFolder("network-failure")
        val failing =
            PinnedResourceDownloader(
                stagingRoot = networkRoot,
                connections = DownloadConnectionFactory { _, offset ->
                    val remaining = content.copyOfRange(offset.toInt(), content.size)
                    FakeConnection(
                        code =
                            if (offset == 0L) {
                                HttpURLConnection.HTTP_OK
                            } else {
                                HttpURLConnection.HTTP_PARTIAL
                            },
                        input = DisconnectingInput(remaining, minOf(2, remaining.size)),
                        contentLength = remaining.size.toLong(),
                        contentRange =
                            if (offset == 0L) {
                                null
                            } else {
                                "bytes $offset-${content.lastIndex}/${content.size}"
                            },
                    )
                },
                availableBytes = { Long.MAX_VALUE / 2 },
                retryDelay = IMMEDIATE_RETRY,
                retryJitterMillis = { 0 },
            )
        val failure =
            assertThrows(ResourceDownloadException::class.java) {
                failing.download(archive, ResourceCancellationSignal()) { _, _, _ -> }
            }
        assertEquals("download_retry_exhausted", failure.stableCode)
        val partials = networkRoot.listFiles().orEmpty().filter { it.name.endsWith(".part") }
        assertEquals(1, partials.size)
        assertTrue(partials.single().length() in 1L until content.size.toLong())

        val cancelledRoot = temporary.newFolder("cancelled")
        java.io.File(cancelledRoot, "${archive.sha256}.part")
            .writeBytes(content.copyOfRange(0, 3))
        val signal = ResourceCancellationSignal().also { it.cancel() }
        val cancelled =
            PinnedResourceDownloader(
                stagingRoot = cancelledRoot,
                connections = DownloadConnectionFactory { _, _ -> error("network must not open") },
                availableBytes = { Long.MAX_VALUE / 2 },
            )
        val cancellation =
            assertThrows(ResourceDownloadException::class.java) {
                cancelled.download(archive, signal) { _, _, _ -> }
            }
        assertEquals("resource_operation_cancelled", cancellation.stableCode)
        assertTrue(cancelledRoot.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun newDownloaderInstanceResumesAProcessSurvivingPartial() {
        val content = "immutable resource bytes".toByteArray()
        val archive = archive(content)
        val root = temporary.newFolder("process-restart")
        java.io.File(root, "${archive.sha256}.part").writeBytes(content.copyOfRange(0, 4))
        val seenOffsets = mutableListOf<Long>()
        val downloader =
            PinnedResourceDownloader(
                stagingRoot = root,
                connections = DownloadConnectionFactory { _, offset ->
                    seenOffsets += offset
                    FakeConnection(
                        code = HttpURLConnection.HTTP_PARTIAL,
                        input =
                            ByteArrayInputStream(
                                content.copyOfRange(offset.toInt(), content.size),
                            ),
                        contentLength = content.size.toLong() - offset,
                        contentRange = "bytes $offset-${content.lastIndex}/${content.size}",
                    )
                },
                availableBytes = { Long.MAX_VALUE / 2 },
            )

        val staged = downloader.download(archive, ResourceCancellationSignal()) { _, _, _ -> }

        assertEquals(listOf(4L), seenOffsets)
        assertTrue(content.contentEquals(staged.file.readBytes()))
    }

    @Test
    fun fullProcessSurvivingPartialIsSyncedBeforePublish() {
        val content = "complete partial".toByteArray()
        val archive = archive(content)
        val root = temporary.newFolder("complete-partial")
        java.io.File(root, "${archive.sha256}.part").writeBytes(content)
        val syncCalls = AtomicInteger()
        val downloader =
            PinnedResourceDownloader(
                stagingRoot = root,
                connections = DownloadConnectionFactory { _, _ -> error("network must not open") },
                availableBytes = { Long.MAX_VALUE / 2 },
                syncOutput = { syncCalls.incrementAndGet() },
            )

        val staged = downloader.download(archive, ResourceCancellationSignal()) { _, _, _ -> }

        assertEquals(1, syncCalls.get())
        assertTrue(staged.file.name.endsWith(".ready"))
    }

    @Test
    fun verifiedReadyArchiveIsReusedWithoutOpeningTheNetwork() {
        val content = "verified archive".toByteArray()
        val archive = archive(content)
        val root = temporary.newFolder("ready-reuse")
        java.io.File(root, "${archive.sha256}.ready").writeBytes(content)
        val downloader =
            PinnedResourceDownloader(
                stagingRoot = root,
                connections = DownloadConnectionFactory { _, _ -> error("network must not open") },
                availableBytes = { Long.MAX_VALUE / 2 },
            )

        val staged = downloader.download(archive, ResourceCancellationSignal()) { _, _, _ -> }

        assertTrue(staged.file.name.endsWith(".ready"))
        assertTrue(content.contentEquals(staged.file.readBytes()))
    }

    @Test
    fun reconciliationRetainsOnlyCatalogBoundPlausibleState() {
        val readyContent = "ready archive".toByteArray()
        val partialContent = "partial archive".toByteArray()
        val invalidContent = "invalid archive".toByteArray()
        val oversizedContent = "small".toByteArray()
        val readyArchive = archive(readyContent)
        val partialArchive = archive(partialContent)
        val invalidArchive = archive(invalidContent)
        val oversizedArchive = archive(oversizedContent)
        val root = temporary.newFolder("reconcile")
        java.io.File(root, "${readyArchive.sha256}.ready").writeBytes(readyContent)
        java.io.File(root, "${readyArchive.sha256}.part")
            .writeBytes(readyContent.copyOfRange(0, 2))
        java.io.File(root, "${partialArchive.sha256}.part")
            .writeBytes(partialContent.copyOfRange(0, 3))
        java.io.File(root, "${invalidArchive.sha256}.ready")
            .writeBytes(ByteArray(invalidContent.size))
        java.io.File(root, "${oversizedArchive.sha256}.part")
            .writeBytes(ByteArray(oversizedContent.size + 1))
        java.io.File(root, "unknown.tmp").writeText("private filename")
        java.io.File(root, "directory.part").mkdir()
        val downloader =
            PinnedResourceDownloader(
                stagingRoot = root,
                connections = DownloadConnectionFactory { _, _ -> error("network must not open") },
                availableBytes = { Long.MAX_VALUE / 2 },
            )

        downloader.reconcile(
            listOf(readyArchive, partialArchive, invalidArchive, oversizedArchive),
        )

        assertEquals(
            setOf("${readyArchive.sha256}.ready", "${partialArchive.sha256}.part"),
            root.listFiles().orEmpty().map { it.name }.toSet(),
        )
    }

    @Test
    fun discardRemovesOnlyTheSelectedCatalogArchiveState() {
        val selectedArchive = archive("selected".toByteArray())
        val retainedArchive = archive("retained".toByteArray())
        val root = temporary.newFolder("discard")
        java.io.File(root, "${selectedArchive.sha256}.ready").writeBytes("selected".toByteArray())
        java.io.File(root, "${selectedArchive.sha256}.part").writeBytes(byteArrayOf(1))
        java.io.File(root, "${retainedArchive.sha256}.part").writeBytes(byteArrayOf(2))
        val downloader =
            PinnedResourceDownloader(
                stagingRoot = root,
                connections = DownloadConnectionFactory { _, _ -> error("network must not open") },
                availableBytes = { Long.MAX_VALUE / 2 },
            )

        downloader.discard(selectedArchive)

        assertEquals(
            setOf("${retainedArchive.sha256}.part"),
            root.listFiles().orEmpty().map { it.name }.toSet(),
        )
    }

    @Test
    fun resumedResponseMustCoverTheExactRemainingCatalogRange() {
        val content = "immutable resource bytes".toByteArray()
        val responses =
            ArrayDeque<HttpURLConnection>().apply {
                add(
                    FakeConnection(
                        code = HttpURLConnection.HTTP_OK,
                        input = DisconnectingInput(content, 4),
                        contentLength = content.size.toLong(),
                    ),
                )
                add(
                    FakeConnection(
                        code = HttpURLConnection.HTTP_PARTIAL,
                        input = ByteArrayInputStream(content.copyOfRange(4, content.size)),
                        contentLength = (content.size - 4).toLong(),
                        contentRange = "bytes 4-4/${content.size}",
                    ),
                )
            }
        val root = temporary.newFolder("bad-range")
        val downloader =
            PinnedResourceDownloader(
                stagingRoot = root,
                connections = DownloadConnectionFactory { _, _ -> responses.removeFirst() },
                availableBytes = { Long.MAX_VALUE / 2 },
                retryDelay = IMMEDIATE_RETRY,
                retryJitterMillis = { 0 },
            )

        val failure =
            assertThrows(ResourceDownloadException::class.java) {
                downloader.download(archive(content), ResourceCancellationSignal()) { _, _, _ -> }
            }

        assertEquals("download_resume_invalid", failure.stableCode)
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    private fun archive(content: ByteArray) =
        ResourceArchive(
            url = "https://example.invalid/pinned.zip",
            sha256 =
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(content)
                    .joinToString("") { "%02x".format(it) },
            sizeBytes = content.size.toLong(),
            format = "zip",
        )

    private class FakeConnection(
        private val code: Int,
        private val input: InputStream,
        private val contentLength: Long,
        private val contentRange: String? = null,
        private val location: String? = null,
        private val retryAfter: String? = null,
        private val responseFailure: IOException? = null,
    ) : HttpURLConnection(URL("https://example.invalid/pinned.zip")) {
        val disconnectCalls = AtomicInteger()

        override fun connect() = Unit

        override fun disconnect() {
            disconnectCalls.incrementAndGet()
        }

        override fun usingProxy(): Boolean = false

        override fun getResponseCode(): Int {
            responseFailure?.let { throw it }
            return code
        }

        override fun getInputStream(): InputStream = input

        override fun getContentLengthLong(): Long = contentLength

        override fun getHeaderField(name: String?): String? =
            when {
                name.equals("Content-Range", ignoreCase = true) -> contentRange
                name.equals("Location", ignoreCase = true) -> location
                name.equals("Retry-After", ignoreCase = true) -> retryAfter
                else -> null
            }
    }

    private class DisconnectingInput(
        private val content: ByteArray,
        private val disconnectAfter: Int,
    ) : InputStream() {
        private var offset = 0

        override fun read(): Int {
            if (offset >= disconnectAfter) throw IOException("connection dropped")
            return content[offset++].toInt() and 0xff
        }

        override fun read(target: ByteArray, targetOffset: Int, length: Int): Int {
            if (offset >= disconnectAfter) throw IOException("connection dropped")
            val count = minOf(length, disconnectAfter - offset)
            content.copyInto(target, targetOffset, offset, offset + count)
            offset += count
            return count
        }
    }

    private companion object {
        val IMMEDIATE_RETRY =
            ResourceRetryDelay { _, cancellation ->
                cancellation.check()
            }
    }
}
