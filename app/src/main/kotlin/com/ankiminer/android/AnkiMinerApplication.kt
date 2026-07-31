package com.ankiminer.android

import android.app.Application
import com.ankiminer.android.anki.provider.AnkiCancellation
import com.ankiminer.android.anki.provider.AnkiProviderRuntime
import com.ankiminer.android.anki.provider.AnkiRemediationCommand
import com.ankiminer.android.anki.provider.NoteTypeProviderErrorReason
import com.ankiminer.android.anki.provider.NoteTypeSetupStatus
import com.ankiminer.android.data.anki.AnkiSetupBackend
import com.ankiminer.android.data.anki.AnkiSetupManager
import com.ankiminer.android.data.anki.ProcessAnkiSetupManager
import com.ankiminer.android.data.RuntimeWorkCoordinator
import com.ankiminer.android.data.resources.AndroidResourceDocumentWriter
import com.ankiminer.android.data.resources.AndroidResourceManager
import com.ankiminer.android.data.resources.PinnedResourceDownloader
import com.ankiminer.android.data.resources.ResourceManager
import com.ankiminer.android.data.resources.ResourceStartupReadiness
import com.ankiminer.android.data.resources.SafArchiveStager
import com.ankiminer.android.data.resources.WordListKind
import com.ankiminer.android.data.settings.AppSettingsRepository
import com.ankiminer.android.data.settings.DataStoreAppSettingsRepository
import com.ankiminer.android.data.settings.DataStoreDiagnosticsSettingsRepository
import com.ankiminer.android.data.settings.DiagnosticsSettingsRepository
import com.ankiminer.android.diagnostics.DiagnosticsBundleJanitor
import com.ankiminer.android.diagnostics.DiagnosticsBundleStager
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
import com.ankiminer.android.mining.AndroidMiningInputOwnerFactory
import com.ankiminer.android.mining.BridgeMiningRepository
import com.ankiminer.android.mining.BuiltInInstalledTokenizerResourceProvider
import com.ankiminer.android.mining.CoordinatorAnkiCancellation
import com.ankiminer.android.mining.MiningForegroundStarter
import com.ankiminer.android.mining.MiningConfigSnapshotResolver
import com.ankiminer.android.mining.MiningRepository
import com.ankiminer.android.mining.AndroidNotificationPermissionProbe
import com.ankiminer.android.mining.AnkiMiningTargetProbe
import com.ankiminer.android.mining.AnkiMiningTargetReadiness
import com.ankiminer.android.mining.MiningRuntimePaths
import com.ankiminer.android.mining.ProviderCoordinatorAnkiCallbacks
import com.ankiminer.android.mining.SafSourceGrantReleaser
import com.ankiminer.android.mining.StatefulMiningRunAdmissionGate
import com.ankiminer.android.mining.asMiningTaskExecutor
import com.ankiminer.android.reading.AndroidReadingSourceStaging
import com.ankiminer.android.reading.BridgeReadingMiningRepository
import com.ankiminer.android.reading.ReadingConfigSnapshotResolver
import com.ankiminer.android.reading.ReadingMiningRepository
import com.ankiminer.android.service.MiningForegroundSessionController
import com.ankiminer.android.tts.AndroidSentenceAudioSynthesizerFactory
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    private val resourceExecutor by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Executors.newSingleThreadExecutor { task -> Thread(task, "anki-miner-resources") }
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
    private val runtimeWorkCoordinator = RuntimeWorkCoordinator()
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
                        cancellation: AnkiCancellation,
                    ) = if (noteType.isNullOrEmpty()) {
                        NoteTypeSetupStatus.NotSelected
                    } else {
                        ankiProviderRuntime.verifyUserNoteType(noteType, fieldMap, cancellation)
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
            bridge = pyBridge,
            tokenizerResources = tokenizerResourceProvider,
            bridgeFilesRoot = filesDir,
            stagingRoot = resourceStagingRoot,
            downloader = PinnedResourceDownloader(File(noBackupFilesDir, "resource-downloads")),
            resourceExecutor = resourceExecutor,
            controlExecutor = resourceControlExecutor,
            runtimeWorkCoordinator = runtimeWorkCoordinator,
            safStager = SafArchiveStager(contentResolver, resourceStagingRoot),
            documentWriter = AndroidResourceDocumentWriter(contentResolver),
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

    internal val miningRepository: MiningRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        BridgeMiningRepository(
            pyBridge = pyBridge,
            anki = ProviderCoordinatorAnkiCallbacks(ankiProviderRuntime.callbacks),
            inputOwnerFactory = AndroidMiningInputOwnerFactory(this),
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
                MiningConfigSnapshotResolver {
                    // BridgeMiningRepository acquires the process mining lease first. Reading
                    // DataStore on this worker therefore captures settings and dictionary slots
                    // atomically with respect to every resource publication.
                    runBlocking {
                        settingsRepository.snapshot(
                            installedDictionaryIds = resourceManager.installedDictionaryIds(),
                            installedFrequencyIds = resourceManager.installedFrequencyIds(),
                            installedAudioPackIds = resourceManager.installedAudioPackIds(),
                            availableWordsetIds = resourceManager.bundledWordsetIds(),
                            blacklistPath = resourceManager.wordListPath(WordListKind.BLACKLIST),
                            whitelistPath = resourceManager.wordListPath(WordListKind.WHITELIST),
                        )
                    }
                },
            resourceStartupReady = {
                resourceManager.state.value.startupReadiness == ResourceStartupReadiness.READY
            },
            strings = stringResourceResolver,
        )
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
                        settingsRepository.snapshot(
                            installedDictionaryIds = resourceManager.installedDictionaryIds(),
                            installedFrequencyIds = resourceManager.installedFrequencyIds(),
                            installedAudioPackIds = resourceManager.installedAudioPackIds(),
                            availableWordsetIds = resourceManager.bundledWordsetIds(),
                            blacklistPath = resourceManager.wordListPath(WordListKind.BLACKLIST),
                            whitelistPath = resourceManager.wordListPath(WordListKind.WHITELIST),
                        )
                    }
                },
            resourceStartupReady = {
                resourceManager.state.value.startupReadiness == ResourceStartupReadiness.READY
            },
            sentenceAudioSynthesizerFactory = AndroidSentenceAudioSynthesizerFactory(this),
            strings = stringResourceResolver,
        )
    }

    override fun onCreate() {
        super.onCreate()
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
                AppLog.w(LogComponent.DIAG, "bundle.janitor", failure)
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
                readingSourceStaging.janitor.removeOrphans()
            } catch (_: Exception) {
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
            } catch (_: Exception) {
                // Best effort only. The first later retain retries reconciliation and surfaces a
                // provider failure to that picker without crashing process startup.
            }
        }
        // Resource recovery owns the first mutation lease. Admission is evaluated only after it
        // releases that lease, otherwise two independently scheduled startup tasks can make the
        // required resource inventory fail with a synthetic busy error.
        applicationScope.launch {
            resourceManager.recoverAndRefresh()
            refreshAnkiSetup()
            refreshMiningAdmission()
        }
    }

    internal fun refreshMiningAdmission() {
        val resources = resourceManager.state.value
        if (
            resources.startupReadiness != ResourceStartupReadiness.READY ||
                resources.activeOperation != null
        ) {
            return
        }
        miningControlExecutor.execute {
            val lease =
                runtimeWorkCoordinator.tryAcquire(RuntimeWorkCoordinator.Kind.MINING)
                    ?: return@execute
            try {
                miningAdmissionGate.evaluate(CoordinatorAnkiCancellation())
            } catch (_: Exception) {
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
                AppLog.w(LogComponent.SETTINGS, "verboseLogging.write", failure, "enabled" to enabled)
            }
        }
    }

    /** Refresh process-owned state which may change while an external Android UI is visible. */
    internal fun refreshExternalReadiness() {
        refreshAnkiSetup()
        refreshMiningAdmission()
    }

    /**
     * Re-verify the user-selected note type against the currently persisted settings. The DataStore
     * read and the verify are scheduled on the setup executor, so callers on the main thread
     * (onResume, permission return) never block on I/O.
     */
    private fun refreshAnkiSetup() {
        ankiSetupExecutor.execute {
            val settings = runBlocking { settingsRepository.settings.first() }
            ankiSetupManager.refresh(settings.noteType, settings.fieldMap)
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
            val settings = runBlocking { settingsRepository.settings.first() }
            val noteType = settings.noteType
            if (noteType.isNullOrEmpty()) {
                return AnkiMiningTargetReadiness.Blocked(
                    stringResourceResolver.resolve(R.string.mining_target_select_note_type),
                    true,
                )
            }
            when (val status = ankiProviderRuntime.verifyUserNoteType(noteType, settings.fieldMap, cancellation)) {
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
        } catch (_: RuntimeException) {
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
