package com.ankiminer.android.ui.reading

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the reading-tab SAF picker MIME allowlists. A too-narrow list greys
 * out valid files — the Issue #3 bug that made `.cbz` unselectable as a
 * reading source and forced users to extract the `.mokuro` file by hand.
 */
class ReadingMimeTypesTest {
    private val archiveMimeTypes =
        listOf(
            "application/zip",
            "application/x-zip-compressed",
            "application/x-cbz",
            "application/vnd.comicbook+zip",
        )

    @Test
    fun readingSourcePickerAcceptsSelfContainedMokuroArchives() {
        archiveMimeTypes.forEach { mimeType ->
            assertTrue(
                "reading source picker missing $mimeType",
                READING_SOURCE_MIME_TYPES.contains(mimeType),
            )
        }
    }

    @Test
    fun readingSourcePickerKeepsThePreArchiveAllowlist() {
        listOf(
            "text/plain",
            "text/*",
            "application/epub+zip",
            "application/x-subrip",
            "application/x-ass",
            "application/x-ssa",
            "application/json",
            "application/octet-stream",
        ).forEach { mimeType ->
            assertTrue(
                "reading source picker dropped $mimeType",
                READING_SOURCE_MIME_TYPES.contains(mimeType),
            )
        }
    }

    @Test
    fun archivePickerAcceptsEveryKnownArchiveMimeType() {
        (archiveMimeTypes + "application/octet-stream").forEach { mimeType ->
            assertTrue(
                "archive picker missing $mimeType",
                MOKURO_ARCHIVE_MIME_TYPES.contains(mimeType),
            )
        }
    }
}
