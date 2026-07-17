package com.ankiminer.android.data.resources

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.ArrayDeque
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
            )

        val staged = downloader.download(archive(content), ResourceCancellationSignal()) { _, _, _ -> }

        assertEquals(listOf(0L, 4L), seenOffsets)
        assertTrue(staged.file.name.endsWith(".ready"))
        assertTrue(content.contentEquals(staged.file.readBytes()))
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
    ) : HttpURLConnection(URL("https://example.invalid/pinned.zip")) {
        override fun connect() = Unit

        override fun disconnect() = Unit

        override fun usingProxy(): Boolean = false

        override fun getResponseCode(): Int = code

        override fun getInputStream(): InputStream = input

        override fun getContentLengthLong(): Long = contentLength

        override fun getHeaderField(name: String?): String? =
            if (name.equals("Content-Range", ignoreCase = true)) contentRange else null
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
}
