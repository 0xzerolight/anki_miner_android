package com.ankiminer.android.ui.settings

import androidx.annotation.StringRes
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
import com.ankiminer.android.anki.provider.AnkiFieldMapPolicy
import com.ankiminer.android.anki.provider.AnkiFieldKeys
import com.ankiminer.android.anki.provider.AnkiRemediationActionKind
import com.ankiminer.android.anki.provider.AnkiRemediationType
import com.ankiminer.android.anki.provider.NoteTypeSetupStatus
import com.ankiminer.android.vm.DeckChoiceKind
import com.ankiminer.android.vm.DeckPersistenceStatus
import com.ankiminer.android.vm.RecoveryPresentationKind
import com.ankiminer.android.vm.SetupUiState

@Composable
internal fun AnkiDeckCard(
    state: SetupUiState,
    onSelectDeck: (String) -> Unit,
    onRetryDeckSelection: () -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.anki_deck_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.anki_deck_description))
            if (!state.ankiReady) {
                Text(stringResource(R.string.anki_deck_connect_first))
            }
            val resolution = state.deckSelection
            NoteTypeDropdown(
                label = stringResource(R.string.anki_deck_picker),
                options =
                    resolution.choices.map { choice ->
                        choice.deckName to
                            when (choice.kind) {
                                DeckChoiceKind.CREATE_OR_USE_DEFAULT ->
                                    stringResource(R.string.anki_deck_create_or_use_default)
                                DeckChoiceKind.EXISTING -> choice.deckName
                                DeckChoiceKind.SAVED_UNAVAILABLE ->
                                    stringResource(
                                        R.string.anki_deck_saved_unavailable,
                                        choice.deckName,
                                    )
                            }
                    },
                selected = resolution.selectedDeckName,
                onSelect = onSelectDeck,
                isOptionEnabled = { !state.busy && it != resolution.selectedDeckName },
            )
            val selectedChoice = resolution.choices.single { it.selected }
            Text(
                stringResource(deckExplanationResource(selectedChoice.kind)),
                style = MaterialTheme.typography.bodySmall,
            )
            when (state.deckPersistence) {
                DeckPersistenceStatus.IDLE -> Unit
                DeckPersistenceStatus.SAVING -> {
                    Text(stringResource(R.string.anki_deck_saving))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                DeckPersistenceStatus.FAILED -> {
                    Text(
                        stringResource(R.string.anki_deck_save_failed),
                        color = MaterialTheme.colorScheme.error,
                    )
                    OutlinedButton(
                        onClick = onRetryDeckSelection,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.busy,
                    ) {
                        Text(stringResource(R.string.anki_deck_save_retry))
                    }
                }
            }
        }
    }
}

@StringRes
internal fun deckExplanationResource(kind: DeckChoiceKind): Int =
    when (kind) {
        DeckChoiceKind.CREATE_OR_USE_DEFAULT -> R.string.anki_deck_default_explanation
        DeckChoiceKind.EXISTING -> R.string.anki_deck_existing_explanation
        DeckChoiceKind.SAVED_UNAVAILABLE -> R.string.anki_deck_saved_unavailable_explanation
    }

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
                    onSelect = { selected ->
                        if (selected != state.noteType) onSelectNoteType(selected)
                    },
                    isOptionEnabled = { it != state.noteType },
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
                            options =
                                AnkiFieldMapPolicy
                                    .destinationOptions(key, fields)
                                    .map { destination ->
                                        destination to destination.ifEmpty { noneLabel }
                                    },
                            selected = state.fieldMap[key] ?: "",
                            onSelect = { field -> onSetFieldMapping(key, field) },
                            isOptionEnabled = { field ->
                                AnkiFieldMapPolicy.isDestinationAvailable(
                                    currentFieldMap = state.fieldMap,
                                    logicalKey = key,
                                    destination = field,
                                    fieldNames = fields,
                                )
                            },
                        )
                        if (key == AnkiFieldKeys.WORD) {
                            Text(
                                stringResource(R.string.anki_field_word_help),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    Text(
                        stringResource(R.string.anki_field_unique_help),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (state.fieldMapChanges.isNotEmpty()) {
                        val details =
                            state.fieldMapChanges.joinToString { change ->
                                val replacement = change.newDestination.ifEmpty { noneLabel }
                                "${change.logicalKey}: ${change.previousDestination} → $replacement"
                            }
                        Text(
                            stringResource(R.string.anki_field_mapping_changes, details),
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }
                Text(
                    noteTypeStatusText(state.noteTypeStatus),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                if (state.noteTypeStatus is NoteTypeSetupStatus.FieldsMissing) {
                    Text(stringResource(R.string.anki_note_type_guidance))
                }
                if (state.noteType != null) {
                    NoteTypeQualitySummary(state)
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
private fun NoteTypeQualitySummary(state: SetupUiState) {
    val quality = state.noteTypeQuality
    val none = stringResource(R.string.anki_field_none)
    fun display(value: String): String = value.ifEmpty { none }
    fun display(values: List<String>): String = values.joinToString().ifEmpty { none }

    HorizontalDivider()
    Text(stringResource(R.string.anki_quality_title), style = MaterialTheme.typography.titleSmall)
    Text(
        stringResource(
            if (quality.writableAndDedupSafe) {
                R.string.anki_quality_writable_yes
            } else {
                R.string.anki_quality_writable_no
            },
        ),
    )
    Text(
        stringResource(
            if (quality.usefulForMining) {
                R.string.anki_quality_useful_yes
            } else {
                R.string.anki_quality_useful_no
            },
        ),
    )
    Text(
        stringResource(
            if (quality.fullyEnriched) {
                R.string.anki_quality_enriched_yes
            } else {
                R.string.anki_quality_enriched_no
            },
        ),
    )
    Text(stringResource(R.string.anki_quality_mapped_fields), style = MaterialTheme.typography.titleSmall)
    Text(stringResource(R.string.anki_quality_word_field, display(quality.fields.word)))
    Text(stringResource(R.string.anki_quality_sentence_field, display(quality.fields.sentence)))
    Text(stringResource(R.string.anki_quality_definition_fields, display(quality.fields.definitions)))
    Text(stringResource(R.string.anki_quality_audio_fields, display(quality.fields.audio)))
    Text(stringResource(R.string.anki_quality_image_field, display(quality.fields.image)))
    if (quality.writableAndDedupSafe && !quality.usefulForMining) {
        Text(
            stringResource(R.string.anki_quality_limited_warning),
            color = MaterialTheme.colorScheme.tertiary,
        )
    } else if (quality.usefulForMining && !quality.fullyEnriched) {
        Text(stringResource(R.string.anki_quality_optional_warning))
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
            is NoteTypeSetupStatus.FieldMapInvalid -> R.string.anki_note_type_status_field_map_invalid
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
    isOptionEnabled: (String) -> Boolean = { true },
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
                    enabled = isOptionEnabled(value),
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
    onRefresh: () -> Unit,
    onReconcile: () -> Unit,
    onRetryStaging: (Long) -> Unit,
    onAcknowledgeMedia: (Long) -> Unit,
    onAcknowledgeUncertainMedia: (Long) -> Unit,
    onResolveReview: (Long, AnkiExternalReviewOutcome) -> Unit,
) {
    var confirmation by remember { mutableStateOf<AnkiRecoveryConfirmation?>(null) }
    val presentation = state.recoveryPresentation
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.anki_recovery_title), style = MaterialTheme.typography.titleMedium)
            val statusText =
                when (presentation.kind) {
                    RecoveryPresentationKind.CHECKING ->
                        stringResource(R.string.anki_recovery_checking)
                    RecoveryPresentationKind.INVENTORY_UNAVAILABLE ->
                        stringResource(R.string.anki_recovery_inventory_unavailable)
                    RecoveryPresentationKind.STARTUP_BLOCKED ->
                        stringResource(R.string.anki_recovery_startup_blocked)
                    RecoveryPresentationKind.STARTUP_BLOCKED_PROVIDER_UNAVAILABLE ->
                        stringResource(R.string.anki_recovery_startup_blocked_provider_unavailable)
                    RecoveryPresentationKind.PENDING ->
                        stringResource(
                            R.string.anki_recovery_attention,
                            state.remediations.pending.size,
                        )
                    RecoveryPresentationKind.PENDING_PROVIDER_UNAVAILABLE ->
                        stringResource(
                            R.string.anki_recovery_attention_provider_unavailable,
                            state.remediations.pending.size,
                        )
                    RecoveryPresentationKind.CLEAR ->
                        stringResource(R.string.anki_recovery_clear)
                }
            Text(statusText)
            if (
                presentation.kind == RecoveryPresentationKind.CHECKING ||
                    presentation.kind == RecoveryPresentationKind.INVENTORY_UNAVAILABLE
            ) {
                OutlinedButton(onClick = onRefresh, enabled = !state.busy) {
                    Text(stringResource(R.string.anki_recovery_retry_inventory))
                }
            }
            if (presentation.canReconcile) {
                OutlinedButton(onClick = onReconcile, enabled = !state.busy) {
                    Text(stringResource(R.string.anki_recovery_reconcile))
                }
            }
        }
        if (presentation.showInventory) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
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
                        ExternalReviewActions(
                            item.id,
                            item.type,
                            state.busy || !state.ankiReady,
                        ) { id, outcome ->
                            confirmation = AnkiRecoveryConfirmation.ExternalReview(id, outcome)
                        }
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
