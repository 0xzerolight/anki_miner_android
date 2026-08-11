package com.ankiminer.android.anki.protocol

import com.ankiminer.android.anki.generated.AnkiLimitsV1
import com.ankiminer.android.anki.generated.UnicodeContractV151
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal enum class AnkiProtocolCategory(val wireName: String) {
    INPUT_TOO_LARGE("input_too_large"),
    OUTPUT_TOO_LARGE("output_too_large"),
    INVALID_UTF8("invalid_utf8"),
    INVALID_JSON("invalid_json"),
    DUPLICATE_JSON_KEY("duplicate_json_key"),
    NUMERIC_TOKEN_TOO_LONG("json_number_too_long"),
    INVALID_ENVELOPE("invalid_envelope"),
    UNSUPPORTED_SCHEMA_VERSION("unsupported_schema_version"),
    INVALID_MESSAGE_TYPE("invalid_message_type"),
    UNEXPECTED_MESSAGE_TYPE("unexpected_message_type"),
    INVALID_PAYLOAD("invalid_payload"),
    INTEGER_OUT_OF_RANGE("integer_out_of_range"),
    NON_FINITE_NUMBER("non_finite_number"),
    INVALID_JSON_NUMBER("invalid_json_number"),
    INVALID_VALUE("invalid_value"),
    LIMIT_MISMATCH("limit_mismatch"),
}

internal class AnkiProtocolException(
    val category: AnkiProtocolCategory,
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause) {
    var recoveredRunId: String? = null
        private set
    var recoveredRequestId: String? = null
        private set

    fun attachIdentifiers(runId: String?, requestId: String?): AnkiProtocolException =
        apply {
            if (recoveredRunId == null && runId != null && AnkiValidators.isValidRunId(runId)) {
                recoveredRunId = runId
            }
            if (
                recoveredRequestId == null &&
                    requestId != null &&
                    AnkiValidators.isValidRequestId(requestId)
            ) {
                recoveredRequestId = requestId
            }
        }
}

internal object AnkiValidators {
    private val runIdPattern = Regex("run_[0-9a-f]{32}")
    private val requestIdPattern = Regex("anki_[0-9a-f]{32}")
    private val assetIdPattern = Regex("asset_[0-9a-f]{32}")
    private val clientNoteIdPattern = Regex("note_[0-9a-f]{32}")
    private val baselineTokenPattern = Regex("baseline_[0-9a-f]{32}")
    private val sha256Pattern = Regex("[0-9a-f]{64}")
    private val preferredFilenameForbidden = setOf('/', '\\', '<', '>', '[', ']', ':', '"')

    data class StringStats(val scalarCount: Int, val utf8Bytes: Int)

    fun isValidRunId(value: String): Boolean = runIdPattern.matches(value)

    fun isValidRequestId(value: String): Boolean = requestIdPattern.matches(value)

    fun strictStats(value: String, context: String): StringStats {
        val scalarCount = UnicodeContractV151.scalarCount(value)
        val utf8Bytes = UnicodeContractV151.strictUtf8Length(value)
        if (scalarCount == null || utf8Bytes == null) {
            fail(AnkiProtocolCategory.INVALID_UTF8, "$context contains an invalid Unicode scalar")
        }
        return StringStats(scalarCount, utf8Bytes)
    }

    fun validateRequest(request: AnkiRequest) {
        validateRunId(request.runId)
        validateRequestId(request.requestId)
        when (request) {
            is VerifyTargetRequest -> validateVerifyTarget(request)
            is ScanFirstFieldsRequest -> validateScan(request)
            is StoreMediaRequest -> validateStoreMedia(request)
            is CreateNotesRequest -> validateCreateNotes(request)
            is ReleaseRunStateRequest -> Unit
        }
    }

    fun validateResponse(response: AnkiResponse) {
        validateRunId(response.runId)
        validateRequestId(response.requestId)
        when (response) {
            is VerifyTargetResult -> validateVerifyTargetResult(response)
            is KnownVocabularyResult -> validateKnownVocabularyResult(response)
            is DuplicateLookupResult -> validateDuplicateLookupResult(response)
            is StoreMediaResult -> validateStoreMediaResult(response)
            is CreateNotesResult -> validateCreateNotesResult(response)
            is ReleaseRunStateResult -> Unit
            is AnkiErrorResult -> validateError(response.code, response.message, response.retryable)
        }
    }

    fun validateResponseForRequest(response: AnkiResponse, request: AnkiRequest) {
        validateResponse(response)
        validateRequest(request)
        if (
            response.runId != request.runId ||
                response.requestId != request.requestId ||
                response.operation != request.operation
        ) {
            failValue("Anki response does not match its request")
        }
        if (response is AnkiErrorResult) return
        when {
            request is ScanFirstFieldsRequest && request.scope is KnownVocabularyScope && response is KnownVocabularyResult -> {
                response.nextCursor?.let { next ->
                    val expectedOrdinal =
                        request.scope.cursor?.ordinal?.let { prior ->
                            try {
                                Math.addExact(prior, 1L)
                            } catch (_: ArithmeticException) {
                                failValue("known-vocabulary cursor ordinal overflowed")
                            }
                        } ?: 1L
                    if (next.ordinal != expectedOrdinal || next.token == request.scope.cursor?.token) {
                        failValue("known-vocabulary cursor did not advance exactly once")
                    }
                }
            }
            request is ScanFirstFieldsRequest && request.scope is KnownVocabularyScope ->
                failValue("known-vocabulary request has the wrong response shape")
            request is ScanFirstFieldsRequest && request.scope is DuplicateScanScope && response is DuplicateLookupResult -> {
                if (response.rawFirstFieldHits.size != request.scope.candidates.size) {
                    failValue("duplicate lookup buckets are not request-aligned")
                }
                if (response.baselineToken == request.scope.invalidateBaselineToken) {
                    failValue("duplicate lookup did not return a fresh baseline token")
                }
            }
            request is ScanFirstFieldsRequest && request.scope is DuplicateScanScope ->
                failValue("duplicate request has the wrong response shape")
            request is StoreMediaRequest && response is StoreMediaResult -> {
                if (response.results.map { it.assetId } != request.assets.map { it.assetId }) {
                    failValue("media results are not request-aligned")
                }
                response.results.zip(request.assets).forEach { (row, asset) ->
                    if (row is StoredMedia) validateProviderFilename(row.actualFilename, asset)
                }
            }
            request is StoreMediaRequest -> failValue("store-media request has the wrong response shape")
            request is CreateNotesRequest && response is CreateNotesResult -> {
                if (response.results.map { it.clientNoteId } != request.notes.map { it.clientNoteId }) {
                    failValue("note results are not request-aligned")
                }
            }
            request is CreateNotesRequest -> failValue("create-notes request has the wrong response shape")
            request is VerifyTargetRequest && response is VerifyTargetResult -> {
                if (!response.fieldNames.containsAll(request.requiredFields)) {
                    failValue("verified target is missing a required field")
                }
            }
            request is VerifyTargetRequest -> failValue("verify-target request has the wrong response shape")
            request is ReleaseRunStateRequest && response !is ReleaseRunStateResult && response !is AnkiErrorResult ->
                failValue("release request has the wrong response shape")
        }
    }

    fun validateProviderTextSnapshot(
        css: String,
        latexPre: String?,
        latexPost: String?,
        templates: List<ProviderTemplateText>,
    ) {
        requireCountBetween(templates.size, 1, AnkiLimitsV1.TargetModel.MAX_TEMPLATE_COUNT, "model templates")
        var total = boundedText(css, "model CSS", AnkiLimitsV1.TargetModel.CSS_MAX_UTF8_BYTES)
        total = addExact(total, boundedNullableText(latexPre, "model LaTeX preamble", AnkiLimitsV1.TargetModel.LATEX_PRE_MAX_UTF8_BYTES))
        total = addExact(total, boundedNullableText(latexPost, "model LaTeX postamble", AnkiLimitsV1.TargetModel.LATEX_POST_MAX_UTF8_BYTES))
        for (template in templates) {
            total = addExact(total, boundedText(template.questionFormat, "template question format", AnkiLimitsV1.TargetModel.TEMPLATE_QUESTION_FORMAT_MAX_UTF8_BYTES))
            total = addExact(total, boundedText(template.answerFormat, "template answer format", AnkiLimitsV1.TargetModel.TEMPLATE_ANSWER_FORMAT_MAX_UTF8_BYTES))
            total = addExact(total, boundedNullableText(template.browserQuestionFormat, "template browser question format", AnkiLimitsV1.TargetModel.TEMPLATE_BROWSER_QUESTION_FORMAT_MAX_UTF8_BYTES))
            total = addExact(total, boundedNullableText(template.browserAnswerFormat, "template browser answer format", AnkiLimitsV1.TargetModel.TEMPLATE_BROWSER_ANSWER_FORMAT_MAX_UTF8_BYTES))
        }
        requireAtMost(total, AnkiLimitsV1.TargetModel.PROVIDER_TEXT_TOTAL_MAX_UTF8_BYTES, "provider model text bytes")
    }

    data class ProviderTemplateText(
        val questionFormat: String,
        val answerFormat: String,
        val browserQuestionFormat: String?,
        val browserAnswerFormat: String?,
    )

    private fun validateVerifyTarget(request: VerifyTargetRequest) {
        validateDeckName(request.deckName)
        validateModelName(request.modelName)
        requireCountAtMost(request.requiredFields.size, AnkiLimitsV1.Names.TargetFields.MAX_ITEM_COUNT, "required fields")
        requireUnique(request.requiredFields, "required field names")
        var totalBytes = 0
        for (field in request.requiredFields) {
            totalBytes = addExact(totalBytes, validateFieldName(field).utf8Bytes)
        }
        requireAtMost(totalBytes, AnkiLimitsV1.Names.TargetFields.MAX_TOTAL_UTF8_BYTES, "required field bytes")
    }

    private fun validateScan(request: ScanFirstFieldsRequest) {
        when (val scope = request.scope) {
            is KnownVocabularyScope -> {
                requireCountAtMost(scope.excludedDecks.size, AnkiLimitsV1.Names.ExcludedDecks.MAX_ITEM_COUNT, "excluded decks")
                requireUnique(scope.excludedDecks, "excluded deck names")
                var total = 0
                for (deck in scope.excludedDecks) total = addExact(total, validateDeckName(deck).utf8Bytes)
                requireAtMost(total, AnkiLimitsV1.Names.ExcludedDecks.MAX_TOTAL_UTF8_BYTES, "excluded deck bytes")
                scope.cursor?.let(::validateKnownCursor)
            }
            is DuplicateScanScope -> {
                validateModelName(scope.modelName)
                validateFieldName(scope.firstFieldName)
                requireCountBetween(scope.candidates.size, 1, AnkiLimitsV1.ScanFirstFields.DUPLICATE_CANDIDATE_MAX_ITEM_COUNT, "duplicate candidates")
                requireUnique(scope.candidates, "duplicate candidates")
                scope.candidates.forEach(::validateDuplicateCandidate)
                requireCountBetween(scope.occurrences.size, 1, AnkiLimitsV1.ScanFirstFields.DUPLICATE_CANDIDATE_MAX_ITEM_COUNT, "duplicate occurrences")
                for (occurrence in scope.occurrences) {
                    if (occurrence !in scope.candidates.indices) failValue("duplicate occurrence is outside the candidate table")
                }
                if (scope.occurrences.toSet() != scope.candidates.indices.toSet()) {
                    failValue("duplicate occurrences do not cover the complete candidate table")
                }
                scope.invalidateBaselineToken?.let(::validateBaselineToken)
            }
        }
    }

    private fun validateStoreMedia(request: StoreMediaRequest) {
        requireCountBetween(request.assets.size, 1, AnkiLimitsV1.StoreMedia.MAX_ASSET_COUNT, "media assets")
        requireUnique(request.assets.map { it.assetId }, "media asset IDs")
        requireUnique(request.assets.map { it.requestedFilename }, "requested media filenames")
        val namespacePrefixes = ArrayList<Pair<String, String>>(request.assets.size)
        val concreteClaims = ArrayList<Pair<String, String>>(request.assets.size)
        var totalBytes = 0L
        for (asset in request.assets) {
            validateAssetId(asset.assetId)
            validateSourcePath(asset.sourcePath)
            validatePreferredFilename(asset.preferredName)
            if (asset.purpose == MediaPurpose.DICTIONARY) {
                validateMediaBasename(asset.requestedFilename, actual = false)
            } else {
                validatePreferredFilename(asset.requestedFilename)
            }
            val expectedPreferred =
                if (asset.purpose == MediaPurpose.DICTIONARY) {
                    "anki_miner_dict_${sha256Hex(asset.requestedFilename)}"
                } else {
                    cardPreferredName(asset.requestedFilename)
                }
            if (asset.preferredName != expectedPreferred) failValue("preferred media name is inconsistent with the requested filename")
            namespacePrefixes += "${asset.preferredName}_" to asset.assetId
            safeProviderFilenameStem(asset.requestedFilename)?.let { stem ->
                concreteClaims += stem to asset.assetId
            }
            if (asset.expectedSizeBytes !in 0L..AnkiLimitsV1.StoreMedia.MAX_ASSET_BYTES.toLong()) {
                failValue("media asset size is outside the v1 limit")
            }
            totalBytes = addExact(totalBytes, asset.expectedSizeBytes)
            if (!sha256Pattern.matches(asset.expectedSha256)) failValue("media SHA-256 is invalid")
        }
        if (totalBytes > AnkiLimitsV1.StoreMedia.MAX_TOTAL_BYTES.toLong()) failValue("media total bytes exceed the v1 limit")
        for (index in namespacePrefixes.indices) {
            val (prefix, owner) = namespacePrefixes[index]
            for (otherIndex in index + 1 until namespacePrefixes.size) {
                val (otherPrefix, otherOwner) = namespacePrefixes[otherIndex]
                if (
                    owner != otherOwner &&
                        (prefix.startsWith(otherPrefix) || otherPrefix.startsWith(prefix))
                ) {
                    failValue("media provider namespaces overlap")
                }
            }
            if (concreteClaims.any { (stem, concreteOwner) -> concreteOwner != owner && stem.startsWith(prefix) }) {
                failValue("requested media filename overlaps another provider namespace")
            }
        }
    }

    private fun validateCreateNotes(request: CreateNotesRequest) {
        validateDeckName(request.deckName)
        validateModelName(request.modelName)
        validateFieldName(request.firstFieldName)
        validateBaselineToken(request.baselineToken)
        requireCountBetween(request.notes.size, 1, AnkiLimitsV1.CreateNotes.MAX_NOTE_COUNT, "notes")
        requireUnique(request.notes.map { it.clientNoteId }, "client note IDs")
        var callbackBytes = 0
        var mediaBindingCount = 0
        var priorOccurrence = -1
        for (note in request.notes) {
            validateClientNoteId(note.clientNoteId)
            requireCountBetween(note.fields.size, 1, AnkiLimitsV1.CreateNotes.MAX_FIELD_COUNT_PER_NOTE, "note fields")
            var noteBytes = 0
            for ((name, value) in note.fields) {
                noteBytes = addExact(noteBytes, validateFieldName(name).utf8Bytes)
                noteBytes = addExact(noteBytes, validatePlainString(value, "field value", allowEmpty = true, maxScalars = AnkiLimitsV1.CreateNotes.FIELD_VALUE_MAX_CODE_POINTS, maxUtf8Bytes = AnkiLimitsV1.CreateNotes.FIELD_VALUE_MAX_UTF8_BYTES).utf8Bytes)
            }
            requireCountAtMost(note.tags.size, AnkiLimitsV1.CreateNotes.MAX_TAG_COUNT_PER_NOTE, "note tags")
            var tagBytes = 0
            for (tag in note.tags) {
                tagBytes = addExact(tagBytes, validatePlainString(tag, "tag", allowEmpty = false, maxScalars = AnkiLimitsV1.CreateNotes.TAG_MAX_CODE_POINTS, maxUtf8Bytes = AnkiLimitsV1.CreateNotes.TAG_MAX_UTF8_BYTES).utf8Bytes)
            }
            requireAtMost(tagBytes, AnkiLimitsV1.CreateNotes.TAGS_PER_NOTE_MAX_UTF8_BYTES, "tag bytes")
            noteBytes = addExact(noteBytes, tagBytes)
            requireCountAtMost(note.mediaBindings.size, AnkiLimitsV1.CreateNotes.MAX_MEDIA_BINDING_COUNT_PER_NOTE, "note media bindings")
            requireUnique(note.mediaBindings.map { it.assetId }, "note media binding asset IDs")
            for (binding in note.mediaBindings) {
                validateAssetId(binding.assetId)
                noteBytes = addExact(noteBytes, strictStats(binding.assetId, "media binding asset ID").utf8Bytes)
                noteBytes = addExact(noteBytes, validateMediaBasename(binding.actualFilename, actual = true).utf8Bytes)
            }
            mediaBindingCount = addExact(mediaBindingCount, note.mediaBindings.size)
            requireAtMost(noteBytes, AnkiLimitsV1.CreateNotes.NOTE_CONTENT_MAX_UTF8_BYTES, "note content bytes")
            callbackBytes = addExact(callbackBytes, noteBytes)
            validateCreateDuplicateCandidate(note.duplicateCandidate)
            if (note.duplicateCandidate.occurrence <= priorOccurrence) failValue("duplicate candidate occurrences must be strictly increasing")
            priorOccurrence = note.duplicateCandidate.occurrence
        }
        requireAtMost(mediaBindingCount, AnkiLimitsV1.CreateNotes.MAX_MEDIA_BINDING_TOTAL_COUNT, "media binding count")
        requireAtMost(callbackBytes, AnkiLimitsV1.CreateNotes.CALLBACK_CONTENT_MAX_UTF8_BYTES, "callback content bytes")
    }

    private fun validateVerifyTargetResult(result: VerifyTargetResult) {
        requirePositive(result.deckId, "deck ID")
        requirePositive(result.modelId, "model ID")
        if (result.deckCreated) failValue("ContentProvider verifyTarget must report deckCreated=false")
        requireCountBetween(result.fieldNames.size, 1, AnkiLimitsV1.Names.TargetFields.MAX_ITEM_COUNT, "field names")
        requireUnique(result.fieldNames, "field names")
        var bytes = 0
        for (name in result.fieldNames) bytes = addExact(bytes, validateFieldName(name).utf8Bytes)
        requireAtMost(bytes, AnkiLimitsV1.Names.TargetFields.MAX_TOTAL_UTF8_BYTES, "field name bytes")
    }

    private fun validateKnownVocabularyResult(result: KnownVocabularyResult) {
        requireCountAtMost(result.firstFields.size, AnkiLimitsV1.ScanFirstFields.KNOWN_PAGE_MAX_ITEM_COUNT, "known first fields")
        if (
            result.scannedNotes !in result.firstFields.size..AnkiLimitsV1.ScanFirstFields.KNOWN_PAGE_MAX_ITEM_COUNT ||
                (result.nextCursor != null && result.scannedNotes == 0)
        ) {
            failValue("scanned note count is invalid")
        }
        var total = 0
        for (value in result.firstFields) {
            total = addExact(total, validatePlainString(value, "known first field", allowEmpty = true, maxScalars = AnkiLimitsV1.ScanFirstFields.FIRST_FIELD_MAX_CODE_POINTS, maxUtf8Bytes = AnkiLimitsV1.ScanFirstFields.FIRST_FIELD_MAX_UTF8_BYTES).utf8Bytes)
        }
        requireAtMost(total, AnkiLimitsV1.ScanFirstFields.KNOWN_PAGE_MAX_UTF8_BYTES, "known first-field bytes")
        result.nextCursor?.let(::validateKnownCursor)
    }

    private fun validateDuplicateLookupResult(result: DuplicateLookupResult) {
        validateBaselineToken(result.baselineToken)
        requireCountBetween(result.rawFirstFieldHits.size, 1, AnkiLimitsV1.ScanFirstFields.DUPLICATE_CANDIDATE_MAX_ITEM_COUNT, "duplicate hit buckets")
        var totalCount = 0
        var totalBytes = 0
        for (bucket in result.rawFirstFieldHits) {
            requireCountAtMost(bucket.size, AnkiLimitsV1.ScanFirstFields.DUPLICATE_HIT_PER_CANDIDATE_MAX_ITEM_COUNT, "duplicate hit bucket")
            requireUnique(bucket.map { it.noteId }, "duplicate hit note IDs")
            totalCount = addExact(totalCount, bucket.size)
            for (hit in bucket) {
                requirePositive(hit.noteId, "note ID")
                totalBytes = addExact(totalBytes, validatePlainString(hit.firstField, "duplicate first field", allowEmpty = true, maxScalars = AnkiLimitsV1.ScanFirstFields.FIRST_FIELD_MAX_CODE_POINTS, maxUtf8Bytes = AnkiLimitsV1.ScanFirstFields.FIRST_FIELD_MAX_UTF8_BYTES).utf8Bytes)
            }
        }
        requireAtMost(totalCount, AnkiLimitsV1.ScanFirstFields.DUPLICATE_HIT_TOTAL_MAX_ITEM_COUNT, "duplicate hit count")
        requireAtMost(totalBytes, AnkiLimitsV1.ScanFirstFields.DUPLICATE_HIT_TOTAL_MAX_UTF8_BYTES, "duplicate hit bytes")
    }

    private fun validateStoreMediaResult(result: StoreMediaResult) {
        requireCountBetween(result.results.size, 1, AnkiLimitsV1.StoreMedia.MAX_ASSET_COUNT, "media results")
        requireUnique(result.results.map { it.assetId }, "media result asset IDs")
        requireUnique(result.results.mapNotNull { (it as? StoredMedia)?.actualFilename }, "stored media filenames")
        var terminalSeen = false
        var storedSeen = false
        for (row in result.results) {
            validateAssetId(row.assetId)
            when (row) {
                is StoredMedia -> {
                    if (terminalSeen) failValue("stored media cannot follow a terminal result")
                    storedSeen = true
                    validateMediaBasename(row.actualFilename, actual = true)
                }
                is FailedMedia -> {
                    if (terminalSeen) failValue("failed media cannot follow a terminal result")
                    validateError(row.error.code, row.error.message, row.error.retryable)
                    if (row.error.code != AnkiErrorCode.MEDIA_STORE_FAILED) {
                        failValue("failed media has the wrong error code")
                    }
                }
                is UncertainMedia -> {
                    if (terminalSeen) failValue("uncertain media is not a strict terminal row")
                    terminalSeen = true
                }
                is NotAttemptedMedia -> terminalSeen = true
            }
        }
        val uncertainSeen = result.results.any { it is UncertainMedia }
        validatePartialError(
            result.error,
            terminalSeen,
            storedSeen,
            uncertainSeen,
            postCommitCarrierSeen = uncertainSeen,
        )
    }

    private fun validateCreateNotesResult(result: CreateNotesResult) {
        requireCountBetween(result.results.size, 1, AnkiLimitsV1.CreateNotes.MAX_NOTE_COUNT, "note results")
        requireUnique(result.results.map { it.clientNoteId }, "note result client IDs")
        requireUnique(result.results.mapNotNull { (it as? CreatedNote)?.noteId ?: (it as? CommittedFailedNote)?.noteId }, "created note IDs")
        var terminalSeen = false
        var terminalCarrierSeen = false
        var notAttemptedSeen = false
        var committedSeen = false
        var committedFailureSeen = false
        var uncertainSeen = false
        for (row in result.results) {
            validateClientNoteId(row.clientNoteId)
            when (row) {
                is CreatedNote -> {
                    if (terminalSeen) failValue("created note cannot follow a terminal result")
                    requirePositive(row.noteId, "note ID")
                    committedSeen = true
                }
                is DuplicateNote -> if (terminalSeen) failValue("duplicate note cannot follow a terminal result")
                is FailedNote -> {
                    if (terminalSeen) failValue("failed note is not the first terminal result")
                    terminalSeen = true
                    terminalCarrierSeen = true
                }
                is CommittedFailedNote -> {
                    if (terminalSeen) failValue("committed-failed note is not the first terminal result")
                    requirePositive(row.noteId, "note ID")
                    committedSeen = true
                    committedFailureSeen = true
                    terminalSeen = true
                    terminalCarrierSeen = true
                }
                is UncertainNote -> {
                    if (terminalSeen) failValue("uncertain note is not the first terminal result")
                    uncertainSeen = true
                    terminalSeen = true
                    terminalCarrierSeen = true
                }
                is NotAttemptedNote -> {
                    terminalSeen = true
                    notAttemptedSeen = true
                }
            }
            if (notAttemptedSeen && row !is NotAttemptedNote) failValue("not-attempted notes must form a strict suffix")
        }
        if (notAttemptedSeen && !terminalCarrierSeen) failValue("not-attempted notes require a preceding terminal carrier")
        validatePartialError(
            result.error,
            terminalCarrierSeen,
            committedSeen,
            uncertainSeen,
            postCommitCarrierSeen = uncertainSeen || committedFailureSeen,
        )
        if (committedFailureSeen && result.error?.code == AnkiErrorCode.CANCELLED) {
            failValue("a known post-commit failure cannot be reported as clean cancellation")
        }
    }

    private fun validatePartialError(
        error: AnkiErrorDetail?,
        terminalErrorSeen: Boolean,
        knownWriteSeen: Boolean,
        uncertainSeen: Boolean,
        postCommitCarrierSeen: Boolean,
    ) {
        if (terminalErrorSeen != (error != null)) failValue("top-level error does not match the aligned terminal results")
        error?.let { validateError(it.code, it.message, it.retryable) }
        if (knownWriteSeen && error?.retryable == true) failValue("a result after a known write cannot be retryable")
        if (uncertainSeen && (error?.code != AnkiErrorCode.POST_COMMIT_UNCERTAIN || error.retryable)) {
            failValue("an uncertain result requires a non-retryable post-commit error")
        }
        if (error?.code == AnkiErrorCode.POST_COMMIT_UNCERTAIN && !postCommitCarrierSeen) {
            failValue("a post-commit uncertainty error requires a row-local carrier")
        }
    }

    private fun validateError(code: AnkiErrorCode, message: String, retryable: Boolean) {
        validatePlainString(message, "error message", allowEmpty = false)
        if (code == AnkiErrorCode.POST_COMMIT_UNCERTAIN && retryable) failValue("post-commit uncertainty cannot be retryable")
        if (code == AnkiErrorCode.CANCELLED && retryable) failValue("cancellation cannot be retryable")
    }

    private fun validateKnownCursor(cursor: KnownVocabularyCursor) {
        requirePositive(cursor.ordinal, "cursor ordinal")
        validatePlainString(cursor.token, "cursor token", allowEmpty = false, maxScalars = AnkiLimitsV1.ScanFirstFields.KNOWN_CURSOR_MAX_CODE_POINTS, maxUtf8Bytes = AnkiLimitsV1.ScanFirstFields.KNOWN_CURSOR_MAX_UTF8_BYTES)
    }

    private fun validateDuplicateCandidate(candidate: DuplicateCandidate) {
        validatePlainString(candidate.key, "duplicate key", allowEmpty = false, maxScalars = AnkiLimitsV1.ScanFirstFields.DUPLICATE_KEY_MAX_CODE_POINTS)
        validatePlainString(candidate.firstField, "duplicate first field", allowEmpty = false, maxScalars = AnkiLimitsV1.ScanFirstFields.DUPLICATE_FIRST_FIELD_MAX_CODE_POINTS)
    }

    private fun validateCreateDuplicateCandidate(candidate: CreateDuplicateCandidate) {
        validatePlainString(candidate.key, "duplicate key", allowEmpty = false, maxScalars = AnkiLimitsV1.ScanFirstFields.DUPLICATE_KEY_MAX_CODE_POINTS)
        validatePlainString(candidate.firstField, "duplicate first field", allowEmpty = false, maxScalars = AnkiLimitsV1.ScanFirstFields.DUPLICATE_FIRST_FIELD_MAX_CODE_POINTS)
        if (candidate.occurrence !in 0 until AnkiLimitsV1.CreateNotes.MAX_NOTE_COUNT) failValue("duplicate occurrence is outside the v1 range")
    }

    private fun validateRunId(value: String) {
        if (!isValidRunId(value)) failValue("run ID is invalid")
    }

    private fun validateRequestId(value: String) {
        if (!isValidRequestId(value)) failValue("request ID is invalid")
    }

    private fun validateAssetId(value: String) {
        if (!assetIdPattern.matches(value)) failValue("asset ID is invalid")
    }

    private fun validateClientNoteId(value: String) {
        if (!clientNoteIdPattern.matches(value)) failValue("client note ID is invalid")
    }

    private fun validateBaselineToken(value: String) {
        if (!baselineTokenPattern.matches(value)) failValue("baseline token is invalid")
    }

    private fun validateDeckName(value: String): StringStats =
        validateCanonicalString(value, "deck name", AnkiLimitsV1.Names.Deck.MAX_CODE_POINTS, AnkiLimitsV1.Names.Deck.MAX_UTF8_BYTES)

    private fun validateModelName(value: String): StringStats =
        validateCanonicalString(value, "model name", AnkiLimitsV1.Names.Model.MAX_CODE_POINTS, AnkiLimitsV1.Names.Model.MAX_UTF8_BYTES)

    private fun validateFieldName(value: String): StringStats =
        validateCanonicalString(value, "field name", AnkiLimitsV1.Names.Field.MAX_CODE_POINTS, AnkiLimitsV1.Names.Field.MAX_UTF8_BYTES)

    private fun validateCanonicalString(value: String, context: String, maxScalars: Int, maxUtf8Bytes: Int): StringStats {
        val stats = validatePlainString(value, context, allowEmpty = false, maxScalars = maxScalars, maxUtf8Bytes = maxUtf8Bytes)
        if (!UnicodeContractV151.isNfc(value)) failValue("$context is not NFC")
        if (UnicodeContractV151.hasLeadingOrTrailingPythonWhitespace(value)) failValue("$context has leading or trailing whitespace")
        if (containsCategoryC(value)) failValue("$context contains a Unicode category-C code point")
        return stats
    }

    private fun validatePreferredFilename(value: String): StringStats {
        val stats = validateCanonicalString(value, "preferred filename", AnkiLimitsV1.StoreMedia.FILENAME_MAX_CODE_POINTS, AnkiLimitsV1.StoreMedia.FILENAME_MAX_UTF8_BYTES)
        if (value == "." || value == ".." || value.any { it in preferredFilenameForbidden }) failValue("preferred filename is not a safe basename")
        return stats
    }

    fun validateMediaBasename(value: String) {
        validateMediaBasename(value, actual = false)
    }

    private fun validateMediaBasename(value: String, actual: Boolean): StringStats {
        val stats = validatePlainString(value, "media basename", allowEmpty = false, maxScalars = AnkiLimitsV1.StoreMedia.FILENAME_MAX_CODE_POINTS, maxUtf8Bytes = AnkiLimitsV1.StoreMedia.FILENAME_MAX_UTF8_BYTES)
        if (value == "." || value == ".." || '/' in value || '\\' in value || containsCategoryC(value)) failValue("media filename is not a safe basename")
        if (
            actual &&
                (startsWithAsciiIgnoreCase(value, "[sound:") || startsWithAsciiIgnoreCase(value, "<img"))
        ) {
            failValue("provider media result contains field markup")
        }
        return stats
    }

    fun validateProviderFilename(actual: String, asset: MediaAsset) {
        validateProviderFilename(
            actual = actual,
            requested = asset.requestedFilename,
            preferred = asset.preferredName,
        )
    }

    /**
     * Validates provider filename attribution from the durable media fields available at recovery.
     *
     * Recovery deliberately does not reconstruct a protocol [MediaAsset]. The requested and
     * preferred names are nevertheless sufficient to prove that a returned filename belongs to
     * the original namespace. Accepting either preferred-name derivation is safe here: the durable
     * request already fixes the media purpose, while this overload's job is to reject corrupted or
     * unrelated name triples before a persisted provider receipt is committed.
     */
    fun validateProviderFilename(
        actual: String,
        requested: String,
        preferred: String,
    ) {
        validateMediaBasename(requested, actual = false)
        validatePreferredFilename(preferred)
        val expectedCardPreferred = cardPreferredName(requested)
        val expectedDictionaryPreferred = "anki_miner_dict_${sha256Hex(requested)}"
        if (preferred != expectedCardPreferred && preferred != expectedDictionaryPreferred) {
            failValue("preferred media name is inconsistent with the requested filename")
        }
        if (actual == requested) return
        validateMediaBasename(actual, actual = true)
        validatePreferredFilename(actual)
        val suffixIndex = actual.lastIndexOf('.')
        if (suffixIndex <= 0 || suffixIndex == actual.lastIndex) failValue("provider media filename has no extension")
        val stem = actual.substring(0, suffixIndex)
        if (!stem.startsWith("${preferred}_")) failValue("provider media filename is unrelated to its request")
    }

    private fun cardPreferredName(filename: String): String {
        val suffixIndex = filename.lastIndexOf('.')
        val stem = if (suffixIndex > 0 && suffixIndex < filename.lastIndex) filename.substring(0, suffixIndex) else filename
        val preferred = stem.replace(' ', '_')
        val length = strictStats(preferred, "preferred card media name").scalarCount
        return if (length >= 2) preferred else "${preferred}_"
    }

    private fun safeProviderFilenameStem(filename: String): String? {
        try {
            validatePreferredFilename(filename)
        } catch (error: AnkiProtocolException) {
            if (error.category == AnkiProtocolCategory.INVALID_VALUE) return null
            throw error
        }
        val suffixIndex = filename.lastIndexOf('.')
        return if (suffixIndex > 0 && suffixIndex < filename.lastIndex) {
            filename.substring(0, suffixIndex)
        } else {
            null
        }
    }

    private fun sha256Hex(value: String): String {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        val alphabet = "0123456789abcdef"
        return buildString(64) {
            for (byte in MessageDigest.getInstance("SHA-256").digest(bytes)) {
                val unsigned = byte.toInt() and 0xff
                append(alphabet[unsigned ushr 4])
                append(alphabet[unsigned and 0x0f])
            }
        }
    }

    private fun startsWithAsciiIgnoreCase(value: String, prefix: String): Boolean {
        if (value.length < prefix.length) return false
        for (index in prefix.indices) {
            val actual = value[index]
            val expected = prefix[index]
            val folded = if (actual in 'A'..'Z') (actual.code + ('a'.code - 'A'.code)).toChar() else actual
            if (folded != expected) return false
        }
        return true
    }

    private fun validateSourcePath(value: String) {
        val stats = validatePlainString(value, "media source path", allowEmpty = false, maxScalars = AnkiLimitsV1.StoreMedia.SOURCE_PATH_MAX_CODE_POINTS, maxUtf8Bytes = AnkiLimitsV1.StoreMedia.SOURCE_PATH_MAX_UTF8_BYTES)
        if (!value.startsWith('/') || '\u0000' in value || stats.scalarCount == 0) failValue("media source path is not an absolute POSIX path")
    }

    private fun validatePlainString(
        value: String,
        context: String,
        allowEmpty: Boolean,
        maxScalars: Int = Int.MAX_VALUE,
        maxUtf8Bytes: Int = Int.MAX_VALUE,
    ): StringStats {
        val stats = strictStats(value, context)
        if (!allowEmpty && stats.scalarCount == 0) failValue("$context is empty")
        requireAtMost(stats.scalarCount, maxScalars, "$context scalar count")
        requireAtMost(stats.utf8Bytes, maxUtf8Bytes, "$context UTF-8 bytes")
        return stats
    }

    private fun containsCategoryC(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val first = value[index].code
            val codePoint: Int
            if (first in 0xD800..0xDBFF) {
                val second = value[index + 1].code
                codePoint = 0x10000 + ((first - 0xD800) shl 10) + (second - 0xDC00)
                index += 2
            } else {
                codePoint = first
                index += 1
            }
            if (UnicodeContractV151.isCategoryC(codePoint)) return true
        }
        return false
    }

    private fun boundedText(value: String, context: String, limit: Int): Int =
        validatePlainString(value, context, allowEmpty = true, maxUtf8Bytes = limit).utf8Bytes

    private fun boundedNullableText(value: String?, context: String, limit: Int): Int =
        value?.let { boundedText(it, context, limit) } ?: 0

    private fun requirePositive(value: Long, context: String) {
        if (value < 1) failValue("$context must be positive")
    }

    private fun requireCountAtMost(value: Int, maximum: Int, context: String) = requireAtMost(value, maximum, context)

    private fun requireCountBetween(value: Int, minimum: Int, maximum: Int, context: String) {
        if (value < minimum || value > maximum) failValue("$context count is outside the v1 limit")
    }

    private fun requireAtMost(value: Int, maximum: Int, context: String) {
        if (value > maximum) failValue("$context exceeds the v1 limit")
    }

    private fun <T> requireUnique(values: List<T>, context: String) {
        if (values.size != values.toSet().size) failValue("$context are not unique")
    }

    private fun addExact(left: Int, right: Int): Int =
        try {
            Math.addExact(left, right)
        } catch (error: ArithmeticException) {
            failValue("aggregate byte or item count overflowed")
        }

    private fun addExact(left: Long, right: Long): Long =
        try {
            Math.addExact(left, right)
        } catch (error: ArithmeticException) {
            failValue("aggregate byte count overflowed")
        }

    private fun failValue(message: String): Nothing = fail(AnkiProtocolCategory.INVALID_VALUE, message)

    private fun fail(category: AnkiProtocolCategory, message: String): Nothing =
        throw AnkiProtocolException(category, message)
}
