package com.ankiminer.android.ui.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnkiMinerNavigationTest {
    @Test
    fun returningUserRedirectWaitsForSetupRouteAndPreservesManualSetupVisits() {
        assertFalse(
            shouldRedirectCompletedSetup(true, currentRoute = null, keepCompletedSetupVisible = false),
        )
        assertTrue(
            shouldRedirectCompletedSetup(true, currentRoute = "setup", keepCompletedSetupVisible = false),
        )
        assertFalse(
            shouldRedirectCompletedSetup(true, currentRoute = "settings", keepCompletedSetupVisible = false),
        )
        assertFalse(
            shouldRedirectCompletedSetup(true, currentRoute = "setup", keepCompletedSetupVisible = true),
        )
        assertFalse(
            shouldRedirectCompletedSetup(false, currentRoute = "setup", keepCompletedSetupVisible = false),
        )
    }
}
