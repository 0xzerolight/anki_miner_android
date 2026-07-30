package com.ankiminer.android.diagnostics

import java.util.concurrent.atomic.AtomicReference

/**
 * Holds the most recent unattributable Anki provider fault so the tester diagnostics report can name
 * it after the mining screen is gone.
 *
 * The Anki callbacks deliberately answer with stable, PII-safe messages, and the catch-all arm used
 * to discard everything that distinguished one internal failure from another — which is why Issue #6
 * could not be root-caused from the artifact the app produces. Only the bounded token from
 * [compactFaultToken] and the wire operation name are kept; never the exception message.
 *
 * Process-wide by necessity: the Python worker thread writes it, the settings share action reads it.
 * One slot is enough — the run stops at the first internal failure.
 */
internal object AnkiFaultRecorder {
    private val fault = AtomicReference<String?>(null)

    fun record(
        operationWireName: String,
        token: String,
    ) {
        fault.set("$operationWireName:$token")
    }

    fun lastFault(): String? = fault.get()

    /** Test-only reset; the recorder outlives every run in production. */
    fun clear() {
        fault.set(null)
    }
}
