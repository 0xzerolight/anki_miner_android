package com.ankiminer.android.media

import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BoundedFileCopierTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun knownSizeIsPreflightedAndProgressReportsEveryBoundedWrite() {
        val content = "abcdef".toByteArray()
        val destination = temporary.newFile("known-size.stage")
        val progress = mutableListOf<BoundedFileCopyProgress>()
        val storageChecks = AtomicInteger()
        val copier =
            BoundedFileCopier {
                storageChecks.incrementAndGet()
                100L
            }

        val copied =
            copier.copy(
                openSource = { ByteArrayInputStream(content) },
                destination = destination,
                knownSizeBytes = content.size.toLong(),
                policy = policy(maxBytes = 10L, reserveBytes = 4L, bufferBytes = 2),
                progressListener = FileCopyProgressListener(progress::add),
            )

        assertEquals(6L, copied)
        assertArrayEquals(content, destination.readBytes())
        assertEquals(
            listOf(
                BoundedFileCopyProgress(0L, 6L),
                BoundedFileCopyProgress(2L, 6L),
                BoundedFileCopyProgress(4L, 6L),
                BoundedFileCopyProgress(6L, 6L),
            ),
            progress,
        )
        assertEquals(4, storageChecks.get())
    }

    @Test
    fun knownOversizeFailsBeforeStorageProbeOrSourceOpenAndRemovesDestination() {
        val destination = temporary.newFile("oversize.stage")
        val sourceOpens = AtomicInteger()
        val storageChecks = AtomicInteger()
        val copier =
            BoundedFileCopier {
                storageChecks.incrementAndGet()
                Long.MAX_VALUE
            }

        val failure =
            assertThrows(FileCopyLimitExceededException::class.java) {
                copier.copy(
                    openSource = {
                        sourceOpens.incrementAndGet()
                        ByteArrayInputStream(byteArrayOf())
                    },
                    destination = destination,
                    knownSizeBytes = 11L,
                    policy = policy(maxBytes = 10L),
                )
            }

        assertEquals(10L, failure.maxBytes)
        assertEquals(11L, failure.observedBytes)
        assertEquals(0, sourceOpens.get())
        assertEquals(0, storageChecks.get())
        assertFalse(destination.exists())
    }

    @Test
    fun knownSizeStoragePreflightIncludesReserveAndDoesNotOpenSource() {
        val destination = temporary.newFile("storage-preflight.stage")
        val sourceOpens = AtomicInteger()
        val copier = BoundedFileCopier { 9L }

        val failure =
            assertThrows(FileCopyStorageException::class.java) {
                copier.copy(
                    openSource = {
                        sourceOpens.incrementAndGet()
                        ByteArrayInputStream("source".toByteArray())
                    },
                    destination = destination,
                    knownSizeBytes = 6L,
                    policy = policy(maxBytes = 10L, reserveBytes = 4L),
                )
            }

        assertEquals(10L, failure.requiredBytes)
        assertEquals(9L, failure.availableBytes)
        assertEquals(0, sourceOpens.get())
        assertFalse(destination.exists())
    }

    @Test
    fun unknownSizeIsBoundedWhileStreamingAndPartialDestinationIsRemoved() {
        val destination = temporary.newFile("stream-limit.stage")
        val progress = mutableListOf<BoundedFileCopyProgress>()
        val copier = BoundedFileCopier { Long.MAX_VALUE }

        val failure =
            assertThrows(FileCopyLimitExceededException::class.java) {
                copier.copy(
                    openSource = { ByteArrayInputStream("abcde".toByteArray()) },
                    destination = destination,
                    knownSizeBytes = null,
                    policy = policy(maxBytes = 4L, bufferBytes = 2),
                    progressListener = FileCopyProgressListener(progress::add),
                )
            }

        assertEquals(4L, failure.maxBytes)
        assertEquals(5L, failure.observedBytes)
        assertEquals(
            listOf(
                BoundedFileCopyProgress(0L, null),
                BoundedFileCopyProgress(2L, null),
                BoundedFileCopyProgress(4L, null),
            ),
            progress,
        )
        assertFalse(destination.exists())
    }

    @Test
    fun storageIsRecheckedBeforeEveryWriteAndReserveIsPreserved() {
        val destination = temporary.newFile("stream-storage.stage")
        val availability = ArrayDeque(listOf(100L, 100L, 5L))
        val copier = BoundedFileCopier { availability.removeFirst() }

        val failure =
            assertThrows(FileCopyStorageException::class.java) {
                copier.copy(
                    openSource = { ByteArrayInputStream("abcd".toByteArray()) },
                    destination = destination,
                    knownSizeBytes = null,
                    policy = policy(maxBytes = 10L, reserveBytes = 4L, bufferBytes = 2),
                )
            }

        assertEquals(6L, failure.requiredBytes)
        assertEquals(5L, failure.availableBytes)
        assertTrue(availability.isEmpty())
        assertFalse(destination.exists())
    }

    @Test
    fun cancellationBeforeOpenAndBetweenChunksNeverLeavesAStagingFile() {
        val beforeOpen = temporary.newFile("cancel-before-open.stage")
        val sourceOpens = AtomicInteger()
        assertThrows(FileCopyCancelledException::class.java) {
            BoundedFileCopier { error("storage must not be inspected") }.copy(
                openSource = {
                    sourceOpens.incrementAndGet()
                    ByteArrayInputStream(byteArrayOf(1))
                },
                destination = beforeOpen,
                knownSizeBytes = 1L,
                policy = policy(maxBytes = 10L),
                cancellation = FileCopyCancellation { true },
            )
        }
        assertEquals(0, sourceOpens.get())
        assertFalse(beforeOpen.exists())

        val betweenChunks = temporary.newFile("cancel-between-chunks.stage")
        val cancelled = AtomicBoolean(false)
        val progress = mutableListOf<BoundedFileCopyProgress>()
        assertThrows(FileCopyCancelledException::class.java) {
            BoundedFileCopier { Long.MAX_VALUE }.copy(
                openSource = { ByteArrayInputStream("abcd".toByteArray()) },
                destination = betweenChunks,
                knownSizeBytes = null,
                policy = policy(maxBytes = 10L, bufferBytes = 2),
                cancellation = FileCopyCancellation(cancelled::get),
                progressListener =
                    FileCopyProgressListener {
                        progress += it
                        if (it.copiedBytes == 2L) cancelled.set(true)
                    },
            )
        }
        assertEquals(
            listOf(
                BoundedFileCopyProgress(0L, null),
                BoundedFileCopyProgress(2L, null),
            ),
            progress,
        )
        assertFalse(betweenChunks.exists())
    }

    @Test
    fun providerSizeChangesInEitherDirectionAreTypedAndCleanedUp() {
        val shorter = temporary.newFile("shorter.stage")
        val shortFailure =
            assertThrows(FileCopySizeMismatchException::class.java) {
                BoundedFileCopier { Long.MAX_VALUE }.copy(
                    openSource = { ByteArrayInputStream("four".toByteArray()) },
                    destination = shorter,
                    knownSizeBytes = 5L,
                    policy = policy(maxBytes = 10L, bufferBytes = 2),
                )
            }
        assertEquals(5L, shortFailure.expectedBytes)
        assertEquals(4L, shortFailure.actualBytes)
        assertFalse(shorter.exists())

        val longer = temporary.newFile("longer.stage")
        val longFailure =
            assertThrows(FileCopySizeMismatchException::class.java) {
                BoundedFileCopier { Long.MAX_VALUE }.copy(
                    openSource = { ByteArrayInputStream("sixsix".toByteArray()) },
                    destination = longer,
                    knownSizeBytes = 5L,
                    policy = policy(maxBytes = 10L, bufferBytes = 3),
                )
            }
        assertEquals(5L, longFailure.expectedBytes)
        assertEquals(6L, longFailure.actualBytes)
        assertFalse(longer.exists())
    }

    @Test
    fun sourceAndProgressFailuresAlsoRemovePartialDestinations() {
        val sourceFailureDestination = temporary.newFile("source-failure.stage")
        val sourceFailure = IOException("provider disconnected")
        val source =
            object : ByteArrayInputStream("partial".toByteArray()) {
                override fun read(
                    buffer: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int {
                    val count = super.read(buffer, offset, length)
                    if (count < 0) throw sourceFailure
                    return count
                }
            }
        assertEquals(
            sourceFailure,
            assertThrows(IOException::class.java) {
                BoundedFileCopier { Long.MAX_VALUE }.copy(
                    openSource = { source },
                    destination = sourceFailureDestination,
                    knownSizeBytes = null,
                    policy = policy(maxBytes = 20L),
                )
            },
        )
        assertFalse(sourceFailureDestination.exists())

        val progressFailureDestination = temporary.newFile("progress-failure.stage")
        val progressFailure = IllegalStateException("listener stopped")
        assertEquals(
            progressFailure,
            assertThrows(IllegalStateException::class.java) {
                BoundedFileCopier { Long.MAX_VALUE }.copy(
                    openSource = { ByteArrayInputStream(byteArrayOf(1)) },
                    destination = progressFailureDestination,
                    knownSizeBytes = 1L,
                    policy = policy(maxBytes = 10L),
                    progressListener = FileCopyProgressListener { throw progressFailure },
                )
            },
        )
        assertFalse(progressFailureDestination.exists())
    }

    private fun policy(
        maxBytes: Long,
        reserveBytes: Long = 0L,
        bufferBytes: Int = 4,
    ) = BoundedFileCopyPolicy(maxBytes, reserveBytes, bufferBytes)
}
