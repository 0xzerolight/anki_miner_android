package com.ankiminer.android.timing

import com.ankiminer.android.data.RuntimeWorkCoordinator
import com.ankiminer.android.engine.BridgeJsonCodec
import com.ankiminer.android.engine.BridgeMessage
import com.ankiminer.android.engine.PyBridge
import com.ankiminer.android.engine.TokenizerIdentity
import com.ankiminer.android.media.CacheFileFactory
import com.ankiminer.android.media.DescriptorOpener
import com.ankiminer.android.media.FileCopyCancellation
import com.ankiminer.android.media.FileCopyCancelledException
import com.ankiminer.android.media.OwnedDescriptor
import com.ankiminer.android.media.SafDocument
import com.ankiminer.android.media.SafJobFileOwner
import com.ankiminer.android.mining.InstalledTokenizerResource
import com.ankiminer.android.mining.InstalledTokenizerResourceProvider
import com.ankiminer.android.mining.TokenizerConfigurationFailure
import com.ankiminer.android.mining.TokenizerConfigurator
import com.ankiminer.android.subtitles.BridgeSubtitleCueLookupService
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class TimingPreviewLoaderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun successOrdersLeaseStageConfigureAndCuesAndHoldsResourcesUntilClose() = runTest {
        withHarness { harness ->
            val result = harness.loader().open(SUBTITLE)

            assertTrue(result.isSuccess)
            assertEquals(listOf("stage", "configure", "cues"), harness.events)
            assertEquals(RuntimeWorkCoordinator.Kind.MINING, harness.coordinator.activeKind.value)
            assertTrue(harness.leaseWasHeldDuringStage)

            result.getOrThrow().close()

            assertEquals(null, harness.coordinator.activeKind.value)
            assertEquals(1, harness.descriptorCloseCount.get())
            assertFalse(harness.stagedFile.get().exists())
        }
    }

    @Test
    fun refusedLeaseDoesNotCreateOrStageAnOwner() = runTest {
        withHarness { harness ->
            val competing =
                requireNotNull(
                    harness.coordinator.tryAcquire(RuntimeWorkCoordinator.Kind.RESOURCE),
                )

            val result = harness.loader().open(SUBTITLE)

            assertTrue(result.exceptionOrNull() is TimingPreviewBusyException)
            assertEquals(0, harness.ownerCreateCount)
            assertTrue(harness.events.isEmpty())
            competing.close()
        }
    }

    @Test
    fun stagingFailureClosesOwnerAndReleasesLease() = runTest {
        withHarness(stageFailure = IOException("stage failed")) { harness ->
            val result = harness.loader().open(SUBTITLE)

            assertTrue(result.isFailure)
            assertEquals(null, harness.coordinator.activeKind.value)
            assertOwnerClosed(requireNotNull(harness.owner.get()))
        }
    }

    @Test
    fun cancellationDuringStagingFiresCopyCancellationAndClosesEverything() = runTest {
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
                    harness.loader().open(SUBTITLE)
                }
            assertTrue(stageStarted.await(5, TimeUnit.SECONDS))

            opening.cancelAndJoin()

            assertEquals(0L, copyCancelled.count)
            assertEquals(null, harness.coordinator.activeKind.value)
            assertOwnerClosed(requireNotNull(harness.owner.get()))
        }
    }

    @Test
    fun cueFailureClosesOwnerAndReleasesLease() = runTest {
        withHarness(cueFailure = true) { harness ->
            val result = harness.loader().open(SUBTITLE)

            assertTrue(result.isFailure)
            assertEquals(null, harness.coordinator.activeKind.value)
            assertEquals(1, harness.descriptorCloseCount.get())
            assertFalse(harness.stagedFile.get().exists())
        }
    }

    @Test
    fun missingTokenizerClosesOwnerAndReturnsTheRequiredFailure() = runTest {
        withHarness(tokenizerInstalled = false) { harness ->
            val result = harness.loader().open(SUBTITLE)

            assertTrue(result.exceptionOrNull() is TokenizerConfigurationFailure.Required)
            assertEquals(null, harness.coordinator.activeKind.value)
            assertEquals(1, harness.descriptorCloseCount.get())
        }
    }

    @Test
    fun sessionCloseIsIdempotentAndRunsTeardownOffTheCallerThread() = runTest {
        withHarness { harness ->
            val callerThread = Thread.currentThread().name
            val session = harness.loader().open(SUBTITLE).getOrThrow()

            session.close()
            session.close()

            assertEquals(1, harness.descriptorCloseCount.get())
            assertEquals("timing-io", harness.descriptorCloseThread.get())
            assertFalse(callerThread == harness.descriptorCloseThread.get())
        }
    }

    @Test
    fun tokenizerAndCueBridgeDispatchBothRunOnTheInjectedResourceWorker() = runTest {
        withHarness { harness ->
            val session = harness.loader().open(SUBTITLE).getOrThrow()

            assertEquals(listOf("timing-resource", "timing-resource"), harness.bridgeThreads)

            session.close()
        }
    }

    private suspend fun withHarness(
        stageFailure: Throwable? = null,
        blockingStage: ((FileCopyCancellation) -> OwnedDescriptor)? = null,
        cueFailure: Boolean = false,
        tokenizerInstalled: Boolean = true,
        block: suspend (Harness) -> Unit,
    ) {
        val ioExecutor =
            Executors.newSingleThreadExecutor { task -> Thread(task, "timing-io") }
        val resourceExecutor =
            Executors.newSingleThreadExecutor { task -> Thread(task, "timing-resource") }
        val io = ioExecutor.asCoroutineDispatcher()
        val resource = resourceExecutor.asCoroutineDispatcher()
        try {
            block(
                Harness(
                    root = temporaryFolder.newFolder(),
                    io = io,
                    resource = resource,
                    resourceExecutor = resourceExecutor,
                    stageFailure = stageFailure,
                    blockingStage = blockingStage,
                    cueFailure = cueFailure,
                    tokenizerInstalled = tokenizerInstalled,
                ),
            )
        } finally {
            io.close()
            resource.close()
        }
    }

    private class Harness(
        root: File,
        private val io: kotlinx.coroutines.CoroutineDispatcher,
        private val resource: kotlinx.coroutines.CoroutineDispatcher,
        resourceExecutor: java.util.concurrent.Executor,
        private val stageFailure: Throwable?,
        private val blockingStage: ((FileCopyCancellation) -> OwnedDescriptor)?,
        private val cueFailure: Boolean,
        private val tokenizerInstalled: Boolean,
    ) {
        val coordinator = RuntimeWorkCoordinator()
        val events = mutableListOf<String>()
        val bridgeThreads = mutableListOf<String>()
        val descriptorCloseCount = AtomicInteger()
        val descriptorCloseThread = AtomicReference<String>()
        val stagedFile = AtomicReference<File>()
        val owner = AtomicReference<SafJobFileOwner>()
        var ownerCreateCount = 0
        var leaseWasHeldDuringStage = false
        private val tokenizerDir = File(root, "dicdir").apply { mkdirs() }
        private val cacheRoot = File(root, "cache").apply { mkdirs() }
        private val bridge =
            PyBridge { raw, _ ->
                bridgeThreads += Thread.currentThread().name
                when (val request = BridgeJsonCodec.decode(raw)) {
                    is BridgeMessage.TokenizerConfigure -> {
                        events += "configure"
                        val configuration = request.configuration
                        BridgeJsonCodec.encodeTokenizerReady(
                            TokenizerIdentity(
                                dicDir = configuration.dicDir,
                                resourceId = configuration.resourceId,
                                treeSha256 = configuration.treeSha256,
                                backend = configuration.backend,
                                fileCount = 1,
                                totalBytes = 1,
                            ),
                        )
                    }
                    is BridgeMessage.SubtitleCuesRequest -> {
                        events += "cues"
                        if (cueFailure) {
                            errorResponse
                        } else {
                            cueResponse(request.subtitlePath)
                        }
                    }
                    else -> error("Unexpected bridge request")
                }
            }
        private val tokenizer =
            TokenizerConfigurator(
                bridge = bridge,
                provider =
                    InstalledTokenizerResourceProvider {
                        if (tokenizerInstalled) {
                            InstalledTokenizerResource(
                                dicDir = tokenizerDir,
                                resourceId = "unidic-lite-test",
                                treeSha256 = "a".repeat(64),
                            )
                        } else {
                            null
                        }
                    },
            )
        private val cueLookup = BridgeSubtitleCueLookupService(bridge, resourceExecutor)

        fun loader(): TimingPreviewLoader =
            TimingPreviewLoader(
                coordinator = coordinator,
                ownerFactory = { cancellation -> createOwner(cancellation) },
                tokenizer = tokenizer,
                cueLookup = cueLookup,
                io = io,
                resourceDispatcher = resource,
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
                                )
                        },
                    cacheFileFactory =
                        CacheFileFactory { suffix ->
                            File.createTempFile("timing-", suffix, cacheRoot).also(stagedFile::set)
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
    ) : OwnedDescriptor {
        override val knownSizeBytes: Long = SUBTITLE_BYTES.size.toLong()

        override fun openInputStream(): InputStream = ByteArrayInputStream(SUBTITLE_BYTES)

        override fun close() {
            closeThread.set(Thread.currentThread().name)
            closeCount.incrementAndGet()
        }
    }

    private fun assertOwnerClosed(owner: SafJobFileOwner) {
        assertThrows(IllegalStateException::class.java) {
            owner.materializeSubtitleUri(SUBTITLE.uri, SUBTITLE.displayName)
        }
    }

    private companion object {
        val SUBTITLE =
            SafDocument(
                uri = "content://test/subtitle.srt",
                displayName = "subtitle.srt",
                mimeType = "application/x-subrip",
                sizeBytes = null,
            )
        val SUBTITLE_BYTES = "1\n00:00:01,000 --> 00:00:02,000\n猫だ。\n".toByteArray()
        val errorResponse =
            """
            {
              "schemaVersion": 1,
              "type": "bridge.error",
              "payload": {
                "code": "subtitle_cues_parse_failed",
                "message": "could not parse",
                "requestType": "subtitle.cues"
              }
            }
            """.trimIndent()

        fun cueResponse(path: String): String =
            """
            {
              "schemaVersion": 1,
              "type": "subtitle.cues.result",
              "payload": {
                "runId": null,
                "subtitlePath": "$path",
                "cues": [{"start": 1.0, "end": 2.0, "text": "猫だ。"}]
              }
            }
            """.trimIndent()
    }
}
