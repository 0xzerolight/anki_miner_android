package com.ankiminer.android.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

internal class SettingsCardIndexRecorderTest {
    @Test
    fun `indices start after the header and tab strip`() {
        val recorder = SettingsCardIndexRecorder()

        recorder.begin(SettingsCategory.MEDIA)

        assertEquals(2, recorder.record(SettingsCategory.MEDIA, "first"))
        assertEquals(3, recorder.record(SettingsCategory.MEDIA, "second"))
    }

    @Test
    fun `a re-emitted category renumbers from the top`() {
        val recorder = SettingsCardIndexRecorder()

        recorder.begin(SettingsCategory.MEDIA)
        recorder.record(SettingsCategory.MEDIA, "first")
        recorder.record(SettingsCategory.MEDIA, "second")
        recorder.begin(SettingsCategory.MEDIA)

        assertEquals(2, recorder.record(SettingsCategory.MEDIA, "first"))
        assertEquals(2, recorder.indexOf(SettingsCategory.MEDIA, "first"))
    }

    @Test
    fun `a category that dropped a conditional card forgets its stale index`() {
        val recorder = SettingsCardIndexRecorder()

        recorder.begin(SettingsCategory.MEDIA)
        recorder.record(SettingsCategory.MEDIA, "first")
        recorder.record(SettingsCategory.MEDIA, "second")
        recorder.begin(SettingsCategory.MEDIA)
        recorder.record(SettingsCategory.MEDIA, "first")

        assertNull(recorder.indexOf(SettingsCategory.MEDIA, "second"))
    }

    @Test
    fun `recording one category leaves another category's indices alone`() {
        val recorder = SettingsCardIndexRecorder()

        recorder.begin(SettingsCategory.MEDIA)
        recorder.record(SettingsCategory.MEDIA, "media")
        recorder.begin(SettingsCategory.FILTERING)
        recorder.record(SettingsCategory.FILTERING, "filtering")

        assertEquals(2, recorder.indexOf(SettingsCategory.MEDIA, "media"))
    }

    @Test
    fun `a category that has not been shown has no indices`() {
        val recorder = SettingsCardIndexRecorder()

        assertNull(recorder.indexOf(SettingsCategory.MEDIA, "missing"))
    }
}
