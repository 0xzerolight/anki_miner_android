package com.ankiminer.android.anki.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decision-table coverage for [AnkiProviderReadService.verifyUserNoteType] against a real read
 * service over [FakeAnkiProviderGateway]. Each case pins one [NoteTypeSetupStatus] branch of the
 * detect/verify contract (the method never creates a note type).
 */
class VerifyUserNoteTypeTest {
    private val lapisFields =
        listOf(
            "Expression",
            "Sentence",
            "MainDefinition",
            "Picture",
            "SentenceAudio",
            "ExpressionFurigana",
            "SentenceFurigana",
        )

    @Test
    fun `a full valid mapping with word on the first field verifies`() {
        val reads = readService(lapisHandler())

        val status =
            reads.verifyUserNoteType(
                MODEL_NAME,
                AnkiFieldAutoMap.autoMap(lapisFields),
                AnkiCancellation.NONE,
            )

        assertEquals(NoteTypeSetupStatus.Verified(MODEL_ID), status)
    }

    @Test
    fun `only word mapped verifies with every other field left unmapped`() {
        val reads = readService(lapisHandler())
        // Regression (bug #1): only word maps to field[0]; every other key -- including the
        // formerly over-required expression_furigana, sentence, definition, picture, audio and
        // sentence_furigana -- is left empty, as EngineSettingsSnapshotMapper emits them. Only
        // word is mandatory, so an all-but-word-unmapped note type still verifies.
        val fieldMap =
            AnkiFieldKeys.ALL.associateWith { "" }.toMutableMap().apply {
                put(AnkiFieldKeys.WORD, lapisFields.first())
            }

        val status = reads.verifyUserNoteType(MODEL_NAME, fieldMap, AnkiCancellation.NONE)

        assertEquals(NoteTypeSetupStatus.Verified(MODEL_ID), status)
    }

    @Test
    fun `an optional field mapped to a non-existent field reports FieldsMissing with that key`() {
        val reads = readService(lapisHandler())
        // definition is optional now (only word is mandatory), but mapping it to a field the note
        // type does not have must still be flagged via the missing-optional branch.
        val fieldMap =
            AnkiFieldAutoMap.autoMap(lapisFields).toMutableMap().apply {
                put("definition", "NoSuchField")
            }

        val status = reads.verifyUserNoteType(MODEL_NAME, fieldMap, AnkiCancellation.NONE)

        assertTrue("expected FieldsMissing but was $status", status is NoteTypeSetupStatus.FieldsMissing)
        assertTrue("definition" in (status as NoteTypeSetupStatus.FieldsMissing).keys)
    }

    @Test
    fun `word mapped to a non-first field reports FirstFieldMismatch`() {
        val reads = readService(lapisHandler())
        val fieldMap =
            AnkiFieldAutoMap.autoMap(lapisFields).toMutableMap().apply {
                // "Sentence" is a real field but not field[0], so dedup would key on the wrong field.
                put("word", "Sentence")
            }

        val status = reads.verifyUserNoteType(MODEL_NAME, fieldMap, AnkiCancellation.NONE)

        assertEquals(NoteTypeSetupStatus.FirstFieldMismatch, status)
    }

    @Test
    fun `duplicate destinations report an invalid field map before setup can verify`() {
        val reads = readService(lapisHandler())
        val fieldMap =
            AnkiFieldAutoMap.autoMap(lapisFields).toMutableMap().apply {
                put("sentence", lapisFields.first())
            }

        val status = reads.verifyUserNoteType(MODEL_NAME, fieldMap, AnkiCancellation.NONE)

        assertEquals(
            NoteTypeSetupStatus.FieldMapInvalid(
                destination = "Expression",
                logicalKeys = listOf("word", "sentence"),
            ),
            status,
        )
    }

    @Test
    fun `quarantined empty field map reports FieldMapInvalid before any note mutation`() {
        val gateway = FakeAnkiProviderGateway()
        gateway.queryHandler = lapisHandler()
        val reads = AnkiProviderReadService(gateway, AnkiRunStateRegistry())

        val status =
            reads.verifyUserNoteType(
                MODEL_NAME,
                emptyMap(),
                AnkiCancellation.NONE,
            )

        assertEquals(
            NoteTypeSetupStatus.FieldMapInvalid(
                destination = lapisFields.first(),
                logicalKeys = listOf(AnkiFieldKeys.WORD),
            ),
            status,
        )
        assertTrue(gateway.noteCommands.isEmpty())
    }

    @Test
    fun `an unknown note type name reports NoteTypeMissing`() {
        val reads = readService(lapisHandler())

        val status =
            reads.verifyUserNoteType(
                "Nonexistent",
                AnkiFieldAutoMap.autoMap(lapisFields),
                AnkiCancellation.NONE,
            )

        assertEquals(NoteTypeSetupStatus.NoteTypeMissing, status)
    }

    @Test
    fun `an unavailable provider reports a retryable ProviderError instead of NoteTypeMissing`() {
        val gateway = FakeAnkiProviderGateway()
        gateway.status = ProviderAccessStatus.Absent
        gateway.queryHandler = lapisHandler()
        val reads = AnkiProviderReadService(gateway, AnkiRunStateRegistry())

        val status =
            reads.verifyUserNoteType(
                MODEL_NAME,
                AnkiFieldAutoMap.autoMap(lapisFields),
                AnkiCancellation.NONE,
            )

        assertTrue("expected ProviderError but was $status", status is NoteTypeSetupStatus.ProviderError)
        assertTrue((status as NoteTypeSetupStatus.ProviderError).retryable)
    }

    private fun readService(
        handler: (ProviderQuery, AnkiCancellation) -> ProviderCursor?,
    ): AnkiProviderReadService {
        val gateway = FakeAnkiProviderGateway()
        gateway.queryHandler = handler
        return AnkiProviderReadService(gateway, AnkiRunStateRegistry())
    }

    private fun lapisHandler(): (ProviderQuery, AnkiCancellation) -> ProviderCursor? =
        { query, _ ->
            when (query.endpoint) {
                ProviderEndpoint.MODELS ->
                    FakeProviderCursor(
                        query.projection,
                        listOf(modelRow(name = MODEL_NAME, fields = lapisFields.joinToString(""))),
                    )
                ProviderEndpoint.MODEL_TEMPLATES ->
                    FakeProviderCursor(query.projection, listOf(templateRow()))
                else -> error("unexpected query $query")
            }
        }

    private companion object {
        const val MODEL_NAME = "Lapis"

        /** [modelRow]'s default id; [templateRow]'s default modelId matches it so validation passes. */
        const val MODEL_ID = 10L
    }
}
