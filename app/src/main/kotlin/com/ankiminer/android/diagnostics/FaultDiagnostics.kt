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
