package com.ankiminer.android.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.ankiminer.android.R
import com.ankiminer.android.data.RuntimeWorkCoordinator
import com.ankiminer.android.data.anki.AnkiSetupFailureOrigin
import com.ankiminer.android.data.anki.AnkiSetupFailureAction
import com.ankiminer.android.data.resources.KnownWordsFailureOperation
import com.ankiminer.android.data.resources.ResourceFailure
import com.ankiminer.android.data.resources.ResourceFailureAction
import com.ankiminer.android.data.resources.ResourceFailureOrigin
import com.ankiminer.android.data.resources.ResourceManagerState
import com.ankiminer.android.data.settings.AudioFormat
import com.ankiminer.android.data.settings.PitchCategoryFormat
import com.ankiminer.android.data.settings.ThemeMode
import com.ankiminer.android.diagnostics.TesterDiagnosticsIdentity
import com.ankiminer.android.localization.LocalizedStringResource
import com.ankiminer.android.ui.mining.RuntimeConflictNotice
import com.ankiminer.android.ui.theme.AnkiMinerTokens
import com.ankiminer.android.ui.theme.SupportingText
import com.ankiminer.android.ui.theme.actionBorder
import com.ankiminer.android.ui.theme.outlinedActionButtonColors
import com.ankiminer.android.vm.SettingsDraft
import com.ankiminer.android.vm.SettingsFieldKey
import com.ankiminer.android.vm.SetupUiState
import com.ankiminer.android.vm.SetupViewModel

internal data class SettingsScreenCallbacks(
    val onDraftChange: (SettingsDraft) -> Unit,
    val onRequestReset: (SettingsResetAction) -> Unit,
    val resetEnabled: Boolean,
    val onRequestPermissions: () -> Unit,
    val onOpenAppSettings: () -> Unit,
    val onInstallAnkiDroid: () -> Unit,
    val onOpenAnkiDroid: () -> Unit,
    val onOpenSpeechSettings: () -> Unit,
    val onShareDiagnostics: () -> Unit,
    val onShareEngineLog: () -> Unit,
    val onReturnToActiveRun: (() -> Unit)?,
    val onAttributions: () -> Unit,
    val onRunSetupWizard: (() -> Unit)?,
    val onImportCustom: () -> Unit,
    val onImportFrequency: () -> Unit,
    val onImportPitch: () -> Unit,
    val onImportAudioPack: () -> Unit,
    val onImportKnownWords: () -> Unit,
    val onExportKnownWords: () -> Unit,
    val onManageKnownWords: () -> Unit,
)

internal enum class KnownWordsFailureTarget {
    IMPORT,
    EXPORT,
}

internal fun knownWordsFailureTarget(failure: ResourceFailure): KnownWordsFailureTarget? {
    if (
        failure.origin != ResourceFailureOrigin.KNOWN_WORDS ||
        failure.retry.action != ResourceFailureAction.CHOOSE_ANOTHER
    ) {
        return null
    }
    return when (failure.knownWordsOperation) {
        KnownWordsFailureOperation.IMPORT,
        KnownWordsFailureOperation.PREVIEW,
        -> KnownWordsFailureTarget.IMPORT
        KnownWordsFailureOperation.EXPORT -> KnownWordsFailureTarget.EXPORT
        null -> null
    }
}

internal fun LazyListScope.settingsCategoryContent(
    category: SettingsCategory,
    draft: SettingsDraft,
    resources: ResourceManagerState,
    setup: SetupUiState,
    setupViewModel: SetupViewModel,
    diagnostics: TesterDiagnosticsIdentity,
    callbacks: SettingsScreenCallbacks,
) {
    when (category) {
        SettingsCategory.SETUP ->
            setupSettings(
                setup,
                setupViewModel,
                callbacks,
            )
        SettingsCategory.ANKI ->
            ankiSettings(
                draft,
                setup,
                setupViewModel,
                callbacks,
            )
        SettingsCategory.MEDIA ->
            mediaSettings(
                draft,
                callbacks.onDraftChange,
            )
        SettingsCategory.DICTIONARIES ->
            dictionarySettings(
                draft,
                resources,
                setup,
                setupViewModel,
                callbacks,
            )
        SettingsCategory.AUDIO ->
            audioSettings(
                draft,
                resources,
                setup,
                setupViewModel,
                callbacks,
            )
        SettingsCategory.FREQUENCY ->
            frequencySettings(
                draft,
                resources,
                setup,
                setupViewModel,
                callbacks,
            )
        SettingsCategory.FILTERING ->
            filteringSettings(
                draft,
                resources,
                setup,
                setupViewModel,
                callbacks,
            )
        SettingsCategory.UI ->
            uiSettings(
                draft,
                callbacks,
            )
        SettingsCategory.DIAGNOSTICS ->
            diagnosticsSettings(
                setup,
                diagnostics,
                callbacks,
            )
    }
}

private fun LazyListScope.setupSettings(
    setup: SetupUiState,
    setupViewModel: SetupViewModel,
    callbacks: SettingsScreenCallbacks,
) {
    if (setup.failure?.origin == ResourceFailureOrigin.SETUP) {
        settingsCard("setup-failure") {
            SettingsSection(stringResource(R.string.b3_settings_category_setup)) {
                ResourceOriginFailure(
                    setup,
                    setOf(ResourceFailureOrigin.SETUP),
                    setupViewModel,
                    callbacks,
                )
            }
        }
    }
    setup.runtimeWorkKind?.let { kind ->
        settingsCard("runtime-conflict") {
            if (kind == RuntimeWorkCoordinator.Kind.MINING) {
                RuntimeConflictNotice(
                    text = stringResource(settingsRuntimeWorkMessage(kind)),
                    onReturnToActiveRun = callbacks.onReturnToActiveRun,
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
    settingsCard("unidic") {
        ResourceCard(
            title = stringResource(R.string.unidic_resource_title),
            description = stringResource(R.string.unidic_resource_description),
            installed = setup.uniDicInstalled,
            busy = setup.busy,
            action = setupViewModel::installUniDic,
            actionLabel =
                stringResource(
                    if (setup.uniDicInstalled) R.string.unidic_repair else R.string.unidic_install,
                ),
            inlineFailure = {
                ResourceOriginFailure(
                    setup,
                    setOf(ResourceFailureOrigin.UNIDIC),
                    setupViewModel,
                    callbacks,
                )
            },
        )
    }
    setup.operation?.let { operation ->
        settingsCard("setup-operation") {
            ResourceOperationCard(operation, setupViewModel::cancelOperation)
        }
    }
}

private fun LazyListScope.ankiSettings(
    draft: SettingsDraft,
    setup: SetupUiState,
    setupViewModel: SetupViewModel,
    callbacks: SettingsScreenCallbacks,
) {
    settingsCard("anki-deck-options") {
        SettingsSection(stringResource(R.string.settings_anki_target)) {
            SettingTextField(
                value = draft.deckName,
                onChange = { callbacks.onDraftChange(draft.copy(deckName = it)) },
                label = stringResource(R.string.settings_deck_name),
                singleLine = false,
                maxLines = 2,
            )
            Text(
                stringResource(R.string.settings_excluded_decks),
                style = MaterialTheme.typography.titleSmall,
            )
            val choices = excludedDeckChoices(setup.availableDeckNames, draft.excludedDecks)
            if (choices.isEmpty()) {
                Text(
                    stringResource(R.string.settings_no_anki_decks),
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                choices.forEach { deck ->
                    BooleanSetting(
                        label = deck.name,
                        detail =
                            if (deck.discovered) {
                                null
                            } else {
                                stringResource(R.string.settings_anki_deck_not_discovered)
                            },
                        checked = deck.checked,
                        onCheckedChange = { checked ->
                            callbacks.onDraftChange(
                                draft.copy(
                                    excludedDecks =
                                        if (checked) {
                                            (draft.excludedDecks + deck.name).distinct()
                                        } else {
                                            draft.excludedDecks - deck.name
                                        },
                                ),
                            )
                        },
                    )
                }
            }
            BooleanSetting(
                label = stringResource(R.string.settings_tags_override),
                checked = draft.tagsOverride,
                onCheckedChange = {
                    callbacks.onDraftChange(draft.copy(tagsOverride = it))
                },
            )
            SettingTextField(
                value = draft.tags,
                onChange = { callbacks.onDraftChange(draft.copy(tags = it)) },
                label = stringResource(R.string.settings_tags),
                enabled = draft.tagsOverride,
            )
        }
    }
    settingsCard("anki-target") {
        AnkiTargetCard(
            setup,
            setupViewModel::selectNoteType,
            setupViewModel::setFieldMapping,
            setupViewModel::verifyNoteType,
            inlineFailure = {
                AnkiOriginFailure(
                    setup,
                    AnkiSetupFailureOrigin.TARGET,
                    setupViewModel,
                )
            },
        )
    }
    settingsCard("anki-recovery") {
        AnkiRecoveryCard(
            state = setup,
            onRefresh = setupViewModel::refresh,
            onReconcile = setupViewModel::reconcileInterruptedWork,
            onRetryStaging = setupViewModel::retryStagingCleanup,
            onAcknowledgeMedia = setupViewModel::acknowledgeUnattachedMedia,
            onAcknowledgeUncertainMedia = setupViewModel::acknowledgeUncertainMedia,
            onResolveReview = setupViewModel::resolveAfterExternalReview,
            inlineFailure = {
                AnkiOriginFailure(
                    setup,
                    AnkiSetupFailureOrigin.RECOVERY,
                    setupViewModel,
                )
            },
        )
    }
    setup.ankiOperation?.let {
        settingsCard("anki-operation") { AnkiOperationCard() }
    }
}

private fun LazyListScope.mediaSettings(
    draft: SettingsDraft,
    onDraftChange: (SettingsDraft) -> Unit,
) {
    settingsCard("media-options") {
        SettingsSection(stringResource(R.string.settings_media)) {
            NumericField(
                draft.audioPadding,
                { onDraftChange(draft.copy(audioPadding = it)) },
                stringResource(R.string.settings_audio_padding),
                error = validationMessage(draft, SettingsFieldKey.AUDIO_PADDING),
                imeAction = ImeAction.Next,
            )
            NumericField(
                draft.screenshotOffset,
                { onDraftChange(draft.copy(screenshotOffset = it)) },
                stringResource(R.string.settings_screenshot_offset),
                error = validationMessage(draft, SettingsFieldKey.SCREENSHOT_OFFSET),
                imeAction = ImeAction.Next,
            )
            NumericField(
                draft.subtitleOffset,
                { onDraftChange(draft.copy(subtitleOffset = it)) },
                stringResource(R.string.settings_subtitle_offset),
                allowNegative = true,
                error = validationMessage(draft, SettingsFieldKey.SUBTITLE_OFFSET),
                imeAction = ImeAction.Next,
            )
            NumericField(
                draft.bitrate,
                { onDraftChange(draft.copy(bitrate = it)) },
                stringResource(R.string.settings_audio_bitrate),
                integer = true,
                error = validationMessage(draft, SettingsFieldKey.BITRATE),
            )
            Text(stringResource(R.string.settings_audio_format))
            ChoiceSegmentedButtons(
                values = listOf(null, AudioFormat.MP3, AudioFormat.OPUS),
                selected = draft.audioFormat,
                label = { value ->
                    stringResource(
                        when (value) {
                            null -> R.string.b3_settings_use_recommended_default
                            AudioFormat.MP3 -> R.string.settings_mp3
                            AudioFormat.OPUS -> R.string.settings_opus
                        },
                    )
                },
                onSelect = { onDraftChange(draft.copy(audioFormat = it)) },
            )
            Text(
                stringResource(
                    R.string.b3_settings_resolved_value,
                    stringResource(
                        if ((draft.audioFormat ?: AudioFormat.MP3) == AudioFormat.MP3) {
                            R.string.settings_mp3
                        } else {
                            R.string.settings_opus
                        },
                    ),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun LazyListScope.dictionarySettings(
    draft: SettingsDraft,
    resources: ResourceManagerState,
    setup: SetupUiState,
    setupViewModel: SetupViewModel,
    callbacks: SettingsScreenCallbacks,
) {
    settingsCard("catalog-dictionaries") {
        CatalogDictionaryCards(
            setup,
            setupViewModel::installCatalogDictionary,
        ) { resourceId ->
            val failure = setup.failure
            if (
                failure?.origin == ResourceFailureOrigin.CATALOG_DICTIONARY &&
                failure.retry.targetId == resourceId
            ) {
                ResourceOriginFailure(
                    setup,
                    setOf(ResourceFailureOrigin.CATALOG_DICTIONARY),
                    setupViewModel,
                    callbacks,
                )
            }
        }
    }
    settingsCard("custom-dictionary") {
        CustomDictionaryImportCard(
            state = setup,
            onSlotChanged = setupViewModel::setCustomSlotId,
            onReplaceChanged = setupViewModel::setCustomReplace,
            onImport = callbacks.onImportCustom,
            inlineFailure = {
                ResourceOriginFailure(
                    setup,
                    setOf(ResourceFailureOrigin.CUSTOM_DICTIONARY),
                    setupViewModel,
                    callbacks,
                )
            },
        )
    }
    settingsCard("pitch") {
        PitchImportCard(
            state = setup,
            onNameChanged = setupViewModel::setPitchSourceName,
            onFormatChanged = setupViewModel::setPitchFormat,
            onReplaceChanged = setupViewModel::setPitchReplace,
            onImport = callbacks.onImportPitch,
            inlineFailure = {
                ResourceOriginFailure(
                    setup,
                    setOf(ResourceFailureOrigin.PITCH),
                    setupViewModel,
                    callbacks,
                )
            },
        )
    }
    settingsCard("dictionary-chain") {
        SettingsSection(stringResource(R.string.settings_dictionary_chain)) {
            ResourceChainEditor(
                choices = draft.dictionarySources,
                labels =
                    resources.dictionaries
                        .filter { it.isUsable }
                        .associate { it.slotId to "${it.sourceName} (${it.entryCount})" },
                emptyMessage = stringResource(R.string.settings_no_dictionaries),
                onChange = {
                    callbacks.onDraftChange(draft.copy(dictionarySources = it))
                },
            )
            HorizontalDivider()
            BooleanSetting(
                label = stringResource(R.string.settings_jisho),
                checked = draft.jisho,
                onCheckedChange = {
                    callbacks.onDraftChange(draft.copy(jisho = it))
                },
            )
            SupportingText(stringResource(R.string.settings_jisho_disclosure))
            HorizontalDivider()
            Text(
                stringResource(R.string.settings_pitch_format),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                resources.pitchAccent?.let {
                    stringResource(
                        R.string.settings_pitch_installed,
                        it.sourceName,
                        it.entryCount,
                    )
                } ?: stringResource(R.string.settings_pitch_not_installed),
                style = MaterialTheme.typography.bodySmall,
            )
            ChoiceSegmentedButtons(
                values = listOf(null, PitchCategoryFormat.JAPANESE, PitchCategoryFormat.ROMAJI),
                selected = draft.pitchFormat,
                label = { value ->
                    stringResource(
                        when (value) {
                            null -> R.string.b3_settings_use_recommended_default
                            PitchCategoryFormat.JAPANESE -> R.string.settings_pitch_japanese
                            PitchCategoryFormat.ROMAJI -> R.string.settings_pitch_romaji
                        },
                    )
                },
                onSelect = {
                    callbacks.onDraftChange(draft.copy(pitchFormat = it))
                },
            )
            Text(
                stringResource(
                    R.string.b3_settings_resolved_value,
                    stringResource(
                        if (
                            (draft.pitchFormat ?: PitchCategoryFormat.JAPANESE) ==
                            PitchCategoryFormat.JAPANESE
                        ) {
                            R.string.settings_pitch_japanese
                        } else {
                            R.string.settings_pitch_romaji
                        },
                    ),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    settingsCard("dictionary-inventory") { DictionaryInventoryCard(setup) }
    if (setup.dictionaries.any { it.isUsable }) {
        settingsCard("dictionary-lookup") {
            DictionaryLookupCard(
                state = setup,
                onTermChanged = setupViewModel::setLookupTerm,
                onSelectSlot = setupViewModel::setLookupSlot,
                onLookup = setupViewModel::lookup,
                inlineFailure = {
                    ResourceOriginFailure(
                        setup,
                        setOf(ResourceFailureOrigin.DICTIONARY_LOOKUP),
                        setupViewModel,
                        callbacks,
                    )
                },
            )
        }
    }
    setup.operation?.let { operation ->
        settingsCard("dictionary-operation") {
            ResourceOperationCard(operation, setupViewModel::cancelOperation)
        }
    }
}

private fun LazyListScope.audioSettings(
    draft: SettingsDraft,
    resources: ResourceManagerState,
    setup: SetupUiState,
    setupViewModel: SetupViewModel,
    callbacks: SettingsScreenCallbacks,
) {
    settingsCard("audio-chain") {
        SettingsSection(stringResource(R.string.settings_audio_pack_chain)) {
            ResourceChainEditor(
                choices = draft.audioPacks,
                labels =
                    resources.audioPacks.associate {
                        it.packId to "${it.sourceName} (${it.entryCount})"
                    },
                emptyMessage = stringResource(R.string.settings_no_audio_packs),
                onChange = {
                    callbacks.onDraftChange(draft.copy(audioPacks = it))
                },
            )
        }
    }
    settingsCard("audio-import") {
        AudioPackImportCard(
            state = setup,
            onIdChanged = setupViewModel::setAudioPackId,
            onReplaceChanged = setupViewModel::setAudioPackReplace,
            onImport = callbacks.onImportAudioPack,
            inlineFailure = {
                ResourceOriginFailure(
                    setup,
                    setOf(ResourceFailureOrigin.AUDIO),
                    setupViewModel,
                    callbacks,
                )
            },
        )
    }
    settingsCard("reading-audio") {
        SettingsSection(stringResource(R.string.settings_reading_audio)) {
            BooleanSetting(
                label = stringResource(R.string.settings_reading_tts),
                checked = draft.readingTts,
                onCheckedChange = {
                    callbacks.onDraftChange(draft.copy(readingTts = it))
                },
            )
            OutlinedButton(
                onClick = callbacks.onOpenSpeechSettings,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_open_speech_services))
            }
        }
    }
}

private fun LazyListScope.frequencySettings(
    draft: SettingsDraft,
    resources: ResourceManagerState,
    setup: SetupUiState,
    setupViewModel: SetupViewModel,
    callbacks: SettingsScreenCallbacks,
) {
    settingsCard("frequency-chain") {
        SettingsSection(stringResource(R.string.settings_frequency_chain)) {
            ResourceChainEditor(
                choices = draft.frequencySources,
                labels =
                    resources.frequencySources.associate {
                        it.sourceId to "${it.sourceName} (${it.entryCount})"
                    },
                emptyMessage = stringResource(R.string.settings_no_frequency_sources),
                onChange = {
                    callbacks.onDraftChange(draft.copy(frequencySources = it))
                },
            )
        }
    }
    settingsCard("frequency-import") {
        FrequencyImportCard(
            state = setup,
            onIdChanged = setupViewModel::setFrequencySourceId,
            onNameChanged = setupViewModel::setFrequencySourceName,
            onFormatChanged = setupViewModel::setFrequencyFormat,
            onReplaceChanged = setupViewModel::setFrequencyReplace,
            onImport = callbacks.onImportFrequency,
            inlineFailure = {
                ResourceOriginFailure(
                    setup,
                    setOf(ResourceFailureOrigin.FREQUENCY),
                    setupViewModel,
                    callbacks,
                )
            },
        )
    }
}

private fun LazyListScope.filteringSettings(
    draft: SettingsDraft,
    resources: ResourceManagerState,
    setup: SetupUiState,
    setupViewModel: SetupViewModel,
    callbacks: SettingsScreenCallbacks,
) {
    settingsCard("filtering-options") {
        SettingsSection(stringResource(R.string.settings_filtering)) {
            NullableToggle(
                stringResource(R.string.settings_known_words),
                draft.knownWords,
                false,
            ) { callbacks.onDraftChange(draft.copy(knownWords = it)) }
            NullableToggle(
                stringResource(R.string.settings_exclude_hiragana),
                draft.hiragana,
                false,
            ) { callbacks.onDraftChange(draft.copy(hiragana = it)) }
            NullableToggle(
                stringResource(R.string.settings_exclude_katakana),
                draft.katakana,
                false,
            ) { callbacks.onDraftChange(draft.copy(katakana = it)) }
            NullableToggle(
                stringResource(R.string.settings_bold_target),
                draft.boldTarget,
                false,
            ) { callbacks.onDraftChange(draft.copy(boldTarget = it)) }
            NullableToggle(
                stringResource(R.string.settings_deduplicate),
                draft.deduplicate,
                true,
            ) { callbacks.onDraftChange(draft.copy(deduplicate = it)) }
            NullableToggle(
                stringResource(R.string.settings_i_plus_one),
                draft.iPlusOne,
                false,
            ) { callbacks.onDraftChange(draft.copy(iPlusOne = it)) }
            NullableToggle(
                stringResource(R.string.settings_sentence_length),
                draft.sentenceLength,
                false,
            ) { callbacks.onDraftChange(draft.copy(sentenceLength = it)) }
            NumericField(
                draft.maxDuration,
                { callbacks.onDraftChange(draft.copy(maxDuration = it)) },
                stringResource(R.string.settings_max_duration),
                error = validationMessage(draft, SettingsFieldKey.MAX_DURATION),
                imeAction = ImeAction.Next,
            )
            NumericField(
                draft.maxCharacters,
                { callbacks.onDraftChange(draft.copy(maxCharacters = it)) },
                stringResource(R.string.settings_max_characters),
                integer = true,
                error = validationMessage(draft, SettingsFieldKey.MAX_CHARACTERS),
                imeAction = ImeAction.Next,
            )
            NumericField(
                draft.readingOccurrence,
                { callbacks.onDraftChange(draft.copy(readingOccurrence = it)) },
                stringResource(R.string.settings_reading_occurrence),
                integer = true,
                error = validationMessage(draft, SettingsFieldKey.READING_OCCURRENCE),
                imeAction = ImeAction.Next,
            )
            NumericField(
                draft.maxFrequency,
                { callbacks.onDraftChange(draft.copy(maxFrequency = it)) },
                stringResource(R.string.settings_max_frequency),
                integer = true,
                error = validationMessage(draft, SettingsFieldKey.MAX_FREQUENCY),
                imeAction = ImeAction.Next,
            )
            NumericField(
                draft.workers,
                { callbacks.onDraftChange(draft.copy(workers = it)) },
                stringResource(R.string.settings_workers),
                integer = true,
                error = validationMessage(draft, SettingsFieldKey.WORKERS),
            )
            HorizontalDivider()
            Text(
                stringResource(R.string.settings_wordsets),
                style = MaterialTheme.typography.titleSmall,
            )
            if (resources.wordsets.isEmpty()) {
                Text(
                    stringResource(R.string.bundled_wordsets_unavailable),
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                resources.wordsets.forEach { wordset ->
                    BooleanSetting(
                        label = wordset.displayName,
                        detail =
                            stringResource(
                                R.string.settings_resource_entries,
                                wordset.entryCount,
                            ),
                        checked = wordset.wordsetId in draft.enabledWordsets,
                        onCheckedChange = { checked ->
                            callbacks.onDraftChange(
                                draft.copy(
                                    enabledWordsets =
                                        if (checked) {
                                            (draft.enabledWordsets + wordset.wordsetId).distinct()
                                        } else {
                                            draft.enabledWordsets - wordset.wordsetId
                                        },
                                ),
                            )
                        },
                    )
                }
            }
        }
    }
    settingsCard("known-words-import") {
        KnownWordsImportCard(
            state = setup,
            onFormatChanged = setupViewModel::setKnownWordsFormat,
            onImport = callbacks.onImportKnownWords,
            onConfirmImport = setupViewModel::confirmKnownWordsImport,
            onDismissImport = setupViewModel::dismissKnownWordsImportPreview,
            onManage = callbacks.onManageKnownWords,
            inlineFailure = {
                ResourceOriginFailure(
                    setup,
                    setOf(ResourceFailureOrigin.KNOWN_WORDS),
                    setupViewModel,
                    callbacks,
                )
            },
        )
    }
    setup.lastLocalImport?.let { imported ->
        settingsCard("filtering-import-result") { LocalImportResultCard(imported) }
    }
}

private fun LazyListScope.uiSettings(
    draft: SettingsDraft,
    callbacks: SettingsScreenCallbacks,
) {
    settingsCard("ui-options") {
        SettingsSection(stringResource(R.string.settings_ui_section)) {
            Text(stringResource(R.string.settings_theme))
            ChoiceSegmentedButtons(
                values = ThemeMode.entries,
                selected = draft.theme,
                label = { value ->
                    stringResource(
                        when (value) {
                            ThemeMode.LIGHT -> R.string.settings_theme_light
                            ThemeMode.DARK -> R.string.settings_theme_dark
                        },
                    )
                },
                onSelect = { callbacks.onDraftChange(draft.copy(theme = it)) },
            )
            callbacks.onRunSetupWizard?.let { runWizard ->
                OutlinedButton(
                    onClick = runWizard,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.settings_run_setup_wizard))
                }
            }
        }
    }
}

private fun LazyListScope.diagnosticsSettings(
    setup: SetupUiState,
    diagnostics: TesterDiagnosticsIdentity,
    callbacks: SettingsScreenCallbacks,
) {
    settingsCard("diagnostic-runtime") {
        SettingsSection(stringResource(R.string.b3_diagnostics_runtime)) {
            Text(stringResource(R.string.readiness_python, pythonStatus(setup.python)))
            Text(
                stringResource(
                    R.string.readiness_resource_recovery,
                    resourceStartupStatus(setup.resourceStartup),
                ),
            )
            Text(stringResource(R.string.b3_diagnostics_api_level, Build.VERSION.SDK_INT))
            Text(
                stringResource(
                    R.string.readiness_anki_recovery,
                    stringResource(
                        if (setup.recoveryReady) {
                            R.string.status_ready
                        } else {
                            R.string.status_action_needed
                        },
                    ),
                ),
            )
        }
    }
    settingsCard("reset-actions") {
        SettingsSection(stringResource(R.string.settings_reset_section)) {
            SettingsResetAction.entries.forEach { action ->
                OutlinedButton(
                    onClick = { callbacks.onRequestReset(action) },
                    enabled = callbacks.resetEnabled && !setup.busy,
                    modifier = Modifier.fillMaxWidth(),
                    colors = outlinedActionButtonColors(),
                    border =
                        actionBorder(
                            enabled = callbacks.resetEnabled && !setup.busy,
                        ),
                ) {
                    Text(stringResource(settingsResetLabel(action)))
                }
            }
        }
    }
    settingsCard("tester-diagnostics") {
        SettingsSection(stringResource(R.string.settings_tester_diagnostics)) {
            Text(
                stringResource(
                    R.string.settings_version_identity,
                    diagnostics.versionLabel,
                ),
            )
            Text(
                stringResource(
                    R.string.settings_source_identity,
                    diagnostics.sourceLabel,
                ),
            )
            Text(
                stringResource(R.string.settings_diagnostics_privacy),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(
                onClick = callbacks.onShareDiagnostics,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_share_diagnostics))
            }
            Text(
                stringResource(R.string.settings_engine_log_privacy),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(
                onClick = callbacks.onShareEngineLog,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_share_engine_log))
            }
        }
    }
    settingsCard("attributions") {
        TextButton(onClick = callbacks.onAttributions) {
            Text(stringResource(R.string.settings_attributions))
        }
    }
}

@Composable
private fun ResourceOriginFailure(
    setup: SetupUiState,
    origins: Set<ResourceFailureOrigin>,
    setupViewModel: SetupViewModel,
    callbacks: SettingsScreenCallbacks,
) {
    val failure = setup.failure?.takeIf { it.origin in origins } ?: return
    val targetId = failure.retry.targetId
    val action: () -> Unit =
        when {
            targetId != null -> {
                setupViewModel::retryResourceFailure
            }
            failure.origin == ResourceFailureOrigin.UNIDIC ->
                setupViewModel::retryResourceFailure
            failure.origin == ResourceFailureOrigin.CUSTOM_DICTIONARY &&
                failure.retry.action == ResourceFailureAction.CHOOSE_ANOTHER ->
                callbacks.onImportCustom
            failure.origin == ResourceFailureOrigin.PITCH -> callbacks.onImportPitch
            failure.origin == ResourceFailureOrigin.DICTIONARY_LOOKUP ->
                setupViewModel::retryResourceFailure
            failure.origin == ResourceFailureOrigin.AUDIO -> callbacks.onImportAudioPack
            failure.origin == ResourceFailureOrigin.FREQUENCY -> callbacks.onImportFrequency
            failure.origin == ResourceFailureOrigin.KNOWN_WORDS &&
                failure.retry.action == ResourceFailureAction.RESOLVE ->
                callbacks.onManageKnownWords
            failure.origin == ResourceFailureOrigin.KNOWN_WORDS &&
                failure.retry.action == ResourceFailureAction.RETRY ->
                setupViewModel::retryResourceFailure
            failure.origin == ResourceFailureOrigin.KNOWN_WORDS ->
                when (knownWordsFailureTarget(failure)) {
                    KnownWordsFailureTarget.IMPORT -> callbacks.onImportKnownWords
                    KnownWordsFailureTarget.EXPORT -> callbacks.onExportKnownWords
                    null -> setupViewModel::retryResourceFailure
                }
            else -> setupViewModel::retryResourceFailure
        }
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
        onAction = action,
        onDismiss = setupViewModel::dismissFailure,
    )
}

@Composable
private fun AnkiOriginFailure(
    setup: SetupUiState,
    origin: AnkiSetupFailureOrigin,
    setupViewModel: SetupViewModel,
) {
    val failure =
        listOfNotNull(setup.ankiFailure, setup.ankiRecoveryFailure)
            .firstOrNull { it.origin == origin }
            ?: return
    InlineFailureContainer(
        message = failure.message,
        actionLabel =
            stringResource(
                if (failure.origin == AnkiSetupFailureOrigin.RECOVERY) {
                    R.string.b3_resolve
                } else {
                    R.string.b3_retry
                },
            ),
        onAction =
            when {
                failure.origin == AnkiSetupFailureOrigin.TARGET ->
                    setupViewModel::verifyNoteType
                failure.action == AnkiSetupFailureAction.RESOLVE ->
                    setupViewModel::reconcileInterruptedWork
                else -> setupViewModel::refresh
            },
        onDismiss = setupViewModel::dismissAnkiFailure,
    )
}

@Composable
private fun validationMessage(
    draft: SettingsDraft,
    key: SettingsFieldKey,
): String? = draft.validation[key]?.localized()

@Composable
private fun LocalizedStringResource.localized(): String =
    stringResource(resourceId, *formatArguments.toTypedArray())
