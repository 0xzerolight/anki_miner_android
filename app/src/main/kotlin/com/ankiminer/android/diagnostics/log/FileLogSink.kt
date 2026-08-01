package com.ankiminer.android.diagnostics.log

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

internal sealed interface LogCommand {
    class Flush(val done: CompletableDeferred<Unit>) : LogCommand

    class Snapshot(val destDir: File, val done: CompletableDeferred<List<File>>) : LogCommand
}

/**
 * Appends rendered records to a rotating file, from a single writer coroutine.
 *
 * The file is `anki_miner_app.log`, deliberately *not* the engine's `anki_miner.log`: that one is
 * owned by Python's `RotatingFileHandler`, which renames the inode out from under any open Kotlin
 * stream when it rolls over.
 *
 * [write] is called from the main thread, so it only hands the line to a channel — no I/O, no
 * suspension, no throwing. Everything else (opening, rotation, snapshots) happens on the one writer
 * coroutine, which is what makes rotation unable to race itself and unable to interleave with a
 * snapshot.
 *
 * Lines and commands travel on separate channels because `DROP_OLDEST` cannot discriminate: a
 * `Flush` or `Snapshot` sharing the lines channel could be silently dropped under load, and the
 * caller would wait forever.
 */
internal class FileLogSink(
    private val directory: File,
    private val baseName: String = "anki_miner_app.log",
    private val maxBytes: Long = 4L * 1024 * 1024,
    private val backupCount: Int = 1,
    scope: CoroutineScope,
    lineCapacity: Int = 4096,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val openStream: (File) -> OutputStream = { file -> FileOutputStream(file, true) },
) : LogSink, AutoCloseable {
    private val dropped = AtomicLong()
    private val lines =
        Channel<String>(lineCapacity, BufferOverflow.DROP_OLDEST) { dropped.incrementAndGet() }
    private val commands = Channel<LogCommand>(Channel.RENDEZVOUS)
    private val target = File(directory, baseName)

    /** Set once an [IOException] (ENOSPC, a read-only volume) has taken the file away. */
    @Volatile
    var disabledBy: IOException? = null
        private set

    /** Writer state. Touched only by the writer coroutine, so it needs no synchronization. */
    private var open: OpenFile? = null

    init {
        val writer = scope.launch(dispatcher) { consume() }
        // If the scope was already cancelled the writer never runs; closing the command channel then
        // keeps flush() from waiting on a rendezvous that can never complete.
        writer.invokeOnCompletion { commands.close() }
    }

    override fun write(rendered: String) {
        // Main-thread safe by construction: trySend never suspends, and DROP_OLDEST means it never
        // fails for a full buffer either — the dropped element is counted by the channel's
        // undelivered-element handler and reported in the next batch.
        lines.trySend(rendered)
    }

    override suspend fun flush() {
        val done = CompletableDeferred<Unit>()
        if (submit(LogCommand.Flush(done))) done.await()
    }

    /**
     * Copies the current log file and its backups into [destDir], returning the copies.
     *
     * Runs on the writer coroutine, so the set of files it copies is never one a rotation is halfway
     * through renaming.
     */
    suspend fun snapshot(destDir: File): List<File> {
        val done = CompletableDeferred<List<File>>()
        if (!submit(LogCommand.Snapshot(destDir, done))) return emptyList()
        return done.await()
    }

    override fun close() {
        // Closing the lines channel drains what is already queued and then stops the writer, which
        // closes the command channel on its way out so a concurrent flush() fails fast.
        lines.close()
    }

    private suspend fun submit(command: LogCommand): Boolean =
        try {
            commands.send(command)
            true
        } catch (_: ClosedSendChannelException) {
            // The writer is gone; there is nothing left to flush or copy.
            false
        }

    private suspend fun consume() {
        try {
            var running = true
            while (running) {
                try {
                    select {
                        // Commands are offered first so a busy producer cannot starve a rendezvous
                        // send. Ordering is not what this buys: the drain at the top of execute()
                        // is what puts every queued record on disk ahead of the command.
                        commands.onReceive { command -> execute(command) }
                        lines.onReceiveCatching { received ->
                            val line = received.getOrNull()
                            if (line == null) running = false else appendBatch(line)
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    // This coroutine runs in the application scope, which carries no
                    // CoroutineExceptionHandler: anything escaping here reaches Android's default
                    // uncaught handler and takes the process down over a log line. File operations
                    // throw more than IOException — SecurityException from rename and delete.
                    disable(failure)
                }
            }
        } finally {
            drainQueuedLines()
            flushFile()
            closeFile()
            // Closed last so a caller suspended in submit() fails fast instead of waiting on a
            // rendezvous nobody will ever receive.
            commands.close()
        }
    }

    /**
     * Every path out of here completes [LogCommand.Flush.done] or [LogCommand.Snapshot.done]. A
     * command whose deferred is dropped strands its caller in `await()` forever, and the ZIP export
     * is that caller.
     */
    private fun execute(command: LogCommand) {
        try {
            // Getting the queue onto disk is this sink's own work, so a failure here is the file
            // being gone and disable() owns it, exactly as it does on the write path.
            drainQueuedLines()
            flushFile()
        } catch (failure: Throwable) {
            fail(command, failure)
            if (failure is CancellationException) throw failure
            disable(failure)
            return
        }
        try {
            when (command) {
                is LogCommand.Flush -> command.done.complete(Unit)
                is LogCommand.Snapshot -> command.done.complete(copyInto(command.destDir))
            }
        } catch (failure: Throwable) {
            // A snapshot is a READ of the log for the export bundle, not a write to it: a full
            // cache or an unusable destination says nothing about the file this sink appends to.
            // Disabling here silently dropped every later record in the process — including the
            // ones describing the failure the user was trying to report.
            fail(command, failure)
            if (failure is CancellationException) throw failure
        }
    }

    private fun fail(
        command: LogCommand,
        failure: Throwable,
    ) {
        when (command) {
            is LogCommand.Flush -> command.done.completeExceptionally(failure)
            is LogCommand.Snapshot -> command.done.completeExceptionally(failure)
        }
    }

    private fun appendBatch(first: String) {
        appendRecord(first)
        var budget = BATCH_LINES - 1
        while (budget > 0) {
            val next = lines.tryReceive().getOrNull() ?: break
            appendRecord(next)
            budget--
        }
        flushFile()
    }

    /**
     * Drains through [appendBatch] rather than record by record: the rotation check lives in
     * [flushFile], so a bare loop would write the whole queue — up to `lineCapacity` records, which
     * with 200-frame stacks is far past the size cap — before any rotation could fire.
     */
    private fun drainQueuedLines() {
        while (true) {
            appendBatch(lines.tryReceive().getOrNull() ?: return)
        }
    }

    private fun appendRecord(record: String) {
        val file = openFile() ?: return
        // A silent gap is not acceptable: report the drop before the record that survived it.
        val gap = dropped.getAndSet(0L)
        if (gap > 0L) writeLine(file, droppedRecord(gap))
        writeLine(file, record)
    }

    private fun writeLine(
        file: OpenFile,
        record: String,
    ) {
        try {
            file.writer.write(record)
            file.writer.write("\n")
        } catch (failure: IOException) {
            disable(failure)
        }
    }

    private fun flushFile() {
        val file = open ?: return
        try {
            file.writer.flush()
        } catch (failure: IOException) {
            disable(failure)
            return
        }
        // Checked after the flush, where the byte count is exact: a BufferedWriter cannot say how
        // many bytes it has actually encoded.
        if (file.bytes >= maxBytes) rotate()
    }

    private fun openFile(): OpenFile? {
        if (disabledBy != null) return null
        open?.let { return it }
        return try {
            directory.mkdirs()
            // The stream is opened through a parameter so a test can make the write path itself
            // fail; a directory that cannot be opened only exercises this method's own catch.
            val counted = CountingOutputStream(openStream(target), target.length())
            OpenFile(counted, BufferedWriter(OutputStreamWriter(counted, StandardCharsets.UTF_8)))
                .also { open = it }
        } catch (failure: IOException) {
            disable(failure)
            null
        }
    }

    private fun closeFile() {
        val file = open ?: return
        open = null
        try {
            file.writer.close()
        } catch (_: IOException) {
            // Nothing left to salvage; the records are already on their way out or lost.
        }
    }

    private fun rotate() {
        closeFile()
        if (backupCount <= 0) {
            if (target.exists() && !target.delete()) {
                disable(IOException("Could not delete the active log during rotation"))
            }
            return
        }
        val oldest = File(directory, "$baseName.$backupCount")
        if (oldest.exists() && !oldest.delete()) {
            disable(IOException("Could not delete the oldest log backup during rotation"))
            return
        }
        for (index in backupCount - 1 downTo 1) {
            val source = File(directory, "$baseName.$index")
            if (source.exists() && !source.renameTo(File(directory, "$baseName.${index + 1}"))) {
                disable(IOException("Could not shift a log backup during rotation"))
                return
            }
        }
        if (target.exists() && !target.renameTo(File(directory, "$baseName.1"))) {
            disable(IOException("Could not rotate the active log"))
        }
    }

    private fun copyInto(destination: File): List<File> {
        destination.mkdirs()
        val sources = listOf(target) + (1..maxOf(backupCount, 0)).map { File(directory, "$baseName.$it") }
        return sources.filter(File::isFile).map { source ->
            source.copyTo(File(destination, source.name), overwrite = true)
        }
    }

    private fun disable(failure: Throwable) {
        if (disabledBy == null) {
            disabledBy = failure as? IOException ?: IOException("log writer failed", failure)
        }
        closeFile()
    }

    private fun droppedRecord(count: Long): String =
        renderLogRecord(
            at = Instant.now(),
            level = LogLevel.WARN,
            runId = null,
            component = LogComponent.DIAG,
            op = "log.dropped",
            fields = arrayOf("n" to count, "outcome" to "fail"),
            failure = DroppedLogRecordsException(count),
        )

    private class DroppedLogRecordsException(count: Long) :
        RuntimeException("$count queued log records were dropped", null, false, false)

    private class OpenFile(
        private val counted: CountingOutputStream,
        val writer: BufferedWriter,
    ) {
        val bytes: Long
            get() = counted.count
    }

    /** Byte-exact rotation accounting for a character-oriented writer. */
    private class CountingOutputStream(
        private val delegate: OutputStream,
        initial: Long,
    ) : OutputStream() {
        var count: Long = initial
            private set

        override fun write(b: Int) {
            delegate.write(b)
            count++
        }

        override fun write(
            b: ByteArray,
            off: Int,
            len: Int,
        ) {
            delegate.write(b, off, len)
            count += len
        }

        override fun flush() = delegate.flush()

        override fun close() = delegate.close()
    }

    private companion object {
        const val BATCH_LINES = 64
    }
}
