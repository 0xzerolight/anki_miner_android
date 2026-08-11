package com.ankiminer.android.diagnostics.log

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileLogSinkTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `records reach the file in the order they were written`() =
        runTest {
            val directory = temporaryFolder.newFolder("ordered")
            val sink = newSink(directory)

            repeat(5) { index -> sink.write("line-$index") }
            sink.flush()

            assertEquals((0..4).map { "line-$it" }, logIn(directory).readLines())
            assertNull(sink.disabledBy)
        }

    @Test
    fun `rotation at maxBytes moves the file to the numbered backup`() =
        runTest {
            val directory = temporaryFolder.newFolder("rotating")
            // Four 15-byte records overshoot the limit; a single one after the rotation does not.
            val sink = newSink(directory, maxBytes = 48)

            repeat(4) { index -> sink.write("rotated-line-$index") }
            sink.flush()

            assertEquals(
                (0..3).map { "rotated-line-$it" },
                File(directory, "anki_miner_app.log.1").readLines(),
            )

            sink.write("after-rotation")
            sink.flush()

            assertEquals(listOf("after-rotation"), logIn(directory).readLines())
        }

    @Test
    fun `backupCount bounds how many rotations are kept`() =
        runTest {
            val directory = temporaryFolder.newFolder("pruned")
            val sink = newSink(directory, maxBytes = 8, backupCount = 2)

            repeat(4) { index ->
                sink.write("rotation-$index")
                sink.flush()
            }

            assertEquals(listOf("rotation-3"), File(directory, "anki_miner_app.log.1").readLines())
            assertEquals(listOf("rotation-2"), File(directory, "anki_miner_app.log.2").readLines())
            assertFalse(File(directory, "anki_miner_app.log.3").exists())
        }

    @Test
    fun `a failed rotation disables the sink before the active log can grow again`() =
        runTest {
            val directory = temporaryFolder.newFolder("rotation-failure")
            val blockedBackup = File(directory, "anki_miner_app.log.1").apply { mkdir() }
            File(blockedBackup, "keep").writeText("occupied")
            val sink = newSink(directory, maxBytes = 8)

            sink.write("rotation-0")
            sink.flush()

            val sizeAtFailure = logIn(directory).length()
            assertNotNull(sink.disabledBy)

            sink.write("must-not-append")
            sink.flush()

            assertEquals(sizeAtFailure, logIn(directory).length())
            assertTrue(File(blockedBackup, "keep").isFile)
        }

    @Test
    fun `records dropped by the bounded queue are reported as a gap`() =
        runTest {
            val directory = temporaryFolder.newFolder("dropping")
            // The writer coroutine cannot run until this test suspends, so all ten writes contend
            // for a queue of four and the six oldest are dropped.
            val sink = newSink(directory, lineCapacity = 4)

            repeat(10) { index -> sink.write("line-$index") }
            sink.flush()

            val lines = logIn(directory).readLines()
            assertTrue(lines.first().contains(" W run=- c=diag op=log.dropped n=6 outcome=fail"))
            assertTrue(lines[1].startsWith('\t'))
            assertEquals(listOf("line-6", "line-7", "line-8", "line-9"), lines.drop(2))
        }

    @Test
    fun `a snapshot drains the queue and copies whole files across a rotation`() =
        runTest {
            val directory = temporaryFolder.newFolder("snapshotting")
            val destination = temporaryFolder.newFolder("bundle")
            // Four 15-byte records overshoot the limit; a single one after the rotation does not.
            val sink = newSink(directory, maxBytes = 48)

            repeat(4) { index -> sink.write("rotated-line-$index") }
            sink.flush()
            // Deliberately not flushed: a snapshot that copied on the caller's thread instead of on
            // the writer coroutine would miss this record, and the file it belongs in would not
            // exist yet.
            sink.write("after-rotation")

            val copies = sink.snapshot(destination)

            assertEquals(
                listOf("anki_miner_app.log", "anki_miner_app.log.1"),
                copies.map { it.name },
            )
            assertTrue(copies.all { it.parentFile == destination })
            assertEquals(listOf("after-rotation"), copies[0].readLines())
            assertEquals((0..3).map { "rotated-line-$it" }, copies[1].readLines())
        }

    @Test
    fun `flush completes while later writes keep the queue nonempty`() =
        runTest {
            val directory = temporaryFolder.newFolder("flush-cutoff")
            val replenisher = QueueReplenisher()
            val sink =
                FileLogSink(
                    directory = directory,
                    maxBytes = Long.MAX_VALUE,
                    scope = backgroundScope,
                    lineCapacity = REPLENISH_LINES,
                    dispatcher = Dispatchers.IO,
                    openStream = replenisher::open,
                )
            replenisher.sink = sink
            sink.write("warmup")
            withContext(Dispatchers.IO) { sink.flush() }

            replenisher.enabled.set(true)
            sink.write("before-flush")
            try {
                withContext(Dispatchers.IO) {
                    withTimeout(COMMAND_TIMEOUT_MILLIS) { sink.flush() }
                }
            } finally {
                replenisher.enabled.set(false)
            }

            assertTrue(replenisher.written.get() >= REPLENISH_LINES)
            assertTrue(logIn(directory).readLines().contains("before-flush"))
        }

    @Test
    fun `snapshot completes while later writes keep the queue nonempty`() =
        runTest {
            val directory = temporaryFolder.newFolder("snapshot-cutoff")
            val destination = temporaryFolder.newFolder("snapshot-cutoff-bundle")
            val replenisher = QueueReplenisher()
            val sink =
                FileLogSink(
                    directory = directory,
                    maxBytes = Long.MAX_VALUE,
                    scope = backgroundScope,
                    lineCapacity = REPLENISH_LINES,
                    dispatcher = Dispatchers.IO,
                    openStream = replenisher::open,
                )
            replenisher.sink = sink
            sink.write("warmup")
            withContext(Dispatchers.IO) { sink.flush() }

            replenisher.enabled.set(true)
            sink.write("before-snapshot")
            val copies =
                try {
                    withContext(Dispatchers.IO) {
                        withTimeout(COMMAND_TIMEOUT_MILLIS) { sink.snapshot(destination) }
                    }
                } finally {
                    replenisher.enabled.set(false)
                }

            assertTrue(replenisher.written.get() >= REPLENISH_LINES)
            assertTrue(copies.single().readLines().contains("before-snapshot"))
        }

    @Test
    fun `a failed snapshot leaves the sink writing`() =
        runTest {
            val directory = temporaryFolder.newFolder("snapshot-failure")
            // A regular file where the snapshot wants a directory: mkdirs() cannot create it and
            // the copy has nowhere to land. This is the near-full-cache export in miniature.
            val destination = temporaryFolder.newFile("occupied-destination")
            val sink = newSink(directory)

            sink.write("before-snapshot")
            var thrown: Throwable? = null
            try {
                sink.snapshot(destination)
            } catch (failure: IOException) {
                thrown = failure
            }

            assertNotNull(thrown)
            // The export failed; the log it was exporting must not have died with it.
            assertNull(sink.disabledBy)

            sink.write("after-snapshot")
            sink.flush()

            assertEquals(
                listOf("before-snapshot", "after-snapshot"),
                logIn(directory).readLines(),
            )
        }

    @Test
    fun `concurrent producers never interleave a partial line`() =
        runTest {
            val directory = temporaryFolder.newFolder("concurrent")
            // Deliberately the production dispatcher: the point of this test is real threads
            // handing lines to one writer.
            val sink = FileLogSink(directory, scope = backgroundScope)
            val producers =
                (0 until PRODUCERS).map { producer ->
                    Thread {
                        repeat(LINES_PER_PRODUCER) { index ->
                            sink.write("p$producer-i$index-" + "x".repeat(40))
                        }
                    }
                }

            producers.forEach(Thread::start)
            producers.forEach(Thread::join)
            sink.flush()

            val lines = logIn(directory).readLines()
            assertEquals(PRODUCERS * LINES_PER_PRODUCER, lines.size)
            assertTrue(lines.all { it.matches(Regex("p\\d-i\\d+-x{40}")) })
        }

    @Test
    fun `an unwritable target disables the sink instead of throwing`() =
        runTest {
            val occupied = temporaryFolder.newFile("occupied")
            val sink = newSink(occupied)

            sink.write("line-0")
            sink.flush()

            val disabled = sink.disabledBy
            assertNotNull(disabled)

            sink.write("line-1")
            sink.flush()

            // Still disabled by the original failure, and no file was created beside the target.
            assertSame(disabled, sink.disabledBy)
            assertTrue(occupied.isFile)
        }

    @Test
    fun `a failing write disables the sink and never reaches the caller`() =
        runTest {
            val directory = temporaryFolder.newFolder("failing")
            val stream = FailingOutputStream()
            val sink = newSink(directory, openStream = { stream })

            sink.write("line-0")
            sink.flush()

            assertNotNull(sink.disabledBy)
            val attemptsBeforeDisable = stream.attempts
            assertTrue(attemptsBeforeDisable > 0)

            sink.write("line-1")
            sink.flush()

            // A disabled sink stops touching the file altogether, and flush() still returns.
            assertEquals(attemptsBeforeDisable, stream.attempts)
        }

    private class FailingOutputStream : OutputStream() {
        var attempts = 0
            private set

        override fun write(b: Int) = fail()

        override fun write(
            b: ByteArray,
            off: Int,
            len: Int,
        ) = fail()

        override fun flush() = fail()

        private fun fail(): Nothing {
            attempts++
            throw IOException("No space left on device")
        }
    }

    private class QueueReplenisher {
        val enabled = AtomicBoolean()
        val written = AtomicInteger()
        lateinit var sink: FileLogSink

        fun open(file: File): OutputStream =
            object : OutputStream() {
                private val delegate = FileOutputStream(file, true)

                override fun write(b: Int) = delegate.write(b)

                override fun write(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ) = delegate.write(b, off, len)

                override fun flush() {
                    delegate.flush()
                    if (enabled.get()) {
                        repeat(REPLENISH_LINES) { index ->
                            sink.write("later-${written.getAndIncrement()}-$index")
                        }
                    }
                }

                override fun close() = delegate.close()
            }
    }

    private fun TestScope.newSink(
        directory: File,
        maxBytes: Long = 4L * 1024 * 1024,
        backupCount: Int = 1,
        lineCapacity: Int = 4096,
        openStream: ((File) -> OutputStream)? = null,
    ) = FileLogSink(
        directory = directory,
        maxBytes = maxBytes,
        backupCount = backupCount,
        scope = backgroundScope,
        lineCapacity = lineCapacity,
        dispatcher = StandardTestDispatcher(testScheduler),
        openStream = openStream ?: { file -> FileOutputStream(file, true) },
    )

    private fun logIn(directory: File) = File(directory, "anki_miner_app.log")

    private companion object {
        const val PRODUCERS = 8
        const val LINES_PER_PRODUCER = 200
        const val REPLENISH_LINES = 128
        const val COMMAND_TIMEOUT_MILLIS = 5_000L
    }
}
