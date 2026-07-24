package com.ankiminer.android.ui.reading

import com.ankiminer.android.media.SafDocument
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingMiningUiStateTest {
    @Test
    fun sourceClassifierAcceptsOnlySupportedReadingInputs() {
        assertTrue(readingSourceKind("aozora.TXT") == ReadingSourceKindUi.TEXT)
        assertTrue(readingSourceKind("book.epub") == ReadingSourceKindUi.EPUB)
        assertTrue(readingSourceKind("episode.SRT") == ReadingSourceKindUi.SUBTITLE)
        assertTrue(readingSourceKind("page.mokuro") == ReadingSourceKindUi.MOKURO)
        assertTrue(readingSourceKind("images.cbz") == ReadingSourceKindUi.MOKURO_ARCHIVE)
        assertTrue(readingSourceKind("images.ZIP") == ReadingSourceKindUi.MOKURO_ARCHIVE)
        assertNull(readingSourceKind("notes.pdf"))
        assertNull(readingSourceKind("folder/novel.txt"))
    }

    @Test
    fun mokuroArchiveSourceStartsWithoutArchiveSlotAndHidesIt() {
        val state =
            ReadingMiningUiState(
                source =
                    ReadingDocumentSlotState(
                        document = document("content://test/self-contained", "volume.cbz"),
                    ),
                sourceKind = ReadingSourceKindUi.MOKURO_ARCHIVE,
            )

        assertTrue(state.canStart)
        assertFalse(state.acceptsArchive)
    }

    @Test
    fun mokuroAllowsTextOnlyOrMatchingArchiveButRejectsMismatch() {
        val sidecar = document("content://test/book-sidecar", "本.mokuro")
        val matchingArchive = document("content://test/book-images", "本.CBZ")
        val mismatchedArchive = document("content://test/other-images", "別.zip")
        val withoutArchive =
            ReadingMiningUiState(
                source = ReadingDocumentSlotState(document = sidecar),
                sourceKind = ReadingSourceKindUi.MOKURO,
            )

        assertTrue(withoutArchive.canStart)
        assertTrue(
            withoutArchive.copy(
                archive = ReadingDocumentSlotState(document = matchingArchive),
            ).canStart,
        )
        assertFalse(
            withoutArchive.copy(
                archive = ReadingDocumentSlotState(document = mismatchedArchive),
            ).canStart,
        )
    }

    @Test
    fun ordinarySingleSourceDoesNotDependOnArchive() {
        val state =
            ReadingMiningUiState(
                source =
                    ReadingDocumentSlotState(
                        document = document("content://test/text", "novel.txt"),
                    ),
                sourceKind = ReadingSourceKindUi.TEXT,
            )

        assertTrue(state.canStart)
    }

    private fun document(
        uri: String,
        displayName: String,
    ) = SafDocument(uri, displayName, mimeType = null, sizeBytes = null)
}
