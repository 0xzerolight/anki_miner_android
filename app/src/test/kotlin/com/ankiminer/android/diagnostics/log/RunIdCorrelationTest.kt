package com.ankiminer.android.diagnostics.log

import com.ankiminer.android.anki.protocol.ReleaseState
import com.ankiminer.android.anki.provider.AnkiCancellation
import com.ankiminer.android.data.RuntimeWorkCoordinator
import com.ankiminer.android.engine.BridgeJsonCodec
import com.ankiminer.android.engine.BridgeMessage
import com.ankiminer.android.engine.EngineCallbacks
import com.ankiminer.android.engine.PyBridge
import com.ankiminer.android.engine.TokenizerIdentity
import com.ankiminer.android.localization.testStringResourceResolver
import com.ankiminer.android.media.SafDocument
import com.ankiminer.android.mining.BridgeMiningRepository
import com.ankiminer.android.mining.CoordinatorAnkiCallbacks
import com.ankiminer.android.mining.InstalledTokenizerResource
import com.ankiminer.android.mining.InstalledTokenizerResourceProvider
import com.ankiminer.android.mining.MiningForegroundStarter
import com.ankiminer.android.mining.MiningInputOwner
import com.ankiminer.android.mining.MiningInputOwnerFactory
import com.ankiminer.android.mining.MiningRunState
import com.ankiminer.android.mining.MiningRuntimePaths
import com.ankiminer.android.mining.MiningSource
import com.ankiminer.android.mining.MiningTaskExecutor
import com.ankiminer.android.mining.SourceGrantReleaser
import com.ankiminer.android.mining.VideoMiningInput
import com.ankiminer.android.mining.asMiningTaskExecutor
import com.ankiminer.android.mining.isTerminal
import com.ankiminer.android.reading.BridgeReadingMiningRepository
import com.ankiminer.android.reading.ReadingMiningInput
import com.ankiminer.android.reading.ReadingSourceInputOpener
import com.ankiminer.android.reading.ReadingSourceSelection
import com.ankiminer.android.reading.ReadingSourceStageLimits
import com.ankiminer.android.reading.ReadingSourceStageNonceSource
import com.ankiminer.android.reading.ReadingSourceStager
import com.ankiminer.android.reading.readingSourceStagingRoot
import com.ankiminer.android.service.MiningForegroundLease
import com.ankiminer.android.service.MiningForegroundProgress
import com.ankiminer.android.service.MiningForegroundSessionIdentity
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The run id has to survive the seam that no single-lane test can see.
 *
 * `AnkiMinerApplication` hands the video and reading repositories the **same** `miningRunExecutor`
 * ("anki-miner-python") and the same `miningControlExecutor`, and Python calls back synchronously on
 * the run thread. That is what lets one thread local reach `onStage`, `ankiCreateNotes`, the provider
 * and the journal with no call-site changes -- and it is also why a run task that sets the id without
 * restoring it mislabels the *other* lane's records rather than its own.
 */
class RunIdCorrelationTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val recorded = RecordingLogSink()
    private val runExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val controlExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    @Before
    fun installRecordingSink() {
        AppLog.setMinLevel(LogLevel.INFO)
        AppLog.install(NoOpSink)
        AppLog.install(recorded)
    }

    @After
    fun stopExecutors() {
        runExecutor.shutdownNow()
        controlExecutor.shutdownNow()
        AppLog.install(NoOpSink)
    }

    @Test
    fun `a reading run after a video run carries its own id and never the previous one`() {
        val bridge = FakeDualLaneBridge()
        val coordinator = RuntimeWorkCoordinator()

        val video = videoRepository(bridge, coordinator)
        runBlocking { video.startVideo(VIDEO_INPUT) }
        awaitTerminal(video.state.value) { video.state.value }
        val afterVideo = recorded.records.toList()

        val reading = readingRepository(bridge, coordinator)
        runBlocking { reading.startReading(readingInput()) }
        awaitTerminal(reading.state.value) { reading.state.value }
        val duringReading = recorded.records.drop(afterVideo.size)

        // Each lane's own phase machine, which runs entirely on the shared thread.
        assertEquals(
            listOf(
                "run=$VIDEO_RUN_ID c=mining op=phase from=PREPARING to=REGISTERED",
                "run=$VIDEO_RUN_ID c=mining op=phase from=REGISTERED to=FINALIZING",
            ),
            afterVideo.phaseHeads(),
        )
        assertEquals(
            listOf(
                "run=$READING_RUN_ID c=reading op=phase from=PREPARING to=REGISTERED",
                "run=$READING_RUN_ID c=reading op=phase from=REGISTERED to=FINALIZING",
            ),
            duringReading.phaseHeads(),
        )

        // The leak the shared executor creates: the video id surviving into the reading run.
        duringReading.forEach { record ->
            assertTrue("video run id leaked into the reading lane: $record", !record.contains(VIDEO_RUN_ID))
        }
        assertTrue(
            "the reading run id appeared before the reading run started",
            afterVideo.none { it.contains(READING_RUN_ID) },
        )

        // The last lane's restore has no later run to expose it, so probe the thread directly.
        // Without this the reading repository could stop restoring and nothing here would notice.
        val afterBoth = CompletableFuture<String?>()
        runExecutor.execute { afterBoth.complete(LogContext.runId()) }
        assertNull(afterBoth.get(3, TimeUnit.SECONDS))
    }

    @Test
    fun `the shared run thread holds no id once a run has finished`() {
        val bridge = FakeDualLaneBridge()
        val video = videoRepository(bridge, RuntimeWorkCoordinator())

        runBlocking { video.startVideo(VIDEO_INPUT) }
        awaitTerminal(video.state.value) { video.state.value }

        // Whatever the executor runs next is the next lane's first statement.
        val observed = CompletableFuture<String?>()
        runExecutor.execute { observed.complete(LogContext.runId()) }

        assertNull(observed.get(3, TimeUnit.SECONDS))
    }

    @Test
    fun `video cancellation dispatch carries the run id on the control worker`() {
        AppLog.setMinLevel(LogLevel.DEBUG)
        val bridge = CancellableBridge(VIDEO_RUN_ID)
        val video = videoRepository(bridge, RuntimeWorkCoordinator())

        runBlocking { video.startVideo(VIDEO_INPUT) }
        bridge.awaitRegistration()
        runBlocking { video.cancel(VIDEO_RUN_ID) }

        assertEquals(VIDEO_RUN_ID, bridge.cancellationRunId.get(3, TimeUnit.SECONDS))
        assertTrue(
            recorded.records.any { record ->
                record.contains(" D run=$VIDEO_RUN_ID c=bridge op=cancel.probe ")
            },
        )
        awaitTerminal(video.state.value) { video.state.value }
    }

    @Test
    fun `reading cancellation fallback carries the run id when control rejects`() {
        AppLog.setMinLevel(LogLevel.DEBUG)
        val bridge = CancellableBridge(READING_RUN_ID)
        val reading =
            readingRepository(
                bridge,
                RuntimeWorkCoordinator(),
                MiningTaskExecutor { throw RejectedExecutionException("test rejection") },
            )

        runBlocking { reading.startReading(readingInput()) }
        bridge.awaitRegistration()
        runBlocking { reading.cancel(READING_RUN_ID) }

        assertEquals(READING_RUN_ID, bridge.cancellationRunId.get(3, TimeUnit.SECONDS))
        assertTrue(
            recorded.records.any { record ->
                record.contains(" D run=$READING_RUN_ID c=bridge op=cancel.probe ")
            },
        )
        awaitTerminal(reading.state.value) { reading.state.value }
    }

    private fun List<String>.phaseHeads(): List<String> =
        map { it.substringBefore('\n') }
            .filter { it.contains("op=phase") }
            .map { record ->
                val from = record.indexOf("run=")
                record.substring(from, record.indexOf(" outcome="))
            }

    private fun videoRepository(
        bridge: PyBridge,
        coordinator: RuntimeWorkCoordinator,
        control: MiningTaskExecutor = controlExecutor.asMiningTaskExecutor(),
    ) = BridgeMiningRepository(
        pyBridge = bridge,
        anki = FakeAnkiCallbacks(),
        inputOwnerFactory = MiningInputOwnerFactory { FakeInputOwner() },
        tokenizerResourceProvider = tokenizerResourceProvider(),
        runtimePaths = runtimePaths("video"),
        sourceGrantReleaser = SourceGrantReleaser { },
        foregroundStarter = completedForegroundStarter(),
        runExecutor = runExecutor.asMiningTaskExecutor(),
        controlExecutor = control,
        strings = testStringResourceResolver,
        runtimeWorkCoordinator = coordinator,
    )

    private fun readingRepository(
        bridge: PyBridge,
        coordinator: RuntimeWorkCoordinator,
        control: MiningTaskExecutor = controlExecutor.asMiningTaskExecutor(),
    ): BridgeReadingMiningRepository {
        // The codec rejects a staged path that is not inside cacheDir, so the stager's root has
        // to be derived from the very cacheDir the repository reports.
        val paths = runtimePaths("reading")
        return BridgeReadingMiningRepository(
            pyBridge = bridge,
            anki = FakeAnkiCallbacks(),
            sourceStager =
                ReadingSourceStager(
                    stagingRoot = readingSourceStagingRoot(paths.cacheDir),
                    inputOpener =
                        ReadingSourceInputOpener { _, _ ->
                            ByteArrayInputStream("novel".toByteArray())
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
                        ),
                    availableBytes = { 1_000_000L },
                    nonceSource = ReadingSourceStageNonceSource { "1".repeat(32) },
                ),
            tokenizerResourceProvider = tokenizerResourceProvider(),
            runtimePaths = paths,
            sourceGrantReleaser = SourceGrantReleaser { },
            foregroundStarter = completedForegroundStarter(),
            runExecutor = runExecutor.asMiningTaskExecutor(),
            controlExecutor = control,
            strings = testStringResourceResolver,
            runtimeWorkCoordinator = coordinator,
        )
    }

    private fun tokenizerResourceProvider() =
        InstalledTokenizerResourceProvider {
            InstalledTokenizerResource(File("/tmp/test-unidic"), TOKENIZER_RESOURCE_ID, TOKENIZER_SHA)
        }

    private fun completedForegroundStarter() =
        MiningForegroundStarter { runId, generation, _ ->
            CompletableFuture.completedFuture(
                object : MiningForegroundLease {
                    override val identity = MiningForegroundSessionIdentity.create(runId, generation)

                    override fun updateProgress(progress: MiningForegroundProgress): Boolean = true

                    override fun close() = Unit
                },
            )
        }

    private fun runtimePaths(label: String) =
        MiningRuntimePaths(temporary.newFolder("cache-$label"), temporary.newFolder("native-$label"))

    private fun awaitTerminal(
        initial: MiningRunState,
        state: () -> MiningRunState,
    ) {
        var current = initial
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            current = state()
            if (current.isTerminal) return
            Thread.sleep(5)
        }
        throw AssertionError("Timed out waiting for a terminal state; current=$current")
    }

    private fun readingInput() =
        ReadingMiningInput(
            ReadingSourceSelection.Single(
                SafDocument(
                    uri = "content://reading/novel",
                    displayName = "Novel.txt",
                    mimeType = "text/plain",
                    sizeBytes = 5,
                ),
            ),
        )

    private class FakeInputOwner : MiningInputOwner {
        override fun openVideo(source: MiningSource): String = "/tmp/video.mkv"

        override fun materializeSubtitle(source: MiningSource): String = "/tmp/subtitle.srt"

        override fun close() = Unit
    }

    private class FakeAnkiCallbacks : CoordinatorAnkiCallbacks {
        override fun registerRun(
            runId: String,
            cancellation: AnkiCancellation,
        ): Boolean = true

        override fun verifyTarget(rawRequest: String): String = error("not called")

        override fun scanFirstFields(rawRequest: String): String = error("not called")

        override fun storeMedia(rawRequest: String): String = error("not called")

        override fun createNotes(rawRequest: String): String = error("not called")

        override fun releaseRunState(rawRequest: String): String = error("not called")

        override fun releaseRunStateFallback(runId: String): ReleaseState = ReleaseState.ABSENT
    }

    /**
     * Drives both lanes to a terminal without curation, which is all this test needs: the run id is
     * installed by `registerJob` and every callback below runs on the caller's thread, exactly as
     * Chaquopy invokes them.
     */
    private class FakeDualLaneBridge : PyBridge {
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
                is BridgeMessage.VideoRun -> runLane(VIDEO_RUN_ID, requireNotNull(callbacks))
                is BridgeMessage.ReadingRun -> runLane(READING_RUN_ID, requireNotNull(callbacks))
                else -> error("Unexpected request: $request")
            }

        private fun runLane(
            runId: String,
            callbacks: EngineCallbacks,
        ): String {
            callbacks.registerJob(registration(runId))
            callbacks.onComplete(terminal(runId))
            return terminal(runId)
        }
    }

    private class CancellableBridge(
        private val runId: String,
    ) : PyBridge {
        private val registered = CompletableFuture<Unit>()
        private val cancellation = CompletableFuture<Unit>()
        val cancellationRunId = CompletableFuture<String?>()

        fun awaitRegistration() {
            registered.get(3, TimeUnit.SECONDS)
        }

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
                is BridgeMessage.VideoRun,
                is BridgeMessage.ReadingRun,
                -> runUntilCancelled(requireNotNull(callbacks))
                is BridgeMessage.JobCancel -> {
                    AppLog.d(LogComponent.BRIDGE, "cancel.probe") {
                        arrayOf("outcome" to "ok")
                    }
                    cancellationRunId.complete(LogContext.runId())
                    cancellation.complete(Unit)
                    cancelled(runId)
                }
                else -> error("Unexpected request: $request")
            }

        private fun runUntilCancelled(callbacks: EngineCallbacks): String {
            callbacks.registerJob(registration(runId))
            registered.complete(Unit)
            cancellation.get(3, TimeUnit.SECONDS)
            return terminal(runId).also(callbacks::onComplete)
        }
    }

    private companion object {
        const val VIDEO_RUN_ID = "run_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val READING_RUN_ID = "run_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val TOKENIZER_RESOURCE_ID = "unidic-lite-1"
        val TOKENIZER_SHA = "e".repeat(64)

        val VIDEO_INPUT =
            VideoMiningInput(
                video = MiningSource("content://test/video", "episode.mkv"),
                subtitle = MiningSource("content://test/subtitle", "episode.srt"),
            )

        fun registration(runId: String) =
            """{"schemaVersion":1,"type":"job.registration.request","payload":{"runId":"$runId"}}"""

        fun terminal(runId: String) =
            """{"schemaVersion":1,"type":"mining.terminal","payload":{"runId":"$runId","outcome":"success",""" +
                """"result":{"totalWordsFound":1,"newWordsFound":0,"cardsCreated":0,"errors":[],""" +
                """"elapsedTime":1.0,"comprehensionPercentage":100.0,"cardIds":[],"videoFile":"",""" +
                """"subtitleFile":"Novel.txt","minedForms":[],"ankiWriteState":"no_note_write",""" +
                """"failureIsTransient":false},"error":null}}"""

        fun cancelled(runId: String) =
            """{"schemaVersion":1,"type":"job.cancelled","payload":{"runId":"$runId","newlyCancelled":true}}"""
    }
}
