package com.ankiminer.android.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrphanedSafGrantReconcilerTest {
    @Test
    fun `startup retains owned grants and removes only orphans exactly once`() {
        val inventory = FakeInventory()
        inventory.putSelection(
            SafSelectionSlot.VIDEO,
            SafSelectionRecord("content://provider/video", "episode.mkv"),
        )
        val access =
            FakeAccess(
                listOf(
                    "content://provider/video",
                    "content://provider/orphan",
                ),
            )
        val reconciler = OrphanedSafGrantReconciler(access, inventory)

        reconciler.reconcile()
        reconciler.reconcile()

        assertTrue(reconciler.isReconciled())
        assertEquals(listOf("content://provider/orphan"), access.released)
        assertEquals("content://provider/video", inventory.ownedUris().single())
        assertEquals(1, access.readCount)
    }

    @Test
    fun `startup removes stale inventory when provider permission is missing`() {
        val inventory = FakeInventory()
        inventory.putSelection(
            SafSelectionSlot.READING_SOURCE,
            SafSelectionRecord("content://provider/missing", "book.epub"),
        )
        inventory.putSelection(
            SafSelectionSlot.READING_ARCHIVE,
            SafSelectionRecord("content://provider/archive", "book.cbz"),
        )
        inventory.putText(SafSelectionSlot.READING_SUBTITLE_SERIES, "Series")
        val access = FakeAccess(listOf("content://provider/archive"))

        OrphanedSafGrantReconciler(access, inventory).reconcile()

        assertEquals(null, inventory.selection(SafSelectionSlot.READING_SOURCE))
        assertEquals(null, inventory.selection(SafSelectionSlot.READING_ARCHIVE))
        assertEquals(null, inventory.text(SafSelectionSlot.READING_SUBTITLE_SERIES))
        assertEquals(listOf("content://provider/archive"), access.released)
    }

    @Test
    fun `failed inventory read stays retryable`() {
        val access = FakeAccess(listOf("content://provider/video"), failFirstRead = true)
        val reconciler = OrphanedSafGrantReconciler(access)

        assertTrue(runCatching(reconciler::reconcile).isFailure)
        assertFalse(reconciler.isReconciled())
        reconciler.reconcile()

        assertTrue(reconciler.isReconciled())
        assertEquals(listOf("content://provider/video"), access.released)
        assertEquals(2, access.readCount)
    }

    private class FakeAccess(
        private val grants: List<String>,
        private val failFirstRead: Boolean = false,
    ) : PersistedSafGrantAccess {
        var readCount = 0
        val released = mutableListOf<String>()

        override fun readGrantUris(): List<String> {
            readCount += 1
            if (failFirstRead && readCount == 1) error("injected")
            return grants
        }

        override fun releaseReadGrant(uri: String) {
            released += uri
        }
    }

    private class FakeInventory : SafSelectionInventory {
        private val selections = mutableMapOf<SafSelectionSlot, SafSelectionRecord>()
        private val text = mutableMapOf<SafSelectionSlot, String>()

        override fun selection(slot: SafSelectionSlot): SafSelectionRecord? = selections[slot]

        override fun putSelection(
            slot: SafSelectionSlot,
            selection: SafSelectionRecord?,
        ) {
            if (selection == null) selections.remove(slot) else selections[slot] = selection
        }

        override fun text(slot: SafSelectionSlot): String? = text[slot]

        override fun putText(
            slot: SafSelectionSlot,
            value: String?,
        ) {
            if (value == null) text.remove(slot) else text[slot] = value
        }

        override fun ownedUris(): Set<String> = selections.values.mapTo(linkedSetOf()) { it.uri }

        override fun pruneMissingGrants(grantedUris: Set<String>) {
            val stale =
                selections
                    .filterValues { it.uri !in grantedUris }
                    .keys
                    .toList()
            stale.forEach(selections::remove)
            if (SafSelectionSlot.READING_SOURCE in stale) {
                selections.remove(SafSelectionSlot.READING_ARCHIVE)
                text.remove(SafSelectionSlot.READING_SUBTITLE_SERIES)
            }
        }
    }
}
