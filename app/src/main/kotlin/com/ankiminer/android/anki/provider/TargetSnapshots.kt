package com.ankiminer.android.anki.provider

import com.ankiminer.android.anki.generated.AnkiLimitsV1
import com.ankiminer.android.anki.generated.UnicodeContractV151
import com.ankiminer.android.anki.protocol.AnkiProtocolException
import com.ankiminer.android.anki.protocol.AnkiValidators

internal data class TemplateSnapshot(
    val modelId: Long,
    val ordinal: Int,
    val name: String,
    val questionFormat: String,
    val answerFormat: String,
    val browserQuestionFormat: String?,
    val browserAnswerFormat: String?,
)

internal data class ModelSnapshot(
    val id: Long,
    val name: String,
    val type: Int,
    val fieldNames: List<String>,
    /** Number of standard templates; generated cards for one note may be a non-empty subset. */
    val cardCount: Int,
    val sortFieldIndex: Int,
    /** Provider-visible value; v2.24 coerces an underlying null to deck ID 1. */
    val effectiveDefaultDeckId: Long,
    val css: String,
    val latexPre: String?,
    val latexPost: String?,
    val templates: List<TemplateSnapshot>,
)

internal data class DeckSnapshot(
    val id: Long,
    val name: String,
    val dynamic: Boolean,
)

internal data class TargetSnapshot(
    val deck: DeckSnapshot,
    val model: ModelSnapshot,
)

/** Exact raw provider state for one known note ID. */
internal data class NoteSnapshot(
    val id: Long,
    val modelId: Long,
    val joinedFields: String,
    val providerTagsWire: String,
)

internal data class CardIdentity(
    val id: Long,
    val noteId: Long,
    val ordinal: Int,
    /** Where the card sits right now, which is the filtered deck while it is being custom-studied. */
    val deckId: Long,
    /** Anki's home-deck link: the deck the card came from, or 0 when it is not in a filtered deck. */
    val originalDeckId: Long = 0L,
) {
    /**
     * The deck that owns the card, which is what deck-scoped reads mean by "in the target deck".
     *
     * Routing compares [deckId] instead: moving a card is about where it currently is. Reads that
     * ask whether a note is already mined must use this, or a Custom Study session over the target
     * hides every card it borrowed and those words get mined again as duplicates.
     */
    val homeDeckId: Long
        get() = if (originalDeckId > 0L) originalDeckId else deckId
}

/**
 * Exact raw card rows observed through `notes/{noteId}/cards`.
 *
 * Standard-template count is only an ordinal upper bound: conditional generation can omit any
 * template for a particular note, so [cards] must never be required to equal the template set.
 */
internal data class CardsForNoteSnapshot(
    val noteId: Long,
    val cards: List<CardIdentity>,
)

internal data class ValidatedModelBase(
    val fieldNames: List<String>,
    val providerTextUtf8Bytes: Int,
)

internal object ProviderSnapshotValidation {
    fun splitFieldsPreservingTrailing(raw: String): List<String> {
        val result = ArrayList<String>()
        var start = 0
        for (index in raw.indices) {
            if (raw[index] == FIELD_SEPARATOR) {
                result += raw.substring(start, index)
                // Seeing a 64th separator proves that the provider supplied at least 65 fields.
                // Stop before allocating the final field (or scanning the rest of an adversarial row).
                if (result.size == AnkiLimitsV1.Names.TargetFields.MAX_ITEM_COUNT) {
                    throw InvalidTargetSnapshotException()
                }
                start = index + 1
            }
        }
        result += raw.substring(start)
        return result
    }

    fun firstField(raw: String): String {
        val separator = raw.indexOf(FIELD_SEPARATOR)
        return if (separator < 0) raw else raw.substring(0, separator)
    }

    fun validateModelBase(
        id: Long,
        name: String,
        type: Int,
        rawFieldNames: String,
        cardCount: Int,
        sortFieldIndex: Int,
        effectiveDefaultDeckId: Long,
        css: String,
        latexPre: String?,
        latexPost: String?,
    ): ValidatedModelBase {
        requireTarget(id > 0L)
        requireTarget(type == AnkiLimitsV1.TargetModel.ALLOWED_TYPE_CODE)
        validateCanonicalName(
            name,
            AnkiLimitsV1.Names.Model.MAX_CODE_POINTS,
            AnkiLimitsV1.Names.Model.MAX_UTF8_BYTES,
        )
        val fieldNames = splitFieldsPreservingTrailing(rawFieldNames)
        requireTarget(fieldNames.size in 1..AnkiLimitsV1.Names.TargetFields.MAX_ITEM_COUNT)
        requireTarget(fieldNames.distinct().size == fieldNames.size)
        var fieldBytes = 0
        for (field in fieldNames) {
            fieldBytes =
                addProviderTextBytes(
                    fieldBytes,
                    validateCanonicalName(
                        field,
                        AnkiLimitsV1.Names.Field.MAX_CODE_POINTS,
                        AnkiLimitsV1.Names.Field.MAX_UTF8_BYTES,
                    ),
                )
        }
        requireTarget(fieldBytes <= AnkiLimitsV1.Names.TargetFields.MAX_TOTAL_UTF8_BYTES)
        requireTarget(cardCount in 1..AnkiLimitsV1.TargetModel.MAX_TEMPLATE_COUNT)
        requireTarget(sortFieldIndex in fieldNames.indices)
        requireTarget(effectiveDefaultDeckId > 0L)

        var providerTextBytes = validateProviderText(css, AnkiLimitsV1.TargetModel.CSS_MAX_UTF8_BYTES)
        providerTextBytes =
            addProviderTextBytes(
                providerTextBytes,
                validateNullableProviderText(
                    latexPre,
                    AnkiLimitsV1.TargetModel.LATEX_PRE_MAX_UTF8_BYTES,
                ),
            )
        providerTextBytes =
            addProviderTextBytes(
                providerTextBytes,
                validateNullableProviderText(
                    latexPost,
                    AnkiLimitsV1.TargetModel.LATEX_POST_MAX_UTF8_BYTES,
                ),
            )
        requireTarget(providerTextBytes <= AnkiLimitsV1.TargetModel.PROVIDER_TEXT_TOTAL_MAX_UTF8_BYTES)
        return ValidatedModelBase(fieldNames.toList(), providerTextBytes)
    }

    fun validateTemplate(
        template: TemplateSnapshot,
        expectedModelId: Long,
        cardCount: Int,
        providerTextUtf8Bytes: Int,
    ): Int {
        requireTarget(template.modelId == expectedModelId)
        requireTarget(template.ordinal in 0 until cardCount)
        validateCanonicalName(
            template.name,
            AnkiLimitsV1.Names.Field.MAX_CODE_POINTS,
            AnkiLimitsV1.Names.Field.MAX_UTF8_BYTES,
        )
        var total = providerTextUtf8Bytes
        total =
            addProviderTextBytes(
                total,
                validateProviderText(
                    template.questionFormat,
                    AnkiLimitsV1.TargetModel.TEMPLATE_QUESTION_FORMAT_MAX_UTF8_BYTES,
                ),
            )
        total =
            addProviderTextBytes(
                total,
                validateProviderText(
                    template.answerFormat,
                    AnkiLimitsV1.TargetModel.TEMPLATE_ANSWER_FORMAT_MAX_UTF8_BYTES,
                ),
            )
        total =
            addProviderTextBytes(
                total,
                validateNullableProviderText(
                    template.browserQuestionFormat,
                    AnkiLimitsV1.TargetModel.TEMPLATE_BROWSER_QUESTION_FORMAT_MAX_UTF8_BYTES,
                ),
            )
        total =
            addProviderTextBytes(
                total,
                validateNullableProviderText(
                    template.browserAnswerFormat,
                    AnkiLimitsV1.TargetModel.TEMPLATE_BROWSER_ANSWER_FORMAT_MAX_UTF8_BYTES,
                ),
            )
        requireTarget(total <= AnkiLimitsV1.TargetModel.PROVIDER_TEXT_TOTAL_MAX_UTF8_BYTES)
        return total
    }

    fun validateModel(snapshot: ModelSnapshot) {
        val base =
            validateModelBase(
                id = snapshot.id,
                name = snapshot.name,
                type = snapshot.type,
                rawFieldNames = snapshot.fieldNames.joinToString(FIELD_SEPARATOR.toString()),
                cardCount = snapshot.cardCount,
                sortFieldIndex = snapshot.sortFieldIndex,
                effectiveDefaultDeckId = snapshot.effectiveDefaultDeckId,
                css = snapshot.css,
                latexPre = snapshot.latexPre,
                latexPost = snapshot.latexPost,
            )
        requireTarget(base.fieldNames == snapshot.fieldNames)
        requireTarget(snapshot.templates.size == snapshot.cardCount)
        requireTarget(snapshot.templates.map { it.ordinal } == snapshot.templates.indices.toList())
        requireTarget(snapshot.templates.map { it.ordinal }.distinct().size == snapshot.templates.size)
        var providerTextBytes = base.providerTextUtf8Bytes
        for (template in snapshot.templates) {
            providerTextBytes =
                validateTemplate(
                    template = template,
                    expectedModelId = snapshot.id,
                    cardCount = snapshot.cardCount,
                    providerTextUtf8Bytes = providerTextBytes,
                )
        }
    }

    fun validateDeck(snapshot: DeckSnapshot) {
        requireTarget(snapshot.id > 0L)
        validateCanonicalName(
            snapshot.name,
            AnkiLimitsV1.Names.Deck.MAX_CODE_POINTS,
            AnkiLimitsV1.Names.Deck.MAX_UTF8_BYTES,
        )
        requireTarget(!snapshot.dynamic)
    }

    fun validateFirstField(value: String): Int {
        val stats =
            try {
                AnkiValidators.strictStats(value, "provider first field")
            } catch (_: AnkiProtocolException) {
                throw InvalidProviderValueException()
            }
        if (
            stats.scalarCount > AnkiLimitsV1.ScanFirstFields.FIRST_FIELD_MAX_CODE_POINTS ||
                stats.utf8Bytes > AnkiLimitsV1.ScanFirstFields.FIRST_FIELD_MAX_UTF8_BYTES
        ) {
            throw InvalidProviderValueException()
        }
        return stats.utf8Bytes
    }

    private fun validateProviderText(
        value: String,
        maxBytes: Int,
    ): Int {
        val stats =
            try {
                AnkiValidators.strictStats(value, "provider model text")
            } catch (_: AnkiProtocolException) {
                throw InvalidTargetSnapshotException()
            }
        requireTarget(stats.utf8Bytes <= maxBytes)
        return stats.utf8Bytes
    }

    private fun validateNullableProviderText(
        value: String?,
        maxBytes: Int,
    ): Int = value?.let { validateProviderText(it, maxBytes) } ?: 0

    private fun addProviderTextBytes(
        left: Int,
        right: Int,
    ): Int =
        try {
            Math.addExact(left, right)
        } catch (_: ArithmeticException) {
            throw InvalidTargetSnapshotException()
        }

    private fun validateCanonicalName(
        value: String,
        maxScalars: Int,
        maxBytes: Int,
    ): Int {
        val scalarCount =
            UnicodeContractV151.scalarCount(value) ?: throw InvalidTargetSnapshotException()
        val utf8Bytes =
            UnicodeContractV151.strictUtf8Length(value) ?: throw InvalidTargetSnapshotException()
        requireTarget(scalarCount > 0 && scalarCount <= maxScalars && utf8Bytes <= maxBytes)
        requireTarget(UnicodeContractV151.isNfc(value))
        requireTarget(!UnicodeContractV151.hasLeadingOrTrailingPythonWhitespace(value))
        var index = 0
        while (index < value.length) {
            val first = value[index].code
            val codePoint =
                if (first in HIGH_SURROGATE_RANGE) {
                    val second = value[index + 1].code
                    index += 2
                    0x10000 + ((first - 0xD800) shl 10) + (second - 0xDC00)
                } else {
                    index += 1
                    first
                }
            requireTarget(!UnicodeContractV151.isCategoryC(codePoint))
        }
        return utf8Bytes
    }

    private fun requireTarget(condition: Boolean) {
        if (!condition) throw InvalidTargetSnapshotException()
    }

    private const val FIELD_SEPARATOR = '\u001f'
    private val HIGH_SURROGATE_RANGE = 0xD800..0xDBFF
}

internal class InvalidTargetSnapshotException : RuntimeException()

internal class InvalidProviderValueException : RuntimeException()
