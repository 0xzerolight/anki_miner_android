package com.ankiminer.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ankiminer.android.R
import com.ankiminer.android.anki.provider.AnkiExternalReviewOutcome
import com.ankiminer.android.anki.provider.AnkiFieldKeys
import com.ankiminer.android.anki.provider.AnkiProviderReadiness
import com.ankiminer.android.anki.provider.AnkiRemediationActionKind
import com.ankiminer.android.anki.provider.AnkiRemediationType
import com.ankiminer.android.anki.provider.NoteTypeSetupStatus
import com.ankiminer.android.vm.SetupUiState

@Composable
internal fun AnkiTargetCard(
    state: SetupUiState,
    onSelectNoteType: (String) -> Unit,
    onSetFieldMapping: (String, String) -> Unit,
    onVerify: () -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.anki_note_type_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.anki_note_type_description))
            if (!state.ankiReady) {
                Text(stringResource(R.string.anki_note_type_connect_first))
            } else {
                NoteTypeDropdown(
                    label = stringResource(R.string.anki_note_type_picker),
                    options = state.availableNoteTypes.map { it.name to it.name },
                    selected = state.noteType ?: "",
                    onSelect = onSelectNoteType,
                )
                if (state.noteType != null) {
                    val fields =
                        state.availableNoteTypes
                            .firstOrNull { it.name == state.noteType }
                            ?.fieldNames
                            ?: emptyList()
                    Text(
                        stringResource(R.string.anki_field_mapping_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    val noneLabel = stringResource(R.string.anki_field_none)
                    AnkiFieldKeys.ALL.forEach { key ->
                        val base = key.replace('_', ' ').replaceFirstChar { it.uppercaseChar() }
                        val label = if (key in AnkiFieldKeys.REQUIRED) "$base *" else base
                        NoteTypeDropdown(
                            label = label,
                            options = listOf("" to noneLabel) + fields.map { it to it },
                            selected = state.fieldMap[key] ?: "",
                            onSelect = { field -> onSetFieldMapping(key, field) },
                        )
                        if (key == AnkiFieldKeys.WORD) {
                            Text(
                                stringResource(R.string.anki_field_word_help),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                Text(
                    noteTypeStatusText(state.noteTypeStatus),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                if (state.noteTypeStatus is NoteTypeSetupStatus.FieldsMissing) {
                    Text(stringResource(R.string.anki_note_type_guidance))
                }
                OutlinedButton(
                    onClick = onVerify,
                    enabled = state.ankiReady && !state.busy && state.noteType != null,
                ) { Text(stringResource(R.string.anki_note_type_verify)) }
            }
        }
    }
}

@Composable
private fun noteTypeStatusText(status: NoteTypeSetupStatus): String =
    stringResource(
        when (status) {
            is NoteTypeSetupStatus.Verified -> R.string.anki_note_type_status_verified
            NoteTypeSetupStatus.NotSelected -> R.string.anki_note_type_status_not_selected
            NoteTypeSetupStatus.NoteTypeMissing -> R.string.anki_note_type_status_missing
            is NoteTypeSetupStatus.FieldsMissing -> R.string.anki_note_type_status_fields_missing
            NoteTypeSetupStatus.FirstFieldMismatch -> R.string.anki_note_type_status_first_field
            is NoteTypeSetupStatus.ProviderError -> R.string.anki_note_type_status_provider_error
        },
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteTypeDropdown(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selected }?.second ?: selected
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { (value, display) ->
                DropdownMenuItem(
                    text = { Text(display) },
                    onClick = {
                        onSelect(value)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
internal fun AnkiOperationCard() {
    OutlinedCard(
        Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.anki_setup_working), style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
internal fun AnkiRecoveryCard(
    state: SetupUiState,
    onReconcile: () -> Unit,
    onRetryStaging: (Long) -> Unit,
    onAcknowledgeMedia: (Long) -> Unit,
    onAcknowledgeUncertainMedia: (Long) -> Unit,
    onResolveReview: (Long, AnkiExternalReviewOutcome) -> Unit,
) {
    var confirmation by remember { mutableStateOf<AnkiRecoveryConfirmation?>(null) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.anki_recovery_title), style = MaterialTheme.typography.titleMedium)
            if (state.remediations.pending.isEmpty()) {
                Text(stringResource(R.string.anki_recovery_clear))
            } else {
                Text(stringResource(R.string.anki_recovery_attention, state.remediations.pending.size))
            }
            if (state.anki == AnkiProviderReadiness.RecoveryBlocked || state.remediations.pending.isNotEmpty()) {
                OutlinedButton(onClick = onReconcile, enabled = !state.busy) {
                    Text(stringResource(R.string.anki_recovery_reconcile))
                }
            }
            state.remediations.pending.forEach { item ->
                HorizontalDivider()
                Text(item.title, style = MaterialTheme.typography.titleSmall)
                Text(item.summary)
                if (AnkiRemediationActionKind.RETRY_STAGING_CLEANUP in item.availableActions) {
                    OutlinedButton(
                        onClick = { onRetryStaging(item.id) },
                        enabled = !state.busy,
                    ) { Text(stringResource(R.string.anki_recovery_retry_cleanup)) }
                }
                if (AnkiRemediationActionKind.ACKNOWLEDGE_UNATTACHED_MEDIA in item.availableActions) {
                    OutlinedButton(
                        onClick = {
                            confirmation = AnkiRecoveryConfirmation.UnattachedMedia(item.id)
                        },
                        enabled = !state.busy,
                    ) { Text(stringResource(R.string.anki_recovery_acknowledge_unattached)) }
                }
                if (AnkiRemediationActionKind.ACKNOWLEDGE_UNCERTAIN_MEDIA in item.availableActions) {
                    Text(stringResource(R.string.anki_recovery_media_uncertain_help))
                    OutlinedButton(
                        onClick = {
                            confirmation = AnkiRecoveryConfirmation.UncertainMedia(item.id)
                        },
                        enabled = !state.busy,
                    ) { Text(stringResource(R.string.anki_recovery_abandon_uncertain_media)) }
                }
                if (AnkiRemediationActionKind.RESOLVE_AFTER_EXTERNAL_REVIEW in item.availableActions) {
                    ExternalReviewActions(item.id, item.type, state.busy) { id, outcome ->
                        confirmation = AnkiRecoveryConfirmation.ExternalReview(id, outcome)
                    }
                }
            }
        }
    }
    confirmation?.let { pending ->
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text(stringResource(R.string.anki_recovery_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        when (pending) {
                            is AnkiRecoveryConfirmation.UnattachedMedia ->
                                R.string.anki_recovery_confirm_unattached_detail
                            is AnkiRecoveryConfirmation.UncertainMedia ->
                                R.string.anki_recovery_confirm_uncertain_detail
                            is AnkiRecoveryConfirmation.ExternalReview ->
                                when (pending.outcome) {
                                    AnkiExternalReviewOutcome.COMMIT_CONFIRMED ->
                                        R.string.anki_recovery_confirm_exists_detail
                                    AnkiExternalReviewOutcome.NOT_COMMITTED_CONFIRMED ->
                                        R.string.anki_recovery_confirm_missing_detail
                                    AnkiExternalReviewOutcome.CURRENT_STATE_ACCEPTED_OR_CORRECTED ->
                                        R.string.anki_recovery_confirm_corrected_detail
                                    AnkiExternalReviewOutcome.CAPACITY_AVAILABLE ->
                                        R.string.anki_recovery_confirm_capacity_detail
                                }
                        },
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmation = null
                        when (pending) {
                            is AnkiRecoveryConfirmation.UnattachedMedia ->
                                onAcknowledgeMedia(pending.remediationId)
                            is AnkiRecoveryConfirmation.UncertainMedia ->
                                onAcknowledgeUncertainMedia(pending.remediationId)
                            is AnkiRecoveryConfirmation.ExternalReview ->
                                onResolveReview(pending.remediationId, pending.outcome)
                        }
                    },
                ) { Text(stringResource(R.string.anki_recovery_confirm_action)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmation = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

private sealed interface AnkiRecoveryConfirmation {
    val remediationId: Long

    data class UnattachedMedia(
        override val remediationId: Long,
    ) : AnkiRecoveryConfirmation

    data class UncertainMedia(
        override val remediationId: Long,
    ) : AnkiRecoveryConfirmation

    data class ExternalReview(
        override val remediationId: Long,
        val outcome: AnkiExternalReviewOutcome,
    ) : AnkiRecoveryConfirmation
}

@Composable
private fun ExternalReviewActions(
    remediationId: Long,
    type: AnkiRemediationType,
    busy: Boolean,
    onResolve: (Long, AnkiExternalReviewOutcome) -> Unit,
) {
    when (type) {
        AnkiRemediationType.DECK_COMMIT_UNCERTAIN,
        AnkiRemediationType.NOTE_COMMIT_UNCERTAIN,
        -> {
            Text(stringResource(R.string.anki_recovery_review_in_ankidroid))
            OutlinedButton(
                onClick = { onResolve(remediationId, AnkiExternalReviewOutcome.COMMIT_CONFIRMED) },
                enabled = !busy,
            ) { Text(stringResource(R.string.anki_recovery_confirm_exists)) }
            OutlinedButton(
                onClick = { onResolve(remediationId, AnkiExternalReviewOutcome.NOT_COMMITTED_CONFIRMED) },
                enabled = !busy,
            ) { Text(stringResource(R.string.anki_recovery_confirm_missing)) }
        }
        AnkiRemediationType.NOTE_COMMITTED_FAILED,
        AnkiRemediationType.CARD_ROUTING_FAILED,
        -> OutlinedButton(
            onClick = {
                onResolve(
                    remediationId,
                    AnkiExternalReviewOutcome.CURRENT_STATE_ACCEPTED_OR_CORRECTED,
                )
            },
            enabled = !busy,
        ) { Text(stringResource(R.string.anki_recovery_confirm_corrected)) }
        AnkiRemediationType.CAPACITY_EXHAUSTED -> OutlinedButton(
            onClick = { onResolve(remediationId, AnkiExternalReviewOutcome.CAPACITY_AVAILABLE) },
            enabled = !busy,
        ) { Text(stringResource(R.string.anki_recovery_confirm_capacity)) }
        AnkiRemediationType.MEDIA_COMMIT_UNCERTAIN,
        AnkiRemediationType.MEDIA_STORED_UNATTACHED,
        AnkiRemediationType.STAGING_QUARANTINED,
        -> Unit
    }
}
