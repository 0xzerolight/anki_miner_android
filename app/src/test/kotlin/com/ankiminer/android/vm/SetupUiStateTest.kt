package com.ankiminer.android.vm

import com.ankiminer.android.anki.provider.AnkiPendingRemediation
import com.ankiminer.android.anki.provider.AnkiProviderReadiness
import com.ankiminer.android.anki.provider.AnkiRecoveryReadiness
import com.ankiminer.android.anki.provider.AnkiRemediationActionKind
import com.ankiminer.android.anki.provider.AnkiRemediationInventory
import com.ankiminer.android.anki.provider.AnkiRemediationSummary
import com.ankiminer.android.anki.provider.AnkiRemediationType
import com.ankiminer.android.anki.provider.NoteTypeSetupStatus
import com.ankiminer.android.data.anki.AnkiSetupOperation
import com.ankiminer.android.data.anki.AnkiRecoveryInventoryStatus
import com.ankiminer.android.data.RuntimeWorkCoordinator
import com.ankiminer.android.data.resources.ResourceStartupReadiness
import com.ankiminer.android.engine.PythonRuntimeReadiness
import com.ankiminer.android.mining.AnkiMiningTargetReadiness
import com.ankiminer.android.mining.NotificationPermissionReadiness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupUiStateTest {
    @Test
    fun verifiedUniDicIsRequiredWhileRecommendedDictionaryRemainsOptional() {
        val recovered =
            SetupUiState(
                python = PythonRuntimeReadiness.Ready("/private/runtime"),
                resourceStartup = ResourceStartupReadiness.READY,
                anki = AnkiProviderReadiness.Ready(2, 24L),
                ankiRecovery = AnkiRecoveryReadiness.Ready,
                miningTarget = AnkiMiningTargetReadiness.Ready,
                noteTypeStatus = NoteTypeSetupStatus.Verified(modelId = 1L),
                recoveryInventoryStatus = AnkiRecoveryInventoryStatus.AVAILABLE,
                uniDicInstalled = true,
            )

        assertTrue(recovered.targetReady)
        assertTrue(recovered.isMiningReady)
        assertTrue(
            recovered.copy(
                notifications = NotificationPermissionReadiness.PERMISSION_DENIED,
            ).isMiningReady,
        )
        assertFalse(recovered.copy(uniDicInstalled = false).isMiningReady)
        val fieldsMissing =
            recovered.copy(noteTypeStatus = NoteTypeSetupStatus.FieldsMissing(listOf("sentence")))
        assertFalse(fieldsMissing.targetReady)
        assertFalse(fieldsMissing.isMiningReady)
        val admissionBlocked =
            recovered.copy(
                miningTarget =
                    AnkiMiningTargetReadiness.Blocked(
                        message = "Recovery item pending",
                        retryable = true,
                    ),
            )
        assertFalse(admissionBlocked.targetReady)
        assertFalse(admissionBlocked.isMiningReady)
        assertFalse(
            recovered.copy(resourceStartup = ResourceStartupReadiness.FAILED).isMiningReady,
        )
        assertFalse(recovered.copy(ankiOperation = AnkiSetupOperation.REFRESHING).isMiningReady)
        assertTrue(recovered.copy(resourceStartup = ResourceStartupReadiness.PENDING).busy)
        assertTrue(recovered.copy(resourceStartup = ResourceStartupReadiness.RECOVERING).busy)
        assertTrue(recovered.copy(resourceStartup = ResourceStartupReadiness.FAILED).busy)
        assertTrue(
            recovered.copy(runtimeWorkKind = RuntimeWorkCoordinator.Kind.MINING).isMiningReady,
        )
        assertFalse(
            recovered.copy(runtimeWorkKind = RuntimeWorkCoordinator.Kind.RESOURCE).isMiningReady,
        )
        assertTrue(recovered.copy(runtimeWorkKind = RuntimeWorkCoordinator.Kind.MINING).busy)
        val pending =
            AnkiPendingRemediation(
                id = 1L,
                type = AnkiRemediationType.MEDIA_COMMIT_UNCERTAIN,
                summaryReason = AnkiRemediationSummary.MEDIA_COMMIT_UNCERTAIN,
                title = "Media save needs review",
                summary = "Review the media write",
                compactEvidence = null,
                createdAtMs = 1L,
                updatedAtMs = 1L,
                availableActions = emptySet<AnkiRemediationActionKind>(),
            )
        assertFalse(
            recovered.copy(remediations = AnkiRemediationInventory(listOf(pending))).isMiningReady,
        )
    }

    @Test
    fun wizardSeenStaysUnknownUntilTheSettingsStoreEmits() {
        assertNull(SetupUiState().wizardSeen)
        assertEquals(false, SetupUiState(wizardSeen = false).wizardSeen)
        assertEquals(true, SetupUiState(wizardSeen = true).wizardSeen)
    }

    @Test
    fun customDictionarySlotMustUseTheStableWireFormat() {
        assertTrue(SetupUiState(customSlotId = "custom-dictionary.v2").customSlotValid)
        assertFalse(SetupUiState(customSlotId = "Custom Dictionary").customSlotValid)
        assertFalse(SetupUiState(customSlotId = "custom..dictionary").customSlotValid)
    }

    @Test
    fun ankiDroidActionMatchesEachExternalReadinessState() {
        assertEquals(
            AnkiDroidSetupAction.INSTALL,
            SetupUiState(anki = AnkiProviderReadiness.NotInstalled).ankiDroidAction,
        )
        assertEquals(
            AnkiDroidSetupAction.OPEN,
            SetupUiState(anki = AnkiProviderReadiness.Uninitialized).ankiDroidAction,
        )
        assertEquals(
            AnkiDroidSetupAction.REQUEST_PERMISSION,
            SetupUiState(anki = AnkiProviderReadiness.PermissionDenied).ankiDroidAction,
        )
        assertEquals(
            AnkiDroidSetupAction.OPEN_OR_INSTALL,
            SetupUiState(
                anki = AnkiProviderReadiness.Incompatible(apiSpecVersion = null),
            ).ankiDroidAction,
        )
        assertEquals(
            AnkiDroidSetupAction.INSTALL,
            SetupUiState(
                anki = AnkiProviderReadiness.Incompatible(apiSpecVersion = 1),
            ).ankiDroidAction,
        )
        assertNull(SetupUiState(anki = AnkiProviderReadiness.NotChecked).ankiDroidAction)
        assertNull(
            SetupUiState(anki = AnkiProviderReadiness.Ready(apiSpecVersion = 2, versionCode = 7L))
                .ankiDroidAction,
        )
    }
}
