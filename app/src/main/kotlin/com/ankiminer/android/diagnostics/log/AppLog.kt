package com.ankiminer.android.diagnostics.log

import java.time.Instant

/**
 * Process-wide structured logging facade. Every Kotlin log call in the app goes through here.
 *
 * It is an object rather than an injected collaborator because the call sites are ~60 files deep,
 * including provider and codec code where a logger parameter would be a large mechanical diff for no
 * benefit; testability comes from the swappable [LogSink]. It is also the reason no code outside
 * [LogcatSink] touches the platform logger: the unit test build has neither Robolectric nor
 * `returnDefaultValues`, so a direct platform call from a tested class throws
 * "Method d in ... not mocked" and reddens the whole suite.
 *
 * Until [install] runs, records accumulate in a [PreInstallBufferSink]; that buffer is the only
 * possible record of a failure during Python bootstrap, which happens before the engine's own file
 * handler exists.
 */
internal object AppLog {
    @Volatile
    private var minLevel: LogLevel = LogLevel.INFO

    @Volatile
    private var sink: LogSink = PreInstallBufferSink()

    /** Read in per-word and per-note loops, so it is a plain field rather than a level comparison. */
    @Volatile
    var debugEnabled: Boolean = false
        private set

    fun setMinLevel(level: LogLevel) {
        minLevel = level
        debugEnabled = level <= LogLevel.DEBUG
    }

    /**
     * Swaps in the real sink and replays whatever was buffered before it existed.
     *
     * Neither swap-then-drain nor drain-then-swap is race-free against a concurrent [emit]: closing
     * the window needs a lock on the write path, which is not worth paying on every record for one
     * call made once during startup. A record emitted at that instant lands in one sink or the
     * other and may sort ahead of the replayed ones.
     */
    @Synchronized
    fun install(sink: LogSink) {
        val previous = this.sink
        this.sink = sink
        if (previous is PreInstallBufferSink) previous.drain().forEach(sink::write)
    }

    suspend fun flush() {
        sink.flush()
    }

    fun i(
        component: LogComponent,
        op: String,
        vararg fields: Pair<String, Any?>,
    ) {
        emit(LogLevel.INFO, component, op, null, fields)
    }

    fun w(
        component: LogComponent,
        op: String,
        failure: Throwable?,
        vararg fields: Pair<String, Any?>,
    ) {
        emit(LogLevel.WARN, component, op, failure, fields)
    }

    fun e(
        component: LogComponent,
        op: String,
        failure: Throwable?,
        vararg fields: Pair<String, Any?>,
    ) {
        emit(LogLevel.ERROR, component, op, failure, fields)
    }

    /** Inline with a lambda so a suppressed DEBUG record allocates nothing at all. */
    inline fun d(
        component: LogComponent,
        op: String,
        fields: () -> Array<out Pair<String, Any?>>,
    ) {
        if (debugEnabled) emit(LogLevel.DEBUG, component, op, null, fields())
    }

    /** For a deliberately swallowed exception: the stack is kept, at DEBUG, with the reason. */
    fun ignored(
        component: LogComponent,
        op: String,
        reason: String,
        failure: Throwable,
    ) {
        emit(LogLevel.DEBUG, component, op, failure, arrayOf("reason" to reason))
    }

    /** A state-machine transition. `op` is the machine name so `op=` stays the primary grep key. */
    fun state(
        component: LogComponent,
        machine: String,
        from: String,
        to: String,
        vararg fields: Pair<String, Any?>,
    ) {
        emit(
            LogLevel.INFO,
            component,
            machine,
            null,
            arrayOf<Pair<String, Any?>>("from" to from, "to" to to) + fields,
        )
    }

    /** Brackets [block] with DEBUG enter/exit records and its elapsed ms. Rethrows unchanged. */
    fun <T> boundary(
        component: LogComponent,
        op: String,
        block: () -> T,
    ): T {
        if (!debugEnabled) return block()
        emit(LogLevel.DEBUG, component, op, null, arrayOf<Pair<String, Any?>>("at" to "enter"))
        val startedNanos = System.nanoTime()
        try {
            val result = block()
            emit(
                LogLevel.DEBUG,
                component,
                op,
                null,
                arrayOf<Pair<String, Any?>>(
                    "at" to "exit",
                    "outcome" to "ok",
                    "ms" to elapsedMillis(startedNanos),
                ),
            )
            return result
        } catch (failure: Throwable) {
            emit(
                LogLevel.DEBUG,
                component,
                op,
                failure,
                arrayOf<Pair<String, Any?>>(
                    "at" to "exit",
                    "outcome" to "error",
                    "ms" to elapsedMillis(startedNanos),
                ),
            )
            throw failure
        }
    }

    /** Not private only so the inline [d] can call it. */
    fun emit(
        level: LogLevel,
        component: LogComponent,
        op: String,
        failure: Throwable?,
        fields: Array<out Pair<String, Any?>>,
    ) {
        if (level < minLevel) return
        try {
            // Rendering is inside the try because it is the part that runs caller-controlled code:
            // toString() on every field and on every link of the cause chain. A Chaquopy
            // PyException whose JNI-backed getMessage() fails once the interpreter is dead throws
            // here — while logging the very failure that killed it. A deep cause chain can also
            // exhaust the stack, which is an Error, hence Throwable rather than Exception.
            sink.write(
                renderLogRecord(
                    Instant.now(),
                    level,
                    LogContext.runId(),
                    component,
                    op,
                    fields,
                    failure,
                ),
            )
        } catch (broken: Throwable) {
            emitRenderFailure(level, component, op, failure, broken)
        }
    }

    /**
     * Last resort when a record cannot be rendered or written: a record built only from values this
     * object controls, so it cannot fail the same way twice. Losing the record entirely would hide
     * the fact that something was logged at all.
     */
    private fun emitRenderFailure(
        level: LogLevel,
        component: LogComponent,
        op: String,
        failure: Throwable?,
        broken: Throwable,
    ) {
        try {
            // Class names only: reading anything else off either throwable is what failed.
            sink.write(
                renderLogRecord(
                    Instant.now(),
                    level,
                    null,
                    component,
                    op,
                    arrayOf(
                        "unrenderable" to (failure?.javaClass?.name ?: "-"),
                        "renderFault" to broken.javaClass.name,
                    ),
                    null,
                ),
            )
        } catch (_: Throwable) {
            // The sink itself is gone. A logging layer that throws into its caller is the classic
            // own-goal, so this is where the record dies.
        }
    }

    private fun elapsedMillis(startedNanos: Long): Long =
        (System.nanoTime() - startedNanos) / 1_000_000L
}
