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
                select {
                    // Commands are offered first so a busy producer cannot starve a rendezvous
                    // send. Record ordering does not depend on that bias: every command drains
                    // the queued lines before it runs.
                    commands.onReceive { command -> execute(command) }
                    lines.onReceiveCatching { received ->
                        val line = received.getOrNull()
                        if (line == null) running = false else appendBatch(line)
                    }
                }
            }
        } finally {
            drainQueuedLines()
            flushFile()
            closeFile()
            commands.close()
        }
    }

    private fun execute(command: LogCommand) {
        drainQueuedLines()
        flushFile()
        when (command) {
            is LogCommand.Flush -> command.done.complete(Unit)
            is LogCommand.Snapshot ->
                try {
                    command.done.complete(copyInto(command.destDir))
                } catch (failure: IOException) {
                    command.done.completeExceptionally(failure)
                }
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

    private fun drainQueuedLines() {
        while (true) {
            appendRecord(lines.tryReceive().getOrNull() ?: return)
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
            val counted = CountingOutputStream(FileOutputStream(target, true), target.length())
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
            target.delete()
            return
        }
        File(directory, "$baseName.$backupCount").delete()
        for (index in backupCount - 1 downTo 1) {
            val source = File(directory, "$baseName.$index")
            if (source.exists()) source.renameTo(File(directory, "$baseName.${index + 1}"))
        }
        target.renameTo(File(directory, "$baseName.1"))
    }

    private fun copyInto(destination: File): List<File> {
        destination.mkdirs()
        val sources = listOf(target) + (1..maxOf(backupCount, 0)).map { File(directory, "$baseName.$it") }
        return sources.filter(File::isFile).map { source ->
            source.copyTo(File(destination, source.name), overwrite = true)
        }
    }

    private fun disable(failure: IOException) {
        if (disabledBy == null) disabledBy = failure
        closeFile()
    }

    private fun droppedRecord(count: Long): String =
        renderLogRecord(
            at = Instant.now(),
            level = LogLevel.WARN,
            runId = null,
            component = LogComponent.DIAG,
            op = "log.dropped",
            fields = arrayOf("n" to count),
            failure = null,
        )

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
