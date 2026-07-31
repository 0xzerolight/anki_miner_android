package com.ankiminer.android.diagnostics.log

/** Test double for assertions against rendered records, in place of logcat. */
internal class RecordingLogSink : LogSink {
    private val captured = mutableListOf<String>()

    val records: List<String>
        get() = synchronized(captured) { captured.toList() }

    override fun write(rendered: String) {
        synchronized(captured) { captured.add(rendered) }
    }
}
