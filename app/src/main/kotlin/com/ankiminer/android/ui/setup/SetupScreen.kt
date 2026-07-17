package com.ankiminer.android.ui.setup

import android.webkit.WebView
import androidx.annotation.StringRes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ankiminer.android.R
import com.ankiminer.android.anki.provider.AnkiExternalReviewOutcome
import com.ankiminer.android.anki.provider.AnkiMinerModelConflictReason
import com.ankiminer.android.anki.provider.AnkiMinerModelProvisioningResult
import com.ankiminer.android.anki.provider.AnkiRemediationActionKind
import com.ankiminer.android.anki.provider.AnkiRemediationType
import com.ankiminer.android.anki.provider.AnkiProviderReadiness
import com.ankiminer.android.data.resources.ResourceOperationPhase
import com.ankiminer.android.data.resources.ResourceStartupReadiness
import com.ankiminer.android.data.resources.FrequencySourceFormat
import com.ankiminer.android.data.resources.ImportedAudioPack
import com.ankiminer.android.data.resources.ImportedFrequencySource
import com.ankiminer.android.data.resources.ImportedKnownWords
import com.ankiminer.android.data.resources.ImportedPitchAccent
import com.ankiminer.android.data.resources.KnownWordsSourceFormat
import com.ankiminer.android.data.resources.LocalResourceImportResult
import com.ankiminer.android.data.resources.PitchAccentSourceFormat
import com.ankiminer.android.engine.PythonRuntimeReadiness
import com.ankiminer.android.mining.NotificationPermissionReadiness
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
        onInstallRecommended = viewModel::installRecommendedDictionary,
        onConfirmRecommendedReplace = viewModel::confirmRecommendedDictionaryReplace,
        onDismissRecommendedReplace = viewModel::dismissRecommendedDictionaryReplace,
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
    onInstallRecommended: () -> Unit,
    onConfirmRecommendedReplace: () -> Unit,
    onDismissRecommendedReplace: () -> Unit,
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
    if (state.recommendedReplaceConfirmationVisible) {
        AlertDialog(
            onDismissRequest = onDismissRecommendedReplace,
            title = {
                Text(
                    stringResource(
                        if (state.recommendedDictionaryNeedsRepair) {
                            R.string.dictionary_repair_confirm_title
                        } else {
                            R.string.dictionary_replace_confirm_title
                        },
                    ),
                )
            },
            text = { Text(stringResource(R.string.dictionary_replace_confirm_message)) },
            confirmButton = {
                Button(onClick = onConfirmRecommendedReplace) {
                    Text(
                        stringResource(
                            if (state.recommendedDictionaryNeedsRepair) {
                                R.string.dictionary_repair_confirm
                            } else {
                                R.string.dictionary_replace_confirm
                            },
                        ),
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissRecommendedReplace) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
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

        StatusCard(
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

        state.ankiOperation?.let {
            OutlinedCard(
                Modifier
                    .fillMaxWidth()
                    .semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.anki_setup_working), style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }

        state.ankiFailure?.let { failure ->
            OutlinedCard(
                Modifier
                    .fillMaxWidth()
                    .semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.anki_setup_stopped), style = MaterialTheme.typography.titleMedium)
                    Text(failure.message)
                    TextButton(onClick = onDismissAnkiFailure) { Text(stringResource(R.string.dismiss)) }
                }
            }
        }

        state.operation?.let { operation ->
            OutlinedCard(
                Modifier
                    .fillMaxWidth()
                    .semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(operation.label, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(resourcePhaseLabel(operation.phase)))
                    operation.fraction?.let { fraction ->
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                        Text(
                            stringResource(
                                R.string.progress_mebibytes,
                                operation.completedBytes / (1024 * 1024),
                                operation.totalBytes / (1024 * 1024),
                            ),
                        )
                    } ?: LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
                }
            }
        }

        state.failure?.let { failure ->
            OutlinedCard(
                Modifier
                    .fillMaxWidth()
                    .semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.resource_operation_stopped), style = MaterialTheme.typography.titleMedium)
                    Text(failure.message)
                    TextButton(onClick = onDismissFailure) { Text(stringResource(R.string.dismiss)) }
                }
            }
        }

        ResourceCard(
            title = stringResource(R.string.unidic_resource_title),
            description = stringResource(R.string.unidic_resource_description),
            installed = state.uniDicInstalled,
            busy = state.busy,
            action = onInstallUniDic,
            actionLabel = stringResource(if (state.uniDicInstalled) R.string.unidic_repair else R.string.unidic_install),
        )
        ResourceCard(
            title = stringResource(R.string.jitendex_resource_title),
            description = stringResource(R.string.jitendex_resource_description),
            installed = state.recommendedDictionaryInstalled,
            busy = state.busy,
            action = onInstallRecommended,
            actionLabel = stringResource(
                when {
                    state.recommendedDictionaryNeedsRepair -> R.string.dictionary_repair
                    state.recommendedDictionaryInstalled -> R.string.dictionary_replace
                    else -> R.string.dictionary_install
                },
            ),
        )

        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.custom_dictionary_title), style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = state.customSlotId,
                    onValueChange = onCustomSlotChanged,
                    label = { Text(stringResource(R.string.custom_dictionary_slot)) },
                    supportingText = {
                        Text(
                            if (state.customSlotValid) {
                                stringResource(R.string.custom_dictionary_slot_help)
                            } else {
                                stringResource(R.string.custom_dictionary_slot_invalid)
                            },
                        )
                    },
                    isError = !state.customSlotValid,
                    enabled = !state.busy,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = state.customReplace,
                            enabled = !state.busy,
                            role = Role.Checkbox,
                            onValueChange = onCustomReplaceChanged,
                        ).padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Checkbox(
                        checked = state.customReplace,
                        onCheckedChange = null,
                        enabled = !state.busy,
                    )
                    Text(stringResource(R.string.custom_dictionary_replace), Modifier.padding(top = 12.dp))
                }
                OutlinedButton(
                    onClick = onImportCustom,
                    enabled = !state.busy && state.customSlotValid,
                ) {
                    Text(stringResource(R.string.custom_dictionary_choose))
                }
            }
        }

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

@Composable
private fun StatusCard(
    state: SetupUiState,
    onRefresh: () -> Unit,
    onRequestPermissions: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onInstallAnkiDroid: () -> Unit,
    onOpenAnkiDroid: () -> Unit,
) {
    OutlinedCard(
        Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.readiness_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.readiness_python, pythonStatus(state.python)))
            Text(stringResource(R.string.readiness_resource_recovery, resourceStartupStatus(state.resourceStartup)))
            Text(stringResource(R.string.readiness_ankidroid, ankiStatus(state.anki)))
            Text(
                stringResource(
                    R.string.readiness_anki_model,
                    stringResource(if (state.targetReady) R.string.status_ready else R.string.status_action_needed),
                ),
            )
            Text(
                stringResource(
                    R.string.readiness_anki_recovery,
                    stringResource(if (state.recoveryReady) R.string.status_ready else R.string.status_action_needed),
                ),
            )
            Text(
                stringResource(
                    R.string.readiness_notifications,
                    stringResource(
                        if (state.notifications == NotificationPermissionReadiness.READY) {
                            R.string.status_ready
                        } else {
                            R.string.status_optional_permission_denied
                        },
                    ),
                ),
            )
            if (!state.notificationReady) {
                Text(
                    stringResource(R.string.notification_permission_optional_help),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                stringResource(
                    R.string.readiness_unidic,
                    stringResource(
                        if (state.uniDicInstalled) R.string.status_unidic_found else R.string.status_unidic_missing,
                    ),
                ),
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (state.ankiDroidAction) {
                    AnkiDroidSetupAction.INSTALL ->
                        OutlinedButton(
                            onClick = onInstallAnkiDroid,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.install_or_update_ankidroid)) }
                    AnkiDroidSetupAction.OPEN ->
                        OutlinedButton(
                            onClick = onOpenAnkiDroid,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.open_ankidroid)) }
                    AnkiDroidSetupAction.OPEN_OR_INSTALL -> {
                        OutlinedButton(
                            onClick = onOpenAnkiDroid,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.open_ankidroid)) }
                        OutlinedButton(
                            onClick = onInstallAnkiDroid,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.install_or_update_ankidroid)) }
                    }
                    AnkiDroidSetupAction.REQUEST_PERMISSION ->
                        OutlinedButton(
                            onClick = onRequestPermissions,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.allow_required_access)) }
                    null -> Unit
                }
                if (!state.notificationReady) {
                    OutlinedButton(
                        onClick = onRequestPermissions,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.allow_notification_access)) }
                }
                if (
                    state.ankiDroidAction == AnkiDroidSetupAction.REQUEST_PERMISSION ||
                        !state.notificationReady
                ) {
                    OutlinedButton(
                        onClick = onOpenAppSettings,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.open_app_settings)) }
                }
                TextButton(onClick = onRefresh, enabled = !state.busy) {
                    Text(stringResource(R.string.check_again))
                }
            }
        }
    }
}

@Composable
private fun AnkiTargetCard(
    state: SetupUiState,
    onProvisionModel: () -> Unit,
) {
    val modelMessage =
        when (val model = state.model) {
            null -> stringResource(R.string.anki_model_not_checked)
            is AnkiMinerModelProvisioningResult.Ready -> stringResource(R.string.anki_model_ready)
            AnkiMinerModelProvisioningResult.Missing -> stringResource(R.string.anki_model_missing)
            is AnkiMinerModelProvisioningResult.Conflict -> model.stableMessage
            is AnkiMinerModelProvisioningResult.RecoveryRequired -> model.stableMessage
            is AnkiMinerModelProvisioningResult.FailedBeforeEntry -> model.stableMessage
        }
    val actionVisible =
        state.model == AnkiMinerModelProvisioningResult.Missing ||
            state.model is AnkiMinerModelProvisioningResult.RecoveryRequired ||
            state.model is AnkiMinerModelProvisioningResult.FailedBeforeEntry ||
            (state.model as? AnkiMinerModelProvisioningResult.Conflict)?.reason in
                setOf(
                    AnkiMinerModelConflictReason.JOURNAL_CONTRACT_CHANGED,
                    AnkiMinerModelConflictReason.JOURNALED_MODEL_ID_CHANGED,
                )
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.anki_model_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.anki_model_description))
            Text(modelMessage)
            if (state.model is AnkiMinerModelProvisioningResult.Conflict) {
                Text(
                    stringResource(
                        if (actionVisible) {
                            R.string.anki_model_restart_help
                        } else {
                            R.string.anki_model_conflict_help
                        },
                    ),
                )
            }
            if (actionVisible) {
                Button(
                    onClick = onProvisionModel,
                    enabled = state.ankiReady && !state.busy,
                ) {
                    Text(
                        stringResource(
                            if (state.model is AnkiMinerModelProvisioningResult.RecoveryRequired) {
                                R.string.anki_model_resume
                            } else if (state.model is AnkiMinerModelProvisioningResult.Conflict) {
                                R.string.anki_model_restart
                            } else {
                                R.string.anki_model_create
                            },
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun AnkiRecoveryCard(
    state: SetupUiState,
    onReconcile: () -> Unit,
    onRetryStaging: (Long) -> Unit,
    onAcknowledgeMedia: (Long) -> Unit,
    onAcknowledgeUncertainMedia: (Long) -> Unit,
    onResolveReview: (Long, AnkiExternalReviewOutcome) -> Unit,
) {
    var confirmation by remember { mutableStateOf<AnkiRecoveryConfirmation?>(null) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.anki_recovery_title), style = MaterialTheme.typography.titleMedium)
            if (state.remediations.pending.isEmpty()) {
                Text(stringResource(R.string.anki_recovery_clear))
            } else {
                Text(stringResource(R.string.anki_recovery_attention, state.remediations.pending.size))
            }
            if (state.anki == AnkiProviderReadiness.RecoveryBlocked || state.remediations.pending.isNotEmpty()) {
                OutlinedButton(onClick = onReconcile, enabled = !state.busy) {
                    Text(stringResource(R.string.anki_recovery_reconcile))
                }
            }
            state.remediations.pending.forEach { item ->
                HorizontalDivider()
                Text(item.title, style = MaterialTheme.typography.titleSmall)
                Text(item.summary)
                if (AnkiRemediationActionKind.RETRY_STAGING_CLEANUP in item.availableActions) {
                    OutlinedButton(
                        onClick = { onRetryStaging(item.id) },
                        enabled = !state.busy,
                    ) { Text(stringResource(R.string.anki_recovery_retry_cleanup)) }
                }
                if (AnkiRemediationActionKind.ACKNOWLEDGE_UNATTACHED_MEDIA in item.availableActions) {
                    OutlinedButton(
                        onClick = {
                            confirmation = AnkiRecoveryConfirmation.UnattachedMedia(item.id)
                        },
                        enabled = !state.busy,
                    ) { Text(stringResource(R.string.anki_recovery_acknowledge_unattached)) }
                }
                if (AnkiRemediationActionKind.ACKNOWLEDGE_UNCERTAIN_MEDIA in item.availableActions) {
                    Text(stringResource(R.string.anki_recovery_media_uncertain_help))
                    OutlinedButton(
                        onClick = {
                            confirmation = AnkiRecoveryConfirmation.UncertainMedia(item.id)
                        },
                        enabled = !state.busy,
                    ) { Text(stringResource(R.string.anki_recovery_abandon_uncertain_media)) }
                }
                if (AnkiRemediationActionKind.RESOLVE_AFTER_EXTERNAL_REVIEW in item.availableActions) {
                    ExternalReviewActions(item.id, item.type, state.busy) { id, outcome ->
                        confirmation = AnkiRecoveryConfirmation.ExternalReview(id, outcome)
                    }
                }
            }
        }
    }
    confirmation?.let { pending ->
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text(stringResource(R.string.anki_recovery_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        when (pending) {
                            is AnkiRecoveryConfirmation.UnattachedMedia ->
                                R.string.anki_recovery_confirm_unattached_detail
                            is AnkiRecoveryConfirmation.UncertainMedia ->
                                R.string.anki_recovery_confirm_uncertain_detail
                            is AnkiRecoveryConfirmation.ExternalReview ->
                                when (pending.outcome) {
                                    AnkiExternalReviewOutcome.COMMIT_CONFIRMED ->
                                        R.string.anki_recovery_confirm_exists_detail
                                    AnkiExternalReviewOutcome.NOT_COMMITTED_CONFIRMED ->
                                        R.string.anki_recovery_confirm_missing_detail
                                    AnkiExternalReviewOutcome.CURRENT_STATE_ACCEPTED_OR_CORRECTED ->
                                        R.string.anki_recovery_confirm_corrected_detail
                                    AnkiExternalReviewOutcome.CAPACITY_AVAILABLE ->
                                        R.string.anki_recovery_confirm_capacity_detail
                                }
                        },
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmation = null
                        when (pending) {
                            is AnkiRecoveryConfirmation.UnattachedMedia ->
                                onAcknowledgeMedia(pending.remediationId)
                            is AnkiRecoveryConfirmation.UncertainMedia ->
                                onAcknowledgeUncertainMedia(pending.remediationId)
                            is AnkiRecoveryConfirmation.ExternalReview ->
                                onResolveReview(pending.remediationId, pending.outcome)
                        }
                    },
                ) { Text(stringResource(R.string.anki_recovery_confirm_action)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmation = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

private sealed interface AnkiRecoveryConfirmation {
    val remediationId: Long

    data class UnattachedMedia(
        override val remediationId: Long,
    ) : AnkiRecoveryConfirmation

    data class UncertainMedia(
        override val remediationId: Long,
    ) : AnkiRecoveryConfirmation

    data class ExternalReview(
        override val remediationId: Long,
        val outcome: AnkiExternalReviewOutcome,
    ) : AnkiRecoveryConfirmation
}

@Composable
private fun FrequencyImportCard(
    state: SetupUiState,
    onIdChanged: (String) -> Unit,
    onNameChanged: (String) -> Unit,
    onFormatChanged: (FrequencySourceFormat) -> Unit,
    onReplaceChanged: (Boolean) -> Unit,
    onImport: () -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.frequency_import_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.frequency_import_help))
            state.frequencySources.forEach { source ->
                Text(
                    stringResource(
                        if (source.schemaOk && source.entryCount > 0) {
                            R.string.local_resource_inventory_ok
                        } else {
                            R.string.local_resource_inventory_invalid
                        },
                        source.sourceName,
                        source.sourceId,
                        source.entryCount,
                    ),
                    color =
                        if (source.schemaOk && source.entryCount > 0) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                )
            }
            if (state.frequencySources.isEmpty()) Text(stringResource(R.string.frequency_none_installed))
            OutlinedTextField(
                value = state.frequencySourceId,
                onValueChange = onIdChanged,
                label = { Text(stringResource(R.string.frequency_source_id)) },
                supportingText = { Text(stringResource(R.string.local_resource_id_help)) },
                isError = !state.frequencySourceIdValid,
                enabled = !state.busy,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.frequencySourceName,
                onValueChange = onNameChanged,
                label = { Text(stringResource(R.string.local_resource_display_name)) },
                isError = state.frequencySourceName.isBlank(),
                enabled = !state.busy,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            FormatButtons(
                values = FrequencySourceFormat.entries,
                selected = state.frequencyFormat,
                label = { frequencyFormatLabel(it) },
                enabled = !state.busy,
                onSelect = onFormatChanged,
            )
            ReplaceToggle(state.frequencyReplace, !state.busy, onReplaceChanged)
            OutlinedButton(
                onClick = onImport,
                enabled = !state.busy && state.frequencySourceIdValid && state.frequencySourceName.isNotBlank(),
            ) { Text(stringResource(R.string.frequency_choose_file)) }
        }
    }
}

@Composable
private fun PitchImportCard(
    state: SetupUiState,
    onNameChanged: (String) -> Unit,
    onFormatChanged: (PitchAccentSourceFormat) -> Unit,
    onReplaceChanged: (Boolean) -> Unit,
    onImport: () -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.pitch_import_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.pitch_import_help))
            state.pitchAccent?.let { pitch ->
                Text(
                    stringResource(
                        if (pitch.schemaOk && pitch.entryCount > 0) {
                            R.string.pitch_inventory_ok
                        } else {
                            R.string.pitch_inventory_invalid
                        },
                        pitch.sourceName,
                        pitch.entryCount,
                    ),
                    color =
                        if (pitch.schemaOk && pitch.entryCount > 0) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                )
            } ?: Text(stringResource(R.string.pitch_none_installed))
            OutlinedTextField(
                value = state.pitchSourceName,
                onValueChange = onNameChanged,
                label = { Text(stringResource(R.string.local_resource_display_name)) },
                isError = state.pitchSourceName.isBlank(),
                enabled = !state.busy,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            FormatButtons(
                values = PitchAccentSourceFormat.entries,
                selected = state.pitchFormat,
                label = { pitchFormatLabel(it) },
                enabled = !state.busy,
                onSelect = onFormatChanged,
            )
            ReplaceToggle(state.pitchReplace, !state.busy, onReplaceChanged)
            OutlinedButton(
                onClick = onImport,
                enabled = !state.busy && state.pitchSourceName.isNotBlank(),
            ) { Text(stringResource(R.string.pitch_choose_file)) }
        }
    }
}

@Composable
private fun AudioPackImportCard(
    state: SetupUiState,
    onIdChanged: (String) -> Unit,
    onReplaceChanged: (Boolean) -> Unit,
    onImport: () -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.audio_pack_import_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.audio_pack_import_help))
            state.audioPacks.forEach { pack ->
                Text(
                    stringResource(
                        if (pack.contentAvailable && pack.entryCount > 0) {
                            R.string.local_resource_inventory_ok
                        } else {
                            R.string.local_resource_inventory_invalid
                        },
                        pack.sourceName,
                        pack.packId,
                        pack.entryCount,
                    ),
                    color =
                        if (pack.contentAvailable && pack.entryCount > 0) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                )
            }
            if (state.audioPacks.isEmpty()) Text(stringResource(R.string.audio_pack_none_installed))
            OutlinedTextField(
                value = state.audioPackId,
                onValueChange = onIdChanged,
                label = { Text(stringResource(R.string.audio_pack_id)) },
                supportingText = { Text(stringResource(R.string.local_resource_id_help)) },
                isError = !state.audioPackIdValid,
                enabled = !state.busy,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            ReplaceToggle(state.audioPackReplace, !state.busy, onReplaceChanged)
            OutlinedButton(
                onClick = onImport,
                enabled = !state.busy && state.audioPackIdValid,
            ) { Text(stringResource(R.string.audio_pack_choose_zip)) }
        }
    }
}

@Composable
private fun KnownWordsImportCard(
    state: SetupUiState,
    onFormatChanged: (KnownWordsSourceFormat) -> Unit,
    onImport: () -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.known_words_import_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.known_words_import_help))
            Text(
                stringResource(
                    if (state.knownWords.schemaOk) {
                        R.string.known_words_inventory
                    } else {
                        R.string.known_words_inventory_invalid
                    },
                    state.knownWords.totalCount,
                    state.knownWords.userCount,
                    state.knownWords.ankiCount,
                    state.knownWords.minedCount,
                ),
                color =
                    if (state.knownWords.schemaOk) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.error
                    },
            )
            FormatButtons(
                values = KnownWordsSourceFormat.entries,
                selected = state.knownWordsFormat,
                label = { knownWordsFormatLabel(it) },
                enabled = !state.busy,
                onSelect = onFormatChanged,
            )
            OutlinedButton(onClick = onImport, enabled = !state.busy) {
                Text(stringResource(R.string.known_words_choose_file))
            }
        }
    }
}

@Composable
private fun BundledWordsetInventoryCard(state: SetupUiState) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.bundled_wordsets_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.bundled_wordsets_help))
            state.wordsets.forEach { wordset ->
                Text(stringResource(R.string.bundled_wordset_item, wordset.displayName, wordset.entryCount))
            }
            if (state.wordsets.isEmpty()) Text(stringResource(R.string.bundled_wordsets_unavailable), color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun DictionaryInventoryCard(state: SetupUiState) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.dictionary_inventory_title),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
            )
            if (state.dictionaries.isEmpty()) {
                Text(stringResource(R.string.dictionary_inventory_empty))
            } else {
                state.dictionaries.forEach { dictionary ->
                    Text(
                        stringResource(
                            if (dictionary.isUsable) {
                                R.string.dictionary_inventory_valid
                            } else {
                                R.string.dictionary_inventory_invalid
                            },
                            dictionary.sourceName,
                            dictionary.slotId,
                            dictionary.entryCount,
                        ),
                        color =
                            if (dictionary.isUsable) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                    )
                }
            }
        }
    }
}

@Composable
private fun LocalImportResultCard(result: LocalResourceImportResult) {
    val summary =
        when (result) {
            is ImportedFrequencySource ->
                stringResource(R.string.frequency_import_result, result.sourceName, result.entryCount, result.skippedMalformed)
            is ImportedPitchAccent ->
                stringResource(R.string.pitch_import_result, result.sourceName, result.entryCount, result.skippedMalformed)
            is ImportedAudioPack ->
                stringResource(R.string.audio_pack_import_result, result.sourceName, result.entryCount)
            is ImportedKnownWords ->
                stringResource(R.string.known_words_import_result, result.importedCount, result.newRowCount, result.totalEntries)
        }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.local_import_complete), style = MaterialTheme.typography.titleMedium)
            Text(summary)
        }
    }
}

@Composable
private fun ReplaceToggle(
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = onChange,
            ).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Text(stringResource(R.string.local_resource_replace), Modifier.padding(top = 12.dp))
    }
}

@Composable
private fun <T> FormatButtons(
    values: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    enabled: Boolean,
    onSelect: (T) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        values.forEach { value ->
            OutlinedButton(
                onClick = { onSelect(value) },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                val text = label(value)
                Text(if (value == selected) "✓ $text" else text)
            }
        }
    }
}

@Composable
private fun frequencyFormatLabel(format: FrequencySourceFormat): String =
    stringResource(
        when (format) {
            FrequencySourceFormat.YOMITAN_ZIP -> R.string.resource_format_yomitan_zip
            FrequencySourceFormat.CSV -> R.string.resource_format_csv
            FrequencySourceFormat.TSV -> R.string.resource_format_tsv
            FrequencySourceFormat.TEXT -> R.string.resource_format_text
        },
    )

@Composable
private fun pitchFormatLabel(format: PitchAccentSourceFormat): String =
    stringResource(
        when (format) {
            PitchAccentSourceFormat.YOMITAN_ZIP -> R.string.resource_format_yomitan_zip
            PitchAccentSourceFormat.CSV -> R.string.resource_format_csv
            PitchAccentSourceFormat.TSV -> R.string.resource_format_tsv
        },
    )

@Composable
private fun knownWordsFormatLabel(format: KnownWordsSourceFormat): String =
    stringResource(
        when (format) {
            KnownWordsSourceFormat.JSON -> R.string.resource_format_json
            KnownWordsSourceFormat.CSV -> R.string.resource_format_csv
            KnownWordsSourceFormat.TSV -> R.string.resource_format_tsv
            KnownWordsSourceFormat.TEXT -> R.string.resource_format_text
        },
    )

@Composable
private fun ExternalReviewActions(
    remediationId: Long,
    type: AnkiRemediationType,
    busy: Boolean,
    onResolve: (Long, AnkiExternalReviewOutcome) -> Unit,
) {
    when (type) {
        AnkiRemediationType.DECK_COMMIT_UNCERTAIN,
        AnkiRemediationType.NOTE_COMMIT_UNCERTAIN,
        -> {
            Text(stringResource(R.string.anki_recovery_review_in_ankidroid))
            OutlinedButton(
                onClick = { onResolve(remediationId, AnkiExternalReviewOutcome.COMMIT_CONFIRMED) },
                enabled = !busy,
            ) { Text(stringResource(R.string.anki_recovery_confirm_exists)) }
            OutlinedButton(
                onClick = { onResolve(remediationId, AnkiExternalReviewOutcome.NOT_COMMITTED_CONFIRMED) },
                enabled = !busy,
            ) { Text(stringResource(R.string.anki_recovery_confirm_missing)) }
        }
        AnkiRemediationType.NOTE_COMMITTED_FAILED,
        AnkiRemediationType.CARD_ROUTING_FAILED,
        -> OutlinedButton(
            onClick = {
                onResolve(
                    remediationId,
                    AnkiExternalReviewOutcome.CURRENT_STATE_ACCEPTED_OR_CORRECTED,
                )
            },
            enabled = !busy,
        ) { Text(stringResource(R.string.anki_recovery_confirm_corrected)) }
        AnkiRemediationType.CAPACITY_EXHAUSTED -> OutlinedButton(
            onClick = { onResolve(remediationId, AnkiExternalReviewOutcome.CAPACITY_AVAILABLE) },
            enabled = !busy,
        ) { Text(stringResource(R.string.anki_recovery_confirm_capacity)) }
        AnkiRemediationType.MEDIA_COMMIT_UNCERTAIN,
        AnkiRemediationType.MEDIA_STORED_UNATTACHED,
        AnkiRemediationType.STAGING_QUARANTINED,
        -> Unit
    }
}

@Composable
private fun ResourceCard(
    title: String,
    description: String,
    installed: Boolean,
    busy: Boolean,
    action: () -> Unit,
    actionLabel: String,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description)
            Text(stringResource(if (installed) R.string.resource_installed else R.string.resource_not_installed))
            Button(onClick = action, enabled = !busy) { Text(actionLabel) }
        }
    }
}

@Composable
private fun DictionaryLookupCard(
    state: SetupUiState,
    onTermChanged: (String) -> Unit,
    onSelectSlot: (String) -> Unit,
    onLookup: () -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.dictionary_test_title), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.dictionaries.filter { it.isUsable }.forEach { dictionary ->
                    OutlinedButton(
                        onClick = { onSelectSlot(dictionary.slotId) },
                        enabled = !state.busy,
                    ) {
                        Text(if (dictionary.slotId == state.lookupSlotId) "✓ ${dictionary.slotId}" else dictionary.slotId)
                    }
                }
            }
            OutlinedTextField(
                value = state.lookupTerm,
                onValueChange = onTermChanged,
                label = { Text(stringResource(R.string.dictionary_term)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = onLookup,
                enabled = state.lookupSlotId != null && state.lookupTerm.isNotBlank() && !state.busy,
            ) { Text(stringResource(R.string.dictionary_render_html)) }
            state.lookup?.let { result ->
                Text(stringResource(R.string.dictionary_lookup_label, result.slotId, result.term))
                DictionaryHtml(result.html, Modifier.fillMaxWidth().height(360.dp))
            }
        }
    }
}

@Composable
private fun DictionaryHtml(html: String, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.blockNetworkLoads = true
                settings.domStorageEnabled = false
                settings.databaseEnabled = false
                setNetworkAvailable(false)
            }
        },
        update = { webView ->
            // The engine renderer's HTML is loaded byte-for-byte; JavaScript, file/content access,
            // and all network subresources remain disabled for user-imported dictionaries.
            webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
        },
        onRelease = WebView::destroy,
    )
}

@Composable
private fun pythonStatus(value: PythonRuntimeReadiness): String =
    when (value) {
        PythonRuntimeReadiness.Pending -> stringResource(R.string.status_queued)
        PythonRuntimeReadiness.Starting -> stringResource(R.string.status_starting)
        is PythonRuntimeReadiness.Ready -> stringResource(R.string.status_ready)
        PythonRuntimeReadiness.Failed -> stringResource(R.string.status_failed_restart)
    }

@Composable
private fun ankiStatus(value: AnkiProviderReadiness): String =
    when (value) {
        AnkiProviderReadiness.NotChecked -> stringResource(R.string.status_not_checked)
        AnkiProviderReadiness.NotInstalled -> stringResource(R.string.status_install_ankidroid)
        AnkiProviderReadiness.Uninitialized -> stringResource(R.string.status_initialize_ankidroid)
        is AnkiProviderReadiness.Incompatible -> stringResource(R.string.status_incompatible_api)
        AnkiProviderReadiness.PermissionDenied -> stringResource(R.string.status_database_permission)
        AnkiProviderReadiness.RecoveryBlocked -> stringResource(R.string.status_recovery_attention)
        is AnkiProviderReadiness.Ready -> stringResource(R.string.status_ready_api, value.apiSpecVersion)
    }

@Composable
private fun resourceStartupStatus(value: ResourceStartupReadiness): String =
    stringResource(
        when (value) {
            ResourceStartupReadiness.PENDING -> R.string.status_pending
            ResourceStartupReadiness.RECOVERING -> R.string.status_recovering
            ResourceStartupReadiness.READY -> R.string.status_ready
            ResourceStartupReadiness.FAILED -> R.string.status_failed
        },
    )

@StringRes
private fun resourcePhaseLabel(value: ResourceOperationPhase): Int =
    when (value) {
        ResourceOperationPhase.PREPARING -> R.string.resource_phase_preparing
        ResourceOperationPhase.DOWNLOADING -> R.string.resource_phase_downloading
        ResourceOperationPhase.VERIFYING -> R.string.resource_phase_verifying
        ResourceOperationPhase.INSTALLING -> R.string.resource_phase_installing
        ResourceOperationPhase.IMPORTING -> R.string.resource_phase_importing
        ResourceOperationPhase.REFRESHING -> R.string.resource_phase_refreshing
        ResourceOperationPhase.CANCELLING -> R.string.resource_phase_cancelling
    }
