package com.ankiminer.android.ui.video

import org.junit.Assert.assertTrue
import org.junit.Test

class VideoMimeTypesTest {
    @Test
    fun subtitlePickerAcceptsAssAndSsaFiles() {
        listOf("application/x-ass", "application/x-ssa").forEach { mimeType ->
            assertTrue(
                "video subtitle picker missing $mimeType",
                SUBTITLE_MIME_TYPES.contains(mimeType),
            )
        }
    }
}
