package com.ankiminer.android.diagnostics

import android.os.Build
import com.ankiminer.android.BuildConfig
import com.ankiminer.android.anki.provider.AnkiMinerModelProvisioningResult
import com.ankiminer.android.anki.provider.AnkiProviderReadiness
import com.ankiminer.android.engine.PythonRuntimeReadiness
import com.ankiminer.android.mining.MiningRunState
import com.ankiminer.android.ui.reading.ReadingMiningUiState
import com.ankiminer.android.ui.setup.SetupUiState
import com.ankiminer.android.ui.video.VideoMiningUiState
import java.util.Locale

internal data class TesterBuildIdentity(
    val applicationId: String,
    val versionName: String,
    val versionCode: Long,
    val releaseChannel: String,
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

internal fun currentTesterBuildIdentity(): TesterBuildIdentity =
    TesterBuildIdentity(
        applicationId = BuildConfig.APPLICATION_ID,
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE.toLong(),
        releaseChannel = BuildConfig.RELEASE_CHANNEL,
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
    fun build(
        build: TesterBuildIdentity,
        setup: SetupUiState,
        video: VideoMiningUiState,
        reading: ReadingMiningUiState,
    ): TesterDiagnostics {
        val versionName = safeBuildValue(build.versionName)
        val releaseChannel = safeBuildValue(build.releaseChannel)
        val sourceCommit = safeBuildValue(build.sourceCommit)
        val report =
            buildString {
                appendLine("Anki Miner tester diagnostics v1")
                line("app.id", safeBuildValue(build.applicationId))
                line("app.version_name", versionName)
                line("app.version_code", build.versionCode.coerceAtLeast(0L).toString())
                line("release.channel", releaseChannel)
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
                line("setup.complete", setup.firstRunComplete.toString())
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
                line("resources.pitch_usable", (setup.pitchAccent?.schemaOk == true).toString())
                line("resources.operation", setup.operation?.phase?.name?.lowercase(Locale.ROOT) ?: NONE)
                line("resources.failure", safeCode(setup.failure?.code))
                line("anki.provider", ankiReadiness(setup.anki))
                line("anki.model", modelReadiness(setup.model))
                line("anki.remediations", setup.remediations.pending.size.toString())
                line("anki.operation", setup.ankiOperation?.name?.lowercase(Locale.ROOT) ?: NONE)
                line("anki.failure", safeCode(setup.ankiFailure?.code))
                line("permissions.notifications", setup.notifications.name.lowercase(Locale.ROOT))
                line("video.run", miningRun(video.runState))
                line("video.pending", videoPending(video))
                line("video.command_failure", video.commandError?.name?.lowercase(Locale.ROOT) ?: NONE)
                line("reading.run", miningRun(reading.runState))
                line("reading.pending", readingPending(reading))
                line("reading.command_failure", reading.commandError?.name?.lowercase(Locale.ROOT) ?: NONE)
            }.take(MAX_REPORT_CHARS)

        return TesterDiagnostics(
            versionLabel = "$versionName (${build.versionCode.coerceAtLeast(0L)}) · $releaseChannel",
            sourceLabel = sourceCommit,
            report = report,
        )
    }

    private fun StringBuilder.line(key: String, value: String) {
        append(key)
        append('=')
        appendLine(value)
    }

    private fun pythonReadiness(readiness: PythonRuntimeReadiness): String =
        when (readiness) {
            PythonRuntimeReadiness.Pending -> "pending"
            PythonRuntimeReadiness.Starting -> "starting"
            is PythonRuntimeReadiness.Ready -> "ready"
            PythonRuntimeReadiness.Failed -> "failed"
        }

    private fun ankiReadiness(readiness: AnkiProviderReadiness): String =
        when (readiness) {
            AnkiProviderReadiness.NotChecked -> "not_checked"
            AnkiProviderReadiness.NotInstalled -> "not_installed"
            AnkiProviderReadiness.Uninitialized -> "uninitialized"
            is AnkiProviderReadiness.Incompatible -> "incompatible"
            AnkiProviderReadiness.PermissionDenied -> "permission_denied"
            AnkiProviderReadiness.RecoveryBlocked -> "recovery_blocked"
            is AnkiProviderReadiness.Ready -> "ready"
        }

    private fun modelReadiness(result: AnkiMinerModelProvisioningResult?): String =
        when (result) {
            null -> "not_checked"
            is AnkiMinerModelProvisioningResult.Ready -> "ready"
            AnkiMinerModelProvisioningResult.Missing -> "missing"
            is AnkiMinerModelProvisioningResult.Conflict -> "conflict"
            is AnkiMinerModelProvisioningResult.RecoveryRequired -> "recovery_required"
            is AnkiMinerModelProvisioningResult.FailedBeforeEntry -> "failed_before_entry"
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

    private fun safeCode(value: String?): String =
        value?.takeIf { SAFE_CODE.matches(it) } ?: NONE

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
    private val SAFE_CODE = Regex("[a-z0-9_.-]{1,64}")
    private val SAFE_BUILD_CHARACTERS =
        ('a'..'z') + ('A'..'Z') + ('0'..'9') + setOf('.', '_', '-', '+', ':', '/')
}
