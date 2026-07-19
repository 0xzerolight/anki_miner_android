package com.ankiminer.android.ui.settings

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the SAF picker MIME allowlists for resource imports. A too-narrow list
 * greys out valid files (or hands back a null URI), silently dropping the
 * import — the bug that hid Yomitan `.zip` (application/x-zip-compressed) and
 * kanjium `.txt` (text/plain) from the pitch importer.
 */
class ImportMimeTypesTest {
    private val zipPickers =
        mapOf(
            "custom dictionary" to CUSTOM_DICTIONARY_MIME_TYPES,
            "frequency" to FREQUENCY_MIME_TYPES,
            "pitch" to PITCH_MIME_TYPES,
            "audio pack" to AUDIO_PACK_MIME_TYPES,
        )

    @Test
    fun pitchAcceptsTextAndAlternateZipTypes() {
        assertTrue(PITCH_MIME_TYPES.contains("text/plain"))
        assertTrue(PITCH_MIME_TYPES.contains("application/x-zip-compressed"))
        assertTrue(PITCH_MIME_TYPES.contains("text/csv"))
        assertTrue(PITCH_MIME_TYPES.contains("text/tab-separated-values"))
    }

    @Test
    fun everyZipPickerAcceptsBothZipMimeTypes() {
        for ((name, types) in zipPickers) {
            assertTrue("$name picker missing application/zip", types.contains("application/zip"))
            assertTrue(
                "$name picker missing application/x-zip-compressed",
                types.contains("application/x-zip-compressed"),
            )
        }
    }
}
