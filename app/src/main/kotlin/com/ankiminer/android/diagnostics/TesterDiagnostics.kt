package com.ankiminer.android.diagnostics

import android.os.Build
import com.ankiminer.android.BuildConfig
import com.ankiminer.android.anki.provider.AnkiProviderReadiness
import com.ankiminer.android.anki.provider.AnkiRecoveryReadiness
import com.ankiminer.android.anki.provider.NoteTypeSetupStatus
import com.ankiminer.android.engine.PythonRuntimeReadiness
import com.ankiminer.android.mining.MiningRunState
import com.ankiminer.android.ui.reading.ReadingMiningUiState
import com.ankiminer.android.vm.SetupUiState
import com.ankiminer.android.ui.video.VideoMiningUiState
import java.util.Locale

internal data class TesterBuildIdentity(
    val applicationId: String,
    val versionName: String,
    val versionCode: Long,
    val sourceCommit: String,
    val sdkInt: Int,
    val supportedAbis: List<String>,
    val pythonVersion: String,
    val runtimeWheelBuildKey: String,
    val tokenizerPublicationBuildKey: String,
    val deviceRuntimeAccepted: Boolean,
)

internal data class TesterDiagnostics(
    val versionLabel: String,
    val sourceLabel: String,
    val report: String,
)

internal data class TesterDiagnosticsIdentity(
    val versionLabel: String,
    val sourceLabel: String,
)

internal fun currentTesterBuildIdentity(): TesterBuildIdentity =
    TesterBuildIdentity(
        applicationId = BuildConfig.APPLICATION_ID,
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE.toLong(),
        sourceCommit = BuildConfig.SOURCE_COMMIT,
        sdkInt = Build.VERSION.SDK_INT,
        supportedAbis = Build.SUPPORTED_ABIS.toList(),
        pythonVersion = BuildConfig.PYTHON_VERSION,
        runtimeWheelBuildKey = BuildConfig.RUNTIME_WHEEL_BUILD_KEY,
        tokenizerPublicationBuildKey = BuildConfig.S1A_PUBLICATION_BUILD_KEY,
        deviceRuntimeAccepted = BuildConfig.S1A_ARM64_ACCEPTED,
    )

/** Builds a bounded report from build identity, stable error codes, categories, and counts only. */
internal object TesterDiagnosticsBuilder {
    fun identity(build: TesterBuildIdentity): TesterDiagnosticsIdentity =
        TesterDiagnosticsIdentity(
            versionLabel =
                "${safeBuildValue(build.versionName)} (${build.versionCode.coerceAtLeast(0L)})",
            sourceLabel = safeBuildValue(build.sourceCommit),
        )

    fun build(
        build: TesterBuildIdentity,
        setup: SetupUiState,
        video: VideoMiningUiState,
        audio: VideoMiningUiState,
        reading: ReadingMiningUiState,
        // Read from AnkiFaultRecorder by the caller, not here: the builder stays a pure function of
        // its arguments so no test observes a fault another test recorded.
        lastAnkiFault: String? = null,
    ): TesterDiagnostics {
        val identity = identity(build)
        val versionName = identity.versionLabel.substringBeforeLast(" (")
        val sourceCommit = identity.sourceLabel
        // Terminal states remain lane-local; one state must own every exported correlation field.
        val failedMiningRun =
            firstFailedMiningRun(video.runState, audio.runState, reading.runState)
        val report =
            buildString {
                appendLine("Anki Miner tester diagnostics v1")
                line("app.id", safeBuildValue(build.applicationId))
                line("app.version_name", versionName)
                line("app.version_code", build.versionCode.coerceAtLeast(0L).toString())
                line("source.commit", sourceCommit)
                line("android.sdk", build.sdkInt.coerceAtLeast(0).toString())
                line(
                    "android.abis",
                    build.supportedAbis
                        .take(MAX_ABIS)
                        .map(::safeBuildValue)
                        .joinToString(",")
                        .ifBlank { NONE },
                )
                line("runtime.python", safeBuildValue(build.pythonVersion))
                line("runtime.wheel", safeBuildValue(build.runtimeWheelBuildKey))
                line(
                    "runtime.tokenizer_publication",
                    safeBuildValue(build.tokenizerPublicationBuildKey),
                )
                line("runtime.device_accepted", build.deviceRuntimeAccepted.toString())
                line("python.readiness", pythonReadiness(setup.python))
                line("wizard.seen", setup.wizardSeen.toString())
                line("setup.mining_ready", setup.isMiningReady.toString())
                line("resources.startup", setup.resourceStartup.name.lowercase(Locale.ROOT))
                line("resources.unidic", if (setup.uniDicInstalled) "installed" else "missing")
                setup.catalogDictionaries.forEach { status ->
                    line(
                        "resources.dictionary.${status.resource.slotId}",
                        if (status.installed) "installed" else "missing",
                    )
                }
                line("resources.dictionaries_usable", setup.dictionaries.count { it.isUsable }.toString())
                line(
                    "resources.frequencies_usable",
                    setup.frequencySources.count { it.schemaOk && it.entryCount > 0 }.toString(),
                )
                line(
                    "resources.audio_packs_usable",
                    setup.audioPacks.count { it.contentAvailable && it.entryCount > 0 }.toString(),
                )
                line(
                    "resources.pitch_usable",
                    setup.pitchSources.count { it.schemaOk && it.entryCount > 0 }.toString(),
                )
                line("resources.operation", setup.operation?.phase?.name?.lowercase(Locale.ROOT) ?: NONE)
                line("resources.failure", safeCode(setup.failure?.code))
                // Both fault ids sit with anki.last_fault, above the lane lines: MAX_REPORT_CHARS
                // truncates the tail, and the keys that point into the log must survive it.
                line("resources.fault_id", safeCode(setup.failure?.faultId))
                line(
                    "mining.fault_id",
                    safeCode(failedMiningRun?.failure?.faultId),
                )
                line(
                    "mining.run_id",
                    safeCode(failedMiningRun?.runId),
                )
                line(
                    "mining.failure_code",
                    safeCode(failedMiningRun?.failure?.diagnostic),
                )
                line("anki.provider", ankiReadiness(setup.anki))
                line("anki.recovery_startup", ankiRecoveryReadiness(setup.ankiRecovery))
                line(
                    "anki.recovery_inventory",
                    setup.recoveryInventoryStatus.name.lowercase(Locale.ROOT),
                )
                line("anki.model", modelReadiness(setup.noteTypeStatus))
                line("anki.remediations", setup.remediations.pending.size.toString())
                line("anki.operation", setup.ankiOperation?.name?.lowercase(Locale.ROOT) ?: NONE)
                line("anki.failure", safeCode(setup.ankiFailure?.code))
                line("anki.last_fault", safeFaultToken(lastAnkiFault))
                line("anki.recovery_failure", safeCode(setup.ankiRecoveryFailure?.code))
                line("permissions.notifications", setup.notifications.name.lowercase(Locale.ROOT))
                line("video.run", miningRun(video.runState))
                line("video.pending", videoPending(video))
                line("video.command_failure", video.commandError?.name?.lowercase(Locale.ROOT) ?: NONE)
                line("audio.run", miningRun(audio.runState))
                line("audio.pending", videoPending(audio))
                line("audio.command_failure", audio.commandError?.name?.lowercase(Locale.ROOT) ?: NONE)
                line("reading.run", miningRun(reading.runState))
                line("reading.pending", readingPending(reading))
                line("reading.command_failure", reading.commandError?.name?.lowercase(Locale.ROOT) ?: NONE)
            }.take(MAX_REPORT_CHARS)

        return TesterDiagnostics(
            versionLabel = identity.versionLabel,
            sourceLabel = identity.sourceLabel,
            report = report,
        )
    }

    private fun StringBuilder.line(key: String, value: String) {
        append(key)
        append('=')
        appendLine(value)
    }

    /**
     * The failed arm is the one state in this report a tester reaches with no way to describe it:
     * the UI shows `status_failed_restart` whatever went wrong. Naming the stage and the fault
     * separates a missing ABI wheel from a home mismatch from an OOM.
     *
     * [safeFaultToken] is reapplied even though the producer already bounds and whitelists the
     * token, for the reason it is reapplied on `anki.last_fault`: this line's `key=value` grammar
     * survives neither a newline nor a second `=`, and the sanitizer is what makes that true here
     * regardless of what any future producer puts in the field.
     */
    private fun pythonReadiness(readiness: PythonRuntimeReadiness): String =
        when (readiness) {
            PythonRuntimeReadiness.Pending -> "pending"
            PythonRuntimeReadiness.Starting -> "starting"
            is PythonRuntimeReadiness.Ready -> "ready"
            is PythonRuntimeReadiness.Failed ->
                "failed:${readiness.stage.name.lowercase(Locale.ROOT)}:${safeFaultToken(readiness.fault)}"
        }

    private fun ankiReadiness(readiness: AnkiProviderReadiness): String =
        when (readiness) {
            AnkiProviderReadiness.NotChecked -> "not_checked"
            AnkiProviderReadiness.NotInstalled -> "not_installed"
            AnkiProviderReadiness.Uninitialized -> "uninitialized"
            is AnkiProviderReadiness.Incompatible -> "incompatible"
            AnkiProviderReadiness.PermissionDenied -> "permission_denied"
            is AnkiProviderReadiness.Ready -> "ready"
        }

    private fun ankiRecoveryReadiness(readiness: AnkiRecoveryReadiness): String =
        when (readiness) {
            AnkiRecoveryReadiness.NotChecked -> "not_checked"
            AnkiRecoveryReadiness.Ready -> "ready"
            AnkiRecoveryReadiness.Blocked -> "blocked"
        }

    private fun modelReadiness(status: NoteTypeSetupStatus): String =
        when (status) {
            is NoteTypeSetupStatus.Verified -> "verified"
            NoteTypeSetupStatus.NoteTypeMissing -> "note_type_missing"
            is NoteTypeSetupStatus.FieldsMissing -> "fields_missing"
            is NoteTypeSetupStatus.FieldMapInvalid -> "field_map_invalid"
            NoteTypeSetupStatus.FirstFieldMismatch -> "first_field_mismatch"
            is NoteTypeSetupStatus.ProviderError -> "provider_error"
            NoteTypeSetupStatus.NotSelected -> "not_selected"
        }

    private fun miningRun(state: MiningRunState): String =
        when (state) {
            MiningRunState.Idle -> "idle"
            is MiningRunState.Starting -> "starting"
            is MiningRunState.Curating -> "curating"
            is MiningRunState.Running -> "running"
            is MiningRunState.Success -> "success"
            is MiningRunState.Cancelled -> "cancelled"
            is MiningRunState.Failed -> "failed"
        }

    private fun firstFailedMiningRun(vararg states: MiningRunState): MiningRunState.Failed? =
        states.firstNotNullOfOrNull { it as? MiningRunState.Failed }

    private fun videoPending(state: VideoMiningUiState): String =
        pendingLabels(
            "start" to state.startPending,
            "curation" to state.curationPending,
            "cancel" to state.cancelPending,
            "reset" to state.resetPending,
        )

    private fun readingPending(state: ReadingMiningUiState): String =
        pendingLabels(
            "start" to state.startPending,
            "curation" to state.curationPending,
            "cancel" to state.cancelPending,
            "reset" to state.resetPending,
        )

    private fun pendingLabels(vararg values: Pair<String, Boolean>): String =
        values.filter { it.second }.joinToString(",") { it.first }.ifBlank { NONE }

    /**
     * Truncated before the alphabet check, not after: `SAFE_CODE` bounds length as well, so an
     * over-long code used to render `none` — no signal at all — and a prefix is strictly better.
     * That degradation only ever applies to a name: every id-shaped value on these lines is
     * pattern-bounded at its producer (`f[0-9a-f]{8}` for both fault ids, `run_[0-9a-f]{32}` for the
     * run id), so no lookup key can be cut into a key that finds nothing. The alphabet is still
     * checked in full, so a code carrying anything else is refused rather than mangled.
     */
    private fun safeCode(value: String?): String =
        value?.take(MAX_CODE_CHARS)?.takeIf { SAFE_CODE.matches(it) } ?: NONE

    /**
     * The recorder already whitelists its alphabet; re-applying the report's own sanitizer keeps this
     * line safe if that alphabet ever widens, and bounds the value like every other build value here.
     * The alphabet matches [compactFaultToken] so a pasted report and a pasted error message carry
     * the same token, character for character.
     */
    private fun safeFaultToken(value: String?): String =
        value
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.take(MAX_BUILD_VALUE_CHARS)
            ?.map { character -> if (character in SAFE_FAULT_CHARACTERS) character else '_' }
            ?.joinToString("")
            ?: NONE

    private fun safeBuildValue(value: String): String =
        value
            .trim()
            .take(MAX_BUILD_VALUE_CHARS)
            .map { character -> if (character in SAFE_BUILD_CHARACTERS) character else '_' }
            .joinToString("")
            .ifBlank { "unknown" }

    private const val NONE = "none"
    private const val MAX_ABIS = 8
    private const val MAX_BUILD_VALUE_CHARS = 128
    private const val MAX_REPORT_CHARS = 4_096
    private const val MAX_CODE_CHARS = 64

    // Length bound repeated from MAX_CODE_CHARS so the pattern is still safe on its own.
    private val SAFE_CODE = Regex("[a-z0-9_.-]{1,64}")
    private val SAFE_BUILD_CHARACTERS =
        ('a'..'z') + ('A'..'Z') + ('0'..'9') + setOf('.', '_', '-', '+', ':', '/')
    private val SAFE_FAULT_CHARACTERS =
        ('a'..'z') + ('A'..'Z') + ('0'..'9') + setOf('.', '_', '-', ':', '/', '$', '@', ' ')
}
