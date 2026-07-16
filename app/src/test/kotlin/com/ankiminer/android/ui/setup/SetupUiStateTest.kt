package com.ankiminer.android.ui.setup

import com.ankiminer.android.data.resources.ResourceStartupReadiness
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupUiStateTest {
    @Test
    fun verifiedUniDicIsRequiredWhileRecommendedDictionaryRemainsOptional() {
        val recovered =
            SetupUiState(
                resourceStartup = ResourceStartupReadiness.READY,
                uniDicInstalled = true,
                recommendedDictionaryInstalled = false,
            )

        assertTrue(recovered.canFinishFirstRun)
        assertFalse(recovered.copy(uniDicInstalled = false).canFinishFirstRun)
        assertFalse(
            recovered.copy(resourceStartup = ResourceStartupReadiness.FAILED).canFinishFirstRun,
        )
    }

    @Test
    fun customDictionarySlotMustUseTheStableWireFormat() {
        assertTrue(SetupUiState(customSlotId = "custom-dictionary.v2").customSlotValid)
        assertFalse(SetupUiState(customSlotId = "Custom Dictionary").customSlotValid)
        assertFalse(SetupUiState(customSlotId = "custom..dictionary").customSlotValid)
    }
}
