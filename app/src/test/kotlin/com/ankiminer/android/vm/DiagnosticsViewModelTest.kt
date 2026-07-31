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

                viewModel.export(DiagnosticsDelivery.SAVE)
                entered.await()
                viewModel.export(DiagnosticsDelivery.SHARE)
                runCurrent()

                assertEquals(1, exporter.buildCalls)
                release.complete(Unit)
                advanceUntilIdle()
                assertEquals(
                    DiagnosticsDelivery.SAVE,
                    (viewModel.state.value as DiagnosticsExportState.Ready).delivery,
                )
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

                        viewModel.export(DiagnosticsDelivery.SAVE)
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
    fun `copy failure retains bundle and share reuses it without capture`() =
        runTest(mainDispatcherRule.dispatcher) {
            val root = Files.createTempDirectory("diagnostics-view-model").toFile()
            val ioDispatcher =
                Executors.newSingleThreadExecutor { runnable ->
                    Thread(runnable, IO_THREAD_NAME)
                }.asCoroutineDispatcher()
            try {
                val exporter = FakeDiagnosticsExporter(staged(root))
                val viewModel = DiagnosticsViewModel(exporter, ioDispatcher)
                viewModel.export(DiagnosticsDelivery.SAVE)
                advanceUntilIdle()
                exporter.copyFailure = DiagnosticsExportException(DiagnosticsExportFailure.COPY)

                viewModel.copyToDocument("content://documents/export.zip")
                advanceUntilIdle()
                assertEquals(
                    R.string.diagnostics_bundle_copy_failed,
                    (viewModel.state.value as DiagnosticsExportState.Failed).message.resourceId,
                )

                viewModel.export(DiagnosticsDelivery.SHARE)
                advanceUntilIdle()
                assertEquals(1, exporter.buildCalls)
                assertEquals(
                    DiagnosticsDelivery.SHARE,
                    (viewModel.state.value as DiagnosticsExportState.Ready).delivery,
                )

                viewModel.deliverShare { uri, name ->
                    assertEquals(SHARE_URI, uri)
                    assertEquals("bundle.zip", name)
                    true
                }
                exporter.shareCalled.await()
                assertEquals(
                    DiagnosticsExportState.Saved,
                    viewModel.state.first { it == DiagnosticsExportState.Saved },
                )
                assertEquals(0, exporter.discardCalls)
                assertTrue(exporter.shareThreadName?.startsWith(IO_THREAD_NAME) == true)
            } finally {
                ioDispatcher.close()
                root.deleteRecursively()
            }
        }

    @Test
    fun `successful document copy discards staged file and reports saved`() =
        runTest(mainDispatcherRule.dispatcher) {
            val root = Files.createTempDirectory("diagnostics-view-model").toFile()
            val ioDispatcher =
                Executors.newSingleThreadExecutor { runnable ->
                    Thread(runnable, IO_THREAD_NAME)
                }.asCoroutineDispatcher()
            try {
                val exporter = FakeDiagnosticsExporter(staged(root))
                val viewModel = DiagnosticsViewModel(exporter, ioDispatcher)
                viewModel.export(DiagnosticsDelivery.SAVE)
                advanceUntilIdle()

                viewModel.copyToDocument("content://documents/export.zip")
                exporter.discardCalled.await()

                assertEquals(
                    DiagnosticsExportState.Saved,
                    viewModel.state.first { it == DiagnosticsExportState.Saved },
                )
                assertEquals(1, exporter.copyCalls)
                assertEquals(1, exporter.discardCalls)
                assertTrue(exporter.discardThreadName?.startsWith(IO_THREAD_NAME) == true)
            } finally {
                ioDispatcher.close()
                root.deleteRecursively()
            }
        }

    @Test
    fun `cancelled picker keeps built bundle for a later delivery choice`() =
        runTest(mainDispatcherRule.dispatcher) {
            val root = Files.createTempDirectory("diagnostics-view-model").toFile()
            try {
                val exporter = FakeDiagnosticsExporter(staged(root))
                val viewModel = DiagnosticsViewModel(exporter)
                viewModel.export(DiagnosticsDelivery.SAVE)
                advanceUntilIdle()

                viewModel.deliveryCancelled()
                assertEquals(DiagnosticsExportState.Idle, viewModel.state.value)
                viewModel.export(DiagnosticsDelivery.SHARE)
                advanceUntilIdle()

                assertEquals(1, exporter.buildCalls)
                assertEquals(
                    DiagnosticsDelivery.SHARE,
                    (viewModel.state.value as DiagnosticsExportState.Ready).delivery,
                )
            } finally {
                root.deleteRecursively()
            }
        }

    private class FakeDiagnosticsExporter(
        val bundle: StagedBundle,
    ) : DiagnosticsExporter {
        var buildCalls = 0
        var copyCalls = 0
        var discardCalls = 0
        var discardThreadName: String? = null
        var shareThreadName: String? = null
        val discardCalled = CompletableDeferred<Unit>()
        val shareCalled = CompletableDeferred<Unit>()
        var copyFailure: DiagnosticsExportException? = null
        var buildBlock: suspend () -> StagedBundle = {
            buildCalls += 1
            bundle
        }

        override suspend fun buildBundle(onStep: (DiagnosticsExportStep) -> Unit): StagedBundle =
            buildBlock()

        override suspend fun copyToDocument(
            bundle: StagedBundle,
            uri: String,
        ) {
            copyCalls += 1
            copyFailure?.let { throw it }
        }

        override fun shareUriFor(bundle: StagedBundle): String {
            shareThreadName = Thread.currentThread().name
            shareCalled.complete(Unit)
            return bundle.uri
        }

        override fun discard(bundle: StagedBundle) {
            discardThreadName = Thread.currentThread().name
            discardCalls += 1
            discardCalled.complete(Unit)
        }
    }

    private fun staged(root: File): StagedBundle {
        val file = root.resolve("bundle.zip").apply { writeText("bundle") }
        return StagedBundle(file, SHARE_URI, file.length(), emptyList())
    }

    private companion object {
        const val IO_THREAD_NAME = "diagnostics-view-model-io-test"
        const val SHARE_URI = "content://com.ankiminer.android.diagnostics/bundle.zip"
    }
}
