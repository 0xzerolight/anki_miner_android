package com.ankiminer.android.ui.settings

import android.util.Log
import android.webkit.MimeTypeMap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Checks the picker allowlists against what this device's `MimeTypeMap` actually reports.
 *
 * `ExternalStorageProvider` types every document it serves through that table, so the answer is
 * API-level specific and the JVM suite cannot see it: pre-Android 10 libcore maps a `.csv` to
 * text/comma-separated-values rather than text/csv, which greyed the file out in the frequency
 * importer and produced the "can't pick frequency csv in the file browser" report. The mapping is
 * logged so the API 26 lane records what the platform answered, not just that the assert held.
 */
@RunWith(AndroidJUnit4::class)
class ImportMimeTypesInstrumentedTest {
    private val mimeTypes = MimeTypeMap.getSingleton()

    private val pickers =
        mapOf(
            "frequency" to (FREQUENCY_MIME_TYPES to listOf("zip", "csv", "tsv", "txt")),
            "pitch" to (PITCH_MIME_TYPES to listOf("zip", "csv", "tsv", "txt")),
            "known words" to (KNOWN_WORDS_MIME_TYPES to listOf("json", "csv", "tsv", "txt")),
            "custom dictionary" to (CUSTOM_DICTIONARY_MIME_TYPES to listOf("zip")),
            // xz and gz cover the upstream local-audio collection, which is the only
            // way most users get a pack and is not distributed as a ZIP.
            "audio pack" to (AUDIO_PACK_MIME_TYPES to listOf("zip", "xz", "gz", "tar")),
            "word list" to (WORD_LIST_MIME_TYPES to listOf("txt")),
        )

    @Test
    fun everyImportableExtensionIsPickableWithThisPlatformsMimeType() {
        for ((picker, spec) in pickers) {
            val (allowed, extensions) = spec
            for (extension in extensions) {
                // Null means the platform has no mapping, so the provider falls back to
                // application/octet-stream, which every picker already offers.
                val platformMime = mimeTypes.getMimeTypeFromExtension(extension) ?: continue
                Log.i(TAG, "$picker: .$extension -> $platformMime")
                assertTrue(
                    "$picker picker greys out .$extension, which this device types as $platformMime",
                    mimeTypeIsPickable(platformMime, allowed),
                )
            }
        }
    }

    private companion object {
        const val TAG = "ImportMimeTypes"
    }
}
