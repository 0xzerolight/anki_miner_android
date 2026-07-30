package com.ankiminer.android.diagnostics.log

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale

/**
 * Renders one record as a single line, with any throwable following on TAB-prefixed continuation
 * lines.
 *
 * The whole format exists to keep one parse rule true for every reader: *a line starting with a
 * digit begins a new record*. Everything a caller can put into a record — exception messages holding
 * user file names and Japanese sentences, arbitrary field values — is therefore escaped or stripped
 * rather than passed through.
 */
internal fun renderLogRecord(
    at: Instant,
    level: LogLevel,
    runId: String?,
    component: LogComponent,
    op: String,
    fields: Array<out Pair<String, Any?>>,
    failure: Throwable?,
): String {
    val record = StringBuilder(RECORD_HINT)
    record.append(TIMESTAMP.format(at))
    record.append(' ').append(level.code)
    record.append(" run=").append(renderValue(runId))
    record.append(" c=").append(component.wireName)
    record.append(" op=").append(renderKey(op))
    for ((key, value) in fields) {
        record.append(' ').append(renderKey(key)).append('=').append(renderValue(value))
    }
    if (failure != null) appendFailure(record, failure)
    return record.toString()
}

/**
 * Must match what the Python handler emits (`%Y-%m-%dT%H:%M:%S` + `.mmm` + `Z`) so a maintainer can
 * merge the Kotlin and engine logs with `sort`. [Locale.ROOT] is load-bearing: some locales render
 * non-ASCII digits, which would break both the sort and the "starts with a digit" parse rule.
 */
private val TIMESTAMP: DateTimeFormatter =
    DateTimeFormatter
        .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)
        .withZone(ZoneOffset.UTC)

/** Length of a rendered timestamp; [LogcatSink] indexes the level character past it. */
internal const val TIMESTAMP_LENGTH = 24

private const val RECORD_HINT = 96
private const val ABSENT = "-"
private const val CONTINUATION = "\n\t"
private const val FRAME_INDENT = "\n\t    "
private const val MAX_FRAMES = 200
private const val BARE_PUNCTUATION = "._:/@+-"

private fun renderValue(value: Any?): String {
    val text = value?.toString() ?: return ABSENT
    if (isBareToken(text)) return text
    val quoted = StringBuilder(text.length + 2).append('"')
    for (character in text) {
        when {
            character == '"' -> quoted.append("\\\"")
            character == '\\' -> quoted.append("\\\\")
            character == '\n' -> quoted.append("\\n")
            isControl(character) -> Unit
            else -> quoted.append(character)
        }
    }
    return quoted.append('"').toString()
}

/**
 * Keys and ops are code constants, so this is a guard rather than an escape: a stray separator in a
 * key would silently produce a second, bogus field instead of a visibly mangled one.
 */
private fun renderKey(key: String): String {
    if (isBareToken(key)) return key
    val safe = StringBuilder(key.length)
    for (character in key) safe.append(if (isBareCharacter(character)) character else '_')
    return safe.toString().ifEmpty { "_" }
}

private fun isBareToken(text: String): Boolean = text.isNotEmpty() && text.all(::isBareCharacter)

private fun isBareCharacter(character: Char): Boolean =
    character in 'A'..'Z' ||
        character in 'a'..'z' ||
        character in '0'..'9' ||
        character in BARE_PUNCTUATION

private fun isControl(character: Char): Boolean =
    character < ' ' || character == '\u007F' || character in '\u0080'..'\u009F'

/**
 * Renders the whole failure: message, frames, the full `Caused by:` chain and `Suppressed:` entries.
 *
 * Suppressed entries are not optional — `SafJobFileOwner.close()` builds an aggregate `IOException`
 * whose suppressed chain *is* the diagnostic. A cause chain can be cyclic (two exceptions each
 * naming the other), so traversal is guarded by identity, and the frame budget is shared across the
 * whole chain so one pathological aggregate cannot fill the file.
 */
private fun appendFailure(
    record: StringBuilder,
    failure: Throwable,
) {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var budget = MAX_FRAMES
    var truncated = false

    fun append(
        label: String,
        throwable: Throwable,
    ) {
        record.append(CONTINUATION).append(label)
        if (!seen.add(throwable)) {
            record.append("[circular] ").append(renderText(throwable.javaClass.name))
            return
        }
        record.append(renderText(throwable.toString()))
        for (frame in throwable.stackTrace) {
            if (budget == 0) {
                if (!truncated) {
                    record.append(FRAME_INDENT).append("... frames truncated")
                    truncated = true
                }
                break
            }
            budget--
            record.append(FRAME_INDENT).append("at ").append(renderText(frame.toString()))
        }
        for (suppressed in throwable.suppressed) append("Suppressed: ", suppressed)
        throwable.cause?.let { cause -> append("Caused by: ", cause) }
    }

    append("", failure)
}

/**
 * Continuation lines are already delimited by their TAB prefix, so they need no quoting — only the
 * characters that would end the line early. A Chaquopy `PyException` message embeds a whole Python
 * traceback, newlines included.
 */
private fun renderText(text: String): String {
    if (text.none { it == '\n' || isControl(it) }) return text
    val safe = StringBuilder(text.length)
    for (character in text) {
        when {
            character == '\n' -> safe.append("\\n")
            isControl(character) -> Unit
            else -> safe.append(character)
        }
    }
    return safe.toString()
}
