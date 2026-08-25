package com.ankiminer.android.anki.provider

import com.ankiminer.android.anki.generated.AnkiLimitsV1
import com.ankiminer.android.anki.generated.UnicodeContractV151
import com.ankiminer.android.anki.protocol.AnkiProtocolException
import com.ankiminer.android.anki.protocol.AnkiValidators
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Sealed provider commands prevent callers from supplying arbitrary target URIs or ContentValues. */
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

    data class StoreMedia(
        val fileUri: String,
        val preferredName: String,
    ) : AnkiProviderMutationCommand {
        init {
            requireCanonicalContentSource(fileUri)
            // The pinned provider appends "_" and passes this as File.createTempFile's prefix,
            // whose minimum length is three characters. Reject a predictable null receipt before
            // a future journal records provider entry.
            requireSafeCanonicalMediaName(
                preferredName,
                "preferred media name",
                minimumScalarCount = 2,
            )
        }
    }

    data class InsertNote(
        val modelId: Long,
        val joinedFields: String,
        val providerTagsWire: String,
    ) : AnkiProviderMutationCommand {
        init {
            require(modelId > 0L) { "Raw note insert requires a positive model ID" }
            requireRawProviderText(
                joinedFields,
                AnkiLimitsV1.CreateNotes.NOTE_CONTENT_MAX_UTF8_BYTES,
                "joined note fields",
            )
            require(
                joinedFields.count { it == FIELD_SEPARATOR } <
                    AnkiLimitsV1.CreateNotes.MAX_FIELD_COUNT_PER_NOTE,
            ) { "Raw note insert exceeds the field-count contract" }
            requireRawProviderText(
                providerTagsWire,
                AnkiLimitsV1.CreateNotes.TAGS_PER_NOTE_MAX_UTF8_BYTES,
                "provider tags",
            )
            require(FIELD_SEPARATOR !in providerTagsWire) {
                "Provider tags must not contain the note field separator"
            }
        }
    }

    /**
     * Routes the provider's documented note/ordinal endpoint. [expectedCardId] is retained for
     * durable pre/post correlation; it is not substituted into the provider URI.
     */
    data class RouteCard(
        val expectedCardId: Long,
        val noteId: Long,
        val ordinal: Int,
        val targetDeckId: Long,
    ) : AnkiProviderMutationCommand {
        init {
            require(expectedCardId > 0L && noteId > 0L && targetDeckId > 0L) {
                "Card routing IDs must be positive"
            }
            require(ordinal in 0 until AnkiLimitsV1.CreateNotes.MAX_CARD_COUNT_PER_NOTE) {
                "Card routing ordinal is outside the provider contract"
            }
        }
    }

    data class DeleteNote(val noteId: Long) : AnkiProviderMutationCommand {
        init {
            require(noteId > 0L) { "Note delete requires a positive note ID" }
        }
    }
}

internal data class DeckCreateReceipt(
    val deckId: Long,
    val contentUri: String,
)

internal data class MediaInsertReceipt(
    val actualFilename: String,
    val fileUri: String,
)

internal data class NoteInsertReceipt(
    val noteId: Long,
    val contentUri: String,
)

internal data object CardDeckUpdateReceipt

/** Accepts only the exact pinned AnkiDroid deck-item URI shape. */
internal object DeckCreateReceiptValidator {
    fun validate(raw: String?): DeckCreateReceipt? {
        val item = validateCanonicalPositiveItemUri(raw, DECK_COLLECTION_URI) ?: return null
        return DeckCreateReceipt(item.id, item.uri)
    }

    internal const val ANKIDROID_AUTHORITY = "com.ichi2.anki.flashcards"
    internal const val DECK_COLLECTION_URI = "content://$ANKIDROID_AUTHORITY/decks"
}

/**
 * Accepts only the pinned AnkiDroid media receipt shape.
 *
 * AnkiDroid 2.24's provider returns `Uri.fromFile(File(actualFilename))`, and its vendored
 * `AddContentApi` interprets the returned path minus the leading slash as the filename. The
 * request still targets [MEDIA_COLLECTION_URI]; the returned receipt is deliberately a
 * single-segment `file:///actualFilename` URI.
 */
internal object MediaInsertReceiptValidator {
    fun validate(raw: String?): MediaInsertReceipt? {
        if (raw == null || !raw.startsWith(FILE_ITEM_PREFIX)) return null
        val rawSegment = raw.substring(FILE_ITEM_PREFIX.length)
        val actualFilename = decodeCanonicalAndroidPathSegment(rawSegment) ?: return null
        if (!isSafeMediaBasename(actualFilename)) return null
        val parsed = parseExactFileUri(raw) ?: return null
        if (parsed.rawPath != "/$rawSegment") return null
        return MediaInsertReceipt(actualFilename, raw)
    }

    internal const val MEDIA_COLLECTION_URI =
        "content://${DeckCreateReceiptValidator.ANKIDROID_AUTHORITY}/media"
    private const val FILE_ITEM_PREFIX = "file:///"
}

/** Accepts only the exact pinned AnkiDroid positive note-item URI shape. */
internal object NoteInsertReceiptValidator {
    fun validate(raw: String?): NoteInsertReceipt? {
        val item = validateCanonicalPositiveItemUri(raw, NOTE_COLLECTION_URI) ?: return null
        return NoteInsertReceipt(item.id, item.uri)
    }

    internal const val NOTE_COLLECTION_URI =
        "content://${DeckCreateReceiptValidator.ANKIDROID_AUTHORITY}/notes"
}

/** Only one affected card is an attributable provider receipt. */
internal object CardDeckUpdateReceiptValidator {
    fun validate(affectedCount: Int): CardDeckUpdateReceipt? =
        CardDeckUpdateReceipt.takeIf { affectedCount == 1 }
}

private data class PositiveItemUri(
    val id: Long,
    val uri: String,
)

private fun validateCanonicalPositiveItemUri(
    raw: String?,
    collectionUri: String,
): PositiveItemUri? {
    if (raw == null) return null
    val prefix = "$collectionUri/"
    if (!raw.startsWith(prefix)) return null
    val idToken = raw.substring(prefix.length)
    if (!CANONICAL_POSITIVE_DECIMAL.matches(idToken)) return null
    val id = idToken.toLongOrNull()?.takeIf { it > 0L } ?: return null
    if (raw != "$prefix$id") return null
    val parsed = parseExactContentUri(raw) ?: return null
    if (parsed.rawPath != URI(collectionUri).rawPath + "/$idToken") return null
    return PositiveItemUri(id, raw)
}

private fun parseExactContentUri(raw: String): URI? {
    val parsed =
        try {
            URI(raw)
        } catch (_: Exception) {
            return null
        }
    if (
        parsed.scheme != CONTENT_SCHEME ||
            parsed.rawAuthority != DeckCreateReceiptValidator.ANKIDROID_AUTHORITY ||
            parsed.rawQuery != null ||
            parsed.rawFragment != null ||
            parsed.rawUserInfo != null ||
            parsed.port != -1
    ) {
        return null
    }
    return parsed
}

private fun parseExactFileUri(raw: String): URI? {
    val parsed =
        try {
            URI(raw)
        } catch (_: Exception) {
            return null
        }
    if (
        parsed.scheme != FILE_SCHEME ||
            parsed.isOpaque ||
            parsed.rawAuthority != null ||
            parsed.rawQuery != null ||
            parsed.rawFragment != null ||
            parsed.rawUserInfo != null ||
            parsed.port != -1
    ) {
        return null
    }
    return parsed
}

private fun requireCanonicalContentSource(raw: String) {
    val stats =
        try {
            AnkiValidators.strictStats(raw, "provider media source URI")
        } catch (error: AnkiProtocolException) {
            throw IllegalArgumentException("Media source URI is not valid Unicode", error)
        }
    require(stats.utf8Bytes <= AnkiLimitsV1.StoreMedia.SOURCE_PATH_MAX_UTF8_BYTES) {
        "Media source URI exceeds the provider contract"
    }
    require(stats.scalarCount <= AnkiLimitsV1.StoreMedia.SOURCE_PATH_MAX_CODE_POINTS) {
        "Media source URI exceeds the provider contract"
    }
    val parsed =
        try {
            URI(raw)
        } catch (error: Exception) {
            throw IllegalArgumentException("Media source URI is malformed", error)
        }
    require(
        parsed.scheme == CONTENT_SCHEME &&
            !parsed.rawAuthority.isNullOrEmpty() &&
            parsed.rawUserInfo == null &&
            parsed.port == -1 &&
            parsed.rawQuery == null &&
            parsed.rawFragment == null &&
            !parsed.rawPath.isNullOrEmpty() &&
            parsed.rawPath != "/" &&
            '\\' !in raw,
    ) { "Media source must be a canonical content URI" }
}

private fun requireRawProviderText(
    value: String,
    maxUtf8Bytes: Int,
    label: String,
) {
    val stats =
        try {
            AnkiValidators.strictStats(value, label)
        } catch (error: AnkiProtocolException) {
            throw IllegalArgumentException("$label is not valid Unicode", error)
        }
    require(stats.utf8Bytes <= maxUtf8Bytes) { "$label exceeds the provider contract" }
}

private fun isSafeMediaBasename(value: String): Boolean =
    try {
        AnkiValidators.validateMediaBasename(value)
        true
    } catch (_: AnkiProtocolException) {
        false
    }

internal fun requireSafeCanonicalMediaName(
    value: String,
    label: String,
    minimumScalarCount: Int,
) {
    val stats =
        try {
            AnkiValidators.strictStats(value, label)
        } catch (error: AnkiProtocolException) {
            throw IllegalArgumentException("$label is not valid Unicode", error)
        }
    require(
        stats.scalarCount in minimumScalarCount..AnkiLimitsV1.StoreMedia.FILENAME_MAX_CODE_POINTS &&
            stats.utf8Bytes <= AnkiLimitsV1.StoreMedia.FILENAME_MAX_UTF8_BYTES &&
            value != "." &&
            value != ".." &&
            value.none(MEDIA_FILENAME_FORBIDDEN::contains) &&
            UnicodeContractV151.isNfc(value) &&
            !UnicodeContractV151.hasLeadingOrTrailingPythonWhitespace(value) &&
            !containsCategoryC(value),
    ) { "$label is not a safe canonical filename" }
}

private fun containsCategoryC(value: String): Boolean {
    var index = 0
    while (index < value.length) {
        val first = value[index].code
        val codePoint =
            if (first in 0xD800..0xDBFF) {
                val second = value[index + 1].code
                index += 2
                0x10000 + ((first - 0xD800) shl 10) + (second - 0xDC00)
            } else {
                index += 1
                first
            }
        if (UnicodeContractV151.isCategoryC(codePoint)) return true
    }
    return false
}

private fun decodeCanonicalAndroidPathSegment(raw: String): String? {
    if (raw.isEmpty() || raw.any { it.code > 0x7f }) return null
    val bytes = ByteArrayOutputStream(raw.length)
    var index = 0
    while (index < raw.length) {
        val character = raw[index]
        if (character == '%') {
            if (index + 2 >= raw.length) return null
            val high = raw[index + 1].digitToIntOrNull(16) ?: return null
            val low = raw[index + 2].digitToIntOrNull(16) ?: return null
            bytes.write((high shl 4) or low)
            index += 3
        } else {
            bytes.write(character.code)
            index += 1
        }
    }
    val decoded =
        try {
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray()))
                .toString()
        } catch (_: Exception) {
            return null
        }
    return decoded.takeIf { encodeCanonicalAndroidPathSegment(it) == raw }
}

private fun encodeCanonicalAndroidPathSegment(value: String): String {
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    return buildString(bytes.size) {
        for (byte in bytes) {
            val unsigned = byte.toInt() and 0xff
            if (isAndroidUriUnescaped(unsigned)) {
                append(unsigned.toChar())
            } else {
                append('%')
                append(HEX[unsigned ushr 4])
                append(HEX[unsigned and 0x0f])
            }
        }
    }
}

private fun isAndroidUriUnescaped(value: Int): Boolean =
    value in 'a'.code..'z'.code ||
        value in 'A'.code..'Z'.code ||
        value in '0'.code..'9'.code ||
        value.toChar() in ANDROID_URI_UNESCAPED_PUNCTUATION

private const val CONTENT_SCHEME = "content"
private const val FILE_SCHEME = "file"
private const val FIELD_SEPARATOR = '\u001f'
private val CANONICAL_POSITIVE_DECIMAL = Regex("[1-9][0-9]{0,18}")
private val MEDIA_FILENAME_FORBIDDEN = setOf('/', '\\', '<', '>', '[', ']', ':', '"')
private val ANDROID_URI_UNESCAPED_PUNCTUATION = setOf('_', '-', '!', '.', '~', '\'', '(', ')', '*')
private const val HEX = "0123456789ABCDEF"
