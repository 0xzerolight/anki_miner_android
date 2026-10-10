package com.ankiminer.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import com.ankiminer.android.R
import com.ankiminer.android.data.resources.DictionaryUpdateResult
import com.ankiminer.android.data.resources.DictionaryUpdateUiState
import com.ankiminer.android.ui.theme.AnkiMinerTokens
import com.ankiminer.android.ui.theme.SecondaryActionButton
import com.ankiminer.android.ui.theme.SupportingText

/**
 * Desktop's "Updates" section of Settings → Dictionaries: the weekly switch, Update Now, and the
 * line desktop shows on its status bar. It covers frequency and pitch sources too, as desktop's
 * does, but sits under the dictionary panel because that is where desktop puts it.
 */
@Composable
internal fun DictionaryUpdatesSection(
    automatic: Boolean,
    onAutomaticChange: (Boolean) -> Unit,
    state: DictionaryUpdateUiState,
    onUpdateNow: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related),
    ) {
        Text(
            stringResource(R.string.dictionary_updates_heading),
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleSmall,
        )
        // TriStateSetting only ever On or Off here: unlike BooleanSetting's one-line label, its label
        // wraps, and desktop's wording does not fit one line at 320dp.
        TriStateSetting(
            label = stringResource(R.string.dictionary_updates_automatic),
            state = ToggleableState(automatic),
            onClick = { onAutomaticChange(!automatic) },
        )
        SupportingText(stringResource(R.string.dictionary_updates_help))
        SecondaryActionButton(
            onClick = onUpdateNow,
            enabled = !state.running,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.dictionary_updates_now))
        }
        dictionaryUpdateLines(state.result).forEach { line ->
            Text(line, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun dictionaryUpdateLines(result: DictionaryUpdateResult?): List<String> =
    when (result) {
        null -> emptyList()
        DictionaryUpdateResult.Checking -> listOf(stringResource(R.string.dictionary_updates_checking))
        DictionaryUpdateResult.Downloading -> listOf(stringResource(R.string.dictionary_updates_downloading))
        is DictionaryUpdateResult.UpToDate ->
            listOf(
                if (result.uncheckedCount > 0) {
                    stringResource(R.string.dictionary_updates_up_to_date_unchecked, result.uncheckedCount)
                } else {
                    stringResource(R.string.dictionary_updates_up_to_date)
                },
            )
        DictionaryUpdateResult.CouldNotCheck -> listOf(stringResource(R.string.dictionary_updates_could_not_check))
        DictionaryUpdateResult.NothingPublishes -> listOf(stringResource(R.string.dictionary_updates_none_publish))
        DictionaryUpdateResult.ChangedDuringCheck -> listOf(stringResource(R.string.dictionary_updates_changed))
        DictionaryUpdateResult.Busy -> listOf(stringResource(R.string.dictionary_updates_busy))
        is DictionaryUpdateResult.Finished ->
            listOfNotNull(
                result.updated.takeIf { it.isNotEmpty() }?.let {
                    stringResource(R.string.dictionary_updates_updated, it.joinToString())
                },
                result.failed.takeIf { it.isNotEmpty() }?.let {
                    stringResource(R.string.dictionary_updates_failed, it.joinToString())
                },
            )
    }
