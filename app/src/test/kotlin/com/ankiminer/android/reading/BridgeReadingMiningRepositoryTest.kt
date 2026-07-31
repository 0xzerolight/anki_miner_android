package com.ankiminer.android.reading

import com.ankiminer.android.R
import com.ankiminer.android.anki.protocol.ReleaseState
import com.ankiminer.android.anki.provider.AnkiCancellation
import com.ankiminer.android.data.RuntimeWorkCoordinator
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
import com.ankiminer.android.localization.testStringResourceResolver
import com.ankiminer.android.mining.CoordinatorAnkiCallbacks
import com.ankiminer.android.mining.CurationSelection
import com.ankiminer.android.mining.InstalledTokenizerResource
import com.ankiminer.android.mining.InstalledTokenizerResourceProvider
import com.ankiminer.android.mining.MiningRunState
import com.ankiminer.android.mining.MiningRuntimePaths
import com.ankiminer.android.mining.MiningTaskExecutor
import com.ankiminer.android.mining.SourceGrantReleaser
import com.ankiminer.android.mining.asMiningTaskExecutor
import com.ankiminer.android.mining.isTerminal
import com.ankiminer.android.service.MiningForegroundLease
import com.ankiminer.android.service.MiningForegroundProgress
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BridgeReadingMiningRepositoryTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val executors = mutableListOf<ExecutorService>()

    @After
    fun stopExecutors() {
        executors.forEach(ExecutorService::shutdownNow)
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
        assertEquals(0, harness.foreground.startCount.get())
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
        assertEquals(0, harness.foreground.startCount.get())

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
    fun `epub selection promotes foreground only when curation is nonempty`() {
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
        assertEquals(0, emptyHarness.foreground.startCount.get())
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
        assertEquals(0, cancelledHarness.foreground.startCount.get())
        cancelledHarness.bridge.allowTerminal.countDown()
        assertTrue(
            awaitState(cancelledHarness.repository, MiningRunState::isTerminal) is
                MiningRunState.Cancelled,
        )
    }

    @Test
    fun `empty media-workload selection completes without foreground promotion`() {
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
        assertEquals(0, harness.foreground.startCount.get())
        harness.bridge.allowTerminal.countDown()

        assertTrue(awaitState(harness.repository, MiningRunState::isTerminal) is MiningRunState.Success)
        assertEquals(0, harness.foreground.lease.closeCount.get())
    }

    @Test
    fun `cancelling parked reading removes the private stage without foreground promotion`() {
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
        assertEquals(0, harness.foreground.startCount.get())

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
        terminalErrorCount: Int = 0,
        readingRunFailure: RuntimeException? = null,
        raisedFailure: Boolean = false,
        fallbackState: ReleaseState = ReleaseState.ABSENT,
        cache: File? = null,
    ): Harness {
        val runExecutor = Executors.newSingleThreadExecutor().also(executors::add)
        val controlExecutor = Executors.newSingleThreadExecutor().also(executors::add)
        val cacheDir = cache ?: temporary.newFolder("cache-${executors.size}")
        val stageRoot = readingSourceStagingRoot(cacheDir)
        val bridge =
            FakeReadingPyBridge(
                pagedCuration = pagedCuration,
                invokeTts = invokeTts,
                presenterWarning = presenterWarning,
                terminalErrorCount = terminalErrorCount,
                readingRunFailure = readingRunFailure,
                raisedFailure = raisedFailure,
            )
        val anki = FakeAnkiCallbacks(fallbackState)
        val foreground = FakeForegroundStarter()
        val openCount = AtomicInteger()
        val repository =
            BridgeReadingMiningRepository(
                pyBridge = bridge,
                anki = anki,
                sourceStager = stager(stageRoot, openCount, inputBytes),
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
                controlExecutor = controlExecutor.asMiningTaskExecutor(),
                strings = testStringResourceResolver,
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
            )
        return Harness(repository, bridge, anki, foreground, cacheDir, stageRoot)
    }

    private fun stager(
        root: File,
        openCount: AtomicInteger,
        inputBytes: Map<String, ByteArray> = mapOf(INPUT_DOCUMENT.uri to "novel".toByteArray()),
    ): ReadingSourceStager =
        ReadingSourceStager(
            stagingRoot = root,
            inputOpener =
                ReadingSourceInputOpener { document ->
                    openCount.incrementAndGet()
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
                ),
            availableBytes = { 1_000_000L },
            nonceSource = ReadingSourceStageNonceSource { "11111111111111111111111111111111" },
        )

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
    )

    private class FakeAnkiCallbacks(
        private val fallbackState: ReleaseState = ReleaseState.ABSENT,
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

    private class FakeForegroundStarter : com.ankiminer.android.mining.MiningForegroundStarter {
        val startCount = AtomicInteger()
        val lease = FakeForegroundLease()

        override fun startSession(
            runId: String,
            generation: Long,
            listener: MiningForegroundSessionListener,
        ): CompletableFuture<MiningForegroundLease> {
            startCount.incrementAndGet()
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

    private class FakeReadingPyBridge(
        private val pagedCuration: Boolean = false,
        private val invokeTts: Boolean = false,
        private val presenterWarning: String? = null,
        private val terminalErrorCount: Int = 0,
        private val readingRunFailure: RuntimeException? = null,
        private val raisedFailure: Boolean = false,
    ) : PyBridge {
        val readingRequest = AtomicReference<ReadingMiningWireRequest?>()
        val curationSubmitted = CountDownLatch(1)
        val intermediateCurationSubmitted = CountDownLatch(1)
        val cancellationSubmitted = CountDownLatch(1)
        val allowTerminal = CountDownLatch(1)
        val ttsSubmitted = CountDownLatch(if (invokeTts) 1 else 0)
        val ttsResult = AtomicReference<String?>()
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
                    cancelled.set(true)
                    cancellationSubmitted.countDown()
                    JOB_CANCELLED
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
                Thread.sleep(2)
            }
            if (invokeTts && !cancelled.get()) {
                ttsResult.set(callbacks.synthesizeSentenceAudio(TTS_REQUEST))
                ttsSubmitted.countDown()
            }
            check(allowTerminal.await(3, TimeUnit.SECONDS))
            if (cancelled.get()) {
                callbacks.onComplete(CANCELLED_TERMINAL)
                return CANCELLED_TERMINAL
            }
            val terminal = terminalPayload()
            if (terminalErrorCount == 0 && !raisedFailure) {
                callbacks.onComplete(terminal)
            } else {
                callbacks.onError(terminal)
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
        const val TOKENIZER_SHA =
            "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
        const val MAX_RESULT_ERRORS = 256
        val INPUT_DOCUMENT =
            SafDocument(
                uri = "content://reading/novel",
                displayName = "Novel.txt",
                mimeType = "text/plain",
                sizeBytes = 5L,
            )
        val INPUT = ReadingMiningInput(ReadingSourceSelection.Single(INPUT_DOCUMENT))
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
        const val MINED_TERM = "猫"

        /** Shaped like a real phase-4 event: the engine names the term it just looked up. */
        val HOSTILE_PROGRESS =
            """{"schemaVersion":1,"type":"progress.update","payload":{"runId":"$RUN_ID","current":2,"description":"Definition found: $MINED_TERM"}}"""
        const val PRESENTER_WARNING_MESSAGE = "Offline sentence audio is unavailable"
        const val PRESENTER_WARNING_PLACEHOLDER = "__WARNING__"
        val PRESENTER_WARNING =
            """{"schemaVersion":1,"type":"presenter.event","payload":{"runId":"$RUN_ID","kind":"warning","message":"$PRESENTER_WARNING_PLACEHOLDER"}}"""
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
        val SUCCESS_TERMINAL =
            """{"schemaVersion":1,"type":"mining.terminal","payload":{"runId":"$RUN_ID","outcome":"success","result":{"totalWordsFound":1,"newWordsFound":0,"cardsCreated":0,"errors":[],"elapsedTime":1.0,"comprehensionPercentage":100.0,"cardIds":[],"videoFile":"","subtitleFile":"Novel.txt","minedForms":[],"ankiWriteState":"no_note_write","failureIsTransient":false},"error":null}}"""
        val CANCELLED_TERMINAL =
            """{"schemaVersion":1,"type":"mining.terminal","payload":{"runId":"$RUN_ID","outcome":"cancelled","result":null,"error":{"code":"cancelled","message":"Mining was cancelled"}}}"""
        const val TERMINAL_FAULT_ID = "f0123abcd"
        val RAISED_FAILURE_TERMINAL =
            """{"schemaVersion":1,"type":"mining.terminal","payload":{"runId":"$RUN_ID","outcome":"failed","result":null,"error":{"code":"engine_error","message":"Mining failed","faultId":"$TERMINAL_FAULT_ID"}}}"""
    }
}
