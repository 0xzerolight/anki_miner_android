package com.ankiminer.android.anki.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

class TargetSnapshotJournalMappingTest {
    @Test
    fun `durable mapping round trips every target leaf without collapsing nullable forms`() {
        val target = targetSnapshot()

        val durable = target.toDurableSnapshot()
        val roundTrip = durable.toProviderSnapshot()

        assertEquals(target, roundTrip)
        assertNotSame(target.model.fieldNames, durable.model.fieldNames)
        assertEquals(null, roundTrip.model.templates[0].browserQuestionFormat)
        assertEquals("", roundTrip.model.templates[0].browserAnswerFormat)
        assertEquals("", roundTrip.model.latexPre)
        assertEquals(null, roundTrip.model.latexPost)
    }

    @Test
    fun `pre-create expectation freezes the exact full model and requested deck name`() {
        val model = targetSnapshot().model

        val expectation = model.toDurableExpectation("Mining::Japanese")

        assertEquals("Mining::Japanese", expectation.expectedDeckName)
        assertEquals(model, expectation.model.toProviderSnapshot())
    }

    private fun targetSnapshot(): TargetSnapshot {
        val modelId = 10L
        return TargetSnapshot(
            deck = DeckSnapshot(id = 20L, name = "Mining", dynamic = false),
            model =
                ModelSnapshot(
                    id = modelId,
                    name = "Mining",
                    type = 0,
                    fieldNames = mutableListOf("Expression", "Meaning"),
                    cardCount = 2,
                    sortFieldIndex = 1,
                    effectiveDefaultDeckId = 1L,
                    css = ".card { color: black; }",
                    latexPre = "",
                    latexPost = null,
                    templates =
                        listOf(
                            TemplateSnapshot(
                                modelId = modelId,
                                ordinal = 0,
                                name = "Recognition",
                                questionFormat = "{{Expression}}",
                                answerFormat = "{{Meaning}}",
                                browserQuestionFormat = null,
                                browserAnswerFormat = "",
                            ),
                            TemplateSnapshot(
                                modelId = modelId,
                                ordinal = 1,
                                name = "Recall",
                                questionFormat = "{{Meaning}}",
                                answerFormat = "{{Expression}}",
                                browserQuestionFormat = "browser-q",
                                browserAnswerFormat = null,
                            ),
                        ),
                ),
        )
    }
}
