package com.ankiminer.android.anki.provider

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderQueryContractTest {
    @Test
    fun `v2 note page translation uses pinned alias and only selection arguments`() {
        val query =
            ProviderQuery(
                endpoint = ProviderEndpoint.NOTES_V2,
                projection = ProviderQueryShapes.NOTE_PAGE_PROJECTION,
                selection = ProviderSelection.NoteIds(listOf(7L, 9L)),
            )
        val compiled =
            compileProviderSelection(
                query = query,
                noteIdColumn = "_id",
                modelIdColumn = "mid",
                checksumColumn = "csum",
            )

        assertEquals("_id IN (?,?)", compiled.text)
        assertEquals(listOf("7", "9"), compiled.arguments)
    }

    @Test
    fun `duplicate translation parameterizes model and every checksum`() {
        val query =
            ProviderQuery(
                endpoint = ProviderEndpoint.NOTES_V2,
                projection = ProviderQueryShapes.DUPLICATE_PROJECTION,
                selection =
                    ProviderSelection.DuplicateChecksums(
                        10L,
                        listOf(1L, 4_294_967_295L),
                    ),
                sortOrder = ProviderOrder.NOTE_ID_ASCENDING,
            )
        val compiled =
            compileProviderSelection(
                query = query,
                noteIdColumn = "_id",
                modelIdColumn = "mid",
                checksumColumn = "csum",
            )

        assertEquals("mid = ? AND csum IN (?,?)", compiled.text)
        assertEquals(listOf("10", "1", "4294967295"), compiled.arguments)
    }

    @Test
    fun `duplicate checksum query permits 100 candidates with only 101 bind arguments`() {
        val checksums = (0L until 100L).toList()
        val query =
            ProviderQuery(
                endpoint = ProviderEndpoint.NOTES_V2,
                projection = ProviderQueryShapes.DUPLICATE_PROJECTION,
                selection = ProviderSelection.DuplicateChecksums(10L, checksums),
                sortOrder = ProviderOrder.NOTE_ID_ASCENDING,
            )
        val compiled =
            compileProviderSelection(
                query = query,
                noteIdColumn = "_id",
                modelIdColumn = "mid",
                checksumColumn = "csum",
            )

        assertEquals(101, requireNotNull(compiled.arguments).size)
        assertEquals("10", compiled.arguments.first())
        assertEquals("99", compiled.arguments.last())
        assertThrows(IllegalArgumentException::class.java) {
            ProviderQuery(
                endpoint = ProviderEndpoint.NOTES_V2,
                projection = ProviderQueryShapes.DUPLICATE_PROJECTION,
                selection = ProviderSelection.DuplicateChecksums(10L, (0L..100L).toList()),
                sortOrder = ProviderOrder.NOTE_ID_ASCENDING,
            )
        }
    }

    @Test
    fun `gateway alone compiles typed browser selections with exact escaping`() {
        val cards =
            ProviderQuery(
                endpoint = ProviderEndpoint.CARDS,
                projection = ProviderQueryShapes.CARD_ID_PROJECTION,
                selection = ProviderSelection.CardsForNote(12L),
            )
        assertEquals(
            CompiledProviderSelection("nid:12", null),
            compileProviderSelection(
                cards,
                noteIdColumn = "_id",
                modelIdColumn = "mid",
                checksumColumn = "csum",
            ),
        )
        val excluded =
            ProviderQuery(
                endpoint = ProviderEndpoint.NOTES_BROWSER,
                projection = ProviderQueryShapes.NOTE_ID_PROJECTION,
                selection = ProviderSelection.ExcludedDeck("Quote \\\" deck"),
            )
        assertEquals(
            CompiledProviderSelection("deck:\"Quote \\\\\\\" deck\"", null),
            compileProviderSelection(
                excluded,
                noteIdColumn = "_id",
                modelIdColumn = "mid",
                checksumColumn = "csum",
            ),
        )
        listOf(
            "Core_2k" to "deck:\"Core\\_2k\"",
            "Wild*Card" to "deck:\"Wild\\*Card\"",
        ).forEach { (deckName, expected) ->
            assertEquals(
                CompiledProviderSelection(expected, null),
                compileProviderSelection(
                    ProviderQuery(
                        endpoint = ProviderEndpoint.NOTES_BROWSER,
                        projection = ProviderQueryShapes.NOTE_ID_PROJECTION,
                        selection = ProviderSelection.ExcludedDeck(deckName),
                    ),
                    noteIdColumn = "_id",
                    modelIdColumn = "mid",
                    checksumColumn = "csum",
                ),
            )
        }
        val deckCards =
            ProviderQuery(
                endpoint = ProviderEndpoint.CARDS,
                projection = ProviderQueryShapes.CARD_NOTE_DECK_PROJECTION,
                selection = ProviderSelection.CardsInDeck("Quote \\\" deck"),
            )
        assertEquals(
            CompiledProviderSelection("deck:\"Quote \\\\\\\" deck\"", null),
            compileProviderSelection(
                deckCards,
                noteIdColumn = "_id",
                modelIdColumn = "mid",
                checksumColumn = "csum",
            ),
        )
    }

    @Test
    fun `every production query shape is admitted exactly`() {
        val legal =
            listOf(
                ProviderQuery(
                    ProviderEndpoint.NOTES_BROWSER,
                    projection = ProviderQueryShapes.NOTE_ID_PROJECTION,
                    selection = ProviderSelection.ExcludedDeck("Mining"),
                ),
                ProviderQuery(
                    ProviderEndpoint.NOTES_V2,
                    projection = ProviderQueryShapes.NOTE_ID_PROJECTION,
                    sortOrder = ProviderOrder.NOTE_ID_ASCENDING,
                ),
                ProviderQuery(
                    ProviderEndpoint.NOTE_BY_ID,
                    endpointId = 7L,
                    projection = ProviderQueryShapes.NOTE_SNAPSHOT_PROJECTION,
                ),
                ProviderQuery(
                    ProviderEndpoint.NOTES_V2,
                    projection = ProviderQueryShapes.NOTE_PAGE_PROJECTION,
                    selection = ProviderSelection.NoteIds(listOf(1L, 2L)),
                ),
                ProviderQuery(
                    ProviderEndpoint.NOTES_V2,
                    projection = ProviderQueryShapes.DUPLICATE_PROJECTION,
                    selection = ProviderSelection.DuplicateChecksums(10L, listOf(0L, 1L)),
                    sortOrder = ProviderOrder.NOTE_ID_ASCENDING,
                ),
                ProviderQuery(
                    ProviderEndpoint.MODELS,
                    projection = ProviderQueryShapes.MODEL_PROJECTION,
                ),
                ProviderQuery(
                    ProviderEndpoint.MODEL_BY_ID,
                    endpointId = 10L,
                    projection = ProviderQueryShapes.MODEL_PROJECTION,
                ),
                ProviderQuery(
                    ProviderEndpoint.MODEL_TEMPLATES,
                    endpointId = 10L,
                    projection = ProviderQueryShapes.TEMPLATE_PROJECTION,
                ),
                ProviderQuery(
                    ProviderEndpoint.DECKS,
                    projection = ProviderQueryShapes.DECK_PROJECTION,
                ),
                ProviderQuery(
                    ProviderEndpoint.DECK_BY_ID,
                    endpointId = 20L,
                    projection = ProviderQueryShapes.DECK_PROJECTION,
                ),
                ProviderQuery(
                    ProviderEndpoint.CARDS,
                    projection = ProviderQueryShapes.CARD_ID_PROJECTION,
                    selection = ProviderSelection.CardsForNote(30L),
                ),
                ProviderQuery(
                    ProviderEndpoint.CARDS,
                    projection = ProviderQueryShapes.CARD_NOTE_DECK_PROJECTION,
                    selection = ProviderSelection.CardsInDeck("Mining"),
                ),
                ProviderQuery(
                    ProviderEndpoint.CARD_BY_ID,
                    endpointId = 40L,
                    projection = ProviderQueryShapes.CARD_IDENTITY_PROJECTION,
                ),
                ProviderQuery(
                    ProviderEndpoint.CARDS_FOR_NOTE,
                    endpointId = 30L,
                    projection = ProviderQueryShapes.CARD_IDENTITY_PROJECTION,
                ),
            )

        assertEquals(14, legal.size)
        assertTrue(legal.all(ProviderQueryShapes::isAllowed))
    }

    @Test
    fun `forbidden endpoint projection selection sort and id combinations fail at construction`() {
        val forbidden =
            listOf<() -> Unit>(
                {
                    ProviderQuery(
                        ProviderEndpoint.NOTES_BROWSER,
                        projection = ProviderQueryShapes.NOTE_ID_PROJECTION,
                        selection = ProviderSelection.CardsForNote(1L),
                    )
                },
                {
                    ProviderQuery(
                        ProviderEndpoint.NOTES_BROWSER,
                        projection = ProviderQueryShapes.NOTE_ID_PROJECTION,
                        selection = ProviderSelection.ExcludedDeck("\uD800"),
                    )
                },
                {
                    ProviderQuery(
                        ProviderEndpoint.CARDS,
                        projection = ProviderQueryShapes.CARD_ID_PROJECTION,
                        selection = ProviderSelection.ExcludedDeck("Mining"),
                    )
                },
                {
                    ProviderQuery(
                        ProviderEndpoint.NOTES_V2,
                        projection = ProviderQueryShapes.NOTE_PAGE_PROJECTION,
                        selection = ProviderSelection.NoteIds(listOf(2L, 1L)),
                    )
                },
                {
                    ProviderQuery(
                        ProviderEndpoint.NOTES_V2,
                        projection = ProviderQueryShapes.NOTE_PAGE_PROJECTION,
                        selection = ProviderSelection.NoteIds(listOf(1L, 1L)),
                    )
                },
                {
                    ProviderQuery(
                        ProviderEndpoint.NOTES_V2,
                        projection = ProviderQueryShapes.DUPLICATE_PROJECTION,
                        selection = ProviderSelection.DuplicateChecksums(1L, listOf(2L, 1L)),
                        sortOrder = ProviderOrder.NOTE_ID_ASCENDING,
                    )
                },
                {
                    ProviderQuery(
                        ProviderEndpoint.NOTES_V2,
                        projection = ProviderQueryShapes.DUPLICATE_PROJECTION,
                        selection =
                            ProviderSelection.DuplicateChecksums(
                                1L,
                                listOf(4_294_967_296L),
                            ),
                        sortOrder = ProviderOrder.NOTE_ID_ASCENDING,
                    )
                },
                {
                    ProviderQuery(
                        ProviderEndpoint.NOTE_BY_ID,
                        endpointId = 1L,
                        projection = ProviderQueryShapes.NOTE_PAGE_PROJECTION,
                    )
                },
                {
                    ProviderQuery(
                        ProviderEndpoint.CARDS_FOR_NOTE,
                        projection = ProviderQueryShapes.CARD_IDENTITY_PROJECTION,
                    )
                },
                {
                    ProviderQuery(
                        ProviderEndpoint.MODELS,
                        endpointId = 1L,
                        projection = ProviderQueryShapes.MODEL_PROJECTION,
                    )
                },
                {
                    ProviderQuery(
                        ProviderEndpoint.MODEL_BY_ID,
                        projection = ProviderQueryShapes.MODEL_PROJECTION,
                    )
                },
                {
                    ProviderQuery(
                        ProviderEndpoint.DECKS,
                        projection = ProviderQueryShapes.MODEL_PROJECTION,
                    )
                },
                {
                    ProviderQuery(
                        ProviderEndpoint.CARD_BY_ID,
                        endpointId = 1L,
                        projection = ProviderQueryShapes.CARD_IDENTITY_PROJECTION,
                        sortOrder = ProviderOrder.NOTE_ID_ASCENDING,
                    )
                },
                {
                    ProviderQuery(
                        ProviderEndpoint.CARDS,
                        projection =
                            listOf(
                                ProviderColumn.CARD_ID,
                                ProviderColumn.CARD_ID,
                            ),
                        selection = ProviderSelection.CardsForNote(1L),
                    )
                },
            )
        forbidden.forEach { build ->
            assertThrows(IllegalArgumentException::class.java, build)
        }
    }

    @Test
    fun `pinned boolean cursor forms normalize without accepting lookalikes`() {
        assertEquals(ProviderCell.Integer(1L), providerStringCell(ProviderColumn.DECK_DYNAMIC, "true"))
        assertEquals(ProviderCell.Integer(0L), providerStringCell(ProviderColumn.DECK_DYNAMIC, "false"))
        assertEquals(ProviderCell.Text("TRUE"), providerStringCell(ProviderColumn.DECK_DYNAMIC, "TRUE"))
        assertEquals(ProviderCell.Text("true"), providerStringCell(ProviderColumn.DECK_NAME, "true"))
    }

    @Test
    fun `production provider package exposes only sealed pinned mutations`() {
        val sourceRoot =
            File(projectRoot(), "app/src/main/kotlin/com/ankiminer/android/anki/provider")
        assertTrue(sourceRoot.isDirectory)
        val source =
            sourceRoot
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .joinToString("\n") { it.readText() }

        assertFalse(source.contains("com.ichi2.anki.api.AddContentApi"))
        assertEquals(2, Regex("resolver\\.insert\\s*\\(").findAll(source).count())
        assertEquals(1, Regex("resolver\\.update\\s*\\(").findAll(source).count())
        assertFalse(Regex("resolver\\.delete\\s*\\(").containsMatchIn(source))
        assertTrue(source.contains("AnkiProviderMutationCommand.CreateDeck"))
        assertTrue(source.contains("AnkiProviderMutationCommand.StoreMedia"))
        assertTrue(source.contains("AnkiProviderMutationCommand.InsertNote"))
        assertTrue(source.contains("AnkiProviderMutationCommand.RouteCard"))
        assertTrue(source.contains("FlashCardsContract.Deck.CONTENT_ALL_URI"))
        assertTrue(source.contains("FlashCardsContract.Model.CONTENT_URI"))
        assertTrue(source.contains("FlashCardsContract.CardTemplate.QUESTION_FORMAT"))
        assertTrue(source.contains("FlashCardsContract.AnkiMedia.CONTENT_URI"))
        assertTrue(source.contains("FlashCardsContract.Note.CONTENT_URI"))
        assertTrue(source.contains("Uri.withAppendedPath(cardsUri, command.ordinal.toString())"))
    }

    private fun projectRoot(): File {
        var cursor = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (!File(cursor, "settings.gradle.kts").isFile) {
            cursor = cursor.parentFile ?: error("could not find project root")
        }
        return cursor
    }
}
