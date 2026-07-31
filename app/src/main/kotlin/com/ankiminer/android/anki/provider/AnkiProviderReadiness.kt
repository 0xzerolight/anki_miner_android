package com.ankiminer.android.anki.provider

import com.ankiminer.android.diagnostics.log.AppLog
import com.ankiminer.android.diagnostics.log.LogComponent

/** Stable, UI-safe reasons why the primary AnkiDroid seam is or is not usable. */
internal sealed interface AnkiProviderReadiness {
    data object NotChecked : AnkiProviderReadiness

    data object NotInstalled : AnkiProviderReadiness

    data object Uninitialized : AnkiProviderReadiness

    data class Incompatible(val apiSpecVersion: Int?) : AnkiProviderReadiness

    data object PermissionDenied : AnkiProviderReadiness

    data class Ready(
        val apiSpecVersion: Int,
        val versionCode: Long?,
    ) : AnkiProviderReadiness
}

/** Local startup-journal recovery is independent from ContentProvider availability. */
internal sealed interface AnkiRecoveryReadiness {
    data object NotChecked : AnkiRecoveryReadiness

    data object Ready : AnkiRecoveryReadiness

    data object Blocked : AnkiRecoveryReadiness
}

internal data class AnkiReadinessSnapshot(
    val provider: AnkiProviderReadiness,
    val recovery: AnkiRecoveryReadiness,
)

/**
 * Worker-only readiness probe. It never stores an Activity: the production lambdas close over
 * process-owned gateway and recovery objects only.
 */
internal class AnkiProviderReadinessProbe(
    private val workerThreadGuard: WorkerThreadGuard,
    private val accessStatus: () -> ProviderAccessStatus,
    private val proveCollectionOperational: (AnkiCancellation) -> Unit,
    private val recoverLocalState: () -> Unit,
) {
    fun probe(cancellation: AnkiCancellation = AnkiCancellation.NONE): AnkiReadinessSnapshot {
        workerThreadGuard.checkWorkerThread()
        val provider = probeProvider(cancellation)
        val recovery =
            if (cancellation.isCancelled()) {
                AnkiRecoveryReadiness.NotChecked
            } else {
                try {
                    recoverLocalState()
                    AnkiRecoveryReadiness.Ready
                } catch (_: RuntimeException) {
                    AnkiRecoveryReadiness.Blocked
                }
            }
        return AnkiReadinessSnapshot(provider, recovery)
    }

    private fun probeProvider(cancellation: AnkiCancellation): AnkiProviderReadiness {
        val available =
            when (val access = accessStatusSafely()) {
                ProviderAccessStatus.Absent -> return AnkiProviderReadiness.NotInstalled
                ProviderAccessStatus.ApiDisabled -> return AnkiProviderReadiness.Incompatible(null)
                is ProviderAccessStatus.Incompatible ->
                    return AnkiProviderReadiness.Incompatible(access.apiSpecVersion)
                ProviderAccessStatus.PermissionRequired ->
                    return AnkiProviderReadiness.PermissionDenied
                is ProviderAccessStatus.Available -> access
            }

        try {
            proveCollectionOperational(cancellation)
        } catch (failure: ProviderGatewayException) {
            return when (failure.kind) {
                ProviderFailureKind.API_DISABLED -> AnkiProviderReadiness.Incompatible(null)
                ProviderFailureKind.PERMISSION_REQUIRED -> AnkiProviderReadiness.PermissionDenied
                ProviderFailureKind.PROVIDER_UNAVAILABLE,
                ProviderFailureKind.QUERY_FAILED,
                ProviderFailureKind.TIMEOUT,
                ProviderFailureKind.MUTATION_FAILED,
                ProviderFailureKind.CANCELLED,
                -> AnkiProviderReadiness.Uninitialized
            }
        } catch (failure: RuntimeException) {
            AppLog.e(LogComponent.ANKI, "readiness.collection", failure, "outcome" to "fail")
            return AnkiProviderReadiness.Uninitialized
        }

        if (cancellation.isCancelled()) return AnkiProviderReadiness.Uninitialized
        return AnkiProviderReadiness.Ready(
            apiSpecVersion = available.apiSpecVersion,
            versionCode = available.versionCode,
        )
    }

    private fun accessStatusSafely(): ProviderAccessStatus =
        try {
            accessStatus()
        } catch (failure: ProviderGatewayException) {
            when (failure.kind) {
                ProviderFailureKind.PERMISSION_REQUIRED -> ProviderAccessStatus.PermissionRequired
                ProviderFailureKind.API_DISABLED -> ProviderAccessStatus.ApiDisabled
                else -> ProviderAccessStatus.Absent
            }
        } catch (failure: RuntimeException) {
            AppLog.e(LogComponent.ANKI, "readiness.access", failure, "outcome" to "fail")
            ProviderAccessStatus.Absent
        }
}
