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
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.ankiminer.android.R
import com.ankiminer.android.anki.provider.AnkiExternalReviewOutcome
import com.ankiminer.android.anki.provider.AnkiFieldKeys
import com.ankiminer.android.anki.provider.AnkiFieldMapPolicy
import com.ankiminer.android.anki.provider.AnkiRemediationActionKind
import com.ankiminer.android.anki.provider.AnkiRemediationType
import com.ankiminer.android.anki.provider.NoteTypeSetupStatus
import com.ankiminer.android.data.settings.CardType
import com.ankiminer.android.ui.theme.AnkiMinerTokens
import com.ankiminer.android.ui.theme.SecondaryActionButton
import com.ankiminer.android.ui.theme.SupportingText
import com.ankiminer.android.vm.DeckChoiceKind
import com.ankiminer.android.vm.DeckPersistenceStatus
import com.ankiminer.android.vm.RecoveryPresentationKind
import com.ankiminer.android.vm.SetupUiState

@Composable
internal fun AnkiDeckCard(
    state: SetupUiState,
    onSelectDeck: (String) -> Unit,
    onRetryDeckSelection: () -> Unit,
    inlineFailure: (@Composable () -> Unit)? = null,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(AnkiMinerTokens.Space.content), verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related)) {
            Text(stringResource(R.string.anki_deck_title), style = MaterialTheme.typography.titleMedium)
            inlineFailure?.invoke()
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
                    SecondaryActionButton(
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

@Composable
internal fun AnkiTargetCard(
    state: SetupUiState,
    onSelectNoteType: (String) -> Unit,
    onSetFieldMapping: (String, String) -> Unit,
    onSelectCardType: (CardType?) -> Unit,
    onSelectCardTypeMarker: (String) -> Unit,
    onRemapFields: () -> Unit,
    inlineFailure: (@Composable () -> Unit)? = null,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(AnkiMinerTokens.Space.content), verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related)) {
            Text(stringResource(R.string.anki_note_type_title), style = MaterialTheme.typography.titleMedium)
            inlineFailure?.invoke()
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
                    isOptionEnabled = { !state.busy && it != state.noteType },
                )
                if (state.noteType != null) {
                    val fields =
                        state.availableNoteTypes
                            .firstOrNull { it.name == state.noteType }
                            ?.fieldNames
                            ?: emptyList()
                    val noneLabel = stringResource(R.string.anki_field_none)
                    val expandedLabel = stringResource(R.string.disclosure_expanded)
                    val collapsedLabel = stringResource(R.string.disclosure_collapsed)
                    val mappedCount = AnkiFieldKeys.ALL.count { !state.fieldMap[it].isNullOrEmpty() }
                    // Only `word` is actually required, so a partial map is a valid setup. Force the
                    // mapper open for real blockers only; otherwise 18 dropdowns sit collapsed.
                    val mappingBlocked =
                        state.noteTypeStatus is NoteTypeSetupStatus.FieldsMissing ||
                            state.noteTypeStatus is NoteTypeSetupStatus.FieldMapInvalid ||
                            state.noteTypeStatus == NoteTypeSetupStatus.FirstFieldMismatch
                    var mappingExpanded by
                        rememberSaveable(state.noteType) { mutableStateOf(false) }
                    val showMapping = mappingExpanded || mappingBlocked
                    TextButton(
                        onClick = { mappingExpanded = !mappingExpanded },
                        enabled = !mappingBlocked,
                        modifier =
                            Modifier.semantics {
                                heading()
                                stateDescription =
                                    if (showMapping) expandedLabel else collapsedLabel
                            },
                    ) {
                        Text(
                            stringResource(
                                R.string.anki_field_mapping_summary,
                                mappedCount,
                                AnkiFieldKeys.ALL.size,
                            ),
                        )
                    }
                    // Selecting a note type maps its fields; selecting the SAME one again does
                    // nothing. This is the way back for a map made against an older keyword table,
                    // and it has to sit outside the collapsed section to be found at all.
                    SecondaryActionButton(
                        onClick = onRemapFields,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.busy && fields.isNotEmpty(),
                    ) {
                        Text(stringResource(R.string.anki_field_mapping_remap))
                    }
                    SupportingText(stringResource(R.string.anki_field_mapping_remap_help))
                    if (showMapping) {
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
                                !state.busy &&
                                    AnkiFieldMapPolicy.isDestinationAvailable(
                                        currentFieldMap = state.fieldMap,
                                        logicalKey = key,
                                        destination = field,
                                        fieldNames = fields,
                                        reservedDestinations =
                                            setOfNotNull(state.cardTypeMarkerField),
                                    )
                            },
                        )
                    }
                    // The uniqueness rule is enforced by isDestinationAvailable disabling taken
                    // fields, and the first-field rule surfaces as a verification status.
                    if (state.fieldMapChanges.isNotEmpty()) {
                        val details =
                            state.fieldMapChanges.joinToString { change ->
                                val replacement = change.newDestination.ifEmpty { noneLabel }
                                val previous = change.previousDestination.ifEmpty { noneLabel }
                                "${change.logicalKey}: $previous → $replacement"
                            }
                        Text(
                            stringResource(R.string.anki_field_mapping_changes, details),
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                    }
                    CardTypeMarkerSection(
                        state = state,
                        fields = fields,
                        noneLabel = noneLabel,
                        onSelectCardType = onSelectCardType,
                        onSelectCardTypeMarker = onSelectCardTypeMarker,
                    )
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
            }
        }
    }
}

/**
 * JP Mining Note support: pick a card mode, then the note-type field that marks it. The engine writes
 * `"x"` into that field on every mined note; anything else about mining is unchanged.
 *
 * Both dropdowns list only the note type's real fields, so the marker can never name a field the
 * note type lacks — which the Anki verification step would reject at the start of a run.
 */
@Composable
private fun CardTypeMarkerSection(
    state: SetupUiState,
    fields: List<String>,
    noneLabel: String,
    onSelectCardType: (CardType?) -> Unit,
    onSelectCardTypeMarker: (String) -> Unit,
) {
    Text(
        stringResource(R.string.anki_card_type_title),
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.titleSmall,
    )
    SupportingText(stringResource(R.string.anki_card_type_explainer))
    NoteTypeDropdown(
        label = stringResource(R.string.anki_card_type_picker),
        options =
            listOf("" to noneLabel) +
                CardType.entries.map { it.wireValue to cardTypeLabel(it) },
        selected = state.cardType?.wireValue ?: "",
        onSelect = { selected -> onSelectCardType(CardType.fromWire(selected)) },
        isOptionEnabled = {
            !state.busy && it != (state.cardType?.wireValue ?: "")
        },
    )
    if (state.cardType != null) {
        NoteTypeDropdown(
            label = stringResource(R.string.anki_card_type_marker_field),
            options = (listOf("") + fields).map { it to it.ifEmpty { noneLabel } },
            selected = state.cardTypeMarkerField ?: "",
            onSelect = onSelectCardTypeMarker,
            // A marker sharing a destination with a mapped field is rejected at the snapshot
            // boundary, so those fields are not offered.
            isOptionEnabled = { field ->
                !state.busy &&
                    (field.isEmpty() || state.fieldMap.none { (_, mapped) -> mapped == field })
            },
        )
        if (state.cardTypeMarkerField.isNullOrEmpty()) {
            Text(
                stringResource(R.string.anki_card_type_marker_missing),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun cardTypeLabel(cardType: CardType): String =
    stringResource(
        when (cardType) {
            CardType.WORD_AND_SENTENCE -> R.string.anki_card_type_word_and_sentence
            CardType.CLICK -> R.string.anki_card_type_click
            CardType.SENTENCE -> R.string.anki_card_type_sentence
            CardType.AUDIO -> R.string.anki_card_type_audio
        },
    )

@Composable
internal fun WizardAnkiTargetCard(
    state: SetupUiState,
    onSelectNoteType: (String) -> Unit,
    onCustomizeFields: () -> Unit,
    inlineFailure: (@Composable () -> Unit)? = null,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(AnkiMinerTokens.Space.content), verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related)) {
            Text(
                stringResource(R.string.anki_note_type_title),
                style = MaterialTheme.typography.titleMedium,
            )
            inlineFailure?.invoke()
            if (!state.ankiReady) {
                Text(stringResource(R.string.anki_note_type_connect_first))
            } else {
                NoteTypeDropdown(
                    label = stringResource(R.string.anki_note_type_picker),
                    options = state.availableNoteTypes.map { it.name to it.name },
                    selected = state.noteType.orEmpty(),
                    onSelect = onSelectNoteType,
                    isOptionEnabled = { !state.busy && it != state.noteType },
                )
                if (state.noteType != null) {
                    Text(
                        stringResource(
                            R.string.b3_wizard_mapping_summary,
                            state.fieldMap.values.count(String::isNotEmpty),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    noteTypeStatusText(state.noteTypeStatus),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                TextButton(onClick = onCustomizeFields) {
                    Text(stringResource(R.string.b3_wizard_customize_fields))
                }
            }
        }
    }
}

/**
 * Warnings only. The yes/no grading and the mapped-field recap that used to live here restated the
 * field dropdowns rendered directly above it.
 */
@Composable
private fun NoteTypeQualitySummary(state: SetupUiState) {
    val quality = state.noteTypeQuality
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
        Column(Modifier.padding(AnkiMinerTokens.Space.content), verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related)) {
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
    inlineFailure: (@Composable () -> Unit)? = null,
) {
    var confirmationKey by rememberSaveable { mutableStateOf<String?>(null) }
    val presentation = state.recoveryPresentation
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(AnkiMinerTokens.Space.content), verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.group)) {
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
                        pluralStringResource(
                            R.plurals.anki_recovery_attention,
                            state.remediations.pending.size,
                            state.remediations.pending.size,
                        )
                    RecoveryPresentationKind.PENDING_PROVIDER_UNAVAILABLE ->
                        pluralStringResource(
                            R.plurals.anki_recovery_attention_provider_unavailable,
                            state.remediations.pending.size,
                            state.remediations.pending.size,
                        )
                    RecoveryPresentationKind.CLEAR ->
                        stringResource(R.string.anki_recovery_clear)
                }
            Text(statusText)
            inlineFailure?.invoke()
            if (
                presentation.kind == RecoveryPresentationKind.CHECKING ||
                    presentation.kind == RecoveryPresentationKind.INVENTORY_UNAVAILABLE
            ) {
                SecondaryActionButton(
                    onClick = onRefresh,
                    enabled = !state.busy,
                ) {
                    Text(stringResource(R.string.anki_recovery_retry_inventory))
                }
            }
            if (presentation.canReconcile) {
                SecondaryActionButton(
                    onClick = onReconcile,
                    enabled = !state.busy,
                ) {
                    Text(stringResource(R.string.anki_recovery_reconcile))
                }
            }
        }
        if (presentation.showInventory) {
            Column(Modifier.padding(horizontal = AnkiMinerTokens.Space.content, vertical = AnkiMinerTokens.Space.related)) {
                state.remediations.pending.forEach { item ->
                    HorizontalDivider()
                    Text(item.title, style = MaterialTheme.typography.titleSmall)
                    Text(item.summary)
                    if (AnkiRemediationActionKind.RETRY_STAGING_CLEANUP in item.availableActions) {
                        SecondaryActionButton(
                            onClick = { onRetryStaging(item.id) },
                            enabled = !state.busy,
                        ) { Text(stringResource(R.string.anki_recovery_retry_cleanup)) }
                    }
                    if (AnkiRemediationActionKind.ACKNOWLEDGE_UNATTACHED_MEDIA in item.availableActions) {
                        SecondaryActionButton(
                            onClick = {
                                confirmationKey =
                                    AnkiRecoveryConfirmation.UnattachedMedia(item.id).saveableKey
                            },
                            enabled = !state.busy,
                        ) { Text(stringResource(R.string.anki_recovery_acknowledge_unattached)) }
                    }
                    if (AnkiRemediationActionKind.ACKNOWLEDGE_UNCERTAIN_MEDIA in item.availableActions) {
                        Text(stringResource(R.string.anki_recovery_media_uncertain_help))
                        SecondaryActionButton(
                            onClick = {
                                confirmationKey =
                                    AnkiRecoveryConfirmation.UncertainMedia(item.id).saveableKey
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
                            confirmationKey =
                                AnkiRecoveryConfirmation.ExternalReview(id, outcome).saveableKey
                        }
                    }
                }
            }
        }
    }
    val confirmation = confirmationKey?.let { AnkiRecoveryConfirmation.fromSaveableKey(it) }
    confirmation?.let { pending ->
        AlertDialog(
            onDismissRequest = { confirmationKey = null },
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
                                R.string.anki_recovery_confirm_recorded_detail
                        },
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmationKey = null
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
                TextButton(onClick = { confirmationKey = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

private sealed interface AnkiRecoveryConfirmation {
    val remediationId: Long
    val saveableKey: String
        get() =
            when (this) {
                is UnattachedMedia -> "unattached:$remediationId"
                is UncertainMedia -> "uncertain:$remediationId"
                is ExternalReview -> "external:$remediationId:${outcome.name}"
            }

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

    companion object {
        fun fromSaveableKey(key: String): AnkiRecoveryConfirmation? {
            val parts = key.split(':', limit = 3)
            val remediationId = parts.getOrNull(1)?.toLongOrNull() ?: return null
            return when (parts.firstOrNull()) {
                "unattached" -> UnattachedMedia(remediationId)
                "uncertain" -> UncertainMedia(remediationId)
                "external" -> {
                    val outcomeName = parts.getOrNull(2) ?: return null
                    val outcome =
                        AnkiExternalReviewOutcome.entries
                            .firstOrNull { it.name == outcomeName }
                            ?: return null
                    ExternalReview(remediationId, outcome)
                }
                else -> null
            }
        }
    }
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
            SecondaryActionButton(
                onClick = { onResolve(remediationId, AnkiExternalReviewOutcome.COMMIT_CONFIRMED) },
                enabled = !busy,
            ) { Text(stringResource(R.string.anki_recovery_confirm_exists)) }
            SecondaryActionButton(
                onClick = { onResolve(remediationId, AnkiExternalReviewOutcome.NOT_COMMITTED_CONFIRMED) },
                enabled = !busy,
            ) { Text(stringResource(R.string.anki_recovery_confirm_missing)) }
        }
        AnkiRemediationType.NOTE_COMMITTED_FAILED,
        AnkiRemediationType.CARD_ROUTING_FAILED,
        -> SecondaryActionButton(
            onClick = {
                onResolve(
                    remediationId,
                    AnkiExternalReviewOutcome.CURRENT_STATE_ACCEPTED_OR_CORRECTED,
                )
            },
            enabled = !busy,
        ) { Text(stringResource(R.string.anki_recovery_confirm_corrected)) }
        AnkiRemediationType.CAPACITY_EXHAUSTED -> SecondaryActionButton(
            onClick = { onResolve(remediationId, AnkiExternalReviewOutcome.CAPACITY_AVAILABLE) },
            enabled = !busy,
        ) { Text(stringResource(R.string.anki_recovery_confirm_capacity)) }
        AnkiRemediationType.MEDIA_COMMIT_UNCERTAIN,
        AnkiRemediationType.MEDIA_STORED_UNATTACHED,
        AnkiRemediationType.STAGING_QUARANTINED,
        -> Unit
    }
}
