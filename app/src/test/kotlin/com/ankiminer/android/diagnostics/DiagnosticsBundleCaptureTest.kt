package com.ankiminer.android.diagnostics

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DiagnosticsBundleCaptureTest {
    @Test
    fun `python snapshot retries a rollover between backup and current copies`() {
        val files = Files.createTempDirectory("diagnostics-python-source").toFile()
        val workspace = Files.createTempDirectory("diagnostics-python-workspace").toFile()
        try {
            files.resolve(BACKUP).writeText("old backup\n")
            files.resolve(CURRENT).writeText("former current\n")
            workspace.resolve("keep.txt").writeText("unrelated workspace data\n")
            val copies = mutableListOf<String>()
            var rolled = false
            val snapshotter =
                PythonLogSnapshotter(files) { name, _ ->
                    copies += name
                    if (name == BACKUP && !rolled) {
                        rolled = true
                        files.resolve(CURRENT).copyTo(files.resolve(BACKUP), overwrite = true)
                        files.resolve(CURRENT).writeText("new current\n")
                    }
                }

            val snapshot = snapshotter.snapshot(workspace)

            assertEquals(listOf(BACKUP, CURRENT, BACKUP, CURRENT), copies)
            assertEquals("former current\n", snapshot.getValue(BACKUP).readText())
            assertEquals("new current\n", snapshot.getValue(CURRENT).readText())
            assertEquals("unrelated workspace data\n", workspace.resolve("keep.txt").readText())
        } finally {
            files.deleteRecursively()
            workspace.deleteRecursively()
        }
    }

    @Test
    fun `python snapshot fails and clears its outputs after repeated rollovers`() {
        val files = Files.createTempDirectory("diagnostics-python-source").toFile()
        val workspace = Files.createTempDirectory("diagnostics-python-workspace").toFile()
        try {
            files.resolve(BACKUP).writeText("backup 0\n")
            files.resolve(CURRENT).writeText("current 0\n")
            workspace.resolve("keep.txt").writeText("keep\n")
            val snapshotter =
                PythonLogSnapshotter(files, maxAttempts = 2) { name, attempt ->
                    if (name == BACKUP) {
                        files.resolve(CURRENT).copyTo(files.resolve(BACKUP), overwrite = true)
                        files.resolve(CURRENT).writeText("current $attempt\n")
                    }
                }

            try {
                snapshotter.snapshot(workspace)
                fail("expected repeated rollover failure")
            } catch (error: IOException) {
                assertTrue(error.message.orEmpty().contains("changed during capture"))
            }

            assertFalse(workspace.resolve("python").exists())
            assertEquals("keep\n", workspace.resolve("keep.txt").readText())
        } finally {
            files.deleteRecursively()
            workspace.deleteRecursively()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `capture gate serializes the complete operation`() =
        runTest {
            val gate = DiagnosticsCaptureGate()
            val firstEntered = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val events = mutableListOf<String>()

            val first =
                async {
                    gate.run {
                        events += "first-start"
                        firstEntered.complete(Unit)
                        releaseFirst.await()
                        events += "first-end"
                    }
                }
            firstEntered.await()
            val second = async { gate.run { events += "second" } }
            runCurrent()

            assertEquals(listOf("first-start"), events)
            releaseFirst.complete(Unit)
            first.await()
            second.await()
            assertEquals(listOf("first-start", "first-end", "second"), events)
        }

    private companion object {
        const val BACKUP = "anki_miner.log.1"
        const val CURRENT = "anki_miner.log"
    }
}
