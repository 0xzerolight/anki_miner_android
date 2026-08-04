package com.ankiminer.android.ui.settings

import com.ankiminer.android.data.resources.CSV_MIME_TYPES
import com.ankiminer.android.data.resources.JSON_MIME_TYPES
import com.ankiminer.android.data.resources.TSV_MIME_TYPES
import com.ankiminer.android.data.resources.ZIP_MIME_TYPES
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Couples the SAF picker MIME allowlists to `detectResourceImportFileKind`.
 *
 * The picker greys out anything it does not match, so an allowlist narrower than the classifier
 * silently drops imports the app could have handled: Yomitan `.zip` as
 * application/x-zip-compressed, kanjium `.txt` as text/plain, and a frequency `.csv` as
 * text/comma-separated-values (what pre-Android 10 `MimeUtils` maps the extension to) each
 * shipped as that bug. Asserting the two lists against each other is what stops the next one.
 */
class ImportMimeTypesTest {
    private class PickerSpec(
        val allowed: Array<String>,
        val classifierMimeTypes: Set<String>,
        val textCapable: Boolean,
    )

    private val pickers =
        mapOf(
            "custom dictionary" to
                PickerSpec(CUSTOM_DICTIONARY_MIME_TYPES, ZIP_MIME_TYPES, textCapable = false),
            "audio pack" to
                PickerSpec(AUDIO_PACK_MIME_TYPES, ZIP_MIME_TYPES, textCapable = false),
            "frequency" to
                PickerSpec(
                    FREQUENCY_MIME_TYPES,
                    ZIP_MIME_TYPES + CSV_MIME_TYPES + TSV_MIME_TYPES,
                    textCapable = true,
                ),
            "pitch" to
                PickerSpec(
                    PITCH_MIME_TYPES,
                    ZIP_MIME_TYPES + CSV_MIME_TYPES + TSV_MIME_TYPES,
                    textCapable = true,
                ),
            "known words" to
                PickerSpec(
                    KNOWN_WORDS_MIME_TYPES,
                    JSON_MIME_TYPES + CSV_MIME_TYPES + TSV_MIME_TYPES,
                    textCapable = true,
                ),
            "word list" to
                PickerSpec(WORD_LIST_MIME_TYPES, emptySet(), textCapable = true),
        )

    @Test
    fun everyPickerOffersEveryMimeTypeItsClassifierAccepts() {
        for ((picker, spec) in pickers) {
            for (mimeType in spec.classifierMimeTypes) {
                assertTrue(
                    "$picker picker greys out $mimeType, which the classifier accepts",
                    mimeTypeIsPickable(mimeType, spec.allowed),
                )
            }
        }
    }

    /**
     * The classifier routes any unrecognised `text/...` to a plain-text import, so a text-capable
     * picker cannot enumerate its way to correctness — providers spell a rank list text/csv,
     * text/comma-separated-values, text/tsv or text/x-csv depending on the Android version and the
     * file manager. Only the wildcard covers all of them.
     */
    @Test
    fun textCapablePickersUseTheWildcardRatherThanAFixedList() {
        for ((picker, spec) in pickers.filterValues { it.textCapable }) {
            assertTrue("$picker picker has no text/* entry", spec.allowed.contains("text/*"))
        }
    }

    /**
     * Cloud providers hand back application/octet-stream for anything they cannot type, and the
     * classifier resolves that by sniffing the leading bytes for the ZIP magic. That path is
     * unreachable if the picker never offered the file.
     */
    @Test
    fun everyPickerOffersUntypedDocuments() {
        for ((picker, spec) in pickers) {
            assertTrue(
                "$picker picker greys out untyped documents",
                mimeTypeIsPickable("application/octet-stream", spec.allowed),
            )
        }
    }

    @Test
    fun wildcardMatchingFollowsTheTopLevelTypeOnly() {
        assertTrue(mimeTypeIsPickable("text/comma-separated-values", arrayOf("text/*")))
        assertTrue(mimeTypeIsPickable("application/csv", arrayOf("application/csv")))
        assertFalse(mimeTypeIsPickable("application/csv", arrayOf("text/*")))
        assertFalse(mimeTypeIsPickable("text/csv", arrayOf("text/plain")))
    }
}
