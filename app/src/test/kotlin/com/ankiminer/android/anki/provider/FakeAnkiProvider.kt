package com.ankiminer.android.anki.provider

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

internal class FakeAnkiProviderGateway : AnkiProviderGateway {
    var status: ProviderAccessStatus = ProviderAccessStatus.Available("com.ichi2.anki", 2, 20240000)
    var queryHandler: (ProviderQuery, AnkiCancellation) -> ProviderCursor? = { query, _ ->
        FakeProviderCursor(query.projection, emptyList())
    }
    var checksum: (String) -> Long = { value -> value.hashCode().toLong() and 0xffff_ffffL }
    var createDeckHandler: (AnkiProviderMutationCommand.CreateDeck) -> String? = { null }
    var storeMediaHandler: (AnkiProviderMutationCommand.StoreMedia) -> String? = { null }
    var insertNoteHandler: (AnkiProviderMutationCommand.InsertNote) -> String? = { null }
    var routeCardHandler: (AnkiProviderMutationCommand.RouteCard) -> Int = { 0 }
    var deleteNoteHandler: (AnkiProviderMutationCommand.DeleteNote) -> Int = { 0 }
    val queries = mutableListOf<ProviderQuery>()
    val deckCommands = mutableListOf<AnkiProviderMutationCommand.CreateDeck>()
    val mediaCommands = mutableListOf<AnkiProviderMutationCommand.StoreMedia>()
    val noteCommands = mutableListOf<AnkiProviderMutationCommand.InsertNote>()
    val cardCommands = mutableListOf<AnkiProviderMutationCommand.RouteCard>()
    val noteDeleteCommands = mutableListOf<AnkiProviderMutationCommand.DeleteNote>()
    var accessChecks = 0

    override fun accessStatus(): ProviderAccessStatus {
        accessChecks += 1
        return status
    }

    override fun query(
        query: ProviderQuery,
        cancellation: AnkiCancellation,
    ): ProviderCursor? {
        queries += query
        return queryHandler(query, cancellation)
    }

    override fun fieldChecksum(firstField: String): Long = checksum(firstField)

    override fun createDeck(command: AnkiProviderMutationCommand.CreateDeck): String? {
        deckCommands += command
        return createDeckHandler(command)
    }

    override fun storeMedia(command: AnkiProviderMutationCommand.StoreMedia): String? {
        mediaCommands += command
        return storeMediaHandler(command)
    }

    override fun insertNote(command: AnkiProviderMutationCommand.InsertNote): String? {
        noteCommands += command
        return insertNoteHandler(command)
    }

    override fun routeCard(command: AnkiProviderMutationCommand.RouteCard): Int {
        cardCommands += command
        return routeCardHandler(command)
    }

    override fun deleteNote(command: AnkiProviderMutationCommand.DeleteNote): Int {
        noteDeleteCommands += command
        return deleteNoteHandler(command)
    }
}

internal class FakeProviderCursor(
    override val projection: List<ProviderColumn>,
    private val rows: List<Map<ProviderColumn, ProviderCell>>,
    private val beforeMove: (nextIndex: Int) -> Unit = {},
    private val beforeCell: (ProviderColumn) -> Unit = {},
    private val beforeClose: () -> Unit = {},
) : ProviderCursor {
    var closeCount = 0
        private set
    private var index = -1
    private var closed = false

    override fun moveToNext(): Boolean {
        check(!closed)
        beforeMove(index + 1)
        index += 1
        return index < rows.size
    }

    override fun cell(column: ProviderColumn): ProviderCell {
        check(!closed && index in rows.indices)
        beforeCell(column)
        return rows[index][column] ?: error("missing fake provider column $column")
    }

    override fun close() {
        if (!closed) {
            beforeClose()
            closed = true
            closeCount += 1
        }
    }
}

internal class GeneratedFakeProviderCursor(
    override val projection: List<ProviderColumn>,
    private val rowCount: Int,
    private val rowAt: (Int) -> Map<ProviderColumn, ProviderCell>,
    private val beforeMove: (nextIndex: Int) -> Unit = {},
    private val beforeCell: (ProviderColumn) -> Unit = {},
) : ProviderCursor {
    var closeCount = 0
        private set
    private var index = -1
    private var closed = false

    override fun moveToNext(): Boolean {
        check(!closed)
        beforeMove(index + 1)
        index += 1
        return index < rowCount
    }

    override fun cell(column: ProviderColumn): ProviderCell {
        check(!closed && index in 0 until rowCount)
        beforeCell(column)
        return rowAt(index)[column] ?: error("missing generated provider column $column")
    }

    override fun close() {
        if (!closed) {
            closed = true
            closeCount += 1
        }
    }
}

internal class MutableAnkiCancellation : AnkiCancellation {
    private val cancelled = AtomicBoolean(false)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    override fun isCancelled(): Boolean = cancelled.get()

    override fun invokeOnCancellation(listener: () -> Unit): CancellationRegistration {
        if (cancelled.get()) listener() else listeners += listener
        return CancellationRegistration { listeners -= listener }
    }

    fun cancel() {
        if (cancelled.compareAndSet(false, true)) listeners.forEach { it() }
    }
}

internal fun integer(value: Long) = ProviderCell.Integer(value)

internal fun text(value: String) = ProviderCell.Text(value)

internal fun nullCell(): ProviderCell = ProviderCell.Null

internal fun modelRow(
    id: Long = 10L,
    name: String = "Mining",
    fields: String = "Expression\u001fMeaning",
    cards: Long = 1L,
    css: String = "css",
    defaultDeckId: ProviderCell = integer(1L),
    sortField: Long = 0L,
    type: Long = 0L,
    latexPost: ProviderCell = text("post"),
    latexPre: ProviderCell = text("pre"),
): Map<ProviderColumn, ProviderCell> =
    mapOf(
        ProviderColumn.MODEL_ID to integer(id),
        ProviderColumn.MODEL_NAME to text(name),
        ProviderColumn.MODEL_FIELD_NAMES to text(fields),
        ProviderColumn.MODEL_CARD_COUNT to integer(cards),
        ProviderColumn.MODEL_CSS to text(css),
        ProviderColumn.MODEL_DEFAULT_DECK_ID to defaultDeckId,
        ProviderColumn.MODEL_SORT_FIELD_INDEX to integer(sortField),
        ProviderColumn.MODEL_TYPE to integer(type),
        ProviderColumn.MODEL_LATEX_POST to latexPost,
        ProviderColumn.MODEL_LATEX_PRE to latexPre,
    )

internal fun templateRow(
    modelId: Long = 10L,
    ordinal: Long = 0L,
    name: String = "Card 1",
    question: String = "{{Expression}}",
    answer: String = "{{Meaning}}",
    browserQuestion: ProviderCell = nullCell(),
    browserAnswer: ProviderCell = nullCell(),
): Map<ProviderColumn, ProviderCell> =
    mapOf(
        ProviderColumn.TEMPLATE_MODEL_ID to integer(modelId),
        ProviderColumn.TEMPLATE_ORDINAL to integer(ordinal),
        ProviderColumn.TEMPLATE_NAME to text(name),
        ProviderColumn.TEMPLATE_QUESTION_FORMAT to text(question),
        ProviderColumn.TEMPLATE_ANSWER_FORMAT to text(answer),
        ProviderColumn.TEMPLATE_BROWSER_QUESTION_FORMAT to browserQuestion,
        ProviderColumn.TEMPLATE_BROWSER_ANSWER_FORMAT to browserAnswer,
    )

internal fun deckRow(
    id: Long = 20L,
    name: String = "Mining",
    dynamic: Long = 0L,
): Map<ProviderColumn, ProviderCell> =
    mapOf(
        ProviderColumn.DECK_ID to integer(id),
        ProviderColumn.DECK_NAME to text(name),
        ProviderColumn.DECK_DYNAMIC to integer(dynamic),
    )

internal fun cardRow(
    id: Long,
    noteId: Long,
    ordinal: Long,
    deckId: Long,
    /** 0 unless the card is currently borrowed by a filtered deck, as AnkiDroid reports it. */
    originalDeckId: Long = 0L,
): Map<ProviderColumn, ProviderCell> =
    mapOf(
        ProviderColumn.CARD_ID to integer(id),
        ProviderColumn.CARD_NOTE_ID to integer(noteId),
        ProviderColumn.CARD_ORDINAL to integer(ordinal),
        ProviderColumn.CARD_DECK_ID to integer(deckId),
        ProviderColumn.CARD_ORIGINAL_DECK_ID to integer(originalDeckId),
    )

internal fun noteRow(
    id: Long,
    modelId: Long,
    joinedFields: String,
    providerTagsWire: String,
): Map<ProviderColumn, ProviderCell> =
    mapOf(
        ProviderColumn.NOTE_ID to integer(id),
        ProviderColumn.NOTE_MODEL_ID to integer(modelId),
        ProviderColumn.NOTE_FIELDS to text(joinedFields),
        ProviderColumn.NOTE_TAGS to text(providerTagsWire),
    )
