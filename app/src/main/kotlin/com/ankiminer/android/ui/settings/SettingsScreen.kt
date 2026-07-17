package com.ankiminer.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ankiminer.android.R
import com.ankiminer.android.data.resources.ResourceManagerState
import com.ankiminer.android.data.settings.AppSettings
import com.ankiminer.android.data.settings.AppSettingsDraftParser
import com.ankiminer.android.data.settings.AudioFormat
import com.ankiminer.android.data.settings.EngineSettingsSnapshotMapper
import com.ankiminer.android.data.settings.PitchCategoryFormat
import com.ankiminer.android.data.settings.ResourceChainSelection
import com.ankiminer.android.diagnostics.TesterDiagnostics
import com.ankiminer.android.vm.SettingsViewModel

@Composable
internal fun SettingsRoute(
    viewModel: SettingsViewModel,
    diagnostics: TesterDiagnostics,
    onOpenSpeechSettings: () -> Unit,
    onShareDiagnostics: (String) -> Unit,
    onAttributions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val resources by viewModel.resourceState.collectAsStateWithLifecycle()
    SettingsScreen(
        settings = settings,
        resources = resources,
        saving = saving,
        error = error,
        diagnostics = diagnostics,
        onSave = viewModel::save,
        onRestoreDefaults = viewModel::restoreDefaults,
        onDismissError = viewModel::dismissError,
        onOpenSpeechSettings = onOpenSpeechSettings,
        onShareDiagnostics = onShareDiagnostics,
        onAttributions = onAttributions,
        modifier = modifier,
    )
}

@Composable
private fun SettingsScreen(
    settings: AppSettings,
    resources: ResourceManagerState,
    saving: Boolean,
    error: String?,
    diagnostics: TesterDiagnostics,
    onSave: (AppSettings) -> Unit,
    onRestoreDefaults: () -> Unit,
    onDismissError: () -> Unit,
    onOpenSpeechSettings: () -> Unit,
    onShareDiagnostics: (String) -> Unit,
    onAttributions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var deckName by remember(settings.deckName) { mutableStateOf(settings.deckName.orEmpty()) }
    var tags by remember(settings.tags) { mutableStateOf(settings.tags.orEmpty()) }
    var tagsOverride by remember(settings.tags) { mutableStateOf(settings.tags != null) }
    var audioPadding by remember(settings.audioPaddingSeconds) { mutableStateOf(settings.audioPaddingSeconds?.toString().orEmpty()) }
    var screenshotOffset by remember(settings.screenshotOffsetSeconds) { mutableStateOf(settings.screenshotOffsetSeconds?.toString().orEmpty()) }
    var subtitleOffset by remember(settings.subtitleOffsetSeconds) { mutableStateOf(settings.subtitleOffsetSeconds?.toString().orEmpty()) }
    var bitrate by remember(settings.audioBitrateKbps) { mutableStateOf(settings.audioBitrateKbps?.toString().orEmpty()) }
    var maxDuration by remember(settings.maxSentenceDurationSeconds) { mutableStateOf(settings.maxSentenceDurationSeconds?.toString().orEmpty()) }
    var maxCharacters by remember(settings.maxSentenceCharacters) { mutableStateOf(settings.maxSentenceCharacters?.toString().orEmpty()) }
    var readingOccurrence by remember(settings.readingMinimumOccurrence) {
        mutableStateOf(settings.readingMinimumOccurrence?.toString().orEmpty())
    }
    var maxFrequency by remember(settings.maxFrequencyRank) {
        mutableStateOf(settings.maxFrequencyRank?.toString().orEmpty())
    }
    var workers by remember(settings.maxParallelWorkers) { mutableStateOf(settings.maxParallelWorkers?.toString().orEmpty()) }
    var audioFormat by remember(settings.audioFormat) { mutableStateOf(settings.audioFormat) }
    var knownWords by remember(settings.useKnownWordsDatabase) { mutableStateOf(settings.useKnownWordsDatabase) }
    var hiragana by remember(settings.excludeHiraganaOnly) { mutableStateOf(settings.excludeHiraganaOnly) }
    var katakana by remember(settings.excludeKatakanaOnly) { mutableStateOf(settings.excludeKatakanaOnly) }
    var boldTarget by remember(settings.boldTargetInSentence) { mutableStateOf(settings.boldTargetInSentence) }
    var deduplicate by remember(settings.deduplicateSentences) { mutableStateOf(settings.deduplicateSentences) }
    var iPlusOne by remember(settings.useIPlusOneFilter) { mutableStateOf(settings.useIPlusOneFilter) }
    var sentenceLength by remember(settings.useSentenceLengthFilter) { mutableStateOf(settings.useSentenceLengthFilter) }
    var pitchFormat by remember(settings.pitchCategoryFormat) { mutableStateOf(settings.pitchCategoryFormat) }
    var dictionarySources by remember(settings.dictionarySources, resources.dictionaries) {
        mutableStateOf(
            EngineSettingsSnapshotMapper.resolveResourceChain(
                settings.dictionarySources,
                resources.dictionaries
                    .filter { it.isUsable }
                    .map { it.slotId },
            ),
        )
    }
    var frequencySources by remember(settings.frequencySources, resources.frequencySources) {
        mutableStateOf(
            EngineSettingsSnapshotMapper.resolveResourceChain(
                settings.frequencySources,
                resources.frequencySources
                    .filter { it.schemaOk && it.entryCount > 0 }
                    .map { it.sourceId },
            ),
        )
    }
    var audioPacks by remember(settings.audioPacks, resources.audioPacks) {
        mutableStateOf(
            EngineSettingsSnapshotMapper.resolveResourceChain(
                settings.audioPacks,
                resources.audioPacks
                    .filter { it.contentAvailable && it.entryCount > 0 }
                    .map { it.packId },
            ),
        )
    }
    var excludedWordsets by remember(settings.excludedWordsets, resources.wordsets) {
        mutableStateOf(settings.excludedWordsets.filter { selected -> resources.wordsets.any { it.wordsetId == selected } })
    }
    var readingTts by remember(settings.readingTtsEnabled) { mutableStateOf(settings.readingTtsEnabled) }
    var jisho by remember(settings.jishoEnabled) { mutableStateOf(settings.jishoEnabled) }
    val numericDraftValid =
        listOf(audioPadding, screenshotOffset, subtitleOffset, maxDuration)
            .all(AppSettingsDraftParser::isOptionalDouble) &&
            listOf(bitrate, maxCharacters, readingOccurrence, maxFrequency, workers)
                .all(AppSettingsDraftParser::isOptionalInt)

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

        SettingsSection(stringResource(R.string.settings_anki_target)) {
            SettingTextField(
                deckName,
                { deckName = it },
                stringResource(R.string.settings_deck_name),
                stringResource(R.string.settings_deck_default),
            )
            Text(stringResource(R.string.settings_note_managed))
            BooleanSetting(
                label = stringResource(R.string.settings_tags_override),
                help = stringResource(R.string.settings_tags_override_help),
                checked = tagsOverride,
                onCheckedChange = { tagsOverride = it },
            )
            SettingTextField(
                tags,
                { tags = it },
                stringResource(R.string.settings_tags),
                stringResource(
                    if (tagsOverride) R.string.settings_tags_help else R.string.settings_tags_default,
                ),
                enabled = tagsOverride,
            )
        }

        SettingsSection(stringResource(R.string.settings_media)) {
            NumericField(audioPadding, { audioPadding = it }, stringResource(R.string.settings_audio_padding), stringResource(R.string.settings_audio_padding_default))
            NumericField(screenshotOffset, { screenshotOffset = it }, stringResource(R.string.settings_screenshot_offset), stringResource(R.string.settings_screenshot_offset_default))
            NumericField(subtitleOffset, { subtitleOffset = it }, stringResource(R.string.settings_subtitle_offset), stringResource(R.string.settings_subtitle_offset_default), allowNegative = true)
            NumericField(bitrate, { bitrate = it }, stringResource(R.string.settings_audio_bitrate), stringResource(R.string.settings_audio_bitrate_default))
            Text(stringResource(R.string.settings_audio_format))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    null to stringResource(R.string.settings_desktop_default),
                    AudioFormat.MP3 to stringResource(R.string.settings_mp3),
                    AudioFormat.OPUS to stringResource(R.string.settings_opus),
                )
                    .forEach { (value, label) ->
                        OutlinedButton(
                            onClick = { audioFormat = value },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (audioFormat == value) "✓ $label" else label)
                        }
                    }
            }
        }

        SettingsSection(stringResource(R.string.settings_filtering)) {
            NullableToggle(stringResource(R.string.settings_known_words), knownWords, false) { knownWords = it }
            Text(
                stringResource(
                    R.string.settings_known_words_inventory,
                    resources.knownWords.userCount,
                    resources.knownWords.ankiCount,
                    resources.knownWords.minedCount,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            NullableToggle(stringResource(R.string.settings_exclude_hiragana), hiragana, false) { hiragana = it }
            NullableToggle(stringResource(R.string.settings_exclude_katakana), katakana, false) { katakana = it }
            NullableToggle(stringResource(R.string.settings_bold_target), boldTarget, false) { boldTarget = it }
            NullableToggle(stringResource(R.string.settings_deduplicate), deduplicate, true) { deduplicate = it }
            NullableToggle(stringResource(R.string.settings_i_plus_one), iPlusOne, false) { iPlusOne = it }
            NullableToggle(stringResource(R.string.settings_sentence_length), sentenceLength, false) { sentenceLength = it }
            NumericField(maxDuration, { maxDuration = it }, stringResource(R.string.settings_max_duration), stringResource(R.string.settings_zero_default))
            NumericField(maxCharacters, { maxCharacters = it }, stringResource(R.string.settings_max_characters), stringResource(R.string.settings_zero_default))
            NumericField(
                readingOccurrence,
                { readingOccurrence = it },
                stringResource(R.string.settings_reading_occurrence),
                stringResource(R.string.settings_reading_occurrence_default),
            )
            NumericField(
                maxFrequency,
                { maxFrequency = it },
                stringResource(R.string.settings_max_frequency),
                stringResource(R.string.settings_max_frequency_default),
            )
            NumericField(workers, { workers = it }, stringResource(R.string.settings_workers), stringResource(R.string.settings_workers_default))
        }

        SettingsSection(stringResource(R.string.settings_local_resources)) {
            Text(stringResource(R.string.settings_dictionary_chain), style = MaterialTheme.typography.titleSmall)
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
                onChange = { dictionarySources = it },
            )

            HorizontalDivider()
            Text(stringResource(R.string.settings_frequency_chain), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.settings_frequency_chain_help), style = MaterialTheme.typography.bodySmall)
            ResourceChainEditor(
                choices = frequencySources,
                labels =
                    resources.frequencySources.associate { source ->
                        source.sourceId to "${source.sourceName} (${source.entryCount})"
                    },
                emptyMessage = stringResource(R.string.settings_no_frequency_sources),
                onChange = { frequencySources = it },
            )

            HorizontalDivider()
            Text(stringResource(R.string.settings_audio_pack_chain), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.settings_audio_pack_help), style = MaterialTheme.typography.bodySmall)
            ResourceChainEditor(
                choices = audioPacks,
                labels =
                    resources.audioPacks.associate { pack ->
                        pack.packId to "${pack.sourceName} (${pack.entryCount})"
                    },
                emptyMessage = stringResource(R.string.settings_no_audio_packs),
                onChange = { audioPacks = it },
            )

            HorizontalDivider()
            Text(stringResource(R.string.settings_pitch_format), style = MaterialTheme.typography.titleSmall)
            Text(
                resources.pitchAccent?.let {
                    stringResource(R.string.settings_pitch_installed, it.sourceName, it.entryCount)
                } ?: stringResource(R.string.settings_pitch_not_installed),
                style = MaterialTheme.typography.bodySmall,
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    null to stringResource(R.string.settings_desktop_default),
                    PitchCategoryFormat.JAPANESE to stringResource(R.string.settings_pitch_japanese),
                    PitchCategoryFormat.ROMAJI to stringResource(R.string.settings_pitch_romaji),
                ).forEach { (value, label) ->
                    OutlinedButton(
                        onClick = { pitchFormat = value },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (pitchFormat == value) "✓ $label" else label)
                    }
                }
            }

            HorizontalDivider()
            Text(stringResource(R.string.settings_wordsets), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.settings_wordsets_help), style = MaterialTheme.typography.bodySmall)
            if (resources.wordsets.isEmpty()) {
                Text(stringResource(R.string.settings_no_wordsets))
            } else {
                resources.wordsets.forEach { wordset ->
                    BooleanSetting(
                        label = wordset.displayName,
                        help = stringResource(R.string.settings_resource_entries, wordset.entryCount),
                        checked = wordset.wordsetId in excludedWordsets,
                        onCheckedChange = { checked ->
                            excludedWordsets =
                                if (checked) {
                                    (excludedWordsets + wordset.wordsetId).distinct()
                                } else {
                                    excludedWordsets - wordset.wordsetId
                                }
                        },
                    )
                }
            }
        }

        SettingsSection(stringResource(R.string.settings_reading_audio)) {
            BooleanSetting(
                label = stringResource(R.string.settings_reading_tts),
                help = stringResource(R.string.settings_reading_tts_help),
                checked = readingTts,
                onCheckedChange = { readingTts = it },
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

        SettingsSection(stringResource(R.string.settings_online_fallback)) {
            BooleanSetting(
                label = stringResource(R.string.settings_jisho),
                help = stringResource(R.string.settings_jisho_disclosure),
                checked = jisho,
                onCheckedChange = { jisho = it },
            )
        }

        error?.let {
            OutlinedCard(
                Modifier
                    .fillMaxWidth()
                    .semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismissError) { Text(stringResource(R.string.dismiss)) }
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

        Button(
            enabled = !saving && numericDraftValid,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                onSave(
                    settings.copy(
                        deckName = deckName.takeIf(String::isNotEmpty),
                        tags = tags.takeIf { tagsOverride },
                        audioPaddingSeconds = AppSettingsDraftParser.optionalDouble(audioPadding),
                        screenshotOffsetSeconds = AppSettingsDraftParser.optionalDouble(screenshotOffset),
                        subtitleOffsetSeconds = AppSettingsDraftParser.optionalDouble(subtitleOffset),
                        audioFormat = audioFormat,
                        audioBitrateKbps = AppSettingsDraftParser.optionalInt(bitrate),
                        useKnownWordsDatabase = knownWords,
                        excludeHiraganaOnly = hiragana,
                        excludeKatakanaOnly = katakana,
                        boldTargetInSentence = boldTarget,
                        deduplicateSentences = deduplicate,
                        useIPlusOneFilter = iPlusOne,
                        useSentenceLengthFilter = sentenceLength,
                        maxSentenceDurationSeconds = AppSettingsDraftParser.optionalDouble(maxDuration),
                        maxSentenceCharacters = AppSettingsDraftParser.optionalInt(maxCharacters),
                        readingMinimumOccurrence = AppSettingsDraftParser.optionalInt(readingOccurrence),
                        maxFrequencyRank = AppSettingsDraftParser.optionalInt(maxFrequency),
                        pitchCategoryFormat = pitchFormat,
                        maxParallelWorkers = AppSettingsDraftParser.optionalInt(workers),
                        dictionarySources = dictionarySources,
                        frequencySources = frequencySources,
                        audioPacks = audioPacks,
                        excludedWordsets = excludedWordsets,
                        readingTtsEnabled = readingTts,
                        jishoEnabled = jisho,
                    ),
                )
            },
        ) {
            Text(stringResource(if (saving) R.string.settings_saving else R.string.settings_save))
        }
        OutlinedButton(onClick = onRestoreDefaults, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.settings_restore_defaults))
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
        }
        HorizontalDivider()
        TextButton(onClick = onAttributions) { Text(stringResource(R.string.settings_attributions)) }
    }
}

@Composable
private fun ResourceChainEditor(
    choices: List<ResourceChainSelection>,
    labels: Map<String, String>,
    emptyMessage: String,
    onChange: (List<ResourceChainSelection>) -> Unit,
) {
    if (choices.isEmpty()) {
        Text(emptyMessage)
        return
    }
    choices.forEachIndexed { index, choice ->
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = choice.enabled,
                        role = Role.Checkbox,
                        onValueChange = { enabled ->
                            onChange(
                                choices.toMutableList().also {
                                    it[index] = choice.copy(enabled = enabled)
                                },
                            )
                        },
                    ).padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Checkbox(
                    checked = choice.enabled,
                    onCheckedChange = null,
                )
                Column(Modifier.weight(1f)) {
                    Text(labels[choice.resourceId] ?: choice.resourceId)
                    Text(choice.resourceId, style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = index > 0,
                    onClick = { onChange(choices.swap(index, index - 1)) },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.settings_move_up)) }
                OutlinedButton(
                    enabled = index < choices.lastIndex,
                    onClick = { onChange(choices.swap(index, index + 1)) },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.settings_move_down)) }
            }
        }
    }
}

private fun <T> List<T>.swap(
    first: Int,
    second: Int,
): List<T> =
    toMutableList().also { values ->
        val held = values[first]
        values[first] = values[second]
        values[second] = held
    }

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                title,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
            )
            content()
        }
    }
}

@Composable
private fun SettingTextField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    supporting: String,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        supportingText = { Text(supporting) },
        enabled = enabled,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun NumericField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    supporting: String,
    allowNegative: Boolean = false,
) {
    SettingTextField(
        value = value,
        onChange = { candidate ->
            if (
                candidate.isEmpty() ||
                candidate.toDoubleOrNull() != null ||
                candidate == "." ||
                (allowNegative && candidate in setOf("-", "-."))
            ) {
                onChange(candidate)
            }
        },
        label = label,
        supporting = supporting,
    )
}

@Composable
private fun NullableToggle(
    label: String,
    value: Boolean?,
    desktopDefault: Boolean,
    onChange: (Boolean?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(
                    value = value ?: desktopDefault,
                    role = Role.Checkbox,
                    onValueChange = { onChange(it) },
                ).padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label)
                Text(
                    stringResource(
                        if (value == null) {
                            if (desktopDefault) {
                                R.string.settings_default_on
                            } else {
                                R.string.settings_default_off
                            }
                        } else {
                            R.string.settings_android_override
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Checkbox(checked = value ?: desktopDefault, onCheckedChange = null)
        }
        if (value != null) {
            TextButton(onClick = { onChange(null) }) {
                Text(stringResource(R.string.settings_default_action))
            }
        }
    }
}

@Composable
private fun BooleanSetting(
    label: String,
    help: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                role = Role.Checkbox,
                onValueChange = onCheckedChange,
            ).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label)
            Text(help, style = MaterialTheme.typography.bodySmall)
        }
        Checkbox(checked = checked, onCheckedChange = null)
    }
}
