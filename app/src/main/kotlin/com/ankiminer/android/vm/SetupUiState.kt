package com.ankiminer.android.vm

import com.ankiminer.android.anki.provider.AnkiFieldMappingChange
import com.ankiminer.android.anki.provider.AnkiProviderReadiness
import com.ankiminer.android.anki.provider.AnkiRecoveryReadiness
import com.ankiminer.android.anki.provider.AnkiRemediationInventory
import com.ankiminer.android.anki.provider.ModelSummary
import com.ankiminer.android.anki.provider.NoteTypeProviderErrorReason
import com.ankiminer.android.anki.provider.NoteTypeSetupStatus
import com.ankiminer.android.data.RuntimeWorkCoordinator
import com.ankiminer.android.data.anki.AnkiRecoveryInventoryStatus
import com.ankiminer.android.data.anki.AnkiSetupFailure
import com.ankiminer.android.data.anki.AnkiSetupOperation
import com.ankiminer.android.data.resources.AudioPackCandidate
import com.ankiminer.android.data.resources.CatalogDictionaryStatus
import com.ankiminer.android.data.resources.DictionaryLookup
import com.ankiminer.android.data.resources.InstalledDictionary
import com.ankiminer.android.data.resources.InstalledAudioPack
import com.ankiminer.android.data.resources.InstalledFrequencySource
import com.ankiminer.android.data.resources.InstalledPitchSource
import com.ankiminer.android.data.resources.InstalledWordList
import com.ankiminer.android.data.resources.KnownWordsInventory
import com.ankiminer.android.data.resources.KnownWordsImportPreview
import com.ankiminer.android.data.resources.KnownWordsPage
import com.ankiminer.android.data.resources.BundledWordset
import com.ankiminer.android.data.resources.LocalResourceImportResult
import com.ankiminer.android.data.resources.ResourceFailure
import com.ankiminer.android.data.resources.ResourceOperationProgress
import com.ankiminer.android.data.resources.ResourceStartupReadiness
import com.ankiminer.android.data.resources.WordListKind
import com.ankiminer.android.data.settings.CardType
import com.ankiminer.android.data.settings.EngineSettingsSnapshotMapper
import com.ankiminer.android.data.settings.ResourceChainSelection
import com.ankiminer.android.engine.PythonRuntimeReadiness
import com.ankiminer.android.mining.AnkiMiningTargetReadiness
import com.ankiminer.android.mining.NotificationPermissionReadiness

internal data class SetupUiState(
    val python: PythonRuntimeReadiness = PythonRuntimeReadiness.Pending,
    val resourceStartup: ResourceStartupReadiness = ResourceStartupReadiness.PENDING,
    val anki: AnkiProviderReadiness = AnkiProviderReadiness.NotChecked,
    val ankiRecovery: AnkiRecoveryReadiness = AnkiRecoveryReadiness.NotChecked,
    val miningTarget: AnkiMiningTargetReadiness = AnkiMiningTargetReadiness.NotChecked,
    val notifications: NotificationPermissionReadiness = NotificationPermissionReadiness.READY,
    val noteTypeStatus: NoteTypeSetupStatus = NoteTypeSetupStatus.NotSelected,
    val availableNoteTypes: List<ModelSummary> = emptyList(),
    val availableDeckNames: List<String> = emptyList(),
    val deckName: String? = null,
    val deckPersistence: DeckPersistenceStatus = DeckPersistenceStatus.IDLE,
    val failedDeckName: String? = null,
    val noteType: String? = null,
    val fieldMap: Map<String, String> = emptyMap(),
    val cardType: CardType? = null,
    val cardTypeMarkerField: String? = null,
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
    /** Packs found in a picked archive that holds more than one; empty when no choice is open. */
    val audioPackChoices: List<AudioPackCandidate> = emptyList(),
    val uniDicInstalled: Boolean = false,
    val catalogDictionaries: List<CatalogDictionaryStatus> = emptyList(),
    val pendingReplace: PendingResourceReplace? = null,
    val pendingDelete: PendingResourceDelete? = null,
    val dictionaries: List<InstalledDictionary> = emptyList(),
    /** Persisted dictionary chain choices; a disabled entry blocks the engine's provider gate. */
    val dictionarySources: List<ResourceChainSelection> = emptyList(),
    val frequencySources: List<InstalledFrequencySource> = emptyList(),
    val pitchSources: List<InstalledPitchSource> = emptyList(),
    val audioPacks: List<InstalledAudioPack> = emptyList(),
    val knownWords: KnownWordsInventory = KnownWordsInventory(0, 0, 0, 0, schemaOk = true),
    val knownWordsImportPreview: KnownWordsImportPreview? = null,
    val knownWordsPage: KnownWordsPage? = null,
    val wordsets: List<BundledWordset> = emptyList(),
    val wordLists: List<InstalledWordList> = emptyList(),
    val lastLocalImport: LocalResourceImportResult? = null,
    val operation: ResourceOperationProgress? = null,
    val failure: ResourceFailure? = null,
    val lookup: DictionaryLookup? = null,
    val lookupTerm: String = "猫",
    val lookupSlotId: String? = null,
    val wordListTarget: WordListKind = WordListKind.BLACKLIST,
    val knownWordsSearch: String = "",
) {
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
        get() = modelReady && miningTarget == AnkiMiningTargetReadiness.Ready

    /**
     * A dictionary is a mining prerequisite, not a nicety: the engine's
     * `require_usable_offline_provider` raises `SetupError` before any work happens, so a run
     * started without one dies seconds in with nothing to show for it. Mirrors the engine's
     * gate exactly — the chain the engine sees is `resolveResourceChain` over the chain-eligible
     * slots, and the engine skips disabled entries and 0-entry indexes.
     */
    val dictionaryReady: Boolean
        get() =
            EngineSettingsSnapshotMapper
                .resolveResourceChain(
                    dictionarySources,
                    dictionaries.filter { it.isChainEligible }.map { it.slotId },
                ).any { it.enabled }

    val busy: Boolean
        get() =
            operation != null ||
                ankiOperation != null ||
                runtimeWorkKind != null ||
                // Only in-progress startup counts as busy. FAILED is terminal and must fall
                // through to its own CHECK_AGAIN action instead of showing a spinner forever.
                resourceStartup == ResourceStartupReadiness.PENDING ||
                resourceStartup == ResourceStartupReadiness.RECOVERING ||
                deckPersistence == DeckPersistenceStatus.SAVING

    val isMiningReady: Boolean
        get() =
            pythonReady &&
                resourceStartup == ResourceStartupReadiness.READY &&
                ankiReady &&
                targetReady &&
                recoveryReady &&
                uniDicInstalled &&
                dictionaryReady &&
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

    /**
     * One corrective action for the first blocking mining-readiness condition.
     * Active startup/setup work wins so UI never offers an action that cannot run.
     */
    val miningReadinessAction: MiningReadinessAction
        get() =
            when {
                busy -> MiningReadinessAction.WAIT
                python == PythonRuntimeReadiness.Pending ||
                    python == PythonRuntimeReadiness.Starting ->
                    MiningReadinessAction.WAIT
                python is PythonRuntimeReadiness.Failed ->
                    MiningReadinessAction.CHECK_AGAIN
                resourceStartup == ResourceStartupReadiness.PENDING ||
                    resourceStartup == ResourceStartupReadiness.RECOVERING ->
                    MiningReadinessAction.WAIT
                resourceStartup == ResourceStartupReadiness.FAILED ->
                    MiningReadinessAction.CHECK_AGAIN
                !uniDicInstalled -> MiningReadinessAction.INSTALL_UNIDIC
                !dictionaryReady ->
                    if (dictionaries.any { it.occupied }) {
                        MiningReadinessAction.ENABLE_DICTIONARY
                    } else {
                        MiningReadinessAction.INSTALL_DICTIONARY
                    }
                anki == AnkiProviderReadiness.NotInstalled ->
                    MiningReadinessAction.INSTALL_ANKIDROID
                anki == AnkiProviderReadiness.Uninitialized ->
                    MiningReadinessAction.OPEN_ANKIDROID
                anki == AnkiProviderReadiness.PermissionDenied ->
                    MiningReadinessAction.CONNECT_ANKIDROID
                anki is AnkiProviderReadiness.Incompatible ->
                    if (anki.apiSpecVersion == null) {
                        MiningReadinessAction.OPEN_ANKIDROID
                    } else {
                        MiningReadinessAction.INSTALL_ANKIDROID
                    }
                anki == AnkiProviderReadiness.NotChecked ->
                    MiningReadinessAction.CHECK_AGAIN
                noteTypeStatus is NoteTypeSetupStatus.ProviderError ->
                    noteTypeStatus.readinessAction()
                !targetReady -> MiningReadinessAction.CHOOSE_NOTE_TYPE
                ankiRecovery == AnkiRecoveryReadiness.Blocked ->
                    MiningReadinessAction.RESOLVE_RECOVERY
                recoveryInventoryStatus == AnkiRecoveryInventoryStatus.AVAILABLE &&
                    remediations.pending.isNotEmpty() ->
                    MiningReadinessAction.RESOLVE_RECOVERY
                !recoveryReady -> MiningReadinessAction.CHECK_AGAIN
                else -> MiningReadinessAction.CHECK_AGAIN
            }

}

private fun NoteTypeSetupStatus.ProviderError.readinessAction(): MiningReadinessAction =
    when (reason) {
        NoteTypeProviderErrorReason.API_DISABLED,
        NoteTypeProviderErrorReason.API_DISABLED_OR_INCOMPATIBLE,
        NoteTypeProviderErrorReason.PROVIDER_UNAVAILABLE,
        NoteTypeProviderErrorReason.PROVIDER_BECAME_UNAVAILABLE,
        -> MiningReadinessAction.OPEN_ANKIDROID
        NoteTypeProviderErrorReason.API_INCOMPATIBLE ->
            MiningReadinessAction.INSTALL_ANKIDROID
        NoteTypeProviderErrorReason.PERMISSION_REQUIRED ->
            MiningReadinessAction.CONNECT_ANKIDROID
        NoteTypeProviderErrorReason.QUERY_FAILED,
        NoteTypeProviderErrorReason.TIMEOUT,
        NoteTypeProviderErrorReason.CANCELLED,
        NoteTypeProviderErrorReason.UNKNOWN,
        -> MiningReadinessAction.CHECK_AGAIN
    }

internal enum class AnkiDroidSetupAction {
    INSTALL,
    OPEN,
    OPEN_OR_INSTALL,
    REQUEST_PERMISSION,
}

/** Corrective action rendered by a mining destination when setup is not ready. */
internal enum class MiningReadinessAction {
    INSTALL_UNIDIC,
    INSTALL_DICTIONARY,
    /** A dictionary slot is occupied but disabled in the chain or empty — review, don't install. */
    ENABLE_DICTIONARY,
    INSTALL_ANKIDROID,
    OPEN_ANKIDROID,
    CONNECT_ANKIDROID,
    CHOOSE_NOTE_TYPE,
    RESOLVE_RECOVERY,
    CHECK_AGAIN,
    WAIT,
}
