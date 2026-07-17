package com.ankiminer.android.tts

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonFactoryBuilder
import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.core.exc.StreamConstraintsException
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.core.json.JsonWriteFeature
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal data class SentenceAudioRequest(
    val runId: String,
    val requestId: String,
    val sentence: String,
)

internal enum class SentenceAudioOutcome(
    val wireValue: String,
) {
    READY("ready"),
    UNAVAILABLE("unavailable"),
    FAILED("failed"),
    CANCELLED("cancelled"),
}

internal data class SentenceAudioSynthesis(
    val outcome: SentenceAudioOutcome,
    val file: File? = null,
    val errorCode: String? = null,
) {
    init {
        if (outcome == SentenceAudioOutcome.READY) {
            require(file != null && errorCode == null)
        } else {
            require(file == null && !errorCode.isNullOrBlank())
            require(errorCode in ERROR_CODES)
            require((outcome == SentenceAudioOutcome.CANCELLED) == (errorCode == "cancelled"))
        }
    }

    companion object {
        private val ERROR_CODES =
            setOf(
                "audio_output_too_large",
                "cache_full",
                "cache_publish_failed",
                "cache_unavailable",
                "cancelled",
                "internal_error",
                "invalid_audio_output",
                "invalid_sentence",
                "main_thread_forbidden",
                "network_voice_rejected",
                "offline_japanese_voice_unavailable",
                "offline_voice_changed",
                "synthesis_failed",
                "synthesis_timeout",
                "synthesizer_closed",
                "tts_engine_unavailable",
                "tts_initialization_timeout",
            )

        fun ready(file: File): SentenceAudioSynthesis =
            SentenceAudioSynthesis(SentenceAudioOutcome.READY, file = file)

        fun unavailable(errorCode: String): SentenceAudioSynthesis =
            SentenceAudioSynthesis(SentenceAudioOutcome.UNAVAILABLE, errorCode = errorCode)

        fun failed(errorCode: String): SentenceAudioSynthesis =
            SentenceAudioSynthesis(SentenceAudioOutcome.FAILED, errorCode = errorCode)

        fun cancelled(): SentenceAudioSynthesis =
            SentenceAudioSynthesis(SentenceAudioOutcome.CANCELLED, errorCode = "cancelled")
    }
}

internal fun interface SentenceAudioSynthesizer : AutoCloseable {
    fun synthesize(
        sentence: String,
        cancellationCheck: () -> Boolean,
    ): SentenceAudioSynthesis

    override fun close() = Unit
}

internal fun interface SentenceAudioSynthesizerFactory {
    /** Opens a run-owned synthesizer. The reading runner must close it after Python returns. */
    fun open(): SentenceAudioSynthesizer
}

/** Strict synchronous dispatcher for the reflected EngineCallbacks TTS method. */
internal class SentenceAudioCallbackDispatcher(
    private val synthesizer: SentenceAudioSynthesizer,
) {
    fun synthesizeSentenceAudio(
        rawRequest: String,
        expectedRunId: String,
        cancellationCheck: () -> Boolean,
    ): String {
        val request = SentenceAudioBridgeCodec.decodeRequest(rawRequest, expectedRunId)
        val result =
            if (cancellationCheck()) {
                SentenceAudioSynthesis.cancelled()
            } else {
                try {
                    synthesizer.synthesize(request.sentence, cancellationCheck)
                } catch (_: RuntimeException) {
                    SentenceAudioSynthesis.failed("internal_error")
                }
            }
        return SentenceAudioBridgeCodec.encodeResult(request, result)
    }
}

internal class SentenceAudioProtocolException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

internal object SentenceAudioBridgeCodec {
    const val MAX_REQUEST_UTF8_BYTES = 32 * 1024
    const val MAX_RESULT_UTF8_BYTES = 8 * 1024
    const val MAX_SENTENCE_UTF8_BYTES = 16 * 1024
    private const val MAX_PATH_UTF8_BYTES = 4 * 1024
    private const val MAX_AUDIO_BYTES = 16L * 1024L * 1024L
    private const val MAX_JSON_DEPTH = 8
    private const val MAX_JSON_TOKENS = 64L
    private val runIdPattern = Regex("run_[0-9a-f]{32}")
    private val requestIdPattern = Regex("tts_[0-9a-f]{32}")

    private val factory: JsonFactory =
        JsonFactoryBuilder()
            .streamReadConstraints(
                StreamReadConstraints.builder()
                    .maxNestingDepth(MAX_JSON_DEPTH)
                    .maxDocumentLength(MAX_REQUEST_UTF8_BYTES.toLong())
                    .maxTokenCount(MAX_JSON_TOKENS)
                    .maxNumberLength(16)
                    .maxStringLength(MAX_SENTENCE_UTF8_BYTES)
                    .maxNameLength(64)
                    .build(),
            )
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(JsonWriteFeature.COMBINE_UNICODE_SURROGATES_IN_UTF8)
            .disable(JsonWriteFeature.ESCAPE_NON_ASCII)
            .also { builder -> JsonReadFeature.entries.forEach { builder.disable(it) } }
            .build()

    fun decodeRequest(
        raw: String,
        expectedRunId: String,
    ): SentenceAudioRequest {
        if (!runIdPattern.matches(expectedRunId)) fail("expected run ID is invalid")
        val bytes = strictUtf8(raw, MAX_REQUEST_UTF8_BYTES)
        try {
            factory.createParser(bytes).use { parser ->
                requireToken(parser.nextToken(), JsonToken.START_OBJECT, "request must be a JSON object")
                var schemaVersion: Int? = null
                var messageType: String? = null
                var payload: SentenceAudioRequest? = null
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    requireToken(parser.currentToken(), JsonToken.FIELD_NAME, "request contains an invalid field")
                    when (val field = parser.currentName()) {
                        "schemaVersion" -> {
                            requireToken(parser.nextToken(), JsonToken.VALUE_NUMBER_INT, "schemaVersion must be an integer")
                            schemaVersion = parser.intValue
                        }
                        "type" -> {
                            requireToken(parser.nextToken(), JsonToken.VALUE_STRING, "type must be a string")
                            messageType = parser.text
                        }
                        "payload" -> {
                            requireToken(parser.nextToken(), JsonToken.START_OBJECT, "payload must be an object")
                            payload = readPayload(parser)
                        }
                        else -> fail("unknown request field: $field")
                    }
                }
                if (parser.nextToken() != null) fail("request contains trailing JSON")
                if (schemaVersion != 1) fail("unsupported schemaVersion")
                if (messageType != "tts.sentence.request") fail("unexpected message type")
                val request = payload ?: fail("payload is missing")
                if (request.runId != expectedRunId) fail("request belongs to a stale run")
                return request
            }
        } catch (failure: SentenceAudioProtocolException) {
            throw failure
        } catch (failure: StreamConstraintsException) {
            fail("request exceeds a structural JSON limit", failure)
        } catch (failure: JsonParseException) {
            fail("request is not strict JSON", failure)
        } catch (failure: IOException) {
            fail("request could not be decoded", failure)
        }
    }

    fun encodeResult(
        request: SentenceAudioRequest,
        result: SentenceAudioSynthesis,
    ): String {
        val output = ByteArrayOutputStream()
        try {
            factory.createGenerator(output).use { generator ->
                generator.writeStartObject()
                generator.writeNumberField("schemaVersion", 1)
                generator.writeStringField("type", "tts.sentence.result")
                generator.writeObjectFieldStart("payload")
                generator.writeStringField("runId", request.runId)
                generator.writeStringField("requestId", request.requestId)
                generator.writeStringField("outcome", result.outcome.wireValue)
                if (result.file == null) {
                    generator.writeNullField("path")
                } else {
                    val path = result.file.canonicalFile
                    if (
                        !path.isAbsolute ||
                        !path.isFile ||
                        path.length() !in 1..MAX_AUDIO_BYTES ||
                        !AUDIO_FILENAME.matches(path.name) ||
                        strictUtf8(path.path, MAX_PATH_UTF8_BYTES).isEmpty()
                    ) {
                        fail("synthesized audio path is invalid")
                    }
                    generator.writeStringField("path", path.path)
                }
                if (result.errorCode == null) {
                    generator.writeNullField("errorCode")
                } else {
                    generator.writeStringField("errorCode", result.errorCode)
                }
                generator.writeEndObject()
                generator.writeEndObject()
            }
        } catch (failure: IOException) {
            fail("result could not be encoded", failure)
        }
        val bytes = output.toByteArray()
        if (bytes.size > MAX_RESULT_UTF8_BYTES) fail("result exceeds its UTF-8 limit")
        return bytes.toString(StandardCharsets.UTF_8)
    }

    private fun readPayload(parser: JsonParser): SentenceAudioRequest {
        var runId: String? = null
        var requestId: String? = null
        var sentence: String? = null
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            requireToken(parser.currentToken(), JsonToken.FIELD_NAME, "payload contains an invalid field")
            val field = parser.currentName()
            requireToken(parser.nextToken(), JsonToken.VALUE_STRING, "$field must be a string")
            when (field) {
                "runId" -> runId = parser.text
                "requestId" -> requestId = parser.text
                "sentence" -> sentence = parser.text
                else -> fail("unknown payload field: $field")
            }
        }
        val checkedRunId = runId ?: fail("runId is missing")
        val checkedRequestId = requestId ?: fail("requestId is missing")
        val checkedSentence = sentence ?: fail("sentence is missing")
        if (!runIdPattern.matches(checkedRunId)) fail("runId is invalid")
        if (!requestIdPattern.matches(checkedRequestId)) fail("requestId is invalid")
        if (checkedSentence.isEmpty() || checkedSentence.indexOf('\u0000') >= 0) {
            fail("sentence is empty or contains NUL")
        }
        if (strictUtf8(checkedSentence, MAX_SENTENCE_UTF8_BYTES).isEmpty()) {
            fail("sentence is empty")
        }
        return SentenceAudioRequest(checkedRunId, checkedRequestId, checkedSentence)
    }

    private fun strictUtf8(
        value: String,
        maxBytes: Int,
    ): ByteArray {
        val encoded =
            try {
                StandardCharsets.UTF_8
                    .newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value))
            } catch (failure: Exception) {
                fail("value contains an invalid Unicode scalar", failure)
            }
        if (encoded.remaining() > maxBytes) fail("value exceeds its UTF-8 limit")
        return ByteArray(encoded.remaining()).also { encoded.get(it) }
    }

    private fun requireToken(
        actual: JsonToken?,
        expected: JsonToken,
        message: String,
    ) {
        if (actual != expected) fail(message)
    }

    private fun fail(
        message: String,
        cause: Throwable? = null,
    ): Nothing = throw SentenceAudioProtocolException(message, cause)

    private val AUDIO_FILENAME = Regex("android_tts_v1_[0-9a-f]{64}\\.wav")
}
