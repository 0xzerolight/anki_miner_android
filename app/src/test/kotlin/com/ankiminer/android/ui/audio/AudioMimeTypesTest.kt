package com.ankiminer.android.ui.audio

import com.ankiminer.android.vm.AUDIO_EXTENSIONS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioMimeTypesTest {
    @Test
    fun audioPickerAcceptsAudioAndOctetStream() {
        listOf("audio/*", "application/octet-stream").forEach { mimeType ->
            assertTrue(
                "audio picker missing $mimeType",
                AUDIO_MIME_TYPES.contains(mimeType),
            )
        }
    }

    @Test
    fun audioExtensionsMatchDesktopAudiobookTab() {
        assertEquals(
            setOf("m4b", "mp3", "m4a", "aac", "ogg", "opus", "flac", "wav"),
            AUDIO_EXTENSIONS,
        )
    }
}
