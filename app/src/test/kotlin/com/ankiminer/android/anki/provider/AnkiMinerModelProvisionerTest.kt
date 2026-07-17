package com.ankiminer.android.anki.provider

import com.ankiminer.android.anki.protocol.AnkiErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AnkiMinerModelProvisionerTest {
    @Test
    fun `inspection of a missing model is strictly read only`() {
        val fixture = Fixture()

        assertEquals(AnkiMinerModelProvisioningResult.Missing, fixture.provisioner.inspect())
        assertEquals(null, fixture.journal.record)
        assertTrue(fixture.gateway.modelCommands.isEmpty())
        assertTrue(fixture.gateway.templateCommands.isEmpty())
    }

    @Test
    fun `explicit provisioning journals both provider entries and exact readback`() {
        val fixture = Fixture()
        fixture.installSuccessfulCreateAndUpdate()

        assertEquals(
            AnkiMinerModelProvisioningResult.Ready(
                modelId = MODEL_ID,
                origin = AnkiMinerModelReadyOrigin.PROVISIONED,
            ),
            fixture.provisioner.provision(),
        )
        assertEquals(
            listOf(
                AnkiMinerModelProvisioningPhase.PREPARED,
                AnkiMinerModelProvisioningPhase.MODEL_CREATE_ENTERED,
                AnkiMinerModelProvisioningPhase.MODEL_BASE_VERIFIED,
                AnkiMinerModelProvisioningPhase.TEMPLATE_UPDATE_ENTERED,
                AnkiMinerModelProvisioningPhase.COMPLETE,
            ),
            fixture.journal.history.map(AnkiMinerModelProvisioningRecord::phase),
        )
        assertEquals(MODEL_ID, fixture.journal.record?.modelId)
        assertEquals(1, fixture.gateway.modelCommands.size)
        assertEquals(
            AnkiProviderMutationCommand.UpdateAnkiMinerTemplate(MODEL_ID),
            fixture.gateway.templateCommands.single(),
        )
        assertTrue(AnkiMinerNoteModel.matchesExactly(requireNotNull(fixture.model)))
    }

    @Test
    fun `same name mismatch is never overwritten or claimed`() {
        val fixture = Fixture(initialModel = baseSnapshot().copy(css = "user css"))

        assertEquals(
            AnkiMinerModelProvisioningResult.Conflict(
                AnkiMinerModelConflictReason.SAME_NAME_MODEL_DIFFERS,
            ),
            fixture.provisioner.provision(),
        )
        assertEquals(null, fixture.journal.record)
        assertTrue(fixture.gateway.modelCommands.isEmpty())
        assertTrue(fixture.gateway.templateCommands.isEmpty())
    }

    @Test
    fun `null and malformed create receipts stop before template mutation`() {
        listOf<String?>(null, "content://com.ichi2.anki.flashcards/models/042", "not a uri").forEach {
            rawReceipt ->
            val fixture = Fixture()
            fixture.gateway.createModelHandler = {
                fixture.model = baseSnapshot()
                rawReceipt
            }
            fixture.gateway.updateTemplateHandler = {
                throw AssertionError("template mutation must not follow an invalid create receipt")
            }

            assertEquals(
                "receipt=$rawReceipt",
                AnkiMinerModelProvisioningResult.RecoveryRequired(
                    AnkiMinerModelRecoveryReason.MODEL_CREATE_OUTCOME_UNCERTAIN,
                ),
                fixture.provisioner.provision(),
            )
            assertEquals(
                AnkiMinerModelProvisioningPhase.MODEL_CREATE_ENTERED,
                fixture.journal.record?.phase,
            )
            assertTrue(fixture.gateway.templateCommands.isEmpty())
        }
    }

    @Test
    fun `restart reconciles an attributable base after create return crash`() {
        val fixture =
            Fixture(
                hooks =
                    object : AnkiMinerModelProvisioningBoundaryHooks {
                        override fun afterModelCreateReturn() {
                            throw SimulatedProcessDeath()
                        }
                    },
            )
        fixture.installSuccessfulCreateAndUpdate()

        assertThrows(SimulatedProcessDeath::class.java) { fixture.provisioner.provision() }
        assertEquals(
            AnkiMinerModelProvisioningPhase.MODEL_CREATE_ENTERED,
            fixture.journal.record?.phase,
        )
        assertTrue(fixture.gateway.templateCommands.isEmpty())

        fixture.replaceProvisioner()
        assertEquals(
            AnkiMinerModelProvisioningResult.Ready(
                modelId = MODEL_ID,
                origin = AnkiMinerModelReadyOrigin.RECOVERED,
            ),
            fixture.provisioner.provision(),
        )
        assertTrue(AnkiMinerNoteModel.matchesExactly(requireNotNull(fixture.model)))
    }

    @Test
    fun `entered create with no observable model is never blindly retried`() {
        val fixture =
            Fixture(
                journalRecord = record(AnkiMinerModelProvisioningPhase.MODEL_CREATE_ENTERED),
            )

        assertEquals(
            AnkiMinerModelProvisioningResult.RecoveryRequired(
                AnkiMinerModelRecoveryReason.JOURNALED_MODEL_MISSING,
            ),
            fixture.provisioner.provision(),
        )
        assertTrue(fixture.gateway.modelCommands.isEmpty())
        assertTrue(fixture.gateway.templateCommands.isEmpty())
    }

    @Test
    fun `template retry requires the full attributable base snapshot`() {
        val original = baseSnapshot()
        val entered =
            record(
                phase = AnkiMinerModelProvisioningPhase.TEMPLATE_UPDATE_ENTERED,
                modelId = MODEL_ID,
                snapshotSha = AnkiMinerNoteModel.snapshotSha256(original),
            )
        val changed =
            original.copy(
                templates = listOf(original.templates.single().copy(questionFormat = "user edit")),
            )
        val fixture = Fixture(initialModel = changed, journalRecord = entered)

        assertEquals(
            AnkiMinerModelProvisioningResult.RecoveryRequired(
                AnkiMinerModelRecoveryReason.JOURNALED_MODEL_CHANGED,
            ),
            fixture.provisioner.provision(),
        )
        assertTrue(fixture.gateway.templateCommands.isEmpty())
    }

    @Test
    fun `unchanged entered template update resumes idempotently`() {
        val base = baseSnapshot()
        val entered =
            record(
                phase = AnkiMinerModelProvisioningPhase.TEMPLATE_UPDATE_ENTERED,
                modelId = MODEL_ID,
                snapshotSha = AnkiMinerNoteModel.snapshotSha256(base),
            )
        val fixture = Fixture(initialModel = base, journalRecord = entered)
        fixture.installSuccessfulTemplateUpdate()

        assertEquals(
            AnkiMinerModelProvisioningResult.Ready(
                modelId = MODEL_ID,
                origin = AnkiMinerModelReadyOrigin.RECOVERED,
            ),
            fixture.provisioner.provision(),
        )
        assertEquals(1, fixture.gateway.templateCommands.size)
    }

    @Test
    fun `invalid update receipt stays uncertain even when provider applied exact bytes`() {
        val base = baseSnapshot()
        val baseRecord =
            record(
                phase = AnkiMinerModelProvisioningPhase.MODEL_BASE_VERIFIED,
                modelId = MODEL_ID,
                snapshotSha = AnkiMinerNoteModel.snapshotSha256(base),
            )
        val fixture = Fixture(initialModel = base, journalRecord = baseRecord)
        fixture.gateway.updateTemplateHandler = {
            fixture.model = exactSnapshot()
            0
        }

        assertEquals(
            AnkiMinerModelProvisioningResult.RecoveryRequired(
                AnkiMinerModelRecoveryReason.TEMPLATE_UPDATE_OUTCOME_UNCERTAIN,
            ),
            fixture.provisioner.provision(),
        )
        assertEquals(
            AnkiMinerModelProvisioningPhase.TEMPLATE_UPDATE_ENTERED,
            fixture.journal.record?.phase,
        )

        fixture.replaceProvisioner()
        assertEquals(
            AnkiMinerModelProvisioningResult.Ready(
                modelId = MODEL_ID,
                origin = AnkiMinerModelReadyOrigin.RECOVERED,
            ),
            fixture.provisioner.provision(),
        )
        assertEquals(1, fixture.gateway.templateCommands.size)
    }

    @Test
    fun `explicit provisioning recreates a model deleted after a completed generation`() {
        val completedSnapshot = exactSnapshot()
        val fixture =
            Fixture(
                journalRecord =
                    record(
                        phase = AnkiMinerModelProvisioningPhase.COMPLETE,
                        modelId = MODEL_ID,
                        snapshotSha = AnkiMinerNoteModel.snapshotSha256(completedSnapshot),
                    ),
            )
        fixture.installSuccessfulCreateAndUpdate()

        assertEquals(
            AnkiMinerModelProvisioningResult.Ready(
                modelId = MODEL_ID,
                origin = AnkiMinerModelReadyOrigin.PROVISIONED,
            ),
            fixture.provisioner.provision(),
        )
        assertEquals(
            AnkiMinerModelProvisioningPhase.PREPARED,
            fixture.journal.history.first().phase,
        )
        assertEquals(
            AnkiMinerModelProvisioningPhase.COMPLETE,
            fixture.journal.record?.phase,
        )
    }

    @Test
    fun `explicit provisioning accepts an exact reimport with a new provider identity`() {
        val oldSnapshot = exactSnapshot()
        val reimported = exactSnapshot(modelId = 84L)
        val fixture =
            Fixture(
                initialModel = reimported,
                journalRecord =
                    record(
                        phase = AnkiMinerModelProvisioningPhase.COMPLETE,
                        modelId = MODEL_ID,
                        snapshotSha = AnkiMinerNoteModel.snapshotSha256(oldSnapshot),
                    ),
            )

        assertEquals(
            AnkiMinerModelProvisioningResult.Ready(
                modelId = 84L,
                origin = AnkiMinerModelReadyOrigin.EXISTING_EXACT,
            ),
            fixture.provisioner.provision(),
        )
        assertEquals(
            AnkiMinerModelProvisioningPhase.PREPARED,
            fixture.journal.record?.phase,
        )
        assertTrue(fixture.gateway.modelCommands.isEmpty())
        assertTrue(fixture.gateway.templateCommands.isEmpty())
    }

    @Test
    fun `explicit provisioning can retire a completed prior contract when the model is missing`() {
        val fixture =
            Fixture(
                journalRecord =
                    record(
                        phase = AnkiMinerModelProvisioningPhase.COMPLETE,
                        modelId = MODEL_ID,
                        snapshotSha = "a".repeat(64),
                        contractSha = "b".repeat(64),
                    ),
            )
        fixture.installSuccessfulCreateAndUpdate()

        assertEquals(
            AnkiMinerModelProvisioningResult.Ready(
                modelId = MODEL_ID,
                origin = AnkiMinerModelReadyOrigin.PROVISIONED,
            ),
            fixture.provisioner.provision(),
        )
        assertEquals(
            AnkiMinerNoteModel.CONTRACT_SHA256,
            fixture.journal.record?.contractSha256,
        )
    }

    @Test
    fun `prior contract never authorizes overwriting a differing same name model`() {
        val fixture =
            Fixture(
                initialModel = exactSnapshot().copy(css = "user css"),
                journalRecord =
                    record(
                        phase = AnkiMinerModelProvisioningPhase.COMPLETE,
                        modelId = MODEL_ID,
                        snapshotSha = "a".repeat(64),
                        contractSha = "b".repeat(64),
                    ),
            )

        assertEquals(
            AnkiMinerModelProvisioningResult.Conflict(
                AnkiMinerModelConflictReason.SAME_NAME_MODEL_DIFFERS,
            ),
            fixture.provisioner.provision(),
        )
        assertTrue(fixture.journal.history.isEmpty())
        assertTrue(fixture.gateway.modelCommands.isEmpty())
        assertTrue(fixture.gateway.templateCommands.isEmpty())
    }

    @Test
    fun `cancellation before a fresh provider entry is nonmutating`() {
        val fixture = Fixture()
        val cancellation = MutableAnkiCancellation().apply { cancel() }

        val result = fixture.provisioner.provision(cancellation)

        assertTrue(result is AnkiMinerModelProvisioningResult.FailedBeforeEntry)
        result as AnkiMinerModelProvisioningResult.FailedBeforeEntry
        assertEquals(AnkiErrorCode.CANCELLED, result.code)
        assertTrue(fixture.gateway.modelCommands.isEmpty())
        assertTrue(fixture.gateway.templateCommands.isEmpty())
    }

    private class Fixture(
        initialModel: ModelSnapshot? = null,
        journalRecord: AnkiMinerModelProvisioningRecord? = null,
        hooks: AnkiMinerModelProvisioningBoundaryHooks =
            NoOpAnkiMinerModelProvisioningBoundaryHooks,
    ) {
        val gateway = FakeAnkiProviderGateway()
        val journal = RecordingJournal(journalRecord)
        var model: ModelSnapshot? = initialModel
        var provisioner = AnkiMinerModelProvisioner(gateway, journal, hooks)

        init {
            gateway.queryHandler = { query, _ ->
                val current = this.model
                when (query.endpoint) {
                    ProviderEndpoint.MODELS ->
                        FakeProviderCursor(
                            query.projection,
                            current?.let { snapshot -> listOf(modelRows(snapshot)) }.orEmpty(),
                        )
                    ProviderEndpoint.MODEL_TEMPLATES ->
                        FakeProviderCursor(
                            query.projection,
                            current
                                ?.takeIf { it.id == query.endpointId }
                                ?.templates
                                ?.map(::templateRows)
                                .orEmpty(),
                        )
                    else -> error("unexpected model provisioning query: $query")
                }
            }
        }

        fun installSuccessfulCreateAndUpdate() {
            gateway.createModelHandler = {
                this.model = baseSnapshot()
                "content://com.ichi2.anki.flashcards/models/$MODEL_ID"
            }
            installSuccessfulTemplateUpdate()
        }

        fun installSuccessfulTemplateUpdate() {
            gateway.updateTemplateHandler = { command ->
                require(command.modelId == MODEL_ID)
                this.model = exactSnapshot()
                1
            }
        }

        fun replaceProvisioner() {
            provisioner = AnkiMinerModelProvisioner(gateway, journal)
        }
    }

    private class RecordingJournal(
        initial: AnkiMinerModelProvisioningRecord?,
    ) : AnkiMinerModelProvisioningJournal {
        var record = initial
            private set
        val history = mutableListOf<AnkiMinerModelProvisioningRecord>()

        override fun read(): AnkiMinerModelProvisioningRecord? = record

        override fun replace(
            expected: AnkiMinerModelProvisioningRecord?,
            updated: AnkiMinerModelProvisioningRecord,
        ) {
            if (record != expected) throw AnkiMinerModelJournalStateChangedException()
            requireAllowedTransition(record, updated)
            record = updated
            history += updated
        }
    }

    private class SimulatedProcessDeath : RuntimeException()

    private companion object {
        const val MODEL_ID = 42L

        fun baseSnapshot(): ModelSnapshot =
            exactSnapshot().copy(
                templates =
                    listOf(
                        exactSnapshot().templates.single().copy(
                            name = "Card 1",
                            questionFormat = "{{Expression}}",
                            answerFormat = "{{Expression}}",
                        ),
                    ),
            )

        fun exactSnapshot(modelId: Long = MODEL_ID): ModelSnapshot =
            ModelSnapshot(
                id = modelId,
                name = AnkiMinerNoteModel.MODEL_NAME,
                type = AnkiMinerNoteModel.MODEL_TYPE,
                fieldNames = AnkiMinerNoteModel.FIELD_NAMES,
                cardCount = AnkiMinerNoteModel.TEMPLATE_COUNT,
                sortFieldIndex = AnkiMinerNoteModel.SORT_FIELD_INDEX,
                effectiveDefaultDeckId = AnkiMinerNoteModel.DEFAULT_DECK_ID,
                css = AnkiMinerNoteModel.CSS,
                latexPre = "provider pre",
                latexPost = "provider post",
                templates =
                    listOf(
                        TemplateSnapshot(
                            modelId = modelId,
                            ordinal = AnkiMinerNoteModel.TEMPLATE_ORDINAL,
                            name = AnkiMinerNoteModel.TEMPLATE_NAME,
                            questionFormat = AnkiMinerNoteModel.QUESTION_FORMAT,
                            answerFormat = AnkiMinerNoteModel.ANSWER_FORMAT,
                            browserQuestionFormat = null,
                            browserAnswerFormat = null,
                        ),
                    ),
            )

        fun modelRows(snapshot: ModelSnapshot) =
            modelRow(
                id = snapshot.id,
                name = snapshot.name,
                fields = snapshot.fieldNames.joinToString("\u001f"),
                cards = snapshot.cardCount.toLong(),
                css = snapshot.css,
                defaultDeckId = integer(snapshot.effectiveDefaultDeckId),
                sortField = snapshot.sortFieldIndex.toLong(),
                type = snapshot.type.toLong(),
                latexPost = snapshot.latexPost?.let(::text) ?: nullCell(),
                latexPre = snapshot.latexPre?.let(::text) ?: nullCell(),
            )

        fun templateRows(snapshot: TemplateSnapshot) =
            templateRow(
                modelId = snapshot.modelId,
                ordinal = snapshot.ordinal.toLong(),
                name = snapshot.name,
                question = snapshot.questionFormat,
                answer = snapshot.answerFormat,
                browserQuestion = snapshot.browserQuestionFormat?.let(::text) ?: nullCell(),
                browserAnswer = snapshot.browserAnswerFormat?.let(::text) ?: nullCell(),
            )

        fun record(
            phase: AnkiMinerModelProvisioningPhase,
            modelId: Long? = null,
            snapshotSha: String? = null,
            contractSha: String = AnkiMinerNoteModel.CONTRACT_SHA256,
        ) =
            AnkiMinerModelProvisioningRecord(
                contractSha256 = contractSha,
                phase = phase,
                modelId = modelId,
                snapshotSha256 = snapshotSha,
            )
    }
}
