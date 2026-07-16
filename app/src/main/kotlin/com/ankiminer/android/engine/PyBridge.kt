package com.ankiminer.android.engine

import android.content.Context
import android.os.Looper
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.StateFlow

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
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(applicationContext))
        }
        val requestedHome = filesDir.canonicalPath
        val boundary = Python.getInstance().getModule("android_bridge.boundary")
        val bootstrapRequest = BridgeJsonCodec.encodeBootstrapInitialize(requestedHome)
        val rawReady =
            boundary
                .callAttr("dispatch", bootstrapRequest)
                .toJava(String::class.java)
        val ready = BridgeJsonCodec.decode(rawReady)
        check(ready is BridgeMessage.BootstrapReady && ready.home == requestedHome) {
            "Python bootstrap did not confirm the requested engine home"
        }
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
