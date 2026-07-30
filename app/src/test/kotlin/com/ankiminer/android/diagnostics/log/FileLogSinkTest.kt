package com.ankiminer.android.diagnostics.log

import java.io.File
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    fun `records dropped by the bounded queue are reported as a gap`() =
        runTest {
            val directory = temporaryFolder.newFolder("dropping")
            // The writer coroutine cannot run until this test suspends, so all ten writes contend
            // for a queue of four and the six oldest are dropped.
            val sink = newSink(directory, lineCapacity = 4)

            repeat(10) { index -> sink.write("line-$index") }
            sink.flush()

            val lines = logIn(directory).readLines()
            assertTrue(lines.first().contains(" W run=- c=diag op=log.dropped n=6"))
            assertEquals(listOf("line-6", "line-7", "line-8", "line-9"), lines.drop(1))
        }

    @Test
    fun `a snapshot copies whole files across a rotation`() =
        runTest {
            val directory = temporaryFolder.newFolder("snapshotting")
            val destination = temporaryFolder.newFolder("bundle")
            // Four 15-byte records overshoot the limit; a single one after the rotation does not.
            val sink = newSink(directory, maxBytes = 48)

            repeat(4) { index -> sink.write("rotated-line-$index") }
            sink.flush()
            sink.write("after-rotation")
            sink.flush()

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

            assertNotNull(sink.disabledBy)

            sink.write("line-1")
            sink.flush()
        }

    private fun TestScope.newSink(
        directory: File,
        maxBytes: Long = 4L * 1024 * 1024,
        backupCount: Int = 1,
        lineCapacity: Int = 4096,
    ) = FileLogSink(
        directory = directory,
        maxBytes = maxBytes,
        backupCount = backupCount,
        scope = backgroundScope,
        lineCapacity = lineCapacity,
        dispatcher = StandardTestDispatcher(testScheduler),
    )

    private fun logIn(directory: File) = File(directory, "anki_miner_app.log")

    private companion object {
        const val PRODUCERS = 8
        const val LINES_PER_PRODUCER = 200
    }
}
