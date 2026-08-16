package com.ankiminer.android.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ankiminer.android.R
import com.ankiminer.android.data.RuntimeWorkCoordinator
import com.ankiminer.android.data.resources.ResourceFailureOrigin
import com.ankiminer.android.data.resources.ResourceManagerState
import com.ankiminer.android.data.resources.WordListKind
import com.ankiminer.android.data.update.UpdateCheckUiState
import com.ankiminer.android.diagnostics.TesterDiagnosticsIdentity
import com.ankiminer.android.localization.LocalizedStringResource
import com.ankiminer.android.ui.mining.RuntimeConflictNotice
import com.ankiminer.android.ui.theme.AnkiMinerTokens
import com.ankiminer.android.ui.theme.dynamicColorSupported
import com.ankiminer.android.vm.DiagnosticsExportState
import com.ankiminer.android.vm.DiagnosticsViewModel
import com.ankiminer.android.vm.SettingsBackupState
import com.ankiminer.android.vm.SettingsDraft
import com.ankiminer.android.vm.SettingsSaveState
import com.ankiminer.android.vm.SettingsViewModel
import com.ankiminer.android.vm.SetupUiState
import com.ankiminer.android.vm.SetupViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

// This is a dwell, not a transition. At a 120–220 ms motion-token duration, the mark would be
// gone before the eye arrived at the jumped-to card.
private const val HIGHLIGHT_MILLIS = 1_200L

internal data class ExternalSettingsCategoryJump(
    val category: SettingsCategory,
    val itemIndex: Int,
    val targetCardKey: String?,
    val searchQuery: String,
)

internal fun externalSettingsCategoryJump(
    currentSearchQuery: String,
    requestedCategory: SettingsCategory?,
    requestedItemIndex: Int,
): ExternalSettingsCategoryJump? {
    val category = requestedCategory ?: return null
    return ExternalSettingsCategoryJump(
        category = category,
        itemIndex = requestedItemIndex,
        targetCardKey = externalSettingsTargetCardKey(category, requestedItemIndex),
        searchQuery = if (currentSearchQuery.isEmpty()) currentSearchQuery else "",
    )
}

private fun externalSettingsTargetCardKey(
    category: SettingsCategory,
    itemIndex: Int,
): String? =
    when (category to itemIndex) {
        SettingsCategory.ANKI to 3 -> "anki-target"
        SettingsCategory.ANKI to 4 -> "anki-recovery"
        SettingsCategory.DICTIONARIES to 2 -> "dictionary-sources"
        SettingsCategory.DICTIONARIES to 3 -> "pitch-sources"
        SettingsCategory.DICTIONARIES to 4 -> "dictionary-lookup"
        SettingsCategory.AUDIO to 2 -> "audio-sources"
        SettingsCategory.FREQUENCY to 2 -> "frequency-sources"
        SettingsCategory.FILTERING to 3 -> "known-words-import"
        SettingsCategory.FILTERING to 4 -> "word-lists"
        SettingsCategory.DIAGNOSTICS to 3 -> "unidic"
        else -> null
    }

// `OpenDocument` greys out anything whose provider-reported MIME is not matched here, and the
// post-pick classifier (`detectResourceImportFileKind`) is extension-first and far more
// permissive, so this list is the only thing that can reject a file the import path would have
// handled. Keep it at least as wide as the classifier; `ImportMimeTypesTest` enforces that.
private val ZIP_IMPORT_MIME_TYPES =
    arrayOf("application/zip", "application/x-zip", "application/x-zip-compressed")

// `text/*` rather than the three spellings we happen to know: pre-Android 10 `MimeUtils` maps a
// .csv to text/comma-separated-values, and providers also invent text/tsv and text/x-csv.
private val TEXT_IMPORT_MIME_TYPES = arrayOf("text/*")

// Out of reach of the wildcard. Drive and several OEM file managers report a Windows-authored
// .csv as application/vnd.ms-excel.
private val CSV_IMPORT_MIME_TYPES = arrayOf("application/csv", "application/vnd.ms-excel")

// A hand-written list often arrives untyped from cloud providers.
private val UNTYPED_IMPORT_MIME_TYPES = arrayOf("application/octet-stream")

// The upstream local-audio collection ships as a .tar.xz torrent, and providers spell that
// container at least five ways. Without these the file users actually download is greyed out in
// the picker with no error at all, which is indistinguishable from the app being broken.
private val TAR_IMPORT_MIME_TYPES =
    arrayOf(
        "application/x-xz",
        "application/x-tar",
        "application/x-gtar",
        "application/x-gzip",
        "application/gzip",
    )

internal val CUSTOM_DICTIONARY_MIME_TYPES = ZIP_IMPORT_MIME_TYPES + UNTYPED_IMPORT_MIME_TYPES
internal val FREQUENCY_MIME_TYPES =
    ZIP_IMPORT_MIME_TYPES + TEXT_IMPORT_MIME_TYPES + CSV_IMPORT_MIME_TYPES +
        UNTYPED_IMPORT_MIME_TYPES
internal val PITCH_MIME_TYPES = FREQUENCY_MIME_TYPES
internal val AUDIO_PACK_MIME_TYPES =
    ZIP_IMPORT_MIME_TYPES + TAR_IMPORT_MIME_TYPES + UNTYPED_IMPORT_MIME_TYPES
internal val KNOWN_WORDS_MIME_TYPES =
    arrayOf("application/json") + TEXT_IMPORT_MIME_TYPES + CSV_IMPORT_MIME_TYPES +
        UNTYPED_IMPORT_MIME_TYPES

internal val WORD_LIST_MIME_TYPES = TEXT_IMPORT_MIME_TYPES + UNTYPED_IMPORT_MIME_TYPES

// A .json is typed `application/json` by most providers, but Drive and several OEM file managers
// report a hand-copied one as text/plain or untyped — the same lesson the CSV import filters learned.
internal val SETTINGS_BACKUP_MIME_TYPES =
    arrayOf("application/json") + TEXT_IMPORT_MIME_TYPES + UNTYPED_IMPORT_MIME_TYPES

/**
 * Whether a picker launched with [allowed] would offer a document of [mimeType].
 *
 * Mirrors DocumentsUI's `MimePredicate.mimeMatches`: an exact match, or a `type/ *` entry against
 * the same top-level type. Only the tests call this — the platform does the real matching — but
 * they need the platform's rule to tell a genuine allowlist gap from a wildcard already covering it.
 */
internal fun mimeTypeIsPickable(
    mimeType: String,
    allowed: Array<String>,
): Boolean =
    allowed.any { entry ->
        entry == mimeType ||
            (entry.endsWith("/*") && mimeType.substringBefore('/') == entry.substringBefore('/'))
    }

internal data class ExcludedDeckChoice(
    val name: String,
    val checked: Boolean,
    val discovered: Boolean,
)

/**
 * The diagnostics delivery request that still has to reach the share sheet, or null when absent.
 *
 * [DiagnosticsExportState.Ready] is a latch held until the share launch finishes or fails, not an
 * event. An Activity recreation composes `SettingsRoute` from scratch against the same retained
 * value the previous composition already acted on, so an effect keyed on that value opens a second
 * share sheet. The returned key identifies one request; the composition remembers it across
 * recreation as [launchedRequest], making the launch one-shot per request instead of one per
 * composition.
 */
internal fun diagnosticsDeliveryToLaunch(
    state: DiagnosticsExportState,
    launchedRequest: String?,
): String? =
    (state as? DiagnosticsExportState.Ready)
        ?.bundle
        ?.file
        ?.path
        ?.takeIf { it != launchedRequest }

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
    diagnosticsViewModel: DiagnosticsViewModel,
    diagnostics: TesterDiagnosticsIdentity,
    onRequestPermissions: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onInstallAnkiDroid: () -> Unit,
    onOpenAnkiDroid: () -> Unit,
    onOpenSpeechSettings: () -> Unit,
    onShareDiagnosticsBundle: (uri: String, fileName: String) -> Boolean,
    verboseLogging: Boolean,
    onVerboseLoggingChange: (Boolean) -> Unit,
    updateCheck: UpdateCheckUiState,
    onUpdateCheckEnabledChange: (Boolean) -> Unit,
    onCheckForUpdates: () -> Unit,
    onSkipUpdate: () -> Unit,
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
    val backupState by viewModel.backupState.collectAsStateWithLifecycle()
    val resources by viewModel.resourceState.collectAsStateWithLifecycle()
    val setup by setupViewModel.uiState.collectAsStateWithLifecycle()
    val diagnosticsExport by diagnosticsViewModel.state.collectAsStateWithLifecycle()
    if (!draftState.loaded) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    val dictionaryPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            setupViewModel.onCustomDictionaryPicked(uri?.toString())
        }
    val frequencyPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            setupViewModel.onFrequencyPicked(uri?.toString())
        }
    val pitchPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            setupViewModel.onPitchPicked(uri?.toString())
        }
    val audioPackPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            setupViewModel.onAudioPackPicked(uri?.toString())
        }
    val knownWordsPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            setupViewModel.onKnownWordsPicked(uri?.toString())
        }
    // One launcher per kind: the contract callback cannot receive which list was chosen.
    val blacklistPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { setupViewModel.importWordList(it.toString(), WordListKind.BLACKLIST) }
        }
    val whitelistPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { setupViewModel.importWordList(it.toString(), WordListKind.WHITELIST) }
        }
    val knownWordsExportPicker =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("text/plain"),
        ) { uri ->
            uri?.let { setupViewModel.exportKnownWords(it.toString()) }
        }
    val settingsExportPicker =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json"),
        ) { uri ->
            uri?.let { viewModel.exportSettings(it.toString()) }
        }
    val settingsImportPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { viewModel.importSettings(it.toString()) }
        }
    var launchedDeliveryRequest by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(diagnosticsExport) {
        val ready = diagnosticsExport as? DiagnosticsExportState.Ready
        if (ready == null) {
            // Idle and Failed end the request, so a later Ready is always a new share attempt.
            launchedDeliveryRequest = null
            return@LaunchedEffect
        }
        val request =
            diagnosticsDeliveryToLaunch(ready, launchedDeliveryRequest) ?: return@LaunchedEffect
        launchedDeliveryRequest = request
        diagnosticsViewModel.deliverShare(onShareDiagnosticsBundle)
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
        diagnosticsExport = diagnosticsExport,
        backupState = backupState,
        onRetrySave = viewModel::retrySave,
        onDraftChange = viewModel::updateDraft,
        onRestoreMiningDefaults = viewModel::restoreMiningDefaults,
        onRequestPermissions = onRequestPermissions,
        onOpenAppSettings = onOpenAppSettings,
        onInstallAnkiDroid = onInstallAnkiDroid,
        onOpenAnkiDroid = onOpenAnkiDroid,
        onOpenSpeechSettings = onOpenSpeechSettings,
        onShareDiagnosticsBundle = diagnosticsViewModel::export,
        onRetryDiagnosticsExport = diagnosticsViewModel::retry,
        onDismissDiagnosticsExport = diagnosticsViewModel::dismissFailure,
        onExportSettings = { settingsExportPicker.launch("anki-miner-settings.json") },
        onImportSettings = { settingsImportPicker.launch(SETTINGS_BACKUP_MIME_TYPES) },
        onDismissBackupState = viewModel::dismissBackupState,
        onReturnToActiveRun = onReturnToActiveRun,
        onAttributions = onAttributions,
        onRunSetupWizard = onRunSetupWizard,
        onManageKnownWords = onManageKnownWords,
        requestedCategory = requestedCategory,
        requestedCategoryItemIndex = requestedCategoryItemIndex,
        onCategoryRequestConsumed = onCategoryRequestConsumed,
        onImportCustom = {
            if (setupViewModel.beginCustomDictionaryPicker()) {
                dictionaryPicker.launch(CUSTOM_DICTIONARY_MIME_TYPES)
            }
        },
        onReplaceCustom = { slotId ->
            if (setupViewModel.beginCustomDictionaryReplacementPicker(slotId)) {
                dictionaryPicker.launch(CUSTOM_DICTIONARY_MIME_TYPES)
            }
        },
        onImportFrequency = {
            if (setupViewModel.beginFrequencyPicker()) {
                frequencyPicker.launch(FREQUENCY_MIME_TYPES)
            }
        },
        onImportPitch = {
            if (setupViewModel.beginPitchPicker()) {
                pitchPicker.launch(PITCH_MIME_TYPES)
            }
        },
        onImportAudioPack = {
            if (setupViewModel.beginAudioPackPicker()) {
                audioPackPicker.launch(AUDIO_PACK_MIME_TYPES)
            }
        },
        onImportKnownWords = {
            if (setupViewModel.beginKnownWordsPicker()) {
                knownWordsPicker.launch(KNOWN_WORDS_MIME_TYPES)
            }
        },
        onImportWordList = { kind ->
            when (kind) {
                WordListKind.BLACKLIST -> blacklistPicker.launch(WORD_LIST_MIME_TYPES)
                WordListKind.WHITELIST -> whitelistPicker.launch(WORD_LIST_MIME_TYPES)
            }
        },
        onExportKnownWords = { knownWordsExportPicker.launch("known_words.txt") },
        verboseLogging = verboseLogging,
        onVerboseLoggingChange = onVerboseLoggingChange,
        updateCheck = updateCheck,
        onUpdateCheckEnabledChange = onUpdateCheckEnabledChange,
        onCheckForUpdates = onCheckForUpdates,
        onSkipUpdate = onSkipUpdate,
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
    diagnosticsExport: DiagnosticsExportState,
    backupState: SettingsBackupState,
    onRetrySave: () -> Unit,
    onDraftChange: (SettingsDraft) -> Unit,
    // Boolean: a reset that the store refuses must leave the confirmation queued
    // instead of being silently dismissed.
    onRestoreMiningDefaults: () -> Boolean,
    onRequestPermissions: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onInstallAnkiDroid: () -> Unit,
    onOpenAnkiDroid: () -> Unit,
    onOpenSpeechSettings: () -> Unit,
    onShareDiagnosticsBundle: () -> Unit,
    onRetryDiagnosticsExport: () -> Unit,
    onDismissDiagnosticsExport: () -> Unit,
    onExportSettings: () -> Unit,
    onImportSettings: () -> Unit,
    onDismissBackupState: () -> Unit,
    onReturnToActiveRun: (() -> Unit)?,
    onAttributions: () -> Unit,
    onRunSetupWizard: (() -> Unit)?,
    onManageKnownWords: () -> Unit,
    requestedCategory: SettingsCategory?,
    requestedCategoryItemIndex: Int,
    onCategoryRequestConsumed: () -> Unit,
    onImportCustom: () -> Unit,
    onReplaceCustom: (String) -> Unit,
    onImportFrequency: () -> Unit,
    onImportPitch: () -> Unit,
    onImportAudioPack: () -> Unit,
    onImportKnownWords: () -> Unit,
    onImportWordList: (WordListKind) -> Unit,
    onExportKnownWords: () -> Unit,
    verboseLogging: Boolean,
    onVerboseLoggingChange: (Boolean) -> Unit,
    updateCheck: UpdateCheckUiState,
    onUpdateCheckEnabledChange: (Boolean) -> Unit,
    onCheckForUpdates: () -> Unit,
    onSkipUpdate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedCategory by rememberSaveable { mutableStateOf(SettingsCategory.ANKI) }
    val listStates = rememberSettingsCategoryListStates()
    val cardIndexRecorder = remember { SettingsCardIndexRecorder() }
    val breadcrumbs = SettingsCategory.entries.associateWith { stringResource(it.label) }
    val resolvedEntries =
        availableSettingsSearchEntries(
            entries = SETTINGS_SEARCH_INDEX,
            setup = setup,
            dynamicColorSupported = dynamicColorSupported(),
        ).map { entry ->
            val title = stringResource(entry.title)
            val detail = entry.detail?.let { stringResource(it) }.orEmpty()
            val breadcrumb = breadcrumbs.getValue(entry.category)
            ResolvedSettingsEntry(
                id = entry.id,
                category = entry.category,
                cardKey = entry.cardKey,
                title = title,
                breadcrumb = breadcrumb,
                haystack =
                    listOf(
                        normalizeSettingsText(title),
                        normalizeSettingsText(detail),
                        normalizeSettingsText(breadcrumb),
                    ).filter(String::isNotEmpty),
            )
        }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val searchResults = searchSettings(resolvedEntries, searchQuery)

    LaunchedEffect(requestedCategory, requestedCategoryItemIndex) {
        val jump =
            externalSettingsCategoryJump(
                currentSearchQuery = searchQuery,
                requestedCategory = requestedCategory,
                requestedItemIndex = requestedCategoryItemIndex,
            ) ?: return@LaunchedEffect
        // Clear stale indices and the saved query first. Normal category content records the
        // destination card only after the query-cleared layout replaces the search results.
        cardIndexRecorder.begin(jump.category)
        selectedCategory = jump.category
        searchQuery = jump.searchQuery
        val recordedTargetIndex =
            jump.targetCardKey?.let { cardKey ->
                withTimeoutOrNull(2_000) {
                    snapshotFlow { cardIndexRecorder.indexOf(jump.category, cardKey) }
                        .filterNotNull()
                        .first()
                }
            }
        val targetIndex =
            when {
                recordedTargetIndex != null -> recordedTargetIndex
                jump.targetCardKey == null -> jump.itemIndex
                else -> SettingsCardIndexRecorder.FIRST_CARD_INDEX
            }
        listStates.getValue(jump.category).scrollToItem(targetIndex)
        onCategoryRequestConsumed()
    }

    // Hosted outside the LazyColumn so it survives the target card scrolling away, and so no
    // deep-link card index shifts.
    ResourceReplaceDialog(
        pending = setup.pendingReplace,
        busy = setup.busy,
        onConfirm = setupViewModel::confirmPendingReplace,
        onDismiss = setupViewModel::dismissPendingReplace,
    )
    ResourceDeleteDialog(
        pending = setup.pendingDelete,
        busy = setup.busy,
        onConfirm = setupViewModel::confirmPendingDelete,
        onDismiss = setupViewModel::dismissPendingDelete,
    )
    AudioPackChoiceDialog(
        choices = setup.audioPackChoices,
        busy = setup.busy,
        onChoose = setupViewModel::chooseAudioPack,
        onDismiss = setupViewModel::dismissAudioPackChoice,
    )

    SettingsResetConfirmationHost(onRestoreMiningDefaults) { onRequestReset ->
        val callbacks =
            SettingsScreenCallbacks(
                onDraftChange = onDraftChange,
                onRequestReset = onRequestReset,
                resetEnabled = !saving,
                onRequestPermissions = onRequestPermissions,
                onOpenAppSettings = onOpenAppSettings,
                onInstallAnkiDroid = onInstallAnkiDroid,
                onOpenAnkiDroid = onOpenAnkiDroid,
                onOpenSpeechSettings = onOpenSpeechSettings,
                onShareDiagnosticsBundle = onShareDiagnosticsBundle,
                onRetryDiagnosticsExport = onRetryDiagnosticsExport,
                onDismissDiagnosticsExport = onDismissDiagnosticsExport,
                backupState = backupState,
                onExportSettings = onExportSettings,
                onImportSettings = onImportSettings,
                onDismissBackupState = onDismissBackupState,
                onReturnToActiveRun = onReturnToActiveRun,
                onAttributions = onAttributions,
                onRunSetupWizard = onRunSetupWizard,
                onImportCustom = onImportCustom,
                onReplaceCustom = onReplaceCustom,
                onImportFrequency = onImportFrequency,
                onImportPitch = onImportPitch,
                onImportAudioPack = onImportAudioPack,
                onImportKnownWords = onImportKnownWords,
                onImportWordList = onImportWordList,
                onExportKnownWords = onExportKnownWords,
                onManageKnownWords = onManageKnownWords,
                verboseLogging = verboseLogging,
                onVerboseLoggingChange = onVerboseLoggingChange,
                updateCheck = updateCheck,
                onUpdateCheckEnabledChange = onUpdateCheckEnabledChange,
                onCheckForUpdates = onCheckForUpdates,
                onSkipUpdate = onSkipUpdate,
            )
        SettingsSearchJumpHandler(
            entries = resolvedEntries,
            recorder = cardIndexRecorder,
            listStates = listStates,
            onSelectedCategory = { selectedCategory = it },
            onClearQuery = { searchQuery = "" },
        ) { onResultChosen ->
            SettingsCategoryLayout(
                selectedCategory = selectedCategory,
                onSelectedCategory = { category ->
                    selectedCategory = category
                },
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                results = searchResults,
                onResultChosen = onResultChosen,
                recorder = cardIndexRecorder,
                listStates = listStates,
                modifier = modifier,
                header = {
                    Column(verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related)) {
                        // SETUP is the default failure origin and what resource-startup recovery
                        // records, so it has no owning card. It renders here rather than inside the
                        // status card because that card is gated off whenever setup is healthy, and
                        // ResourceOriginFailure draws nothing when the origin does not match, so
                        // rendering it unconditionally is free.
                        ResourceOriginFailure(
                            setup,
                            setOf(ResourceFailureOrigin.SETUP),
                            setupViewModel,
                            callbacks,
                        )
                        // Only a broken required task earns header space: busy work already has the
                        // operation card and the runtime notice below, and the optional-warning state
                        // is the wizard's surface.
                        if (setup.setupNeedsAttention()) {
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
                                onImportDictionary = {
                                    selectedCategory = SettingsCategory.DICTIONARIES
                                },
                                inlineFailure = {},
                            )
                        }
                        updateCheck.available?.let { available ->
                            OutlinedCard(Modifier.fillMaxWidth()) {
                                Column(
                                    Modifier.padding(AnkiMinerTokens.Space.group),
                                    verticalArrangement =
                                        Arrangement.spacedBy(AnkiMinerTokens.Space.related),
                                ) {
                                    UpdateAvailableActions(available, onSkipUpdate)
                                }
                            }
                        }
                        // Both conditions gate controls in every category, so they live where every
                        // category can see them rather than on a tab the user may never open.
                        // The conflict notice yields to the operation card: the RESOURCE work lease is
                        // taken before activeOperation is set and released after it clears, so ungated
                        // it would sit above the live progress card for the whole of every import.
                        if (setup.operation == null) {
                            setup.runtimeWorkKind?.let { kind ->
                                if (kind == RuntimeWorkCoordinator.Kind.MINING) {
                                    RuntimeConflictNotice(
                                        text = stringResource(settingsRuntimeWorkMessage(kind)),
                                        onReturnToActiveRun = onReturnToActiveRun,
                                    )
                                } else {
                                    OutlinedCard(Modifier.fillMaxWidth()) {
                                        Text(
                                            stringResource(settingsRuntimeWorkMessage(kind)),
                                            Modifier.padding(AnkiMinerTokens.Space.group),
                                        )
                                    }
                                }
                            }
                        }
                        setup.operation?.let { operation ->
                            ResourceOperationCard(operation, setupViewModel::cancelOperation)
                        }
                        // Saving is autosaved and expected to work; only a failure needs a
                        // persistent surface, because it carries the retry action.
                        if (saveState is SettingsSaveState.Failed) {
                            SettingsSaveStatus(
                                state = saveState,
                                error = saveError?.localized(),
                                onRetry = onRetrySave,
                            )
                        }
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
                    diagnosticsExport = diagnosticsExport,
                    recorder = cardIndexRecorder,
                    callbacks = callbacks,
                )
            }
        }
    }
}

@Composable
internal fun SettingsSearchJumpHandler(
    entries: List<ResolvedSettingsEntry>,
    recorder: SettingsCardIndexRecorder,
    listStates: Map<SettingsCategory, LazyListState>,
    onSelectedCategory: (SettingsCategory) -> Unit,
    onClearQuery: () -> Unit,
    onJumpIndexResolved: (Int?) -> Unit = {},
    content: @Composable ((ResolvedSettingsEntry) -> Unit) -> Unit,
) {
    var pendingJumpId by rememberSaveable { mutableStateOf<String?>(null) }
    val pendingEntry = pendingJumpId?.let { id -> entries.firstOrNull { it.id == id } }
    LaunchedEffect(pendingJumpId, pendingEntry) {
        val requestedId = pendingJumpId ?: return@LaunchedEffect
        val entry = pendingEntry
        if (entry == null) {
            pendingJumpId = null
            return@LaunchedEffect
        }
        // A previously visited category may still have a now-stale index. Clear it first so the
        // flow below can only resume from the destination's new layout pass.
        recorder.begin(entry.category)
        onSelectedCategory(entry.category)
        onClearQuery()
        // The lazy content lambda runs during layout, which can be after this effect starts, so
        // wait for the index rather than reading it once and giving up.
        val index =
            withTimeoutOrNull(2_000) {
                snapshotFlow { recorder.indexOf(entry.category, entry.cardKey) }
                    .filterNotNull()
                    .first()
            }
        onJumpIndexResolved(index)
        if (index != null) {
            listStates.getValue(entry.category).scrollToItem(index)
            recorder.highlightedKey = entry.cardKey
            delay(HIGHLIGHT_MILLIS)
            recorder.highlightedKey = null
        }
        if (pendingJumpId == requestedId) pendingJumpId = null
    }

    content { entry -> pendingJumpId = entry.id }
}

@Composable
internal fun SettingsResetConfirmationHost(
    onRestoreMiningDefaults: () -> Boolean,
    content: @Composable ((SettingsResetAction) -> Unit) -> Unit,
) {
    var pendingActionName by rememberSaveable { mutableStateOf<String?>(null) }
    val pendingAction =
        pendingActionName?.let { saved ->
            SettingsResetAction.entries.firstOrNull { it.name == saved }
        }
    pendingAction?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingActionName = null },
            title = { Text(stringResource(settingsResetLabel(action))) },
            text = { Text(stringResource(settingsResetDescription(action))) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val next =
                            SettingsResetConfirmationState(action)
                                .confirmDispatching(onRestoreMiningDefaults)
                        pendingActionName = next.pendingAction?.name
                    },
                ) {
                    Text(stringResource(settingsResetLabel(action)))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingActionName = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
    content { action -> pendingActionName = action.name }
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
    }

@StringRes
private fun settingsResetDescription(action: SettingsResetAction): Int =
    when (action) {
        SettingsResetAction.RESTORE_MINING_DEFAULTS ->
            R.string.settings_restore_mining_defaults_confirmation
    }
