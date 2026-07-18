package com.ankiminer.android.service

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MiningNotificationIntentInstrumentedTest {
    @Test
    fun notificationRunPayloadIsConsumedExactlyOnceAcrossRecreation() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val identity =
            MiningForegroundSessionIdentity(
                runId = "run_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                generation = 7L,
                leaseId = "00000000-0000-0000-0000-000000000001",
            )
        val retainedActivityIntent = MiningForegroundService.openAppIntent(context, identity)

        assertEquals(
            identity.runId,
            MiningForegroundService.consumeOpenedRunId(retainedActivityIntent),
        )
        assertNull(MiningForegroundService.consumeOpenedRunId(retainedActivityIntent))
        assertNull(MiningForegroundService.openedRunId(retainedActivityIntent))
    }
}
