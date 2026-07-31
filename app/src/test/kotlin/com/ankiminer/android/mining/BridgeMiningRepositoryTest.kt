package com.ankiminer.android.mining

import com.ankiminer.android.anki.protocol.ReleaseState
import com.ankiminer.android.anki.provider.AnkiCancellation
import com.ankiminer.android.data.RuntimeWorkCoordinator
import com.ankiminer.android.engine.BridgeJsonCodec
import com.ankiminer.android.engine.BridgeMessage
import com.ankiminer.android.engine.EngineCallbacks
import com.ankiminer.android.engine.MiningConfigSnapshot
import com.ankiminer.android.engine.PyBridge
import com.ankiminer.android.engine.TokenizerIdentity
import com.ankiminer.android.engine.VideoMiningWireRequest
import com.ankiminer.android.media.FileCopyCancelledException
import com.ankiminer.android.localization.testStringResourceResolver
import com.ankiminer.android.service.MiningForegroundLease
import com.ankiminer.android.service.MiningForegroundProgress
import com.ankiminer.android.service.MiningForegroundSessionIdentity
import com.ankiminer.android.service.MiningForegroundSessionListener
import java.io.File
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeMiningRepositoryTest {
    private val executors = mutableListOf<ExecutorService>()

    @After
    fun stopExecutors() {
        executors.forEach(ExecutorService::shutdownNow)
    }

    @Test
    fun `engine progress descriptions never reach the foreground notification`() {
        val harness = harness()

        runBlocking { harness.repository.startVideo(INPUT) }
        val curating = awaitState(harness.repository) { it is MiningRunState.Curating } as MiningRunState.Curating
        runBlocking {
            harness.repository.confirmCuration(
                curating.request.runId,
                curating.request.requestId,
                FIRST_SELECTION,
            )
        }
        assertTrue(harness.bridge.curationSubmitted.await(2, TimeUnit.SECONDS))
        assertEquals(1, harness.foreground.startCount.get())

        harness.bridge.runCallbacks!!.onProgress(HOSTILE_PROGRESS)

        val published = harness.foreground.lease.published
        assertTrue("expected the hostile progress event to be published", published.isNotEmpty())
        assertEquals(MiningForegroundProgress(completed = 2, total = 3), published.last())
        published.forEach { progress ->
            assertFalse(
                "mined term leaked into foreground progress: $progress",
                progress.toString().contains(MINED_TERM),
            )
        }

        harness.bridge.allowTerminal.countDown()
        awaitState(harness.repository, MiningRunState::isTerminal)
    }

    @Test
    fun `confirmed nonempty curation promotes foreground before unblocking Python`() {
        val harness = harness()

        runBlocking { harness.repository.startVideo(INPUT) }
        val curating = awaitState(harness.repository) { it is MiningRunState.Curating } as MiningRunState.Curating
        runBlocking {
            harness.repository.confirmCuration(
                curating.request.runId,
                curating.request.requestId,
                FIRST_SELECTION,
            )
        }

        assertTrue(harness.repository.state.value is MiningRunState.Running)
        assertTrue(harness.bridge.curationSubmitted.await(2, TimeUnit.SECONDS))
        assertEquals(1, harness.foreground.startCount.get())
        assertEquals(FIRST_SELECTION, harness.bridge.selection)
        harness.bridge.allowTerminal.countDown()

        val success = awaitState(harness.repository, MiningRunState::isTerminal) as MiningRunState.Success
        assertEquals(RUN_ID, success.runId)
        assertEquals(1, harness.inputOwner.closeCount.get())
        assertEquals(1, harness.foreground.lease.closeCount.get())
        assertEquals(listOf(RUN_ID), harness.anki.fallbackRuns)
    }

    @Test
    fun `video request keeps episode filename and uses stable local series label`() {
        val harness = harness()

        runBlocking { harness.repository.startVideo(INPUT) }
        val curating =
            awaitState(harness.repository) { it is MiningRunState.Curating } as MiningRunState.Curating
        val request = requireNotNull(harness.bridge.videoRequest.get())

        assertEquals("episode", request.episodeName)
        assertEquals("Local video", request.seriesName)
        runBlocking { harness.repository.cancel(curating.request.runId) }
        assertTrue(harness.bridge.cancellationSubmitted.await(2, TimeUnit.SECONDS))
        harness.bridge.allowTerminal.countDown()
        assertTrue(awaitState(harness.repository, MiningRunState::isTerminal) is MiningRunState.Cancelled)
    }

    @Test
    fun `video request falls back to local label when filename has no usable stem`() {
        val input =
            INPUT.copy(video = MiningSource("content://test/unnamed-video", ".mkv"))
        val harness = harness()

        runBlocking { harness.repository.startVideo(input) }
        val curating =
            awaitState(harness.repository) { it is MiningRunState.Curating } as MiningRunState.Curating
        val request = requireNotNull(harness.bridge.videoRequest.get())

        assertEquals("Local video", request.episodeName)
        assertEquals("Local video", request.seriesName)
        runBlocking { harness.repository.cancel(curating.request.runId) }
        assertTrue(harness.bridge.cancellationSubmitted.await(2, TimeUnit.SECONDS))
        harness.bridge.allowTerminal.countDown()
        awaitState(harness.repository, MiningRunState::isTerminal)
    }

    @Test
    fun `empty single-page selection skips foreground and still completes`() {
        val harness = harness()

        runBlocking { harness.repository.startVideo(INPUT) }
        val curating = awaitState(harness.repository) { it is MiningRunState.Curating } as MiningRunState.Curating
        runBlocking {
            harness.repository.confirmCuration(
                curating.request.runId,
                curating.request.requestId,
                emptyList(),
            )
        }

        assertTrue(harness.bridge.curationSubmitted.await(2, TimeUnit.SECONDS))
        assertEquals(0, harness.foreground.startCount.get())
        assertEquals(emptyList<CurationSelection>(), harness.bridge.selection)
        harness.bridge.allowTerminal.countDown()

        assertTrue(awaitState(harness.repository, MiningRunState::isTerminal) is MiningRunState.Success)
        assertEquals(0, harness.foreground.lease.closeCount.get())
    }

    @Test
    fun `cancelling parked curation never starts foreground and stays cancelled`() {
        val harness = harness()

        runBlocking { harness.repository.startVideo(INPUT) }
        val curating = awaitState(harness.repository) { it is MiningRunState.Curating } as MiningRunState.Curating
        runBlocking { harness.repository.cancel(curating.request.runId) }

        assertTrue(harness.bridge.cancellationSubmitted.await(2, TimeUnit.SECONDS))
        assertEquals(0, harness.foreground.startCount.get())
        assertTrue(harness.anki.cancellation?.isCancelled() == true)
        harness.bridge.allowTerminal.countDown()

        val cancelled = awaitState(harness.repository, MiningRunState::isTerminal) as MiningRunState.Cancelled
        assertEquals(RUN_ID, cancelled.runId)
        assertNull(cancelled.result)
    }

    @Test
    fun `earlier page selection and empty final page promote foreground once`() {
        val harness = harness(pagedCuration = true)

        runBlocking { harness.repository.startVideo(INPUT) }
        val first = awaitState(harness.repository) {
            (it as? MiningRunState.Curating)?.request?.page?.pageIndex == 0L
        } as MiningRunState.Curating
        runBlocking {
            harness.repository.confirmCuration(
                first.request.runId,
                first.request.requestId,
                FIRST_SELECTION,
                pageIndex = 0,
            )
        }

        assertTrue(harness.bridge.intermediateCurationSubmitted.await(2, TimeUnit.SECONDS))
        val second = awaitState(harness.repository) {
            (it as? MiningRunState.Curating)?.request?.page?.pageIndex == 1L
        } as MiningRunState.Curating
        assertEquals(0, harness.foreground.startCount.get())
        assertFalse(second.pageSubmissionPending)

        runBlocking {
            harness.repository.confirmCuration(
                second.request.runId,
                second.request.requestId,
                emptyList(),
                pageIndex = 1,
            )
        }
        assertTrue(harness.bridge.curationSubmitted.await(2, TimeUnit.SECONDS))
        assertEquals(1, harness.foreground.startCount.get())
        assertEquals(emptyList<CurationSelection>(), harness.bridge.selection)
        harness.bridge.allowTerminal.countDown()
        assertTrue(awaitState(harness.repository, MiningRunState::isTerminal) is MiningRunState.Success)
    }

    @Test
    fun `stale curation page index is rejected and page can still be cancelled`() {
        val harness = harness(pagedCuration = true)

        runBlocking { harness.repository.startVideo(INPUT) }
        val first = awaitState(harness.repository) {
            (it as? MiningRunState.Curating)?.request?.page?.pageIndex == 0L
        } as MiningRunState.Curating
        runBlocking {
            harness.repository.confirmCuration(
                first.request.runId,
                first.request.requestId,
                emptyList(),
                pageIndex = 0,
            )
        }
        val second = awaitState(harness.repository) {
            (it as? MiningRunState.Curating)?.request?.page?.pageIndex == 1L
        } as MiningRunState.Curating

        var stale: RuntimeException? = null
        try {
            runBlocking {
                harness.repository.confirmCuration(
                    second.request.runId,
                    second.request.requestId,
                    emptyList(),
                    pageIndex = 0,
                )
            }
        } catch (failure: RuntimeException) {
            stale = failure
        }
        assertTrue(stale is MiningCommandException)
        assertEquals(0, harness.foreground.startCount.get())

        runBlocking { harness.repository.cancel(second.request.runId) }
        assertTrue(harness.bridge.cancellationSubmitted.await(2, TimeUnit.SECONDS))
        harness.bridge.allowTerminal.countDown()
        assertTrue(awaitState(harness.repository, MiningRunState::isTerminal) is MiningRunState.Cancelled)
    }

    @Test
    fun `foreground promotion failure cancels Python and dominates terminal cancellation`() {
        val harness = harness(foregroundFailure = true)

        runBlocking { harness.repository.startVideo(INPUT) }
        val curating = awaitState(harness.repository) { it is MiningRunState.Curating } as MiningRunState.Curating
        runBlocking {
            harness.repository.confirmCuration(
                curating.request.runId,
                curating.request.requestId,
                FIRST_SELECTION,
            )
        }

        assertTrue(harness.bridge.cancellationSubmitted.await(2, TimeUnit.SECONDS))
        harness.bridge.allowTerminal.countDown()
        val failed = awaitState(harness.repository, MiningRunState::isTerminal) as MiningRunState.Failed
        assertEquals("Background mining did not start safely", failed.failure.message)
        assertFalse(failed.failure.retryable)
        // "unconfirmed", not "timeout": this fixture completes the future exceptionally, so the
        // bounded get() raises ExecutionException and never reaches its deadline.
        assertEquals("foreground_start_unconfirmed", failed.failure.diagnostic)
    }

    @Test
    fun `terminal callback and return must be byte identical`() {
        val harness = harness(mismatchedTerminal = true)

        runBlocking { harness.repository.startVideo(INPUT) }
        val curating = awaitState(harness.repository) { it is MiningRunState.Curating } as MiningRunState.Curating
        runBlocking {
            harness.repository.confirmCuration(
                curating.request.runId,
                curating.request.requestId,
                emptyList(),
            )
        }
        assertTrue(harness.bridge.curationSubmitted.await(2, TimeUnit.SECONDS))
        harness.bridge.allowTerminal.countDown()

        val failed = awaitState(harness.repository, MiningRunState::isTerminal) as MiningRunState.Failed
        assertEquals("Python terminal callback and return value disagreed", failed.failure.message)
        assertNotNull(failed.result)
    }

    @Test
    fun `detached equal URIs release two owned references`() {
        val releases = Collections.synchronizedList(mutableListOf<String>())
        val sameInput =
            VideoMiningInput(
                video = MiningSource("content://test/same", "video.mkv"),
                subtitle = MiningSource("content://test/same", "subtitle.srt"),
            )
        val harness = harness(releases = releases)

        runBlocking { harness.repository.startVideo(sameInput) }
        val curating = awaitState(harness.repository) { it is MiningRunState.Curating } as MiningRunState.Curating
        assertTrue(harness.repository.detachActiveSources(sameInput))
        assertFalse(harness.repository.detachActiveSources(sameInput))
        runBlocking { harness.repository.cancel(curating.request.runId) }
        assertTrue(harness.bridge.cancellationSubmitted.await(2, TimeUnit.SECONDS))
        harness.bridge.allowTerminal.countDown()
        awaitState(harness.repository, MiningRunState::isTerminal)

        assertEquals(listOf("content://test/same", "content://test/same"), releases)
    }

    @Test
    fun `deferred Anki cleanup quarantines future admission until process restart`() {
        val harness = harness(fallbackState = ReleaseState.DEFERRED)

        runBlocking { harness.repository.startVideo(INPUT) }
        val curating = awaitState(harness.repository) { it is MiningRunState.Curating } as MiningRunState.Curating
        runBlocking {
            harness.repository.confirmCuration(
                curating.request.runId,
                curating.request.requestId,
                emptyList(),
            )
        }
        assertTrue(harness.bridge.curationSubmitted.await(2, TimeUnit.SECONDS))
        harness.bridge.allowTerminal.countDown()
        val cleanupFailure = awaitState(harness.repository, MiningRunState::isTerminal) as MiningRunState.Failed
        assertEquals("Anki cleanup remained incomplete", cleanupFailure.failure.message)

        runBlocking { harness.repository.reset() }
        runBlocking { harness.repository.startVideo(INPUT) }
        val restartFailure = harness.repository.state.value as MiningRunState.Failed
        assertEquals("Restart the app before starting another mining run", restartFailure.failure.message)
        assertEquals(1, harness.bridge.videoRuns.get())
    }

    @Test
    fun `installed resource inspection stays on the mining worker and fails before SAF`() {
        val callerThread = Thread.currentThread()
        val inspectedOn = AtomicReference<Thread>()
        val harness =
            harness(
                tokenizerResourceProvider =
                    InstalledTokenizerResourceProvider {
                        inspectedOn.set(Thread.currentThread())
                        null
                    },
            )

        runBlocking { harness.repository.startVideo(INPUT) }
        val failed = awaitState(harness.repository, MiningRunState::isTerminal) as MiningRunState.Failed

        assertTrue(inspectedOn.get() !== callerThread)
        assertEquals("Install the Japanese tokenizer resource before mining", failed.failure.message)
        assertTrue(failed.failure.retryable)
        assertEquals(0, harness.bridge.videoRuns.get())
        assertEquals(0, harness.inputOwner.closeCount.get())
    }

    @Test
    fun `settings snapshot is captured only after mining excludes resource publication`() {
        val coordinator = RuntimeWorkCoordinator()
        val resolverReached = CountDownLatch(1)
        val allowResolver = CountDownLatch(1)
        val harness =
            harness(
                runtimeWorkCoordinator = coordinator,
                configSnapshotResolver =
                    MiningConfigSnapshotResolver {
                        resolverReached.countDown()
                        check(allowResolver.await(2, TimeUnit.SECONDS))
                        MiningConfigSnapshot(emptyMap(), false)
                    },
            )

        runBlocking { harness.repository.startVideo(INPUT) }
        assertTrue(resolverReached.await(2, TimeUnit.SECONDS))
        assertNull(coordinator.tryAcquire(RuntimeWorkCoordinator.Kind.RESOURCE))
        allowResolver.countDown()
        val curating = awaitState(harness.repository) { it is MiningRunState.Curating } as MiningRunState.Curating
        runBlocking { harness.repository.cancel(curating.request.runId) }
        harness.bridge.allowTerminal.countDown()
        awaitState(harness.repository, MiningRunState::isTerminal)

        val resourceLease = coordinator.tryAcquire(RuntimeWorkCoordinator.Kind.RESOURCE)
        assertNotNull(resourceLease)
        requireNotNull(resourceLease).close()
    }

    @Test
    fun `opaque token cancels accepted work before any expensive preparation`() {
        val queuedRun = AtomicReference<(() -> Unit)?>()
        val controlExecutor = Executors.newSingleThreadExecutor().also(executors::add)
        val bridge = FakePyBridge(mismatchedTerminal = false)
        val inputOwner = FakeInputOwner()
        val repository =
            BridgeMiningRepository(
                pyBridge = bridge,
                anki = FakeAnkiCallbacks(ReleaseState.ABSENT),
                inputOwnerFactory = MiningInputOwnerFactory { inputOwner },
                tokenizerResourceProvider = InstalledTokenizerResourceProvider { error("must not inspect") },
                runtimePaths = MiningRuntimePaths(File("/tmp/cache"), File("/tmp/native")),
                sourceGrantReleaser = SourceGrantReleaser { },
                foregroundStarter = FakeForegroundStarter(fail = false),
                runExecutor = MiningTaskExecutor { task -> queuedRun.set(task) },
                controlExecutor = controlExecutor.asMiningTaskExecutor(),
                strings = testStringResourceResolver,
            )

        runBlocking { repository.startVideo(INPUT) }
        val starting = repository.state.value as MiningRunState.Starting
        val token = requireNotNull(starting.cancellationToken)
        runBlocking { repository.cancel(token) }
        requireNotNull(queuedRun.get()).invoke()

        val cancelled = repository.state.value as MiningRunState.Cancelled
        assertNull(cancelled.runId)
        assertEquals(0, bridge.videoRuns.get())
        assertEquals(0, inputOwner.closeCount.get())
    }

    @Test
    fun `cancel during media preparation terminates as cancelled without fault`() {
        val copyStarted = CountDownLatch(1)
        val allowCopyFailure = CountDownLatch(1)
        val runExecutor = Executors.newSingleThreadExecutor().also(executors::add)
        val controlExecutor = Executors.newSingleThreadExecutor().also(executors::add)
        val repository =
            BridgeMiningRepository(
                pyBridge = FakePyBridge(mismatchedTerminal = false),
                anki = FakeAnkiCallbacks(ReleaseState.ABSENT),
                inputOwnerFactory =
                    MiningInputOwnerFactory {
                        object : MiningInputOwner {
                            override fun openVideo(source: MiningSource): String {
                                copyStarted.countDown()
                                check(allowCopyFailure.await(2, TimeUnit.SECONDS))
                                throw FileCopyCancelledException()
                            }

                            override fun materializeSubtitle(source: MiningSource): String =
                                error("subtitle must not be reached after a cancelled video copy")

                            override fun close() {}
                        }
                    },
                tokenizerResourceProvider =
                    InstalledTokenizerResourceProvider {
                        InstalledTokenizerResource(
                            File("/tmp/test-unidic"),
                            TOKENIZER_RESOURCE_ID,
                            TOKENIZER_SHA,
                        )
                    },
                runtimePaths = MiningRuntimePaths(File("/tmp/cache"), File("/tmp/native")),
                sourceGrantReleaser = SourceGrantReleaser { },
                foregroundStarter = FakeForegroundStarter(fail = false),
                runExecutor = runExecutor.asMiningTaskExecutor(),
                controlExecutor = controlExecutor.asMiningTaskExecutor(),
                strings = testStringResourceResolver,
            )

        runBlocking { repository.startVideo(INPUT) }
        assertTrue(copyStarted.await(2, TimeUnit.SECONDS))
        val token =
            requireNotNull((repository.state.value as MiningRunState.Starting).cancellationToken)
        runBlocking { repository.cancel(token) }
        allowCopyFailure.countDown()

        val terminal = awaitState(repository, MiningRunState::isTerminal)
        assertTrue("expected Cancelled, was $terminal", terminal is MiningRunState.Cancelled)
    }

    @Test
    fun `token cancellation racing Python registration is forwarded by run identity`() {
        val harness = harness(blockRegistration = true)

        runBlocking { harness.repository.startVideo(INPUT) }
        assertTrue(harness.bridge.registrationReached.await(2, TimeUnit.SECONDS))
        val token =
            requireNotNull((harness.repository.state.value as MiningRunState.Starting).cancellationToken)
        runBlocking { harness.repository.cancel(token) }
        harness.bridge.allowRegistration.countDown()

        assertTrue(harness.bridge.cancellationSubmitted.await(2, TimeUnit.SECONDS))
        assertTrue(harness.anki.cancellation?.isCancelled() == true)
        harness.bridge.allowTerminal.countDown()
        val cancelled = awaitState(harness.repository, MiningRunState::isTerminal) as MiningRunState.Cancelled
        assertEquals(RUN_ID, cancelled.runId)
    }

    @Test
    fun `presenter warning emitted before terminal is retained in successful result`() {
        val harness = harness(presenterWarning = PRESENTER_WARNING_MESSAGE)

        runBlocking { harness.repository.startVideo(INPUT) }
        val curating =
            awaitState(harness.repository) { it is MiningRunState.Curating } as MiningRunState.Curating
        runBlocking {
            harness.repository.confirmCuration(
                curating.request.runId,
                curating.request.requestId,
                emptyList(),
            )
        }

        assertTrue(harness.bridge.curationSubmitted.await(2, TimeUnit.SECONDS))
        harness.bridge.allowTerminal.countDown()

        val success =
            awaitState(harness.repository, MiningRunState::isTerminal) as MiningRunState.Success
        assertEquals(listOf(PRESENTER_WARNING_MESSAGE), success.result.errors)
    }

    @Test
    fun `presenter warning survives the retained terminal error cap`() {
        val harness =
            harness(
                presenterWarning = PRESENTER_WARNING_MESSAGE,
                terminalErrorCount = MAX_RESULT_ERRORS,
            )

        runBlocking { harness.repository.startVideo(INPUT) }
        val curating =
            awaitState(harness.repository) { it is MiningRunState.Curating } as MiningRunState.Curating
        runBlocking {
            harness.repository.confirmCuration(
                curating.request.runId,
                curating.request.requestId,
                emptyList(),
            )
        }

        assertTrue(harness.bridge.curationSubmitted.await(2, TimeUnit.SECONDS))
        harness.bridge.allowTerminal.countDown()

        val failed =
            awaitState(harness.repository, MiningRunState::isTerminal) as MiningRunState.Failed
        val errors = requireNotNull(failed.result).errors
        assertEquals(MAX_RESULT_ERRORS, errors.size)
        assertEquals(PRESENTER_WARNING_MESSAGE, errors.first())
        assertEquals("terminal error 254", errors.last())
        assertFalse("terminal error 255" in errors)
    }

    @Test
    fun `last-resort failure carries a message-free exception digest in the fault`() {
        val harness =
            harness(
                videoRunFailure = IllegalStateException("secret /storage/emulated/0/episode.mkv"),
            )

        runBlocking { harness.repository.startVideo(INPUT) }

        val failed =
            awaitState(harness.repository, MiningRunState::isTerminal) as MiningRunState.Failed
        val message = failed.failure.message
        assertTrue(message, "IllegalStateException" in message)
        assertFalse(message, "secret" in message)
        assertFalse(message, "episode.mkv" in message)
    }

    @Test
    fun `python fault id reaches the failure state without changing its message`() {
        val harness = harness(raisedFailure = true)

        runBlocking { harness.repository.startVideo(INPUT) }
        val curating = awaitState(harness.repository) { it is MiningRunState.Curating } as MiningRunState.Curating
        runBlocking {
            harness.repository.confirmCuration(curating.request.runId, curating.request.requestId, emptyList())
        }
        assertTrue(harness.bridge.curationSubmitted.await(2, TimeUnit.SECONDS))
        harness.bridge.allowTerminal.countDown()

        val failed = awaitState(harness.repository, MiningRunState::isTerminal) as MiningRunState.Failed
        assertEquals("Mining failed", failed.failure.message)
        assertEquals(TERMINAL_FAULT_ID, failed.failure.faultId)
        // The engine's terminal code, kept beside the localized message it cannot replace.
        assertEquals("engine_error", failed.failure.diagnostic)
    }

    @Test
    fun `a Kotlin fault wins the message but keeps the Python fault id`() {
        val harness = harness(raisedFailure = true, fallbackState = ReleaseState.DEFERRED)

        runBlocking { harness.repository.startVideo(INPUT) }
        val curating = awaitState(harness.repository) { it is MiningRunState.Curating } as MiningRunState.Curating
        runBlocking {
            harness.repository.confirmCuration(curating.request.runId, curating.request.requestId, emptyList())
        }
        assertTrue(harness.bridge.curationSubmitted.await(2, TimeUnit.SECONDS))
        harness.bridge.allowTerminal.countDown()

        val failed = awaitState(harness.repository, MiningRunState::isTerminal) as MiningRunState.Failed
        assertEquals("Anki cleanup remained incomplete", failed.failure.message)
        assertEquals(TERMINAL_FAULT_ID, failed.failure.faultId)
        // The Kotlin fault owns the message, so it owns the code too. This site has no code of its
        // own, and inheriting the engine's would claim a Python origin the failure does not have.
        assertNull(failed.failure.diagnostic)
    }

    private fun harness(
        foregroundFailure: Boolean = false,
        mismatchedTerminal: Boolean = false,
        fallbackState: ReleaseState = ReleaseState.ABSENT,
        releases: MutableList<String> = Collections.synchronizedList(mutableListOf()),
        blockRegistration: Boolean = false,
        pagedCuration: Boolean = false,
        presenterWarning: String? = null,
        terminalErrorCount: Int = 0,
        videoRunFailure: RuntimeException? = null,
        raisedFailure: Boolean = false,
        tokenizerResourceProvider: InstalledTokenizerResourceProvider =
            InstalledTokenizerResourceProvider {
                InstalledTokenizerResource(
                    File("/tmp/test-unidic"),
                    TOKENIZER_RESOURCE_ID,
                    TOKENIZER_SHA,
                )
            },
        runtimeWorkCoordinator: RuntimeWorkCoordinator = RuntimeWorkCoordinator(),
        configSnapshotResolver: MiningConfigSnapshotResolver =
            MiningConfigSnapshotResolver { MiningConfigSnapshot(emptyMap(), false) },
    ): Harness {
        val runExecutor = Executors.newSingleThreadExecutor().also(executors::add)
        val controlExecutor = Executors.newSingleThreadExecutor().also(executors::add)
        val bridge =
            FakePyBridge(
                mismatchedTerminal = mismatchedTerminal,
                blockRegistration = blockRegistration,
                pagedCuration = pagedCuration,
                presenterWarning = presenterWarning,
                terminalErrorCount = terminalErrorCount,
                videoRunFailure = videoRunFailure,
                raisedFailure = raisedFailure,
            )
        val anki = FakeAnkiCallbacks(fallbackState)
        val inputOwner = FakeInputOwner()
        val foreground = FakeForegroundStarter(foregroundFailure)
        val repository =
            BridgeMiningRepository(
                pyBridge = bridge,
                anki = anki,
                inputOwnerFactory = MiningInputOwnerFactory { inputOwner },
                tokenizerResourceProvider = tokenizerResourceProvider,
                runtimePaths = MiningRuntimePaths(File("/tmp/cache"), File("/tmp/native")),
                sourceGrantReleaser = SourceGrantReleaser(releases::add),
                foregroundStarter = foreground,
                runExecutor = runExecutor.asMiningTaskExecutor(),
                controlExecutor = controlExecutor.asMiningTaskExecutor(),
                runtimeWorkCoordinator = runtimeWorkCoordinator,
                configSnapshotResolver = configSnapshotResolver,
                strings = testStringResourceResolver,
                foregroundStartTimeoutSeconds = 2,
            )
        return Harness(repository, bridge, anki, inputOwner, foreground)
    }

    private fun awaitState(
        repository: MiningRepository,
        predicate: (MiningRunState) -> Boolean,
    ): MiningRunState {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (System.nanoTime() < deadline) {
            val state = repository.state.value
            if (predicate(state)) return state
            Thread.sleep(5)
        }
        throw AssertionError("Timed out waiting for repository state; current=${repository.state.value}")
    }

    private data class Harness(
        val repository: BridgeMiningRepository,
        val bridge: FakePyBridge,
        val anki: FakeAnkiCallbacks,
        val inputOwner: FakeInputOwner,
        val foreground: FakeForegroundStarter,
    )

    private class FakeInputOwner : MiningInputOwner {
        val closeCount = AtomicInteger()

        override fun openVideo(source: MiningSource): String = "/tmp/video.mkv"

        override fun materializeSubtitle(source: MiningSource): String = "/tmp/subtitle.srt"

        override fun close() {
            closeCount.incrementAndGet()
        }
    }

    private class FakeAnkiCallbacks(
        private val fallbackState: ReleaseState,
    ) : CoordinatorAnkiCallbacks {
        var cancellation: AnkiCancellation? = null
        val fallbackRuns = Collections.synchronizedList(mutableListOf<String>())

        override fun registerRun(
            runId: String,
            cancellation: AnkiCancellation,
        ): Boolean {
            assertEquals(RUN_ID, runId)
            this.cancellation = cancellation
            return true
        }

        override fun verifyTarget(rawRequest: String): String = error("not called")

        override fun scanFirstFields(rawRequest: String): String = error("not called")

        override fun storeMedia(rawRequest: String): String = error("not called")

        override fun createNotes(rawRequest: String): String = error("not called")

        override fun releaseRunState(rawRequest: String): String = error("not called")

        override fun releaseRunStateFallback(runId: String): ReleaseState {
            fallbackRuns += runId
            return fallbackState
        }
    }

    private class FakeForegroundStarter(
        private val fail: Boolean,
    ) : MiningForegroundStarter {
        val startCount = AtomicInteger()
        val lease = FakeForegroundLease()

        override fun startSession(
            runId: String,
            generation: Long,
            listener: MiningForegroundSessionListener,
        ): CompletableFuture<MiningForegroundLease> {
            startCount.incrementAndGet()
            if (fail) {
                return CompletableFuture<MiningForegroundLease>().also {
                    it.completeExceptionally(IllegalStateException("test promotion failure"))
                }
            }
            lease.sessionIdentity =
                MiningForegroundSessionIdentity(
                    runId,
                    generation,
                    "00000000-0000-0000-0000-000000000001",
                )
            return CompletableFuture.completedFuture(lease)
        }
    }

    private class FakeForegroundLease : MiningForegroundLease {
        lateinit var sessionIdentity: MiningForegroundSessionIdentity
        override val identity: MiningForegroundSessionIdentity
            get() = sessionIdentity
        val closeCount = AtomicInteger()
        val published = CopyOnWriteArrayList<MiningForegroundProgress>()

        override fun updateProgress(progress: MiningForegroundProgress): Boolean {
            published += progress
            return true
        }

        override fun close() {
            closeCount.incrementAndGet()
        }
    }

    private class FakePyBridge(
        private val mismatchedTerminal: Boolean,
        blockRegistration: Boolean = false,
        private val pagedCuration: Boolean = false,
        private val presenterWarning: String? = null,
        private val terminalErrorCount: Int = 0,
        private val videoRunFailure: RuntimeException? = null,
        private val raisedFailure: Boolean = false,
    ) : PyBridge {
        val videoRuns = AtomicInteger()
        val videoRequest = AtomicReference<VideoMiningWireRequest?>()
        val curationSubmitted = CountDownLatch(1)
        val intermediateCurationSubmitted = CountDownLatch(1)
        val cancellationSubmitted = CountDownLatch(1)
        val allowTerminal = CountDownLatch(1)
        val registrationReached = CountDownLatch(1)
        val allowRegistration = CountDownLatch(if (blockRegistration) 1 else 0)
        private val cancelled = AtomicBoolean()
        @Volatile
        var runCallbacks: EngineCallbacks? = null
        @Volatile
        var selection: List<CurationSelection>? = null

        override fun dispatch(
            rawRequest: String,
            callbacks: EngineCallbacks?,
        ): String =
            when (val request = BridgeJsonCodec.decode(rawRequest)) {
                is BridgeMessage.TokenizerConfigure ->
                    BridgeJsonCodec.encodeTokenizerReady(
                        TokenizerIdentity(
                            dicDir = request.configuration.dicDir,
                            resourceId = request.configuration.resourceId,
                            treeSha256 = request.configuration.treeSha256,
                            backend = request.configuration.backend,
                            fileCount = 6,
                            totalBytes = 1024,
                        ),
                    )
                is BridgeMessage.VideoRun -> {
                    videoRunFailure?.let { throw it }
                    runVideo(request.request, requireNotNull(callbacks))
                }
                is BridgeMessage.CurationResponse -> {
                    selection = request.selection
                    curationSubmitted.countDown()
                    CURATION_ACCEPTED
                }
                is BridgeMessage.CurationPageResponse -> {
                    selection = request.selection
                    if (request.pageIndex == 0L) {
                        requireNotNull(runCallbacks).onCurationNeeded(CURATION_PAGE_2_REQUEST)
                        intermediateCurationSubmitted.countDown()
                        CURATION_PAGE_1_ACCEPTED
                    } else {
                        curationSubmitted.countDown()
                        CURATION_PAGE_2_ACCEPTED
                    }
                }
                is BridgeMessage.JobCancel -> {
                    cancelled.set(true)
                    cancellationSubmitted.countDown()
                    JOB_CANCELLED
                }
                else -> error("Unexpected request: $request")
            }

        private fun runVideo(
            request: VideoMiningWireRequest,
            callbacks: EngineCallbacks,
        ): String {
            runCallbacks = callbacks
            videoRequest.set(request)
            videoRuns.incrementAndGet()
            registrationReached.countDown()
            check(allowRegistration.await(3, TimeUnit.SECONDS))
            BridgeJsonCodec.decode(callbacks.registerJob(JOB_REGISTRATION), expectedRunId = RUN_ID)
            callbacks.onStart(PROGRESS_START)
            presenterWarning?.let {
                callbacks.onPresenterEvent(
                    PRESENTER_WARNING.replace(PRESENTER_WARNING_PLACEHOLDER, it),
                )
            }
            callbacks.onCurationNeeded(if (pagedCuration) CURATION_PAGE_1_REQUEST else CURATION_REQUEST)
            while (curationSubmitted.count > 0 && cancellationSubmitted.count > 0) {
                Thread.sleep(2)
            }
            check(allowTerminal.await(3, TimeUnit.SECONDS))
            if (cancelled.get()) {
                callbacks.onComplete(CANCELLED_TERMINAL)
                return CANCELLED_TERMINAL
            }
            val terminal = terminalPayload()
            val callbackTerminal =
                if (mismatchedTerminal) {
                    terminal.replace("\"elapsedTime\":1.0", "\"elapsedTime\":2.0")
                } else {
                    terminal
                }
            if (terminalErrorCount > 0 || raisedFailure) {
                callbacks.onError(callbackTerminal)
            } else {
                callbacks.onComplete(callbackTerminal)
            }
            return terminal
        }

        private fun terminalPayload(): String {
            if (raisedFailure) return RAISED_FAILURE_TERMINAL
            if (terminalErrorCount == 0) return SUCCESS_TERMINAL
            val errors =
                (0 until terminalErrorCount).joinToString(prefix = "[", postfix = "]") {
                    "\"terminal error $it\""
                }
            return SUCCESS_TERMINAL
                .replace("\"outcome\":\"success\"", "\"outcome\":\"failed\"")
                .replace("\"errors\":[]", "\"errors\":$errors")
                .replace(
                    "\"error\":null",
                    "\"error\":{\"code\":\"processing_failed\",\"message\":\"Processing failed\"}",
                )
        }
    }

    private companion object {
        const val RUN_ID = "run_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val REQUEST_ID = "curation_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val CANDIDATE_ID = "candidate_cccccccccccccccccccccccccccccccc"
        const val SENTENCE_ID = "sentence_dddddddddddddddddddddddddddddddd"
        const val NEXT_CANDIDATE_ID = "candidate_eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
        const val NEXT_SENTENCE_ID = "sentence_ffffffffffffffffffffffffffffffff"
        const val TOKENIZER_RESOURCE_ID = "unidic-lite-1"
        const val TOKENIZER_SHA = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
        const val MAX_RESULT_ERRORS = 256
        const val PRESENTER_WARNING_MESSAGE = "Offline sentence audio is unavailable"
        const val PRESENTER_WARNING_PLACEHOLDER = "__WARNING__"
        val FIRST_SELECTION = listOf(CurationSelection(CANDIDATE_ID, SENTENCE_ID))
        val INPUT =
            VideoMiningInput(
                video = MiningSource("content://test/video", "episode.mkv"),
                subtitle = MiningSource("content://test/subtitle", "episode.srt"),
            )
        val JOB_REGISTRATION =
            """{"schemaVersion":1,"type":"job.registration.request","payload":{"runId":"$RUN_ID"}}"""
        val PROGRESS_START =
            """{"schemaVersion":1,"type":"progress.start","payload":{"runId":"$RUN_ID","total":3,"description":"Preparing curation"}}"""
        const val MINED_TERM = "猫"

        /** Shaped like a real phase-4 event: the engine names the term it just looked up. */
        val HOSTILE_PROGRESS =
            """{"schemaVersion":1,"type":"progress.update","payload":{"runId":"$RUN_ID","current":2,"description":"Definition found: $MINED_TERM"}}"""
        val PRESENTER_WARNING =
            """{"schemaVersion":1,"type":"presenter.event","payload":{"runId":"$RUN_ID","kind":"warning","message":"$PRESENTER_WARNING_PLACEHOLDER"}}"""
        val CURATION_REQUEST =
            """{"schemaVersion":1,"type":"curation.request","payload":{"runId":"$RUN_ID","requestId":"$REQUEST_ID","candidates":[{"candidateId":"$CANDIDATE_ID","minedForm":"猫","surface":"猫","lemma":"猫","reading":"ネコ","expressionReading":"ねこ","partOfSpeech":null,"frequencyRank":12,"occurrenceCount":1,"defaultSentenceId":"$SENTENCE_ID","sentences":[{"sentenceId":"$SENTENCE_ID","sentence":"猫だ。","sentenceFurigana":"猫[ねこ]だ。","sentenceReading":"ねこだ。","startTime":1.0,"endTime":2.0,"duration":1.0}]}]}}"""
        val CURATION_PAGE_1_REQUEST =
            """{"schemaVersion":1,"type":"curation.page.request","payload":{"runId":"$RUN_ID","requestId":"$REQUEST_ID","pageIndex":0,"pageCount":2,"candidateStart":0,"totalCandidates":2,"candidates":[{"candidateId":"$CANDIDATE_ID","minedForm":"猫","surface":"猫","lemma":"猫","reading":"ネコ","expressionReading":"ねこ","partOfSpeech":null,"frequencyRank":12,"occurrenceCount":1,"defaultSentenceId":"$SENTENCE_ID","sentences":[{"sentenceId":"$SENTENCE_ID","sentence":"猫だ。","sentenceFurigana":"猫[ねこ]だ。","sentenceReading":"ねこだ。","startTime":1.0,"endTime":2.0,"duration":1.0}]}]}}"""
        val CURATION_PAGE_2_REQUEST =
            """{"schemaVersion":1,"type":"curation.page.request","payload":{"runId":"$RUN_ID","requestId":"$REQUEST_ID","pageIndex":1,"pageCount":2,"candidateStart":1,"totalCandidates":2,"candidates":[{"candidateId":"$NEXT_CANDIDATE_ID","minedForm":"犬","surface":"犬","lemma":"犬","reading":"イヌ","expressionReading":"いぬ","partOfSpeech":null,"frequencyRank":13,"occurrenceCount":1,"defaultSentenceId":"$NEXT_SENTENCE_ID","sentences":[{"sentenceId":"$NEXT_SENTENCE_ID","sentence":"犬だ。","sentenceFurigana":"犬[いぬ]だ。","sentenceReading":"いぬだ。","startTime":2.0,"endTime":3.0,"duration":1.0}]}]}}"""
        val CURATION_ACCEPTED =
            """{"schemaVersion":1,"type":"curation.accepted","payload":{"runId":"$RUN_ID","requestId":"$REQUEST_ID"}}"""
        val CURATION_PAGE_1_ACCEPTED =
            """{"schemaVersion":1,"type":"curation.page.accepted","payload":{"runId":"$RUN_ID","requestId":"$REQUEST_ID","pageIndex":0,"finalPage":false}}"""
        val CURATION_PAGE_2_ACCEPTED =
            """{"schemaVersion":1,"type":"curation.page.accepted","payload":{"runId":"$RUN_ID","requestId":"$REQUEST_ID","pageIndex":1,"finalPage":true}}"""
        val JOB_CANCELLED =
            """{"schemaVersion":1,"type":"job.cancelled","payload":{"runId":"$RUN_ID","newlyCancelled":true}}"""
        val SUCCESS_TERMINAL =
            """{"schemaVersion":1,"type":"mining.terminal","payload":{"runId":"$RUN_ID","outcome":"success","result":{"totalWordsFound":1,"newWordsFound":0,"cardsCreated":0,"errors":[],"elapsedTime":1.0,"comprehensionPercentage":100.0,"cardIds":[],"videoFile":"episode.mkv","subtitleFile":"episode.srt","minedForms":[],"ankiWriteState":"no_note_write","failureIsTransient":false},"error":null}}"""
        val CANCELLED_TERMINAL =
            """{"schemaVersion":1,"type":"mining.terminal","payload":{"runId":"$RUN_ID","outcome":"cancelled","result":null,"error":{"code":"cancelled","message":"Mining was cancelled"}}}"""
        const val TERMINAL_FAULT_ID = "f0123abcd"
        val RAISED_FAILURE_TERMINAL =
            """{"schemaVersion":1,"type":"mining.terminal","payload":{"runId":"$RUN_ID","outcome":"failed","result":null,"error":{"code":"engine_error","message":"Mining failed","faultId":"$TERMINAL_FAULT_ID"}}}"""
    }
}
