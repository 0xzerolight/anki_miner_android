package com.ankiminer.android.reading

import com.ankiminer.android.R
import com.ankiminer.android.anki.protocol.ReleaseState
import com.ankiminer.android.anki.provider.AnkiReadFailure
import com.ankiminer.android.data.RuntimeWorkCoordinator
import com.ankiminer.android.diagnostics.exceptionDigest
import com.ankiminer.android.diagnostics.log.AppLog
import com.ankiminer.android.diagnostics.log.LogComponent
import com.ankiminer.android.diagnostics.log.LogContext
import com.ankiminer.android.engine.BridgeJsonCodec
import com.ankiminer.android.engine.BridgeJsonValue
import com.ankiminer.android.engine.BridgeMessage
import com.ankiminer.android.engine.BridgeProtocolException
import com.ankiminer.android.engine.EngineCallbacks
import com.ankiminer.android.engine.MiningConfigSnapshot
import com.ankiminer.android.engine.MiningOutcome
import com.ankiminer.android.engine.PresenterEvent
import com.ankiminer.android.engine.PresenterMessageKind
import com.ankiminer.android.engine.PyBridge
import com.ankiminer.android.engine.ReadingMiningSourceKind
import com.ankiminer.android.engine.ReadingMiningWireRequest
import com.ankiminer.android.engine.TokenizerConfiguration
import com.ankiminer.android.localization.EngineNoticeRewriter
import com.ankiminer.android.localization.StringResourceResolver
import com.ankiminer.android.media.FileCopyCancellation
import com.ankiminer.android.media.ProviderIoCancellationRegistration
import com.ankiminer.android.media.SafDocument
import com.ankiminer.android.mining.AlwaysReadyMiningRunAdmissionGate
import com.ankiminer.android.mining.CoordinatorAnkiCallbacks
import com.ankiminer.android.mining.CoordinatorAnkiCancellation
import com.ankiminer.android.mining.CurationRequest
import com.ankiminer.android.mining.CurationSelection
import com.ankiminer.android.mining.CurationSessionState
import com.ankiminer.android.mining.InstalledTokenizerResource
import com.ankiminer.android.mining.InstalledTokenizerResourceProvider
import com.ankiminer.android.mining.InterruptedMiningRun
import com.ankiminer.android.mining.MiningCancellationToken
import com.ankiminer.android.mining.MiningCancellationTokenFactory
import com.ankiminer.android.mining.MiningCommandException
import com.ankiminer.android.mining.MiningFailure
import com.ankiminer.android.mining.MiningForegroundStarter
import com.ankiminer.android.mining.MiningProgress
import com.ankiminer.android.mining.MiningProgressUnit
import com.ankiminer.android.mining.MiningRunAdmissionGate
import com.ankiminer.android.mining.MiningRunAdmissionState
import com.ankiminer.android.mining.MiningRunInterruptionStore
import com.ankiminer.android.mining.foregroundRunId
import com.ankiminer.android.mining.MiningRunKind
import com.ankiminer.android.mining.MiningRunState
import com.ankiminer.android.mining.MiningRuntimePaths
import com.ankiminer.android.mining.MiningStage
import com.ankiminer.android.mining.MiningTaskExecutor
import com.ankiminer.android.mining.NoOpMiningRunInterruptionStore
import com.ankiminer.android.mining.ProcessingResult
import com.ankiminer.android.mining.SecureMiningCancellationTokenFactory
import com.ankiminer.android.mining.SourceGrantReleaser
import com.ankiminer.android.mining.StartupInterruption
import com.ankiminer.android.mining.isTerminal
import com.ankiminer.android.mining.runId
import com.ankiminer.android.service.MiningForegroundCancellationReason
import com.ankiminer.android.service.MiningForegroundLease
import com.ankiminer.android.service.MiningForegroundProgress
import com.ankiminer.android.service.MiningForegroundProgressUnit
import com.ankiminer.android.service.MiningForegroundSessionIdentity
import com.ankiminer.android.service.MiningForegroundSessionListener
import com.ankiminer.android.tts.SentenceAudioCallbackDispatcher
import com.ankiminer.android.tts.SentenceAudioSynthesizer
import com.ankiminer.android.tts.SentenceAudioSynthesizerFactory
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class ReadingMiningInput(
    val selection: ReadingSourceSelection,
    val subtitleSeriesName: String? = null,
)

internal interface ReadingMiningRepository {
    val state: StateFlow<MiningRunState>

    fun curationSessionState(): CurationSessionState? = null

    fun saveCurationSessionState(state: CurationSessionState) {}

    fun clearCurationSessionState(runId: String? = null) {}

    /** Transfer selection-owned SAF grants to the matching live run during caller teardown. */
    fun detachActiveSources(input: ReadingMiningInput): Boolean = false

    suspend fun startReading(input: ReadingMiningInput)

    /** Confirm one exact curation page; an empty selection remains distinct from cancellation. */
    suspend fun confirmCuration(
        runId: String,
        requestId: String,
        selection: List<CurationSelection>,
        pageIndex: Long? = null,
        knownCandidateIds: List<String> = emptyList(),
    )

    suspend fun cancel(runId: String)

    suspend fun cancel(token: MiningCancellationToken) {
        throw MiningCommandException("The mining run cannot be cancelled before registration")
    }

    suspend fun reset()
}

internal fun interface ReadingConfigSnapshotResolver {
    fun resolve(input: ReadingMiningInput): MiningConfigSnapshot
}

/**
 * Process-scoped reading runner with the same parked-curation boundary as video mining.
 *
 * The source is copied to a bounded app-private stage before Python sees it. That stage remains
 * owned until the engine has returned, Anki run state has been released, and any foreground lease
 * has closed. Eligible media work claims foreground ownership before staging; text-only work uses
 * durable interruption state. The run and control executors remain independent so control can
 * reach parked Python.
 */
internal class BridgeReadingMiningRepository(
    private val pyBridge: PyBridge,
    private val anki: CoordinatorAnkiCallbacks,
    private val sourceStager: ReadingSourceStager,
    private val tokenizerResourceProvider: InstalledTokenizerResourceProvider,
    private val runtimePaths: MiningRuntimePaths,
    private val sourceGrantReleaser: SourceGrantReleaser,
    private val foregroundStarter: MiningForegroundStarter,
    private val runExecutor: MiningTaskExecutor,
    private val controlExecutor: MiningTaskExecutor,
    private val strings: StringResourceResolver,
    private val admissionGate: MiningRunAdmissionGate = AlwaysReadyMiningRunAdmissionGate,
    private val runtimeWorkCoordinator: RuntimeWorkCoordinator = RuntimeWorkCoordinator(),
    private val configSnapshotResolver: ReadingConfigSnapshotResolver =
        ReadingConfigSnapshotResolver {
            MiningConfigSnapshot(settings = emptyMap(), androidTtsEnabled = false)
        },
    private val resourceStartupReady: () -> Boolean = { true },
    private val sentenceAudioSynthesizerFactory: SentenceAudioSynthesizerFactory? = null,
    private val cancellationTokenFactory: MiningCancellationTokenFactory =
        SecureMiningCancellationTokenFactory(),
    private val foregroundStartTimeoutSeconds: Long = 15,
    private val interruptionStore: MiningRunInterruptionStore = NoOpMiningRunInterruptionStore,
) : ReadingMiningRepository {
    private enum class Phase {
        PREPARING,
        REGISTERED,
        CURATING,
        ADVANCING,
        PROMOTING,
        RUNNING,
        CANCELLING,
        FINALIZING,
    }

    /**
     * A phase change captured under [monitor] and emitted once the caller has left it.
     *
     * Rendering or writing a record inside the lock would put log work on the critical section that
     * every Python callback, every progress tick and every cancellation contends for.
     */
    private class PhaseTransition(
        val from: Phase,
        val to: Phase,
        val detail: String,
    )

    private data class ProtocolFault(
        val message: String,
        val retryable: Boolean = false,
        val diagnostic: String? = null,
    )

    private data class CancellationAction(
        val generation: Long,
        val cancellation: CoordinatorAnkiCancellation,
        val foregroundStart: CompletableFuture<MiningForegroundLease>?,
        val foregroundLease: MiningForegroundLease?,
    )

    private class ActiveRun(
        val generation: Long,
        val input: ReadingMiningInput,
        val cancellationToken: MiningCancellationToken,
        val workLease: RuntimeWorkCoordinator.Lease,
        val cancellation: CoordinatorAnkiCancellation = CoordinatorAnkiCancellation(),
    ) {
        var phase = Phase.PREPARING
        var runId: String? = null
        var progress: MiningProgress? = null
        var curation: CurationRequest? = null
        var terminalCallback: BridgeMessage.Terminal? = null
        var stickyFault: ProtocolFault? = null
        var cancellationDispatchFault: ProtocolFault? = null
        var cancelRequested = false
        var cancellationDispatchInFlight = false
        var cancellationAcknowledged = false
        var foregroundStart: CompletableFuture<MiningForegroundLease>? = null
        var foregroundLease: MiningForegroundLease? = null
        var foregroundClosingExpected = false
        var sourcesDetached = false
        var interruptionRecorded = false
        var configSnapshot: MiningConfigSnapshot? = null
        var sentenceAudioSynthesizer: SentenceAudioSynthesizer? = null
        var sentenceAudioDispatcher: SentenceAudioCallbackDispatcher? = null
        var requiresMediaForeground = false
        var hasSelectedCandidate = false
        val presenterNotices = mutableListOf<String>()
        val progressErrors = mutableListOf<String>()
    }

    private val monitor = Any()
    private val noticeRewriter = EngineNoticeRewriter(strings)
    private val startupInterruption = interruptionStore.startupInterruption()

    /** Recognised whichever lane wrote it: one durable record covers both, so either can clear it. */
    private val startupInterruptedRun =
        (startupInterruption as? StartupInterruption.Interrupted)?.record

    /** Only this lane's own run id belongs in this lane's state. */
    private val startupRunId =
        startupInterruptedRun?.takeIf { it.kind == MiningRunKind.READING }?.runId
    private val mutableState =
        MutableStateFlow<MiningRunState>(
            if (startupInterruption is StartupInterruption.None) {
                MiningRunState.Idle
            } else {
                ProtocolFault(strings.resolve(R.string.mining_failure_background_stopped))
                    .toFailed(startupRunId, result = null)
            },
        )
    override val state: StateFlow<MiningRunState> = mutableState.asStateFlow()
    internal val admissionState: StateFlow<MiningRunAdmissionState> = admissionGate.state
    private var active: ActiveRun? = null
    private var nextGeneration = 1L
    private var restartRequired: ProtocolFault? = null
    private var savedCurationSessionState: CurationSessionState? = null
    /** Run whose terminal callback already arrived; correlates late cancel replies. */
    private var terminatedRunId: String? = null
    private var pendingInterruptionCleanup: InterruptedMiningRun? = null

    init {
        require(foregroundStartTimeoutSeconds > 0)
    }

    override fun detachActiveSources(input: ReadingMiningInput): Boolean =
        synchronized(monitor) {
            val run = active ?: return@synchronized false
            if (run.input != input || run.sourcesDetached || run.phase == Phase.FINALIZING) {
                return@synchronized false
            }
            run.sourcesDetached = true
            true
        }

    override fun curationSessionState(): CurationSessionState? =
        synchronized(monitor) { savedCurationSessionState }

    override fun saveCurationSessionState(state: CurationSessionState) {
        synchronized(monitor) {
            if (mutableState.value.runId == state.runId) {
                savedCurationSessionState = state
            }
        }
    }

    override fun clearCurationSessionState(runId: String?) {
        synchronized(monitor) {
            if (runId == null || savedCurationSessionState?.runId == runId) {
                savedCurationSessionState = null
            }
        }
    }

    override suspend fun startReading(input: ReadingMiningInput) {
        val generation: Long
        val cancellationToken = cancellationTokenFactory.next()
        synchronized(monitor) {
            if (active != null || mutableState.value != MiningRunState.Idle) {
                throw MiningCommandException(strings.resolve(R.string.mining_failure_run_active))
            }
            restartRequired?.let { fault ->
                mutableState.value = fault.toFailed(runId = null, result = null)
                return
            }
            if (!resourceStartupReady()) {
                throw MiningCommandException(
                    "Resource recovery must finish before a mining run starts",
                )
            }
            val workLease =
                runtimeWorkCoordinator.tryAcquire(RuntimeWorkCoordinator.Kind.MINING)
                    ?: throw MiningCommandException(
                        "Another mining or resource setup task must finish before reading mining starts",
                    )
            generation = nextGeneration++
            savedCurationSessionState = null
            active = ActiveRun(generation, input, cancellationToken, workLease)
            mutableState.value =
                MiningRunState.Starting(
                    runId = null,
                    progress =
                        MiningProgress(
                            0,
                            0,
                            strings.resolve(R.string.mining_progress_preparing_reading_source),
                        ),
                    cancellationToken = cancellationToken,
                )
        }
        try {
            runExecutor.execute { runReading(generation) }
        } catch (_: RuntimeException) {
            recordFault(generation, strings.resolve(R.string.mining_failure_worker_start))
            finishRun(generation, terminal = null, stagedSource = null)
        }
    }

    override suspend fun confirmCuration(
        runId: String,
        requestId: String,
        selection: List<CurationSelection>,
        pageIndex: Long?,
        knownCandidateIds: List<String>,
    ) {
        val request =
            synchronized(monitor) {
                val run = active ?: throw MiningCommandException("No curation request is pending")
                val pending = run.curation ?: throw MiningCommandException("No curation request is pending")
                if (
                    run.phase != Phase.CURATING ||
                    pending.runId != runId ||
                    pending.requestId != requestId ||
                    pending.page?.pageIndex != pageIndex
                ) {
                    throw MiningCommandException("The curation response is stale")
                }
                pending
            }
        val rawResponse =
            try {
                BridgeJsonCodec.encodeCurationResponse(request, selection, knownCandidateIds)
            } catch (_: RuntimeException) {
                throw MiningCommandException("The curation selection is invalid")
            }
        var transition: PhaseTransition? = null
        var resumingLease: MiningForegroundLease? = null
        val (generation, hasSelectedCandidate) =
            synchronized(monitor) {
                val run = active ?: throw MiningCommandException("No curation request is pending")
                if (run.curation !== request || run.phase != Phase.CURATING) {
                    throw MiningCommandException("The curation response is stale")
                }
                resumingLease = run.foregroundLease
                run.hasSelectedCandidate = run.hasSelectedCandidate || selection.isNotEmpty()
                if (request.isFinalPage) {
                    transition = run.transition(Phase.PROMOTING, "curation_final")
                    val progress =
                        run.progress
                            ?: MiningProgress(0, 0, strings.resolve(R.string.mining_progress_starting_background))
                    mutableState.value = MiningRunState.Running(runId, progress)
                } else {
                    transition = run.transition(Phase.ADVANCING, "curation_page")
                    mutableState.value = MiningRunState.Curating(request, pageSubmissionPending = true)
                }
                run.generation to run.hasSelectedCandidate
            }
        transition.emit()
        // The user's wait is over; media processing resumes whichever page follows.
        resumingLease?.resumeCpuWake()
        if (request.isFinalPage) {
            val requiresMediaForeground =
                hasSelectedCandidate &&
                    synchronized(monitor) {
                        activeFor(generation)?.requiresMediaForeground == true
                    }
            if (requiresMediaForeground) {
                executeControl(generation) {
                    promoteAndSubmitCuration(generation, request, rawResponse)
                }
            } else {
                executeControl(generation) {
                    submitFinalCurationWithoutForeground(generation, request, rawResponse)
                }
            }
        } else {
            executeControl(generation) { submitIntermediateCurationPage(generation, request, rawResponse) }
        }
    }

    override suspend fun cancel(runId: String) {
        var transition: PhaseTransition? = null
        val cancellation =
            synchronized(monitor) {
                val run = active ?: throw MiningCommandException("The mining run cannot be cancelled")
                if (run.runId != runId || run.phase == Phase.FINALIZING) {
                    throw MiningCommandException("The mining run cannot be cancelled")
                }
                transition = markCancellationLocked(run)
                CancellationAction(
                    generation = run.generation,
                    cancellation = run.cancellation,
                    foregroundStart = run.foregroundStart,
                    foregroundLease = run.foregroundLease,
                )
            }
        transition.emit()
        forwardCancellation(cancellation)
    }

    override suspend fun cancel(token: MiningCancellationToken) {
        var transition: PhaseTransition? = null
        val cancellation =
            synchronized(monitor) {
                val run = active ?: throw MiningCommandException("The mining run cannot be cancelled")
                if (run.cancellationToken != token || run.phase == Phase.FINALIZING) {
                    throw MiningCommandException("The mining run cannot be cancelled")
                }
                transition = markCancellationLocked(run)
                CancellationAction(
                    generation = run.generation,
                    cancellation = run.cancellation,
                    foregroundStart = run.foregroundStart,
                    foregroundLease = run.foregroundLease,
                )
            }
        transition.emit()
        forwardCancellation(cancellation)
    }

    override suspend fun reset() {
        synchronized(monitor) {
            if (active != null || !mutableState.value.isTerminal) {
                throw MiningCommandException("Only a terminal mining run can be reset")
            }
            val interruption = pendingInterruptionCleanup ?: startupInterruptedRun
            val cleaned =
                when {
                    // The record's own kind, not this lane's: a crash in the other lane is cleared
                    // from here too, otherwise its record blocks every start on this screen.
                    interruption != null ->
                        interruptionStore.complete(
                            interruption.kind,
                            interruption.ownerId,
                        )
                    startupInterruption is StartupInterruption.Unrecognized ->
                        interruptionStore.clearUnrecognizedRecord()
                    else -> true
                }
            if (!cleaned) {
                throw MiningCommandException(
                    strings.resolve(R.string.mining_failure_background_stopped),
                )
            }
            pendingInterruptionCleanup = null
            savedCurationSessionState = null
            mutableState.value = MiningRunState.Idle
        }
    }

    private fun runReading(generation: Long) {
        // registerJob installs the ambient run id on this thread part-way through this block.
        // The video and reading repositories share one run executor, so leaving it set would
        // label the next lane's records with this run's id; withRunId restores rather than clears.
        LogContext.withRunId(null) {
            var stagedSource: StagedReadingSource? = null
            var terminal: BridgeMessage.Terminal? = null
            try {
                val run = requireActive(generation)
                if (run.cancellation.isCancelled()) return
                if (!beginInterruptionRecord(generation)) return
                if (run.cancellation.isCancelled()) return
                run.configSnapshot =
                    try {
                        configSnapshotResolver.resolve(run.input)
                    } catch (failure: Exception) {
                        recordFault(generation, strings.resolve(R.string.mining_failure_settings_snapshot))
                        throw failure
                    }
                if (run.cancellation.isCancelled()) return
                val admission =
                    try {
                        admissionGate.evaluate(run.cancellation)
                    } catch (_: RuntimeException) {
                        if (run.cancellation.isCancelled()) return
                        recordFault(generation, strings.resolve(R.string.mining_failure_anki_readiness))
                        return
                    }
                if (run.cancellation.isCancelled()) return
                if (!admission.isReady) {
                    val failure = requireNotNull(admission.stableFailure(strings))
                    recordFault(generation, failure.message, failure.retryable)
                    return
                }
                val tokenizer =
                    try {
                        tokenizerResourceProvider.installedResource()
                    } catch (failure: Exception) {
                        recordFault(generation, strings.resolve(R.string.mining_failure_tokenizer_inspection))
                        throw failure
                    }
                if (tokenizer == null) {
                    recordFault(
                        generation,
                        strings.resolve(R.string.mining_failure_tokenizer_required),
                        retryable = true,
                    )
                    return
                }
                if (run.cancellation.isCancelled()) return
                configureTokenizer(run, tokenizer)
                if (run.cancellation.isCancelled()) return
                run.requiresMediaForeground = requiresMediaForeground(run)
                if (run.requiresMediaForeground && !startForegroundOwnership(generation)) return
                if (run.cancellation.isCancelled()) return
                stagedSource =
                    try {
                        sourceStager.stage(
                            selection = run.input.selection,
                            cancellation =
                                object : FileCopyCancellation {
                                    override fun isCancelled(): Boolean =
                                        run.cancellation.isCancelled()

                                    override fun invokeOnCancellation(
                                        listener: () -> Unit,
                                    ): ProviderIoCancellationRegistration {
                                        val registration =
                                            run.cancellation.invokeOnCancellation(listener)
                                        return ProviderIoCancellationRegistration(registration::close)
                                    }
                                },
                            progressListener =
                                ReadingSourceStageProgressListener { progress ->
                                    updateStagingProgress(generation, progress)
                                },
                        )
                    } catch (failure: Exception) {
                        if (!run.cancellation.isCancelled()) {
                            recordFault(generation, strings.resolve(stagingFaultMessage(failure)))
                        }
                        throw failure
                    }
                if (run.cancellation.isCancelled()) return
                run.requiresMediaForeground =
                    run.requiresMediaForeground ||
                        stagedSource.sourceKind == StagedReadingSourceKind.EPUB ||
                        stagedSource.imageArchivePath != null ||
                        requireNotNull(run.configSnapshot).androidTtsEnabled == true ||
                        requireNotNull(run.configSnapshot).mapsExpressionAudioField()
                if (requireNotNull(run.configSnapshot).androidTtsEnabled == true) {
                    val synthesizer =
                        try {
                            sentenceAudioSynthesizerFactory?.open()
                                ?: throw IllegalStateException("Sentence-audio integration is unavailable")
                        } catch (failure: RuntimeException) {
                            recordFault(generation, strings.resolve(R.string.mining_failure_sentence_audio_preparation))
                            throw failure
                        }
                    run.sentenceAudioSynthesizer = synthesizer
                    run.sentenceAudioDispatcher = SentenceAudioCallbackDispatcher(synthesizer)
                }
                if (run.cancellation.isCancelled()) return
                val request =
                    ReadingMiningWireRequest(
                        sourceKind = stagedSource.sourceKind.toWireKind(),
                        sourcePath = stagedSource.detectorPath,
                        imageArchivePath = stagedSource.imageArchivePath,
                        seriesName =
                            if (stagedSource.sourceKind == StagedReadingSourceKind.SUBTITLE) {
                                canonicalLabel(
                                    run.input.subtitleSeriesName ?: DEFAULT_SUBTITLE_SERIES_NAME,
                                ).ifEmpty { DEFAULT_SUBTITLE_SERIES_NAME }
                            } else {
                                null
                            },
                        cacheDir = runtimePaths.cacheDir.canonicalPath,
                        nativeLibraryDir = runtimePaths.nativeLibraryDir.canonicalPath,
                        configSnapshot = requireNotNull(run.configSnapshot),
                    )
                val rawResult =
                    pyBridge.dispatch(
                        BridgeJsonCodec.encodeReadingRun(request),
                        RunCallbacks(generation),
                    )
                terminal = reconcileTerminal(generation, rawResult)
            } catch (failure: Exception) {
                if (!isCancellationRequested(generation)) {
                    recordFault(
                        generation,
                        strings.resolve(
                            R.string.mining_failure_embedded_reading_detailed,
                            listOf(exceptionDigest(failure)),
                        ),
                    )
                }
            } finally {
                finishRun(generation, terminal, stagedSource)
            }
        }
    }

    private fun stagingFaultMessage(failure: Throwable): Int =
        when ((failure as? EmbeddedSidecarException)?.failure) {
            EmbeddedSidecarFailure.NO_MOKURO_MEMBER ->
                R.string.mining_failure_reading_archive_no_mokuro
            EmbeddedSidecarFailure.MULTIPLE_MOKURO_MEMBERS ->
                R.string.mining_failure_reading_archive_multiple_mokuro
            EmbeddedSidecarFailure.UNREADABLE_ARCHIVE ->
                R.string.mining_failure_reading_archive_unreadable
            null -> R.string.mining_failure_reading_source_preparation
        }

    private fun configureTokenizer(
        run: ActiveRun,
        tokenizer: InstalledTokenizerResource,
    ) {
        val raw =
            try {
                pyBridge.dispatch(
                    BridgeJsonCodec.encodeTokenizerConfigure(
                        TokenizerConfiguration(
                            dicDir = tokenizer.dicDir.canonicalPath,
                            resourceId = tokenizer.resourceId,
                            treeSha256 = tokenizer.treeSha256,
                            backend = tokenizer.backend,
                        ),
                    ),
                    null,
                )
            } catch (failure: Exception) {
                recordFault(run.generation, strings.resolve(R.string.mining_failure_tokenizer_setup))
                throw failure
            }
        val decoded =
            try {
                BridgeJsonCodec.decode(raw)
            } catch (failure: RuntimeException) {
                recordFault(run.generation, strings.resolve(R.string.mining_failure_tokenizer_response))
                throw failure
            }
        when (val response = decoded) {
            is BridgeMessage.TokenizerReady -> {
                val identity = response.identity
                if (
                    identity.dicDir != tokenizer.dicDir.canonicalPath ||
                    identity.resourceId != tokenizer.resourceId ||
                    identity.treeSha256 != tokenizer.treeSha256 ||
                    identity.backend != tokenizer.backend ||
                    identity.fileCount <= 0 ||
                    identity.totalBytes <= 0
                ) {
                    recordFault(run.generation, strings.resolve(R.string.mining_failure_tokenizer_identity))
                    throw MiningCommandException("Tokenizer identity did not match its installed resource")
                }
            }
            is BridgeMessage.Error -> {
                if (response.code == "tokenizer_restart_required") {
                    setRestartRequired(strings.resolve(R.string.mining_failure_tokenizer_restart))
                }
                recordFault(run.generation, strings.resolve(R.string.mining_failure_tokenizer_verification))
                throw MiningCommandException("Tokenizer setup was rejected")
            }
            else -> {
                recordFault(run.generation, strings.resolve(R.string.mining_failure_tokenizer_response))
                throw MiningCommandException("Tokenizer setup returned an invalid response")
            }
        }
    }

    private fun reconcileTerminal(
        generation: Long,
        rawResult: String,
    ): BridgeMessage.Terminal? {
        val runId = synchronized(monitor) { activeFor(generation)?.runId }
        val returned =
            try {
                BridgeJsonCodec.decode(rawResult, expectedRunId = runId)
            } catch (_: RuntimeException) {
                recordFault(generation, strings.resolve(R.string.mining_failure_terminal_response))
                return null
            }
        val terminal =
            when (returned) {
                is BridgeMessage.Terminal -> returned
                is BridgeMessage.Error -> {
                    recordFault(generation, strings.resolve(R.string.mining_failure_reading_request_rejected))
                    return null
                }
                else -> {
                    recordFault(generation, strings.resolve(R.string.mining_failure_non_terminal_response))
                    return null
                }
            }
        val callback = synchronized(monitor) { activeFor(generation)?.terminalCallback }
        if (callback != null && callback.rawEnvelope != terminal.rawEnvelope) {
            recordFault(generation, strings.resolve(R.string.mining_failure_terminal_disagreement))
        }
        return terminal
    }

    private fun finishRun(
        generation: Long,
        terminal: BridgeMessage.Terminal?,
        stagedSource: StagedReadingSource?,
    ) {
        val runId: String?
        val foregroundLease: MiningForegroundLease?
        val runtimeWorkLease: RuntimeWorkCoordinator.Lease
        val sentenceAudioSynthesizer: SentenceAudioSynthesizer?
        val cancelled: Boolean
        val transition: PhaseTransition?
        var terminalForState = terminal
        synchronized(monitor) {
            val run = activeFor(generation) ?: return
            transition =
                if (run.phase == Phase.FINALIZING) {
                    null
                } else {
                    run.transition(Phase.FINALIZING, "finish")
                }
            run.foregroundClosingExpected = true
            runId = run.runId
            foregroundLease = run.foregroundLease
            runtimeWorkLease = run.workLease
            sentenceAudioSynthesizer = run.sentenceAudioSynthesizer
            cancelled = run.cancelRequested || run.cancellation.isCancelled()
            if (terminalForState == null) terminalForState = run.terminalCallback
        }
        transition?.emit()
        try {
            if (runId != null) releaseAnkiFallback(generation, runId)
            try {
                sentenceAudioSynthesizer?.close()
            } catch (failure: RuntimeException) {
                AppLog.w(LogComponent.READING, "sentenceAudio.close", failure, "outcome" to "fail")
                recordFault(generation, strings.resolve(R.string.mining_failure_sentence_audio_cleanup))
            }
            try {
                stagedSource?.close()
            } catch (failure: Exception) {
                // StagedReadingSource.close() folds every staged file's delete failure into one
                // IOException and hangs the rest off addSuppressed, preserving every cleanup
                // failure in one throwable chain.
                AppLog.w(LogComponent.READING, "source.close", failure, "outcome" to "fail")
                recordFault(generation, strings.resolve(R.string.mining_failure_reading_source_cleanup))
            }
            try {
                foregroundLease?.close()
            } catch (failure: RuntimeException) {
                AppLog.w(LogComponent.READING, "foreground.close", failure, "outcome" to "fail")
                recordFault(generation, strings.resolve(R.string.mining_failure_background_cleanup))
            }
            val interruption =
                synchronized(monitor) {
                    activeFor(generation)?.let { run ->
                        if (run.interruptionRecorded) {
                            InterruptedMiningRun(
                                MiningRunKind.READING,
                                run.cancellationToken.value,
                                run.runId,
                            )
                        } else {
                            null
                        }
                    }
                }
            if (
                interruption != null &&
                !interruptionStore.complete(MiningRunKind.READING, interruption.ownerId)
            ) {
                synchronized(monitor) {
                    pendingInterruptionCleanup = interruption
                }
                recordFault(generation, strings.resolve(R.string.mining_failure_background_cleanup))
            }

            val detachedInput: ReadingMiningInput?
            val runFault: ProtocolFault?
            val terminalNotices: List<String>
            synchronized(monitor) {
                val run = activeFor(generation) ?: return
                detachedInput = run.input.takeIf { run.sourcesDetached }
                runFault = run.stickyFault ?: run.cancellationDispatchFault
                terminalNotices =
                    (run.presenterNotices + run.progressErrors)
                        .distinct()
                        .take(MAX_RESULT_ERRORS)
                // A cancellation dispatch can still be in flight here. Remember which run
                // reported terminal so a late no_active_job reply is recognised as the
                // acknowledgement for THIS run instead of being retried.
                terminatedRunId = run.terminalCallback?.runId ?: terminatedRunId
                active = null
                savedCurationSessionState = null
            }
            val detachedCleanupFault =
                detachedInput?.selection?.documents()?.let(::releaseDetachedSources)

            val finalState: MiningRunState
            synchronized(monitor) {
                finalState =
                    terminalState(
                        runId = runId,
                        terminal = terminalForState,
                        fault = runFault ?: detachedCleanupFault,
                        cancelled = cancelled,
                        terminalNotices = terminalNotices,
                    )
                mutableState.value = finalState
            }
            logTerminal(finalState, terminalNotices.size)
        } finally {
            runtimeWorkLease.close()
        }
    }

    /**
     * The one record of how a run ended. Only the mapped outcome, the non-localized diagnostic code
     * and counts cross into it: the terminal's `message` and the presenter notices are engine-authored
     * and can name mined terms.
     */
    private fun logTerminal(
        state: MiningRunState,
        notices: Int,
    ) {
        val failure = (state as? MiningRunState.Failed)?.failure
        AppLog.i(
            LogComponent.READING,
            "run.terminal",
            "outcome" to
                when (state) {
                    is MiningRunState.Success -> "ok"
                    is MiningRunState.Cancelled -> "skip"
                    else -> "fail"
                },
            "code" to failure?.diagnostic,
            "retryable" to failure?.retryable,
            "notices" to notices,
        )
    }

    private fun releaseAnkiFallback(
        generation: Long,
        runId: String,
    ) {
        val state =
            try {
                anki.releaseRunStateFallback(runId)
            } catch (_: RuntimeException) {
                setRestartRequired(strings.resolve(R.string.mining_failure_restart_required))
                recordFault(generation, strings.resolve(R.string.mining_failure_anki_cleanup))
                return
            }
        if (state != ReleaseState.RELEASED && state != ReleaseState.ABSENT) {
            setRestartRequired(strings.resolve(R.string.mining_failure_restart_required))
            recordFault(generation, strings.resolve(R.string.mining_failure_anki_cleanup_incomplete))
        }
    }

    private fun releaseDetachedSource(uri: String): ProtocolFault? =
        try {
            sourceGrantReleaser.release(uri)
            null
        } catch (_: Exception) {
            ProtocolFault(strings.resolve(R.string.mining_failure_document_permission_cleanup))
        }

    private fun releaseDetachedSources(documents: List<SafDocument>): ProtocolFault? {
        var firstFault: ProtocolFault? = null
        documents.forEach { document ->
            val fault = releaseDetachedSource(document.uri)
            if (firstFault == null) firstFault = fault
        }
        return firstFault
    }

    private fun terminalState(
        runId: String?,
        terminal: BridgeMessage.Terminal?,
        fault: ProtocolFault?,
        cancelled: Boolean,
        terminalNotices: List<String>,
    ): MiningRunState {
        val result = terminal?.result.withTerminalNotices(terminalNotices)
        // A Kotlin fault outranks the Python terminal's message, but not its fault id: this pairing
        // is a Kotlin protocol failure and a Python traceback describing the same run, which is
        // exactly the case a maintainer needs the log key for.
        if (fault != null) return fault.toFailed(runId, result, terminal?.error?.faultId)
        if (terminal == null && cancelled) return MiningRunState.Cancelled(runId, null)
        if (terminal == null) {
            return ProtocolFault(strings.resolve(R.string.mining_failure_missing_result)).toFailed(runId, null)
        }
        return when (terminal.outcome) {
            MiningOutcome.SUCCESS ->
                MiningRunState.Success(terminal.runId, requireNotNull(result))
            MiningOutcome.CANCELLED -> MiningRunState.Cancelled(terminal.runId, result)
            MiningOutcome.FAILED ->
                MiningRunState.Failed(
                    runId = terminal.runId,
                    failure =
                        MiningFailure(
                            message =
                                terminal.error?.message
                                    ?: terminalNotices.firstOrNull()
                                    ?: strings.resolve(R.string.mining_failure_generic),
                            retryable = terminal.error?.code in RETRYABLE_TERMINAL_ERRORS,
                            faultId = terminal.error?.faultId,
                            // The engine's own terminal code: already snake_case on the wire and
                            // already validated by the codec, so there is nothing to invent here.
                            diagnostic = terminal.error?.code,
                        ),
                    result = result,
                )
        }
    }

    private fun startForegroundOwnership(generation: Long): Boolean {
        val listener =
            MiningForegroundSessionListener { identity, reason ->
                onForegroundCancellation(identity, reason)
            }
        val foregroundRunId =
            synchronized(monitor) {
                activeFor(generation)?.cancellationToken?.foregroundRunId(MiningRunKind.READING)
                    ?: return false
            }
        val future =
            try {
                foregroundStarter.startSession(foregroundRunId, generation, listener)
            } catch (failure: RuntimeException) {
                if (!isCancellationRequested(generation)) {
                    AppLog.e(
                        LogComponent.READING,
                        "foreground.start",
                        failure,
                        "outcome" to "fail",
                        "code" to "foreground_start_rejected",
                    )
                    recordFaultAndCancel(
                        generation,
                        strings.resolve(R.string.mining_failure_background_start),
                        diagnostic = "foreground_start_rejected",
                    )
                }
                return false
            }
        val await =
            synchronized(monitor) {
                val run = activeFor(generation)
                if (run == null || run.phase == Phase.FINALIZING || run.cancelRequested) {
                    false
                } else {
                    run.foregroundStart = future
                    true
                }
            }
        if (!await) {
            future.cancel(false)
            return false
        }
        val lease =
            try {
                future.get(foregroundStartTimeoutSeconds, TimeUnit.SECONDS)
            } catch (_: CancellationException) {
                handleForegroundStartCancellation(generation, future)
                return false
            } catch (failure: TimeoutException) {
                handleForegroundStartFailure(
                    generation,
                    future,
                    failure,
                    "foreground_start_timeout",
                )
                return false
            } catch (failure: ExecutionException) {
                handleForegroundStartFailure(
                    generation,
                    future,
                    failure,
                    "foreground_start_failed",
                )
                return false
            } catch (failure: InterruptedException) {
                Thread.currentThread().interrupt()
                handleForegroundStartFailure(
                    generation,
                    future,
                    failure,
                    "foreground_start_interrupted",
                )
                return false
            }
        var initialProgress: MiningProgress? = null
        val accepted =
            synchronized(monitor) {
                val run = activeFor(generation)
                if (
                    run == null ||
                    run.phase == Phase.FINALIZING ||
                    run.cancelRequested ||
                    run.foregroundStart !== future
                ) {
                    false
                } else {
                    run.foregroundStart = null
                    run.foregroundLease = lease
                    initialProgress = run.progress
                    true
                }
            }
        if (!accepted) {
            try {
                lease.close()
            } catch (_: RuntimeException) {
                recordFault(generation, strings.resolve(R.string.mining_failure_background_cleanup))
            }
            return false
        }
        return initialProgress?.let { publishForegroundProgress(generation, lease, it) } ?: true
    }

    private fun beginInterruptionRecord(generation: Long): Boolean {
        val ownerId =
            synchronized(monitor) {
                activeFor(generation)?.cancellationToken?.value ?: return false
            }
        if (!interruptionStore.begin(MiningRunKind.READING, ownerId)) {
            recordFault(generation, strings.resolve(R.string.mining_failure_background_stopped))
            return false
        }
        synchronized(monitor) {
            val run = activeFor(generation)
            if (run == null || run.phase == Phase.FINALIZING) {
                interruptionStore.complete(MiningRunKind.READING, ownerId)
                return false
            }
            run.interruptionRecorded = true
        }
        return true
    }

    private fun promoteAndSubmitCuration(
        generation: Long,
        request: CurationRequest,
        rawResponse: String,
    ) {
        var transition: PhaseTransition? = null
        val shouldSubmit =
            synchronized(monitor) {
                val run = activeFor(generation)
                if (
                    run != null &&
                        run.runId == request.runId &&
                        run.foregroundLease != null &&
                        !run.cancelRequested &&
                        run.phase == Phase.PROMOTING
                ) {
                    transition = run.transition(Phase.RUNNING, "foreground_started")
                    true
                } else {
                    false
                }
            }
        transition.emit()
        if (!shouldSubmit) {
            sendCancellation(generation)
            return
        }
        submitFinalCurationResponse(generation, request, rawResponse)
    }

    private fun handleForegroundStartFailure(
        generation: Long,
        future: CompletableFuture<MiningForegroundLease>,
        failure: Exception,
        diagnostic: String,
    ) {
        future.cancel(false)
        synchronized(monitor) {
            activeFor(generation)?.takeIf { it.foregroundStart === future }?.foregroundStart = null
        }
        if (isCancellationRequested(generation)) return
        AppLog.e(
            LogComponent.READING,
            "foreground.start",
            failure,
            "outcome" to "fail",
            "code" to diagnostic,
            "timeoutSeconds" to foregroundStartTimeoutSeconds,
        )
        recordFaultAndCancel(
            generation,
            strings.resolve(R.string.mining_failure_background_start_unsafe),
            diagnostic = diagnostic,
        )
    }

    private fun handleForegroundStartCancellation(
        generation: Long,
        future: CompletableFuture<MiningForegroundLease>,
    ) {
        future.cancel(false)
        var transition: PhaseTransition? = null
        val action =
            synchronized(monitor) {
                val run = activeFor(generation) ?: return
                if (run.foregroundStart === future) run.foregroundStart = null
                transition = markCancellationLocked(run)
                CancellationAction(
                    generation = run.generation,
                    cancellation = run.cancellation,
                    foregroundStart = run.foregroundStart,
                    foregroundLease = run.foregroundLease,
                )
            }
        transition.emit()
        AppLog.d(LogComponent.READING, "foreground.start") {
            arrayOf("outcome" to "skip", "code" to "foreground_start_cancelled")
        }
        forwardCancellation(action)
    }

    /**
     * Resume text-only reading work without claiming the ineligible media-processing
     * foreground-service type. Durable interruption state makes an Android process stop explicit
     * and recoverable instead of silently returning the repository to Idle.
     */
    private fun submitFinalCurationWithoutForeground(
        generation: Long,
        request: CurationRequest,
        rawResponse: String,
    ) {
        var transition: PhaseTransition? = null
        val shouldSubmit =
            synchronized(monitor) {
                val run = activeFor(generation)
                if (
                    run != null &&
                    run.runId == request.runId &&
                    run.phase == Phase.PROMOTING &&
                    !run.cancelRequested
                ) {
                    transition = run.transition(Phase.RUNNING, "no_foreground")
                    true
                } else {
                    false
                }
            }
        transition.emit()
        if (!shouldSubmit) {
            sendCancellation(generation)
            return
        }
        submitFinalCurationResponse(generation, request, rawResponse)
    }

    private fun submitFinalCurationResponse(
        generation: Long,
        request: CurationRequest,
        rawResponse: String,
    ) {
        val response =
            try {
                pyBridge.dispatch(rawResponse, null)
            } catch (_: RuntimeException) {
                recordFaultAndCancel(generation, strings.resolve(R.string.mining_failure_curation_rejected))
                return
            }
        val accepted =
            try {
                BridgeJsonCodec.decode(
                    response,
                    expectedRunId = request.runId,
                    expectedRequestId = request.requestId,
                )
            } catch (_: RuntimeException) {
                recordFaultAndCancel(generation, strings.resolve(R.string.mining_failure_curation_ack_invalid))
                return
            }
        val validAcknowledgement =
            when (accepted) {
                is BridgeMessage.CurationAccepted -> request.page == null
                is BridgeMessage.CurationPageAccepted ->
                    request.page?.pageIndex == accepted.pageIndex && accepted.finalPage
                else -> false
            }
        if (!validAcknowledgement) {
            recordFaultAndCancel(generation, strings.resolve(R.string.mining_failure_curation_not_accepted))
        }
    }

    private fun submitIntermediateCurationPage(
        generation: Long,
        request: CurationRequest,
        rawResponse: String,
    ) {
        val page = request.page
            ?: run {
                recordFaultAndCancel(generation, strings.resolve(R.string.mining_failure_curation_page_metadata))
                return
            }
        val shouldSubmit =
            synchronized(monitor) {
                val run = activeFor(generation)
                run != null &&
                    run.runId == request.runId &&
                    run.curation === request &&
                    run.phase == Phase.ADVANCING &&
                    !run.cancelRequested
            }
        if (!shouldSubmit) return
        val response =
            try {
                pyBridge.dispatch(rawResponse, null)
            } catch (_: RuntimeException) {
                if (isCancellationRequested(generation)) return
                recordFaultAndCancel(generation, strings.resolve(R.string.mining_failure_curation_page_rejected))
                return
            }
        val accepted =
            try {
                BridgeJsonCodec.decode(
                    response,
                    expectedRunId = request.runId,
                    expectedRequestId = request.requestId,
                )
            } catch (_: RuntimeException) {
                if (isCancellationRequested(generation)) return
                recordFaultAndCancel(generation, strings.resolve(R.string.mining_failure_curation_page_ack_invalid))
                return
            }
        if (isCancellationRequested(generation)) return
        if (
            accepted !is BridgeMessage.CurationPageAccepted ||
            accepted.pageIndex != page.pageIndex ||
            accepted.finalPage
        ) {
            recordFaultAndCancel(generation, strings.resolve(R.string.mining_failure_curation_page_not_accepted))
        }
    }

    private fun onForegroundCancellation(
        identity: MiningForegroundSessionIdentity,
        reason: MiningForegroundCancellationReason,
    ) {
        var transition: PhaseTransition? = null
        val cancellation =
            synchronized(monitor) {
                val run = activeFor(identity.generation) ?: return
                if (
                    run.foregroundClosingExpected ||
                    (run.foregroundLease != null && run.foregroundLease?.identity != identity)
                ) {
                    return
                }
                if (
                    reason != MiningForegroundCancellationReason.USER_REQUESTED &&
                    !run.cancelRequested
                ) {
                    if (run.stickyFault == null) {
                        run.stickyFault = ProtocolFault(strings.resolve(R.string.mining_failure_background_stopped))
                    }
                }
                transition = markCancellationLocked(run)
                CancellationAction(
                    generation = run.generation,
                    cancellation = run.cancellation,
                    foregroundStart = run.foregroundStart,
                    foregroundLease = run.foregroundLease,
                )
            }
        transition.emit()
        forwardCancellation(cancellation)
    }

    private fun sendCancellation(generation: Long) {
        val runId =
            synchronized(monitor) {
                val run = activeFor(generation) ?: return
                val registered = run.runId ?: return
                if (
                    run.cancellationAcknowledged ||
                    run.cancellationDispatchInFlight ||
                    run.phase == Phase.FINALIZING
                ) {
                    return
                }
                run.cancellationDispatchInFlight = true
                registered
            }
        var failureMessage = strings.resolve(R.string.mining_failure_cancellation_dispatch)
        repeat(MAX_CANCELLATION_DISPATCH_ATTEMPTS) {
            val response =
                try {
                    pyBridge.dispatch(BridgeJsonCodec.encodeJobCancel(runId), null)
                } catch (_: RuntimeException) {
                    failureMessage = strings.resolve(R.string.mining_failure_cancellation_dispatch)
                    return@repeat
                }
            val decoded =
                try {
                    BridgeJsonCodec.decode(response, expectedRunId = runId)
                } catch (_: RuntimeException) {
                    failureMessage = strings.resolve(R.string.mining_failure_cancellation_ack_invalid)
                    return@repeat
                }
            val accepted =
                decoded is BridgeMessage.JobCancelled ||
                    (
                        decoded is BridgeMessage.Error &&
                            decoded.code == "no_active_job" &&
                            synchronized(monitor) {
                                activeFor(generation)?.terminalCallback?.runId == runId ||
                                    terminatedRunId == runId
                            }
                    )
            if (accepted) {
                synchronized(monitor) {
                    activeFor(generation)?.let { run ->
                        run.cancellationDispatchInFlight = false
                        run.cancellationAcknowledged = true
                        run.cancellationDispatchFault = null
                    }
                }
                return
            }
            failureMessage = strings.resolve(R.string.mining_failure_cancellation_not_acknowledged)
        }
        synchronized(monitor) {
            activeFor(generation)?.let { run ->
                run.cancellationDispatchInFlight = false
                if (run.terminalCallback != null) {
                    run.cancellationAcknowledged = true
                    run.cancellationDispatchFault = null
                } else {
                    run.cancellationDispatchFault = ProtocolFault(failureMessage)
                }
            }
        }
    }

    private fun updateStagingProgress(
        generation: Long,
        stage: ReadingSourceStageProgress,
    ) {
        val description =
            when (stage.role) {
                ReadingSourceStageRole.TXT -> R.string.reading_progress_preparing_text
                ReadingSourceStageRole.PASTED_TEXT -> R.string.reading_progress_preparing_pasted_text
                ReadingSourceStageRole.EPUB -> R.string.reading_progress_preparing_epub
                ReadingSourceStageRole.SUBTITLE -> R.string.reading_progress_preparing_subtitle
                ReadingSourceStageRole.MOKURO_SIDECAR -> R.string.reading_progress_preparing_mokuro_sidecar
                ReadingSourceStageRole.MOKURO_ARCHIVE -> R.string.reading_progress_preparing_mokuro_images
            }
        val expected = stage.expectedBytes ?: 0L
        updateProgress(
            generation,
            MiningProgress(
                // SAF metadata can undershoot the real size, so the copied count clamps to the
                // total it is measured against.
                current = if (expected > 0L) stage.copiedBytes.coerceAtMost(expected) else stage.copiedBytes,
                total = expected,
                description = strings.resolve(description),
                // Staging counts bytes; without this the UI and the notification both label them
                // items.
                unit = MiningProgressUnit.BYTES,
            ),
        )
    }

    private fun updateProgress(
        generation: Long,
        progress: MiningProgress,
    ) {
        val lease: MiningForegroundLease?
        val published: MiningProgress
        synchronized(monitor) {
            val run = activeFor(generation) ?: return
            val previous = run.progress
            published =
                if (progress.stage != null && previous?.stage != null) {
                    // One stage counts several inner cycles and every cycle restarts at zero. Once
                    // the whole-run scale is established on both sides the bar never moves back.
                    progress.copy(fractionFloor = maxOf(previous.fraction ?: 0f, previous.fractionFloor))
                } else {
                    // Kotlin SAF staging (stage-null bytes) is its own lifecycle; flooring across
                    // that boundary would pin the engine bar at 100% before stage 1.
                    progress
                }
            run.progress = published
            when (run.phase) {
                Phase.PREPARING, Phase.REGISTERED ->
                    mutableState.value =
                        MiningRunState.Starting(
                            runId = run.runId,
                            progress = published,
                            cancellationToken = run.cancellationToken,
                            cancellationPending = run.phase == Phase.CANCELLING,
                        )
                Phase.PROMOTING, Phase.RUNNING ->
                    run.runId?.let {
                        mutableState.value =
                            MiningRunState.Running(
                                it,
                                published,
                            )
                    }
                // The panel the user is looking at keeps counting through curation, page advances,
                // cancellation and the terminal handoff. Curating owns no progress slot, so it is
                // left alone.
                Phase.CANCELLING, Phase.CURATING, Phase.ADVANCING, Phase.FINALIZING ->
                    mutableState.value =
                        when (val state = mutableState.value) {
                            is MiningRunState.Starting -> state.copy(progress = published)
                            is MiningRunState.Running -> state.copy(progress = published)
                            else -> state
                        }
            }
            lease = run.foregroundLease
        }
        if (lease != null) publishForegroundProgress(generation, lease, published)
    }

    private fun publishForegroundProgress(
        generation: Long,
        lease: MiningForegroundLease,
        progress: MiningProgress,
    ): Boolean {
        val determinate =
            progress.total in 1..Int.MAX_VALUE.toLong() &&
                progress.current <= Int.MAX_VALUE.toLong()
        // progress.description is engine-authored and can name mined terms; it stays out of the
        // notification entirely. Only the counts cross this boundary.
        val converted =
            MiningForegroundProgress(
                completed = progress.current.takeIf { determinate }?.toInt(),
                total = progress.total.takeIf { determinate }?.toInt(),
                unit =
                    when (progress.unit) {
                        MiningProgressUnit.ITEMS -> MiningForegroundProgressUnit.ITEMS
                        MiningProgressUnit.BYTES -> MiningForegroundProgressUnit.BYTES
                    },
            )
        val accepted =
            try {
                lease.updateProgress(converted)
            } catch (_: RuntimeException) {
                false
            }
        if (!accepted) recordFaultAndCancel(generation, strings.resolve(R.string.mining_failure_progress_session))
        return accepted
    }

    private fun registerJob(
        generation: Long,
        raw: String,
    ): String {
        val request =
            BridgeJsonCodec.decode(raw) as? BridgeMessage.JobRegistrationRequest
                ?: throw IllegalStateException("Python sent an invalid job registration")
        val cancellation: CoordinatorAnkiCancellation
        synchronized(monitor) {
            val run = activeFor(generation) ?: throw IllegalStateException("Mining registration is stale")
            val cancellableRegistrationRace = run.phase == Phase.CANCELLING && run.cancelRequested
            if ((run.phase != Phase.PREPARING && !cancellableRegistrationRace) || run.runId != null) {
                throw IllegalStateException("Mining registration is duplicated")
            }
            run.runId = request.runId
            cancellation = run.cancellation
        }
        // The first moment the run id exists. Python calls back synchronously on this same
        // thread and the Anki chain below is plain delegation, so onStage, ankiCreateNotes, the
        // provider gateway and the journal all pick the id up from the thread local with no
        // call-site changes. The one exception is the gateway's "anki-provider-deadline"
        // watchdog, which is its own thread and will render run=- until it is threaded.
        LogContext.setRunId(request.runId)
        val admitted = anki.registerRun(request.runId, cancellation)
        if (!admitted) {
            recordFaultAndCancel(generation, strings.resolve(R.string.mining_failure_anki_not_ready))
            throw IllegalStateException("Anki run registration was rejected")
        }
        var transition: PhaseTransition? = null
        interruptionStore.registered(
            MiningRunKind.READING,
            synchronized(monitor) {
                activeFor(generation)?.cancellationToken?.value
                    ?: throw IllegalStateException("Mining registration is stale")
            },
            request.runId,
        )
        val forwardCancellation =
            synchronized(monitor) {
                val run = activeFor(generation) ?: throw IllegalStateException("Mining registration is stale")
                transition =
                    run.transition(
                        if (run.cancelRequested) Phase.CANCELLING else Phase.REGISTERED,
                        "registration",
                    )
                mutableState.value =
                    MiningRunState.Starting(
                        runId = run.runId,
                        progress = run.progress,
                        cancellationToken = run.cancellationToken,
                        cancellationPending = run.cancelRequested,
                    )
                run.cancelRequested
            }
        transition.emit()
        if (forwardCancellation) executeCancellation(generation)
        return BridgeJsonCodec.encodeRegistrationAccepted(request.runId)
    }

    private fun acceptCuration(
        generation: Long,
        raw: String,
    ) {
        val runId = synchronized(monitor) { activeFor(generation)?.runId }
        val message =
            BridgeJsonCodec.decode(raw, expectedRunId = runId) as? BridgeMessage.CurationNeeded
                ?: throw IllegalStateException("Python sent an invalid curation request")
        var parkingLease: MiningForegroundLease? = null
        val admittedPhase =
            synchronized(monitor) {
                val run = activeFor(generation) ?: throw IllegalStateException("Curation request is stale")
                if (run.cancelRequested) return
                when (run.phase) {
                    Phase.REGISTERED -> {
                        val firstPageIndex = message.request.page?.pageIndex
                        if (run.curation != null || (firstPageIndex != null && firstPageIndex != 0L)) {
                            throw IllegalStateException("Curation request is duplicated or out of order")
                        }
                    }
                    Phase.ADVANCING -> {
                        val previous = run.curation
                            ?: throw IllegalStateException("Curation page has no predecessor")
                        val previousPage = previous.page
                            ?: throw IllegalStateException("A single curation request cannot advance")
                        val nextPage = message.request.page
                            ?: throw IllegalStateException("A paged curation request cannot become single")
                        if (
                            previous.isFinalPage ||
                            message.request.runId != previous.runId ||
                            message.request.requestId != previous.requestId ||
                            nextPage.pageIndex != previousPage.pageIndex + 1 ||
                            nextPage.pageCount != previousPage.pageCount ||
                            nextPage.totalCandidates != previousPage.totalCandidates ||
                            nextPage.candidateStart !=
                                previousPage.candidateStart + previous.candidates.size.toLong()
                        ) {
                            throw IllegalStateException("Curation page is duplicated or out of order")
                        }
                    }
                    else -> throw IllegalStateException("Curation request is duplicated or out of order")
                }
                parkingLease = run.foregroundLease
                run.phase
            }
        // The engine is parked on a threading.Event until the user answers. Nothing is processed
        // meanwhile, so the six-hour media-processing wake lease has no work behind it.
        //
        // The lease call is a Binder round trip and cannot be held under `monitor`, so the park has
        // to be ordered against confirmCuration's resume some other way: nothing can be confirmed
        // until CURATING is published, and that happens below. The park is therefore complete
        // before a confirm can even be admitted, and its resume can only follow.
        parkingLease?.parkCpuWake()
        var transition: PhaseTransition? = null
        synchronized(monitor) {
            val run = activeFor(generation) ?: throw IllegalStateException("Curation request is stale")
            // Cancellation is the only thing that can move this run while the lease call is out;
            // its own teardown closes the lease, so the park needs no undo here.
            if (run.cancelRequested || run.phase != admittedPhase) return
            transition = run.transition(Phase.CURATING, "curation_needed")
            run.curation = message.request
            mutableState.value = MiningRunState.Curating(message.request, pageSubmissionPending = false)
        }
        transition.emit()
    }

    private fun captureTerminal(
        generation: Long,
        terminal: BridgeMessage.Terminal,
    ) {
        var transition: PhaseTransition? = null
        synchronized(monitor) {
            val run = activeFor(generation) ?: throw IllegalStateException("Terminal callback is stale")
            if (run.terminalCallback != null) {
                throw IllegalStateException("Terminal callback was duplicated")
            }
            run.terminalCallback = terminal
            transition = run.transition(Phase.FINALIZING, "terminal")
            run.cancellationDispatchFault = null
        }
        transition.emit()
    }

    private fun onProgressStart(
        generation: Long,
        message: BridgeMessage.ProgressStart,
    ) {
        val stage = synchronized(monitor) { activeFor(generation)?.progress?.stage }
        updateProgress(
            generation,
            MiningProgress(0, message.total, message.description, stage = stage),
        )
    }

    private fun onProgressUpdate(
        generation: Long,
        message: BridgeMessage.ProgressUpdate,
    ) {
        val (total, stage) =
            synchronized(monitor) {
                val progress =
                    activeFor(generation)?.progress
                        ?: throw IllegalStateException("Progress update arrived before progress start")
                progress.total to progress.stage
            }
        // An engine counting slip past its declared total is display noise, not a protocol fault:
        // clamp it and leave a breadcrumb rather than failing the run over a bar.
        val current = if (total == 0L) message.current else message.current.coerceAtMost(total)
        if (current != message.current) {
            AppLog.i(
                LogComponent.READING,
                "progress.clamp",
                "outcome" to "reconcile",
                "current" to message.current,
                "total" to total,
            )
        }
        updateProgress(
            generation,
            MiningProgress(current, total, message.description, stage = stage),
        )
    }

    private fun onProgressStage(
        generation: Long,
        message: BridgeMessage.ProgressStage,
    ) {
        AppLog.i(
            LogComponent.READING,
            "engine_stage",
            "outcome" to "ok",
            "index" to message.index,
            "total" to message.total,
            "name" to message.name,
        )
        // A stage boundary starts a fresh inner count at the completed edge of the prior band.
        // The following start/update callbacks retain this stage.
        val stage = MiningStage(message.index, message.total, message.name)
        updateProgress(
            generation,
            MiningProgress(0, 0, message.name, stage = stage),
        )
    }

    private fun onProgressComplete(generation: Long) {
        val progress = synchronized(monitor) { activeFor(generation)?.progress } ?: return
        // A stage that never announced a count still owns a band. stageComplete fills it, so the
        // last stage reaches 100% before the terminal state replaces the panel.
        updateProgress(generation, progress.copy(current = progress.total, stageComplete = true))
    }

    private fun handleProgressError(
        generation: Long,
        message: BridgeMessage.ProgressError,
    ) {
        synchronized(monitor) {
            val run = activeFor(generation) ?: throw IllegalStateException("Progress error is stale")
            val detail = "${message.description}: ${message.message}"
            if (run.progressErrors.size < MAX_RESULT_ERRORS && detail !in run.progressErrors) {
                run.progressErrors += detail
            }
        }
    }

    private fun handlePresenter(
        generation: Long,
        event: PresenterEvent,
    ) {
        synchronized(monitor) {
            val run = activeFor(generation) ?: throw IllegalStateException("Presenter event is stale")
            if (event.runId != run.runId) {
                throw IllegalStateException("Presenter event belongs to another run")
            }
            val notices =
                when (event) {
                    is PresenterEvent.Message ->
                        if (
                            event.kind == PresenterMessageKind.WARNING ||
                            event.kind == PresenterMessageKind.ERROR
                        ) {
                            listOf(event.message)
                        } else {
                            emptyList()
                        }
                    is PresenterEvent.Validation ->
                        event.result.issues
                            .filter { issue ->
                                issue.severity == com.ankiminer.android.engine.ValidationSeverity.WARNING ||
                                    issue.severity == com.ankiminer.android.engine.ValidationSeverity.ERROR
                            }.map { it.message }
                    is PresenterEvent.Processing -> emptyList()
                }
            // Rewrite before the dedup so two spellings of one notice cannot both survive.
            // mapNotNull: the rewriter drops receipts the result screen must not carry.
            notices.mapNotNull(noticeRewriter::rewrite).forEach { notice ->
                if (
                    run.presenterNotices.size < MAX_PRESENTER_NOTICES &&
                    notice !in run.presenterNotices
                ) {
                    run.presenterNotices += notice
                }
            }
        }
    }

    /**
     * [callback] is the only thing that distinguishes these failures in the field: seven callbacks
     * raise roughly two dozen different protocol messages and every one of them resolves to the same
     * user string.
     */
    private fun callbackFailure(
        generation: Long,
        callback: String,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (failure: RuntimeException) {
            if (failure is BridgeProtocolException) {
                AppLog.e(
                    LogComponent.READING,
                    callback,
                    failure,
                    "outcome" to "fail",
                    "category" to failure.category.name,
                )
            } else {
                AppLog.e(LogComponent.READING, callback, failure, "outcome" to "fail")
            }
            recordFaultAndCancel(
                generation,
                strings.resolve(R.string.mining_failure_python_callback),
                diagnostic = "python_callback_protocol",
            )
            throw IllegalStateException("Invalid Python callback", failure)
        }
    }

    private inner class RunCallbacks(
        private val generation: Long,
    ) : EngineCallbacks {
        override fun cancellationRequested(): Boolean =
            synchronized(monitor) {
                activeFor(generation)?.let { run ->
                    run.cancelRequested || run.cancellation.isCancelled()
                } ?: true
            }

        override fun registerJob(message: String): String =
            try {
                registerJob(generation, message)
            } catch (failure: RuntimeException) {
                recordFaultAndCancel(generation, strings.resolve(R.string.mining_failure_job_registration))
                throw failure
            }

        override fun onStart(message: String) =
            callbackFailure(generation, "onStart") {
                val decoded = decodeCallback(generation, message)
                val progress = decoded as? BridgeMessage.ProgressStart
                    ?: throw IllegalStateException("Unexpected onStart message")
                onProgressStart(generation, progress)
            }

        override fun onProgress(message: String) =
            callbackFailure(generation, "onProgress") {
                val decoded = decodeCallback(generation, message)
                val progress = decoded as? BridgeMessage.ProgressUpdate
                    ?: throw IllegalStateException("Unexpected onProgress message")
                onProgressUpdate(generation, progress)
            }

        override fun onStage(message: String) =
            callbackFailure(generation, "onStage") {
                val decoded = decodeCallback(generation, message)
                val stage = decoded as? BridgeMessage.ProgressStage
                    ?: throw IllegalStateException("Unexpected onStage message")
                onProgressStage(generation, stage)
            }

        override fun onComplete(message: String) =
            callbackFailure(generation, "onComplete") {
                when (val decoded = decodeCallback(generation, message)) {
                    is BridgeMessage.ProgressComplete -> onProgressComplete(generation)
                    is BridgeMessage.Terminal -> {
                        if (decoded.outcome == MiningOutcome.FAILED) {
                            throw IllegalStateException("Failed terminal arrived on onComplete")
                        }
                        captureTerminal(generation, decoded)
                    }
                    else -> throw IllegalStateException("Unexpected onComplete message")
                }
            }

        override fun onError(message: String) =
            callbackFailure(generation, "onError") {
                when (val decoded = decodeCallback(generation, message)) {
                    is BridgeMessage.ProgressError -> handleProgressError(generation, decoded)
                    is BridgeMessage.Terminal -> {
                        if (decoded.outcome != MiningOutcome.FAILED) {
                            throw IllegalStateException("Non-failed terminal arrived on onError")
                        }
                        captureTerminal(generation, decoded)
                    }
                    else -> throw IllegalStateException("Unexpected onError message")
                }
            }

        override fun onPresenterEvent(message: String) =
            callbackFailure(generation, "onPresenterEvent") {
                val decoded = decodeCallback(generation, message) as? BridgeMessage.Presenter
                    ?: throw IllegalStateException("Unexpected presenter message")
                handlePresenter(generation, decoded.event)
            }

        override fun onCurationNeeded(message: String) =
            callbackFailure(generation, "onCurationNeeded") { acceptCuration(generation, message) }

        override fun synthesizeSentenceAudio(message: String): String =
            sentenceAudioCallback(generation, message)

        override fun ankiVerifyTarget(message: String): String =
            ankiCallback(generation, "ankiVerifyTarget") { anki.verifyTarget(message) }

        override fun ankiScanFirstFields(message: String): String =
            ankiCallback(generation, "ankiScanFirstFields") { anki.scanFirstFields(message) }

        override fun ankiStoreMedia(message: String): String =
            ankiCallback(generation, "ankiStoreMedia") { anki.storeMedia(message) }

        override fun ankiCreateNotes(message: String): String =
            ankiCallback(generation, "ankiCreateNotes") { anki.createNotes(message) }

        override fun ankiReleaseRunState(message: String): String =
            ankiCallback(generation, "ankiReleaseRunState") { anki.releaseRunState(message) }
    }

    private fun decodeCallback(
        generation: Long,
        raw: String,
    ): BridgeMessage {
        val runId =
            synchronized(monitor) { activeFor(generation)?.runId }
                ?: throw IllegalStateException("Python callback arrived before job registration")
        return BridgeJsonCodec.decodeCallback(raw, expectedRunId = runId)
    }

    /**
     * `code` separates a provider refusal from an out-of-order callback: an [AnkiReadFailure] also
     * carries the provider exception as its cause, which the record renders after the message.
     */
    private fun ankiCallback(
        generation: Long,
        callback: String,
        block: () -> String,
    ): String =
        try {
            synchronized(monitor) {
                val run = activeFor(generation) ?: throw IllegalStateException("Anki callback is stale")
                if (run.runId == null || run.phase == Phase.FINALIZING) {
                    throw IllegalStateException("Anki callback is out of order")
                }
            }
            block()
        } catch (failure: RuntimeException) {
            AppLog.e(
                LogComponent.READING,
                callback,
                failure,
                "outcome" to "fail",
                "code" to (failure as? AnkiReadFailure)?.code?.name,
            )
            recordFaultAndCancel(generation, strings.resolve(R.string.mining_failure_anki_callback))
            throw failure
        }

    private fun sentenceAudioCallback(
        generation: Long,
        rawRequest: String,
    ): String =
        try {
            val callback =
                synchronized(monitor) {
                    val run = activeFor(generation)
                        ?: throw IllegalStateException("Sentence-audio callback is stale")
                    val runId = run.runId
                        ?: throw IllegalStateException("Sentence-audio callback arrived before registration")
                    if (
                        (run.phase != Phase.RUNNING && run.phase != Phase.CANCELLING) ||
                            run.configSnapshot?.androidTtsEnabled != true
                    ) {
                        throw IllegalStateException("Sentence-audio callback is out of order")
                    }
                    val dispatcher = run.sentenceAudioDispatcher
                        ?: throw IllegalStateException("Sentence-audio callback is unavailable")
                    runId to dispatcher
                }
            callback.second.synthesizeSentenceAudio(
                rawRequest = rawRequest,
                expectedRunId = callback.first,
                cancellationCheck = { isCancellationRequested(generation) },
            )
        } catch (failure: RuntimeException) {
            AppLog.e(LogComponent.READING, "sentenceAudio", failure, "outcome" to "fail")
            recordFaultAndCancel(generation, strings.resolve(R.string.mining_failure_sentence_audio_callback))
            throw failure
        }

    private fun requireActive(generation: Long): ActiveRun =
        synchronized(monitor) {
            activeFor(generation) ?: throw IllegalStateException("Mining run is stale")
        }

    private fun isCancellationRequested(generation: Long): Boolean =
        synchronized(monitor) {
            activeFor(generation)?.let { it.cancelRequested || it.cancellation.isCancelled() } == true
        }

    private fun activeFor(generation: Long): ActiveRun? = active?.takeIf { it.generation == generation }

    /** The only writer of [ActiveRun.phase]; every caller holds [monitor] and emits after it. */
    private fun ActiveRun.transition(
        to: Phase,
        detail: String,
    ): PhaseTransition {
        val from = phase
        phase = to
        return PhaseTransition(from, to, detail)
    }

    /** No-op when the caller took a path that changed no phase. */
    private fun PhaseTransition?.emit() {
        val transition = this ?: return
        AppLog.state(
            LogComponent.READING,
            "phase",
            transition.from.name,
            transition.to.name,
            "outcome" to "ok",
            "detail" to transition.detail,
        )
    }

    private fun markCancellationLocked(run: ActiveRun): PhaseTransition? {
        run.cancelRequested = true
        val transition =
            if (run.phase != Phase.FINALIZING) {
                run.transition(Phase.CANCELLING, "cancel")
            } else {
                null
            }
        mutableState.value =
            when (val state = mutableState.value) {
                is MiningRunState.Starting -> state.copy(cancellationPending = true)
                is MiningRunState.Curating -> state.copy(cancellationPending = true)
                is MiningRunState.Running -> state.copy(cancellationPending = true)
                else -> state
            }
        return transition
    }

    private fun forwardCancellation(action: CancellationAction) {
        action.cancellation.cancel()
        action.foregroundStart?.cancel(false)
        action.foregroundLease?.markCancelling()
        executeCancellation(action.generation)
    }

    private fun executeCancellation(generation: Long) {
        val runId = synchronized(monitor) { activeFor(generation)?.runId }
        val task = { LogContext.withRunId(runId) { sendCancellation(generation) } }
        try {
            controlExecutor.execute(task)
        } catch (_: RuntimeException) {
            try {
                Thread(task, "anki-miner-cancel-fallback")
                    .apply { isDaemon = true }
                    .start()
            } catch (_: RuntimeException) {
                synchronized(monitor) {
                    activeFor(generation)?.cancellationDispatchFault =
                        ProtocolFault(strings.resolve(R.string.mining_failure_control_worker))
                }
            }
        }
    }

    private fun recordFaultAndCancel(
        generation: Long,
        message: String,
        diagnostic: String? = null,
    ) {
        var transition: PhaseTransition? = null
        val action =
            synchronized(monitor) {
                val run = activeFor(generation) ?: return
                if (run.stickyFault == null) {
                    run.stickyFault = ProtocolFault(message, diagnostic = diagnostic)
                }
                transition = markCancellationLocked(run)
                CancellationAction(
                    generation = run.generation,
                    cancellation = run.cancellation,
                    foregroundStart = run.foregroundStart,
                    foregroundLease = run.foregroundLease,
                )
            }
        transition.emit()
        forwardCancellation(action)
    }

    private fun recordFault(
        generation: Long,
        message: String,
        retryable: Boolean = false,
        diagnostic: String? = null,
    ) {
        synchronized(monitor) {
            val run = activeFor(generation) ?: return
            if (run.stickyFault == null) run.stickyFault = ProtocolFault(message, retryable, diagnostic)
        }
    }

    private fun executeControl(
        generation: Long,
        task: () -> Unit,
    ) {
        // The control executor is a different thread from the run executor, so it does not
        // inherit the id registerJob installed; it has to be carried across explicitly.
        val runId = synchronized(monitor) { activeFor(generation)?.runId }
        try {
            controlExecutor.execute { LogContext.withRunId(runId) { task() } }
        } catch (_: RuntimeException) {
            recordFault(generation, strings.resolve(R.string.mining_failure_control_worker))
        }
    }

    private fun setRestartRequired(message: String) {
        synchronized(monitor) {
            if (restartRequired == null) restartRequired = ProtocolFault(message)
        }
    }

    private fun ProtocolFault.toFailed(
        runId: String?,
        result: ProcessingResult?,
        faultId: String? = null,
    ): MiningRunState.Failed =
        MiningRunState.Failed(
            runId = runId,
            failure = MiningFailure(message, retryable, faultId, diagnostic),
            result = result,
        )

    private fun ProcessingResult?.withTerminalNotices(notices: List<String>): ProcessingResult? =
        this?.copy(errors = (notices + errors).distinct().take(MAX_RESULT_ERRORS))

    private fun canonicalLabel(raw: String): String {
        val filtered =
            buildString(raw.length) {
                var index = 0
                while (index < raw.length) {
                    val codePoint = raw.codePointAt(index)
                    if (!isCategoryC(codePoint)) appendCodePoint(codePoint)
                    index += Character.charCount(codePoint)
                }
            }.trim { Character.isWhitespace(it) || Character.isSpaceChar(it) }
        return Normalizer.normalize(filtered, Normalizer.Form.NFC)
    }

    private fun isCategoryC(codePoint: Int): Boolean =
        when (Character.getType(codePoint)) {
            Character.CONTROL.toInt(),
            Character.FORMAT.toInt(),
            Character.PRIVATE_USE.toInt(),
            Character.SURROGATE.toInt(),
            Character.UNASSIGNED.toInt(),
            -> true
            else -> false
        }

    private fun StagedReadingSourceKind.toWireKind(): ReadingMiningSourceKind =
        when (this) {
            StagedReadingSourceKind.TXT -> ReadingMiningSourceKind.TXT
            StagedReadingSourceKind.EPUB -> ReadingMiningSourceKind.EPUB
            StagedReadingSourceKind.SUBTITLE -> ReadingMiningSourceKind.SUBTITLE
            StagedReadingSourceKind.MOKURO -> ReadingMiningSourceKind.MOKURO
            StagedReadingSourceKind.TEXT -> ReadingMiningSourceKind.TEXT
        }

    private fun ReadingSourceSelection.documents(): List<SafDocument> =
        when (this) {
            is ReadingSourceSelection.Single -> listOf(document)
            is ReadingSourceSelection.MokuroArchivePair -> listOf(sidecar, archive)
            is ReadingSourceSelection.PastedText -> emptyList()
        }

    private fun requiresMediaForeground(run: ActiveRun): Boolean {
        val config = requireNotNull(run.configSnapshot)
        if (config.androidTtsEnabled == true || config.mapsExpressionAudioField()) return true
        return when (val selection = run.input.selection) {
            is ReadingSourceSelection.MokuroArchivePair -> true
            is ReadingSourceSelection.PastedText -> false
            is ReadingSourceSelection.Single -> {
                val name = selection.document.displayName.lowercase(Locale.ROOT)
                name.endsWith(".epub") || name.endsWith(".cbz") || name.endsWith(".zip")
            }
        }
    }

    /**
     * True when the run may fetch expression audio, approximating the engine's true fetch
     * condition ([anki_miner] audio_stage: `expression_audio_fetcher is not None and
     * anki_fields["expression_audio"]`). The builder returns a non-None fetcher only when the
     * expression_audio field is mapped AND an enabled pack entry exists; checking the field alone
     * over-promotes the zero-pack case, which is the safe direction for a foreground-service
     * decision (a promoted run that fetches nothing beats an unpromoted run that fetches).
     */
    private fun MiningConfigSnapshot.mapsExpressionAudioField(): Boolean {
        val fields = settings["anki_fields"] as? BridgeJsonValue.ObjectValue
            ?: return false
        val mapped = fields.values["expression_audio"] as? BridgeJsonValue.Text
            ?: return false
        return mapped.value.isNotEmpty()
    }

    private companion object {
        const val DEFAULT_SUBTITLE_SERIES_NAME = "Subtitles"
        val RETRYABLE_TERMINAL_ERRORS =
            setOf("provider_unavailable", "query_failed", "timeout", "processing_failed", "engine_error")
        const val MAX_PRESENTER_NOTICES = 16
        const val MAX_RESULT_ERRORS = 256
        const val MAX_CANCELLATION_DISPATCH_ATTEMPTS = 2
    }
}
