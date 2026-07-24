package com.ankiminer.android.diagnostics

/**
 * Compact, PII-safe description of an unexpected failure for user-facing fault
 * messages. Uses only the exception class name and the first in-app stack
 * frame — never the exception message, which for Chaquopy PyException can
 * embed Python tracebacks containing user file paths.
 */
internal fun exceptionDigest(failure: Throwable): String {
    val name = failure.javaClass.simpleName.ifEmpty { failure.javaClass.name.substringAfterLast('.') }
    val frame =
        failure.stackTrace.firstOrNull { element ->
            element.className.startsWith("com.ankiminer")
        } ?: return name
    return "$name @ ${frame.className.substringAfterLast('.')}.${frame.methodName}:${frame.lineNumber}"
}
