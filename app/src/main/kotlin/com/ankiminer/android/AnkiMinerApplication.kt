package com.ankiminer.android

import android.app.Application
import com.ankiminer.android.anki.provider.AnkiProviderRuntime
import com.ankiminer.android.engine.ChaquopyPyBridge
import com.ankiminer.android.engine.ChaquopyPythonRuntime
import com.ankiminer.android.engine.PythonRuntimeReadiness
import com.ankiminer.android.media.AndroidSafBroker
import com.ankiminer.android.media.SafBroker
import com.ankiminer.android.media.SafInputCacheJanitor
import com.ankiminer.android.mining.AndroidMiningInputOwnerFactory
import com.ankiminer.android.mining.BridgeMiningRepository
import com.ankiminer.android.mining.BuiltInInstalledTokenizerResourceProvider
import com.ankiminer.android.mining.MiningForegroundStarter
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
import kotlinx.coroutines.flow.StateFlow

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
    private val pythonRuntime by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ChaquopyPythonRuntime(this)
    }
    internal val pythonRuntimeReadiness: StateFlow<PythonRuntimeReadiness>
        get() = pythonRuntime.readiness
    private val ankiProviderRuntime by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AnkiProviderRuntime(this)
    }
    private val miningAdmissionGate by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        StatefulMiningRunAdmissionGate(
            ankiProbe = ankiProviderRuntime::probeReadiness,
            notificationProbe = AndroidNotificationPermissionProbe(this),
        )
    }
    internal val miningAdmissionState
        get() = miningAdmissionGate.state

    internal val miningRepository: MiningRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        BridgeMiningRepository(
            pyBridge = ChaquopyPyBridge(pythonRuntime),
            anki = ProviderCoordinatorAnkiCallbacks(ankiProviderRuntime.callbacks),
            inputOwnerFactory = AndroidMiningInputOwnerFactory(this),
            tokenizerResourceProvider = BuiltInInstalledTokenizerResourceProvider(this),
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
    }
}
