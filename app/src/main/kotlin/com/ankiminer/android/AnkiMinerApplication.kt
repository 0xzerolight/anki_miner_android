package com.ankiminer.android

import android.app.Application
import com.ankiminer.android.anki.provider.AnkiCancellation
import com.ankiminer.android.anki.provider.AnkiProviderRuntime
import com.ankiminer.android.anki.provider.AnkiRemediationCommand
import com.ankiminer.android.anki.provider.NoteTypeProviderErrorReason
import com.ankiminer.android.anki.provider.NoteTypeSetupStatus
import com.ankiminer.android.anki.provider.platformCanNameFilesFor
import com.ankiminer.android.data.anki.AnkiSetupBackend
import com.ankiminer.android.data.anki.AnkiSetupManager
import com.ankiminer.android.data.anki.ProcessAnkiSetupManager
import com.ankiminer.android.data.RuntimeWorkCoordinator
import com.ankiminer.android.data.resources.AndroidResourceDocumentWriter
import com.ankiminer.android.data.resources.AndroidResourceForegroundLease
import com.ankiminer.android.data.resources.AndroidResourceManager
import com.ankiminer.android.data.resources.HttpsDownloadConnectionFactory
import com.ankiminer.android.data.resources.PinnedResourceDownloader
import com.ankiminer.android.data.resources.ResourceDocumentWriter
import com.ankiminer.android.data.resources.ResourceManager
import com.ankiminer.android.data.resources.ResourceStartupReadiness
import com.ankiminer.android.data.resources.SafArchiveStager
import com.ankiminer.android.data.resources.WordListKind
import com.ankiminer.android.data.settings.AppSettings
import com.ankiminer.android.data.settings.AppSettingsRepository
import com.ankiminer.android.data.settings.AndroidSettingsDocumentReader
import com.ankiminer.android.data.settings.DataStoreAppSettingsRepository
import com.ankiminer.android.data.settings.DataStoreDiagnosticsSettingsRepository
import com.ankiminer.android.data.settings.DiagnosticsSettingsRepository
import com.ankiminer.android.data.settings.SettingsDocumentReader
import com.ankiminer.android.data.update.DataStoreUpdateCheckRepository
import com.ankiminer.android.data.update.GitHubUpdateCheckClient
import com.ankiminer.android.data.update.UpdateCheckCoordinator
import com.ankiminer.android.dictionary.BridgeDefinitionLookupService
import com.ankiminer.android.dictionary.DefinitionLookupService
import com.ankiminer.android.diagnostics.AndroidDiagnosticsExporter
import com.ankiminer.android.diagnostics.DiagnosticsBundleJanitor
import com.ankiminer.android.diagnostics.DiagnosticsBundleStager
import com.ankiminer.android.diagnostics.DiagnosticsExporter
import com.ankiminer.android.diagnostics.currentTesterBuildIdentity
import com.ankiminer.android.diagnostics.log.AppLog
import com.ankiminer.android.diagnostics.log.CompositeSink
import com.ankiminer.android.diagnostics.log.FileLogSink
import com.ankiminer.android.diagnostics.log.LogComponent
import com.ankiminer.android.diagnostics.log.LogLevel
import com.ankiminer.android.diagnostics.log.LogcatSink
import com.ankiminer.android.engine.ChaquopyPyBridge
import com.ankiminer.android.engine.ChaquopyPythonRuntime
import com.ankiminer.android.engine.PythonRuntimeReadiness
import com.ankiminer.android.engine.applyPythonLogLevelSafely
import com.ankiminer.android.localization.AndroidStringResourceResolver
import com.ankiminer.android.localization.StringResourceResolver
import com.ankiminer.android.media.AndroidSafBroker
import com.ankiminer.android.media.AndroidSafSelectionInventory
import com.ankiminer.android.media.SafBroker
import com.ankiminer.android.media.SafInputCacheJanitor
import com.ankiminer.android.media.SafJobFileOwner
import com.ankiminer.android.mining.AndroidMiningInputOwnerFactory
import com.ankiminer.android.mining.AndroidMiningRunInterruptionStore
import com.ankiminer.android.mining.BridgeMiningRepository
import com.ankiminer.android.mining.BuiltInInstalledTokenizerResourceProvider
import com.ankiminer.android.mining.CoordinatorAnkiCancellation
import com.ankiminer.android.mining.MiningForegroundStarter
import com.ankiminer.android.mining.MiningConfigSnapshotResolver
import com.ankiminer.android.mining.MiningLane
import com.ankiminer.android.mining.MiningRepository
import com.ankiminer.android.mining.AndroidNotificationPermissionProbe
import com.ankiminer.android.mining.AnkiMiningTargetProbe
import com.ankiminer.android.mining.AnkiMiningTargetReadiness
import com.ankiminer.android.mining.MiningRuntimePaths
import com.ankiminer.android.mining.ProviderCoordinatorAnkiCallbacks
import com.ankiminer.android.mining.SafSourceGrantReleaser
import com.ankiminer.android.mining.StatefulMiningRunAdmissionGate
import com.ankiminer.android.mining.TokenizerConfigurator
import com.ankiminer.android.mining.asMiningTaskExecutor
import com.ankiminer.android.reading.AndroidReadingSourceStaging
import com.ankiminer.android.reading.BridgeReadingMiningRepository
import com.ankiminer.android.reading.ReadingConfigSnapshotResolver
import com.ankiminer.android.reading.ReadingMiningRepository
import com.ankiminer.android.service.MiningForegroundSessionController
import com.ankiminer.android.subtitles.BridgeSubtitleCueLookupService
import com.ankiminer.android.subtitles.SubtitleCueLookupService
import com.ankiminer.android.tts.AndroidSentenceAudioSynthesizerFactory
import com.ankiminer.android.timing.TimingPreviewLoader
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Single production composition boundary for video and reading runs.
 *
 * Every persisted local-resource chain is intersected with this inventory before crossing the
 * bridge. Keep all installed-id kinds here so adding a call-site default cannot silently disable a
 * configured source in one mining mode.
 */
internal suspend fun ResourceManager.snapshotProductionSettings(
    settingsRepository: AppSettingsRepository,
    /** Injectable only because `MimeTypeMap` is not mocked under the JVM android.jar stub. */
    canNameFilesFor: (String) -> Boolean = ::platformCanNameFilesFor,
) =
    settingsRepository.snapshot(
        installedDictionaryIds = installedDictionaryIds(),
        installedFrequencyIds = installedFrequencyIds(),
        installedPitchIds = installedPitchIds(),
        installedAudioPackIds = installedAudioPackIds(),
        availableWordsetIds = bundledWordsetIds(),
        blacklistPath = wordListPath(WordListKind.BLACKLIST),
        whitelistPath = wordListPath(WordListKind.WHITELIST),
        // Asked here rather than defaulted in the mapper: a default would silently put every device
        // on the WebP path, which is what shipped and what nobody noticed.
        avifNameable = canNameFilesFor("avif"),
    )

/**
 * Startup re-verification of the persisted Anki target.
 *
 * Runs in application scope, which has no exception handler, so it reads through the degraded flow:
 * an unreadable store skips the refresh and leaves the previous setup state alone rather than
 * refreshing against defaults. Mining is still blocked, with the target probe's own reason.
 */
internal suspend fun refreshAnkiSetupFromSettings(
    settingsRepository: AppSettingsRepository,
    refresh: suspend (AppSettings) -> Unit,
) {
    val settings = settingsRepository.settingsOrNull.first()
    if (settings == null) {
        AppLog.i(
            LogComponent.SETTINGS,
            "setup.refresh",
            "outcome" to "skip",
            "code" to "settings_unreadable",
        )
        return
    }
    refresh(settings)
}

internal suspend fun runStartupRecoverySequence(
    recoverResources: suspend () -> Unit,
    refreshSetup: suspend () -> Unit,
    refreshAdmission: suspend () -> Unit,
) {
    recoverResources()
    refreshSetup()
    refreshAdmission()
}

/** Contains expected update-state storage failures at the process-owned coroutine boundary. */
internal suspend fun runUpdateOperation(
    event: String,
    operation: suspend () -> Unit,
) {
    try {
        operation()
    } catch (failure: IOException) {
        AppLog.w(
            LogComponent.SETTINGS,
            event,
            failure,
            "outcome" to "fail",
        )
    }
}

class AnkiMinerApplication : Application() {
    internal val stringResourceResolver by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AndroidStringResourceResolver(this)
    }

    internal val safSelectionInventory by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AndroidSafSelectionInventory(this)
    }

    /** One process-wide grant ledger prevents Activity recreation from splitting SAF ownership. */
    val safBroker: SafBroker by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AndroidSafBroker(this, selectionInventory = safSelectionInventory)
    }

    private val miningRunExecutor by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "anki-miner-python").apply { priority = Thread.NORM_PRIORITY - 1 }
        }
    }
    private val miningControlExecutor by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Executors.newSingleThreadExecutor { task -> Thread(task, "anki-miner-control") }
    }
    private val miningRunTaskExecutor by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        miningRunExecutor.asMiningTaskExecutor()
    }
    private val miningControlTaskExecutor by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        miningControlExecutor.asMiningTaskExecutor()
    }
    private val resourceExecutor by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Executors.newSingleThreadExecutor { task -> Thread(task, "anki-miner-resources") }
    }

    /**
     * Preview lookups share [resourceExecutor]: curation and the pre-run timing workbench both hold
     * the exclusive mining lease, so no resource operation can occupy that thread.
     */
    val definitionLookupService: DefinitionLookupService by
        lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            BridgeDefinitionLookupService(pyBridge, resourceExecutor)
        }

    val subtitleCueLookupService: SubtitleCueLookupService by
        lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            BridgeSubtitleCueLookupService(pyBridge, resourceExecutor)
        }

    private val resourceControlExecutor by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Executors.newSingleThreadExecutor { task -> Thread(task, "anki-miner-resource-control") }
    }
    private val ankiSetupExecutor by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Executors.newSingleThreadExecutor { task -> Thread(task, "anki-miner-setup") }
    }

    /** Its own thread so a level change never queues behind a curation submit. */
    private val diagnosticsExecutor by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Executors.newSingleThreadExecutor { task -> Thread(task, "anki-miner-diagnostics") }
    }
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Retained rather than built inline in [AppLog.install], because the diagnostics export needs
     * this exact instance: [FileLogSink.snapshot] runs on the writer coroutine, which is the only
     * way to copy the files without a rotation halfway through a rename. A second instance would
     * hold a second handle on the same file and know nothing about the first one's queue.
     */
    internal val logFileSink: FileLogSink by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        FileLogSink(filesDir, scope = applicationScope)
    }
    internal val diagnosticsBundleStager: DiagnosticsBundleStager by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        DiagnosticsBundleStager(this, logFileSink, safSelectionInventory)
    }
    internal val resourceDocumentWriter: ResourceDocumentWriter by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        AndroidResourceDocumentWriter(contentResolver)
    }
    internal val settingsDocumentReader: SettingsDocumentReader by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        AndroidSettingsDocumentReader(contentResolver)
    }

    internal fun createDiagnosticsExporter(buildDiagnostics: () -> String): DiagnosticsExporter =
        AndroidDiagnosticsExporter(
            stagingRoot = File(cacheDir, DiagnosticsBundleJanitor.DIRECTORY_NAME),
            stageBundle = {
                diagnosticsBundleStager.stage(
                    diagnostics = buildDiagnostics(),
                    // Strict on purpose: the persisted deck, note type, and tags are what the
                    // export redacts against, so an unreadable store must fail the export (the
                    // ViewModel renders that) rather than ship an under-redacted bundle.
                    settings = settingsRepository.settings.first(),
                    verboseLogging = diagnosticsSettings.verboseLogging.first(),
                )
            },
        )
    private val runtimeWorkCoordinator = RuntimeWorkCoordinator()
    private val miningRunInterruptionStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AndroidMiningRunInterruptionStore(noBackupFilesDir)
    }
    private val miningForegroundSessionController by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        MiningForegroundSessionController(this)
    }
    private val miningForegroundStarter by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        MiningForegroundStarter(miningForegroundSessionController::startSession)
    }
    internal val runtimeWorkState
        get() = runtimeWorkCoordinator.activeKind
    private val pythonRuntime by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ChaquopyPythonRuntime(this, diagnosticsSettings)
    }
    private val pyBridge by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ChaquopyPyBridge(pythonRuntime)
    }
    private val tokenizerResourceProvider by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        BuiltInInstalledTokenizerResourceProvider(this)
    }
    internal val timingPreviewLoader: TimingPreviewLoader by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        TimingPreviewLoader(
            coordinator = runtimeWorkCoordinator,
            ownerFactory = { cancellation -> SafJobFileOwner(this, cancellation) },
            tokenizer = TokenizerConfigurator(pyBridge, tokenizerResourceProvider),
            cueLookup = subtitleCueLookupService,
            io = Dispatchers.IO,
            resourceDispatcher = resourceExecutor.asCoroutineDispatcher(),
        )
    }
    private val readingSourceStaging by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AndroidReadingSourceStaging(this)
    }
    internal val pythonRuntimeReadiness: StateFlow<PythonRuntimeReadiness>
        get() = pythonRuntime.readiness
    private val ankiProviderRuntime by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AnkiProviderRuntime(this)
    }
    internal val ankiCallbacksForInstrumentation
        get() = ankiProviderRuntime.callbacks
    private val miningAdmissionGate by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        StatefulMiningRunAdmissionGate(
            ankiProbe = ankiProviderRuntime::probeReadiness,
            notificationProbe = AndroidNotificationPermissionProbe(this),
            targetProbe = AnkiMiningTargetProbe(::probeAnkiMiningTarget),
        )
    }
    internal val miningAdmissionState
        get() = miningAdmissionGate.state

    internal val settingsRepository: AppSettingsRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        DataStoreAppSettingsRepository(this)
    }

    internal val diagnosticsSettings: DiagnosticsSettingsRepository by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        DataStoreDiagnosticsSettingsRepository(this)
    }

    internal val updateCheckCoordinator: UpdateCheckCoordinator by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        UpdateCheckCoordinator(
            repository = DataStoreUpdateCheckRepository(this),
            client = GitHubUpdateCheckClient(HttpsDownloadConnectionFactory()),
            currentVersion = BuildConfig.VERSION_NAME,
        )
    }

    internal val ankiSetupManager: AnkiSetupManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ProcessAnkiSetupManager(
            backend =
                object : AnkiSetupBackend {
                    override fun listNoteTypes(cancellation: AnkiCancellation) =
                        ankiProviderRuntime.listNoteTypes(cancellation)

                    override fun listDeckNames(cancellation: AnkiCancellation) =
                        ankiProviderRuntime.listDeckNames(cancellation)

                    override fun verifyNoteType(
                        noteType: String?,
                        fieldMap: Map<String, String>,
                        cardTypeMarkerField: String?,
                        cancellation: AnkiCancellation,
                    ) = if (noteType.isNullOrEmpty()) {
                        NoteTypeSetupStatus.NotSelected
                    } else {
                        ankiProviderRuntime.verifyUserNoteType(
                            noteType,
                            fieldMap,
                            cancellation,
                            cardTypeMarkerField,
                        )
                    }

                    override fun remediationInventory(cancellation: AnkiCancellation) =
                        ankiProviderRuntime.remediationInventory(cancellation)

                    override fun reconcileInterruptedWork(cancellation: AnkiCancellation) =
                        ankiProviderRuntime.reconcileInterruptedWork(cancellation)

                    override fun performRemediation(
                        command: AnkiRemediationCommand,
                        cancellation: AnkiCancellation,
                    ) = ankiProviderRuntime.performRemediation(command, cancellation)
                },
            executor = ankiSetupExecutor,
            runtimeWorkCoordinator = runtimeWorkCoordinator,
            strings = stringResourceResolver,
        )
    }

    internal val resourceManager: ResourceManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val resourceStagingRoot = File(noBackupFilesDir, "resource-staging")
        AndroidResourceManager(
            safBroker = safBroker,
            safSelectionInventory = safSelectionInventory,
            bridge = pyBridge,
            tokenizerResources = tokenizerResourceProvider,
            bridgeFilesRoot = filesDir,
            stagingRoot = resourceStagingRoot,
            downloader = PinnedResourceDownloader(File(noBackupFilesDir, "resource-downloads")),
            resourceExecutor = resourceExecutor,
            controlExecutor = resourceControlExecutor,
            runtimeWorkCoordinator = runtimeWorkCoordinator,
            safStager = SafArchiveStager(contentResolver, resourceStagingRoot),
            documentWriter = resourceDocumentWriter,
            foregroundLease = AndroidResourceForegroundLease(this),
            strings = stringResourceResolver,
        )
    }
    internal val resourceStartupReadiness: StateFlow<ResourceStartupReadiness> by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        resourceManager.state
            .map { it.startupReadiness }
            .stateIn(
                applicationScope,
                SharingStarted.Eagerly,
                ResourceStartupReadiness.PENDING,
            )
    }

    private fun buildMediaMiningRepository(lane: MiningLane): MiningRepository =
        BridgeMiningRepository(
            pyBridge = pyBridge,
            anki = ProviderCoordinatorAnkiCallbacks(ankiProviderRuntime.callbacks),
            inputOwnerFactory = AndroidMiningInputOwnerFactory(this),
            lane = lane,
            tokenizerResourceProvider = tokenizerResourceProvider,
            runtimePaths =
                MiningRuntimePaths(
                    cacheDir = cacheDir,
                    nativeLibraryDir = File(requireNotNull(applicationInfo.nativeLibraryDir)),
                ),
            sourceGrantReleaser = SafSourceGrantReleaser(safBroker),
            foregroundStarter = miningForegroundStarter,
            runExecutor = miningRunTaskExecutor,
            controlExecutor = miningControlTaskExecutor,
            admissionGate = miningAdmissionGate,
            runtimeWorkCoordinator = runtimeWorkCoordinator,
            configSnapshotResolver =
                MiningConfigSnapshotResolver {
                    // BridgeMiningRepository acquires the process mining lease first. Reading
                    // DataStore on this worker therefore captures settings and dictionary slots
                    // atomically with respect to every resource publication.
                    runBlocking {
                        resourceManager.snapshotProductionSettings(settingsRepository)
                    }
                },
            resourceStartupReady = {
                resourceManager.state.value.startupReadiness == ResourceStartupReadiness.READY
            },
            strings = stringResourceResolver,
            interruptionStore = miningRunInterruptionStore,
        )

    internal val miningRepository: MiningRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        buildMediaMiningRepository(MiningLane.VIDEO)
    }

    internal val audioRepository: MiningRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        buildMediaMiningRepository(MiningLane.AUDIO)
    }

    internal val readingRepository: ReadingMiningRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        BridgeReadingMiningRepository(
            pyBridge = pyBridge,
            anki = ProviderCoordinatorAnkiCallbacks(ankiProviderRuntime.callbacks),
            sourceStager = readingSourceStaging.stager,
            tokenizerResourceProvider = tokenizerResourceProvider,
            runtimePaths =
                MiningRuntimePaths(
                    cacheDir = cacheDir,
                    nativeLibraryDir = File(requireNotNull(applicationInfo.nativeLibraryDir)),
                ),
            sourceGrantReleaser = SafSourceGrantReleaser(safBroker),
            foregroundStarter =
                MiningForegroundSessionController(this).let { controller ->
                    MiningForegroundStarter(controller::startSession)
                },
            runExecutor = miningRunExecutor.asMiningTaskExecutor(),
            controlExecutor = miningControlExecutor.asMiningTaskExecutor(),
            admissionGate = miningAdmissionGate,
            runtimeWorkCoordinator = runtimeWorkCoordinator,
            configSnapshotResolver =
                ReadingConfigSnapshotResolver {
                    runBlocking {
                        resourceManager.snapshotProductionSettings(settingsRepository)
                    }
                },
            resourceStartupReady = {
                resourceManager.state.value.startupReadiness == ResourceStartupReadiness.READY
            },
            sentenceAudioSynthesizerFactory = AndroidSentenceAudioSynthesizerFactory(this),
            strings = stringResourceResolver,
            interruptionStore = miningRunInterruptionStore,
        )
    }

    override fun onCreate() {
        super.onCreate()
        val buildIdentity = currentTesterBuildIdentity()
        AppLog.i(
            LogComponent.APP,
            "startup",
            "outcome" to "ok",
            "applicationId" to buildIdentity.applicationId,
            "versionName" to buildIdentity.versionName,
            "versionCode" to buildIdentity.versionCode,
            "sourceCommit" to buildIdentity.sourceCommit,
            "sdkInt" to buildIdentity.sdkInt,
            "supportedAbis" to buildIdentity.supportedAbis.joinToString(","),
            "pythonVersion" to buildIdentity.pythonVersion,
            "runtimeWheelBuildKey" to buildIdentity.runtimeWheelBuildKey,
            "tokenizerPublicationBuildKey" to buildIdentity.tokenizerPublicationBuildKey,
            "deviceRuntimeAccepted" to buildIdentity.deviceRuntimeAccepted,
        )
        // Load-bearing ordering: this is the first task submitted to the process Python executor.
        // It starts Chaquopy and establishes ANKI_MINER_HOME before any engine import.
        pythonRuntime.enqueueFirst(miningRunExecutor)
        // Installed after the enqueue so nothing displaces the bootstrap as the first task on the
        // Python executor. Logging before this point is not lost: install() replays the pre-install
        // buffer, which is the only place a Python startup failure can be recorded at all, because
        // the engine's own file handler is created inside bootstrap.initialize.
        AppLog.install(CompositeSink(LogcatSink(), logFileSink))
        applicationScope.launch(Dispatchers.IO) {
            try {
                DiagnosticsBundleJanitor(
                    File(cacheDir, DiagnosticsBundleJanitor.DIRECTORY_NAME),
                ).clean()
            } catch (failure: RuntimeException) {
                AppLog.w(LogComponent.DIAG, "bundle.janitor", failure, "outcome" to "fail")
            }
        }
        applicationScope.launch(Dispatchers.IO) {
            try {
                runUpdateOperation("update.check") {
                    updateCheckCoordinator.checkIfDue()
                }
            } catch (failure: RuntimeException) {
                AppLog.w(LogComponent.SETTINGS, "update.check", failure, "outcome" to "fail")
            }
        }
        applicationScope.launch {
            diagnosticsSettings.verboseLogging.distinctUntilChanged().collect { verbose ->
                val level = if (verbose) LogLevel.DEBUG else LogLevel.INFO
                AppLog.setMinLevel(level)
                diagnosticsExecutor.execute {
                    applyPythonLogLevelSafely(level) { raw -> pyBridge.dispatch(raw, null) }
                }
            }
        }
        miningRunExecutor.execute {
            try {
                SafInputCacheJanitor(this).removeOrphans()
            } catch (failure: Exception) {
                AppLog.w(
                    LogComponent.MEDIA,
                    "startup.orphans.saf_input",
                    failure,
                    "outcome" to "fail",
                )
                // A current run creates collision-resistant names and owns its own cleanup.
            }
            try {
                readingSourceStaging.janitor.removeOrphans()
            } catch (failure: Exception) {
                AppLog.w(
                    LogComponent.READING,
                    "startup.orphans.reading",
                    failure,
                    "outcome" to "fail",
                )
                // A current run creates collision-resistant names and owns its own cleanup.
            }
        }
        applicationScope.launch {
            ankiSetupManager.state
                .map { it.operation }
                .distinctUntilChanged()
                .drop(1)
                .collect { operation ->
                    val resources = resourceManager.state.value
                    if (
                        operation == null &&
                            resources.startupReadiness == ResourceStartupReadiness.READY &&
                            resources.activeOperation == null
                    ) {
                        refreshMiningAdmission()
                    }
                }
        }
        // Keep durable selections and release only grants no longer owned by a saved slot.
        applicationScope.launch {
            try {
                safBroker.reconcileStartup()
            } catch (failure: Exception) {
                AppLog.w(
                    LogComponent.SAF,
                    "startup.reconcile",
                    failure,
                    "outcome" to "fail",
                )
                // Best effort only. The first later retain retries reconciliation and surfaces a
                // provider failure to that picker without crashing process startup.
            }
        }
        // Resource recovery owns the first mutation lease. Admission is evaluated only after it
        // releases that lease, otherwise two independently scheduled startup tasks can make the
        // required resource inventory fail with a synthetic busy error.
        applicationScope.launch {
            runStartupRecoverySequence(
                recoverResources = resourceManager::recoverAndRefresh,
                refreshSetup = ::refreshAnkiSetupAndAwait,
                refreshAdmission = ::refreshMiningAdmissionAndAwait,
            )
        }
    }

    internal fun refreshMiningAdmission() {
        applicationScope.launch { refreshMiningAdmissionAndAwait() }
    }

    private suspend fun refreshMiningAdmissionAndAwait() {
        val resources = resourceManager.state.value
        if (
            resources.startupReadiness != ResourceStartupReadiness.READY ||
                resources.activeOperation != null
        ) {
            return
        }
        runOnExecutor(miningControlExecutor) {
            val lease =
                runtimeWorkCoordinator.tryAcquire(RuntimeWorkCoordinator.Kind.MINING)
                    ?: return@runOnExecutor
            try {
                miningAdmissionGate.evaluate(CoordinatorAnkiCancellation())
            } catch (failure: Exception) {
                AppLog.w(
                    LogComponent.MINING,
                    "admission.refresh",
                    failure,
                    "outcome" to "fail",
                )
                // The fail-closed admission state remains visible and is rechecked at run start.
            } finally {
                lease.close()
            }
        }
    }

    /**
     * Persist the tester logging switch. Scoped to the process, not the Activity, so the write
     * still completes if the settings screen is torn down the instant after the tap; the collector
     * in [onCreate] applies whatever lands.
     */
    internal fun setVerboseLogging(enabled: Boolean) {
        applicationScope.launch {
            try {
                diagnosticsSettings.setVerboseLogging(enabled)
            } catch (failure: IOException) {
                AppLog.w(
                    LogComponent.SETTINGS,
                    "verboseLogging.write",
                    failure,
                    "enabled" to enabled,
                    "outcome" to "fail",
                )
            }
        }
    }

    internal fun setUpdateCheckEnabled(enabled: Boolean) {
        applicationScope.launch {
            runUpdateOperation("update.enabled.write") {
                updateCheckCoordinator.setEnabled(enabled)
            }
        }
    }

    internal fun checkForUpdates() {
        applicationScope.launch {
            runUpdateOperation("update.check") {
                updateCheckCoordinator.checkNow()
            }
        }
    }

    internal fun skipAvailableUpdate() {
        applicationScope.launch {
            runUpdateOperation("update.skip.write") {
                updateCheckCoordinator.skipAvailable()
            }
        }
    }

    /** Refresh process-owned state which may change while an external Android UI is visible. */
    internal fun refreshExternalReadiness() {
        applicationScope.launch {
            refreshAnkiSetupAndAwait()
            refreshMiningAdmissionAndAwait()
        }
    }

    /**
     * Re-verify the user-selected note type against the currently persisted settings. The DataStore
     * read runs in application scope; provider verification runs on the setup executor and is
     * awaited so admission cannot publish against an older remediation inventory.
     */
    private suspend fun refreshAnkiSetupAndAwait() {
        refreshAnkiSetupFromSettings(settingsRepository) { settings ->
            ankiSetupManager.refreshAndAwait(
                settings.noteType,
                settings.fieldMap,
                settings.cardType?.let { settings.cardTypeMarkerField },
            )
        }
    }

    private suspend fun <T> runOnExecutor(
        executor: java.util.concurrent.Executor,
        block: () -> T,
    ): T =
        suspendCoroutine { continuation ->
            executor.execute {
                try {
                    continuation.resume(block())
                } catch (failure: Throwable) {
                    continuation.resumeWithException(failure)
                }
            }
        }

    private fun probeAnkiMiningTarget(
        cancellation: AnkiCancellation,
    ): AnkiMiningTargetReadiness {
        if (cancellation.isCancelled()) {
            return AnkiMiningTargetReadiness.Blocked(
                stringResourceResolver.resolve(R.string.mining_target_cancelled),
                true,
            )
        }
        return try {
            // Worker executor, never the main thread. A store which cannot be read blocks the run
            // here rather than probing a default target, and the fail-closed state is rechecked at
            // run start like every other blocked reason.
            val settings = runBlocking { settingsRepository.settingsOrNull.first() }
            if (settings == null) {
                AppLog.d(LogComponent.ANKI, "target.probe") {
                    arrayOf(
                        "outcome" to "skip",
                        "code" to "settings_unreadable",
                    )
                }
                return AnkiMiningTargetReadiness.Blocked(
                    stringResourceResolver.resolve(R.string.mining_target_inspection_failed),
                    true,
                )
            }
            val noteType = settings.noteType
            if (noteType.isNullOrEmpty()) {
                return AnkiMiningTargetReadiness.Blocked(
                    stringResourceResolver.resolve(R.string.mining_target_select_note_type),
                    true,
                )
            }
            when (
                val status =
                    ankiProviderRuntime.verifyUserNoteType(
                        noteType,
                        settings.fieldMap,
                        cancellation,
                        settings.cardType?.let { settings.cardTypeMarkerField },
                    )
            ) {
                is NoteTypeSetupStatus.Verified -> {
                    val pending = ankiProviderRuntime.remediationInventory(cancellation).pending
                    if (pending.isEmpty()) {
                        AnkiMiningTargetReadiness.Ready
                    } else {
                        AnkiMiningTargetReadiness.Blocked(
                            stringResourceResolver.resolve(R.string.mining_target_resolve_recovery),
                            false,
                        )
                    }
                }
                NoteTypeSetupStatus.NoteTypeMissing ->
                    AnkiMiningTargetReadiness.Blocked(
                        stringResourceResolver.resolve(R.string.mining_target_note_type_missing),
                        true,
                    )
                is NoteTypeSetupStatus.FieldsMissing ->
                    AnkiMiningTargetReadiness.Blocked(
                        stringResourceResolver.resolve(R.string.mining_target_fields_missing),
                        true,
                    )
                is NoteTypeSetupStatus.FieldMapInvalid ->
                    AnkiMiningTargetReadiness.Blocked(
                        stringResourceResolver.resolve(R.string.mining_target_field_map_invalid),
                        false,
                    )
                NoteTypeSetupStatus.FirstFieldMismatch ->
                    AnkiMiningTargetReadiness.Blocked(
                        stringResourceResolver.resolve(R.string.mining_target_first_field_mismatch),
                        false,
                    )
                is NoteTypeSetupStatus.ProviderError ->
                    AnkiMiningTargetReadiness.Blocked(
                        providerErrorMessage(status, stringResourceResolver),
                        status.retryable,
                    )
                NoteTypeSetupStatus.NotSelected ->
                    AnkiMiningTargetReadiness.Blocked(
                        stringResourceResolver.resolve(R.string.mining_target_select_note_type),
                        true,
                    )
            }
        } catch (failure: RuntimeException) {
            AppLog.w(
                LogComponent.ANKI,
                "target.probe",
                failure,
                "outcome" to "fail",
            )
            AnkiMiningTargetReadiness.Blocked(
                stringResourceResolver.resolve(R.string.mining_target_inspection_failed),
                true,
            )
        }
    }
}

internal fun providerErrorMessage(
    status: NoteTypeSetupStatus.ProviderError,
    strings: StringResourceResolver,
): String =
    when (status.reason) {
        NoteTypeProviderErrorReason.API_DISABLED ->
            strings.resolve(R.string.mining_target_provider_api_disabled)
        NoteTypeProviderErrorReason.API_INCOMPATIBLE ->
            strings.resolve(R.string.mining_target_provider_api_incompatible)
        NoteTypeProviderErrorReason.API_DISABLED_OR_INCOMPATIBLE ->
            strings.resolve(R.string.mining_target_provider_api_disabled_or_incompatible)
        NoteTypeProviderErrorReason.PERMISSION_REQUIRED ->
            strings.resolve(R.string.mining_target_provider_permission_required)
        NoteTypeProviderErrorReason.PROVIDER_UNAVAILABLE ->
            strings.resolve(R.string.mining_target_provider_unavailable)
        NoteTypeProviderErrorReason.PROVIDER_BECAME_UNAVAILABLE ->
            strings.resolve(R.string.mining_target_provider_became_unavailable)
        NoteTypeProviderErrorReason.QUERY_FAILED ->
            strings.resolve(R.string.mining_target_provider_query_failed)
        NoteTypeProviderErrorReason.TIMEOUT ->
            strings.resolve(R.string.mining_target_provider_timeout)
        NoteTypeProviderErrorReason.CANCELLED ->
            strings.resolve(R.string.mining_target_provider_cancelled)
        NoteTypeProviderErrorReason.UNKNOWN ->
            strings.resolve(
                R.string.mining_target_provider_unknown_code,
                listOf(status.code.wireName),
            )
    }
