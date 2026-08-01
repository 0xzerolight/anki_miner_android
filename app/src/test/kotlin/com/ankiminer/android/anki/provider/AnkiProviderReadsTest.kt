package com.ankiminer.android.anki.provider

import com.ankiminer.android.anki.protocol.AnkiErrorCode
import com.ankiminer.android.anki.protocol.DuplicateCandidate
import com.ankiminer.android.anki.protocol.DuplicateLookupResult
import com.ankiminer.android.anki.protocol.DuplicateScanScope
import com.ankiminer.android.anki.protocol.KnownVocabularyResult
import com.ankiminer.android.anki.protocol.KnownVocabularyScope
import com.ankiminer.android.anki.protocol.ScanFirstFieldsRequest
import com.ankiminer.android.anki.protocol.VerifyTargetRequest
import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AnkiProviderReadsTest {
    @Test
    fun `deck picker discovery returns every live deck in stable name order`() {
        val gateway = FakeAnkiProviderGateway()
        gateway.queryHandler = { query, _ ->
            assertEquals(ProviderEndpoint.DECKS, query.endpoint)
            FakeProviderCursor(
                query.projection,
                listOf(
                    deckRow(id = 3L, name = "Japanese::Known"),
                    deckRow(id = 1L, name = "Default"),
                    deckRow(id = 2L, name = "Japanese"),
                ),
            )
        }

        val names =
            AnkiProviderReadService(gateway, AnkiRunStateRegistry())
                .listDeckNames(AnkiCancellation.NONE)

        assertEquals(listOf("Default", "Japanese", "Japanese::Known"), names)
    }

    @Test
    fun `verify target snapshots every insertion-relevant model template and deck field`() {
        val fixture = fixture()
        fixture.gateway.queryHandler = targetQueryHandler()

        val result = fixture.withOwner { owner -> fixture.verifyExistingTarget(owner, verifyRequest()) }

        assertEquals(20L, result.deck.id)
        assertEquals(10L, result.model.id)
        assertEquals(listOf("Expression", "Meaning"), result.model.fieldNames)
        assertEquals(
            listOf(
                ProviderEndpoint.MODELS,
                ProviderEndpoint.MODEL_TEMPLATES,
                ProviderEndpoint.DECKS,
            ),
            fixture.gateway.queries.map { it.endpoint },
        )
        assertEquals(
            listOf(
                ProviderColumn.TEMPLATE_MODEL_ID,
                ProviderColumn.TEMPLATE_ORDINAL,
                ProviderColumn.TEMPLATE_NAME,
                ProviderColumn.TEMPLATE_QUESTION_FORMAT,
                ProviderColumn.TEMPLATE_ANSWER_FORMAT,
                ProviderColumn.TEMPLATE_BROWSER_QUESTION_FORMAT,
                ProviderColumn.TEMPLATE_BROWSER_ANSWER_FORMAT,
            ),
            fixture.gateway.queries[1].projection,
        )
        fixture.withOwner { owner ->
            val target = requireNotNull(fixture.registry.target(owner))
            assertNull(target.model.templates.single().browserQuestionFormat)
            assertEquals("pre", target.model.latexPre)
            assertEquals(1L, target.model.effectiveDefaultDeckId)
        }
    }

    @Test
    fun `verify target rejects missing fields cloze filtered and oversized provider text`() {
        fun failureFor(
            request: VerifyTargetRequest = verifyRequest(),
            handler: (ProviderQuery, AnkiCancellation) -> ProviderCursor?,
        ): AnkiReadFailure {
            val fixture = fixture()
            fixture.gateway.queryHandler = handler
            return assertThrows(AnkiReadFailure::class.java) {
                fixture.withOwner { owner -> fixture.verifyExistingTarget(owner, request) }
            }
        }

        assertEquals(
            AnkiErrorCode.FIELD_MISSING,
            failureFor(verifyRequest(required = listOf("Missing")), targetQueryHandler()).code,
        )
        assertEquals(
            AnkiErrorCode.TARGET_INVALID,
            failureFor(handler = targetQueryHandler(model = modelRow(type = 1L))).code,
        )
        assertEquals(
            AnkiErrorCode.TARGET_INVALID,
            failureFor(handler = targetQueryHandler(deck = deckRow(dynamic = 1L))).code,
        )
        assertEquals(
            AnkiErrorCode.TARGET_INVALID,
            failureFor(handler = targetQueryHandler(model = modelRow(css = "x".repeat(262_145)))).code,
        )
    }

    @Test
    fun `model fields stop at the 65th field before any template query`() {
        val exactFields = (0 until 64).joinToString("\u001f") { "Field$it" }
        val exact = fixture()
        exact.gateway.queryHandler =
            targetQueryHandler(
                model = modelRow(fields = exactFields, sortField = 63L),
            )
        val result =
            exact.withOwner { owner ->
                exact.verifyExistingTarget(
                    owner,
                    verifyRequest(required = listOf("Field63")),
                )
            }
        assertEquals(64, result.model.fieldNames.size)

        val oversized = fixture()
        val modelCursor =
            FakeProviderCursor(
                ProviderQueryShapes.MODEL_PROJECTION,
                listOf(modelRow(fields = "$exactFields\u001fField64")),
            )
        oversized.gateway.queryHandler = { query, _ ->
            when (query.endpoint) {
                ProviderEndpoint.MODELS -> modelCursor
                ProviderEndpoint.MODEL_TEMPLATES -> error("template query must not run")
                else -> error("unexpected query $query")
            }
        }

        val failure =
            assertThrows(AnkiReadFailure::class.java) {
                oversized.withOwner { owner ->
                    oversized.verifyExistingTarget(owner, verifyRequest())
                }
            }
        assertEquals(AnkiErrorCode.TARGET_INVALID, failure.code)
        assertEquals(1, modelCursor.closeCount)
        assertEquals(listOf(ProviderEndpoint.MODELS), oversized.gateway.queries.map { it.endpoint })
    }

    @Test
    fun `invalid model unicode fails after closing the model cursor and before templates`() {
        val fixture = fixture()
        val modelCursor =
            FakeProviderCursor(
                ProviderQueryShapes.MODEL_PROJECTION,
                listOf(modelRow(css = "\uD800")),
            )
        fixture.gateway.queryHandler = { query, _ ->
            when (query.endpoint) {
                ProviderEndpoint.MODELS -> modelCursor
                ProviderEndpoint.MODEL_TEMPLATES -> error("template query must not run")
                else -> error("unexpected query $query")
            }
        }

        val failure =
            assertThrows(AnkiReadFailure::class.java) {
                fixture.withOwner { owner -> fixture.verifyExistingTarget(owner, verifyRequest()) }
            }
        assertEquals(AnkiErrorCode.TARGET_INVALID, failure.code)
        assertEquals(1, modelCursor.closeCount)
        assertEquals(listOf(ProviderEndpoint.MODELS), fixture.gateway.queries.map { it.endpoint })
    }

    @Test
    fun `every template text form rejects invalid unicode and closes immediately`() {
        val invalidRows =
            listOf(
                templateRow(name = "\uD800"),
                templateRow(question = "\uD800"),
                templateRow(answer = "\uD800"),
                templateRow(browserQuestion = text("\uD800")),
                templateRow(browserAnswer = text("\uD800")),
            )
        for (invalidRow in invalidRows) {
            val fixture = fixture()
            val templateCursor =
                FakeProviderCursor(ProviderQueryShapes.TEMPLATE_PROJECTION, listOf(invalidRow))
            fixture.gateway.queryHandler = { query, _ ->
                when (query.endpoint) {
                    ProviderEndpoint.MODELS ->
                        FakeProviderCursor(query.projection, listOf(modelRow()))
                    ProviderEndpoint.MODEL_TEMPLATES -> templateCursor
                    else -> error("unexpected query $query")
                }
            }

            val failure =
                assertThrows(AnkiReadFailure::class.java) {
                    fixture.withOwner { owner -> fixture.verifyExistingTarget(owner, verifyRequest()) }
                }
            assertEquals(AnkiErrorCode.TARGET_INVALID, failure.code)
            assertEquals(1, templateCursor.closeCount)
            assertEquals(
                listOf(ProviderEndpoint.MODELS, ProviderEndpoint.MODEL_TEMPLATES),
                fixture.gateway.queries.map { it.endpoint },
            )
        }
    }

    @Test
    fun `template provider text accepts the exact aggregate boundary and rejects one byte more`() {
        val textChunk = "x".repeat(65_536)
        val templates =
            (0 until 16).map { ordinal ->
                templateRow(
                    ordinal = ordinal.toLong(),
                    question = textChunk,
                    answer = textChunk,
                    browserQuestion = text(textChunk),
                    browserAnswer = text(textChunk),
                )
            }
        val exact = fixture()
        exact.gateway.queryHandler =
            targetQueryHandler(
                model =
                    modelRow(
                        cards = 16L,
                        css = "",
                        latexPre = nullCell(),
                        latexPost = nullCell(),
                    ),
                templates = templates,
            )
        val exactResult =
            exact.withOwner { owner -> exact.verifyExistingTarget(owner, verifyRequest()) }
        assertEquals(10L, exactResult.model.id)

        val oversized = fixture()
        val templateCursor =
            FakeProviderCursor(ProviderQueryShapes.TEMPLATE_PROJECTION, templates)
        oversized.gateway.queryHandler = { query, _ ->
            when (query.endpoint) {
                ProviderEndpoint.MODELS ->
                    FakeProviderCursor(
                        query.projection,
                        listOf(
                            modelRow(
                                cards = 16L,
                                css = "x",
                                latexPre = nullCell(),
                                latexPost = nullCell(),
                            ),
                        ),
                    )
                ProviderEndpoint.MODEL_TEMPLATES -> templateCursor
                else -> error("unexpected query $query")
            }
        }
        val failure =
            assertThrows(AnkiReadFailure::class.java) {
                oversized.withOwner { owner -> oversized.verifyExistingTarget(owner, verifyRequest()) }
            }
        assertEquals(AnkiErrorCode.TARGET_INVALID, failure.code)
        assertEquals(1, templateCursor.closeCount)
    }

    @Test
    fun `each provider model text field enforces its exact N and N plus one boundary`() {
        data class TextCase(
            val name: String,
            val model: (String) -> Map<ProviderColumn, ProviderCell> = { modelRow() },
            val template: (String) -> Map<ProviderColumn, ProviderCell> = { templateRow() },
        )

        val cases =
            listOf(
                TextCase("CSS", model = { value -> modelRow(css = value) }),
                TextCase(
                    "LaTeX pre",
                    model = { value -> modelRow(latexPre = text(value)) },
                ),
                TextCase(
                    "LaTeX post",
                    model = { value -> modelRow(latexPost = text(value)) },
                ),
                TextCase(
                    "question",
                    template = { value -> templateRow(question = value) },
                ),
                TextCase(
                    "answer",
                    template = { value -> templateRow(answer = value) },
                ),
                TextCase(
                    "browser question",
                    template = { value -> templateRow(browserQuestion = text(value)) },
                ),
                TextCase(
                    "browser answer",
                    template = { value -> templateRow(browserAnswer = text(value)) },
                ),
            )
        val exact = "x".repeat(262_144)
        val oversized = "$exact+"
        for (case in cases) {
            val accepted = fixture()
            accepted.gateway.queryHandler =
                targetQueryHandler(
                    model = case.model(exact),
                    templates = listOf(case.template(exact)),
                )
            val acceptedResult =
                accepted.withOwner { owner ->
                    accepted.verifyExistingTarget(owner, verifyRequest())
                }
            assertEquals(case.name, 10L, acceptedResult.model.id)

            val rejected = fixture()
            rejected.gateway.queryHandler =
                targetQueryHandler(
                    model = case.model(oversized),
                    templates = listOf(case.template(oversized)),
                )
            val failure =
                assertThrows(case.name, AnkiReadFailure::class.java) {
                    rejected.withOwner { owner ->
                        rejected.verifyExistingTarget(owner, verifyRequest())
                    }
                }
            assertEquals(case.name, AnkiErrorCode.TARGET_INVALID, failure.code)
        }
    }

    @Test
    fun `template name accepts 256 bytes and rejects 257`() {
        val accepted = fixture()
        accepted.gateway.queryHandler =
            targetQueryHandler(
                templates = listOf(templateRow(name = "x".repeat(256))),
            )
        assertEquals(
            10L,
            accepted.withOwner { owner ->
                accepted.verifyExistingTarget(owner, verifyRequest())
            }.model.id,
        )

        val rejected = fixture()
        rejected.gateway.queryHandler =
            targetQueryHandler(
                templates = listOf(templateRow(name = "x".repeat(257))),
            )
        assertEquals(
            AnkiErrorCode.TARGET_INVALID,
            assertThrows(AnkiReadFailure::class.java) {
                rejected.withOwner { owner ->
                    rejected.verifyExistingTarget(owner, verifyRequest())
                }
            }.code,
        )
    }

    @Test
    fun `template cardinality accepts one and 64 while rejecting zero 65 duplicate and gapped ordinals`() {
        fun resultFor(
            cardCount: Long,
            templates: List<Map<ProviderColumn, ProviderCell>>,
        ): Result<VerifyTargetRequest> {
            val fixture = fixture()
            fixture.gateway.queryHandler =
                targetQueryHandler(
                    model = modelRow(cards = cardCount),
                    templates = templates,
                )
            return runCatching {
                fixture.withOwner { owner ->
                    fixture.verifyExistingTarget(owner, verifyRequest())
                    verifyRequest()
                }
            }
        }

        assertTrue(resultFor(1L, listOf(templateRow())).isSuccess)
        assertTrue(
            resultFor(
                64L,
                (0 until 64).map { ordinal -> templateRow(ordinal = ordinal.toLong()) },
            ).isSuccess,
        )

        val invalid =
            listOf(
                resultFor(1L, emptyList()),
                resultFor(
                    64L,
                    (0..64).map { ordinal -> templateRow(ordinal = ordinal.toLong()) },
                ),
                resultFor(
                    2L,
                    listOf(templateRow(ordinal = 0L), templateRow(ordinal = 0L)),
                ),
                resultFor(
                    2L,
                    listOf(templateRow(ordinal = 0L), templateRow(ordinal = 2L)),
                ),
            )
        assertTrue(invalid.all { result -> result.exceptionOrNull() is AnkiReadFailure })
        assertTrue(
            invalid.all { result ->
                (result.exceptionOrNull() as AnkiReadFailure).code == AnkiErrorCode.TARGET_INVALID
            },
        )
    }

    @Test
    fun `verify target classifies absent model missing deck and malformed cursors`() {
        val absent = fixture()
        absent.gateway.queryHandler = targetQueryHandler(models = emptyList())
        assertEquals(
            AnkiErrorCode.NOTE_TYPE_NOT_FOUND,
            assertThrows(AnkiReadFailure::class.java) {
                absent.withOwner { owner -> absent.verifyExistingTarget(owner, verifyRequest()) }
            }.code,
        )

        val missingDeck = fixture()
        missingDeck.gateway.queryHandler = targetQueryHandler(decks = emptyList())
        assertEquals(
            AnkiErrorCode.UNSUPPORTED_OPERATION,
            assertThrows(AnkiReadFailure::class.java) {
                missingDeck.withOwner { owner -> missingDeck.verifyExistingTarget(owner, verifyRequest()) }
            }.code,
        )

        val nullCursor = fixture()
        nullCursor.gateway.queryHandler = { query, _ ->
            if (query.endpoint == ProviderEndpoint.MODELS) null else error("unexpected")
        }
        assertEquals(
            AnkiErrorCode.QUERY_FAILED,
            assertThrows(AnkiReadFailure::class.java) {
                nullCursor.withOwner { owner -> nullCursor.verifyExistingTarget(owner, verifyRequest()) }
            }.code,
        )

        val malformed = fixture()
        val wrongProjectionCursor =
            FakeProviderCursor(listOf(ProviderColumn.MODEL_ID), listOf(mapOf(ProviderColumn.MODEL_ID to integer(10))))
        malformed.gateway.queryHandler = { _, _ -> wrongProjectionCursor }
        assertThrows(AnkiReadFailure::class.java) {
            malformed.withOwner { owner -> malformed.verifyExistingTarget(owner, verifyRequest()) }
        }
        assertEquals(1, wrongProjectionCursor.closeCount)

        val nullEffectiveDefault = fixture()
        nullEffectiveDefault.gateway.queryHandler =
            targetQueryHandler(model = modelRow(defaultDeckId = nullCell()))
        val target =
            nullEffectiveDefault.withOwner { owner ->
                nullEffectiveDefault.verifyExistingTarget(owner, verifyRequest())
            }
        assertEquals(1L, target.model.effectiveDefaultDeckId)
    }

    @Test
    fun `target revalidation reads full model before deck and rejects leaf drift deletion and dynamic replacement`() {
        data class DriftCase(
            val name: String,
            val handler: (ProviderQuery, AnkiCancellation) -> ProviderCursor?,
            val expectedTrace: List<ProviderEndpoint>,
        )

        fun directHandler(
            itemModels: List<Map<ProviderColumn, ProviderCell>> = listOf(modelRow()),
            itemQueryFails: Boolean = false,
            fallbackModels: List<Map<ProviderColumn, ProviderCell>> = emptyList(),
            templates: List<Map<ProviderColumn, ProviderCell>> = listOf(templateRow()),
            decks: List<Map<ProviderColumn, ProviderCell>> = listOf(deckRow()),
        ): (ProviderQuery, AnkiCancellation) -> ProviderCursor? = { query, _ ->
            when (query.endpoint) {
                ProviderEndpoint.MODEL_BY_ID -> {
                    if (itemQueryFails) {
                        throw ProviderGatewayException(ProviderFailureKind.QUERY_FAILED)
                    }
                    FakeProviderCursor(query.projection, itemModels)
                }
                ProviderEndpoint.MODELS ->
                    FakeProviderCursor(query.projection, fallbackModels)
                ProviderEndpoint.MODEL_TEMPLATES -> FakeProviderCursor(query.projection, templates)
                ProviderEndpoint.DECK_BY_ID -> FakeProviderCursor(query.projection, decks)
                else -> error("unexpected query $query")
            }
        }

        val cases =
            listOf(
                DriftCase(
                    "model leaf drift",
                    directHandler(itemModels = listOf(modelRow(css = "changed"))),
                    listOf(
                        ProviderEndpoint.MODEL_BY_ID,
                        ProviderEndpoint.MODEL_TEMPLATES,
                        ProviderEndpoint.DECK_BY_ID,
                    ),
                ),
                DriftCase(
                    "template leaf drift",
                    directHandler(templates = listOf(templateRow(answer = "changed"))),
                    listOf(
                        ProviderEndpoint.MODEL_BY_ID,
                        ProviderEndpoint.MODEL_TEMPLATES,
                        ProviderEndpoint.DECK_BY_ID,
                    ),
                ),
                DriftCase(
                    "model deletion",
                    directHandler(itemQueryFails = true, fallbackModels = emptyList()),
                    listOf(ProviderEndpoint.MODEL_BY_ID, ProviderEndpoint.MODELS),
                ),
                DriftCase(
                    "model replacement after item failure",
                    directHandler(
                        itemQueryFails = true,
                        fallbackModels = listOf(modelRow(name = "Replacement")),
                    ),
                    listOf(
                        ProviderEndpoint.MODEL_BY_ID,
                        ProviderEndpoint.MODELS,
                        ProviderEndpoint.MODEL_TEMPLATES,
                        ProviderEndpoint.DECK_BY_ID,
                    ),
                ),
                DriftCase(
                    "ambiguous model ID after item failure",
                    directHandler(
                        itemQueryFails = true,
                        fallbackModels = listOf(modelRow(), modelRow()),
                    ),
                    listOf(ProviderEndpoint.MODEL_BY_ID, ProviderEndpoint.MODELS),
                ),
                DriftCase(
                    "deck deletion",
                    directHandler(decks = emptyList()),
                    listOf(
                        ProviderEndpoint.MODEL_BY_ID,
                        ProviderEndpoint.MODEL_TEMPLATES,
                        ProviderEndpoint.DECK_BY_ID,
                    ),
                ),
                DriftCase(
                    "dynamic replacement",
                    directHandler(decks = listOf(deckRow(dynamic = 1L))),
                    listOf(
                        ProviderEndpoint.MODEL_BY_ID,
                        ProviderEndpoint.MODEL_TEMPLATES,
                        ProviderEndpoint.DECK_BY_ID,
                    ),
                ),
            )

        for (case in cases) {
            val fixture = fixture()
            fixture.gateway.queryHandler = targetQueryHandler()
            val expected =
                fixture.withOwner { owner ->
                    fixture.verifyExistingTarget(owner, verifyRequest())
                    requireNotNull(fixture.registry.target(owner))
                }
            fixture.gateway.queries.clear()
            fixture.gateway.queryHandler = case.handler

            val failure =
                assertThrows("case ${case.name}", AnkiReadFailure::class.java) {
                    fixture.withOwner { owner -> fixture.reads.readTargetById(owner, expected) }
                }
            assertEquals(case.name, AnkiErrorCode.TARGET_INVALID, failure.code)
            assertEquals(case.name, case.expectedTrace, fixture.gateway.queries.map { it.endpoint })
        }
    }

    @Test
    fun `every provider failure kind keeps its platform cause across the read seam`() {
        // Over entries rather than a fixed list: a kind added later gets an arm in toReadFailure,
        // and an arm that forgets the cause is the bug this guards.
        for (kind in ProviderFailureKind.entries) {
            val platform = SecurityException("com.ichi2.anki denied READ")
            val gateway = FakeAnkiProviderGateway()
            gateway.queryHandler = { _, _ -> throw ProviderGatewayException(kind, platform) }

            val failure =
                assertThrows("kind $kind", AnkiReadFailure::class.java) {
                    AnkiProviderReadService(gateway, AnkiRunStateRegistry())
                        .listDeckNames(AnkiCancellation.NONE)
                }

            // stableMessage is category-level on purpose, so without the chain a log says an Anki
            // read failed and never which platform refusal did it.
            val gatewayFailure = failure.cause as? ProviderGatewayException
            assertEquals("kind $kind", kind, gatewayFailure?.kind)
            assertSame("kind $kind", platform, gatewayFailure?.cause)
        }
    }

    @Test
    fun `model item fallback does not swallow timeout access or permission loss`() {
        val failures =
            listOf(
                ProviderFailureKind.TIMEOUT to AnkiErrorCode.TIMEOUT,
                ProviderFailureKind.PROVIDER_UNAVAILABLE to AnkiErrorCode.PROVIDER_UNAVAILABLE,
                ProviderFailureKind.API_DISABLED to AnkiErrorCode.API_DISABLED,
                ProviderFailureKind.PERMISSION_REQUIRED to AnkiErrorCode.PERMISSION_REQUIRED,
            )
        for ((kind, expectedCode) in failures) {
            val fixture = fixture()
            fixture.gateway.queryHandler = targetQueryHandler()
            val expected =
                fixture.withOwner { owner ->
                    fixture.verifyExistingTarget(owner, verifyRequest())
                    requireNotNull(fixture.registry.target(owner))
                }
            fixture.gateway.queries.clear()
            fixture.gateway.queryHandler = { query, _ ->
                if (query.endpoint != ProviderEndpoint.MODEL_BY_ID) {
                    error("fallback must not run for $kind")
                }
                throw ProviderGatewayException(kind)
            }

            val failure =
                assertThrows(AnkiReadFailure::class.java) {
                    fixture.withOwner { owner -> fixture.reads.readTargetById(owner, expected) }
                }
            assertEquals(expectedCode, failure.code)
            assertEquals(
                listOf(ProviderEndpoint.MODEL_BY_ID),
                fixture.gateway.queries.map { it.endpoint },
            )
        }
    }

    @Test
    fun `model item fallback closes and fails at all-model row 100001`() {
        val fixture = fixture()
        fixture.gateway.queryHandler = targetQueryHandler()
        val expected =
            fixture.withOwner { owner ->
                fixture.verifyExistingTarget(owner, verifyRequest())
                requireNotNull(fixture.registry.target(owner))
            }
        fixture.gateway.queries.clear()
        val fallback =
            GeneratedFakeProviderCursor(
                ProviderQueryShapes.MODEL_PROJECTION,
                rowCount = 100_001,
                rowAt = { index ->
                    mapOf(ProviderColumn.MODEL_ID to integer(index + 1_000L))
                },
            )
        fixture.gateway.queryHandler = { query, _ ->
            when (query.endpoint) {
                ProviderEndpoint.MODEL_BY_ID ->
                    throw ProviderGatewayException(ProviderFailureKind.QUERY_FAILED)
                ProviderEndpoint.MODELS -> fallback
                else -> error("unexpected query $query")
            }
        }

        val failure =
            assertThrows(AnkiReadFailure::class.java) {
                fixture.withOwner { owner -> fixture.reads.readTargetById(owner, expected) }
            }
        assertEquals(AnkiErrorCode.QUERY_FAILED, failure.code)
        assertEquals(1, fallback.closeCount)
    }

    @Test
    fun `known vocabulary pages deterministic v2 snapshot and consumes each cursor once`() {
        val fixture = fixture(tokens = listOf("cursor_${"a".repeat(32)}"))
        fixture.gateway.queryHandler = { query, _ ->
            when {
                query.endpoint == ProviderEndpoint.NOTES_V2 && query.selection == null ->
                    FakeProviderCursor(
                        query.projection,
                        (1L..257L).map { id -> mapOf(ProviderColumn.NOTE_ID to integer(id)) },
                    )
                query.selection is ProviderSelection.NoteIds -> {
                    val ids = (query.selection as ProviderSelection.NoteIds).ids
                    FakeProviderCursor(
                        query.projection,
                        ids.map { id ->
                            mapOf(
                                ProviderColumn.NOTE_ID to integer(id),
                                ProviderColumn.NOTE_FIELDS to text("word-$id\u001fmeaning"),
                            )
                        },
                    )
                }
                else -> error("unexpected query $query")
            }
        }
        val firstRequest = knownRequest()
        val first =
            fixture.withOwner { owner -> fixture.reads.scanFirstFields(owner, firstRequest) }
                as KnownVocabularyResult
        assertEquals(256, first.firstFields.size)
        assertEquals(256, first.scannedNotes)
        assertEquals(1L, first.nextCursor?.ordinal)
        val secondRequest = knownRequest(cursor = first.nextCursor, requestId = SECOND_REQUEST_ID)
        val second =
            fixture.withOwner { owner -> fixture.reads.scanFirstFields(owner, secondRequest) }
                as KnownVocabularyResult
        assertEquals(listOf("word-257"), second.firstFields)
        assertEquals(1, second.scannedNotes)
        assertNull(second.nextCursor)
        assertThrows(InvalidCapabilityException::class.java) {
            fixture.withOwner { owner -> fixture.reads.scanFirstFields(owner, secondRequest) }
        }
        assertEquals(ProviderOrder.NOTE_ID_ASCENDING, fixture.gateway.queries.first().sortOrder)
        val pageQueries = fixture.gateway.queries.filter { it.selection is ProviderSelection.NoteIds }
        assertEquals((1L..256L).toList(), (pageQueries[0].selection as ProviderSelection.NoteIds).ids)
    }

    @Test
    fun `known vocabulary exact deck scope excludes children and other decks`() {
        val fixture = fixture()
        fixture.gateway.queryHandler = targetQueryHandler()
        fixture.withOwner { owner -> fixture.verifyExistingTarget(owner, verifyRequest()) }
        fixture.gateway.queries.clear()
        fixture.gateway.queryHandler = { query, _ ->
            when {
                query.endpoint == ProviderEndpoint.CARDS -> {
                    assertEquals(ProviderSelection.CardsInDeck("Mining"), query.selection)
                    FakeProviderCursor(
                        query.projection,
                        listOf(
                            mapOf(
                                ProviderColumn.CARD_NOTE_ID to integer(3L),
                                ProviderColumn.CARD_DECK_ID to integer(20L),
                                ProviderColumn.CARD_ORIGINAL_DECK_ID to integer(0L),
                            ),
                            mapOf(
                                ProviderColumn.CARD_NOTE_ID to integer(2L),
                                ProviderColumn.CARD_DECK_ID to integer(21L),
                                ProviderColumn.CARD_ORIGINAL_DECK_ID to integer(0L),
                            ),
                            mapOf(
                                ProviderColumn.CARD_NOTE_ID to integer(1L),
                                ProviderColumn.CARD_DECK_ID to integer(20L),
                                ProviderColumn.CARD_ORIGINAL_DECK_ID to integer(0L),
                            ),
                            mapOf(
                                ProviderColumn.CARD_NOTE_ID to integer(1L),
                                ProviderColumn.CARD_DECK_ID to integer(20L),
                                ProviderColumn.CARD_ORIGINAL_DECK_ID to integer(0L),
                            ),
                        ),
                    )
                }
                query.selection is ProviderSelection.NoteIds -> {
                    assertEquals(listOf(1L, 3L), (query.selection as ProviderSelection.NoteIds).ids)
                    FakeProviderCursor(
                        query.projection,
                        listOf(
                            mapOf(
                                ProviderColumn.NOTE_ID to integer(3L),
                                ProviderColumn.NOTE_FIELDS to text("three\u001fmeaning"),
                            ),
                            mapOf(
                                ProviderColumn.NOTE_ID to integer(1L),
                                ProviderColumn.NOTE_FIELDS to text("one\u001fmeaning"),
                            ),
                        ),
                    )
                }
                else -> error("unexpected query $query")
            }
        }

        val result =
            fixture.withOwner { owner ->
                fixture.reads.scanFirstFields(owner, knownRequest(deckName = "Mining"))
            } as KnownVocabularyResult

        assertEquals(listOf("one", "three"), result.firstFields)
        assertEquals(2, result.scannedNotes)
        assertNull(result.nextCursor)
    }

    @Test
    fun `known vocabulary keeps cards a filtered deck borrowed from the target`() {
        val fixture = fixture()
        fixture.gateway.queryHandler = targetQueryHandler()
        fixture.withOwner { owner -> fixture.verifyExistingTarget(owner, verifyRequest()) }
        fixture.gateway.queries.clear()
        fixture.gateway.queryHandler = { query, _ ->
            when {
                query.endpoint == ProviderEndpoint.CARDS ->
                    FakeProviderCursor(
                        query.projection,
                        listOf(
                            // Custom Study over "Mining" moved this card into a filtered deck; its
                            // home deck is still the target, so its note is already mined.
                            mapOf(
                                ProviderColumn.CARD_NOTE_ID to integer(1L),
                                ProviderColumn.CARD_DECK_ID to integer(99L),
                                ProviderColumn.CARD_ORIGINAL_DECK_ID to integer(20L),
                            ),
                            // A subdeck card borrowed by the same session stays out: its home deck
                            // is the subdeck, not the target.
                            mapOf(
                                ProviderColumn.CARD_NOTE_ID to integer(2L),
                                ProviderColumn.CARD_DECK_ID to integer(99L),
                                ProviderColumn.CARD_ORIGINAL_DECK_ID to integer(21L),
                            ),
                        ),
                    )
                query.selection is ProviderSelection.NoteIds -> {
                    assertEquals(listOf(1L), (query.selection as ProviderSelection.NoteIds).ids)
                    FakeProviderCursor(
                        query.projection,
                        listOf(
                            mapOf(
                                ProviderColumn.NOTE_ID to integer(1L),
                                ProviderColumn.NOTE_FIELDS to text("one\u001fmeaning"),
                            ),
                        ),
                    )
                }
                else -> error("unexpected query $query")
            }
        }

        val result =
            fixture.withOwner { owner ->
                fixture.reads.scanFirstFields(owner, knownRequest(deckName = "Mining"))
            } as KnownVocabularyResult

        assertEquals(listOf("one"), result.firstFields)
        assertEquals(1, result.scannedNotes)
    }

    @Test
    fun `deck card scan accepts a deck whose card rows outnumber the note ceiling`() {
        // 60000 notes at two cards each is 120000 rows: past the note ceiling as a row count,
        // well under it as the note count the ceiling actually governs. Spending one budget on
        // both refused this deck, which is the whole reason the row budget is a separate limit.
        var cardCellReads = 0
        val cardCursor =
            exactDeckCardCursor(rowCount = 120_000, cardsPerNote = 2) { cardCellReads += 1 }
        val fixture = deckCardScanFixture(cardCursor)

        val result =
            fixture.withOwner { owner ->
                fixture.reads.scanFirstFields(owner, knownRequest(deckName = "Mining"))
            } as KnownVocabularyResult

        // Two cells on every one of the 120000 rows: the traversal ran to the end of the cursor,
        // so neither ceiling refused it and the snapshot holds all 60000 notes.
        assertEquals(240_000, cardCellReads)
        assertEquals(1, cardCursor.closeCount)
        // scannedNotes is this page, not the snapshot — 60000 notes page at 256 with a cursor.
        assertEquals(256, result.firstFields.size)
        assertEquals(256, result.scannedNotes)
        assertEquals(1L, result.nextCursor?.ordinal)
    }

    @Test
    fun `deck card scan holds the note ceiling at exactly 100000 exact deck notes`() {
        var acceptedCellReads = 0
        val acceptedCursor =
            exactDeckCardCursor(rowCount = 100_000, cardsPerNote = 1) { acceptedCellReads += 1 }
        val accepted = deckCardScanFixture(acceptedCursor)

        accepted.withOwner { owner ->
            accepted.reads.scanFirstFields(owner, knownRequest(deckName = "Mining"))
        } as KnownVocabularyResult

        assertEquals(200_000, acceptedCellReads)
        assertEquals(1, acceptedCursor.closeCount)

        // One more distinct note in the target deck, and the snapshot exceeds what it may hand back
        // as scannedNotes — anki_adapter.py refuses above the same constant.
        var refusedCellReads = 0
        val refusedCursor =
            exactDeckCardCursor(rowCount = 100_001, cardsPerNote = 1) { refusedCellReads += 1 }
        val refused = deckCardScanFixture(refusedCursor)

        val failure =
            assertThrows(AnkiReadFailure::class.java) {
                refused.withOwner { owner ->
                    refused.reads.scanFirstFields(owner, knownRequest(deckName = "Mining"))
                }
            }
        assertEquals(AnkiErrorCode.QUERY_FAILED, failure.code)
        assertEquals(false, failure.retryable)
        assertEquals(
            "Known-word filtering supports at most 100000 notes in the selected Anki deck",
            failure.stableMessage,
        )
        // Unlike the row budget, this ceiling has to read the row's cells to learn its note, so the
        // refusing row is read and then rejected rather than refused before its cells.
        assertEquals(200_002, refusedCellReads)
        assertEquals(1, refusedCursor.closeCount)
    }

    @Test
    fun `deck card scan closes at row 1000001 of subdeck rows before reading its cells`() {
        var cardCellReads = 0
        // Every row belongs to a subdeck, so none matches the verified target deck (20) and none
        // can ever contribute a note. `deck:"Mining"` returns them regardless, so the walk needs a
        // bound of its own or a deck under a large tree scans without end.
        val cardCursor =
            GeneratedFakeProviderCursor(
                ProviderQueryShapes.CARD_NOTE_DECK_PROJECTION,
                rowCount = 1_000_001,
                rowAt = { index ->
                    mapOf(
                        ProviderColumn.CARD_NOTE_ID to integer(index + 1L),
                        ProviderColumn.CARD_DECK_ID to integer(21L),
                        ProviderColumn.CARD_ORIGINAL_DECK_ID to integer(0L),
                    )
                },
                beforeCell = { cardCellReads += 1 },
            )
        val fixture = deckCardScanFixture(cardCursor)

        val failure =
            assertThrows(AnkiReadFailure::class.java) {
                fixture.withOwner { owner ->
                    fixture.reads.scanFirstFields(owner, knownRequest(deckName = "Mining"))
                }
            }
        assertEquals(AnkiErrorCode.QUERY_FAILED, failure.code)
        assertEquals(false, failure.retryable)
        // The refusal names card rows and the subdecks, because that is what ran out. Calling them
        // notes would quote a number the deck never reached.
        assertEquals(
            "Known-word filtering scans at most 1000000 cards in " +
                "the selected Anki deck and its subdecks",
            failure.stableMessage,
        )
        // Three cells per row for the first 1000000 rows — a row outside the target deck also has
        // to be tested against its home deck — and row 1000001 is refused before its cells.
        assertEquals(3_000_000, cardCellReads)
        assertEquals(1, cardCursor.closeCount)
    }

    @Test
    fun `known continuation failure is nonretryable after consuming its cursor`() {
        val fixture = fixture(tokens = listOf("cursor_${"a".repeat(32)}"))
        fixture.gateway.queryHandler = { query, _ ->
            when {
                query.endpoint == ProviderEndpoint.NOTES_V2 && query.selection == null ->
                    FakeProviderCursor(
                        query.projection,
                        (1L..257L).map { id -> mapOf(ProviderColumn.NOTE_ID to integer(id)) },
                    )
                query.selection is ProviderSelection.NoteIds -> {
                    val ids = (query.selection as ProviderSelection.NoteIds).ids
                    FakeProviderCursor(
                        query.projection,
                        ids.map { id ->
                            mapOf(
                                ProviderColumn.NOTE_ID to integer(id),
                                ProviderColumn.NOTE_FIELDS to text("word-$id"),
                            )
                        },
                    )
                }
                else -> error("unexpected query $query")
            }
        }
        val first =
            fixture.withOwner { owner -> fixture.reads.scanFirstFields(owner, knownRequest()) }
                as KnownVocabularyResult
        val continuation = knownRequest(cursor = requireNotNull(first.nextCursor), requestId = SECOND_REQUEST_ID)
        fixture.gateway.queryHandler = { _, _ ->
            throw ProviderGatewayException(ProviderFailureKind.TIMEOUT)
        }

        val failure =
            assertThrows(AnkiReadFailure::class.java) {
                fixture.withOwner { owner -> fixture.reads.scanFirstFields(owner, continuation) }
            }
        assertEquals(AnkiErrorCode.TIMEOUT, failure.code)
        assertFalse(failure.retryable)
        assertThrows(InvalidCapabilityException::class.java) {
            fixture.withOwner { owner -> fixture.reads.scanFirstFields(owner, continuation) }
        }
    }

    @Test
    fun `known vocabulary excludes parent scope once and preserves mixed snapshot order`() {
        val fixture = fixture()
        fixture.gateway.queryHandler = { query, _ ->
            when (query.endpoint) {
                ProviderEndpoint.NOTES_V2 -> {
                    if (query.selection == null) {
                        FakeProviderCursor(
                            query.projection,
                            listOf(1L, 2L, 3L).map { id -> mapOf(ProviderColumn.NOTE_ID to integer(id)) },
                        )
                    } else {
                        FakeProviderCursor(
                            query.projection,
                            listOf(
                                mapOf(
                                    ProviderColumn.NOTE_ID to integer(3L),
                                    ProviderColumn.NOTE_FIELDS to text("three\u001f"),
                                ),
                                mapOf(
                                    ProviderColumn.NOTE_ID to integer(1L),
                                    ProviderColumn.NOTE_FIELDS to text("one\u001f"),
                                ),
                            ),
                        )
                    }
                }
                ProviderEndpoint.DECKS ->
                    FakeProviderCursor(
                        query.projection,
                        listOf(
                            deckRow(20, "Parent"),
                            deckRow(21, "Parent::Child"),
                            deckRow(22, "Quote \\\" deck"),
                        ),
                    )
                ProviderEndpoint.NOTES_BROWSER ->
                    FakeProviderCursor(
                        query.projection,
                        listOf(mapOf(ProviderColumn.NOTE_ID to integer(2L))),
                    )
                else -> error("unexpected query $query")
            }
        }
        val result =
            fixture.withOwner { owner ->
                fixture.reads.scanFirstFields(
                    owner,
                    knownRequest(excluded = listOf("Parent::Child", "Parent")),
                )
            } as KnownVocabularyResult

        assertEquals(listOf("one", "three"), result.firstFields)
        val browserQueries = fixture.gateway.queries.filter { it.endpoint == ProviderEndpoint.NOTES_BROWSER }
        assertEquals(1, browserQueries.size)
        assertEquals(ProviderSelection.ExcludedDeck("Parent"), browserQueries.single().selection)
    }

    @Test
    fun `known vocabulary counts missing notes and closes every cursor on failure`() {
        val fixture = fixture()
        val snapshotCursor =
            FakeProviderCursor(
                listOf(ProviderColumn.NOTE_ID),
                listOf(
                    mapOf(ProviderColumn.NOTE_ID to integer(1L)),
                    mapOf(ProviderColumn.NOTE_ID to integer(2L)),
                ),
            )
        val pageCursor =
            FakeProviderCursor(
                listOf(ProviderColumn.NOTE_ID, ProviderColumn.NOTE_FIELDS),
                listOf(
                    mapOf(
                        ProviderColumn.NOTE_ID to integer(2L),
                        ProviderColumn.NOTE_FIELDS to text("two"),
                    ),
                ),
            )
        fixture.gateway.queryHandler = { query, _ ->
            if (query.selection == null) snapshotCursor else pageCursor
        }
        val result =
            fixture.withOwner { owner -> fixture.reads.scanFirstFields(owner, knownRequest()) }
                as KnownVocabularyResult
        assertEquals(listOf("two"), result.firstFields)
        assertEquals(2, result.scannedNotes)
        assertEquals(1, snapshotCursor.closeCount)
        assertEquals(1, pageCursor.closeCount)

        val malformed = fixture()
        val bad =
            FakeProviderCursor(
                listOf(ProviderColumn.NOTE_ID),
                listOf(
                    mapOf(ProviderColumn.NOTE_ID to integer(2L)),
                    mapOf(ProviderColumn.NOTE_ID to integer(1L)),
                ),
            )
        malformed.gateway.queryHandler = { _, _ -> bad }
        assertThrows(AnkiReadFailure::class.java) {
            malformed.withOwner { owner -> malformed.reads.scanFirstFields(owner, knownRequest()) }
        }
        assertEquals(1, bad.closeCount)
    }

    @Test
    fun `known vocabulary closes immediately at the 100001st note`() {
        val fixture = fixture()
        val cursor =
            GeneratedFakeProviderCursor(
                listOf(ProviderColumn.NOTE_ID),
                rowCount = 100_001,
                rowAt = { index ->
                    mapOf(ProviderColumn.NOTE_ID to integer(index + 1L))
                },
            )
        fixture.gateway.queryHandler = { _, _ -> cursor }

        val failure = assertThrows(AnkiReadFailure::class.java) {
            fixture.withOwner { owner -> fixture.reads.scanFirstFields(owner, knownRequest()) }
        }
        assertEquals(AnkiErrorCode.QUERY_FAILED, failure.code)
        assertEquals(false, failure.retryable)
        assertEquals(
            "Known-word filtering supports at most 100000 notes in an Anki collection",
            failure.stableMessage,
        )
        assertEquals(1, cursor.closeCount)
    }

    @Test
    fun `known vocabulary distinguishes null empty and exact 100000 note snapshots`() {
        val nullFixture = fixture()
        nullFixture.gateway.queryHandler = { _, _ -> null }
        assertEquals(
            AnkiErrorCode.QUERY_FAILED,
            assertThrows(AnkiReadFailure::class.java) {
                nullFixture.withOwner { owner ->
                    nullFixture.reads.scanFirstFields(owner, knownRequest())
                }
            }.code,
        )

        val emptyFixture = fixture()
        emptyFixture.gateway.queryHandler = { query, _ ->
            FakeProviderCursor(query.projection, emptyList())
        }
        val empty =
            emptyFixture.withOwner { owner ->
                emptyFixture.reads.scanFirstFields(owner, knownRequest())
            } as KnownVocabularyResult
        assertEquals(emptyList<String>(), empty.firstFields)
        assertEquals(0, empty.scannedNotes)
        assertNull(empty.nextCursor)

        val boundaryFixture = fixture(tokens = listOf("cursor_${"9".repeat(32)}"))
        val snapshot =
            GeneratedFakeProviderCursor(
                ProviderQueryShapes.NOTE_ID_PROJECTION,
                rowCount = 100_000,
                rowAt = { index ->
                    mapOf(ProviderColumn.NOTE_ID to integer(index + 1L))
                },
            )
        boundaryFixture.gateway.queryHandler = { query, _ ->
            when {
                query.selection == null -> snapshot
                query.selection is ProviderSelection.NoteIds ->
                    FakeProviderCursor(query.projection, emptyList())
                else -> error("unexpected query $query")
            }
        }
        val boundary =
            boundaryFixture.withOwner { owner ->
                boundaryFixture.reads.scanFirstFields(owner, knownRequest())
            } as KnownVocabularyResult
        assertEquals(256, boundary.scannedNotes)
        assertEquals(emptyList<String>(), boundary.firstFields)
        assertEquals(1L, boundary.nextCursor?.ordinal)
        assertEquals(1, snapshot.closeCount)
    }

    @Test
    fun `known vocabulary rejects snapshot page and continuation cursor corruption`() {
        fun pageFailure(rows: List<Map<ProviderColumn, ProviderCell>>): AnkiReadFailure {
            val fixture = fixture(tokens = listOf("cursor_${"8".repeat(32)}"))
            fixture.gateway.queryHandler = { query, _ ->
                when {
                    query.selection == null ->
                        FakeProviderCursor(
                            query.projection,
                            listOf(
                                mapOf(ProviderColumn.NOTE_ID to integer(1L)),
                                mapOf(ProviderColumn.NOTE_ID to integer(2L)),
                            ),
                        )
                    query.selection is ProviderSelection.NoteIds ->
                        FakeProviderCursor(query.projection, rows)
                    else -> error("unexpected query $query")
                }
            }
            return assertThrows(AnkiReadFailure::class.java) {
                fixture.withOwner { owner ->
                    fixture.reads.scanFirstFields(owner, knownRequest())
                }
            }
        }

        assertEquals(
            AnkiErrorCode.QUERY_FAILED,
            pageFailure(
                listOf(
                    mapOf(
                        ProviderColumn.NOTE_ID to integer(3L),
                        ProviderColumn.NOTE_FIELDS to text("unexpected"),
                    ),
                ),
            ).code,
        )
        assertEquals(
            AnkiErrorCode.QUERY_FAILED,
            pageFailure(
                listOf(
                    mapOf(
                        ProviderColumn.NOTE_ID to integer(1L),
                        ProviderColumn.NOTE_FIELDS to text("one"),
                    ),
                    mapOf(
                        ProviderColumn.NOTE_ID to integer(1L),
                        ProviderColumn.NOTE_FIELDS to text("duplicate"),
                    ),
                ),
            ).code,
        )

        val continuationFixture = fixture(tokens = listOf("cursor_${"7".repeat(32)}"))
        continuationFixture.gateway.queryHandler = { query, _ ->
            when {
                query.selection == null ->
                    GeneratedFakeProviderCursor(
                        query.projection,
                        rowCount = 257,
                        rowAt = { index ->
                            mapOf(ProviderColumn.NOTE_ID to integer(index + 1L))
                        },
                    )
                query.selection is ProviderSelection.NoteIds ->
                    FakeProviderCursor(query.projection, emptyList())
                else -> error("unexpected query $query")
            }
        }
        val first =
            continuationFixture.withOwner { owner ->
                continuationFixture.reads.scanFirstFields(owner, knownRequest())
            } as KnownVocabularyResult
        val corrupt =
            requireNotNull(first.nextCursor).copy(
                token = "cursor_${"6".repeat(32)}",
            )
        assertThrows(InvalidCapabilityException::class.java) {
            continuationFixture.withOwner { owner ->
                continuationFixture.reads.scanFirstFields(
                    owner,
                    knownRequest(cursor = corrupt, requestId = SECOND_REQUEST_ID),
                )
            }
        }
    }

    @Test
    fun `excluded deck browser scan closes at row 1000001 before reading its cells`() {
        val fixture = fixture()
        var browserCellReads = 0
        val browserCursor =
            GeneratedFakeProviderCursor(
                listOf(ProviderColumn.NOTE_ID),
                rowCount = 1_000_001,
                rowAt = { index ->
                    mapOf(ProviderColumn.NOTE_ID to integer(index + 1L))
                },
                beforeCell = { browserCellReads += 1 },
            )
        fixture.gateway.queryHandler = { query, _ ->
            when (query.endpoint) {
                ProviderEndpoint.NOTES_V2 ->
                    FakeProviderCursor(
                        query.projection,
                        listOf(mapOf(ProviderColumn.NOTE_ID to integer(1L))),
                    )
                ProviderEndpoint.DECKS ->
                    FakeProviderCursor(query.projection, listOf(deckRow(name = "Excluded")))
                ProviderEndpoint.NOTES_BROWSER -> browserCursor
                else -> error("unexpected query $query")
            }
        }

        val failure =
            assertThrows(AnkiReadFailure::class.java) {
                fixture.withOwner { owner ->
                    fixture.reads.scanFirstFields(
                        owner,
                        knownRequest(excluded = listOf("Excluded")),
                    )
                }
            }
        // Same non-retryable refusal as the unexcluded scan. The connection class is deliberate:
        // the protocol class reaches the user as an unhandled app bug, and an over-large collection
        // is a condition of theirs, not a protocol violation.
        assertEquals(AnkiErrorCode.QUERY_FAILED, failure.code)
        assertEquals(false, failure.retryable)
        // The refusal names the excluded decks and their own budget, not the result ceiling: these
        // rows are subtracted from the scan rather than counted into it.
        assertEquals(
            "Known-word filtering scans at most 1000000 notes in the excluded Anki decks",
            failure.stableMessage,
        )
        assertEquals(1_000_000, browserCellReads)
        assertEquals(1, browserCursor.closeCount)
    }

    @Test
    fun `a large excluded deck does not abort a small deck-scoped scan`() {
        val fixture = fixture()
        fixture.gateway.queryHandler = targetQueryHandler()
        fixture.withOwner { owner -> fixture.verifyExistingTarget(owner, verifyRequest()) }
        fixture.gateway.queries.clear()
        // The target holds one note; the excluded deck holds far more rows than the note ceiling.
        // Spending the result ceiling on them aborted the run, while the identical run without the
        // exclusion configured succeeded.
        val browserCursor =
            GeneratedFakeProviderCursor(
                listOf(ProviderColumn.NOTE_ID),
                rowCount = 150_000,
                rowAt = { index -> mapOf(ProviderColumn.NOTE_ID to integer(index + 2L)) },
            )
        fixture.gateway.queryHandler = { query, _ ->
            when {
                query.endpoint == ProviderEndpoint.CARDS ->
                    FakeProviderCursor(
                        query.projection,
                        listOf(
                            mapOf(
                                ProviderColumn.CARD_NOTE_ID to integer(1L),
                                ProviderColumn.CARD_DECK_ID to integer(20L),
                                ProviderColumn.CARD_ORIGINAL_DECK_ID to integer(0L),
                            ),
                        ),
                    )
                query.endpoint == ProviderEndpoint.DECKS ->
                    FakeProviderCursor(query.projection, listOf(deckRow(name = "Core")))
                query.endpoint == ProviderEndpoint.NOTES_BROWSER -> browserCursor
                query.selection is ProviderSelection.NoteIds ->
                    FakeProviderCursor(
                        query.projection,
                        listOf(
                            mapOf(
                                ProviderColumn.NOTE_ID to integer(1L),
                                ProviderColumn.NOTE_FIELDS to text("one\u001fmeaning"),
                            ),
                        ),
                    )
                else -> error("unexpected query $query")
            }
        }

        val result =
            fixture.withOwner { owner ->
                fixture.reads.scanFirstFields(
                    owner,
                    knownRequest(deckName = "Mining", excluded = listOf("Core")),
                )
            } as KnownVocabularyResult

        assertEquals(listOf("one"), result.firstFields)
        assertEquals(1, result.scannedNotes)
    }

    @Test
    fun `known initial and continuation pages preflight escape-expanded responses`() {
        val fixture = fixture(tokens = listOf("cursor_${"e".repeat(32)}"))
        val escapeHeavy = "\u0001".repeat(1024)
        fixture.gateway.queryHandler = { query, _ ->
            when {
                query.endpoint == ProviderEndpoint.NOTES_V2 && query.selection == null ->
                    FakeProviderCursor(
                        query.projection,
                        (1L..257L).map { id -> mapOf(ProviderColumn.NOTE_ID to integer(id)) },
                    )
                query.selection is ProviderSelection.NoteIds -> {
                    val ids = (query.selection as ProviderSelection.NoteIds).ids
                    FakeProviderCursor(
                        query.projection,
                        ids.map { id ->
                            mapOf(
                                ProviderColumn.NOTE_ID to integer(id),
                                ProviderColumn.NOTE_FIELDS to text(escapeHeavy),
                            )
                        },
                    )
                }
                else -> error("unexpected query $query")
            }
        }

        val first =
            fixture.withOwner { owner -> fixture.reads.scanFirstFields(owner, knownRequest()) }
                as KnownVocabularyResult
        assertEquals(256, first.firstFields.size)
        assertTrue(first.firstFields.all { it == escapeHeavy })
        val continuation =
            fixture.withOwner { owner ->
                fixture.reads.scanFirstFields(
                    owner,
                    knownRequest(
                        cursor = requireNotNull(first.nextCursor),
                        requestId = SECOND_REQUEST_ID,
                    ),
                )
            } as KnownVocabularyResult
        assertEquals(listOf(escapeHeavy), continuation.firstFields)
        assertNull(continuation.nextCursor)
    }

    @Test
    fun `duplicate snapshot preserves checksum collisions and atomically replaces baseline`() {
        val fixture =
            fixture(
                tokens =
                    listOf(
                        "baseline_${"a".repeat(32)}",
                        "baseline_${"b".repeat(32)}",
                    ),
            )
        fixture.gateway.checksum = { 42L }
        fixture.gateway.queryHandler = targetThenDuplicateHandler()
        fixture.withOwner { owner -> fixture.verifyExistingTarget(owner, verifyRequest()) }
        val scope =
            DuplicateScanScope(
                modelName = "Mining",
                firstFieldName = "Expression",
                deckName = null,
                candidates =
                    listOf(
                        DuplicateCandidate("cat", "<b>cat</b>"),
                        DuplicateCandidate("dog", "dog"),
                    ),
                occurrences = listOf(0, 1),
                invalidateBaselineToken = null,
            )
        val first =
            fixture.withOwner { owner ->
                fixture.reads.scanFirstFields(owner, duplicateRequest(scope))
            } as DuplicateLookupResult
        assertEquals(listOf(31L, 32L), first.rawFirstFieldHits[0].map { it.noteId })
        assertEquals(listOf(31L, 32L), first.rawFirstFieldHits[1].map { it.noteId })
        val selection =
            fixture.gateway.queries.last { it.selection is ProviderSelection.DuplicateChecksums }.selection
        assertEquals(
            ProviderSelection.DuplicateChecksums(10L, listOf(42L)),
            selection,
        )

        val replacementScope = scope.copy(invalidateBaselineToken = first.baselineToken)
        val second =
            fixture.withOwner { owner ->
                fixture.reads.scanFirstFields(
                    owner,
                    duplicateRequest(replacementScope, SECOND_REQUEST_ID),
                )
            } as DuplicateLookupResult
        assertEquals("baseline_${"b".repeat(32)}", second.baselineToken)
        assertThrows(InvalidCapabilityException::class.java) {
            fixture.withOwner { owner -> fixture.registry.consumeBaseline(owner, first.baselineToken) }
        }
    }

    @Test
    fun `duplicate baseline is consumed only after the exact response can be encoded`() {
        val firstToken = "baseline_${"a".repeat(32)}"
        val secondToken = "baseline_${"b".repeat(32)}"
        val fixture = fixture(tokens = listOf(firstToken, secondToken))
        fixture.gateway.checksum = { 42L }
        fixture.gateway.queryHandler = targetThenDuplicateHandler()
        fixture.withOwner { owner -> fixture.verifyExistingTarget(owner, verifyRequest()) }
        val initialScope =
            DuplicateScanScope(
                modelName = "Mining",
                firstFieldName = "Expression",
                deckName = null,
                candidates = listOf(DuplicateCandidate("control", "control")),
                occurrences = listOf(0),
                invalidateBaselineToken = null,
            )
        val initial =
            fixture.withOwner { owner ->
                fixture.reads.scanFirstFields(owner, duplicateRequest(initialScope))
            } as DuplicateLookupResult
        assertEquals(firstToken, initial.baselineToken)

        val escapeHeavyField = "\u0001".repeat(65_536)
        fixture.gateway.queryHandler = { query, _ ->
            check(query.endpoint == ProviderEndpoint.NOTES_V2)
            FakeProviderCursor(
                query.projection,
                (1L..6L).map { id ->
                    mapOf(
                        ProviderColumn.NOTE_ID to integer(id),
                        ProviderColumn.NOTE_FIELDS to text(escapeHeavyField),
                        ProviderColumn.NOTE_CHECKSUM to integer(42L),
                    )
                },
            )
        }
        val failure =
            assertThrows(AnkiReadFailure::class.java) {
                fixture.withOwner { owner ->
                    fixture.reads.scanFirstFields(
                        owner,
                        duplicateRequest(
                            initialScope.copy(invalidateBaselineToken = firstToken),
                            SECOND_REQUEST_ID,
                        ),
                    )
                }
            }
        assertEquals(AnkiErrorCode.QUERY_FAILED, failure.code)
        assertFalse(failure.retryable)
        fixture.withOwner { owner ->
            assertThrows(InvalidCapabilityException::class.java) {
                fixture.registry.consumeBaseline(owner, firstToken)
            }
            assertThrows(InvalidCapabilityException::class.java) {
                fixture.registry.consumeBaseline(owner, secondToken)
            }
        }
    }

    @Test
    fun `duplicate hit bounds accept 100 per candidate and 1000 total then reject N plus one`() {
        fun resultFor(
            candidateCount: Int,
            providerRows: Int,
        ): Result<DuplicateLookupResult> {
            val fixture = fixture(tokens = listOf("baseline_${"4".repeat(32)}"))
            fixture.gateway.checksum = { 42L }
            fixture.gateway.queryHandler = { query, _ ->
                when (query.endpoint) {
                    ProviderEndpoint.MODELS ->
                        FakeProviderCursor(query.projection, listOf(modelRow()))
                    ProviderEndpoint.MODEL_TEMPLATES ->
                        FakeProviderCursor(query.projection, listOf(templateRow()))
                    ProviderEndpoint.DECKS ->
                        FakeProviderCursor(query.projection, listOf(deckRow()))
                    ProviderEndpoint.NOTES_V2 ->
                        GeneratedFakeProviderCursor(
                            query.projection,
                            rowCount = providerRows,
                            rowAt = { index ->
                                mapOf(
                                    ProviderColumn.NOTE_ID to integer(index + 1L),
                                    ProviderColumn.NOTE_FIELDS to text("cat"),
                                    ProviderColumn.NOTE_CHECKSUM to integer(42L),
                                )
                            },
                        )
                    else -> error("unexpected query $query")
                }
            }
            fixture.withOwner { owner -> fixture.verifyExistingTarget(owner, verifyRequest()) }
            val scope =
                DuplicateScanScope(
                    modelName = "Mining",
                    firstFieldName = "Expression",
                    deckName = null,
                    candidates =
                        (0 until candidateCount).map { index ->
                            DuplicateCandidate("key-$index", "key-$index")
                        },
                    occurrences = (0 until candidateCount).toList(),
                    invalidateBaselineToken = null,
                )
            return runCatching {
                fixture.withOwner { owner ->
                    fixture.reads.scanFirstFields(owner, duplicateRequest(scope))
                        as DuplicateLookupResult
                }
            }
        }

        val perCandidateBoundary = resultFor(candidateCount = 1, providerRows = 100)
        assertEquals(100, perCandidateBoundary.getOrThrow().rawFirstFieldHits.single().size)
        assertEquals(
            AnkiErrorCode.QUERY_FAILED,
            (resultFor(candidateCount = 1, providerRows = 101).exceptionOrNull() as AnkiReadFailure).code,
        )

        val totalBoundary = resultFor(candidateCount = 10, providerRows = 100)
        assertEquals(
            1000,
            totalBoundary.getOrThrow().rawFirstFieldHits.sumOf { hits -> hits.size },
        )
        assertEquals(
            AnkiErrorCode.QUERY_FAILED,
            (resultFor(candidateCount = 11, providerRows = 91).exceptionOrNull() as AnkiReadFailure).code,
        )
    }

    @Test
    fun `exact-deck duplicate filter reads each notes cards endpoint once`() {
        val fixture = fixture(tokens = listOf("baseline_${"c".repeat(32)}"))
        fixture.gateway.checksum = { 42L }
        fixture.gateway.queryHandler = targetThenDuplicateHandler(exactDeck = true)
        fixture.withOwner { owner -> fixture.verifyExistingTarget(owner, verifyRequest()) }
        val result =
            fixture.withOwner { owner ->
                fixture.reads.scanFirstFields(
                    owner,
                    duplicateRequest(
                        DuplicateScanScope(
                            "Mining",
                            "Expression",
                            "Mining",
                            listOf(DuplicateCandidate("cat", "cat")),
                            listOf(0),
                            null,
                        ),
                    ),
                )
            } as DuplicateLookupResult
        assertEquals(listOf(31L), result.rawFirstFieldHits.single().map { it.noteId })
        val cardReads = fixture.gateway.queries.filter { it.endpoint == ProviderEndpoint.CARDS_FOR_NOTE }
        assertEquals(listOf(31L, 32L), cardReads.map { it.endpointId })
        assertTrue(cardReads.all { it.projection == ProviderQueryShapes.CARD_IDENTITY_PROJECTION })
        assertTrue(fixture.gateway.queries.none { it.endpoint == ProviderEndpoint.CARDS })
        val directReads = fixture.gateway.queries.filter { it.endpoint == ProviderEndpoint.CARD_BY_ID }
        assertTrue(directReads.isEmpty())
    }

    @Test
    fun `exact-deck duplicate rejects more cards than the verified template count`() {
        val fixture = fixture(tokens = listOf("baseline_${"d".repeat(32)}"))
        fixture.gateway.checksum = { 42L }
        fixture.gateway.queryHandler = targetThenDuplicateHandler(exactDeck = true, malformedCardCount = true)
        fixture.withOwner { owner -> fixture.verifyExistingTarget(owner, verifyRequest()) }
        val failure =
            assertThrows(AnkiReadFailure::class.java) {
                fixture.withOwner { owner ->
                    fixture.reads.scanFirstFields(
                        owner,
                        duplicateRequest(
                            DuplicateScanScope(
                                "Mining",
                                "Expression",
                                "Mining",
                                listOf(DuplicateCandidate("cat", "cat")),
                                listOf(0),
                                null,
                            ),
                        ),
                    )
                }
            }
        assertEquals(AnkiErrorCode.QUERY_FAILED, failure.code)
    }

    @Test
    fun `cards-for-note read rejects malformed ids note ids ordinals decks and duplicate ordinals`() {
        data class CardCase(
            val name: String,
            val cardCount: Int = 1,
            val discoveredIds: List<Long>,
            val row: (Long) -> Map<ProviderColumn, ProviderCell>,
        )

        val cases =
            listOf(
                CardCase("missing card ID", discoveredIds = emptyList()) { id ->
                    cardRow(id, 31L, 0L, 20L)
                },
                CardCase("non-positive discovery ID", discoveredIds = listOf(0L)) { id ->
                    cardRow(id, 31L, 0L, 20L)
                },
                CardCase("duplicate discovery ID", discoveredIds = listOf(131L, 131L)) { id ->
                    cardRow(id, 31L, 0L, 20L)
                },
                CardCase("returned note ID", discoveredIds = listOf(131L)) { id ->
                    cardRow(id, 32L, 0L, 20L)
                },
                CardCase("returned ordinal", discoveredIds = listOf(131L)) { id ->
                    cardRow(id, 31L, 1L, 20L)
                },
                CardCase("returned deck", discoveredIds = listOf(131L)) { id ->
                    cardRow(id, 31L, 0L, 0L)
                },
                CardCase(
                    "duplicate ordinal",
                    cardCount = 2,
                    discoveredIds = listOf(131L, 132L),
                ) { id ->
                    cardRow(id, 31L, 0L, 20L)
                },
            )
        for (case in cases) {
            val fixture = fixture(tokens = listOf("baseline_${"f".repeat(32)}"))
            fixture.gateway.checksum = { 42L }
            fixture.gateway.queryHandler = { query, _ ->
                when (query.endpoint) {
                    ProviderEndpoint.MODELS ->
                        FakeProviderCursor(
                            query.projection,
                            listOf(modelRow(cards = case.cardCount.toLong())),
                        )
                    ProviderEndpoint.MODEL_TEMPLATES ->
                        FakeProviderCursor(
                            query.projection,
                            (0 until case.cardCount).map { ordinal ->
                                templateRow(ordinal = ordinal.toLong())
                            },
                        )
                    ProviderEndpoint.DECKS ->
                        FakeProviderCursor(query.projection, listOf(deckRow()))
                    ProviderEndpoint.NOTES_V2 ->
                        FakeProviderCursor(
                            query.projection,
                            listOf(
                                mapOf(
                                    ProviderColumn.NOTE_ID to integer(31L),
                                    ProviderColumn.NOTE_FIELDS to text("cat"),
                                    ProviderColumn.NOTE_CHECKSUM to integer(42L),
                                ),
                            ),
                        )
                    ProviderEndpoint.CARDS_FOR_NOTE ->
                        FakeProviderCursor(
                            query.projection,
                            case.discoveredIds.map(case.row),
                        )
                    else -> error("unexpected query $query")
                }
            }
            fixture.withOwner { owner -> fixture.verifyExistingTarget(owner, verifyRequest()) }
            val failure =
                assertThrows(case.name, AnkiReadFailure::class.java) {
                    fixture.withOwner { owner ->
                        fixture.reads.scanFirstFields(
                            owner,
                            duplicateRequest(
                                DuplicateScanScope(
                                    modelName = "Mining",
                                    firstFieldName = "Expression",
                                    deckName = "Mining",
                                    candidates = listOf(DuplicateCandidate("cat", "cat")),
                                    occurrences = listOf(0),
                                    invalidateBaselineToken = null,
                                ),
                            ),
                        )
                    }
                }
            assertEquals(case.name, AnkiErrorCode.QUERY_FAILED, failure.code)
        }
    }

    @Test
    fun `provider availability and cancellation map deterministically before query`() {
        val statuses =
            listOf(
                ProviderAccessStatus.Absent to AnkiErrorCode.PROVIDER_UNAVAILABLE,
                ProviderAccessStatus.ApiDisabled to AnkiErrorCode.API_DISABLED,
                ProviderAccessStatus.Incompatible(1) to AnkiErrorCode.API_DISABLED,
                ProviderAccessStatus.PermissionRequired to AnkiErrorCode.PERMISSION_REQUIRED,
            )
        for ((status, code) in statuses) {
            val fixture = fixture()
            fixture.gateway.status = status
            val failure = assertThrows(AnkiReadFailure::class.java) {
                fixture.withOwner { owner -> fixture.reads.scanFirstFields(owner, knownRequest()) }
            }
            assertEquals(code, failure.code)
            assertTrue(fixture.gateway.queries.isEmpty())
        }

        val cancellation = MutableAnkiCancellation().also(MutableAnkiCancellation::cancel)
        val fixture = fixture(cancellation = cancellation)
        assertThrows(RunCancelledException::class.java) {
            fixture.withOwner { owner -> fixture.reads.scanFirstFields(owner, knownRequest()) }
        }
        assertTrue(fixture.gateway.queries.isEmpty())
    }

    @Test
    fun `field splitter preserves empty and exact space values`() {
        assertEquals(
            listOf("", " ", "", ""),
            ProviderSnapshotValidation.splitFieldsPreservingTrailing("\u001f \u001f\u001f"),
        )
    }

    private fun targetThenDuplicateHandler(
        exactDeck: Boolean = false,
        malformedCardCount: Boolean = false,
    ): (ProviderQuery, AnkiCancellation) -> ProviderCursor? =
        { query, _ ->
            when (query.endpoint) {
                ProviderEndpoint.MODELS -> FakeProviderCursor(query.projection, listOf(modelRow()))
                ProviderEndpoint.MODEL_TEMPLATES -> FakeProviderCursor(query.projection, listOf(templateRow()))
                ProviderEndpoint.DECKS -> FakeProviderCursor(query.projection, listOf(deckRow()))
                ProviderEndpoint.NOTES_V2 ->
                    FakeProviderCursor(
                        query.projection,
                        listOf(
                            mapOf(
                                ProviderColumn.NOTE_ID to integer(31L),
                                ProviderColumn.NOTE_FIELDS to text("<b>cat</b>\u001fmeaning"),
                                ProviderColumn.NOTE_CHECKSUM to integer(42L),
                            ),
                            mapOf(
                                ProviderColumn.NOTE_ID to integer(32L),
                                ProviderColumn.NOTE_FIELDS to text("dog\u001fmeaning"),
                                ProviderColumn.NOTE_CHECKSUM to integer(42L),
                            ),
                        ),
                    )
                ProviderEndpoint.CARDS_FOR_NOTE -> {
                    check(exactDeck)
                    val noteId = requireNotNull(query.endpointId)
                    FakeProviderCursor(
                        query.projection,
                        buildList {
                            add(cardRow(noteId + 100L, noteId, 0L, if (noteId == 31L) 20L else 99L))
                            if (malformedCardCount) {
                                add(cardRow(noteId + 200L, noteId, 1L, 99L))
                            }
                        },
                    )
                }
                else -> error("unexpected query $query")
            }
        }

    private fun targetQueryHandler(
        model: Map<ProviderColumn, ProviderCell> = modelRow(),
        deck: Map<ProviderColumn, ProviderCell> = deckRow(),
        models: List<Map<ProviderColumn, ProviderCell>> = listOf(model),
        decks: List<Map<ProviderColumn, ProviderCell>> = listOf(deck),
        templates: List<Map<ProviderColumn, ProviderCell>> = listOf(templateRow()),
    ): (ProviderQuery, AnkiCancellation) -> ProviderCursor? =
        { query, _ ->
            when (query.endpoint) {
                ProviderEndpoint.MODELS -> FakeProviderCursor(query.projection, models)
                ProviderEndpoint.MODEL_TEMPLATES -> FakeProviderCursor(query.projection, templates)
                ProviderEndpoint.DECKS -> FakeProviderCursor(query.projection, decks)
                else -> error("unexpected query $query")
            }
        }

    private fun fixture(
        tokens: List<String> = emptyList(),
        cancellation: AnkiCancellation = AnkiCancellation.NONE,
    ): Fixture {
        val gateway = FakeAnkiProviderGateway()
        val registry = AnkiRunStateRegistry()
        assertTrue(registry.register(RUN_ID, cancellation))
        val queue = ArrayDeque(tokens)
        val tokenFactory = OpaqueTokenFactory { prefix ->
            if (queue.isEmpty()) "$prefix${"0".repeat(32)}" else queue.removeFirst()
        }
        return Fixture(gateway, registry, AnkiProviderReadService(gateway, registry, tokenFactory))
    }

    private data class Fixture(
        val gateway: FakeAnkiProviderGateway,
        val registry: AnkiRunStateRegistry,
        val reads: AnkiProviderReadService,
    ) {
        fun <T> withOwner(block: (AnkiRunStateRegistry.RunOwner) -> T): T =
            registry.withOwner(RUN_ID, block)

        fun verifyExistingTarget(
            owner: AnkiRunStateRegistry.RunOwner,
            request: VerifyTargetRequest,
        ): TargetSnapshot {
            val reservation = registry.beginTargetVerification(owner, request)
            return try {
                val target = reads.readExistingTarget(owner, request)
                registry.commitDurableTargetResponse(owner, reservation, request.requestId, target)
                target
            } catch (error: Throwable) {
                registry.abortTargetVerification(owner, reservation)
                throw error
            }
        }
    }

    private fun verifyRequest(required: List<String> = listOf("Expression")) =
        VerifyTargetRequest(RUN_ID, REQUEST_ID, "Mining", "Mining", required)

    /** Rows all in the verified target deck (20), [cardsPerNote] consecutive rows per note. */
    private fun exactDeckCardCursor(
        rowCount: Int,
        cardsPerNote: Int,
        beforeCell: (ProviderColumn) -> Unit = {},
    ) = GeneratedFakeProviderCursor(
        ProviderQueryShapes.CARD_NOTE_DECK_PROJECTION,
        rowCount = rowCount,
        rowAt = { index ->
            mapOf(
                ProviderColumn.CARD_NOTE_ID to integer(index / cardsPerNote + 1L),
                ProviderColumn.CARD_DECK_ID to integer(20L),
                ProviderColumn.CARD_ORIGINAL_DECK_ID to integer(0L),
            )
        },
        beforeCell = beforeCell,
    )

    /** A verified "Mining" target whose CARDS traversal is [cardCursor] and whose pages resolve. */
    private fun deckCardScanFixture(cardCursor: GeneratedFakeProviderCursor): Fixture {
        val fixture = fixture()
        fixture.gateway.queryHandler = targetQueryHandler()
        fixture.withOwner { owner -> fixture.verifyExistingTarget(owner, verifyRequest()) }
        fixture.gateway.queries.clear()
        fixture.gateway.queryHandler = { query, _ ->
            when {
                query.endpoint == ProviderEndpoint.CARDS -> cardCursor
                query.selection is ProviderSelection.NoteIds ->
                    FakeProviderCursor(
                        query.projection,
                        (query.selection as ProviderSelection.NoteIds).ids.map { id ->
                            mapOf(
                                ProviderColumn.NOTE_ID to integer(id),
                                ProviderColumn.NOTE_FIELDS to text("word-$id\u001fmeaning"),
                            )
                        },
                    )
                else -> error("unexpected query $query")
            }
        }
        return fixture
    }

    private fun knownRequest(
        excluded: List<String> = emptyList(),
        cursor: com.ankiminer.android.anki.protocol.KnownVocabularyCursor? = null,
        requestId: String = REQUEST_ID,
        deckName: String? = null,
    ) =
        ScanFirstFieldsRequest(
            RUN_ID,
            requestId,
            KnownVocabularyScope(excluded, cursor, deckName),
        )

    private fun duplicateRequest(
        scope: DuplicateScanScope,
        requestId: String = REQUEST_ID,
    ) = ScanFirstFieldsRequest(RUN_ID, requestId, scope)

    private companion object {
        const val RUN_ID = "run_11111111111111111111111111111111"
        const val REQUEST_ID = "anki_11111111111111111111111111111111"
        const val SECOND_REQUEST_ID = "anki_22222222222222222222222222222222"
    }
}
