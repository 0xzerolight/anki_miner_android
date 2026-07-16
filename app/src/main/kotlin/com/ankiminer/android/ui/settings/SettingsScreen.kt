package com.ankiminer.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ankiminer.android.R
import com.ankiminer.android.data.settings.AppSettings
import com.ankiminer.android.data.settings.AppSettingsDraftParser
import com.ankiminer.android.data.settings.AudioFormat
import com.ankiminer.android.vm.SettingsViewModel

@Composable
internal fun SettingsRoute(
    viewModel: SettingsViewModel,
    onAttributions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    SettingsScreen(
        settings = settings,
        saving = saving,
        error = error,
        onSave = viewModel::save,
        onRestoreDefaults = viewModel::restoreDesktopDefaults,
        onDismissError = viewModel::dismissError,
        onAttributions = onAttributions,
        modifier = modifier,
    )
}

@Composable
private fun SettingsScreen(
    settings: AppSettings,
    saving: Boolean,
    error: String?,
    onSave: (AppSettings) -> Unit,
    onRestoreDefaults: () -> Unit,
    onDismissError: () -> Unit,
    onAttributions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var deckName by remember(settings.deckName) { mutableStateOf(settings.deckName.orEmpty()) }
    var noteType by remember(settings.noteType) { mutableStateOf(settings.noteType.orEmpty()) }
    var tags by remember(settings.tags) { mutableStateOf(settings.tags.orEmpty()) }
    var tagsOverride by remember(settings.tags) { mutableStateOf(settings.tags != null) }
    var audioPadding by remember(settings.audioPaddingSeconds) { mutableStateOf(settings.audioPaddingSeconds?.toString().orEmpty()) }
    var screenshotOffset by remember(settings.screenshotOffsetSeconds) { mutableStateOf(settings.screenshotOffsetSeconds?.toString().orEmpty()) }
    var subtitleOffset by remember(settings.subtitleOffsetSeconds) { mutableStateOf(settings.subtitleOffsetSeconds?.toString().orEmpty()) }
    var bitrate by remember(settings.audioBitrateKbps) { mutableStateOf(settings.audioBitrateKbps?.toString().orEmpty()) }
    var maxDuration by remember(settings.maxSentenceDurationSeconds) { mutableStateOf(settings.maxSentenceDurationSeconds?.toString().orEmpty()) }
    var maxCharacters by remember(settings.maxSentenceCharacters) { mutableStateOf(settings.maxSentenceCharacters?.toString().orEmpty()) }
    var workers by remember(settings.maxParallelWorkers) { mutableStateOf(settings.maxParallelWorkers?.toString().orEmpty()) }
    var audioFormat by remember(settings.audioFormat) { mutableStateOf(settings.audioFormat) }
    var knownWords by remember(settings.useKnownWordsDatabase) { mutableStateOf(settings.useKnownWordsDatabase) }
    var hiragana by remember(settings.excludeHiraganaOnly) { mutableStateOf(settings.excludeHiraganaOnly) }
    var katakana by remember(settings.excludeKatakanaOnly) { mutableStateOf(settings.excludeKatakanaOnly) }
    var boldTarget by remember(settings.boldTargetInSentence) { mutableStateOf(settings.boldTargetInSentence) }
    var deduplicate by remember(settings.deduplicateSentences) { mutableStateOf(settings.deduplicateSentences) }
    var iPlusOne by remember(settings.useIPlusOneFilter) { mutableStateOf(settings.useIPlusOneFilter) }
    var sentenceLength by remember(settings.useSentenceLengthFilter) { mutableStateOf(settings.useSentenceLengthFilter) }
    var jisho by remember(settings.jishoEnabled) { mutableStateOf(settings.jishoEnabled) }
    val numericDraftValid =
        listOf(audioPadding, screenshotOffset, subtitleOffset, maxDuration)
            .all(AppSettingsDraftParser::isOptionalDouble) &&
            listOf(bitrate, maxCharacters, workers)
                .all(AppSettingsDraftParser::isOptionalInt)

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.settings_intro))

        SettingsSection(stringResource(R.string.settings_anki_target)) {
            SettingTextField(
                deckName,
                { deckName = it },
                stringResource(R.string.settings_deck_name),
                stringResource(R.string.settings_deck_default),
            )
            SettingTextField(
                noteType,
                { noteType = it },
                stringResource(R.string.settings_note_type),
                stringResource(R.string.settings_note_default),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_tags_override))
                    Text(
                        stringResource(R.string.settings_tags_override_help),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Checkbox(checked = tagsOverride, onCheckedChange = { tagsOverride = it })
            }
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    null to stringResource(R.string.settings_desktop_default),
                    AudioFormat.MP3 to stringResource(R.string.settings_mp3),
                    AudioFormat.OPUS to stringResource(R.string.settings_opus),
                )
                    .forEach { (value, label) ->
                        OutlinedButton(onClick = { audioFormat = value }) {
                            Text(if (audioFormat == value) "✓ $label" else label)
                        }
                    }
            }
        }

        SettingsSection(stringResource(R.string.settings_filtering)) {
            NullableToggle(stringResource(R.string.settings_known_words), knownWords, false) { knownWords = it }
            NullableToggle(stringResource(R.string.settings_exclude_hiragana), hiragana, false) { hiragana = it }
            NullableToggle(stringResource(R.string.settings_exclude_katakana), katakana, false) { katakana = it }
            NullableToggle(stringResource(R.string.settings_bold_target), boldTarget, false) { boldTarget = it }
            NullableToggle(stringResource(R.string.settings_deduplicate), deduplicate, true) { deduplicate = it }
            NullableToggle(stringResource(R.string.settings_i_plus_one), iPlusOne, false) { iPlusOne = it }
            NullableToggle(stringResource(R.string.settings_sentence_length), sentenceLength, false) { sentenceLength = it }
            NumericField(maxDuration, { maxDuration = it }, stringResource(R.string.settings_max_duration), stringResource(R.string.settings_zero_default))
            NumericField(maxCharacters, { maxCharacters = it }, stringResource(R.string.settings_max_characters), stringResource(R.string.settings_zero_default))
            NumericField(workers, { workers = it }, stringResource(R.string.settings_workers), stringResource(R.string.settings_workers_default))
        }

        SettingsSection(stringResource(R.string.settings_online_fallback)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_jisho))
                    Text(
                        stringResource(R.string.settings_jisho_disclosure),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Checkbox(checked = jisho, onCheckedChange = { jisho = it })
            }
        }

        error?.let {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismissError) { Text(stringResource(R.string.dismiss)) }
                }
            }
        }

        if (!numericDraftValid) {
            Text(
                stringResource(R.string.settings_numeric_incomplete),
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
                        noteType = noteType.takeIf(String::isNotEmpty),
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
                        maxParallelWorkers = AppSettingsDraftParser.optionalInt(workers),
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
        HorizontalDivider()
        TextButton(onClick = onAttributions) { Text(stringResource(R.string.settings_attributions)) }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
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
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
            Text(label)
            Text(
                stringResource(
                    if (value == null) {
                        if (desktopDefault) R.string.settings_default_on else R.string.settings_default_off
                    } else {
                        R.string.settings_android_override
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Checkbox(checked = value ?: desktopDefault, onCheckedChange = { onChange(it) })
        if (value != null) {
            TextButton(onClick = { onChange(null) }) {
                Text(stringResource(R.string.settings_default_action))
            }
        }
    }
}
