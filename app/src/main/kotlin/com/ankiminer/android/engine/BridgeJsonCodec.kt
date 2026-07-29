package com.ankiminer.android.engine

import com.ankiminer.android.anki.generated.UnicodeContractV151
import com.ankiminer.android.mining.CurationCandidate
import com.ankiminer.android.mining.CurationPage
import com.ankiminer.android.mining.CurationRequest
import com.ankiminer.android.mining.CurationSelection
import com.ankiminer.android.mining.CurationSentence
import com.ankiminer.android.mining.CURATION_PAGE_MAX_CANDIDATES
import com.ankiminer.android.mining.ProcessingResult
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonFactoryBuilder
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.core.exc.StreamConstraintsException
import com.fasterxml.jackson.core.io.CharacterEscapes
import com.fasterxml.jackson.core.io.SerializedString
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.core.json.JsonWriteFeature
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.InvalidPathException
import java.nio.file.Paths

/** Strict JSON codec for every non-Anki message crossing the Chaquopy boundary. */
object BridgeJsonCodec {
    const val MAX_ENVELOPE_UTF8_BYTES = 32 * 1024 * 1024
    const val MAX_READING_RUN_UTF8_BYTES = 1024 * 1024
    const val MAX_READING_SERIES_NAME_UTF8_BYTES = 1024
    const val MAX_CURATION_PAGE_UTF8_BYTES = 512 * 1024
    private const val MAX_JSON_DEPTH = 128
    private const val MAX_JSON_TOKENS = 1_000_000L
    private const val MAX_JSON_NUMBER_CHARS = 1000
    private val minimumSignedLong = BigInteger.valueOf(Long.MIN_VALUE)
    private val maximumSignedLong = BigInteger.valueOf(Long.MAX_VALUE)
    private const val POSITIVE_LONG_BOUNDARY_AS_DOUBLE = 9.223372036854776E18
    private const val NEGATIVE_LONG_BOUNDARY_AS_DOUBLE = -9.223372036854776E18

    private val messageTypePattern = Regex("[a-z][a-z0-9]*(?:\\.[a-z][a-z0-9]*)+")
    private val runIdPattern = Regex("run_[0-9a-f]{32}")
    private val curationIdPattern = Regex("curation_[0-9a-f]{32}")
    private val candidateIdPattern = Regex("candidate_[0-9a-f]{32}")
    private val sentenceIdPattern = Regex("sentence_[0-9a-f]{32}")
    private val tokenizerResourceIdPattern = Regex("[A-Za-z0-9](?:[A-Za-z0-9._-]{0,126}[A-Za-z0-9_-])?")
    private val configResourceIdPattern = Regex("(?!.*\\.\\.)[A-Za-z0-9](?:[A-Za-z0-9._-]{0,126}[A-Za-z0-9_-])?")
    private val sha256Pattern = Regex("[0-9a-f]{64}")
    private val errorCodePattern = Regex("[a-z][a-z0-9]*(?:_[a-z0-9]+)*")

    private val factory: JsonFactory =
        JsonFactoryBuilder()
            .streamReadConstraints(
                StreamReadConstraints.builder()
                    .maxNestingDepth(MAX_JSON_DEPTH)
                    .maxDocumentLength(MAX_ENVELOPE_UTF8_BYTES.toLong())
                    .maxTokenCount(MAX_JSON_TOKENS)
                    .maxNumberLength(MAX_JSON_NUMBER_CHARS)
                    .maxStringLength(MAX_ENVELOPE_UTF8_BYTES)
                    .maxNameLength(MAX_ENVELOPE_UTF8_BYTES)
                    .build(),
            )
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(JsonWriteFeature.COMBINE_UNICODE_SURROGATES_IN_UTF8)
            .disable(JsonWriteFeature.ESCAPE_NON_ASCII)
            .also { builder -> JsonReadFeature.entries.forEach { builder.disable(it) } }
            .characterEscapes(PythonCanonicalEscapes)
            .build()

    fun decode(
        raw: String,
        expectedRunId: String? = null,
        expectedRequestId: String? = null,
    ): BridgeMessage {
        if (raw.startsWith('\uFEFF')) fail(BridgeProtocolCategory.INVALID_JSON, "a leading BOM is not JSON whitespace")
        val bytes = strictUtf8(raw)
        rejectOversizedJsonNumbers(raw)
        val decoded =
            try {
                factory.createParser(bytes).use { parser -> readEnvelope(parser, raw) }
            } catch (error: BridgeProtocolException) {
                throw error
            } catch (error: StreamConstraintsException) {
                fail(BridgeProtocolCategory.INVALID_JSON, "bridge JSON exceeds a structural limit", error)
            } catch (error: JsonParseException) {
                val duplicate = error.originalMessage.contains("Duplicate field", ignoreCase = true)
                val surrogate = error.originalMessage.contains("surrogate", ignoreCase = true)
                when {
                    duplicate -> fail(BridgeProtocolCategory.DUPLICATE_JSON_KEY, "bridge JSON contains a duplicate key", error)
                    surrogate -> fail(BridgeProtocolCategory.INVALID_UTF8, "bridge JSON contains an invalid Unicode scalar", error)
                    else -> fail(BridgeProtocolCategory.INVALID_JSON, "malformed bridge JSON", error)
                }
            } catch (error: IOException) {
                fail(BridgeProtocolCategory.INVALID_JSON, "malformed bridge JSON", error)
            } catch (error: IllegalArgumentException) {
                fail(BridgeProtocolCategory.INVALID_VALUE, "bridge payload violates its model invariants", error)
            }
        val (runId, requestId) = identifiers(decoded)
        if (expectedRunId != null && runId != null && runId != expectedRunId) {
            fail(BridgeProtocolCategory.STALE_RUN, "bridge message belongs to a stale run")
        }
        if (expectedRequestId != null && requestId != null && requestId != expectedRequestId) {
            fail(BridgeProtocolCategory.STALE_REQUEST, "bridge message belongs to a stale request")
        }
        return decoded
    }

    fun encodeBootstrapInitialize(filesDir: String): String =
        encode("bootstrap.initialize") { generator -> generator.writeStringField("filesDir", filesDir) }

    fun encodeTokenizerConfigure(configuration: TokenizerConfiguration): String =
        encode("tokenizer.configure") { generator -> writeTokenizerConfiguration(generator, configuration) }

    fun encodeTokenizerReady(identity: TokenizerIdentity): String =
        encode("tokenizer.ready") { generator ->
            generator.writeStringField("backend", identity.backend)
            generator.writeStringField("resourceId", identity.resourceId)
            generator.writeStringField("dicDir", identity.dicDir)
            generator.writeStringField("treeSha256", identity.treeSha256)
            generator.writeNumberField("fileCount", identity.fileCount)
            generator.writeNumberField("totalBytes", identity.totalBytes)
        }

    fun encodeVideoRun(request: VideoMiningWireRequest): String =
        encode("mining.video.run") { generator -> writeVideoRequest(generator, request) }

    fun encodeReadingRun(request: ReadingMiningWireRequest): String =
        encode("mining.reading.run") { generator -> writeReadingRequest(generator, request) }

    fun encodeRegistrationAccepted(runId: String): String =
        encode("job.registration.accepted") { generator -> generator.writeStringField("runId", runId) }

    fun encodeJobCancel(runId: String): String =
        encode("job.cancel") { generator -> generator.writeStringField("runId", runId) }

    fun encodeCurationResponse(
        request: CurationRequest,
        selection: List<CurationSelection>?,
    ): String {
        validateSelection(request, selection)
        val type = if (request.page == null) "curation.response" else "curation.page.response"
        return encode(type) { generator ->
            generator.writeStringField("runId", request.runId)
            generator.writeStringField("requestId", request.requestId)
            request.page?.let { generator.writeNumberField("pageIndex", it.pageIndex) }
            generator.writeFieldName("selection")
            if (selection == null) {
                generator.writeNull()
            } else {
                generator.writeStartArray()
                selection.forEach { chosen ->
                    generator.writeStartObject()
                    generator.writeStringField("candidateId", chosen.candidateId)
                    chosen.sentenceId?.let { generator.writeStringField("sentenceId", it) }
                    generator.writeEndObject()
                }
                generator.writeEndArray()
            }
        }
    }

    private fun readEnvelope(
        parser: JsonParser,
        raw: String,
    ): BridgeMessage {
        expectNext(parser, JsonToken.START_OBJECT, "bridge envelope must be an object", BridgeProtocolCategory.INVALID_ENVELOPE)
        val envelope = readObject(parser, "bridge envelope")
        if (parser.nextToken() != null) fail(BridgeProtocolCategory.INVALID_JSON, "bridge envelope has trailing JSON tokens")
        requireExact(envelope, setOf("schemaVersion", "type", "payload"), "bridge envelope", BridgeProtocolCategory.INVALID_ENVELOPE)
        if (integral(envelope.getValue("schemaVersion"), "schemaVersion") != 1L) {
            fail(BridgeProtocolCategory.UNSUPPORTED_SCHEMA_VERSION, "unsupported bridge schema version")
        }
        val type = text(envelope.getValue("type"), "message type")
        if (!messageTypePattern.matches(type)) fail(BridgeProtocolCategory.INVALID_MESSAGE_TYPE, "bridge message type is invalid")
        val payload = objectValue(envelope.getValue("payload"), "bridge payload")
        return readTyped(type, payload, raw)
    }

    private fun readTyped(
        type: String,
        payload: Map<String, BridgeJsonValue>,
        raw: String,
    ): BridgeMessage =
        when (type) {
            "bootstrap.initialize" -> {
                requireExact(payload, setOf("filesDir"), type)
                BridgeMessage.BootstrapInitialize(absolutePath(payload.getValue("filesDir"), "filesDir"))
            }
            "bootstrap.ready" -> {
                requireExact(payload, setOf("home"), type)
                BridgeMessage.BootstrapReady(absolutePath(payload.getValue("home"), "home"))
            }
            "tokenizer.configure" -> BridgeMessage.TokenizerConfigure(readTokenizerConfiguration(payload))
            "tokenizer.ready" -> BridgeMessage.TokenizerReady(readTokenizerIdentity(payload))
            "bridge.error" -> readBridgeError(payload)
            "mining.video.run" -> BridgeMessage.VideoRun(readVideoRequest(payload))
            "mining.reading.run" -> {
                if (strictUtf8(raw).size > MAX_READING_RUN_UTF8_BYTES) {
                    fail(BridgeProtocolCategory.INPUT_TOO_LARGE, "reading mining request is too large")
                }
                BridgeMessage.ReadingRun(readReadingRequest(payload))
            }
            "job.registration.request" -> BridgeMessage.JobRegistrationRequest(singleRunId(payload, type))
            "job.registration.accepted" -> BridgeMessage.JobRegistrationAccepted(singleRunId(payload, type))
            "progress.start" -> readProgressStart(payload)
            "progress.update" -> readProgressUpdate(payload)
            "progress.complete" -> BridgeMessage.ProgressComplete(singleRunId(payload, type))
            "progress.error" -> readProgressError(payload)
            "presenter.event" -> BridgeMessage.Presenter(readPresenter(payload))
            "curation.request" -> {
                requireCurationEnvelopeBound(raw)
                BridgeMessage.CurationNeeded(readCurationRequest(payload, paged = false))
            }
            "curation.page.request" -> {
                requireCurationEnvelopeBound(raw)
                BridgeMessage.CurationNeeded(readCurationRequest(payload, paged = true))
            }
            "curation.response" -> readCurationResponse(payload)
            "curation.page.response" -> readCurationPageResponse(payload)
            "curation.accepted" -> readCurationAccepted(payload)
            "curation.page.accepted" -> readCurationPageAccepted(payload)
            "job.cancel" -> BridgeMessage.JobCancel(singleRunId(payload, type))
            "job.cancelled" -> readJobCancelled(payload)
            "mining.terminal" -> readTerminal(payload, raw)
            else -> fail(BridgeProtocolCategory.UNSUPPORTED_MESSAGE_TYPE, "unsupported bridge message type")
        }

    private fun readBridgeError(payload: Map<String, BridgeJsonValue>): BridgeMessage.Error {
        val allowed = setOf("code", "message", "requestType")
        if (payload.keys !in setOf(setOf("code", "message"), allowed)) {
            fail(BridgeProtocolCategory.INVALID_PAYLOAD, "bridge.error has missing or unknown fields")
        }
        val code = text(payload.getValue("code"), "bridge error code")
        if (!errorCodePattern.matches(code)) fail(BridgeProtocolCategory.INVALID_VALUE, "bridge error code is invalid")
        val requestType = payload["requestType"]?.let { text(it, "bridge error request type") }
        if (requestType != null && !messageTypePattern.matches(requestType)) {
            fail(BridgeProtocolCategory.INVALID_VALUE, "bridge error request type is invalid")
        }
        return BridgeMessage.Error(code, text(payload.getValue("message"), "bridge error message"), requestType)
    }

    private fun readProgressStart(payload: Map<String, BridgeJsonValue>): BridgeMessage.ProgressStart {
        requireExact(payload, setOf("runId", "total", "description"), "progress.start")
        val total = integral(payload.getValue("total"), "progress total")
        if (total < 0) fail(BridgeProtocolCategory.INVALID_VALUE, "progress total must be non-negative")
        return BridgeMessage.ProgressStart(runId(payload.getValue("runId")), total, text(payload.getValue("description"), "progress description"))
    }

    private fun readProgressUpdate(payload: Map<String, BridgeJsonValue>): BridgeMessage.ProgressUpdate {
        requireExact(payload, setOf("runId", "current", "description"), "progress.update")
        val current = integral(payload.getValue("current"), "progress current")
        if (current < 0) fail(BridgeProtocolCategory.INVALID_VALUE, "progress current must be non-negative")
        return BridgeMessage.ProgressUpdate(runId(payload.getValue("runId")), current, text(payload.getValue("description"), "progress description"))
    }

    private fun readProgressError(payload: Map<String, BridgeJsonValue>): BridgeMessage.ProgressError {
        requireExact(payload, setOf("runId", "description", "message"), "progress.error")
        return BridgeMessage.ProgressError(
            runId(payload.getValue("runId")),
            text(payload.getValue("description"), "progress description"),
            text(payload.getValue("message"), "progress error message"),
        )
    }

    private fun readPresenter(payload: Map<String, BridgeJsonValue>): PresenterEvent {
        val runId = runId(payload["runId"] ?: missing("presenter runId"))
        return when (val kind = text(payload["kind"] ?: missing("presenter kind"), "presenter kind")) {
            "info", "success", "warning", "error" -> {
                requireExact(payload, setOf("runId", "kind", "message"), "presenter message")
                val typedKind = PresenterMessageKind.entries.single { it.wireName == kind }
                PresenterEvent.Message(runId, typedKind, text(payload.getValue("message"), "presenter message"))
            }
            "validation" -> {
                requireExact(payload, setOf("runId", "kind", "result"), "presenter validation")
                PresenterEvent.Validation(runId, readValidationResult(objectValue(payload.getValue("result"), "validation result")))
            }
            "processingResult" -> {
                requireExact(payload, setOf("runId", "kind", "result"), "presenter processing result")
                PresenterEvent.Processing(runId, readProcessingResult(objectValue(payload.getValue("result"), "processing result")))
            }
            else -> fail(BridgeProtocolCategory.INVALID_VALUE, "presenter kind is invalid")
        }
    }

    private fun readValidationResult(payload: Map<String, BridgeJsonValue>): ValidationResult {
        requireExact(
            payload,
            setOf("ankiconnectOk", "ffmpegOk", "deckExists", "noteTypeExists", "issues", "ffprobeOk"),
            "validation result",
        )
        val issues = array(payload.getValue("issues"), "validation issues").map { rawIssue ->
            val issue = objectValue(rawIssue, "validation issue")
            requireExact(issue, setOf("component", "severity", "message"), "validation issue")
            val severity =
                try {
                    ValidationSeverity.valueOf(text(issue.getValue("severity"), "validation severity"))
                } catch (_: IllegalArgumentException) {
                    fail(BridgeProtocolCategory.INVALID_VALUE, "validation severity is invalid")
                }
            ValidationIssue(
                text(issue.getValue("component"), "validation component"),
                severity,
                text(issue.getValue("message"), "validation issue message"),
            )
        }
        return ValidationResult(
            bool(payload.getValue("ankiconnectOk"), "ankiconnectOk"),
            bool(payload.getValue("ffmpegOk"), "ffmpegOk"),
            bool(payload.getValue("deckExists"), "deckExists"),
            bool(payload.getValue("noteTypeExists"), "noteTypeExists"),
            issues,
            bool(payload.getValue("ffprobeOk"), "ffprobeOk"),
        )
    }

    private fun readProcessingResult(payload: Map<String, BridgeJsonValue>): ProcessingResult {
        requireExact(
            payload,
            setOf(
                "totalWordsFound",
                "newWordsFound",
                "cardsCreated",
                "errors",
                "elapsedTime",
                "comprehensionPercentage",
                "cardIds",
                "videoFile",
                "subtitleFile",
                "minedForms",
            ),
            "processing result",
        )
        val totalWords = nonNegative(payload.getValue("totalWordsFound"), "totalWordsFound")
        val newWords = nonNegative(payload.getValue("newWordsFound"), "newWordsFound")
        val cardsCreated = nonNegative(payload.getValue("cardsCreated"), "cardsCreated")
        val comprehension = number(payload.getValue("comprehensionPercentage"), "comprehensionPercentage")
        if (comprehension !in 0.0..100.0) fail(BridgeProtocolCategory.INVALID_VALUE, "comprehensionPercentage is outside 0 through 100")
        val cardIds = array(payload.getValue("cardIds"), "cardIds").map { positive(it, "cardId") }
        if (cardIds.toSet().size != cardIds.size) fail(BridgeProtocolCategory.INVALID_VALUE, "cardIds must be unique")
        return ProcessingResult(
            totalWords,
            newWords,
            cardsCreated,
            stringArray(payload.getValue("errors"), "processing errors"),
            number(payload.getValue("elapsedTime"), "elapsedTime"),
            comprehension,
            cardIds,
            text(payload.getValue("videoFile"), "videoFile"),
            text(payload.getValue("subtitleFile"), "subtitleFile"),
            stringArray(payload.getValue("minedForms"), "minedForms"),
        )
    }

    private fun readTerminal(
        payload: Map<String, BridgeJsonValue>,
        raw: String,
    ): BridgeMessage.Terminal {
        requireExact(payload, setOf("runId", "outcome", "result", "error"), "mining.terminal")
        val runId = runId(payload.getValue("runId"))
        val outcome = MiningOutcome.entries.find { it.wireName == text(payload.getValue("outcome"), "terminal outcome") }
            ?: fail(BridgeProtocolCategory.INVALID_VALUE, "terminal outcome is invalid")
        val result = nullableObject(payload.getValue("result"), "terminal result")?.let(::readProcessingResult)
        val error = nullableObject(payload.getValue("error"), "terminal error")?.let(::readTerminalError)
        validateTerminal(outcome, result, error)
        return BridgeMessage.Terminal(runId, outcome, result, error, raw)
    }

    private fun readTerminalError(payload: Map<String, BridgeJsonValue>): TerminalError {
        requireExact(payload, setOf("code", "message"), "terminal error")
        val code = text(payload.getValue("code"), "terminal error code")
        if (!errorCodePattern.matches(code)) fail(BridgeProtocolCategory.INVALID_VALUE, "terminal error code is invalid")
        return TerminalError(code, text(payload.getValue("message"), "terminal error message"))
    }

    private fun validateTerminal(
        outcome: MiningOutcome,
        result: ProcessingResult?,
        error: TerminalError?,
    ) {
        val cancellationMarker = "Processing cancelled by user"
        when (outcome) {
            MiningOutcome.SUCCESS ->
                if (result == null || result.errors.isNotEmpty() || error != null) {
                    fail(BridgeProtocolCategory.INVALID_VALUE, "successful terminal has inconsistent result or error")
                }
            MiningOutcome.CANCELLED -> {
                val retained = result != null && cancellationMarker in result.errors && error == null
                val raised = result == null && error?.code == "cancelled"
                if (!retained && !raised) fail(BridgeProtocolCategory.INVALID_VALUE, "cancelled terminal has inconsistent result or error")
            }
            MiningOutcome.FAILED -> {
                if (error == null || error.code == "cancelled") {
                    fail(BridgeProtocolCategory.INVALID_VALUE, "failed terminal requires a non-cancellation error")
                }
                when (error.code) {
                    "processing_failed" -> {
                        if (result != null && (result.errors.isEmpty() || cancellationMarker in result.errors)) {
                            fail(BridgeProtocolCategory.INVALID_VALUE, "processing failure retained an invalid result")
                        }
                    }
                    "cleanup_failed" -> if (result == null) fail(BridgeProtocolCategory.INVALID_VALUE, "cleanup failure must retain its result")
                    else -> if (result != null) fail(BridgeProtocolCategory.INVALID_VALUE, "raised failure cannot retain a result")
                }
            }
        }
    }

    private fun requireCurationEnvelopeBound(raw: String) {
        if (strictUtf8(raw).size > MAX_CURATION_PAGE_UTF8_BYTES) {
            fail(BridgeProtocolCategory.INPUT_TOO_LARGE, "curation request page is too large")
        }
    }

    private fun readCurationRequest(
        payload: Map<String, BridgeJsonValue>,
        paged: Boolean,
    ): CurationRequest {
        val context = if (paged) "curation.page.request" else "curation.request"
        requireExact(
            payload,
            if (paged) {
                setOf(
                    "runId",
                    "requestId",
                    "pageIndex",
                    "pageCount",
                    "candidateStart",
                    "totalCandidates",
                    "candidates",
                )
            } else {
                setOf("runId", "requestId", "candidates")
            },
            context,
        )
        val candidates = array(payload.getValue("candidates"), "curation candidates").map { rawCandidate ->
            readCurationCandidate(objectValue(rawCandidate, "curation candidate"))
        }
        if (candidates.size > CURATION_PAGE_MAX_CANDIDATES) {
            fail(BridgeProtocolCategory.INVALID_VALUE, "curation request exceeds its candidate limit")
        }
        if (paged && candidates.isEmpty()) {
            fail(BridgeProtocolCategory.INVALID_VALUE, "curation page must not be empty")
        }
        val page =
            if (paged) {
                CurationPage(
                    pageIndex = nonNegative(payload.getValue("pageIndex"), "curation page index"),
                    pageCount = positive(payload.getValue("pageCount"), "curation page count"),
                    candidateStart = nonNegative(payload.getValue("candidateStart"), "curation candidate start"),
                    totalCandidates = positive(payload.getValue("totalCandidates"), "curation total candidates"),
                )
            } else {
                null
            }
        return CurationRequest(
            runId(payload.getValue("runId")),
            opaque(payload.getValue("requestId"), curationIdPattern, "curation request ID"),
            candidates,
            page,
        )
    }

    private fun readCurationCandidate(payload: Map<String, BridgeJsonValue>): CurationCandidate {
        requireExact(
            payload,
            setOf(
                "candidateId",
                "minedForm",
                "surface",
                "lemma",
                "reading",
                "expressionReading",
                "partOfSpeech",
                "frequencyRank",
                "occurrenceCount",
                "defaultSentenceId",
                "sentences",
            ),
            "curation candidate",
        )
        val sentences = array(payload.getValue("sentences"), "curation sentences").map { rawSentence ->
            readCurationSentence(objectValue(rawSentence, "curation sentence"))
        }
        return CurationCandidate(
            opaque(payload.getValue("candidateId"), candidateIdPattern, "candidate ID"),
            text(payload.getValue("minedForm"), "minedForm"),
            text(payload.getValue("surface"), "surface"),
            text(payload.getValue("lemma"), "lemma"),
            text(payload.getValue("reading"), "reading"),
            text(payload.getValue("expressionReading"), "expressionReading"),
            nullableText(payload.getValue("partOfSpeech"), "partOfSpeech"),
            nullableIntegral(payload.getValue("frequencyRank"), "frequencyRank"),
            nonNegative(payload.getValue("occurrenceCount"), "occurrenceCount"),
            opaque(payload.getValue("defaultSentenceId"), sentenceIdPattern, "default sentence ID"),
            sentences,
        )
    }

    private fun readCurationSentence(payload: Map<String, BridgeJsonValue>): CurationSentence {
        requireExact(
            payload,
            setOf("sentenceId", "sentence", "sentenceFurigana", "sentenceReading", "startTime", "endTime", "duration"),
            "curation sentence",
        )
        return CurationSentence(
            opaque(payload.getValue("sentenceId"), sentenceIdPattern, "sentence ID"),
            text(payload.getValue("sentence"), "sentence"),
            text(payload.getValue("sentenceFurigana"), "sentenceFurigana"),
            text(payload.getValue("sentenceReading"), "sentenceReading"),
            number(payload.getValue("startTime"), "startTime"),
            number(payload.getValue("endTime"), "endTime"),
            number(payload.getValue("duration"), "duration"),
        )
    }

    private fun readCurationResponse(payload: Map<String, BridgeJsonValue>): BridgeMessage.CurationResponse {
        requireExact(payload, setOf("runId", "requestId", "selection"), "curation.response")
        return BridgeMessage.CurationResponse(
            runId(payload.getValue("runId")),
            opaque(payload.getValue("requestId"), curationIdPattern, "curation request ID"),
            readSelections(payload.getValue("selection")),
        )
    }

    private fun readCurationPageResponse(payload: Map<String, BridgeJsonValue>): BridgeMessage.CurationPageResponse {
        requireExact(payload, setOf("runId", "requestId", "pageIndex", "selection"), "curation.page.response")
        return BridgeMessage.CurationPageResponse(
            runId(payload.getValue("runId")),
            opaque(payload.getValue("requestId"), curationIdPattern, "curation request ID"),
            nonNegative(payload.getValue("pageIndex"), "curation page index"),
            readSelections(payload.getValue("selection")),
        )
    }

    private fun readSelections(value: BridgeJsonValue): List<CurationSelection>? {
        val selections =
            when (value) {
                BridgeJsonValue.Null -> null
                else -> array(value, "curation selection").map { rawSelection -> readSelection(objectValue(rawSelection, "curation selection")) }
            }
        if (selections != null && selections.size > CURATION_PAGE_MAX_CANDIDATES) {
            fail(BridgeProtocolCategory.INVALID_VALUE, "curation selection exceeds its candidate limit")
        }
        if (selections != null && selections.map { it.candidateId }.toSet().size != selections.size) {
            fail(BridgeProtocolCategory.INVALID_VALUE, "a curation candidate may only be selected once")
        }
        return selections
    }

    private fun readSelection(payload: Map<String, BridgeJsonValue>): CurationSelection {
        if (payload.keys !in setOf(setOf("candidateId"), setOf("candidateId", "sentenceId"))) {
            fail(BridgeProtocolCategory.INVALID_PAYLOAD, "curation selection has missing or unknown fields")
        }
        return CurationSelection(
            opaque(payload.getValue("candidateId"), candidateIdPattern, "candidate ID"),
            payload["sentenceId"]?.let { opaque(it, sentenceIdPattern, "sentence ID") },
        )
    }

    private fun readCurationAccepted(payload: Map<String, BridgeJsonValue>): BridgeMessage.CurationAccepted {
        requireExact(payload, setOf("runId", "requestId"), "curation.accepted")
        return BridgeMessage.CurationAccepted(
            runId(payload.getValue("runId")),
            opaque(payload.getValue("requestId"), curationIdPattern, "curation request ID"),
        )
    }

    private fun readCurationPageAccepted(payload: Map<String, BridgeJsonValue>): BridgeMessage.CurationPageAccepted {
        requireExact(
            payload,
            setOf("runId", "requestId", "pageIndex", "finalPage"),
            "curation.page.accepted",
        )
        return BridgeMessage.CurationPageAccepted(
            runId(payload.getValue("runId")),
            opaque(payload.getValue("requestId"), curationIdPattern, "curation request ID"),
            nonNegative(payload.getValue("pageIndex"), "curation page index"),
            bool(payload.getValue("finalPage"), "curation final page"),
        )
    }

    private fun readJobCancelled(payload: Map<String, BridgeJsonValue>): BridgeMessage.JobCancelled {
        requireExact(payload, setOf("runId", "newlyCancelled"), "job.cancelled")
        return BridgeMessage.JobCancelled(runId(payload.getValue("runId")), bool(payload.getValue("newlyCancelled"), "newlyCancelled"))
    }

    private fun readTokenizerConfiguration(payload: Map<String, BridgeJsonValue>): TokenizerConfiguration {
        requireExact(payload, setOf("dicDir", "resourceId", "treeSha256", "backend"), "tokenizer.configure")
        val configuration =
            TokenizerConfiguration(
                absolutePath(payload.getValue("dicDir"), "dicDir"),
                tokenizerResourceId(payload.getValue("resourceId")),
                sha256(payload.getValue("treeSha256")),
                text(payload.getValue("backend"), "tokenizer backend"),
            )
        if (configuration.backend != "s1a") fail(BridgeProtocolCategory.INVALID_VALUE, "Android tokenizer backend must be s1a")
        return configuration
    }

    private fun readTokenizerIdentity(payload: Map<String, BridgeJsonValue>): TokenizerIdentity {
        requireExact(
            payload,
            setOf("dicDir", "resourceId", "treeSha256", "backend", "fileCount", "totalBytes"),
            "tokenizer.ready",
        )
        val configuration = readTokenizerConfiguration(payload - setOf("fileCount", "totalBytes"))
        return TokenizerIdentity(
            configuration.dicDir,
            configuration.resourceId,
            configuration.treeSha256,
            configuration.backend,
            nonNegative(payload.getValue("fileCount"), "fileCount"),
            nonNegative(payload.getValue("totalBytes"), "totalBytes"),
        )
    }

    private fun readVideoRequest(payload: Map<String, BridgeJsonValue>): VideoMiningWireRequest {
        requireExact(
            payload,
            setOf(
                "videoPath",
                "subtitlePath",
                "episodeName",
                "seriesName",
                "sourceLabel",
                "audioTrackOverride",
                "cacheDir",
                "nativeLibraryDir",
                "configSnapshot",
            ),
            "mining.video.run",
        )
        val subtitlePath = absolutePath(payload.getValue("subtitlePath"), "subtitlePath")
        if (!subtitlePath.lowercase().let { it.endsWith(".ass") || it.endsWith(".srt") || it.endsWith(".ssa") || it.endsWith(".vtt") }) {
            fail(BridgeProtocolCategory.INVALID_VALUE, "subtitlePath must preserve a supported suffix")
        }
        return VideoMiningWireRequest(
            absolutePath(payload.getValue("videoPath"), "videoPath"),
            subtitlePath,
            canonicalLabel(payload.getValue("episodeName"), "episodeName"),
            canonicalLabel(payload.getValue("seriesName"), "seriesName"),
            nullableCanonicalLabel(payload.getValue("sourceLabel"), "sourceLabel"),
            nullableNonNegative(payload.getValue("audioTrackOverride"), "audioTrackOverride"),
            absolutePath(payload.getValue("cacheDir"), "cacheDir"),
            absolutePath(payload.getValue("nativeLibraryDir"), "nativeLibraryDir"),
            readConfigSnapshot(objectValue(payload.getValue("configSnapshot"), "configSnapshot")),
        )
    }

    private fun readReadingRequest(payload: Map<String, BridgeJsonValue>): ReadingMiningWireRequest {
        requireExact(
            payload,
            setOf(
                "sourceKind",
                "sourcePath",
                "imageArchivePath",
                "seriesName",
                "cacheDir",
                "nativeLibraryDir",
                "configSnapshot",
            ),
            "mining.reading.run",
        )
        val sourceKindText = text(payload.getValue("sourceKind"), "sourceKind")
        val sourceKind =
            ReadingMiningSourceKind.entries.singleOrNull { it.wireName == sourceKindText }
                ?: fail(BridgeProtocolCategory.INVALID_VALUE, "sourceKind is invalid")
        val sourcePath = boundedAbsolutePath(payload.getValue("sourcePath"), "sourcePath")
        val archivePath =
            payload.getValue("imageArchivePath").let { value ->
                if (value is BridgeJsonValue.Null) null else boundedAbsolutePath(value, "imageArchivePath")
            }
        val cacheDir = boundedAbsolutePath(payload.getValue("cacheDir"), "cacheDir")
        val nativeLibraryDir =
            boundedAbsolutePath(payload.getValue("nativeLibraryDir"), "nativeLibraryDir")
        val seriesName =
            payload.getValue("seriesName").let { value ->
                if (value is BridgeJsonValue.Null) {
                    null
                } else {
                    canonicalLabel(value, "seriesName").also {
                        if (strictUtf8(it).size > MAX_READING_SERIES_NAME_UTF8_BYTES) {
                            fail(
                                BridgeProtocolCategory.INVALID_VALUE,
                                "seriesName exceeds its UTF-8 byte limit",
                            )
                        }
                    }
                }
            }

        requireReadingPathInsideCache(sourcePath, cacheDir, "sourcePath")
        requireReadingSuffix(sourcePath, sourceKind)
        when (sourceKind) {
            ReadingMiningSourceKind.MOKURO -> {
                if (seriesName != null) {
                    fail(BridgeProtocolCategory.INVALID_VALUE, "seriesName is only valid for subtitles")
                }
                archivePath?.let { archive ->
                    requireReadingPathInsideCache(archive, cacheDir, "imageArchivePath")
                    if (!archive.lowercase().let { it.endsWith(".cbz") || it.endsWith(".zip") }) {
                        fail(
                            BridgeProtocolCategory.INVALID_VALUE,
                            "imageArchivePath must preserve a supported archive suffix",
                        )
                    }
                    requireMokuroCompanion(sourcePath, archive)
                }
            }
            ReadingMiningSourceKind.SUBTITLE -> {
                if (archivePath != null) {
                    fail(
                        BridgeProtocolCategory.INVALID_VALUE,
                        "imageArchivePath is only valid for a mokuro source",
                    )
                }
                if (seriesName == null) {
                    fail(BridgeProtocolCategory.INVALID_VALUE, "seriesName is required for subtitles")
                }
            }
            ReadingMiningSourceKind.TXT, ReadingMiningSourceKind.EPUB -> {
                if (archivePath != null) {
                    fail(
                        BridgeProtocolCategory.INVALID_VALUE,
                        "imageArchivePath is only valid for a mokuro source",
                    )
                }
                if (seriesName != null) {
                    fail(BridgeProtocolCategory.INVALID_VALUE, "seriesName is only valid for subtitles")
                }
            }
        }
        return ReadingMiningWireRequest(
            sourceKind = sourceKind,
            sourcePath = sourcePath,
            imageArchivePath = archivePath,
            seriesName = seriesName,
            cacheDir = cacheDir,
            nativeLibraryDir = nativeLibraryDir,
            configSnapshot =
                readConfigSnapshot(objectValue(payload.getValue("configSnapshot"), "configSnapshot")),
        )
    }

    private fun requireReadingSuffix(
        sourcePath: String,
        sourceKind: ReadingMiningSourceKind,
    ) {
        val suffixes =
            when (sourceKind) {
                ReadingMiningSourceKind.TXT -> setOf(".txt")
                ReadingMiningSourceKind.EPUB -> setOf(".epub")
                ReadingMiningSourceKind.SUBTITLE -> setOf(".ass", ".srt", ".ssa", ".vtt")
                ReadingMiningSourceKind.MOKURO -> setOf(".mokuro")
            }
        if (suffixes.none(sourcePath.lowercase()::endsWith)) {
            fail(BridgeProtocolCategory.INVALID_VALUE, "sourcePath suffix does not match sourceKind")
        }
    }

    private fun requireReadingPathInsideCache(
        candidate: String,
        cacheDir: String,
        context: String,
    ) {
        val candidatePath = normalizedPath(candidate, context)
        val cachePath = normalizedPath(cacheDir, "cacheDir")
        if (candidatePath == cachePath || !candidatePath.startsWith(cachePath)) {
            fail(BridgeProtocolCategory.INVALID_VALUE, "$context must be inside cacheDir")
        }
    }

    private fun requireMokuroCompanion(
        sourcePath: String,
        archivePath: String,
    ) {
        val source = normalizedPath(sourcePath, "sourcePath")
        val archive = normalizedPath(archivePath, "imageArchivePath")
        val sourceName = source.fileName.toString()
        val archiveName = archive.fileName.toString()
        if (
            source.parent != archive.parent ||
            sourceName.substringBeforeLast('.', sourceName) !=
            archiveName.substringBeforeLast('.', archiveName)
        ) {
            fail(
                BridgeProtocolCategory.INVALID_VALUE,
                "imageArchivePath must be a same-directory, same-stem mokuro companion",
            )
        }
    }

    private fun normalizedPath(
        value: String,
        context: String,
    ) =
        try {
            Paths.get(value).normalize()
        } catch (failure: InvalidPathException) {
            fail(BridgeProtocolCategory.INVALID_VALUE, "$context is not a valid path", failure)
        }

    private fun readConfigSnapshot(payload: Map<String, BridgeJsonValue>): MiningConfigSnapshot {
        if (payload.keys !in setOf(setOf("settings"), setOf("settings", "androidTtsEnabled"))) {
            fail(BridgeProtocolCategory.INVALID_PAYLOAD, "configSnapshot has missing or unknown fields")
        }
        val settings = objectValue(payload.getValue("settings"), "config settings")
        validateSettings(settings)
        return MiningConfigSnapshot(
            settings,
            payload["androidTtsEnabled"]?.let { bool(it, "androidTtsEnabled") },
        )
    }

    private fun validateSettings(settings: Map<String, BridgeJsonValue>) {
        val known =
            setOf(
                "anki_deck_name", "anki_note_type", "anki_fields", "card_type", "card_type_marker_fields",
                "anki_tags", "excluded_decks", "audio_padding", "screenshot_offset", "audio_format", "audio_bitrate",
                "screenshot_animated", "subtitle_offset", "allowed_pos", "excluded_subtypes", "excluded_wordsets",
                "dictionary_chain", "jisho_delay", "expression_audio_chain", "reading_tts_enabled", "pitch_category_format",
                "max_frequency_rank", "frequency_chain", "use_known_words_db", "exclude_hiragana_only_words",
                "exclude_katakana_only_words", "blacklist_path", "whitelist_path", "use_blacklist", "use_whitelist",
                "subtitle_regex_filter", "subtitle_regex_replacement", "use_subtitle_regex_filter",
                "strip_subtitle_annotations", "bold_target_in_sentence",
                "deduplicate_sentences", "use_i_plus_one_filter", "use_sentence_length_filter",
                "max_sentence_duration_seconds", "max_sentence_chars", "reading_min_occurrence", "max_parallel_workers",
            )
        if (!known.containsAll(settings.keys)) fail(BridgeProtocolCategory.INVALID_PAYLOAD, "config settings contain an unknown field")
        settings.forEach { (key, value) -> validateSetting(key, value) }
    }

    private fun validateSetting(
        key: String,
        value: BridgeJsonValue,
    ) {
        when (key) {
            "anki_deck_name", "anki_note_type" -> canonicalLabel(value, key)
            "anki_fields" -> validateMappedFields(value, ANKI_FIELDS, key)
            "card_type_marker_fields" -> validateMappedFields(value, MARKER_FIELDS, key)
            "card_type" -> requireOneOf(text(value, key), setOf("", "word_and_sentence", "click", "sentence", "audio"), key)
            "anki_tags", "subtitle_regex_filter", "subtitle_regex_replacement" -> text(value, key)
            "excluded_decks" -> {
                val items = array(value, key).map { canonicalLabel(it, key) }
                if (items.toSet().size != items.size) fail(BridgeProtocolCategory.INVALID_VALUE, "$key must be unique")
            }
            "allowed_pos", "excluded_subtypes", "excluded_wordsets" -> stringArray(value, key)
            "audio_padding", "screenshot_offset", "max_sentence_duration_seconds" -> requireMinimum(number(value, key), 0.0, key)
            "subtitle_offset" -> number(value, key)
            "jisho_delay" -> requireMinimum(number(value, key), 0.5, key)
            "audio_format" -> requireOneOf(text(value, key), setOf("mp3", "opus"), key)
            "pitch_category_format" -> requireOneOf(text(value, key), setOf("jp", "romaji"), key)
            "audio_bitrate", "reading_min_occurrence" -> if (integral(value, key) < 1) fail(BridgeProtocolCategory.INVALID_VALUE, "$key must be positive")
            "max_frequency_rank", "max_sentence_chars" -> nonNegative(value, key)
            "max_parallel_workers" -> if (integral(value, key) !in 1L..32L) fail(BridgeProtocolCategory.INVALID_VALUE, "$key is outside 1 through 32")
            "screenshot_animated" -> if (bool(value, key)) fail(BridgeProtocolCategory.INVALID_VALUE, "screenshot_animated must be false")
            "reading_tts_enabled", "use_known_words_db", "exclude_hiragana_only_words", "exclude_katakana_only_words",
            "use_blacklist", "use_whitelist", "use_subtitle_regex_filter", "strip_subtitle_annotations",
            "bold_target_in_sentence", "deduplicate_sentences", "use_i_plus_one_filter",
            "use_sentence_length_filter" -> bool(value, key)
            "blacklist_path", "whitelist_path" -> if (value !is BridgeJsonValue.Null) absolutePath(value, key)
            "dictionary_chain" -> validateProviderArray(value, key, "kind", setOf("indexed", "jisho"))
            "expression_audio_chain" -> validateProviderArray(value, key, "kind", setOf("pack"))
            "frequency_chain" -> validateFrequencyArray(value, key)
        }
    }

    private fun validateMappedFields(
        value: BridgeJsonValue,
        allowed: Set<String>,
        context: String,
    ) {
        val fields = objectValue(value, context)
        if (!allowed.containsAll(fields.keys)) fail(BridgeProtocolCategory.INVALID_PAYLOAD, "$context contains an unknown field")
        fields.values.forEach { mapped ->
            val text = text(mapped, context)
            if (text.isNotEmpty()) requireCanonical(text, context)
        }
    }

    private fun validateProviderArray(
        value: BridgeJsonValue,
        context: String,
        discriminator: String,
        kinds: Set<String>,
    ) {
        val entries = array(value, context)
        if (entries.toSet().size != entries.size) fail(BridgeProtocolCategory.INVALID_VALUE, "$context must contain unique entries")
        entries.forEach { raw ->
            val entry = objectValue(raw, context)
            val kind = text(entry[discriminator] ?: missing("$context kind"), "$context kind")
            requireOneOf(kind, kinds, context)
            val required = if (kind == "jisho") setOf("kind") else setOf("kind", if (kind == "pack") "pack_id" else "dict_id")
            val allowed = required + setOf("enabled") + if (kind == "jisho") setOf("dict_id") else emptySet()
            if (!entry.keys.containsAll(required) || !allowed.containsAll(entry.keys)) fail(BridgeProtocolCategory.INVALID_PAYLOAD, "$context entry fields are invalid")
            entry["enabled"]?.let { bool(it, "$context enabled") }
            if (kind == "jisho") {
                if (entry["dict_id"] != null && entry["dict_id"] !is BridgeJsonValue.Null) fail(BridgeProtocolCategory.INVALID_VALUE, "jisho dict_id must be null")
            } else {
                resourceId(entry.getValue(if (kind == "pack") "pack_id" else "dict_id"))
            }
        }
    }

    private fun validateFrequencyArray(
        value: BridgeJsonValue,
        context: String,
    ) {
        val entries = array(value, context)
        if (entries.toSet().size != entries.size) fail(BridgeProtocolCategory.INVALID_VALUE, "$context must contain unique entries")
        entries.forEach { raw ->
            val entry = objectValue(raw, context)
            if (!entry.keys.contains("source_id") || !setOf("source_id", "enabled").containsAll(entry.keys)) {
                fail(BridgeProtocolCategory.INVALID_PAYLOAD, "$context entry fields are invalid")
            }
            resourceId(entry.getValue("source_id"))
            entry["enabled"]?.let { bool(it, "$context enabled") }
        }
    }

    private fun readObject(
        parser: JsonParser,
        context: String,
    ): Map<String, BridgeJsonValue> {
        if (parser.currentToken() != JsonToken.START_OBJECT) fail(BridgeProtocolCategory.INVALID_PAYLOAD, "$context must be an object")
        val result = linkedMapOf<String, BridgeJsonValue>()
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken() != JsonToken.FIELD_NAME) fail(BridgeProtocolCategory.INVALID_JSON, "$context has an invalid member")
            val name = parser.currentName()
            requireScalars(name, "$context object key")
            if (parser.nextToken() == null) fail(BridgeProtocolCategory.INVALID_JSON, "$context ends before a value")
            result[name] = readValue(parser, context)
        }
        return result
    }

    private fun readValue(
        parser: JsonParser,
        context: String,
    ): BridgeJsonValue =
        when (parser.currentToken()) {
            JsonToken.START_OBJECT -> BridgeJsonValue.ObjectValue(readObject(parser, context))
            JsonToken.START_ARRAY -> {
                val values = mutableListOf<BridgeJsonValue>()
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    if (parser.currentToken() == null) fail(BridgeProtocolCategory.INVALID_JSON, "$context array is unterminated")
                    values += readValue(parser, context)
                }
                BridgeJsonValue.ArrayValue(values)
            }
            JsonToken.VALUE_STRING -> BridgeJsonValue.Text(parser.text.also { requireScalars(it, context) })
            JsonToken.VALUE_TRUE -> BridgeJsonValue.Bool(true)
            JsonToken.VALUE_FALSE -> BridgeJsonValue.Bool(false)
            JsonToken.VALUE_NULL -> BridgeJsonValue.Null
            JsonToken.VALUE_NUMBER_INT -> {
                val value = parser.bigIntegerValue
                if (value < minimumSignedLong || value > maximumSignedLong) {
                    fail(BridgeProtocolCategory.INTEGER_OUT_OF_RANGE, "JSON integer is outside the signed-64 wire domain")
                }
                BridgeJsonValue.Integer(value.toLong())
            }
            JsonToken.VALUE_NUMBER_FLOAT -> {
                val value = parser.text.toDoubleOrNull() ?: fail(BridgeProtocolCategory.INVALID_JSON, "invalid JSON number")
                if (!value.isFinite()) fail(BridgeProtocolCategory.NON_FINITE_NUMBER, "JSON number is not finite")
                BridgeJsonValue.Decimal(value)
            }
            else -> fail(BridgeProtocolCategory.INVALID_JSON, "$context contains an invalid JSON value")
        }

    private fun encode(
        type: String,
        payloadWriter: (JsonGenerator) -> Unit,
    ): String {
        val output = ByteArrayOutputStream()
        try {
            factory.createGenerator(output).use { generator ->
                generator.writeStartObject()
                generator.writeNumberField("schemaVersion", 1)
                generator.writeStringField("type", type)
                generator.writeObjectFieldStart("payload")
                payloadWriter(generator)
                generator.writeEndObject()
                generator.writeEndObject()
            }
        } catch (error: IOException) {
            throw IllegalStateException("failed to encode bridge message", error)
        }
        if (output.size() > MAX_ENVELOPE_UTF8_BYTES) fail(BridgeProtocolCategory.INPUT_TOO_LARGE, "encoded bridge message is too large")
        return output.toString(StandardCharsets.UTF_8.name()).also { decode(it) }
    }

    private fun writeTokenizerConfiguration(
        generator: JsonGenerator,
        configuration: TokenizerConfiguration,
    ) {
        generator.writeStringField("dicDir", configuration.dicDir)
        generator.writeStringField("resourceId", configuration.resourceId)
        generator.writeStringField("treeSha256", configuration.treeSha256)
        generator.writeStringField("backend", configuration.backend)
    }

    private fun writeVideoRequest(
        generator: JsonGenerator,
        request: VideoMiningWireRequest,
    ) {
        generator.writeStringField("videoPath", request.videoPath)
        generator.writeStringField("subtitlePath", request.subtitlePath)
        generator.writeStringField("episodeName", request.episodeName)
        generator.writeStringField("seriesName", request.seriesName)
        generator.writeFieldName("sourceLabel")
        writeNullableString(generator, request.sourceLabel)
        generator.writeFieldName("audioTrackOverride")
        request.audioTrackOverride?.let(generator::writeNumber) ?: generator.writeNull()
        generator.writeStringField("cacheDir", request.cacheDir)
        generator.writeStringField("nativeLibraryDir", request.nativeLibraryDir)
        generator.writeObjectFieldStart("configSnapshot")
        generator.writeObjectFieldStart("settings")
        request.configSnapshot.settings.toSortedMap().forEach { (key, value) ->
            generator.writeFieldName(key)
            writeJsonValue(generator, value)
        }
        generator.writeEndObject()
        request.configSnapshot.androidTtsEnabled?.let { generator.writeBooleanField("androidTtsEnabled", it) }
        generator.writeEndObject()
    }

    private fun writeReadingRequest(
        generator: JsonGenerator,
        request: ReadingMiningWireRequest,
    ) {
        generator.writeStringField("sourceKind", request.sourceKind.wireName)
        generator.writeStringField("sourcePath", request.sourcePath)
        generator.writeFieldName("imageArchivePath")
        writeNullableString(generator, request.imageArchivePath)
        generator.writeFieldName("seriesName")
        writeNullableString(generator, request.seriesName)
        generator.writeStringField("cacheDir", request.cacheDir)
        generator.writeStringField("nativeLibraryDir", request.nativeLibraryDir)
        writeConfigSnapshot(generator, request.configSnapshot)
    }

    private fun writeConfigSnapshot(
        generator: JsonGenerator,
        snapshot: MiningConfigSnapshot,
    ) {
        generator.writeObjectFieldStart("configSnapshot")
        generator.writeObjectFieldStart("settings")
        snapshot.settings.toSortedMap().forEach { (key, value) ->
            generator.writeFieldName(key)
            writeJsonValue(generator, value)
        }
        generator.writeEndObject()
        snapshot.androidTtsEnabled?.let { generator.writeBooleanField("androidTtsEnabled", it) }
        generator.writeEndObject()
    }

    private fun writeJsonValue(
        generator: JsonGenerator,
        value: BridgeJsonValue,
    ) {
        when (value) {
            BridgeJsonValue.Null -> generator.writeNull()
            is BridgeJsonValue.Bool -> generator.writeBoolean(value.value)
            is BridgeJsonValue.Integer -> generator.writeNumber(value.value)
            is BridgeJsonValue.Decimal -> generator.writeNumber(value.value)
            is BridgeJsonValue.Text -> generator.writeString(value.value)
            is BridgeJsonValue.ArrayValue -> {
                generator.writeStartArray()
                value.values.forEach { writeJsonValue(generator, it) }
                generator.writeEndArray()
            }
            is BridgeJsonValue.ObjectValue -> {
                generator.writeStartObject()
                value.values.toSortedMap().forEach { (key, child) ->
                    generator.writeFieldName(key)
                    writeJsonValue(generator, child)
                }
                generator.writeEndObject()
            }
        }
    }

    private fun writeNullableString(
        generator: JsonGenerator,
        value: String?,
    ) {
        if (value == null) generator.writeNull() else generator.writeString(value)
    }

    private fun validateSelection(
        request: CurationRequest,
        selection: List<CurationSelection>?,
    ) {
        if (selection == null) return
        if (selection.size > CURATION_PAGE_MAX_CANDIDATES) {
            fail(BridgeProtocolCategory.INVALID_VALUE, "curation selection exceeds its candidate limit")
        }
        if (selection.map { it.candidateId }.toSet().size != selection.size) {
            fail(BridgeProtocolCategory.INVALID_VALUE, "a curation candidate may only be selected once")
        }
        val candidates = request.candidates.associateBy { it.candidateId }
        selection.forEach { chosen ->
            val candidate = candidates[chosen.candidateId]
                ?: fail(BridgeProtocolCategory.STALE_REQUEST, "selection names an unknown candidate")
            if (chosen.sentenceId != null && candidate.sentences.none { it.sentenceId == chosen.sentenceId }) {
                fail(BridgeProtocolCategory.STALE_REQUEST, "selection names a sentence outside its candidate")
            }
        }
    }

    private fun identifiers(message: BridgeMessage): Pair<String?, String?> =
        when (message) {
            is BridgeMessage.JobRegistrationRequest -> message.runId to null
            is BridgeMessage.JobRegistrationAccepted -> message.runId to null
            is BridgeMessage.ProgressStart -> message.runId to null
            is BridgeMessage.ProgressUpdate -> message.runId to null
            is BridgeMessage.ProgressComplete -> message.runId to null
            is BridgeMessage.ProgressError -> message.runId to null
            is BridgeMessage.Presenter -> message.event.runId to null
            is BridgeMessage.CurationNeeded -> message.request.runId to message.request.requestId
            is BridgeMessage.CurationResponse -> message.runId to message.requestId
            is BridgeMessage.CurationPageResponse -> message.runId to message.requestId
            is BridgeMessage.CurationAccepted -> message.runId to message.requestId
            is BridgeMessage.CurationPageAccepted -> message.runId to message.requestId
            is BridgeMessage.JobCancel -> message.runId to null
            is BridgeMessage.JobCancelled -> message.runId to null
            is BridgeMessage.Terminal -> message.runId to null
            else -> null to null
        }

    private fun singleRunId(
        payload: Map<String, BridgeJsonValue>,
        context: String,
    ): String {
        requireExact(payload, setOf("runId"), context)
        return runId(payload.getValue("runId"))
    }

    private fun requireExact(
        actual: Map<String, BridgeJsonValue>,
        expected: Set<String>,
        context: String,
        category: BridgeProtocolCategory = BridgeProtocolCategory.INVALID_PAYLOAD,
    ) {
        if (actual.keys != expected) fail(category, "$context has missing or unknown fields")
    }

    private fun objectValue(
        value: BridgeJsonValue,
        context: String,
    ): Map<String, BridgeJsonValue> =
        (value as? BridgeJsonValue.ObjectValue)?.values
            ?: fail(BridgeProtocolCategory.INVALID_PAYLOAD, "$context must be an object")

    private fun nullableObject(
        value: BridgeJsonValue,
        context: String,
    ): Map<String, BridgeJsonValue>? =
        if (value is BridgeJsonValue.Null) null else objectValue(value, context)

    private fun array(
        value: BridgeJsonValue,
        context: String,
    ): List<BridgeJsonValue> =
        (value as? BridgeJsonValue.ArrayValue)?.values
            ?: fail(BridgeProtocolCategory.INVALID_PAYLOAD, "$context must be an array")

    private fun text(
        value: BridgeJsonValue,
        context: String,
    ): String =
        (value as? BridgeJsonValue.Text)?.value
            ?: fail(BridgeProtocolCategory.INVALID_PAYLOAD, "$context must be a string")

    private fun nullableText(
        value: BridgeJsonValue,
        context: String,
    ): String? = if (value is BridgeJsonValue.Null) null else text(value, context)

    private fun bool(
        value: BridgeJsonValue,
        context: String,
    ): Boolean =
        (value as? BridgeJsonValue.Bool)?.value
            ?: fail(BridgeProtocolCategory.INVALID_PAYLOAD, "$context must be a boolean")

    private fun integral(
        value: BridgeJsonValue,
        context: String,
    ): Long =
        when (value) {
            is BridgeJsonValue.Integer -> value.value
            is BridgeJsonValue.Decimal -> {
                val number = value.value
                if (number % 1.0 != 0.0 || number < NEGATIVE_LONG_BOUNDARY_AS_DOUBLE || number >= POSITIVE_LONG_BOUNDARY_AS_DOUBLE) {
                    fail(BridgeProtocolCategory.INVALID_VALUE, "$context must be a signed-64 mathematical integer")
                }
                number.toLong()
            }
            else -> fail(BridgeProtocolCategory.INVALID_PAYLOAD, "$context must be an integer")
        }

    private fun nullableIntegral(
        value: BridgeJsonValue,
        context: String,
    ): Long? = if (value is BridgeJsonValue.Null) null else integral(value, context)

    private fun number(
        value: BridgeJsonValue,
        context: String,
    ): Double =
        when (value) {
            is BridgeJsonValue.Integer -> value.value.toDouble()
            is BridgeJsonValue.Decimal -> value.value
            else -> fail(BridgeProtocolCategory.INVALID_PAYLOAD, "$context must be a number")
        }.also { if (!it.isFinite()) fail(BridgeProtocolCategory.NON_FINITE_NUMBER, "$context must be finite") }

    private fun nonNegative(
        value: BridgeJsonValue,
        context: String,
    ): Long = integral(value, context).also { if (it < 0) fail(BridgeProtocolCategory.INVALID_VALUE, "$context must be non-negative") }

    private fun nullableNonNegative(
        value: BridgeJsonValue,
        context: String,
    ): Long? = if (value is BridgeJsonValue.Null) null else nonNegative(value, context)

    private fun positive(
        value: BridgeJsonValue,
        context: String,
    ): Long = integral(value, context).also { if (it <= 0) fail(BridgeProtocolCategory.INVALID_VALUE, "$context must be positive") }

    private fun stringArray(
        value: BridgeJsonValue,
        context: String,
    ): List<String> = array(value, context).map { text(it, "$context item") }

    private fun runId(value: BridgeJsonValue): String = opaque(value, runIdPattern, "run ID")

    private fun opaque(
        value: BridgeJsonValue,
        pattern: Regex,
        context: String,
    ): String = text(value, context).also { if (!pattern.matches(it)) fail(BridgeProtocolCategory.INVALID_VALUE, "$context is invalid") }

    private fun resourceId(value: BridgeJsonValue): String =
        text(value, "resourceId").also { if (!configResourceIdPattern.matches(it)) fail(BridgeProtocolCategory.INVALID_VALUE, "resourceId is invalid") }

    private fun tokenizerResourceId(value: BridgeJsonValue): String =
        text(value, "resourceId").also { if (!tokenizerResourceIdPattern.matches(it)) fail(BridgeProtocolCategory.INVALID_VALUE, "resourceId is invalid") }

    private fun sha256(value: BridgeJsonValue): String =
        text(value, "treeSha256").also { if (!sha256Pattern.matches(it)) fail(BridgeProtocolCategory.INVALID_VALUE, "treeSha256 is invalid") }

    private fun absolutePath(
        value: BridgeJsonValue,
        context: String,
    ): String = text(value, context).also { if (it.isEmpty() || !it.startsWith('/') || '\u0000' in it) fail(BridgeProtocolCategory.INVALID_VALUE, "$context must be an absolute path") }

    private fun boundedAbsolutePath(
        value: BridgeJsonValue,
        context: String,
    ): String =
        absolutePath(value, context).also {
            if (strictUtf8(it).size > 4096) {
                fail(BridgeProtocolCategory.INVALID_VALUE, "$context exceeds its UTF-8 byte limit")
            }
        }

    private fun canonicalLabel(
        value: BridgeJsonValue,
        context: String,
    ): String = text(value, context).also { requireCanonical(it, context) }

    private fun nullableCanonicalLabel(
        value: BridgeJsonValue,
        context: String,
    ): String? = if (value is BridgeJsonValue.Null) null else canonicalLabel(value, context)

    private fun requireCanonical(
        value: String,
        context: String,
    ) {
        if (
            value.isEmpty() ||
            !UnicodeContractV151.isNfc(value) ||
            UnicodeContractV151.hasLeadingOrTrailingPythonWhitespace(value) ||
            containsCategoryC(value)
        ) {
            fail(BridgeProtocolCategory.INVALID_VALUE, "$context is not a canonical non-empty string")
        }
    }

    private fun containsCategoryC(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val first = value[index]
            val codePoint =
                if (first.isHighSurrogate()) {
                    Character.toCodePoint(first, value[index + 1]).also { index += 2 }
                } else {
                    first.code.also { index += 1 }
                }
            if (UnicodeContractV151.isCategoryC(codePoint)) return true
        }
        return false
    }

    private fun requireOneOf(
        value: String,
        allowed: Set<String>,
        context: String,
    ) {
        if (value !in allowed) fail(BridgeProtocolCategory.INVALID_VALUE, "$context is invalid")
    }

    private fun requireMinimum(
        value: Double,
        minimum: Double,
        context: String,
    ) {
        if (value < minimum) fail(BridgeProtocolCategory.INVALID_VALUE, "$context is below its minimum")
    }

    private fun strictUtf8(raw: String): ByteArray {
        requireScalars(raw, "bridge JSON")
        val encoder =
            StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        val capacity = minOf(MAX_ENVELOPE_UTF8_BYTES.toLong() + 1, maxOf(1L, raw.length.toLong() * 3)).toInt()
        val buffer = ByteBuffer.allocate(capacity)
        val input = CharBuffer.wrap(raw)
        val encoded = encoder.encode(input, buffer, true)
        if (encoded.isError) fail(BridgeProtocolCategory.INVALID_UTF8, "bridge JSON contains an invalid Unicode scalar")
        if (encoded.isOverflow) fail(BridgeProtocolCategory.INPUT_TOO_LARGE, "bridge JSON exceeds 32 MiB UTF-8")
        val flushed = encoder.flush(buffer)
        if (flushed.isError) fail(BridgeProtocolCategory.INVALID_UTF8, "bridge JSON contains an invalid Unicode scalar")
        if (flushed.isOverflow || input.hasRemaining() || buffer.position() > MAX_ENVELOPE_UTF8_BYTES) {
            fail(BridgeProtocolCategory.INPUT_TOO_LARGE, "bridge JSON exceeds 32 MiB UTF-8")
        }
        return ByteArray(buffer.position()).also { bytes ->
            buffer.flip()
            buffer.get(bytes)
        }
    }

    private fun requireScalars(
        value: String,
        context: String,
    ) {
        if (UnicodeContractV151.scalarCount(value) == null) {
            fail(BridgeProtocolCategory.INVALID_UTF8, "$context contains an invalid Unicode scalar")
        }
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
            if (index - start > MAX_JSON_NUMBER_CHARS) {
                fail(BridgeProtocolCategory.NUMERIC_TOKEN_TOO_LONG, "JSON number exceeds 1000 characters")
            }
        }
    }

    private fun expectNext(
        parser: JsonParser,
        token: JsonToken,
        message: String,
        category: BridgeProtocolCategory,
    ) {
        if (parser.nextToken() != token) fail(category, message)
    }

    private fun missing(context: String): Nothing = fail(BridgeProtocolCategory.INVALID_PAYLOAD, "$context is missing")

    private fun fail(
        category: BridgeProtocolCategory,
        message: String,
        cause: Throwable? = null,
    ): Nothing = throw BridgeProtocolException(category, message, cause)

    private val ANKI_FIELDS =
        setOf(
            "word", "sentence", "definition", "glossary", "picture", "audio", "expression_furigana",
            "expression_reading", "sentence_furigana", "sentence_reading", "pitch_position", "pitch_category",
            "pitch_graph", "pitch_text", "frequency", "frequency_sort", "source", "expression_audio",
        )
    private val MARKER_FIELDS = setOf("word_and_sentence", "click", "sentence", "audio")

    private object PythonCanonicalEscapes : CharacterEscapes() {
        private val escapeCodes =
            standardAsciiEscapesForJSON().also { codes ->
                for (codePoint in 0..0x1f) {
                    if (!usesShortEscape(codePoint)) codes[codePoint] = ESCAPE_CUSTOM
                }
            }
        private const val HEX = "0123456789abcdef"

        override fun getEscapeCodesForAscii(): IntArray = escapeCodes

        override fun getEscapeSequence(codePoint: Int): SerializedString? =
            if (codePoint in 0..0x1f && !usesShortEscape(codePoint)) {
                SerializedString("\\u00${HEX[(codePoint ushr 4) and 0x0f]}${HEX[codePoint and 0x0f]}")
            } else {
                null
            }

        private fun usesShortEscape(codePoint: Int): Boolean =
            codePoint == 0x08 || codePoint == 0x09 || codePoint == 0x0a || codePoint == 0x0c || codePoint == 0x0d
    }
}
