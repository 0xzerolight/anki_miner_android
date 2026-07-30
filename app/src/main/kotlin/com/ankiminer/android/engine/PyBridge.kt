package com.ankiminer.android.engine

import android.content.Context
import android.os.Looper
import com.ankiminer.android.data.settings.DiagnosticsSettingsRepository
import com.ankiminer.android.diagnostics.log.AppLog
import com.ankiminer.android.diagnostics.log.LogComponent
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** Raw, deterministic seam used by the coordinator and its JVM fakes. */
fun interface PyBridge {
    fun dispatch(
        rawRequest: String,
        callbacks: EngineCallbacks?,
    ): String
}

/**
 * The process-global Chaquopy owner. [enqueueFirst] is called from Application.onCreate before
 * any other task is submitted to the serialized Python run executor. Waiting is worker-only, so
 * Application startup never blocks the main thread.
 */
internal class ChaquopyPythonRuntime(
    context: Context,
    private val diagnosticsSettings: DiagnosticsSettingsRepository,
    private val filesDir: File = context.filesDir,
) {
    private val applicationContext = context.applicationContext
    private val bootstrap = PythonRuntimeBootstrapGate<Runtime>(Runtime::home)
    val readiness: StateFlow<PythonRuntimeReadiness> = bootstrap.readiness

    fun enqueueFirst(executor: Executor) {
        bootstrap.enqueueFirst(executor, ::initialize)
    }

    internal fun awaitRuntime(): Runtime =
        bootstrap.await {
            check(Looper.myLooper() != Looper.getMainLooper()) {
                "Python runtime readiness must only be awaited from a worker thread"
            }
        }

    private fun initialize(): Runtime {
        val startedNanos = System.nanoTime()
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(applicationContext))
        }
        AppLog.i(
            LogComponent.BOOTSTRAP,
            "python.start",
            "ms" to (System.nanoTime() - startedNanos) / 1_000_000L,
        )
        val requestedHome = filesDir.canonicalPath
        val boundary = Python.getInstance().getModule("android_bridge.boundary")
        val bootstrapRequest = BridgeJsonCodec.encodeBootstrapInitialize(requestedHome)
        val rawReady =
            boundary
                .callAttr("dispatch", bootstrapRequest)
                .toJava(String::class.java)
        val ready = BridgeJsonCodec.decode(rawReady)
        // ANKI_MINER_HOME freezes at import, so a mismatch here means every later path resolves
        // somewhere else. The check message cannot name the two homes; this record can.
        if (ready !is BridgeMessage.BootstrapReady || ready.home != requestedHome) {
            AppLog.e(
                LogComponent.BOOTSTRAP,
                "python.home",
                null,
                "requested" to requestedHome,
                "confirmed" to (ready as? BridgeMessage.BootstrapReady)?.home,
            )
        }
        check(ready is BridgeMessage.BootstrapReady && ready.home == requestedHome) {
            "Python bootstrap did not confirm the requested engine home"
        }
        AppLog.i(LogComponent.BOOTSTRAP, "python.home", "home" to requestedHome)
        // Before tokenizer.configure and any engine work: the app starts Python work at launch
        // without the user touching anything, so waiting for the settings collector in
        // AnkiMinerApplication would leave the first run of the process logging at INFO.
        applyStoredPythonLogLevel(
            readVerbose = { runBlocking { diagnosticsSettings.verboseLogging.first() } },
            dispatch = { raw -> boundary.callAttr("dispatch", raw).toJava(String::class.java) },
        )
        return Runtime(requestedHome, boundary)
    }

    internal data class Runtime(
        val home: String,
        val boundary: PyObject,
    )
}

/** Every bridge and Python-backed resource operation waits for the eager process bootstrap. */
internal class ChaquopyPyBridge(
    private val runtime: ChaquopyPythonRuntime,
) : PyBridge {

    override fun dispatch(
        rawRequest: String,
        callbacks: EngineCallbacks?,
    ): String {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "Python bridge calls must run on a worker thread"
        }
        val boundary = runtime.awaitRuntime().boundary
        val result =
            if (callbacks == null) {
                boundary.callAttr("dispatch", rawRequest)
            } else {
                boundary.callAttr("dispatch", rawRequest, callbacks)
            }
        return result.toJava(String::class.java)
    }
}
