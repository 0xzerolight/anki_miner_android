package com.ankiminer.android.anki.journal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalRetentionPolicyTest {
    @Test
    fun ageCutoffClampsAtZeroWhenWallClockMovesBackward() {
        val policy =
            JournalRetentionPolicy.forTests(
                completedCohortLimit = 2,
                resolvedRemediationLimit = 3,
                maxAgeMillis = 100,
            )

        assertEquals(0L, policy.ageCutoff(nowEpochMillis = 50))
        assertEquals(900L, policy.ageCutoff(nowEpochMillis = 1_000))
    }

    @Test
    fun completedCohortPruningUsesAgeOrCountBoundary() {
        val policy =
            JournalRetentionPolicy.forTests(
                completedCohortLimit = 2,
                resolvedRemediationLimit = 3,
                maxAgeMillis = 100,
            )
        val countBoundary = JournalRetentionBoundary(finalizedAtMs = 950, parentId = 20)

        assertTrue(policy.shouldPrune(899, 100, nowEpochMillis = 1_000, countBoundary))
        assertTrue(policy.shouldPrune(949, 99, nowEpochMillis = 1_000, countBoundary))
        assertTrue(policy.shouldPrune(950, 19, nowEpochMillis = 1_000, countBoundary))
        assertFalse(policy.shouldPrune(950, 20, nowEpochMillis = 1_000, countBoundary))
        assertFalse(policy.shouldPrune(951, 1, nowEpochMillis = 1_000, countBoundary))
    }
}
