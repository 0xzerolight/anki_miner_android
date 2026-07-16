package com.ankiminer.android.anki.provider

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.FlashCardsContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContentResolverDeckMutationInstrumentedTest {
    @Test
    fun create_deck_uses_one_exact_collection_insert_and_returns_raw_receipt() {
        var calls = 0
        val gateway =
            ContentResolverAnkiGateway(
                context = ApplicationProvider.getApplicationContext(),
                workerThreadGuard = WorkerThreadGuard { },
                accessStatusOverride = { AVAILABLE },
                resolverInsertOverride =
                    ProviderResolverInsert { uri, values ->
                        calls += 1
                        assertEquals(FlashCardsContract.Deck.CONTENT_ALL_URI, uri)
                        assertEquals(setOf(FlashCardsContract.Deck.DECK_NAME), values.keySet())
                        assertEquals("Mining::Japanese", values.getAsString(FlashCardsContract.Deck.DECK_NAME))
                        Uri.parse("content://com.ichi2.anki.flashcards/decks/42")
                    },
            )

        val returned =
            gateway.createDeck(
                AnkiProviderMutationCommand.CreateDeck("Mining::Japanese"),
            )

        assertEquals(1, calls)
        assertEquals("content://com.ichi2.anki.flashcards/decks/42", returned)
    }

    @Test
    fun null_insert_receipt_stays_null_without_a_second_provider_call() {
        var calls = 0
        val gateway =
            ContentResolverAnkiGateway(
                context = ApplicationProvider.getApplicationContext(),
                workerThreadGuard = WorkerThreadGuard { },
                accessStatusOverride = { AVAILABLE },
                resolverInsertOverride =
                    ProviderResolverInsert { _, _ ->
                        calls += 1
                        null
                    },
            )

        assertNull(gateway.createDeck(AnkiProviderMutationCommand.CreateDeck("Mining")))
        assertEquals(1, calls)
    }

    @Test
    fun insert_security_failure_has_stable_permission_taxonomy() {
        val gateway =
            ContentResolverAnkiGateway(
                context = ApplicationProvider.getApplicationContext(),
                workerThreadGuard = WorkerThreadGuard { },
                accessStatusOverride = { AVAILABLE },
                resolverInsertOverride =
                    ProviderResolverInsert { _, _ -> throw SecurityException("denied") },
            )

        assertEquals(
            ProviderFailureKind.PERMISSION_REQUIRED,
            assertThrows(ProviderGatewayException::class.java) {
                gateway.createDeck(AnkiProviderMutationCommand.CreateDeck("Mining"))
            }.kind,
        )
    }

    private companion object {
        val AVAILABLE = ProviderAccessStatus.Available("com.ichi2.anki", 2, 1L)
    }
}
