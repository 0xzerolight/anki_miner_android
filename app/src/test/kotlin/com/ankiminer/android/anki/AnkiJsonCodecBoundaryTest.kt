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
import com.ankiminer.android.anki.protocol.CreateDuplicateCandidate
import com.ankiminer.android.anki.protocol.CreateNote
import com.ankiminer.android.anki.protocol.CreateNoteRow
import com.ankiminer.android.anki.protocol.CreateNotesRequest
import com.ankiminer.android.anki.protocol.CreateNotesResult
import com.ankiminer.android.anki.protocol.CreatedNote
import com.ankiminer.android.anki.protocol.DuplicateCandidate
import com.ankiminer.android.anki.protocol.DuplicateLookupResult
import com.ankiminer.android.anki.protocol.DuplicateNote
import com.ankiminer.android.anki.protocol.DuplicateScanScope
import com.ankiminer.android.anki.protocol.FailedMedia
import com.ankiminer.android.anki.protocol.FailedNote
import com.ankiminer.android.anki.protocol.KnownVocabularyCursor
import com.ankiminer.android.anki.protocol.KnownVocabularyResult
import com.ankiminer.android.anki.protocol.KnownVocabularyScope
import com.ankiminer.android.anki.protocol.MediaAsset
import com.ankiminer.android.anki.protocol.MediaBinding
import com.ankiminer.android.anki.protocol.MediaKind
import com.ankiminer.android.anki.protocol.MediaPurpose
import com.ankiminer.android.anki.protocol.MediaStoreRow
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
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonFactoryBuilder
import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.core.exc.InputCoercionException
import com.fasterxml.jackson.core.exc.StreamConstraintsException
import com.fasterxml.jackson.core.json.JsonReadFeature
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private typealias JsonObject = Map<String, Any?>

/** Adversarial contract tests kept separate from the readable protocol examples. */
class AnkiJsonCodecBoundaryTest {
    @Test
    fun `all request envelopes accept exactly their byte ceiling and reject one more byte`() {
        val payloads =
            mapOf(
                AnkiOperation.VERIFY_TARGET to verifyPayload(),
                AnkiOperation.SCAN_FIRST_FIELDS to knownPayload(),
                AnkiOperation.STORE_MEDIA to storePayload(listOf(cardAssetJson())),
                AnkiOperation.CREATE_NOTES to createPayload(listOf(createNoteJson())),
                AnkiOperation.RELEASE_RUN_STATE to releasePayload(),
            )

        for ((operation, payload) in payloads) {
            val raw = envelope(operation, payload)
            val padding = operation.requestEnvelopeMaxUtf8Bytes - raw.utf8Size()
            assertTrue("${operation.wireName} fixture exceeds its limit", padding >= 0)
            val exact = raw + " ".repeat(padding)
            assertEquals(operation.requestEnvelopeMaxUtf8Bytes, exact.utf8Size())
            AnkiJsonCodec.decodeRequest(exact, operation)
            assertCategory("${operation.wireName} N+1 envelope", AnkiProtocolCategory.INPUT_TOO_LARGE) {
                AnkiJsonCodec.decodeRequest("$exact ", operation)
            }
        }

        assertCategory("zero-byte document", AnkiProtocolCategory.INVALID_ENVELOPE) {
            AnkiJsonCodec.decodeRequest("", AnkiOperation.RELEASE_RUN_STATE)
        }
    }

    @Test
    fun `collection item limits enforce zero exact maximum and maximum plus one`() {
        val cases =
            listOf(
                ItemBoundaryCase(
                    "required fields",
                    minimumAccepted = true,
                    maximum = AnkiLimitsV1.Names.TargetFields.MAX_ITEM_COUNT,
                    payload = { count ->
                        verifyPayload(requiredFields = List(count) { index -> "Field$index" })
                    },
                    operation = AnkiOperation.VERIFY_TARGET,
                ),
                ItemBoundaryCase(
                    "excluded decks",
                    minimumAccepted = true,
                    maximum = AnkiLimitsV1.Names.ExcludedDecks.MAX_ITEM_COUNT,
                    payload = { count ->
                        knownPayload(excludedDecks = List(count) { index -> "Excluded$index" })
                    },
                    operation = AnkiOperation.SCAN_FIRST_FIELDS,
                ),
                ItemBoundaryCase(
                    "duplicate candidates",
                    minimumAccepted = false,
                    maximum = AnkiLimitsV1.ScanFirstFields.DUPLICATE_CANDIDATE_MAX_ITEM_COUNT,
                    payload = { count -> duplicatePayload(count) },
                    operation = AnkiOperation.SCAN_FIRST_FIELDS,
                ),
                ItemBoundaryCase(
                    "media assets",
                    minimumAccepted = false,
                    maximum = AnkiLimitsV1.StoreMedia.MAX_ASSET_COUNT,
                    payload = { count ->
                        storePayload(List(count) { index -> cardAssetJson(index = index) })
                    },
                    operation = AnkiOperation.STORE_MEDIA,
                ),
                ItemBoundaryCase(
                    "notes",
                    minimumAccepted = false,
                    maximum = AnkiLimitsV1.CreateNotes.MAX_NOTE_COUNT,
                    payload = { count ->
                        createPayload(
                            List(count) { index ->
                                createNoteJson(index = index, occurrence = index)
                            },
                        )
                    },
                    operation = AnkiOperation.CREATE_NOTES,
                ),
            )

        for (case in cases) {
            if (case.minimumAccepted) {
                decode(case.operation, case.payload(0))
            } else {
                assertCategory("${case.name} zero", AnkiProtocolCategory.INVALID_PAYLOAD) {
                    decode(case.operation, case.payload(0))
                }
            }
            decode(case.operation, case.payload(case.maximum))
            assertCategory("${case.name} N+1", AnkiProtocolCategory.INVALID_VALUE) {
                decode(case.operation, case.payload(case.maximum + 1))
            }
        }
    }

    @Test
    fun `note child item limits enforce their reachable endpoints`() {
        val fieldCases =
            listOf(
                Triple(0, false, emptyMap()),
                Triple(
                    AnkiLimitsV1.CreateNotes.MAX_FIELD_COUNT_PER_NOTE,
                    true,
                    List(AnkiLimitsV1.CreateNotes.MAX_FIELD_COUNT_PER_NOTE) { "F$it" }
                        .associateWith { "" },
                ),
                Triple(
                    AnkiLimitsV1.CreateNotes.MAX_FIELD_COUNT_PER_NOTE + 1,
                    false,
                    List(AnkiLimitsV1.CreateNotes.MAX_FIELD_COUNT_PER_NOTE + 1) { "F$it" }
                        .associateWith { "" },
                ),
            )
        for ((count, accepted, fields) in fieldCases) {
            val payload = createPayload(listOf(createNoteJson(fields = fields)))
            if (accepted) {
                decode(AnkiOperation.CREATE_NOTES, payload)
            } else {
                val category =
                    if (count == 0) {
                        AnkiProtocolCategory.INVALID_PAYLOAD
                    } else {
                        AnkiProtocolCategory.INVALID_VALUE
                    }
                assertCategory("field count $count", category) {
                    decode(AnkiOperation.CREATE_NOTES, payload)
                }
            }
        }

        val tagCases =
            listOf(
                0 to true,
                AnkiLimitsV1.CreateNotes.MAX_TAG_COUNT_PER_NOTE to true,
                (AnkiLimitsV1.CreateNotes.MAX_TAG_COUNT_PER_NOTE + 1) to false,
            )
        for ((count, accepted) in tagCases) {
            assertDecodeOutcome(
                "tag count $count",
                accepted,
                createPayload(
                    listOf(createNoteJson(tags = List(count) { index -> "tag$index" })),
                ),
            )
        }

        decode(
            AnkiOperation.CREATE_NOTES,
            createPayload(listOf(createNoteJson(mediaBindings = emptyList()))),
        )
        val tooManyBindings =
            directCreateRequest(
                listOf(
                    directNote(
                        index = 0,
                        occurrence = 0,
                        mediaBindings =
                            List(AnkiLimitsV1.CreateNotes.MAX_MEDIA_BINDING_COUNT_PER_NOTE + 1) {
                                binding(it)
                            },
                    ),
                ),
            )
        assertCategory("per-note media binding N+1", AnkiProtocolCategory.INVALID_VALUE) {
            AnkiValidators.validateRequest(tooManyBindings)
        }

        val exactTotal =
            directCreateRequest(
                listOf(2667, 2667, 2666).mapIndexed { index, count ->
                    directNote(
                        index = index,
                        occurrence = index,
                        mediaBindings = List(count) { binding(it) },
                    )
                },
            )
        AnkiValidators.validateRequest(exactTotal)
        val overTotal =
            directCreateRequest(
                List(3) { index ->
                    directNote(
                        index = index,
                        occurrence = index,
                        mediaBindings = List(2667) { binding(it) },
                    )
                },
            )
        assertCategory("total media binding N+1", AnkiProtocolCategory.INVALID_VALUE) {
            AnkiValidators.validateRequest(overTotal)
        }
    }

    @Test
    fun `string leaf and aggregate limits count exact UTF-8 bytes`() {
        val canonicalCases =
            listOf(
                StringBoundaryCase(
                    "deck",
                    AnkiLimitsV1.Names.Deck.MAX_UTF8_BYTES,
                ) { value -> verifyPayload(deckName = value) },
                StringBoundaryCase(
                    "model",
                    AnkiLimitsV1.Names.Model.MAX_UTF8_BYTES,
                ) { value -> verifyPayload(modelName = value) },
                StringBoundaryCase(
                    "field",
                    AnkiLimitsV1.Names.Field.MAX_UTF8_BYTES,
                ) { value -> verifyPayload(requiredFields = listOf(value)) },
            )
        for (case in canonicalCases) {
            decode(AnkiOperation.VERIFY_TARGET, case.payload("x".repeat(case.maximum)))
            assertCategory("${case.name} byte N+1", AnkiProtocolCategory.INVALID_VALUE) {
                decode(
                    AnkiOperation.VERIFY_TARGET,
                    case.payload("x".repeat(case.maximum + 1)),
                )
            }
        }

        val exactPath = "/" + "x".repeat(AnkiLimitsV1.StoreMedia.SOURCE_PATH_MAX_UTF8_BYTES - 1)
        decode(
            AnkiOperation.STORE_MEDIA,
            storePayload(listOf(cardAssetJson(sourcePath = exactPath))),
        )
        assertCategory("source path byte N+1", AnkiProtocolCategory.INVALID_VALUE) {
            decode(
                AnkiOperation.STORE_MEDIA,
                storePayload(listOf(cardAssetJson(sourcePath = "$exactPath!"))),
            )
        }

        val exactFilename =
            "x".repeat(AnkiLimitsV1.StoreMedia.FILENAME_MAX_UTF8_BYTES - ".opus".length) +
                ".opus"
        decode(
            AnkiOperation.STORE_MEDIA,
            storePayload(listOf(cardAssetJson(filename = exactFilename))),
        )
        assertCategory("filename byte N+1", AnkiProtocolCategory.INVALID_VALUE) {
            decode(
                AnkiOperation.STORE_MEDIA,
                storePayload(listOf(cardAssetJson(filename = "x$exactFilename"))),
            )
        }

        val exactFieldValue = "x".repeat(AnkiLimitsV1.CreateNotes.FIELD_VALUE_MAX_UTF8_BYTES)
        decode(
            AnkiOperation.CREATE_NOTES,
            createPayload(listOf(createNoteJson(fields = mapOf("E" to exactFieldValue)))),
        )
        assertCategory("field value byte N+1", AnkiProtocolCategory.INVALID_VALUE) {
            decode(
                AnkiOperation.CREATE_NOTES,
                createPayload(
                    listOf(createNoteJson(fields = mapOf("E" to "$exactFieldValue!"))),
                ),
            )
        }

        val exactTags = List(32) { fixedAscii("t$it", AnkiLimitsV1.CreateNotes.TAG_MAX_UTF8_BYTES) }
        decode(
            AnkiOperation.CREATE_NOTES,
            createPayload(listOf(createNoteJson(tags = exactTags))),
        )
        assertCategory("tag aggregate byte N+1", AnkiProtocolCategory.INVALID_VALUE) {
            decode(
                AnkiOperation.CREATE_NOTES,
                createPayload(listOf(createNoteJson(tags = exactTags + "z"))),
            )
        }

        val exactNote = directNoteWithContentBytes(index = 0, occurrence = 0, bytes = 131_072)
        AnkiValidators.validateRequest(directCreateRequest(listOf(exactNote)))
        assertCategory("note content byte N+1", AnkiProtocolCategory.INVALID_VALUE) {
            AnkiValidators.validateRequest(
                directCreateRequest(
                    listOf(
                        directNoteWithContentBytes(
                            index = 0,
                            occurrence = 0,
                            bytes = 131_073,
                        ),
                    ),
                ),
            )
        }

        val exactCallback =
            directCreateRequest(
                List(4) { index ->
                    directNoteWithContentBytes(index, index, bytes = 98_304)
                },
            )
        AnkiValidators.validateRequest(exactCallback)
        val overCallback =
            directCreateRequest(
                List(4) { index ->
                    directNoteWithContentBytes(
                        index,
                        index,
                        bytes = if (index == 0) 98_305 else 98_304,
                    )
                },
            )
        assertCategory("callback content byte N+1", AnkiProtocolCategory.INVALID_VALUE) {
            AnkiValidators.validateRequest(overCallback)
        }
    }

    @Test
    fun `Jackson permissive extensions remain disabled`() {
        val valid = envelope(AnkiOperation.RELEASE_RUN_STATE, releasePayload())
        val malformed =
            listOf(
                "single quotes" to valid.replace('"', '\''),
                "unquoted key" to valid.replaceFirst("\"schemaVersion\"", "schemaVersion"),
                "JavaScript comment" to valid.replaceFirst(",\"type\"", ",/*x*/\"type\""),
                "YAML comment" to valid.replaceFirst(",\"type\"", ",# x\n\"type\""),
                "trailing comma" to valid.replaceFirst("true}}", "true,}}"),
                "missing array value" to verifyPayload(requiredFields = listOf("E"))
                    .replace("[\"E\"]", "[,\"E\"]")
                    .let { envelope(AnkiOperation.VERIFY_TARGET, it) },
                "leading plus" to valid.replace("\"schemaVersion\":1", "\"schemaVersion\":+1"),
                "leading zero" to valid.replace("\"schemaVersion\":1", "\"schemaVersion\":01"),
                "leading decimal point" to valid.replace("\"schemaVersion\":1", "\"schemaVersion\":.1"),
                "trailing decimal point" to valid.replace("\"schemaVersion\":1", "\"schemaVersion\":1."),
                "NaN literal" to valid.replace("\"schemaVersion\":1", "\"schemaVersion\":NaN"),
                "Infinity literal" to valid.replace("\"schemaVersion\":1", "\"schemaVersion\":Infinity"),
                "invalid escape" to valid.replace(RUN_ID, "run_00000000000000000000000000000\\x"),
                "unescaped control" to valid.replace(RUN_ID, "run_00000000000000000000000000000\u001f"),
            )
        for ((name, raw) in malformed) {
            assertCategory(name, AnkiProtocolCategory.INVALID_JSON) {
                AnkiJsonCodec.decodeRequest(raw, operationFor(raw))
            }
        }
    }

    @Test
    fun `missing unknown and duplicate keys fail at every request nesting family`() {
        val verify = verifyPayload()
        val known = knownPayload()
        val store = storePayload(listOf(cardAssetJson()))
        val create = createPayload(listOf(createNoteJson(mediaBindings = listOf(bindingJson()))))
        val release = releasePayload()
        val cases =
            listOf(
                RejectionCase(
                    "verify payload missing",
                    AnkiOperation.VERIFY_TARGET,
                    verify.replace(",\"modelName\":\"Mining\"", ""),
                    AnkiProtocolCategory.INVALID_PAYLOAD,
                ),
                RejectionCase(
                    "verify payload unknown",
                    AnkiOperation.VERIFY_TARGET,
                    verify.replaceFirst("{", "{\"extra\":0,"),
                    AnkiProtocolCategory.INVALID_PAYLOAD,
                ),
                RejectionCase(
                    "scan scope duplicate",
                    AnkiOperation.SCAN_FIRST_FIELDS,
                    known.replace("\"kind\":\"knownVocabulary\"", "\"kind\":\"knownVocabulary\",\"kind\":\"knownVocabulary\""),
                    AnkiProtocolCategory.DUPLICATE_JSON_KEY,
                ),
                RejectionCase(
                    "cursor unknown",
                    AnkiOperation.SCAN_FIRST_FIELDS,
                    known.replace("\"ordinal\":1", "\"ordinal\":1,\"extra\":0"),
                    AnkiProtocolCategory.INVALID_PAYLOAD,
                ),
                RejectionCase(
                    "cursor missing",
                    AnkiOperation.SCAN_FIRST_FIELDS,
                    known.replace("\"ordinal\":1,", ""),
                    AnkiProtocolCategory.INVALID_PAYLOAD,
                ),
                RejectionCase(
                    "scan limits unknown",
                    AnkiOperation.SCAN_FIRST_FIELDS,
                    known.replace("\"maxItems\":256", "\"extra\":0,\"maxItems\":256"),
                    AnkiProtocolCategory.LIMIT_MISMATCH,
                ),
                RejectionCase(
                    "asset duplicate",
                    AnkiOperation.STORE_MEDIA,
                    store.replace("\"mediaKind\":\"audio\"", "\"mediaKind\":\"audio\",\"mediaKind\":\"audio\""),
                    AnkiProtocolCategory.DUPLICATE_JSON_KEY,
                ),
                RejectionCase(
                    "asset unknown",
                    AnkiOperation.STORE_MEDIA,
                    store.replace("\"purpose\":\"card\"", "\"purpose\":\"card\",\"extra\":0"),
                    AnkiProtocolCategory.INVALID_PAYLOAD,
                ),
                RejectionCase(
                    "asset missing",
                    AnkiOperation.STORE_MEDIA,
                    store.replace(",\"expectedSha256\":\"${"0".repeat(64)}\"", ""),
                    AnkiProtocolCategory.INVALID_PAYLOAD,
                ),
                RejectionCase(
                    "store limits duplicate",
                    AnkiOperation.STORE_MEDIA,
                    store.replace("\"maxAssets\":50", "\"maxAssets\":50,\"maxAssets\":50"),
                    AnkiProtocolCategory.DUPLICATE_JSON_KEY,
                ),
                RejectionCase(
                    "note missing",
                    AnkiOperation.CREATE_NOTES,
                    create.replace(",\"tags\":[]", ""),
                    AnkiProtocolCategory.INVALID_PAYLOAD,
                ),
                RejectionCase(
                    "note unknown",
                    AnkiOperation.CREATE_NOTES,
                    create.replace("\"tags\":[]", "\"tags\":[],\"extra\":0"),
                    AnkiProtocolCategory.INVALID_PAYLOAD,
                ),
                RejectionCase(
                    "field map decoded duplicate",
                    AnkiOperation.CREATE_NOTES,
                    create.replace("{\"Expression\":\"value\"}", "{\"Expression\":\"value\",\"\\u0045xpression\":\"other\"}"),
                    AnkiProtocolCategory.DUPLICATE_JSON_KEY,
                ),
                RejectionCase(
                    "duplicate candidate missing",
                    AnkiOperation.CREATE_NOTES,
                    create.replace(",\"firstField\":\"value\"", ""),
                    AnkiProtocolCategory.INVALID_PAYLOAD,
                ),
                RejectionCase(
                    "media binding duplicate",
                    AnkiOperation.CREATE_NOTES,
                    create.replace("\"actualFilename\":\"clip.opus\"", "\"actualFilename\":\"clip.opus\",\"actualFilename\":\"clip.opus\""),
                    AnkiProtocolCategory.DUPLICATE_JSON_KEY,
                ),
                RejectionCase(
                    "duplicate scope unknown",
                    AnkiOperation.CREATE_NOTES,
                    create.replace("\"kind\":\"collection\"", "\"kind\":\"collection\",\"deckName\":\"Mining\""),
                    AnkiProtocolCategory.INVALID_PAYLOAD,
                ),
                RejectionCase(
                    "create limits missing",
                    AnkiOperation.CREATE_NOTES,
                    create.replace(",\"maxCardsPerNote\":64", ""),
                    AnkiProtocolCategory.LIMIT_MISMATCH,
                ),
                RejectionCase(
                    "release duplicate",
                    AnkiOperation.RELEASE_RUN_STATE,
                    release.replace("\"acknowledgeTerminalResponses\":true", "\"acknowledgeTerminalResponses\":true,\"acknowledgeTerminalResponses\":false"),
                    AnkiProtocolCategory.DUPLICATE_JSON_KEY,
                ),
                RejectionCase(
                    "release missing",
                    AnkiOperation.RELEASE_RUN_STATE,
                    release.replace(",\"acknowledgeTerminalResponses\":true", ""),
                    AnkiProtocolCategory.INVALID_PAYLOAD,
                ),
                RejectionCase(
                    "release unknown",
                    AnkiOperation.RELEASE_RUN_STATE,
                    release.replaceFirst("{", "{\"extra\":0,"),
                    AnkiProtocolCategory.INVALID_PAYLOAD,
                ),
            )
        for (case in cases) {
            assertCategory(case.name, case.category) {
                decode(case.operation, case.payload)
            }
        }

        val escapedEnvelopeDuplicate =
            envelope(AnkiOperation.RELEASE_RUN_STATE, release)
                .replace("\"type\":", "\"type\":\"${AnkiOperation.RELEASE_RUN_STATE.requestType}\",\"\\u0074ype\":")
        assertCategory("decoded envelope duplicate", AnkiProtocolCategory.DUPLICATE_JSON_KEY) {
            AnkiJsonCodec.decodeRequest(
                escapedEnvelopeDuplicate,
                AnkiOperation.RELEASE_RUN_STATE,
            )
        }
    }

    @Test
    fun `decoded surrogate keys and values never cross the typed boundary`() {
        val surrogateCases =
            listOf(
                SurrogateCase(
                    "field value",
                    AnkiOperation.CREATE_NOTES,
                    createPayload(
                        listOf(createNoteJson(fieldsJson = "{\"Expression\":\"\\udfff\"}")),
                    ),
                ),
                SurrogateCase(
                    "array value",
                    AnkiOperation.VERIFY_TARGET,
                    verifyPayload(requiredFieldsJson = "[\"\\ud800\"]"),
                ),
                SurrogateCase(
                    "payload key",
                    AnkiOperation.RELEASE_RUN_STATE,
                    releasePayload().replaceFirst("{", "{\"\\ud800\":0,"),
                ),
                SurrogateCase(
                    "field key",
                    AnkiOperation.CREATE_NOTES,
                    createPayload(
                        listOf(createNoteJson(fieldsJson = "{\"\\ud800\":\"value\"}")),
                    ),
                ),
            )
        for (case in surrogateCases) {
            assertCategory(case.name, AnkiProtocolCategory.INVALID_UTF8) {
                decode(case.operation, case.payload)
            }
        }

        val paired =
            createPayload(
                listOf(createNoteJson(fieldsJson = "{\"\\ud83d\\ude00\":\"\\ud83d\\ude00\"}")),
            )
        decode(AnkiOperation.CREATE_NOTES, paired)
    }

    @Test
    fun `signed-64 integer fields handle exact integer and double edge spellings`() {
        val acceptedSizes =
            listOf(
                "0",
                "-0",
                "0.0",
                "-0.0",
                "1e0",
                "1.000e+0",
                "1e-1000",
                "67108864.000",
            )
        for (literal in acceptedSizes) {
            val request =
                decode(
                    AnkiOperation.STORE_MEDIA,
                    storePayload(listOf(cardAssetJson(expectedSize = literal))),
                ) as StoreMediaRequest
            val expected = if (literal == "67108864.000") 67_108_864L else if (literal.startsWith("1") && literal != "1e-1000") 1L else 0L
            assertEquals(literal, expected, request.assets.single().expectedSizeBytes)
        }

        val rejected =
            listOf(
                NumberCase("integer maximum plus one", "9223372036854775808", AnkiProtocolCategory.INTEGER_OUT_OF_RANGE),
                NumberCase("integer minimum minus one", "-9223372036854775809", AnkiProtocolCategory.INTEGER_OUT_OF_RANGE),
                NumberCase("fraction", "1.5", AnkiProtocolCategory.INVALID_VALUE),
                NumberCase("positive double boundary", "9.223372036854776e18", AnkiProtocolCategory.INVALID_VALUE),
                NumberCase("rounded long maximum double", "9223372036854775807.0", AnkiProtocolCategory.INVALID_VALUE),
                NumberCase("finite asset overflow", "6.7108865e7", AnkiProtocolCategory.INVALID_VALUE),
                NumberCase("positive exponent overflow", "1e309", AnkiProtocolCategory.NON_FINITE_NUMBER),
                NumberCase("negative exponent overflow", "-1e309", AnkiProtocolCategory.NON_FINITE_NUMBER),
            )
        for (case in rejected) {
            assertCategory(case.name, case.category) {
                decode(
                    AnkiOperation.STORE_MEDIA,
                    storePayload(listOf(cardAssetJson(expectedSize = case.literal))),
                )
            }
        }

        val maximumCursor =
            decode(
                AnkiOperation.SCAN_FIRST_FIELDS,
                knownPayload(ordinal = Long.MAX_VALUE.toString()),
            ) as ScanFirstFieldsRequest
        assertEquals(Long.MAX_VALUE, (maximumCursor.scope as KnownVocabularyScope).cursor?.ordinal)
        assertCategory("negative signed endpoint is parsed then constrained", AnkiProtocolCategory.INVALID_VALUE) {
            decode(
                AnkiOperation.SCAN_FIRST_FIELDS,
                knownPayload(ordinal = Long.MIN_VALUE.toString()),
            )
        }
    }

    @Test
    fun `numeric token ceiling ignores strings and handles signs fractions and exponents exactly`() {
        val exactTokens =
            listOf(
                "1" + "0".repeat(999),
                "-" + "1" + "0".repeat(998),
                "1." + "0".repeat(998),
                "1e+" + "0".repeat(997),
            )
        for ((index, literal) in exactTokens.withIndex()) {
            assertEquals(AnkiLimitsV1.Wire.NUMERIC_TOKEN_MAX_CHARS, literal.length)
            val raw =
                envelope(
                    AnkiOperation.RELEASE_RUN_STATE,
                    releasePayload(),
                    version = literal,
                )
            if (index < 2) {
                assertCategory(
                    "exact token $index reaches semantics",
                    AnkiProtocolCategory.INTEGER_OUT_OF_RANGE,
                ) {
                    AnkiJsonCodec.decodeRequest(raw, AnkiOperation.RELEASE_RUN_STATE)
                }
            } else {
                AnkiJsonCodec.decodeRequest(raw, AnkiOperation.RELEASE_RUN_STATE)
            }
            val over = literal + "0"
            assertCategory("token N+1 $index", AnkiProtocolCategory.NUMERIC_TOKEN_TOO_LONG) {
                AnkiJsonCodec.decodeRequest(
                    envelope(
                        AnkiOperation.RELEASE_RUN_STATE,
                        releasePayload(),
                        version = over,
                    ),
                    AnkiOperation.RELEASE_RUN_STATE,
                )
            }
        }

        val quoted = "1e+" + "0".repeat(2_000)
        decode(
            AnkiOperation.CREATE_NOTES,
            createPayload(listOf(createNoteJson(fields = mapOf("Expression" to quoted)))),
        )
    }

    @Test
    fun `card and dictionary preferred names follow distinct request rules`() {
        val cardCases =
            listOf(
                Triple("a.opus", "a_", true),
                Triple("a b.opus", "a_b", true),
                Triple("archive.tar.gz", "archive.tar", true),
                Triple("[unsafe].opus", "[unsafe]", false),
                Triple(" e.opus", "_e", false),
                Triple("e\u0301.opus", "e\u0301", false),
            )
        for ((filename, preferred, accepted) in cardCases) {
            val raw =
                storePayload(
                    listOf(
                        cardAssetJson(
                            filename = filename,
                            preferredName = preferred,
                        ),
                    ),
                )
            assertDecodeOutcome("card $filename", accepted, raw, AnkiOperation.STORE_MEDIA)
        }

        val dictionaryFilename = " quote [辞書] e\u0301.webp "
        val digestAlias = dictionaryPreferred(dictionaryFilename)
        decode(
            AnkiOperation.STORE_MEDIA,
            storePayload(
                listOf(
                    dictionaryAssetJson(
                        filename = dictionaryFilename,
                        preferredName = digestAlias,
                    ),
                ),
            ),
        )
        assertCategory("dictionary alias mismatch", AnkiProtocolCategory.INVALID_VALUE) {
            decode(
                AnkiOperation.STORE_MEDIA,
                storePayload(
                    listOf(
                        dictionaryAssetJson(
                            filename = dictionaryFilename,
                            preferredName = "anki_miner_dict_${"0".repeat(64)}",
                        ),
                    ),
                ),
            )
        }
        for (unsafe in listOf("../dict.webp", "dir\\dict.webp", ".", "..", "bad\u0000.webp")) {
            assertCategory("dictionary basename $unsafe", AnkiProtocolCategory.INVALID_VALUE) {
                decode(
                    AnkiOperation.STORE_MEDIA,
                    storePayload(listOf(dictionaryAssetJson(filename = unsafe))),
                )
            }
        }
    }

    @Test
    fun `ASCII case-insensitive media markup is rejected in results and note bindings`() {
        val markup = listOf("[sound:x.opus]", "[SoUnD:x.opus]", "<img src=x>", "<ImG src=x>")
        for (value in markup) {
            assertCategory("stored $value", AnkiProtocolCategory.INVALID_VALUE) {
                encode(
                    StoreMediaResult(RUN_ID, REQUEST_ID, listOf(StoredMedia(ASSET_ID, value)), null),
                )
            }
            assertCategory("binding $value", AnkiProtocolCategory.INVALID_VALUE) {
                AnkiValidators.validateRequest(
                    directCreateRequest(
                        listOf(
                            directNote(
                                index = 0,
                                occurrence = 0,
                                mediaBindings = listOf(MediaBinding(ASSET_ID, value)),
                            ),
                        ),
                    ),
                )
            }
        }
    }

    @Test
    fun `request-aware response checks IDs shapes alignment and provider filename relations`() {
        val verify = VerifyTargetRequest(RUN_ID, REQUEST_ID, "Mining", "Mining", listOf("Expression"))
        val verifyResult = VerifyTargetResult(RUN_ID, REQUEST_ID, 1, 2, listOf("Expression"), false)
        AnkiJsonCodec.encodeResponse(verifyResult, verify)
        val mismatches =
            listOf(
                "run" to verifyResult.copy(runId = RUN_ID_2),
                "request" to verifyResult.copy(requestId = REQUEST_ID_2),
            )
        for ((name, response) in mismatches) {
            assertCategory(name, AnkiProtocolCategory.INVALID_VALUE) {
                AnkiJsonCodec.encodeResponse(response, verify)
            }
        }
        assertCategory("operation", AnkiProtocolCategory.INVALID_VALUE) {
            AnkiJsonCodec.encodeResponse(
                ReleaseRunStateResult(RUN_ID, REQUEST_ID, ReleaseState.RELEASED),
                verify,
            )
        }
        AnkiJsonCodec.encodeResponse(
            AnkiErrorResult(
                RUN_ID,
                REQUEST_ID,
                AnkiOperation.VERIFY_TARGET,
                AnkiErrorCode.PERMISSION_REQUIRED,
                "permission",
                true,
            ),
            verify,
        )

        val known = ScanFirstFieldsRequest(RUN_ID, REQUEST_ID, KnownVocabularyScope(emptyList(), null))
        AnkiJsonCodec.encodeResponse(KnownVocabularyResult(RUN_ID, REQUEST_ID, emptyList(), 0, null), known)
        assertCategory("known response union", AnkiProtocolCategory.INVALID_VALUE) {
            AnkiJsonCodec.encodeResponse(
                DuplicateLookupResult(RUN_ID, REQUEST_ID, listOf(emptyList()), BASELINE_TOKEN),
                known,
            )
        }

        val duplicate =
            ScanFirstFieldsRequest(
                RUN_ID,
                REQUEST_ID,
                DuplicateScanScope(
                    "Mining",
                    "Expression",
                    listOf(DuplicateCandidate("a", "a"), DuplicateCandidate("b", "b")),
                    listOf(0, 1),
                    null,
                ),
            )
        assertCategory("duplicate bucket count", AnkiProtocolCategory.INVALID_VALUE) {
            AnkiJsonCodec.encodeResponse(
                DuplicateLookupResult(RUN_ID, REQUEST_ID, listOf(emptyList()), BASELINE_TOKEN),
                duplicate,
            )
        }

        val card = cardAsset()
        val mediaRequest = StoreMediaRequest(RUN_ID, REQUEST_ID, listOf(card, cardAsset(index = 1)))
        val aligned =
            listOf(
                StoredMedia(card.assetId, card.requestedFilename),
                StoredMedia(assetId(1), "clip1_provider.opus"),
            )
        AnkiJsonCodec.encodeResponse(StoreMediaResult(RUN_ID, REQUEST_ID, aligned, null), mediaRequest)
        assertCategory("media order", AnkiProtocolCategory.INVALID_VALUE) {
            AnkiJsonCodec.encodeResponse(
                StoreMediaResult(RUN_ID, REQUEST_ID, aligned.reversed(), null),
                mediaRequest,
            )
        }
        for (filename in listOf("unrelated.opus", "clip0_suffix", "clip0suffix.opus")) {
            assertCategory("provider relation $filename", AnkiProtocolCategory.INVALID_VALUE) {
                AnkiJsonCodec.encodeResponse(
                    StoreMediaResult(
                        RUN_ID,
                        REQUEST_ID,
                        listOf(StoredMedia(card.assetId, filename)),
                        null,
                    ),
                    StoreMediaRequest(RUN_ID, REQUEST_ID, listOf(card)),
                )
            }
        }

        val dictionary = dictionaryAsset(filename = " quote [辞書].webp ")
        val dictionaryRequest = StoreMediaRequest(RUN_ID, REQUEST_ID, listOf(dictionary))
        for (filename in listOf(dictionary.requestedFilename, "${dictionary.preferredName}_provider.webp")) {
            AnkiJsonCodec.encodeResponse(
                StoreMediaResult(
                    RUN_ID,
                    REQUEST_ID,
                    listOf(StoredMedia(dictionary.assetId, filename)),
                    null,
                ),
                dictionaryRequest,
            )
        }

        val createRequest =
            directCreateRequest(
                listOf(
                    directNote(0, 0),
                    directNote(1, 1),
                ),
            )
        val noteRows = listOf(CreatedNote(noteId(0), 1), DuplicateNote(noteId(1)))
        AnkiJsonCodec.encodeResponse(CreateNotesResult(RUN_ID, REQUEST_ID, noteRows, null), createRequest)
        assertCategory("note order", AnkiProtocolCategory.INVALID_VALUE) {
            AnkiJsonCodec.encodeResponse(
                CreateNotesResult(RUN_ID, REQUEST_ID, noteRows.reversed(), null),
                createRequest,
            )
        }
    }

    @Test
    fun `response collections enforce zero exact maximum and maximum plus one`() {
        assertCategory("verify deckCreated true", AnkiProtocolCategory.INVALID_VALUE) {
            encode(VerifyTargetResult(RUN_ID, REQUEST_ID, 1, 2, listOf("Expression"), true))
        }
        assertCategory("verify zero fields", AnkiProtocolCategory.INVALID_VALUE) {
            encode(VerifyTargetResult(RUN_ID, REQUEST_ID, 1, 2, emptyList(), false))
        }
        val maximumFields = List(AnkiLimitsV1.Names.TargetFields.MAX_ITEM_COUNT) { "F$it" }
        encode(VerifyTargetResult(RUN_ID, REQUEST_ID, 1, 2, maximumFields, false))
        assertCategory("verify field N+1", AnkiProtocolCategory.INVALID_VALUE) {
            encode(VerifyTargetResult(RUN_ID, REQUEST_ID, 1, 2, maximumFields + "overflow", false))
        }

        encode(KnownVocabularyResult(RUN_ID, REQUEST_ID, emptyList(), 0, null))
        val maximumKnown = List(AnkiLimitsV1.ScanFirstFields.KNOWN_PAGE_MAX_ITEM_COUNT) { "" }
        encode(
            KnownVocabularyResult(
                RUN_ID,
                REQUEST_ID,
                maximumKnown,
                maximumKnown.size,
                null,
            ),
        )
        assertCategory("known first-field N+1", AnkiProtocolCategory.INVALID_VALUE) {
            val over = maximumKnown + ""
            encode(KnownVocabularyResult(RUN_ID, REQUEST_ID, over, over.size, null))
        }

        encode(
            DuplicateLookupResult(
                RUN_ID,
                REQUEST_ID,
                listOf(emptyList()),
                BASELINE_TOKEN,
            ),
        )
        val fullBucket =
            List(AnkiLimitsV1.ScanFirstFields.DUPLICATE_HIT_PER_CANDIDATE_MAX_ITEM_COUNT) {
                RawFirstFieldHit(it.toLong() + 1, "")
            }
        encode(DuplicateLookupResult(RUN_ID, REQUEST_ID, listOf(fullBucket), BASELINE_TOKEN))
        assertCategory("duplicate hit bucket N+1", AnkiProtocolCategory.INVALID_VALUE) {
            encode(
                DuplicateLookupResult(
                    RUN_ID,
                    REQUEST_ID,
                    listOf(fullBucket + RawFirstFieldHit(101, "")),
                    BASELINE_TOKEN,
                ),
            )
        }
        val exactTotalHits = List(10) { fullBucket }
        encode(DuplicateLookupResult(RUN_ID, REQUEST_ID, exactTotalHits, BASELINE_TOKEN))
        assertCategory("duplicate total hit N+1", AnkiProtocolCategory.INVALID_VALUE) {
            encode(
                DuplicateLookupResult(
                    RUN_ID,
                    REQUEST_ID,
                    exactTotalHits + listOf(listOf(RawFirstFieldHit(1, ""))),
                    BASELINE_TOKEN,
                ),
            )
        }

        val localFailure =
            AnkiErrorDetail(AnkiErrorCode.MEDIA_STORE_FAILED, "asset failed", true)
        assertCategory("media zero rows", AnkiProtocolCategory.INVALID_VALUE) {
            encode(StoreMediaResult(RUN_ID, REQUEST_ID, emptyList(), null))
        }
        val maximumMediaRows =
            List(AnkiLimitsV1.StoreMedia.MAX_ASSET_COUNT) { index ->
                FailedMedia(assetId(index), localFailure)
            }
        encode(StoreMediaResult(RUN_ID, REQUEST_ID, maximumMediaRows, null))
        assertCategory("media row N+1", AnkiProtocolCategory.INVALID_VALUE) {
            encode(
                StoreMediaResult(
                    RUN_ID,
                    REQUEST_ID,
                    maximumMediaRows + FailedMedia(assetId(51), localFailure),
                    null,
                ),
            )
        }

        assertCategory("note zero rows", AnkiProtocolCategory.INVALID_VALUE) {
            encode(CreateNotesResult(RUN_ID, REQUEST_ID, emptyList(), null))
        }
        val maximumNoteRows =
            List(AnkiLimitsV1.CreateNotes.MAX_NOTE_COUNT) { index ->
                DuplicateNote(noteId(index))
            }
        encode(CreateNotesResult(RUN_ID, REQUEST_ID, maximumNoteRows, null))
        assertCategory("note row N+1", AnkiProtocolCategory.INVALID_VALUE) {
            encode(
                CreateNotesResult(
                    RUN_ID,
                    REQUEST_ID,
                    maximumNoteRows + DuplicateNote(noteId(101)),
                    null,
                ),
            )
        }
    }

    @Test
    fun `response text aggregates and every output envelope use exact UTF-8 ceilings`() {
        val exactKnownBytes =
            List(4) { "x".repeat(AnkiLimitsV1.ScanFirstFields.FIRST_FIELD_MAX_UTF8_BYTES) }
        encode(
            KnownVocabularyResult(
                RUN_ID,
                REQUEST_ID,
                exactKnownBytes,
                exactKnownBytes.size,
                null,
            ),
        )
        assertCategory("known byte aggregate N+1", AnkiProtocolCategory.INVALID_VALUE) {
            val over = exactKnownBytes + "x"
            encode(KnownVocabularyResult(RUN_ID, REQUEST_ID, over, over.size, null))
        }

        val exactDuplicateBytes =
            List(16) { index ->
                RawFirstFieldHit(
                    index.toLong() + 1,
                    "x".repeat(AnkiLimitsV1.ScanFirstFields.FIRST_FIELD_MAX_UTF8_BYTES),
                )
            }
        encode(
            DuplicateLookupResult(
                RUN_ID,
                REQUEST_ID,
                listOf(exactDuplicateBytes),
                BASELINE_TOKEN,
            ),
        )
        assertCategory("duplicate byte aggregate N+1", AnkiProtocolCategory.INVALID_VALUE) {
            encode(
                DuplicateLookupResult(
                    RUN_ID,
                    REQUEST_ID,
                    listOf(exactDuplicateBytes + RawFirstFieldHit(17, "x")),
                    BASELINE_TOKEN,
                ),
            )
        }

        for (operation in AnkiOperation.entries) {
            val base =
                AnkiErrorResult(
                    RUN_ID,
                    REQUEST_ID,
                    operation,
                    AnkiErrorCode.INTERNAL_ERROR,
                    "x",
                    false,
                )
            val baseSize = encode(base).utf8Size()
            val padding = operation.resultEnvelopeMaxUtf8Bytes - baseSize
            assertTrue("${operation.wireName} output fixture exceeds limit", padding >= 0)
            val exact = base.copy(message = "x".repeat(padding + 1))
            assertEquals(
                operation.wireName,
                operation.resultEnvelopeMaxUtf8Bytes,
                encode(exact).utf8Size(),
            )
            assertCategory(
                "${operation.wireName} output N+1",
                AnkiProtocolCategory.OUTPUT_TOO_LARGE,
            ) {
                encode(exact.copy(message = exact.message + "x"))
            }
        }
    }

    @Test
    fun `media partial results enforce the temporal state machine`() {
        val localFailure = AnkiErrorDetail(AnkiErrorCode.MEDIA_STORE_FAILED, "asset", true)
        val retryableStop = AnkiErrorDetail(AnkiErrorCode.QUERY_FAILED, "stop", true)
        val fatalStop = AnkiErrorDetail(AnkiErrorCode.WRITE_FAILED, "stop", false)
        val cancellation = AnkiErrorDetail(AnkiErrorCode.CANCELLED, "cancelled", false)
        val retryableCancellation = AnkiErrorDetail(AnkiErrorCode.CANCELLED, "cancelled", true)
        val uncertainty =
            AnkiErrorDetail(AnkiErrorCode.POST_COMMIT_UNCERTAIN, "uncertain", false)
        val valid =
            listOf(
                MediaShape("stored", listOf(StoredMedia(ASSET_ID, "a.opus")), null),
                MediaShape("local failed then stored", listOf(FailedMedia(ASSET_ID, localFailure), StoredMedia(ASSET_ID_2, "b.opus")), null),
                MediaShape("retryable before write", listOf(NotAttemptedMedia(ASSET_ID)), retryableStop),
                MediaShape("known write then stop", listOf(StoredMedia(ASSET_ID, "a.opus"), NotAttemptedMedia(ASSET_ID_2)), fatalStop),
                MediaShape("known write then cancellation", listOf(StoredMedia(ASSET_ID, "a.opus"), NotAttemptedMedia(ASSET_ID_2)), cancellation),
                MediaShape("uncertain suffix", listOf(UncertainMedia(ASSET_ID), NotAttemptedMedia(ASSET_ID_2)), uncertainty),
            )
        valid.forEach { shape ->
            encode(
                StoreMediaResult(RUN_ID, REQUEST_ID, shape.rows, shape.error),
            )
        }

        val invalid =
            listOf(
                MediaShape("empty", emptyList(), null),
                MediaShape("stored after suffix", listOf(NotAttemptedMedia(ASSET_ID), StoredMedia(ASSET_ID_2, "b.opus")), fatalStop),
                MediaShape("failed after uncertainty", listOf(UncertainMedia(ASSET_ID), FailedMedia(ASSET_ID_2, localFailure)), uncertainty),
                MediaShape("terminal without error", listOf(NotAttemptedMedia(ASSET_ID)), null),
                MediaShape("error without terminal", listOf(StoredMedia(ASSET_ID, "a.opus")), fatalStop),
                MediaShape("retryable after write", listOf(StoredMedia(ASSET_ID, "a.opus"), NotAttemptedMedia(ASSET_ID_2)), retryableStop),
                MediaShape("uncertain wrong error", listOf(UncertainMedia(ASSET_ID)), fatalStop),
                MediaShape("post-commit without uncertain", listOf(NotAttemptedMedia(ASSET_ID)), uncertainty),
                MediaShape("retryable cancellation", listOf(NotAttemptedMedia(ASSET_ID)), retryableCancellation),
                MediaShape("wrong local code", listOf(FailedMedia(ASSET_ID, fatalStop)), null),
            )
        invalid.forEach { shape ->
            assertCategory(shape.name, AnkiProtocolCategory.INVALID_VALUE) {
                encode(
                    StoreMediaResult(RUN_ID, REQUEST_ID, shape.rows, shape.error),
                )
            }
        }
    }

    @Test
    fun `note partial results enforce the temporal state machine`() {
        val retryableStop = AnkiErrorDetail(AnkiErrorCode.QUERY_FAILED, "stop", true)
        val fatalStop = AnkiErrorDetail(AnkiErrorCode.WRITE_FAILED, "stop", false)
        val cancelled = AnkiErrorDetail(AnkiErrorCode.CANCELLED, "cancelled", false)
        val retryableCancellation = AnkiErrorDetail(AnkiErrorCode.CANCELLED, "cancelled", true)
        val uncertainty =
            AnkiErrorDetail(AnkiErrorCode.POST_COMMIT_UNCERTAIN, "uncertain", false)
        val valid =
            listOf(
                NoteShape("created duplicate", listOf(CreatedNote(NOTE_ID, 1), DuplicateNote(NOTE_ID_2)), null),
                NoteShape("retryable before write", listOf(FailedNote(NOTE_ID), NotAttemptedNote(NOTE_ID_2)), retryableStop),
                NoteShape("known write then stop", listOf(CreatedNote(NOTE_ID, 1), FailedNote(NOTE_ID_2)), fatalStop),
                NoteShape("committed failure", listOf(CommittedFailedNote(NOTE_ID, 1), NotAttemptedNote(NOTE_ID_2)), fatalStop),
                NoteShape("known write then cancellation before next entry", listOf(CreatedNote(NOTE_ID, 1), FailedNote(NOTE_ID_2), NotAttemptedNote(NOTE_ID_3)), cancelled),
                NoteShape("committed uncertain failure", listOf(CommittedFailedNote(NOTE_ID, 1), NotAttemptedNote(NOTE_ID_2)), uncertainty),
                NoteShape("uncertain suffix", listOf(UncertainNote(NOTE_ID), NotAttemptedNote(NOTE_ID_2)), uncertainty),
            )
        valid.forEach { shape ->
            encode(
                CreateNotesResult(RUN_ID, REQUEST_ID, shape.rows, shape.error),
            )
        }

        val invalid =
            listOf(
                NoteShape("empty", emptyList(), null),
                NoteShape("created after failed", listOf(FailedNote(NOTE_ID), CreatedNote(NOTE_ID_2, 2)), fatalStop),
                NoteShape("duplicate after suffix", listOf(NotAttemptedNote(NOTE_ID), DuplicateNote(NOTE_ID_2)), retryableStop),
                NoteShape("second terminal", listOf(UncertainNote(NOTE_ID), FailedNote(NOTE_ID_2)), uncertainty),
                NoteShape("terminal without error", listOf(FailedNote(NOTE_ID)), null),
                NoteShape("error without terminal", listOf(DuplicateNote(NOTE_ID)), fatalStop),
                NoteShape("suffix without carrier", listOf(CreatedNote(NOTE_ID, 1), NotAttemptedNote(NOTE_ID_2)), cancelled),
                NoteShape("retryable after create", listOf(CreatedNote(NOTE_ID, 1), FailedNote(NOTE_ID_2)), retryableStop),
                NoteShape("retryable cancellation", listOf(FailedNote(NOTE_ID)), retryableCancellation),
                NoteShape("uncertain wrong error", listOf(UncertainNote(NOTE_ID)), fatalStop),
                NoteShape("post-commit without uncertain", listOf(FailedNote(NOTE_ID)), uncertainty),
                NoteShape("committed cancellation", listOf(CommittedFailedNote(NOTE_ID, 1)), cancelled),
            )
        invalid.forEach { shape ->
            assertCategory(shape.name, AnkiProtocolCategory.INVALID_VALUE) {
                encode(
                    CreateNotesResult(RUN_ID, REQUEST_ID, shape.rows, shape.error),
                )
            }
        }
    }

    @Test
    fun `provider template limits enforce every leaf template count and aggregate bytes`() {
        val maximum = AnkiLimitsV1.TargetModel.CSS_MAX_UTF8_BYTES
        val exact = "😀".repeat(maximum / 4)
        assertEquals(maximum, exact.utf8Size())
        val over = "$exact!"
        val leafCases =
            listOf<(String) -> Unit>(
                { value -> providerText(css = value) },
                { value -> providerText(latexPre = value) },
                { value -> providerText(latexPost = value) },
                { value -> providerText(template = template(question = value)) },
                { value -> providerText(template = template(answer = value)) },
                { value -> providerText(template = template(browserQuestion = value)) },
                { value -> providerText(template = template(browserAnswer = value)) },
            )
        for ((index, invoke) in leafCases.withIndex()) {
            invoke(exact)
            assertCategory("template leaf $index N+1", AnkiProtocolCategory.INVALID_VALUE) {
                invoke(over)
            }
        }

        assertCategory("zero templates", AnkiProtocolCategory.INVALID_VALUE) {
            AnkiValidators.validateProviderTextSnapshot("", null, null, emptyList())
        }
        AnkiValidators.validateProviderTextSnapshot(
            "",
            null,
            null,
            List(AnkiLimitsV1.TargetModel.MAX_TEMPLATE_COUNT) { template() },
        )
        assertCategory("template count N+1", AnkiProtocolCategory.INVALID_VALUE) {
            AnkiValidators.validateProviderTextSnapshot(
                "",
                null,
                null,
                List(AnkiLimitsV1.TargetModel.MAX_TEMPLATE_COUNT + 1) { template() },
            )
        }

        val exactAggregate = List(4) { template(exact, exact, exact, exact) }
        AnkiValidators.validateProviderTextSnapshot("", null, null, exactAggregate)
        assertEquals(
            AnkiLimitsV1.TargetModel.PROVIDER_TEXT_TOTAL_MAX_UTF8_BYTES,
            16 * exact.utf8Size(),
        )
        assertCategory("provider aggregate N+1", AnkiProtocolCategory.INVALID_VALUE) {
            AnkiValidators.validateProviderTextSnapshot(
                "",
                null,
                null,
                exactAggregate + template(question = "!"),
            )
        }
    }

    @Test
    fun `shared request corpus decodes to the same typed semantics and categories`() {
        val vectors = corpusVectors().filter { it.direction == "request" }
        assertTrue("request corpus must not be empty", vectors.isNotEmpty())
        for (vector in vectors) {
            val operation = operation(vector.callback)
            val raw = renderCorpusInput(vector.input)
            if (vector.expect.string("outcome") == "accept") {
                val actual = AnkiJsonCodec.decodeRequest(raw, operation)
                val expected = requestFromCorpus(operation, vector.expect.obj("payload"))
                assertEquals(vector.id, expected, actual)
                assertEquals(vector.id, operation.requestType, vector.expect.string("messageType"))
            } else {
                val error =
                    assertThrows(vector.id, AnkiProtocolException::class.java) {
                        AnkiJsonCodec.decodeRequest(raw, operation)
                    }
                assertEquals(
                    vector.id,
                    vector.expect.string("category"),
                    corpusCategory(vector.id, error.category),
                )
            }
        }
    }

    @Test
    fun `shared accepted response corpus builds typed results with exact canonical JSON`() {
        val vectors =
            corpusVectors().filter {
                it.direction == "response" && it.expect.string("outcome") == "accept"
            }
        assertTrue("accepted response corpus must not be empty", vectors.isNotEmpty())
        for (vector in vectors) {
            val response = responseFromCorpus(vector.expect.obj("payload"), vector.expect.string("messageType"))
            assertEquals(vector.id, vector.expect.string("canonical"), encode(response))
        }
    }

    @Test
    fun `shared rejected response corpus exercises every parser validator and output category`() {
        val vectors =
            corpusVectors().filter {
                it.direction == "response" && it.expect.string("outcome") == "reject"
            }
        assertTrue("rejected response corpus must not be empty", vectors.isNotEmpty())

        var parsedResponseCount = 0
        for (vector in vectors) {
            val actualCategory =
                try {
                    val response =
                        rejectedResponseFromCorpus(
                            renderCorpusInput(vector.input),
                            operation(vector.callback),
                        )
                    parsedResponseCount += 1
                    val error =
                        assertThrows(vector.id, AnkiProtocolException::class.java) {
                            encode(response)
                        }
                    corpusCategory(vector.id, error.category)
                } catch (error: CorpusResponseParseException) {
                    corpusCategory(vector.id, error.category)
                }
            assertEquals(vector.id, vector.expect.string("category"), actualCategory)
        }

        assertTrue(
            "the reject corpus must exercise typed production response validation",
            parsedResponseCount > 0,
        )
    }

    @Test
    fun `codec validation sources never use host Unicode or trim semantics`() {
        val root = projectRoot()
        val sources =
            listOf(
                "app/src/main/kotlin/com/ankiminer/android/anki/protocol/AnkiJsonCodec.kt",
                "app/src/main/kotlin/com/ankiminer/android/anki/protocol/AnkiValidators.kt",
            )
        val forbidden =
            listOf(
                "java.lang.Character",
                "java.text.Normalizer",
                "Character.getType",
                "Character.isWhitespace",
                "Normalizer.normalize",
                ".trim(",
                ".trimStart(",
                ".trimEnd(",
            )
        for (relative in sources) {
            val source = File(root, relative).readText()
            for (needle in forbidden) {
                assertTrue("$relative must not contain $needle", needle !in source)
            }
        }
    }

    private fun corpusVectors(): List<CorpusVector> {
        val corpus = File(projectRoot(), "golden/bridge/anki-protocol-v1.jsonl")
        check(corpus.isFile) { "shared Anki protocol corpus is missing" }
        return corpus.readLines(StandardCharsets.UTF_8)
            .filter(String::isNotEmpty)
            .mapIndexed { index, line ->
                val row = parseJsonObject(line, "corpus line ${index + 1}")
                CorpusVector(
                    id = row.string("id"),
                    callback = row.string("callback"),
                    direction = row.string("direction"),
                    input = row.obj("input"),
                    expect = row.obj("expect"),
                )
            }
    }

    private fun renderCorpusInput(input: JsonObject): String {
        input["raw"]?.let { return it.asString("corpus raw input") }
        return input.array("concat").joinToString(separator = "") { part ->
            when (part) {
                is String -> part
                is Map<*, *> -> {
                    val component = part.asObject("concat component")
                    when {
                        "repeat" in component -> {
                            val repeat = component.obj("repeat")
                            repeat.string("text").repeat(repeat.long("count").toInt())
                        }
                        "utf16CodeUnits" in component ->
                            buildString {
                                for (unit in component.array("utf16CodeUnits")) {
                                    append(unit.asLong("UTF-16 code unit").toInt().toChar())
                                }
                            }
                        else -> error("unsupported corpus concat object")
                    }
                }
                else -> error("unsupported corpus concat component")
            }
        }
    }

    private fun requestFromCorpus(
        operation: AnkiOperation,
        payload: JsonObject,
    ): AnkiRequest {
        val runId = payload.string("runId")
        val requestId = payload.string("requestId")
        return when (operation) {
            AnkiOperation.VERIFY_TARGET ->
                VerifyTargetRequest(
                    runId,
                    requestId,
                    payload.string("deckName"),
                    payload.string("modelName"),
                    payload.strings("requiredFields"),
                )
            AnkiOperation.SCAN_FIRST_FIELDS -> {
                val scope = payload.obj("scope")
                val typedScope =
                    when (scope.string("kind")) {
                        "knownVocabulary" -> {
                            val cursor =
                                scope["cursor"]?.let { rawCursor ->
                                    val value = rawCursor.asObject("known cursor")
                                    KnownVocabularyCursor(
                                        value.long("ordinal"),
                                        value.string("token"),
                                    )
                                }
                            KnownVocabularyScope(scope.strings("excludedDecks"), cursor)
                        }
                        "duplicates" ->
                            DuplicateScanScope(
                                scope.string("modelName"),
                                scope.string("firstFieldName"),
                                scope.array("candidates").map { rawCandidate ->
                                    val candidate = rawCandidate.asObject("duplicate candidate")
                                    DuplicateCandidate(
                                        candidate.string("key"),
                                        candidate.string("firstField"),
                                    )
                                },
                                scope.array("occurrences").map { it.asLong("occurrence").toInt() },
                                scope.nullableString("invalidateBaselineToken"),
                            )
                        else -> error("unsupported accepted scan scope")
                    }
                ScanFirstFieldsRequest(runId, requestId, typedScope)
            }
            AnkiOperation.STORE_MEDIA ->
                StoreMediaRequest(
                    runId,
                    requestId,
                    payload.array("assets").map { rawAsset ->
                        val asset = rawAsset.asObject("media asset")
                        MediaAsset(
                            asset.string("assetId"),
                            asset.string("sourcePath"),
                            asset.string("preferredName"),
                            asset.string("requestedFilename"),
                            MediaPurpose.entries.single { it.wireName == asset.string("purpose") },
                            MediaKind.entries.single { it.wireName == asset.string("mediaKind") },
                            asset.long("expectedSizeBytes"),
                            asset.string("expectedSha256"),
                        )
                    },
                )
            AnkiOperation.CREATE_NOTES -> {
                val scope = payload.obj("duplicateScope")
                val typedScope =
                    when (scope.string("kind")) {
                        "collection" -> CollectionCreateDuplicateScope
                        else -> error("unsupported accepted create scope")
                    }
                CreateNotesRequest(
                    runId,
                    requestId,
                    payload.string("deckName"),
                    payload.string("modelName"),
                    payload.string("firstFieldName"),
                    payload.string("baselineToken"),
                    typedScope,
                    payload.array("notes").map { rawNote ->
                        val note = rawNote.asObject("create note")
                        val duplicate = note.obj("duplicateCandidate")
                        CreateNote(
                            note.string("clientNoteId"),
                            note.obj("fields").mapValues { (_, value) ->
                                value.asString("note field value")
                            },
                            note.strings("tags"),
                            CreateDuplicateCandidate(
                                duplicate.string("key"),
                                duplicate.string("firstField"),
                                duplicate.long("occurrence").toInt(),
                            ),
                            note.array("mediaBindings").map { rawBinding ->
                                val media = rawBinding.asObject("media binding")
                                MediaBinding(
                                    media.string("assetId"),
                                    media.string("actualFilename"),
                                )
                            },
                        )
                    },
                )
            }
            AnkiOperation.RELEASE_RUN_STATE ->
                ReleaseRunStateRequest(
                    runId,
                    requestId,
                    payload.boolean("acknowledgeTerminalResponses"),
                )
        }
    }

    private fun responseFromCorpus(
        payload: JsonObject,
        messageType: String,
    ): AnkiResponse {
        val runId = payload.string("runId")
        val requestId = payload.string("requestId")
        if (messageType == "anki.error") {
            return AnkiErrorResult(
                runId,
                requestId,
                operation(payload.string("operation")),
                errorCode(payload.string("code")),
                payload.string("message"),
                payload.boolean("retryable"),
            )
        }
        return when (messageType) {
            AnkiOperation.VERIFY_TARGET.resultType ->
                VerifyTargetResult(
                    runId,
                    requestId,
                    payload.long("deckId"),
                    payload.long("modelId"),
                    payload.strings("fieldNames"),
                    payload.boolean("deckCreated"),
                )
            AnkiOperation.SCAN_FIRST_FIELDS.resultType ->
                if ("firstFields" in payload) {
                    KnownVocabularyResult(
                        runId,
                        requestId,
                        payload.strings("firstFields"),
                        payload.long("scannedNotes").toInt(),
                        payload["nextCursor"]?.let { rawCursor ->
                            val cursor = rawCursor.asObject("next cursor")
                            KnownVocabularyCursor(
                                cursor.long("ordinal"),
                                cursor.string("token"),
                            )
                        },
                    )
                } else {
                    DuplicateLookupResult(
                        runId,
                        requestId,
                        payload.array("rawFirstFieldHits").map { rawBucket ->
                            rawBucket.asArray("duplicate hit bucket").map { rawHit ->
                                val hit = rawHit.asObject("duplicate hit")
                                RawFirstFieldHit(
                                    hit.long("noteId"),
                                    hit.string("firstField"),
                                )
                            }
                        },
                        payload.string("baselineToken"),
                    )
                }
            AnkiOperation.STORE_MEDIA.resultType ->
                StoreMediaResult(
                    runId,
                    requestId,
                    payload.array("results").map { rawRow -> mediaRow(rawRow.asObject("media row")) },
                    payload["error"]?.let { errorDetail(it.asObject("media top-level error")) },
                )
            AnkiOperation.CREATE_NOTES.resultType ->
                CreateNotesResult(
                    runId,
                    requestId,
                    payload.array("results").map { rawRow -> noteRow(rawRow.asObject("note row")) },
                    payload["error"]?.let { errorDetail(it.asObject("note top-level error")) },
                )
            AnkiOperation.RELEASE_RUN_STATE.resultType ->
                ReleaseRunStateResult(
                    runId,
                    requestId,
                    ReleaseState.entries.single { it.wireName == payload.string("state") },
                )
            else -> error("unsupported accepted corpus response type: $messageType")
        }
    }

    /**
     * The production bridge only decodes requests: responses originate as typed Kotlin values.
     * This strict test-side reader covers malformed response JSON, then hands every representable
     * response to the real request-aware encoder and validators.
     */
    private fun rejectedResponseFromCorpus(
        raw: String,
        expectedOperation: AnkiOperation,
    ): AnkiResponse {
        val envelope = parseRejectedResponseObject(raw)
        requireCorpusKeys(
            envelope,
            setOf("schemaVersion", "type", "payload"),
            AnkiProtocolCategory.INVALID_ENVELOPE,
            "response envelope",
        )
        val schemaVersion =
            corpusLong(envelope["schemaVersion"], "schemaVersion", AnkiProtocolCategory.INVALID_ENVELOPE)
        if (schemaVersion != AnkiLimitsV1.SCHEMA_VERSION.toLong()) {
            throw CorpusResponseParseException(
                AnkiProtocolCategory.UNSUPPORTED_SCHEMA_VERSION,
                "unsupported response schema version",
            )
        }
        val messageType =
            corpusString(envelope["type"], "type", AnkiProtocolCategory.INVALID_ENVELOPE)
        if (messageType != expectedOperation.resultType && messageType != "anki.error") {
            throw CorpusResponseParseException(
                AnkiProtocolCategory.UNEXPECTED_MESSAGE_TYPE,
                "response type does not match its callback",
            )
        }
        val payload =
            corpusObject(envelope["payload"], "payload", AnkiProtocolCategory.INVALID_PAYLOAD)

        try {
            validateRejectedResponseShape(messageType, payload)
            return responseFromCorpus(payload, messageType).also { response ->
                if (response.operation != expectedOperation) {
                    throw CorpusResponseParseException(
                        AnkiProtocolCategory.INVALID_PAYLOAD,
                        "error operation does not match its callback",
                    )
                }
            }
        } catch (error: CorpusResponseParseException) {
            throw error
        } catch (error: RuntimeException) {
            throw CorpusResponseParseException(
                AnkiProtocolCategory.INVALID_PAYLOAD,
                "response payload cannot be represented by the typed Kotlin contract",
                error,
            )
        }
    }

    private fun parseRejectedResponseObject(raw: String): JsonObject {
        rejectOversizedCorpusNumber(raw)
        return try {
            rejectedResponseJsonFactory.createParser(raw).use { parser ->
                if (parser.nextToken() != JsonToken.START_OBJECT) {
                    throw CorpusResponseParseException(
                        AnkiProtocolCategory.INVALID_ENVELOPE,
                        "response envelope is not an object",
                    )
                }
                val result = readJsonValue(parser).asObject("response envelope")
                if (parser.nextToken() != null) {
                    throw CorpusResponseParseException(
                        AnkiProtocolCategory.INVALID_ENVELOPE,
                        "response envelope has trailing JSON",
                    )
                }
                result
            }
        } catch (error: CorpusResponseParseException) {
            throw error
        } catch (error: StreamConstraintsException) {
            val category =
                if (error.message.orEmpty().contains("Number value length")) {
                    AnkiProtocolCategory.NUMERIC_TOKEN_TOO_LONG
                } else {
                    AnkiProtocolCategory.INVALID_JSON
                }
            throw CorpusResponseParseException(category, "response JSON exceeds a lexical limit", error)
        } catch (error: InputCoercionException) {
            throw CorpusResponseParseException(
                AnkiProtocolCategory.INTEGER_OUT_OF_RANGE,
                "response integer is outside signed 64-bit range",
                error,
            )
        } catch (error: JsonParseException) {
            val message = error.originalMessage
            val category =
                when {
                    message.contains("Duplicate field", ignoreCase = true) ->
                        AnkiProtocolCategory.DUPLICATE_JSON_KEY
                    message.contains("NaN", ignoreCase = true) ||
                        message.contains("Infinity", ignoreCase = true) ->
                        AnkiProtocolCategory.NON_FINITE_NUMBER
                    message.contains("surrogate", ignoreCase = true) ->
                        AnkiProtocolCategory.INVALID_UTF8
                    else -> AnkiProtocolCategory.INVALID_JSON
                }
            throw CorpusResponseParseException(category, "malformed response JSON", error)
        }
    }

    private fun rejectOversizedCorpusNumber(raw: String) {
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
                throw CorpusResponseParseException(
                    AnkiProtocolCategory.NUMERIC_TOKEN_TOO_LONG,
                    "response JSON number exceeds its lexical limit",
                )
            }
            if (index == start) index += 1
        }
    }

    private fun validateRejectedResponseShape(
        messageType: String,
        payload: JsonObject,
    ) {
        when (messageType) {
            "anki.error" -> {
                requireCorpusKeys(
                    payload,
                    setOf("runId", "requestId", "operation", "code", "message", "retryable"),
                    AnkiProtocolCategory.INVALID_PAYLOAD,
                    "error payload",
                )
                val message = corpusString(payload["message"], "message")
                if (message.isEmpty()) {
                    throw CorpusResponseParseException(
                        AnkiProtocolCategory.INVALID_PAYLOAD,
                        "error message is empty",
                    )
                }
                enumWireName<AnkiOperation>(payload, "operation") { it.wireName }
                enumWireName<AnkiErrorCode>(payload, "code") { it.wireName }
                corpusBoolean(payload["retryable"], "retryable")
            }
            AnkiOperation.VERIFY_TARGET.resultType -> {
                requireCorpusKeys(
                    payload,
                    setOf("runId", "requestId", "deckId", "modelId", "fieldNames", "deckCreated"),
                    AnkiProtocolCategory.INVALID_PAYLOAD,
                    "verify-target result",
                )
                corpusLong(payload["deckId"], "deckId")
                corpusLong(payload["modelId"], "modelId")
                corpusStrings(payload["fieldNames"], "fieldNames")
                corpusBoolean(payload["deckCreated"], "deckCreated")
            }
            AnkiOperation.SCAN_FIRST_FIELDS.resultType -> {
                when {
                    "firstFields" in payload -> {
                        requireCorpusKeys(
                            payload,
                            setOf("runId", "requestId", "firstFields", "scannedNotes", "nextCursor"),
                            AnkiProtocolCategory.INVALID_PAYLOAD,
                            "known-vocabulary result",
                        )
                    }
                    "rawFirstFieldHits" in payload -> {
                        requireCorpusKeys(
                            payload,
                            setOf("runId", "requestId", "rawFirstFieldHits", "baselineToken"),
                            AnkiProtocolCategory.INVALID_PAYLOAD,
                            "duplicate lookup result",
                        )
                    }
                    else -> invalidCorpusPayload("scan-first-fields result has no recognized shape")
                }
            }
            AnkiOperation.STORE_MEDIA.resultType -> {
                requireCorpusKeys(
                    payload,
                    setOf("runId", "requestId", "results", "error"),
                    AnkiProtocolCategory.INVALID_PAYLOAD,
                    "store-media result",
                )
                val results = corpusArray(payload["results"], "results")
                if (results.isEmpty()) invalidCorpusPayload("store-media result rows are empty")
                results.forEachIndexed { index, rawRow ->
                    validateMediaRowShape(corpusObject(rawRow, "results[$index]"))
                }
                payload["error"]?.let { validateErrorDetailShape(corpusObject(it, "error")) }
            }
            AnkiOperation.CREATE_NOTES.resultType -> {
                requireCorpusKeys(
                    payload,
                    setOf("runId", "requestId", "results", "error"),
                    AnkiProtocolCategory.INVALID_PAYLOAD,
                    "create-notes result",
                )
                val results = corpusArray(payload["results"], "results")
                if (results.isEmpty()) invalidCorpusPayload("create-notes result rows are empty")
                results.forEachIndexed { index, rawRow ->
                    validateNoteRowShape(corpusObject(rawRow, "results[$index]"))
                }
                payload["error"]?.let { validateErrorDetailShape(corpusObject(it, "error")) }
            }
            AnkiOperation.RELEASE_RUN_STATE.resultType -> {
                requireCorpusKeys(
                    payload,
                    setOf("runId", "requestId", "state"),
                    AnkiProtocolCategory.INVALID_PAYLOAD,
                    "release-run-state result",
                )
                enumWireName<ReleaseState>(payload, "state") { it.wireName }
            }
            else -> invalidCorpusPayload("unsupported response message type")
        }
        corpusString(payload["runId"], "runId")
        corpusString(payload["requestId"], "requestId")
    }

    private fun validateMediaRowShape(row: JsonObject) {
        val expected =
            when (corpusString(row["status"], "media status")) {
                "stored" -> setOf("assetId", "status", "actualFilename")
                "failed" -> setOf("assetId", "status", "error")
                "uncertain", "notAttempted" -> setOf("assetId", "status")
                else -> invalidCorpusPayload("unknown media result status")
            }
        requireCorpusKeys(row, expected, AnkiProtocolCategory.INVALID_PAYLOAD, "media result row")
        corpusString(row["assetId"], "assetId")
        row["actualFilename"]?.let { corpusString(it, "actualFilename") }
        row["error"]?.let { validateErrorDetailShape(corpusObject(it, "row error")) }
    }

    private fun validateNoteRowShape(row: JsonObject) {
        val expected =
            when (corpusString(row["status"], "note status")) {
                "created", "committedFailed" -> setOf("clientNoteId", "status", "noteId")
                "duplicate", "failed", "uncertain", "notAttempted" ->
                    setOf("clientNoteId", "status")
                else -> invalidCorpusPayload("unknown note result status")
            }
        requireCorpusKeys(row, expected, AnkiProtocolCategory.INVALID_PAYLOAD, "note result row")
        corpusString(row["clientNoteId"], "clientNoteId")
        row["noteId"]?.let { corpusLong(it, "noteId") }
    }

    private fun validateErrorDetailShape(error: JsonObject) {
        requireCorpusKeys(
            error,
            setOf("code", "message", "retryable"),
            AnkiProtocolCategory.INVALID_PAYLOAD,
            "error detail",
        )
        enumWireName<AnkiErrorCode>(error, "code") { it.wireName }
        if (corpusString(error["message"], "message").isEmpty()) {
            invalidCorpusPayload("error detail message is empty")
        }
        corpusBoolean(error["retryable"], "retryable")
    }

    private inline fun <reified T : Enum<T>> enumWireName(
        value: JsonObject,
        key: String,
        wireName: (T) -> String,
    ): T {
        val raw = corpusString(value[key], key)
        return enumValues<T>().singleOrNull { wireName(it) == raw }
            ?: invalidCorpusPayload("$key has an unknown enum value")
    }

    private fun requireCorpusKeys(
        value: JsonObject,
        expected: Set<String>,
        category: AnkiProtocolCategory,
        context: String,
    ) {
        if (value.keys != expected) {
            throw CorpusResponseParseException(category, "$context has missing or extra fields")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun corpusObject(
        value: Any?,
        context: String,
        category: AnkiProtocolCategory = AnkiProtocolCategory.INVALID_PAYLOAD,
    ): JsonObject =
        value as? JsonObject
            ?: throw CorpusResponseParseException(category, "$context must be an object")

    private fun corpusArray(value: Any?, context: String): List<Any?> =
        value as? List<Any?>
            ?: throw CorpusResponseParseException(
                AnkiProtocolCategory.INVALID_PAYLOAD,
                "$context must be an array",
            )

    private fun corpusString(
        value: Any?,
        context: String,
        category: AnkiProtocolCategory = AnkiProtocolCategory.INVALID_PAYLOAD,
    ): String =
        value as? String
            ?: throw CorpusResponseParseException(category, "$context must be a string")

    private fun corpusStrings(value: Any?, context: String): List<String> =
        corpusArray(value, context).mapIndexed { index, item ->
            corpusString(item, "$context[$index]")
        }

    private fun corpusBoolean(value: Any?, context: String): Boolean =
        value as? Boolean
            ?: throw CorpusResponseParseException(
                AnkiProtocolCategory.INVALID_PAYLOAD,
                "$context must be a boolean",
            )

    private fun corpusLong(
        value: Any?,
        context: String,
        wrongTypeCategory: AnkiProtocolCategory = AnkiProtocolCategory.INVALID_PAYLOAD,
    ): Long =
        when (value) {
            is Long -> value
            is Double -> {
                when {
                    !value.isFinite() ->
                        throw CorpusResponseParseException(
                            AnkiProtocolCategory.NON_FINITE_NUMBER,
                            "$context is non-finite",
                        )
                    value % 1.0 != 0.0 ->
                        throw CorpusResponseParseException(
                            AnkiProtocolCategory.INVALID_JSON_NUMBER,
                            "$context is not an integer",
                        )
                    value < Long.MIN_VALUE.toDouble() || value >= 9.223372036854776E18 ->
                        throw CorpusResponseParseException(
                            AnkiProtocolCategory.INTEGER_OUT_OF_RANGE,
                            "$context is outside signed 64-bit range",
                        )
                    else -> value.toLong()
                }
            }
            else -> throw CorpusResponseParseException(wrongTypeCategory, "$context must be an integer")
        }

    private fun invalidCorpusPayload(message: String): Nothing =
        throw CorpusResponseParseException(AnkiProtocolCategory.INVALID_PAYLOAD, message)

    private fun mediaRow(row: JsonObject): MediaStoreRow =
        when (row.string("status")) {
            "stored" -> StoredMedia(row.string("assetId"), row.string("actualFilename"))
            "failed" -> FailedMedia(row.string("assetId"), errorDetail(row.obj("error")))
            "uncertain" -> UncertainMedia(row.string("assetId"))
            "notAttempted" -> NotAttemptedMedia(row.string("assetId"))
            else -> error("unsupported accepted media row")
        }

    private fun noteRow(row: JsonObject): CreateNoteRow =
        when (row.string("status")) {
            "created" -> CreatedNote(row.string("clientNoteId"), row.long("noteId"))
            "duplicate" -> DuplicateNote(row.string("clientNoteId"))
            "failed" -> FailedNote(row.string("clientNoteId"))
            "committedFailed" -> CommittedFailedNote(row.string("clientNoteId"), row.long("noteId"))
            "uncertain" -> UncertainNote(row.string("clientNoteId"))
            "notAttempted" -> NotAttemptedNote(row.string("clientNoteId"))
            else -> error("unsupported accepted note row")
        }

    private fun errorDetail(value: JsonObject): AnkiErrorDetail =
        AnkiErrorDetail(
            errorCode(value.string("code")),
            value.string("message"),
            value.boolean("retryable"),
        )

    private fun errorCode(wireName: String): AnkiErrorCode =
        AnkiErrorCode.entries.single { it.wireName == wireName }

    private fun operation(wireName: String): AnkiOperation =
        AnkiOperation.entries.single { it.wireName == wireName }

    private fun encode(response: AnkiResponse): String =
        AnkiJsonCodec.encodeResponse(response, matchingRequest(response))

    private fun matchingRequest(response: AnkiResponse): AnkiRequest =
        when (response) {
            is VerifyTargetResult ->
                VerifyTargetRequest(
                    response.runId,
                    response.requestId,
                    "Mining",
                    "Mining",
                    response.fieldNames,
                )
            is KnownVocabularyResult ->
                ScanFirstFieldsRequest(
                    response.runId,
                    response.requestId,
                    KnownVocabularyScope(emptyList(), null),
                )
            is DuplicateLookupResult -> {
                val candidates =
                    List(response.rawFirstFieldHits.size) { index ->
                        DuplicateCandidate("key$index", "field$index")
                    }
                ScanFirstFieldsRequest(
                    response.runId,
                    response.requestId,
                    DuplicateScanScope(
                        "Mining",
                        "Expression",
                        candidates,
                        candidates.indices.toList(),
                        null,
                    ),
                )
            }
            is StoreMediaResult ->
                StoreMediaRequest(
                    response.runId,
                    response.requestId,
                    response.results.mapIndexed { index, row ->
                        val filename = (row as? StoredMedia)?.actualFilename ?: "asset$index.opus"
                        MediaAsset(
                            row.assetId,
                            "/cache/asset$index.opus",
                            cardPreferred(filename),
                            filename,
                            MediaPurpose.CARD,
                            MediaKind.AUDIO,
                            0,
                            "0".repeat(64),
                        )
                    },
                )
            is CreateNotesResult ->
                CreateNotesRequest(
                    response.runId,
                    response.requestId,
                    "Mining",
                    "Mining",
                    "Expression",
                    BASELINE_TOKEN,
                    CollectionCreateDuplicateScope,
                    response.results.mapIndexed { index, row ->
                        CreateNote(
                            row.clientNoteId,
                            mapOf("Expression" to "value$index"),
                            emptyList(),
                            CreateDuplicateCandidate("key$index", "field$index", index),
                            emptyList(),
                        )
                    },
                )
            is ReleaseRunStateResult ->
                ReleaseRunStateRequest(
                    response.runId,
                    response.requestId,
                    acknowledgeTerminalResponses = true,
                )
            is AnkiErrorResult -> matchingErrorRequest(response)
        }

    private fun matchingErrorRequest(response: AnkiErrorResult): AnkiRequest =
        when (response.operation) {
            AnkiOperation.VERIFY_TARGET ->
                VerifyTargetRequest(
                    response.runId,
                    response.requestId,
                    "Mining",
                    "Mining",
                    emptyList(),
                )
            AnkiOperation.SCAN_FIRST_FIELDS ->
                ScanFirstFieldsRequest(
                    response.runId,
                    response.requestId,
                    KnownVocabularyScope(emptyList(), null),
                )
            AnkiOperation.STORE_MEDIA ->
                StoreMediaRequest(
                    response.runId,
                    response.requestId,
                    listOf(
                        MediaAsset(
                            ASSET_ID,
                            "/cache/a.opus",
                            "a_",
                            "a.opus",
                            MediaPurpose.CARD,
                            MediaKind.AUDIO,
                            0,
                            "0".repeat(64),
                        ),
                    ),
                )
            AnkiOperation.CREATE_NOTES ->
                CreateNotesRequest(
                    response.runId,
                    response.requestId,
                    "Mining",
                    "Mining",
                    "Expression",
                    BASELINE_TOKEN,
                    CollectionCreateDuplicateScope,
                    listOf(
                        CreateNote(
                            NOTE_ID,
                            mapOf("Expression" to "value"),
                            emptyList(),
                            CreateDuplicateCandidate("key", "field", 0),
                            emptyList(),
                        ),
                    ),
                )
            AnkiOperation.RELEASE_RUN_STATE ->
                ReleaseRunStateRequest(
                    response.runId,
                    response.requestId,
                    acknowledgeTerminalResponses = false,
                )
        }

    private fun corpusCategory(
        vectorId: String,
        category: AnkiProtocolCategory,
    ): String =
        when (category) {
            AnkiProtocolCategory.INTEGER_OUT_OF_RANGE,
            AnkiProtocolCategory.NON_FINITE_NUMBER,
            AnkiProtocolCategory.INVALID_JSON_NUMBER,
            -> "invalid_value"
            AnkiProtocolCategory.INVALID_MESSAGE_TYPE -> "invalid_envelope"
            AnkiProtocolCategory.UNEXPECTED_MESSAGE_TYPE -> {
                check(vectorId != "reject_message_type_pattern") {
                    "malformed message type must not be classified as callback mismatch"
                }
                category.wireName
            }
            else -> category.wireName
        }

    private fun parseJsonObject(
        raw: String,
        context: String,
    ): JsonObject =
        corpusJsonFactory.createParser(raw).use { parser ->
            check(parser.nextToken() == JsonToken.START_OBJECT) { "$context is not an object" }
            val result = readJsonValue(parser).asObject(context)
            check(parser.nextToken() == null) { "$context has trailing data" }
            result
        }

    private fun readJsonValue(parser: JsonParser): Any? =
        when (parser.currentToken()) {
            JsonToken.START_OBJECT -> {
                val result = linkedMapOf<String, Any?>()
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    check(parser.currentToken() == JsonToken.FIELD_NAME)
                    val field = parser.currentName()
                    check(parser.nextToken() != null)
                    result[field] = readJsonValue(parser)
                }
                result
            }
            JsonToken.START_ARRAY -> {
                val result = mutableListOf<Any?>()
                while (parser.nextToken() != JsonToken.END_ARRAY) result += readJsonValue(parser)
                result
            }
            JsonToken.VALUE_STRING -> parser.text
            JsonToken.VALUE_NUMBER_INT -> parser.longValue
            JsonToken.VALUE_NUMBER_FLOAT -> parser.doubleValue
            JsonToken.VALUE_TRUE -> true
            JsonToken.VALUE_FALSE -> false
            JsonToken.VALUE_NULL -> null
            else -> error("unsupported corpus JSON token ${parser.currentToken()}")
        }

    @Suppress("UNCHECKED_CAST")
    private fun Any?.asObject(context: String): JsonObject =
        this as? JsonObject ?: error("$context must be an object")

    @Suppress("UNCHECKED_CAST")
    private fun Any?.asArray(context: String): List<Any?> =
        this as? List<Any?> ?: error("$context must be an array")

    private fun Any?.asString(context: String): String =
        this as? String ?: error("$context must be a string")

    private fun Any?.asLong(context: String): Long =
        when (this) {
            is Long -> this
            is Double -> {
                check(isFinite() && this % 1.0 == 0.0) { "$context must be integral" }
                toLong()
            }
            else -> error("$context must be numeric")
        }

    private fun JsonObject.obj(key: String): JsonObject =
        getValue(key).asObject(key)

    private fun JsonObject.array(key: String): List<Any?> =
        getValue(key).asArray(key)

    private fun JsonObject.string(key: String): String =
        getValue(key).asString(key)

    private fun JsonObject.nullableString(key: String): String? =
        getValue(key)?.asString(key)

    private fun JsonObject.long(key: String): Long =
        getValue(key).asLong(key)

    private fun JsonObject.boolean(key: String): Boolean =
        getValue(key) as? Boolean ?: error("$key must be a boolean")

    private fun JsonObject.strings(key: String): List<String> =
        array(key).map { it.asString("$key item") }

    private fun providerText(
        css: String = "",
        latexPre: String? = null,
        latexPost: String? = null,
        template: AnkiValidators.ProviderTemplateText = template(),
    ) {
        AnkiValidators.validateProviderTextSnapshot(css, latexPre, latexPost, listOf(template))
    }

    private fun template(
        question: String = "",
        answer: String = "",
        browserQuestion: String? = null,
        browserAnswer: String? = null,
    ) = AnkiValidators.ProviderTemplateText(question, answer, browserQuestion, browserAnswer)

    private fun assertDecodeOutcome(
        name: String,
        accepted: Boolean,
        payload: String,
        operation: AnkiOperation = AnkiOperation.CREATE_NOTES,
    ) {
        if (accepted) {
            decode(operation, payload)
        } else {
            assertCategory(name, AnkiProtocolCategory.INVALID_VALUE) {
                decode(operation, payload)
            }
        }
    }

    private fun assertCategory(
        name: String,
        category: AnkiProtocolCategory,
        block: () -> Unit,
    ) {
        val error =
            assertThrows(name, AnkiProtocolException::class.java) {
                block()
            }
        assertEquals(name, category, error.category)
        assertTrue(name, !error.message.isNullOrEmpty())
    }

    private fun decode(operation: AnkiOperation, payload: String): AnkiRequest =
        AnkiJsonCodec.decodeRequest(envelope(operation, payload), operation)

    private fun envelope(
        operation: AnkiOperation,
        payload: String,
        version: String = "1",
    ): String =
        """{"schemaVersion":$version,"type":"${operation.requestType}","payload":$payload}"""

    private fun verifyPayload(
        deckName: String = "Mining",
        modelName: String = "Mining",
        requiredFields: List<String> = listOf("Expression"),
        requiredFieldsJson: String? = null,
    ): String =
        """{"runId":"$RUN_ID","requestId":"$REQUEST_ID","deckName":"${escape(deckName)}","modelName":"${escape(modelName)}","requiredFields":${requiredFieldsJson ?: stringArray(requiredFields)}}"""

    private fun knownPayload(
        excludedDecks: List<String> = emptyList(),
        ordinal: String = "1",
    ): String =
        """{"runId":"$RUN_ID","requestId":"$REQUEST_ID","scope":{"kind":"knownVocabulary","excludedDecks":${stringArray(excludedDecks)},"cursor":{"ordinal":$ordinal,"token":"cursor"},"limits":$KNOWN_LIMITS}}"""

    private fun duplicatePayload(count: Int): String {
        val candidates =
            List(count) { index ->
                """{"key":"key$index","firstField":"field$index"}"""
            }.joinToString(prefix = "[", postfix = "]")
        val occurrences = (0 until count).joinToString(prefix = "[", postfix = "]")
        return """{"runId":"$RUN_ID","requestId":"$REQUEST_ID","scope":{"kind":"duplicates","modelName":"Mining","firstFieldName":"Expression","candidates":$candidates,"occurrences":$occurrences,"invalidateBaselineToken":null,"limits":$DUPLICATE_LIMITS}}"""
    }

    private fun storePayload(assets: List<String>): String =
        """{"runId":"$RUN_ID","requestId":"$REQUEST_ID","assets":${assets.joinToString(prefix = "[", postfix = "]")},"limits":$STORE_LIMITS}"""

    private fun cardAssetJson(
        index: Int = 0,
        sourcePath: String = "/cache/clip$index.opus",
        filename: String = "clip$index.opus",
        preferredName: String = cardPreferred(filename),
        expectedSize: String = "0",
    ): String =
        """{"assetId":"${assetId(index)}","sourcePath":"${escape(sourcePath)}","preferredName":"${escape(preferredName)}","requestedFilename":"${escape(filename)}","purpose":"card","mediaKind":"audio","expectedSizeBytes":$expectedSize,"expectedSha256":"${"0".repeat(64)}"}"""

    private fun dictionaryAssetJson(
        index: Int = 0,
        filename: String,
        preferredName: String = dictionaryPreferred(filename),
    ): String =
        """{"assetId":"${assetId(index)}","sourcePath":"/cache/dict$index.webp","preferredName":"${escape(preferredName)}","requestedFilename":"${escape(filename)}","purpose":"dictionary","mediaKind":"image","expectedSizeBytes":0,"expectedSha256":"${"0".repeat(64)}"}"""

    private fun createPayload(notes: List<String>): String =
        """{"runId":"$RUN_ID","requestId":"$REQUEST_ID","deckName":"Mining","modelName":"Mining","firstFieldName":"Expression","baselineToken":"$BASELINE_TOKEN","duplicateScope":{"kind":"collection","limits":$CREATE_SNAPSHOT_LIMITS},"limits":$CREATE_LIMITS,"notes":${notes.joinToString(prefix = "[", postfix = "]")}}"""

    private fun createNoteJson(
        index: Int = 0,
        occurrence: Int = 0,
        fields: Map<String, String> = mapOf("Expression" to "value"),
        fieldsJson: String? = null,
        tags: List<String> = emptyList(),
        mediaBindings: List<String> = emptyList(),
    ): String =
        """{"clientNoteId":"${noteId(index)}","fields":${fieldsJson ?: stringMap(fields)},"tags":${stringArray(tags)},"duplicateCandidate":{"key":"value$index","firstField":"value","occurrence":$occurrence},"mediaBindings":${mediaBindings.joinToString(prefix = "[", postfix = "]")}}"""

    private fun bindingJson(
        index: Int = 0,
        filename: String = "clip.opus",
    ): String =
        """{"assetId":"${assetId(index)}","actualFilename":"${escape(filename)}"}"""

    private fun releasePayload(): String =
        """{"runId":"$RUN_ID","requestId":"$REQUEST_ID","acknowledgeTerminalResponses":true}"""

    private fun directCreateRequest(notes: List<CreateNote>): CreateNotesRequest =
        CreateNotesRequest(
            RUN_ID,
            REQUEST_ID,
            "Mining",
            "Mining",
            "Expression",
            BASELINE_TOKEN,
            CollectionCreateDuplicateScope,
            notes,
        )

    private fun directNote(
        index: Int,
        occurrence: Int,
        fields: Map<String, String> = mapOf("E" to ""),
        tags: List<String> = emptyList(),
        mediaBindings: List<MediaBinding> = emptyList(),
    ): CreateNote =
        CreateNote(
            noteId(index),
            fields,
            tags,
            CreateDuplicateCandidate("key$index", "field$index", occurrence),
            mediaBindings,
        )

    private fun directNoteWithContentBytes(
        index: Int,
        occurrence: Int,
        bytes: Int,
    ): CreateNote {
        if (bytes <= AnkiLimitsV1.CreateNotes.FIELD_VALUE_MAX_UTF8_BYTES + 1) {
            return directNote(
                index,
                occurrence,
                fields = mapOf("E" to "x".repeat(bytes - 1)),
            )
        }
        val fieldBytes = AnkiLimitsV1.CreateNotes.FIELD_VALUE_MAX_UTF8_BYTES + 1
        val tags = List(32) { fixedAscii("t$it", AnkiLimitsV1.CreateNotes.TAG_MAX_UTF8_BYTES) }
        val remaining = bytes - fieldBytes - tags.sumOf { it.utf8Size() }
        check(remaining >= 39)
        val bindingCount = remaining / 39
        val remainder = remaining % 39
        val bindings =
            List(bindingCount) { bindingIndex ->
                binding(
                    bindingIndex,
                    filename = if (bindingIndex == 0) "a" + "x".repeat(remainder) else "a",
                )
            }
        return directNote(
            index,
            occurrence,
            fields = mapOf("E" to "x".repeat(AnkiLimitsV1.CreateNotes.FIELD_VALUE_MAX_UTF8_BYTES)),
            tags = tags,
            mediaBindings = bindings,
        )
    }

    private fun binding(index: Int, filename: String = "a"): MediaBinding =
        MediaBinding(assetId(index), filename)

    private fun cardAsset(index: Int = 0, filename: String = "clip$index.opus"): MediaAsset =
        MediaAsset(
            assetId(index),
            "/cache/$filename",
            cardPreferred(filename),
            filename,
            MediaPurpose.CARD,
            MediaKind.AUDIO,
            0,
            "0".repeat(64),
        )

    private fun dictionaryAsset(index: Int = 0, filename: String): MediaAsset =
        MediaAsset(
            assetId(index),
            "/cache/dict$index.webp",
            dictionaryPreferred(filename),
            filename,
            MediaPurpose.DICTIONARY,
            MediaKind.IMAGE,
            0,
            "0".repeat(64),
        )

    private fun cardPreferred(filename: String): String {
        val suffix = filename.lastIndexOf('.')
        val stem =
            if (suffix > 0 && suffix < filename.lastIndex) {
                filename.substring(0, suffix)
            } else {
                filename
            }
        val preferred = stem.replace(' ', '_')
        return if (preferred.codePointCount(0, preferred.length) >= 2) preferred else "${preferred}_"
    }

    private fun dictionaryPreferred(filename: String): String {
        val digest =
            MessageDigest.getInstance("SHA-256")
                .digest(filename.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return "anki_miner_dict_$digest"
    }

    private fun stringArray(values: List<String>): String =
        values.joinToString(prefix = "[", postfix = "]") { value ->
            "\"${escape(value)}\""
        }

    private fun stringMap(values: Map<String, String>): String =
        values.entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
            "\"${escape(key)}\":\"${escape(value)}\""
        }

    private fun escape(value: String): String =
        buildString(value.length) {
            for (character in value) {
                when (character) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\u0000' -> append("\\u0000")
                    else -> append(character)
                }
            }
        }

    private fun fixedAscii(prefix: String, length: Int): String {
        check(prefix.length <= length)
        return prefix + "x".repeat(length - prefix.length)
    }

    private fun operationFor(raw: String): AnkiOperation =
        if (AnkiOperation.VERIFY_TARGET.requestType in raw) {
            AnkiOperation.VERIFY_TARGET
        } else {
            AnkiOperation.RELEASE_RUN_STATE
        }

    private fun projectRoot(): File {
        var cursor = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (!File(cursor, "settings.gradle.kts").isFile) {
            cursor = cursor.parentFile ?: error("could not find project root")
        }
        return cursor
    }

    private fun String.utf8Size(): Int = toByteArray(StandardCharsets.UTF_8).size

    private fun assetId(index: Int): String = "asset_${index.toString(16).padStart(32, '0')}"

    private fun noteId(index: Int): String = "note_${index.toString(16).padStart(32, '0')}"

    private data class ItemBoundaryCase(
        val name: String,
        val minimumAccepted: Boolean,
        val maximum: Int,
        val payload: (Int) -> String,
        val operation: AnkiOperation,
    )

    private data class StringBoundaryCase(
        val name: String,
        val maximum: Int,
        val payload: (String) -> String,
    )

    private data class RejectionCase(
        val name: String,
        val operation: AnkiOperation,
        val payload: String,
        val category: AnkiProtocolCategory,
    )

    private data class NumberCase(
        val name: String,
        val literal: String,
        val category: AnkiProtocolCategory,
    )

    private data class SurrogateCase(
        val name: String,
        val operation: AnkiOperation,
        val payload: String,
    )

    private data class MediaShape(
        val name: String,
        val rows: List<MediaStoreRow>,
        val error: AnkiErrorDetail?,
    )

    private data class NoteShape(
        val name: String,
        val rows: List<CreateNoteRow>,
        val error: AnkiErrorDetail?,
    )

    private data class CorpusVector(
        val id: String,
        val callback: String,
        val direction: String,
        val input: JsonObject,
        val expect: JsonObject,
    )

    private class CorpusResponseParseException(
        val category: AnkiProtocolCategory,
        message: String,
        cause: Throwable? = null,
    ) : IllegalArgumentException(message, cause)

    private companion object {
        val corpusJsonFactory = JsonFactory()
        val rejectedResponseJsonFactory: JsonFactory =
            JsonFactoryBuilder()
                .streamReadConstraints(
                    StreamReadConstraints.builder()
                        .maxNestingDepth(128)
                        .maxNumberLength(AnkiLimitsV1.Wire.NUMERIC_TOKEN_MAX_CHARS)
                        .build(),
                )
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .also { builder -> JsonReadFeature.entries.forEach { builder.disable(it) } }
                .build()

        const val RUN_ID = "run_00000000000000000000000000000000"
        const val RUN_ID_2 = "run_11111111111111111111111111111111"
        const val REQUEST_ID = "anki_22222222222222222222222222222222"
        const val REQUEST_ID_2 = "anki_33333333333333333333333333333333"
        const val ASSET_ID = "asset_44444444444444444444444444444444"
        const val ASSET_ID_2 = "asset_55555555555555555555555555555555"
        const val NOTE_ID = "note_66666666666666666666666666666666"
        const val NOTE_ID_2 = "note_77777777777777777777777777777777"
        const val NOTE_ID_3 = "note_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val BASELINE_TOKEN = "baseline_88888888888888888888888888888888"

        const val KNOWN_LIMITS =
            "{\"maxScannedNotes\":256,\"maxTotalScannedNotes\":100000,\"maxItems\":256,\"maxItemUtf8Bytes\":65536,\"maxTotalUtf8Bytes\":262144}"
        const val DUPLICATE_LIMITS =
            "{\"maxHitsPerCandidate\":100,\"maxTotalHits\":1000,\"maxItemUtf8Bytes\":65536,\"maxTotalUtf8Bytes\":1048576}"
        const val STORE_LIMITS =
            "{\"maxAssets\":50,\"maxAssetBytes\":67108864,\"maxTotalBytes\":67108864}"
        const val CREATE_SNAPSHOT_LIMITS =
            "{\"maxNoteIdsPerCandidate\":100,\"maxTotalNoteIds\":1000}"
        const val CREATE_LIMITS =
            "{\"maxNotes\":100,\"maxFieldsPerNote\":64,\"maxCardsPerNote\":64,\"maxFieldNameUtf8Bytes\":256,\"maxFieldValueUtf8Bytes\":98304,\"maxTagsPerNote\":64,\"maxTagUtf8Bytes\":256,\"maxTagsUtf8BytesPerNote\":8192,\"maxNoteContentUtf8Bytes\":131072,\"maxTotalContentUtf8Bytes\":393216,\"maxMediaBindingsPerNote\":8000,\"maxMediaBindingsTotal\":8000,\"maxEnvelopeUtf8Bytes\":524288}"
    }
}
