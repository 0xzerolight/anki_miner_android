package com.ankiminer.android

import android.app.Application
import com.ankiminer.android.anki.provider.AnkiProviderRuntime
import com.ankiminer.android.data.RuntimeWorkCoordinator
import com.ankiminer.android.data.resources.AndroidResourceManager
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
import com.ankiminer.android.mining.MiningRuntimePaths
import com.ankiminer.android.mining.ProviderCoordinatorAnkiCallbacks
import com.ankiminer.android.mining.SafSourceGrantReleaser
import com.ankiminer.android.mining.StatefulMiningRunAdmissionGate
import com.ankiminer.android.mining.asMiningTaskExecutor
import com.ankiminer.android.service.MiningForegroundSessionController
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
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
        )
    }
    internal val miningAdmissionState
        get() = miningAdmissionGate.state

    internal val settingsRepository: AppSettingsRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        DataStoreAppSettingsRepository(this)
    }

    internal val resourceManager: ResourceManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AndroidResourceManager(
            resolver = contentResolver,
            safBroker = safBroker,
            bridge = pyBridge,
            tokenizerResources = tokenizerResourceProvider,
            stagingRoot = File(noBackupFilesDir, "resource-staging"),
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
                        settingsRepository.snapshot(resourceManager.installedDictionaryIds())
                    }
                },
            resourceStartupReady = {
                resourceManager.state.value.startupReadiness == ResourceStartupReadiness.READY
            },
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
            } catch (_: Exception) {
                // A current run creates collision-resistant names and owns its own cleanup.
            }
        }
        refreshMiningAdmission()
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
        applicationScope.launch { resourceManager.recoverAndRefresh() }
    }

    internal fun refreshMiningAdmission() {
        miningControlExecutor.execute {
            try {
                miningAdmissionGate.evaluate(CoordinatorAnkiCancellation())
            } catch (_: RuntimeException) {
                // The fail-closed admission state remains visible and is rechecked at run start.
            }
        }
    }
}
