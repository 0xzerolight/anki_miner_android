package com.ankiminer.android.anki

import com.ankiminer.android.anki.protocol.AnkiJsonCodec
import com.ankiminer.android.anki.protocol.AnkiOperation
import com.ankiminer.android.anki.protocol.AnkiProtocolException
import com.ankiminer.android.anki.protocol.AnkiRequestDigest
import com.fasterxml.jackson.core.JsonFactoryBuilder
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.StreamReadFeature
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnkiRequestDigestTest {
    @Test
    fun sharedRequestDigestVectors() {
        for (vector in vectors) {
            val operation = AnkiOperation.entries.single { it.wireName == vector.operation }
            if (vector.rejectCategory != null) {
                val error =
                    runCatching {
                        AnkiRequestDigest.compute(AnkiJsonCodec.decodeRequest(vector.raw, operation))
                    }.exceptionOrNull()
                assertTrue(vector.id, error is AnkiProtocolException)
                assertEquals(
                    vector.id,
                    vector.rejectCategory,
                    (error as AnkiProtocolException).category.wireName,
                )
                continue
            }

            val digest = AnkiRequestDigest.compute(AnkiJsonCodec.decodeRequest(vector.raw, operation))
            val expected = requireNotNull(vector.canonical).toByteArray(StandardCharsets.UTF_8)
            assertEquals(vector.id, AnkiRequestDigest.VERSION, digest.digestVersion)
            assertArrayEquals(vector.id, expected, digest.canonicalBytes)
            assertEquals(vector.id, vector.sha256, digest.sha256)
            assertEquals(vector.id, sha256(expected), digest.sha256)
            assertTrue(
                vector.id,
                String(digest.canonicalBytes, StandardCharsets.UTF_8).startsWith(
                    "{\"domain\":\"${AnkiRequestDigest.DOMAIN}\",\"digestVersion\":1,",
                ),
            )
            assertNotEquals(
                vector.id,
                sha256(vector.raw.toByteArray(StandardCharsets.UTF_8)),
                digest.sha256,
            )
        }
    }

    @Test
    fun wireOrderMapOrderAndNumericAliasesDoNotChangeIdentity() {
        val digests = acceptedDigests()
        assertEquals(digests.getValue("scan_known_integer"), digests.getValue("scan_known_float"))
        assertEquals(digests.getValue("scan_known_integer"), digests.getValue("scan_known_exponent"))
        assertEquals(digests.getValue("create_fields_reverse"), digests.getValue("create_fields_forward"))
    }

    @Test
    fun nullEmptyListOrderAndLeafChangesDoChangeIdentity() {
        val digests = acceptedDigests()
        assertNotEquals(digests.getValue("scan_known_integer"), digests.getValue("scan_known_null_cursor"))
        assertNotEquals(digests.getValue("verify_escaping"), digests.getValue("verify_empty_fields"))
        assertNotEquals(digests.getValue("verify_escaping"), digests.getValue("verify_reordered_fields"))
        assertNotEquals(digests.getValue("create_fields_reverse"), digests.getValue("create_empty_changed"))
        assertNotEquals(digests.getValue("store_assets_forward"), digests.getValue("store_assets_reversed"))
        assertNotEquals(digests.getValue("release_true"), digests.getValue("release_false"))
    }

    @Test
    fun sharedValidatedOneLeafMutationMatrix() {
        assertEquals(expectedMutationLeaves, mutations.mapTo(mutableSetOf()) { it.leaf })
        val materialized = materializeMutations()
        for (mutation in mutations) {
            val context = materialized.getValue(mutation.id)
            val raw = encodeJson(context.envelope)
            if (mutation.rejectCategory != null) {
                val error =
                    runCatching {
                        AnkiRequestDigest.compute(
                            AnkiJsonCodec.decodeRequest(raw, context.operation),
                        )
                    }.exceptionOrNull()
                assertTrue(mutation.id, error is AnkiProtocolException)
                assertEquals(
                    mutation.id,
                    mutation.rejectCategory,
                    (error as AnkiProtocolException).category.wireName,
                )
                continue
            }

            val digest =
                AnkiRequestDigest.compute(
                    AnkiJsonCodec.decodeRequest(raw, context.operation),
                )
            assertEquals(mutation.id, mutation.sha256, digest.sha256)
            assertEquals(mutation.id, sha256(digest.canonicalBytes), digest.sha256)

            val base = materialized.getValue(mutation.base)
            val baseDigest =
                AnkiRequestDigest.compute(
                    AnkiJsonCodec.decodeRequest(encodeJson(base.envelope), base.operation),
                )
            assertNotEquals(mutation.id, baseDigest.sha256, digest.sha256)
        }
    }

    @Test
    fun canonicalByteArrayIsDefensivelyCopied() {
        val vector = vectors.single { it.id == "release_true" }
        val operation = AnkiOperation.RELEASE_RUN_STATE
        val digest = AnkiRequestDigest.compute(AnkiJsonCodec.decodeRequest(vector.raw, operation))
        val first = digest.canonicalBytes
        first[0] = 0
        val second = digest.canonicalBytes
        assertFalse(first.contentEquals(second))
        assertEquals('{'.code.toByte(), second[0])
    }

    private fun acceptedDigests(): Map<String, String> =
        vectors
            .filter { it.rejectCategory == null }
            .associate { vector ->
                val operation = AnkiOperation.entries.single { it.wireName == vector.operation }
                vector.id to
                    AnkiRequestDigest.compute(
                        AnkiJsonCodec.decodeRequest(vector.raw, operation),
                    ).sha256
            }

    private fun materializeMutations(): Map<String, MaterializedRequest> {
        val result = linkedMapOf<String, MaterializedRequest>()
        for (vector in vectors) {
            if (vector.rejectCategory == null) {
                val operation = AnkiOperation.entries.single { it.wireName == vector.operation }
                result[vector.id] = MaterializedRequest(operation, parseJsonObject(vector.raw))
            }
        }
        for (mutation in mutations) {
            val base = result.getValue(mutation.base)
            result[mutation.id] =
                MaterializedRequest(
                    base.operation,
                    applyMutation(base.envelope, mutation),
                )
        }
        return result
    }

    private data class MaterializedRequest(
        val operation: AnkiOperation,
        val envelope: JsonObjectValue,
    )

    private data class Vector(
        val id: String,
        val operation: String,
        val raw: String,
        val canonical: String?,
        val sha256: String?,
        val rejectCategory: String?,
    )

    private data class Mutation(
        val id: String,
        val base: String,
        val leaf: String,
        val kind: MutationKind,
        val path: List<JsonPathPart>,
        val value: JsonValue,
        val sha256: String?,
        val rejectCategory: String?,
    )

    private enum class MutationKind(val wireName: String) {
        REPLACE("replace"),
        APPEND("append"),
        RENAME_KEY("renameKey"),
        REVERSE("reverse"),
    }

    private sealed interface JsonPathPart

    private data class JsonKey(val value: String) : JsonPathPart

    private data class JsonIndex(val value: Int) : JsonPathPart

    private sealed interface JsonValue

    private data class JsonObjectValue(
        val members: LinkedHashMap<String, JsonValue>,
    ) : JsonValue

    private data class JsonArrayValue(
        val items: MutableList<JsonValue>,
    ) : JsonValue

    private data class JsonStringValue(val value: String) : JsonValue

    private data class JsonNumberValue(val literal: String) : JsonValue

    private data class JsonBooleanValue(val value: Boolean) : JsonValue

    private data object JsonNullValue : JsonValue

    private companion object {
        private val fixtureFactory = JsonFactoryBuilder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()
        private val vectors: List<Vector> by lazy(::loadVectors)
        private val mutations: List<Mutation> by lazy(::loadMutations)
        private val expectedMutationLeaves =
            setOf(
                "verify.runId",
                "verify.requestId",
                "verify.deckName",
                "verify.modelName",
                "verify.requiredFields.item",
                "verify.requiredFields.append",
                "verify.requiredFields.order",
                "scan.runId",
                "scan.requestId",
                "scan.known.excludedDecks.item",
                "scan.known.excludedDecks.append",
                "scan.known.cursor.ordinal",
                "scan.known.cursor.token",
                "scan.known.cursor.nullable",
                "scan.duplicates.modelName",
                "scan.duplicates.firstFieldName",
                "scan.duplicates.deckName.nullable",
                "scan.duplicates.candidate.key",
                "scan.duplicates.candidate.firstField",
                "scan.duplicates.occurrences.list",
                "scan.duplicates.invalidateBaselineToken.nullable",
                "store.runId",
                "store.requestId",
                "store.assets.order",
                "store.asset.assetId",
                "store.asset.sourcePath",
                "store.asset.requestedFilename",
                "store.asset.mediaKind",
                "store.asset.expectedSizeBytes",
                "store.asset.expectedSha256",
                "store.assets.dictionary.append",
                "create.runId",
                "create.requestId",
                "create.deckName",
                "create.modelName",
                "create.firstFieldName",
                "create.baselineToken",
                "create.duplicateScope.variant",
                "create.notes.append",
                "create.note.clientNoteId",
                "create.note.fields.key",
                "create.note.fields.value",
                "create.note.tags.item",
                "create.note.tags.append",
                "create.note.duplicateCandidate.key",
                "create.note.duplicateCandidate.firstField",
                "create.note.duplicateCandidate.occurrence",
                "create.note.mediaBindings.append",
                "create.note.mediaBinding.assetId",
                "create.note.mediaBinding.actualFilename",
                "release.runId",
                "release.requestId",
                "release.acknowledgeTerminalResponses",
                "reject.cursor.longMin",
                "reject.cursor.longMinMinusOne",
                "reject.occurrence.intMaxPlusOne",
            )

        private fun loadVectors(): List<Vector> {
            val fixture = findProjectRoot().resolve("golden/bridge/anki-request-digest-v1.jsonl")
            val bytes = fixture.readBytes()
            check(bytes.isNotEmpty() && bytes.last() == '\n'.code.toByte()) { "digest fixture must end in a newline" }
            check(
                bytes.size < 3 ||
                    bytes[0] != 0xef.toByte() ||
                    bytes[1] != 0xbb.toByte() ||
                    bytes[2] != 0xbf.toByte(),
            ) {
                "digest fixture must not have a UTF-8 BOM"
            }
            val identifiers = mutableSetOf<String>()
            return fixture.readLines(StandardCharsets.UTF_8).mapIndexed { index, line ->
                check(line.isNotEmpty()) { "digest fixture line ${index + 1} is empty" }
                parseVector(line).also { vector ->
                    check(vector.id.matches(Regex("[a-z0-9]+(?:_[a-z0-9]+)*"))) { "invalid digest fixture ID" }
                    check(identifiers.add(vector.id)) { "duplicate digest fixture ID" }
                    check(AnkiOperation.entries.any { it.wireName == vector.operation }) { "unknown digest operation" }
                    if (vector.rejectCategory == null) {
                        check(vector.canonical != null && vector.sha256?.matches(Regex("[0-9a-f]{64}")) == true) {
                            "accepted digest fixture is incomplete"
                        }
                    } else {
                        check(vector.canonical == null && vector.sha256 == null) {
                            "rejected digest fixture has an output"
                        }
                    }
                }
            }
        }

        private fun loadMutations(): List<Mutation> {
            val fixture = findProjectRoot().resolve("golden/bridge/anki-request-digest-mutations-v1.jsonl")
            val bytes = fixture.readBytes()
            check(bytes.isNotEmpty() && bytes.last() == '\n'.code.toByte()) {
                "digest mutation fixture must end in a newline"
            }
            check(
                bytes.size < 3 ||
                    bytes[0] != 0xef.toByte() ||
                    bytes[1] != 0xbb.toByte() ||
                    bytes[2] != 0xbf.toByte(),
            ) {
                "digest mutation fixture must not have a UTF-8 BOM"
            }
            val identifiers = mutableSetOf<String>()
            val leaves = mutableSetOf<String>()
            return fixture.readLines(StandardCharsets.UTF_8).mapIndexed { index, line ->
                check(line.isNotEmpty()) { "digest mutation fixture line ${index + 1} is empty" }
                parseMutation(line).also { mutation ->
                    check(mutation.id.matches(Regex("[a-z0-9]+(?:_[a-z0-9]+)*"))) {
                        "invalid digest mutation fixture ID"
                    }
                    check(identifiers.add(mutation.id)) { "duplicate digest mutation fixture ID" }
                    check(leaves.add(mutation.leaf)) { "duplicate digest mutation leaf" }
                    if (mutation.rejectCategory == null) {
                        check(mutation.sha256?.matches(Regex("[0-9a-f]{64}")) == true) {
                            "accepted digest mutation fixture is incomplete"
                        }
                    } else {
                        check(mutation.sha256 == null) { "rejected digest mutation has a hash" }
                    }
                }
            }
        }

        private fun parseMutation(line: String): Mutation {
            val record = parseJsonObject(line)
            check(
                record.members.keys ==
                    setOf(
                        "id",
                        "base",
                        "leaf",
                        "kind",
                        "path",
                        "value",
                        "sha256",
                        "rejectCategory",
                    ),
            ) {
                "digest mutation fixture record has missing or unknown members"
            }
            val pathValue = record.members.getValue("path") as? JsonArrayValue
                ?: error("digest mutation path must be an array")
            check(pathValue.items.isNotEmpty()) { "digest mutation path is empty" }
            val path =
                pathValue.items.map { part ->
                    when (part) {
                        is JsonStringValue -> JsonKey(part.value)
                        is JsonNumberValue -> {
                            val index = part.literal.toIntOrNull()
                            check(index != null && index >= 0) { "digest mutation index is invalid" }
                            JsonIndex(index)
                        }
                        else -> error("digest mutation path part is invalid")
                    }
                }
            val kindWire = record.requiredString("kind")
            val kind = MutationKind.entries.singleOrNull { it.wireName == kindWire }
                ?: error("digest mutation kind is invalid")
            return Mutation(
                id = record.requiredString("id"),
                base = record.requiredString("base"),
                leaf = record.requiredString("leaf"),
                kind = kind,
                path = path,
                value = record.members.getValue("value"),
                sha256 = record.nullableString("sha256"),
                rejectCategory = record.nullableString("rejectCategory"),
            )
        }

        private fun parseJsonObject(raw: String): JsonObjectValue =
            fixtureFactory.createParser(raw).use { parser ->
                check(parser.nextToken() == JsonToken.START_OBJECT) { "JSON value must be an object" }
                val value = readJsonValue(parser) as JsonObjectValue
                check(parser.nextToken() == null) { "JSON value has trailing tokens" }
                value
            }

        private fun readJsonValue(parser: JsonParser): JsonValue =
            when (parser.currentToken()) {
                JsonToken.START_OBJECT -> {
                    val members = linkedMapOf<String, JsonValue>()
                    while (parser.nextToken() != JsonToken.END_OBJECT) {
                        check(parser.currentToken() == JsonToken.FIELD_NAME) { "invalid JSON object member" }
                        val name = parser.currentName()
                        check(parser.nextToken() != null) { "JSON object member has no value" }
                        check(members.put(name, readJsonValue(parser)) == null) { "duplicate JSON object member" }
                    }
                    JsonObjectValue(members)
                }
                JsonToken.START_ARRAY -> {
                    val items = mutableListOf<JsonValue>()
                    while (parser.nextToken() != JsonToken.END_ARRAY) {
                        check(parser.currentToken() != null) { "unterminated JSON array" }
                        items += readJsonValue(parser)
                    }
                    JsonArrayValue(items)
                }
                JsonToken.VALUE_STRING -> JsonStringValue(parser.text)
                JsonToken.VALUE_NUMBER_INT, JsonToken.VALUE_NUMBER_FLOAT -> JsonNumberValue(parser.text)
                JsonToken.VALUE_TRUE -> JsonBooleanValue(true)
                JsonToken.VALUE_FALSE -> JsonBooleanValue(false)
                JsonToken.VALUE_NULL -> JsonNullValue
                else -> error("unsupported JSON fixture value")
            }

        private fun JsonObjectValue.requiredString(name: String): String =
            (members.getValue(name) as? JsonStringValue)?.value
                ?: error("digest mutation $name must be a string")

        private fun JsonObjectValue.nullableString(name: String): String? =
            when (val value = members.getValue(name)) {
                is JsonStringValue -> value.value
                JsonNullValue -> null
                else -> error("digest mutation $name must be a string or null")
            }

        private fun applyMutation(
            envelope: JsonObjectValue,
            mutation: Mutation,
        ): JsonObjectValue {
            val result = envelope.deepCopy() as JsonObjectValue
            val payload = result.members.getValue("payload") as? JsonObjectValue
                ?: error("Anki request envelope has no object payload")
            when (mutation.kind) {
                MutationKind.APPEND -> {
                    val target = resolve(payload, mutation.path) as? JsonArrayValue
                        ?: error("append mutation target is not an array")
                    target.items += mutation.value.deepCopy()
                }
                MutationKind.REVERSE -> {
                    val target = resolve(payload, mutation.path) as? JsonArrayValue
                        ?: error("reverse mutation target is not an array")
                    target.items.reverse()
                }
                MutationKind.REPLACE -> replace(payload, mutation.path, mutation.value.deepCopy())
                MutationKind.RENAME_KEY -> renameKey(payload, mutation)
            }
            return result
        }

        private fun replace(
            payload: JsonObjectValue,
            path: List<JsonPathPart>,
            value: JsonValue,
        ) {
            val parent = resolve(payload, path.dropLast(1))
            when (val last = path.last()) {
                is JsonKey -> {
                    val objectParent = parent as? JsonObjectValue
                        ?: error("replace mutation parent is not an object")
                    check(last.value in objectParent.members) { "replace mutation key is absent" }
                    objectParent.members[last.value] = value
                }
                is JsonIndex -> {
                    val arrayParent = parent as? JsonArrayValue
                        ?: error("replace mutation parent is not an array")
                    arrayParent.items[last.value] = value
                }
            }
        }

        private fun renameKey(payload: JsonObjectValue, mutation: Mutation) {
            val parent = resolve(payload, mutation.path.dropLast(1)) as? JsonObjectValue
                ?: error("rename-key mutation parent is not an object")
            val oldKey = (mutation.path.last() as? JsonKey)?.value
                ?: error("rename-key mutation requires a key path")
            val newKey = (mutation.value as? JsonStringValue)?.value
                ?: error("rename-key mutation requires a string value")
            check(oldKey in parent.members && newKey !in parent.members) {
                "rename-key mutation is not one-to-one"
            }
            parent.members[newKey] = parent.members.remove(oldKey)!!
        }

        private fun resolve(root: JsonValue, path: List<JsonPathPart>): JsonValue {
            var current = root
            for (part in path) {
                current =
                    when (part) {
                        is JsonKey -> (current as JsonObjectValue).members.getValue(part.value)
                        is JsonIndex -> (current as JsonArrayValue).items[part.value]
                    }
            }
            return current
        }

        private fun JsonValue.deepCopy(): JsonValue =
            when (this) {
                is JsonObjectValue ->
                    JsonObjectValue(
                        members.entries.associateTo(linkedMapOf()) { (key, value) ->
                            key to value.deepCopy()
                        },
                    )
                is JsonArrayValue -> JsonArrayValue(items.mapTo(mutableListOf()) { it.deepCopy() })
                is JsonStringValue, is JsonNumberValue, is JsonBooleanValue, JsonNullValue -> this
            }

        private fun encodeJson(value: JsonValue): String {
            val output = ByteArrayOutputStream()
            fixtureFactory.createGenerator(output).use { generator -> writeJson(generator, value) }
            return output.toString(StandardCharsets.UTF_8)
        }

        private fun writeJson(generator: JsonGenerator, value: JsonValue) {
            when (value) {
                is JsonObjectValue -> {
                    generator.writeStartObject()
                    for ((name, member) in value.members) {
                        generator.writeFieldName(name)
                        writeJson(generator, member)
                    }
                    generator.writeEndObject()
                }
                is JsonArrayValue -> {
                    generator.writeStartArray()
                    value.items.forEach { writeJson(generator, it) }
                    generator.writeEndArray()
                }
                is JsonStringValue -> generator.writeString(value.value)
                is JsonNumberValue -> generator.writeNumber(value.literal)
                is JsonBooleanValue -> generator.writeBoolean(value.value)
                JsonNullValue -> generator.writeNull()
            }
        }

        private fun parseVector(line: String): Vector {
            var id: String? = null
            var operation: String? = null
            var raw: String? = null
            var canonical: String? = null
            var sha256: String? = null
            var rejectCategory: String? = null
            val seen = linkedSetOf<String>()
            fixtureFactory.createParser(line).use { parser ->
                check(parser.nextToken() == JsonToken.START_OBJECT) { "digest fixture record must be an object" }
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    check(parser.currentToken() == JsonToken.FIELD_NAME) { "invalid digest fixture member" }
                    val name = parser.currentName()
                    check(seen.add(name)) { "duplicate digest fixture member" }
                    val token = parser.nextToken()
                    fun nullableText(): String? =
                        when (token) {
                            JsonToken.VALUE_STRING -> parser.text
                            JsonToken.VALUE_NULL -> null
                            else -> error("digest fixture $name must be a string or null")
                        }
                    when (name) {
                        "id" -> id = nullableText() ?: error("digest fixture id is null")
                        "operation" -> operation = nullableText() ?: error("digest fixture operation is null")
                        "raw" -> raw = nullableText() ?: error("digest fixture raw is null")
                        "canonical" -> canonical = nullableText()
                        "sha256" -> sha256 = nullableText()
                        "rejectCategory" -> rejectCategory = nullableText()
                        else -> error("unknown digest fixture member $name")
                    }
                }
                check(parser.nextToken() == null) { "digest fixture record has trailing JSON" }
            }
            check(seen == setOf("id", "operation", "raw", "canonical", "sha256", "rejectCategory")) {
                "digest fixture record has missing members"
            }
            return Vector(id!!, operation!!, raw!!, canonical, sha256, rejectCategory)
        }

        private fun findProjectRoot(): File =
            generateSequence(
                File(requireNotNull(System.getProperty("user.dir"))).absoluteFile,
            ) { it.parentFile }
                .first { File(it, "golden/bridge/anki-protocol-v1.jsonl").isFile }

        private fun sha256(bytes: ByteArray): String {
            val alphabet = "0123456789abcdef"
            return buildString(64) {
                for (byte in MessageDigest.getInstance("SHA-256").digest(bytes)) {
                    val unsigned = byte.toInt() and 0xff
                    append(alphabet[unsigned ushr 4])
                    append(alphabet[unsigned and 0x0f])
                }
            }
        }
    }
}
