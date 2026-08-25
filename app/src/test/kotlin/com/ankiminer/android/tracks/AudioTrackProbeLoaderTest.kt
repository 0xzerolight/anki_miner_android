package com.ankiminer.android.tracks

import com.ankiminer.android.data.RuntimeWorkCoordinator
import com.ankiminer.android.media.CacheFileFactory
import com.ankiminer.android.media.DescriptorOpener
import com.ankiminer.android.media.FileCopyCancellation
import com.ankiminer.android.media.FileCopyCancelledException
import com.ankiminer.android.media.OwnedDescriptor
import com.ankiminer.android.media.SafDocument
import com.ankiminer.android.media.SafJobFileOwner
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class AudioTrackProbeLoaderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun successOrdersLeaseStageAndLookupAndClosesEverythingBeforeReturning() =
        runTest {
            withHarness { harness ->
                val result = harness.loader().probe(VIDEO)

                assertTrue(result.isSuccess)
                assertEquals(AUDIO_TRACK_LIST, result.getOrThrow())
                assertEquals(listOf("stage", "lookup"), harness.events)
                assertTrue(harness.leaseWasHeldDuringStage)
                assertEquals(null, harness.coordinator.activeKind.value)
                assertEquals(1, harness.descriptorCloseCount.get())
                assertEquals("tracks-io", harness.descriptorCloseThread.get())
                assertFalse(harness.stagedFile.get().exists())
                assertEquals(harness.stagedFile.get().absolutePath, harness.lookupPath.get())
            }
        }

    @Test
    fun refusedLeaseDoesNotCreateOrStageAnOwner() =
        runTest {
            withHarness { harness ->
                val competing =
                    requireNotNull(
                        harness.coordinator.tryAcquire(RuntimeWorkCoordinator.Kind.RESOURCE),
                    )

                val result = harness.loader().probe(VIDEO)

                assertTrue(result.exceptionOrNull() is AudioTrackProbeBusyException)
                assertEquals(0, harness.ownerCreateCount)
                assertTrue(harness.events.isEmpty())
                competing.close()
            }
        }

    @Test
    fun stagingFailureClosesOwnerAndReleasesLease() =
        runTest {
            withHarness(stageFailure = IOException("stage failed")) { harness ->
                val result = harness.loader().probe(VIDEO)

                assertTrue(result.isFailure)
                assertEquals(null, harness.coordinator.activeKind.value)
                assertOwnerClosed(requireNotNull(harness.owner.get()))
            }
        }

    @Test
    fun lookupFailureClosesOwnerAndPropagatesTheFailure() =
        runTest {
            val failure = IllegalStateException("no tracks")
            withHarness(lookupResult = Result.failure(failure)) { harness ->
                val result = harness.loader().probe(VIDEO)

                assertTrue(result.isFailure)
                assertEquals(failure, result.exceptionOrNull())
                assertEquals(null, harness.coordinator.activeKind.value)
                assertEquals(1, harness.descriptorCloseCount.get())
                assertFalse(harness.stagedFile.get().exists())
            }
        }

    @Test
    fun cancellationDuringStagingFiresCopyCancellationAndClosesEverything() =
        runTest {
            val stageStarted = CountDownLatch(1)
            val copyCancelled = CountDownLatch(1)
            withHarness(
                blockingStage = { cancellation ->
                    cancellation.invokeOnCancellation { copyCancelled.countDown() }
                    stageStarted.countDown()
                    check(copyCancelled.await(5, TimeUnit.SECONDS))
                    throw FileCopyCancelledException()
                },
            ) { harness ->
                val opening =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        harness.loader().probe(VIDEO)
                    }
                assertTrue(stageStarted.await(5, TimeUnit.SECONDS))

                opening.cancelAndJoin()

                assertEquals(0L, copyCancelled.count)
                assertEquals(null, harness.coordinator.activeKind.value)
                assertOwnerClosed(requireNotNull(harness.owner.get()))
            }
        }

    @Test
    fun successBecomesAFailureWhenCloseFailsButLeaseIsStillReleased() =
        runTest {
            val closeFailure = IOException("close failed")
            withHarness(closeFailure = closeFailure) { harness ->
                val result = harness.loader().probe(VIDEO)

                assertTrue(result.isFailure)
                assertEquals(closeFailure, result.exceptionOrNull())
                assertEquals(null, harness.coordinator.activeKind.value)
            }
        }

    @Test
    fun cancellationSurvivesACleanupFailureAsASuppressedException() =
        runTest {
            val closeFailure = IOException("close failed")
            val cancellationFailure = CancellationException("lookup cancelled")
            withHarness(closeFailure = closeFailure, lookupThrow = cancellationFailure) { harness ->
                var caught: CancellationException? = null
                try {
                    harness.loader().probe(VIDEO)
                    fail("expected a CancellationException")
                } catch (failure: CancellationException) {
                    caught = failure
                }

                assertEquals(cancellationFailure, caught)
                assertTrue(caught!!.suppressed.contains(closeFailure))
                assertEquals(null, harness.coordinator.activeKind.value)
            }
        }

    private suspend fun withHarness(
        stageFailure: Throwable? = null,
        blockingStage: ((FileCopyCancellation) -> OwnedDescriptor)? = null,
        lookupResult: Result<AudioTrackList> = Result.success(AUDIO_TRACK_LIST),
        lookupThrow: Throwable? = null,
        closeFailure: Throwable? = null,
        block: suspend (Harness) -> Unit,
    ) {
        val ioExecutor =
            Executors.newSingleThreadExecutor { task -> Thread(task, "tracks-io") }
        val io = ioExecutor.asCoroutineDispatcher()
        try {
            block(
                Harness(
                    root = temporaryFolder.newFolder(),
                    io = io,
                    stageFailure = stageFailure,
                    blockingStage = blockingStage,
                    lookupResult = lookupResult,
                    lookupThrow = lookupThrow,
                    closeFailure = closeFailure,
                ),
            )
        } finally {
            io.close()
        }
    }

    private class Harness(
        root: File,
        private val io: kotlinx.coroutines.CoroutineDispatcher,
        private val stageFailure: Throwable?,
        private val blockingStage: ((FileCopyCancellation) -> OwnedDescriptor)?,
        private val lookupResult: Result<AudioTrackList>,
        private val lookupThrow: Throwable?,
        private val closeFailure: Throwable?,
    ) {
        val coordinator = RuntimeWorkCoordinator()
        val events = mutableListOf<String>()
        val descriptorCloseCount = AtomicInteger()
        val descriptorCloseThread = AtomicReference<String>()
        val stagedFile = AtomicReference<File>()
        val owner = AtomicReference<SafJobFileOwner>()
        val lookupPath = AtomicReference<String>()
        var ownerCreateCount = 0
        var leaseWasHeldDuringStage = false
        private val cacheRoot = File(root, "cache").apply { mkdirs() }
        private val lookup =
            AudioTrackLookupService { path ->
                events += "lookup"
                lookupPath.set(path)
                lookupThrow?.let { throw it }
                lookupResult
            }

        fun loader(): AudioTrackProbeLoader =
            AudioTrackProbeLoader(
                coordinator = coordinator,
                ownerFactory = { cancellation -> createOwner(cancellation) },
                lookup = lookup,
                io = io,
            )

        private fun createOwner(cancellation: FileCopyCancellation): SafJobFileOwner {
            ownerCreateCount += 1
            val created =
                SafJobFileOwner(
                    descriptorOpener =
                        DescriptorOpener { _, _ ->
                            events += "stage"
                            leaseWasHeldDuringStage =
                                coordinator.activeKind.value == RuntimeWorkCoordinator.Kind.MINING
                            stageFailure?.let { throw it }
                            blockingStage?.invoke(cancellation)
                                ?: RecordingDescriptor(
                                    closeCount = descriptorCloseCount,
                                    closeThread = descriptorCloseThread,
                                    closeFailure = closeFailure,
                                )
                        },
                    cacheFileFactory =
                        CacheFileFactory { suffix ->
                            File.createTempFile("tracks-", suffix, cacheRoot).also(stagedFile::set)
                        },
                    cancellation = cancellation,
                )
            owner.set(created)
            return created
        }
    }

    private class RecordingDescriptor(
        private val closeCount: AtomicInteger,
        private val closeThread: AtomicReference<String>,
        private val closeFailure: Throwable? = null,
    ) : OwnedDescriptor {
        override val knownSizeBytes: Long = VIDEO_BYTES.size.toLong()

        override fun openInputStream(): InputStream = ByteArrayInputStream(VIDEO_BYTES)

        override fun close() {
            closeThread.set(Thread.currentThread().name)
            closeCount.incrementAndGet()
            closeFailure?.let { throw it }
        }
    }

    private fun assertOwnerClosed(owner: SafJobFileOwner) {
        assertThrows(IllegalStateException::class.java) {
            owner.openVideoUri(VIDEO.uri)
        }
    }

    private companion object {
        val VIDEO =
            SafDocument(
                uri = "content://test/video.mp4",
                displayName = "video.mp4",
                mimeType = "video/mp4",
                sizeBytes = null,
            )
        val VIDEO_BYTES = ByteArray(16)
        val AUDIO_TRACK_LIST = AudioTrackList(autoAudioIndex = 0, tracks = emptyList())
    }
}
