package com.ankiminer.android.anki.protocol

import com.ankiminer.android.anki.generated.AnkiLimitsV1
import com.ankiminer.android.anki.generated.UnicodeContractV151
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonFactoryBuilder
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.core.io.CharacterEscapes
import com.fasterxml.jackson.core.io.SerializedString
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.core.json.JsonWriteFeature
import java.io.IOException
import java.io.OutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal object AnkiJsonCodec {
    private val minimumSignedLong = BigInteger.valueOf(Long.MIN_VALUE)
    private val maximumSignedLong = BigInteger.valueOf(Long.MAX_VALUE)
    private const val POSITIVE_LONG_BOUNDARY_AS_DOUBLE = 9.223372036854776E18
    private const val NEGATIVE_LONG_BOUNDARY_AS_DOUBLE = -9.223372036854776E18
    private const val MAX_JSON_DEPTH = 128
    private const val MAX_JSON_TOKEN_COUNT = 2_000_000L
    private const val PLACEHOLDER_RUN_ID = "run_00000000000000000000000000000000"
    private const val PLACEHOLDER_REQUEST_ID = "anki_00000000000000000000000000000000"
    private val messageTypePattern = Regex("[a-z][a-z0-9]*(?:\\.[a-z][a-z0-9]*)+")

    private val factory: JsonFactory =
        JsonFactoryBuilder()
            .streamReadConstraints(
                StreamReadConstraints.builder()
                    .maxNestingDepth(MAX_JSON_DEPTH)
                    .maxDocumentLength(AnkiLimitsV1.ScanFirstFields.REQUEST_ENVELOPE_MAX_UTF8_BYTES.toLong())
                    .maxTokenCount(MAX_JSON_TOKEN_COUNT)
                    .maxNumberLength(AnkiLimitsV1.Wire.NUMERIC_TOKEN_MAX_CHARS)
                    .maxStringLength(AnkiLimitsV1.ScanFirstFields.REQUEST_ENVELOPE_MAX_UTF8_BYTES)
                    .maxNameLength(AnkiLimitsV1.Names.Deck.MAX_CODE_POINTS * 2)
                    .build(),
            )
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(JsonWriteFeature.COMBINE_UNICODE_SURROGATES_IN_UTF8)
            .disable(JsonWriteFeature.ESCAPE_NON_ASCII)
            .also { builder -> JsonReadFeature.entries.forEach { builder.disable(it) } }
            .characterEscapes(PythonCanonicalEscapes)
            .build()

    private object PythonCanonicalEscapes : CharacterEscapes() {
        private val escapeCodes =
            standardAsciiEscapesForJSON().also { codes ->
                for (codePoint in 0..0x1f) {
                    if (!usesShortEscape(codePoint)) {
                        codes[codePoint] = ESCAPE_CUSTOM
                    }
                }
            }
        private const val HEX = "0123456789abcdef"

        override fun getEscapeCodesForAscii(): IntArray = escapeCodes

        override fun getEscapeSequence(codePoint: Int): SerializedString? =
            if (codePoint in 0..0x1f && !usesShortEscape(codePoint)) {
                SerializedString(
                    "\\u00${HEX[(codePoint ushr 4) and 0x0f]}${HEX[codePoint and 0x0f]}",
                )
            } else {
                null
            }

        private fun usesShortEscape(codePoint: Int): Boolean =
            codePoint == 0x08 ||
                codePoint == 0x09 ||
                codePoint == 0x0a ||
                codePoint == 0x0c ||
                codePoint == 0x0d
    }

    fun decodeRequest(raw: String, operation: AnkiOperation): AnkiRequest =
        decodeRequest(
            raw = raw,
            expectedType = operation.requestType,
            operation = operation,
            maxUtf8Bytes = operation.requestEnvelopeMaxUtf8Bytes,
        )

    fun decodeRequest(
        raw: String,
        expectedType: String,
        operation: AnkiOperation,
        maxUtf8Bytes: Int,
    ): AnkiRequest {
        if (expectedType != operation.requestType) {
            fail(AnkiProtocolCategory.UNEXPECTED_MESSAGE_TYPE, "callback expected type does not match its operation")
        }
        if (maxUtf8Bytes != operation.requestEnvelopeMaxUtf8Bytes) {
            fail(AnkiProtocolCategory.LIMIT_MISMATCH, "callback envelope limit does not match its operation")
        }
        if (raw.startsWith('\uFEFF')) {
            fail(AnkiProtocolCategory.INVALID_JSON, "a leading byte-order mark is not JSON whitespace")
        }
        val bytes = strictUtf8(raw, maxUtf8Bytes, AnkiProtocolCategory.INPUT_TOO_LARGE)
        rejectOversizedJsonNumbers(raw)
        var headerReader: EnvelopeHeaderReader? = null
        var reader: RequestReader? = null
        try {
            factory.createParser(bytes).use { parser ->
                headerReader = EnvelopeHeaderReader(parser, expectedType)
                headerReader.validate()
            }
            factory.createParser(bytes).use { parser ->
                reader = RequestReader(parser, operation, expectedType)
                val request = reader.readEnvelope()
                AnkiValidators.validateRequest(request)
                return request
            }
        } catch (error: AnkiProtocolException) {
            throw error
                .attachIdentifiers(headerReader?.recoveredRunId, headerReader?.recoveredRequestId)
                .attachIdentifiers(reader?.recoveredRunId, reader?.recoveredRequestId)
        } catch (error: JsonParseException) {
            val duplicate = error.originalMessage.contains("Duplicate field", ignoreCase = true)
            val invalidSurrogate = error.originalMessage.contains("surrogate", ignoreCase = true)
            val category =
                when {
                    duplicate -> AnkiProtocolCategory.DUPLICATE_JSON_KEY
                    invalidSurrogate -> AnkiProtocolCategory.INVALID_UTF8
                    else -> AnkiProtocolCategory.INVALID_JSON
                }
            val message =
                when {
                    duplicate -> "bridge JSON contains a duplicate object key"
                    invalidSurrogate -> "bridge JSON contains an invalid Unicode scalar"
                    else -> "malformed bridge JSON"
                }
            throw AnkiProtocolException(category, message, error)
                .attachIdentifiers(headerReader?.recoveredRunId, headerReader?.recoveredRequestId)
                .attachIdentifiers(reader?.recoveredRunId, reader?.recoveredRequestId)
        } catch (error: IOException) {
            throw AnkiProtocolException(
                AnkiProtocolCategory.INVALID_JSON,
                "malformed bridge JSON",
                error,
            ).attachIdentifiers(headerReader?.recoveredRunId, headerReader?.recoveredRequestId)
                .attachIdentifiers(reader?.recoveredRunId, reader?.recoveredRequestId)
        }
    }

    fun encodeResponse(response: AnkiResponse, request: AnkiRequest): String {
        AnkiValidators.validateResponseForRequest(response, request)
        return encodeValidatedResponse(response)
    }

    fun encodeProtocolError(operation: AnkiOperation, error: AnkiProtocolException): String =
        AnkiErrorResult(
                runId = error.recoveredRunId ?: PLACEHOLDER_RUN_ID,
                requestId = error.recoveredRequestId ?: PLACEHOLDER_REQUEST_ID,
                operation = operation,
                code = AnkiErrorCode.INVALID_REQUEST,
                message = "Invalid Anki request (${error.category.wireName})",
                retryable = false,
            ).also(AnkiValidators::validateResponse)
            .let(::encodeValidatedResponse)

    private fun encodeValidatedResponse(response: AnkiResponse): String {
        val output = BoundedUtf8Output(response.operation.resultEnvelopeMaxUtf8Bytes)
        try {
            factory.createGenerator(output).use { generator ->
                generator.writeStartObject()
                generator.writeNumberField("schemaVersion", AnkiLimitsV1.SCHEMA_VERSION)
                generator.writeStringField("type", response.messageType)
                generator.writeObjectFieldStart("payload")
                writePayload(generator, response)
                generator.writeEndObject()
                generator.writeEndObject()
            }
        } catch (error: OutputLimitExceededException) {
            fail(AnkiProtocolCategory.OUTPUT_TOO_LARGE, "Anki response exceeds its v1 UTF-8 envelope limit", error)
        } catch (error: IOException) {
            throw IllegalStateException("failed to encode a validated Anki response", error)
        }
        return output.decodeUtf8()
    }

    private class OutputLimitExceededException : IOException("bounded JSON output exceeded its limit")

    private class BoundedUtf8Output(private val limit: Int) : OutputStream() {
        private var buffer = ByteArray(minOf(1024, limit))
        private var size = 0

        override fun write(value: Int) {
            ensureCapacity(1)
            buffer[size] = value.toByte()
            size += 1
        }

        override fun write(source: ByteArray, offset: Int, length: Int) {
            if (offset < 0 || length < 0 || offset > source.size - length) {
                throw IndexOutOfBoundsException("invalid output slice")
            }
            ensureCapacity(length)
            source.copyInto(buffer, size, offset, offset + length)
            size += length
        }

        fun decodeUtf8(): String = String(buffer, 0, size, StandardCharsets.UTF_8)

        private fun ensureCapacity(additional: Int) {
            if (additional > limit - size) throw OutputLimitExceededException()
            val required = size + additional
            if (required <= buffer.size) return
            var capacity = maxOf(buffer.size.toLong() * 2, required.toLong())
            capacity = minOf(capacity, limit.toLong())
            buffer = buffer.copyOf(capacity.toInt())
        }
    }

    private class EnvelopeHeaderReader(
        private val parser: JsonParser,
        private val expectedType: String,
    ) {
        var recoveredRunId: String? = null
            private set
        var recoveredRequestId: String? = null
            private set

        fun validate() {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                fail(AnkiProtocolCategory.INVALID_ENVELOPE, "bridge envelope must be an object")
            }
            val seen = linkedSetOf<String>()
            var schemaVersion: Long? = null
            var messageType: String? = null
            var payloadIsObject = false
            var unknownField = false
            while (true) {
                when (parser.nextToken()) {
                    JsonToken.END_OBJECT -> break
                    JsonToken.FIELD_NAME -> {
                        val field = parser.currentName()
                        AnkiValidators.strictStats(field, "bridge envelope object key")
                        if (!seen.add(field)) {
                            fail(AnkiProtocolCategory.DUPLICATE_JSON_KEY, "bridge envelope contains a duplicate key")
                        }
                        if (parser.nextToken() == null) {
                            fail(AnkiProtocolCategory.INVALID_ENVELOPE, "bridge envelope ends before a field value")
                        }
                        when (field) {
                            "schemaVersion" -> schemaVersion = readIntegral("schemaVersion")
                            "type" -> {
                                if (parser.currentToken() != JsonToken.VALUE_STRING) {
                                    fail(AnkiProtocolCategory.INVALID_MESSAGE_TYPE, "bridge message type is invalid")
                                }
                                messageType = parser.text.also { AnkiValidators.strictStats(it, "message type") }
                            }
                            "payload" -> {
                                payloadIsObject = parser.currentToken() == JsonToken.START_OBJECT
                                scanCurrentValue(capturePayloadIdentifiers = payloadIsObject)
                            }
                            else -> {
                                unknownField = true
                                scanCurrentValue(capturePayloadIdentifiers = false)
                            }
                        }
                    }
                    else -> fail(AnkiProtocolCategory.INVALID_ENVELOPE, "bridge envelope contains an invalid member")
                }
            }
            if (parser.nextToken() != null) {
                fail(AnkiProtocolCategory.INVALID_JSON, "bridge envelope has trailing JSON tokens")
            }
            if (unknownField || seen != setOf("schemaVersion", "type", "payload")) {
                fail(AnkiProtocolCategory.INVALID_ENVELOPE, "bridge envelope has missing or unknown fields")
            }
            if (schemaVersion != AnkiLimitsV1.SCHEMA_VERSION.toLong()) {
                fail(AnkiProtocolCategory.UNSUPPORTED_SCHEMA_VERSION, "unsupported bridge schema version")
            }
            if (messageType == null || !messageTypePattern.matches(messageType!!)) {
                fail(AnkiProtocolCategory.INVALID_MESSAGE_TYPE, "bridge message type is invalid")
            }
            if (messageType != expectedType) {
                fail(AnkiProtocolCategory.UNEXPECTED_MESSAGE_TYPE, "unexpected Anki callback message type")
            }
            if (!payloadIsObject) {
                fail(AnkiProtocolCategory.INVALID_PAYLOAD, "Anki request payload must be an object")
            }
        }

        private fun scanCurrentValue(capturePayloadIdentifiers: Boolean) {
            var openContainers = 0
            var directPayloadField: String? = null
            do {
                when (parser.currentToken()) {
                    JsonToken.START_OBJECT, JsonToken.START_ARRAY -> openContainers += 1
                    JsonToken.END_OBJECT, JsonToken.END_ARRAY -> openContainers -= 1
                    JsonToken.FIELD_NAME -> {
                        val field = parser.currentName()
                        AnkiValidators.strictStats(field, "bridge JSON object key")
                        directPayloadField = if (capturePayloadIdentifiers && openContainers == 1) field else null
                    }
                    JsonToken.VALUE_STRING -> {
                        val value = parser.text
                        AnkiValidators.strictStats(value, "bridge JSON string")
                        if (capturePayloadIdentifiers && openContainers == 1) {
                            when (directPayloadField) {
                                "runId" -> recoveredRunId = value
                                "requestId" -> recoveredRequestId = value
                            }
                        }
                        directPayloadField = null
                    }
                    JsonToken.VALUE_NUMBER_INT -> {
                        validateGenericInteger()
                        directPayloadField = null
                    }
                    JsonToken.VALUE_NUMBER_FLOAT -> {
                        validateGenericFloat()
                        directPayloadField = null
                    }
                    else -> Unit
                }
                if (openContainers == 0) return
                if (parser.nextToken() == null) {
                    fail(AnkiProtocolCategory.INVALID_JSON, "bridge JSON value is unterminated")
                }
            } while (true)
        }

        private fun validateGenericInteger() {
            val value = parser.bigIntegerValue
            if (value < minimumSignedLong || value > maximumSignedLong) {
                fail(AnkiProtocolCategory.INTEGER_OUT_OF_RANGE, "JSON integer is outside the signed-64 wire domain")
            }
        }

        private fun validateGenericFloat() {
            val value =
                parser.text.toDoubleOrNull()
                    ?: fail(AnkiProtocolCategory.INVALID_JSON_NUMBER, "JSON floating-point token is invalid")
            if (!value.isFinite()) {
                fail(AnkiProtocolCategory.NON_FINITE_NUMBER, "JSON number exceeds the finite IEEE-754 wire domain")
            }
        }

        private fun readIntegral(context: String): Long =
            when (parser.currentToken()) {
                JsonToken.VALUE_NUMBER_INT -> {
                    val value = parser.bigIntegerValue
                    if (value < minimumSignedLong || value > maximumSignedLong) {
                        fail(AnkiProtocolCategory.INTEGER_OUT_OF_RANGE, "$context is outside the signed-64 wire domain")
                    }
                    value.toLong()
                }
                JsonToken.VALUE_NUMBER_FLOAT -> {
                    val value =
                        parser.text.toDoubleOrNull()
                            ?: fail(AnkiProtocolCategory.INVALID_JSON_NUMBER, "$context is not a valid JSON floating-point number")
                    if (!value.isFinite()) {
                        fail(AnkiProtocolCategory.NON_FINITE_NUMBER, "$context exceeds the finite IEEE-754 wire domain")
                    }
                    if (value % 1.0 != 0.0 || value < NEGATIVE_LONG_BOUNDARY_AS_DOUBLE || value >= POSITIVE_LONG_BOUNDARY_AS_DOUBLE) {
                        fail(AnkiProtocolCategory.INVALID_VALUE, "$context is not a signed-64 mathematical integer")
                    }
                    value.toLong()
                }
                else -> fail(AnkiProtocolCategory.UNSUPPORTED_SCHEMA_VERSION, "$context must be a JSON integer")
            }
    }

    private class RequestReader(
        private val parser: JsonParser,
        private val operation: AnkiOperation,
        private val expectedType: String,
    ) {
        var recoveredRunId: String? = null
            private set
        var recoveredRequestId: String? = null
            private set

        fun readEnvelope(): AnkiRequest {
            expectNext(JsonToken.START_OBJECT, "bridge envelope must be an object", AnkiProtocolCategory.INVALID_ENVELOPE)
            var schemaVersion: Long? = null
            var messageType: String? = null
            var request: AnkiRequest? = null
            readObjectBody(
                context = "bridge envelope",
                required = setOf("schemaVersion", "type", "payload"),
                category = AnkiProtocolCategory.INVALID_ENVELOPE,
            ) { field ->
                when (field) {
                    "schemaVersion" -> schemaVersion = readIntegral("schemaVersion")
                    "type" -> messageType = readString("message type")
                    "payload" -> request = readPayload()
                    else -> unknown("bridge envelope", field, AnkiProtocolCategory.INVALID_ENVELOPE)
                }
            }
            if (parser.nextToken() != null) {
                fail(AnkiProtocolCategory.INVALID_JSON, "bridge envelope has trailing JSON tokens")
            }
            if (schemaVersion != AnkiLimitsV1.SCHEMA_VERSION.toLong()) {
                fail(AnkiProtocolCategory.UNSUPPORTED_SCHEMA_VERSION, "unsupported bridge schema version")
            }
            if (messageType == null || !messageTypePattern.matches(messageType!!)) {
                fail(AnkiProtocolCategory.INVALID_MESSAGE_TYPE, "bridge message type is invalid")
            }
            if (messageType != expectedType) {
                fail(AnkiProtocolCategory.UNEXPECTED_MESSAGE_TYPE, "unexpected Anki callback message type")
            }
            return request ?: fail(AnkiProtocolCategory.INVALID_PAYLOAD, "Anki request payload is missing")
        }

        private fun readPayload(): AnkiRequest =
            when (operation) {
                AnkiOperation.VERIFY_TARGET -> readVerifyTarget()
                AnkiOperation.SCAN_FIRST_FIELDS -> readScanFirstFields()
                AnkiOperation.STORE_MEDIA -> readStoreMedia()
                AnkiOperation.CREATE_NOTES -> readCreateNotes()
                AnkiOperation.RELEASE_RUN_STATE -> readReleaseRunState()
            }

        private fun readVerifyTarget(): VerifyTargetRequest {
            var runId: String? = null
            var requestId: String? = null
            var deckName: String? = null
            var modelName: String? = null
            var requiredFields: List<String>? = null
            readObject(
                "verify-target payload",
                setOf("runId", "requestId", "deckName", "modelName", "requiredFields"),
            ) { field ->
                when (field) {
                    "runId" -> runId = readRunId()
                    "requestId" -> requestId = readRequestId()
                    "deckName" -> deckName = readString("deck name")
                    "modelName" -> modelName = readString("model name")
                    "requiredFields" -> requiredFields = readStringArray(AnkiLimitsV1.Names.TargetFields.MAX_ITEM_COUNT, 0, "required fields")
                    else -> unknownPayload(field)
                }
            }
            return VerifyTargetRequest(runId!!, requestId!!, deckName!!, modelName!!, requiredFields!!)
        }

        private fun readScanFirstFields(): ScanFirstFieldsRequest {
            var runId: String? = null
            var requestId: String? = null
            var scope: ScanScope? = null
            readObject("scan-first-fields payload", setOf("runId", "requestId", "scope")) { field ->
                when (field) {
                    "runId" -> runId = readRunId()
                    "requestId" -> requestId = readRequestId()
                    "scope" -> scope = readScanScope()
                    else -> unknownPayload(field)
                }
            }
            return ScanFirstFieldsRequest(runId!!, requestId!!, scope!!)
        }

        private fun readScanScope(): ScanScope {
            var kind: String? = null
            var excludedDecks: List<String>? = null
            var cursorSeen = false
            var cursor: KnownVocabularyCursor? = null
            var modelName: String? = null
            var firstFieldName: String? = null
            var deckNameSeen = false
            var deckName: String? = null
            var candidates: List<DuplicateCandidate>? = null
            var occurrences: List<Int>? = null
            var invalidateSeen = false
            var invalidateBaselineToken: String? = null
            var limits: Map<String, Long>? = null
            val union =
                setOf(
                    "kind",
                    "excludedDecks",
                    "cursor",
                    "modelName",
                    "firstFieldName",
                    "deckName",
                    "candidates",
                    "occurrences",
                    "invalidateBaselineToken",
                    "limits",
                )
            val seen = readObjectUnion("scan scope", union) { field ->
                when (field) {
                    "kind" -> kind = readString("scan scope kind")
                    "excludedDecks" -> excludedDecks = readStringArray(AnkiLimitsV1.Names.ExcludedDecks.MAX_ITEM_COUNT, 0, "excluded decks")
                    "cursor" -> {
                        cursorSeen = true
                        cursor = readNullable { readKnownCursor() }
                    }
                    "modelName" -> modelName = readString("model name")
                    "firstFieldName" -> firstFieldName = readString("first field name")
                    "deckName" -> {
                        deckNameSeen = true
                        deckName = readNullable { readString("deck name") }
                    }
                    "candidates" -> candidates = readArray(AnkiLimitsV1.ScanFirstFields.DUPLICATE_CANDIDATE_MAX_ITEM_COUNT, 1, "duplicate candidates") { readDuplicateCandidate() }
                    "occurrences" -> occurrences = readArray(AnkiLimitsV1.ScanFirstFields.DUPLICATE_CANDIDATE_MAX_ITEM_COUNT, 1, "duplicate occurrences") { readInt("duplicate occurrence") }
                    "invalidateBaselineToken" -> {
                        invalidateSeen = true
                        invalidateBaselineToken = readNullable { readString("baseline invalidation token") }
                    }
                    "limits" -> limits = readNumericObject("scan limits", 5)
                    else -> unknownPayload(field)
                }
            }
            return when (kind) {
                "knownVocabulary" -> {
                    val required = setOf("kind", "excludedDecks", "cursor", "limits")
                    val accepted = if (deckNameSeen) required + "deckName" else required
                    requireExactSeen(seen, accepted, "known-vocabulary scope")
                    if (!cursorSeen) missingPayload("cursor")
                    if (deckNameSeen && deckName == null) {
                        fail(AnkiProtocolCategory.INVALID_VALUE, "known-vocabulary deck name must not be null")
                    }
                    requireExactLimits(
                        limits!!,
                        mapOf(
                            "maxScannedNotes" to AnkiLimitsV1.ScanFirstFields.KNOWN_PAGE_MAX_ITEM_COUNT.toLong(),
                            "maxTotalScannedNotes" to AnkiLimitsV1.ScanFirstFields.KNOWN_TOTAL_SCANNED_NOTE_MAX_COUNT.toLong(),
                            "maxItems" to AnkiLimitsV1.ScanFirstFields.KNOWN_PAGE_MAX_ITEM_COUNT.toLong(),
                            "maxItemUtf8Bytes" to AnkiLimitsV1.ScanFirstFields.FIRST_FIELD_MAX_UTF8_BYTES.toLong(),
                            "maxTotalUtf8Bytes" to AnkiLimitsV1.ScanFirstFields.KNOWN_PAGE_MAX_UTF8_BYTES.toLong(),
                        ),
                    )
                    KnownVocabularyScope(excludedDecks!!, cursor, deckName)
                }
                "duplicates" -> {
                    requireExactSeen(
                        seen,
                        setOf("kind", "modelName", "firstFieldName", "deckName", "candidates", "occurrences", "invalidateBaselineToken", "limits"),
                        "duplicate scope",
                    )
                    if (!deckNameSeen) missingPayload("deckName")
                    if (!invalidateSeen) missingPayload("invalidateBaselineToken")
                    requireExactLimits(
                        limits!!,
                        mapOf(
                            "maxHitsPerCandidate" to AnkiLimitsV1.ScanFirstFields.DUPLICATE_HIT_PER_CANDIDATE_MAX_ITEM_COUNT.toLong(),
                            "maxTotalHits" to AnkiLimitsV1.ScanFirstFields.DUPLICATE_HIT_TOTAL_MAX_ITEM_COUNT.toLong(),
                            "maxItemUtf8Bytes" to AnkiLimitsV1.ScanFirstFields.FIRST_FIELD_MAX_UTF8_BYTES.toLong(),
                            "maxTotalUtf8Bytes" to AnkiLimitsV1.ScanFirstFields.DUPLICATE_HIT_TOTAL_MAX_UTF8_BYTES.toLong(),
                        ),
                    )
                    DuplicateScanScope(modelName!!, firstFieldName!!, deckName, candidates!!, occurrences!!, invalidateBaselineToken)
                }
                else -> fail(AnkiProtocolCategory.INVALID_VALUE, "scan scope kind is invalid")
            }
        }

        private fun readKnownCursor(): KnownVocabularyCursor {
            var ordinal: Long? = null
            var token: String? = null
            readObject("known-vocabulary cursor", setOf("ordinal", "token")) { field ->
                when (field) {
                    "ordinal" -> ordinal = readIntegral("cursor ordinal")
                    "token" -> token = readString("cursor token")
                    else -> unknownPayload(field)
                }
            }
            return KnownVocabularyCursor(ordinal!!, token!!)
        }

        private fun readDuplicateCandidate(): DuplicateCandidate {
            var key: String? = null
            var firstField: String? = null
            readObject("duplicate candidate", setOf("key", "firstField")) { field ->
                when (field) {
                    "key" -> key = readString("duplicate key")
                    "firstField" -> firstField = readString("duplicate first field")
                    else -> unknownPayload(field)
                }
            }
            return DuplicateCandidate(key!!, firstField!!)
        }

        private fun readStoreMedia(): StoreMediaRequest {
            var runId: String? = null
            var requestId: String? = null
            var assets: List<MediaAsset>? = null
            var limits: Map<String, Long>? = null
            readObject("store-media payload", setOf("runId", "requestId", "assets", "limits")) { field ->
                when (field) {
                    "runId" -> runId = readRunId()
                    "requestId" -> requestId = readRequestId()
                    "assets" -> assets = readArray(AnkiLimitsV1.StoreMedia.MAX_ASSET_COUNT, 1, "media assets") { readMediaAsset() }
                    "limits" -> limits = readNumericObject("store-media limits", 3)
                    else -> unknownPayload(field)
                }
            }
            requireExactLimits(
                limits!!,
                mapOf(
                    "maxAssets" to AnkiLimitsV1.StoreMedia.MAX_ASSET_COUNT.toLong(),
                    "maxAssetBytes" to AnkiLimitsV1.StoreMedia.MAX_ASSET_BYTES.toLong(),
                    "maxTotalBytes" to AnkiLimitsV1.StoreMedia.MAX_TOTAL_BYTES.toLong(),
                ),
            )
            return StoreMediaRequest(runId!!, requestId!!, assets!!)
        }

        private fun readMediaAsset(): MediaAsset {
            var assetId: String? = null
            var sourcePath: String? = null
            var preferredName: String? = null
            var requestedFilename: String? = null
            var purpose: MediaPurpose? = null
            var mediaKind: MediaKind? = null
            var expectedSizeBytes: Long? = null
            var expectedSha256: String? = null
            readObject(
                "media asset",
                setOf("assetId", "sourcePath", "preferredName", "requestedFilename", "purpose", "mediaKind", "expectedSizeBytes", "expectedSha256"),
            ) { field ->
                when (field) {
                    "assetId" -> assetId = readString("asset ID")
                    "sourcePath" -> sourcePath = readString("media source path")
                    "preferredName" -> preferredName = readString("preferred media name")
                    "requestedFilename" -> requestedFilename = readString("requested media filename")
                    "purpose" -> purpose = readEnum("media purpose", MediaPurpose.entries.associateBy { it.wireName })
                    "mediaKind" -> mediaKind = readEnum("media kind", MediaKind.entries.associateBy { it.wireName })
                    "expectedSizeBytes" -> expectedSizeBytes = readIntegral("expected media size")
                    "expectedSha256" -> expectedSha256 = readString("expected media SHA-256")
                    else -> unknownPayload(field)
                }
            }
            return MediaAsset(assetId!!, sourcePath!!, preferredName!!, requestedFilename!!, purpose!!, mediaKind!!, expectedSizeBytes!!, expectedSha256!!)
        }

        private fun readCreateNotes(): CreateNotesRequest {
            var runId: String? = null
            var requestId: String? = null
            var deckName: String? = null
            var modelName: String? = null
            var firstFieldName: String? = null
            var baselineToken: String? = null
            var duplicateScope: CreateDuplicateScope? = null
            var limits: Map<String, Long>? = null
            var notes: List<CreateNote>? = null
            readObject(
                "create-notes payload",
                setOf("runId", "requestId", "deckName", "modelName", "firstFieldName", "baselineToken", "duplicateScope", "limits", "notes"),
            ) { field ->
                when (field) {
                    "runId" -> runId = readRunId()
                    "requestId" -> requestId = readRequestId()
                    "deckName" -> deckName = readString("deck name")
                    "modelName" -> modelName = readString("model name")
                    "firstFieldName" -> firstFieldName = readString("first field name")
                    "baselineToken" -> baselineToken = readString("baseline token")
                    "duplicateScope" -> duplicateScope = readCreateDuplicateScope()
                    "limits" -> limits = readNumericObject("create-notes limits", 13)
                    "notes" -> notes = readArray(AnkiLimitsV1.CreateNotes.MAX_NOTE_COUNT, 1, "notes") { readCreateNote() }
                    else -> unknownPayload(field)
                }
            }
            requireExactLimits(
                limits!!,
                mapOf(
                    "maxNotes" to AnkiLimitsV1.CreateNotes.MAX_NOTE_COUNT.toLong(),
                    "maxFieldsPerNote" to AnkiLimitsV1.CreateNotes.MAX_FIELD_COUNT_PER_NOTE.toLong(),
                    "maxCardsPerNote" to AnkiLimitsV1.CreateNotes.MAX_CARD_COUNT_PER_NOTE.toLong(),
                    "maxFieldNameUtf8Bytes" to AnkiLimitsV1.CreateNotes.FIELD_NAME_MAX_UTF8_BYTES.toLong(),
                    "maxFieldValueUtf8Bytes" to AnkiLimitsV1.CreateNotes.FIELD_VALUE_MAX_UTF8_BYTES.toLong(),
                    "maxTagsPerNote" to AnkiLimitsV1.CreateNotes.MAX_TAG_COUNT_PER_NOTE.toLong(),
                    "maxTagUtf8Bytes" to AnkiLimitsV1.CreateNotes.TAG_MAX_UTF8_BYTES.toLong(),
                    "maxTagsUtf8BytesPerNote" to AnkiLimitsV1.CreateNotes.TAGS_PER_NOTE_MAX_UTF8_BYTES.toLong(),
                    "maxNoteContentUtf8Bytes" to AnkiLimitsV1.CreateNotes.NOTE_CONTENT_MAX_UTF8_BYTES.toLong(),
                    "maxTotalContentUtf8Bytes" to AnkiLimitsV1.CreateNotes.CALLBACK_CONTENT_MAX_UTF8_BYTES.toLong(),
                    "maxMediaBindingsPerNote" to AnkiLimitsV1.CreateNotes.MAX_MEDIA_BINDING_COUNT_PER_NOTE.toLong(),
                    "maxMediaBindingsTotal" to AnkiLimitsV1.CreateNotes.MAX_MEDIA_BINDING_TOTAL_COUNT.toLong(),
                    "maxEnvelopeUtf8Bytes" to AnkiLimitsV1.CreateNotes.REQUEST_ENVELOPE_MAX_UTF8_BYTES.toLong(),
                ),
            )
            return CreateNotesRequest(runId!!, requestId!!, deckName!!, modelName!!, firstFieldName!!, baselineToken!!, duplicateScope!!, notes!!)
        }

        private fun readCreateDuplicateScope(): CreateDuplicateScope {
            var kind: String? = null
            var deckName: String? = null
            var includeChildren: Boolean? = null
            var limits: Map<String, Long>? = null
            val seen = readObjectUnion("create duplicate scope", setOf("kind", "deckName", "includeChildren", "limits")) { field ->
                when (field) {
                    "kind" -> kind = readString("create duplicate scope kind")
                    "deckName" -> deckName = readString("duplicate scope deck")
                    "includeChildren" -> includeChildren = readBoolean("include-children flag")
                    "limits" -> limits = readNumericObject("create duplicate limits", 2)
                    else -> unknownPayload(field)
                }
            }
            requireExactLimits(
                limits ?: missingPayload("limits"),
                mapOf(
                    "maxNoteIdsPerCandidate" to AnkiLimitsV1.ScanFirstFields.DUPLICATE_HIT_PER_CANDIDATE_MAX_ITEM_COUNT.toLong(),
                    "maxTotalNoteIds" to AnkiLimitsV1.ScanFirstFields.DUPLICATE_HIT_TOTAL_MAX_ITEM_COUNT.toLong(),
                ),
            )
            return when (kind) {
                "collection" -> {
                    requireExactSeen(seen, setOf("kind", "limits"), "collection duplicate scope")
                    CollectionCreateDuplicateScope
                }
                "exactDeck" -> {
                    requireExactSeen(seen, setOf("kind", "deckName", "includeChildren", "limits"), "exact-deck duplicate scope")
                    if (includeChildren != false) fail(AnkiProtocolCategory.INVALID_VALUE, "exact-deck scope must exclude children")
                    ExactDeckCreateDuplicateScope(deckName!!)
                }
                else -> fail(AnkiProtocolCategory.INVALID_VALUE, "create duplicate scope kind is invalid")
            }
        }

        private fun readCreateNote(): CreateNote {
            var clientNoteId: String? = null
            var fields: Map<String, String>? = null
            var tags: List<String>? = null
            var duplicateCandidate: CreateDuplicateCandidate? = null
            var mediaBindings: List<MediaBinding>? = null
            readObject(
                "create note",
                setOf("clientNoteId", "fields", "tags", "duplicateCandidate", "mediaBindings"),
            ) { field ->
                when (field) {
                    "clientNoteId" -> clientNoteId = readString("client note ID")
                    "fields" -> fields = readStringMap(AnkiLimitsV1.CreateNotes.MAX_FIELD_COUNT_PER_NOTE, 1, "note fields")
                    "tags" -> tags = readStringArray(AnkiLimitsV1.CreateNotes.MAX_TAG_COUNT_PER_NOTE, 0, "note tags")
                    "duplicateCandidate" -> duplicateCandidate = readCreateDuplicateCandidate()
                    "mediaBindings" -> mediaBindings = readArray(AnkiLimitsV1.CreateNotes.MAX_MEDIA_BINDING_COUNT_PER_NOTE, 0, "media bindings") { readMediaBinding() }
                    else -> unknownPayload(field)
                }
            }
            return CreateNote(clientNoteId!!, fields!!, tags!!, duplicateCandidate!!, mediaBindings!!)
        }

        private fun readCreateDuplicateCandidate(): CreateDuplicateCandidate {
            var key: String? = null
            var firstField: String? = null
            var occurrence: Int? = null
            readObject("create duplicate candidate", setOf("key", "firstField", "occurrence")) { field ->
                when (field) {
                    "key" -> key = readString("duplicate key")
                    "firstField" -> firstField = readString("duplicate first field")
                    "occurrence" -> occurrence = readInt("duplicate occurrence")
                    else -> unknownPayload(field)
                }
            }
            return CreateDuplicateCandidate(key!!, firstField!!, occurrence!!)
        }

        private fun readMediaBinding(): MediaBinding {
            var assetId: String? = null
            var actualFilename: String? = null
            readObject("media binding", setOf("assetId", "actualFilename")) { field ->
                when (field) {
                    "assetId" -> assetId = readString("media binding asset ID")
                    "actualFilename" -> actualFilename = readString("media binding filename")
                    else -> unknownPayload(field)
                }
            }
            return MediaBinding(assetId!!, actualFilename!!)
        }

        private fun readReleaseRunState(): ReleaseRunStateRequest {
            var runId: String? = null
            var requestId: String? = null
            var acknowledgeTerminalResponses: Boolean? = null
            readObject("release-run-state payload", setOf("runId", "requestId", "acknowledgeTerminalResponses")) { field ->
                when (field) {
                    "runId" -> runId = readRunId()
                    "requestId" -> requestId = readRequestId()
                    "acknowledgeTerminalResponses" -> acknowledgeTerminalResponses = readBoolean("terminal-response acknowledgement")
                    else -> unknownPayload(field)
                }
            }
            return ReleaseRunStateRequest(runId!!, requestId!!, acknowledgeTerminalResponses!!)
        }

        private fun readObject(
            context: String,
            required: Set<String>,
            fieldReader: (String) -> Unit,
        ) {
            expectCurrent(JsonToken.START_OBJECT, "$context must be an object", AnkiProtocolCategory.INVALID_PAYLOAD)
            readObjectBody(context, required, AnkiProtocolCategory.INVALID_PAYLOAD, fieldReader)
        }

        private fun readObjectUnion(
            context: String,
            allowed: Set<String>,
            fieldReader: (String) -> Unit,
        ): Set<String> {
            expectCurrent(JsonToken.START_OBJECT, "$context must be an object", AnkiProtocolCategory.INVALID_PAYLOAD)
            return readObjectBody(context, emptySet(), AnkiProtocolCategory.INVALID_PAYLOAD) { field ->
                if (field !in allowed) unknownPayload(field)
                fieldReader(field)
            }
        }

        private fun readObjectBody(
            context: String,
            required: Set<String>,
            category: AnkiProtocolCategory,
            fieldReader: (String) -> Unit,
        ): Set<String> {
            val seen = linkedSetOf<String>()
            while (true) {
                when (parser.nextToken()) {
                    JsonToken.END_OBJECT -> break
                    JsonToken.FIELD_NAME -> {
                        val field = parser.currentName()
                        AnkiValidators.strictStats(field, "$context object key")
                        if (!seen.add(field)) fail(AnkiProtocolCategory.DUPLICATE_JSON_KEY, "$context contains a duplicate key")
                        if (parser.nextToken() == null) fail(category, "$context ends before a field value")
                        fieldReader(field)
                    }
                    else -> fail(category, "$context contains an invalid object member")
                }
            }
            val missing = required - seen
            if (missing.isNotEmpty()) fail(category, "$context has missing fields")
            return seen
        }

        private fun readStringMap(maximum: Int, minimum: Int, context: String): Map<String, String> {
            expectCurrent(JsonToken.START_OBJECT, "$context must be an object", AnkiProtocolCategory.INVALID_PAYLOAD)
            val result = linkedMapOf<String, String>()
            while (true) {
                when (parser.nextToken()) {
                    JsonToken.END_OBJECT -> break
                    JsonToken.FIELD_NAME -> {
                        if (result.size == maximum) fail(AnkiProtocolCategory.INVALID_VALUE, "$context exceeds its item limit")
                        val name = parser.currentName()
                        AnkiValidators.strictStats(name, "$context object key")
                        if (name in result) fail(AnkiProtocolCategory.DUPLICATE_JSON_KEY, "$context contains a duplicate key")
                        if (parser.nextToken() == null) fail(AnkiProtocolCategory.INVALID_PAYLOAD, "$context ends before a field value")
                        result[name] = readString("$context value")
                    }
                    else -> fail(AnkiProtocolCategory.INVALID_PAYLOAD, "$context contains an invalid object member")
                }
            }
            if (result.size < minimum) fail(AnkiProtocolCategory.INVALID_PAYLOAD, "$context has too few entries")
            return result
        }

        private fun readStringArray(maximum: Int, minimum: Int, context: String): List<String> =
            readArray(maximum, minimum, context) { readString("$context item") }

        private fun <T> readArray(maximum: Int, minimum: Int, context: String, itemReader: () -> T): List<T> {
            expectCurrent(JsonToken.START_ARRAY, "$context must be an array", AnkiProtocolCategory.INVALID_PAYLOAD)
            val result = ArrayList<T>(minimum.coerceAtLeast(0))
            while (parser.nextToken() != JsonToken.END_ARRAY) {
                if (parser.currentToken() == null) fail(AnkiProtocolCategory.INVALID_PAYLOAD, "$context is unterminated")
                if (result.size == maximum) fail(AnkiProtocolCategory.INVALID_VALUE, "$context exceeds its item limit")
                result += itemReader()
            }
            if (result.size < minimum) fail(AnkiProtocolCategory.INVALID_PAYLOAD, "$context has too few items")
            return result
        }

        private fun readNumericObject(context: String, maximum: Int): Map<String, Long> {
            expectCurrent(JsonToken.START_OBJECT, "$context must be an object", AnkiProtocolCategory.INVALID_PAYLOAD)
            val result = linkedMapOf<String, Long>()
            while (true) {
                when (parser.nextToken()) {
                    JsonToken.END_OBJECT -> break
                    JsonToken.FIELD_NAME -> {
                        if (result.size == maximum) fail(AnkiProtocolCategory.LIMIT_MISMATCH, "$context has too many fields")
                        val name = parser.currentName()
                        AnkiValidators.strictStats(name, "$context object key")
                        if (name in result) fail(AnkiProtocolCategory.DUPLICATE_JSON_KEY, "$context contains a duplicate key")
                        if (parser.nextToken() == null) fail(AnkiProtocolCategory.INVALID_PAYLOAD, "$context ends before a field value")
                        result[name] = readIntegral("$context value")
                    }
                    else -> fail(AnkiProtocolCategory.INVALID_PAYLOAD, "$context contains an invalid object member")
                }
            }
            return result
        }

        private fun requireExactLimits(actual: Map<String, Long>, expected: Map<String, Long>) {
            if (actual != expected) fail(AnkiProtocolCategory.LIMIT_MISMATCH, "caller-supplied Anki limits do not match v1")
        }

        private fun requireExactSeen(actual: Set<String>, expected: Set<String>, context: String) {
            if (actual != expected) fail(AnkiProtocolCategory.INVALID_PAYLOAD, "$context has missing or unknown fields")
        }

        private fun <T> readNullable(reader: () -> T): T? =
            if (parser.currentToken() == JsonToken.VALUE_NULL) null else reader()

        private fun readString(context: String): String {
            expectCurrent(JsonToken.VALUE_STRING, "$context must be a string", AnkiProtocolCategory.INVALID_PAYLOAD)
            return parser.text.also { AnkiValidators.strictStats(it, context) }
        }

        private fun readRunId(): String =
            readString("run ID").also { recoveredRunId = it }

        private fun readRequestId(): String =
            readString("request ID").also { recoveredRequestId = it }

        private fun readBoolean(context: String): Boolean =
            when (parser.currentToken()) {
                JsonToken.VALUE_TRUE -> true
                JsonToken.VALUE_FALSE -> false
                else -> fail(AnkiProtocolCategory.INVALID_PAYLOAD, "$context must be a boolean")
            }

        private fun readInt(context: String): Int {
            val value = readIntegral(context)
            if (value !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) fail(AnkiProtocolCategory.INVALID_VALUE, "$context is outside the integer range")
            return value.toInt()
        }

        private fun readIntegral(context: String): Long =
            when (parser.currentToken()) {
                JsonToken.VALUE_NUMBER_INT -> {
                    val value = parser.bigIntegerValue
                    if (value < minimumSignedLong || value > maximumSignedLong) {
                        fail(AnkiProtocolCategory.INTEGER_OUT_OF_RANGE, "$context is outside the signed-64 wire domain")
                    }
                    value.toLong()
                }
                JsonToken.VALUE_NUMBER_FLOAT -> {
                    val value =
                        parser.text.toDoubleOrNull()
                            ?: fail(AnkiProtocolCategory.INVALID_JSON_NUMBER, "$context is not a valid JSON floating-point number")
                    if (!value.isFinite()) {
                        fail(AnkiProtocolCategory.NON_FINITE_NUMBER, "$context exceeds the finite IEEE-754 wire domain")
                    }
                    if (value % 1.0 != 0.0 || value < NEGATIVE_LONG_BOUNDARY_AS_DOUBLE || value >= POSITIVE_LONG_BOUNDARY_AS_DOUBLE) {
                        fail(AnkiProtocolCategory.INVALID_VALUE, "$context is not a signed-64 mathematical integer")
                    }
                    value.toLong()
                }
                else -> fail(AnkiProtocolCategory.INVALID_PAYLOAD, "$context must be a JSON integer")
            }

        private fun <T> readEnum(context: String, values: Map<String, T>): T {
            val wire = readString(context)
            return values[wire] ?: fail(AnkiProtocolCategory.INVALID_VALUE, "$context is invalid")
        }

        private fun expectNext(token: JsonToken, message: String, category: AnkiProtocolCategory) {
            if (parser.nextToken() != token) fail(category, message)
        }

        private fun expectCurrent(token: JsonToken, message: String, category: AnkiProtocolCategory) {
            if (parser.currentToken() != token) fail(category, message)
        }

        private fun unknownPayload(field: String): Nothing = unknown("Anki payload", field, AnkiProtocolCategory.INVALID_PAYLOAD)

        private fun unknown(context: String, field: String, category: AnkiProtocolCategory): Nothing {
            parser.skipChildren()
            fail(category, "$context contains unknown field $field")
        }

        private fun missingPayload(field: String): Nothing =
            fail(AnkiProtocolCategory.INVALID_PAYLOAD, "Anki payload is missing $field")
    }

    private fun strictUtf8(raw: String, maximum: Int, oversizeCategory: AnkiProtocolCategory): ByteArray {
        if (UnicodeContractV151.scalarCount(raw) == null) {
            fail(AnkiProtocolCategory.INVALID_UTF8, "bridge JSON contains an invalid Unicode scalar")
        }
        val encoder =
            StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        val capacity =
            minOf(
                maximum.toLong() + 1,
                maxOf(1L, raw.length.toLong() * 3),
            ).toInt()
        val buffer = ByteBuffer.allocate(capacity)
        val input = CharBuffer.wrap(raw)
        val encoded = encoder.encode(input, buffer, true)
        if (encoded.isError) fail(AnkiProtocolCategory.INVALID_UTF8, "bridge JSON contains an invalid Unicode scalar")
        if (encoded.isOverflow) {
            if (capacity <= maximum) {
                fail(AnkiProtocolCategory.INVALID_UTF8, "bridge JSON could not be encoded exactly as UTF-8")
            }
            fail(oversizeCategory, "bridge JSON exceeds its v1 UTF-8 envelope limit")
        }
        val flushed = encoder.flush(buffer)
        if (flushed.isError) fail(AnkiProtocolCategory.INVALID_UTF8, "bridge JSON contains an invalid Unicode scalar")
        if (flushed.isOverflow || input.hasRemaining() || buffer.position() > maximum) {
            fail(oversizeCategory, "bridge JSON exceeds its v1 UTF-8 envelope limit")
        }
        val bytes = ByteArray(buffer.position())
        buffer.flip()
        buffer.get(bytes)
        return bytes
    }

    private fun rejectOversizedJsonNumbers(raw: String) {
        var index = 0
        var inString = false
        var escaped = false
        while (index < raw.length) {
            val current = raw[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    current == '\\' -> escaped = true
                    current == '"' -> inString = false
                }
                index += 1
                continue
            }
            if (current == '"') {
                inString = true
                index += 1
                continue
            }
            if (current != '-' && current !in '0'..'9') {
                index += 1
                continue
            }
            val start = index
            if (raw[index] == '-') {
                index += 1
                if (index == raw.length || raw[index] !in '0'..'9') continue
            }
            if (raw[index] == '0') {
                index += 1
            } else {
                while (index < raw.length && raw[index] in '0'..'9') index += 1
            }
            if (index < raw.length && raw[index] == '.') {
                val fraction = index
                index += 1
                if (index == raw.length || raw[index] !in '0'..'9') {
                    index = fraction
                } else {
                    while (index < raw.length && raw[index] in '0'..'9') index += 1
                }
            }
            if (index < raw.length && raw[index] in "eE") {
                val exponent = index
                index += 1
                if (index < raw.length && raw[index] in "+-") index += 1
                if (index == raw.length || raw[index] !in '0'..'9') {
                    index = exponent
                } else {
                    while (index < raw.length && raw[index] in '0'..'9') index += 1
                }
            }
            if (index - start > AnkiLimitsV1.Wire.NUMERIC_TOKEN_MAX_CHARS) {
                fail(AnkiProtocolCategory.NUMERIC_TOKEN_TOO_LONG, "JSON number token exceeds the v1 character limit")
            }
            if (index == start) index += 1
        }
    }

    private fun writePayload(generator: JsonGenerator, response: AnkiResponse) {
        when (response) {
            is VerifyTargetResult -> writeVerifyTargetResult(generator, response)
            is KnownVocabularyResult -> writeKnownVocabularyResult(generator, response)
            is DuplicateLookupResult -> writeDuplicateLookupResult(generator, response)
            is StoreMediaResult -> writeStoreMediaResult(generator, response)
            is CreateNotesResult -> writeCreateNotesResult(generator, response)
            is ReleaseRunStateResult -> writeReleaseResult(generator, response)
            is AnkiErrorResult -> writeAnkiError(generator, response)
        }
    }

    private fun writeVerifyTargetResult(generator: JsonGenerator, result: VerifyTargetResult) {
        writeResponseIds(generator, result)
        generator.writeNumberField("deckId", result.deckId)
        generator.writeNumberField("modelId", result.modelId)
        generator.writeArrayFieldStart("fieldNames")
        result.fieldNames.forEach(generator::writeString)
        generator.writeEndArray()
        generator.writeBooleanField("deckCreated", result.deckCreated)
    }

    private fun writeKnownVocabularyResult(generator: JsonGenerator, result: KnownVocabularyResult) {
        writeResponseIds(generator, result)
        generator.writeArrayFieldStart("firstFields")
        result.firstFields.forEach(generator::writeString)
        generator.writeEndArray()
        generator.writeNumberField("scannedNotes", result.scannedNotes)
        generator.writeFieldName("nextCursor")
        writeCursor(generator, result.nextCursor)
    }

    private fun writeDuplicateLookupResult(generator: JsonGenerator, result: DuplicateLookupResult) {
        writeResponseIds(generator, result)
        generator.writeArrayFieldStart("rawFirstFieldHits")
        for (bucket in result.rawFirstFieldHits) {
            generator.writeStartArray()
            for (hit in bucket) {
                generator.writeStartObject()
                generator.writeNumberField("noteId", hit.noteId)
                generator.writeStringField("firstField", hit.firstField)
                generator.writeEndObject()
            }
            generator.writeEndArray()
        }
        generator.writeEndArray()
        generator.writeStringField("baselineToken", result.baselineToken)
    }

    private fun writeStoreMediaResult(generator: JsonGenerator, result: StoreMediaResult) {
        writeResponseIds(generator, result)
        generator.writeArrayFieldStart("results")
        for (row in result.results) {
            generator.writeStartObject()
            generator.writeStringField("assetId", row.assetId)
            generator.writeStringField("status", row.status)
            when (row) {
                is StoredMedia -> generator.writeStringField("actualFilename", row.actualFilename)
                is FailedMedia -> {
                    generator.writeObjectFieldStart("error")
                    writeErrorDetail(generator, row.error)
                    generator.writeEndObject()
                }
                is UncertainMedia, is NotAttemptedMedia -> Unit
            }
            generator.writeEndObject()
        }
        generator.writeEndArray()
        generator.writeFieldName("error")
        writeNullableError(generator, result.error)
    }

    private fun writeCreateNotesResult(generator: JsonGenerator, result: CreateNotesResult) {
        writeResponseIds(generator, result)
        generator.writeArrayFieldStart("results")
        for (row in result.results) {
            generator.writeStartObject()
            generator.writeStringField("clientNoteId", row.clientNoteId)
            generator.writeStringField("status", row.status)
            when (row) {
                is CreatedNote -> generator.writeNumberField("noteId", row.noteId)
                is CommittedFailedNote -> generator.writeNumberField("noteId", row.noteId)
                is DuplicateNote, is FailedNote, is UncertainNote, is NotAttemptedNote -> Unit
            }
            generator.writeEndObject()
        }
        generator.writeEndArray()
        generator.writeFieldName("error")
        writeNullableError(generator, result.error)
    }

    private fun writeReleaseResult(generator: JsonGenerator, result: ReleaseRunStateResult) {
        writeResponseIds(generator, result)
        generator.writeStringField("state", result.state.wireName)
    }

    private fun writeAnkiError(generator: JsonGenerator, result: AnkiErrorResult) {
        writeResponseIds(generator, result)
        generator.writeStringField("operation", result.operation.wireName)
        generator.writeStringField("code", result.code.wireName)
        generator.writeStringField("message", result.message)
        generator.writeBooleanField("retryable", result.retryable)
    }

    private fun writeResponseIds(generator: JsonGenerator, response: AnkiResponse) {
        generator.writeStringField("runId", response.runId)
        generator.writeStringField("requestId", response.requestId)
    }

    private fun writeCursor(generator: JsonGenerator, cursor: KnownVocabularyCursor?) {
        if (cursor == null) {
            generator.writeNull()
            return
        }
        generator.writeStartObject()
        generator.writeNumberField("ordinal", cursor.ordinal)
        generator.writeStringField("token", cursor.token)
        generator.writeEndObject()
    }

    private fun writeNullableError(generator: JsonGenerator, error: AnkiErrorDetail?) {
        if (error == null) {
            generator.writeNull()
            return
        }
        generator.writeStartObject()
        writeErrorDetail(generator, error)
        generator.writeEndObject()
    }

    private fun writeErrorDetail(generator: JsonGenerator, error: AnkiErrorDetail) {
        generator.writeStringField("code", error.code.wireName)
        generator.writeStringField("message", error.message)
        generator.writeBooleanField("retryable", error.retryable)
    }

    private fun fail(
        category: AnkiProtocolCategory,
        message: String,
        cause: Throwable? = null,
    ): Nothing = throw AnkiProtocolException(category, message, cause)
}
