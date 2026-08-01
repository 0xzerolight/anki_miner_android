package com.ankiminer.android.anki.protocol

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Stable replay identity for one validated Anki callback request. */
internal class AnkiRequestDigest private constructor(
    val digestVersion: Int,
    canonicalBytes: ByteArray,
    val sha256: String,
) {
    private val encoded = canonicalBytes.copyOf()

    val canonicalBytes: ByteArray
        get() = encoded.copyOf()

    override fun equals(other: Any?): Boolean =
        other is AnkiRequestDigest &&
            digestVersion == other.digestVersion &&
            sha256 == other.sha256 &&
            encoded.contentEquals(other.encoded)

    override fun hashCode(): Int {
        var result = digestVersion
        result = 31 * result + encoded.contentHashCode()
        result = 31 * result + sha256.hashCode()
        return result
    }

    override fun toString(): String = "AnkiRequestDigest(v$digestVersion:$sha256)"

    internal companion object {
        const val VERSION = 1
        const val DOMAIN = "com.ankiminer.android.anki.request"

        fun compute(request: AnkiRequest): AnkiRequestDigest {
            AnkiValidators.validateRequest(request)
            val bytes = CanonicalRequestWriter().encode(request)
            return AnkiRequestDigest(
                digestVersion = VERSION,
                canonicalBytes = bytes,
                sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).lowerHex(),
            )
        }
    }
}

private class CanonicalRequestWriter {
    private val output = ByteArrayOutputStream()

    fun encode(request: AnkiRequest): ByteArray {
        ascii("{\"domain\":")
        string(AnkiRequestDigest.DOMAIN)
        ascii(",\"digestVersion\":${AnkiRequestDigest.VERSION},\"operation\":")
        string(request.operation.wireName)
        ascii(",\"request\":{")
        when (request) {
            is VerifyTargetRequest -> writeVerifyTarget(request)
            is ScanFirstFieldsRequest -> writeScanFirstFields(request)
            is StoreMediaRequest -> writeStoreMedia(request)
            is CreateNotesRequest -> writeCreateNotes(request)
            is ReleaseRunStateRequest -> writeReleaseRunState(request)
        }
        ascii("}}")
        return output.toByteArray()
    }

    private fun writeVerifyTarget(request: VerifyTargetRequest) {
        stringField("runId", request.runId)
        stringField("requestId", request.requestId, leadingComma = true)
        stringField("deckName", request.deckName, leadingComma = true)
        stringField("modelName", request.modelName, leadingComma = true)
        fieldName("requiredFields", leadingComma = true)
        stringArray(request.requiredFields)
    }

    private fun writeScanFirstFields(request: ScanFirstFieldsRequest) {
        stringField("runId", request.runId)
        stringField("requestId", request.requestId, leadingComma = true)
        fieldName("scope", leadingComma = true)
        ascii("{")
        when (val scope = request.scope) {
            is KnownVocabularyScope -> {
                stringField("kind", "knownVocabulary")
                fieldName("excludedDecks", leadingComma = true)
                stringArray(scope.excludedDecks)
                fieldName("cursor", leadingComma = true)
                if (scope.cursor == null) {
                    ascii("null")
                } else {
                    ascii("{")
                    longField("ordinal", scope.cursor.ordinal)
                    stringField("token", scope.cursor.token, leadingComma = true)
                    ascii("}")
                }
            }

            is DuplicateScanScope -> {
                stringField("kind", "duplicates")
                stringField("modelName", scope.modelName, leadingComma = true)
                stringField("firstFieldName", scope.firstFieldName, leadingComma = true)
                nullableStringField("deckName", scope.deckName, leadingComma = true)
                fieldName("candidates", leadingComma = true)
                ascii("[")
                scope.candidates.forEachIndexed { index, candidate ->
                    if (index > 0) ascii(",")
                    ascii("{")
                    stringField("key", candidate.key)
                    stringField("firstField", candidate.firstField, leadingComma = true)
                    ascii("}")
                }
                ascii("]")
                fieldName("occurrences", leadingComma = true)
                intArray(scope.occurrences)
                nullableStringField(
                    "invalidateBaselineToken",
                    scope.invalidateBaselineToken,
                    leadingComma = true,
                )
            }
        }
        ascii("}")
    }

    private fun writeStoreMedia(request: StoreMediaRequest) {
        stringField("runId", request.runId)
        stringField("requestId", request.requestId, leadingComma = true)
        fieldName("assets", leadingComma = true)
        ascii("[")
        request.assets.forEachIndexed { index, asset ->
            if (index > 0) ascii(",")
            ascii("{")
            stringField("assetId", asset.assetId)
            stringField("sourcePath", asset.sourcePath, leadingComma = true)
            stringField("preferredName", asset.preferredName, leadingComma = true)
            stringField("requestedFilename", asset.requestedFilename, leadingComma = true)
            stringField("purpose", asset.purpose.wireName, leadingComma = true)
            stringField("mediaKind", asset.mediaKind.wireName, leadingComma = true)
            longField("expectedSizeBytes", asset.expectedSizeBytes, leadingComma = true)
            stringField("expectedSha256", asset.expectedSha256, leadingComma = true)
            ascii("}")
        }
        ascii("]")
    }

    private fun writeCreateNotes(request: CreateNotesRequest) {
        stringField("runId", request.runId)
        stringField("requestId", request.requestId, leadingComma = true)
        stringField("deckName", request.deckName, leadingComma = true)
        stringField("modelName", request.modelName, leadingComma = true)
        stringField("firstFieldName", request.firstFieldName, leadingComma = true)
        stringField("baselineToken", request.baselineToken, leadingComma = true)
        fieldName("duplicateScope", leadingComma = true)
        when (request.duplicateScope) {
            CollectionCreateDuplicateScope -> ascii("{\"kind\":\"collection\"}")
        }
        fieldName("notes", leadingComma = true)
        ascii("[")
        request.notes.forEachIndexed { index, note ->
            if (index > 0) ascii(",")
            writeCreateNote(note)
        }
        ascii("]")
    }

    private fun writeCreateNote(note: CreateNote) {
        ascii("{")
        stringField("clientNoteId", note.clientNoteId)
        fieldName("fields", leadingComma = true)
        stringMap(note.fields)
        fieldName("tags", leadingComma = true)
        stringArray(note.tags)
        fieldName("duplicateCandidate", leadingComma = true)
        ascii("{")
        stringField("key", note.duplicateCandidate.key)
        stringField("firstField", note.duplicateCandidate.firstField, leadingComma = true)
        intField("occurrence", note.duplicateCandidate.occurrence, leadingComma = true)
        ascii("}")
        fieldName("mediaBindings", leadingComma = true)
        ascii("[")
        note.mediaBindings.forEachIndexed { index, binding ->
            if (index > 0) ascii(",")
            ascii("{")
            stringField("assetId", binding.assetId)
            stringField("actualFilename", binding.actualFilename, leadingComma = true)
            ascii("}")
        }
        ascii("]}")
    }

    private fun writeReleaseRunState(request: ReleaseRunStateRequest) {
        stringField("runId", request.runId)
        stringField("requestId", request.requestId, leadingComma = true)
        fieldName("acknowledgeTerminalResponses", leadingComma = true)
        ascii(if (request.acknowledgeTerminalResponses) "true" else "false")
    }

    private fun stringMap(values: Map<String, String>) {
        val sorted =
            values.entries
                .map { entry -> EncodedMapEntry(entry.key.strictUtf8(), entry.key, entry.value) }
                .sortedWith { left, right -> compareUnsigned(left.keyBytes, right.keyBytes) }
        ascii("{")
        sorted.forEachIndexed { index, entry ->
            if (index > 0) ascii(",")
            string(entry.key)
            ascii(":")
            string(entry.value)
        }
        ascii("}")
    }

    private fun stringArray(values: List<String>) {
        ascii("[")
        values.forEachIndexed { index, value ->
            if (index > 0) ascii(",")
            string(value)
        }
        ascii("]")
    }

    private fun intArray(values: List<Int>) {
        ascii("[")
        values.forEachIndexed { index, value ->
            if (index > 0) ascii(",")
            ascii(value.toString())
        }
        ascii("]")
    }

    private fun stringField(name: String, value: String, leadingComma: Boolean = false) {
        fieldName(name, leadingComma)
        string(value)
    }

    private fun nullableStringField(name: String, value: String?, leadingComma: Boolean = false) {
        fieldName(name, leadingComma)
        if (value == null) ascii("null") else string(value)
    }

    private fun longField(name: String, value: Long, leadingComma: Boolean = false) {
        fieldName(name, leadingComma)
        ascii(value.toString())
    }

    private fun intField(name: String, value: Int, leadingComma: Boolean = false) {
        fieldName(name, leadingComma)
        ascii(value.toString())
    }

    private fun fieldName(name: String, leadingComma: Boolean = false) {
        if (leadingComma) ascii(",")
        string(name)
        ascii(":")
    }

    private fun string(value: String) {
        val bytes = value.strictUtf8()
        ascii("\"")
        var index = 0
        while (index < bytes.size) {
            val unsigned = bytes[index].toInt() and 0xff
            when {
                unsigned == 0x22 -> ascii("\\\"")
                unsigned == 0x5c -> ascii("\\\\")
                unsigned <= 0x1f -> {
                    ascii("\\u00")
                    output.write(HEX[unsigned ushr 4].code)
                    output.write(HEX[unsigned and 0x0f].code)
                }

                else -> output.write(unsigned)
            }
            index += 1
        }
        ascii("\"")
    }

    private fun ascii(value: String) {
        check(value.all { it.code <= 0x7f }) { "canonical JSON structural text must be ASCII" }
        output.write(value.toByteArray(StandardCharsets.US_ASCII))
    }

    private data class EncodedMapEntry(
        val keyBytes: ByteArray,
        val key: String,
        val value: String,
    )

    private companion object {
        const val HEX = "0123456789abcdef"

        fun compareUnsigned(left: ByteArray, right: ByteArray): Int {
            val shared = minOf(left.size, right.size)
            for (index in 0 until shared) {
                val comparison =
                    (left[index].toInt() and 0xff).compareTo(right[index].toInt() and 0xff)
                if (comparison != 0) return comparison
            }
            return left.size.compareTo(right.size)
        }
    }
}

private fun String.strictUtf8(): ByteArray {
    AnkiValidators.strictStats(this, "canonical request string")
    return toByteArray(StandardCharsets.UTF_8)
}

private fun ByteArray.lowerHex(): String {
    val alphabet = "0123456789abcdef"
    return buildString(size * 2) {
        for (byte in this@lowerHex) {
            val unsigned = byte.toInt() and 0xff
            append(alphabet[unsigned ushr 4])
            append(alphabet[unsigned and 0x0f])
        }
    }
}
