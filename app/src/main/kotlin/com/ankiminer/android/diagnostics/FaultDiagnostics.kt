package com.ankiminer.android.diagnostics

import com.ankiminer.android.engine.BridgeProtocolException

/**
 * Compact, PII-safe description of an unexpected failure for user-facing fault
 * messages. Uses only the exception class name, its protocol category where it
 * carries one, and the topmost stack frame — never the exception message, which
 * for Chaquopy PyException can embed Python tracebacks containing user file
 * paths.
 *
 * The frame is reported whatever its package: R8 repackages app classes, so a
 * `com.ankiminer` filter would skip the throwing class and name a synthetic
 * lambda instead. For a [BridgeProtocolException] the frame is always the codec's
 * shared throw helper, which is why the category is what identifies the rejected
 * invariant.
 */
internal fun exceptionDigest(failure: Throwable): String {
    val name = failure.javaClass.simpleName.ifEmpty { failure.javaClass.name.substringAfterLast('.') }
    val label = if (failure is BridgeProtocolException) "$name/${failure.category.name}" else name
    val frame = failure.stackTrace.firstOrNull() ?: return label
    return "$label @ ${frame.className.substringAfterLast('.')}.${frame.methodName}:${frame.lineNumber}"
}

/**
 * [exceptionDigest] reduced to a bounded single-token ASCII alphabet so it can be carried inside a
 * protocol error message.
 *
 * The Anki wire error is a fixed `{code, message, retryable}` triple, so a fault digest has nowhere
 * to ride except `message`, and one of the encoders on that path
 * (`AnkiProviderCallbacks.fixedErrorEnvelope`) builds JSON by concatenation. Whitelisting the
 * alphabet — rather than escaping — keeps every encoder on the path safe by construction whatever a
 * repackaged class or synthetic lambda is named. The bound matters for the same reason: a message is
 * validated for valid Unicode but not for length.
 */
internal fun compactFaultToken(failure: Throwable): String =
    exceptionDigest(failure)
        .take(MAX_FAULT_TOKEN_CHARS)
        .map { character -> if (character in FAULT_TOKEN_CHARACTERS) character else '_' }
        .joinToString("")
        .ifBlank { "unknown" }

private const val MAX_FAULT_TOKEN_CHARS = 120

// The digest's own " @ " separator is kept: this token is read by a person pasting it into a bug
// report, and both carriers (a protocol error message, one key=value diagnostics line) hold a space
// without ambiguity.
private val FAULT_TOKEN_CHARACTERS =
    ('a'..'z') + ('A'..'Z') + ('0'..'9') + setOf('.', '_', '-', ':', '/', '$', '@', ' ')
