package com.ankiminer.android.diagnostics.log

/**
 * Destination for already-rendered records.
 *
 * A sink receives one finished line (plus any TAB-prefixed continuation lines) and never sees the
 * fields, so rendering stays in one place and every destination is byte-identical. [write] is called
 * from whatever thread logged, including the main thread, so an implementation may not block.
 */
internal interface LogSink {
    fun write(rendered: String)

    /** Suspends until every record written before this call is durable in this sink. */
    suspend fun flush() {}

    fun close() {}
}

/** Discards everything. Used where logging must be turned off outright. */
internal object NoOpSink : LogSink {
    override fun write(rendered: String) = Unit
}

/**
 * Holds the most recent records until the real sinks exist.
 *
 * This is the default sink, and it is the only possible record of a failure during Python bootstrap:
 * the engine's own file handler is installed inside `bootstrap.initialize`, so a crash before that
 * point leaves nothing on disk unless Kotlin buffered it. Bounded because a failure that never
 * reaches [AppLog.install] must not grow without limit.
 */
internal class PreInstallBufferSink(private val capacity: Int = DEFAULT_CAPACITY) : LogSink {
    private val buffered = ArrayDeque<String>()
    private var forwardingTo: LogSink? = null

    override fun write(rendered: String) {
        val destination =
            synchronized(buffered) {
                forwardingTo?.let { return@synchronized it }
                while (buffered.size >= capacity) buffered.removeFirst()
                buffered.addLast(rendered)
                null
            }
        destination?.write(rendered)
    }

    /** Retires this buffer atomically, forwarding every later write to [sink]. */
    fun retireTo(sink: LogSink): List<String> =
        synchronized(buffered) {
            forwardingTo = sink
            val captured = buffered.toList()
            buffered.clear()
            captured
        }

    fun drain(): List<String> =
        synchronized(buffered) {
            val captured = buffered.toList()
            buffered.clear()
            captured
        }

    private companion object {
        const val DEFAULT_CAPACITY = 256
    }
}

/** Fans one record out to every destination. */
internal class CompositeSink(private vararg val sinks: LogSink) : LogSink {
    override fun write(rendered: String) {
        for (sink in sinks) {
            // One destination failing must not cost the others the record: logcat is the one that
            // can throw (its native layer), and the file is the one that matters for a bug report.
            try {
                sink.write(rendered)
            } catch (_: Exception) {
                // Nothing to report to; a sink that fails records that fact itself.
            }
        }
    }

    override suspend fun flush() {
        for (sink in sinks) sink.flush()
    }

    override fun close() {
        for (sink in sinks) {
            try {
                sink.close()
            } catch (_: Exception) {
                // Best effort: process teardown must not fail on a log file handle.
            }
        }
    }
}
