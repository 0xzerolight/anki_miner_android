package com.ankiminer.android.ui.wizard

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ankiminer.android.R
import com.ankiminer.android.data.anki.AnkiSetupFailureOrigin
import com.ankiminer.android.data.resources.ResourceFailureAction
import com.ankiminer.android.data.resources.ResourceFailureOrigin
import com.ankiminer.android.ui.navigation.AppChrome
import com.ankiminer.android.ui.settings.AnkiDeckCard
import com.ankiminer.android.ui.settings.AnkiOperationCard
import com.ankiminer.android.ui.settings.CatalogDictionaryCards
import com.ankiminer.android.ui.settings.ResourceReplaceDialog
import com.ankiminer.android.ui.settings.InlineFailureContainer
import com.ankiminer.android.ui.settings.ResourceCard
import com.ankiminer.android.ui.settings.ResourceOperationCard
import com.ankiminer.android.ui.settings.SetupTaskId
import com.ankiminer.android.ui.settings.SystemStatusCard
import com.ankiminer.android.ui.settings.WizardAnkiTargetCard
import com.ankiminer.android.ui.theme.AdaptiveActionGroup
import com.ankiminer.android.ui.theme.AnkiMinerTokens
import com.ankiminer.android.ui.theme.PrimaryActionButton
import com.ankiminer.android.ui.theme.SecondaryActionButton
import com.ankiminer.android.vm.AnkiDroidSetupAction
import com.ankiminer.android.vm.SetupUiState
import com.ankiminer.android.vm.SetupViewModel
import com.ankiminer.android.vm.WizardCompletionStatus

internal const val WIZARD_STEP_HEADING_TEST_TAG = "wizard_step_heading"

internal fun wizardVisible(
    wizardSeen: Boolean?,
    rerunRequested: Boolean,
    sessionDismissed: Boolean,
    completion: WizardCompletionStatus = WizardCompletionStatus.IDLE,
): Boolean =
    rerunRequested ||
        completion == WizardCompletionStatus.SAVING ||
        completion == WizardCompletionStatus.FAILED ||
        (
            !sessionDismissed &&
                wizardSeen == false &&
                completion != WizardCompletionStatus.PERSISTED &&
                completion != WizardCompletionStatus.DISMISSED_FOR_SESSION
        )

internal enum class WizardStep {
    WELCOME,
    ANKIDROID,
    ANKIDROID_DECK,
    ANKIDROID_NOTE_TYPE,
    TOKENIZER,
    DICTIONARY,
    DONE,
}

internal enum class WizardStepRequirement {
    REQUIRED,
    OPTIONAL,
}

internal fun wizardStepRequirement(step: WizardStep): WizardStepRequirement? =
    when (step) {
        WizardStep.ANKIDROID,
        WizardStep.ANKIDROID_NOTE_TYPE,
        WizardStep.TOKENIZER,
        // Mining cannot start without a usable dictionary: the engine raises SetupError before
        // any work happens. Labelling this optional walked testers to a "ready" wizard whose
        // first run then failed seconds in.
        WizardStep.DICTIONARY,
        -> WizardStepRequirement.REQUIRED
        WizardStep.ANKIDROID_DECK,
        -> WizardStepRequirement.OPTIONAL
        WizardStep.WELCOME,
        WizardStep.DONE,
        -> null
    }

internal fun nextWizardStep(step: WizardStep): WizardStep =
    WizardStep.entries.getOrElse(step.ordinal + 1) { step }

internal fun previousWizardStep(step: WizardStep): WizardStep =
    WizardStep.entries.getOrElse(step.ordinal - 1) { step }

internal sealed interface WizardBackAction {
    data class Previous(
        val step: WizardStep,
    ) : WizardBackAction

    data object ConfirmSkip : WizardBackAction
}

internal fun wizardBackAction(step: WizardStep): WizardBackAction =
    if (step == WizardStep.WELCOME) {
        WizardBackAction.ConfirmSkip
    } else {
        WizardBackAction.Previous(previousWizardStep(step))
    }

internal enum class WizardFinalState {
    READY,
    INCOMPLETE,
}

internal fun wizardFinalState(isMiningReady: Boolean): WizardFinalState =
    if (isMiningReady) WizardFinalState.READY else WizardFinalState.INCOMPLETE

internal data class OnboardingWizardCallbacks(
    val onStep: (WizardStep) -> Unit = {},
    val onFinished: () -> Unit = {},
    val onRequestPermissions: () -> Unit = {},
    val onOpenAppSettings: () -> Unit = {},
    val onInstallAnkiDroid: () -> Unit = {},
    val onOpenAnkiDroid: () -> Unit = {},
    val onConfirmResourceReplace: () -> Unit = {},
    val onDismissResourceReplace: () -> Unit = {},
    val onDismissFailure: () -> Unit = {},
    val onDismissAnkiFailure: () -> Unit = {},
    val onInstallUniDic: () -> Unit = {},
    val onInstallCatalogDictionary: (String) -> Unit = {},
    val onSelectDeck: (String) -> Unit = {},
    val onRetryDeckSelection: () -> Unit = {},
    val onSelectNoteType: (String) -> Unit = {},
    val onSetFieldMapping: (String, String) -> Unit = { _, _ -> },
    val onCustomizeFields: () -> Unit = {},
    val onResolveRecovery: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onCancelOperation: () -> Unit = {},
    val onRetryResourceFailure: () -> Unit = {},
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
    onCustomizeFields: () -> Unit = {},
    onResolveRecovery: () -> Unit = onCustomizeFields,
) {
    var step by rememberSaveable { mutableStateOf(WizardStep.WELCOME) }
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
                onConfirmResourceReplace = viewModel::confirmPendingReplace,
                onDismissResourceReplace = viewModel::dismissPendingReplace,
                onDismissFailure = viewModel::dismissFailure,
                onDismissAnkiFailure = viewModel::dismissAnkiFailure,
                onInstallUniDic = viewModel::installUniDic,
                onInstallCatalogDictionary = viewModel::installCatalogDictionary,
                onSelectDeck = viewModel::selectDeck,
                onRetryDeckSelection = viewModel::retryDeckSelection,
                onSelectNoteType = viewModel::selectNoteType,
                onSetFieldMapping = viewModel::setFieldMapping,
                onCustomizeFields = onCustomizeFields,
                onResolveRecovery = onResolveRecovery,
                onRefresh = viewModel::refresh,
                onCancelOperation = viewModel::cancelOperation,
                onRetryResourceFailure = viewModel::retryResourceFailure,
                onRetryWizardCompletion = viewModel::retryWizardCompletion,
                onDismissWizardForSession = viewModel::dismissWizardForSession,
            ),
        modifier = modifier,
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
    var showSkipConfirmation by rememberSaveable { mutableStateOf(false) }
    val requestBack = {
        when (val action = wizardBackAction(step)) {
            WizardBackAction.ConfirmSkip -> showSkipConfirmation = true
            is WizardBackAction.Previous -> callbacks.onStep(action.step)
        }
    }
    BackHandler(
        onBack = {
            if (state.wizardCompletion != WizardCompletionStatus.SAVING) requestBack()
        },
    )
    val headingFocusRequester = remember { FocusRequester() }
    LaunchedEffect(step) {
        scrollState.scrollTo(0)
        headingFocusRequester.requestFocus()
    }
    ResourceReplaceDialog(
        pending = state.pendingReplace,
        busy = state.busy,
        onConfirm = callbacks.onConfirmResourceReplace,
        onDismiss = callbacks.onDismissResourceReplace,
    )
    if (showSkipConfirmation) {
        AlertDialog(
            onDismissRequest = { showSkipConfirmation = false },
            title = { Text(stringResource(R.string.b3_wizard_skip_title)) },
            text = { Text(stringResource(R.string.b3_wizard_skip_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSkipConfirmation = false
                        callbacks.onFinished()
                    },
                ) { Text(stringResource(R.string.b3_wizard_confirm_skip)) }
            },
            dismissButton = {
                TextButton(onClick = { showSkipConfirmation = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val title = wizardTitle(step, state.isMiningReady)
    val animatedProgress by
        animateFloatAsState(
            targetValue = (step.ordinal + 1).toFloat() / WizardStep.entries.size.toFloat(),
            animationSpec = tween(durationMillis = 150),
            label = "wizard progress",
        )
    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            Column {
                AppChrome(
                    title = title,
                    titleModifier =
                        Modifier
                            .focusRequester(headingFocusRequester)
                            .focusable()
                            .testTag(WIZARD_STEP_HEADING_TEST_TAG),
                    onNavigateBack =
                        if (state.wizardCompletion == WizardCompletionStatus.SAVING) {
                            null
                        } else {
                            requestBack
                        },
                )
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        bottomBar = {
            Surface(
                modifier = Modifier.navigationBarsPadding().imePadding(),
                tonalElevation = 3.dp,
            ) {
                WizardNavigation(
                    step = step,
                    saving = state.wizardCompletion == WizardCompletionStatus.SAVING,
                    onStep = callbacks.onStep,
                    onRequestSkip = callbacks.onFinished,
                    onFinished = callbacks.onFinished,
                    modifier = Modifier.padding(AnkiMinerTokens.Space.content),
                )
            }
        },
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.navigationBarsPadding(),
            )
        },
    ) { scaffoldPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(scaffoldPadding)
                .consumeWindowInsets(scaffoldPadding)
                .verticalScroll(scrollState)
                .imePadding()
                .padding(AnkiMinerTokens.Space.content),
            verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.content),
        ) {
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    fadeIn(tween(durationMillis = 150)) togetherWith
                        fadeOut(tween(durationMillis = 90))
                },
                contentKey = { targetStep -> targetStep },
                label = "wizard step",
            ) { targetStep ->
                val targetTitle = wizardTitle(targetStep, state.isMiningReady)
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .semantics { paneTitle = targetTitle },
                    verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.content),
                ) {
                    Text(
                        stringResource(
                            R.string.wizard_step_position,
                            targetStep.ordinal + 1,
                            WizardStep.entries.size,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    wizardStepRequirement(targetStep)?.let { requirement ->
                        Text(
                            stringResource(
                                if (requirement == WizardStepRequirement.REQUIRED) {
                                    R.string.b3_wizard_required
                                } else {
                                    R.string.b3_wizard_optional
                                },
                            ),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    WizardStepBody(state, targetStep, callbacks)
                    if (targetStep != WizardStep.WELCOME && targetStep != WizardStep.DONE) {
                        state.operation?.let { operation ->
                            ResourceOperationCard(operation, callbacks.onCancelOperation)
                        }
                    }
                }
            }
            WizardCompletionCard(
                status = state.wizardCompletion,
                onRetry = callbacks.onRetryWizardCompletion,
                onDismissForSession = callbacks.onDismissWizardForSession,
            )
        }
    }
}

@Composable
private fun WizardStepBody(
    state: SetupUiState,
    step: WizardStep,
    callbacks: OnboardingWizardCallbacks,
) {
    when (step) {
        WizardStep.WELCOME -> {
            SystemStatusCard(
                state = state,
                onRefresh = callbacks.onRefresh,
                onRequestPermissions = callbacks.onRequestPermissions,
                onOpenAppSettings = callbacks.onOpenAppSettings,
                onInstallAnkiDroid = callbacks.onInstallAnkiDroid,
                onOpenAnkiDroid = callbacks.onOpenAnkiDroid,
                onInstallUniDic = callbacks.onInstallUniDic,
                onChooseNoteType = {
                    callbacks.onStep(WizardStep.ANKIDROID_NOTE_TYPE)
                },
                onResolveRecovery = callbacks.onResolveRecovery,
                onImportDictionary = {
                    callbacks.onStep(WizardStep.DICTIONARY)
                },
            )
            WizardResourceFailure(
                state,
                ResourceFailureOrigin.SETUP,
                callbacks.onRefresh,
                callbacks.onDismissFailure,
            )
        }
        WizardStep.ANKIDROID -> {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(AnkiMinerTokens.Space.content),
                    verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related),
                ) {
                    WizardAnkiFailure(state, callbacks)
                    AnkiDroidActionButtons(
                        state = state,
                        onRequestPermissions = callbacks.onRequestPermissions,
                        onInstallAnkiDroid = callbacks.onInstallAnkiDroid,
                        onOpenAnkiDroid = callbacks.onOpenAnkiDroid,
                    )
                }
            }
            state.ankiOperation?.let { AnkiOperationCard() }
        }
        WizardStep.ANKIDROID_DECK -> {
            AnkiDeckCard(
                state,
                callbacks.onSelectDeck,
                callbacks.onRetryDeckSelection,
            )
            state.ankiOperation?.let { AnkiOperationCard() }
        }
        WizardStep.ANKIDROID_NOTE_TYPE -> {
            WizardAnkiTargetCard(
                state = state,
                onSelectNoteType = callbacks.onSelectNoteType,
                onCustomizeFields = callbacks.onCustomizeFields,
            )
            state.ankiOperation?.let { AnkiOperationCard() }
        }
        WizardStep.TOKENIZER -> {
            ResourceCard(
                title = stringResource(R.string.unidic_resource_title),
                description = stringResource(R.string.unidic_resource_description),
                installed = state.uniDicInstalled,
                busy = state.busy,
                action = callbacks.onInstallUniDic,
                actionLabel =
                    stringResource(
                        if (state.uniDicInstalled) {
                            R.string.unidic_repair
                        } else {
                            R.string.unidic_install
                        },
                    ),
                inlineFailure = {
                    WizardResourceFailure(
                        state,
                        ResourceFailureOrigin.UNIDIC,
                        callbacks.onInstallUniDic,
                        callbacks.onDismissFailure,
                    )
                },
            )
        }
        WizardStep.DICTIONARY -> {
            CatalogDictionaryCards(
                state,
                callbacks.onInstallCatalogDictionary,
            ) { resourceId ->
                val failure = state.failure
                if (
                    failure?.origin == ResourceFailureOrigin.CATALOG_DICTIONARY &&
                    failure.retry.targetId == resourceId
                ) {
                    WizardResourceFailure(
                        state,
                        ResourceFailureOrigin.CATALOG_DICTIONARY,
                        callbacks.onRetryResourceFailure,
                        callbacks.onDismissFailure,
                    )
                }
            }
        }
        WizardStep.DONE -> {
            val hasRecoveryFailure =
                state.ankiFailure?.origin == AnkiSetupFailureOrigin.RECOVERY ||
                    state.ankiRecoveryFailure?.origin == AnkiSetupFailureOrigin.RECOVERY
            SystemStatusCard(
                state = state,
                onRefresh = callbacks.onRefresh,
                onRequestPermissions = callbacks.onRequestPermissions,
                onOpenAppSettings = callbacks.onOpenAppSettings,
                onInstallAnkiDroid = callbacks.onInstallAnkiDroid,
                onOpenAnkiDroid = callbacks.onOpenAnkiDroid,
                onInstallUniDic = callbacks.onInstallUniDic,
                onChooseNoteType = {
                    callbacks.onStep(WizardStep.ANKIDROID_NOTE_TYPE)
                },
                onResolveRecovery = callbacks.onResolveRecovery,
                onImportDictionary = {
                    callbacks.onStep(WizardStep.DICTIONARY)
                },
                inlineFailureTaskId = SetupTaskId.RECOVERY.takeIf { hasRecoveryFailure },
                inlineFailure =
                    if (hasRecoveryFailure) {
                        {
                            WizardAnkiFailure(
                                state,
                                callbacks,
                                origin = AnkiSetupFailureOrigin.RECOVERY,
                            )
                        }
                    } else {
                        null
                    },
            )
        }
    }
}

@Composable
private fun wizardTitle(
    step: WizardStep,
    isMiningReady: Boolean,
): String =
    stringResource(
        when (step) {
            WizardStep.WELCOME -> R.string.wizard_welcome_title
            WizardStep.ANKIDROID -> R.string.wizard_ankidroid_title
            WizardStep.ANKIDROID_DECK -> R.string.wizard_deck_title
            WizardStep.ANKIDROID_NOTE_TYPE -> R.string.wizard_note_type_title
            WizardStep.TOKENIZER -> R.string.wizard_tokenizer_title
            WizardStep.DICTIONARY -> R.string.wizard_dictionary_title
            WizardStep.DONE ->
                if (wizardFinalState(isMiningReady) == WizardFinalState.READY) {
                    R.string.b3_wizard_ready_title
                } else {
                    R.string.b3_wizard_incomplete_title
                }
        },
    )

@Composable
private fun WizardResourceFailure(
    state: SetupUiState,
    origin: ResourceFailureOrigin,
    onAction: () -> Unit,
    onDismiss: () -> Unit,
) {
    state.failure?.takeIf { it.origin == origin }?.let { failure ->
        InlineFailureContainer(
            message = failure.message,
            actionLabel =
                stringResource(
                    when (failure.retry.action) {
                        ResourceFailureAction.RETRY -> R.string.b3_retry
                        ResourceFailureAction.CHOOSE_ANOTHER -> R.string.b3_choose_another
                        ResourceFailureAction.RESOLVE -> R.string.b3_resolve
                    },
                ),
            onAction = onAction,
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun WizardAnkiFailure(
    state: SetupUiState,
    callbacks: OnboardingWizardCallbacks,
    origin: AnkiSetupFailureOrigin = AnkiSetupFailureOrigin.TARGET,
) {
    val failure =
        listOfNotNull(state.ankiFailure, state.ankiRecoveryFailure)
            .firstOrNull { it.origin == origin }
    failure?.let {
        InlineFailureContainer(
            message = it.message,
            actionLabel =
                stringResource(
                    if (it.origin == AnkiSetupFailureOrigin.RECOVERY) {
                        R.string.b3_resolve
                    } else {
                        R.string.b3_retry
                    },
                ),
            onAction =
                if (it.origin == AnkiSetupFailureOrigin.RECOVERY) {
                    callbacks.onResolveRecovery
                } else {
                    callbacks.onRefresh
                },
            onDismiss = callbacks.onDismissAnkiFailure,
        )
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
                    Modifier.padding(AnkiMinerTokens.Space.group),
                    verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related),
                ) {
                    Text(
                        stringResource(R.string.wizard_completion_failed),
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(
                        onClick = onRetry,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(stringResource(R.string.wizard_completion_retry))
                    }
                    OutlinedButton(
                        onClick = onDismissForSession,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
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
                shape = MaterialTheme.shapes.small,
            ) { Text(stringResource(R.string.install_or_update_ankidroid)) }
        AnkiDroidSetupAction.OPEN ->
            OutlinedButton(
                onClick = onOpenAnkiDroid,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
            ) { Text(stringResource(R.string.open_ankidroid)) }
        AnkiDroidSetupAction.OPEN_OR_INSTALL -> {
            OutlinedButton(
                onClick = onOpenAnkiDroid,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
            ) { Text(stringResource(R.string.open_ankidroid)) }
            OutlinedButton(
                onClick = onInstallAnkiDroid,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
            ) { Text(stringResource(R.string.install_or_update_ankidroid)) }
        }
        AnkiDroidSetupAction.REQUEST_PERMISSION ->
            OutlinedButton(
                onClick = onRequestPermissions,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
            ) { Text(stringResource(R.string.allow_required_access)) }
        null -> Text(stringResource(R.string.wizard_ankidroid_ready))
    }
}

@Composable
private fun WizardNavigation(
    step: WizardStep,
    saving: Boolean,
    onStep: (WizardStep) -> Unit,
    onRequestSkip: () -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (step) {
        WizardStep.WELCOME ->
            AdaptiveActionGroup(
                primary = { actionModifier ->
                    PrimaryActionButton(
                        onClick = { onStep(nextWizardStep(step)) },
                        enabled = !saving,
                        modifier = actionModifier,
                    ) { Text(stringResource(R.string.wizard_set_up_now)) }
                },
                secondary = { actionModifier ->
                    SecondaryActionButton(
                        onClick = onRequestSkip,
                        enabled = !saving,
                        modifier = actionModifier,
                    ) { Text(stringResource(R.string.wizard_skip_for_now)) }
                },
                modifier = modifier,
            )
        WizardStep.DONE ->
            AdaptiveActionGroup(
                primary = { actionModifier ->
                    PrimaryActionButton(
                        onClick = onFinished,
                        enabled = !saving,
                        modifier = actionModifier,
                    ) { Text(stringResource(R.string.wizard_finish)) }
                },
                secondary = { actionModifier ->
                    SecondaryActionButton(
                        onClick = { onStep(previousWizardStep(step)) },
                        enabled = !saving,
                        modifier = actionModifier,
                    ) { Text(stringResource(R.string.wizard_back)) }
                },
                modifier = modifier,
            )
        else ->
            AdaptiveActionGroup(
                primary = { actionModifier ->
                    PrimaryActionButton(
                        onClick = { onStep(nextWizardStep(step)) },
                        enabled = !saving,
                        modifier = actionModifier,
                    ) { Text(stringResource(R.string.wizard_next)) }
                },
                secondary = { actionModifier ->
                    SecondaryActionButton(
                        onClick = { onStep(previousWizardStep(step)) },
                        enabled = !saving,
                        modifier = actionModifier,
                    ) { Text(stringResource(R.string.wizard_back)) }
                },
                modifier = modifier,
            )
    }
}
