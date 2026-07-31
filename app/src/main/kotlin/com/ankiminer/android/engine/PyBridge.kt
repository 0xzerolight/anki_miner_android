package com.ankiminer.android.engine

import android.content.Context
import android.os.Looper
import com.ankiminer.android.data.settings.DiagnosticsSettingsRepository
import com.ankiminer.android.diagnostics.log.AppLog
import com.ankiminer.android.diagnostics.log.LogComponent
import com.chaquo.python.PyException
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
        // Interpreter startup and the bridge module import share a stage: both fail on the same
        // causes -- a missing native wheel for this ABI, a truncated asset set -- and neither has
        // reached the protocol yet.
        val boundary =
            pythonBootstrapStage(PythonBootstrapStage.START) {
                if (!Python.isStarted()) {
                    Python.start(AndroidPlatform(applicationContext))
                }
                AppLog.i(
                    LogComponent.BOOTSTRAP,
                    "python.start",
                    "ms" to (System.nanoTime() - startedNanos) / 1_000_000L,
                )
                Python.getInstance().getModule("android_bridge.boundary")
            }
        // Tagged, not bare: getCanonicalPath throws IOException, and an untagged throw here would be
        // reported as a startup failure -- naming Python.start for a fault that happened after the
        // interpreter came up fine. DISPATCH is where it belongs; it is the request's only input.
        val requestedHome = pythonBootstrapStage(PythonBootstrapStage.DISPATCH) { filesDir.canonicalPath }
        val rawReady =
            pythonBootstrapStage(PythonBootstrapStage.DISPATCH) {
                val bootstrapRequest = BridgeJsonCodec.encodeBootstrapInitialize(requestedHome)
                boundary
                    .callAttr("dispatch", bootstrapRequest)
                    .toJava(String::class.java)
            }
        pythonBootstrapStage(PythonBootstrapStage.HANDSHAKE) {
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
        // Resolved outside the DEBUG guard because the failure record needs it too, and it is a
        // bounded prefix scan rather than a parse.
        val type = bridgeEnvelopeType(rawRequest)
        val startedNanos = System.nanoTime()
        emitDispatchEntry(type, rawRequest)
        val response =
            try {
                val result =
                    if (callbacks == null) {
                        boundary.callAttr("dispatch", rawRequest)
                    } else {
                        boundary.callAttr("dispatch", rawRequest, callbacks)
                    }
                result.toJava(String::class.java)
            } catch (failure: PyException) {
                // The Chaquopy message embeds the whole Python traceback, and this is the only place
                // it exists as a throwable: the bridge protocol turns everything else into an error
                // envelope, so an exception escaping here means Python failed outside the protocol.
                AppLog.e(
                    LogComponent.BRIDGE,
                    "dispatch",
                    failure,
                    "type" to type,
                    "outcome" to "fail",
                    "ms" to elapsedMillis(startedNanos),
                )
                throw failure
            }
        AppLog.d(LogComponent.BRIDGE, "dispatch") {
            arrayOf(
                "at" to "exit",
                "outcome" to "ok",
                "ms" to elapsedMillis(startedNanos),
                "bytes" to utf8Length(response),
            )
        }
        return response
    }
}

internal fun emitDispatchEntry(
    type: String,
    rawRequest: String,
) {
    AppLog.d(LogComponent.BRIDGE, "dispatch") {
        arrayOf(
            "at" to "enter",
            "type" to type,
            "bytes" to utf8Length(rawRequest),
            "outcome" to "ok",
        )
    }
}

/** Characters of the envelope prefix scanned for the message type. */
private const val TYPE_PREFIX_WINDOW = 128
private const val TYPE_MARKER = "\"type\":\""
private const val UNKNOWN_TYPE = "?"

/**
 * The bridge message type, read out of the fixed envelope prefix `BridgeJsonCodec.encode` writes.
 *
 * Deliberately not a parse: the payload carries mined Japanese sentences and curation candidate
 * text, and none of it may reach a log record. Anything that does not look like an envelope yields
 * [UNKNOWN_TYPE] rather than throwing, because a log call may not fail its caller.
 */
internal fun bridgeEnvelopeType(rawRequest: String): String {
    val window =
        if (rawRequest.length > TYPE_PREFIX_WINDOW) rawRequest.substring(0, TYPE_PREFIX_WINDOW) else rawRequest
    val marker = window.indexOf(TYPE_MARKER)
    if (marker < 0) return UNKNOWN_TYPE
    val from = marker + TYPE_MARKER.length
    val end = window.indexOf('"', from)
    return if (end < 0) UNKNOWN_TYPE else window.substring(from, end)
}

/**
 * UTF-8 length without encoding the string. A curation envelope reaches a megabyte, and
 * `toByteArray()` would copy all of it once per dispatch just to count it.
 *
 * An unpaired surrogate counts as 3 — the width of the U+FFFD a lenient encoder substitutes. It is
 * a defensive value and not a size the bridge ever sees: `BridgeJsonCodec.strictUtf8` encodes with
 * `CodingErrorAction.REPORT`, so an envelope holding one is rejected before it can be dispatched.
 * `String.toByteArray` does not agree here — it substitutes a single `?` — which is why nothing
 * asserts the two are equal for this input.
 */
internal fun utf8Length(text: String): Int {
    var total = 0
    var index = 0
    while (index < text.length) {
        val codePoint = text.codePointAt(index)
        total +=
            when {
                codePoint < 0x80 -> 1
                codePoint < 0x800 -> 2
                codePoint < 0x10000 -> 3
                else -> 4
            }
        index += Character.charCount(codePoint)
    }
    return total
}

private fun elapsedMillis(startedNanos: Long): Long = (System.nanoTime() - startedNanos) / 1_000_000L
