package com.ankiminer.android.ui.wizard

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ankiminer.android.R
import com.ankiminer.android.ui.navigation.AppChrome
import com.ankiminer.android.ui.settings.AnkiOperationCard
import com.ankiminer.android.ui.settings.AnkiDeckCard
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
import com.ankiminer.android.vm.WizardCompletionStatus

/**
 * The wizard shows on first launch until completed or skipped, and on demand from
 * Settings → UI. Every step is skippable. Normal close paths persist completion; a failed write
 * keeps retry visible and permits only a session-level escape. Everything remains in Settings.
 */
internal fun wizardVisible(
    wizardSeen: Boolean?,
    rerunRequested: Boolean,
    sessionDismissed: Boolean,
    completion: WizardCompletionStatus = WizardCompletionStatus.IDLE,
): Boolean =
    rerunRequested ||
        (
            !sessionDismissed &&
                wizardSeen == false &&
                completion != WizardCompletionStatus.PERSISTED &&
                completion != WizardCompletionStatus.DISMISSED_FOR_SESSION
        )

internal enum class WizardStep {
    WELCOME,
    TOKENIZER,
    DICTIONARY,
    ANKIDROID,
    ANKIDROID_DECK,
    ANKIDROID_NOTE_TYPE,
    DONE,
}

internal fun nextWizardStep(step: WizardStep): WizardStep =
    WizardStep.entries.getOrElse(step.ordinal + 1) { step }

internal fun previousWizardStep(step: WizardStep): WizardStep =
    WizardStep.entries.getOrElse(step.ordinal - 1) { step }

internal data class OnboardingWizardCallbacks(
    val onStep: (WizardStep) -> Unit = {},
    val onFinished: () -> Unit = {},
    val onRequestPermissions: () -> Unit = {},
    val onOpenAppSettings: () -> Unit = {},
    val onInstallAnkiDroid: () -> Unit = {},
    val onOpenAnkiDroid: () -> Unit = {},
    val onConfirmCatalogDictionaryReplace: () -> Unit = {},
    val onDismissCatalogDictionaryReplace: () -> Unit = {},
    val onDismissFailure: () -> Unit = {},
    val onDismissAnkiFailure: () -> Unit = {},
    val onInstallUniDic: () -> Unit = {},
    val onInstallCatalogDictionary: (String) -> Unit = {},
    val onSelectDeck: (String) -> Unit = {},
    val onRetryDeckSelection: () -> Unit = {},
    val onSelectNoteType: (String) -> Unit = {},
    val onSetFieldMapping: (String, String) -> Unit = { _, _ -> },
    val onVerifyNoteType: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onCancelOperation: () -> Unit = {},
    val onRetryWizardCompletion: () -> Unit = {},
    val onDismissWizardForSession: () -> Unit = {},
)

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
    BackHandler(
        enabled = state.wizardCompletion != WizardCompletionStatus.SAVING,
        onBack = onFinished,
    )
    OnboardingWizardContent(
        state = state,
        step = step,
        callbacks =
            OnboardingWizardCallbacks(
                onStep = { step = it },
                onFinished = onFinished,
                onRequestPermissions = onRequestPermissions,
                onOpenAppSettings = onOpenAppSettings,
                onInstallAnkiDroid = onInstallAnkiDroid,
                onOpenAnkiDroid = onOpenAnkiDroid,
                onConfirmCatalogDictionaryReplace =
                    viewModel::confirmCatalogDictionaryReplace,
                onDismissCatalogDictionaryReplace =
                    viewModel::dismissCatalogDictionaryReplace,
                onDismissFailure = viewModel::dismissFailure,
                onDismissAnkiFailure = viewModel::dismissAnkiFailure,
                onInstallUniDic = viewModel::installUniDic,
                onInstallCatalogDictionary = viewModel::installCatalogDictionary,
                onSelectDeck = viewModel::selectDeck,
                onRetryDeckSelection = viewModel::retryDeckSelection,
                onSelectNoteType = viewModel::selectNoteType,
                onSetFieldMapping = viewModel::setFieldMapping,
                onVerifyNoteType = viewModel::verifyNoteType,
                onRefresh = viewModel::refresh,
                onCancelOperation = viewModel::cancelOperation,
                onRetryWizardCompletion = viewModel::retryWizardCompletion,
                onDismissWizardForSession = viewModel::dismissWizardForSession,
            ),
        modifier = modifier,
        scrollState = scrollState,
    )
}

@Composable
internal fun OnboardingWizardContent(
    state: SetupUiState,
    step: WizardStep,
    callbacks: OnboardingWizardCallbacks,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
) {
    LaunchedEffect(step) { scrollState.scrollTo(0) }
    CatalogReplaceDialog(
        state = state,
        onConfirm = callbacks.onConfirmCatalogDictionaryReplace,
        onDismiss = callbacks.onDismissCatalogDictionaryReplace,
    )
    val snackbarHostState = remember { SnackbarHostState() }
    MessageSnackbarEffect(
        state.failure?.message,
        snackbarHostState,
        callbacks.onDismissFailure,
    )
    MessageSnackbarEffect(
        state.ankiFailure?.message,
        snackbarHostState,
        callbacks.onDismissAnkiFailure,
    )
    MessageSnackbarEffect(
        state.ankiRecoveryFailure?.message,
        snackbarHostState,
        callbacks.onDismissAnkiFailure,
    )
    val title =
        stringResource(
            when (step) {
                WizardStep.WELCOME -> R.string.wizard_welcome_title
                WizardStep.TOKENIZER -> R.string.wizard_tokenizer_title
                WizardStep.DICTIONARY -> R.string.wizard_dictionary_title
                WizardStep.ANKIDROID -> R.string.wizard_ankidroid_title
                WizardStep.ANKIDROID_DECK -> R.string.wizard_deck_title
                WizardStep.ANKIDROID_NOTE_TYPE -> R.string.wizard_note_type_title
                WizardStep.DONE -> R.string.wizard_done_title
            },
        )
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            AppChrome(
                title = title,
                onNavigateBack =
                    if (
                        step == WizardStep.WELCOME ||
                        state.wizardCompletion == WizardCompletionStatus.SAVING
                    ) {
                        null
                    } else {
                        { callbacks.onStep(previousWizardStep(step)) }
                    },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { scaffoldPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(scaffoldPadding)
                .consumeWindowInsets(scaffoldPadding)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
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
                        action = callbacks.onInstallUniDic,
                        actionLabel = stringResource(
                            if (state.uniDicInstalled) R.string.unidic_repair else R.string.unidic_install,
                        ),
                    )
                }
                WizardStep.DICTIONARY -> {
                    Text(stringResource(R.string.wizard_dictionary_body))
                    CatalogDictionaryCards(
                        state,
                        callbacks.onInstallCatalogDictionary,
                    )
                }
                WizardStep.ANKIDROID -> {
                    Text(stringResource(R.string.wizard_ankidroid_body))
                    AnkiDroidActionButtons(
                        state = state,
                        onRequestPermissions = callbacks.onRequestPermissions,
                        onInstallAnkiDroid = callbacks.onInstallAnkiDroid,
                        onOpenAnkiDroid = callbacks.onOpenAnkiDroid,
                    )
                    state.ankiOperation?.let { AnkiOperationCard() }
                }
                WizardStep.ANKIDROID_DECK -> {
                    Text(stringResource(R.string.wizard_deck_body))
                    AnkiDeckCard(
                        state,
                        callbacks.onSelectDeck,
                        callbacks.onRetryDeckSelection,
                    )
                    state.ankiOperation?.let { AnkiOperationCard() }
                }
                WizardStep.ANKIDROID_NOTE_TYPE -> {
                    Text(stringResource(R.string.wizard_note_type_body))
                    AnkiTargetCard(
                        state,
                        callbacks.onSelectNoteType,
                        callbacks.onSetFieldMapping,
                        callbacks.onVerifyNoteType,
                    )
                    state.ankiOperation?.let { AnkiOperationCard() }
                }
                WizardStep.DONE -> {
                    Text(stringResource(R.string.wizard_done_body))
                    SystemStatusCard(
                        state = state,
                        onRefresh = callbacks.onRefresh,
                        onRequestPermissions = callbacks.onRequestPermissions,
                        onOpenAppSettings = callbacks.onOpenAppSettings,
                        onInstallAnkiDroid = callbacks.onInstallAnkiDroid,
                        onOpenAnkiDroid = callbacks.onOpenAnkiDroid,
                    )
                }
            }

            if (step != WizardStep.WELCOME && step != WizardStep.DONE) {
                state.operation?.let { operation ->
                    ResourceOperationCard(operation, callbacks.onCancelOperation)
                }
            }
            WizardCompletionCard(
                status = state.wizardCompletion,
                onRetry = callbacks.onRetryWizardCompletion,
                onDismissForSession = callbacks.onDismissWizardForSession,
            )
            NavigationButtons(
                step,
                saving = state.wizardCompletion == WizardCompletionStatus.SAVING,
                onStep = callbacks.onStep,
                onFinished = callbacks.onFinished,
            )
        }
    }
}

@Composable
private fun WizardCompletionCard(
    status: WizardCompletionStatus,
    onRetry: () -> Unit,
    onDismissForSession: () -> Unit,
) {
    when (status) {
        WizardCompletionStatus.SAVING -> {
            Text(stringResource(R.string.wizard_completion_saving))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        WizardCompletionStatus.FAILED -> {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(R.string.wizard_completion_failed),
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.wizard_completion_retry))
                    }
                    OutlinedButton(
                        onClick = onDismissForSession,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.wizard_completion_continue_session))
                    }
                    Text(
                        stringResource(R.string.wizard_completion_session_help),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        WizardCompletionStatus.IDLE,
        WizardCompletionStatus.PERSISTED,
        WizardCompletionStatus.DISMISSED_FOR_SESSION,
        -> Unit
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
    saving: Boolean,
    onStep: (WizardStep) -> Unit,
    onFinished: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (step) {
            WizardStep.WELCOME -> {
                Button(
                    onClick = { onStep(nextWizardStep(step)) },
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.wizard_set_up_now)) }
                OutlinedButton(
                    onClick = onFinished,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.wizard_skip_for_now)) }
            }
            WizardStep.DONE -> {
                Button(
                    onClick = onFinished,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.wizard_finish)) }
                OutlinedButton(
                    onClick = { onStep(previousWizardStep(step)) },
                    enabled = !saving,
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
                        enabled = !saving,
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.wizard_back)) }
                    Button(
                        onClick = { onStep(nextWizardStep(step)) },
                        enabled = !saving,
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.wizard_next)) }
                }
                TextButton(onClick = onFinished, enabled = !saving) {
                    Text(stringResource(R.string.wizard_skip_setup))
                }
            }
        }
    }
}
