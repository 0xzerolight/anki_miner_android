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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ankiminer.android.R
import com.ankiminer.android.data.RuntimeWorkCoordinator
import com.ankiminer.android.data.resources.ResourceManagerState
import com.ankiminer.android.data.settings.AppSettings
import com.ankiminer.android.data.settings.AudioFormat
import com.ankiminer.android.data.settings.PitchCategoryFormat
import com.ankiminer.android.data.settings.ThemeMode
import com.ankiminer.android.diagnostics.TesterDiagnostics
import com.ankiminer.android.vm.SetupUiState
import com.ankiminer.android.vm.SettingsViewModel
import com.ankiminer.android.vm.SettingsDraft
import com.ankiminer.android.vm.SetupViewModel

// SAF picker MIME allowlists for the resource imports. Kept broad on purpose:
// Android content providers type the same file inconsistently, so a `.zip` may
// arrive as application/zip OR application/x-zip-compressed, and a `.txt` as
// text/plain. Anything not listed here is greyed out (or returns a null URI) in
// the OpenDocument picker, which silently drops the import.
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
    diagnostics: TesterDiagnostics,
    onRequestPermissions: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onInstallAnkiDroid: () -> Unit,
    onOpenAnkiDroid: () -> Unit,
    onOpenSpeechSettings: () -> Unit,
    onShareDiagnostics: (String) -> Unit,
    onShareEngineLog: () -> Unit,
    onAttributions: () -> Unit,
    onRunSetupWizard: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(setupViewModel) { setupViewModel.refresh() }
    val draftState by viewModel.draftState.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
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
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            uri?.let { setupViewModel.exportKnownWords(it.toString()) }
        }
    SettingsScreen(
        draft = draftState.draft,
        resources = resources,
        setup = setup,
        setupViewModel = setupViewModel,
        saving = saving,
        diagnostics = diagnostics,
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
        onAttributions = onAttributions,
        onRunSetupWizard = onRunSetupWizard,
        onImportCustom = { dictionaryPicker.launch(CUSTOM_DICTIONARY_MIME_TYPES) },
        onImportFrequency = { frequencyPicker.launch(FREQUENCY_MIME_TYPES) },
        onImportPitch = { pitchPicker.launch(PITCH_MIME_TYPES) },
        onImportAudioPack = { audioPackPicker.launch(AUDIO_PACK_MIME_TYPES) },
        onImportKnownWords = { knownWordsPicker.launch(KNOWN_WORDS_MIME_TYPES) },
        onExportKnownWords = { knownWordsExportPicker.launch("known_words.txt") },
        modifier = modifier,
    )
}

/**
 * Mirrors the desktop Settings organisation: Anki, Media, Dictionaries, Audio, Frequency,
 * Filtering, UI. Immediate actions (installs, imports, recovery) run instantly through
 * [SetupViewModel]; edits to the persisted [AppSettings] fields auto-save on every change.
 */
@Composable
private fun SettingsScreen(
    draft: SettingsDraft,
    resources: ResourceManagerState,
    setup: SetupUiState,
    setupViewModel: SetupViewModel,
    saving: Boolean,
    diagnostics: TesterDiagnostics,
    onDraftChange: (SettingsDraft) -> Unit,
    onRestoreMiningDefaults: () -> Unit,
    onResetAnkiTarget: () -> Unit,
    onResetResourceChoices: () -> Unit,
    onRequestPermissions: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onInstallAnkiDroid: () -> Unit,
    onOpenAnkiDroid: () -> Unit,
    onOpenSpeechSettings: () -> Unit,
    onShareDiagnostics: (String) -> Unit,
    onShareEngineLog: () -> Unit,
    onAttributions: () -> Unit,
    onRunSetupWizard: (() -> Unit)?,
    onImportCustom: () -> Unit,
    onImportFrequency: () -> Unit,
    onImportPitch: () -> Unit,
    onImportAudioPack: () -> Unit,
    onImportKnownWords: () -> Unit,
    onExportKnownWords: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val deckName = draft.deckName
    val excludedDecks = draft.excludedDecks
    val tags = draft.tags
    val tagsOverride = draft.tagsOverride
    val audioPadding = draft.audioPadding
    val screenshotOffset = draft.screenshotOffset
    val subtitleOffset = draft.subtitleOffset
    val bitrate = draft.bitrate
    val maxDuration = draft.maxDuration
    val maxCharacters = draft.maxCharacters
    val readingOccurrence = draft.readingOccurrence
    val maxFrequency = draft.maxFrequency
    val workers = draft.workers
    val audioFormat = draft.audioFormat
    val knownWords = draft.knownWords
    val hiragana = draft.hiragana
    val katakana = draft.katakana
    val boldTarget = draft.boldTarget
    val deduplicate = draft.deduplicate
    val iPlusOne = draft.iPlusOne
    val sentenceLength = draft.sentenceLength
    val pitchFormat = draft.pitchFormat
    val theme = draft.theme
    val dictionarySources = draft.dictionarySources
    val frequencySources = draft.frequencySources
    val audioPacks = draft.audioPacks
    val enabledWordsets = draft.enabledWordsets
    val readingTts = draft.readingTts
    val jisho = draft.jisho
    val numericDraftValid = draft.numericValuesValid
    var resetConfirmation by remember { mutableStateOf(SettingsResetConfirmationState()) }

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

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.settings_title),
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(stringResource(R.string.settings_intro))
        setup.runtimeWorkKind?.let { kind ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(settingsRuntimeWorkMessage(kind)),
                    modifier =
                        Modifier
                            .padding(12.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }

        SystemStatusCard(
            state = setup,
            onRefresh = setupViewModel::refresh,
            onRequestPermissions = onRequestPermissions,
            onOpenAppSettings = onOpenAppSettings,
            onInstallAnkiDroid = onInstallAnkiDroid,
            onOpenAnkiDroid = onOpenAnkiDroid,
        )
        setup.operation?.let { operation ->
            ResourceOperationCard(operation, setupViewModel::cancelOperation)
        }

        SettingsSectionHeading(stringResource(R.string.settings_anki_target))
        SettingsSection(stringResource(R.string.settings_anki_target)) {
            SettingTextField(
                deckName,
                { onDraftChange(draft.copy(deckName = it)) },
                stringResource(R.string.settings_deck_name),
                stringResource(R.string.settings_deck_default),
            )
            Text(stringResource(R.string.settings_excluded_decks), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.settings_excluded_decks_help), style = MaterialTheme.typography.bodySmall)
            val deckChoices = excludedDeckChoices(setup.availableDeckNames, excludedDecks)
            if (deckChoices.isEmpty()) {
                Text(stringResource(R.string.settings_no_anki_decks), color = MaterialTheme.colorScheme.error)
            } else {
                deckChoices.forEach { deck ->
                    BooleanSetting(
                        label = deck.name,
                        help =
                            if (deck.discovered) {
                                ""
                            } else {
                                stringResource(R.string.settings_anki_deck_not_discovered)
                            },
                        checked = deck.checked,
                        onCheckedChange = { checked ->
                            onDraftChange(
                                draft.copy(
                                    excludedDecks =
                                        if (checked) {
                                            (excludedDecks + deck.name).distinct()
                                        } else {
                                            excludedDecks - deck.name
                                        },
                                ),
                            )
                        },
                    )
                }
            }
            BooleanSetting(
                label = stringResource(R.string.settings_tags_override),
                help = stringResource(R.string.settings_tags_override_help),
                checked = tagsOverride,
                onCheckedChange = { onDraftChange(draft.copy(tagsOverride = it)) },
            )
            SettingTextField(
                tags,
                { onDraftChange(draft.copy(tags = it)) },
                stringResource(R.string.settings_tags),
                stringResource(
                    if (tagsOverride) R.string.settings_tags_help else R.string.settings_tags_default,
                ),
                enabled = tagsOverride,
            )
        }
        AnkiTargetCard(
            setup,
            setupViewModel::selectNoteType,
            setupViewModel::setFieldMapping,
            setupViewModel::verifyNoteType,
        )
        AnkiRecoveryCard(
            state = setup,
            onRefresh = setupViewModel::refresh,
            onReconcile = setupViewModel::reconcileInterruptedWork,
            onRetryStaging = setupViewModel::retryStagingCleanup,
            onAcknowledgeMedia = setupViewModel::acknowledgeUnattachedMedia,
            onAcknowledgeUncertainMedia = setupViewModel::acknowledgeUncertainMedia,
            onResolveReview = setupViewModel::resolveAfterExternalReview,
        )
        setup.ankiOperation?.let { AnkiOperationCard() }

        SettingsSectionHeading(stringResource(R.string.settings_media))
        SettingsSection(stringResource(R.string.settings_media)) {
            NumericField(audioPadding, { onDraftChange(draft.copy(audioPadding = it)) }, stringResource(R.string.settings_audio_padding), stringResource(R.string.settings_audio_padding_default))
            NumericField(screenshotOffset, { onDraftChange(draft.copy(screenshotOffset = it)) }, stringResource(R.string.settings_screenshot_offset), stringResource(R.string.settings_screenshot_offset_default))
            NumericField(subtitleOffset, { onDraftChange(draft.copy(subtitleOffset = it)) }, stringResource(R.string.settings_subtitle_offset), stringResource(R.string.settings_subtitle_offset_default), allowNegative = true)
            NumericField(bitrate, { onDraftChange(draft.copy(bitrate = it)) }, stringResource(R.string.settings_audio_bitrate), stringResource(R.string.settings_audio_bitrate_default))
            Text(stringResource(R.string.settings_audio_format))
            ChoiceSegmentedButtons(
                values = listOf(null, AudioFormat.MP3, AudioFormat.OPUS),
                selected = audioFormat,
                label = { value ->
                    stringResource(
                        when (value) {
                            null -> R.string.settings_desktop_default
                            AudioFormat.MP3 -> R.string.settings_mp3
                            AudioFormat.OPUS -> R.string.settings_opus
                        },
                    )
                },
                onSelect = { onDraftChange(draft.copy(audioFormat = it)) },
            )
        }

        SettingsSectionHeading(stringResource(R.string.settings_dictionaries))
        ResourceCard(
            title = stringResource(R.string.unidic_resource_title),
            description = stringResource(R.string.unidic_resource_description),
            installed = setup.uniDicInstalled,
            busy = setup.busy,
            action = setupViewModel::installUniDic,
            actionLabel = stringResource(if (setup.uniDicInstalled) R.string.unidic_repair else R.string.unidic_install),
        )
        CatalogDictionaryCards(setup, setupViewModel::installCatalogDictionary)
        CustomDictionaryImportCard(
            state = setup,
            onSlotChanged = setupViewModel::setCustomSlotId,
            onReplaceChanged = setupViewModel::setCustomReplace,
            onImport = onImportCustom,
        )
        PitchImportCard(
            state = setup,
            onNameChanged = setupViewModel::setPitchSourceName,
            onFormatChanged = setupViewModel::setPitchFormat,
            onReplaceChanged = setupViewModel::setPitchReplace,
            onImport = onImportPitch,
        )
        SettingsSection(stringResource(R.string.settings_dictionary_chain)) {
            Text(stringResource(R.string.settings_dictionary_chain_help), style = MaterialTheme.typography.bodySmall)
            ResourceChainEditor(
                choices = dictionarySources,
                labels =
                    resources.dictionaries
                        .filter { it.isUsable }
                        .associate { dictionary ->
                            dictionary.slotId to "${dictionary.sourceName} (${dictionary.entryCount})"
                        },
                emptyMessage = stringResource(R.string.settings_no_dictionaries),
                onChange = { onDraftChange(draft.copy(dictionarySources = it)) },
            )
            HorizontalDivider()
            BooleanSetting(
                label = stringResource(R.string.settings_jisho),
                help = stringResource(R.string.settings_jisho_disclosure),
                checked = jisho,
                onCheckedChange = { onDraftChange(draft.copy(jisho = it)) },
            )
            HorizontalDivider()
            Text(stringResource(R.string.settings_pitch_format), style = MaterialTheme.typography.titleSmall)
            Text(
                resources.pitchAccent?.let {
                    stringResource(R.string.settings_pitch_installed, it.sourceName, it.entryCount)
                } ?: stringResource(R.string.settings_pitch_not_installed),
                style = MaterialTheme.typography.bodySmall,
            )
            ChoiceSegmentedButtons(
                values = listOf(null, PitchCategoryFormat.JAPANESE, PitchCategoryFormat.ROMAJI),
                selected = pitchFormat,
                label = { value ->
                    stringResource(
                        when (value) {
                            null -> R.string.settings_desktop_default
                            PitchCategoryFormat.JAPANESE -> R.string.settings_pitch_japanese
                            PitchCategoryFormat.ROMAJI -> R.string.settings_pitch_romaji
                        },
                    )
                },
                onSelect = { onDraftChange(draft.copy(pitchFormat = it)) },
            )
        }
        DictionaryInventoryCard(setup)
        if (setup.dictionaries.any { it.isUsable }) {
            DictionaryLookupCard(
                state = setup,
                onTermChanged = setupViewModel::setLookupTerm,
                onSelectSlot = setupViewModel::setLookupSlot,
                onLookup = setupViewModel::lookup,
            )
        }

        SettingsSectionHeading(stringResource(R.string.settings_audio_section))
        SettingsSection(stringResource(R.string.settings_audio_pack_chain)) {
            Text(stringResource(R.string.settings_audio_pack_help), style = MaterialTheme.typography.bodySmall)
            ResourceChainEditor(
                choices = audioPacks,
                labels =
                    resources.audioPacks.associate { pack ->
                        pack.packId to "${pack.sourceName} (${pack.entryCount})"
                    },
                emptyMessage = stringResource(R.string.settings_no_audio_packs),
                onChange = { onDraftChange(draft.copy(audioPacks = it)) },
            )
        }
        AudioPackImportCard(
            state = setup,
            onIdChanged = setupViewModel::setAudioPackId,
            onReplaceChanged = setupViewModel::setAudioPackReplace,
            onImport = onImportAudioPack,
        )
        SettingsSection(stringResource(R.string.settings_reading_audio)) {
            BooleanSetting(
                label = stringResource(R.string.settings_reading_tts),
                help = stringResource(R.string.settings_reading_tts_help),
                checked = readingTts,
                onCheckedChange = { onDraftChange(draft.copy(readingTts = it)) },
            )
            OutlinedButton(
                onClick = onOpenSpeechSettings,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_open_speech_services))
            }
            Text(
                stringResource(R.string.settings_tts_readiness_help),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        SettingsSectionHeading(stringResource(R.string.settings_frequency_section))
        SettingsSection(stringResource(R.string.settings_frequency_chain)) {
            Text(stringResource(R.string.settings_frequency_chain_help), style = MaterialTheme.typography.bodySmall)
            ResourceChainEditor(
                choices = frequencySources,
                labels =
                    resources.frequencySources.associate { source ->
                        source.sourceId to "${source.sourceName} (${source.entryCount})"
                    },
                emptyMessage = stringResource(R.string.settings_no_frequency_sources),
                onChange = { onDraftChange(draft.copy(frequencySources = it)) },
            )
        }
        FrequencyImportCard(
            state = setup,
            onIdChanged = setupViewModel::setFrequencySourceId,
            onNameChanged = setupViewModel::setFrequencySourceName,
            onFormatChanged = setupViewModel::setFrequencyFormat,
            onReplaceChanged = setupViewModel::setFrequencyReplace,
            onImport = onImportFrequency,
        )

        SettingsSectionHeading(stringResource(R.string.settings_filtering))
        SettingsSection(stringResource(R.string.settings_filtering)) {
            NullableToggle(stringResource(R.string.settings_known_words), knownWords, false) {
                onDraftChange(draft.copy(knownWords = it))
            }
            Text(
                stringResource(
                    R.string.settings_known_words_inventory,
                    resources.knownWords.userCount,
                    resources.knownWords.ankiCount,
                    resources.knownWords.minedCount,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            NullableToggle(stringResource(R.string.settings_exclude_hiragana), hiragana, false) { onDraftChange(draft.copy(hiragana = it)) }
            NullableToggle(stringResource(R.string.settings_exclude_katakana), katakana, false) { onDraftChange(draft.copy(katakana = it)) }
            NullableToggle(stringResource(R.string.settings_bold_target), boldTarget, false) { onDraftChange(draft.copy(boldTarget = it)) }
            NullableToggle(stringResource(R.string.settings_deduplicate), deduplicate, true) { onDraftChange(draft.copy(deduplicate = it)) }
            NullableToggle(stringResource(R.string.settings_i_plus_one), iPlusOne, false) { onDraftChange(draft.copy(iPlusOne = it)) }
            NullableToggle(stringResource(R.string.settings_sentence_length), sentenceLength, false) { onDraftChange(draft.copy(sentenceLength = it)) }
            NumericField(maxDuration, { onDraftChange(draft.copy(maxDuration = it)) }, stringResource(R.string.settings_max_duration), stringResource(R.string.settings_zero_default))
            NumericField(maxCharacters, { onDraftChange(draft.copy(maxCharacters = it)) }, stringResource(R.string.settings_max_characters), stringResource(R.string.settings_zero_default))
            NumericField(
                readingOccurrence,
                { onDraftChange(draft.copy(readingOccurrence = it)) },
                stringResource(R.string.settings_reading_occurrence),
                stringResource(R.string.settings_reading_occurrence_default),
            )
            NumericField(
                maxFrequency,
                { onDraftChange(draft.copy(maxFrequency = it)) },
                stringResource(R.string.settings_max_frequency),
                stringResource(R.string.settings_max_frequency_default),
            )
            NumericField(workers, { onDraftChange(draft.copy(workers = it)) }, stringResource(R.string.settings_workers), stringResource(R.string.settings_workers_default))
            HorizontalDivider()
            Text(stringResource(R.string.settings_wordsets), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.settings_wordsets_help), style = MaterialTheme.typography.bodySmall)
            if (resources.wordsets.isEmpty()) {
                Text(stringResource(R.string.bundled_wordsets_unavailable), color = MaterialTheme.colorScheme.error)
            } else {
                resources.wordsets.forEach { wordset ->
                    BooleanSetting(
                        label = wordset.displayName,
                        help = stringResource(R.string.settings_resource_entries, wordset.entryCount),
                        checked = wordset.wordsetId in enabledWordsets,
                        onCheckedChange = { checked ->
                            onDraftChange(
                                draft.copy(
                                    enabledWordsets =
                                        if (checked) {
                                            (enabledWordsets + wordset.wordsetId).distinct()
                                        } else {
                                            enabledWordsets - wordset.wordsetId
                                        },
                                ),
                            )
                        },
                    )
                }
            }
        }
        KnownWordsImportCard(
            state = setup,
            onFormatChanged = setupViewModel::setKnownWordsFormat,
            onImport = onImportKnownWords,
            onConfirmImport = setupViewModel::confirmKnownWordsImport,
            onDismissImport = setupViewModel::dismissKnownWordsImportPreview,
            onSearchChanged = setupViewModel::setKnownWordsSearch,
            onSearch = setupViewModel::searchKnownWords,
            onLoadMore = setupViewModel::loadMoreKnownWords,
            onRemove = setupViewModel::removeKnownWord,
            onExport = onExportKnownWords,
            onReset = setupViewModel::resetKnownWords,
        )
        setup.lastLocalImport?.let { imported -> LocalImportResultCard(imported) }

        SettingsSectionHeading(stringResource(R.string.settings_ui_section))
        SettingsSection(stringResource(R.string.settings_ui_section)) {
            Text(stringResource(R.string.settings_theme))
            ChoiceSegmentedButtons(
                values = ThemeMode.entries,
                selected = theme,
                label = { value ->
                    stringResource(
                        when (value) {
                            ThemeMode.LIGHT -> R.string.settings_theme_light
                            ThemeMode.DARK -> R.string.settings_theme_dark
                        },
                    )
                },
                onSelect = { onDraftChange(draft.copy(theme = it)) },
            )
            if (onRunSetupWizard != null) {
                OutlinedButton(
                    onClick = onRunSetupWizard,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.settings_run_setup_wizard))
                }
            }
        }

        if (!numericDraftValid) {
            Text(
                stringResource(R.string.settings_numeric_incomplete),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = MaterialTheme.colorScheme.error,
            )
        }

        SettingsSection(stringResource(R.string.settings_reset_section)) {
            SettingsResetAction.entries.forEach { action ->
                OutlinedButton(
                    onClick = { resetConfirmation = resetConfirmation.request(action) },
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(settingsResetLabel(action)))
                }
            }
        }
        SettingsSection(stringResource(R.string.settings_tester_diagnostics)) {
            Text(stringResource(R.string.settings_version_identity, diagnostics.versionLabel))
            Text(stringResource(R.string.settings_source_identity, diagnostics.sourceLabel))
            Text(
                stringResource(R.string.settings_diagnostics_privacy),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(
                onClick = { onShareDiagnostics(diagnostics.report) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_share_diagnostics))
            }
            Text(
                stringResource(R.string.settings_engine_log_privacy),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(
                onClick = onShareEngineLog,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_share_engine_log))
            }
        }
        HorizontalDivider()
        TextButton(onClick = onAttributions) { Text(stringResource(R.string.settings_attributions)) }
    }
}

@StringRes
private fun settingsRuntimeWorkMessage(kind: RuntimeWorkCoordinator.Kind): Int =
    when (kind) {
        RuntimeWorkCoordinator.Kind.MINING -> R.string.runtime_work_settings_mining_active
        RuntimeWorkCoordinator.Kind.RESOURCE -> R.string.runtime_work_settings_resource_active
        RuntimeWorkCoordinator.Kind.ANKI_SETUP -> R.string.runtime_work_settings_anki_active
    }

@StringRes
private fun settingsResetLabel(action: SettingsResetAction): Int =
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
        SettingsResetAction.RESET_ANKI_TARGET -> R.string.settings_reset_anki_target_confirmation
        SettingsResetAction.RESET_RESOURCE_CHOICES ->
            R.string.settings_reset_resource_choices_confirmation
    }
