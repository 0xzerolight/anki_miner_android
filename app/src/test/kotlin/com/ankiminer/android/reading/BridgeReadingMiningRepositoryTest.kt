package com.ankiminer.android.reading

import com.ankiminer.android.R
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
import com.ankiminer.android.engine.ReadingMiningWireRequest
import com.ankiminer.android.engine.ReadingMiningSourceKind
import com.ankiminer.android.engine.TokenizerIdentity
import com.ankiminer.android.media.SafDocument
import com.ankiminer.android.localization.StringResourceResolver
import com.ankiminer.android.localization.testStringResourceResolver
import com.ankiminer.android.mining.CoordinatorAnkiCallbacks
import com.ankiminer.android.mining.CurationSelection
import com.ankiminer.android.mining.InstalledTokenizerResource
import com.ankiminer.android.mining.InstalledTokenizerResourceProvider
import com.ankiminer.android.mining.InterruptedMiningRun
import com.ankiminer.android.mining.MiningCommandException
import com.ankiminer.android.mining.MiningRunKind
import com.ankiminer.android.mining.MiningRunState
import com.ankiminer.android.mining.MiningRuntimePaths
import com.ankiminer.android.mining.MiningStage
import com.ankiminer.android.mining.MiningTaskExecutor
import com.ankiminer.android.mining.SourceGrantReleaser
import com.ankiminer.android.mining.StartupInterruption
import com.ankiminer.android.mining.asMiningTaskExecutor
import com.ankiminer.android.mining.isTerminal
import com.ankiminer.android.service.ForegroundSessionRegistry
import com.ankiminer.android.service.MiningForegroundLease
import com.ankiminer.android.service.MiningForegroundProgress
import com.ankiminer.android.service.MiningForegroundProgressUnit
import com.ankiminer.android.service.MiningForegroundSessionIdentity
import com.ankiminer.android.service.MiningForegroundSessionListener
import com.ankiminer.android.tts.SentenceAudioSynthesis
import com.ankiminer.android.tts.SentenceAudioSynthesizer
import com.ankiminer.android.tts.SentenceAudioSynthesizerFactory
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BridgeReadingMiningRepositoryTest {
    @get:Rule
    val temporary = TemporaryFolder()

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
        val harness = harness(expressionAudioFieldMapped = true)

        runBlocking { harness.repository.startReading(INPUT) }
        val curating =
            awaitState(harness.repository) { it is MiningRunState.Curating } as MiningRunState.Curating
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
                "c=reading op=phase from=PREPARING to=REGISTERED outcome=ok detail=registration",
                "c=reading op=phase from=REGISTERED to=CURATING outcome=ok detail=curation_needed",
                "c=reading op=phase from=CURATING to=PROMOTING outcome=ok detail=curation_final",
                "c=reading op=phase from=PROMOTING to=RUNNING outcome=ok detail=foreground_started",
                "c=reading op=phase from=RUNNING to=FINALIZING outcome=ok detail=terminal",
            ),
            recordsFor("op=phase"),
        )

        // The ambient run id, on both threads that emit these records: the run executor, which
        // registerJob installs it on, and the control executor, which has to carry it across.
        val onRunThread = recorded.records.single { it.contains("detail=terminal") }
        val onControlThread = recorded.records.single { it.contains("detail=foreground_started") }
        assertTrue(onRunThread, onRunThread.contains(" run=$RUN_ID c=reading op=phase"))
        assertTrue(onControlThread, onControlThread.contains(" run=$RUN_ID c=reading op=phase"))
        assertTrue(
            recorded.records.single { it.contains("op=engine_stage") }
                .contains("outcome=ok"),
        )
    }

    @Test
    fun `stage start and update retain the whole run progress band`() {
        val harness = harness()

        runBlocking { harness.repository.startReading(INPUT) }
        val curating =
            awaitState(harness.repository) { it is MiningRunState.Curating } as MiningRunState.Curating
        harness.bridge.runCallbacks!!.onProgress(HOSTILE_PROGRESS)
        runBlocking {
            harness.repository.confirmCuration(
                curating.request.runId,
                curating.request.requestId,
                FIRST_SELECTION,
            )
        }
        awaitState(harness.repository) { it is MiningRunState.Running }

        val callbacks = requireNotNull(harness.bridge.runCallbacks)
        callbacks.onStage(PROGRESS_STAGE)
        val entered = harness.repository.state.value as MiningRunState.Running
        assertEquals(0L, entered.progress.current)
        assertEquals(0L, entered.progress.total)
        assertEquals(MiningStage(2, 5, "Extracting media"), entered.progress.stage)
        assertEquals(0.2f, entered.progress.fraction)

        callbacks.onStart(PROGRESS_STAGE_START)
        val started = harness.repository.state.value as MiningRunState.Running
        assertEquals(MiningStage(2, 5, "Extracting media"), started.progress.stage)
        assertEquals(0.2f, started.progress.fraction)

        callbacks.onProgress(PROGRESS_STAGE_UPDATE)
        val updated = harness.repository.state.value as MiningRunState.Running
        assertEquals(MiningStage(2, 5, "Extracting media"), updated.progress.stage)
        assertEquals(0.3f, updated.progress.fraction)

        harness.bridge.allowTerminal.countDown()
        awaitState(harness.repository, MiningRunState::isTerminal)
    }

    @Test
    fun `the terminal mapping is logged with its outcome, code and notice count`() {
        val harness = harness(expressionAudioFieldMapped = true, raisedFailure = true)

        runBlocking { harness.repository.startReading(INPUT) }
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
        harness.bridge.allowTerminal.countDown()
        awaitState(harness.repository, MiningRunState::isTerminal)

        assertEquals(
            listOf("c=reading op=run.terminal outcome=fail code=engine_error retryable=true notices=0"),
            recordsFor("op=run.terminal"),
        )
    }

    @Test
    fun `a protocol violation names the callback that raised it`() {
        val harness = harness(expressionAudioFieldMapped = true)

        runBlocking { harness.repository.startReading(INPUT) }
        awaitState(harness.repository) { it is MiningRunState.Curating }

        // A progress.start envelope arriving on onStage: one of the ~24 messages that all resolve to
        // the same user string, and the callback name is the only thing that separates them.
        assertThrows(IllegalStateException::class.java) {
            harness.bridge.runCallbacks!!.onStage(PROGRESS_START)
        }
        harness.bridge.allowTerminal.countDown()
        awaitState(harness.repository, MiningRunState::isTerminal)

        val record = recorded.records.single { it.contains("op=onStage") }
        assertTrue(record, record.contains(" E run=- c=reading op=onStage outcome=fail"))
        assertTrue(record, record.contains("Unexpected onStage message"))
    }

    @Test
    fun `a malformed callback has one owner with its callback and category`() {
        val harness = harness(expressionAudioFieldMapped = true)

        runBlocking { harness.repository.startReading(INPUT) }
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
                " E run=- c=reading op=onStage outcome=fail " +
                    "category=UNSUPPORTED_SCHEMA_VERSION",
            ),
        )
    }

    @Test
    fun `an Anki callback failure carries the provider code and its cause`() {
        val harness =
            harness(
                expressionAudioFieldMapped = true,
                ankiFailure =
                    AnkiReadFailure(
                        AnkiErrorCode.PROVIDER_UNAVAILABLE,
                        retryable = true,
                        stableMessage = "AnkiDroid is unavailable",
                        cause = IllegalStateException("provider died"),
                    ),
            )

        runBlocking { harness.repository.startReading(INPUT) }
        awaitState(harness.repository) { it is MiningRunState.Curating }

        assertThrows(AnkiReadFailure::class.java) {
            harness.bridge.runCallbacks!!.ankiVerifyTarget(ANKI_VERIFY_REQUEST)
        }
        harness.bridge.allowTerminal.countDown()
        awaitState(harness.repository, MiningRunState::isTerminal)

        val record = recorded.records.single { it.contains("op=ankiVerifyTarget") }
        assertTrue(record, record.contains(" E run=- c=reading op=ankiVerifyTarget outcome=fail code=PROVIDER_UNAVAILABLE"))
        assertTrue(record, record.contains("Caused by: java.lang.IllegalStateException: provider died"))
    }

    @Test
    fun `foreground promotion execution failure keeps its cause and distinct fault`() {
        // The mirror of the video lane's promotion-failure test. Without it, deleting `diagnostic`
        // from this repository's toFailed goes undetected: the other two sites that mention it here
        // are the terminal arm, which builds MiningFailure directly, and an assertNull.
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
        val harness =
            harness(
                expressionAudioFieldMapped = true,
                foregroundFailure = ForegroundStartFailure.ABANDONED,
            )

        runBlocking { harness.repository.startReading(INPUT) }

        assertTrue(awaitState(harness.repository, MiningRunState::isTerminal) is MiningRunState.Cancelled)
        assertFalse(recorded.records.any { it.substringBefore('\n').contains(" E run=") })
    }

    @Test
    fun `engine progress descriptions never reach the foreground notification`() {
        val harness = harness(expressionAudioFieldMapped = true)

        runBlocking { harness.repository.startReading(INPUT) }
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
    fun `media workload keeps source and runtime lease through parked curation then starts foreground`() {
        val coordinator = RuntimeWorkCoordinator()
        val releases = Collections.synchronizedList(mutableListOf<String>())
        val harness =
            harness(
                runtimeWorkCoordinator = coordinator,
                releases = releases,
                expressionAudioFieldMapped = true,
            )

        runBlocking { harness.repository.startReading(INPUT) }
        val curating =
            awaitState(harness.repository) { it is MiningRunState.Curating } as MiningRunState.Curating
        val wire = requireNotNull(harness.bridge.readingRequest.get())
        val stagedFile = File(wire.sourcePath)

        assertTrue(stagedFile.isFile)
        assertEquals("Novel.txt", stagedFile.name)
        assertTrue(stagedFile.toPath().startsWith(harness.cacheDir.toPath().toRealPath()))
        assertNull(wire.imageArchivePath)
        assertNull(wire.seriesName)
        assertEquals(1, harness.foreground.startCount.get())
        assertNull(coordinator.tryAcquire(RuntimeWorkCoordinator.Kind.RESOURCE))
        assertTrue(harness.repository.detachActiveSources(INPUT))

        runBlocking {
            harness.repository.confirmCuration(
                curating.request.runId,
                curating.request.requestId,
                FIRST_SELECTION,
            )
        }

        assertTrue(harness.bridge.curationSubmitted.await(2, TimeUnit.SECONDS))
        assertEquals(FIRST_SELECTION, harness.bridge.selection)
        assertEquals(1, harness.foreground.startCount.get())
        assertTrue(stagedFile.isFile)
        harness.bridge.allowTerminal.countDown()

        val success = awaitState(harness.repository, MiningRunState::isTerminal)
        assertTrue(success is MiningRunState.Success)
        assertFalse(stagedFile.exists())
        assertTrue(harness.stageRoot.listFiles().orEmpty().isEmpty())
        assertEquals(1, harness.foreground.lease.closeCount.get())
        assertEquals(listOf(INPUT_DOCUMENT.uri), releases)
        assertEquals(listOf(RUN_ID), harness.anki.fallbackRuns)
        val resourceLease = coordinator.tryAcquire(RuntimeWorkCoordinator.Kind.RESOURCE)
        assertNotNull(resourceLease)
        requireNotNull(resourceLease).close()
    }

    @Test
    fun `localaudio expression audio with zero packs promotes media foreground`() {
        // The user's headline scenario: the expression_audio field is mapped and NO
        // audio packs are imported. localaudio (localhost) is injected Python-side and
        // never appears in this Kotlin snapshot, so the reading FGS must promote on the
        // mapped field alone — mirroring the engine's true fetch gate (audio_stage).
        val harness = harness(expressionAudioFieldMapped = true)

        runBlocking { harness.repository.startReading(INPUT) }
        val curating =
            awaitState(harness.repository) { it is MiningRunState.Curating } as MiningRunState.Curating
        assertEquals(1, harness.foreground.startCount.get())

        runBlocking {
            harness.repository.confirmCuration(
                curating.request.runId,
                curating.request.requestId,
                FIRST_SELECTION,
            )
        }

        assertTrue(harness.bridge.curationSubmitted.await(2, TimeUnit.SECONDS))
        assertEquals(FIRST_SELECTION, harness.bridge.selection)
        assertEquals(1, harness.foreground.startCount.get())
        harness.bridge.allowTerminal.countDown()

        assertTrue(awaitState(harness.repository, MiningRunState::isTerminal) is MiningRunState.Success)
        assertEquals(1, harness.foreground.lease.closeCount.get())
    }

    @Test
    fun `curation parks the cpu wake lease and confirming re-arms it`() {
        val harness = harness(expressionAudioFieldMapped = true)

        runBlocking { harness.repository.startReading(INPUT) }
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
        val harness = harness(expressionAudioFieldMapped = true)
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

        runBlocking { harness.repository.startReading(INPUT) }
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
        val harness = harness(expressionAudioFieldMapped = true)

        runBlocking { harness.repository.startReading(INPUT) }
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
        val harness = harness(expressionAudioFieldMapped = true)

        runBlocking { harness.repository.startReading(INPUT) }
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
        // "novel" is five staged bytes; without a unit they rendered as five items.
        assertTrue(
            published.toString(),
            MiningForegroundProgress(
                completed = 5,
                total = 5,
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
    fun `eligible foreground ownership starts before reading source staging`() {
        val harness = harness(expressionAudioFieldMapped = true)

        runBlocking { harness.repository.startReading(INPUT) }
        val curating =
            awaitState(harness.repository) { it is MiningRunState.Curating } as MiningRunState.Curating

        assertEquals(1, harness.foregroundStartsAtOpen.get())
        runBlocking { harness.repository.cancel(curating.request.runId) }
        harness.bridge.allowTerminal.countDown()
        awaitState(harness.repository, MiningRunState::isTerminal)
    }

    @Test
    fun `process recreation surfaces an interrupted reading run`() {
        val interruptionStore = FakeMiningRunInterruptionStore()
        val activeHarness = harness(interruptionStore = interruptionStore)

        runBlocking { activeHarness.repository.startReading(INPUT) }
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
    fun `failed reading interruption cleanup is retried by reset`() {
        val interruptionStore = FakeMiningRunInterruptionStore(failCompletions = 1)
        val harness = harness(interruptionStore = interruptionStore)

        runBlocking { harness.repository.startReading(INPUT) }
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
    fun `reading reset clears an interrupted video run`() {
        val interruptionStore = FakeMiningRunInterruptionStore(durableRecord = VIDEO_INTERRUPTION)
        val harness = harness(interruptionStore = interruptionStore)

        val interrupted = harness.repository.state.value as MiningRunState.Failed
        assertEquals("Background mining stopped unexpectedly", interrupted.failure.message)
        // The other lane's run id has no meaning on this screen.
        assertNull(interrupted.runId)

        runBlocking { harness.repository.reset() }
        assertTrue(harness.repository.state.value is MiningRunState.Idle)
        assertFalse(interruptionStore.hasBlockedRecord())

        runReadingToTerminal(harness)
    }

    @Test
    fun `both lanes constructed before a cross-lane clear can still start`() {
        val interruptionStore = FakeMiningRunInterruptionStore(durableRecord = VIDEO_INTERRUPTION)
        val first = harness(interruptionStore = interruptionStore)
        val second = harness(interruptionStore = interruptionStore)

        runBlocking { first.repository.reset() }
        assertFalse(interruptionStore.hasBlockedRecord())

        // The second repository cached the record the first one already cleared.
        runBlocking { second.repository.reset() }
        assertTrue(second.repository.state.value is MiningRunState.Idle)
        runReadingToTerminal(second)
    }

    @Test
    fun `reset succeeds without removing the record of a run started since`() {
        val interruptionStore = FakeMiningRunInterruptionStore(durableRecord = VIDEO_INTERRUPTION)
        val first = harness(interruptionStore = interruptionStore)
        val second = harness(interruptionStore = interruptionStore)

        runBlocking { first.repository.reset() }
        runBlocking { first.repository.startReading(INPUT) }
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
        val interruptionStore = FakeMiningRunInterruptionStore(durableRecord = VIDEO_INTERRUPTION)
        val first = harness(interruptionStore = interruptionStore)
        runBlocking { first.repository.reset() }

        val second = harness(interruptionStore = interruptionStore)
        assertTrue(second.repository.state.value is MiningRunState.Idle)
        runReadingToTerminal(second)
    }

    @Test
    fun `an interrupted reading run blocks a new reading run until reset`() {
        val interruptionStore = FakeMiningRunInterruptionStore(durableRecord = READING_INTERRUPTION)
        val harness = harness(interruptionStore = interruptionStore)

        val interrupted = harness.repository.state.value as MiningRunState.Failed
        assertEquals(READING_INTERRUPTION.runId, interrupted.runId)
        assertThrows(MiningCommandException::class.java) {
            runBlocking { harness.repository.startReading(INPUT) }
        }
        assertTrue(interruptionStore.hasBlockedRecord())

        runBlocking { harness.repository.reset() }
        assertFalse(interruptionStore.hasBlockedRecord())
        runReadingToTerminal(harness)
    }

    @Test
    fun `cancelling pending reading foreground start bypasses control timeout`() {
        val harness =
            harness(
                expressionAudioFieldMapped = true,
                pendingForegroundStart = true,
            )

        runBlocking { harness.repository.startReading(INPUT) }
        assertTrue(harness.foreground.started.await(2, TimeUnit.SECONDS))
        val token =
            requireNotNull((harness.repository.state.value as MiningRunState.Starting).cancellationToken)

        runBlocking { harness.repository.cancel(token) }

        assertTrue(harness.foreground.future.isCancelled)
        assertTrue(awaitState(harness.repository, MiningRunState::isTerminal) is MiningRunState.Cancelled)
        assertEquals(0, harness.openCount.get())
    }

    @Test
    fun `text-only reading submits selected candidates without media foreground`() {
        val harness = harness()

        runBlocking { harness.repository.startReading(INPUT) }
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
        assertEquals(FIRST_SELECTION, harness.bridge.selection)
        assertEquals(0, harness.foreground.startCount.get())
        harness.bridge.allowTerminal.countDown()

        assertTrue(awaitState(harness.repository, MiningRunState::isTerminal) is MiningRunState.Success)
        assertEquals(0, harness.foreground.lease.closeCount.get())
    }

    @Test
    fun `pasted text dispatches once without SAF or media foreground and cleans success`() {
        val releases = Collections.synchronizedList(mutableListOf<String>())
        val harness = harness(releases = releases)

        runBlocking { harness.repository.startReading(PASTED_INPUT) }
        val curating =
            awaitState(harness.repository) { it is MiningRunState.Curating } as MiningRunState.Curating
        val wire = requireNotNull(harness.bridge.readingRequest.get())
        val rawRequest = harness.bridge.readingRunRequests.single()
        val stagedFile = File(wire.sourcePath)

        assertTrue(rawRequest, rawRequest.contains("\"type\":\"mining.reading.run\""))
        assertTrue(rawRequest, rawRequest.contains("\"sourceKind\":\"text\""))
        assertFalse(rawRequest, rawRequest.contains(PASTED_TEXT_CONTENT))
        assertEquals(ReadingMiningSourceKind.TEXT, wire.sourceKind)
        assertTrue(wire.sourcePath, wire.sourcePath.endsWith("${File.separator}pasted.text"))
        assertNull(wire.seriesName)
        assertNull(wire.imageArchivePath)
        assertEquals(0, harness.openCount.get())
        assertEquals(0, harness.foreground.startCount.get())
        assertTrue(harness.repository.detachActiveSources(PASTED_INPUT))
        assertFalse(harness.repository.detachActiveSources(PASTED_INPUT))

        runBlocking {
            harness.repository.confirmCuration(
                curating.request.runId,
                curating.request.requestId,
                emptyList(),
            )
        }
        assertTrue(harness.bridge.curationSubmitted.await(2, TimeUnit.SECONDS))
        harness.bridge.allowTerminal.countDown()

        assertTrue(awaitState(harness.repository, MiningRunState::isTerminal) is MiningRunState.Success)
        assertFalse(stagedFile.exists())
        assertTrue(harness.stageRoot.listFiles().orEmpty().isEmpty())
        assertEquals(0, harness.openCount.get())
        assertTrue(releases.isEmpty())
        assertFalse(recorded.records.any { PASTED_TEXT_CONTENT in it })
    }

    @Test
    fun `pasted text failure terminal removes its private stage`() {
        val harness = harness(raisedFailure = true)

        runBlocking { harness.repository.startReading(PASTED_INPUT) }
        val curating =
            awaitState(harness.repository) { it is MiningRunState.Curating } as MiningRunState.Curating
        val stagedFile = File(requireNotNull(harness.bridge.readingRequest.get()).sourcePath)
        runBlocking {
            harness.repository.confirmCuration(
                curating.request.runId,
                curating.request.requestId,
                emptyList(),
            )
        }
        assertTrue(harness.bridge.curationSubmitted.await(2, TimeUnit.SECONDS))
        harness.bridge.allowTerminal.countDown()

        assertTrue(awaitState(harness.repository, MiningRunState::isTerminal) is MiningRunState.Failed)
        assertFalse(stagedFile.exists())
        assertTrue(harness.stageRoot.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `cancelling pasted text while staging removes its private stage`() {
        val stagingStarted = CountDownLatch(1)
        val continueStaging = CountDownLatch(1)
        val blockingStrings =
            StringResourceResolver { resourceId, formatArguments ->
                if (resourceId == R.string.reading_progress_preparing_pasted_text) {
                    stagingStarted.countDown()
                    check(continueStaging.await(2, TimeUnit.SECONDS))
                }
                testStringResourceResolver.resolve(resourceId, formatArguments)
            }
        val harness = harness(strings = blockingStrings)

        runBlocking { harness.repository.startReading(PASTED_INPUT) }
        assertTrue(stagingStarted.await(2, TimeUnit.SECONDS))
        val token =
            requireNotNull((harness.repository.state.value as MiningRunState.Starting).cancellationToken)
        assertTrue(harness.stageRoot.listFiles().orEmpty().isNotEmpty())

        runBlocking { harness.repository.cancel(token) }
        continueStaging.countDown()

        assertTrue(awaitState(harness.repository, MiningRunState::isTerminal) is MiningRunState.Cancelled)
        assertTrue(harness.stageRoot.listFiles().orEmpty().isEmpty())
        assertEquals(0, harness.openCount.get())
        assertTrue(harness.bridge.readingRunRequests.isEmpty())
    }

    @Test
    fun `pasted text with reading TTS still owns media foreground`() {
        val audio = File(temporary.root, "android_tts_v1_${"c".repeat(64)}.wav").apply {
            writeBytes(byteArrayOf(1))
        }
        val synthesizer = FakeSentenceAudioSynthesizer(audio)
        val harness =
            harness(
                ttsEnabled = true,
                invokeTts = true,
                sentenceAudioFactory = SentenceAudioSynthesizerFactory { synthesizer },
            )

        runBlocking { harness.repository.startReading(PASTED_INPUT) }
        val curating =
            awaitState(harness.repository) { it is MiningRunState.Curating } as MiningRunState.Curating
        assertEquals(1, harness.foreground.startCount.get())
        runBlocking {
            harness.repository.confirmCuration(
                curating.request.runId,
                curating.request.requestId,
                FIRST_SELECTION,
            )
        }

        assertTrue(harness.bridge.ttsSubmitted.await(2, TimeUnit.SECONDS))
        harness.bridge.allowTerminal.countDown()
        assertTrue(awaitState(harness.repository, MiningRunState::isTerminal) is MiningRunState.Success)
        assertEquals(1, harness.foreground.lease.closeCount.get())
    }

    @Test
    fun `epub selection owns foreground before curation for every outcome`() {
        val selectedHarness =
            harness(inputBytes = mapOf(EPUB_DOCUMENT.uri to "epub".toByteArray()))

        runBlocking { selectedHarness.repository.startReading(EPUB_INPUT) }
        val selectedCuration =
            awaitState(selectedHarness.repository) { it is MiningRunState.Curating } as
                MiningRunState.Curating
        assertEquals(
            ReadingMiningSourceKind.EPUB,
            requireNotNull(selectedHarness.bridge.readingRequest.get()).sourceKind,
        )
        runBlocking {
            selectedHarness.repository.confirmCuration(
                selectedCuration.request.runId,
                selectedCuration.request.requestId,
                FIRST_SELECTION,
            )
        }

        assertTrue(selectedHarness.bridge.curationSubmitted.await(2, TimeUnit.SECONDS))
        assertEquals(1, selectedHarness.foreground.startCount.get())
        selectedHarness.bridge.allowTerminal.countDown()
        assertTrue(
            awaitState(selectedHarness.repository, MiningRunState::isTerminal) is
                MiningRunState.Success,
        )

        val emptyHarness =
            harness(inputBytes = mapOf(EPUB_DOCUMENT.uri to "epub".toByteArray()))
        runBlocking { emptyHarness.repository.startReading(EPUB_INPUT) }
        val emptyCuration =
            awaitState(emptyHarness.repository) { it is MiningRunState.Curating } as
                MiningRunState.Curating
        runBlocking {
            emptyHarness.repository.confirmCuration(
                emptyCuration.request.runId,
                emptyCuration.request.requestId,
                emptyList(),
            )
        }

        assertTrue(emptyHarness.bridge.curationSubmitted.await(2, TimeUnit.SECONDS))
        assertEquals(1, emptyHarness.foreground.startCount.get())
        emptyHarness.bridge.allowTerminal.countDown()
        assertTrue(
            awaitState(emptyHarness.repository, MiningRunState::isTerminal) is
                MiningRunState.Success,
        )

        val cancelledHarness =
            harness(inputBytes = mapOf(EPUB_DOCUMENT.uri to "epub".toByteArray()))
        runBlocking { cancelledHarness.repository.startReading(EPUB_INPUT) }
        val cancelledCuration =
            awaitState(cancelledHarness.repository) { it is MiningRunState.Curating } as
                MiningRunState.Curating
        runBlocking { cancelledHarness.repository.cancel(cancelledCuration.request.runId) }
        assertTrue(cancelledHarness.bridge.cancellationSubmitted.await(2, TimeUnit.SECONDS))
        assertEquals(1, cancelledHarness.foreground.startCount.get())
        assertTrue(
            (cancelledHarness.repository.state.value as MiningRunState.Curating)
                .cancellationPending,
        )
        cancelledHarness.bridge.allowTerminal.countDown()
        assertTrue(
            awaitState(cancelledHarness.repository, MiningRunState::isTerminal) is
                MiningRunState.Cancelled,
        )
    }

    @Test
    fun `empty media-workload selection closes its preparation foreground`() {
        val harness = harness(expressionAudioFieldMapped = true)

        runBlocking { harness.repository.startReading(INPUT) }
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
        assertEquals(emptyList<CurationSelection>(), harness.bridge.selection)
        assertEquals(1, harness.foreground.startCount.get())
        harness.bridge.allowTerminal.countDown()

        assertTrue(awaitState(harness.repository, MiningRunState::isTerminal) is MiningRunState.Success)
        assertEquals(1, harness.foreground.lease.closeCount.get())
    }

    @Test
    fun `cancelling parked text reading removes the private stage without foreground`() {
        val harness = harness()

        runBlocking { harness.repository.startReading(INPUT) }
        val curating =
            awaitState(harness.repository) { it is MiningRunState.Curating } as MiningRunState.Curating
        val stagedFile = File(requireNotNull(harness.bridge.readingRequest.get()).sourcePath)
        runBlocking { harness.repository.cancel(curating.request.runId) }

        assertTrue(harness.bridge.cancellationSubmitted.await(2, TimeUnit.SECONDS))
        assertEquals(0, harness.foreground.startCount.get())
        assertTrue(harness.anki.cancellation?.isCancelled() == true)
        harness.bridge.allowTerminal.countDown()

        val cancelled = awaitState(harness.repository, MiningRunState::isTerminal)
        assertTrue(cancelled is MiningRunState.Cancelled)
        assertFalse(stagedFile.exists())
        assertTrue(harness.stageRoot.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `reading curation preserves prior-page selection for one final foreground promotion`() {
        val harness = harness(pagedCuration = true, expressionAudioFieldMapped = true)

        runBlocking { harness.repository.startReading(INPUT) }
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
        harness.bridge.allowTerminal.countDown()
        assertTrue(awaitState(harness.repository, MiningRunState::isTerminal) is MiningRunState.Success)
    }

    @Test
    fun `opaque cancellation before worker execution skips tokenizer and source staging`() {
        val queuedRun = AtomicReference<(() -> Unit)?>()
        val controlExecutor = Executors.newSingleThreadExecutor().also(executors::add)
        val openCount = AtomicInteger()
        val cache = temporary.newFolder("queued-cache")
        val stageRoot = File(cache, "reading")
        val repository =
            BridgeReadingMiningRepository(
                pyBridge = FakeReadingPyBridge(),
                anki = FakeAnkiCallbacks(),
                sourceStager = stager(stageRoot, openCount),
                tokenizerResourceProvider = InstalledTokenizerResourceProvider { error("must not inspect") },
                runtimePaths = MiningRuntimePaths(cache, temporary.newFolder("queued-native")),
                sourceGrantReleaser = SourceGrantReleaser { },
                foregroundStarter = FakeForegroundStarter(),
                runExecutor = MiningTaskExecutor { task -> queuedRun.set(task) },
                controlExecutor = controlExecutor.asMiningTaskExecutor(),
                strings = testStringResourceResolver,
            )

        runBlocking { repository.startReading(INPUT) }
        val token = requireNotNull((repository.state.value as MiningRunState.Starting).cancellationToken)
        runBlocking { repository.cancel(token) }
        requireNotNull(queuedRun.get()).invoke()

        val cancelled = repository.state.value as MiningRunState.Cancelled
        assertNull(cancelled.runId)
        assertEquals(0, openCount.get())
        assertFalse(stageRoot.exists())
    }

    @Test
    fun `subtitle request uses an explicit stable series rather than its staging directory`() {
        val subtitle =
            ReadingMiningInput(
                selection =
                    ReadingSourceSelection.Single(
                        SafDocument(
                            uri = "content://reading/subtitle",
                            displayName = "Episode.srt",
                            mimeType = "application/x-subrip",
                            sizeBytes = 4L,
                        ),
                    ),
                subtitleSeriesName = "My Series",
            )
        val harness = harness(inputBytes = mapOf("content://reading/subtitle" to "text".toByteArray()))

        runBlocking { harness.repository.startReading(subtitle) }
        val curating =
            awaitState(harness.repository) { it is MiningRunState.Curating } as MiningRunState.Curating

        assertEquals("My Series", requireNotNull(harness.bridge.readingRequest.get()).seriesName)
        assertFalse(requireNotNull(harness.bridge.readingRequest.get()).seriesName!!.contains("reading-job-"))
        runBlocking { harness.repository.cancel(curating.request.runId) }
        harness.bridge.allowTerminal.countDown()
        awaitState(harness.repository, MiningRunState::isTerminal)
    }

    @Test
    fun `enabled reading TTS is run owned callable only after promotion and closed at terminal`() {
        val audio = File(temporary.root, "android_tts_v1_${"a".repeat(64)}.wav").apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val synthesizer = FakeSentenceAudioSynthesizer(audio)
        val harness =
            harness(
                ttsEnabled = true,
                invokeTts = true,
                sentenceAudioFactory = SentenceAudioSynthesizerFactory { synthesizer },
            )

        runBlocking { harness.repository.startReading(INPUT) }
        val curating =
            awaitState(harness.repository) { it is MiningRunState.Curating } as MiningRunState.Curating
        runBlocking {
            harness.repository.confirmCuration(
                curating.request.runId,
                curating.request.requestId,
                FIRST_SELECTION,
            )
        }

        assertTrue(harness.bridge.ttsSubmitted.await(2, TimeUnit.SECONDS))
        assertEquals(1, harness.foreground.startCount.get())
        assertEquals(1, synthesizer.synthesizeCount.get())
        assertTrue(requireNotNull(harness.bridge.ttsResult.get()).contains(audio.canonicalPath))
        assertFalse(synthesizer.closed.get())
        harness.bridge.allowTerminal.countDown()

        assertTrue(awaitState(harness.repository, MiningRunState::isTerminal) is MiningRunState.Success)
        assertTrue(synthesizer.closed.get())
    }

    @Test
    fun `TTS callback raced with cancellation returns cancelled without mining fault`() {
        val audio = File(temporary.root, "android_tts_v1_${"b".repeat(64)}.wav").apply {
            writeBytes(byteArrayOf(1))
        }
        val synthesizer = FakeSentenceAudioSynthesizer(audio)
        val harness =
            harness(
                ttsEnabled = true,
                invokeTts = true,
                invokeTtsAfterCancellation = true,
                sentenceAudioFactory = SentenceAudioSynthesizerFactory { synthesizer },
            )

        runBlocking { harness.repository.startReading(INPUT) }
        val curating =
            awaitState(harness.repository) { it is MiningRunState.Curating } as MiningRunState.Curating
        runBlocking {
            harness.repository.confirmCuration(
                curating.request.runId,
                curating.request.requestId,
                FIRST_SELECTION,
            )
        }
        assertTrue(harness.bridge.ttsWindowReached.await(2, TimeUnit.SECONDS))

        runBlocking { harness.repository.cancel(curating.request.runId) }
        harness.bridge.allowTtsCallback.countDown()

        assertTrue(harness.bridge.ttsSubmitted.await(2, TimeUnit.SECONDS))
        assertTrue(requireNotNull(harness.bridge.ttsResult.get()).contains("\"outcome\":\"cancelled\""))
        assertEquals(0, synthesizer.synthesizeCount.get())
        harness.bridge.allowTerminal.countDown()
        assertTrue(awaitState(harness.repository, MiningRunState::isTerminal) is MiningRunState.Cancelled)
    }

    @Test
    fun `terminal callback atomically closes reading cancellation admission`() {
        val harness = harness(pauseAfterTerminalCallback = true)

        runBlocking { harness.repository.startReading(INPUT) }
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

        assertTrue(rejected is com.ankiminer.android.mining.MiningCommandException)
        assertTrue(awaitState(harness.repository, MiningRunState::isTerminal) is MiningRunState.Success)
        assertEquals(0, harness.bridge.cancellationAttempts.get())
    }

    @Test
    fun `failed reading cancellation dispatch retries and releases parked run`() {
        val harness = harness(cancelFailuresBeforeSuccess = 1)

        runBlocking { harness.repository.startReading(INPUT) }
        val curating =
            awaitState(harness.repository) { it is MiningRunState.Curating } as MiningRunState.Curating
        runBlocking { harness.repository.cancel(curating.request.runId) }

        assertTrue(harness.bridge.cancellationSubmitted.await(2, TimeUnit.SECONDS))
        assertEquals(2, harness.bridge.cancellationAttempts.get())
        harness.bridge.allowTerminal.countDown()
        assertTrue(awaitState(harness.repository, MiningRunState::isTerminal) is MiningRunState.Cancelled)
    }

    @Test
    fun `local reading cancellation releases curation after all dispatches fail`() {
        val harness = harness(cancelFailuresBeforeSuccess = Int.MAX_VALUE)

        runBlocking { harness.repository.startReading(INPUT) }
        val curating =
            awaitState(harness.repository) { it is MiningRunState.Curating } as MiningRunState.Curating
        runBlocking { harness.repository.cancel(curating.request.runId) }

        assertTrue(harness.bridge.localCancellationObserved.await(2, TimeUnit.SECONDS))
        assertTrue(waitUntil(2, TimeUnit.SECONDS) { harness.bridge.cancellationAttempts.get() == 2 })
        assertEquals(2, harness.bridge.cancellationAttempts.get())
        harness.bridge.allowTerminal.countDown()

        assertTrue(awaitState(harness.repository, MiningRunState::isTerminal) is MiningRunState.Cancelled)
        // INPUT is a text-only source, which never takes a media foreground lease, so there
        // is nothing to close. The video twin asserts 1 because video always promotes.
        assertEquals(0, harness.foreground.startCount.get())
        assertEquals(0, harness.foreground.lease.closeCount.get())
    }

    @Test
    fun `rejected reading control task falls back to cancellation worker`() {
        val harness = harness(rejectControlTasks = true)

        runBlocking { harness.repository.startReading(INPUT) }
        val curating =
            awaitState(harness.repository) { it is MiningRunState.Curating } as MiningRunState.Curating
        runBlocking { harness.repository.cancel(curating.request.runId) }

        assertTrue(harness.bridge.cancellationSubmitted.await(2, TimeUnit.SECONDS))
        harness.bridge.allowTerminal.countDown()
        assertTrue(awaitState(harness.repository, MiningRunState::isTerminal) is MiningRunState.Cancelled)
    }

    @Test
    fun `late reading cancellation no-active acknowledgement cannot replace success`() {
        val harness = harness(pauseCancellationUntilTerminal = true)

        runBlocking { harness.repository.startReading(INPUT) }
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

        runBlocking { harness.repository.startReading(INPUT) }
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
        harness.bridge.allowTerminal.countDown()

        val success =
            awaitState(harness.repository, MiningRunState::isTerminal) as MiningRunState.Success
        assertEquals(listOf(PRESENTER_WARNING_MESSAGE), success.result.errors)
    }

    @Test
    fun `nonfatal progress error is retained in successful result`() {
        val harness = harness(progressError = PROGRESS_ERROR)

        runBlocking { harness.repository.startReading(INPUT) }
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
        harness.bridge.allowTerminal.countDown()

        val success =
            awaitState(harness.repository, MiningRunState::isTerminal) as MiningRunState.Success
        assertEquals(listOf("猫: Audio extraction failed"), success.result.errors)
    }

    @Test
    fun `engine no-definition warning reaches the result restated`() {
        val harness = harness(presenterWarning = NO_DEFINITION_WARNING)

        runBlocking { harness.repository.startReading(INPUT) }
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

        runBlocking { harness.repository.startReading(INPUT) }
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
                readingRunFailure = IllegalStateException("secret /storage/emulated/0/user.cbz"),
            )

        runBlocking { harness.repository.startReading(INPUT) }

        val failed =
            awaitState(harness.repository, MiningRunState::isTerminal) as MiningRunState.Failed
        val message = failed.failure.message
        assertTrue(message, "IllegalStateException" in message)
        assertFalse(message, "secret" in message)
        assertFalse(message, "user.cbz" in message)
    }

    @Test
    fun `a self-contained archive with no mokuro member fails with the no-member fault`() {
        val archive =
            SafDocument(
                uri = "content://reading/lone",
                displayName = "Volume.cbz",
                mimeType = "application/x-cbz",
                sizeBytes = null,
            )
        val archiveBytes = zipBytes("Volume/001.jpg" to "jpeg".toByteArray())
        val harness =
            harness(inputBytes = mapOf(archive.uri to archiveBytes))

        runBlocking {
            harness.repository.startReading(
                ReadingMiningInput(ReadingSourceSelection.Single(archive)),
            )
        }

        val failed =
            awaitState(harness.repository, MiningRunState::isTerminal) as MiningRunState.Failed
        assertEquals(
            "resource:${R.string.mining_failure_reading_archive_no_mokuro}",
            failed.failure.message,
        )
    }

    @Test
    fun `a lone archive mines through a cache reached by a symlinked ancestor`() {
        val cache = symlinkedCache()
        val archive =
            SafDocument(
                uri = "content://reading/lone-linked",
                displayName = "Volume.cbz",
                mimeType = "application/x-cbz",
                sizeBytes = null,
            )
        val harness =
            harness(
                inputBytes =
                    mapOf(
                        archive.uri to
                            zipBytes(
                                "Volume/001.jpg" to "jpeg".toByteArray(),
                                "Volume.mokuro" to "{}".toByteArray(),
                            ),
                    ),
                cache = cache,
            )

        runBlocking {
            harness.repository.startReading(
                ReadingMiningInput(ReadingSourceSelection.Single(archive)),
            )
        }

        val curating = awaitCuratingOrFailure(harness)
        val wire = requireNotNull(harness.bridge.readingRequest.get())
        assertTrue(
            wire.sourcePath,
            File(wire.sourcePath).toPath().startsWith(cache.toPath().toRealPath()),
        )
        assertEquals(ReadingMiningSourceKind.MOKURO, wire.sourceKind)
        assertNotNull(wire.imageArchivePath)
        drain(harness, curating.request.runId)
    }

    @Test
    fun `a sidecar and archive pair mines through a cache reached by a symlinked ancestor`() {
        val cache = symlinkedCache()
        val sidecar =
            SafDocument(
                uri = "content://reading/pair-sidecar",
                displayName = "Volume.mokuro",
                mimeType = "application/json",
                sizeBytes = null,
            )
        val archive =
            SafDocument(
                uri = "content://reading/pair-archive",
                displayName = "Volume.cbz",
                mimeType = "application/x-cbz",
                sizeBytes = null,
            )
        val harness =
            harness(
                inputBytes =
                    mapOf(
                        sidecar.uri to "{}".toByteArray(),
                        archive.uri to zipBytes("Volume/001.jpg" to "jpeg".toByteArray()),
                    ),
                cache = cache,
            )

        runBlocking {
            harness.repository.startReading(
                ReadingMiningInput(ReadingSourceSelection.MokuroArchivePair(sidecar, archive)),
            )
        }

        val curating = awaitCuratingOrFailure(harness)
        val wire = requireNotNull(harness.bridge.readingRequest.get())
        assertTrue(
            wire.sourcePath,
            File(wire.sourcePath).toPath().startsWith(cache.toPath().toRealPath()),
        )
        drain(harness, curating.request.runId)
    }

    /**
     * Mirrors Android's `/data/user/0 -> /data/data` app-data symlink: the cache directory itself is
     * a real directory, reached through a symlinked ancestor, exactly as `Context.getCacheDir()`
     * returns it on the affected devices.
     */
    private fun symlinkedCache(): File {
        val root = temporary.newFolder("app-storage-${executors.size}").toPath()
        val real = Files.createDirectory(root.resolve("real"))
        Files.createDirectory(real.resolve("cache"))
        val link = Files.createSymbolicLink(root.resolve("link"), real)
        return link.resolve("cache").toFile()
    }

    /**
     * Fails with the run's own fault message rather than a bare timeout, so a staging or encode
     * rejection names itself (the digest reaches the message through [testStringResourceResolver]).
     */
    private fun awaitCuratingOrFailure(harness: Harness): MiningRunState.Curating {
        val state =
            awaitState(harness.repository) {
                it is MiningRunState.Curating || it is MiningRunState.Failed
            }
        assertTrue("$state", state is MiningRunState.Curating)
        return state as MiningRunState.Curating
    }

    private fun drain(
        harness: Harness,
        runId: String,
    ) {
        runBlocking { harness.repository.cancel(runId) }
        harness.bridge.allowTerminal.countDown()
        awaitState(harness.repository, MiningRunState::isTerminal)
    }

    @Test
    fun `python fault id reaches the failure state without changing its message`() {
        val harness = harness(raisedFailure = true)

        runBlocking { harness.repository.startReading(INPUT) }
        val curating =
            awaitState(harness.repository) { it is MiningRunState.Curating } as MiningRunState.Curating
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

        runBlocking { harness.repository.startReading(INPUT) }
        val curating =
            awaitState(harness.repository) { it is MiningRunState.Curating } as MiningRunState.Curating
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

    private fun zipBytes(vararg members: Pair<String, ByteArray>): ByteArray {
        val bytes = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bytes).use { zip ->
            members.forEach { (name, content) ->
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun harness(
        runtimeWorkCoordinator: RuntimeWorkCoordinator = RuntimeWorkCoordinator(),
        releases: MutableList<String> = Collections.synchronizedList(mutableListOf()),
        inputBytes: Map<String, ByteArray> = mapOf(INPUT_DOCUMENT.uri to "novel".toByteArray()),
        pagedCuration: Boolean = false,
        ttsEnabled: Boolean = false,
        invokeTts: Boolean = false,
        sentenceAudioFactory: SentenceAudioSynthesizerFactory? = null,
        expressionAudioFieldMapped: Boolean = false,
        presenterWarning: String? = null,
        progressError: String? = null,
        terminalErrorCount: Int = 0,
        readingRunFailure: RuntimeException? = null,
        raisedFailure: Boolean = false,
        fallbackState: ReleaseState = ReleaseState.ABSENT,
        pendingForegroundStart: Boolean = false,
        pauseAfterTerminalCallback: Boolean = false,
        cancelFailuresBeforeSuccess: Int = 0,
        pauseCancellationUntilTerminal: Boolean = false,
        rejectControlTasks: Boolean = false,
        invokeTtsAfterCancellation: Boolean = false,
        interruptionStore: com.ankiminer.android.mining.MiningRunInterruptionStore =
            com.ankiminer.android.mining.NoOpMiningRunInterruptionStore,
        cache: File? = null,
        foregroundFailure: ForegroundStartFailure? = null,
        ankiFailure: RuntimeException? = null,
        strings: StringResourceResolver = testStringResourceResolver,
    ): Harness {
        val runExecutor = Executors.newSingleThreadExecutor().also(executors::add)
        val controlExecutor = Executors.newSingleThreadExecutor().also(executors::add)
        val controlFailure = AtomicReference<Throwable?>()
        val controlTaskCompleted = CountDownLatch(1)
        val cacheDir = cache ?: temporary.newFolder("cache-${executors.size}")
        val stageRoot = readingSourceStagingRoot(cacheDir)
        val bridge =
            FakeReadingPyBridge(
                pagedCuration = pagedCuration,
                invokeTts = invokeTts,
                presenterWarning = presenterWarning,
                progressError = progressError,
                terminalErrorCount = terminalErrorCount,
                readingRunFailure = readingRunFailure,
                raisedFailure = raisedFailure,
                pauseAfterTerminalCallback = pauseAfterTerminalCallback,
                cancelFailuresBeforeSuccess = cancelFailuresBeforeSuccess,
                pauseCancellationUntilTerminal = pauseCancellationUntilTerminal,
                invokeTtsAfterCancellation = invokeTtsAfterCancellation,
            )
        val anki = FakeAnkiCallbacks(fallbackState, ankiFailure)
        val foreground = FakeForegroundStarter(foregroundFailure, pendingForegroundStart)
        val openCount = AtomicInteger()
        val foregroundStartsAtOpen = AtomicInteger(-1)
        val repository =
            BridgeReadingMiningRepository(
                pyBridge = bridge,
                anki = anki,
                sourceStager =
                    stager(
                        stageRoot,
                        openCount,
                        inputBytes,
                        foreground.startCount,
                        foregroundStartsAtOpen,
                    ),
                tokenizerResourceProvider =
                    InstalledTokenizerResourceProvider {
                        InstalledTokenizerResource(
                            File("/tmp/test-unidic"),
                            TOKENIZER_RESOURCE_ID,
                            TOKENIZER_SHA,
                        )
                    },
                runtimePaths = MiningRuntimePaths(cacheDir, temporary.newFolder("native-${executors.size}")),
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
                strings = strings,
                runtimeWorkCoordinator = runtimeWorkCoordinator,
                configSnapshotResolver =
                    ReadingConfigSnapshotResolver {
                        MiningConfigSnapshot(
                            settings =
                                if (expressionAudioFieldMapped) {
                                    // Model the localaudio-only configuration: the expression_audio
                                    // Anki field is mapped and ZERO packs are imported. The reading
                                    // FGS predicate reads anki_fields — localaudio is injected
                                    // Python-side and never appears in this Kotlin snapshot.
                                    mapOf(
                                        "anki_fields" to
                                            BridgeJsonValue.ObjectValue(
                                                mapOf(
                                                    "expression_audio" to BridgeJsonValue.Text("ExpressionAudio"),
                                                ),
                                            ),
                                    )
                                } else {
                                    emptyMap()
                                },
                            androidTtsEnabled = ttsEnabled,
                        )
                    },
                sentenceAudioSynthesizerFactory = sentenceAudioFactory,
                foregroundStartTimeoutSeconds = 2,
                interruptionStore = interruptionStore,
            )
        return Harness(
            repository,
            bridge,
            anki,
            foreground,
            cacheDir,
            stageRoot,
            controlFailure,
            controlTaskCompleted,
            openCount,
            foregroundStartsAtOpen,
        )
    }

    private fun foregroundFailure(
        failure: ForegroundStartFailure,
    ): Pair<Harness, MiningRunState.Failed> {
        val harness =
            harness(
                expressionAudioFieldMapped = true,
                foregroundFailure = failure,
            )
        runBlocking { harness.repository.startReading(INPUT) }
        val failed =
            awaitState(harness.repository, MiningRunState::isTerminal) as MiningRunState.Failed
        return harness to failed
    }

    private fun stager(
        root: File,
        openCount: AtomicInteger,
        inputBytes: Map<String, ByteArray> = mapOf(INPUT_DOCUMENT.uri to "novel".toByteArray()),
        foregroundStarts: AtomicInteger? = null,
        foregroundStartsAtOpen: AtomicInteger? = null,
    ): ReadingSourceStager =
        ReadingSourceStager(
            stagingRoot = root,
            inputOpener =
                ReadingSourceInputOpener { document, _ ->
                    openCount.incrementAndGet()
                    foregroundStartsAtOpen?.set(foregroundStarts?.get() ?: -1)
                    ByteArrayInputStream(requireNotNull(inputBytes[document.uri]))
                },
            limits =
                ReadingSourceStageLimits(
                    textMaxBytes = 1024,
                    epubMaxBytes = 1024,
                    subtitleMaxBytes = 1024,
                    mokuroSidecarMaxBytes = 1024,
                    mokuroArchiveMaxBytes = 1024,
                    jobMaxBytes = 2048,
                    freeSpaceReserveBytes = 0,
                    bufferBytes = 2,
                    checkpointIntervalBytes = 1,
                ),
            availableBytes = { 1_000_000L },
            nonceSource = ReadingSourceStageNonceSource { "11111111111111111111111111111111" },
        )

    /** Record heads from `c=` onward: the timestamp, level and run id are not what these assert. */
    private fun recordsFor(op: String): List<String> =
        recorded.records
            .map { it.substringBefore('\n') }
            .filter { it.contains(op) }
            .map { it.substring(it.indexOf("c=")) }

    /** Start a run and cancel it out again, so the lane is proven usable and leaves no record. */
    private fun runReadingToTerminal(harness: Harness) {
        runBlocking { harness.repository.startReading(INPUT) }
        val curating =
            awaitState(harness.repository) { it is MiningRunState.Curating } as
                MiningRunState.Curating
        runBlocking { harness.repository.cancel(curating.request.runId) }
        harness.bridge.allowTerminal.countDown()
        awaitState(harness.repository, MiningRunState::isTerminal)
    }

    private fun awaitState(
        repository: ReadingMiningRepository,
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
        val repository: BridgeReadingMiningRepository,
        val bridge: FakeReadingPyBridge,
        val anki: FakeAnkiCallbacks,
        val foreground: FakeForegroundStarter,
        val cacheDir: File,
        val stageRoot: File,
        val controlFailure: AtomicReference<Throwable?>,
        val controlTaskCompleted: CountDownLatch,
        val openCount: AtomicInteger,
        val foregroundStartsAtOpen: AtomicInteger,
    )

    private class FakeAnkiCallbacks(
        private val fallbackState: ReleaseState = ReleaseState.ABSENT,
        private val failure: RuntimeException? = null,
    ) : CoordinatorAnkiCallbacks {
        var cancellation: AnkiCancellation? = null
        val fallbackRuns = Collections.synchronizedList(mutableListOf<String>())

        override fun registerRun(
            runId: String,
            cancellation: AnkiCancellation,
        ): Boolean {
            this.cancellation = cancellation
            return runId == RUN_ID
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
        private val failure: ForegroundStartFailure? = null,
        private val pending: Boolean = false,
    ) : com.ankiminer.android.mining.MiningForegroundStarter {
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

    private class FakeSentenceAudioSynthesizer(
        private val audio: File,
    ) : SentenceAudioSynthesizer {
        val synthesizeCount = AtomicInteger()
        val closed = AtomicBoolean()

        override fun synthesize(
            sentence: String,
            cancellationCheck: () -> Boolean,
        ): SentenceAudioSynthesis {
            assertEquals("猫だ。", sentence)
            assertFalse(cancellationCheck())
            synthesizeCount.incrementAndGet()
            return SentenceAudioSynthesis.ready(audio)
        }

        override fun close() {
            closed.set(true)
        }
    }

    private class FakeMiningRunInterruptionStore(
        private var failCompletions: Int = 0,
        private var invalidRecord: Boolean = false,
        durableRecord: com.ankiminer.android.mining.InterruptedMiningRun? = null,
    ) :
        com.ankiminer.android.mining.MiningRunInterruptionStore {
        private var current: com.ankiminer.android.mining.InterruptedMiningRun? = durableRecord
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
            kind: com.ankiminer.android.mining.MiningRunKind,
            ownerId: String,
        ): Boolean {
            if (hasBlockedRecord()) return false
            current =
                com.ankiminer.android.mining.InterruptedMiningRun(
                    kind,
                    ownerId,
                    runId = null,
                )
            startup = StartupInterruption.None
            return true
        }

        override fun registered(
            kind: com.ankiminer.android.mining.MiningRunKind,
            ownerId: String,
            runId: String,
        ): Boolean {
            if (
                current !=
                com.ankiminer.android.mining.InterruptedMiningRun(
                    kind,
                    ownerId,
                    runId = null,
                )
            ) {
                return false
            }
            current = com.ankiminer.android.mining.InterruptedMiningRun(kind, ownerId, runId)
            return true
        }

        override fun complete(
            kind: com.ankiminer.android.mining.MiningRunKind,
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

    private class FakeReadingPyBridge(
        private val pagedCuration: Boolean = false,
        private val invokeTts: Boolean = false,
        private val presenterWarning: String? = null,
        private val progressError: String? = null,
        private val terminalErrorCount: Int = 0,
        private val readingRunFailure: RuntimeException? = null,
        private val raisedFailure: Boolean = false,
        private val pauseAfterTerminalCallback: Boolean = false,
        private val cancelFailuresBeforeSuccess: Int = 0,
        private val pauseCancellationUntilTerminal: Boolean = false,
        private val invokeTtsAfterCancellation: Boolean = false,
    ) : PyBridge {
        val readingRequest = AtomicReference<ReadingMiningWireRequest?>()
        val readingRunRequests = CopyOnWriteArrayList<String>()
        val curationSubmitted = CountDownLatch(1)
        val intermediateCurationSubmitted = CountDownLatch(1)
        val cancellationSubmitted = CountDownLatch(1)
        val allowTerminal = CountDownLatch(1)
        val ttsSubmitted = CountDownLatch(if (invokeTts) 1 else 0)
        val ttsWindowReached = CountDownLatch(if (invokeTtsAfterCancellation) 1 else 0)
        val allowTtsCallback = CountDownLatch(if (invokeTtsAfterCancellation) 1 else 0)
        val ttsResult = AtomicReference<String?>()
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
                is BridgeMessage.ReadingRun -> {
                    readingRunRequests += rawRequest
                    readingRunFailure?.let { throw it }
                    runReading(request.request, requireNotNull(callbacks))
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

        private fun runReading(
            request: ReadingMiningWireRequest,
            callbacks: EngineCallbacks,
        ): String {
            runCallbacks = callbacks
            readingRequest.set(request)
            check(File(request.sourcePath).isFile)
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
            if (invokeTtsAfterCancellation) {
                ttsWindowReached.countDown()
                check(allowTtsCallback.await(3, TimeUnit.SECONDS))
            }
            if (invokeTts && (!cancelled.get() || invokeTtsAfterCancellation)) {
                ttsResult.set(callbacks.synthesizeSentenceAudio(TTS_REQUEST))
                ttsSubmitted.countDown()
            }
            check(allowTerminal.await(3, TimeUnit.SECONDS))
            if (cancelled.get()) {
                callbacks.onComplete(CANCELLED_TERMINAL)
                return CANCELLED_TERMINAL
            }
            progressError?.let(callbacks::onError)
            val terminal = terminalPayload()
            if (terminalErrorCount == 0 && !raisedFailure) {
                callbacks.onComplete(terminal)
            } else {
                callbacks.onError(terminal)
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
        const val TOKENIZER_SHA =
            "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
        const val MAX_RESULT_ERRORS = 256

        /** A crash in the video lane leaves this behind for the reading lane to find. */
        val VIDEO_INTERRUPTION =
            InterruptedMiningRun(
                MiningRunKind.VIDEO,
                ownerId = "cancel_11111111111111111111111111111111",
                runId = "run_22222222222222222222222222222222",
            )
        val READING_INTERRUPTION =
            InterruptedMiningRun(
                MiningRunKind.READING,
                ownerId = "cancel_33333333333333333333333333333333",
                runId = "run_44444444444444444444444444444444",
            )
        val INPUT_DOCUMENT =
            SafDocument(
                uri = "content://reading/novel",
                displayName = "Novel.txt",
                mimeType = "text/plain",
                sizeBytes = 5L,
            )
        val INPUT = ReadingMiningInput(ReadingSourceSelection.Single(INPUT_DOCUMENT))
        const val PASTED_TEXT_CONTENT = "本文。"
        val PASTED_INPUT =
            ReadingMiningInput(
                ReadingSourceSelection.PastedText(PASTED_TEXT_CONTENT),
                subtitleSeriesName = null,
            )
        val EPUB_DOCUMENT =
            SafDocument(
                uri = "content://reading/book",
                displayName = "Book.epub",
                mimeType = "application/epub+zip",
                sizeBytes = 4L,
            )
        val EPUB_INPUT = ReadingMiningInput(ReadingSourceSelection.Single(EPUB_DOCUMENT))
        val FIRST_SELECTION = listOf(CurationSelection(CANDIDATE_ID, SENTENCE_ID))
        val JOB_REGISTRATION =
            """{"schemaVersion":1,"type":"job.registration.request","payload":{"runId":"$RUN_ID"}}"""
        val PROGRESS_START =
            """{"schemaVersion":1,"type":"progress.start","payload":{"runId":"$RUN_ID","total":3,"description":"Preparing curation"}}"""
        val PROGRESS_STAGE =
            """{"schemaVersion":1,"type":"progress.stage","payload":{"runId":"$RUN_ID","index":2,"total":5,"name":"Extracting media"}}"""
        val PROGRESS_STAGE_START =
            """{"schemaVersion":1,"type":"progress.start","payload":{"runId":"$RUN_ID","total":10,"description":"Extracting media"}}"""
        val PROGRESS_STAGE_UPDATE =
            """{"schemaVersion":1,"type":"progress.update","payload":{"runId":"$RUN_ID","current":5,"description":"Extracting media: 猫"}}"""
        const val MINED_TERM = "猫"
        const val ANKI_VERIFY_REQUEST =
            """{"schemaVersion":1,"type":"anki.verify.request","payload":{}}"""

        /** Shaped like a real phase-4 event: the engine names the term it just looked up. */
        val HOSTILE_PROGRESS =
            """{"schemaVersion":1,"type":"progress.update","payload":{"runId":"$RUN_ID","current":2,"description":"Definition found: $MINED_TERM"}}"""
        const val PRESENTER_WARNING_MESSAGE = "Offline sentence audio is unavailable"
        const val NO_DEFINITION_WARNING = "Skipped 2 words with no definition found: 本好き, 編み"
        const val PRESENTER_WARNING_PLACEHOLDER = "__WARNING__"
        val PRESENTER_WARNING =
            """{"schemaVersion":1,"type":"presenter.event","payload":{"runId":"$RUN_ID","kind":"warning","message":"$PRESENTER_WARNING_PLACEHOLDER"}}"""
        val PROGRESS_ERROR =
            """{"schemaVersion":1,"type":"progress.error","payload":{"runId":"$RUN_ID","description":"猫","message":"Audio extraction failed"}}"""
        const val TTS_REQUEST =
            """{"schemaVersion":1,"type":"tts.sentence.request","payload":{"runId":"$RUN_ID","requestId":"tts_11111111111111111111111111111111","sentence":"猫だ。"}}"""
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
            """{"schemaVersion":1,"type":"mining.terminal","payload":{"runId":"$RUN_ID","outcome":"success","result":{"totalWordsFound":1,"newWordsFound":0,"cardsCreated":0,"errors":[],"elapsedTime":1.0,"comprehensionPercentage":100.0,"cardIds":[],"videoFile":"","subtitleFile":"Novel.txt","minedForms":[],"ankiWriteState":"no_note_write","failureIsTransient":false},"error":null}}"""
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
