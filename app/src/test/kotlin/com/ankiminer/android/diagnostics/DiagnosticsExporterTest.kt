package com.ankiminer.android.diagnostics

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsExporterTest {
    @Test
    fun `build switches to IO and reports both build steps`() =
        runTest {
            val root = Files.createTempDirectory("diagnostics-export").toFile()
            val dispatcher =
                Executors.newSingleThreadExecutor { task ->
                    Thread(task, "diagnostics-export-test")
                }.asCoroutineDispatcher()
            try {
                val file = root.resolve("bundle.zip").apply { writeText("bundle") }
                val expected = staged(file)
                var calls = 0
                var threadName: String? = null
                val exporter =
                    AndroidDiagnosticsExporter(
                        stagingRoot = root,
                        stageBundle = {
                            calls += 1
                            threadName = Thread.currentThread().name
                            expected
                        },
                        ioDispatcher = dispatcher,
                    )
                val steps = mutableListOf<DiagnosticsExportStep>()

                val actual = exporter.buildBundle(steps::add)

                assertEquals(expected, actual)
                assertEquals(1, calls)
                assertEquals("diagnostics-export-test", threadName)
                assertEquals(
                    listOf(
                        DiagnosticsExportStep.PREPARING,
                        DiagnosticsExportStep.BUILDING,
                    ),
                    steps,
                )
            } finally {
                dispatcher.close()
                root.deleteRecursively()
            }
        }

    @Test
    fun `build failure has its own failure kind`() =
        runTest {
            val root = Files.createTempDirectory("diagnostics-export").toFile()
            try {
                val exporter =
                    AndroidDiagnosticsExporter(
                        stagingRoot = root,
                        stageBundle = { throw IOException("capture failed") },
                        ioDispatcher = StandardTestDispatcher(testScheduler),
                    )

                val failure = expectFailure { exporter.buildBundle {} }

                assertEquals(DiagnosticsExportFailure.BUILD, failure.kind)
                assertTrue(failure.cause is IOException)
            } finally {
                root.deleteRecursively()
            }
        }

    @Test
    fun `storage exhaustion has its own failure kind`() =
        runTest {
            val root = Files.createTempDirectory("diagnostics-export").toFile()
            try {
                listOf(
                    IOException("ENOSPC: No space left on device"),
                    IOException(
                        "diagnostics bundle publication failed",
                        IOException("EDQUOT: Disk quota exceeded"),
                    ),
                ).forEach { cause ->
                    val exporter =
                        AndroidDiagnosticsExporter(
                            stagingRoot = root,
                            stageBundle = { throw cause },
                            ioDispatcher = StandardTestDispatcher(testScheduler),
                        )

                    val failure = expectFailure { exporter.buildBundle {} }

                    assertEquals(DiagnosticsExportFailure.STORAGE, failure.kind)
                    assertEquals(cause, failure.cause)
                }
            } finally {
                root.deleteRecursively()
            }
        }

    @Test
    fun `share rejects a staged file outside its canonical root`() =
        runTest {
            val parent = Files.createTempDirectory("diagnostics-export").toFile()
            try {
                val root = parent.resolve("owned").apply { mkdirs() }
                val file = parent.resolve("foreign.zip").apply { writeText("bundle") }
                val exporter = exporter(root)

                val failure = expectFailure { exporter.shareUriFor(staged(file)) }

                assertEquals(DiagnosticsExportFailure.BUNDLE, failure.kind)
                assertTrue(file.exists())
            } finally {
                parent.deleteRecursively()
            }
        }

    @Test
    fun `share rejects a symlink and a changed staged size`() =
        runTest {
            val root = Files.createTempDirectory("diagnostics-export").toFile()
            try {
                val target = root.resolve("target.zip").apply { writeText("bundle") }
                val link = root.resolve("link.zip")
                Files.createSymbolicLink(link.toPath(), target.toPath())
                val exporter = exporter(root)

                val linkFailure = expectFailure { exporter.shareUriFor(staged(link)) }
                val bundle = staged(target)
                target.appendText("tampered")
                val sizeFailure = expectFailure { exporter.shareUriFor(bundle) }

                assertEquals(DiagnosticsExportFailure.BUNDLE, linkFailure.kind)
                assertEquals(DiagnosticsExportFailure.BUNDLE, sizeFailure.kind)
            } finally {
                root.deleteRecursively()
            }
        }

    @Test
    fun `share returns the staged URI`() =
        runTest {
            val parent = Files.createTempDirectory("diagnostics-export").toFile()
            try {
                val root = parent.resolve("owned").apply { mkdirs() }
                val owned = root.resolve("bundle.zip").apply { writeText("owned") }
                val exporter = exporter(root)

                assertEquals(SHARE_URI, exporter.shareUriFor(staged(owned)))
                assertTrue(owned.exists())
            } finally {
                parent.deleteRecursively()
            }
        }

    private fun exporter(root: File): AndroidDiagnosticsExporter =
        AndroidDiagnosticsExporter(
            stagingRoot = root,
            stageBundle = { error("not used") },
        )

    private fun staged(file: File): StagedBundle =
        StagedBundle(
            file = file,
            uri = SHARE_URI,
            sizeBytes = file.length(),
            entries = emptyList(),
        )

    private suspend fun expectFailure(block: suspend () -> Unit): DiagnosticsExportException =
        try {
            block()
            fail("expected diagnostics export failure")
            error("unreachable")
        } catch (failure: DiagnosticsExportException) {
            failure
        }

    private companion object {
        const val SHARE_URI = "content://com.ankiminer.android.diagnostics/bundle.zip"
    }
}
