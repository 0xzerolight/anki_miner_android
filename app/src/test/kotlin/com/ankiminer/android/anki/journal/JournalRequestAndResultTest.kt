package com.ankiminer.android.anki.journal

import com.ankiminer.android.anki.protocol.KnownVocabularyScope
import com.ankiminer.android.anki.protocol.ScanFirstFieldsRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalRequestAndResultTest {
    @Test
    fun journalRequestDerivesVersionedIdentityAndNormalizedItemOrder() {
        val verify = JournalRequest.from(verifyRequest())
        assertEquals(ParentOperation.VERIFY_TARGET, verify.operation)
        assertEquals(ParentKey(TEST_RUN_ID, TEST_REQUEST_ID), verify.key)
        assertEquals(listOf(JournalRequest.TARGET_ITEM_ID), verify.itemIds)
        assertEquals(1, verify.digest.digestVersion)
        assertTrue(verify.digest.sha256.matches(Regex("[0-9a-f]{64}")))

        val storeIds = listOf(protocolAssetId(3), protocolAssetId(1), protocolAssetId(2))
        val store = JournalRequest.from(storeRequest(storeIds))
        assertEquals(ParentOperation.STORE_MEDIA, store.operation)
        assertEquals(storeIds, store.itemIds)

        val noteIds = listOf(protocolNoteId(2), protocolNoteId(0), protocolNoteId(1))
        val create = JournalRequest.from(createRequest(noteIds))
        assertEquals(ParentOperation.CREATE_NOTES, create.operation)
        assertEquals(noteIds, create.itemIds)
        assertNotEquals(store.digest.sha256, create.digest.sha256)
    }

    @Test
    fun journalRequestRejectsUnsupportedEmptyDuplicateAndMalformedIdentity() {
        assertThrows(IllegalArgumentException::class.java) {
            JournalRequest.from(
                ScanFirstFieldsRequest(
                    runId = TEST_RUN_ID,
                    requestId = TEST_REQUEST_ID,
                    scope = KnownVocabularyScope(emptyList(), null),
                ),
            )
        }
        assertThrows(RuntimeException::class.java) {
            JournalRequest.from(storeRequest(listOf(protocolAssetId(0), protocolAssetId(0))))
        }
        assertThrows(RuntimeException::class.java) {
            JournalRequest.from(storeRequest(emptyList()))
        }
        assertThrows(RuntimeException::class.java) {
            JournalRequest.from(createRequest(listOf(protocolNoteId(0), protocolNoteId(0))))
        }
        assertThrows(RuntimeException::class.java) {
            JournalRequest.from(createRequest(emptyList()))
        }
        assertThrows(RuntimeException::class.java) {
            JournalRequest.from(verifyRequest(runId = "bad\ud800"))
        }
        assertThrows(IllegalArgumentException::class.java) { ParentKey(" ", "request") }
        assertThrows(IllegalArgumentException::class.java) { ParentKey("run", "\n\t") }
    }

    @Test
    fun strictUtf8DigestAndEvidenceHelpersRejectAmbiguousData() {
        requireStrictUtf8Bound("😀", 4, "value")
        assertThrows(IllegalArgumentException::class.java) {
            requireStrictUtf8Bound("😀", 3, "value")
        }
        assertThrows(IllegalArgumentException::class.java) {
            requireStrictUtf8Bound("\ud800", 4, "value")
        }
        requireSha256("0".repeat(64), "digest")
        listOf("0".repeat(63), "0".repeat(65), "A".repeat(64), "g".repeat(64)).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { requireSha256(value, "digest") }
        }
        requireCompactEvidence(null)
        requireCompactEvidence("😀".repeat(MAX_COMPACT_EVIDENCE_UTF8_BYTES / 4))
        assertThrows(IllegalArgumentException::class.java) { requireCompactEvidence("") }
        assertThrows(IllegalArgumentException::class.java) {
            requireCompactEvidence("😀".repeat(MAX_COMPACT_EVIDENCE_UTF8_BYTES / 4 + 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            JournalError(JournalErrorCode.INTERNAL_ERROR, "\ud800", retryable = false)
        }
    }

    @Test
    fun noteMaterializationDigestChangesForEveryProviderBoundLeaf() {
        val baseline = testMaterialization()
        val changed =
            listOf(
                baseline.copy(requestIndex = 1),
                baseline.copy(clientNoteId = "note-1"),
                baseline.copy(orderedFields = listOf(OrderedNoteField("Expression", "other"))),
                baseline.copy(joinedFields = "other"),
                baseline.copy(normalizedTags = listOf("other")),
                baseline.copy(providerTagsWire = " other "),
                baseline.copy(duplicateDecision = baseline.duplicateDecision.copy(key = "other")),
                baseline.copy(duplicateDecision = baseline.duplicateDecision.copy(firstField = "other")),
                baseline.copy(duplicateDecision = baseline.duplicateDecision.copy(occurrence = 1)),
                baseline.copy(duplicateDecision = baseline.duplicateDecision.copy(duplicate = true)),
                baseline.copy(mediaBindings = listOf(DurableMediaBinding("asset-1", "clip.ogg", 1))),
                baseline.copy(mediaBindings = listOf(DurableMediaBinding("asset-0", "other.ogg", 1))),
                baseline.copy(mediaBindings = listOf(DurableMediaBinding("asset-0", "clip.ogg", 2))),
            )
        assertEquals(changed.size, changed.map { it.itemSha256 }.toSet().size)
        changed.forEach { assertNotEquals(baseline.itemSha256, it.itemSha256) }
        assertTrue(baseline.itemSha256.matches(Regex("[0-9a-f]{64}")))

        ActiveNoteRecord(1, baseline, baseline.itemSha256, 1, 1)
        assertThrows(IllegalArgumentException::class.java) {
            ActiveNoteRecord(1, baseline, TEST_DIGEST, 1, 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            testChild(operation = ChildOperation.MEDIA_INSERT).copy(itemSha256 = TEST_DIGEST)
        }
        assertThrows(IllegalArgumentException::class.java) {
            testChild(operation = ChildOperation.NOTE_INSERT).copy(itemSha256 = null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            testMaterialization().copy(joinedFields = "bad\ud800").itemSha256
        }
    }

    @Test
    fun verifyTerminalizationUsesTypedSnapshotAndBackendConstantDeckCreatedFalse() {
        val request = JournalRequest.from(verifyRequest())
        val success = JournalResponse.VerifySuccess(request.key, testTargetSnapshot())
        JournalStateMachine.validateTerminalResponse(request, success, emptyList())
        assertFalse(success.deckCreated)
        JournalStateMachine.validateTerminalResponse(
            request,
            JournalResponse.VerifyError(request.key, testError(JournalErrorCode.TARGET_INVALID)),
            emptyList(),
        )

        assertThrows(JournalInvariantViolation::class.java) {
            JournalStateMachine.validateTerminalResponse(
                request,
                JournalResponse.VerifySuccess(ParentKey("other", "request"), testTargetSnapshot()),
                emptyList(),
            )
        }
        assertThrows(JournalInvariantViolation::class.java) {
            JournalStateMachine.validateTerminalResponse(
                request,
                success,
                listOf(AlignedResult.TargetVerified()),
            )
        }
        val target = testTargetSnapshot()
        listOf(
            target.copy(deck = target.deck.copy(name = "Other")),
            target.copy(model = target.model.copy(name = "Other")),
            target.copy(model = target.model.copy(fieldNames = listOf("Other"))),
        ).forEach { mismatched ->
            assertThrows(JournalInvariantViolation::class.java) {
                JournalStateMachine.validateTerminalResponse(
                    request,
                    JournalResponse.VerifySuccess(request.key, mismatched),
                    emptyList(),
                )
            }
        }
    }

    @Test
    fun mediaTerminalizationRequiresExactAlignmentStrictSuffixAndMatchingErrors() {
        val request = JournalRequest.from(storeRequest())
        val (asset0, asset1, asset2) = request.itemIds
        val stored0 = AlignedResult.MediaStored(0, asset0, "actual-0.ogg", "receipt-0")
        val stored1 = AlignedResult.MediaStored(1, asset1, "actual-1.ogg", "receipt-1")
        val stored2 = AlignedResult.MediaStored(2, asset2, "actual-2.ogg", "receipt-2")
        JournalStateMachine.validateTerminalResponse(
            request,
            JournalResponse.StoreMedia(request.key, listOf(stored0, stored1, stored2), null),
            listOf(stored0, stored1),
        )

        val failure = testError(JournalErrorCode.MEDIA_STORE_FAILED, retryable = false)
        val failedRows =
            listOf(
                stored0,
                AlignedResult.MediaFailed(1, asset1, failure, "before-entry"),
                AlignedResult.MediaNotAttempted(2, asset2),
            )
        JournalStateMachine.validateTerminalResponse(
            request,
            JournalResponse.StoreMedia(request.key, failedRows, failure),
            listOf(stored0),
        )

        val uncertain = postCommitError()
        JournalStateMachine.validateTerminalResponse(
            request,
            JournalResponse.StoreMedia(
                request.key,
                listOf(
                    stored0,
                    AlignedResult.MediaUncertain(1, asset1, "provider-entered"),
                    AlignedResult.MediaNotAttempted(2, asset2),
                ),
                uncertain,
            ),
            listOf(stored0),
        )

        assertInvalidMedia(request, listOf(stored0, stored1), null)
        assertInvalidMedia(request, listOf(stored0, stored2, stored1), null)
        JournalStateMachine.validateTerminalResponse(
            request,
            JournalResponse.StoreMedia(
                request.key,
                listOf(stored0, AlignedResult.MediaNotAttempted(1, asset1), AlignedResult.MediaNotAttempted(2, asset2)),
                failure,
            ),
            listOf(stored0),
        )
        JournalStateMachine.validateTerminalResponse(
            request,
            JournalResponse.StoreMedia(
                request.key,
                listOf(
                    AlignedResult.MediaFailed(0, asset0, failure),
                    stored1,
                    AlignedResult.MediaNotAttempted(2, asset2),
                ),
                failure,
            ),
            emptyList(),
        )
        JournalStateMachine.validateTerminalResponse(
            request,
            JournalResponse.StoreMedia(
                request.key,
                listOf(
                    AlignedResult.MediaFailed(0, asset0, failure),
                    stored1,
                    AlignedResult.MediaFailed(2, asset2, failure),
                ),
                null,
            ),
            emptyList(),
        )
        assertInvalidMedia(request, listOf(stored0, stored1, stored2), failure)
        val wrongMediaCode = testError(JournalErrorCode.TIMEOUT, retryable = false)
        assertInvalidMedia(
            request,
            listOf(
                stored0,
                AlignedResult.MediaFailed(1, asset1, wrongMediaCode),
                AlignedResult.MediaNotAttempted(2, asset2),
            ),
            wrongMediaCode,
        )
        assertInvalidMedia(request, failedRows, testError(JournalErrorCode.TIMEOUT, retryable = true))
        assertInvalidMedia(
            request,
            listOf(
                stored0,
                AlignedResult.MediaUncertain(1, asset1),
                AlignedResult.MediaNotAttempted(2, asset2),
            ),
            testError(JournalErrorCode.TIMEOUT, retryable = false),
        )
        assertInvalidMedia(
            request,
            listOf(
                stored0,
                AlignedResult.MediaFailed(1, asset1, uncertain),
                AlignedResult.MediaNotAttempted(2, asset2),
            ),
            uncertain,
        )
        val retryableMediaFailure = testError(JournalErrorCode.MEDIA_STORE_FAILED, retryable = true)
        assertInvalidMedia(
            request,
            listOf(
                stored0,
                AlignedResult.MediaFailed(1, asset1, retryableMediaFailure),
                AlignedResult.MediaNotAttempted(2, asset2),
            ),
            retryableMediaFailure,
        )
        assertInvalidMedia(
            request,
            listOf(
                stored0,
                AlignedResult.MediaStored(1, asset1, "actual-0.ogg"),
                stored2,
            ),
            null,
        )
        assertThrows(JournalInvariantViolation::class.java) {
            JournalStateMachine.validateTerminalResponse(
                request,
                JournalResponse.StoreMedia(request.key, listOf(stored0, stored1, stored2), null),
                listOf(stored0.copy(actualFilename = "changed.ogg")),
            )
        }
    }

    @Test
    fun createTerminalizationPreservesRowLocalOutcomeBoundaries() {
        val request = JournalRequest.from(createRequest())
        val (note0, note1, note2) = request.itemIds
        val created0 = AlignedResult.NoteCreated(0, note0, 100, "note-receipt")
        val duplicate1 = AlignedResult.NoteDuplicate(1, note1)
        val created2 = AlignedResult.NoteCreated(2, note2, 102)
        JournalStateMachine.validateTerminalResponse(
            request,
            JournalResponse.CreateNotes(request.key, listOf(created0, duplicate1, created2), null),
            listOf(created0),
        )

        val cancelled = testError(JournalErrorCode.CANCELLED, retryable = false)
        JournalStateMachine.validateTerminalResponse(
            request,
            JournalResponse.CreateNotes(
                request.key,
                listOf(
                    created0,
                    AlignedResult.NoteFailed(1, note1, cancelled, "cancelled-before-entry"),
                    AlignedResult.NoteNotAttempted(2, note2),
                ),
                cancelled,
            ),
            listOf(created0),
        )

        val deterministic = testError(JournalErrorCode.FIELD_MAPPING_INVALID, retryable = false)
        JournalStateMachine.validateTerminalResponse(
            request,
            JournalResponse.CreateNotes(
                request.key,
                listOf(
                    created0,
                    AlignedResult.NoteCommittedFailed(1, note1, 101, deterministic, "known-note"),
                    AlignedResult.NoteNotAttempted(2, note2),
                ),
                deterministic,
            ),
            listOf(created0),
        )

        val uncertain = postCommitError()
        listOf<AlignedResult>(
            AlignedResult.NoteUncertain(1, note1, "entered-no-receipt"),
            AlignedResult.NoteCommittedFailed(1, note1, 101, uncertain, "known-note-inconclusive"),
        ).forEach { active ->
            JournalStateMachine.validateTerminalResponse(
                request,
                JournalResponse.CreateNotes(
                    request.key,
                    listOf(created0, active, AlignedResult.NoteNotAttempted(2, note2)),
                    uncertain,
                ),
                listOf(created0),
            )
        }
    }

    @Test
    fun createTerminalizationRejectsMalformedSuffixEvidenceAndErrorCarriers() {
        val request = JournalRequest.from(createRequest())
        val (note0, note1, note2) = request.itemIds
        val created0 = AlignedResult.NoteCreated(0, note0, 100)
        val cancelled = testError(JournalErrorCode.CANCELLED, retryable = false)
        val uncertain = postCommitError()

        assertInvalidCreate(request, listOf(created0), null)
        assertInvalidCreate(
            request,
            listOf(created0, AlignedResult.NoteNotAttempted(1, note1), AlignedResult.NoteNotAttempted(2, note2)),
            cancelled,
        )
        assertInvalidCreate(
            request,
            listOf(
                AlignedResult.NoteFailed(0, note0, cancelled),
                AlignedResult.NoteDuplicate(1, note1),
                AlignedResult.NoteNotAttempted(2, note2),
            ),
            cancelled,
        )
        assertInvalidCreate(
            request,
            listOf(
                created0,
                AlignedResult.NoteFailed(1, note1, cancelled),
                AlignedResult.NoteNotAttempted(2, protocolNoteId(9)),
            ),
            cancelled,
        )
        assertInvalidCreate(
            request,
            listOf(
                created0,
                AlignedResult.NoteUncertain(1, note1),
                AlignedResult.NoteNotAttempted(2, note2),
            ),
            cancelled,
        )
        assertInvalidCreate(
            request,
            listOf(
                created0,
                AlignedResult.NoteFailed(1, note1, uncertain),
                AlignedResult.NoteNotAttempted(2, note2),
            ),
            uncertain,
        )
        assertInvalidCreate(
            request,
            listOf(
                created0,
                AlignedResult.NoteCommittedFailed(
                    1,
                    note1,
                    101,
                    postCommitError(retryable = true),
                ),
                AlignedResult.NoteNotAttempted(2, note2),
            ),
            postCommitError(retryable = true),
        )
        val retryableFailure = testError(JournalErrorCode.QUERY_FAILED, retryable = true)
        assertInvalidCreate(
            request,
            listOf(
                created0,
                AlignedResult.NoteFailed(1, note1, retryableFailure),
                AlignedResult.NoteNotAttempted(2, note2),
            ),
            retryableFailure,
        )
        assertInvalidCreate(
            request,
            listOf(
                created0,
                AlignedResult.NoteCommittedFailed(1, note1, 101, cancelled),
                AlignedResult.NoteNotAttempted(2, note2),
            ),
            cancelled,
        )
        assertInvalidCreate(
            request,
            listOf(
                created0,
                AlignedResult.NoteCommittedFailed(1, note1, 100, uncertain),
                AlignedResult.NoteNotAttempted(2, note2),
            ),
            uncertain,
        )
        assertInvalidCreate(
            request,
            listOf(
                created0,
                AlignedResult.NoteFailed(1, note1, cancelled, compactEvidence = " "),
                AlignedResult.NoteNotAttempted(2, note2),
            ),
            cancelled,
            expected = IllegalArgumentException::class.java,
        )
    }

    @Test
    fun alignedResultShapeMustMatchItsParentOperation() {
        assertThrows(JournalInvariantViolation::class.java) {
            JournalStateMachine.validateAlignedResult(
                ParentOperation.STORE_MEDIA,
                AlignedResult.NoteDuplicate(0, "note"),
            )
        }
        assertThrows(JournalInvariantViolation::class.java) {
            JournalStateMachine.validateAlignedResult(
                ParentOperation.CREATE_NOTES,
                AlignedResult.MediaStored(0, "asset", "asset.ogg"),
            )
        }
        assertThrows(JournalInvariantViolation::class.java) {
            JournalStateMachine.validateAlignedResult(
                ParentOperation.VERIFY_TARGET,
                AlignedResult.TargetVerified(requestIndex = -1),
            )
        }
    }

    private fun assertInvalidMedia(
        request: JournalRequest,
        rows: List<AlignedResult>,
        error: JournalError?,
    ) {
        assertThrows(JournalInvariantViolation::class.java) {
            JournalStateMachine.validateTerminalResponse(
                request,
                JournalResponse.StoreMedia(request.key, rows, error),
                emptyList(),
            )
        }
    }

    private fun assertInvalidCreate(
        request: JournalRequest,
        rows: List<AlignedResult>,
        error: JournalError?,
        expected: Class<out Throwable> = JournalInvariantViolation::class.java,
    ) {
        assertThrows(expected) {
            JournalStateMachine.validateTerminalResponse(
                request,
                JournalResponse.CreateNotes(request.key, rows, error),
                emptyList(),
            )
        }
    }

    private fun postCommitError(retryable: Boolean = false): JournalError =
        testError(
            JournalErrorCode.POST_COMMIT_UNCERTAIN,
            retryable = retryable,
            message = "post-commit state cannot be observed",
        )

    private fun testMaterialization(): ActiveNoteMaterialization =
        ActiveNoteMaterialization(
            requestIndex = 0,
            clientNoteId = "note-0",
            orderedFields = listOf(OrderedNoteField("Expression", "word")),
            joinedFields = "word",
            normalizedTags = listOf("mined"),
            providerTagsWire = " mined ",
            duplicateDecision = DurableDuplicateDecision("key", "word", 0, duplicate = false),
            mediaBindings = listOf(DurableMediaBinding("asset-0", "clip.ogg", 1)),
        )
}
