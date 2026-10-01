package com.ankiminer.android.mining

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import com.ankiminer.android.anki.provider.AnkiProviderReadiness
import com.ankiminer.android.anki.provider.AnkiReadinessSnapshot
import com.ankiminer.android.anki.provider.AnkiRecoveryReadiness
import com.ankiminer.android.localization.testStringResourceResolver
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
    fun `AnkiDroid and notification permissions are asked separately`() {
        assertEquals(AnkiApiBuildConfig.READ_WRITE_PERMISSION, MiningRuntimePermissions.ANKIDROID_DATABASE)
        assertNull(MiningRuntimePermissions.notificationPermissionFor(32))
        assertEquals(
            Manifest.permission.POST_NOTIFICATIONS,
            MiningRuntimePermissions.notificationPermissionFor(Build.VERSION_CODES.TIRAMISU),
        )
    }

    @Test
    fun `a denial with no rationale left is permanent`() {
        assertTrue(ankiPermissionPermanentlyDenied(granted = false, showRationale = false))
        assertFalse(ankiPermissionPermanentlyDenied(granted = false, showRationale = true))
        assertFalse(ankiPermissionPermanentlyDenied(granted = true, showRationale = false))
    }

    @Test
    fun `notification permission is asked once, when a foreground job starts`() {
        assertTrue(notificationPermissionDue(33, notificationsReady = false, foregroundJobStarting = true, alreadyAskedThisProcess = false))
        assertFalse(notificationPermissionDue(33, notificationsReady = false, foregroundJobStarting = false, alreadyAskedThisProcess = false))
        assertFalse(notificationPermissionDue(33, notificationsReady = false, foregroundJobStarting = true, alreadyAskedThisProcess = true))
        assertFalse(notificationPermissionDue(33, notificationsReady = true, foregroundJobStarting = true, alreadyAskedThisProcess = false))
        assertFalse(notificationPermissionDue(32, notificationsReady = false, foregroundJobStarting = true, alreadyAskedThisProcess = false))
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
            assertTrue(requireNotNull(evaluated.stableFailure(testStringResourceResolver)).message.isNotBlank())
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
            requireNotNull(recoveryBlocked.stableFailure(testStringResourceResolver)).message,
        )
        assertEquals(0, blockedTargetCalls)
    }

    @Test
    fun `failed AnkiDroid access check asks for another check instead of installation`() {
        val failure =
            MiningRunAdmissionState(
                anki = AnkiProviderReadiness.NotChecked,
                ankiRecovery = AnkiRecoveryReadiness.Ready,
                notifications = NotificationPermissionReadiness.READY,
                target = AnkiMiningTargetReadiness.NotChecked,
            ).stableFailure(testStringResourceResolver)

        assertEquals("AnkiDroid readiness has not been checked", requireNotNull(failure).message)
        assertTrue(failure.retryable)
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
        assertNull(denied.stableFailure(testStringResourceResolver))

        val ready =
            MiningRunAdmissionState(
                anki = ankiReady,
                ankiRecovery = AnkiRecoveryReadiness.Ready,
                notifications = NotificationPermissionReadiness.READY,
                target = AnkiMiningTargetReadiness.Ready,
            )
        assertTrue(ready.isReady)
        assertNull(ready.stableFailure(testStringResourceResolver))
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
            requireNotNull(blocked.stableFailure(testStringResourceResolver)).message,
        )
    }
}
