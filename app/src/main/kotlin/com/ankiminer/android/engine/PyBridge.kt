package com.ankiminer.android.engine

import android.content.Context
import android.os.Looper
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File

/** Raw, deterministic seam used by the coordinator and its JVM fakes. */
fun interface PyBridge {
    fun dispatch(
        rawRequest: String,
        callbacks: EngineCallbacks?,
    ): String
}

/**
 * Process-global Chaquopy owner. Python starts lazily on the calling worker and is bootstrapped
 * through the sole public Python boundary before the requested operation is dispatched.
 */
class ChaquopyPyBridge(
    context: Context,
    private val filesDir: File = context.filesDir,
) : PyBridge {
    private val applicationContext = context.applicationContext

    override fun dispatch(
        rawRequest: String,
        callbacks: EngineCallbacks?,
    ): String {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "Python bridge calls must run on a worker thread"
        }
        val boundary = runtimeBoundary()
        val result =
            if (callbacks == null) {
                boundary.callAttr("dispatch", rawRequest)
            } else {
                boundary.callAttr("dispatch", rawRequest, callbacks)
            }
        return result.toJava(String::class.java)
    }

    private fun runtimeBoundary(): PyObject {
        val requestedHome = filesDir.canonicalPath
        runtime?.let { existing ->
            check(existing.home == requestedHome) {
                "The process Python bridge is already bound to another files directory"
            }
            return existing.boundary
        }
        synchronized(runtimeLock) {
            runtime?.let { existing ->
                check(existing.home == requestedHome) {
                    "The process Python bridge is already bound to another files directory"
                }
                return existing.boundary
            }

            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(applicationContext))
            }
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
            return boundary.also { runtime = Runtime(requestedHome, it) }
        }
    }

    private data class Runtime(
        val home: String,
        val boundary: PyObject,
    )

    private companion object {
        val runtimeLock = Any()

        @Volatile
        var runtime: Runtime? = null
    }
}
