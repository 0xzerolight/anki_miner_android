package com.ankiminer.android.anki.provider

import com.ankiminer.android.anki.generated.AnkiLimitsV1
import com.ankiminer.android.anki.protocol.AnkiErrorCode
import com.ankiminer.android.anki.protocol.AnkiJsonCodec
import com.ankiminer.android.anki.protocol.DuplicateLookupResult
import com.ankiminer.android.anki.protocol.DuplicateScanScope
import com.ankiminer.android.anki.protocol.KnownVocabularyResult
import com.ankiminer.android.anki.protocol.KnownVocabularyScope
import com.ankiminer.android.anki.protocol.RawFirstFieldHit
import com.ankiminer.android.anki.protocol.ScanFirstFieldsRequest
import com.ankiminer.android.anki.protocol.VerifyTargetRequest

internal data class DuplicateRawSnapshot(
    val rawFirstFieldHits: List<List<RawFirstFieldHit>>,
    val normalizedMatchingNoteIds: List<Set<Long>>,
)

/**
 * [stableMessage] is the whole user-facing account of the failure, and it is deliberately
 * category-level: it names what the app could not do, never what the platform said. [cause] is what
 * the platform said. Keeping it costs nothing at the seam and turns "AnkiDroid became unavailable"
 * in a log into a named `SecurityException` or `DeadObjectException` with a stack.
 */
internal class AnkiReadFailure(
    val code: AnkiErrorCode,
    val retryable: Boolean,
    val stableMessage: String,
    val providerErrorReason: NoteTypeProviderErrorReason = code.defaultProviderErrorReason(),
    cause: Throwable? = null,
) : RuntimeException(stableMessage, cause)

private fun AnkiErrorCode.defaultProviderErrorReason(): NoteTypeProviderErrorReason =
    when (this) {
        AnkiErrorCode.API_DISABLED -> NoteTypeProviderErrorReason.API_DISABLED
        AnkiErrorCode.PERMISSION_REQUIRED -> NoteTypeProviderErrorReason.PERMISSION_REQUIRED
        AnkiErrorCode.PROVIDER_UNAVAILABLE -> NoteTypeProviderErrorReason.PROVIDER_UNAVAILABLE
        AnkiErrorCode.QUERY_FAILED -> NoteTypeProviderErrorReason.QUERY_FAILED
        AnkiErrorCode.TIMEOUT -> NoteTypeProviderErrorReason.TIMEOUT
        AnkiErrorCode.CANCELLED -> NoteTypeProviderErrorReason.CANCELLED
        else -> NoteTypeProviderErrorReason.UNKNOWN
    }

internal class AnkiProviderReadService(
    private val gateway: AnkiProviderGateway,
    private val registry: AnkiRunStateRegistry,
    private val tokenFactory: OpaqueTokenFactory = SecureOpaqueTokenFactory(),
) {
    private val provider = CheckedProvider(gateway)
    private val targets = TargetSnapshotReader(provider)
    private val notes = NoteSnapshotReader(provider)
    private val cards = GlobalCardReader(provider)

    /** Read-only existing-target probe. Durable admission is owned exclusively by DurableTargetVerifier. */
    fun readExistingTarget(
        owner: AnkiRunStateRegistry.RunOwner,
        request: VerifyTargetRequest,
    ): TargetSnapshot {
        val cancellation = registry.cancellation(owner)
        ensureActive(cancellation)
        val model = targets.readModelByName(request.modelName, cancellation)
        val missing = request.requiredFields.filterNot(model.fieldNames::contains)
        if (missing.isNotEmpty()) {
            throw AnkiReadFailure(
                AnkiErrorCode.FIELD_MISSING,
                retryable = false,
                stableMessage = "The selected note type is missing a required field",
            )
        }
        val deck =
            targets.readDeckByName(request.deckName, cancellation)
                ?: throw AnkiReadFailure(
                    AnkiErrorCode.UNSUPPORTED_OPERATION,
                    retryable = false,
                    stableMessage = "Creating a missing Anki deck is not available in the read-only provider phase",
                )
        return TargetSnapshot(deck, model)
    }

    /** Picker read of every user note type, for the setup UI. */
    fun listNoteTypes(cancellation: AnkiCancellation): List<ModelSummary> =
        targets.listModelSummaries(cancellation)

    /** Live picker read of canonical Anki deck names for known-vocabulary exclusions. */
    fun listDeckNames(cancellation: AnkiCancellation): List<String> =
        targets.readAllDeckNames(cancellation).sorted()

    /**
     * Detect/verify a user-selected note type + field mapping. Never creates a note type; the
     * authoritative mining gate stays the admission probe plus the Python `verify_card_target`.
     */
    fun verifyUserNoteType(
        noteType: String,
        fieldMap: Map<String, String>,
        cancellation: AnkiCancellation,
        cardTypeMarkerField: String? = null,
    ): NoteTypeSetupStatus {
        val model =
            try {
                targets.readModelByNameOrNull(noteType, cancellation)
            } catch (f: AnkiReadFailure) {
                return NoteTypeSetupStatus.ProviderError(
                    reason = f.providerErrorReason,
                    code = f.code,
                    retryable = f.retryable,
                    stableMessage = f.stableMessage,
                )
            } ?: return NoteTypeSetupStatus.NoteTypeMissing
        if (fieldMap[AnkiFieldKeys.WORD].isNullOrEmpty()) {
            return NoteTypeSetupStatus.FieldMapInvalid(
                destination = model.fieldNames.firstOrNull().orEmpty(),
                logicalKeys = listOf(AnkiFieldKeys.WORD),
            )
        }
        val missing =
            AnkiFieldKeys.REQUIRED.filter { key ->
                val v = fieldMap[key].orEmpty()
                v.isEmpty() || v !in model.fieldNames
            }
        val missingOptional =
            fieldMap.keys.filter { key ->
                key !in AnkiFieldKeys.REQUIRED &&
                    fieldMap[key].orEmpty().let { it.isNotEmpty() && it !in model.fieldNames }
            }
        val allMissing = (missing + missingOptional).distinct().sorted()
        if (allMissing.isNotEmpty()) return NoteTypeSetupStatus.FieldsMissing(allMissing)
        val marker = cardTypeMarkerField?.takeIf { it.isNotEmpty() }
        if (marker != null && marker !in model.fieldNames) {
            return NoteTypeSetupStatus.FieldsMissing(
                listOf(AnkiFieldMapPolicy.CARD_TYPE_MARKER_KEY),
            )
        }
        if (fieldMap[AnkiFieldKeys.WORD] != model.fieldNames.firstOrNull()) {
            return NoteTypeSetupStatus.FirstFieldMismatch
        }
        AnkiFieldMapPolicy.firstConflict(fieldMap)?.let { conflict ->
            return NoteTypeSetupStatus.FieldMapInvalid(
                conflict.destination,
                conflict.logicalKeys,
            )
        }
        if (marker != null) {
            fieldMap.entries
                .firstOrNull { (_, destination) -> destination == marker }
                ?.let { (logicalKey, _) ->
                    return NoteTypeSetupStatus.FieldMapInvalid(
                        marker,
                        listOf(logicalKey, AnkiFieldMapPolicy.CARD_TYPE_MARKER_KEY),
                    )
                }
        }
        return NoteTypeSetupStatus.Verified(model.id)
    }

    fun readTargetById(
        owner: AnkiRunStateRegistry.RunOwner,
        expected: TargetSnapshot,
    ): TargetSnapshot {
        val cancellation = registry.cancellation(owner)
        val actual =
            TargetSnapshot(
                model = targets.readModelById(expected.model.id, cancellation),
                deck = targets.readDeckById(expected.deck.id, cancellation),
            )
        if (actual != expected) throw targetInvalid("The verified Anki target changed during this run")
        return actual
    }

    fun readNoteById(
        owner: AnkiRunStateRegistry.RunOwner,
        noteId: Long,
    ): NoteSnapshot = notes.readById(noteId, registry.cancellation(owner))

    fun readCardById(
        owner: AnkiRunStateRegistry.RunOwner,
        cardId: Long,
    ): CardIdentity = cards.readById(cardId, registry.cancellation(owner))

    /**
     * Reusable exact duplicate snapshot for baseline admission and pre-insert reconciliation.
     * A null deck ID means collection scope; the only accepted exact scope is the target deck.
     */
    fun readDuplicateSnapshot(
        owner: AnkiRunStateRegistry.RunOwner,
        target: TargetSnapshot,
        candidates: List<com.ankiminer.android.anki.protocol.DuplicateCandidate>,
        scopeDeckId: Long?,
    ): DuplicateRawSnapshot {
        if (registry.target(owner) != target) {
            throw invalidRequest("The duplicate lookup target is not active")
        }
        if (scopeDeckId != null && scopeDeckId != target.deck.id) {
            throw invalidRequest("The duplicate lookup deck scope is not active")
        }
        if (
            candidates.size !in
                1..AnkiLimitsV1.ScanFirstFields.DUPLICATE_CANDIDATE_MAX_ITEM_COUNT ||
                candidates.distinct().size != candidates.size
        ) {
            throw invalidRequest("The duplicate candidate table is invalid")
        }
        val cancellation = registry.cancellation(owner)
        ensureActive(cancellation)
        val checksums = candidates.map { gateway.fieldChecksum(it.firstField) }
        if (checksums.any { it !in 0L..MAX_FIELD_CHECKSUM }) throw queryFailed()
        val hits =
            readDuplicateHits(
                target = target,
                checksums = checksums,
                exactDeck = scopeDeckId != null,
                cancellation = cancellation,
            )
        val matches =
            candidates.indices.map { index ->
                hits[index]
                    .asSequence()
                    .filter { hit ->
                        try {
                            DuplicateFirstFieldNormalizer.normalize(hit.firstField) ==
                                candidates[index].key
                        } catch (_: IllegalArgumentException) {
                            throw queryFailed()
                        }
                    }.mapTo(linkedSetOf(), RawFirstFieldHit::noteId)
            }
        return DuplicateRawSnapshot(
            rawFirstFieldHits = hits.map { bucket -> bucket.toList() },
            normalizedMatchingNoteIds = matches,
        )
    }

    fun scanFirstFields(
        owner: AnkiRunStateRegistry.RunOwner,
        request: ScanFirstFieldsRequest,
    ) =
        when (val scope = request.scope) {
            is KnownVocabularyScope -> scanKnownVocabulary(owner, request, scope)
            is DuplicateScanScope -> scanDuplicates(owner, request, scope)
        }

    private fun scanKnownVocabulary(
        owner: AnkiRunStateRegistry.RunOwner,
        request: ScanFirstFieldsRequest,
        scope: KnownVocabularyScope,
    ): KnownVocabularyResult {
        val cancellation = registry.cancellation(owner)
        val traversalScope =
            KnownTraversalScope(
                excludedDecks = scope.excludedDecks,
                deckName = scope.deckName,
            )
        val lease =
            if (scope.cursor == null) {
                val initialization = registry.beginKnownTraversal(owner, traversalScope)
                try {
                    val noteIds = snapshotKnownNoteIds(owner, traversalScope, cancellation)
                    registry.finishKnownTraversalInitialization(owner, initialization, noteIds)
                } catch (error: RuntimeException) {
                    registry.abortKnownTraversalInitialization(owner, initialization)
                    throw error
                }
            } else {
                registry.reserveKnownPage(owner, traversalScope, scope.cursor)
            }
        try {
            val firstFields = readKnownPage(lease.noteIds, cancellation)
            val nextToken =
                if (lease.hasMoreAfterPage) tokenFactory.nextToken(KNOWN_CURSOR_PREFIX) else null
            val expectedNextCursor =
                nextToken?.let { token ->
                    com.ankiminer.android.anki.protocol.KnownVocabularyCursor(
                        ordinal = lease.responseCursorOrdinal,
                        token = token,
                    )
                }
            val response =
                KnownVocabularyResult(
                    runId = request.runId,
                    requestId = request.requestId,
                    firstFields = firstFields,
                    scannedNotes = lease.noteIds.size,
                    nextCursor = expectedNextCursor,
                )
            preflightCanonicalResponse(request, response)
            registry.completeKnownPage(owner, lease, nextToken)
            return response
        } catch (error: RuntimeException) {
            registry.abortKnownPage(owner, lease)
            throw if (scope.cursor != null) error.afterCapabilityConsumption() else error
        }
    }

    private fun snapshotKnownNoteIds(
        owner: AnkiRunStateRegistry.RunOwner,
        scope: KnownTraversalScope,
        cancellation: AnkiCancellation,
    ): List<Long> {
        val snapshot =
            if (scope.deckName == null) {
                val allNotes = ArrayList<Long>()
                provider.queryRequired(NOTE_ID_SNAPSHOT_QUERY, cancellation).use { cursor ->
                    requireProjection(cursor, NOTE_ID_SNAPSHOT_QUERY)
                    var prior = 0L
                    while (cursor.moveToNext()) {
                        ensureActive(cancellation)
                        val id = cursor.positiveLong(ProviderColumn.NOTE_ID)
                        if (id <= prior) throw queryFailed()
                        prior = id
                        allNotes += id
                        if (allNotes.size > AnkiLimitsV1.ScanFirstFields.KNOWN_TOTAL_SCANNED_NOTE_MAX_COUNT) {
                            throw knownVocabularyLimitExceeded("an Anki collection")
                        }
                    }
                }
                allNotes
            } else {
                val target = registry.target(owner) ?: throw invalidRequest("The Anki target has not been verified")
                if (scope.deckName != target.deck.name) {
                    throw invalidRequest("The known-vocabulary deck scope does not match the verified target")
                }
                val exactNotes = linkedSetOf<Long>()
                val query =
                    ProviderQuery(
                        endpoint = ProviderEndpoint.CARDS,
                        projection = ProviderQueryShapes.CARD_NOTE_DECK_PROJECTION,
                        selection = ProviderSelection.CardsInDeck(scope.deckName),
                    )
                var scannedCardRows = 0
                provider.queryRequired(query, cancellation).use { cursor ->
                    requireProjection(cursor, query)
                    while (cursor.moveToNext()) {
                        ensureActive(cancellation)
                        // `deck:"Name"` is a deck-TREE scope, so subdeck rows cross Binder too.
                        // Counted per row and checked before the cell reads, so the walk refuses on
                        // reaching the first row past its budget without pulling that row's cells.
                        scannedCardRows = checkedAdd(scannedCardRows, 1)
                        if (
                            scannedCardRows >
                            AnkiLimitsV1.ScanFirstFields.KNOWN_TOTAL_SCANNED_CARD_ROW_MAX_COUNT
                        ) {
                            throw knownVocabularyDeckTreeTooLarge()
                        }
                        val noteId = cursor.positiveLong(ProviderColumn.CARD_NOTE_ID)
                        val deckId = cursor.positiveLong(ProviderColumn.CARD_DECK_ID)
                        if (deckId != target.deck.id) continue
                        exactNotes += noteId
                        // The note ceiling binds the RESULT, as it does for every other scan: this
                        // snapshot is returned as `scannedNotes`, which anki_adapter.py refuses
                        // above the same constant. The row budget above cannot stand in for it.
                        if (
                            exactNotes.size >
                            AnkiLimitsV1.ScanFirstFields.KNOWN_TOTAL_SCANNED_NOTE_MAX_COUNT
                        ) {
                            throw knownVocabularyLimitExceeded("the selected Anki deck")
                        }
                    }
                }
                exactNotes.sorted()
            }
        if (scope.excludedDecks.isEmpty() || snapshot.isEmpty()) return snapshot

        val existingNames = targets.readAllDeckNames(cancellation)
        val minimalScopes = minimalExistingDeckScopes(scope.excludedDecks, existingNames)
        if (minimalScopes.isEmpty()) return snapshot
        val snapshotSet = snapshot.toHashSet()
        val excluded = HashSet<Long>()
        var excludedBrowserRows = 0
        for (deckName in minimalScopes) {
            ensureActive(cancellation)
            val query =
                ProviderQuery(
                    endpoint = ProviderEndpoint.NOTES_BROWSER,
                    projection = ProviderQueryShapes.NOTE_ID_PROJECTION,
                    selection = ProviderSelection.ExcludedDeck(deckName),
                )
            provider.queryRequired(query, cancellation).use { cursor ->
                requireProjection(cursor, query)
                val seen = HashSet<Long>()
                while (cursor.moveToNext()) {
                    ensureActive(cancellation)
                    // Counted per row and checked before the cell read, so the scan refuses on
                    // reaching row 100001 without pulling its cells.
                    excludedBrowserRows = checkedAdd(excludedBrowserRows, 1)
                    if (
                        excludedBrowserRows >
                        AnkiLimitsV1.ScanFirstFields.KNOWN_TOTAL_SCANNED_NOTE_MAX_COUNT
                    ) {
                        throw knownVocabularyLimitExceeded("the excluded Anki decks")
                    }
                    val id = cursor.positiveLong(ProviderColumn.NOTE_ID)
                    if (!seen.add(id)) throw queryFailed()
                    if (id in snapshotSet) excluded += id
                }
            }
        }
        return snapshot.filterNot(excluded::contains)
    }

    private fun readKnownPage(
        noteIds: List<Long>,
        cancellation: AnkiCancellation,
    ): List<String> {
        if (noteIds.isEmpty()) return emptyList()
        val query =
            ProviderQuery(
                endpoint = ProviderEndpoint.NOTES_V2,
                projection = ProviderQueryShapes.NOTE_PAGE_PROJECTION,
                selection = ProviderSelection.NoteIds(noteIds),
            )
        val fieldsById = HashMap<Long, String>()
        var totalBytes = 0
        provider.queryRequired(query, cancellation).use { cursor ->
            requireProjection(cursor, query)
            while (cursor.moveToNext()) {
                ensureActive(cancellation)
                val id = cursor.positiveLong(ProviderColumn.NOTE_ID)
                if (id !in noteIds || fieldsById.containsKey(id)) throw queryFailed()
                val rawFields = cursor.text(ProviderColumn.NOTE_FIELDS)
                val firstField = ProviderSnapshotValidation.firstField(rawFields)
                totalBytes = checkedAdd(totalBytes, validateProviderFirstField(firstField))
                if (totalBytes > AnkiLimitsV1.ScanFirstFields.KNOWN_PAGE_MAX_UTF8_BYTES) {
                    throw queryFailed("An Anki known-vocabulary page exceeds the v1 text limit")
                }
                fieldsById[id] = firstField
            }
        }
        val result = ArrayList<String>(fieldsById.size)
        for (id in noteIds) {
            val value = fieldsById[id] ?: continue
            result += value
        }
        return result
    }

    private fun scanDuplicates(
        owner: AnkiRunStateRegistry.RunOwner,
        request: ScanFirstFieldsRequest,
        scope: DuplicateScanScope,
    ): DuplicateLookupResult {
        val target = registry.target(owner) ?: throw invalidRequest("The Anki target has not been verified")
        if (
            target.model.name != scope.modelName ||
                target.model.fieldNames.first() != scope.firstFieldName ||
                (scope.deckName != null && scope.deckName != target.deck.name)
        ) {
            throw invalidRequest("The duplicate lookup does not match the verified target")
        }
        val probe = registry.beginBaselineProbe(owner, scope.invalidateBaselineToken)
        try {
            val snapshot =
                readDuplicateSnapshot(
                    owner = owner,
                    target = target,
                    candidates = scope.candidates,
                    scopeDeckId = if (scope.deckName == null) null else target.deck.id,
                )
            val token = tokenFactory.nextToken(BASELINE_PREFIX)
            val baseline =
                DuplicateBaseline(
                    token = token,
                    target = target,
                    firstFieldName = scope.firstFieldName,
                    scopeDeckId = if (scope.deckName == null) null else target.deck.id,
                    candidates = scope.candidates,
                    occurrences = scope.occurrences,
                    providerNoteIds =
                        snapshot.rawFirstFieldHits.map { bucket ->
                            bucket.mapTo(linkedSetOf(), RawFirstFieldHit::noteId)
                        },
                    normalizedMatchingNoteIds = snapshot.normalizedMatchingNoteIds,
                )
            val response =
                DuplicateLookupResult(
                    runId = request.runId,
                    requestId = request.requestId,
                    rawFirstFieldHits = snapshot.rawFirstFieldHits,
                    baselineToken = token,
                )
            preflightCanonicalResponse(request, response)
            registry.completeBaselineProbe(owner, probe, baseline)
            return response
        } catch (error: RuntimeException) {
            registry.abortBaselineProbe(owner, probe)
            throw if (scope.invalidateBaselineToken != null) error.afterCapabilityConsumption() else error
        }
    }

    private fun readDuplicateHits(
        target: TargetSnapshot,
        checksums: List<Long>,
        exactDeck: Boolean,
        cancellation: AnkiCancellation,
    ): List<List<RawFirstFieldHit>> {
        val uniqueChecksums = checksums.distinct().sorted()
        val query =
            ProviderQuery(
                endpoint = ProviderEndpoint.NOTES_V2,
                projection = ProviderQueryShapes.DUPLICATE_PROJECTION,
                selection =
                    ProviderSelection.DuplicateChecksums(
                        modelId = target.model.id,
                        checksums = uniqueChecksums,
                    ),
                sortOrder = ProviderOrder.NOTE_ID_ASCENDING,
            )
        val rows = ArrayList<DuplicateProviderRow>()
        var observedRows = 0
        var retainedHits = 0
        var retainedBytes = 0
        val candidateMultiplicity = checksums.groupingBy { it }.eachCount()
        val hitsPerChecksum = HashMap<Long, Int>()
        provider.queryRequired(query, cancellation).use { cursor ->
            requireProjection(cursor, query)
            var priorId = 0L
            while (cursor.moveToNext()) {
                ensureActive(cancellation)
                observedRows += 1
                if (observedRows > AnkiLimitsV1.ScanFirstFields.DUPLICATE_HIT_TOTAL_MAX_ITEM_COUNT) {
                    throw queryFailed("The Anki duplicate lookup exceeds the v1 hit limit")
                }
                val id = cursor.positiveLong(ProviderColumn.NOTE_ID)
                if (id <= priorId) throw queryFailed()
                priorId = id
                val checksum = cursor.nonNegativeLong(ProviderColumn.NOTE_CHECKSUM)
                if (checksum !in uniqueChecksums) throw queryFailed()
                val firstField =
                    ProviderSnapshotValidation.firstField(
                        cursor.text(ProviderColumn.NOTE_FIELDS),
                    )
                val firstFieldBytes = validateProviderFirstField(firstField)
                if (
                    !exactDeck ||
                        cards.readForNote(
                            noteId = id,
                            templateCount = target.model.templates.size,
                            cancellation = cancellation,
                        ).any { it.deckId == target.deck.id }
                ) {
                    val checksumHits = checkedAdd(hitsPerChecksum[checksum] ?: 0, 1)
                    if (checksumHits > AnkiLimitsV1.ScanFirstFields.DUPLICATE_HIT_PER_CANDIDATE_MAX_ITEM_COUNT) {
                        throw queryFailed("An Anki duplicate bucket exceeds the v1 hit limit")
                    }
                    hitsPerChecksum[checksum] = checksumHits
                    val multiplicity = candidateMultiplicity.getValue(checksum)
                    retainedHits = checkedAdd(retainedHits, multiplicity)
                    if (retainedHits > AnkiLimitsV1.ScanFirstFields.DUPLICATE_HIT_TOTAL_MAX_ITEM_COUNT) {
                        throw queryFailed("The Anki duplicate lookup exceeds the v1 hit limit")
                    }
                    retainedBytes =
                        checkedAdd(
                            retainedBytes,
                            checkedMultiply(firstFieldBytes, multiplicity),
                        )
                    if (retainedBytes > AnkiLimitsV1.ScanFirstFields.DUPLICATE_HIT_TOTAL_MAX_UTF8_BYTES) {
                        throw queryFailed("The Anki duplicate lookup exceeds the v1 text limit")
                    }
                    rows += DuplicateProviderRow(id, checksum, firstField)
                    if (rows.size > AnkiLimitsV1.ScanFirstFields.DUPLICATE_HIT_TOTAL_MAX_ITEM_COUNT) {
                        throw queryFailed("The Anki duplicate lookup exceeds the v1 hit limit")
                    }
                }
            }
        }
        return checksums.map { checksum ->
            rows
                .asSequence()
                .filter { it.checksum == checksum }
                .map { row -> RawFirstFieldHit(row.noteId, row.firstField) }
                .toList()
        }
    }

    private data class DuplicateProviderRow(
        val noteId: Long,
        val checksum: Long,
        val firstField: String,
    )

    private companion object {
        const val KNOWN_CURSOR_PREFIX = "cursor_"
        const val BASELINE_PREFIX = "baseline_"
        const val MAX_FIELD_CHECKSUM = 0xffff_ffffL
        val NOTE_ID_SNAPSHOT_QUERY =
            ProviderQuery(
                endpoint = ProviderEndpoint.NOTES_V2,
                projection = ProviderQueryShapes.NOTE_ID_PROJECTION,
                sortOrder = ProviderOrder.NOTE_ID_ASCENDING,
            )
    }
}

internal class TargetSnapshotReader(private val provider: CheckedProvider) {
    fun readModelByName(
        name: String,
        cancellation: AnkiCancellation,
    ): ModelSnapshot =
        readModelByNameOrNull(name, cancellation)
            ?: throw AnkiReadFailure(
                AnkiErrorCode.NOTE_TYPE_NOT_FOUND,
                retryable = false,
                stableMessage = "The selected Anki note type was not found",
            )

    fun readModelByNameOrNull(
        name: String,
        cancellation: AnkiCancellation,
    ): ModelSnapshot? {
        val matches = ArrayList<ModelRow>()
        provider.queryRequired(MODEL_LIST_QUERY, cancellation).use { cursor ->
            requireProjection(cursor, MODEL_LIST_QUERY)
            var rows = 0
            while (cursor.moveToNext()) {
                ensureActive(cancellation)
                rows += 1
                if (rows > MAX_PROVIDER_LIST_ROWS) throw queryFailed()
                val providerName = cursor.text(ProviderColumn.MODEL_NAME)
                if (providerName == name) matches += cursor.readModelRow(providerName)
            }
        }
        if (matches.isEmpty()) return null
        if (matches.size != 1) throw targetInvalid("The selected Anki note type is ambiguous")
        return completeModel(matches.single(), cancellation)
    }

    /**
     * Lightweight picker read: every model's id/name/field names, without completing templates.
     * A single unsupported model (TARGET_INVALID) is skipped so it cannot break the whole list;
     * any other read failure propagates.
     */
    fun listModelSummaries(cancellation: AnkiCancellation): List<ModelSummary> {
        val summaries = ArrayList<ModelSummary>()
        provider.queryRequired(MODEL_LIST_QUERY, cancellation).use { cursor ->
            requireProjection(cursor, MODEL_LIST_QUERY)
            var rows = 0
            while (cursor.moveToNext()) {
                ensureActive(cancellation)
                rows += 1
                if (rows > MAX_PROVIDER_LIST_ROWS) throw queryFailed()
                val row =
                    try {
                        cursor.readModelRow()
                    } catch (failure: AnkiReadFailure) {
                        if (failure.code == AnkiErrorCode.TARGET_INVALID) continue
                        throw failure
                    }
                summaries +=
                    ModelSummary(
                        id = row.id,
                        name = row.name,
                        fieldNames = row.fieldNames,
                    )
            }
        }
        return summaries
    }

    fun readModelById(
        id: Long,
        cancellation: AnkiCancellation,
    ): ModelSnapshot {
        val row =
            try {
                readModelItemById(id, cancellation)
            } catch (failure: AnkiReadFailure) {
                if (failure.code != AnkiErrorCode.QUERY_FAILED) throw failure
                reconcileModelByIdFromList(id, cancellation)
            }
        if (row.id != id) throw queryFailed()
        return completeModel(row, cancellation)
    }

    private fun readModelItemById(
        id: Long,
        cancellation: AnkiCancellation,
    ): ModelRow {
        val query = MODEL_LIST_QUERY.copy(endpoint = ProviderEndpoint.MODEL_BY_ID, endpointId = id)
        return provider.queryRequired(query, cancellation).use { cursor ->
            requireProjection(cursor, query)
            if (!cursor.moveToNext()) throw targetInvalid("The verified Anki note type no longer exists")
            val result = cursor.readModelRow()
            if (cursor.moveToNext()) throw queryFailed()
            result
        }
    }

    private fun reconcileModelByIdFromList(
        id: Long,
        cancellation: AnkiCancellation,
    ): ModelRow {
        val matches = ArrayList<ModelRow>()
        provider.queryRequired(MODEL_LIST_QUERY, cancellation).use { cursor ->
            requireProjection(cursor, MODEL_LIST_QUERY)
            var rows = 0
            while (cursor.moveToNext()) {
                ensureActive(cancellation)
                rows += 1
                if (rows > MAX_PROVIDER_LIST_ROWS) throw queryFailed()
                val providerId = cursor.positiveLong(ProviderColumn.MODEL_ID)
                if (providerId == id) matches += cursor.readModelRow(knownId = providerId)
            }
        }
        if (matches.isEmpty()) {
            throw targetInvalid("The verified Anki note type no longer exists")
        }
        if (matches.size != 1) {
            throw targetInvalid("The verified Anki note type ID is ambiguous")
        }
        return matches.single()
    }

    fun readDeckByName(
        name: String,
        cancellation: AnkiCancellation,
    ): DeckSnapshot? {
        val matches = readAllDecks(cancellation).filter { it.name == name }
        if (matches.size > 1) throw targetInvalid("The selected Anki deck is ambiguous")
        return matches.singleOrNull()?.also { deck ->
            try {
                ProviderSnapshotValidation.validateDeck(deck)
            } catch (_: InvalidTargetSnapshotException) {
                throw targetInvalid("The selected Anki deck is not supported")
            }
        }
    }

    fun readDeckById(
        id: Long,
        cancellation: AnkiCancellation,
    ): DeckSnapshot {
        val query = DECK_LIST_QUERY.copy(endpoint = ProviderEndpoint.DECK_BY_ID, endpointId = id)
        val deck = provider.queryRequired(query, cancellation).use { cursor ->
            requireProjection(cursor, query)
            if (!cursor.moveToNext()) throw targetInvalid("The verified Anki deck no longer exists")
            val result = cursor.readDeck()
            if (cursor.moveToNext()) throw queryFailed()
            result
        }
        if (deck.id != id) throw queryFailed()
        try {
            ProviderSnapshotValidation.validateDeck(deck)
        } catch (_: InvalidTargetSnapshotException) {
            throw targetInvalid("The verified Anki deck is not supported")
        }
        return deck
    }

    fun readAllDeckNames(cancellation: AnkiCancellation): Set<String> =
        readAllDecks(cancellation).mapTo(linkedSetOf()) { it.name }

    private fun readAllDecks(cancellation: AnkiCancellation): List<DeckSnapshot> {
        val decks = ArrayList<DeckSnapshot>()
        provider.queryRequired(DECK_LIST_QUERY, cancellation).use { cursor ->
            requireProjection(cursor, DECK_LIST_QUERY)
            while (cursor.moveToNext()) {
                ensureActive(cancellation)
                decks += cursor.readDeck()
                if (decks.size > MAX_PROVIDER_LIST_ROWS) throw queryFailed()
            }
        }
        return decks
    }

    private fun completeModel(
        row: ModelRow,
        cancellation: AnkiCancellation,
    ): ModelSnapshot {
        val query =
            ProviderQuery(
                endpoint = ProviderEndpoint.MODEL_TEMPLATES,
                endpointId = row.id,
                projection = ProviderQueryShapes.TEMPLATE_PROJECTION,
            )
        val templates = ArrayList<TemplateSnapshot>()
        val ordinals = HashSet<Int>()
        var providerTextBytes = row.providerTextUtf8Bytes
        provider.queryRequired(query, cancellation).use { cursor ->
            requireProjection(cursor, query)
            while (cursor.moveToNext()) {
                ensureActive(cancellation)
                if (templates.size == AnkiLimitsV1.TargetModel.MAX_TEMPLATE_COUNT) {
                    throw targetInvalid("The selected Anki note type has too many templates")
                }
                val template = cursor.readTemplate()
                try {
                    providerTextBytes =
                        ProviderSnapshotValidation.validateTemplate(
                            template = template,
                            expectedModelId = row.id,
                            cardCount = row.cardCount,
                            providerTextUtf8Bytes = providerTextBytes,
                        )
                    if (!ordinals.add(template.ordinal)) throw InvalidTargetSnapshotException()
                } catch (_: InvalidTargetSnapshotException) {
                    throw targetInvalid("The selected Anki note type is not supported")
                }
                templates += template
            }
        }
        val snapshot =
            ModelSnapshot(
                id = row.id,
                name = row.name,
                type = row.type,
                fieldNames = row.fieldNames,
                cardCount = row.cardCount,
                sortFieldIndex = row.sortFieldIndex,
                effectiveDefaultDeckId = row.effectiveDefaultDeckId,
                css = row.css,
                latexPre = row.latexPre,
                latexPost = row.latexPost,
                templates = templates.sortedBy { it.ordinal },
            )
        try {
            ProviderSnapshotValidation.validateModel(snapshot)
        } catch (_: InvalidTargetSnapshotException) {
            throw targetInvalid("The selected Anki note type is not supported")
        }
        return snapshot
    }

    private data class ModelRow(
        val id: Long,
        val name: String,
        val fieldNames: List<String>,
        val cardCount: Int,
        val css: String,
        val effectiveDefaultDeckId: Long,
        val sortFieldIndex: Int,
        val type: Int,
        val latexPost: String?,
        val latexPre: String?,
        val providerTextUtf8Bytes: Int,
    )

    private fun ProviderCursor.readModelRow(
        knownName: String? = null,
        knownId: Long? = null,
    ): ModelRow {
        val id = knownId ?: positiveLong(ProviderColumn.MODEL_ID)
        val name = knownName ?: text(ProviderColumn.MODEL_NAME)
        val rawFieldNames = text(ProviderColumn.MODEL_FIELD_NAMES)
        val cardCount = exactInt(ProviderColumn.MODEL_CARD_COUNT)
        val css = text(ProviderColumn.MODEL_CSS)
        val effectiveDefaultDeckId = effectiveDefaultDeckId()
        val sortFieldIndex = exactInt(ProviderColumn.MODEL_SORT_FIELD_INDEX)
        val type = exactInt(ProviderColumn.MODEL_TYPE)
        val latexPost = nullableText(ProviderColumn.MODEL_LATEX_POST)
        val latexPre = nullableText(ProviderColumn.MODEL_LATEX_PRE)
        val validated =
            try {
                ProviderSnapshotValidation.validateModelBase(
                    id = id,
                    name = name,
                    type = type,
                    rawFieldNames = rawFieldNames,
                    cardCount = cardCount,
                    sortFieldIndex = sortFieldIndex,
                    effectiveDefaultDeckId = effectiveDefaultDeckId,
                    css = css,
                    latexPre = latexPre,
                    latexPost = latexPost,
                )
            } catch (_: InvalidTargetSnapshotException) {
                throw targetInvalid("The selected Anki note type is not supported")
            }
        return ModelRow(
            id = id,
            name = name,
            fieldNames = validated.fieldNames,
            cardCount = cardCount,
            css = css,
            effectiveDefaultDeckId = effectiveDefaultDeckId,
            sortFieldIndex = sortFieldIndex,
            type = type,
            latexPost = latexPost,
            latexPre = latexPre,
            providerTextUtf8Bytes = validated.providerTextUtf8Bytes,
        )
    }

    private fun ProviderCursor.readTemplate(): TemplateSnapshot =
        TemplateSnapshot(
            modelId = positiveLong(ProviderColumn.TEMPLATE_MODEL_ID),
            ordinal = exactInt(ProviderColumn.TEMPLATE_ORDINAL),
            name = text(ProviderColumn.TEMPLATE_NAME),
            questionFormat = text(ProviderColumn.TEMPLATE_QUESTION_FORMAT),
            answerFormat = text(ProviderColumn.TEMPLATE_ANSWER_FORMAT),
            browserQuestionFormat = nullableText(ProviderColumn.TEMPLATE_BROWSER_QUESTION_FORMAT),
            browserAnswerFormat = nullableText(ProviderColumn.TEMPLATE_BROWSER_ANSWER_FORMAT),
        )

    private fun ProviderCursor.readDeck(): DeckSnapshot {
        val dynamic = exactInt(ProviderColumn.DECK_DYNAMIC)
        if (dynamic !in 0..1) throw queryFailed()
        return DeckSnapshot(
            id = positiveLong(ProviderColumn.DECK_ID),
            name = text(ProviderColumn.DECK_NAME),
            dynamic = dynamic == 1,
        )
    }

    private companion object {
        const val MAX_PROVIDER_LIST_ROWS = 100_000
        val MODEL_LIST_QUERY =
            ProviderQuery(
                endpoint = ProviderEndpoint.MODELS,
                projection = ProviderQueryShapes.MODEL_PROJECTION,
            )
        val DECK_LIST_QUERY =
            ProviderQuery(
                endpoint = ProviderEndpoint.DECKS,
                projection = ProviderQueryShapes.DECK_PROJECTION,
            )
    }
}

internal class NoteSnapshotReader(private val provider: CheckedProvider) {
    fun readById(
        noteId: Long,
        cancellation: AnkiCancellation,
    ): NoteSnapshot {
        if (noteId <= 0L) throw queryFailed()
        val query =
            ProviderQuery(
                endpoint = ProviderEndpoint.NOTE_BY_ID,
                endpointId = noteId,
                projection = ProviderQueryShapes.NOTE_SNAPSHOT_PROJECTION,
            )
        return provider.queryRequired(query, cancellation).use { cursor ->
            requireProjection(cursor, query)
            ensureActive(cancellation)
            if (!cursor.moveToNext()) throw queryFailed()
            ensureActive(cancellation)
            val snapshot =
                NoteSnapshot(
                    id = cursor.positiveLong(ProviderColumn.NOTE_ID),
                    modelId = cursor.positiveLong(ProviderColumn.NOTE_MODEL_ID),
                    joinedFields = cursor.text(ProviderColumn.NOTE_FIELDS),
                    providerTagsWire = cursor.text(ProviderColumn.NOTE_TAGS),
                )
            validateNoteSnapshot(snapshot, noteId)
            ensureActive(cancellation)
            if (cursor.moveToNext()) throw queryFailed()
            snapshot
        }
    }

    private fun validateNoteSnapshot(
        snapshot: NoteSnapshot,
        expectedId: Long,
    ) {
        if (snapshot.id != expectedId) throw queryFailed()
        val fieldBytes = strictProviderBytes(snapshot.joinedFields)
        if (
            fieldBytes > AnkiLimitsV1.CreateNotes.NOTE_CONTENT_MAX_UTF8_BYTES ||
                snapshot.joinedFields.count { it == FIELD_SEPARATOR } >=
                AnkiLimitsV1.CreateNotes.MAX_FIELD_COUNT_PER_NOTE
        ) {
            throw queryFailed()
        }
        val tagBytes = strictProviderBytes(snapshot.providerTagsWire)
        if (
            tagBytes > AnkiLimitsV1.CreateNotes.TAGS_PER_NOTE_MAX_UTF8_BYTES ||
                FIELD_SEPARATOR in snapshot.providerTagsWire
        ) {
            throw queryFailed()
        }
    }

    private fun strictProviderBytes(value: String): Int =
        try {
            com.ankiminer.android.anki.protocol.AnkiValidators
                .strictStats(value, "provider note snapshot")
                .utf8Bytes
        } catch (_: com.ankiminer.android.anki.protocol.AnkiProtocolException) {
            throw queryFailed()
        }

    private companion object {
        const val FIELD_SEPARATOR = '\u001f'
    }
}

internal class GlobalCardReader(private val provider: CheckedProvider) {
    fun readById(
        cardId: Long,
        cancellation: AnkiCancellation,
    ): CardIdentity {
        if (cardId <= 0L) throw queryFailed()
        val query =
            ProviderQuery(
                endpoint = ProviderEndpoint.CARD_BY_ID,
                endpointId = cardId,
                projection = ProviderQueryShapes.CARD_IDENTITY_PROJECTION,
            )
        return provider.queryRequired(query, cancellation).use { cursor ->
            requireProjection(cursor, query)
            ensureActive(cancellation)
            if (!cursor.moveToNext()) throw queryFailed()
            ensureActive(cancellation)
            val result =
                CardIdentity(
                    id = cursor.positiveLong(ProviderColumn.CARD_ID),
                    noteId = cursor.positiveLong(ProviderColumn.CARD_NOTE_ID),
                    ordinal = cursor.exactInt(ProviderColumn.CARD_ORDINAL),
                    deckId = cursor.positiveLong(ProviderColumn.CARD_DECK_ID),
                )
            if (
                result.id != cardId ||
                    result.ordinal !in 0 until AnkiLimitsV1.CreateNotes.MAX_CARD_COUNT_PER_NOTE
            ) {
                throw queryFailed()
            }
            ensureActive(cancellation)
            if (cursor.moveToNext()) throw queryFailed()
            result
        }
    }

    fun readForNote(
        noteId: Long,
        templateCount: Int,
        cancellation: AnkiCancellation,
    ): List<CardIdentity> {
        if (noteId <= 0L || templateCount !in 1..AnkiLimitsV1.CreateNotes.MAX_CARD_COUNT_PER_NOTE) {
            throw queryFailed()
        }
        val query =
            ProviderQuery(
                endpoint = ProviderEndpoint.CARDS_FOR_NOTE,
                endpointId = noteId,
                projection = ProviderQueryShapes.CARD_IDENTITY_PROJECTION,
            )
        val cards = ArrayList<CardIdentity>()
        val ids = HashSet<Long>()
        val ordinals = HashSet<Int>()
        provider.queryRequired(query, cancellation).use { cursor ->
            requireProjection(cursor, query)
            while (cursor.moveToNext()) {
                ensureActive(cancellation)
                val card =
                    CardIdentity(
                        id = cursor.positiveLong(ProviderColumn.CARD_ID),
                        noteId = cursor.positiveLong(ProviderColumn.CARD_NOTE_ID),
                        ordinal = cursor.exactInt(ProviderColumn.CARD_ORDINAL),
                        deckId = cursor.positiveLong(ProviderColumn.CARD_DECK_ID),
                    )
                if (
                    card.noteId != noteId ||
                        card.ordinal !in 0 until templateCount ||
                        !ids.add(card.id) ||
                        !ordinals.add(card.ordinal)
                ) {
                    throw queryFailed()
                }
                cards += card
                if (cards.size > templateCount) throw queryFailed()
            }
        }
        if (cards.isEmpty()) throw queryFailed()
        return cards.sortedWith(compareBy(CardIdentity::ordinal, CardIdentity::id))
    }

}

internal class CheckedProvider(private val gateway: AnkiProviderGateway) {
    fun preflightMutation(cancellation: AnkiCancellation) {
        ensureActive(cancellation)
        requireAvailableAccess()
        ensureActive(cancellation)
    }

    fun queryRequired(
        query: ProviderQuery,
        cancellation: AnkiCancellation,
    ): ProviderCursor = queryOptional(query, cancellation) ?: throw queryFailed()

    fun queryOptional(
        query: ProviderQuery,
        cancellation: AnkiCancellation,
    ): ProviderCursor? {
        preflightMutation(cancellation)
        val cursor =
            try {
                gateway.query(query, cancellation)
            } catch (error: ProviderGatewayException) {
                throw error.toReadFailure()
            }
        return cursor?.let(::CheckedProviderCursor)
    }

    private fun requireAvailableAccess() {
        when (val status = gateway.accessStatus()) {
            is ProviderAccessStatus.Available -> Unit
            ProviderAccessStatus.Absent ->
                throw AnkiReadFailure(
                    AnkiErrorCode.PROVIDER_UNAVAILABLE,
                    retryable = true,
                    stableMessage = "AnkiDroid is not available",
                )
            ProviderAccessStatus.ApiDisabled ->
                throw AnkiReadFailure(
                    AnkiErrorCode.API_DISABLED,
                    retryable = false,
                    stableMessage = "The AnkiDroid API is disabled",
                )
            is ProviderAccessStatus.Incompatible ->
                throw AnkiReadFailure(
                    AnkiErrorCode.API_DISABLED,
                    retryable = false,
                    stableMessage = "The installed AnkiDroid API is incompatible",
                    providerErrorReason = NoteTypeProviderErrorReason.API_INCOMPATIBLE,
                )
            ProviderAccessStatus.PermissionRequired ->
                throw AnkiReadFailure(
                    AnkiErrorCode.PERMISSION_REQUIRED,
                    retryable = false,
                    stableMessage = "AnkiDroid permission is required",
                )
        }
    }

    private class CheckedProviderCursor(
        private val delegate: ProviderCursor,
    ) : ProviderCursor {
        override val projection: List<ProviderColumn>
            get() = delegate.projection

        override fun moveToNext(): Boolean =
            try {
                delegate.moveToNext()
            } catch (error: ProviderGatewayException) {
                throw error.toReadFailure()
            }

        override fun cell(column: ProviderColumn): ProviderCell =
            try {
                delegate.cell(column)
            } catch (error: ProviderGatewayException) {
                throw error.toReadFailure()
            }

        override fun close() {
            try {
                delegate.close()
            } catch (error: ProviderGatewayException) {
                throw error.toReadFailure()
            }
        }
    }
}

/**
 * The one construction site that has a real platform throwable to hand on: everywhere else the app
 * itself decided the read had failed, so there is nothing underneath to keep.
 */
private fun ProviderGatewayException.toReadFailure(): AnkiReadFailure =
    when (kind) {
        ProviderFailureKind.API_DISABLED ->
            AnkiReadFailure(
                AnkiErrorCode.API_DISABLED,
                retryable = false,
                stableMessage = "The AnkiDroid API became disabled or incompatible",
                providerErrorReason = NoteTypeProviderErrorReason.API_DISABLED_OR_INCOMPATIBLE,
                cause = this,
            )
        ProviderFailureKind.PERMISSION_REQUIRED ->
            AnkiReadFailure(
                AnkiErrorCode.PERMISSION_REQUIRED,
                retryable = false,
                stableMessage = "AnkiDroid permission is required",
                cause = this,
            )
        ProviderFailureKind.PROVIDER_UNAVAILABLE ->
            AnkiReadFailure(
                AnkiErrorCode.PROVIDER_UNAVAILABLE,
                retryable = true,
                stableMessage = "AnkiDroid became unavailable",
                providerErrorReason = NoteTypeProviderErrorReason.PROVIDER_BECAME_UNAVAILABLE,
                cause = this,
            )
        ProviderFailureKind.QUERY_FAILED -> queryFailed(cause = this)
        ProviderFailureKind.MUTATION_FAILED ->
            AnkiReadFailure(
                AnkiErrorCode.WRITE_FAILED,
                retryable = false,
                stableMessage = "The AnkiDroid write failed",
                cause = this,
            )
        ProviderFailureKind.TIMEOUT ->
            AnkiReadFailure(
                AnkiErrorCode.TIMEOUT,
                retryable = true,
                stableMessage = "The AnkiDroid read timed out",
                cause = this,
            )
        ProviderFailureKind.CANCELLED -> cancelled(cause = this)
    }

internal fun minimalExistingDeckScopes(
    requested: List<String>,
    existing: Set<String>,
): List<String> =
    requested
        .asSequence()
        .filter(existing::contains)
        .sortedWith(compareBy<String>({ it.count { character -> character == ':' } }, { it }))
        .fold(ArrayList()) { result, deck ->
            if (result.none { ancestor -> deck.startsWith("$ancestor::") }) result += deck
            result
        }

private fun requireProjection(
    cursor: ProviderCursor,
    query: ProviderQuery,
) {
    if (cursor.projection != query.projection) throw queryFailed()
}

private fun ProviderCursor.text(column: ProviderColumn): String =
    when (val value = cell(column)) {
        is ProviderCell.Text -> value.value
        ProviderCell.Null, is ProviderCell.Integer -> throw queryFailed()
    }

private fun ProviderCursor.nullableText(column: ProviderColumn): String? =
    when (val value = cell(column)) {
        is ProviderCell.Text -> value.value
        ProviderCell.Null -> null
        is ProviderCell.Integer -> throw queryFailed()
    }

private fun ProviderCursor.positiveLong(column: ProviderColumn): Long =
    when (val value = cell(column)) {
        is ProviderCell.Integer -> value.value.takeIf { it > 0L } ?: throw queryFailed()
        ProviderCell.Null, is ProviderCell.Text -> throw queryFailed()
    }

/** AnkiDroid v2.24 exposes an unset model deck as null, which means the built-in deck ID 1. */
private fun ProviderCursor.effectiveDefaultDeckId(): Long =
    when (val value = cell(ProviderColumn.MODEL_DEFAULT_DECK_ID)) {
        ProviderCell.Null -> 1L
        is ProviderCell.Integer -> value.value.takeIf { it > 0L } ?: throw queryFailed()
        is ProviderCell.Text -> throw queryFailed()
    }

private fun ProviderCursor.nonNegativeLong(column: ProviderColumn): Long =
    when (val value = cell(column)) {
        is ProviderCell.Integer -> value.value.takeIf { it >= 0L } ?: throw queryFailed()
        ProviderCell.Null, is ProviderCell.Text -> throw queryFailed()
    }

private fun ProviderCursor.exactInt(column: ProviderColumn): Int =
    when (val value = cell(column)) {
        is ProviderCell.Integer ->
            value.value.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()
                ?: throw queryFailed()
        ProviderCell.Null, is ProviderCell.Text -> throw queryFailed()
    }

private fun ensureActive(cancellation: AnkiCancellation) {
    if (cancellation.isCancelled()) throw cancelled()
}

private fun validateProviderFirstField(value: String): Int =
    try {
        ProviderSnapshotValidation.validateFirstField(value)
    } catch (_: InvalidProviderValueException) {
        throw queryFailed()
    }

private fun preflightCanonicalResponse(
    request: ScanFirstFieldsRequest,
    response: com.ankiminer.android.anki.protocol.AnkiResponse,
) {
    try {
        AnkiJsonCodec.encodeResponse(response, request)
    } catch (_: RuntimeException) {
        throw queryFailed("The Anki read result exceeds the v1 response contract")
    }
}

private fun RuntimeException.afterCapabilityConsumption(): RuntimeException =
    if (this is AnkiReadFailure && retryable) {
        AnkiReadFailure(code, retryable = false, stableMessage)
    } else {
        this
    }

private fun checkedAdd(
    left: Int,
    right: Int,
): Int =
    try {
        Math.addExact(left, right)
    } catch (_: ArithmeticException) {
        throw queryFailed()
    }

private fun checkedMultiply(
    left: Int,
    right: Int,
): Int =
    try {
        Math.multiplyExact(left, right)
    } catch (_: ArithmeticException) {
        throw queryFailed()
    }

private fun cancelled(cause: Throwable? = null) =
    AnkiReadFailure(
        AnkiErrorCode.CANCELLED,
        retryable = false,
        stableMessage = "The Anki operation was cancelled",
        cause = cause,
    )

private fun queryFailed(
    message: String = "AnkiDroid returned an invalid or failed query",
    cause: Throwable? = null,
) = AnkiReadFailure(
    AnkiErrorCode.QUERY_FAILED,
    retryable = true,
    stableMessage = message,
    cause = cause,
)

private fun knownVocabularyLimitExceeded(scope: String) =
    AnkiReadFailure(
        AnkiErrorCode.UNSUPPORTED_OPERATION,
        retryable = false,
        stableMessage =
            "Known-word filtering supports at most " +
                "${AnkiLimitsV1.ScanFirstFields.KNOWN_TOTAL_SCANNED_NOTE_MAX_COUNT} notes in $scope",
    )

/**
 * The deck tree walked to collect those notes has its own budget, in card rows rather than notes.
 *
 * `deck:"Name"` is a deck-TREE selection over the CARDS endpoint, so the walk crosses one row per
 * card in the whole tree while only exact-deck rows can contribute a note. Rows therefore have no
 * fixed ratio to the result and cannot share its ceiling: spending the note budget on them costs a
 * deck of 60k notes at two cards each its scan. This bound exists only to stop an unbounded walk.
 */
private fun knownVocabularyDeckTreeTooLarge() =
    AnkiReadFailure(
        AnkiErrorCode.UNSUPPORTED_OPERATION,
        retryable = false,
        stableMessage =
            "Known-word filtering scans at most " +
                "${AnkiLimitsV1.ScanFirstFields.KNOWN_TOTAL_SCANNED_CARD_ROW_MAX_COUNT} cards " +
                "in the selected Anki deck and its subdecks",
    )

private fun targetInvalid(message: String) =
    AnkiReadFailure(
        AnkiErrorCode.TARGET_INVALID,
        retryable = false,
        stableMessage = message,
    )

private fun invalidRequest(message: String) =
    AnkiReadFailure(
        AnkiErrorCode.INVALID_REQUEST,
        retryable = false,
        stableMessage = message,
    )
