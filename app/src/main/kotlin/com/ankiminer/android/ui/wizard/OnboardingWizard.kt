package com.ankiminer.android.ui.wizard

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ankiminer.android.R
import com.ankiminer.android.ui.settings.AnkiOperationCard
import com.ankiminer.android.ui.settings.AnkiTargetCard
import com.ankiminer.android.ui.settings.CatalogDictionaryCards
import com.ankiminer.android.ui.settings.CatalogReplaceDialog
import com.ankiminer.android.ui.settings.MessageSnackbarEffect
import com.ankiminer.android.ui.settings.ResourceCard
import com.ankiminer.android.ui.settings.ResourceOperationCard
import com.ankiminer.android.ui.settings.SystemStatusCard
import com.ankiminer.android.vm.AnkiDroidSetupAction
import com.ankiminer.android.vm.SetupUiState
import com.ankiminer.android.vm.SetupViewModel

/**
 * The wizard shows on first launch until completed or skipped, and on demand from
 * Settings → UI. Every step is skippable — closing the wizard in any way marks it seen;
 * everything it offers stays available in Settings.
 */
internal fun wizardVisible(wizardSeen: Boolean?, rerunRequested: Boolean): Boolean =
    rerunRequested || wizardSeen == false

internal enum class WizardStep {
    WELCOME,
    TOKENIZER,
    DICTIONARY,
    ANKIDROID,
    ANKIDROID_NOTE_TYPE,
    DONE,
}

internal fun nextWizardStep(step: WizardStep): WizardStep =
    WizardStep.entries.getOrElse(step.ordinal + 1) { step }

internal fun previousWizardStep(step: WizardStep): WizardStep =
    WizardStep.entries.getOrElse(step.ordinal - 1) { step }

@Composable
internal fun OnboardingWizard(
    state: SetupUiState,
    viewModel: SetupViewModel,
    onRequestPermissions: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onInstallAnkiDroid: () -> Unit,
    onOpenAnkiDroid: () -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var step by rememberSaveable { mutableStateOf(WizardStep.WELCOME) }
    val scrollState = rememberScrollState()
    BackHandler(onBack = onFinished)
    LaunchedEffect(step) { scrollState.scrollTo(0) }
    CatalogReplaceDialog(
        state = state,
        onConfirm = viewModel::confirmCatalogDictionaryReplace,
        onDismiss = viewModel::dismissCatalogDictionaryReplace,
    )
    val snackbarHostState = remember { SnackbarHostState() }
    MessageSnackbarEffect(state.failure?.message, snackbarHostState, viewModel::dismissFailure)
    MessageSnackbarEffect(state.ankiFailure?.message, snackbarHostState, viewModel::dismissAnkiFailure)
    Surface(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    stringResource(
                        when (step) {
                            WizardStep.WELCOME -> R.string.wizard_welcome_title
                            WizardStep.TOKENIZER -> R.string.wizard_tokenizer_title
                            WizardStep.DICTIONARY -> R.string.wizard_dictionary_title
                            WizardStep.ANKIDROID -> R.string.wizard_ankidroid_title
                            WizardStep.ANKIDROID_NOTE_TYPE -> R.string.wizard_note_type_title
                            WizardStep.DONE -> R.string.wizard_done_title
                        },
                    ),
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    stringResource(
                        R.string.wizard_step_position,
                        step.ordinal + 1,
                        WizardStep.entries.size,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )

                when (step) {
                    WizardStep.WELCOME -> {
                        Text(stringResource(R.string.wizard_welcome_body))
                        Text(stringResource(R.string.wizard_welcome_settings_note))
                    }
                    WizardStep.TOKENIZER -> {
                        Text(stringResource(R.string.wizard_tokenizer_body))
                        ResourceCard(
                            title = stringResource(R.string.unidic_resource_title),
                            description = stringResource(R.string.unidic_resource_description),
                            installed = state.uniDicInstalled,
                            busy = state.busy,
                            action = viewModel::installUniDic,
                            actionLabel = stringResource(
                                if (state.uniDicInstalled) R.string.unidic_repair else R.string.unidic_install,
                            ),
                        )
                    }
                    WizardStep.DICTIONARY -> {
                        Text(stringResource(R.string.wizard_dictionary_body))
                        CatalogDictionaryCards(state, viewModel::installCatalogDictionary)
                    }
                    WizardStep.ANKIDROID -> {
                        Text(stringResource(R.string.wizard_ankidroid_body))
                        AnkiDroidActionButtons(
                            state = state,
                            onRequestPermissions = onRequestPermissions,
                            onInstallAnkiDroid = onInstallAnkiDroid,
                            onOpenAnkiDroid = onOpenAnkiDroid,
                        )
                        state.ankiOperation?.let { AnkiOperationCard() }
                    }
                    WizardStep.ANKIDROID_NOTE_TYPE -> {
                        Text(stringResource(R.string.wizard_note_type_body))
                        AnkiTargetCard(
                            state,
                            viewModel::selectNoteType,
                            viewModel::setFieldMapping,
                            viewModel::verifyNoteType,
                        )
                        state.ankiOperation?.let { AnkiOperationCard() }
                    }
                    WizardStep.DONE -> {
                        Text(stringResource(R.string.wizard_done_body))
                        SystemStatusCard(
                            state = state,
                            onRefresh = viewModel::refresh,
                            onRequestPermissions = onRequestPermissions,
                            onOpenAppSettings = onOpenAppSettings,
                            onInstallAnkiDroid = onInstallAnkiDroid,
                            onOpenAnkiDroid = onOpenAnkiDroid,
                        )
                    }
                }

                if (step != WizardStep.WELCOME && step != WizardStep.DONE) {
                    state.operation?.let { operation ->
                        ResourceOperationCard(operation, viewModel::cancelOperation)
                    }
                }
                NavigationButtons(step, onStep = { step = it }, onFinished = onFinished)
            }
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun AnkiDroidActionButtons(
    state: SetupUiState,
    onRequestPermissions: () -> Unit,
    onInstallAnkiDroid: () -> Unit,
    onOpenAnkiDroid: () -> Unit,
) {
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
        null -> Text(stringResource(R.string.wizard_ankidroid_ready))
    }
}

@Composable
private fun NavigationButtons(
    step: WizardStep,
    onStep: (WizardStep) -> Unit,
    onFinished: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (step) {
                WizardStep.WELCOME -> {
                    Button(
                        onClick = { onStep(nextWizardStep(step)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.wizard_set_up_now)) }
                    OutlinedButton(
                        onClick = onFinished,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.wizard_skip_for_now)) }
                }
                WizardStep.DONE -> {
                    Button(
                        onClick = onFinished,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.wizard_finish)) }
                    OutlinedButton(
                        onClick = { onStep(previousWizardStep(step)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.wizard_back)) }
                }
                else -> {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { onStep(previousWizardStep(step)) },
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.wizard_back)) }
                        Button(
                            onClick = { onStep(nextWizardStep(step)) },
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.wizard_next)) }
                    }
                    TextButton(onClick = onFinished) {
                        Text(stringResource(R.string.wizard_skip_setup))
                    }
                }
            }
    }
}
