package com.ankiminer.android.ui.setup

import com.ankiminer.android.anki.provider.AnkiMinerModelProvisioningResult
import com.ankiminer.android.anki.provider.AnkiMinerModelReadyOrigin
import com.ankiminer.android.anki.provider.AnkiPendingRemediation
import com.ankiminer.android.anki.provider.AnkiProviderReadiness
import com.ankiminer.android.anki.provider.AnkiRemediationActionKind
import com.ankiminer.android.anki.provider.AnkiRemediationInventory
import com.ankiminer.android.anki.provider.AnkiRemediationType
import com.ankiminer.android.data.anki.AnkiSetupOperation
import com.ankiminer.android.data.resources.ResourceStartupReadiness
import com.ankiminer.android.engine.PythonRuntimeReadiness
import com.ankiminer.android.mining.NotificationPermissionReadiness
import org.junit.Assert.assertFalse
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
                model =
                    AnkiMinerModelProvisioningResult.Ready(
                        7L,
                        AnkiMinerModelReadyOrigin.EXISTING_EXACT,
                    ),
                uniDicInstalled = true,
                recommendedDictionaryInstalled = false,
            )

        assertTrue(recovered.canFinishFirstRun)
        assertTrue(
            recovered.copy(
                notifications = NotificationPermissionReadiness.PERMISSION_DENIED,
            ).canFinishFirstRun,
        )
        assertFalse(recovered.copy(uniDicInstalled = false).canFinishFirstRun)
        assertFalse(
            recovered.copy(resourceStartup = ResourceStartupReadiness.FAILED).canFinishFirstRun,
        )
        assertFalse(recovered.copy(legacyNoteType = "Lapis").canFinishFirstRun)
        assertFalse(recovered.copy(ankiOperation = AnkiSetupOperation.REFRESHING).canFinishFirstRun)
        val pending =
            AnkiPendingRemediation(
                id = 1L,
                type = AnkiRemediationType.MEDIA_COMMIT_UNCERTAIN,
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
    fun customDictionarySlotMustUseTheStableWireFormat() {
        assertTrue(SetupUiState(customSlotId = "custom-dictionary.v2").customSlotValid)
        assertFalse(SetupUiState(customSlotId = "Custom Dictionary").customSlotValid)
        assertFalse(SetupUiState(customSlotId = "custom..dictionary").customSlotValid)
    }
}
