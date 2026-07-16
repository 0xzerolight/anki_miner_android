package com.ankiminer.android.media

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.ArrayDeque
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SafJobFileOwnerTest {
    @Test
    fun seekableDescriptorStaysOwnedUntilWholeJobCloses() {
        val descriptor = FakeDescriptor(rawFd = 41, seekable = true, content = byteArrayOf(1))
        val owner = ownerWith(descriptor)

        val input = owner.openVideoUri("content://test/seekable")

        assertEquals("/proc/self/fd/41", input.path)
        assertEquals(PythonMediaInput.Backing.SEEKABLE_DESCRIPTOR, input.backing)
        assertFalse(descriptor.closed)
        assertEquals(0, descriptor.copyCount)

        owner.close()

        assertTrue(descriptor.closed)
        owner.close()
        assertTrue(descriptor.closed)
    }

    @Test
    fun nonSeekableDescriptorCopiesOnceAndBothResourcesLiveForJob() {
        val directory = Files.createTempDirectory("saf-owner-test").toFile()
        try {
            val descriptor =
                FakeDescriptor(rawFd = 42, seekable = false, content = "mkv".toByteArray())
            val cache = File(directory, "copy.media")
            val owner = ownerWith(descriptor, cache)

            val input = owner.openVideoUri("content://test/pipe")

            assertEquals(cache.absolutePath, input.path)
            assertEquals(PythonMediaInput.Backing.CACHE_COPY, input.backing)
            assertArrayEquals("mkv".toByteArray(), cache.readBytes())
            assertFalse(descriptor.closed)
            assertTrue(cache.exists())

            owner.close()

            assertTrue(descriptor.closed)
            assertFalse(cache.exists())
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
                    rawFd = 43,
                    seekable = false,
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
                DescriptorOpener {
                    opens += 1
                    FakeDescriptor(44, true, byteArrayOf())
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
        val first = FakeDescriptor(45, true, byteArrayOf(), closeFailure = IOException("first"))
        val second = FakeDescriptor(46, true, byteArrayOf(), closeFailure = IOException("second"))
        val descriptors = ArrayDeque(listOf(first, second))
        val owner =
            SafJobFileOwner(
                DescriptorOpener { descriptors.removeFirst() },
                CacheFileFactory { error("cache not expected") },
            )
        owner.openVideoUri("content://test/one")
        owner.openVideoUri("content://test/two")

        val error = assertThrows(IOException::class.java) { owner.close() }

        assertEquals("second", error.message)
        assertEquals(listOf("first"), error.suppressed.map { it.message })
        assertTrue(first.closed)
        assertTrue(second.closed)
    }

    @Test
    fun seekableSubtitleIsStillCopiedWithNormalizedSupportedSuffix() {
        val directory = Files.createTempDirectory("saf-subtitle-test").toFile()
        try {
            val descriptor =
                FakeDescriptor(rawFd = 47, seekable = true, content = "subtitle".toByteArray())
            var requestedSuffix: String? = null
            val owner =
                SafJobFileOwner(
                    DescriptorOpener { descriptor },
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
            assertEquals(PythonMediaInput.Backing.CACHE_COPY, input.backing)
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
                DescriptorOpener {
                    opens += 1
                    FakeDescriptor(48, true, byteArrayOf())
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
                    rawFd = 49,
                    seekable = true,
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
            DescriptorOpener { descriptor },
            CacheFileFactory { cache.apply { createNewFile() } },
        )

    private class FakeDescriptor(
        override val rawFd: Int,
        private val seekable: Boolean,
        private val content: ByteArray,
        private val copyFailure: IOException? = null,
        private val closeFailure: IOException? = null,
    ) : OwnedDescriptor {
        var closed = false
            private set
        var copyCount = 0
            private set

        override fun isSeekable(): Boolean {
            check(!closed)
            return seekable
        }

        override fun copyTo(target: File) {
            check(!closed)
            copyCount += 1
            target.writeBytes(content)
            copyFailure?.let { throw it }
        }

        override fun close() {
            closed = true
            closeFailure?.let { throw it }
        }
    }
}
