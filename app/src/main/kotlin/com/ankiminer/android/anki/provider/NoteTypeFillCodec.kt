package com.ankiminer.android.anki.provider

import com.ankiminer.android.data.settings.CardType
import com.ankiminer.android.data.settings.PitchCategoryFormat
import com.ankiminer.android.engine.PyBridge
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonFactoryBuilder
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.core.json.JsonWriteFeature
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A community note type "Fill in automatically" recognised by its field names (desktop
 * `note_presets.NotePreset`: Lapis, Kiku, Senren, Anki Miner Note).
 */
internal data class NoteTypePreset(
    val id: String,
    val name: String,
    val pitchCategoryFormat: PitchCategoryFormat,
    /** Every card mode's marker field; `""` where the note type has no such card. */
    val cardTypeMarkerFields: Map<CardType, String>,
    /** The card modes the note type renders. No mode at all is always supported. */
    val supportedCardTypes: Set<CardType>,
    /** The note type's cards rely on a bold target word, so applying it turns that on. */
    val boldTargetInSentence: Boolean,
)

/**
 * The bridge's `anki.notetype.fill` answer for one note type (desktop `NoteTypeFill`).
 *
 * With a [preset], [fields] is its whole map and `""` is an answer, not a gap. Without one it is the
 * keyword pass, which Android runs itself ([AnkiFieldMapPolicy.remap]). [extraFields] holds the
 * mining language's own card fields that matched by placeholder or alias.
 */
internal data class NoteTypeFill(
    val preset: NoteTypePreset?,
    val fields: Map<String, String>,
    val extraFields: Map<String, String>,
)

/** Asks the engine what "Fill in automatically" proposes. A failure is a [Result], never a throw. */
internal fun interface NoteTypeFillSource {
    suspend fun fill(
        fieldNames: List<String>,
        language: String,
    ): Result<NoteTypeFill>
}

/** Off the main thread, as every bridge dispatch must be; the call waits for Python to start. */
internal class BridgeNoteTypeFillSource(
    private val bridge: PyBridge,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : NoteTypeFillSource {
    override suspend fun fill(
        fieldNames: List<String>,
        language: String,
    ): Result<NoteTypeFill> =
        withContext(dispatcher) {
            runCatching {
                val raw = bridge.dispatch(NoteTypeFillCodec.encodeRequest(fieldNames, language), null)
                NoteTypeFillCodec.decodeResult(raw, fieldNames)
            }
        }
}

internal class NoteTypeFillException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/** Strict codec for `anki.notetype.fill` (schemas/note-type-fill.schema.json). */
internal object NoteTypeFillCodec {
    private const val MAX_RESULT_UTF8_BYTES = 64 * 1024
    private const val REQUEST_TYPE = "anki.notetype.fill"
    private const val RESULT_TYPE = "anki.notetype.fill.result"
    private val logicalKeyPattern = Regex("[a-z][a-z0-9_]*")
    private val languagePattern = Regex("[a-z]{2,3}")
    private val engineFieldKeys = AnkiFieldKeys.ALL.toSet()

    private val factory: JsonFactory =
        JsonFactoryBuilder()
            .streamReadConstraints(
                StreamReadConstraints.builder()
                    .maxDocumentLength(MAX_RESULT_UTF8_BYTES.toLong())
                    .maxNestingDepth(8)
                    .maxNumberLength(16)
                    .maxNameLength(256)
                    .build(),
            ).enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(JsonWriteFeature.COMBINE_UNICODE_SURROGATES_IN_UTF8)
            .disable(JsonWriteFeature.ESCAPE_NON_ASCII)
            .also { builder -> JsonReadFeature.entries.forEach { builder.disable(it) } }
            .build()

    fun encodeRequest(
        fieldNames: List<String>,
        language: String,
    ): String {
        require(languagePattern.matches(language)) { "language is invalid" }
        val output = ByteArrayOutputStream()
        factory.createGenerator(output).use { generator ->
            generator.writeStartObject()
            generator.writeNumberField("schemaVersion", 1)
            generator.writeStringField("type", REQUEST_TYPE)
            generator.writeObjectFieldStart("payload")
            generator.writeArrayFieldStart("fieldNames")
            fieldNames.forEach(generator::writeString)
            generator.writeEndArray()
            generator.writeStringField("language", language)
            generator.writeEndObject()
            generator.writeEndObject()
        }
        return output.toString(StandardCharsets.UTF_8.name())
    }

    /** The answer for [fieldNames]; a bridge error or anything off-contract throws [NoteTypeFillException]. */
    fun decodeResult(
        raw: String,
        fieldNames: List<String>,
    ): NoteTypeFill {
        val envelope = asObject(parse(raw), "envelope")
        exact(envelope, setOf("schemaVersion", "type", "payload"), "envelope")
        if (envelope["schemaVersion"] != 1L) fail("unsupported schemaVersion")
        when (val type = envelope["type"]) {
            RESULT_TYPE -> Unit
            "bridge.error" -> fail("the bridge refused the fill: ${asObject(envelope["payload"], "error")["code"]}")
            else -> fail("unexpected message type: $type")
        }
        val payload = asObject(envelope["payload"], "payload")
        exact(payload, setOf("preset", "fields", "extraFields"), "payload")
        val known = fieldNames.toSet()
        val fields = mapping(payload["fields"], "fields", known)
        if (fields.keys != engineFieldKeys) fail("fields must answer every engine key")
        return NoteTypeFill(
            preset = payload["preset"]?.let { preset(asObject(it, "preset"), known) },
            fields = fields,
            extraFields =
                mapping(payload["extraFields"], "extraFields", known).also { extras ->
                    if (extras.values.any(String::isEmpty)) fail("an extra field match is empty")
                },
        )
    }

    private fun preset(
        value: Map<String, Any?>,
        known: Set<String>,
    ): NoteTypePreset {
        exact(
            value,
            setOf("id", "name", "pitchCategoryFormat", "cardTypeMarkerFields", "supportedCardTypes", "boldTargetInSentence"),
            "preset",
        )
        val markers = asObject(value["cardTypeMarkerFields"], "cardTypeMarkerFields")
        exact(markers, CardType.entries.mapTo(mutableSetOf(), CardType::wireValue), "cardTypeMarkerFields")
        val supported = asList(value["supportedCardTypes"], "supportedCardTypes").map { text(it, "card type") }
        if (supported.toSet().size != supported.size || "" !in supported) {
            fail("supportedCardTypes must be distinct and include the disabled state")
        }
        val format = text(value["pitchCategoryFormat"], "pitchCategoryFormat")
        return NoteTypePreset(
            id = text(value["id"], "id").also { if (!logicalKeyPattern.matches(it)) fail("preset id is invalid") },
            name = text(value["name"], "name").also { if (it.isEmpty()) fail("preset name is empty") },
            pitchCategoryFormat =
                PitchCategoryFormat.entries.singleOrNull { it.wireValue == format }
                    ?: fail("pitchCategoryFormat is invalid"),
            cardTypeMarkerFields =
                CardType.entries.associateWith { type ->
                    destination(markers[type.wireValue], "marker field", known)
                },
            supportedCardTypes =
                supported.filter(String::isNotEmpty).mapTo(mutableSetOf()) { wire ->
                    CardType.fromWire(wire) ?: fail("card type is invalid")
                },
            boldTargetInSentence =
                value["boldTargetInSentence"] as? Boolean ?: fail("boldTargetInSentence must be a boolean"),
        )
    }

    private fun mapping(
        value: Any?,
        context: String,
        known: Set<String>,
    ): Map<String, String> =
        asObject(value, context).entries.associate { (key, field) ->
            if (!logicalKeyPattern.matches(key)) fail("$context key is invalid")
            key to destination(field, "$context value", known)
        }

    /** `""`, or a field the note type has: a proposal can never name a field the note type lacks. */
    private fun destination(
        value: Any?,
        context: String,
        known: Set<String>,
    ): String = text(value, context).also { if (it.isNotEmpty() && it !in known) fail("$context names an unknown field") }

    private fun parse(raw: String): Any? {
        val bytes = raw.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > MAX_RESULT_UTF8_BYTES) fail("result exceeds its UTF-8 limit")
        try {
            factory.createParser(bytes).use { parser ->
                val value = readValue(parser, parser.nextToken())
                if (parser.nextToken() != null) fail("result contains trailing JSON")
                return value
            }
        } catch (failure: JsonProcessingException) {
            fail("result is not strict JSON", failure)
        } catch (failure: IOException) {
            fail("result could not be decoded", failure)
        }
    }

    private fun readValue(
        parser: JsonParser,
        token: JsonToken?,
    ): Any? =
        when (token) {
            JsonToken.START_OBJECT ->
                linkedMapOf<String, Any?>().also { values ->
                    while (parser.nextToken() == JsonToken.FIELD_NAME) {
                        val name = parser.currentName()
                        values[name] = readValue(parser, parser.nextToken())
                    }
                }
            JsonToken.START_ARRAY ->
                buildList {
                    var next = parser.nextToken()
                    while (next != JsonToken.END_ARRAY) {
                        add(readValue(parser, next))
                        next = parser.nextToken()
                    }
                }
            JsonToken.VALUE_STRING -> parser.text
            JsonToken.VALUE_NUMBER_INT -> parser.longValue
            JsonToken.VALUE_TRUE -> true
            JsonToken.VALUE_FALSE -> false
            JsonToken.VALUE_NULL -> null
            else -> fail("unexpected JSON token: $token")
        }

    @Suppress("UNCHECKED_CAST")
    private fun asObject(
        value: Any?,
        context: String,
    ): Map<String, Any?> = value as? Map<String, Any?> ?: fail("$context must be an object")

    private fun asList(
        value: Any?,
        context: String,
    ): List<Any?> = value as? List<Any?> ?: fail("$context must be an array")

    private fun text(
        value: Any?,
        context: String,
    ): String = value as? String ?: fail("$context must be a string")

    private fun exact(
        value: Map<String, Any?>,
        keys: Set<String>,
        context: String,
    ) {
        if (value.keys != keys) fail("$context has missing or unknown fields")
    }

    private fun fail(
        message: String,
        cause: Throwable? = null,
    ): Nothing = throw NoteTypeFillException(message, cause)
}
