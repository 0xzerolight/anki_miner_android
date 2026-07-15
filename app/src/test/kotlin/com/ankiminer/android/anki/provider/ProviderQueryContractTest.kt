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
                    ProviderEndpoint.CARD_BY_ID,
                    endpointId = 40L,
                    projection = ProviderQueryShapes.CARD_IDENTITY_PROJECTION,
                ),
            )

        assertEquals(11, legal.size)
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
    fun `production provider package exposes only the sealed deck insert mutation`() {
        val sourceRoot =
            File(projectRoot(), "app/src/main/kotlin/com/ankiminer/android/anki/provider")
        assertTrue(sourceRoot.isDirectory)
        val source =
            sourceRoot
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .joinToString("\n") { it.readText() }

        assertFalse(source.contains("AddContentApi"))
        assertEquals(1, Regex("resolver\\.insert\\s*\\(").findAll(source).count())
        assertFalse(Regex("resolver\\.(update|delete)\\s*\\(").containsMatchIn(source))
        assertTrue(source.contains("AnkiProviderMutationCommand.CreateDeck"))
        assertTrue(source.contains("FlashCardsContract.Deck.CONTENT_ALL_URI"))
        assertFalse(source.contains("appendPath(\"cards\")"))
        assertFalse(source.contains("notes/{"))
    }

    private fun projectRoot(): File {
        var cursor = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (!File(cursor, "settings.gradle.kts").isFile) {
            cursor = cursor.parentFile ?: error("could not find project root")
        }
        return cursor
    }
}
