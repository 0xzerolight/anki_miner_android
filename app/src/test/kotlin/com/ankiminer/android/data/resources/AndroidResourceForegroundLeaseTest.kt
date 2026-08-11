package com.ankiminer.android.data.resources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AndroidResourceForegroundLeaseTest {
    @Test
    fun initialForegroundAdmissionFailurePropagates() {
        val failure = IllegalStateException("foreground start denied")
        var starts = 0
        val lease =
            AndroidResourceForegroundLease(
                startService = {
                    starts += 1
                    throw failure
                },
                stopService = {},
                elapsedMillis = { 0L },
            )

        val thrown =
            assertThrows(IllegalStateException::class.java) {
                lease.start(progress(ResourceOperationPhase.PREPARING))
            }

        assertEquals(failure, thrown)
        lease.update(progress(ResourceOperationPhase.IMPORTING))
        assertEquals(1, starts)
    }

    @Test
    fun notificationUpdateFailureIsBestEffortAfterAdmission() {
        var starts = 0
        val lease =
            AndroidResourceForegroundLease(
                startService = {
                    starts += 1
                    if (starts > 1) error("notification update denied")
                },
                stopService = {},
                elapsedMillis = { 0L },
            )

        lease.start(progress(ResourceOperationPhase.PREPARING))
        lease.update(progress(ResourceOperationPhase.IMPORTING))

        assertEquals(2, starts)
    }

    private fun progress(phase: ResourceOperationPhase) =
        ResourceOperationProgress(
            operationId = "resource_fixture",
            label = "Fixture import",
            phase = phase,
        )
}
