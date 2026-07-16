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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ankiminer.android.R
import com.ankiminer.android.anki.provider.AnkiProviderReadiness
import com.ankiminer.android.data.resources.ResourceOperationPhase
import com.ankiminer.android.data.resources.ResourceStartupReadiness
import com.ankiminer.android.engine.PythonRuntimeReadiness
import com.ankiminer.android.mining.NotificationPermissionReadiness
import com.ankiminer.android.vm.SetupViewModel

@Composable
internal fun SetupRoute(
    viewModel: SetupViewModel,
    onRequestPermissions: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val dictionaryPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { viewModel.importCustomDictionary(it.toString()) }
        }
    SetupScreen(
        state = state,
        onRefresh = viewModel::refresh,
        onRequestPermissions = onRequestPermissions,
        onInstallUniDic = viewModel::installUniDic,
        onInstallRecommended = viewModel::installRecommendedDictionary,
        onImportCustom = { dictionaryPicker.launch(arrayOf("application/zip", "application/octet-stream")) },
        onCustomSlotChanged = viewModel::setCustomSlotId,
        onCustomReplaceChanged = viewModel::setCustomReplace,
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
    onInstallUniDic: () -> Unit,
    onInstallRecommended: () -> Unit,
    onImportCustom: () -> Unit,
    onCustomSlotChanged: (String) -> Unit,
    onCustomReplaceChanged: (Boolean) -> Unit,
    onCancel: () -> Unit,
    onDismissFailure: () -> Unit,
    onLookupTermChanged: (String) -> Unit,
    onLookupSlot: (String) -> Unit,
    onLookup: () -> Unit,
    onFinish: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.setup_intro))

        StatusCard(state, onRefresh, onRequestPermissions)

        state.operation?.let { operation ->
            OutlinedCard(Modifier.fillMaxWidth()) {
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
            OutlinedCard(Modifier.fillMaxWidth()) {
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
            busy = state.operation != null,
            action = onInstallUniDic,
            actionLabel = stringResource(if (state.uniDicInstalled) R.string.unidic_repair else R.string.unidic_install),
        )
        ResourceCard(
            title = stringResource(R.string.jitendex_resource_title),
            description = stringResource(R.string.jitendex_resource_description),
            installed = state.recommendedDictionaryInstalled,
            busy = state.operation != null,
            action = onInstallRecommended,
            actionLabel = stringResource(
                if (state.recommendedDictionaryInstalled) R.string.dictionary_replace else R.string.dictionary_install,
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
                    enabled = state.operation == null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Checkbox(
                        checked = state.customReplace,
                        onCheckedChange = onCustomReplaceChanged,
                        enabled = state.operation == null,
                    )
                    Text(stringResource(R.string.custom_dictionary_replace), Modifier.padding(top = 12.dp))
                }
                OutlinedButton(
                    onClick = onImportCustom,
                    enabled = state.operation == null && state.customSlotValid,
                ) {
                    Text(stringResource(R.string.custom_dictionary_choose))
                }
            }
        }

        if (state.dictionaries.isNotEmpty()) {
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
            if (state.completionError) Text(stringResource(R.string.finish_setup_save_failed), color = MaterialTheme.colorScheme.error)
        } else {
            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.open_video_mining))
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
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.readiness_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.readiness_python, pythonStatus(state.python)))
            Text(stringResource(R.string.readiness_resource_recovery, resourceStartupStatus(state.resourceStartup)))
            Text(stringResource(R.string.readiness_ankidroid, ankiStatus(state.anki)))
            Text(
                stringResource(
                    R.string.readiness_notifications,
                    stringResource(
                        if (state.notifications == NotificationPermissionReadiness.READY) {
                            R.string.status_ready
                        } else {
                            R.string.status_permission_needed
                        },
                    ),
                ),
            )
            Text(
                stringResource(
                    R.string.readiness_unidic,
                    stringResource(
                        if (state.uniDicInstalled) R.string.status_unidic_found else R.string.status_unidic_missing,
                    ),
                ),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRequestPermissions) { Text(stringResource(R.string.allow_required_access)) }
                TextButton(onClick = onRefresh, enabled = state.operation == null) {
                    Text(stringResource(R.string.check_again))
                }
            }
        }
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
                state.dictionaries.forEach { dictionary ->
                    OutlinedButton(
                        onClick = { onSelectSlot(dictionary.slotId) },
                        enabled = state.operation == null,
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
                enabled = state.lookupSlotId != null && state.lookupTerm.isNotBlank() && state.operation == null,
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
