package com.ankiminer.android.ui.wizard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingWizardTest {
    @Test
    fun wizardShowsOnlyForConfirmedUnseenStateOrExplicitRerun() {
        // null = settings store has not emitted yet: never flash the wizard.
        assertFalse(wizardVisible(wizardSeen = null, rerunRequested = false))
        assertFalse(wizardVisible(wizardSeen = true, rerunRequested = false))
        assertTrue(wizardVisible(wizardSeen = false, rerunRequested = false))
        // Re-run from Settings works regardless of the persisted flag.
        assertTrue(wizardVisible(wizardSeen = true, rerunRequested = true))
        assertTrue(wizardVisible(wizardSeen = null, rerunRequested = true))
    }
}
