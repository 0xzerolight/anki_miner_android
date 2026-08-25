package com.ankiminer.android.anki.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * [AnkiProviderRuntime] itself needs a real `android.content.Context` and cannot be constructed
 * in a JVM unit test, so these tests target [deleteNotesLoop] — the extracted core that backs
 * [AnkiProviderRuntime.deleteNotes] — against the JVM [FakeAnkiProviderGateway].
 */
class AnkiProviderRuntimeTest {
    @Test
    fun `deleteNotesLoop counts only notes with an affected row`() {
        val gateway = FakeAnkiProviderGateway()
        val results = mutableListOf(1, 0, 1).iterator()
        gateway.deleteNoteHandler = { results.next() }

        val deleted = deleteNotesLoop(gateway, listOf(10L, 11L, 12L), AnkiCancellation.NONE)

        assertEquals(2, deleted)
        assertEquals(listOf(10L, 11L, 12L), gateway.noteDeleteCommands.map { it.noteId })
    }

    @Test
    fun `deleteNotesLoop stops after cancellation is observed between notes`() {
        val gateway = FakeAnkiProviderGateway()
        val cancellation = MutableAnkiCancellation()
        gateway.deleteNoteHandler = {
            cancellation.cancel()
            1
        }

        val deleted = deleteNotesLoop(gateway, listOf(10L, 20L, 30L), cancellation)

        assertEquals(1, deleted)
        assertEquals(listOf(10L), gateway.noteDeleteCommands.map { it.noteId })
    }

    @Test
    fun `deleteNotesLoop lets a gateway exception propagate unwrapped`() {
        val gateway = FakeAnkiProviderGateway()
        val failure = ProviderGatewayException(ProviderFailureKind.MUTATION_FAILED)
        gateway.deleteNoteHandler = { throw failure }

        val thrown =
            assertThrows(ProviderGatewayException::class.java) {
                deleteNotesLoop(gateway, listOf(10L), AnkiCancellation.NONE)
            }

        assertSame(failure, thrown)
    }
}
