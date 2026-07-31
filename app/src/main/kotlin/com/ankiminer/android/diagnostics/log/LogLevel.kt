package com.ankiminer.android.diagnostics.log

/**
 * Severity of one log record.
 *
 * [code] is the single character written into the record. A one-character level keeps the fixed
 * prefix of every line short enough that a maintainer can read the timestamp, level and component
 * without horizontal scrolling, and it is what [LogcatSink] re-derives the logcat priority from.
 */
internal enum class LogLevel(val code: Char) {
    DEBUG('D'),
    INFO('I'),
    WARN('W'),
    ERROR('E'),
}
