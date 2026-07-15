package com.ankiminer.android.anki.journal

import com.ankiminer.android.anki.protocol.CollectionCreateDuplicateScope
import com.ankiminer.android.anki.protocol.CreateDuplicateCandidate
import com.ankiminer.android.anki.protocol.CreateNote
import com.ankiminer.android.anki.protocol.CreateNotesRequest
import com.ankiminer.android.anki.protocol.MediaAsset
import com.ankiminer.android.anki.protocol.MediaKind as ProtocolMediaKind
import com.ankiminer.android.anki.protocol.MediaPurpose as ProtocolMediaPurpose
import com.ankiminer.android.anki.protocol.StoreMediaRequest
import com.ankiminer.android.anki.protocol.VerifyTargetRequest

internal const val TEST_DIGEST = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
internal const val TEST_RUN_ID = "run_00000000000000000000000000000000"
internal const val TEST_REQUEST_ID = "anki_11111111111111111111111111111111"
internal const val TEST_BASELINE_TOKEN = "baseline_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

internal fun protocolAssetId(index: Int): String = "asset_${index.toString(16).padStart(32, '0')}"

internal fun protocolNoteId(index: Int): String = "note_${index.toString(16).padStart(32, '0')}"

internal fun testParent(
    id: Long = 1,
    runId: String = "run-$id",
    requestId: String = "request-$id",
    operation: ParentOperation = ParentOperation.STORE_MEDIA,
    state: ParentState = ParentState.RUNNING,
): ParentRecord =
    ParentRecord(
        id = id,
        key = ParentKey(runId, requestId),
        operation = operation,
        digestVersion = 1,
        requestSha256 = TEST_DIGEST,
        state = state,
        activeRequestIndex = null,
        activeNoteId = null,
        routingPhase = null,
        hasTargetExpectation = false,
        createdAtMs = id,
        updatedAtMs = id,
    )

internal fun testCommand(operation: ChildOperation): MutationCommand =
    when (operation) {
        ChildOperation.DECK_CREATE -> MutationCommand.CreateDeck("Mining")
        ChildOperation.MEDIA_INSERT ->
            MutationCommand.StoreMedia(
                requestIndexValue = 0,
                assetId = "asset-0",
                fileUri = "content://com.ankiminer.test/staging/asset-0",
                preferredName = "asset-0.ogg",
            )
        ChildOperation.NOTE_INSERT ->
            MutationCommand.InsertNote(
                requestIndexValue = 0,
                clientNoteId = "note-0",
                modelId = 11,
                joinedFields = "expression\u001freading",
                providerTagsWire = " mined ",
            )
        ChildOperation.CARD_DECK_UPDATE ->
            MutationCommand.RouteCard(
                intentId = 90,
                requestIndexValue = 0,
                cardId = 70,
                noteId = 60,
                ordinal = 0,
                targetDeckId = 20,
                preUpdateDeckId = 10,
            )
    }

internal fun testReceipt(operation: ChildOperation): ProviderReceipt =
    when (operation) {
        ChildOperation.DECK_CREATE -> ProviderReceipt.Deck(20, "content://com.ichi2.anki.flashcards/decks/20")
        ChildOperation.MEDIA_INSERT -> ProviderReceipt.Media("asset-0.ogg", "file:///asset-0.ogg")
        ChildOperation.NOTE_INSERT -> ProviderReceipt.Note(60, "content://com.ichi2.anki.flashcards/notes/60")
        ChildOperation.CARD_DECK_UPDATE -> ProviderReceipt.CardAffectedOne
    }

internal fun testChild(
    parentId: Long = 1,
    operation: ChildOperation,
    attemptCount: Int = 0,
    receipt: ProviderReceipt? = null,
    state: ChildState = ChildState.PREPARED,
    id: Long = 10,
    mediaClaimId: Long? = if (operation == ChildOperation.MEDIA_INSERT) 44 else null,
): ChildRecord =
    ChildRecord(
        id = id,
        parentId = parentId,
        sequence = 0,
        digestVersion = 1,
        requestSha256 = TEST_DIGEST,
        itemSha256 = if (operation == ChildOperation.NOTE_INSERT) TEST_DIGEST else null,
        command = testCommand(operation),
        mediaClaimId = mediaClaimId,
        state = state,
        attempts =
            List(attemptCount) { index ->
                ProviderAttempt(
                    childId = id,
                    attemptNumber = index + 1,
                    recoveryReissue = index == 1,
                    enteredAtMs = 100L + index,
                )
            },
        receipt = receipt,
        terminalEvidence = null,
        createdAtMs = 10,
        updatedAtMs = 10,
    )

internal fun testRoutingIntent(
    parentId: Long = 1,
    childId: Long? = 10,
    state: RoutingIntentState = RoutingIntentState.UPDATE_PREPARED,
): RoutingIntentRecord =
    RoutingIntentRecord(
        id = 90,
        parentId = parentId,
        requestIndex = 0,
        cardId = 70,
        noteId = 60,
        ordinal = 0,
        targetDeckId = 20,
        preUpdateDeckId = 10,
        childId = childId,
        state = state,
        terminalEvidence = null,
        createdAtMs = 1,
        updatedAtMs = 1,
    )

internal fun testError(
    code: JournalErrorCode = JournalErrorCode.WRITE_FAILED,
    retryable: Boolean = false,
    message: String = code.name.lowercase(),
): JournalError = JournalError(code, message, retryable)

internal fun storeRequest(
    assetIds: List<String> = List(3, ::protocolAssetId),
    runId: String = TEST_RUN_ID,
    requestId: String = TEST_REQUEST_ID,
): StoreMediaRequest =
    StoreMediaRequest(
        runId = runId,
        requestId = requestId,
        assets = assetIds.mapIndexed { index, assetId -> testMediaAsset(index, assetId) },
    )

internal fun testMediaAsset(index: Int, assetId: String = "asset-$index"): MediaAsset =
    MediaAsset(
        assetId = assetId,
        sourcePath = "/tmp/$assetId.ogg",
        preferredName = assetId,
        requestedFilename = "$assetId.ogg",
        purpose = ProtocolMediaPurpose.CARD,
        mediaKind = ProtocolMediaKind.AUDIO,
        expectedSizeBytes = index.toLong(),
        expectedSha256 = index.toString(16).padStart(64, '0'),
    )

internal fun createRequest(
    noteIds: List<String> = List(3, ::protocolNoteId),
    runId: String = TEST_RUN_ID,
    requestId: String = TEST_REQUEST_ID,
): CreateNotesRequest =
    CreateNotesRequest(
        runId = runId,
        requestId = requestId,
        deckName = "Mining",
        modelName = "Mining",
        firstFieldName = "Expression",
        baselineToken = TEST_BASELINE_TOKEN,
        duplicateScope = CollectionCreateDuplicateScope,
        notes =
            noteIds.mapIndexed { index, noteId ->
                CreateNote(
                    clientNoteId = noteId,
                    fields = mapOf("Expression" to "word-$index"),
                    tags = listOf("mined"),
                    duplicateCandidate = CreateDuplicateCandidate("key-$index", "word-$index", index),
                    mediaBindings = emptyList(),
                )
            },
    )

internal fun verifyRequest(
    runId: String = TEST_RUN_ID,
    requestId: String = TEST_REQUEST_ID,
): VerifyTargetRequest =
    VerifyTargetRequest(
        runId = runId,
        requestId = requestId,
        deckName = "Mining",
        modelName = "Mining",
        requiredFields = listOf("Expression"),
    )

internal fun testTargetSnapshot(): DurableTargetSnapshot {
    val template =
        DurableTemplateSnapshot(
            modelId = 11,
            ordinal = 0,
            name = "Card 1",
            questionFormat = "{{Expression}}",
            answerFormat = "{{FrontSide}}",
            browserQuestionFormat = null,
            browserAnswerFormat = null,
        )
    return DurableTargetSnapshot(
        deck = DurableDeckSnapshot(id = 20, name = "Mining", dynamic = false),
        model =
            DurableModelSnapshot(
                id = 11,
                name = "Mining",
                type = 0,
                fieldNames = listOf("Expression"),
                cardCount = 1,
                sortFieldIndex = 0,
                effectiveDefaultDeckId = 20,
                css = "",
                latexPre = null,
                latexPost = null,
                templates = listOf(template),
            ),
    )
}
