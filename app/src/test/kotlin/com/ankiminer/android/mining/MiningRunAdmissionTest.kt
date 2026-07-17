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
                AnkiProviderReadiness.RecoveryBlocked,
            )
        outcomes.forEach { outcome ->
            val gate =
                StatefulMiningRunAdmissionGate(
                    ankiProbe = { outcome },
                    notificationProbe = NotificationPermissionProbe { NotificationPermissionReadiness.READY },
                    targetProbe = AnkiMiningTargetProbe { AnkiMiningTargetReadiness.Ready },
                )
            val evaluated = gate.evaluate(com.ankiminer.android.anki.provider.AnkiCancellation.NONE)
            assertFalse(evaluated.isReady)
            assertTrue(requireNotNull(evaluated.stableFailure).message.isNotBlank())
            assertEquals(evaluated, gate.state.value)
        }
    }

    @Test
    fun `notification denial is reported but does not block mining admission`() {
        val ankiReady = AnkiProviderReadiness.Ready(2, 24L)
        val denied =
            MiningRunAdmissionState(
                ankiReady,
                NotificationPermissionReadiness.PERMISSION_DENIED,
                AnkiMiningTargetReadiness.Ready,
            )
        assertTrue(denied.isReady)
        assertNull(denied.stableFailure)

        val ready =
            MiningRunAdmissionState(
                ankiReady,
                NotificationPermissionReadiness.READY,
                AnkiMiningTargetReadiness.Ready,
            )
        assertTrue(ready.isReady)
        assertNull(ready.stableFailure)
    }

    @Test
    fun `first party model and remediation readiness are mandatory`() {
        val ankiReady = AnkiProviderReadiness.Ready(2, 24L)
        val blocked =
            MiningRunAdmissionState(
                ankiReady,
                NotificationPermissionReadiness.READY,
                AnkiMiningTargetReadiness.Blocked(
                    "Create the Anki Miner note type before mining",
                    retryable = true,
                ),
            )

        assertFalse(blocked.isReady)
        assertEquals(
            "Create the Anki Miner note type before mining",
            requireNotNull(blocked.stableFailure).message,
        )
    }
}
