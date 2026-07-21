package com.ankiminer.android.mining

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import com.ankiminer.android.anki.provider.AnkiProviderReadiness
import com.ankiminer.android.anki.provider.AnkiReadinessSnapshot
import com.ankiminer.android.anki.provider.AnkiRecoveryReadiness
import com.ichi2.anki.api.BuildConfig as AnkiApiBuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiningRunAdmissionTest {
    @Test
    fun `notification permission state is reported only from API 33`() {
        assertEquals(
            NotificationPermissionReadiness.READY,
            AndroidNotificationPermissionProbe(32) { PackageManager.PERMISSION_DENIED }.probe(),
        )
        assertEquals(
            NotificationPermissionReadiness.PERMISSION_DENIED,
            AndroidNotificationPermissionProbe(33) { PackageManager.PERMISSION_DENIED }.probe(),
        )
        assertEquals(
            NotificationPermissionReadiness.READY,
            AndroidNotificationPermissionProbe(33) { PackageManager.PERMISSION_GRANTED }.probe(),
        )
    }

    @Test
    fun `runtime permission descriptions are SDK exact and Activity independent`() {
        assertEquals(
            listOf(AnkiApiBuildConfig.READ_WRITE_PERMISSION),
            MiningRuntimePermissions.requestableFor(32).map { it.permission },
        )
        assertEquals(
            listOf(AnkiApiBuildConfig.READ_WRITE_PERMISSION, Manifest.permission.POST_NOTIFICATIONS),
            MiningRuntimePermissions.requestableFor(Build.VERSION_CODES.TIRAMISU).map { it.permission },
        )
    }

    @Test
    fun `admission publishes each stable fail closed reason`() {
        val outcomes =
            listOf(
                AnkiProviderReadiness.NotInstalled,
                AnkiProviderReadiness.Uninitialized,
                AnkiProviderReadiness.Incompatible(1),
                AnkiProviderReadiness.PermissionDenied,
            )
        outcomes.forEach { outcome ->
            var targetCalls = 0
            val gate =
                StatefulMiningRunAdmissionGate(
                    ankiProbe = {
                        AnkiReadinessSnapshot(outcome, AnkiRecoveryReadiness.Ready)
                    },
                    notificationProbe = NotificationPermissionProbe { NotificationPermissionReadiness.READY },
                    targetProbe =
                        AnkiMiningTargetProbe {
                            targetCalls += 1
                            AnkiMiningTargetReadiness.Ready
                        },
                )
            val evaluated = gate.evaluate(com.ankiminer.android.anki.provider.AnkiCancellation.NONE)
            assertFalse(evaluated.isReady)
            assertTrue(requireNotNull(evaluated.stableFailure).message.isNotBlank())
            assertEquals(evaluated, gate.state.value)
            assertEquals(0, targetCalls)
        }

        var blockedTargetCalls = 0
        val recoveryBlocked =
            StatefulMiningRunAdmissionGate(
                ankiProbe = {
                    AnkiReadinessSnapshot(
                        AnkiProviderReadiness.Ready(2, 24L),
                        AnkiRecoveryReadiness.Blocked,
                    )
                },
                notificationProbe = NotificationPermissionProbe { NotificationPermissionReadiness.READY },
                targetProbe =
                    AnkiMiningTargetProbe {
                        blockedTargetCalls += 1
                        AnkiMiningTargetReadiness.Ready
                    },
            ).evaluate(com.ankiminer.android.anki.provider.AnkiCancellation.NONE)
        assertFalse(recoveryBlocked.isReady)
        assertEquals(
            "Anki recovery must be resolved before another mining run",
            requireNotNull(recoveryBlocked.stableFailure).message,
        )
        assertEquals(0, blockedTargetCalls)
    }

    @Test
    fun `notification denial is reported but does not block mining admission`() {
        val ankiReady = AnkiProviderReadiness.Ready(2, 24L)
        val denied =
            MiningRunAdmissionState(
                anki = ankiReady,
                ankiRecovery = AnkiRecoveryReadiness.Ready,
                notifications = NotificationPermissionReadiness.PERMISSION_DENIED,
                target = AnkiMiningTargetReadiness.Ready,
            )
        assertTrue(denied.isReady)
        assertNull(denied.stableFailure)

        val ready =
            MiningRunAdmissionState(
                anki = ankiReady,
                ankiRecovery = AnkiRecoveryReadiness.Ready,
                notifications = NotificationPermissionReadiness.READY,
                target = AnkiMiningTargetReadiness.Ready,
            )
        assertTrue(ready.isReady)
        assertNull(ready.stableFailure)
    }

    @Test
    fun `note type selection and remediation readiness are mandatory`() {
        val ankiReady = AnkiProviderReadiness.Ready(2, 24L)
        val blocked =
            MiningRunAdmissionState(
                anki = ankiReady,
                ankiRecovery = AnkiRecoveryReadiness.Ready,
                notifications = NotificationPermissionReadiness.READY,
                target = AnkiMiningTargetReadiness.Blocked(
                    "Select and verify a note type in Settings before mining",
                    retryable = true,
                ),
            )

        assertFalse(blocked.isReady)
        assertEquals(
            "Select and verify a note type in Settings before mining",
            requireNotNull(blocked.stableFailure).message,
        )
    }
}
