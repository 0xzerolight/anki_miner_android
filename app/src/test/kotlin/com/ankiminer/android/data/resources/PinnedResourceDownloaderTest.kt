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
    fun exhaustedNetworkFailureAndCancellationLeaveNoPartialOrPublishedArchive() {
        val content = "resource".toByteArray()
        val networkRoot = temporary.newFolder("network-failure")
        val failing =
            PinnedResourceDownloader(
                stagingRoot = networkRoot,
                connections =
                    DownloadConnectionFactory { _, _ ->
                        FakeConnection(
                            code = HttpURLConnection.HTTP_OK,
                            input = object : InputStream() {
                                override fun read(): Int = throw IOException("offline")
                            },
                            contentLength = content.size.toLong(),
                        )
                    },
                availableBytes = { Long.MAX_VALUE / 2 },
            )
        assertThrows(ResourceDownloadException::class.java) {
            failing.download(archive(content), ResourceCancellationSignal()) { _, _, _ -> }
        }
        assertTrue(networkRoot.listFiles().orEmpty().isEmpty())

        val cancelledRoot = temporary.newFolder("cancelled")
        val signal = ResourceCancellationSignal().also { it.cancel() }
        val cancelled =
            PinnedResourceDownloader(
                stagingRoot = cancelledRoot,
                connections = DownloadConnectionFactory { _, _ -> error("network must not open") },
                availableBytes = { Long.MAX_VALUE / 2 },
            )
        assertThrows(ResourceDownloadException::class.java) {
            cancelled.download(archive(content), signal) { _, _, _ -> }
        }
        assertTrue(cancelledRoot.listFiles().orEmpty().isEmpty())
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
            sha256 = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) },
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
