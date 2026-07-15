package com.ankiminer.android.anki.provider

import com.ankiminer.android.anki.journal.DurableDeckSnapshot
import com.ankiminer.android.anki.journal.DurableModelSnapshot
import com.ankiminer.android.anki.journal.DurableTargetExpectation
import com.ankiminer.android.anki.journal.DurableTargetSnapshot
import com.ankiminer.android.anki.journal.DurableTemplateSnapshot

internal fun ModelSnapshot.toDurableSnapshot(): DurableModelSnapshot =
    DurableModelSnapshot(
        id = id,
        name = name,
        type = type,
        fieldNames = fieldNames.toList(),
        cardCount = cardCount,
        sortFieldIndex = sortFieldIndex,
        effectiveDefaultDeckId = effectiveDefaultDeckId,
        css = css,
        latexPre = latexPre,
        latexPost = latexPost,
        templates =
            templates.map { template ->
                DurableTemplateSnapshot(
                    modelId = template.modelId,
                    ordinal = template.ordinal,
                    name = template.name,
                    questionFormat = template.questionFormat,
                    answerFormat = template.answerFormat,
                    browserQuestionFormat = template.browserQuestionFormat,
                    browserAnswerFormat = template.browserAnswerFormat,
                )
            },
    )

internal fun TargetSnapshot.toDurableSnapshot(): DurableTargetSnapshot {
    ProviderSnapshotValidation.validateModel(model)
    ProviderSnapshotValidation.validateDeck(deck)
    return DurableTargetSnapshot(
        deck = DurableDeckSnapshot(deck.id, deck.name, deck.dynamic),
        model = model.toDurableSnapshot(),
    )
}

internal fun ModelSnapshot.toDurableExpectation(expectedDeckName: String): DurableTargetExpectation {
    ProviderSnapshotValidation.validateModel(this)
    ProviderSnapshotValidation.validateDeck(DeckSnapshot(1L, expectedDeckName, dynamic = false))
    return DurableTargetExpectation(expectedDeckName, toDurableSnapshot())
}

internal fun DurableTargetSnapshot.toProviderSnapshot(): TargetSnapshot {
    val providerModel = model.toProviderSnapshot()
    val providerDeck = DeckSnapshot(deck.id, deck.name, deck.dynamic)
    ProviderSnapshotValidation.validateModel(providerModel)
    ProviderSnapshotValidation.validateDeck(providerDeck)
    return TargetSnapshot(providerDeck, providerModel)
}

internal fun DurableModelSnapshot.toProviderSnapshot(): ModelSnapshot =
    ModelSnapshot(
        id = id,
        name = name,
        type = type,
        fieldNames = fieldNames.toList(),
        cardCount = cardCount,
        sortFieldIndex = sortFieldIndex,
        effectiveDefaultDeckId = effectiveDefaultDeckId,
        css = css,
        latexPre = latexPre,
        latexPost = latexPost,
        templates =
            templates.map { template ->
                TemplateSnapshot(
                    modelId = template.modelId,
                    ordinal = template.ordinal,
                    name = template.name,
                    questionFormat = template.questionFormat,
                    answerFormat = template.answerFormat,
                    browserQuestionFormat = template.browserQuestionFormat,
                    browserAnswerFormat = template.browserAnswerFormat,
                )
            },
    )
