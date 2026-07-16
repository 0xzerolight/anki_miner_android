package com.ankiminer.android.mining

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.ankiminer.android.anki.provider.AnkiProviderReadiness
import com.ankiminer.android.anki.provider.AnkiCancellation
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

internal enum class MiningRuntimePermissionKind {
    ANKIDROID_DATABASE,
    NOTIFICATIONS,
}

internal data class MiningRuntimePermissionRequest(
    val kind: MiningRuntimePermissionKind,
    val permission: String,
)

/** Activity-independent request descriptions for a future Activity Result launcher. */
internal object MiningRuntimePermissions {
    fun requiredFor(sdkInt: Int): List<MiningRuntimePermissionRequest> =
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
    val notifications: NotificationPermissionReadiness,
) {
    val isReady: Boolean
        get() = anki is AnkiProviderReadiness.Ready && notifications == NotificationPermissionReadiness.READY

    val stableFailure: MiningFailure?
        get() {
            if (notifications == NotificationPermissionReadiness.PERMISSION_DENIED) {
                return MiningFailure(
                    "Allow notifications before starting background mining",
                    retryable = true,
                )
            }
            return when (anki) {
                AnkiProviderReadiness.NotChecked ->
                    MiningFailure("AnkiDroid readiness has not been checked", retryable = true)
                AnkiProviderReadiness.NotInstalled ->
                    MiningFailure("Install AnkiDroid before mining", retryable = true)
                AnkiProviderReadiness.Uninitialized ->
                    MiningFailure("Open AnkiDroid and finish its initial setup before mining", retryable = true)
                is AnkiProviderReadiness.Incompatible ->
                    MiningFailure("The installed AnkiDroid API is incompatible", retryable = false)
                AnkiProviderReadiness.PermissionDenied ->
                    MiningFailure("Allow AnkiDroid database access before mining", retryable = true)
                AnkiProviderReadiness.RecoveryBlocked ->
                    MiningFailure("Anki recovery must be resolved before another mining run", retryable = false)
                is AnkiProviderReadiness.Ready -> null
            }
        }

    internal companion object {
        val READY_FOR_TESTS =
            MiningRunAdmissionState(
                anki = AnkiProviderReadiness.Ready(apiSpecVersion = 2, versionCode = null),
                notifications = NotificationPermissionReadiness.READY,
            )
    }
}

internal interface MiningRunAdmissionGate {
    val state: StateFlow<MiningRunAdmissionState>

    /** Synchronous worker-only recheck performed immediately before accepting expensive work. */
    fun evaluate(cancellation: AnkiCancellation): MiningRunAdmissionState
}

internal class StatefulMiningRunAdmissionGate(
    private val ankiProbe: (AnkiCancellation) -> AnkiProviderReadiness,
    private val notificationProbe: NotificationPermissionProbe,
) : MiningRunAdmissionGate {
    private val mutableState = MutableStateFlow(initialState())
    override val state: StateFlow<MiningRunAdmissionState> = mutableState.asStateFlow()

    override fun evaluate(cancellation: AnkiCancellation): MiningRunAdmissionState =
        MiningRunAdmissionState(
            anki = ankiProbe(cancellation),
            notifications = notificationProbe.probe(),
        ).also { mutableState.value = it }

    private companion object {
        fun initialState() =
            MiningRunAdmissionState(
                anki = AnkiProviderReadiness.NotChecked,
                notifications = NotificationPermissionReadiness.READY,
            )
    }
}

internal object AlwaysReadyMiningRunAdmissionGate : MiningRunAdmissionGate {
    private val ready = MutableStateFlow(MiningRunAdmissionState.READY_FOR_TESTS).asStateFlow()
    override val state: StateFlow<MiningRunAdmissionState> = ready

    override fun evaluate(cancellation: AnkiCancellation): MiningRunAdmissionState = ready.value
}
