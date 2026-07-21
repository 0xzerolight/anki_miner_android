package com.ankiminer.android.vm

import com.ankiminer.android.anki.provider.AnkiFieldKeys
import com.ankiminer.android.anki.provider.AnkiMinerNoteModel
import com.ankiminer.android.anki.provider.AnkiProviderReadiness
import com.ankiminer.android.anki.provider.AnkiRecoveryReadiness
import com.ankiminer.android.anki.provider.NoteTypeSetupStatus
import com.ankiminer.android.data.anki.AnkiRecoveryInventoryStatus

internal enum class DeckChoiceKind {
    CREATE_OR_USE_DEFAULT,
    EXISTING,
    SAVED_UNAVAILABLE,
}

internal enum class DeckPersistenceStatus {
    IDLE,
    SAVING,
    FAILED,
}

internal data class DeckChoice(
    val deckName: String,
    val kind: DeckChoiceKind,
    val selected: Boolean,
)

internal data class DeckSelectionResolution(
    val selectedDeckName: String,
    val choices: List<DeckChoice>,
)

/** Resolves the nullable persisted value into one explicit, stable wizard choice. */
internal fun resolveDeckSelection(
    savedDeckName: String?,
    discoveredDeckNames: List<String>,
): DeckSelectionResolution {
    val defaultName = AnkiMinerNoteModel.DEFAULT_DECK_NAME
    val selectedName = savedDeckName ?: defaultName
    val discovered = discoveredDeckNames.distinct()
    val choices = mutableListOf(defaultName to DeckChoiceKind.CREATE_OR_USE_DEFAULT)
    discovered
        .filterNot { it == defaultName }
        .forEach { choices += it to DeckChoiceKind.EXISTING }
    if (selectedName != defaultName && selectedName !in discovered) {
        choices += selectedName to DeckChoiceKind.SAVED_UNAVAILABLE
    }
    return DeckSelectionResolution(
        selectedDeckName = selectedName,
        choices =
            choices.map { (name, kind) ->
                DeckChoice(name, kind, selected = name == selectedName)
            },
    )
}

internal data class NoteTypeMappedFieldSummary(
    val word: String,
    val sentence: String,
    val definitions: List<String>,
    val audio: List<String>,
    val image: String,
)

internal data class NoteTypeQualityAssessment(
    val writableAndDedupSafe: Boolean,
    val usefulForMining: Boolean,
    val fullyEnriched: Boolean,
    val fields: NoteTypeMappedFieldSummary,
)

/**
 * Both Android mining workflows produce a sentence and dictionary content. A useful target can
 * retain both; full enrichment additionally retains at least one audio value and an image.
 */
internal fun classifyNoteTypeQuality(
    status: NoteTypeSetupStatus,
    fieldMap: Map<String, String>,
): NoteTypeQualityAssessment {
    fun mapped(key: String): String = fieldMap[key].orEmpty()
    fun mappedAll(vararg keys: String): List<String> =
        keys.map(::mapped).filter(String::isNotEmpty).distinct()

    val fields =
        NoteTypeMappedFieldSummary(
            word = mapped(AnkiFieldKeys.WORD),
            sentence = mapped("sentence"),
            definitions = mappedAll("definition", "glossary"),
            audio = mappedAll("audio", "expression_audio"),
            image = mapped("picture"),
        )
    val writable = status is NoteTypeSetupStatus.Verified
    val useful = writable && fields.sentence.isNotEmpty() && fields.definitions.isNotEmpty()
    return NoteTypeQualityAssessment(
        writableAndDedupSafe = writable,
        usefulForMining = useful,
        fullyEnriched = useful && fields.audio.isNotEmpty() && fields.image.isNotEmpty(),
        fields = fields,
    )
}

internal enum class RecoveryPresentationKind {
    CHECKING,
    INVENTORY_UNAVAILABLE,
    STARTUP_BLOCKED,
    STARTUP_BLOCKED_PROVIDER_UNAVAILABLE,
    PENDING,
    PENDING_PROVIDER_UNAVAILABLE,
    CLEAR,
}

internal data class RecoveryPresentationDecision(
    val kind: RecoveryPresentationKind,
    val showInventory: Boolean,
    val canReconcile: Boolean,
)

/** Keeps local journal visibility independent from current ContentProvider availability. */
internal fun decideRecoveryPresentation(
    provider: AnkiProviderReadiness,
    startupRecovery: AnkiRecoveryReadiness,
    inventoryStatus: AnkiRecoveryInventoryStatus,
    pendingCount: Int,
): RecoveryPresentationDecision {
    require(pendingCount >= 0)
    val providerReady = provider is AnkiProviderReadiness.Ready
    val showInventory = inventoryStatus == AnkiRecoveryInventoryStatus.AVAILABLE
    val kind =
        when {
            inventoryStatus == AnkiRecoveryInventoryStatus.NOT_CHECKED ->
                RecoveryPresentationKind.CHECKING
            inventoryStatus == AnkiRecoveryInventoryStatus.UNAVAILABLE ->
                RecoveryPresentationKind.INVENTORY_UNAVAILABLE
            pendingCount > 0 && !providerReady ->
                RecoveryPresentationKind.PENDING_PROVIDER_UNAVAILABLE
            pendingCount > 0 -> RecoveryPresentationKind.PENDING
            startupRecovery == AnkiRecoveryReadiness.Blocked && !providerReady ->
                RecoveryPresentationKind.STARTUP_BLOCKED_PROVIDER_UNAVAILABLE
            startupRecovery == AnkiRecoveryReadiness.Blocked ->
                RecoveryPresentationKind.STARTUP_BLOCKED
            startupRecovery == AnkiRecoveryReadiness.NotChecked ->
                RecoveryPresentationKind.CHECKING
            else -> RecoveryPresentationKind.CLEAR
        }
    return RecoveryPresentationDecision(
        kind = kind,
        showInventory = showInventory,
        canReconcile =
            providerReady &&
                inventoryStatus == AnkiRecoveryInventoryStatus.AVAILABLE &&
                (pendingCount > 0 || startupRecovery == AnkiRecoveryReadiness.Blocked),
    )
}

internal enum class WizardCompletionStatus {
    IDLE,
    SAVING,
    FAILED,
    PERSISTED,
    DISMISSED_FOR_SESSION,
}

internal enum class WizardCompletionEvent {
    REQUEST_PERSISTENCE,
    PERSISTENCE_SUCCEEDED,
    PERSISTENCE_FAILED,
    DISMISS_FOR_SESSION,
}

/** Pure state transition used by ViewModel retry/escape handling and JVM tests. */
internal fun reduceWizardCompletion(
    current: WizardCompletionStatus,
    event: WizardCompletionEvent,
): WizardCompletionStatus =
    when (event) {
        WizardCompletionEvent.REQUEST_PERSISTENCE ->
            when (current) {
                WizardCompletionStatus.IDLE,
                WizardCompletionStatus.FAILED,
                WizardCompletionStatus.DISMISSED_FOR_SESSION,
                -> WizardCompletionStatus.SAVING
                WizardCompletionStatus.SAVING,
                WizardCompletionStatus.PERSISTED,
                -> current
            }
        WizardCompletionEvent.PERSISTENCE_SUCCEEDED ->
            if (current == WizardCompletionStatus.SAVING) WizardCompletionStatus.PERSISTED else current
        WizardCompletionEvent.PERSISTENCE_FAILED ->
            if (current == WizardCompletionStatus.SAVING) WizardCompletionStatus.FAILED else current
        WizardCompletionEvent.DISMISS_FOR_SESSION ->
            if (current == WizardCompletionStatus.FAILED) {
                WizardCompletionStatus.DISMISSED_FOR_SESSION
            } else {
                current
            }
    }
