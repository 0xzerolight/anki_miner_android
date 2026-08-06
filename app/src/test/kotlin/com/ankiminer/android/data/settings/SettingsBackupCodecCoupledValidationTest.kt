package com.ankiminer.android.data.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsBackupCodecCoupledValidationTest {
    @Test
    fun `a conditionally invalid imported value is rejected without escaping quarantine`() {
        val current =
            AppSettings(
                animatedScreenshotsEnabled = true,
                animatedScreenshotQuality = 60,
            )
        val json =
            """{"ankiMinerAndroidSettings":1,"appVersion":"0.4.1","schemaVersion":2,""" +
                """"settings":{"screenshot_animated_quality":9999}}"""

        val applied = with(SettingsBackupCodec) { parse(json).applyTo(current) }

        assertEquals(listOf("screenshot_animated_quality"), applied.rejectedKeys)
        assertEquals(current, applied.settings)
    }

    @Test
    fun `an imported cross-field conflict cannot erase an absent current key`() {
        val current =
            AppSettings(
                fieldMap = mapOf("word" to "Word"),
                cardTypeMarkerField = "IsClickCard",
            )
        val json =
            """{"ankiMinerAndroidSettings":1,"appVersion":"0.4.1","schemaVersion":2,""" +
                """"settings":{"field_map_v1":"field-map-v1\nword=IsClickCard\n"}}"""

        val applied = with(SettingsBackupCodec) { parse(json).applyTo(current) }

        assertEquals(listOf("field_map_v1"), applied.rejectedKeys)
        assertEquals(current, applied.settings)
    }
}
