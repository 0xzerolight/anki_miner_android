package com.ankiminer.android.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SafSelectionInventoryTest {
    @Test
    fun replacementLeavesOnlyNewUriOwned() {
        val inventory = TransientSafSelectionInventory()
        inventory.putSelection(
            SafSelectionSlot.VIDEO,
            SafSelectionRecord("content://provider/old", "old.mkv"),
        )

        inventory.putSelection(
            SafSelectionSlot.VIDEO,
            SafSelectionRecord("content://provider/new", "new.mkv"),
        )

        assertEquals(setOf("content://provider/new"), inventory.ownedUris())
        assertEquals(
            "new.mkv",
            inventory.selection(SafSelectionSlot.VIDEO)?.displayName,
        )
    }

    @Test
    fun pruningMissingReadingSourceAlsoClearsSeriesName() {
        val inventory = TransientSafSelectionInventory()
        inventory.putSelection(
            SafSelectionSlot.READING_SOURCE,
            SafSelectionRecord("content://provider/episode", "episode.srt"),
        )
        inventory.putText(SafSelectionSlot.READING_SUBTITLE_SERIES, "Show")
        inventory.putSelection(
            SafSelectionSlot.READING_ARCHIVE,
            SafSelectionRecord("content://provider/archive", "episode.cbz"),
        )

        inventory.pruneMissingGrants(emptySet())

        assertNull(inventory.selection(SafSelectionSlot.READING_SOURCE))
        assertNull(inventory.selection(SafSelectionSlot.READING_ARCHIVE))
        assertNull(inventory.text(SafSelectionSlot.READING_SUBTITLE_SERIES))
    }

    @Test
    fun incompatibleReadingDependentsArePrunedEvenWhenTheirGrantsExist() {
        val inventory = TransientSafSelectionInventory()
        inventory.putSelection(
            SafSelectionSlot.READING_SOURCE,
            SafSelectionRecord("content://provider/book", "book.epub"),
        )
        inventory.putSelection(
            SafSelectionSlot.READING_ARCHIVE,
            SafSelectionRecord("content://provider/archive", "book.cbz"),
        )
        inventory.putText(SafSelectionSlot.READING_SUBTITLE_SERIES, "Wrong series")

        inventory.pruneMissingGrants(
            setOf("content://provider/book", "content://provider/archive"),
        )

        assertEquals("book.epub", inventory.selection(SafSelectionSlot.READING_SOURCE)?.displayName)
        assertNull(inventory.selection(SafSelectionSlot.READING_ARCHIVE))
        assertNull(inventory.text(SafSelectionSlot.READING_SUBTITLE_SERIES))
    }

    @Test
    fun invalidDurableMetadataIsRejected() {
        assertNull(safSelectionRecordOrNull("file:///private/video.mkv", "video.mkv"))
        assertNull(safSelectionRecordOrNull("content://provider/video", "../video.mkv"))
        assertNull(safSelectionRecordOrNull("content://provider/video", "folder/video.mkv"))
        assertEquals(
            SafSelectionRecord("content://provider/video", "video.mkv"),
            safSelectionRecordOrNull("content://provider/video", "video.mkv"),
        )
    }
}
