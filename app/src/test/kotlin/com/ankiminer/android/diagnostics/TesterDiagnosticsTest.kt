package com.ankiminer.android.diagnostics

import com.ankiminer.android.anki.provider.AnkiProviderReadiness
import com.ankiminer.android.anki.provider.AnkiRecoveryReadiness
import com.ankiminer.android.data.anki.AnkiRecoveryInventoryStatus
import com.ankiminer.android.data.anki.AnkiSetupFailure
import com.ankiminer.android.data.resources.ResourceFailure
import com.ankiminer.android.data.resources.ResourceOperationPhase
import com.ankiminer.android.data.resources.ResourceOperationProgress
import com.ankiminer.android.engine.PythonBootstrapStage
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
import org.junit.Assert.assertEquals
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
                    ),
                setup =
                    SetupUiState(
                        python = PythonRuntimeReadiness.Ready("/private/python/home"),
                        anki = AnkiProviderReadiness.PermissionDenied,
                        ankiRecovery = AnkiRecoveryReadiness.Blocked,
                        recoveryInventoryStatus = AnkiRecoveryInventoryStatus.AVAILABLE,
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
                                faultId = "fbeef1234",
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
                                runId = "run_0123456789abcdef0123456789abcdef",
                                failure =
                                    MiningFailure(
                                        "Secret video failure detail",
                                        true,
                                        "f0123abcd",
                                        "foreground_start_failed",
                                    ),
                                result = null,
                            ),
                        startPending = true,
                    ),
                audio =
                    VideoMiningUiState(
                        runState =
                            MiningRunState.Running(
                                runId = "run_00000000000000000000000000000003",
                                progress =
                                    MiningProgress(
                                        current = 1,
                                        total = 3,
                                        description = "Mining audio",
                                    ),
                            ),
                        curationPending = true,
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
        assertTrue(diagnostics.report.contains("anki.provider=permission_denied"))
        assertTrue(diagnostics.report.contains("anki.recovery_startup=blocked"))
        assertTrue(diagnostics.report.contains("anki.recovery_inventory=available"))
        assertTrue(diagnostics.report.contains("anki.failure=provider_timeout"))
        assertTrue(diagnostics.report.contains("video.run=failed"))
        assertTrue(diagnostics.report.contains("video.pending=start"))
        assertTrue(diagnostics.report.contains("audio.run=running"))
        assertTrue(diagnostics.report.contains("audio.pending=curation"))
        assertTrue(diagnostics.report.contains("reading.run=starting"))
        assertTrue(diagnostics.report.contains("reading.pending=cancel"))
        // The fault id is opaque by construction, so reporting it leaks nothing while giving a
        // pasted report the key that finds the traceback.
        assertTrue(diagnostics.report.contains("mining.fault_id=f0123abcd"))
        assertTrue(diagnostics.report.contains("resources.fault_id=fbeef1234"))
        assertTrue(diagnostics.report.contains("mining.run_id=run_0123456789abcdef0123456789abcdef"))
        // The code is reported while the message it accompanies is not: the message is localized
        // free text, the code is neither.
        assertTrue(diagnostics.report.contains("mining.failure_code=foreground_start_failed"))
        assertTrue(diagnostics.report.length <= 4_096)

        listOf(
            "/private/python/home",
            privateVideoName,
            privateReadingName,
            "secret-operation-id",
            "secret-reading-run-id",
            "Secret video failure detail",
            "secret collection",
            "content://private/",
        ).forEach { privateValue ->
            assertFalse("Leaked private value: $privateValue", diagnostics.report.contains(privateValue))
        }
    }

    @Test
    fun `the last anki fault is reported as a bounded token and defaults to none`() {
        val withoutFault =
            TesterDiagnosticsBuilder.build(
                build = plainIdentity(),
                setup = SetupUiState(),
                video = VideoMiningUiState(),
                audio = VideoMiningUiState(),
                reading = ReadingMiningUiState(),
            )

        assertTrue(withoutFault.report.contains("anki.last_fault=none"))
        assertTrue(withoutFault.report.contains("mining.run_id=none"))
        assertTrue(withoutFault.report.contains("mining.failure_code=none"))

        val withFault =
            TesterDiagnosticsBuilder.build(
                build = plainIdentity(),
                setup = SetupUiState(),
                video = VideoMiningUiState(),
                audio = VideoMiningUiState(),
                reading = ReadingMiningUiState(),
                lastAnkiFault =
                    "storeMedia:JournalInvariantViolation @ SqliteStore.reserveMedia:1609",
            )

        assertTrue(
            withFault.report.contains(
                "anki.last_fault=storeMedia:JournalInvariantViolation @ SqliteStore.reserveMedia:1609",
            ),
        )
    }

    @Test
    fun `mapped pitch fields are reported by logical key and never by the user's field name`() {
        val unmapped =
            TesterDiagnosticsBuilder.build(
                build = plainIdentity(),
                setup = SetupUiState(),
                video = VideoMiningUiState(),
                audio = VideoMiningUiState(),
                reading = ReadingMiningUiState(),
            )

        assertTrue(unmapped.report.contains("anki.pitch_fields_mapped=none"))

        val mapped =
            TesterDiagnosticsBuilder.build(
                build = plainIdentity(),
                setup =
                    SetupUiState(
                        fieldMap =
                            mapOf(
                                "word" to "Expression",
                                "pitch_position" to "PitchPosition",
                                "pitch_graph" to "MyPrivateGraphField",
                                "pitch_category" to "",
                            ),
                    ),
                video = VideoMiningUiState(),
                audio = VideoMiningUiState(),
                reading = ReadingMiningUiState(),
            )

        assertTrue(mapped.report.contains("anki.pitch_fields_mapped=pitch_position,pitch_graph"))
        // The destination names stay out of the bundle, exactly as LogRedactor keeps them out
        // of the log.
        assertFalse(mapped.report.contains("MyPrivateGraphField"))
        assertFalse(mapped.report.contains("PitchPosition"))
    }

    @Test
    fun `a run id is reported only once its run has failed`() {
        val report =
            TesterDiagnosticsBuilder.build(
                build = plainIdentity(),
                setup = SetupUiState(),
                video =
                    VideoMiningUiState(
                        runState =
                            MiningRunState.Running(
                                runId = "run_00000000000000000000000000000001",
                                progress = MiningProgress(current = 1, total = 2, description = "Mining"),
                            ),
                    ),
                audio = VideoMiningUiState(),
                // A second in-flight state, and a different one: the main fixture's failed video
                // lane is scanned first, so no other test can reach a non-failed lane at all.
                reading =
                    ReadingMiningUiState(
                        runState =
                            MiningRunState.Starting(
                                runId = "run_00000000000000000000000000000002",
                                progress = null,
                            ),
                    ),
            ).report

        // A run still in flight has nothing to look up yet, and reporting its id would make the
        // line mean two different things depending on when Share was pressed.
        assertTrue(report, report.contains("video.run=running"))
        assertTrue(report, report.contains("reading.run=starting"))
        assertTrue(report, report.contains("mining.run_id=none"))
    }

    @Test
    fun `mining correlation fields come from one failed lane`() {
        val report =
            TesterDiagnosticsBuilder.build(
                build = plainIdentity(),
                setup = SetupUiState(),
                video =
                    VideoMiningUiState(
                        runState =
                            MiningRunState.Failed(
                                runId = "run_video",
                                failure =
                                    MiningFailure(
                                        "Video failed",
                                        false,
                                        null,
                                        "video_failed",
                                    ),
                                result = null,
                            ),
                    ),
                audio =
                    VideoMiningUiState(
                        runState =
                            MiningRunState.Failed(
                                runId = "run_audio",
                                failure =
                                    MiningFailure(
                                        "Audio failed",
                                        false,
                                        "f12345678",
                                        "audio_failed",
                                    ),
                                result = null,
                            ),
                    ),
                reading = ReadingMiningUiState(),
            ).report

        assertTrue(report, report.contains("mining.fault_id=none\n"))
        assertTrue(report, report.contains("mining.run_id=run_video\n"))
        assertTrue(report, report.contains("mining.failure_code=video_failed\n"))
        assertFalse(report, report.contains("mining.fault_id=f12345678\n"))
    }

    @Test
    fun `an over-long failure code is cut to a prefix rather than dropped`() {
        // The engine's terminal code pattern is length-unbounded, so this is reachable from Python.
        val longCode = "e".repeat(70)
        val report =
            TesterDiagnosticsBuilder.build(
                build = plainIdentity(),
                setup = SetupUiState(),
                video =
                    VideoMiningUiState(
                        runState =
                            MiningRunState.Failed(
                                runId = null,
                                failure = MiningFailure("Mining failed", false, null, longCode),
                                result = null,
                            ),
                    ),
                audio = VideoMiningUiState(),
                reading = ReadingMiningUiState(),
            ).report

        assertTrue(report, report.contains("mining.failure_code=${"e".repeat(64)}\n"))

        // A code outside the alphabet is still refused outright: truncating is for length only.
        val hostile =
            TesterDiagnosticsBuilder.build(
                build = plainIdentity(),
                setup = SetupUiState(),
                video =
                    VideoMiningUiState(
                        runState =
                            MiningRunState.Failed(
                                runId = null,
                                failure = MiningFailure("Mining failed", false, null, "Engine Error!"),
                                result = null,
                            ),
                    ),
                audio = VideoMiningUiState(),
                reading = ReadingMiningUiState(),
            ).report

        assertTrue(hostile, hostile.contains("mining.failure_code=none\n"))
    }

    @Test
    fun `a failed python runtime is reported with its stage and fault`() {
        val report =
            TesterDiagnosticsBuilder.build(
                build = plainIdentity(),
                setup =
                    SetupUiState(
                        python =
                            PythonRuntimeReadiness.Failed(
                                PythonBootstrapStage.HANDSHAKE,
                                "IllegalStateException @ ChaquopyPythonRuntime.initialize:88",
                            ),
                    ),
                video = VideoMiningUiState(),
                audio = VideoMiningUiState(),
                reading = ReadingMiningUiState(),
            ).report

        // Trailing newline: the value has to be the whole line, because separating a missing wheel
        // from a home mismatch is the entire point of carrying it.
        assertTrue(
            report,
            report.contains(
                "python.readiness=failed:handshake:IllegalStateException @ " +
                    "ChaquopyPythonRuntime.initialize:88\n",
            ),
        )
    }

    @Test
    fun `a python fault cannot break the report grammar`() {
        val report =
            TesterDiagnosticsBuilder.build(
                build = plainIdentity(),
                setup =
                    SetupUiState(
                        python =
                            PythonRuntimeReadiness.Failed(
                                PythonBootstrapStage.START,
                                "UnsatisfiedLinkError\nanki.failure=forged",
                            ),
                    ),
                video = VideoMiningUiState(),
                audio = VideoMiningUiState(),
                reading = ReadingMiningUiState(),
            ).report

        // Both the newline and the `=` are replaced, so a fault token can neither end the line early
        // nor forge a second key on it.
        assertTrue(report, report.contains("python.readiness=failed:start:UnsatisfiedLinkError_anki.failure_forged\n"))
    }

    private fun plainIdentity(): TesterBuildIdentity =
        TesterBuildIdentity(
            applicationId = "com.ankiminer.android",
            versionName = "0.1.8",
            versionCode = 100_008,
            sourceCommit = "0123456789abcdef0123456789abcdef01234567",
            sdkInt = 33,
            supportedAbis = listOf("arm64-v8a"),
            pythonVersion = "3.11",
            runtimeWheelBuildKey = "cp311-arm64-release",
            tokenizerPublicationBuildKey = "sudachipy-arm64-publication",
        )

    private fun privateDocument(displayName: String): SafDocument =
        SafDocument(
            uri = "content://private/$displayName",
            displayName = displayName,
            mimeType = null,
            sizeBytes = 42,
        )
}
