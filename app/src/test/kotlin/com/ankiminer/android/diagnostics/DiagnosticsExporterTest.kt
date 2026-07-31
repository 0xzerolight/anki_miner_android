package com.ankiminer.android.diagnostics

import com.ankiminer.android.data.resources.ResourceDocumentWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
                        documentWriter = ResourceDocumentWriter { ByteArrayOutputStream() },
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
                        documentWriter = ResourceDocumentWriter { ByteArrayOutputStream() },
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
    fun `copy writes an intact staged bundle to a content document`() =
        runTest {
            val root = Files.createTempDirectory("diagnostics-export").toFile()
            try {
                val bytes = "zip payload".toByteArray()
                val file = root.resolve("bundle.zip").apply { writeBytes(bytes) }
                val output = ByteArrayOutputStream()
                var openedUri: String? = null
                val exporter =
                    exporter(
                        root,
                        ResourceDocumentWriter { uri ->
                            openedUri = uri
                            output
                        },
                    )

                exporter.copyToDocument(staged(file), CONTENT_URI)

                assertEquals(CONTENT_URI, openedUri)
                assertArrayEquals(bytes, output.toByteArray())
                assertTrue(file.exists())
            } finally {
                root.deleteRecursively()
            }
        }

    @Test
    fun `copy rejects a non-content destination before opening it`() =
        runTest {
            val root = Files.createTempDirectory("diagnostics-export").toFile()
            try {
                val file = root.resolve("bundle.zip").apply { writeText("bundle") }
                var opened = false
                val exporter =
                    exporter(
                        root,
                        ResourceDocumentWriter {
                            opened = true
                            ByteArrayOutputStream()
                        },
                    )

                val failure =
                    expectFailure {
                        exporter.copyToDocument(staged(file), "file:///tmp/export.zip")
                    }

                assertEquals(DiagnosticsExportFailure.DESTINATION, failure.kind)
                assertFalse(opened)
                assertTrue(file.exists())
            } finally {
                root.deleteRecursively()
            }
        }

    @Test
    fun `copy rejects a staged file outside its canonical root`() =
        runTest {
            val parent = Files.createTempDirectory("diagnostics-export").toFile()
            try {
                val root = parent.resolve("owned").apply { mkdirs() }
                val file = parent.resolve("foreign.zip").apply { writeText("bundle") }
                val exporter = exporter(root, ResourceDocumentWriter { ByteArrayOutputStream() })

                val failure = expectFailure { exporter.copyToDocument(staged(file), CONTENT_URI) }

                assertEquals(DiagnosticsExportFailure.BUNDLE, failure.kind)
                assertTrue(file.exists())
            } finally {
                parent.deleteRecursively()
            }
        }

    @Test
    fun `copy rejects a symlink and a changed staged size`() =
        runTest {
            val root = Files.createTempDirectory("diagnostics-export").toFile()
            try {
                val target = root.resolve("target.zip").apply { writeText("bundle") }
                val link = root.resolve("link.zip")
                Files.createSymbolicLink(link.toPath(), target.toPath())
                val exporter = exporter(root, ResourceDocumentWriter { ByteArrayOutputStream() })

                val linkFailure = expectFailure { exporter.copyToDocument(staged(link), CONTENT_URI) }
                val bundle = staged(target)
                target.appendText("tampered")
                val sizeFailure = expectFailure { exporter.copyToDocument(bundle, CONTENT_URI) }

                assertEquals(DiagnosticsExportFailure.BUNDLE, linkFailure.kind)
                assertEquals(DiagnosticsExportFailure.BUNDLE, sizeFailure.kind)
            } finally {
                root.deleteRecursively()
            }
        }

    @Test
    fun `document failures retain the staged bundle for retry`() =
        runTest {
            val root = Files.createTempDirectory("diagnostics-export").toFile()
            try {
                val file = root.resolve("bundle.zip").apply { writeText("bundle") }
                val unavailable = exporter(root, ResourceDocumentWriter { null })
                val unavailableFailure =
                    expectFailure { unavailable.copyToDocument(staged(file), CONTENT_URI) }
                val broken =
                    exporter(
                        root,
                        ResourceDocumentWriter { throw IOException("provider failed") },
                    )
                val copyFailure = expectFailure { broken.copyToDocument(staged(file), CONTENT_URI) }

                assertEquals(DiagnosticsExportFailure.DOCUMENT, unavailableFailure.kind)
                assertEquals(DiagnosticsExportFailure.COPY, copyFailure.kind)
                assertTrue(file.exists())
            } finally {
                root.deleteRecursively()
            }
        }

    @Test
    fun `share returns the staged URI and discard deletes only an owned bundle`() =
        runTest {
            val parent = Files.createTempDirectory("diagnostics-export").toFile()
            try {
                val root = parent.resolve("owned").apply { mkdirs() }
                val owned = root.resolve("bundle.zip").apply { writeText("owned") }
                val foreign = parent.resolve("foreign.zip").apply { writeText("foreign") }
                val exporter = exporter(root, ResourceDocumentWriter { ByteArrayOutputStream() })

                assertEquals(SHARE_URI, exporter.shareUriFor(staged(owned)))
                exporter.discard(staged(foreign))
                exporter.discard(staged(owned))

                assertFalse(owned.exists())
                assertTrue(foreign.exists())
            } finally {
                parent.deleteRecursively()
            }
        }

    private fun exporter(
        root: File,
        writer: ResourceDocumentWriter,
    ): AndroidDiagnosticsExporter =
        AndroidDiagnosticsExporter(
            stagingRoot = root,
            documentWriter = writer,
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
        const val CONTENT_URI = "content://documents/export.zip"
        const val SHARE_URI = "content://com.ankiminer.android.diagnostics/bundle.zip"
    }
}
