package com.ankiminer.android.anki

import com.ankiminer.android.anki.generated.AnkiLimitsV1
import com.ankiminer.android.anki.protocol.AnkiErrorCode
import com.ankiminer.android.anki.protocol.AnkiErrorDetail
import com.ankiminer.android.anki.protocol.AnkiErrorResult
import com.ankiminer.android.anki.protocol.AnkiJsonCodec
import com.ankiminer.android.anki.protocol.AnkiOperation
import com.ankiminer.android.anki.protocol.AnkiProtocolCategory
import com.ankiminer.android.anki.protocol.AnkiProtocolException
import com.ankiminer.android.anki.protocol.AnkiRequest
import com.ankiminer.android.anki.protocol.AnkiResponse
import com.ankiminer.android.anki.protocol.AnkiValidators
import com.ankiminer.android.anki.protocol.CollectionCreateDuplicateScope
import com.ankiminer.android.anki.protocol.CommittedFailedNote
import com.ankiminer.android.anki.protocol.CreateNotesRequest
import com.ankiminer.android.anki.protocol.CreateNotesResult
import com.ankiminer.android.anki.protocol.CreateDuplicateCandidate
import com.ankiminer.android.anki.protocol.CreateNote
import com.ankiminer.android.anki.protocol.CreatedNote
import com.ankiminer.android.anki.protocol.DuplicateLookupResult
import com.ankiminer.android.anki.protocol.DuplicateNote
import com.ankiminer.android.anki.protocol.DuplicateScanScope
import com.ankiminer.android.anki.protocol.FailedMedia
import com.ankiminer.android.anki.protocol.KnownVocabularyCursor
import com.ankiminer.android.anki.protocol.KnownVocabularyResult
import com.ankiminer.android.anki.protocol.KnownVocabularyScope
import com.ankiminer.android.anki.protocol.MediaKind
import com.ankiminer.android.anki.protocol.MediaAsset
import com.ankiminer.android.anki.protocol.MediaPurpose
import com.ankiminer.android.anki.protocol.NotAttemptedMedia
import com.ankiminer.android.anki.protocol.NotAttemptedNote
import com.ankiminer.android.anki.protocol.RawFirstFieldHit
import com.ankiminer.android.anki.protocol.ReleaseRunStateRequest
import com.ankiminer.android.anki.protocol.ReleaseRunStateResult
import com.ankiminer.android.anki.protocol.ReleaseState
import com.ankiminer.android.anki.protocol.ScanFirstFieldsRequest
import com.ankiminer.android.anki.protocol.StoreMediaRequest
import com.ankiminer.android.anki.protocol.StoreMediaResult
import com.ankiminer.android.anki.protocol.StoredMedia
import com.ankiminer.android.anki.protocol.UncertainMedia
import com.ankiminer.android.anki.protocol.UncertainNote
import com.ankiminer.android.anki.protocol.VerifyTargetRequest
import com.ankiminer.android.anki.protocol.VerifyTargetResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AnkiJsonCodecTest {
    @Test
    fun `decodes all callback request variants without key-order dependence`() {
        val verify =
            decode(
                AnkiOperation.VERIFY_TARGET,
                """{"requiredFields":["Expression","Meaning"],"modelName":"Mining","requestId":"$REQUEST_ID","deckName":"Mining::Japanese","runId":"$RUN_ID"}""",
            )
        assertEquals(
            VerifyTargetRequest(RUN_ID, REQUEST_ID, "Mining::Japanese", "Mining", listOf("Expression", "Meaning")),
            verify,
        )

        val known = decode(AnkiOperation.SCAN_FIRST_FIELDS, knownVocabularyPayload()) as ScanFirstFieldsRequest
        assertEquals(
            KnownVocabularyScope(listOf("Suspended"), KnownVocabularyCursor(1, "cursor-token")),
            known.scope,
        )

        val duplicate = decode(AnkiOperation.SCAN_FIRST_FIELDS, duplicateScanPayload()) as ScanFirstFieldsRequest
        assertEquals(
            DuplicateScanScope(
                modelName = "Mining",
                firstFieldName = "Expression",
                deckName = null,
                candidates = listOf(com.ankiminer.android.anki.protocol.DuplicateCandidate("猫", "<b>猫</b>")),
                occurrences = listOf(0, 0),
                invalidateBaselineToken = null,
            ),
            duplicate.scope,
        )

        val media = decode(AnkiOperation.STORE_MEDIA, storeMediaPayload()) as StoreMediaRequest
        assertEquals(1, media.assets.size)
        assertEquals(MediaPurpose.CARD, media.assets.single().purpose)
        assertEquals(MediaKind.AUDIO, media.assets.single().mediaKind)
        assertEquals(3, media.assets.single().expectedSizeBytes)

        val create = decode(AnkiOperation.CREATE_NOTES, createNotesPayload()) as CreateNotesRequest
        assertEquals(CollectionCreateDuplicateScope, create.duplicateScope)
        assertEquals("猫", create.notes.single().fields.getValue("Expression"))
        assertEquals("clip.mp3", create.notes.single().mediaBindings.single().actualFilename)

        val release =
            decode(
                AnkiOperation.RELEASE_RUN_STATE,
                """{"acknowledgeTerminalResponses":true,"requestId":"$REQUEST_ID","runId":"$RUN_ID"}""",
            ) as ReleaseRunStateRequest
        assertTrue(release.acknowledgeTerminalResponses)
    }

    @Test
    fun `accepts mathematical integer spellings and signed-long upper endpoint`() {
        val decimalVersion = envelope(AnkiOperation.RELEASE_RUN_STATE, releasePayload(), version = "1.0")
        assertTrue((AnkiJsonCodec.decodeRequest(decimalVersion, AnkiOperation.RELEASE_RUN_STATE) as ReleaseRunStateRequest).acknowledgeTerminalResponses)

        val payload = knownVocabularyPayload().replace("\"ordinal\":1", "\"ordinal\":${Long.MAX_VALUE}")
        val request = decode(AnkiOperation.SCAN_FIRST_FIELDS, payload) as ScanFirstFieldsRequest
        assertEquals(Long.MAX_VALUE, (request.scope as KnownVocabularyScope).cursor?.ordinal)
    }

    @Test
    fun `enforces the lexical number ceiling before numeric conversion`() {
        val exact = "1" + "0".repeat(AnkiLimitsV1.Wire.NUMERIC_TOKEN_MAX_CHARS - 1)
        assertCategory(AnkiProtocolCategory.INTEGER_OUT_OF_RANGE) {
            AnkiJsonCodec.decodeRequest(
                envelope(AnkiOperation.RELEASE_RUN_STATE, releasePayload(), version = exact),
                AnkiOperation.RELEASE_RUN_STATE,
            )
        }

        val over = exact + "0"
        assertCategory(AnkiProtocolCategory.NUMERIC_TOKEN_TOO_LONG) {
            AnkiJsonCodec.decodeRequest(
                envelope(AnkiOperation.RELEASE_RUN_STATE, releasePayload(), version = over),
                AnkiOperation.RELEASE_RUN_STATE,
            )
        }
        val quoted = releasePayload().replace(RUN_ID, "run_${"1".repeat(32)}") + " ".repeat(1001)
        assertTrue(decode(AnkiOperation.RELEASE_RUN_STATE, quoted) is ReleaseRunStateRequest)
    }

    @Test
    fun `rejects positive signed-long boundary after floating-point rounding`() {
        val payload = knownVocabularyPayload().replace("\"ordinal\":1", "\"ordinal\":9.223372036854775807e18")
        assertCategory(AnkiProtocolCategory.INVALID_VALUE) { decode(AnkiOperation.SCAN_FIRST_FIELDS, payload) }
        val overflow = knownVocabularyPayload().replace("\"ordinal\":1", "\"ordinal\":1e999")
        assertCategory(AnkiProtocolCategory.NON_FINITE_NUMBER) { decode(AnkiOperation.SCAN_FIRST_FIELDS, overflow) }
    }

    @Test
    fun `rejects malformed JSON extensions duplicate keys and wrong envelopes`() {
        val duplicate = releasePayload().replace("\"runId\":\"$RUN_ID\"", "\"runId\":\"$RUN_ID\",\"runId\":\"$RUN_ID\"")
        assertCategory(AnkiProtocolCategory.DUPLICATE_JSON_KEY) { decode(AnkiOperation.RELEASE_RUN_STATE, duplicate) }
        assertCategory(AnkiProtocolCategory.INVALID_JSON) {
            AnkiJsonCodec.decodeRequest("\uFEFF" + envelope(AnkiOperation.RELEASE_RUN_STATE, releasePayload()), AnkiOperation.RELEASE_RUN_STATE)
        }
        assertCategory(AnkiProtocolCategory.INVALID_JSON) {
            AnkiJsonCodec.decodeRequest(envelope(AnkiOperation.RELEASE_RUN_STATE, releasePayload()) + "//comment", AnkiOperation.RELEASE_RUN_STATE)
        }
        assertCategory(AnkiProtocolCategory.INVALID_ENVELOPE) {
            AnkiJsonCodec.decodeRequest(
                """{"schemaVersion":1,"type":"${AnkiOperation.RELEASE_RUN_STATE.requestType}","payload":${releasePayload()},"extra":0}""",
                AnkiOperation.RELEASE_RUN_STATE,
            )
        }
        assertCategory(AnkiProtocolCategory.UNEXPECTED_MESSAGE_TYPE) {
            AnkiJsonCodec.decodeRequest(
                envelope(AnkiOperation.VERIFY_TARGET, releasePayload()),
                AnkiOperation.RELEASE_RUN_STATE,
            )
        }
    }

    @Test
    fun `protocol errors echo parsed valid identifiers and otherwise use placeholders`() {
        val malformedPayload =
            """{"runId":"$RUN_ID","requestId":"$REQUEST_ID","acknowledgeTerminalResponses":"yes"}"""
        val parsedError =
            assertThrows(AnkiProtocolException::class.java) {
                decode(AnkiOperation.RELEASE_RUN_STATE, malformedPayload)
            }
        assertEquals(RUN_ID, parsedError.recoveredRunId)
        assertEquals(REQUEST_ID, parsedError.recoveredRequestId)
        assertEquals(
            """{"schemaVersion":1,"type":"anki.error","payload":{"runId":"$RUN_ID","requestId":"$REQUEST_ID","operation":"releaseRunState","code":"invalid_request","message":"Invalid Anki request (invalid_payload)","retryable":false}}""",
            AnkiJsonCodec.encodeProtocolError(AnkiOperation.RELEASE_RUN_STATE, parsedError),
        )

        val unparsedError =
            assertThrows(AnkiProtocolException::class.java) {
                AnkiJsonCodec.decodeRequest("\uFEFF{}", AnkiOperation.RELEASE_RUN_STATE)
            }
        assertTrue(
            AnkiJsonCodec.encodeProtocolError(AnkiOperation.RELEASE_RUN_STATE, unparsedError)
                .contains("\"runId\":\"run_00000000000000000000000000000000\""),
        )

        val duplicateAfterIds =
            releasePayload().replace(
                "\"acknowledgeTerminalResponses\":true",
                "\"requestId\":\"$REQUEST_ID\",\"acknowledgeTerminalResponses\":true",
            )
        val trailingCommaAfterIds = releasePayload().replace("true}", "true,}")
        for (payload in listOf(duplicateAfterIds, trailingCommaAfterIds)) {
            val structuralError =
                assertThrows(AnkiProtocolException::class.java) {
                    decode(AnkiOperation.RELEASE_RUN_STATE, payload)
                }
            assertEquals(RUN_ID, structuralError.recoveredRunId)
            assertEquals(REQUEST_ID, structuralError.recoveredRequestId)
        }
    }

    @Test
    fun `rejects raw and escaped unpaired surrogates`() {
        val rawSurrogatePayload = releasePayload().replace(RUN_ID, "run_${"0".repeat(31)}\uD800")
        assertCategory(AnkiProtocolCategory.INVALID_UTF8) {
            AnkiJsonCodec.decodeRequest(
                envelope(AnkiOperation.RELEASE_RUN_STATE, rawSurrogatePayload),
                AnkiOperation.RELEASE_RUN_STATE,
            )
        }
        val escaped = releasePayload().replace(RUN_ID, "run_${"0".repeat(31)}\\ud800")
        assertCategory(AnkiProtocolCategory.INVALID_UTF8) { decode(AnkiOperation.RELEASE_RUN_STATE, escaped) }

        val oversizedMalformed =
            " ".repeat(AnkiOperation.RELEASE_RUN_STATE.requestEnvelopeMaxUtf8Bytes + 1) +
                '\uD800'
        assertCategory(AnkiProtocolCategory.INVALID_UTF8) {
            AnkiJsonCodec.decodeRequest(
                oversizedMalformed,
                AnkiOperation.RELEASE_RUN_STATE,
            )
        }
    }

    @Test
    fun `enforces exact request UTF-8 envelope boundary before parsing`() {
        val raw = envelope(AnkiOperation.RELEASE_RUN_STATE, releasePayload())
        val limit = AnkiOperation.RELEASE_RUN_STATE.requestEnvelopeMaxUtf8Bytes
        val exact = raw + " ".repeat(limit - raw.toByteArray().size)
        assertEquals(limit, exact.toByteArray().size)
        assertTrue(AnkiJsonCodec.decodeRequest(exact, AnkiOperation.RELEASE_RUN_STATE) is ReleaseRunStateRequest)
        assertCategory(AnkiProtocolCategory.INPUT_TOO_LARGE) {
            AnkiJsonCodec.decodeRequest("$exact ", AnkiOperation.RELEASE_RUN_STATE)
        }
    }

    @Test
    fun `uses pinned Unicode rules for canonical names and UTF-8 limits`() {
        val acceptedAstral = "😀".repeat(256)
        assertTrue(decode(AnkiOperation.VERIFY_TARGET, verifyPayload(deck = acceptedAstral)) is VerifyTargetRequest)

        val invalidDecks =
            listOf(
                "😀".repeat(257),
                " e",
                "e ",
                "e\u0301",
                "bad\u00ADname",
            )
        for (deck in invalidDecks) {
            assertCategory(AnkiProtocolCategory.INVALID_VALUE) {
                decode(AnkiOperation.VERIFY_TARGET, verifyPayload(deck = deck))
            }
        }
    }

    @Test
    fun `requires exact generated limits and amended contract fields`() {
        val badLimits = createNotesPayload().replace("\"maxMediaBindingsTotal\":8000", "\"maxMediaBindingsTotal\":7999")
        assertCategory(AnkiProtocolCategory.LIMIT_MISMATCH) { decode(AnkiOperation.CREATE_NOTES, badLimits) }

        val missingBindings = createNotesPayload().replace(",\"mediaBindings\":[{\"assetId\":\"$ASSET_ID\",\"actualFilename\":\"clip.mp3\"}]", "")
        assertCategory(AnkiProtocolCategory.INVALID_PAYLOAD) { decode(AnkiOperation.CREATE_NOTES, missingBindings) }

        val missingAcknowledgement = releasePayload().replace(",\"acknowledgeTerminalResponses\":true", "")
        assertCategory(AnkiProtocolCategory.INVALID_PAYLOAD) { decode(AnkiOperation.RELEASE_RUN_STATE, missingAcknowledgement) }
    }

    @Test
    fun `validates provider text leaves and aggregate with exact UTF-8 counts`() {
        val emptyTemplate = AnkiValidators.ProviderTemplateText("", "", null, null)
        AnkiValidators.validateProviderTextSnapshot(
            css = "x".repeat(AnkiLimitsV1.TargetModel.CSS_MAX_UTF8_BYTES),
            latexPre = null,
            latexPost = "",
            templates = listOf(emptyTemplate),
        )
        assertCategory(AnkiProtocolCategory.INVALID_VALUE) {
            AnkiValidators.validateProviderTextSnapshot(
                css = "x".repeat(AnkiLimitsV1.TargetModel.CSS_MAX_UTF8_BYTES + 1),
                latexPre = null,
                latexPost = null,
                templates = listOf(emptyTemplate),
            )
        }
        val chunk = "x".repeat(AnkiLimitsV1.TargetModel.CSS_MAX_UTF8_BYTES)
        val templates =
            List(4) {
                AnkiValidators.ProviderTemplateText(chunk, chunk, chunk, chunk)
            }
        AnkiValidators.validateProviderTextSnapshot(chunk, null, null, templates.take(3))
        assertCategory(AnkiProtocolCategory.INVALID_VALUE) {
            AnkiValidators.validateProviderTextSnapshot(chunk, null, null, templates)
        }
    }

    @Test
    fun `generates fixed canonical envelopes for every result family`() {
        assertEquals(
            """{"schemaVersion":1,"type":"anki.verifytarget.result","payload":{"runId":"$RUN_ID","requestId":"$REQUEST_ID","deckId":2,"modelId":3,"fieldNames":["Expression"],"deckCreated":false}}""",
            encode(VerifyTargetResult(RUN_ID, REQUEST_ID, 2, 3, listOf("Expression"), false)),
        )
        assertEquals(
            """{"schemaVersion":1,"type":"anki.scanfirstfields.result","payload":{"runId":"$RUN_ID","requestId":"$REQUEST_ID","firstFields":["猫"],"scannedNotes":1,"nextCursor":null}}""",
            encode(KnownVocabularyResult(RUN_ID, REQUEST_ID, listOf("猫"), 1, null)),
        )
        assertEquals(
            """{"schemaVersion":1,"type":"anki.scanfirstfields.result","payload":{"runId":"$RUN_ID","requestId":"$REQUEST_ID","rawFirstFieldHits":[[{"noteId":9,"firstField":"猫"}]],"baselineToken":"$BASELINE_TOKEN"}}""",
            encode(DuplicateLookupResult(RUN_ID, REQUEST_ID, listOf(listOf(RawFirstFieldHit(9, "猫"))), BASELINE_TOKEN)),
        )
        assertEquals(
            """{"schemaVersion":1,"type":"anki.storemedia.result","payload":{"runId":"$RUN_ID","requestId":"$REQUEST_ID","results":[{"assetId":"$ASSET_ID","status":"stored","actualFilename":"clip.mp3"}],"error":null}}""",
            encode(StoreMediaResult(RUN_ID, REQUEST_ID, listOf(StoredMedia(ASSET_ID, "clip.mp3")), null)),
        )
        assertEquals(
            """{"schemaVersion":1,"type":"anki.createnotes.result","payload":{"runId":"$RUN_ID","requestId":"$REQUEST_ID","results":[{"clientNoteId":"$NOTE_ID","status":"created","noteId":7}],"error":null}}""",
            encode(CreateNotesResult(RUN_ID, REQUEST_ID, listOf(CreatedNote(NOTE_ID, 7)), null)),
        )
        assertEquals(
            """{"schemaVersion":1,"type":"anki.releaserunstate.result","payload":{"runId":"$RUN_ID","requestId":"$REQUEST_ID","state":"released"}}""",
            encode(ReleaseRunStateResult(RUN_ID, REQUEST_ID, ReleaseState.RELEASED)),
        )
        assertEquals(
            """{"schemaVersion":1,"type":"anki.error","payload":{"runId":"$RUN_ID","requestId":"$REQUEST_ID","operation":"verifyTarget","code":"permission_required","message":"Permission required","retryable":true}}""",
            encode(
                AnkiErrorResult(RUN_ID, REQUEST_ID, AnkiOperation.VERIFY_TARGET, AnkiErrorCode.PERMISSION_REQUIRED, "Permission required", true),
            ),
        )
    }

    @Test
    fun `validates all aligned partial-result variants before generation`() {
        val mediaError = AnkiErrorDetail(AnkiErrorCode.POST_COMMIT_UNCERTAIN, "Provider result is uncertain", false)
        val media =
            StoreMediaResult(
                RUN_ID,
                REQUEST_ID,
                listOf(StoredMedia(ASSET_ID, "clip.mp3"), UncertainMedia(ASSET_ID_2), NotAttemptedMedia(ASSET_ID_3)),
                mediaError,
            )
        assertTrue(encode(media).contains("\"status\":\"uncertain\""))

        val noteError = AnkiErrorDetail(AnkiErrorCode.WRITE_FAILED, "Routing failed", false)
        val notes =
            CreateNotesResult(
                RUN_ID,
                REQUEST_ID,
                listOf(DuplicateNote(NOTE_ID), CommittedFailedNote(NOTE_ID_2, 8), NotAttemptedNote(NOTE_ID_3)),
                noteError,
            )
        assertTrue(encode(notes).contains("\"status\":\"committedFailed\""))

        assertCategory(AnkiProtocolCategory.INVALID_VALUE) {
            encode(
                StoreMediaResult(
                    RUN_ID,
                    REQUEST_ID,
                    listOf(StoredMedia(ASSET_ID, "same.mp3"), StoredMedia(ASSET_ID_2, "same.mp3")),
                    null,
                ),
            )
        }
        assertCategory(AnkiProtocolCategory.INVALID_VALUE) {
            encode(
                CreateNotesResult(
                    RUN_ID,
                    REQUEST_ID,
                    listOf(UncertainNote(NOTE_ID), CreatedNote(NOTE_ID_2, 10)),
                    mediaError,
                ),
            )
        }
        assertCategory(AnkiProtocolCategory.INVALID_VALUE) {
            encode(
                StoreMediaResult(
                    RUN_ID,
                    REQUEST_ID,
                    listOf(FailedMedia(ASSET_ID, AnkiErrorDetail(AnkiErrorCode.WRITE_FAILED, "wrong", false))),
                    null,
                ),
            )
        }
    }

    @Test
    fun `rejects invalid response Unicode and output overflow`() {
        assertCategory(AnkiProtocolCategory.INVALID_UTF8) {
            encode(
                AnkiErrorResult(RUN_ID, REQUEST_ID, AnkiOperation.RELEASE_RUN_STATE, AnkiErrorCode.INTERNAL_ERROR, "bad\uD800", false),
            )
        }
        assertCategory(AnkiProtocolCategory.OUTPUT_TOO_LARGE) {
            encode(
                AnkiErrorResult(
                    RUN_ID,
                    REQUEST_ID,
                    AnkiOperation.RELEASE_RUN_STATE,
                    AnkiErrorCode.INTERNAL_ERROR,
                    "x".repeat(AnkiOperation.RELEASE_RUN_STATE.resultEnvelopeMaxUtf8Bytes),
                    false,
                ),
            )
        }
        assertCategory(AnkiProtocolCategory.INVALID_VALUE) {
            encode(
                StoreMediaResult(RUN_ID, REQUEST_ID, listOf(StoredMedia(ASSET_ID, "[SoUnD:clip.mp3]")), null),
            )
        }
    }

    @Test
    fun `nonstandard media basename characters remain distinct from preferred names`() {
        val requested =
            storeMediaPayload()
                .replace("\"purpose\":\"card\"", "\"purpose\":\"dictionary\"")
                .replace("\"requestedFilename\":\"clip.mp3\"", "\"requestedFilename\":\"quoted [clip] \\\".mp3\"")
                .replace(
                    "\"preferredName\":\"clip\"",
                    "\"preferredName\":\"anki_miner_dict_72a54deb85e495b3a01ec2288ddea4480d56e0b4c31cccf655d5dccb8e810135\"",
                )
        assertTrue(decode(AnkiOperation.STORE_MEDIA, requested) is StoreMediaRequest)
        val badPreferred = storeMediaPayload().replace("\"preferredName\":\"clip\"", "\"preferredName\":\"[clip]\"")
        assertCategory(AnkiProtocolCategory.INVALID_VALUE) { decode(AnkiOperation.STORE_MEDIA, badPreferred) }
    }

    private fun decode(operation: AnkiOperation, payload: String): Any =
        AnkiJsonCodec.decodeRequest(envelope(operation, payload), operation)

    private fun encode(response: AnkiResponse): String =
        AnkiJsonCodec.encodeResponse(response, matchingRequest(response))

    private fun matchingRequest(response: AnkiResponse): AnkiRequest =
        when (response.operation) {
            AnkiOperation.VERIFY_TARGET ->
                VerifyTargetRequest(response.runId, response.requestId, "Mining", "Mining", emptyList())
            AnkiOperation.SCAN_FIRST_FIELDS ->
                when (response) {
                    is DuplicateLookupResult ->
                        ScanFirstFieldsRequest(
                            response.runId,
                            response.requestId,
                            DuplicateScanScope(
                                "Mining",
                                "Expression",
                                null,
                                response.rawFirstFieldHits.indices.map { index ->
                                    com.ankiminer.android.anki.protocol.DuplicateCandidate("key-$index", "value-$index")
                                },
                                response.rawFirstFieldHits.indices.toList(),
                                null,
                            ),
                        )
                    else ->
                        ScanFirstFieldsRequest(
                            response.runId,
                            response.requestId,
                            KnownVocabularyScope(emptyList(), null),
                        )
                }
            AnkiOperation.STORE_MEDIA -> {
                val rows = (response as? StoreMediaResult)?.results
                    ?: listOf(NotAttemptedMedia(ASSET_ID))
                StoreMediaRequest(
                    response.runId,
                    response.requestId,
                    rows.mapIndexed { index, row ->
                        val requested = (row as? StoredMedia)?.actualFilename ?: "asset-$index.mp3"
                        val suffix = requested.lastIndexOf('.')
                        val preferred = if (suffix > 0) requested.substring(0, suffix).replace(' ', '_') else requested
                        MediaAsset(
                            row.assetId,
                            "/tmp/asset-$index.mp3",
                            if (preferred.length >= 2) preferred else "${preferred}_",
                            requested,
                            MediaPurpose.CARD,
                            MediaKind.AUDIO,
                            0,
                            "0".repeat(64),
                        )
                    },
                )
            }
            AnkiOperation.CREATE_NOTES -> {
                val rows = (response as? CreateNotesResult)?.results
                    ?: listOf(NotAttemptedNote(NOTE_ID))
                CreateNotesRequest(
                    response.runId,
                    response.requestId,
                    "Mining",
                    "Mining",
                    "Expression",
                    BASELINE_TOKEN,
                    CollectionCreateDuplicateScope,
                    rows.mapIndexed { index, row ->
                        CreateNote(
                            row.clientNoteId,
                            linkedMapOf("Expression" to "value-$index"),
                            emptyList(),
                            CreateDuplicateCandidate("key-$index", "value-$index", index),
                            emptyList(),
                        )
                    },
                )
            }
            AnkiOperation.RELEASE_RUN_STATE ->
                ReleaseRunStateRequest(response.runId, response.requestId, false)
        }

    private fun envelope(operation: AnkiOperation, payload: String, version: String = "1"): String =
        """{"schemaVersion":$version,"type":"${operation.requestType}","payload":$payload}"""

    private fun verifyPayload(deck: String = "Mining"): String =
        """{"runId":"$RUN_ID","requestId":"$REQUEST_ID","deckName":"$deck","modelName":"Mining","requiredFields":["Expression"]}"""

    private fun knownVocabularyPayload(): String =
        """{"runId":"$RUN_ID","requestId":"$REQUEST_ID","scope":{"kind":"knownVocabulary","excludedDecks":["Suspended"],"cursor":{"ordinal":1,"token":"cursor-token"},"limits":{"maxScannedNotes":256,"maxTotalScannedNotes":100000,"maxItems":256,"maxItemUtf8Bytes":65536,"maxTotalUtf8Bytes":262144}}}"""

    private fun duplicateScanPayload(): String =
        """{"runId":"$RUN_ID","requestId":"$REQUEST_ID","scope":{"kind":"duplicates","modelName":"Mining","firstFieldName":"Expression","deckName":null,"candidates":[{"key":"猫","firstField":"<b>猫</b>"}],"occurrences":[0,0],"invalidateBaselineToken":null,"limits":{"maxHitsPerCandidate":100,"maxTotalHits":1000,"maxItemUtf8Bytes":65536,"maxTotalUtf8Bytes":1048576}}}"""

    private fun storeMediaPayload(): String =
        """{"runId":"$RUN_ID","requestId":"$REQUEST_ID","assets":[{"assetId":"$ASSET_ID","sourcePath":"/tmp/clip.mp3","preferredName":"clip","requestedFilename":"clip.mp3","purpose":"card","mediaKind":"audio","expectedSizeBytes":3,"expectedSha256":"${"a".repeat(64)}"}],"limits":{"maxAssets":50,"maxAssetBytes":67108864,"maxTotalBytes":67108864}}"""

    private fun createNotesPayload(): String =
        """{"runId":"$RUN_ID","requestId":"$REQUEST_ID","deckName":"Mining","modelName":"Mining","firstFieldName":"Expression","baselineToken":"$BASELINE_TOKEN","duplicateScope":{"kind":"collection","limits":{"maxNoteIdsPerCandidate":100,"maxTotalNoteIds":1000}},"limits":{"maxNotes":100,"maxFieldsPerNote":64,"maxCardsPerNote":64,"maxFieldNameUtf8Bytes":256,"maxFieldValueUtf8Bytes":98304,"maxTagsPerNote":64,"maxTagUtf8Bytes":256,"maxTagsUtf8BytesPerNote":8192,"maxNoteContentUtf8Bytes":131072,"maxTotalContentUtf8Bytes":393216,"maxMediaBindingsPerNote":8000,"maxMediaBindingsTotal":8000,"maxEnvelopeUtf8Bytes":524288},"notes":[{"clientNoteId":"$NOTE_ID","fields":{"Expression":"猫","Meaning":""},"tags":["mined"],"duplicateCandidate":{"key":"猫","firstField":"猫","occurrence":0},"mediaBindings":[{"assetId":"$ASSET_ID","actualFilename":"clip.mp3"}]}]}"""

    private fun releasePayload(): String =
        """{"runId":"$RUN_ID","requestId":"$REQUEST_ID","acknowledgeTerminalResponses":true}"""

    private fun assertCategory(expected: AnkiProtocolCategory, block: () -> Unit) {
        val error = assertThrows(AnkiProtocolException::class.java, block)
        assertEquals(expected, error.category)
        assertFalse(error.message.isNullOrEmpty())
    }

    private companion object {
        const val RUN_ID = "run_00000000000000000000000000000000"
        const val REQUEST_ID = "anki_11111111111111111111111111111111"
        const val ASSET_ID = "asset_22222222222222222222222222222222"
        const val ASSET_ID_2 = "asset_33333333333333333333333333333333"
        const val ASSET_ID_3 = "asset_44444444444444444444444444444444"
        const val NOTE_ID = "note_55555555555555555555555555555555"
        const val NOTE_ID_2 = "note_66666666666666666666666666666666"
        const val NOTE_ID_3 = "note_77777777777777777777777777777777"
        const val BASELINE_TOKEN = "baseline_88888888888888888888888888888888"
    }
}
