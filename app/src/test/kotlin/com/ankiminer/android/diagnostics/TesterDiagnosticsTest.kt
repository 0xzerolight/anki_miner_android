package com.ankiminer.android.diagnostics

import com.ankiminer.android.anki.provider.AnkiProviderReadiness
import com.ankiminer.android.data.anki.AnkiSetupFailure
import com.ankiminer.android.data.resources.ResourceFailure
import com.ankiminer.android.data.resources.ResourceOperationPhase
import com.ankiminer.android.data.resources.ResourceOperationProgress
import com.ankiminer.android.engine.PythonRuntimeReadiness
import com.ankiminer.android.media.SafDocument
import com.ankiminer.android.mining.MiningFailure
import com.ankiminer.android.mining.MiningProgress
import com.ankiminer.android.mining.MiningRunState
import com.ankiminer.android.ui.reading.ReadingDocumentSlotState
import com.ankiminer.android.ui.reading.ReadingMiningUiState
import com.ankiminer.android.vm.SetupUiState
import com.ankiminer.android.ui.video.DocumentSlotState
import com.ankiminer.android.ui.video.VideoMiningUiState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TesterDiagnosticsTest {
    @Test
    fun reportUsesOnlyBuildIdentityStableCategoriesAndCounts() {
        val privateVideoName = "private-video-title.mkv"
        val privateReadingName = "private-reading-title.epub"
        val diagnostics =
            TesterDiagnosticsBuilder.build(
                build =
                    TesterBuildIdentity(
                        applicationId = "com.ankiminer.android",
                        versionName = "0.1.0-alpha.1",
                        versionCode = 100_001,
                        sourceCommit = "0123456789abcdef0123456789abcdef01234567",
                        sdkInt = 35,
                        supportedAbis = listOf("arm64-v8a"),
                        pythonVersion = "3.11",
                        runtimeWheelBuildKey = "cp311-arm64-release",
                        tokenizerPublicationBuildKey = "sudachipy-arm64-publication",
                        deviceRuntimeAccepted = true,
                    ),
                setup =
                    SetupUiState(
                        python = PythonRuntimeReadiness.Ready("/private/python/home"),
                        anki = AnkiProviderReadiness.PermissionDenied,
                        operation =
                            ResourceOperationProgress(
                                operationId = "secret-operation-id",
                                label = "Import $privateReadingName",
                                phase = ResourceOperationPhase.DOWNLOADING,
                            ),
                        failure =
                            ResourceFailure(
                                code = "download_retry_exhausted",
                                message = "Could not read /private/$privateReadingName",
                                retryable = true,
                            ),
                        ankiFailure =
                            AnkiSetupFailure(
                                code = "provider_timeout",
                                message = "Provider failed for secret collection",
                            ),
                    ),
                video =
                    VideoMiningUiState(
                        video = DocumentSlotState(privateDocument(privateVideoName)),
                        runState =
                            MiningRunState.Failed(
                                runId = "secret-video-run-id",
                                failure = MiningFailure("Secret video failure detail", true),
                                result = null,
                            ),
                        startPending = true,
                    ),
                reading =
                    ReadingMiningUiState(
                        source = ReadingDocumentSlotState(privateDocument(privateReadingName)),
                        runState =
                            MiningRunState.Starting(
                                runId = "secret-reading-run-id",
                                progress =
                                    MiningProgress(
                                        current = 1,
                                        total = 2,
                                        description = "Reading $privateReadingName",
                                    ),
                            ),
                        cancelPending = true,
                    ),
            )

        assertTrue(diagnostics.report.contains("app.version_name=0.1.0-alpha.1"))
        assertFalse(diagnostics.report.contains("release.channel"))
        assertTrue(
            diagnostics.report.contains(
                "runtime.tokenizer_publication=sudachipy-arm64-publication",
            ),
        )
        assertTrue(
            diagnostics.report.contains(
                "source.commit=0123456789abcdef0123456789abcdef01234567",
            ),
        )
        assertTrue(diagnostics.report.contains("python.readiness=ready"))
        assertTrue(diagnostics.report.contains("resources.operation=downloading"))
        assertTrue(diagnostics.report.contains("resources.failure=download_retry_exhausted"))
        assertTrue(diagnostics.report.contains("anki.failure=provider_timeout"))
        assertTrue(diagnostics.report.contains("video.run=failed"))
        assertTrue(diagnostics.report.contains("video.pending=start"))
        assertTrue(diagnostics.report.contains("reading.run=starting"))
        assertTrue(diagnostics.report.contains("reading.pending=cancel"))
        assertTrue(diagnostics.report.length <= 4_096)

        listOf(
            "/private/python/home",
            privateVideoName,
            privateReadingName,
            "secret-operation-id",
            "secret-video-run-id",
            "secret-reading-run-id",
            "Secret video failure detail",
            "secret collection",
            "content://private/",
        ).forEach { privateValue ->
            assertFalse("Leaked private value: $privateValue", diagnostics.report.contains(privateValue))
        }
    }

    private fun privateDocument(displayName: String): SafDocument =
        SafDocument(
            uri = "content://private/$displayName",
            displayName = displayName,
            mimeType = null,
            sizeBytes = 42,
        )
}
