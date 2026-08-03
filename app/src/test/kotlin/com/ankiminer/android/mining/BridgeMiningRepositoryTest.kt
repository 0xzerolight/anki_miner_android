package com.ankiminer.android.mining

import com.ankiminer.android.anki.protocol.AnkiErrorCode
import com.ankiminer.android.anki.protocol.ReleaseState
import com.ankiminer.android.anki.provider.AnkiCancellation
import com.ankiminer.android.anki.provider.AnkiReadFailure
import com.ankiminer.android.data.RuntimeWorkCoordinator
import com.ankiminer.android.diagnostics.log.AppLog
import com.ankiminer.android.diagnostics.log.LogLevel
import com.ankiminer.android.diagnostics.log.NoOpSink
import com.ankiminer.android.diagnostics.log.RecordingLogSink
import com.ankiminer.android.engine.BridgeJsonCodec
import com.ankiminer.android.engine.BridgeJsonValue
import com.ankiminer.android.engine.BridgeMessage
import com.ankiminer.android.engine.EngineCallbacks
import com.ankiminer.android.engine.MiningConfigSnapshot
import com.ankiminer.android.engine.PyBridge
import com.ankiminer.android.engine.TokenizerIdentity
import com.ankiminer.android.engine.VideoMiningWireRequest
import com.ankiminer.android.media.FileCopyCancelledException
import com.ankiminer.android.media.SafCopyProgress
import com.ankiminer.android.media.SafCopyProgressListener
import com.ankiminer.android.media.SafCopyRole
import com.ankiminer.android.localization.testStringResourceResolver
import com.ankiminer.android.service.ForegroundSessionRegistry
import com.ankiminer.android.service.MiningForegroundLease
import com.ankiminer.android.service.MiningForegroundProgress
import com.ankiminer.android.service.MiningForegroundProgressUnit
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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BridgeMiningRepositoryTest {
    private val executors = mutableListOf<ExecutorService>()
    private val recorded = RecordingLogSink()

    @Before
    fun installRecordingSink() {
        AppLog.setMinLevel(LogLevel.INFO)
        // Two installs: the first discards whatever a previous test class left in the pre-install
        // buffer, so the second starts from a known-empty recorder.
        AppLog.install(NoOpSink)
        AppLog.install(recorded)
    }

    @After
    fun stopExecutors() {
        executors.forEach(ExecutorService::shutdownNow)
        AppLog.install(NoOpSink)
    }

    @Test
    fun `every phase the run passes through is logged, in order, with its trigger`() {
        val harness = harness()

        runBlocking { harness.repository.startVideo(INPUT) }
        val curating = awaitState(harness.repository) { it is MiningRunState.Curating } as MiningRunState.Curating
        harness.bridge.runCallbacks!!.onStage(PROGRESS_STAGE)
        runBlocking {
            harness.repository.confirmCuration(
                curating.request.runId,
                curating.request.requestId,
                FIRST_SELECTION,
            )
        }
        assertTrue(harness.bridge.curationSubmitted.await(2, TimeUnit.SECONDS))
        harness.bridge.allowTerminal.countDown()
        awaitState(harness.repository, MiningRunState::isTerminal)

        assertEquals(
            listOf(
                "c=mining op=phase from=PREPARING to=REGISTERED outcome=ok detail=registration",
                "c=mining op=phase from=REGISTERED to=CURATING outcome=ok detail=curation_needed",
                "c=mining op=phase from=CURATING to=PROMOTING outcome=ok detail=curation_final",
                "c=mining op=phase from=PROMOTING to=RUNNING outcome=ok detail=foreground_started",
                "c=mining op=phase from=RUNNING to=FINALIZING outcome=ok detail=terminal",
            ),
            recordsFor("op=phase"),
        )

        // The ambient run id, on both threads that emit these records: the run executor, which
        // registerJob installs it on, and the control executor, which has to carry it across.
        val onRunThread = recorded.records.single { it.contains("detail=terminal") }
        val onControlThread = recorded.records.single { it.contains("detail=foreground_started") }
        assertTrue(onRunThread, onRunThread.contains(" run=$RUN_ID c=mining op=phase"))
        assertTrue(onControlThread, onControlThread.contains(" run=$RUN_ID c=mining op=phase"))
        assertTrue(
            recorded.records.single { it.contains("op=engine_stage") }
                .contains("outcome=ok"),
        )
    }

    @Test
    fun `the terminal mapping is logged with its outcome, code and notice count`() {
        val harness = harness(raisedFailure = true)

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
        harness.bridge.allowTerminal.countDown()
        awaitState(harness.repository, MiningRunState::isTerminal)

        assertEquals(
            listOf("c=mining op=run.terminal outcome=fail code=engine_error retryable=true notices=0"),
            recordsFor("op=run.terminal"),
        )
    }

    @Test
    fun `a protocol violation names the callback that raised it`() {
        val harness = harness()

        runBlocking { harness.repository.startVideo(INPUT) }
        awaitState(harness.repository) { it is MiningRunState.Curating }

        // A progress.start envelope arriving on onStage: one of the ~24 messages that all resolve to
        // the same user string, and the callback name is the only thing that separates them.
        assertThrows(IllegalStateException::class.java) {
            harness.bridge.runCallbacks!!.onStage(PROGRESS_START)
        }
        harness.bridge.allowTerminal.countDown()
        awaitState(harness.repository, MiningRunState::isTerminal)

        val record = recorded.records.single { it.contains("op=onStage") }
        assertTrue(record, record.contains(" E run=- c=mining op=onStage outcome=fail"))
        assertTrue(record, record.contains("Unexpected onStage message"))
    }

    @Test
    fun `a malformed callback has one owner with its callback and category`() {
        val harness = harness()

        runBlocking { harness.repository.startVideo(INPUT) }
        awaitState(harness.repository) { it is MiningRunState.Curating }

        assertThrows(IllegalStateException::class.java) {
            harness.bridge.runCallbacks!!.onStage(
                """{"schemaVersion":2,"type":"progress.stage","payload":{}}""",
            )
        }
        harness.bridge.allowTerminal.countDown()
        awaitState(harness.repository, MiningRunState::isTerminal)

        val records =
            recorded.records.filter { record ->
                record.contains("op=codec.decode") || record.contains("op=onStage")
            }
        assertEquals(1, records.size)
        val record = records.single()
        assertTrue(
            record,
            record.contains(
                " E run=- c=mining op=onStage outcome=fail " +
                    "category=UNSUPPORTED_SCHEMA_VERSION",
            ),
        )
    }

    @Test
    fun `an Anki callback failure carries the provider code and its cause`() {
        val harness =
            harness(
                ankiFailure =
                    AnkiReadFailure(
                        AnkiErrorCode.PROVIDER_UNAVAILABLE,
                        retryable = true,
                        stableMessage = "AnkiDroid is unavailable",
                        cause = IllegalStateException("provider died"),
                    ),
            )

        runBlocking { harness.repository.startVideo(INPUT) }
        awaitState(harness.repository) { it is MiningRunState.Curating }

        assertThrows(AnkiReadFailure::class.java) {
            harness.bridge.runCallbacks!!.ankiVerifyTarget(ANKI_VERIFY_REQUEST)
        }
        harness.bridge.allowTerminal.countDown()
        awaitState(harness.repository, MiningRunState::isTerminal)

        val record = recorded.records.single { it.contains("op=ankiVerifyTarget") }
        assertTrue(record, record.contains(" E run=- c=mining op=ankiVerifyTarget outcome=fail code=PROVIDER_UNAVAILABLE"))
        assertTrue(record, record.contains("Caused by: java.lang.IllegalStateException: provider died"))
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
    fun `curation parks the cpu wake lease and confirming re-arms it`() {
        val harness = harness()

        runBlocking { harness.repository.startVideo(INPUT) }
        val curating =
            awaitState(harness.repository) { it is MiningRunState.Curating } as MiningRunState.Curating

        // The FGS stays up across the wait; only the media-processing wake lease is dropped. The
        // park completes before Curating is published, so observing that state is enough: this is
        // not a race with the engine thread.
        assertEquals(listOf(true), harness.foreground.lease.cpuWakeEvents)
        assertEquals(0, harness.foreground.lease.closeCount.get())

        runBlocking {
            harness.repository.confirmCuration(
                curating.request.runId,
                curating.request.requestId,
                FIRST_SELECTION,
            )
        }

        assertEquals(listOf(true, false), harness.foreground.lease.cpuWakeEvents)
        harness.bridge.allowTerminal.countDown()
        awaitState(harness.repository, MiningRunState::isTerminal)
    }

    /**
     * A confirm landing between the park's decision and its lease call would leave the registry
     * parked for the rest of the run, so phases 3-5 would process media with no CPU wake lock.
     */
    @Test
    fun `no curation confirm is admitted while the wake park is in flight`() {
        val harness = harness()
        val confirmDuringPark = AtomicReference<Throwable?>()
        harness.foreground.lease.onPark = {
            confirmDuringPark.set(
                runCatching {
                    runBlocking {
                        harness.repository.confirmCuration(RUN_ID, REQUEST_ID, FIRST_SELECTION)
                    }
                }.exceptionOrNull(),
            )
        }

        runBlocking { harness.repository.startVideo(INPUT) }
        awaitState(harness.repository) { it is MiningRunState.Curating }

        assertTrue(
            "a confirm racing the park was admitted: ${confirmDuringPark.get()}",
            confirmDuringPark.get() is MiningCommandException,
        )
        assertEquals(listOf(true), harness.foreground.lease.cpuWakeEvents)

        runBlocking { harness.repository.cancel(RUN_ID) }
        harness.bridge.allowTerminal.countDown()
        awaitState(harness.repository, MiningRunState::isTerminal)
    }

    @Test
    fun `cancelling during curation tears the parked wake lease down exactly once`() {
        val harness = harness()

        runBlocking { harness.repository.startVideo(INPUT) }
        val curating =
            awaitState(harness.repository) { it is MiningRunState.Curating } as MiningRunState.Curating
        assertEquals(listOf(true), harness.foreground.lease.cpuWakeEvents)

        runBlocking { harness.repository.cancel(curating.request.runId) }
        harness.bridge.allowTerminal.countDown()
        awaitState(harness.repository, MiningRunState::isTerminal)

        // No resume on the way out, and the lease close is the single teardown.
        assertEquals(listOf(true), harness.foreground.lease.cpuWakeEvents)
        assertEquals(1, harness.foreground.lease.closeCount.get())
    }

    @Test
    fun `staging bytes reach the notification as bytes and engine counts as items`() {
        val harness =
            harness(
                copyProgress =
                    listOf(SafCopyProgress(SafCopyRole.VIDEO, 1L * 1024 * 1024, 4L * 1024 * 1024)),
            )

        runBlocking { harness.repository.startVideo(INPUT) }
        val curating =
            awaitState(harness.repository) { it is MiningRunState.Curating } as MiningRunState.Curating
        runBlocking {
            harness.repository.confirmCuration(
                curating.request.runId,
                curating.request.requestId,
                FIRST_SELECTION,
            )
        }
        assertTrue(harness.bridge.curationSubmitted.await(2, TimeUnit.SECONDS))
        harness.bridge.runCallbacks!!.onProgress(HOSTILE_PROGRESS)

        val published = harness.foreground.lease.published
        assertTrue(
            published.toString(),
            MiningForegroundProgress(
                completed = 1024 * 1024,
                total = 4 * 1024 * 1024,
                unit = MiningForegroundProgressUnit.BYTES,
            ) in published,
        )
        assertEquals(
            MiningForegroundProgress(
                completed = 2,
                total = 3,
                unit = MiningForegroundProgressUnit.ITEMS,
            ),
            published.last(),
        )

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
    fun `foreground ownership starts before video materialization`() {
        val harness = harness()

        runBlocking { harness.repository.startVideo(INPUT) }
        val curating =
            awaitState(harness.repository) { it is MiningRunState.Curating } as MiningRunState.Curating

        assertEquals(1, harness.inputOwner.foregroundStartsAtOpen.get())
        runBlocking { harness.repository.cancel(curating.request.runId) }
        harness.bridge.allowTerminal.countDown()
        awaitState(harness.repository, MiningRunState::isTerminal)
    }

    @Test
    fun `process recreation surfaces an interrupted video run`() {
        val interruptionStore = FakeMiningRunInterruptionStore()
        val activeHarness = harness(interruptionStore = interruptionStore)

        runBlocking { activeHarness.repository.startVideo(INPUT) }
        val curating =
            awaitState(activeHarness.repository) { it is MiningRunState.Curating } as
                MiningRunState.Curating
        interruptionStore.simulateProcessRestart()
        val recreated = harness(interruptionStore = interruptionStore)

        val interrupted = recreated.repository.state.value as MiningRunState.Failed
        assertEquals(RUN_ID, interrupted.runId)
        assertEquals("Background mining stopped unexpectedly", interrupted.failure.message)

        runBlocking { activeHarness.repository.cancel(curating.request.runId) }
        activeHarness.bridge.allowTerminal.countDown()
        awaitState(activeHarness.repository, MiningRunState::isTerminal)
    }

    @Test
    fun `failed video interruption cleanup is retried by reset`() {
        val interruptionStore = FakeMiningRunInterruptionStore(failCompletions = 1)
        val harness = harness(interruptionStore = interruptionStore)

        runBlocking { harness.repository.startVideo(INPUT) }
        val curating =
            awaitState(harness.repository) { it is MiningRunState.Curating } as
                MiningRunState.Curating
        runBlocking { harness.repository.cancel(curating.request.runId) }
        harness.bridge.allowTerminal.countDown()

        assertTrue(awaitState(harness.repository, MiningRunState::isTerminal) is MiningRunState.Failed)
        assertTrue(interruptionStore.hasBlockedRecord())
        runBlocking { harness.repository.reset() }
        assertTrue(harness.repository.state.value is MiningRunState.Idle)
        assertFalse(interruptionStore.hasBlockedRecord())
    }

    @Test
    fun `reset clears an unrecognized interruption record`() {
        val interruptionStore = FakeMiningRunInterruptionStore(invalidRecord = true)
        val harness = harness(interruptionStore = interruptionStore)

        assertTrue(harness.repository.state.value is MiningRunState.Failed)
        runBlocking { harness.repository.reset() }

        assertTrue(harness.repository.state.value is MiningRunState.Idle)
        assertFalse(interruptionStore.hasBlockedRecord())
    }

    @Test
    fun `video reset clears an interrupted reading run`() {
        val interruptionStore = FakeMiningRunInterruptionStore(durableRecord = READING_INTERRUPTION)
        val harness = harness(interruptionStore = interruptionStore)

        val interrupted = harness.repository.state.value as MiningRunState.Failed
        assertEquals("Background mining stopped unexpectedly", interrupted.failure.message)
        // The other lane's run id has no meaning on this screen.
        assertNull(interrupted.runId)

        runBlocking { harness.repository.reset() }
        assertTrue(harness.repository.state.value is MiningRunState.Idle)
        assertFalse(interruptionStore.hasBlockedRecord())

        runVideoToTerminal(harness)
    }

    @Test
    fun `both lanes constructed before a cross-lane clear can still start`() {
        val interruptionStore = FakeMiningRunInterruptionStore(durableRecord = READING_INTERRUPTION)
        val first = harness(interruptionStore = interruptionStore)
        val second = harness(interruptionStore = interruptionStore)

        runBlocking { first.repository.reset() }
        assertFalse(interruptionStore.hasBlockedRecord())

        // The second repository cached the record the first one already cleared.
        runBlocking { second.repository.reset() }
        assertTrue(second.repository.state.value is MiningRunState.Idle)
        runVideoToTerminal(second)
    }

    @Test
    fun `reset succeeds without removing the record of a run started since`() {
        val interruptionStore = FakeMiningRunInterruptionStore(durableRecord = READING_INTERRUPTION)
        val first = harness(interruptionStore = interruptionStore)
        val second = harness(interruptionStore = interruptionStore)

        runBlocking { first.repository.reset() }
        runBlocking { first.repository.startVideo(INPUT) }
        val curating =
            awaitState(first.repository) { it is MiningRunState.Curating } as
                MiningRunState.Curating

        // The record `second` cached is gone, replaced by the live run's own.
        runBlocking { second.repository.reset() }
        assertTrue(second.repository.state.value is MiningRunState.Idle)
        assertTrue(interruptionStore.hasBlockedRecord())

        runBlocking { first.repository.cancel(curating.request.runId) }
        first.bridge.allowTerminal.countDown()
        awaitState(first.repository, MiningRunState::isTerminal)
        assertFalse(interruptionStore.hasBlockedRecord())
    }

    @Test
    fun `a lane constructed after a cross-lane clear starts idle`() {
        val interruptionStore = FakeMiningRunInterruptionStore(durableRecord = READING_INTERRUPTION)
        val first = harness(interruptionStore = interruptionStore)
        runBlocking { first.repository.reset() }

        val second = harness(interruptionStore = interruptionStore)
        assertTrue(second.repository.state.value is MiningRunState.Idle)
        runVideoToTerminal(second)
    }

    @Test
    fun `an interrupted video run blocks a new video run until reset`() {
        val interruptionStore = FakeMiningRunInterruptionStore(durableRecord = VIDEO_INTERRUPTION)
        val harness = harness(interruptionStore = interruptionStore)

        val interrupted = harness.repository.state.value as MiningRunState.Failed
        assertEquals(VIDEO_INTERRUPTION.runId, interrupted.runId)
        assertThrows(MiningCommandException::class.java) {
            runBlocking { harness.repository.startVideo(INPUT) }
        }
        assertTrue(interruptionStore.hasBlockedRecord())

        runBlocking { harness.repository.reset() }
        assertFalse(interruptionStore.hasBlockedRecord())
        runVideoToTerminal(harness)
    }

    @Test
    fun `cancelling pending foreground start does not wait for control timeout`() {
        val harness = harness(pendingForegroundStart = true)

        runBlocking { harness.repository.startVideo(INPUT) }
        assertTrue(harness.foreground.started.await(2, TimeUnit.SECONDS))
        val token =
            requireNotNull((harness.repository.state.value as MiningRunState.Starting).cancellationToken)

        runBlocking { harness.repository.cancel(token) }

        assertTrue(harness.foreground.future.isCancelled)
        assertTrue(awaitState(harness.repository, MiningRunState::isTerminal) is MiningRunState.Cancelled)
        assertEquals(0, harness.inputOwner.openCount.get())
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
    fun `empty single-page selection keeps preparation foreground through completion`() {
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
        assertEquals(1, harness.foreground.startCount.get())
        assertEquals(emptyList<CurationSelection>(), harness.bridge.selection)
        harness.bridge.allowTerminal.countDown()

        assertTrue(awaitState(harness.repository, MiningRunState::isTerminal) is MiningRunState.Success)
        assertEquals(1, harness.foreground.lease.closeCount.get())
    }

    @Test
    fun `cancelling parked curation closes preparation foreground and stays cancelled`() {
        val harness = harness()

        runBlocking { harness.repository.startVideo(INPUT) }
        val curating = awaitState(harness.repository) { it is MiningRunState.Curating } as MiningRunState.Curating
        runBlocking { harness.repository.cancel(curating.request.runId) }

        assertTrue(harness.bridge.cancellationSubmitted.await(2, TimeUnit.SECONDS))
        assertEquals(1, harness.foreground.startCount.get())
        assertTrue((harness.repository.state.value as MiningRunState.Curating).cancellationPending)
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
        assertEquals(1, harness.foreground.startCount.get())
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
        assertEquals(1, harness.foreground.startCount.get())

        runBlocking { harness.repository.cancel(second.request.runId) }
        assertTrue(harness.bridge.cancellationSubmitted.await(2, TimeUnit.SECONDS))
        harness.bridge.allowTerminal.countDown()
        assertTrue(awaitState(harness.repository, MiningRunState::isTerminal) is MiningRunState.Cancelled)
    }

    @Test
    fun `per-run subtitle offset override replaces the resolved snapshot value`() {
        val harness =
            harness(
                configSnapshotResolver =
                    MiningConfigSnapshotResolver {
                        MiningConfigSnapshot(
                            mapOf("subtitle_offset" to BridgeJsonValue.Decimal(-2.0)),
                            false,
                        )
                    },
            )

        runBlocking { harness.repository.startVideo(INPUT.copy(subtitleOffsetOverride = 1.5)) }
        val curating = awaitState(harness.repository) { it is MiningRunState.Curating } as MiningRunState.Curating

        assertEquals(
            BridgeJsonValue.Decimal(1.5),
            harness.bridge.videoRequest.get()!!.configSnapshot.settings["subtitle_offset"],
        )

        runBlocking { harness.repository.cancel(curating.request.runId) }
        assertTrue(harness.bridge.cancellationSubmitted.await(2, TimeUnit.SECONDS))
        harness.bridge.allowTerminal.countDown()
        awaitState(harness.repository, MiningRunState::isTerminal)
    }

    @Test
    fun `null subtitle offset override keeps the resolved snapshot untouched`() {
        val harness =
            harness(
                configSnapshotResolver =
                    MiningConfigSnapshotResolver {
                        MiningConfigSnapshot(
                            mapOf("subtitle_offset" to BridgeJsonValue.Decimal(-2.0)),
                            false,
                        )
                    },
            )

        runBlocking { harness.repository.startVideo(INPUT) }
        val curating = awaitState(harness.repository) { it is MiningRunState.Curating } as MiningRunState.Curating

        assertEquals(
            BridgeJsonValue.Decimal(-2.0),
            harness.bridge.videoRequest.get()!!.configSnapshot.settings["subtitle_offset"],
        )

        runBlocking { harness.repository.cancel(curating.request.runId) }
        assertTrue(harness.bridge.cancellationSubmitted.await(2, TimeUnit.SECONDS))
        harness.bridge.allowTerminal.countDown()
        awaitState(harness.repository, MiningRunState::isTerminal)
    }

    @Test
    fun `curating publishes the staged media paths and keeps them across pages`() {
        val harness = harness(pagedCuration = true)

        runBlocking { harness.repository.startVideo(INPUT) }
        val first = awaitState(harness.repository) {
            (it as? MiningRunState.Curating)?.request?.page?.pageIndex == 0L
        } as MiningRunState.Curating
        assertEquals(CurationMediaBinding("/tmp/video.mkv", "/tmp/subtitle.srt"), first.media)

        runBlocking {
            harness.repository.confirmCuration(
                first.request.runId,
                first.request.requestId,
                emptyList(),
                pageIndex = 0,
            )
        }
        // Whichever Curating state is current now — the transient pageSubmissionPending
        // one from confirmCuration or the already-accepted next page — must still carry
        // the binding; the pending state is constructed fresh, not copied.
        val afterConfirm = harness.repository.state.value as MiningRunState.Curating
        assertEquals(first.media, afterConfirm.media)
        val second = awaitState(harness.repository) {
            (it as? MiningRunState.Curating)?.request?.page?.pageIndex == 1L
        } as MiningRunState.Curating
        assertEquals(first.media, second.media)

        runBlocking { harness.repository.cancel(second.request.runId) }
        assertTrue(harness.bridge.cancellationSubmitted.await(2, TimeUnit.SECONDS))
        harness.bridge.allowTerminal.countDown()
        awaitState(harness.repository, MiningRunState::isTerminal)
    }

    @Test
    fun `foreground promotion execution failure keeps its cause and distinct fault`() {
        val (harness, failed) = foregroundFailure(ForegroundStartFailure.EXECUTION)

        assertEquals("Background mining did not start safely", failed.failure.message)
        assertFalse(failed.failure.retryable)
        assertEquals("foreground_start_failed", failed.failure.diagnostic)
        val record = recorded.records.single { it.contains("op=foreground.start") }
        assertTrue(record, record.contains("java.util.concurrent.ExecutionException"))
        assertTrue(record, record.contains("Caused by: java.lang.IllegalStateException: test promotion failure"))
    }

    @Test
    fun `foreground promotion timeout has a distinct fault`() {
        val (_, failed) = foregroundFailure(ForegroundStartFailure.TIMEOUT)

        assertEquals("Background mining did not start safely", failed.failure.message)
        assertFalse(failed.failure.retryable)
        assertEquals("foreground_start_timeout", failed.failure.diagnostic)
        val record = recorded.records.single { it.contains("op=foreground.start") }
        assertTrue(record, record.contains("java.util.concurrent.TimeoutException: test promotion timeout"))
    }

    @Test
    fun `foreground promotion interruption restores the thread interrupt`() {
        val (harness, failed) = foregroundFailure(ForegroundStartFailure.INTERRUPTION)

        assertEquals("Background mining did not start safely", failed.failure.message)
        assertFalse(failed.failure.retryable)
        assertEquals("foreground_start_interrupted", failed.failure.diagnostic)
        assertTrue(harness.foreground.cancelObservedInterrupt.get())
        val record = recorded.records.single { it.contains("op=foreground.start") }
        assertTrue(record, record.contains("java.lang.InterruptedException: test promotion interruption"))
    }

    @Test
    fun `abandoned foreground start ends the run without propagating or logging an error`() {
        val harness = harness(foregroundFailure = ForegroundStartFailure.ABANDONED)

        runBlocking { harness.repository.startVideo(INPUT) }

        assertTrue(awaitState(harness.repository, MiningRunState::isTerminal) is MiningRunState.Cancelled)
        assertFalse(recorded.records.any { it.substringBefore('\n').contains(" E run=") })
    }

    @Test
    fun `foreground ownership failure stops before Python dispatch`() {
        val harness = harness(foregroundFailure = ForegroundStartFailure.EXECUTION)

        runBlocking { harness.repository.startVideo(INPUT) }
        val failed = awaitState(harness.repository, MiningRunState::isTerminal) as MiningRunState.Failed

        assertEquals("Background mining did not start safely", failed.failure.message)
        assertFalse(failed.failure.retryable)
        assertEquals(0, harness.bridge.videoRuns.get())
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
                foregroundStarter = FakeForegroundStarter(failure = null),
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
                foregroundStarter = FakeForegroundStarter(failure = null),
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
    fun `terminal callback atomically closes video cancellation admission`() {
        val harness = harness(pauseAfterTerminalCallback = true)

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
        harness.bridge.allowTerminal.countDown()
        assertTrue(harness.bridge.terminalCallbackDelivered.await(2, TimeUnit.SECONDS))

        var rejected: RuntimeException? = null
        try {
            runBlocking { harness.repository.cancel(curating.request.runId) }
        } catch (failure: RuntimeException) {
            rejected = failure
        }
        harness.bridge.allowDispatchReturn.countDown()

        assertTrue(rejected is MiningCommandException)
        assertTrue(awaitState(harness.repository, MiningRunState::isTerminal) is MiningRunState.Success)
        assertEquals(0, harness.bridge.cancellationAttempts.get())
    }

    @Test
    fun `failed video cancellation dispatch retries and releases parked run`() {
        val harness = harness(cancelFailuresBeforeSuccess = 1)

        runBlocking { harness.repository.startVideo(INPUT) }
        val curating =
            awaitState(harness.repository) { it is MiningRunState.Curating } as MiningRunState.Curating
        runBlocking { harness.repository.cancel(curating.request.runId) }

        assertTrue(harness.bridge.cancellationSubmitted.await(2, TimeUnit.SECONDS))
        assertEquals(2, harness.bridge.cancellationAttempts.get())
        harness.bridge.allowTerminal.countDown()
        assertTrue(awaitState(harness.repository, MiningRunState::isTerminal) is MiningRunState.Cancelled)
    }

    @Test
    fun `local video cancellation releases curation after all dispatches fail`() {
        val harness = harness(cancelFailuresBeforeSuccess = Int.MAX_VALUE)

        runBlocking { harness.repository.startVideo(INPUT) }
        val curating =
            awaitState(harness.repository) { it is MiningRunState.Curating } as MiningRunState.Curating
        runBlocking { harness.repository.cancel(curating.request.runId) }

        assertTrue(harness.bridge.localCancellationObserved.await(2, TimeUnit.SECONDS))
        assertTrue(waitUntil(2, TimeUnit.SECONDS) { harness.bridge.cancellationAttempts.get() == 2 })
        assertEquals(2, harness.bridge.cancellationAttempts.get())
        harness.bridge.allowTerminal.countDown()

        assertTrue(awaitState(harness.repository, MiningRunState::isTerminal) is MiningRunState.Cancelled)
        assertEquals(1, harness.inputOwner.closeCount.get())
        assertEquals(1, harness.foreground.lease.closeCount.get())
    }

    @Test
    fun `rejected video control task falls back to cancellation worker`() {
        val harness = harness(rejectControlTasks = true)

        runBlocking { harness.repository.startVideo(INPUT) }
        val curating =
            awaitState(harness.repository) { it is MiningRunState.Curating } as MiningRunState.Curating
        runBlocking { harness.repository.cancel(curating.request.runId) }

        assertTrue(harness.bridge.cancellationSubmitted.await(2, TimeUnit.SECONDS))
        harness.bridge.allowTerminal.countDown()
        assertTrue(awaitState(harness.repository, MiningRunState::isTerminal) is MiningRunState.Cancelled)
    }

    @Test
    fun `late video cancellation no-active acknowledgement cannot replace success`() {
        val harness = harness(pauseCancellationUntilTerminal = true)

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
        runBlocking { harness.repository.cancel(curating.request.runId) }
        assertTrue(harness.bridge.cancellationDispatchReached.await(2, TimeUnit.SECONDS))

        harness.bridge.allowTerminal.countDown()

        assertTrue(awaitState(harness.repository, MiningRunState::isTerminal) is MiningRunState.Success)
        assertEquals(1, harness.bridge.cancellationAttempts.get())
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
    fun `engine no-definition warning reaches the result restated`() {
        val harness = harness(presenterWarning = NO_DEFINITION_WARNING)

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
        assertEquals(
            listOf("No dictionary entry for 2 word(s), so no card was made: 本好き, 編み"),
            success.result.errors,
        )
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
        foregroundFailure: ForegroundStartFailure? = null,
        mismatchedTerminal: Boolean = false,
        fallbackState: ReleaseState = ReleaseState.ABSENT,
        releases: MutableList<String> = Collections.synchronizedList(mutableListOf()),
        blockRegistration: Boolean = false,
        pagedCuration: Boolean = false,
        presenterWarning: String? = null,
        terminalErrorCount: Int = 0,
        videoRunFailure: RuntimeException? = null,
        raisedFailure: Boolean = false,
        ankiFailure: RuntimeException? = null,
        pendingForegroundStart: Boolean = false,
        pauseAfterTerminalCallback: Boolean = false,
        cancelFailuresBeforeSuccess: Int = 0,
        pauseCancellationUntilTerminal: Boolean = false,
        rejectControlTasks: Boolean = false,
        interruptionStore: MiningRunInterruptionStore = NoOpMiningRunInterruptionStore,
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
        copyProgress: List<SafCopyProgress> = emptyList(),
    ): Harness {
        val runExecutor = Executors.newSingleThreadExecutor().also(executors::add)
        val controlExecutor = Executors.newSingleThreadExecutor().also(executors::add)
        val controlFailure = AtomicReference<Throwable?>()
        val controlTaskCompleted = CountDownLatch(1)
        val bridge =
            FakePyBridge(
                mismatchedTerminal = mismatchedTerminal,
                blockRegistration = blockRegistration,
                pagedCuration = pagedCuration,
                presenterWarning = presenterWarning,
                terminalErrorCount = terminalErrorCount,
                videoRunFailure = videoRunFailure,
                raisedFailure = raisedFailure,
                pauseAfterTerminalCallback = pauseAfterTerminalCallback,
                cancelFailuresBeforeSuccess = cancelFailuresBeforeSuccess,
                pauseCancellationUntilTerminal = pauseCancellationUntilTerminal,
            )
        val anki = FakeAnkiCallbacks(fallbackState, ankiFailure)
        val foreground = FakeForegroundStarter(foregroundFailure, pendingForegroundStart)
        val inputOwner = FakeInputOwner(foreground.startCount, copyProgress)
        val repository =
            BridgeMiningRepository(
                pyBridge = bridge,
                anki = anki,
                inputOwnerFactory =
                    object : MiningInputOwnerFactory {
                        override fun create(): MiningInputOwner = inputOwner

                        override fun create(
                            cancellation: AnkiCancellation,
                            progressListener: SafCopyProgressListener,
                        ): MiningInputOwner {
                            inputOwner.progressListener = progressListener
                            return inputOwner
                        }
                    },
                tokenizerResourceProvider = tokenizerResourceProvider,
                runtimePaths = MiningRuntimePaths(File("/tmp/cache"), File("/tmp/native")),
                sourceGrantReleaser = SourceGrantReleaser(releases::add),
                foregroundStarter = foreground,
                runExecutor = runExecutor.asMiningTaskExecutor(),
                controlExecutor =
                    if (rejectControlTasks) {
                        MiningTaskExecutor { throw IllegalStateException("test control rejection") }
                    } else {
                        MiningTaskExecutor { task ->
                            controlExecutor.execute {
                                try {
                                    task()
                                } catch (failure: Throwable) {
                                    controlFailure.compareAndSet(null, failure)
                                } finally {
                                    controlTaskCompleted.countDown()
                                }
                            }
                        }
                    },
                runtimeWorkCoordinator = runtimeWorkCoordinator,
                configSnapshotResolver = configSnapshotResolver,
                strings = testStringResourceResolver,
                foregroundStartTimeoutSeconds = 2,
                interruptionStore = interruptionStore,
            )
        return Harness(
            repository,
            bridge,
            anki,
            inputOwner,
            foreground,
            controlFailure,
            controlTaskCompleted,
        )
    }

    private fun foregroundFailure(
        failure: ForegroundStartFailure,
    ): Pair<Harness, MiningRunState.Failed> {
        val harness = harness(foregroundFailure = failure)
        runBlocking { harness.repository.startVideo(INPUT) }
        val failed =
            awaitState(harness.repository, MiningRunState::isTerminal) as MiningRunState.Failed
        return harness to failed
    }

    /** Record heads from `c=` onward: the timestamp, level and run id are not what these assert. */
    private fun recordsFor(op: String): List<String> =
        recorded.records
            .map { it.substringBefore('\n') }
            .filter { it.contains(op) }
            .map { it.substring(it.indexOf("c=")) }

    /** Start a run and cancel it out again, so the lane is proven usable and leaves no record. */
    private fun runVideoToTerminal(harness: Harness) {
        runBlocking { harness.repository.startVideo(INPUT) }
        val curating =
            awaitState(harness.repository) { it is MiningRunState.Curating } as
                MiningRunState.Curating
        runBlocking { harness.repository.cancel(curating.request.runId) }
        harness.bridge.allowTerminal.countDown()
        awaitState(harness.repository, MiningRunState::isTerminal)
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
        val controlFailure: AtomicReference<Throwable?>,
        val controlTaskCompleted: CountDownLatch,
    )

    private class FakeInputOwner(
        private val foregroundStarts: AtomicInteger? = null,
        private val copyProgress: List<SafCopyProgress> = emptyList(),
    ) : MiningInputOwner {
        val closeCount = AtomicInteger()
        val openCount = AtomicInteger()
        val foregroundStartsAtOpen = AtomicInteger(-1)
        var progressListener: SafCopyProgressListener = SafCopyProgressListener.NONE

        override fun openVideo(source: MiningSource): String {
            openCount.incrementAndGet()
            foregroundStartsAtOpen.set(foregroundStarts?.get() ?: -1)
            copyProgress.forEach(progressListener::onProgress)
            return "/tmp/video.mkv"
        }

        override fun materializeSubtitle(source: MiningSource): String = "/tmp/subtitle.srt"

        override fun close() {
            closeCount.incrementAndGet()
        }
    }

    private class FakeAnkiCallbacks(
        private val fallbackState: ReleaseState,
        private val failure: RuntimeException? = null,
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

        override fun verifyTarget(rawRequest: String): String = throw (failure ?: error("not called"))

        override fun scanFirstFields(rawRequest: String): String = error("not called")

        override fun storeMedia(rawRequest: String): String = error("not called")

        override fun createNotes(rawRequest: String): String = error("not called")

        override fun releaseRunState(rawRequest: String): String = error("not called")

        override fun releaseRunStateFallback(runId: String): ReleaseState {
            fallbackRuns += runId
            return fallbackState
        }
    }

    private enum class ForegroundStartFailure {
        ABANDONED,
        EXECUTION,
        TIMEOUT,
        INTERRUPTION,
    }

    private class FakeForegroundStarter(
        private val failure: ForegroundStartFailure?,
        private val pending: Boolean = false,
    ) : MiningForegroundStarter {
        val startCount = AtomicInteger()
        val lease = FakeForegroundLease()
        val started = CountDownLatch(1)
        val future = CompletableFuture<MiningForegroundLease>()
        val cancelObservedInterrupt = AtomicBoolean()

        override fun startSession(
            runId: String,
            generation: Long,
            listener: MiningForegroundSessionListener,
        ): CompletableFuture<MiningForegroundLease> {
            startCount.incrementAndGet()
            started.countDown()
            when (failure) {
                ForegroundStartFailure.ABANDONED -> {
                    val identity =
                        MiningForegroundSessionIdentity(
                            runId,
                            generation,
                            "00000000-0000-0000-0000-000000000001",
                        )
                    val registry =
                        ForegroundSessionRegistry(
                            java.util.concurrent.Executor { command -> command.run() },
                        )
                    val registration = registry.register(identity, listener)
                    check(registry.cancelAbandonedStart(identity))
                    @Suppress("UNCHECKED_CAST")
                    return registration.started as CompletableFuture<MiningForegroundLease>
                }

                ForegroundStartFailure.EXECUTION -> {
                    return CompletableFuture<MiningForegroundLease>().also {
                        it.completeExceptionally(IllegalStateException("test promotion failure"))
                    }
                }

                ForegroundStartFailure.TIMEOUT ->
                    return ThrowingForegroundFuture(
                        java.util.concurrent.TimeoutException("test promotion timeout"),
                        cancelObservedInterrupt,
                    )

                ForegroundStartFailure.INTERRUPTION ->
                    return ThrowingForegroundFuture(
                        InterruptedException("test promotion interruption"),
                        cancelObservedInterrupt,
                    )

                null -> Unit
            }
            lease.sessionIdentity =
                MiningForegroundSessionIdentity(
                    runId,
                    generation,
                    "00000000-0000-0000-0000-000000000001",
                )
            if (pending) return future
            return CompletableFuture.completedFuture(lease)
        }
    }

    private class ThrowingForegroundFuture(
        private val failure: Exception,
        private val cancelObservedInterrupt: AtomicBoolean,
    ) : CompletableFuture<MiningForegroundLease>() {
        override fun get(
            timeout: Long,
            unit: TimeUnit,
        ): MiningForegroundLease = throw failure

        override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
            cancelObservedInterrupt.set(Thread.currentThread().isInterrupted)
            return super.cancel(mayInterruptIfRunning)
        }
    }

    private class FakeForegroundLease : MiningForegroundLease {
        lateinit var sessionIdentity: MiningForegroundSessionIdentity
        override val identity: MiningForegroundSessionIdentity
            get() = sessionIdentity
        val closeCount = AtomicInteger()
        val published = CopyOnWriteArrayList<MiningForegroundProgress>()

        /** True for a park, false for a resume, in call order. */
        val cpuWakeEvents = CopyOnWriteArrayList<Boolean>()

        /** Runs on the engine thread with the park still in flight. */
        @Volatile
        var onPark: (() -> Unit)? = null

        override fun updateProgress(progress: MiningForegroundProgress): Boolean {
            published += progress
            return true
        }

        override fun parkCpuWake(): Boolean {
            onPark?.invoke()
            cpuWakeEvents += true
            return true
        }

        override fun resumeCpuWake(): Boolean {
            cpuWakeEvents += false
            return true
        }

        override fun close() {
            closeCount.incrementAndGet()
        }
    }

    private class FakeMiningRunInterruptionStore(
        private var failCompletions: Int = 0,
        private var invalidRecord: Boolean = false,
        durableRecord: InterruptedMiningRun? = null,
    ) : MiningRunInterruptionStore {
        private var current: InterruptedMiningRun? = durableRecord
        private var startup: StartupInterruption = sample()

        fun hasBlockedRecord(): Boolean = current != null || invalidRecord

        /** Re-sample the durable state the way a fresh process would. */
        fun simulateProcessRestart() {
            startup = sample()
        }

        override fun startupInterruption(): StartupInterruption = startup

        override fun clearUnrecognizedRecord(): Boolean {
            if (current != null) return true
            invalidRecord = false
            startup = StartupInterruption.None
            return true
        }

        override fun begin(
            kind: MiningRunKind,
            ownerId: String,
        ): Boolean {
            if (hasBlockedRecord()) return false
            current = InterruptedMiningRun(kind, ownerId, runId = null)
            startup = StartupInterruption.None
            return true
        }

        override fun registered(
            kind: MiningRunKind,
            ownerId: String,
            runId: String,
        ): Boolean {
            if (current != InterruptedMiningRun(kind, ownerId, runId = null)) return false
            current = InterruptedMiningRun(kind, ownerId, runId)
            return true
        }

        override fun complete(
            kind: MiningRunKind,
            ownerId: String,
        ): Boolean {
            if (failCompletions > 0) {
                failCompletions -= 1
                return false
            }
            val active = current
            if (active == null && invalidRecord) return false
            if (active != null && (active.kind == kind && active.ownerId == ownerId)) {
                current = null
            }
            // Anything else is already gone: the other lane cleared it, or a later run replaced it.
            startup = StartupInterruption.None
            return true
        }

        private fun sample(): StartupInterruption {
            val record = current
            return when {
                record != null -> StartupInterruption.Interrupted(record)
                invalidRecord -> StartupInterruption.Unrecognized
                else -> StartupInterruption.None
            }
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
        private val pauseAfterTerminalCallback: Boolean = false,
        private val cancelFailuresBeforeSuccess: Int = 0,
        private val pauseCancellationUntilTerminal: Boolean = false,
    ) : PyBridge {
        val videoRuns = AtomicInteger()
        val videoRequest = AtomicReference<VideoMiningWireRequest?>()
        val curationSubmitted = CountDownLatch(1)
        val intermediateCurationSubmitted = CountDownLatch(1)
        val cancellationSubmitted = CountDownLatch(1)
        val allowTerminal = CountDownLatch(1)
        val registrationReached = CountDownLatch(1)
        val allowRegistration = CountDownLatch(if (blockRegistration) 1 else 0)
        val terminalCallbackDelivered = CountDownLatch(if (pauseAfterTerminalCallback) 1 else 0)
        val allowDispatchReturn = CountDownLatch(if (pauseAfterTerminalCallback) 1 else 0)
        val cancellationDispatchReached =
            CountDownLatch(if (pauseCancellationUntilTerminal) 1 else 0)
        val localCancellationObserved = CountDownLatch(1)
        val cancellationAttempts = AtomicInteger()
        private val terminalDelivered = AtomicBoolean()
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
                    val attempt = cancellationAttempts.incrementAndGet()
                    cancellationDispatchReached.countDown()
                    if (pauseCancellationUntilTerminal) {
                        check(waitUntil(3, TimeUnit.SECONDS) { terminalDelivered.get() })
                    }
                    if (attempt <= cancelFailuresBeforeSuccess) {
                        throw IllegalStateException("test cancellation transport failure")
                    }
                    if (terminalDelivered.get()) {
                        NO_ACTIVE_JOB
                    } else {
                        cancelled.set(true)
                        cancellationSubmitted.countDown()
                        JOB_CANCELLED
                    }
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
                if (callbacks.cancellationRequested()) {
                    cancelled.set(true)
                    localCancellationObserved.countDown()
                    break
                }
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
            terminalDelivered.set(true)
            terminalCallbackDelivered.countDown()
            check(allowDispatchReturn.await(3, TimeUnit.SECONDS))
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
        const val NO_DEFINITION_WARNING = "Skipped 2 words with no definition found: 本好き, 編み"
        const val PRESENTER_WARNING_PLACEHOLDER = "__WARNING__"
        val FIRST_SELECTION = listOf(CurationSelection(CANDIDATE_ID, SENTENCE_ID))

        /** A crash in the reading lane leaves this behind for the video lane to find. */
        val READING_INTERRUPTION =
            InterruptedMiningRun(
                MiningRunKind.READING,
                ownerId = "cancel_11111111111111111111111111111111",
                runId = "run_22222222222222222222222222222222",
            )
        val VIDEO_INTERRUPTION =
            InterruptedMiningRun(
                MiningRunKind.VIDEO,
                ownerId = "cancel_33333333333333333333333333333333",
                runId = "run_44444444444444444444444444444444",
            )
        val INPUT =
            VideoMiningInput(
                video = MiningSource("content://test/video", "episode.mkv"),
                subtitle = MiningSource("content://test/subtitle", "episode.srt"),
            )
        val JOB_REGISTRATION =
            """{"schemaVersion":1,"type":"job.registration.request","payload":{"runId":"$RUN_ID"}}"""
        val PROGRESS_START =
            """{"schemaVersion":1,"type":"progress.start","payload":{"runId":"$RUN_ID","total":3,"description":"Preparing curation"}}"""
        val PROGRESS_STAGE =
            """{"schemaVersion":1,"type":"progress.stage","payload":{"runId":"$RUN_ID","index":2,"total":5,"name":"Extracting media"}}"""
        const val MINED_TERM = "猫"
        const val ANKI_VERIFY_REQUEST =
            """{"schemaVersion":1,"type":"anki.verify.request","payload":{}}"""

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
        val NO_ACTIVE_JOB =
            """{"schemaVersion":1,"type":"bridge.error","payload":{"code":"no_active_job","message":"There is no active Python mining job","requestType":"job.cancel"}}"""
        val SUCCESS_TERMINAL =
            """{"schemaVersion":1,"type":"mining.terminal","payload":{"runId":"$RUN_ID","outcome":"success","result":{"totalWordsFound":1,"newWordsFound":0,"cardsCreated":0,"errors":[],"elapsedTime":1.0,"comprehensionPercentage":100.0,"cardIds":[],"videoFile":"episode.mkv","subtitleFile":"episode.srt","minedForms":[],"ankiWriteState":"no_note_write","failureIsTransient":false},"error":null}}"""
        val CANCELLED_TERMINAL =
            """{"schemaVersion":1,"type":"mining.terminal","payload":{"runId":"$RUN_ID","outcome":"cancelled","result":null,"error":{"code":"cancelled","message":"Mining was cancelled"}}}"""
        const val TERMINAL_FAULT_ID = "f0123abcd"
        val RAISED_FAILURE_TERMINAL =
            """{"schemaVersion":1,"type":"mining.terminal","payload":{"runId":"$RUN_ID","outcome":"failed","result":null,"error":{"code":"engine_error","message":"Mining failed","faultId":"$TERMINAL_FAULT_ID"}}}"""
    }
}

/** Polls [predicate] until it holds or [timeout] elapses. Shared by the tests and the fakes. */
private fun waitUntil(
    timeout: Long,
    unit: TimeUnit,
    predicate: () -> Boolean,
): Boolean {
    val deadline = System.nanoTime() + unit.toNanos(timeout)
    while (System.nanoTime() < deadline) {
        if (predicate()) return true
        Thread.sleep(2)
    }
    return predicate()
}
