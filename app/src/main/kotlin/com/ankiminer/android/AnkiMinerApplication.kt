package com.ankiminer.android

import android.app.Application
import com.ankiminer.android.anki.provider.AnkiCancellation
import com.ankiminer.android.anki.provider.AnkiMinerModelProvisioningResult
import com.ankiminer.android.anki.provider.AnkiProviderRuntime
import com.ankiminer.android.anki.provider.AnkiRemediationCommand
import com.ankiminer.android.data.anki.AnkiSetupBackend
import com.ankiminer.android.data.anki.AnkiSetupManager
import com.ankiminer.android.data.anki.ProcessAnkiSetupManager
import com.ankiminer.android.data.RuntimeWorkCoordinator
import com.ankiminer.android.data.resources.AndroidResourceManager
import com.ankiminer.android.data.resources.PinnedResourceDownloader
import com.ankiminer.android.data.resources.ResourceManager
import com.ankiminer.android.data.resources.ResourceStartupReadiness
import com.ankiminer.android.data.settings.AppSettingsRepository
import com.ankiminer.android.data.settings.DataStoreAppSettingsRepository
import com.ankiminer.android.engine.ChaquopyPyBridge
import com.ankiminer.android.engine.ChaquopyPythonRuntime
import com.ankiminer.android.engine.PythonRuntimeReadiness
import com.ankiminer.android.media.AndroidSafBroker
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
    /** One process-wide grant ledger prevents Activity recreation from splitting SAF ownership. */
    val safBroker: SafBroker by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AndroidSafBroker(this)
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
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val runtimeWorkCoordinator = RuntimeWorkCoordinator()
    private val pythonRuntime by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ChaquopyPythonRuntime(this)
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

    internal val ankiSetupManager: AnkiSetupManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ProcessAnkiSetupManager(
            backend =
                object : AnkiSetupBackend {
                    override fun inspectModel(cancellation: AnkiCancellation) =
                        ankiProviderRuntime.inspectAnkiMinerModel(cancellation)

                    override fun provisionModel(cancellation: AnkiCancellation) =
                        ankiProviderRuntime.provisionAnkiMinerModel(cancellation)

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
        )
    }

    internal val resourceManager: ResourceManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AndroidResourceManager(
            resolver = contentResolver,
            safBroker = safBroker,
            bridge = pyBridge,
            tokenizerResources = tokenizerResourceProvider,
            stagingRoot = File(noBackupFilesDir, "resource-staging"),
            downloader = PinnedResourceDownloader(File(noBackupFilesDir, "resource-downloads")),
            resourceExecutor = resourceExecutor,
            controlExecutor = resourceControlExecutor,
            runtimeWorkCoordinator = runtimeWorkCoordinator,
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
                        )
                    }
                },
            resourceStartupReady = {
                resourceManager.state.value.startupReadiness == ResourceStartupReadiness.READY
            },
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
                        )
                    }
                },
            resourceStartupReady = {
                resourceManager.state.value.startupReadiness == ResourceStartupReadiness.READY
            },
            sentenceAudioSynthesizerFactory = AndroidSentenceAudioSynthesizerFactory(this),
        )
    }

    override fun onCreate() {
        super.onCreate()
        // Load-bearing ordering: this is the first task submitted to the process Python executor.
        // It starts Chaquopy and establishes ANKI_MINER_HOME before any engine import.
        pythonRuntime.enqueueFirst(miningRunExecutor)
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
        // M3 has no durable job/selection inventory. Reconcile process-orphaned grants now so a
        // user who never opens a picker again does not leak provider quota indefinitely.
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
            ankiSetupManager.refresh()
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

    /** Refresh process-owned state which may change while an external Android UI is visible. */
    internal fun refreshExternalReadiness() {
        ankiSetupManager.refresh()
        refreshMiningAdmission()
    }

    private fun probeAnkiMiningTarget(
        cancellation: AnkiCancellation,
    ): AnkiMiningTargetReadiness {
        if (cancellation.isCancelled()) {
            return AnkiMiningTargetReadiness.Blocked("Anki target verification was cancelled", true)
        }
        val legacyTarget =
            try {
                runBlocking { settingsRepository.settings.first().legacyNoteType }
            } catch (_: Exception) {
                return AnkiMiningTargetReadiness.Blocked(
                    "Saved Anki target settings could not be read",
                    true,
                )
            }
        if (legacyTarget != null) {
            return AnkiMiningTargetReadiness.Blocked(
                "Review and accept migration from the saved legacy note type before mining",
                false,
            )
        }
        return try {
            when (val model = ankiProviderRuntime.inspectAnkiMinerModel(cancellation)) {
            is AnkiMinerModelProvisioningResult.Ready -> {
                val pending = ankiProviderRuntime.remediationInventory(cancellation).pending
                if (pending.isEmpty()) {
                    AnkiMiningTargetReadiness.Ready
                } else {
                    AnkiMiningTargetReadiness.Blocked(
                        "Resolve pending Anki recovery items before mining",
                        false,
                    )
                }
            }
            AnkiMinerModelProvisioningResult.Missing ->
                AnkiMiningTargetReadiness.Blocked(
                    "Create the Anki Miner note type in setup before mining",
                    true,
                )
            is AnkiMinerModelProvisioningResult.Conflict ->
                AnkiMiningTargetReadiness.Blocked(model.stableMessage, false)
            is AnkiMinerModelProvisioningResult.RecoveryRequired ->
                AnkiMiningTargetReadiness.Blocked(model.stableMessage, true)
            is AnkiMinerModelProvisioningResult.FailedBeforeEntry ->
                AnkiMiningTargetReadiness.Blocked(model.stableMessage, model.retryable)
            }
        } catch (_: RuntimeException) {
            AnkiMiningTargetReadiness.Blocked(
                "The Anki Miner target and recovery state could not be inspected",
                true,
            )
        }
    }
}
