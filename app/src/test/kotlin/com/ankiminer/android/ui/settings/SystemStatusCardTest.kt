package com.ankiminer.android.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemStatusCardTest {
    @Test
    fun readyStateHasNoAttentionRows() {
        val result = setupTaskStatus(SetupTaskFacts.ready())

        assertEquals(SetupSummaryKind.READY, result.summary)
        assertEquals(0, result.requiredAttentionCount)
        assertTrue(result.rows.all { it.role == SetupTaskRole.SUCCESS })
    }

    @Test
    fun optionalNotificationWarningDoesNotMakeMiningSetupIncomplete() {
        val result =
            setupTaskStatus(
                SetupTaskFacts.ready().copy(notificationReady = false),
            )

        assertEquals(SetupSummaryKind.READY_WITH_OPTIONAL_WARNING, result.summary)
        assertEquals(0, result.requiredAttentionCount)
        assertEquals(
            SetupTaskRole.OPTIONAL_WARNING,
            result.rows.single { it.id == SetupTaskId.NOTIFICATIONS }.role,
        )
    }

    @Test
    fun everyFailedRequirementCountsAndUsesRequiredActionRole() {
        val result =
            setupTaskStatus(
                SetupTaskFacts(
                    ankiReady = false,
                    noteTypeReady = false,
                    recoveryReady = false,
                    uniDicReady = false,
                    notificationReady = true,
                    busy = false,
                ),
            )

        assertEquals(SetupSummaryKind.ATTENTION, result.summary)
        assertEquals(4, result.requiredAttentionCount)
        assertEquals(
            4,
            result.rows.count { it.role == SetupTaskRole.REQUIRED_ACTION },
        )
    }

    @Test
    fun busyStateUsesBusyRoleWithoutClaimingFailure() {
        val result = setupTaskStatus(SetupTaskFacts.ready().copy(busy = true))

        assertEquals(SetupSummaryKind.BUSY, result.summary)
        assertEquals(0, result.requiredAttentionCount)
        assertTrue(result.rows.all { it.role == SetupTaskRole.BUSY })
    }
}
