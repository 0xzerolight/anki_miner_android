package com.ankiminer.android.vm

import com.ankiminer.android.MainDispatcherRule
import com.ankiminer.android.R
import com.ankiminer.android.diagnostics.DiagnosticsExportException
import com.ankiminer.android.diagnostics.DiagnosticsExportFailure
import com.ankiminer.android.diagnostics.DiagnosticsExportStep
import com.ankiminer.android.diagnostics.DiagnosticsExporter
import com.ankiminer.android.diagnostics.StagedBundle
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `double tap starts only one bundle capture`() =
        runTest(mainDispatcherRule.dispatcher) {
            val root = Files.createTempDirectory("diagnostics-view-model").toFile()
            try {
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val exporter = FakeDiagnosticsExporter(staged(root)).apply {
                    buildBlock = {
                        buildCalls += 1
                        entered.complete(Unit)
                        release.await()
                        bundle
                    }
                }
                val viewModel = DiagnosticsViewModel(exporter)

                viewModel.export()
                entered.await()
                viewModel.export()
                runCurrent()

                assertEquals(1, exporter.buildCalls)
                release.complete(Unit)
                advanceUntilIdle()
                assertTrue(viewModel.state.value is DiagnosticsExportState.Ready)
            } finally {
                root.deleteRecursively()
            }
        }

    @Test
    fun `each export failure maps to a distinct inline message`() =
        runTest(mainDispatcherRule.dispatcher) {
            val root = Files.createTempDirectory("diagnostics-view-model").toFile()
            try {
                val messages =
                    DiagnosticsExportFailure.entries.map { kind ->
                        val exporter = FakeDiagnosticsExporter(staged(root)).apply {
                            buildBlock = { throw DiagnosticsExportException(kind) }
                        }
                        val viewModel = DiagnosticsViewModel(exporter)

                        viewModel.export()
                        advanceUntilIdle()

                        (viewModel.state.value as DiagnosticsExportState.Failed).message.resourceId
                    }

                assertEquals(DiagnosticsExportFailure.entries.size, messages.toSet().size)
                assertTrue(R.string.diagnostics_action_unavailable in messages)
            } finally {
                root.deleteRecursively()
            }
        }

    @Test
    fun `share launches staged bundle off the main thread`() =
        runTest(mainDispatcherRule.dispatcher) {
            val root = Files.createTempDirectory("diagnostics-view-model").toFile()
            val ioDispatcher =
                Executors.newSingleThreadExecutor { runnable ->
                    Thread(runnable, IO_THREAD_NAME)
                }.asCoroutineDispatcher()
            try {
                val exporter = FakeDiagnosticsExporter(staged(root))
                val viewModel = DiagnosticsViewModel(exporter, ioDispatcher)
                viewModel.export()
                advanceUntilIdle()

                viewModel.deliverShare { uri, name ->
                    assertEquals(SHARE_URI, uri)
                    assertEquals("bundle.zip", name)
                    true
                }
                exporter.shareCalled.await()
                assertEquals(
                    DiagnosticsExportState.Idle,
                    viewModel.state.first { it == DiagnosticsExportState.Idle },
                )
                assertEquals(1, exporter.buildCalls)
                assertTrue(exporter.shareThreadName?.startsWith(IO_THREAD_NAME) == true)
            } finally {
                ioDispatcher.close()
                root.deleteRecursively()
            }
        }

    @Test
    fun `a staged file reclaimed between exports is rebuilt, not republished`() =
        runTest(mainDispatcherRule.dispatcher) {
            val root = Files.createTempDirectory("diagnostics-view-model").toFile()
            try {
                val exporter = FakeDiagnosticsExporter(staged(root))
                val viewModel = DiagnosticsViewModel(exporter, mainDispatcherRule.dispatcher)
                viewModel.export()
                advanceUntilIdle()
                assertEquals(1, exporter.buildCalls)

                // cacheDir eviction, or DiagnosticsBundleJanitor's 24 h / 8 file policy.
                assertTrue(exporter.bundle.file.delete())
                viewModel.export()
                advanceUntilIdle()

                assertEquals(2, exporter.buildCalls)
                assertTrue(viewModel.state.value is DiagnosticsExportState.Ready)
                assertTrue(exporter.bundle.file.isFile)
            } finally {
                root.deleteRecursively()
            }
        }

    @Test
    fun `a bundle-kind share failure drops the handle instead of offering it again`() =
        runTest(mainDispatcherRule.dispatcher) {
            val root = Files.createTempDirectory("diagnostics-view-model").toFile()
            try {
                val exporter = FakeDiagnosticsExporter(staged(root))
                val viewModel = DiagnosticsViewModel(exporter, mainDispatcherRule.dispatcher)
                viewModel.export()
                advanceUntilIdle()
                exporter.shareFailure = DiagnosticsExportException(DiagnosticsExportFailure.BUNDLE)

                viewModel.deliverShare { _, _ -> true }
                advanceUntilIdle()
                viewModel.retry()
                advanceUntilIdle()

                assertEquals(2, exporter.buildCalls)
            } finally {
                root.deleteRecursively()
            }
        }

    private class FakeDiagnosticsExporter(
        val bundle: StagedBundle,
    ) : DiagnosticsExporter {
        var buildCalls = 0
        var shareThreadName: String? = null
        val shareCalled = CompletableDeferred<Unit>()
        var shareFailure: DiagnosticsExportException? = null
        var buildBlock: suspend () -> StagedBundle = {
            buildCalls += 1
            // A build stages the file, so a rebuild after a reclaim puts it back.
            bundle.file.writeText(STAGED_CONTENT)
            bundle
        }

        override suspend fun buildBundle(onStep: (DiagnosticsExportStep) -> Unit): StagedBundle =
            buildBlock()

        override fun shareUriFor(bundle: StagedBundle): String {
            shareThreadName = Thread.currentThread().name
            shareCalled.complete(Unit)
            shareFailure?.let { throw it }
            return bundle.uri
        }

        /** What AndroidDiagnosticsExporter checks of a cached handle, minus the staging root. */
        override fun isStaged(bundle: StagedBundle): Boolean =
            bundle.file.isFile && bundle.file.length() == bundle.sizeBytes
    }

    private fun staged(root: File): StagedBundle {
        val file = root.resolve("bundle.zip").apply { writeText(STAGED_CONTENT) }
        return StagedBundle(file, SHARE_URI, file.length(), emptyList())
    }

    private companion object {
        const val IO_THREAD_NAME = "diagnostics-view-model-io-test"
        const val SHARE_URI = "content://com.ankiminer.android.diagnostics/bundle.zip"
        const val STAGED_CONTENT = "bundle"
    }
}
