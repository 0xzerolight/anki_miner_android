package com.ankiminer.android.ui.setup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ankiminer.android.R
import com.ankiminer.android.anki.provider.AnkiExternalReviewOutcome
import com.ankiminer.android.data.resources.FrequencySourceFormat
import com.ankiminer.android.data.resources.KnownWordsSourceFormat
import com.ankiminer.android.data.resources.PitchAccentSourceFormat
import com.ankiminer.android.ui.settings.AnkiFailureCard
import com.ankiminer.android.ui.settings.AnkiOperationCard
import com.ankiminer.android.ui.settings.AnkiRecoveryCard
import com.ankiminer.android.ui.settings.AnkiTargetCard
import com.ankiminer.android.ui.settings.AudioPackImportCard
import com.ankiminer.android.ui.settings.CatalogDictionaryCards
import com.ankiminer.android.ui.settings.CatalogReplaceDialog
import com.ankiminer.android.ui.settings.CustomDictionaryImportCard
import com.ankiminer.android.ui.settings.DictionaryInventoryCard
import com.ankiminer.android.ui.settings.DictionaryLookupCard
import com.ankiminer.android.ui.settings.FrequencyImportCard
import com.ankiminer.android.ui.settings.KnownWordsImportCard
import com.ankiminer.android.ui.settings.LocalImportResultCard
import com.ankiminer.android.ui.settings.PitchImportCard
import com.ankiminer.android.ui.settings.ResourceCard
import com.ankiminer.android.ui.settings.ResourceFailureCard
import com.ankiminer.android.ui.settings.ResourceOperationCard
import com.ankiminer.android.ui.settings.SystemStatusCard
import com.ankiminer.android.ui.settings.BundledWordsetInventoryCard
import com.ankiminer.android.vm.SetupViewModel

@Composable
internal fun SetupRoute(
    viewModel: SetupViewModel,
    onRequestPermissions: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onInstallAnkiDroid: () -> Unit,
    onOpenAnkiDroid: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(viewModel) { viewModel.refresh() }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val dictionaryPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { viewModel.importCustomDictionary(it.toString()) }
        }
    val frequencyPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { viewModel.importFrequencySource(it.toString()) }
        }
    val pitchPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { viewModel.importPitchAccent(it.toString()) }
        }
    val audioPackPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { viewModel.importAudioPack(it.toString()) }
        }
    val knownWordsPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { viewModel.importKnownWords(it.toString()) }
        }
    SetupScreen(
        state = state,
        onRefresh = viewModel::refresh,
        onRequestPermissions = onRequestPermissions,
        onOpenAppSettings = onOpenAppSettings,
        onInstallAnkiDroid = onInstallAnkiDroid,
        onOpenAnkiDroid = onOpenAnkiDroid,
        onProvisionModel = viewModel::provisionModel,
        onReconcileAnki = viewModel::reconcileInterruptedWork,
        onRetryStaging = viewModel::retryStagingCleanup,
        onAcknowledgeMedia = viewModel::acknowledgeUnattachedMedia,
        onAcknowledgeUncertainMedia = viewModel::acknowledgeUncertainMedia,
        onResolveReview = viewModel::resolveAfterExternalReview,
        onDismissAnkiFailure = viewModel::dismissAnkiFailure,
        onInstallUniDic = viewModel::installUniDic,
        onInstallCatalogDictionary = viewModel::installCatalogDictionary,
        onConfirmCatalogReplace = viewModel::confirmCatalogDictionaryReplace,
        onDismissCatalogReplace = viewModel::dismissCatalogDictionaryReplace,
        onImportCustom = { dictionaryPicker.launch(arrayOf("application/zip", "application/octet-stream")) },
        onCustomSlotChanged = viewModel::setCustomSlotId,
        onCustomReplaceChanged = viewModel::setCustomReplace,
        onFrequencyIdChanged = viewModel::setFrequencySourceId,
        onFrequencyNameChanged = viewModel::setFrequencySourceName,
        onFrequencyFormatChanged = viewModel::setFrequencyFormat,
        onFrequencyReplaceChanged = viewModel::setFrequencyReplace,
        onImportFrequency = {
            frequencyPicker.launch(arrayOf("application/zip", "text/csv", "text/tab-separated-values", "text/plain", "application/octet-stream"))
        },
        onPitchNameChanged = viewModel::setPitchSourceName,
        onPitchFormatChanged = viewModel::setPitchFormat,
        onPitchReplaceChanged = viewModel::setPitchReplace,
        onImportPitch = {
            pitchPicker.launch(arrayOf("application/zip", "text/csv", "text/tab-separated-values", "application/octet-stream"))
        },
        onAudioPackIdChanged = viewModel::setAudioPackId,
        onAudioPackReplaceChanged = viewModel::setAudioPackReplace,
        onImportAudioPack = { audioPackPicker.launch(arrayOf("application/zip", "application/octet-stream")) },
        onKnownWordsFormatChanged = viewModel::setKnownWordsFormat,
        onImportKnownWords = {
            knownWordsPicker.launch(arrayOf("application/json", "text/csv", "text/tab-separated-values", "text/plain", "application/octet-stream"))
        },
        onCancel = viewModel::cancelOperation,
        onDismissFailure = viewModel::dismissFailure,
        onLookupTermChanged = viewModel::setLookupTerm,
        onLookupSlot = viewModel::setLookupSlot,
        onLookup = viewModel::lookup,
        onFinish = viewModel::finishFirstRun,
        onContinue = onContinue,
        modifier = modifier,
    )
}

@Composable
private fun SetupScreen(
    state: SetupUiState,
    onRefresh: () -> Unit,
    onRequestPermissions: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onInstallAnkiDroid: () -> Unit,
    onOpenAnkiDroid: () -> Unit,
    onProvisionModel: () -> Unit,
    onReconcileAnki: () -> Unit,
    onRetryStaging: (Long) -> Unit,
    onAcknowledgeMedia: (Long) -> Unit,
    onAcknowledgeUncertainMedia: (Long) -> Unit,
    onResolveReview: (Long, AnkiExternalReviewOutcome) -> Unit,
    onDismissAnkiFailure: () -> Unit,
    onInstallUniDic: () -> Unit,
    onInstallCatalogDictionary: (String) -> Unit,
    onConfirmCatalogReplace: () -> Unit,
    onDismissCatalogReplace: () -> Unit,
    onImportCustom: () -> Unit,
    onCustomSlotChanged: (String) -> Unit,
    onCustomReplaceChanged: (Boolean) -> Unit,
    onFrequencyIdChanged: (String) -> Unit,
    onFrequencyNameChanged: (String) -> Unit,
    onFrequencyFormatChanged: (FrequencySourceFormat) -> Unit,
    onFrequencyReplaceChanged: (Boolean) -> Unit,
    onImportFrequency: () -> Unit,
    onPitchNameChanged: (String) -> Unit,
    onPitchFormatChanged: (PitchAccentSourceFormat) -> Unit,
    onPitchReplaceChanged: (Boolean) -> Unit,
    onImportPitch: () -> Unit,
    onAudioPackIdChanged: (String) -> Unit,
    onAudioPackReplaceChanged: (Boolean) -> Unit,
    onImportAudioPack: () -> Unit,
    onKnownWordsFormatChanged: (KnownWordsSourceFormat) -> Unit,
    onImportKnownWords: () -> Unit,
    onCancel: () -> Unit,
    onDismissFailure: () -> Unit,
    onLookupTermChanged: (String) -> Unit,
    onLookupSlot: (String) -> Unit,
    onLookup: () -> Unit,
    onFinish: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CatalogReplaceDialog(
        state = state,
        onConfirm = onConfirmCatalogReplace,
        onDismiss = onDismissCatalogReplace,
    )
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.setup_title),
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(stringResource(R.string.setup_intro))

        SystemStatusCard(
            state,
            onRefresh,
            onRequestPermissions,
            onOpenAppSettings,
            onInstallAnkiDroid,
            onOpenAnkiDroid,
        )

        AnkiTargetCard(state, onProvisionModel)

        AnkiRecoveryCard(
            state = state,
            onReconcile = onReconcileAnki,
            onRetryStaging = onRetryStaging,
            onAcknowledgeMedia = onAcknowledgeMedia,
            onAcknowledgeUncertainMedia = onAcknowledgeUncertainMedia,
            onResolveReview = onResolveReview,
        )

        state.ankiOperation?.let { AnkiOperationCard() }
        state.ankiFailure?.let { failure -> AnkiFailureCard(failure, onDismissAnkiFailure) }
        state.operation?.let { operation -> ResourceOperationCard(operation, onCancel) }
        state.failure?.let { failure -> ResourceFailureCard(failure, onDismissFailure) }

        ResourceCard(
            title = stringResource(R.string.unidic_resource_title),
            description = stringResource(R.string.unidic_resource_description),
            installed = state.uniDicInstalled,
            busy = state.busy,
            action = onInstallUniDic,
            actionLabel = stringResource(if (state.uniDicInstalled) R.string.unidic_repair else R.string.unidic_install),
        )
        CatalogDictionaryCards(state, onInstallCatalogDictionary)

        CustomDictionaryImportCard(
            state = state,
            onSlotChanged = onCustomSlotChanged,
            onReplaceChanged = onCustomReplaceChanged,
            onImport = onImportCustom,
        )

        FrequencyImportCard(
            state = state,
            onIdChanged = onFrequencyIdChanged,
            onNameChanged = onFrequencyNameChanged,
            onFormatChanged = onFrequencyFormatChanged,
            onReplaceChanged = onFrequencyReplaceChanged,
            onImport = onImportFrequency,
        )
        PitchImportCard(
            state = state,
            onNameChanged = onPitchNameChanged,
            onFormatChanged = onPitchFormatChanged,
            onReplaceChanged = onPitchReplaceChanged,
            onImport = onImportPitch,
        )
        AudioPackImportCard(
            state = state,
            onIdChanged = onAudioPackIdChanged,
            onReplaceChanged = onAudioPackReplaceChanged,
            onImport = onImportAudioPack,
        )
        KnownWordsImportCard(
            state = state,
            onFormatChanged = onKnownWordsFormatChanged,
            onImport = onImportKnownWords,
        )
        BundledWordsetInventoryCard(state)
        state.lastLocalImport?.let { imported -> LocalImportResultCard(imported) }

        DictionaryInventoryCard(state)

        if (state.dictionaries.any { it.isUsable }) {
            DictionaryLookupCard(
                state = state,
                onTermChanged = onLookupTermChanged,
                onSelectSlot = onLookupSlot,
                onLookup = onLookup,
            )
        }

        HorizontalDivider()
        if (!state.firstRunComplete) {
            Button(
                onClick = onFinish,
                enabled = state.canFinishFirstRun,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.finish_setup))
            }
            if (!state.uniDicInstalled) Text(stringResource(R.string.finish_setup_unidic_required))
            if (!state.targetReady) Text(stringResource(R.string.finish_setup_model_required))
            if (!state.recoveryReady) Text(stringResource(R.string.finish_setup_recovery_required))
            if (state.completionError) Text(stringResource(R.string.finish_setup_save_failed), color = MaterialTheme.colorScheme.error)
        } else {
            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.continue_to_mining))
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
