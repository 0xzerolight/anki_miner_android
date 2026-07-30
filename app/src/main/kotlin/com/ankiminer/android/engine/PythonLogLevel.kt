package com.ankiminer.android.engine

import com.ankiminer.android.diagnostics.log.AppLog
import com.ankiminer.android.diagnostics.log.LogComponent
import com.ankiminer.android.diagnostics.log.LogLevel

/** Wire name of the Python level that pairs with a Kotlin [LogLevel]. */
internal fun pythonLogLevelName(level: LogLevel): String =
    when (level) {
        LogLevel.DEBUG -> "debug"
        else -> "info"
    }

/**
 * Raise or lower the Python-side level through the bridge.
 *
 * [dispatch] is the raw seam rather than a [PyBridge] because the bootstrap caller holds the
 * boundary module directly: going through [ChaquopyPyBridge] there would wait on the very runtime
 * future the caller is in the middle of completing.
 *
 * Throws on a rejected request. Callers on a path that must not fail use
 * [applyStoredPythonLogLevel] or [applyPythonLogLevelSafely] instead.
 */
internal fun applyPythonLogLevel(
    level: LogLevel,
    dispatch: (String) -> String,
) {
    val name = pythonLogLevelName(level)
    val response = BridgeJsonCodec.decode(dispatch(BridgeJsonCodec.encodeDiagnosticsLogLevelSet(name)))
    check(response is BridgeMessage.DiagnosticsLogLevelApplied && response.level == name) {
        "Python did not confirm the requested log level"
    }
}

/** Level changes are diagnostics, never a reason to fail the caller that requested one. */
internal fun applyPythonLogLevelSafely(
    level: LogLevel,
    dispatch: (String) -> String,
) {
    runCatching { applyPythonLogLevel(level, dispatch) }
        .onFailure { failure ->
            AppLog.w(LogComponent.DIAG, "python.loglevel", failure, "level" to pythonLogLevelName(level))
        }
}

/**
 * Apply the persisted verbose-logging preference to both sides at bootstrap time.
 *
 * Every part of this is inside one `runCatching`, including the preference read. It runs on the
 * bootstrap worker inside `PythonRuntimeBootstrapGate.enqueueFirst`'s `catch (failure: Throwable)`,
 * over a one-shot `CompletableFuture`: anything thrown here would mark readiness `Failed`
 * permanently and take the embedded Python runtime down for the whole process. A DataStore IO
 * error or a rejected request type is not worth an app with no engine.
 */
internal fun applyStoredPythonLogLevel(
    readVerbose: () -> Boolean,
    dispatch: (String) -> String,
) {
    runCatching {
        val level = if (readVerbose()) LogLevel.DEBUG else LogLevel.INFO
        AppLog.setMinLevel(level)
        applyPythonLogLevel(level, dispatch)
    }.onFailure { failure ->
        AppLog.w(LogComponent.DIAG, "loglevel.bootstrap", failure)
    }
}
