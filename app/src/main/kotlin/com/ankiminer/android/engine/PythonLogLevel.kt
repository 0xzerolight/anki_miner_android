package com.ankiminer.android.engine

import com.ankiminer.android.diagnostics.log.AppLog
import com.ankiminer.android.diagnostics.log.LogComponent
import com.ankiminer.android.diagnostics.log.LogLevel

/**
 * Wire name of the Python level that pairs with a Kotlin [LogLevel].
 *
 * Exhaustive rather than `else -> "info"`: the toggle only ever produces INFO or DEBUG, so a caller
 * arriving with WARN or ERROR is a mistake that should fail to compile, not silently raise Python
 * logging to INFO.
 */
internal fun pythonLogLevelName(level: LogLevel): String =
    when (level) {
        LogLevel.DEBUG -> "debug"
        LogLevel.INFO -> "info"
        LogLevel.WARN, LogLevel.ERROR ->
            throw IllegalArgumentException("The Python log level toggle accepts INFO or DEBUG only")
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
            // level.name, not the wire name: rendering the wire name is one of the things that
            // can throw here, and a failure handler must not fail the same way as its subject.
            AppLog.w(LogComponent.DIAG, "python.loglevel", failure, "level" to level.name)
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
