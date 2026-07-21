package com.ankiminer.android.vm

import com.ankiminer.android.anki.provider.AnkiFieldMappingChange
import com.ankiminer.android.anki.provider.AnkiProviderReadiness
import com.ankiminer.android.anki.provider.AnkiRecoveryReadiness
import com.ankiminer.android.anki.provider.AnkiRemediationInventory
import com.ankiminer.android.anki.provider.ModelSummary
import com.ankiminer.android.anki.provider.NoteTypeSetupStatus
import com.ankiminer.android.data.RuntimeWorkCoordinator
import com.ankiminer.android.data.anki.AnkiRecoveryInventoryStatus
import com.ankiminer.android.data.anki.AnkiSetupFailure
import com.ankiminer.android.data.anki.AnkiSetupOperation
import com.ankiminer.android.data.resources.CatalogDictionaryStatus
import com.ankiminer.android.data.resources.DictionaryLookup
import com.ankiminer.android.data.resources.InstalledDictionary
import com.ankiminer.android.data.resources.InstalledAudioPack
import com.ankiminer.android.data.resources.InstalledFrequencySource
import com.ankiminer.android.data.resources.InstalledPitchAccent
import com.ankiminer.android.data.resources.KnownWordsInventory
import com.ankiminer.android.data.resources.KnownWordsImportPreview
import com.ankiminer.android.data.resources.KnownWordsPage
import com.ankiminer.android.data.resources.BundledWordset
import com.ankiminer.android.data.resources.LocalResourceImportResult
import com.ankiminer.android.data.resources.FrequencySourceFormat
import com.ankiminer.android.data.resources.KnownWordsSourceFormat
import com.ankiminer.android.data.resources.PitchAccentSourceFormat
import com.ankiminer.android.data.resources.ResourceFailure
import com.ankiminer.android.data.resources.ResourceOperationProgress
import com.ankiminer.android.data.resources.ResourceStartupReadiness
import com.ankiminer.android.engine.PythonRuntimeReadiness
import com.ankiminer.android.mining.NotificationPermissionReadiness

internal data class SetupUiState(
    val python: PythonRuntimeReadiness = PythonRuntimeReadiness.Pending,
    val resourceStartup: ResourceStartupReadiness = ResourceStartupReadiness.PENDING,
    val anki: AnkiProviderReadiness = AnkiProviderReadiness.NotChecked,
    val ankiRecovery: AnkiRecoveryReadiness = AnkiRecoveryReadiness.NotChecked,
    val notifications: NotificationPermissionReadiness = NotificationPermissionReadiness.READY,
    val noteTypeStatus: NoteTypeSetupStatus = NoteTypeSetupStatus.NotSelected,
    val availableNoteTypes: List<ModelSummary> = emptyList(),
    val availableDeckNames: List<String> = emptyList(),
    val deckName: String? = null,
    val deckPersistence: DeckPersistenceStatus = DeckPersistenceStatus.IDLE,
    val failedDeckName: String? = null,
    val noteType: String? = null,
    val fieldMap: Map<String, String> = emptyMap(),
    val fieldMapChanges: List<AnkiFieldMappingChange> = emptyList(),
    val remediations: AnkiRemediationInventory = AnkiRemediationInventory(emptyList()),
    val recoveryInventoryStatus: AnkiRecoveryInventoryStatus =
        AnkiRecoveryInventoryStatus.NOT_CHECKED,
    val ankiOperation: AnkiSetupOperation? = null,
    val ankiFailure: AnkiSetupFailure? = null,
    val ankiRecoveryFailure: AnkiSetupFailure? = null,
    val runtimeWorkKind: RuntimeWorkCoordinator.Kind? = null,
    /** Tri-state startup-flash guard: null until the settings store has emitted once. */
    val wizardSeen: Boolean? = null,
    val wizardCompletion: WizardCompletionStatus = WizardCompletionStatus.IDLE,
    val uniDicInstalled: Boolean = false,
    val catalogDictionaries: List<CatalogDictionaryStatus> = emptyList(),
    val pendingReplaceResourceId: String? = null,
    val dictionaries: List<InstalledDictionary> = emptyList(),
    val frequencySources: List<InstalledFrequencySource> = emptyList(),
    val pitchAccent: InstalledPitchAccent? = null,
    val audioPacks: List<InstalledAudioPack> = emptyList(),
    val knownWords: KnownWordsInventory = KnownWordsInventory(0, 0, 0, 0, schemaOk = true),
    val knownWordsImportPreview: KnownWordsImportPreview? = null,
    val knownWordsPage: KnownWordsPage? = null,
    val wordsets: List<BundledWordset> = emptyList(),
    val lastLocalImport: LocalResourceImportResult? = null,
    val operation: ResourceOperationProgress? = null,
    val failure: ResourceFailure? = null,
    val lookup: DictionaryLookup? = null,
    val lookupTerm: String = "猫",
    val lookupSlotId: String? = null,
    val customSlotId: String = "custom-dictionary",
    val customReplace: Boolean = false,
    val frequencySourceId: String = "frequency",
    val frequencySourceName: String = "Imported frequency",
    val frequencyFormat: FrequencySourceFormat = FrequencySourceFormat.YOMITAN_ZIP,
    val frequencyReplace: Boolean = false,
    val pitchSourceName: String = "Imported pitch accent",
    val pitchFormat: PitchAccentSourceFormat = PitchAccentSourceFormat.YOMITAN_ZIP,
    val pitchReplace: Boolean = false,
    val audioPackId: String = "audio-pack",
    val audioPackReplace: Boolean = false,
    val knownWordsFormat: KnownWordsSourceFormat = KnownWordsSourceFormat.JSON,
    val knownWordsSearch: String = "",
) {
    val customSlotValid: Boolean
        get() = CUSTOM_SLOT_ID.matches(customSlotId)

    val frequencySourceIdValid: Boolean
        get() = CUSTOM_SLOT_ID.matches(frequencySourceId)

    val audioPackIdValid: Boolean
        get() = CUSTOM_SLOT_ID.matches(audioPackId) && audioPackId != "jpod101"

    val pythonReady: Boolean
        get() = python is PythonRuntimeReadiness.Ready

    val ankiReady: Boolean
        get() = anki is AnkiProviderReadiness.Ready

    val notificationReady: Boolean
        get() = notifications == NotificationPermissionReadiness.READY

    val modelReady: Boolean
        get() = noteTypeStatus is NoteTypeSetupStatus.Verified

    val deckSelection: DeckSelectionResolution
        get() = resolveDeckSelection(deckName, availableDeckNames)

    val noteTypeQuality: NoteTypeQualityAssessment
        get() = classifyNoteTypeQuality(noteTypeStatus, fieldMap)

    val recoveryPresentation: RecoveryPresentationDecision
        get() =
            decideRecoveryPresentation(
                provider = anki,
                startupRecovery = ankiRecovery,
                inventoryStatus = recoveryInventoryStatus,
                pendingCount = remediations.pending.size,
            )

    val recoveryReady: Boolean
        get() =
            ankiRecovery == AnkiRecoveryReadiness.Ready &&
                recoveryInventoryStatus == AnkiRecoveryInventoryStatus.AVAILABLE &&
                remediations.pending.isEmpty()

    val targetReady: Boolean
        get() = modelReady

    val busy: Boolean
        get() =
            operation != null ||
                ankiOperation != null ||
                runtimeWorkKind != null ||
                deckPersistence == DeckPersistenceStatus.SAVING

    val isMiningReady: Boolean
        get() =
            pythonReady &&
                resourceStartup == ResourceStartupReadiness.READY &&
                ankiReady &&
                targetReady &&
                recoveryReady &&
                uniDicInstalled &&
                operation == null &&
                ankiOperation == null &&
                runtimeWorkKind != RuntimeWorkCoordinator.Kind.RESOURCE &&
                runtimeWorkKind != RuntimeWorkCoordinator.Kind.ANKI_SETUP

    val ankiDroidAction: AnkiDroidSetupAction?
        get() =
            when (val readiness = anki) {
                AnkiProviderReadiness.NotInstalled -> AnkiDroidSetupAction.INSTALL
                AnkiProviderReadiness.Uninitialized,
                -> AnkiDroidSetupAction.OPEN
                is AnkiProviderReadiness.Incompatible ->
                    if (readiness.apiSpecVersion == null) {
                        AnkiDroidSetupAction.OPEN_OR_INSTALL
                    } else {
                        AnkiDroidSetupAction.INSTALL
                    }
                AnkiProviderReadiness.PermissionDenied ->
                    AnkiDroidSetupAction.REQUEST_PERMISSION
                AnkiProviderReadiness.NotChecked,
                is AnkiProviderReadiness.Ready,
                -> null
            }

    private companion object {
        val CUSTOM_SLOT_ID = Regex("(?!.*(?:\\.\\.|--))[a-z0-9](?:[a-z0-9._-]{0,62}[a-z0-9])?")
    }
}

internal enum class AnkiDroidSetupAction {
    INSTALL,
    OPEN,
    OPEN_OR_INSTALL,
    REQUEST_PERMISSION,
}
