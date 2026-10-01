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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ankiminer.android.R
import com.ankiminer.android.data.resources.requiredDownloads
import com.ankiminer.android.engine.LanguageProfileInfo
import com.ankiminer.android.engine.LanguageUnavailableReason
import com.ankiminer.android.ui.navigation.AppChrome
import com.ankiminer.android.ui.settings.ResourceOperationCard
import com.ankiminer.android.ui.settings.ResourceReplaceDialog
import com.ankiminer.android.ui.settings.SetupTaskId
import com.ankiminer.android.ui.settings.SetupTaskRole
import com.ankiminer.android.ui.settings.languageDisplayName
import com.ankiminer.android.ui.settings.orderedLanguageChoices
import com.ankiminer.android.ui.settings.setupTaskStatus
import com.ankiminer.android.ui.theme.AnkiMinerTokens
import com.ankiminer.android.ui.theme.PrimaryActionButton
import com.ankiminer.android.ui.theme.SecondaryActionButton
import com.ankiminer.android.ui.theme.accentTextButtonColors
import com.ankiminer.android.vm.SetupUiState
import com.ankiminer.android.vm.SetupViewModel
import com.ankiminer.android.vm.WizardCompletionStatus
import java.util.Locale

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

/** Desktop's four pages (owner decision D1). Language comes first: everything after it is scoped to it. */
internal enum class WizardStep {
    LANGUAGE,
    DOWNLOADS,
    ANKIDROID,
    READY,
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

/** Where "Continue setup" reopens the wizard: the first page whose work is still undone. */
internal fun firstIncompleteWizardStep(state: SetupUiState): WizardStep =
    when {
        !state.tokenizerReady || !state.dictionaryReady -> WizardStep.DOWNLOADS
        !state.ankiReady || !state.targetReady -> WizardStep.ANKIDROID
        else -> WizardStep.READY
    }

internal fun wizardBackAction(step: WizardStep): WizardBackAction =
    if (step == WizardStep.LANGUAGE) {
        WizardBackAction.ConfirmSkip
    } else {
        WizardBackAction.Previous(previousWizardStep(step))
    }

/**
 * Next waits for nothing but the language list: until the engine has listed the languages, the
 * Language page shows only the active one, and Next would silently keep it.
 */
internal fun wizardNextEnabled(
    step: WizardStep,
    saving: Boolean,
    profilesLoaded: Boolean,
): Boolean = !saving && (step != WizardStep.LANGUAGE || profilesLoaded)

internal enum class WizardFinalState {
    READY,
    ALMOST_READY,
    INCOMPLETE,
}

/**
 * "Almost ready" only while a download is running and the downloads are all that is left. While a
 * picked language is held ([languagePending]), the active language's rows belong to the old
 * language and are not judged: only AnkiDroid and the running download count.
 */
internal fun wizardFinalState(
    state: SetupUiState,
    languagePending: Boolean = false,
    languageDownloadRunning: Boolean = languagePending,
): WizardFinalState {
    if (languagePending) {
        return if (languageDownloadRunning && state.ankiDroidAction == null) {
            WizardFinalState.ALMOST_READY
        } else {
            WizardFinalState.INCOMPLETE
        }
    }
    if (state.isMiningReady) return WizardFinalState.READY
    val onlyDownloadsLeft =
        state.setupTaskStatus().rows
            .filter { it.role == SetupTaskRole.REQUIRED_ACTION }
            .all { it.id == SetupTaskId.UNIDIC || it.id == SetupTaskId.DICTIONARY }
    return if (state.operation != null && onlyDownloadsLeft) {
        WizardFinalState.ALMOST_READY
    } else {
        WizardFinalState.INCOMPLETE
    }
}

/**
 * The page's own required action has not run yet, so it carries the filled emphasis and Next is
 * the quiet one: a filled Next beside a grey Install invited skipping the download mining needs.
 */
internal fun wizardStepActionPending(
    step: WizardStep,
    state: SetupUiState,
    languagePending: Boolean = false,
): Boolean =
    when (step) {
        WizardStep.LANGUAGE,
        WizardStep.READY,
        -> false
        WizardStep.DOWNLOADS ->
            !languagePending &&
                state.operation == null &&
                ((state.uniDicRequired && !state.uniDicInstalled) || state.recommendedPlan.isActionable)
        WizardStep.ANKIDROID -> state.ankiDroidAction != null
    }

/** What the Language page reads from the Settings language machinery. */
internal data class WizardLanguageState(
    val profiles: List<LanguageProfileInfo> = emptyList(),
    /** The language a "Download and switch" is fetching, if any. In memory only. */
    val downloadingCode: String? = null,
    /** Bytes a language's one-time download would fetch. */
    val downloadBytes: (String) -> Long = { 0L },
)

internal data class WizardLanguageChoice(
    val code: String,
    val label: String,
    val needsDownload: Boolean,
)

/**
 * The languages this build can mine, or can unlock with one download, in the Settings picker's
 * order with the active one first. One this build cannot mine at all is not offered.
 */
internal fun wizardLanguageChoices(
    profiles: List<LanguageProfileInfo>,
    uiLocale: Locale,
    activeCode: String,
): List<WizardLanguageChoice> =
    orderedLanguageChoices(profiles, uiLocale, activeCode)
        .filter { it.unavailableReason != LanguageUnavailableReason.UNSUPPORTED }
        .sortedByDescending { it.code == activeCode }
        .map { profile ->
            val localized = languageDisplayName(profile, uiLocale)
            WizardLanguageChoice(
                code = profile.code,
                label = if (profile.displayName == localized) localized else "${profile.displayName} — $localized",
                needsDownload = profile.unavailableReason == LanguageUnavailableReason.DATA_REQUIRED,
            )
        }

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
    val onInstallRequiredResources: () -> Unit = {},
    val onSelectDeck: (String) -> Unit = {},
    val onRetryDeckSelection: () -> Unit = {},
    val onSelectNoteType: (String) -> Unit = {},
    val onChangeCardFields: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onCancelOperation: () -> Unit = {},
    val onRetryResourceFailure: () -> Unit = {},
    val onRetryWizardCompletion: () -> Unit = {},
    val onDismissWizardForSession: () -> Unit = {},
    val onSwitchLanguage: (String) -> Unit = {},
    val onDownloadAndSwitchLanguage: (String) -> Unit = {},
)

@Composable
internal fun OnboardingWizard(
    state: SetupUiState,
    viewModel: SetupViewModel,
    language: WizardLanguageState,
    onSwitchLanguage: (String) -> Unit,
    onDownloadAndSwitchLanguage: (String) -> Unit,
    onRequestPermissions: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onInstallAnkiDroid: () -> Unit,
    onOpenAnkiDroid: () -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    onChangeCardFields: () -> Unit = {},
    initialStep: WizardStep = WizardStep.LANGUAGE,
) {
    var step by rememberSaveable { mutableStateOf(initialStep) }
    val inventory by viewModel.inventory.collectAsStateWithLifecycle()
    OnboardingWizardContent(
        state = state,
        step = step,
        language =
            language.copy(
                downloadBytes = { code ->
                    requiredDownloads(inventory.recommendedPlan(code), uniDicMissing = false).bytes
                },
            ),
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
                onInstallRequiredResources = viewModel::installRequiredResources,
                onSelectDeck = viewModel::selectDeck,
                onRetryDeckSelection = viewModel::retryDeckSelection,
                onSelectNoteType = viewModel::selectNoteType,
                onChangeCardFields = onChangeCardFields,
                onRefresh = viewModel::refresh,
                onCancelOperation = viewModel::cancelOperation,
                onRetryResourceFailure = viewModel::retryResourceFailure,
                onRetryWizardCompletion = viewModel::retryWizardCompletion,
                onDismissWizardForSession = viewModel::dismissWizardForSession,
                onSwitchLanguage = onSwitchLanguage,
                onDownloadAndSwitchLanguage = onDownloadAndSwitchLanguage,
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
    language: WizardLanguageState = WizardLanguageState(),
    scrollState: ScrollState = rememberScrollState(),
) {
    var showSkipConfirmation by rememberSaveable { mutableStateOf(false) }
    // A language that needs its data is switched to only once the data is in; until Next starts
    // that download it is just the user's pick.
    var pendingLanguage by rememberSaveable { mutableStateOf<String?>(null) }
    // The language Next started downloading. Saved, because the download itself is not: after a
    // process death the later pages must still hold the deck, note type and old-language downloads
    // back, and offer the download again.
    var targetLanguage by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(state.language) {
        if (targetLanguage == state.language) targetLanguage = null
    }
    val heldLanguage = language.downloadingCode ?: targetLanguage?.takeIf { it != state.language }
    val languageDownloadRunning = language.downloadingCode != null
    val saving = state.wizardCompletion == WizardCompletionStatus.SAVING
    val requestBack = {
        when (val action = wizardBackAction(step)) {
            WizardBackAction.ConfirmSkip -> showSkipConfirmation = true
            is WizardBackAction.Previous -> callbacks.onStep(action.step)
        }
    }
    BackHandler(onBack = { if (!saving) requestBack() })
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
                    colors = accentTextButtonColors(),
                ) { Text(stringResource(R.string.b3_wizard_confirm_skip)) }
            },
            dismissButton = {
                TextButton(onClick = { showSkipConfirmation = false }, colors = accentTextButtonColors()) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val title = wizardTitle(step, state, heldLanguage != null, languageDownloadRunning)
    // The position lives in the progress bar's semantics; a visible "Step N of 4" repeated the bar.
    val position = stringResource(R.string.wizard_step_position, step.ordinal + 1, WizardStep.entries.size)
    val animatedProgress by
        animateFloatAsState(
            targetValue = (step.ordinal + 1).toFloat() / WizardStep.entries.size.toFloat(),
            animationSpec = tween(durationMillis = 150),
            label = "wizard progress",
        )
    val onNext = {
        if (step == WizardStep.LANGUAGE) {
            pendingLanguage?.takeIf { it != state.language }?.let { code ->
                targetLanguage = code
                callbacks.onDownloadAndSwitchLanguage(code)
            }
            pendingLanguage = null
        }
        callbacks.onStep(nextWizardStep(step))
    }
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
                    // Back lives here alone (owner decision D2). The first page has nothing behind
                    // it; its exit is the Skip button at the end of the page, so the heading keeps
                    // the whole bar.
                    onNavigateBack = requestBack.takeUnless { saving || step == WizardStep.LANGUAGE },
                )
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier.fillMaxWidth().semantics { stateDescription = position },
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
                    enabled = wizardNextEnabled(step, saving, profilesLoaded = language.profiles.isNotEmpty()),
                    emphasized = !wizardStepActionPending(step, state, languagePending = heldLanguage != null),
                    onNext = onNext,
                    onFinished = callbacks.onFinished,
                    modifier = Modifier.padding(AnkiMinerTokens.Space.content),
                )
            }
        },
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState, modifier = Modifier.navigationBarsPadding())
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
                    fadeIn(tween(durationMillis = 150)) togetherWith fadeOut(tween(durationMillis = 90))
                },
                contentKey = { targetStep -> targetStep },
                label = "wizard step",
            ) { targetStep ->
                val targetTitle = wizardTitle(targetStep, state, heldLanguage != null, languageDownloadRunning)
                Column(
                    modifier = Modifier.fillMaxWidth().semantics { paneTitle = targetTitle },
                    verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.content),
                ) {
                    when (targetStep) {
                        WizardStep.LANGUAGE ->
                            WizardLanguagePage(
                                state = state,
                                language = language,
                                pendingLanguage = pendingLanguage,
                                heldLanguage = heldLanguage,
                                onPick = { code, needsDownload ->
                                    if (needsDownload) {
                                        pendingLanguage = code
                                    } else {
                                        pendingLanguage = null
                                        targetLanguage = null
                                        callbacks.onSwitchLanguage(code)
                                    }
                                },
                                onSkip = callbacks.onFinished,
                                skipEnabled = !saving,
                            )
                        WizardStep.DOWNLOADS -> WizardDownloadsPage(state, language, heldLanguage, callbacks)
                        WizardStep.ANKIDROID ->
                            WizardAnkiDroidPage(
                                state = state,
                                language = language,
                                heldLanguage = heldLanguage,
                                callbacks = callbacks,
                                emphasized = wizardStepActionPending(WizardStep.ANKIDROID, state),
                            )
                        WizardStep.READY -> WizardReadyPage(state, language, heldLanguage, callbacks)
                    }
                    // A running download follows the user through every page after the first.
                    if (targetStep != WizardStep.LANGUAGE) {
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
private fun wizardTitle(
    step: WizardStep,
    state: SetupUiState,
    languagePending: Boolean,
    languageDownloadRunning: Boolean,
): String =
    stringResource(
        when (step) {
            WizardStep.LANGUAGE -> R.string.wizard_language_title
            WizardStep.DOWNLOADS -> R.string.wizard_downloads_title
            WizardStep.ANKIDROID -> R.string.wizard_ankidroid_title
            WizardStep.READY ->
                when (wizardFinalState(state, languagePending, languageDownloadRunning)) {
                    WizardFinalState.READY -> R.string.b3_wizard_ready_title
                    WizardFinalState.ALMOST_READY -> R.string.wizard_almost_ready_title
                    WizardFinalState.INCOMPLETE -> R.string.b3_wizard_incomplete_title
                }
        },
    )

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
                    PrimaryActionButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.wizard_completion_retry))
                    }
                    SecondaryActionButton(onClick = onDismissForSession, modifier = Modifier.fillMaxWidth()) {
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

/** One full-width Next or Finish (owner decision D2). Next never waits on a download. */
@Composable
private fun WizardNavigation(
    step: WizardStep,
    enabled: Boolean,
    emphasized: Boolean,
    onNext: () -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val finish = step == WizardStep.READY
    val label = stringResource(if (finish) R.string.wizard_finish else R.string.wizard_next)
    val onClick = if (finish) onFinished else onNext
    if (emphasized) {
        PrimaryActionButton(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth()) { Text(label) }
    } else {
        SecondaryActionButton(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth()) { Text(label) }
    }
}
