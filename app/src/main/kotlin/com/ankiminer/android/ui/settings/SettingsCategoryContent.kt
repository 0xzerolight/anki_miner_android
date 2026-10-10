package com.ankiminer.android.ui.settings

import android.content.Context
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.ankiminer.android.R
import com.ankiminer.android.anki.provider.platformCanNameFilesFor
import com.ankiminer.android.data.anki.AnkiSetupFailureOrigin
import com.ankiminer.android.data.resources.DictionaryUpdateUiState
import com.ankiminer.android.data.resources.InstalledResourceKind
import com.ankiminer.android.data.resources.KnownWordsFailureOperation
import com.ankiminer.android.data.resources.ResourceFailure
import com.ankiminer.android.data.resources.ResourceFailureAction
import com.ankiminer.android.data.resources.ResourceFailureOrigin
import com.ankiminer.android.data.resources.ResourceManagerState
import com.ankiminer.android.data.resources.WordListKind
import com.ankiminer.android.data.settings.AnimatedScreenshotFormat
import com.ankiminer.android.data.settings.AnimatedScreenshotLimits
import com.ankiminer.android.data.settings.AudioFormat
import com.ankiminer.android.data.settings.EngineDefaults
import com.ankiminer.android.data.settings.LanguageDefaults
import com.ankiminer.android.data.settings.LanguageScope
import com.ankiminer.android.data.settings.PitchCategoryFormat
import com.ankiminer.android.data.settings.ThemeMode
import com.ankiminer.android.data.update.AvailableUpdate
import com.ankiminer.android.data.update.UpdateCheckUiState
import com.ankiminer.android.diagnostics.DiagnosticsExportStep
import com.ankiminer.android.diagnostics.TesterDiagnosticsIdentity
import com.ankiminer.android.localization.LocalizedStringResource
import com.ankiminer.android.tts.DeviceVoiceStatus
import com.ankiminer.android.tts.probeDeviceVoice
import com.ankiminer.android.ui.links.rememberExternalLinkOpener
import com.ankiminer.android.ui.theme.AnkiMinerTokens
import com.ankiminer.android.ui.theme.SecondaryActionButton
import com.ankiminer.android.ui.theme.SupportingText
import com.ankiminer.android.ui.theme.ThemePalettes
import com.ankiminer.android.ui.theme.accentTextButtonColors
import com.ankiminer.android.ui.theme.accentTextColor
import com.ankiminer.android.ui.theme.actionBorder
import com.ankiminer.android.ui.theme.disabledActionContentColor
import com.ankiminer.android.ui.theme.dynamicColorSupported
import com.ankiminer.android.vm.DiagnosticsExportState
import com.ankiminer.android.vm.FrequencyBandEnd
import com.ankiminer.android.vm.SettingsBackupOperation
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
    val onDownloadRecommended: () -> Unit,
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
    val language: LanguageSettingsActions = LanguageSettingsActions(),
    /** The active mining language; outside Japanese the Word audio card shows the device voice. */
    val miningLanguage: String = LanguageScope.JAPANESE,
    /** What the active language's unset scoped settings resolve to; their rows show these values. */
    val languageDefaults: LanguageDefaults = LanguageDefaults.JAPANESE,
    /** The Updates block under the dictionary panel. */
    val dictionaryUpdates: DictionaryUpdateUiState = DictionaryUpdateUiState(),
    val onUpdateDictionariesNow: () -> Unit = {},
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
    expansion: SettingsPanelExpansion,
    callbacks: SettingsScreenCallbacks,
    language: LanguageSettingsState = LanguageSettingsState(),
    otherLanguageSlots: OtherLanguageSlots = OtherLanguageSlots(),
) {
    when (category) {
        SettingsCategory.ANKI ->
            ankiSettings(
                draft,
                setup,
                setupViewModel,
                recorder,
                expansion,
                callbacks,
                language,
            )
        SettingsCategory.MEDIA ->
            mediaSettings(
                draft,
                recorder,
                onOpenSpeechSettings = callbacks.onOpenSpeechSettings,
                onDraftChange = callbacks.onDraftChange,
            )
        SettingsCategory.RESOURCES ->
            resourceSettings(
                draft,
                resources,
                setup,
                setupViewModel,
                recorder,
                expansion,
                callbacks,
                language,
                otherLanguageSlots,
            )
        SettingsCategory.WORD_FILTERS ->
            wordFilterSettings(
                draft,
                resources,
                setup,
                setupViewModel,
                recorder,
                callbacks,
                language,
            )
        SettingsCategory.SENTENCES ->
            sentencesSettings(
                draft,
                recorder,
                callbacks.onDraftChange,
                callbacks.languageDefaults,
            )
        SettingsCategory.LANGUAGE ->
            languageSettings(
                language = language,
                draft = draft,
                recorder = recorder,
                onDraftChange = callbacks.onDraftChange,
                actions = callbacks.language,
                inlineFailure = {
                    // A failed "Download and switch" reports here, where it was asked for.
                    ResourceOriginFailure(
                        setup,
                        setOf(ResourceFailureOrigin.RECOMMENDED_SET),
                        setupViewModel,
                        callbacks,
                        language = language,
                    )
                },
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
                language,
            )
    }
}

private fun LazyListScope.ankiSettings(
    draft: SettingsDraft,
    setup: SetupUiState,
    setupViewModel: SetupViewModel,
    recorder: SettingsCardIndexRecorder,
    expansion: SettingsPanelExpansion,
    callbacks: SettingsScreenCallbacks,
    language: LanguageSettingsState,
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
            setupViewModel::fillFieldsAutomatically,
            mappingExpanded = expansion.isExpanded("anki-target"),
            onMappingExpandedChange = { expansion.setExpanded("anki-target", it) },
            inlineFailure = {
                AnkiOriginFailure(
                    setup,
                    AnkiSetupFailureOrigin.TARGET,
                    setupViewModel,
                    callbacks,
                )
            },
            fieldRowExtras = { key ->
                AnkiFieldRowSettings(
                    key = key,
                    draft = draft,
                    onDraftChange = callbacks.onDraftChange,
                    showsToneColor = language.showsToneColor,
                    showsPitch = language.showsPitch,
                )
            },
        )
    }
    // After anki-target, so TARGET's deep-link index stays 3, and ahead of the conditional
    // operation card, which has to trail every unconditional one.
    settingsCard(SettingsCategory.ANKI, recorder, "anki-card-creation") {
        SettingsSection(stringResource(R.string.settings_card_creation)) {
            NullableToggle(
                stringResource(R.string.settings_strict_card_order),
                draft.strictCardOrder,
                EngineDefaults.STRICT_CARD_ORDER,
            ) { callbacks.onDraftChange(draft.copy(strictCardOrder = it)) }
            SupportingText(stringResource(R.string.settings_strict_card_order_help))
        }
    }
    setup.ankiOperation?.let {
        settingsCard(SettingsCategory.ANKI, recorder, "anki-operation") { AnkiOperationCard() }
    }
}

/** The field-map row each formatting setting follows, as on desktop's Cards & Anki page. */
private const val TONE_COLOR_AFTER_FIELD = "sentence_reading"
private const val PITCH_FORMAT_AFTER_FIELD = "pitch_category"

/**
 * The settings that format a mapped field, drawn right after its row in the Anki field map:
 * "Colour the reading by tone" after Sentence reading (tonal languages), the pitch category format
 * after Pitch category (languages with pitch). Desktop moved both there from pages of their own.
 *
 * Internal rather than private so the instrumented tests compose the real rows.
 */
@Composable
internal fun AnkiFieldRowSettings(
    key: String,
    draft: SettingsDraft,
    onDraftChange: (SettingsDraft) -> Unit,
    showsToneColor: Boolean,
    showsPitch: Boolean,
) {
    when {
        key == TONE_COLOR_AFTER_FIELD && showsToneColor ->
            Column(Modifier.testTag(SettingsCategoryTestTags.TONE_COLOR)) {
                // Off unless set: the engine leaves readings uncoloured by default.
                NullableToggle(
                    stringResource(R.string.settings_reading_tone_color),
                    draft.readingToneColor,
                    false,
                ) { onDraftChange(draft.copy(readingToneColor = it)) }
            }
        key == PITCH_FORMAT_AFTER_FIELD && showsPitch ->
            Column(Modifier.testTag(SettingsCategoryTestTags.PITCH_FORMAT)) {
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
                    onChange = { onDraftChange(draft.copy(pitchFormat = it)) },
                )
                SupportingText(stringResource(R.string.settings_pitch_format_help))
            }
    }
}

/** Card keys of the Media tab: desktop's two Card Media sections. */
internal const val MEDIA_SENTENCE_AUDIO_KEY = "media-sentence-audio"
internal const val MEDIA_SCREENSHOT_KEY = "media-screenshot"

/**
 * Desktop's Card Media page: Sentence audio (format, bitrate, padding, text-to-speech, then the
 * Android-only subtitle offset) and Screenshot (offset, then the animated clip: format, length,
 * size).
 *
 * No failure origin deep-links here. Internal rather than private so the instrumented tests can
 * compose the real group.
 */
internal fun LazyListScope.mediaSettings(
    draft: SettingsDraft,
    recorder: SettingsCardIndexRecorder,
    onOpenSpeechSettings: () -> Unit = {},
    onDraftChange: (SettingsDraft) -> Unit,
) {
    settingsCard(SettingsCategory.MEDIA, recorder, MEDIA_SENTENCE_AUDIO_KEY) {
        SettingsSection(stringResource(R.string.settings_media_sentence_audio)) {
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
            NumericField(
                draft.bitrate,
                { onDraftChange(draft.copy(bitrate = it)) },
                stringResource(R.string.settings_audio_bitrate),
                integer = true,
                error = validationMessage(draft, SettingsFieldKey.BITRATE),
                imeAction = ImeAction.Next,
                placeholder = inheritedDefault(EngineDefaults.AUDIO_BITRATE_KBPS),
            )
            NumericField(
                draft.audioPadding,
                { onDraftChange(draft.copy(audioPadding = it)) },
                stringResource(R.string.settings_audio_padding),
                error = validationMessage(draft, SettingsFieldKey.AUDIO_PADDING),
                imeAction = ImeAction.Next,
                placeholder = inheritedDefault(EngineDefaults.AUDIO_PADDING_SECONDS),
            )
            // Desktop's Text-to-speech row, moved here from the word-audio sources. Android speaks
            // with the device's offline voice, so the row is on or off rather than a list of web
            // voices.
            BooleanSetting(
                label = stringResource(R.string.settings_reading_tts),
                checked = draft.readingTts,
                onCheckedChange = { onDraftChange(draft.copy(readingTts = it)) },
            )
            SupportingText(stringResource(R.string.settings_reading_tts_help))
            // Only with text-to-speech on: the button has nothing to set up otherwise.
            if (draft.readingTts) {
                SecondaryActionButton(
                    onClick = onOpenSpeechSettings,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.settings_open_speech_services))
                }
            }
            NumericField(
                draft.subtitleOffset,
                { onDraftChange(draft.copy(subtitleOffset = it)) },
                stringResource(R.string.settings_subtitle_offset),
                allowNegative = true,
                error = validationMessage(draft, SettingsFieldKey.SUBTITLE_OFFSET),
                placeholder = inheritedDefault(EngineDefaults.SUBTITLE_OFFSET_SECONDS),
            )
        }
    }
    settingsCard(SettingsCategory.MEDIA, recorder, MEDIA_SCREENSHOT_KEY) {
        // Read once per composition: MimeTypeMap is a process-wide singleton and the answer cannot
        // change while the app runs.
        val avifNameable =
            remember { platformCanNameFilesFor("avif") }
        SettingsSection(stringResource(R.string.settings_media_screenshot)) {
            NumericField(
                draft.screenshotOffset,
                { onDraftChange(draft.copy(screenshotOffset = it)) },
                stringResource(R.string.settings_screenshot_offset),
                error = validationMessage(draft, SettingsFieldKey.SCREENSHOT_OFFSET),
                placeholder = inheritedDefault(EngineDefaults.SCREENSHOT_OFFSET_SECONDS),
            )
            BooleanSetting(
                label = stringResource(R.string.settings_animated_screenshots),
                checked = draft.animatedScreenshots,
                onCheckedChange = { onDraftChange(draft.copy(animatedScreenshots = it)) },
            )
            SupportingText(stringResource(R.string.settings_animated_screenshots_summary))
            // The tuning rows stay visible and go disabled while the feature is off, as on desktop.
            AnimatedFormatChoice(draft, onDraftChange, avifNameable)
            ClipLengthField(draft, onDraftChange)
            SupportingText(stringResource(R.string.settings_animated_clip_duration_help))
            AnimatedSizeChoice(draft, onDraftChange)
            SupportingText(stringResource(R.string.settings_animated_quality_help))
        }
    }
}

/**
 * Desktop's Animated Format combo. Unset keeps the choice made before this was a setting: AVIF
 * wherever the device can name it. A `.avif` this device cannot name would be stored by AnkiDroid
 * as `.bin`, so there AVIF is offered disabled, with the reason, and WebP is what the run sends.
 */
@Composable
private fun AnimatedFormatChoice(
    draft: SettingsDraft,
    onDraftChange: (SettingsDraft) -> Unit,
    avifNameable: Boolean,
) {
    NullableChoice(
        label = stringResource(R.string.settings_animated_format),
        value = draft.animatedScreenshotFormat?.takeIf { avifNameable || it != AnimatedScreenshotFormat.AVIF },
        engineDefault = if (avifNameable) AnimatedScreenshotFormat.AVIF else AnimatedScreenshotFormat.WEBP,
        values = listOf(AnimatedScreenshotFormat.AVIF, AnimatedScreenshotFormat.WEBP),
        optionLabel = { value ->
            stringResource(
                when (value) {
                    AnimatedScreenshotFormat.AVIF -> R.string.settings_animated_format_avif
                    AnimatedScreenshotFormat.WEBP -> R.string.settings_animated_format_webp
                },
            )
        },
        onChange = { onDraftChange(draft.copy(animatedScreenshotFormat = it)) },
        enabled = draft.animatedScreenshots,
        modifier = Modifier.testTag(SettingsCategoryTestTags.ANIMATED_FORMAT),
        optionEnabled = { it != AnimatedScreenshotFormat.AVIF || avifNameable },
    )
    SupportingText(stringResource(R.string.settings_animated_format_help))
    if (draft.animatedScreenshots && !avifNameable) {
        SupportingText(stringResource(R.string.settings_animated_screenshots_webp_only))
    }
}

/**
 * Desktop's one Clip length stepper: half-second steps whose lowest value, "Same as sentence
 * audio", is match-audio. [ClipLengthStepper] keeps the length the user had set across a trip down
 * to that value and back.
 */
@Composable
private fun ClipLengthField(
    draft: SettingsDraft,
    onDraftChange: (SettingsDraft) -> Unit,
) {
    val stepper = remember { ClipLengthStepper() }
    val length = draft.clipLength
    val enabled = draft.animatedScreenshots
    val locale = currentUiLocale()
    val value =
        if (length.sameAsAudio) {
            stringResource(R.string.settings_animated_match_audio)
        } else {
            stringResource(
                R.string.settings_animated_clip_length_seconds,
                // The Android locale's decimal separator, as every other number on the page.
                String.format(locale, "%.1f", length.seconds),
            )
        }
    Column(
        Modifier
            .fillMaxWidth()
            .testTag(SettingsCategoryTestTags.ANIMATED_SCREENSHOT_DURATION),
        verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.line),
    ) {
        Text(
            stringResource(R.string.settings_animated_clip_duration),
            style = MaterialTheme.typography.titleSmall,
            color = if (enabled) Color.Unspecified else MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_CONTENT_ALPHA),
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ClipLengthStepButton(
                icon = R.drawable.ic_step_down,
                description = stringResource(R.string.settings_animated_clip_length_shorter),
                testTag = SettingsCategoryTestTags.ANIMATED_CLIP_SHORTER,
                enabled = enabled && !length.sameAsAudio,
            ) { onDraftChange(draft.withClipLength(stepper.step(length, up = false))) }
            Text(
                value,
                modifier =
                    Modifier
                        .weight(1f)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                textAlign = TextAlign.Center,
                color = if (enabled) Color.Unspecified else MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_CONTENT_ALPHA),
            )
            ClipLengthStepButton(
                icon = R.drawable.ic_step_up,
                description = stringResource(R.string.settings_animated_clip_length_longer),
                testTag = SettingsCategoryTestTags.ANIMATED_CLIP_LONGER,
                enabled =
                    enabled &&
                        (length.sameAsAudio || length.seconds < AnimatedScreenshotLimits.CLIP_DURATION_SECONDS.endInclusive),
            ) { onDraftChange(draft.withClipLength(stepper.step(length, up = true))) }
        }
    }
}

@Composable
private fun ClipLengthStepButton(
    @DrawableRes icon: Int,
    description: String,
    testTag: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    OutlinedIconButton(
        onClick = onClick,
        modifier =
            Modifier
                .minimumInteractiveComponentSize()
                .testTag(testTag)
                .semantics { contentDescription = description },
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        colors =
            IconButtonDefaults.outlinedIconButtonColors(
                contentColor = accentTextColor(),
                disabledContentColor = disabledActionContentColor(),
            ),
        border = actionBorder(enabled = enabled),
    ) {
        Icon(painter = painterResource(icon), contentDescription = null)
    }
}

/**
 * Desktop's Size combo: Small, Balanced and High set frame rate, height and quality together. A
 * stored triple matching none shows as Custom with its values and stays until a preset is picked;
 * Custom cannot be picked back.
 */
@Composable
private fun AnimatedSizeChoice(
    draft: SettingsDraft,
    onDraftChange: (SettingsDraft) -> Unit,
) {
    val size = draft.animatedSize
    val options =
        buildList {
            AnimatedSizePreset.entries.forEach { preset ->
                add(preset.name to stringResource(animatedSizeLabel(preset)))
            }
            if (size is AnimatedSize.Custom) {
                add(
                    CUSTOM_SIZE to
                        stringResource(R.string.settings_animated_size_custom, size.fps, size.height, size.quality),
                )
            }
        }
    SettingsDropdown(
        label = stringResource(R.string.settings_animated_size),
        options = options,
        selected =
            when (size) {
                is AnimatedSize.Preset -> size.preset.name
                is AnimatedSize.Custom -> CUSTOM_SIZE
            },
        onSelect = { picked ->
            AnimatedSizePreset.entries
                .firstOrNull { it.name == picked }
                ?.takeIf { size != AnimatedSize.Preset(it) }
                ?.let { onDraftChange(draft.withAnimatedSize(it)) }
        },
        isOptionEnabled = { it != CUSTOM_SIZE },
        enabled = draft.animatedScreenshots,
        modifier = Modifier.testTag(SettingsCategoryTestTags.ANIMATED_SIZE),
    )
}

private const val CUSTOM_SIZE = "custom"

@StringRes
private fun animatedSizeLabel(preset: AnimatedSizePreset): Int =
    when (preset) {
        AnimatedSizePreset.SMALL -> R.string.settings_animated_size_small
        AnimatedSizePreset.BALANCED -> R.string.settings_animated_size_balanced
        AnimatedSizePreset.HIGH -> R.string.settings_animated_size_high
    }

/**
 * What the example sentence is and looks like, mirroring desktop's Sentences page: subtitle text
 * cleanup first, then the sentence rule, the length caps and the sentence formatting rows.
 *
 * No failure origin deep-links here. Internal rather than private so the instrumented tests can
 * compose the real group.
 */
internal fun LazyListScope.sentencesSettings(
    draft: SettingsDraft,
    recorder: SettingsCardIndexRecorder,
    onDraftChange: (SettingsDraft) -> Unit,
    inherited: LanguageDefaults = LanguageDefaults.JAPANESE,
) {
    settingsCard(SettingsCategory.SENTENCES, recorder, "subtitle-text") {
        SettingsSection(stringResource(R.string.settings_subtitle_text)) {
            // Desktop's one cleanup box (D15 extension): its state is derived from the filter toggle
            // and the pattern, so nothing new is stored. Partly checked means the user's own
            // pattern is in use; a click adds every built-in piece to it.
            TriStateSetting(
                label = stringResource(R.string.settings_subtitle_cleanup),
                state = draft.subtitleCleanupState(inherited),
                onClick = { onDraftChange(draft.withSubtitleCleanupClicked(inherited)) },
                modifier = Modifier.testTag(SettingsCategoryTestTags.SUBTITLE_CLEANUP),
            )
            SupportingText(stringResource(R.string.settings_subtitle_cleanup_help))
            val pieces = subtitleCleanupPieces(inherited.subtitleRegexFilter)
            SettingsDisclosure(
                title = stringResource(R.string.settings_subtitle_edit_pattern),
                // Open from the start when the pattern is the user's own, as desktop opens it.
                initiallyExpanded = subtitlePatternIsCustom(draft.effectiveSubtitleRegex(inherited), pieces),
                // A rejected pattern blocks every settings write, so its field must stay in view.
                forceOpen =
                    SettingsFieldKey.SUBTITLE_REGEX in draft.validation ||
                        SettingsFieldKey.SUBTITLE_REGEX_REPLACEMENT in draft.validation ||
                        draft.subtitleRegexWarning,
            ) {
                SubtitlePatternFields(draft, onDraftChange, inherited)
            }
        }
    }
    settingsCard(SettingsCategory.SENTENCES, recorder, "sentence-options") {
        SettingsSection(stringResource(R.string.settings_sentence_options)) {
            SentenceRuleChoice(draft, onDraftChange)
            // No master toggle: each cap is off at 0, which its help line says.
            NumericField(
                draft.maxDuration,
                { onDraftChange(draft.copy(maxDuration = it)) },
                stringResource(R.string.settings_max_duration),
                error = validationMessage(draft, SettingsFieldKey.MAX_DURATION),
                imeAction = ImeAction.Next,
                placeholder = inheritedDefault(EngineDefaults.MAX_SENTENCE_DURATION_SECONDS),
            )
            SupportingText(stringResource(R.string.settings_max_duration_help))
            NumericField(
                draft.maxCharacters,
                { onDraftChange(draft.copy(maxCharacters = it)) },
                stringResource(R.string.settings_max_characters),
                integer = true,
                error = validationMessage(draft, SettingsFieldKey.MAX_CHARACTERS),
                placeholder = inheritedDefault(EngineDefaults.MAX_SENTENCE_CHARACTERS),
            )
            SupportingText(stringResource(R.string.settings_max_characters_help))
            // Android-only: it gates the Video tab's second subtitle picker and never reaches the
            // engine snapshot, so it has no engine default to inherit.
            BooleanSetting(
                label = stringResource(R.string.settings_secondary_subtitle),
                checked = draft.secondarySubtitleEnabled,
                onCheckedChange = { onDraftChange(draft.copy(secondarySubtitleEnabled = it)) },
            )
            SupportingText(stringResource(R.string.settings_secondary_subtitle_help))
            NullableToggle(
                stringResource(R.string.settings_merge_incomplete_cues),
                draft.mergeIncompleteCues,
                EngineDefaults.MERGE_INCOMPLETE_CUES,
            ) { onDraftChange(draft.copy(mergeIncompleteCues = it)) }
            SupportingText(stringResource(R.string.settings_merge_incomplete_cues_help))
            NullableToggle(
                stringResource(R.string.settings_bold_target),
                draft.boldTarget,
                EngineDefaults.BOLD_TARGET_IN_SENTENCE,
            ) { onDraftChange(draft.copy(boldTarget = it)) }
        }
    }
}

/** The raw pattern, its replacement and the preset buttons, behind "Edit the pattern (advanced)". */
@Composable
private fun SubtitlePatternFields(
    draft: SettingsDraft,
    onDraftChange: (SettingsDraft) -> Unit,
    inherited: LanguageDefaults,
) {
    SettingTextField(
        value = draft.subtitleRegex,
        onChange = { onDraftChange(draft.copy(subtitleRegex = it)) },
        label = stringResource(R.string.settings_subtitle_regex),
        error = validationMessage(draft, SettingsFieldKey.SUBTITLE_REGEX),
        // An empty field runs the language's own pattern, so say which one.
        placeholder =
            inherited.subtitleRegexFilter.takeIf(String::isNotEmpty)?.let(::inheritedDefault),
    )
    // Not an error: the engine compiles with Python's regex dialect, so a pattern this platform
    // cannot parse may still be valid there.
    if (draft.subtitleRegexWarning) {
        SupportingText(stringResource(R.string.settings_subtitle_regex_uncompilable))
    }
    SettingTextField(
        value = draft.subtitleRegexReplacement,
        onChange = { onDraftChange(draft.copy(subtitleRegexReplacement = it)) },
        label = stringResource(R.string.settings_subtitle_replacement),
        error = validationMessage(draft, SettingsFieldKey.SUBTITLE_REGEX_REPLACEMENT),
    )
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
            SecondaryActionButton(
                onClick = {
                    onDraftChange(
                        draft.copy(
                            subtitleRegex =
                                appendSubtitleRegexPreset(
                                    draft.subtitleRegex,
                                    preset.pattern,
                                ),
                            // Appending a pattern while the filter is off looked like the preset
                            // did nothing. Tapping one is a request to filter, so turn the filter
                            // on with it; the cleanup box above stays available for parking a
                            // pattern afterwards.
                            useSubtitleRegex = true,
                        ),
                    )
                },
                modifier =
                    Modifier.semantics { contentDescription = description },
            ) { Text(label) }
        }
    }
}

/**
 * Desktop's Sentence Rule combo over the dedup and i+1 booleans. The help line under it is the
 * picked rule's own tooltip on desktop.
 */
@Composable
private fun SentenceRuleChoice(
    draft: SettingsDraft,
    onDraftChange: (SettingsDraft) -> Unit,
) {
    val rule = draft.sentenceRule
    val options = SentenceRule.entries.map { it.name to stringResource(sentenceRuleLabel(it)) }
    SettingsDropdown(
        label = stringResource(R.string.settings_sentence_rule),
        options = options,
        selected = rule.name,
        onSelect = { picked -> onDraftChange(draft.withSentenceRule(SentenceRule.valueOf(picked))) },
        modifier = Modifier.testTag(SettingsCategoryTestTags.SENTENCE_RULE),
    )
    when (rule) {
        SentenceRule.ALL -> Unit
        SentenceRule.ONE_PER_SENTENCE -> SupportingText(stringResource(R.string.settings_sentence_rule_dedup_help))
        SentenceRule.I_PLUS_ONE -> SupportingText(stringResource(R.string.settings_sentence_rule_i_plus_one_help))
    }
}

@StringRes
private fun sentenceRuleLabel(rule: SentenceRule): Int =
    when (rule) {
        SentenceRule.ALL -> R.string.settings_sentence_rule_all
        SentenceRule.ONE_PER_SENTENCE -> R.string.settings_sentence_rule_dedup
        SentenceRule.I_PLUS_ONE -> R.string.settings_sentence_rule_i_plus_one
    }

/**
 * One resource panel behind a disclosure, closed until asked.
 *
 * Four priority lists share this tab, so opening on four expanded lists would be the scroll the
 * merge removed. The header carries the enabled/total counts, so a closed panel still says what it
 * holds.
 *
 * Internal rather than private so the disclosure tests drive the wrapper the four cards share,
 * instead of standing up a SetupViewModel to reach it.
 */
@Composable
internal fun ResourcePanelDisclosure(
    cardKey: String,
    heading: String,
    rows: List<ResourceRowSpec>,
    expansion: SettingsPanelExpansion,
    failed: Boolean,
    content: @Composable () -> Unit,
) {
    // A failure for this panel is reported inside it, so a closed panel would hide the only
    // account of what went wrong.
    LaunchedEffect(failed) { if (failed) expansion.expand(cardKey) }
    CollapsibleSettingGroup(
        title = heading,
        selectedCount = rows.count { it.enabled },
        totalCount = rows.size,
        titleStyle = MaterialTheme.typography.titleMedium,
        expanded = expansion.isExpanded(cardKey),
        onExpandedChange = { expansion.setExpanded(cardKey, it) },
        content = content,
    )
}

private const val DICTIONARY_SOURCES_KEY = "dictionary-sources"
private const val PITCH_SOURCES_KEY = "pitch-sources"
private const val AUDIO_SOURCES_KEY = "audio-sources"
private const val FREQUENCY_SOURCES_KEY = "frequency-sources"

/**
 * Every resource chain the engine consults, on one tab: dictionaries, pitch accent, audio packs
 * and frequency lists, each behind its own disclosure.
 */
private fun LazyListScope.resourceSettings(
    draft: SettingsDraft,
    resources: ResourceManagerState,
    setup: SetupUiState,
    setupViewModel: SetupViewModel,
    recorder: SettingsCardIndexRecorder,
    expansion: SettingsPanelExpansion,
    callbacks: SettingsScreenCallbacks,
    language: LanguageSettingsState,
    otherLanguageSlots: OtherLanguageSlots,
) {
    dictionarySourcesCard(
        draft,
        resources,
        setup,
        setupViewModel,
        recorder,
        expansion,
        callbacks,
        otherLanguageSlots = otherLanguageSlots.dictionaries,
    )
    // Pitch accent is a Japanese capability: a language without it has no pitch sources to rank
    // and no pitch format to choose. Deep links resolve by card key, so audio and frequency still
    // land on their own cards when this one is absent.
    if (language.showsPitch) {
        pitchSourcesCard(
            draft,
            resources,
            setup,
            setupViewModel,
            recorder,
            expansion,
            callbacks,
            otherLanguageSlots.pitch,
        )
    }
    audioSourcesCard(draft, resources, setup, setupViewModel, recorder, expansion, callbacks, otherLanguageSlots.audio)
    frequencySourcesCard(
        draft,
        resources,
        setup,
        setupViewModel,
        recorder,
        expansion,
        callbacks,
        otherLanguageSlots.frequencies,
    )
    // Conditional cards trail every deep-link target on this tab so settingsCardIndexFor stays a
    // table of constants. Adding a conditional card ahead of one, or moving this behind one,
    // silently shifts that target's index whenever this card is hidden.
    if (setup.dictionaries.any { it.isUsable }) {
        dictionaryLookupCard(setup, setupViewModel, recorder, callbacks)
    }
}

private fun LazyListScope.dictionarySourcesCard(
    draft: SettingsDraft,
    resources: ResourceManagerState,
    setup: SetupUiState,
    setupViewModel: SetupViewModel,
    recorder: SettingsCardIndexRecorder,
    expansion: SettingsPanelExpansion,
    callbacks: SettingsScreenCallbacks,
    otherLanguageSlots: List<Pair<String, String>>,
) {
    // One panel for every dictionary the engine may consult, in the order it consults them. The
    // catalog install cards are gone from here: the wizard still renders CatalogDictionaryCards,
    // and a permanent "install Jitendex" card on this tab was a prompt that never went away. The
    // install entries live in this panel's Add menu while the dictionary is missing.
    settingsCard(SettingsCategory.RESOURCES, recorder, DICTIONARY_SOURCES_KEY) {
        val occupiedSlotIds =
            resources.dictionaries.filter { it.occupied }.mapTo(mutableSetOf()) { it.slotId }
        val rows =
            dictionaryPanelRows(
                chain = draft.dictionarySources,
                installed = resources.dictionaries,
                strings = dictionaryRowStrings(),
                onChainChange = {
                    callbacks.onDraftChange(draft.copy(dictionarySources = it))
                },
                onRepair = setupViewModel::installCatalogDictionary,
                onReplace = callbacks.onReplaceCustom,
            )
        ResourcePanelDisclosure(
            cardKey = DICTIONARY_SOURCES_KEY,
            heading = stringResource(R.string.resource_panel_dictionaries_heading),
            rows = rows,
            expansion = expansion,
            failed =
                setup.failure?.origin in
                    setOf(
                        ResourceFailureOrigin.CATALOG_DICTIONARY,
                        ResourceFailureOrigin.CUSTOM_DICTIONARY,
                        ResourceFailureOrigin.RECOMMENDED_SET,
                    ),
        ) {
            ResourceChainPanel(
                // The disclosure header above carries the title and the enabled/total counts.
                heading = null,
                explanation = stringResource(R.string.resource_panel_dictionaries_explanation),
                rows = rows,
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
                        // Menu-shadowed: this panel's addMenu always holds the Yomitan importer, so
                        // the button opens the menu and only this label renders. Do not turn the
                        // click into a second import path.
                        onClick = callbacks.onImportCustom,
                    ),
                addMenu = dictionaryAddActions(resources, callbacks),
                busy = setup.busy,
                footer = {
                    ResourceOriginFailure(
                        setup,
                        setOf(
                            ResourceFailureOrigin.CATALOG_DICTIONARY,
                            ResourceFailureOrigin.CUSTOM_DICTIONARY,
                            ResourceFailureOrigin.RECOMMENDED_SET,
                        ),
                        setupViewModel,
                        callbacks,
                    )
                    resources.dictionaryLanguageMismatch
                        ?.takeIf { mismatch -> resources.dictionaries.any { it.slotId == mismatch.slotId } }
                        ?.let { mismatch ->
                            val declared =
                                java.util.Locale.forLanguageTag(mismatch.sourceLanguage)
                                    .getDisplayLanguage(currentUiLocale())
                                    .ifBlank { mismatch.sourceLanguage }
                            Text(
                                stringResource(
                                    R.string.resource_dictionary_language_mismatch,
                                    mismatch.sourceName,
                                    declared,
                                ),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    OtherLanguageSlotsNote(otherLanguageSlots)
                    DictionaryUpdatesSection(
                        automatic = draft.autoUpdateDictionaries,
                        onAutomaticChange = {
                            callbacks.onDraftChange(draft.copy(autoUpdateDictionaries = it))
                        },
                        state = callbacks.dictionaryUpdates,
                        onUpdateNow = callbacks.onUpdateDictionariesNow,
                    )
                },
            )
        }
    }
}

private fun LazyListScope.pitchSourcesCard(
    draft: SettingsDraft,
    resources: ResourceManagerState,
    setup: SetupUiState,
    setupViewModel: SetupViewModel,
    recorder: SettingsCardIndexRecorder,
    expansion: SettingsPanelExpansion,
    callbacks: SettingsScreenCallbacks,
    otherLanguageSlots: List<Pair<String, String>>,
) {
    settingsCard(SettingsCategory.RESOURCES, recorder, PITCH_SOURCES_KEY) {
        val installedSourceIds = resources.pitchSources.mapTo(mutableSetOf()) { it.sourceId }
        val rows =
            pitchPanelRows(
                chain = draft.pitchSources,
                installed = resources.pitchSources,
                strings = resourceRowStrings(),
                onChainChange = { callbacks.onDraftChange(draft.copy(pitchSources = it)) },
            )
        ResourcePanelDisclosure(
            cardKey = PITCH_SOURCES_KEY,
            heading = stringResource(R.string.resource_panel_pitch_heading),
            rows = rows,
            expansion = expansion,
            failed = setup.failure?.origin == ResourceFailureOrigin.PITCH,
        ) {
            ResourceChainPanel(
                // The disclosure header above carries the title and the enabled/total counts.
                heading = null,
                explanation = stringResource(R.string.resource_panel_pitch_explanation),
                rows = rows,
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
                    OtherLanguageSlotsNote(otherLanguageSlots)
                    // The pitch category format moved to the Anki field map, beside the Pitch
                    // category row it formats, as on desktop.
                },
            )
        }
    }
    // No inventory card after it either: every occupied slot — broken ones included — is a row of
    // the dictionary panel above, with the same Replace and Remove actions.
    // No operation card here: the shared header renders the one ResourceOperationCard for
    // setup.operation, and a second copy on this tab meant two Cancel buttons for one operation.
}

/** Emitted last on the tab, and only with a usable dictionary, so no other card's index moves. */
private fun LazyListScope.dictionaryLookupCard(
    setup: SetupUiState,
    setupViewModel: SetupViewModel,
    recorder: SettingsCardIndexRecorder,
    callbacks: SettingsScreenCallbacks,
) {
    settingsCard(SettingsCategory.RESOURCES, recorder, "dictionary-lookup") {
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

/**
 * The Add menu of the dictionary panel: the recommended set while it still has work to do, then
 * the Yomitan importer.
 *
 * A satisfied set is absent rather than disabled — a menu row that installs nothing is a dead end.
 * A broken slot is repaired from its own row, which is the only remaining caller of
 * `installCatalogDictionary`.
 */
@Composable
private fun dictionaryAddActions(
    resources: ResourceManagerState,
    callbacks: SettingsScreenCallbacks,
): List<ResourcePanelAction> =
    buildList {
        if (resources.recommendedPlan.isActionable) {
            add(
                ResourcePanelAction(
                    label = stringResource(R.string.resource_panel_download_recommended),
                    onClick = callbacks.onDownloadRecommended,
                ),
            )
        }
        add(
            ResourcePanelAction(
                label = stringResource(R.string.resource_panel_import_yomitan_zip),
                onClick = callbacks.onImportCustom,
            ),
        )
    }

/**
 * Row text every panel needs. Resolved here because row assembly runs outside composition.
 *
 * Internal rather than private so the UI-audit fixtures render the panels with the same strings
 * the settings screen does; a fixture-local copy would drift silently.
 */
@Composable
internal fun resourceRowStrings(): ResourceRowStrings {
    // Captured rather than pre-formatted: the count is per row and the panel formats on demand.
    // Read through LocalResources, not LocalContext: a configuration change invalidates this
    // composition, so a locale or font-scale switch reformats the counts.
    val resources = LocalResources.current
    return ResourceRowStrings(
        entries = { count -> resources.getString(R.string.settings_resource_entries, count) },
        notInChain = stringResource(R.string.resource_panel_not_in_chain),
        missingWarning = stringResource(R.string.resource_panel_warning_missing),
        repairWarning = stringResource(R.string.resource_panel_warning_repair),
    )
}

@Composable
internal fun dictionaryRowStrings(): DictionaryRowStrings =
    DictionaryRowStrings(
        rows = resourceRowStrings(),
        repairAction = stringResource(R.string.resource_panel_row_repair),
        replaceAction = stringResource(R.string.resource_panel_row_replace),
    )


private fun LazyListScope.audioSourcesCard(
    draft: SettingsDraft,
    resources: ResourceManagerState,
    setup: SetupUiState,
    setupViewModel: SetupViewModel,
    recorder: SettingsCardIndexRecorder,
    expansion: SettingsPanelExpansion,
    callbacks: SettingsScreenCallbacks,
    otherLanguageSlots: List<Pair<String, String>>,
) {
    // One card: the pack priority list, its importer, and the device voice that speaks a word no
    // pack has. Sentence text-to-speech moved to Media, as desktop's Text-to-speech row did.
    settingsCard(SettingsCategory.RESOURCES, recorder, AUDIO_SOURCES_KEY) {
        val installedPackIds = resources.audioPacks.mapTo(mutableSetOf()) { it.packId }
        val rows =
            audioPanelRows(
                chain = draft.audioPacks,
                installed = resources.audioPacks,
                strings = resourceRowStrings(),
                onChainChange = { callbacks.onDraftChange(draft.copy(audioPacks = it)) },
            )
        ResourcePanelDisclosure(
            cardKey = AUDIO_SOURCES_KEY,
            heading = stringResource(R.string.resource_panel_audio_heading),
            rows = rows,
            expansion = expansion,
            failed = setup.failure?.origin == ResourceFailureOrigin.AUDIO,
        ) {
            ResourceChainPanel(
                // The disclosure header above carries the title and the enabled/total counts.
                heading = null,
                explanation = stringResource(R.string.resource_panel_audio_explanation),
                rows = rows,
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
                    OtherLanguageSlotsNote(otherLanguageSlots)
                    if (callbacks.miningLanguage != LanguageScope.JAPANESE) {
                        DeviceVoiceSection(
                            callbacks.miningLanguage,
                            onOpenSpeechSettings = callbacks.onOpenSpeechSettings,
                        )
                    }
                },
            )
        }
    }
}

/**
 * The device voice that speaks a non-Japanese language's word audio after the packs, and whether
 * this device has one for [language]. The status is probed per language and again on every return
 * to the screen, so a voice downloaded from the speech services shows up; it stays blank while the
 * engine first answers.
 */
@Composable
internal fun DeviceVoiceSection(
    language: String,
    onOpenSpeechSettings: () -> Unit = {},
    probe: suspend (Context, String) -> DeviceVoiceStatus = ::probeDeviceVoice,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val status by produceState<DeviceVoiceStatus?>(null, language, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { value = probe(context, language) }
    }
    SettingsSection(stringResource(R.string.settings_word_audio_device_voice)) {
        SupportingText(stringResource(R.string.settings_word_audio_device_voice_help))
        status?.let { SupportingText(stringResource(it.message)) }
        // The missing-voice line names this button, so it appears with that line.
        if (status == DeviceVoiceStatus.MISSING_DATA) {
            SecondaryActionButton(
                onClick = onOpenSpeechSettings,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.settings_open_speech_services)) }
        }
    }
}

private val DeviceVoiceStatus.message: Int
    get() =
        when (this) {
            DeviceVoiceStatus.AVAILABLE -> R.string.settings_word_audio_voice_available
            DeviceVoiceStatus.MISSING_DATA -> R.string.settings_word_audio_voice_missing_data
            DeviceVoiceStatus.UNSUPPORTED -> R.string.settings_word_audio_voice_unsupported
        }

private fun LazyListScope.frequencySourcesCard(
    draft: SettingsDraft,
    resources: ResourceManagerState,
    setup: SetupUiState,
    setupViewModel: SetupViewModel,
    recorder: SettingsCardIndexRecorder,
    expansion: SettingsPanelExpansion,
    callbacks: SettingsScreenCallbacks,
    otherLanguageSlots: List<Pair<String, String>>,
) {
    settingsCard(SettingsCategory.RESOURCES, recorder, FREQUENCY_SOURCES_KEY) {
        val installedSourceIds = resources.frequencySources.mapTo(mutableSetOf()) { it.sourceId }
        val rows =
            frequencyPanelRows(
                chain = draft.frequencySources,
                installed = resources.frequencySources,
                strings = resourceRowStrings(),
                onChainChange = { callbacks.onDraftChange(draft.copy(frequencySources = it)) },
            )
        ResourcePanelDisclosure(
            cardKey = FREQUENCY_SOURCES_KEY,
            heading = stringResource(R.string.resource_panel_frequency_heading),
            rows = rows,
            expansion = expansion,
            failed = setup.failure?.origin == ResourceFailureOrigin.FREQUENCY,
        ) {
            ResourceChainPanel(
                // The disclosure header above carries the title and the enabled/total counts.
                heading = null,
                explanation = stringResource(R.string.resource_panel_frequency_explanation),
                rows = rows,
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
                    OtherLanguageSlotsNote(otherLanguageSlots)
                },
            )
        }
    }
}

private fun LazyListScope.wordFilterSettings(
    draft: SettingsDraft,
    resources: ResourceManagerState,
    setup: SetupUiState,
    setupViewModel: SetupViewModel,
    recorder: SettingsCardIndexRecorder,
    callbacks: SettingsScreenCallbacks,
    language: LanguageSettingsState,
) {
    // First on the tab: the known-words and word-list deep links count on staying at 3 and 4.
    wordFilterOptions(
        draft,
        resources,
        setup.availableDeckNames,
        recorder,
        callbacks.onDraftChange,
        showsKanaFilters = language.showsKanaFilters,
        showsNameWordsets = language.showsNameWordsets,
        showsHangulFilters = language.showsHangulFilters,
        inherited = callbacks.languageDefaults,
        unusableDecksHidden = setup.unusableDecksHidden,
    )
    settingsCard(SettingsCategory.WORD_FILTERS, recorder, "known-words-import") {
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
    settingsCard(SettingsCategory.WORD_FILTERS, recorder, "word-lists") {
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
            inherited = callbacks.languageDefaults,
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
}

/**
 * Which words get mined, mirroring desktop's Word Filters page: the frequency band, the known-words
 * rules and excluded decks, the name lists, the script filters and the reading threshold. The kana
 * rows and the name box are Japanese (desktop `kana_filters`, `name_wordsets`); the hangul rows are
 * Korean (`hangul_filters`) and write the same two booleans as Script type.
 *
 * Internal rather than private so the instrumented tests can compose the real card.
 */
internal fun LazyListScope.wordFilterOptions(
    draft: SettingsDraft,
    resources: ResourceManagerState,
    availableDeckNames: List<String>,
    recorder: SettingsCardIndexRecorder,
    onDraftChange: (SettingsDraft) -> Unit,
    showsKanaFilters: Boolean = true,
    showsNameWordsets: Boolean = true,
    showsHangulFilters: Boolean = false,
    inherited: LanguageDefaults = LanguageDefaults.JAPANESE,
    unusableDecksHidden: Boolean = false,
) {
    settingsCard(SettingsCategory.WORD_FILTERS, recorder, "filtering-options") {
        SettingsSection(stringResource(R.string.settings_filtering)) {
            // Two ends of one filter. Desktop keeps the band ordered by moving the other end when
            // one is pushed past it; here that happens when the field is left rather than on every
            // keystroke, or typing 5000 into the maximum would first drag the minimum down to 5.
            FrequencyBandField(
                value = draft.minFrequency,
                onChange = { onDraftChange(draft.copy(minFrequency = it)) },
                label = stringResource(R.string.settings_min_frequency),
                error = validationMessage(draft, SettingsFieldKey.MIN_FREQUENCY),
                placeholderValue = inherited.minFrequencyRank,
                testTag = SettingsCategoryTestTags.MIN_FREQUENCY,
                onLeave = { onDraftChange(draft.withOrderedFrequencyBand(FrequencyBandEnd.MIN)) },
            )
            FrequencyBandField(
                value = draft.maxFrequency,
                onChange = { onDraftChange(draft.copy(maxFrequency = it)) },
                label = stringResource(R.string.settings_max_frequency),
                error = validationMessage(draft, SettingsFieldKey.MAX_FREQUENCY),
                placeholderValue = inherited.maxFrequencyRank,
                testTag = SettingsCategoryTestTags.MAX_FREQUENCY,
                onLeave = { onDraftChange(draft.withOrderedFrequencyBand(FrequencyBandEnd.MAX)) },
            )
            SupportingText(stringResource(R.string.settings_frequency_band_help))
            // Only means anything while one end is set, which is when desktop enables it too.
            NullableToggle(
                stringResource(R.string.settings_frequency_keep_unranked),
                draft.frequencyKeepUnranked,
                inherited.frequencyKeepUnranked,
                enabled = draft.frequencyBandSet,
            ) { onDraftChange(draft.copy(frequencyKeepUnranked = it)) }
            HorizontalDivider()
            NullableToggle(
                stringResource(R.string.settings_known_words),
                draft.knownWords,
                EngineDefaults.USE_KNOWN_WORDS_DATABASE,
            ) { onDraftChange(draft.copy(knownWords = it)) }
            if (showsKanaFilters) {
                NullableToggle(
                    stringResource(R.string.settings_known_words_match_kana_variants),
                    draft.knownWordsMatchKanaVariants,
                    inherited.knownWordsMatchKanaVariants,
                ) { onDraftChange(draft.copy(knownWordsMatchKanaVariants = it)) }
                SupportingText(stringResource(R.string.settings_known_words_match_kana_variants_help))
            }
            // Desktop keeps the excluded decks with the known-words rules: they decide which
            // cards count as known.
            val choices = excludedDeckChoices(availableDeckNames, draft.excludedDecks)
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
                                onDraftChange(
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
                if (unusableDecksHidden) {
                    SupportingText(stringResource(R.string.anki_deck_unusable_hidden))
                }
            }
            if (showsNameWordsets) {
                NameWordsetsRow(draft, resources, onDraftChange)
            }
            if (showsKanaFilters) {
                ScriptTypeChoice(draft, onDraftChange, inherited)
            }
            if (showsHangulFilters) {
                // Korean's two script filters bind to the kana booleans, as its profile declares:
                // hangul-only writes the hiragana one, hanja-containing the katakana one.
                NullableToggle(
                    stringResource(R.string.settings_exclude_hangul_only),
                    draft.hiragana,
                    inherited.excludeHiraganaOnly,
                ) { onDraftChange(draft.copy(hiragana = it)) }
                SupportingText(stringResource(R.string.settings_exclude_hangul_only_help))
                NullableToggle(
                    stringResource(R.string.settings_exclude_hanja),
                    draft.katakana,
                    inherited.excludeKatakanaOnly,
                ) { onDraftChange(draft.copy(katakana = it)) }
                SupportingText(stringResource(R.string.settings_exclude_hanja_help))
            }
            NumericField(
                draft.readingOccurrence,
                { onDraftChange(draft.copy(readingOccurrence = it)) },
                stringResource(R.string.settings_reading_occurrence),
                integer = true,
                error = validationMessage(draft, SettingsFieldKey.READING_OCCURRENCE),
                imeAction = ImeAction.Next,
                placeholder = inheritedDefault(EngineDefaults.READING_MINIMUM_OCCURRENCE),
            )
            NumericField(
                draft.workers,
                { onDraftChange(draft.copy(workers = it)) },
                stringResource(R.string.settings_workers),
                integer = true,
                error = validationMessage(draft, SettingsFieldKey.WORKERS),
                placeholder = inheritedDefault(EngineDefaults.MAX_PARALLEL_WORKERS),
            )
        }
    }
}

/**
 * Desktop's one names box (D15 item 2) over the bundled wordsets: checked skips every list, clear
 * skips none. A subset saved before the box existed shows partly checked and is kept until clicked.
 */
@Composable
private fun NameWordsetsRow(
    draft: SettingsDraft,
    resources: ResourceManagerState,
    onDraftChange: (SettingsDraft) -> Unit,
) {
    HorizontalDivider()
    val catalog = resources.wordsets.map { it.wordsetId }
    TriStateSetting(
        label = stringResource(R.string.settings_skip_names),
        state = nameWordsetsState(draft.enabledWordsets, catalog),
        enabled = catalog.isNotEmpty(),
        onClick = {
            onDraftChange(draft.copy(enabledWordsets = nameWordsetsAfterClick(draft.enabledWordsets, catalog)))
        },
        modifier = Modifier.testTag(SettingsCategoryTestTags.NAME_WORDSETS),
    )
    if (catalog.isEmpty()) {
        Text(
            stringResource(R.string.bundled_wordsets_unavailable),
            color = MaterialTheme.colorScheme.error,
        )
    } else {
        SupportingText(stringResource(R.string.settings_skip_names_help))
    }
}

/** Desktop's Script Type combo over the two kana booleans; an unset pair shows the language default. */
@Composable
private fun ScriptTypeChoice(
    draft: SettingsDraft,
    onDraftChange: (SettingsDraft) -> Unit,
    inherited: LanguageDefaults,
) {
    val current = draft.scriptType(inherited)
    val options = ScriptType.entries.map { it.name to stringResource(scriptTypeLabel(it)) }
    SettingsDropdown(
        label = stringResource(R.string.settings_script_type),
        options = options,
        selected = current.name,
        onSelect = { picked ->
            val type = ScriptType.valueOf(picked)
            if (type != current) onDraftChange(draft.withScriptType(type))
        },
        modifier = Modifier.testTag(SettingsCategoryTestTags.SCRIPT_TYPE),
    )
}

@StringRes
private fun scriptTypeLabel(type: ScriptType): Int =
    when (type) {
        ScriptType.KEEP -> R.string.settings_script_type_keep
        ScriptType.HIRAGANA -> R.string.settings_script_type_hiragana
        ScriptType.KATAKANA -> R.string.settings_script_type_katakana
        ScriptType.ALL_KANA -> R.string.settings_script_type_all_kana
    }

/**
 * One end of the frequency band. [onLeave] puts the band back in order when the field is left:
 * focus moving on (IME Next, a tap on another field), or the field leaving the screen while it
 * still has focus — Back closes the keyboard but keeps focus, so a tab switch after it is a leave
 * that no focus change reports.
 */
@Composable
private fun FrequencyBandField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    error: String?,
    placeholderValue: Int,
    testTag: String,
    onLeave: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val leave by rememberUpdatedState(onLeave)
    DisposableEffect(Unit) { onDispose { if (focused) leave() } }
    NumericField(
        value,
        onChange,
        label,
        integer = true,
        error = error,
        imeAction = ImeAction.Next,
        modifier =
            Modifier
                .testTag(testTag)
                .onFocusChanged { state ->
                    if (focused && !state.isFocused) leave()
                    focused = state.isFocused
                },
        placeholder = inheritedDefault(placeholderValue),
    )
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
                SecondaryActionButton(
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
    language: LanguageSettingsState,
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
    // A language that tokenizes without UniDic (every one but Japanese) never needs the card.
    if (!setup.tokenizerReady || setup.failure?.origin == ResourceFailureOrigin.UNIDIC) {
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
            importEnabled = language.switchAllowed,
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
                SecondaryActionButton(
                    onClick = { callbacks.onRequestReset(action) },
                    enabled = callbacks.resetEnabled && !setup.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(settingsResetLabel(action)))
                }
            }
        }
    }
    settingsCard(SettingsCategory.DIAGNOSTICS, recorder, "tester-diagnostics") {
        SettingsSection(stringResource(R.string.settings_diagnostics_bundle)) {
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
            SecondaryActionButton(
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
        TextButton(onClick = callbacks.onAttributions, colors = accentTextButtonColors()) {
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
        SecondaryActionButton(
            onClick = onCheck,
            enabled = checkEnabled,
            modifier = Modifier.fillMaxWidth(),
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
    val openLink = rememberExternalLinkOpener()
    Text(stringResource(R.string.settings_update_available, available.version))
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related),
        verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related),
    ) {
        TextButton(
            onClick = { openLink(available.releasePageUrl) },
            colors = accentTextButtonColors(),
        ) {
            Text(stringResource(R.string.settings_update_view_release))
        }
        TextButton(onClick = onSkip, colors = accentTextButtonColors()) {
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
    importEnabled: Boolean = true,
) {
    SettingsSection(stringResource(R.string.settings_backup_section)) {
        Text(
            stringResource(R.string.settings_backup_detail),
            style = MaterialTheme.typography.bodySmall,
        )
        val actionsEnabled = backupState !is SettingsBackupState.Working
        SecondaryActionButton(
            onClick = onExportSettings,
            enabled = actionsEnabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.settings_backup_export))
        }
        SecondaryActionButton(
            onClick = onImportSettings,
            // A file may carry another mining language: it loads only while a switch could start.
            enabled = actionsEnabled && importEnabled,
            modifier = Modifier.fillMaxWidth(),
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
            is SettingsBackupState.Imported -> {
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
                state.unknownLanguage?.let { code ->
                    Text(
                        stringResource(R.string.settings_backup_unknown_language, code),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            is SettingsBackupState.Failed ->
                InlineFailureContainer(
                    message = state.message.localized(),
                    actionLabel = stringResource(R.string.b3_retry),
                    onAction = settingsBackupRetry(state.operation, onExportSettings, onImportSettings),
                    onDismiss = onDismissBackupState,
                )
        }
    }
}

/** Retry repeats the action that failed: a failed save must never open the load picker. */
internal fun settingsBackupRetry(
    operation: SettingsBackupOperation,
    onExportSettings: () -> Unit,
    onImportSettings: () -> Unit,
): () -> Unit =
    when (operation) {
        SettingsBackupOperation.EXPORT -> onExportSettings
        SettingsBackupOperation.IMPORT -> onImportSettings
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
    /** Only the Language card passes it: there a failed "Download and switch" retries the switch. */
    language: LanguageSettingsState? = null,
) {
    val failure = setup.failure?.takeIf { it.origin in origins } ?: return
    val targetId = failure.retry.targetId
    val switchTarget = language?.downloadAndSwitchRetry(failure)
    val action: () -> Unit =
        when {
            // Ahead of the targetId branch: a failed set carries its language as the target.
            switchTarget != null -> {
                { callbacks.language.onDownloadAndSwitch(switchTarget) }
            }
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
            // Only a failed import chooses another file; a failed removal retries the removal.
            failure.origin == ResourceFailureOrigin.WORD_LIST ->
                if (failure.retry.action == ResourceFailureAction.CHOOSE_ANOTHER) {
                    { callbacks.onImportWordList(setup.wordListTarget) }
                } else {
                    setupViewModel::retryResourceFailure
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
    callbacks: SettingsScreenCallbacks,
) {
    if (setup.ankiDroidAction != null) {
        AnkiDroidConnectActions(
            state = setup,
            onRequestPermissions = callbacks.onRequestPermissions,
            onOpenAppSettings = callbacks.onOpenAppSettings,
            onInstallAnkiDroid = callbacks.onInstallAnkiDroid,
            onOpenAnkiDroid = callbacks.onOpenAnkiDroid,
        )
        return
    }
    val failure = setup.ankiFailure?.takeIf { it.origin == origin } ?: return
    InlineFailureContainer(
        message = failure.message,
        actionLabel = stringResource(R.string.b3_retry),
        onAction = setupViewModel::verifyNoteType,
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
