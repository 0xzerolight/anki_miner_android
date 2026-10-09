package com.ankiminer.android.service

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class MiningForegroundServiceTypeTest {
    @Test
    fun `api 29 to 34 run as dataSync because they predate mediaProcessing`() {
        for (sdkInt in 29..34) {
            assertEquals(
                "sdkInt=$sdkInt",
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                miningForegroundServiceType(sdkInt),
            )
        }
    }

    @Test
    fun `api 35 and later run as mediaProcessing`() {
        for (sdkInt in 35..37) {
            assertEquals(
                "sdkInt=$sdkInt",
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
                miningForegroundServiceType(sdkInt),
            )
        }
    }
}
