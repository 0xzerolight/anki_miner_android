package com.ankiminer.android.ui.mining

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ankiminer.android.R
import com.ankiminer.android.data.resources.InstalledAudioPack
import com.ankiminer.android.ui.theme.AnkiMinerTokens
import com.ankiminer.android.ui.theme.SupportingText
import com.ankiminer.android.ui.theme.accentTextButtonColors

enum class SentenceAudioAdvisory { NONE, UNMAPPED, UNMAPPED_COVER_ART_ONLY }

/** Field-map gaps worth one quiet line above Mine; none of them blocks a run. */
@Immutable
data class MiningFieldAdvisories(
    val sentenceAudio: SentenceAudioAdvisory = SentenceAudioAdvisory.NONE,
    val wordAudioUnmapped: Boolean = false,
    val unusableAudioPack: Boolean = false,
) {
    val needsFieldMapping: Boolean
        get() = sentenceAudio != SentenceAudioAdvisory.NONE || wordAudioUnmapped

    val any: Boolean
        get() = needsFieldMapping || unusableAudioPack
}

/** Every lane's advisories from the active field map and audio packs (D4: whenever sentence audio is unmapped). */
internal fun miningFieldAdvisories(
    fieldMap: Map<String, String>,
    audioPacks: List<InstalledAudioPack>,
    audioLane: Boolean,
): MiningFieldAdvisories {
    val usablePack = audioPacks.any { it.contentAvailable && it.entryCount > 0 }
    return MiningFieldAdvisories(
        sentenceAudio =
            when {
                !fieldMap["audio"].isNullOrBlank() -> SentenceAudioAdvisory.NONE
                // An audio run with Picture mapped keeps only words whose file has cover art.
                audioLane && !fieldMap["picture"].isNullOrBlank() -> SentenceAudioAdvisory.UNMAPPED_COVER_ART_ONLY
                else -> SentenceAudioAdvisory.UNMAPPED
            },
        // Word audio needs a usable pack to come from; with none there is nothing to warn about.
        wordAudioUnmapped = fieldMap["expression_audio"].isNullOrBlank() && usablePack,
        unusableAudioPack = audioPacks.any { !(it.contentAvailable && it.entryCount > 0) },
    )
}

/** One quiet line, not a red card: Mine stays enabled, and "Map fields" is the one fix. */
@Composable
internal fun MiningAdvisoryLines(
    advisories: MiningFieldAdvisories,
    onMapFields: () -> Unit,
    mapFieldsTestTag: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.micro),
    ) {
        if (advisories.needsFieldMapping) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(advisories.fieldMessage()),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = onMapFields,
                    modifier = Modifier.heightIn(min = 48.dp).testTag(mapFieldsTestTag),
                    colors = accentTextButtonColors(),
                ) {
                    Text(stringResource(R.string.mining_advisory_map_fields))
                }
            }
        }
        if (advisories.unusableAudioPack) {
            SupportingText(stringResource(R.string.audio_pack_unusable_warning))
        }
    }
}

@StringRes
private fun MiningFieldAdvisories.fieldMessage(): Int =
    when {
        sentenceAudio == SentenceAudioAdvisory.UNMAPPED_COVER_ART_ONLY -> R.string.audio_field_unmapped_warning
        sentenceAudio == SentenceAudioAdvisory.UNMAPPED && wordAudioUnmapped ->
            R.string.mining_advisory_sentence_and_word_audio
        sentenceAudio == SentenceAudioAdvisory.UNMAPPED -> R.string.mining_advisory_sentence_audio
        else -> R.string.expression_audio_field_unmapped_warning
    }
