package com.ankiminer.android

import android.app.Application
import com.ankiminer.android.anki.provider.AnkiProviderRuntime
import com.ankiminer.android.engine.ChaquopyPyBridge
import com.ankiminer.android.media.AndroidSafBroker
import com.ankiminer.android.media.SafBroker
import com.ankiminer.android.media.SafInputCacheJanitor
import com.ankiminer.android.mining.AndroidMiningInputOwnerFactory
import com.ankiminer.android.mining.BridgeMiningRepository
import com.ankiminer.android.mining.BuiltInInstalledTokenizerResourceProvider
import com.ankiminer.android.mining.MiningForegroundStarter
import com.ankiminer.android.mining.MiningRepository
import com.ankiminer.android.mining.MiningRuntimePaths
import com.ankiminer.android.mining.ProviderCoordinatorAnkiCallbacks
import com.ankiminer.android.mining.SafSourceGrantReleaser
import com.ankiminer.android.mining.asMiningTaskExecutor
import com.ankiminer.android.service.MiningForegroundSessionController
import java.io.File
import java.util.concurrent.Executors

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
    private val ankiProviderRuntime by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AnkiProviderRuntime(this)
    }

    internal val miningRepository: MiningRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        BridgeMiningRepository(
            pyBridge = ChaquopyPyBridge(this),
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
        )
    }

    override fun onCreate() {
        super.onCreate()
        // This task is ordered before every later Python run on the same executor.
        miningRunExecutor.execute {
            try {
                SafInputCacheJanitor(this).removeOrphans()
            } catch (_: Exception) {
                // A current run creates collision-resistant names and owns its own cleanup.
            }
        }
    }
}
