package com.ankiminer.android.anki.provider

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `FlashCardsContract`'s docs say notes support only `query()`; vendored `AddContentApi.kt:333`
 * proves the pinned AnkiDroid v2.24.0 provider also implements item-URI delete. This pins that
 * undocumented behaviour against a real AnkiDroid install so a future pin bump that drops it
 * fails loudly instead of silently.
 *
 * The two probe methods need a real, operational AnkiDroid and skip via [assumeTrue] when it is
 * absent, mirroring [com.ankiminer.android.anki.s2.AnkiDroidS2CapabilityInstrumentedTest].
 */
@RunWith(AndroidJUnit4::class)
class ContentResolverNoteDeleteInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val gateway = ContentResolverAnkiGateway(context, WorkerThreadGuard { })

    @Test
    fun delete_note_removes_created_note() {
        assumeRealAnkiDroid()
        val noteId = createProbeNote()

        val affected = gateway.deleteNote(AnkiProviderMutationCommand.DeleteNote(noteId))

        assertEquals(1, affected)
        assertFalse(noteExists(noteId))
    }

    /**
     * AnkiDroid 2.24.0's `CardContentProvider` reports request-count, not affected-rows, for
     * item-URI note deletes: a second delete of an already-gone note still returns 1 — observed
     * on-device 2026-08-25. `deletedNotes` downstream is therefore "requested", not "verified"
     * deletion count, matching the desktop engine's own contract (no per-note ack there either).
     */
    @Test
    fun delete_missing_note_still_reports_request_count() {
        assumeRealAnkiDroid()
        val noteId = createProbeNote()
        assertEquals(1, gateway.deleteNote(AnkiProviderMutationCommand.DeleteNote(noteId)))

        val secondDelete = gateway.deleteNote(AnkiProviderMutationCommand.DeleteNote(noteId))

        assertEquals(1, secondDelete)
        assertFalse(noteExists(noteId))
    }

    /** No real provider round trip needed: the sealed command rejects id 0 during construction. */
    @Test
    fun delete_note_rejects_invalid_id_before_provider() {
        assertThrows(IllegalArgumentException::class.java) {
            gateway.deleteNote(AnkiProviderMutationCommand.DeleteNote(0L))
        }
    }

    private fun assumeRealAnkiDroid() {
        assumeTrue(
            "This device probe requires a real, operational AnkiDroid install",
            gateway.accessStatus() is ProviderAccessStatus.Available,
        )
    }

    private fun createProbeNote(): Long {
        val model = probeModel()
        val fields =
            List(model.fieldCount) { index -> "$PROBE_TAG ${System.nanoTime()} #$index" }
                .joinToString("\u001f")
        val raw = gateway.insertNote(AnkiProviderMutationCommand.InsertNote(model.id, fields, PROBE_TAG))
        return requireNotNull(NoteInsertReceiptValidator.validate(raw)) {
            "probe note insert returned no exact receipt: $raw"
        }.noteId
    }

    /** Resolves an already-existing note type rather than creating one: the gateway has no create op. */
    private fun probeModel(): ProbeModel {
        val query =
            ProviderQuery(endpoint = ProviderEndpoint.MODELS, projection = ProviderQueryShapes.MODEL_PROJECTION)
        return gateway.query(query, AnkiCancellation.NONE)!!.use { cursor ->
            assertTrue("AnkiDroid must expose at least one existing note type", cursor.moveToNext())
            val id = (cursor.cell(ProviderColumn.MODEL_ID) as ProviderCell.Integer).value
            val fieldNamesWire = (cursor.cell(ProviderColumn.MODEL_FIELD_NAMES) as ProviderCell.Text).value
            ProbeModel(id, fieldNamesWire.split('\u001f').size)
        }
    }

    private fun noteExists(noteId: Long): Boolean {
        val query =
            ProviderQuery(
                endpoint = ProviderEndpoint.NOTE_BY_ID,
                endpointId = noteId,
                projection = ProviderQueryShapes.NOTE_SNAPSHOT_PROJECTION,
            )
        return gateway.query(query, AnkiCancellation.NONE)!!.use { it.moveToNext() }
    }

    private data class ProbeModel(val id: Long, val fieldCount: Int)

    private companion object {
        const val PROBE_TAG = "anki_miner_note_delete_probe"
    }
}
