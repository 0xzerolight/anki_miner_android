package com.ankiminer.android.mining

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.ankiminer.android.R
import com.ankiminer.android.anki.provider.AnkiCancellation
import com.ankiminer.android.anki.provider.AnkiReadinessSnapshot
import com.ankiminer.android.anki.provider.AnkiProviderReadiness
import com.ankiminer.android.anki.provider.AnkiRecoveryReadiness
import com.ankiminer.android.localization.StringResourceResolver
import com.ichi2.anki.api.BuildConfig as AnkiApiBuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class NotificationPermissionReadiness {
    READY,
    PERMISSION_DENIED,
}

internal fun interface NotificationPermissionProbe {
    fun probe(): NotificationPermissionReadiness
}

internal sealed interface AnkiMiningTargetReadiness {
    data object NotChecked : AnkiMiningTargetReadiness

    data object Ready : AnkiMiningTargetReadiness

    data class Blocked(
        val message: String,
        val retryable: Boolean,
    ) : AnkiMiningTargetReadiness {
        init {
            require(message.isNotBlank()) { "A blocked Anki target needs a stable explanation" }
        }
    }
}

internal fun interface AnkiMiningTargetProbe {
    fun probe(cancellation: AnkiCancellation): AnkiMiningTargetReadiness
}

internal enum class MiningRuntimePermissionKind {
    ANKIDROID_DATABASE,
    NOTIFICATIONS,
}

internal data class MiningRuntimePermissionRequest(
    val kind: MiningRuntimePermissionKind,
    val permission: String,
)

/** Activity-independent permissions which setup can request through an Activity Result launcher. */
@SuppressLint("InlinedApi")
internal object MiningRuntimePermissions {
    fun requestableFor(sdkInt: Int): List<MiningRuntimePermissionRequest> =
        buildList {
            add(
                MiningRuntimePermissionRequest(
                    MiningRuntimePermissionKind.ANKIDROID_DATABASE,
                    AnkiApiBuildConfig.READ_WRITE_PERMISSION,
                ),
            )
            if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
                add(
                    MiningRuntimePermissionRequest(
                        MiningRuntimePermissionKind.NOTIFICATIONS,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ),
                )
            }
        }
}

/** Process-context-only permission probe; an Activity is needed later only to launch a request. */
internal class AndroidNotificationPermissionProbe(
    private val sdkInt: Int,
    private val permissionCheck: (String) -> Int,
) : NotificationPermissionProbe {
    constructor(
        context: Context,
        sdkInt: Int = Build.VERSION.SDK_INT,
    ) : this(sdkInt, applicationPermissionChecker(context))

    override fun probe(): NotificationPermissionReadiness =
        if (
            sdkInt < Build.VERSION_CODES.TIRAMISU ||
                permissionCheck(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationPermissionReadiness.READY
        } else {
            NotificationPermissionReadiness.PERMISSION_DENIED
        }
}

private fun applicationPermissionChecker(context: Context): (String) -> Int {
    val applicationContext = context.applicationContext
    return applicationContext::checkSelfPermission
}

internal data class MiningRunAdmissionState(
    val anki: AnkiProviderReadiness,
    val ankiRecovery: AnkiRecoveryReadiness,
    val notifications: NotificationPermissionReadiness,
    val target: AnkiMiningTargetReadiness,
) {
    val isReady: Boolean
        get() =
            anki is AnkiProviderReadiness.Ready &&
                ankiRecovery == AnkiRecoveryReadiness.Ready &&
                target == AnkiMiningTargetReadiness.Ready

    fun stableFailure(strings: StringResourceResolver): MiningFailure? {
        val ankiFailure =
            when (anki) {
                AnkiProviderReadiness.NotChecked ->
                    MiningFailure(strings.resolve(R.string.mining_admission_not_checked), retryable = true)
                AnkiProviderReadiness.NotInstalled ->
                    MiningFailure(strings.resolve(R.string.mining_admission_install_ankidroid), retryable = true)
                AnkiProviderReadiness.Uninitialized ->
                    MiningFailure(strings.resolve(R.string.mining_admission_initialize_ankidroid), retryable = true)
                is AnkiProviderReadiness.Incompatible ->
                    MiningFailure(strings.resolve(R.string.mining_admission_incompatible_ankidroid), retryable = false)
                AnkiProviderReadiness.PermissionDenied ->
                    MiningFailure(strings.resolve(R.string.mining_admission_permission_required), retryable = true)
                is AnkiProviderReadiness.Ready -> null
            }
        if (ankiFailure != null) return ankiFailure
        if (ankiRecovery != AnkiRecoveryReadiness.Ready) {
            return MiningFailure(
                strings.resolve(R.string.mining_admission_recovery_required),
                retryable = ankiRecovery == AnkiRecoveryReadiness.NotChecked,
            )
        }
        return when (val targetState = target) {
            AnkiMiningTargetReadiness.NotChecked ->
                MiningFailure(strings.resolve(R.string.mining_admission_note_type_not_checked), retryable = true)
            AnkiMiningTargetReadiness.Ready -> null
            is AnkiMiningTargetReadiness.Blocked ->
                MiningFailure(targetState.message, retryable = targetState.retryable)
        }
    }

    internal companion object {
        val READY_FOR_TESTS =
            MiningRunAdmissionState(
                anki = AnkiProviderReadiness.Ready(apiSpecVersion = 2, versionCode = null),
                ankiRecovery = AnkiRecoveryReadiness.Ready,
                notifications = NotificationPermissionReadiness.READY,
                target = AnkiMiningTargetReadiness.Ready,
            )
    }
}

internal interface MiningRunAdmissionGate {
    val state: StateFlow<MiningRunAdmissionState>

    /** Synchronous worker-only recheck performed immediately before accepting expensive work. */
    fun evaluate(cancellation: AnkiCancellation): MiningRunAdmissionState
}

internal class StatefulMiningRunAdmissionGate(
    private val ankiProbe: (AnkiCancellation) -> AnkiReadinessSnapshot,
    private val notificationProbe: NotificationPermissionProbe,
    private val targetProbe: AnkiMiningTargetProbe,
) : MiningRunAdmissionGate {
    private val mutableState = MutableStateFlow(initialState())
    override val state: StateFlow<MiningRunAdmissionState> = mutableState.asStateFlow()

    override fun evaluate(cancellation: AnkiCancellation): MiningRunAdmissionState {
        val anki = ankiProbe(cancellation)
        val canProbeTarget =
            anki.provider is AnkiProviderReadiness.Ready &&
                anki.recovery == AnkiRecoveryReadiness.Ready
        return MiningRunAdmissionState(
            anki = anki.provider,
            ankiRecovery = anki.recovery,
            notifications = notificationProbe.probe(),
            target =
                if (canProbeTarget) {
                    targetProbe.probe(cancellation)
                } else {
                    AnkiMiningTargetReadiness.NotChecked
                },
        ).also { mutableState.value = it }
    }

    private companion object {
        fun initialState() =
            MiningRunAdmissionState(
                anki = AnkiProviderReadiness.NotChecked,
                ankiRecovery = AnkiRecoveryReadiness.NotChecked,
                notifications = NotificationPermissionReadiness.READY,
                target = AnkiMiningTargetReadiness.NotChecked,
            )
    }
}

internal object AlwaysReadyMiningRunAdmissionGate : MiningRunAdmissionGate {
    private val ready = MutableStateFlow(MiningRunAdmissionState.READY_FOR_TESTS).asStateFlow()
    override val state: StateFlow<MiningRunAdmissionState> = ready

    override fun evaluate(cancellation: AnkiCancellation): MiningRunAdmissionState = ready.value
}
