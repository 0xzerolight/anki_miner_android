package com.ankiminer.android.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ankiminer.android.R
import com.ankiminer.android.data.RuntimeWorkCoordinator
import com.ankiminer.android.data.resources.ResourceManagerState
import com.ankiminer.android.diagnostics.TesterDiagnosticsIdentity
import com.ankiminer.android.localization.LocalizedStringResource
import com.ankiminer.android.vm.SettingsDraft
import com.ankiminer.android.vm.SettingsSaveState
import com.ankiminer.android.vm.SettingsViewModel
import com.ankiminer.android.vm.SetupUiState
import com.ankiminer.android.vm.SetupViewModel

internal val CUSTOM_DICTIONARY_MIME_TYPES =
    arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")
internal val FREQUENCY_MIME_TYPES =
    arrayOf(
        "application/zip",
        "application/x-zip-compressed",
        "text/csv",
        "text/tab-separated-values",
        "text/plain",
        "application/octet-stream",
    )
internal val PITCH_MIME_TYPES =
    arrayOf(
        "application/zip",
        "application/x-zip-compressed",
        "text/csv",
        "text/tab-separated-values",
        "text/plain",
        "application/octet-stream",
    )
internal val AUDIO_PACK_MIME_TYPES =
    arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")
internal val KNOWN_WORDS_MIME_TYPES =
    arrayOf(
        "application/json",
        "text/csv",
        "text/tab-separated-values",
        "text/plain",
        "application/octet-stream",
    )

internal data class ExcludedDeckChoice(
    val name: String,
    val checked: Boolean,
    val discovered: Boolean,
)

internal fun excludedDeckChoices(
    availableDecks: List<String>,
    excludedDecks: List<String>,
): List<ExcludedDeckChoice> {
    val discovered = availableDecks.toSet()
    val checked = excludedDecks.toSet()
    return (availableDecks + excludedDecks)
        .distinct()
        .sorted()
        .map { name ->
            ExcludedDeckChoice(
                name = name,
                checked = name in checked,
                discovered = name in discovered,
            )
        }
}

@Composable
internal fun SettingsRoute(
    viewModel: SettingsViewModel,
    setupViewModel: SetupViewModel,
    diagnostics: TesterDiagnosticsIdentity,
    onRequestPermissions: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onInstallAnkiDroid: () -> Unit,
    onOpenAnkiDroid: () -> Unit,
    onOpenSpeechSettings: () -> Unit,
    onShareDiagnostics: () -> Unit,
    onShareEngineLog: () -> Unit,
    onReturnToActiveRun: (() -> Unit)? = null,
    onAttributions: () -> Unit,
    onRunSetupWizard: (() -> Unit)? = null,
    onManageKnownWords: () -> Unit = {},
    requestedCategory: SettingsCategory? = null,
    requestedCategoryItemIndex: Int = 2,
    onCategoryRequestConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    LifecycleStartEffect(viewModel) {
        onStopOrDispose { viewModel.flushPendingWrites() }
    }
    LaunchedEffect(setupViewModel) { setupViewModel.refresh() }
    val draftState by viewModel.draftState.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val saveState by viewModel.saveState.collectAsStateWithLifecycle()
    val saveError by viewModel.error.collectAsStateWithLifecycle()
    val resources by viewModel.resourceState.collectAsStateWithLifecycle()
    val setup by setupViewModel.uiState.collectAsStateWithLifecycle()
    if (!draftState.loaded) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    val dictionaryPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { setupViewModel.importCustomDictionary(it.toString()) }
        }
    val frequencyPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { setupViewModel.importFrequencySource(it.toString()) }
        }
    val pitchPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { setupViewModel.importPitchAccent(it.toString()) }
        }
    val audioPackPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { setupViewModel.importAudioPack(it.toString()) }
        }
    val knownWordsPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { setupViewModel.importKnownWords(it.toString()) }
        }
    val knownWordsExportPicker =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("text/plain"),
        ) { uri ->
            uri?.let { setupViewModel.exportKnownWords(it.toString()) }
        }
    SettingsScreen(
        draft = draftState.draft,
        resources = resources,
        setup = setup,
        setupViewModel = setupViewModel,
        saveState = saveState,
        saveError = saveError,
        saving = saving,
        diagnostics = diagnostics,
        onRetrySave = viewModel::retrySave,
        onDraftChange = viewModel::updateDraft,
        onRestoreMiningDefaults = viewModel::restoreMiningDefaults,
        onResetAnkiTarget = viewModel::resetAnkiTarget,
        onResetResourceChoices = viewModel::resetResourceChoices,
        onRequestPermissions = onRequestPermissions,
        onOpenAppSettings = onOpenAppSettings,
        onInstallAnkiDroid = onInstallAnkiDroid,
        onOpenAnkiDroid = onOpenAnkiDroid,
        onOpenSpeechSettings = onOpenSpeechSettings,
        onShareDiagnostics = onShareDiagnostics,
        onShareEngineLog = onShareEngineLog,
        onReturnToActiveRun = onReturnToActiveRun,
        onAttributions = onAttributions,
        onRunSetupWizard = onRunSetupWizard,
        onManageKnownWords = onManageKnownWords,
        requestedCategory = requestedCategory,
        requestedCategoryItemIndex = requestedCategoryItemIndex,
        onCategoryRequestConsumed = onCategoryRequestConsumed,
        onImportCustom = { dictionaryPicker.launch(CUSTOM_DICTIONARY_MIME_TYPES) },
        onImportFrequency = { frequencyPicker.launch(FREQUENCY_MIME_TYPES) },
        onImportPitch = { pitchPicker.launch(PITCH_MIME_TYPES) },
        onImportAudioPack = { audioPackPicker.launch(AUDIO_PACK_MIME_TYPES) },
        onImportKnownWords = { knownWordsPicker.launch(KNOWN_WORDS_MIME_TYPES) },
        onExportKnownWords = { knownWordsExportPicker.launch("known_words.txt") },
        modifier = modifier,
    )
}

@Composable
private fun SettingsScreen(
    draft: SettingsDraft,
    resources: ResourceManagerState,
    setup: SetupUiState,
    setupViewModel: SetupViewModel,
    saveState: SettingsSaveState,
    saveError: LocalizedStringResource?,
    saving: Boolean,
    diagnostics: TesterDiagnosticsIdentity,
    onRetrySave: () -> Unit,
    onDraftChange: (SettingsDraft) -> Unit,
    onRestoreMiningDefaults: () -> Unit,
    onResetAnkiTarget: () -> Unit,
    onResetResourceChoices: () -> Unit,
    onRequestPermissions: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onInstallAnkiDroid: () -> Unit,
    onOpenAnkiDroid: () -> Unit,
    onOpenSpeechSettings: () -> Unit,
    onShareDiagnostics: () -> Unit,
    onShareEngineLog: () -> Unit,
    onReturnToActiveRun: (() -> Unit)?,
    onAttributions: () -> Unit,
    onRunSetupWizard: (() -> Unit)?,
    onManageKnownWords: () -> Unit,
    requestedCategory: SettingsCategory?,
    requestedCategoryItemIndex: Int,
    onCategoryRequestConsumed: () -> Unit,
    onImportCustom: () -> Unit,
    onImportFrequency: () -> Unit,
    onImportPitch: () -> Unit,
    onImportAudioPack: () -> Unit,
    onImportKnownWords: () -> Unit,
    onExportKnownWords: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedCategory by rememberSaveable { mutableStateOf(SettingsCategory.SETUP) }
    val listStates = rememberSettingsCategoryListStates()
    var resetConfirmation by remember {
        mutableStateOf(SettingsResetConfirmationState())
    }

    LaunchedEffect(requestedCategory) {
        requestedCategory?.let { category ->
            selectedCategory = category
            listStates.getValue(category).scrollToItem(requestedCategoryItemIndex)
            onCategoryRequestConsumed()
        }
    }

    resetConfirmation.pendingAction?.let { action ->
        AlertDialog(
            onDismissRequest = { resetConfirmation = resetConfirmation.cancel() },
            title = { Text(stringResource(settingsResetLabel(action))) },
            text = { Text(stringResource(settingsResetDescription(action))) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val (nextState, confirmedAction) = resetConfirmation.confirm()
                        resetConfirmation = nextState
                        dispatchConfirmedSettingsReset(
                            action = confirmedAction,
                            onRestoreMiningDefaults = onRestoreMiningDefaults,
                            onResetAnkiTarget = onResetAnkiTarget,
                            onResetResourceChoices = onResetResourceChoices,
                        )
                    },
                ) {
                    Text(stringResource(settingsResetLabel(action)))
                }
            },
            dismissButton = {
                TextButton(onClick = { resetConfirmation = resetConfirmation.cancel() }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
    CatalogReplaceDialog(
        state = setup,
        onConfirm = setupViewModel::confirmCatalogDictionaryReplace,
        onDismiss = setupViewModel::dismissCatalogDictionaryReplace,
    )

    val callbacks =
        SettingsScreenCallbacks(
            onDraftChange = onDraftChange,
            onRequestReset = { resetConfirmation = resetConfirmation.request(it) },
            resetEnabled = !saving,
            onRequestPermissions = onRequestPermissions,
            onOpenAppSettings = onOpenAppSettings,
            onInstallAnkiDroid = onInstallAnkiDroid,
            onOpenAnkiDroid = onOpenAnkiDroid,
            onOpenSpeechSettings = onOpenSpeechSettings,
            onShareDiagnostics = onShareDiagnostics,
            onShareEngineLog = onShareEngineLog,
            onReturnToActiveRun = onReturnToActiveRun,
            onAttributions = onAttributions,
            onRunSetupWizard = onRunSetupWizard,
            onImportCustom = onImportCustom,
            onImportFrequency = onImportFrequency,
            onImportPitch = onImportPitch,
            onImportAudioPack = onImportAudioPack,
            onImportKnownWords = onImportKnownWords,
            onExportKnownWords = onExportKnownWords,
            onManageKnownWords = onManageKnownWords,
        )
    SettingsCategoryLayout(
        selectedCategory = selectedCategory,
        onSelectedCategory = { category ->
            selectedCategory = category
        },
        listStates = listStates,
        modifier = modifier,
        header = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SystemStatusCard(
                    state = setup,
                    onRefresh = setupViewModel::refresh,
                    onRequestPermissions = onRequestPermissions,
                    onOpenAppSettings = onOpenAppSettings,
                    onInstallAnkiDroid = onInstallAnkiDroid,
                    onOpenAnkiDroid = onOpenAnkiDroid,
                    compact = true,
                    onInstallUniDic = setupViewModel::installUniDic,
                    onChooseNoteType = {
                        selectedCategory = SettingsCategory.ANKI
                    },
                    onResolveRecovery = {
                        selectedCategory = SettingsCategory.ANKI
                    },
                )
                SettingsSaveStatus(
                    state = saveState,
                    error = saveError?.localized(),
                    onRetry = onRetrySave,
                )
            }
        },
    ) { category ->
        settingsCategoryContent(
            category = category,
            draft = draft,
            resources = resources,
            setup = setup,
            setupViewModel = setupViewModel,
            diagnostics = diagnostics,
            callbacks = callbacks,
        )
    }
}

@Composable
private fun LocalizedStringResource.localized(): String =
    stringResource(resourceId, *formatArguments.toTypedArray())

@StringRes
internal fun settingsRuntimeWorkMessage(kind: RuntimeWorkCoordinator.Kind): Int =
    when (kind) {
        RuntimeWorkCoordinator.Kind.MINING -> R.string.runtime_work_settings_mining_active
        RuntimeWorkCoordinator.Kind.RESOURCE -> R.string.runtime_work_settings_resource_active
        RuntimeWorkCoordinator.Kind.ANKI_SETUP -> R.string.runtime_work_settings_anki_active
    }

@StringRes
internal fun settingsResetLabel(action: SettingsResetAction): Int =
    when (action) {
        SettingsResetAction.RESTORE_MINING_DEFAULTS -> R.string.settings_restore_mining_defaults
        SettingsResetAction.RESET_ANKI_TARGET -> R.string.settings_reset_anki_target
        SettingsResetAction.RESET_RESOURCE_CHOICES -> R.string.settings_reset_resource_choices
    }

@StringRes
private fun settingsResetDescription(action: SettingsResetAction): Int =
    when (action) {
        SettingsResetAction.RESTORE_MINING_DEFAULTS ->
            R.string.settings_restore_mining_defaults_confirmation
        SettingsResetAction.RESET_ANKI_TARGET ->
            R.string.settings_reset_anki_target_confirmation
        SettingsResetAction.RESET_RESOURCE_CHOICES ->
            R.string.settings_reset_resource_choices_confirmation
    }
