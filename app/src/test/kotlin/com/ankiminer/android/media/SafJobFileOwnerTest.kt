package com.ankiminer.android.media

import com.ankiminer.android.diagnostics.log.AppLog
import com.ankiminer.android.diagnostics.log.LogLevel
import com.ankiminer.android.diagnostics.log.NoOpSink
import com.ankiminer.android.diagnostics.log.RecordingLogSink
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SafJobFileOwnerTest {
    @Test
    fun videoDescriptorAlwaysCopiesToCacheAndCleansUpOnClose() {
        val directory = Files.createTempDirectory("saf-video-copy-test").toFile()
        try {
            val descriptor = FakeDescriptor(content = "mkv".toByteArray())
            val cache = File(directory, "copy.media")
            val owner = ownerWith(descriptor, cache)

            val input = owner.openVideoUri("content://test/video")

            assertEquals(cache.absolutePath, input.path)
            assertFalse(input.path.startsWith("/proc/self/fd/"))
            assertArrayEquals("mkv".toByteArray(), cache.readBytes())
            assertEquals(1, descriptor.copyCount)
            assertFalse(descriptor.closed)

            owner.close()

            assertTrue(descriptor.closed)
            assertFalse(cache.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun closingTwiceIsIdempotent() {
        val directory = Files.createTempDirectory("saf-owner-close-test").toFile()
        try {
            val descriptor = FakeDescriptor(content = byteArrayOf(1))
            val owner = ownerWith(descriptor, File(directory, "copy.media"))
            owner.openVideoUri("content://test/video")

            owner.close()

            assertTrue(descriptor.closed)
            owner.close()
            assertTrue(descriptor.closed)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun failedCopyClosesDescriptorAndDeletesPartialFile() {
        val directory = Files.createTempDirectory("saf-owner-failure").toFile()
        try {
            val descriptor =
                FakeDescriptor(
                    content = byteArrayOf(1),
                    copyFailure = IOException("provider failed"),
                )
            val cache = File(directory, "partial.media")
            val owner = ownerWith(descriptor, cache)

            val error =
                assertThrows(IOException::class.java) {
                    owner.openVideoUri("content://test/broken")
                }

            assertEquals("provider failed", error.message)
            assertTrue(descriptor.closed)
            assertFalse(cache.exists())
            owner.close()
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun openAfterCloseFailsBeforeDocumentsProviderIsTouched() {
        var opens = 0
        val owner =
            SafJobFileOwner(
                DescriptorOpener { _, _ ->
                    opens += 1
                    FakeDescriptor(byteArrayOf())
                },
                CacheFileFactory { error("cache not expected") },
            )
        owner.close()

        assertThrows(IllegalStateException::class.java) {
            owner.openVideoUri("content://test/late")
        }
        assertEquals(0, opens)
    }

    @Test
    fun closeAttemptsEveryDescriptorAndReportsAllFailures() {
        val directory = Files.createTempDirectory("saf-owner-close-failures").toFile()
        try {
            val first = FakeDescriptor(byteArrayOf(), closeFailure = IOException("first"))
            val second = FakeDescriptor(byteArrayOf(), closeFailure = IOException("second"))
            val descriptors = ArrayDeque(listOf(first, second))
            var caches = 0
            val owner =
                SafJobFileOwner(
                    DescriptorOpener { _, _ -> descriptors.removeFirst() },
                    CacheFileFactory { suffix ->
                        caches += 1
                        File(directory, "copy-$caches$suffix").apply { createNewFile() }
                    },
                )
            owner.openVideoUri("content://test/one")
            owner.openVideoUri("content://test/two")

            val error = assertThrows(IOException::class.java) { owner.close() }

            assertEquals("second", error.message)
            assertEquals(listOf("first"), error.suppressed.map { it.message })
            assertTrue(first.closed)
            assertTrue(second.closed)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun closeFailurePreservesAggregateSuppressedChainWithoutPrematureLog() {
        val recorded = RecordingLogSink()
        AppLog.setMinLevel(LogLevel.DEBUG)
        AppLog.install(NoOpSink)
        AppLog.install(recorded)
        val directory = Files.createTempDirectory("saf-owner-close-log").toFile()
        try {
            val first = FakeDescriptor(byteArrayOf(), closeFailure = IOException("first"))
            val second = FakeDescriptor(byteArrayOf(), closeFailure = IOException("second"))
            val descriptors = ArrayDeque(listOf(first, second))
            var caches = 0
            val owner =
                SafJobFileOwner(
                    DescriptorOpener { _, _ -> descriptors.removeFirst() },
                    CacheFileFactory { suffix ->
                        caches += 1
                        File(directory, "copy-$caches$suffix").apply { createNewFile() }
                    },
                )
            owner.openVideoUri("content://test/one")
            owner.openVideoUri("content://test/two")

            val failure = assertThrows(IOException::class.java) { owner.close() }

            assertEquals("second", failure.message)
            assertEquals(listOf("first"), failure.suppressed.map { it.message })
            assertTrue(recorded.records.isEmpty())
        } finally {
            AppLog.install(NoOpSink)
            directory.deleteRecursively()
        }
    }

    @Test
    fun seekableSubtitleIsStillCopiedWithNormalizedSupportedSuffix() {
        val directory = Files.createTempDirectory("saf-subtitle-test").toFile()
        try {
            val descriptor = FakeDescriptor(content = "subtitle".toByteArray())
            var requestedSuffix: String? = null
            val owner =
                SafJobFileOwner(
                    DescriptorOpener { _, _ -> descriptor },
                    CacheFileFactory { suffix ->
                        requestedSuffix = suffix
                        File(directory, "subtitle$suffix").apply { createNewFile() }
                    },
                )

            val input =
                owner.materializeSubtitleUri(
                    uri = "content://test/subtitle",
                    displayName = "Episode.Final.SRT",
                )

            assertEquals(".srt", requestedSuffix)
            assertTrue(input.path.endsWith(".srt"))
            assertEquals(1, descriptor.copyCount)
            assertFalse(descriptor.closed)

            owner.close()

            assertTrue(descriptor.closed)
            assertFalse(File(input.path).exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun unsupportedSubtitleSuffixFailsBeforeOpeningProviderOrCreatingCache() {
        var opens = 0
        var cacheCreates = 0
        val owner =
            SafJobFileOwner(
                DescriptorOpener { _, _ ->
                    opens += 1
                    FakeDescriptor(byteArrayOf())
                },
                CacheFileFactory {
                    cacheCreates += 1
                    error("cache not expected")
                },
            )

        assertThrows(IllegalArgumentException::class.java) {
            owner.materializeSubtitleUri("content://test/subtitle", "episode.txt")
        }

        assertEquals(0, opens)
        assertEquals(0, cacheCreates)
        owner.close()
    }

    @Test
    fun failedSubtitleCopyClosesDescriptorAndDeletesSuffixedPartialFile() {
        val directory = Files.createTempDirectory("saf-subtitle-failure").toFile()
        try {
            val descriptor =
                FakeDescriptor(
                    content = byteArrayOf(1),
                    copyFailure = IOException("subtitle provider failed"),
                )
            val cache = File(directory, "partial.ass")
            val owner = ownerWith(descriptor, cache)

            val error =
                assertThrows(IOException::class.java) {
                    owner.materializeSubtitleUri("content://test/broken", "episode.ass")
                }

            assertEquals("subtitle provider failed", error.message)
            assertTrue(descriptor.closed)
            assertFalse(cache.exists())
            owner.close()
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun oversizedSubtitleIsRejectedBeforeOpeningItsStreamAndCleansBothResources() {
        val directory = Files.createTempDirectory("saf-subtitle-limit").toFile()
        try {
            val descriptor =
                FakeDescriptor(
                    content = byteArrayOf(),
                    knownSizeBytes = 32L * 1024 * 1024 + 1,
                )
            val cache = File(directory, "oversized.srt")
            val owner = ownerWith(descriptor, cache)

            val failure =
                assertThrows(FileCopyLimitExceededException::class.java) {
                    owner.materializeSubtitleUri("content://test/oversized", "episode.srt")
                }

            assertEquals(32L * 1024 * 1024, failure.maxBytes)
            assertEquals(0, descriptor.copyCount)
            assertTrue(descriptor.closed)
            assertFalse(cache.exists())
            owner.close()
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun ownerCloseCancelsAStalledProviderReadWithoutDoubleClosingDescriptor() {
        val directory = Files.createTempDirectory("saf-owner-stalled-read").toFile()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val source = StalledInputStream()
            val descriptor = StalledDescriptor(source)
            val cache = File(directory, "partial.media")
            val owner =
                SafJobFileOwner(
                    DescriptorOpener { _, _ -> descriptor },
                    CacheFileFactory { cache.apply { createNewFile() } },
                )
            val opening =
                executor.submit<PythonMediaInput> {
                    owner.openVideoUri("content://test/stalled")
                }
            assertTrue(source.readStarted.await(1, TimeUnit.SECONDS))

            owner.close()

            val failure =
                assertThrows(ExecutionException::class.java) {
                    opening.get(1, TimeUnit.SECONDS)
                }
            assertTrue(failure.cause is FileCopyCancelledException)
            assertEquals(1, source.closeCalls.get())
            assertEquals(1, descriptor.closeCalls.get())
            assertFalse(cache.exists())
            owner.close()
            assertEquals(1, descriptor.closeCalls.get())
        } finally {
            executor.shutdownNow()
            directory.deleteRecursively()
        }
    }

    @Test
    fun ownerCloseReturnsWhenProviderReadIgnoresStreamAndDescriptorClose() {
        val directory = Files.createTempDirectory("saf-owner-uncooperative-read").toFile()
        val executor = Executors.newSingleThreadExecutor()
        val source = UncooperativeInputStream()
        try {
            val descriptor = StalledDescriptor(source)
            val cache = File(directory, "partial.media")
            val owner =
                SafJobFileOwner(
                    DescriptorOpener { _, _ -> descriptor },
                    CacheFileFactory { cache.apply { createNewFile() } },
                )
            val opening =
                executor.submit<PythonMediaInput> {
                    owner.openVideoUri("content://test/uncooperative")
                }
            assertTrue(source.readStarted.await(1, TimeUnit.SECONDS))

            owner.close()

            val failure =
                assertThrows(ExecutionException::class.java) {
                    opening.get(1, TimeUnit.SECONDS)
                }
            assertTrue(failure.cause is FileCopyCancelledException)
            assertEquals(1, source.closeCalls.get())
            assertEquals(1, descriptor.closeCalls.get())
            assertFalse(cache.exists())
        } finally {
            source.release.countDown()
            executor.shutdownNow()
            directory.deleteRecursively()
        }
    }

    @Test
    fun startupJanitorRemovesOnlyDirectOrphanEntries() {
        val directory = Files.createTempDirectory("saf-janitor-test").toFile()
        try {
            File(directory, "input-one.media").writeText("video")
            File(directory, "input-two.srt").writeText("subtitle")
            val unrelated = File(directory, "future-resource.bin").apply { writeText("keep") }
            val nested = File(directory, "input-nested.srt").apply { mkdir() }
            File(nested, "payload").writeText("keep")

            assertEquals(2, SafInputCacheJanitor(directory).removeOrphans())
            assertTrue(directory.isDirectory)
            assertTrue(unrelated.isFile)
            assertTrue(nested.isDirectory)
            assertEquals(0, SafInputCacheJanitor(directory).removeOrphans())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun startupJanitorHandlesAbsentRootAndRejectsNonDirectoryRoot() {
        val parent = Files.createTempDirectory("saf-janitor-root-test").toFile()
        try {
            assertEquals(0, SafInputCacheJanitor(File(parent, "absent")).removeOrphans())
            val regularFile = File(parent, "not-a-directory").apply { writeText("x") }

            assertThrows(IOException::class.java) {
                SafInputCacheJanitor(regularFile).removeOrphans()
            }
        } finally {
            parent.deleteRecursively()
        }
    }

    private fun ownerWith(
        descriptor: FakeDescriptor,
        cache: File = File("unused"),
    ): SafJobFileOwner =
        SafJobFileOwner(
            DescriptorOpener { _, _ -> descriptor },
            CacheFileFactory { cache.apply { createNewFile() } },
        )

    private class FakeDescriptor(
        private val content: ByteArray,
        private val copyFailure: IOException? = null,
        private val closeFailure: IOException? = null,
        override val knownSizeBytes: Long? = content.size.toLong(),
    ) : OwnedDescriptor {
        var closed = false
            private set
        var copyCount = 0
            private set

        override fun openInputStream(): InputStream {
            check(!closed)
            copyCount += 1
            return object : ByteArrayInputStream(content) {
                override fun read(
                    buffer: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int {
                    val count = super.read(buffer, offset, length)
                    if (count < 0) copyFailure?.let { throw it }
                    return count
                }
            }
        }

        override fun close() {
            closed = true
            closeFailure?.let { throw it }
        }
    }

    private class StalledDescriptor(
        private val source: InputStream,
    ) : OwnedDescriptor {
        override val knownSizeBytes: Long? = null
        val closeCalls = AtomicInteger()

        override fun openInputStream(): InputStream = source

        override fun close() {
            closeCalls.incrementAndGet()
        }
    }

    private class StalledInputStream : InputStream() {
        val readStarted = CountDownLatch(1)
        val closeCalls = AtomicInteger()
        private val closed = CountDownLatch(1)
        private val suppliedFirstChunk = AtomicBoolean()

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            if (suppliedFirstChunk.compareAndSet(false, true)) {
                buffer[offset] = 1
                return 1
            }
            readStarted.countDown()
            check(closed.await(5, TimeUnit.SECONDS)) { "test provider read was not cancelled" }
            throw IOException("provider descriptor closed")
        }

        override fun close() {
            if (closeCalls.incrementAndGet() == 1) closed.countDown()
        }
    }

    private class UncooperativeInputStream : InputStream() {
        val readStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closeCalls = AtomicInteger()

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            readStarted.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "test provider read was not released" }
            return -1
        }

        override fun close() {
            closeCalls.incrementAndGet()
        }
    }
}
