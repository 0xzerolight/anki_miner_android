package com.ankiminer.android.anki.provider

import android.database.MatrixCursor
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.FlashCardsContract
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContentResolverProviderPrimitivesInstrumentedTest {
    @Test
    fun media_receipt_accepts_androids_canonical_encoded_path_segment() {
        val raw = Uri.fromFile(File("anki_miner_日.mp3")).toString()

        assertEquals(
            "file:///anki_miner_%E6%97%A5.mp3",
            raw,
        )
        assertEquals(
            MediaInsertReceipt("anki_miner_日.mp3", raw),
            MediaInsertReceiptValidator.validate(raw),
        )
    }

    @Test
    fun exact_note_snapshot_uses_direct_note_item_query() {
        val expectedProjection = arrayOf("_id", "mid", "flds", "tags")
        var calls = 0
        val gateway =
            queryGateway { uri, projection, selection, selectionArgs, sortOrder, signal ->
                calls += 1
                assertEquals("content://com.ichi2.anki.flashcards/notes/42", uri.toString())
                assertArrayEquals(expectedProjection, projection)
                assertNull(selection)
                assertNull(selectionArgs)
                assertNull(sortOrder)
                assertFalse(signal.isCanceled)
                MatrixCursor(projection)
            }
        val query =
            ProviderQuery(
                endpoint = ProviderEndpoint.NOTE_BY_ID,
                endpointId = 42L,
                projection = ProviderQueryShapes.NOTE_SNAPSHOT_PROJECTION,
            )

        gateway.query(query, AnkiCancellation.NONE)!!.close()

        assertEquals(1, calls)
    }

    @Test
    fun exact_cards_snapshot_uses_direct_note_cards_query() {
        // `original_deck_id` is the home-deck link: a card a filtered deck borrowed reports the
        // filtered deck in `deck_id`, so deck-scoped reads need both.
        val expectedProjection = arrayOf("_id", "note_id", "ord", "deck_id", "original_deck_id")
        var calls = 0
        val gateway =
            queryGateway { uri, projection, selection, selectionArgs, sortOrder, signal ->
                calls += 1
                assertEquals("content://com.ichi2.anki.flashcards/notes/42/cards", uri.toString())
                assertArrayEquals(expectedProjection, projection)
                assertNull(selection)
                assertNull(selectionArgs)
                assertNull(sortOrder)
                assertFalse(signal.isCanceled)
                MatrixCursor(projection)
            }
        val query =
            ProviderQuery(
                endpoint = ProviderEndpoint.CARDS_FOR_NOTE,
                endpointId = 42L,
                projection = ProviderQueryShapes.CARD_IDENTITY_PROJECTION,
            )

        gateway.query(query, AnkiCancellation.NONE)!!.close()

        assertEquals(1, calls)
    }

    @Test
    fun media_and_note_inserts_use_only_the_pinned_content_values() {
        var calls = 0
        val gateway =
            ContentResolverAnkiGateway(
                context = ApplicationProvider.getApplicationContext(),
                workerThreadGuard = WorkerThreadGuard { },
                accessStatusOverride = { AVAILABLE },
                resolverInsertOverride =
                    ProviderResolverInsert { uri, values ->
                        calls += 1
                        when (calls) {
                            1 -> {
                                assertEquals(FlashCardsContract.AnkiMedia.CONTENT_URI, uri)
                                assertEquals(
                                    setOf(
                                        FlashCardsContract.AnkiMedia.FILE_URI,
                                        FlashCardsContract.AnkiMedia.PREFERRED_NAME,
                                    ),
                                    values.keySet(),
                                )
                                assertEquals(
                                    "content://com.ankiminer.android.staging/assets/token_1",
                                    values.getAsString(FlashCardsContract.AnkiMedia.FILE_URI),
                                )
                                assertEquals(
                                    "anki_miner_audio",
                                    values.getAsString(FlashCardsContract.AnkiMedia.PREFERRED_NAME),
                                )
                                Uri.parse("file:///anki_miner_audio_1.mp3")
                            }
                            2 -> {
                                assertEquals(FlashCardsContract.Note.CONTENT_URI, uri)
                                assertEquals(
                                    setOf(
                                        FlashCardsContract.Note.MID,
                                        FlashCardsContract.Note.FLDS,
                                        FlashCardsContract.Note.TAGS,
                                    ),
                                    values.keySet(),
                                )
                                assertEquals(7L, values.getAsLong(FlashCardsContract.Note.MID))
                                assertEquals(
                                    "expression\u001fmeaning",
                                    values.getAsString(FlashCardsContract.Note.FLDS),
                                )
                                assertEquals(
                                    "mined japanese",
                                    values.getAsString(FlashCardsContract.Note.TAGS),
                                )
                                Uri.parse("content://com.ichi2.anki.flashcards/notes/21")
                            }
                            else -> error("unexpected insert")
                        }
                    },
            )

        assertEquals(
            "file:///anki_miner_audio_1.mp3",
            gateway.storeMedia(
                AnkiProviderMutationCommand.StoreMedia(
                    "content://com.ankiminer.android.staging/assets/token_1",
                    "anki_miner_audio",
                ),
            ),
        )
        assertEquals(
            "content://com.ichi2.anki.flashcards/notes/21",
            gateway.insertNote(
                AnkiProviderMutationCommand.InsertNote(
                    7L,
                    "expression\u001fmeaning",
                    "mined japanese",
                ),
            ),
        )
        assertEquals(2, calls)
    }

    @Test
    fun card_routing_updates_one_documented_note_ordinal_uri() {
        var calls = 0
        val gateway =
            ContentResolverAnkiGateway(
                context = ApplicationProvider.getApplicationContext(),
                workerThreadGuard = WorkerThreadGuard { },
                accessStatusOverride = { AVAILABLE },
                resolverUpdateOverride =
                    ProviderResolverUpdate { uri, values, selection, selectionArgs ->
                        calls += 1
                        assertEquals(
                            "content://com.ichi2.anki.flashcards/notes/12/cards/0",
                            uri.toString(),
                        )
                        assertEquals(setOf(FlashCardsContract.Card.DECK_ID), values.keySet())
                        assertEquals(13L, values.getAsLong(FlashCardsContract.Card.DECK_ID))
                        assertNull(selection)
                        assertNull(selectionArgs)
                        1
                    },
            )

        val affected =
            gateway.routeCard(
                AnkiProviderMutationCommand.RouteCard(
                    expectedCardId = 11L,
                    noteId = 12L,
                    ordinal = 0,
                    targetDeckId = 13L,
                ),
            )

        assertEquals(1, affected)
        assertEquals(1, calls)
    }

    @Test
    fun note_delete_removes_the_documented_note_item_uri() {
        var calls = 0
        var affected = 0
        val gateway =
            ContentResolverAnkiGateway(
                context = ApplicationProvider.getApplicationContext(),
                workerThreadGuard = WorkerThreadGuard { },
                accessStatusOverride = { AVAILABLE },
                resolverDeleteOverride =
                    ProviderResolverDelete { uri, selection, selectionArgs ->
                        calls += 1
                        assertEquals("content://com.ichi2.anki.flashcards/notes/42", uri.toString())
                        assertNull(selection)
                        assertNull(selectionArgs)
                        affected
                    },
            )

        affected = 0
        assertEquals(0, gateway.deleteNote(AnkiProviderMutationCommand.DeleteNote(42L)))
        affected = 1
        assertEquals(1, gateway.deleteNote(AnkiProviderMutationCommand.DeleteNote(42L)))

        assertEquals(2, calls)
    }

    @Test
    fun note_delete_worker_guard_failure_precedes_resolver_entry() {
        val failure = IllegalStateException("main thread")
        val gateway =
            ContentResolverAnkiGateway(
                context = ApplicationProvider.getApplicationContext(),
                workerThreadGuard = WorkerThreadGuard { throw failure },
                accessStatusOverride = { AVAILABLE },
                resolverDeleteOverride =
                    ProviderResolverDelete { _, _, _ ->
                        error("deleteNote must not reach the resolver after a worker guard failure")
                    },
            )

        val thrown =
            assertThrows(IllegalStateException::class.java) {
                gateway.deleteNote(AnkiProviderMutationCommand.DeleteNote(42L))
            }

        assertSame(failure, thrown)
    }

    @Test
    fun note_delete_security_failure_has_stable_permission_taxonomy() {
        val gateway =
            ContentResolverAnkiGateway(
                context = ApplicationProvider.getApplicationContext(),
                workerThreadGuard = WorkerThreadGuard { },
                accessStatusOverride = { AVAILABLE },
                resolverDeleteOverride =
                    ProviderResolverDelete { _, _, _ -> throw SecurityException("denied") },
            )

        assertEquals(
            ProviderFailureKind.PERMISSION_REQUIRED,
            assertThrows(ProviderGatewayException::class.java) {
                gateway.deleteNote(AnkiProviderMutationCommand.DeleteNote(42L))
            }.kind,
        )
    }

    @Test
    fun every_mutation_rechecks_access_before_resolver_entry() {
        var resolverCalls = 0
        val gateway =
            ContentResolverAnkiGateway(
                context = ApplicationProvider.getApplicationContext(),
                workerThreadGuard = WorkerThreadGuard { },
                accessStatusOverride = { ProviderAccessStatus.PermissionRequired },
                resolverInsertOverride =
                    ProviderResolverInsert { _, _ ->
                        resolverCalls += 1
                        null
                    },
                resolverUpdateOverride =
                    ProviderResolverUpdate { _, _, _, _ ->
                        resolverCalls += 1
                        0
                    },
                resolverDeleteOverride =
                    ProviderResolverDelete { _, _, _ ->
                        resolverCalls += 1
                        0
                    },
            )
        val operations =
            listOf<() -> Unit>(
                { gateway.createDeck(AnkiProviderMutationCommand.CreateDeck("Mining")) },
                {
                    gateway.storeMedia(
                        AnkiProviderMutationCommand.StoreMedia("content://staging/assets/1", "ab"),
                    )
                },
                { gateway.insertNote(AnkiProviderMutationCommand.InsertNote(7L, "word", "tag")) },
                { gateway.routeCard(AnkiProviderMutationCommand.RouteCard(8L, 9L, 0, 10L)) },
                { gateway.deleteNote(AnkiProviderMutationCommand.DeleteNote(42L)) },
            )

        operations.forEach { operation ->
            assertEquals(
                ProviderFailureKind.PERMISSION_REQUIRED,
                assertThrows(ProviderGatewayException::class.java) { operation() }.kind,
            )
        }
        assertEquals(0, resolverCalls)
    }

    @Test
    fun generic_insert_and_update_failures_are_mutations_not_queries() {
        val insertGateway =
            ContentResolverAnkiGateway(
                context = ApplicationProvider.getApplicationContext(),
                workerThreadGuard = WorkerThreadGuard { },
                accessStatusOverride = { AVAILABLE },
                resolverInsertOverride =
                    ProviderResolverInsert { _, _ -> throw IllegalStateException("provider rejected write") },
            )
        val updateGateway =
            ContentResolverAnkiGateway(
                context = ApplicationProvider.getApplicationContext(),
                workerThreadGuard = WorkerThreadGuard { },
                accessStatusOverride = { AVAILABLE },
                resolverUpdateOverride =
                    ProviderResolverUpdate { _, _, _, _ -> throw IllegalStateException("provider rejected write") },
            )
        val deleteGateway =
            ContentResolverAnkiGateway(
                context = ApplicationProvider.getApplicationContext(),
                workerThreadGuard = WorkerThreadGuard { },
                accessStatusOverride = { AVAILABLE },
                resolverDeleteOverride =
                    ProviderResolverDelete { _, _, _ -> throw IllegalStateException("provider rejected write") },
            )

        assertEquals(
            ProviderFailureKind.MUTATION_FAILED,
            assertThrows(ProviderGatewayException::class.java) {
                insertGateway.insertNote(AnkiProviderMutationCommand.InsertNote(7L, "word", "tag"))
            }.kind,
        )
        assertEquals(
            ProviderFailureKind.MUTATION_FAILED,
            assertThrows(ProviderGatewayException::class.java) {
                updateGateway.routeCard(AnkiProviderMutationCommand.RouteCard(8L, 9L, 0, 10L))
            }.kind,
        )
        assertEquals(
            ProviderFailureKind.MUTATION_FAILED,
            assertThrows(ProviderGatewayException::class.java) {
                deleteGateway.deleteNote(AnkiProviderMutationCommand.DeleteNote(42L))
            }.kind,
        )

        listOf(
            ProviderFailureKind.QUERY_FAILED,
            ProviderFailureKind.TIMEOUT,
            ProviderFailureKind.CANCELLED,
        ).forEach { impossibleKind ->
            val gateway =
                ContentResolverAnkiGateway(
                    context = ApplicationProvider.getApplicationContext(),
                    workerThreadGuard = WorkerThreadGuard { },
                    accessStatusOverride = { AVAILABLE },
                    resolverInsertOverride =
                        ProviderResolverInsert { _, _ ->
                            throw ProviderGatewayException(impossibleKind)
                        },
                )
            val failure =
                assertThrows(ProviderGatewayException::class.java) {
                    gateway.insertNote(AnkiProviderMutationCommand.InsertNote(7L, "word", "tag"))
                }
            assertEquals(impossibleKind.toString(), ProviderFailureKind.MUTATION_FAILED, failure.kind)
            assertEquals(impossibleKind.toString(), impossibleKind, (failure.cause as ProviderGatewayException).kind)
        }
    }

    private fun queryGateway(query: ProviderResolverQuery): ContentResolverAnkiGateway =
        ContentResolverAnkiGateway(
            context = ApplicationProvider.getApplicationContext(),
            workerThreadGuard = WorkerThreadGuard { },
            accessStatusOverride = { AVAILABLE },
            resolverQueryOverride = query,
        )

    private companion object {
        val AVAILABLE = ProviderAccessStatus.Available("com.ichi2.anki", 2, 1L)
    }
}
