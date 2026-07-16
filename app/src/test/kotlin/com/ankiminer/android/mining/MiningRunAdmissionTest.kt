package com.ankiminer.android.mining

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import com.ankiminer.android.anki.provider.AnkiProviderReadiness
import com.ichi2.anki.api.BuildConfig as AnkiApiBuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiningRunAdmissionTest {
    @Test
    fun `notification permission is required only from API 33`() {
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
            MiningRuntimePermissions.requiredFor(32).map { it.permission },
        )
        assertEquals(
            listOf(AnkiApiBuildConfig.READ_WRITE_PERMISSION, Manifest.permission.POST_NOTIFICATIONS),
            MiningRuntimePermissions.requiredFor(Build.VERSION_CODES.TIRAMISU).map { it.permission },
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
                AnkiProviderReadiness.RecoveryBlocked,
            )
        outcomes.forEach { outcome ->
            val gate =
                StatefulMiningRunAdmissionGate(
                    ankiProbe = { outcome },
                    notificationProbe = NotificationPermissionProbe { NotificationPermissionReadiness.READY },
                )
            val evaluated = gate.evaluate(com.ankiminer.android.anki.provider.AnkiCancellation.NONE)
            assertFalse(evaluated.isReady)
            assertTrue(requireNotNull(evaluated.stableFailure).message.isNotBlank())
            assertEquals(evaluated, gate.state.value)
        }
    }

    @Test
    fun `notifications and Anki must both be ready`() {
        val ankiReady = AnkiProviderReadiness.Ready(2, 24L)
        val denied =
            MiningRunAdmissionState(ankiReady, NotificationPermissionReadiness.PERMISSION_DENIED)
        assertFalse(denied.isReady)
        assertTrue(requireNotNull(denied.stableFailure).retryable)

        val ready = MiningRunAdmissionState(ankiReady, NotificationPermissionReadiness.READY)
        assertTrue(ready.isReady)
        assertNull(ready.stableFailure)
    }
}
