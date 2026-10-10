package com.ankiminer.android.ui.wizard

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.ankiminer.android.R
import com.ankiminer.android.data.anki.AnkiSetupFailureOrigin
import com.ankiminer.android.data.resources.ResourceFailureAction
import com.ankiminer.android.data.resources.ResourceFailureOrigin
import com.ankiminer.android.engine.LanguageProfileInfo
import com.ankiminer.android.engine.LanguageUnavailableReason
import com.ankiminer.android.ui.settings.AnkiDeckCard
import com.ankiminer.android.ui.settings.AnkiDroidConnectActions
import com.ankiminer.android.ui.settings.AnkiOperationCard
import com.ankiminer.android.ui.settings.InlineFailureContainer
import com.ankiminer.android.ui.settings.LanguageSettingsState
import com.ankiminer.android.ui.settings.bidiIsolated
import com.ankiminer.android.ui.settings.RecommendedResourcesCard
import com.ankiminer.android.ui.settings.SettingsDropdown
import com.ankiminer.android.ui.settings.SystemStatusCard
import com.ankiminer.android.ui.settings.WizardAnkiTargetCard
import com.ankiminer.android.ui.settings.currentUiLocale
import com.ankiminer.android.ui.settings.languageDisplayName
import com.ankiminer.android.ui.theme.AnkiMinerTokens
import com.ankiminer.android.ui.theme.ExitActionButton
import com.ankiminer.android.ui.theme.SecondaryActionButton
import com.ankiminer.android.ui.theme.SupportingText
import com.ankiminer.android.ui.theme.accentTextButtonColors
import com.ankiminer.android.vm.SetupUiState

/** Page 1: what the user is learning, plus the one-sentence welcome. Skip ends the page. */
@Composable
internal fun WizardLanguagePage(
    state: SetupUiState,
    language: WizardLanguageState,
    pendingLanguage: String?,
    heldLanguage: String?,
    onPick: (code: String, needsDownload: Boolean) -> Unit,
    onSkip: () -> Unit,
    skipEnabled: Boolean,
) {
    val choices = wizardLanguageChoices(language.profiles, state.language)
    val switchAllowed =
        LanguageSettingsState(
            activeCode = state.language,
            profiles = language.profiles,
            downloadingCode = language.downloadingCode,
            knownWordsPreviewOpen = state.languageSwitchRefusal != null,
            busy = state.busy,
        ).switchAllowed
    val labels =
        choices.associate { choice ->
            choice.code to
                if (choice.needsDownload) {
                    // Isolated: an Arabic name would otherwise turn the whole line right to left.
                    stringResource(R.string.wizard_language_option_download, bidiIsolated(choice.label))
                } else {
                    choice.label
                }
        }
    Text(stringResource(R.string.wizard_intro))
    SettingsDropdown(
        label = stringResource(R.string.wizard_language_picker),
        options = choices.map { it.code to labels.getValue(it.code) },
        selected = pendingLanguage ?: heldLanguage ?: state.language,
        onSelect = { code -> onPick(code, choices.first { it.code == code }.needsDownload) },
        isOptionEnabled = { switchAllowed },
    )
    if (language.profiles.isEmpty()) {
        // The engine has not listed the languages yet; Next waits for the list (wizardNextEnabled).
        SupportingText(stringResource(R.string.b3_status_checking))
    }
    SupportingText(stringResource(R.string.wizard_language_help))
    choices.firstOrNull { it.code == pendingLanguage && it.needsDownload }?.let { choice ->
        Text(
            stringResource(
                R.string.wizard_language_download_note,
                bidiIsolated(choice.label),
                Formatter.formatShortFileSize(LocalContext.current, language.downloadBytes(choice.code)),
            ),
        )
    }
    // The first page has nothing behind it, so its exit sits here. In the app bar it took the
    // heading's width first: at 320dp and 1.3x text the title read "What ar…".
    ExitActionButton(onClick = onSkip, enabled = skipEnabled) {
        Text(stringResource(R.string.wizard_skip_for_now))
    }
}

/** Page 2: one button; downloads keep running and Next never waits (owner decision D1). */
@Composable
internal fun WizardDownloadsPage(
    state: SetupUiState,
    language: WizardLanguageState,
    heldLanguage: String?,
    callbacks: OnboardingWizardCallbacks,
) {
    Text(stringResource(R.string.wizard_downloads_intro))
    if (heldLanguage != null) {
        // The picked language's data and dictionary arrive together; the old language's set is
        // not offered meanwhile.
        WizardHeldLanguage(heldLanguage, language, callbacks)
        return
    }
    RecommendedResourcesCard(
        state = state,
        title = stringResource(R.string.wizard_dictionary_title),
        uniDicMissing = state.uniDicRequired && !state.uniDicInstalled,
        statusOnly = true,
        emphasized = wizardStepActionPending(WizardStep.DOWNLOADS, state),
        onDownload = callbacks.onInstallRequiredResources,
        inlineFailure = {
            WizardResourceFailure(state, ResourceFailureOrigin.UNIDIC, callbacks.onInstallRequiredResources, callbacks.onDismissFailure)
            WizardResourceFailure(state, ResourceFailureOrigin.RECOMMENDED_SET, callbacks.onRetryResourceFailure, callbacks.onDismissFailure)
        },
    )
}

/** Page 3: connect, then deck and note type together (onboarding-13). */
@Composable
internal fun WizardAnkiDroidPage(
    state: SetupUiState,
    language: WizardLanguageState,
    heldLanguage: String?,
    callbacks: OnboardingWizardCallbacks,
    emphasized: Boolean,
) {
    // A connected AnkiDroid with no provider error has nothing to say here; an empty card above
    // the deck read as a broken step.
    val connectCardHasContent =
        !state.ankiReady ||
            state.ankiDroidAction != null ||
            state.ankiFailure?.origin == AnkiSetupFailureOrigin.TARGET
    if (connectCardHasContent) {
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(AnkiMinerTokens.Space.content),
                verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related),
            ) {
                if (state.ankiDroidAction == null) {
                    WizardAnkiFailure(state, callbacks)
                    if (!state.ankiReady) Text(stringResource(R.string.b3_status_checking))
                }
                AnkiDroidConnectActions(
                    state = state,
                    onRequestPermissions = callbacks.onRequestPermissions,
                    onOpenAppSettings = callbacks.onOpenAppSettings,
                    onInstallAnkiDroid = callbacks.onInstallAnkiDroid,
                    onOpenAnkiDroid = callbacks.onOpenAnkiDroid,
                    emphasized = emphasized,
                )
            }
        }
    }
    if (state.ankiReady) {
        if (heldLanguage != null) {
            // Deck and note type belong to a language; picked now, they would land in the old one.
            SupportingText(
                stringResource(R.string.wizard_anki_waits_for_language, wizardLanguageName(heldLanguage, language.profiles)),
            )
        } else {
            AnkiDeckCard(state, callbacks.onSelectDeck, callbacks.onRetryDeckSelection)
            WizardAnkiTargetCard(
                state = state,
                onSelectNoteType = callbacks.onSelectNoteType,
            )
        }
    }
    state.ankiOperation?.let { AnkiOperationCard() }
}

/** Page 4: only what is still missing, each with its fix, then the next step once ready. */
@Composable
internal fun WizardReadyPage(
    state: SetupUiState,
    language: WizardLanguageState,
    heldLanguage: String?,
    callbacks: OnboardingWizardCallbacks,
) {
    if (heldLanguage != null) {
        // The status card would judge the old language (its tokenizer, its dictionary) and offer
        // to install what the user did not pick. Only the picked language and AnkiDroid count.
        WizardHeldLanguage(heldLanguage, language, callbacks)
        AnkiDroidConnectActions(
            state = state,
            onRequestPermissions = callbacks.onRequestPermissions,
            onOpenAppSettings = callbacks.onOpenAppSettings,
            onInstallAnkiDroid = callbacks.onInstallAnkiDroid,
            onOpenAnkiDroid = callbacks.onOpenAnkiDroid,
        )
        return
    }
    SystemStatusCard(
        state = state,
        onRefresh = callbacks.onRefresh,
        onRequestPermissions = callbacks.onRequestPermissions,
        onOpenAppSettings = callbacks.onOpenAppSettings,
        onInstallAnkiDroid = callbacks.onInstallAnkiDroid,
        onOpenAnkiDroid = callbacks.onOpenAnkiDroid,
        onInstallUniDic = callbacks.onInstallRequiredResources,
        onChooseNoteType = { callbacks.onStep(WizardStep.ANKIDROID) },
        onImportDictionary = callbacks.onInstallRequiredResources,
    )
    WizardResourceFailure(state, ResourceFailureOrigin.SETUP, callbacks.onRefresh, callbacks.onDismissFailure)
    if (wizardFinalState(state) == WizardFinalState.READY) {
        Text(stringResource(R.string.wizard_next_step))
    }
    // The way to the full field map, from the page that finishes setup instead of mid-way.
    TextButton(onClick = callbacks.onChangeCardFields, colors = accentTextButtonColors()) {
        Text(stringResource(R.string.wizard_change_card_fields))
    }
}

/**
 * The language Next started fetching: its download running, or gone (Android closed the app, or
 * it failed), in which case it is offered again rather than silently falling back to the old one.
 */
@Composable
private fun WizardHeldLanguage(
    code: String,
    language: WizardLanguageState,
    callbacks: OnboardingWizardCallbacks,
) {
    if (language.downloadingCode != null) {
        Text(stringResource(R.string.wizard_downloads_language_running, wizardLanguageName(code, language.profiles)))
    } else {
        // After a process death, or a cancel once the data was in, the profile can already read
        // available: the same button then fetches the rest of the set and switches.
        val needsData =
            language.profiles.firstOrNull { it.code == code }?.unavailableReason ==
                LanguageUnavailableReason.DATA_REQUIRED
        if (needsData) SupportingText(stringResource(R.string.language_unavailable_data_required))
        SecondaryActionButton(
            onClick = { callbacks.onDownloadAndSwitchLanguage(code) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.language_download_and_switch)) }
    }
}

@Composable
private fun wizardLanguageName(
    code: String,
    profiles: List<LanguageProfileInfo>,
): String {
    val locale = currentUiLocale()
    return profiles.firstOrNull { it.code == code }?.let { languageDisplayName(it, locale) } ?: code
}

@Composable
internal fun WizardResourceFailure(
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
internal fun WizardAnkiFailure(
    state: SetupUiState,
    callbacks: OnboardingWizardCallbacks,
    origin: AnkiSetupFailureOrigin = AnkiSetupFailureOrigin.TARGET,
) {
    state.ankiFailure?.takeIf { it.origin == origin }?.let { failure ->
        InlineFailureContainer(
            message = failure.message,
            actionLabel = stringResource(R.string.b3_retry),
            onAction = callbacks.onRefresh,
            onDismiss = callbacks.onDismissAnkiFailure,
        )
    }
}
