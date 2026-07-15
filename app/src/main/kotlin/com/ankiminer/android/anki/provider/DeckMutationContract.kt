package com.ankiminer.android.anki.provider

import java.net.URI

/** Sealed provider commands prevent callers from supplying arbitrary URIs or ContentValues. */
internal sealed interface AnkiProviderMutationCommand {
    data class CreateDeck(val deckName: String) : AnkiProviderMutationCommand {
        init {
            try {
                ProviderSnapshotValidation.validateDeck(
                    DeckSnapshot(id = 1L, name = deckName, dynamic = false),
                )
            } catch (error: InvalidTargetSnapshotException) {
                throw IllegalArgumentException("Deck creation requires an exact valid deck name", error)
            }
        }
    }
}

internal data class DeckCreateReceipt(
    val deckId: Long,
    val contentUri: String,
)

/** Accepts only the exact pinned AnkiDroid deck-item URI shape. */
internal object DeckCreateReceiptValidator {
    fun validate(raw: String?): DeckCreateReceipt? {
        if (raw == null || !raw.startsWith(DECK_ITEM_PREFIX)) return null
        val idToken = raw.substring(DECK_ITEM_PREFIX.length)
        if (!CANONICAL_POSITIVE_DECIMAL.matches(idToken)) return null
        val deckId = idToken.toLongOrNull()?.takeIf { it > 0L } ?: return null
        if (raw != "$DECK_ITEM_PREFIX$deckId") return null
        val parsed = try {
            URI(raw)
        } catch (_: Exception) {
            return null
        }
        if (
            parsed.scheme != CONTENT_SCHEME ||
                parsed.rawAuthority != ANKIDROID_AUTHORITY ||
                parsed.rawPath != "/decks/$idToken" ||
                parsed.rawQuery != null ||
                parsed.rawFragment != null ||
                parsed.rawUserInfo != null ||
                parsed.port != -1
        ) {
            return null
        }
        return DeckCreateReceipt(deckId, raw)
    }

    internal const val ANKIDROID_AUTHORITY = "com.ichi2.anki.flashcards"
    internal const val DECK_COLLECTION_URI = "content://$ANKIDROID_AUTHORITY/decks"
    private const val CONTENT_SCHEME = "content"
    private const val DECK_ITEM_PREFIX = "$DECK_COLLECTION_URI/"
    private val CANONICAL_POSITIVE_DECIMAL = Regex("[1-9][0-9]{0,18}")
}
