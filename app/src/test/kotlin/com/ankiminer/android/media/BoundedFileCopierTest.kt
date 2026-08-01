package com.ankiminer.android.media

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
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
import org.junit.Assert.assertSame
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
    fun cancellationClosesAStalledProviderReadExactlyOnceAndRemovesPartialFile() {
        val destination = temporary.newFile("cancel-stalled-read.stage")
        val cancellation = ProviderIoCancellationController()
        val source = StalledInputStream()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val copy =
                executor.submit<Long> {
                    BoundedFileCopier { Long.MAX_VALUE }.copy(
                        openSource = { source },
                        destination = destination,
                        knownSizeBytes = null,
                        policy = policy(maxBytes = 10L),
                        cancellation = cancellation,
                    )
                }
            assertTrue(source.readStarted.await(1, TimeUnit.SECONDS))

            cancellation.cancel()

            val failure =
                assertThrows(ExecutionException::class.java) {
                    copy.get(1, TimeUnit.SECONDS)
                }
            assertTrue(failure.cause is FileCopyCancelledException)
            assertEquals(1, source.closeCalls.get())
            assertFalse(destination.exists())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun resourceCloseFailureIsSuppressedUnderThePrimaryReadFailure() {
        val primary = IOException("provider read failed")
        val closeFailure = IOException("provider close failed")
        val source =
            object : InputStream() {
                override fun read(): Int = throw primary

                override fun close() {
                    throw closeFailure
                }
            }

        val failure =
            assertThrows(IOException::class.java) {
                CancellableProviderIo.useResource(
                    cancellation = ProviderIoCancellation.NONE,
                    open = { source },
                    block = { it.read() },
                )
            }

        assertSame(primary, failure)
        assertEquals(listOf(closeFailure), failure.suppressed.toList())
    }

    @Test
    fun throwingStreamCloseCannotAbortCancellationDelivery() {
        val destination = temporary.newFile("cancel-close-throws.stage")
        val cancellation = ThrowPropagatingCancellation()
        val source = StalledInputStream(closeFailure = IOException("provider close failed"))
        val executor = Executors.newSingleThreadExecutor()
        try {
            val copy =
                executor.submit<Long> {
                    BoundedFileCopier { Long.MAX_VALUE }.copy(
                        openSource = { source },
                        destination = destination,
                        knownSizeBytes = null,
                        policy = policy(maxBytes = 10L),
                        cancellation = cancellation,
                    )
                }
            assertTrue(source.readStarted.await(1, TimeUnit.SECONDS))

            cancellation.cancel()

            val failure =
                assertThrows(ExecutionException::class.java) {
                    copy.get(1, TimeUnit.SECONDS)
                }
            assertTrue(failure.cause is FileCopyCancelledException)
            assertFalse(destination.exists())
        } finally {
            executor.shutdownNow()
        }
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

    @Test
    fun progressAndStorageProbesCoalesceOverALargeCopy() {
        val expectedBytes = 256L * 1024 * 1024
        val bufferBytes = 256 * 1024
        val buffers = expectedBytes / bufferBytes
        val destination = temporary.newFile("large.stage")
        val progress = mutableListOf<BoundedFileCopyProgress>()
        val storageProbes = AtomicInteger()
        val copier =
            BoundedFileCopier {
                storageProbes.incrementAndGet()
                Long.MAX_VALUE
            }

        val copied =
            copier.copy(
                openSource = { ZeroInputStream(expectedBytes) },
                destination = destination,
                knownSizeBytes = expectedBytes,
                policy =
                    BoundedFileCopyPolicy(
                        maxBytes = expectedBytes,
                        freeSpaceReserveBytes = 0L,
                        bufferBytes = bufferBytes,
                        checkpointIntervalBytes = 1024L * 1024,
                    ),
                progressListener = FileCopyProgressListener(progress::add),
            )

        assertEquals(expectedBytes, copied)
        // One event per buffer is the defect: 1,024 publishes and 1,024 free-space probes.
        assertEquals(1024L, buffers)
        // One of each per megabyte, plus the leading zero and the storage preflight.
        assertEquals(257, progress.size)
        assertEquals(257, storageProbes.get())
        assertEquals(BoundedFileCopyProgress(0L, expectedBytes), progress.first())
        assertEquals(BoundedFileCopyProgress(expectedBytes, expectedBytes), progress.last())
        assertEquals(progress.sortedBy(BoundedFileCopyProgress::copiedBytes), progress)
    }

    @Test
    fun anIntervalWiderThanTheSourceStillReportsTheFirstAndFinalEvents() {
        val content = "abcdefghij".toByteArray()
        val destination = temporary.newFile("wide-interval.stage")
        val progress = mutableListOf<BoundedFileCopyProgress>()

        val copied =
            BoundedFileCopier { Long.MAX_VALUE }.copy(
                openSource = { ByteArrayInputStream(content) },
                destination = destination,
                knownSizeBytes = content.size.toLong(),
                policy =
                    BoundedFileCopyPolicy(
                        maxBytes = 64L,
                        freeSpaceReserveBytes = 0L,
                        bufferBytes = 2,
                        checkpointIntervalBytes = 1024L * 1024 * 1024,
                    ),
                progressListener = FileCopyProgressListener(progress::add),
            )

        assertEquals(10L, copied)
        assertEquals(
            listOf(
                BoundedFileCopyProgress(0L, 10L),
                BoundedFileCopyProgress(10L, 10L),
            ),
            progress,
        )
    }

    @Test
    fun aCoalescedProbeAuthorizesItsWholeCheckpointAboveTheReserve() {
        val destination = temporary.newFile("probe-interval.stage")
        val availability = ArrayDeque(listOf(Long.MAX_VALUE, 9L))
        val copier = BoundedFileCopier { availability.removeFirst() }

        val failure =
            assertThrows(FileCopyStorageException::class.java) {
                copier.copy(
                    openSource = { ByteArrayInputStream("abcd".toByteArray()) },
                    destination = destination,
                    knownSizeBytes = null,
                    policy =
                        BoundedFileCopyPolicy(
                            maxBytes = 16L,
                            freeSpaceReserveBytes = 4L,
                            bufferBytes = 2,
                            checkpointIntervalBytes = 6L,
                        ),
                )
            }

        // Six pre-authorized bytes plus the reserve, not the two bytes about to be written.
        assertEquals(10L, failure.requiredBytes)
        assertEquals(9L, failure.availableBytes)
        assertTrue(availability.isEmpty())
        assertFalse(destination.exists())
    }

    private fun policy(
        maxBytes: Long,
        reserveBytes: Long = 0L,
        bufferBytes: Int = 4,
    ) = BoundedFileCopyPolicy(
        maxBytes = maxBytes,
        freeSpaceReserveBytes = reserveBytes,
        bufferBytes = bufferBytes,
        checkpointIntervalBytes = 1L,
    )

    /** A few hundred megabytes of source without allocating a few hundred megabytes. */
    private class ZeroInputStream(
        private var remaining: Long,
    ) : InputStream() {
        override fun read(): Int = if (remaining-- > 0L) 0 else -1

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            if (remaining <= 0L) return -1
            val count = minOf(length.toLong(), remaining).toInt()
            buffer.fill(0, offset, offset + count)
            remaining -= count
            return count
        }
    }

    private class StalledInputStream(
        private val closeFailure: IOException? = null,
    ) : InputStream() {
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
            if (closeCalls.incrementAndGet() == 1) {
                closed.countDown()
                closeFailure?.let { throw it }
            }
        }
    }

    private class ThrowPropagatingCancellation : ProviderIoCancellation {
        private val cancelled = AtomicBoolean()
        private var listener: (() -> Unit)? = null

        override fun isCancelled(): Boolean = cancelled.get()

        override fun invokeOnCancellation(
            listener: () -> Unit,
        ): ProviderIoCancellationRegistration {
            this.listener = listener
            return ProviderIoCancellationRegistration { this.listener = null }
        }

        fun cancel() {
            cancelled.set(true)
            listener?.invoke()
        }
    }
}
