package com.ankiminer.android.ui.settings

import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.ankiminer.android.R
import com.ankiminer.android.anki.provider.platformCanNameFilesFor
import com.ankiminer.android.data.anki.AnkiSetupFailureOrigin
import com.ankiminer.android.data.anki.AnkiSetupFailureAction
import com.ankiminer.android.data.resources.InstalledResourceKind
import com.ankiminer.android.data.resources.KnownWordsFailureOperation
import com.ankiminer.android.data.resources.ResourceFailure
import com.ankiminer.android.data.resources.ResourceFailureAction
import com.ankiminer.android.data.resources.ResourceFailureOrigin
import com.ankiminer.android.data.resources.ResourceManagerState
import com.ankiminer.android.data.resources.WordListKind
import com.ankiminer.android.data.settings.AudioFormat
import com.ankiminer.android.data.settings.EngineDefaults
import com.ankiminer.android.data.settings.PitchCategoryFormat
import com.ankiminer.android.data.settings.ThemeMode
import com.ankiminer.android.data.update.AvailableUpdate
import com.ankiminer.android.data.update.UpdateCheckUiState
import com.ankiminer.android.diagnostics.DiagnosticsExportStep
import com.ankiminer.android.diagnostics.TesterDiagnosticsIdentity
import com.ankiminer.android.localization.LocalizedStringResource
import com.ankiminer.android.ui.theme.AnkiMinerTokens
import com.ankiminer.android.ui.theme.SecondaryActionButton
import com.ankiminer.android.ui.theme.SupportingText
import com.ankiminer.android.ui.theme.ThemePalettes
import com.ankiminer.android.ui.theme.actionBorder
import com.ankiminer.android.ui.theme.dynamicColorSupported
import com.ankiminer.android.ui.theme.outlinedActionButtonColors
import com.ankiminer.android.vm.DiagnosticsExportState
import com.ankiminer.android.vm.SettingsBackupState
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
    val onShareDiagnosticsBundle: () -> Unit,
    val onRetryDiagnosticsExport: () -> Unit,
    val onDismissDiagnosticsExport: () -> Unit,
    val backupState: SettingsBackupState,
    val onExportSettings: () -> Unit,
    val onImportSettings: () -> Unit,
    val onDismissBackupState: () -> Unit,
    val onReturnToActiveRun: (() -> Unit)?,
    val onAttributions: () -> Unit,
    val onRunSetupWizard: (() -> Unit)?,
    val onImportCustom: () -> Unit,
    val onReplaceCustom: (String) -> Unit,
    val onImportFrequency: () -> Unit,
    val onImportPitch: () -> Unit,
    val onImportAudioPack: () -> Unit,
    val onImportKnownWords: () -> Unit,
    val onImportWordList: (WordListKind) -> Unit,
    val onExportKnownWords: () -> Unit,
    val onManageKnownWords: () -> Unit,
    val verboseLogging: Boolean,
    val onVerboseLoggingChange: (Boolean) -> Unit,
    val updateCheck: UpdateCheckUiState,
    val onUpdateCheckEnabledChange: (Boolean) -> Unit,
    val onCheckForUpdates: () -> Unit,
    val onSkipUpdate: () -> Unit,
)

internal enum class KnownWordsFailureTarget {
    IMPORT,
    EXPORT,
}

private enum class ThemeSlot {
    LIGHT,
    DARK,
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
    diagnosticsExport: DiagnosticsExportState,
    recorder: SettingsCardIndexRecorder,
    callbacks: SettingsScreenCallbacks,
) {
    when (category) {
        SettingsCategory.ANKI ->
            ankiSettings(
                draft,
                setup,
                setupViewModel,
                recorder,
                callbacks,
            )
        SettingsCategory.MEDIA ->
            mediaSettings(
                draft,
                recorder,
                callbacks.onDraftChange,
            )
        SettingsCategory.DICTIONARIES ->
            dictionarySettings(
                draft,
                resources,
                setup,
                setupViewModel,
                recorder,
                callbacks,
            )
        SettingsCategory.AUDIO ->
            audioSettings(
                draft,
                resources,
                setup,
                setupViewModel,
                recorder,
                callbacks,
            )
        SettingsCategory.FREQUENCY ->
            frequencySettings(
                draft,
                resources,
                setup,
                setupViewModel,
                recorder,
                callbacks,
            )
        SettingsCategory.FILTERING ->
            filteringSettings(
                draft,
                resources,
                setup,
                setupViewModel,
                recorder,
                callbacks,
            )
        SettingsCategory.UI ->
            uiSettings(
                draft,
                recorder,
                callbacks,
            )
        SettingsCategory.DIAGNOSTICS ->
            diagnosticsSettings(
                setup,
                setupViewModel,
                diagnostics,
                diagnosticsExport,
                recorder,
                callbacks,
            )
    }
}

private fun LazyListScope.ankiSettings(
    draft: SettingsDraft,
    setup: SetupUiState,
    setupViewModel: SetupViewModel,
    recorder: SettingsCardIndexRecorder,
    callbacks: SettingsScreenCallbacks,
) {
    settingsCard(SettingsCategory.ANKI, recorder, "anki-deck-options") {
        SettingsSection(stringResource(R.string.settings_anki_target)) {
            SettingTextField(
                value = draft.deckName,
                onChange = { callbacks.onDraftChange(draft.copy(deckName = it)) },
                label = stringResource(R.string.settings_deck_name),
                singleLine = false,
                maxLines = 2,
                placeholder = inheritedDefault(EngineDefaults.DECK_NAME),
            )
            val choices = excludedDeckChoices(setup.availableDeckNames, draft.excludedDecks)
            CollapsibleSettingGroup(
                title = stringResource(R.string.settings_excluded_decks),
                selectedCount = choices.count { it.checked },
                totalCount = choices.size,
                // Nothing to collapse, and the only explanation is the error line inside.
                forceOpen = choices.isEmpty(),
            ) {
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
            }
            SettingTextField(
                value = draft.tags,
                onChange = { callbacks.onDraftChange(draft.copy(tags = it)) },
                label = stringResource(R.string.settings_tags),
            )
            SupportingText(stringResource(R.string.settings_tags_help))
        }
    }
    settingsCard(SettingsCategory.ANKI, recorder, "anki-target") {
        AnkiTargetCard(
            setup,
            setupViewModel::selectNoteType,
            setupViewModel::setFieldMapping,
            setupViewModel::selectCardType,
            setupViewModel::setCardTypeMarkerField,
            inlineFailure = {
                AnkiOriginFailure(
                    setup,
                    AnkiSetupFailureOrigin.TARGET,
                    setupViewModel,
                )
            },
        )
    }
    settingsCard(SettingsCategory.ANKI, recorder, "anki-recovery") {
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
        settingsCard(SettingsCategory.ANKI, recorder, "anki-operation") { AnkiOperationCard() }
    }
}

/** Internal rather than private so the instrumented tests can compose the real group. */
internal fun LazyListScope.mediaSettings(
    draft: SettingsDraft,
    recorder: SettingsCardIndexRecorder,
    onDraftChange: (SettingsDraft) -> Unit,
) {
    settingsCard(SettingsCategory.MEDIA, recorder, "media-options") {
        // Read once per composition: MimeTypeMap is a process-wide singleton and the answer cannot
        // change while the app runs.
        val avifNameable =
            remember { platformCanNameFilesFor("avif") }
        SettingsSection(stringResource(R.string.settings_media)) {
            NumericField(
                draft.audioPadding,
                { onDraftChange(draft.copy(audioPadding = it)) },
                stringResource(R.string.settings_audio_padding),
                error = validationMessage(draft, SettingsFieldKey.AUDIO_PADDING),
                imeAction = ImeAction.Next,
                placeholder = inheritedDefault(EngineDefaults.AUDIO_PADDING_SECONDS),
            )
            NumericField(
                draft.screenshotOffset,
                { onDraftChange(draft.copy(screenshotOffset = it)) },
                stringResource(R.string.settings_screenshot_offset),
                error = validationMessage(draft, SettingsFieldKey.SCREENSHOT_OFFSET),
                imeAction = ImeAction.Next,
                placeholder = inheritedDefault(EngineDefaults.SCREENSHOT_OFFSET_SECONDS),
            )
            BooleanSetting(
                label = stringResource(R.string.settings_animated_screenshots),
                checked = draft.animatedScreenshots,
                onCheckedChange = { onDraftChange(draft.copy(animatedScreenshots = it)) },
            )
            SupportingText(stringResource(R.string.settings_animated_screenshots_summary))
            // A .avif this device cannot name would be stored by AnkiDroid as .bin, so the mapper
            // sends WebP instead. Say so rather than silently producing a different format.
            if (draft.animatedScreenshots && !avifNameable) {
                SupportingText(stringResource(R.string.settings_animated_screenshots_webp_only))
            }
            BooleanSetting(
                label = stringResource(R.string.settings_animated_match_audio),
                checked = draft.animatedScreenshotMatchAudio,
                enabled = draft.animatedScreenshots,
                onCheckedChange = {
                    onDraftChange(draft.copy(animatedScreenshotMatchAudio = it))
                },
            )
            SupportingText(stringResource(R.string.settings_animated_match_audio_help))
            NumericField(
                draft.animatedScreenshotDuration,
                { onDraftChange(draft.copy(animatedScreenshotDuration = it)) },
                stringResource(R.string.settings_animated_clip_duration),
                // Match-audio derives the window from the subtitle and the audio padding, so the
                // configured length has no effect while it is on. Desktop's media panel greys the
                // same field out rather than letting it read as if it still applied.
                enabled = draft.animatedScreenshots && !draft.animatedScreenshotMatchAudio,
                error = validationMessage(draft, SettingsFieldKey.ANIMATED_SCREENSHOT_DURATION),
                imeAction = ImeAction.Next,
                modifier = Modifier.testTag(SettingsCategoryTestTags.ANIMATED_SCREENSHOT_DURATION),
                placeholder =
                    inheritedDefault(EngineDefaults.ANIMATED_SCREENSHOT_DURATION_SECONDS),
            )
            SupportingText(stringResource(R.string.settings_animated_clip_duration_help))
            NumericField(
                draft.animatedScreenshotQuality,
                { onDraftChange(draft.copy(animatedScreenshotQuality = it)) },
                stringResource(R.string.settings_animated_quality),
                integer = true,
                enabled = draft.animatedScreenshots,
                error = validationMessage(draft, SettingsFieldKey.ANIMATED_SCREENSHOT_QUALITY),
                imeAction = ImeAction.Next,
                modifier = Modifier.testTag(SettingsCategoryTestTags.ANIMATED_SCREENSHOT_QUALITY),
                placeholder = inheritedDefault(EngineDefaults.ANIMATED_SCREENSHOT_QUALITY),
            )
            SupportingText(stringResource(R.string.settings_animated_quality_help))
            NumericField(
                draft.subtitleOffset,
                { onDraftChange(draft.copy(subtitleOffset = it)) },
                stringResource(R.string.settings_subtitle_offset),
                allowNegative = true,
                error = validationMessage(draft, SettingsFieldKey.SUBTITLE_OFFSET),
                imeAction = ImeAction.Next,
                placeholder = inheritedDefault(EngineDefaults.SUBTITLE_OFFSET_SECONDS),
            )
            NumericField(
                draft.bitrate,
                { onDraftChange(draft.copy(bitrate = it)) },
                stringResource(R.string.settings_audio_bitrate),
                integer = true,
                error = validationMessage(draft, SettingsFieldKey.BITRATE),
                placeholder = inheritedDefault(EngineDefaults.AUDIO_BITRATE_KBPS),
            )
            NullableChoice(
                label = stringResource(R.string.settings_audio_format),
                value = draft.audioFormat,
                engineDefault = EngineDefaults.AUDIO_FORMAT,
                values = listOf(AudioFormat.MP3, AudioFormat.OPUS),
                optionLabel = { value ->
                    stringResource(
                        when (value) {
                            AudioFormat.MP3 -> R.string.settings_mp3
                            AudioFormat.OPUS -> R.string.settings_opus
                        },
                    )
                },
                onChange = { onDraftChange(draft.copy(audioFormat = it)) },
            )
        }
    }
    // Its own card, after media-options: no failure origin deep-links into MEDIA, so appending here
    // cannot shift the hardcoded item indices in settingsCardIndexFor.
    settingsCard(SettingsCategory.MEDIA, recorder, "subtitle-text") {
        SettingsSection(stringResource(R.string.settings_subtitle_text)) {
            NullableToggle(
                stringResource(R.string.settings_strip_annotations),
                draft.stripAnnotations,
                EngineDefaults.STRIP_SUBTITLE_ANNOTATIONS,
            ) { onDraftChange(draft.copy(stripAnnotations = it)) }
            SettingTextField(
                value = draft.subtitleRegex,
                onChange = { onDraftChange(draft.copy(subtitleRegex = it)) },
                label = stringResource(R.string.settings_subtitle_regex),
                error = validationMessage(draft, SettingsFieldKey.SUBTITLE_REGEX),
            )
            // Not an error: the engine compiles with Python's regex dialect, so a pattern this
            // platform cannot parse may still be valid there.
            if (draft.subtitleRegexWarning) {
                SupportingText(stringResource(R.string.settings_subtitle_regex_uncompilable))
            }
            SettingTextField(
                value = draft.subtitleRegexReplacement,
                onChange = { onDraftChange(draft.copy(subtitleRegexReplacement = it)) },
                label = stringResource(R.string.settings_subtitle_replacement),
                error = validationMessage(draft, SettingsFieldKey.SUBTITLE_REGEX_REPLACEMENT),
            )
            NullableToggle(
                stringResource(R.string.settings_use_subtitle_regex),
                draft.useSubtitleRegex,
                EngineDefaults.USE_SUBTITLE_REGEX_FILTER,
            ) { onDraftChange(draft.copy(useSubtitleRegex = it)) }
            Text(
                stringResource(R.string.settings_subtitle_presets),
                style = MaterialTheme.typography.titleSmall,
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related),
                verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related),
            ) {
                SUBTITLE_REGEX_PRESETS.forEach { preset ->
                    val label = stringResource(preset.label)
                    val description =
                        stringResource(
                            R.string.settings_subtitle_preset_description,
                            label,
                            preset.pattern,
                        )
                    OutlinedButton(
                        onClick = {
                            onDraftChange(
                                draft.copy(
                                    subtitleRegex =
                                        appendSubtitleRegexPreset(
                                            draft.subtitleRegex,
                                            preset.pattern,
                                        ),
                                    // Appending a pattern while the filter is off looked like the
                                    // preset did nothing. Tapping one is a request to filter, so
                                    // turn the filter on with it; the toggle stays available for
                                    // parking a pattern afterwards.
                                    useSubtitleRegex = true,
                                ),
                            )
                        },
                        border = actionBorder(enabled = true),
                        colors = outlinedActionButtonColors(),
                        modifier =
                            Modifier
                                .heightIn(min = AnkiMinerTokens.Layout.minTouchTarget)
                                .semantics { contentDescription = description },
                    ) { Text(label) }
                }
            }
        }
    }
}

private fun LazyListScope.dictionarySettings(
    draft: SettingsDraft,
    resources: ResourceManagerState,
    setup: SetupUiState,
    setupViewModel: SetupViewModel,
    recorder: SettingsCardIndexRecorder,
    callbacks: SettingsScreenCallbacks,
) {
    // One panel for every dictionary the engine may consult, in the order it consults them. The
    // catalog install cards are gone from here: the wizard still renders CatalogDictionaryCards,
    // and a permanent "install Jitendex" card on this tab was a prompt that never went away. The
    // install entries live in this panel's Add menu while the dictionary is missing.
    settingsCard(SettingsCategory.DICTIONARIES, recorder, "dictionary-sources") {
        val occupiedSlotIds =
            resources.dictionaries.filter { it.occupied }.mapTo(mutableSetOf()) { it.slotId }
        ResourceChainPanel(
            heading = stringResource(R.string.resource_panel_dictionaries_heading),
            explanation = stringResource(R.string.resource_panel_dictionaries_explanation),
            rows =
                dictionaryPanelRows(
                    chain = draft.dictionarySources,
                    installed = resources.dictionaries,
                    jishoEnabled = draft.jisho,
                    strings = dictionaryRowStrings(),
                    onChainChange = {
                        callbacks.onDraftChange(draft.copy(dictionarySources = it))
                    },
                    onJishoChange = { callbacks.onDraftChange(draft.copy(jisho = it)) },
                    onRepair = setupViewModel::installCatalogDictionary,
                    onReplace = callbacks.onReplaceCustom,
                ),
            emptyMessage = stringResource(R.string.settings_no_dictionaries),
            onMove = { id, delta ->
                callbacks.onDraftChange(
                    draft.copy(dictionarySources = draft.dictionarySources.movedResource(id, delta)),
                )
            },
            // A row with a slot behind it is a real delete and keeps its confirmation; a chain
            // entry whose slot is already gone has nothing to delete, so it is a draft edit.
            onRemove = { id ->
                if (id in occupiedSlotIds) {
                    setupViewModel.requestResourceDelete(InstalledResourceKind.DICTIONARY, id)
                } else {
                    callbacks.onDraftChange(
                        draft.copy(dictionarySources = draft.dictionarySources.withoutResource(id)),
                    )
                }
            },
            addPrimary =
                ResourcePanelAction(
                    label = stringResource(R.string.resource_panel_add_dictionary),
                    onClick = callbacks.onImportCustom,
                ),
            addMenu = dictionaryAddActions(resources, setupViewModel, callbacks),
            busy = setup.busy,
            footer = {
                ResourceOriginFailure(
                    setup,
                    setOf(
                        ResourceFailureOrigin.CATALOG_DICTIONARY,
                        ResourceFailureOrigin.CUSTOM_DICTIONARY,
                    ),
                    setupViewModel,
                    callbacks,
                )
                // The pinned Jisho row is the only network dictionary; the disclosure it carries
                // is what the Play data-safety declaration promises the user can read here.
                SupportingText(stringResource(R.string.settings_jisho_disclosure))
            },
        )
    }
    settingsCard(SettingsCategory.DICTIONARIES, recorder, "pitch-sources") {
        val installedSourceIds = resources.pitchSources.mapTo(mutableSetOf()) { it.sourceId }
        ResourceChainPanel(
            heading = stringResource(R.string.resource_panel_pitch_heading),
            explanation = stringResource(R.string.resource_panel_pitch_explanation),
            rows =
                pitchPanelRows(
                    chain = draft.pitchSources,
                    installed = resources.pitchSources,
                    strings = resourceRowStrings(),
                    onChainChange = { callbacks.onDraftChange(draft.copy(pitchSources = it)) },
                ),
            emptyMessage = stringResource(R.string.settings_pitch_not_installed),
            onMove = { id, delta ->
                callbacks.onDraftChange(
                    draft.copy(pitchSources = draft.pitchSources.movedResource(id, delta)),
                )
            },
            onRemove = { id ->
                if (id in installedSourceIds) {
                    setupViewModel.requestResourceDelete(InstalledResourceKind.PITCH, id)
                } else {
                    callbacks.onDraftChange(
                        draft.copy(pitchSources = draft.pitchSources.withoutResource(id)),
                    )
                }
            },
            addPrimary =
                ResourcePanelAction(
                    label = stringResource(R.string.resource_panel_add_pitch),
                    onClick = callbacks.onImportPitch,
                ),
            busy = setup.busy,
            footer = {
                ResourceOriginFailure(
                    setup,
                    setOf(ResourceFailureOrigin.PITCH),
                    setupViewModel,
                    callbacks,
                )
                // Belongs to the sources above it, not to a card of its own: it only decides how
                // the pitch a source supplies is written onto the card.
                NullableChoice(
                    label = stringResource(R.string.settings_pitch_format),
                    value = draft.pitchFormat,
                    engineDefault = EngineDefaults.PITCH_CATEGORY_FORMAT,
                    values = listOf(PitchCategoryFormat.JAPANESE, PitchCategoryFormat.ROMAJI),
                    optionLabel = { value ->
                        stringResource(
                            when (value) {
                                PitchCategoryFormat.JAPANESE -> R.string.settings_pitch_japanese
                                PitchCategoryFormat.ROMAJI -> R.string.settings_pitch_romaji
                            },
                        )
                    },
                    onChange = {
                        callbacks.onDraftChange(draft.copy(pitchFormat = it))
                    },
                )
            },
        )
    }
    // Conditional cards trail the deep-link targets so settingsCardIndexFor stays a table of
    // constants. Adding a conditional card ahead of dictionary-lookup, or moving one behind it,
    // silently shifts the DICTIONARY_LOOKUP index whenever that card is hidden.
    if (setup.dictionaries.any { it.isUsable }) {
        settingsCard(SettingsCategory.DICTIONARIES, recorder, "dictionary-lookup") {
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
    // No inventory card after it either: every occupied slot — broken ones included — is a row of
    // the dictionary panel above, with the same Replace and Remove actions.
    // No operation card here: the shared header renders the one ResourceOperationCard for
    // setup.operation, and a second copy on this tab meant two Cancel buttons for one operation.
}

/**
 * The Add menu of the dictionary panel: each catalog dictionary while it is missing, then the
 * Yomitan importer.
 *
 * An installed catalog dictionary is deliberately absent — re-installing a healthy one is the
 * wizard's job, and a broken one offers Repair on its own row.
 */
@Composable
private fun dictionaryAddActions(
    resources: ResourceManagerState,
    setupViewModel: SetupViewModel,
    callbacks: SettingsScreenCallbacks,
): List<ResourcePanelAction> =
    buildList {
        resources.catalogDictionaries
            .filterNot { it.installed }
            .forEach { status ->
                add(
                    ResourcePanelAction(
                        label =
                            stringResource(
                                if (status.resource.slotId == JMDICT_SLOT_ID) {
                                    R.string.resource_panel_install_jmdict
                                } else {
                                    R.string.resource_panel_install_jitendex
                                },
                            ),
                    ) { setupViewModel.installCatalogDictionary(status.resource.resourceId) },
                )
            }
        add(
            ResourcePanelAction(
                label = stringResource(R.string.resource_panel_import_yomitan_zip),
                onClick = callbacks.onImportCustom,
            ),
        )
    }

/** Row text every panel needs. Resolved here because row assembly runs outside composition. */
@Composable
private fun resourceRowStrings(): ResourceRowStrings {
    // Captured rather than pre-formatted: the count is per row and the panel formats on demand.
    val context = LocalContext.current
    return ResourceRowStrings(
        entries = { count -> context.getString(R.string.resource_panel_entries, count) },
        notInChain = stringResource(R.string.resource_panel_not_in_chain),
        missingWarning = stringResource(R.string.resource_panel_warning_missing),
        repairWarning = stringResource(R.string.resource_panel_warning_repair),
    )
}

@Composable
private fun dictionaryRowStrings(): DictionaryRowStrings =
    DictionaryRowStrings(
        rows = resourceRowStrings(),
        repairAction = stringResource(R.string.resource_panel_row_repair),
        replaceAction = stringResource(R.string.resource_panel_row_replace),
        jishoTitle = stringResource(R.string.settings_jisho),
        jishoMeta = stringResource(R.string.resource_panel_meta_online),
        jishoWarning = stringResource(R.string.resource_panel_warning_jisho),
    )

private const val JMDICT_SLOT_ID = "jmdict"

private fun LazyListScope.audioSettings(
    draft: SettingsDraft,
    resources: ResourceManagerState,
    setup: SetupUiState,
    setupViewModel: SetupViewModel,
    recorder: SettingsCardIndexRecorder,
    callbacks: SettingsScreenCallbacks,
) {
    // The whole category is one card: the pack priority list, its importer, and the reading
    // text-to-speech switch that decides what happens when no pack has the word.
    settingsCard(SettingsCategory.AUDIO, recorder, "audio-sources") {
        val installedPackIds = resources.audioPacks.mapTo(mutableSetOf()) { it.packId }
        ResourceChainPanel(
            heading = stringResource(R.string.resource_panel_audio_heading),
            explanation = stringResource(R.string.resource_panel_audio_explanation),
            rows =
                audioPanelRows(
                    chain = draft.audioPacks,
                    installed = resources.audioPacks,
                    strings = resourceRowStrings(),
                    onChainChange = { callbacks.onDraftChange(draft.copy(audioPacks = it)) },
                ),
            emptyMessage = stringResource(R.string.settings_no_audio_packs),
            onMove = { id, delta ->
                callbacks.onDraftChange(
                    draft.copy(audioPacks = draft.audioPacks.movedResource(id, delta)),
                )
            },
            onRemove = { id ->
                if (id in installedPackIds) {
                    setupViewModel.requestResourceDelete(InstalledResourceKind.AUDIO_PACK, id)
                } else {
                    callbacks.onDraftChange(
                        draft.copy(audioPacks = draft.audioPacks.withoutResource(id)),
                    )
                }
            },
            // One button, no menu: every audio source on Android is an imported local pack. The
            // online and database kinds the desktop offers are cut, not deferred.
            addPrimary =
                ResourcePanelAction(
                    label = stringResource(R.string.resource_panel_add_audio),
                    onClick = callbacks.onImportAudioPack,
                ),
            busy = setup.busy,
            footer = {
                SupportingText(stringResource(R.string.audio_pack_archive_guidance))
                ResourceOriginFailure(
                    setup,
                    setOf(ResourceFailureOrigin.AUDIO),
                    setupViewModel,
                    callbacks,
                )
                SettingsSection(
                    stringResource(R.string.resource_panel_sentence_audio_heading),
                ) {
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
            },
        )
    }
}

private fun LazyListScope.frequencySettings(
    draft: SettingsDraft,
    resources: ResourceManagerState,
    setup: SetupUiState,
    setupViewModel: SetupViewModel,
    recorder: SettingsCardIndexRecorder,
    callbacks: SettingsScreenCallbacks,
) {
    settingsCard(SettingsCategory.FREQUENCY, recorder, "frequency-sources") {
        val installedSourceIds = resources.frequencySources.mapTo(mutableSetOf()) { it.sourceId }
        ResourceChainPanel(
            heading = stringResource(R.string.resource_panel_frequency_heading),
            explanation = stringResource(R.string.resource_panel_frequency_explanation),
            rows =
                frequencyPanelRows(
                    chain = draft.frequencySources,
                    installed = resources.frequencySources,
                    strings = resourceRowStrings(),
                    onChainChange = { callbacks.onDraftChange(draft.copy(frequencySources = it)) },
                ),
            emptyMessage = stringResource(R.string.settings_no_frequency_sources),
            onMove = { id, delta ->
                callbacks.onDraftChange(
                    draft.copy(frequencySources = draft.frequencySources.movedResource(id, delta)),
                )
            },
            onRemove = { id ->
                if (id in installedSourceIds) {
                    setupViewModel.requestResourceDelete(InstalledResourceKind.FREQUENCY, id)
                } else {
                    callbacks.onDraftChange(
                        draft.copy(frequencySources = draft.frequencySources.withoutResource(id)),
                    )
                }
            },
            addPrimary =
                ResourcePanelAction(
                    label = stringResource(R.string.resource_panel_add_frequency),
                    onClick = callbacks.onImportFrequency,
                ),
            busy = setup.busy,
            footer = {
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
    recorder: SettingsCardIndexRecorder,
    callbacks: SettingsScreenCallbacks,
) {
    settingsCard(SettingsCategory.FILTERING, recorder, "filtering-options") {
        SettingsSection(stringResource(R.string.settings_filtering)) {
            NullableToggle(
                stringResource(R.string.settings_known_words),
                draft.knownWords,
                EngineDefaults.USE_KNOWN_WORDS_DATABASE,
            ) { callbacks.onDraftChange(draft.copy(knownWords = it)) }
            NullableToggle(
                stringResource(R.string.settings_exclude_hiragana),
                draft.hiragana,
                EngineDefaults.EXCLUDE_HIRAGANA_ONLY,
            ) { callbacks.onDraftChange(draft.copy(hiragana = it)) }
            NullableToggle(
                stringResource(R.string.settings_exclude_katakana),
                draft.katakana,
                EngineDefaults.EXCLUDE_KATAKANA_ONLY,
            ) { callbacks.onDraftChange(draft.copy(katakana = it)) }
            NullableToggle(
                stringResource(R.string.settings_bold_target),
                draft.boldTarget,
                EngineDefaults.BOLD_TARGET_IN_SENTENCE,
            ) { callbacks.onDraftChange(draft.copy(boldTarget = it)) }
            NullableToggle(
                stringResource(R.string.settings_deduplicate),
                draft.deduplicate,
                EngineDefaults.DEDUPLICATE_SENTENCES,
            ) { callbacks.onDraftChange(draft.copy(deduplicate = it)) }
            NullableToggle(
                stringResource(R.string.settings_i_plus_one),
                draft.iPlusOne,
                EngineDefaults.USE_I_PLUS_ONE_FILTER,
            ) { callbacks.onDraftChange(draft.copy(iPlusOne = it)) }
            NullableToggle(
                stringResource(R.string.settings_sentence_length),
                draft.sentenceLength,
                EngineDefaults.USE_SENTENCE_LENGTH_FILTER,
            ) { callbacks.onDraftChange(draft.copy(sentenceLength = it)) }
            NumericField(
                draft.maxDuration,
                { callbacks.onDraftChange(draft.copy(maxDuration = it)) },
                stringResource(R.string.settings_max_duration),
                error = validationMessage(draft, SettingsFieldKey.MAX_DURATION),
                imeAction = ImeAction.Next,
                placeholder = inheritedDefault(EngineDefaults.MAX_SENTENCE_DURATION_SECONDS),
            )
            NumericField(
                draft.maxCharacters,
                { callbacks.onDraftChange(draft.copy(maxCharacters = it)) },
                stringResource(R.string.settings_max_characters),
                integer = true,
                error = validationMessage(draft, SettingsFieldKey.MAX_CHARACTERS),
                imeAction = ImeAction.Next,
                placeholder = inheritedDefault(EngineDefaults.MAX_SENTENCE_CHARACTERS),
            )
            NumericField(
                draft.readingOccurrence,
                { callbacks.onDraftChange(draft.copy(readingOccurrence = it)) },
                stringResource(R.string.settings_reading_occurrence),
                integer = true,
                error = validationMessage(draft, SettingsFieldKey.READING_OCCURRENCE),
                imeAction = ImeAction.Next,
                placeholder = inheritedDefault(EngineDefaults.READING_MINIMUM_OCCURRENCE),
            )
            NumericField(
                draft.maxFrequency,
                { callbacks.onDraftChange(draft.copy(maxFrequency = it)) },
                stringResource(R.string.settings_max_frequency),
                integer = true,
                error = validationMessage(draft, SettingsFieldKey.MAX_FREQUENCY),
                imeAction = ImeAction.Next,
                placeholder = inheritedDefault(EngineDefaults.MAX_FREQUENCY_RANK),
            )
            NumericField(
                draft.workers,
                { callbacks.onDraftChange(draft.copy(workers = it)) },
                stringResource(R.string.settings_workers),
                integer = true,
                error = validationMessage(draft, SettingsFieldKey.WORKERS),
                placeholder = inheritedDefault(EngineDefaults.MAX_PARALLEL_WORKERS),
            )
            HorizontalDivider()
            CollapsibleSettingGroup(
                title = stringResource(R.string.settings_wordsets),
                selectedCount =
                    resources.wordsets.count { it.wordsetId in draft.enabledWordsets },
                totalCount = resources.wordsets.size,
                forceOpen = resources.wordsets.isEmpty(),
            ) {
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
                                                (draft.enabledWordsets + wordset.wordsetId)
                                                    .distinct()
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
    }
    settingsCard(SettingsCategory.FILTERING, recorder, "known-words-import") {
        KnownWordsImportCard(
            state = setup,
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
    settingsCard(SettingsCategory.FILTERING, recorder, "word-lists") {
        WordListImportCard(
            state = setup,
            blacklistEnabled = draft.useBlacklist,
            whitelistEnabled = draft.useWhitelist,
            onImport = callbacks.onImportWordList,
            onRemove = setupViewModel::removeWordList,
            onBlacklistEnabledChange = {
                callbacks.onDraftChange(draft.copy(useBlacklist = it))
            },
            onWhitelistEnabledChange = {
                callbacks.onDraftChange(draft.copy(useWhitelist = it))
            },
            inlineFailure = {
                ResourceOriginFailure(
                    setup,
                    setOf(ResourceFailureOrigin.WORD_LIST),
                    setupViewModel,
                    callbacks,
                )
            },
        )
    }
    setup.lastLocalImport?.let { imported ->
        settingsCard(SettingsCategory.FILTERING, recorder, "filtering-import-result") {
            LocalImportResultCard(imported)
        }
    }
}

private fun LazyListScope.uiSettings(
    draft: SettingsDraft,
    recorder: SettingsCardIndexRecorder,
    callbacks: SettingsScreenCallbacks,
) {
    settingsCard(SettingsCategory.UI, recorder, "ui-options") {
        var editingSlot by rememberSaveable { mutableStateOf<String?>(null) }
        val editing = editingSlot?.let(ThemeSlot::valueOf)
        SettingsSection(stringResource(R.string.settings_ui_section)) {
            Text(stringResource(R.string.settings_theme_mode))
            AdaptiveChoiceSelector(
                values = ThemeMode.entries,
                selected = draft.theme,
                label = { value ->
                    stringResource(
                        when (value) {
                            ThemeMode.LIGHT -> R.string.settings_theme_light
                            ThemeMode.DARK -> R.string.settings_theme_dark
                            ThemeMode.SYSTEM -> R.string.settings_theme_system
                        },
                    )
                },
                onSelect = { callbacks.onDraftChange(draft.copy(theme = it)) },
            )
            val themeChoicesEnabled = !(draft.dynamicColorEnabled && dynamicColorSupported())
            SecondaryActionButton(
                onClick = { editingSlot = ThemeSlot.LIGHT.name },
                modifier = Modifier.fillMaxWidth(),
                enabled = themeChoicesEnabled,
            ) {
                Text(
                    "${stringResource(R.string.settings_theme_light_choice)}: " +
                        ThemePalettes.requireByKey(draft.lightThemeKey).displayName,
                )
            }
            SecondaryActionButton(
                onClick = { editingSlot = ThemeSlot.DARK.name },
                modifier = Modifier.fillMaxWidth(),
                enabled = themeChoicesEnabled,
            ) {
                Text(
                    "${stringResource(R.string.settings_theme_dark_choice)}: " +
                        ThemePalettes.requireByKey(draft.darkThemeKey).displayName,
                )
            }
            if (dynamicColorSupported()) {
                BooleanSetting(
                    label = stringResource(R.string.settings_theme_dynamic),
                    checked = draft.dynamicColorEnabled,
                    onCheckedChange = {
                        callbacks.onDraftChange(draft.copy(dynamicColorEnabled = it))
                    },
                )
                SupportingText(stringResource(R.string.settings_theme_dynamic_supporting))
            }
            callbacks.onRunSetupWizard?.let { runWizard ->
                OutlinedButton(
                    onClick = runWizard,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.settings_run_setup_wizard))
                }
            }
        }
        editing?.let { slot ->
            ThemePickerDialog(
                title =
                    stringResource(
                        when (slot) {
                            ThemeSlot.LIGHT -> R.string.settings_theme_light_choice
                            ThemeSlot.DARK -> R.string.settings_theme_dark_choice
                        },
                    ),
                selectedKey =
                    when (slot) {
                        ThemeSlot.LIGHT -> draft.lightThemeKey
                        ThemeSlot.DARK -> draft.darkThemeKey
                    },
                onSelect = { key ->
                    callbacks.onDraftChange(
                        when (slot) {
                            ThemeSlot.LIGHT -> draft.copy(lightThemeKey = key)
                            ThemeSlot.DARK -> draft.copy(darkThemeKey = key)
                        },
                    )
                    editingSlot = null
                },
                onDismiss = { editingSlot = null },
            )
        }
    }
}

private fun LazyListScope.diagnosticsSettings(
    setup: SetupUiState,
    setupViewModel: SetupViewModel,
    diagnostics: TesterDiagnosticsIdentity,
    diagnosticsExport: DiagnosticsExportState,
    recorder: SettingsCardIndexRecorder,
    callbacks: SettingsScreenCallbacks,
) {
    settingsCard(SettingsCategory.DIAGNOSTICS, recorder, "diagnostic-runtime") {
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
    // Only shown when the tokenizer needs something. A healthy install has nothing to say here,
    // and a permanently visible "Japanese tokenizer - required" card with a Repair button reads
    // as a fault report. Repair re-downloads ~45 MiB and then no-ops when nothing is wrong.
    if (!setup.uniDicInstalled || setup.failure?.origin == ResourceFailureOrigin.UNIDIC) {
        settingsCard(SettingsCategory.DIAGNOSTICS, recorder, "unidic") {
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
    }
    settingsCard(SettingsCategory.DIAGNOSTICS, recorder, "diagnostic-logging") {
        SettingsSection(stringResource(R.string.settings_verbose_logging_section)) {
            BooleanSetting(
                label = stringResource(R.string.settings_verbose_logging),
                checked = callbacks.verboseLogging,
                onCheckedChange = callbacks.onVerboseLoggingChange,
            )
            Text(
                stringResource(R.string.settings_verbose_logging_detail),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    settingsCard(SettingsCategory.DIAGNOSTICS, recorder, "settings-backup") {
        SettingsBackupSection(
            backupState = callbacks.backupState,
            onExportSettings = callbacks.onExportSettings,
            onImportSettings = callbacks.onImportSettings,
            onDismissBackupState = callbacks.onDismissBackupState,
        )
    }
    settingsCard(SettingsCategory.DIAGNOSTICS, recorder, "update-check") {
        UpdateCheckSection(
            updateCheck = callbacks.updateCheck,
            onEnabledChange = callbacks.onUpdateCheckEnabledChange,
            onCheck = callbacks.onCheckForUpdates,
            onSkip = callbacks.onSkipUpdate,
        )
    }
    settingsCard(SettingsCategory.DIAGNOSTICS, recorder, "reset-actions") {
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
    settingsCard(SettingsCategory.DIAGNOSTICS, recorder, "tester-diagnostics") {
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
                stringResource(R.string.settings_diagnostics_bundle_privacy),
                style = MaterialTheme.typography.bodySmall,
            )
            // diagnostics.txt already carries the bounded report, and the share sheet can save
            // this same ZIP, so separate text-share and SAF-save routes would duplicate delivery.
            OutlinedButton(
                onClick = callbacks.onShareDiagnosticsBundle,
                enabled = diagnosticsExport !is DiagnosticsExportState.Working &&
                    diagnosticsExport !is DiagnosticsExportState.Ready,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_share_diagnostics_bundle))
            }
            when (diagnosticsExport) {
                DiagnosticsExportState.Idle,
                is DiagnosticsExportState.Ready,
                -> Unit
                is DiagnosticsExportState.Working ->
                    Text(
                        stringResource(diagnosticsExportStepLabel(diagnosticsExport.step)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                is DiagnosticsExportState.Failed ->
                    InlineFailureContainer(
                        message = stringResource(diagnosticsExport.message.resourceId),
                        actionLabel = stringResource(R.string.b3_retry),
                        onAction = callbacks.onRetryDiagnosticsExport,
                        onDismiss = callbacks.onDismissDiagnosticsExport,
                    )
            }
        }
    }
    settingsCard(SettingsCategory.DIAGNOSTICS, recorder, "attributions") {
        TextButton(onClick = callbacks.onAttributions) {
            Text(stringResource(R.string.settings_attributions))
        }
    }
}

@Composable
internal fun UpdateCheckSection(
    updateCheck: UpdateCheckUiState,
    onEnabledChange: (Boolean) -> Unit,
    onCheck: () -> Unit,
    onSkip: () -> Unit,
) {
    SettingsSection(stringResource(R.string.settings_update_section)) {
        BooleanSetting(
            label = stringResource(R.string.settings_update_check_enabled),
            checked = updateCheck.enabled,
            onCheckedChange = onEnabledChange,
        )
        Text(
            stringResource(R.string.settings_update_check_detail),
            style = MaterialTheme.typography.bodySmall,
        )
        val checkEnabled = updateCheck.enabled && !updateCheck.checking
        OutlinedButton(
            onClick = onCheck,
            enabled = checkEnabled,
            modifier = Modifier.fillMaxWidth(),
            colors = outlinedActionButtonColors(),
            border = actionBorder(enabled = checkEnabled),
        ) {
            Text(stringResource(R.string.settings_update_check_now))
        }
        val available = updateCheck.available
        when {
            available != null ->
                UpdateAvailableActions(available, onSkip)
            updateCheck.lastCheckFailed ->
                Text(stringResource(R.string.settings_update_failed))
            updateCheck.lastCheckedAtMillis > 0L ->
                Text(stringResource(R.string.settings_update_up_to_date))
        }
    }
}

@Composable
internal fun UpdateAvailableActions(
    available: AvailableUpdate,
    onSkip: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    Text(stringResource(R.string.settings_update_available, available.version))
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related),
        verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related),
    ) {
        TextButton(onClick = { uriHandler.openUri(available.releasePageUrl) }) {
            Text(stringResource(R.string.settings_update_view_release))
        }
        TextButton(onClick = onSkip) {
            Text(stringResource(R.string.settings_update_skip))
        }
    }
}

@Composable
internal fun SettingsBackupSection(
    backupState: SettingsBackupState,
    onExportSettings: () -> Unit,
    onImportSettings: () -> Unit,
    onDismissBackupState: () -> Unit,
) {
    SettingsSection(stringResource(R.string.settings_backup_section)) {
        Text(
            stringResource(R.string.settings_backup_detail),
            style = MaterialTheme.typography.bodySmall,
        )
        val actionsEnabled = backupState !is SettingsBackupState.Working
        OutlinedButton(
            onClick = onExportSettings,
            enabled = actionsEnabled,
            modifier = Modifier.fillMaxWidth(),
            colors = outlinedActionButtonColors(),
            border = actionBorder(enabled = actionsEnabled),
        ) {
            Text(stringResource(R.string.settings_backup_export))
        }
        OutlinedButton(
            onClick = onImportSettings,
            enabled = actionsEnabled,
            modifier = Modifier.fillMaxWidth(),
            colors = outlinedActionButtonColors(),
            border = actionBorder(enabled = actionsEnabled),
        ) {
            Text(stringResource(R.string.settings_backup_import))
        }
        when (val state = backupState) {
            SettingsBackupState.Idle, SettingsBackupState.Working -> Unit
            SettingsBackupState.Exported ->
                Text(
                    stringResource(R.string.settings_backup_exported),
                    style = MaterialTheme.typography.bodySmall,
                )
            is SettingsBackupState.Imported ->
                Text(
                    if (state.ignored + state.rejected == 0) {
                        stringResource(R.string.settings_backup_imported, state.applied)
                    } else {
                        stringResource(
                            R.string.settings_backup_imported_skipped,
                            state.applied,
                            state.ignored + state.rejected,
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            is SettingsBackupState.Failed ->
                InlineFailureContainer(
                    message = state.message.localized(),
                    actionLabel = stringResource(R.string.b3_retry),
                    onAction = onImportSettings,
                    onDismiss = onDismissBackupState,
                )
        }
    }
}

@StringRes
private fun diagnosticsExportStepLabel(step: DiagnosticsExportStep): Int =
    when (step) {
        DiagnosticsExportStep.PREPARING ->
            R.string.diagnostics_export_preparing
        DiagnosticsExportStep.BUILDING ->
            R.string.diagnostics_export_building
    }

/** Internal rather than private: the shared Settings header renders the SETUP-origin failure. */
@Composable
internal fun ResourceOriginFailure(
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
            failure.origin == ResourceFailureOrigin.WORD_LIST -> {
                { callbacks.onImportWordList(setup.wordListTarget) }
            }
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
