package com.ankiminer.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ankiminer.android.R
import com.ankiminer.android.data.resources.ResourceStartupReadiness
import com.ankiminer.android.vm.AnkiDroidSetupAction
import com.ankiminer.android.vm.SetupUiState

internal enum class SetupTaskId {
    RUNTIME,
    ANKIDROID,
    NOTE_TYPE,
    RECOVERY,
    UNIDIC,
    NOTIFICATIONS,
}

internal enum class SetupTaskRole {
    SUCCESS,
    OPTIONAL_WARNING,
    REQUIRED_ACTION,
    BUSY,
}

internal enum class SetupSummaryKind {
    READY,
    READY_WITH_OPTIONAL_WARNING,
    ATTENTION,
    BUSY,
}

internal data class SetupTaskFacts(
    val ankiReady: Boolean,
    val noteTypeReady: Boolean,
    val recoveryReady: Boolean,
    val uniDicReady: Boolean,
    val notificationReady: Boolean,
    val busy: Boolean,
    val runtimeReady: Boolean = true,
) {
    companion object {
        fun ready(): SetupTaskFacts =
            SetupTaskFacts(
                ankiReady = true,
                noteTypeReady = true,
                recoveryReady = true,
                uniDicReady = true,
                notificationReady = true,
                busy = false,
                runtimeReady = true,
            )
    }
}

internal data class SetupTaskRow(
    val id: SetupTaskId,
    val role: SetupTaskRole,
)

internal data class SetupTaskStatus(
    val summary: SetupSummaryKind,
    val requiredAttentionCount: Int,
    val rows: List<SetupTaskRow>,
)

internal fun setupTaskStatus(facts: SetupTaskFacts): SetupTaskStatus {
    val required =
        listOf(
            SetupTaskId.RUNTIME to facts.runtimeReady,
            SetupTaskId.ANKIDROID to facts.ankiReady,
            SetupTaskId.NOTE_TYPE to facts.noteTypeReady,
            SetupTaskId.RECOVERY to facts.recoveryReady,
            SetupTaskId.UNIDIC to facts.uniDicReady,
        )
    val rows =
        if (facts.busy) {
            (required.map { it.first } + SetupTaskId.NOTIFICATIONS)
                .map { SetupTaskRow(it, SetupTaskRole.BUSY) }
        } else {
            required.map { (id, ready) ->
                SetupTaskRow(
                    id,
                    if (ready) SetupTaskRole.SUCCESS else SetupTaskRole.REQUIRED_ACTION,
                )
            } +
                SetupTaskRow(
                    SetupTaskId.NOTIFICATIONS,
                    if (facts.notificationReady) {
                        SetupTaskRole.SUCCESS
                    } else {
                        SetupTaskRole.OPTIONAL_WARNING
                    },
                )
        }
    val requiredAttentionCount =
        if (facts.busy) 0 else rows.count { it.role == SetupTaskRole.REQUIRED_ACTION }
    val summary =
        when {
            facts.busy -> SetupSummaryKind.BUSY
            requiredAttentionCount > 0 -> SetupSummaryKind.ATTENTION
            rows.any { it.role == SetupTaskRole.OPTIONAL_WARNING } ->
                SetupSummaryKind.READY_WITH_OPTIONAL_WARNING
            else -> SetupSummaryKind.READY
        }
    return SetupTaskStatus(summary, requiredAttentionCount, rows)
}

private fun SetupUiState.taskFacts(): SetupTaskFacts =
    SetupTaskFacts(
        ankiReady = ankiReady,
        noteTypeReady = targetReady,
        recoveryReady = recoveryReady,
        uniDicReady = uniDicInstalled,
        notificationReady = notificationReady,
        busy = busy,
        runtimeReady =
            pythonReady && resourceStartup == ResourceStartupReadiness.READY,
    )

@Composable
internal fun SystemStatusCard(
    state: SetupUiState,
    onRefresh: () -> Unit,
    onRequestPermissions: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onInstallAnkiDroid: () -> Unit,
    onOpenAnkiDroid: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onInstallUniDic: () -> Unit = {},
    onChooseNoteType: () -> Unit = {},
    onResolveRecovery: () -> Unit = onRefresh,
    inlineFailure: (@Composable () -> Unit)? = null,
) {
    val status = setupTaskStatus(state.taskFacts())
    OutlinedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                setupSummary(status),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.titleMedium,
            )
            inlineFailure?.invoke()
            if (!compact) {
                status.rows.forEach { row ->
                    SetupStatusRow(
                        row = row,
                        state = state,
                        onRefresh = onRefresh,
                        onRequestPermissions = onRequestPermissions,
                        onOpenAppSettings = onOpenAppSettings,
                        onInstallAnkiDroid = onInstallAnkiDroid,
                        onOpenAnkiDroid = onOpenAnkiDroid,
                        onInstallUniDic = onInstallUniDic,
                        onChooseNoteType = onChooseNoteType,
                        onResolveRecovery = onResolveRecovery,
                    )
                }
            }
        }
    }
}

@Composable
private fun setupSummary(status: SetupTaskStatus): String =
    when (status.summary) {
        SetupSummaryKind.READY -> stringResource(R.string.b3_status_ready_to_mine)
        SetupSummaryKind.READY_WITH_OPTIONAL_WARNING ->
            stringResource(R.string.b3_status_ready_optional_warning)
        SetupSummaryKind.ATTENTION ->
            pluralStringResource(
                R.plurals.b3_status_attention_count,
                status.requiredAttentionCount,
                status.requiredAttentionCount,
            )
        SetupSummaryKind.BUSY -> stringResource(R.string.b3_status_checking)
    }

@Composable
private fun SetupStatusRow(
    row: SetupTaskRow,
    state: SetupUiState,
    onRefresh: () -> Unit,
    onRequestPermissions: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onInstallAnkiDroid: () -> Unit,
    onOpenAnkiDroid: () -> Unit,
    onInstallUniDic: () -> Unit,
    onChooseNoteType: () -> Unit,
    onResolveRecovery: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(setupTaskLabel(row.id)),
                modifier = Modifier.weight(1f),
            )
            Surface(
                color =
                    when (row.role) {
                        SetupTaskRole.SUCCESS -> MaterialTheme.colorScheme.primaryContainer
                        SetupTaskRole.OPTIONAL_WARNING ->
                            MaterialTheme.colorScheme.secondaryContainer
                        SetupTaskRole.REQUIRED_ACTION -> MaterialTheme.colorScheme.errorContainer
                        SetupTaskRole.BUSY -> MaterialTheme.colorScheme.surfaceVariant
                    },
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    stringResource(setupRoleLabel(row.role)),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        if (row.role == SetupTaskRole.REQUIRED_ACTION) {
            SetupTaskAction(
                id = row.id,
                state = state,
                onRefresh = onRefresh,
                onRequestPermissions = onRequestPermissions,
                onOpenAppSettings = onOpenAppSettings,
                onInstallAnkiDroid = onInstallAnkiDroid,
                onOpenAnkiDroid = onOpenAnkiDroid,
                onInstallUniDic = onInstallUniDic,
                onChooseNoteType = onChooseNoteType,
                onResolveRecovery = onResolveRecovery,
            )
        }
    }
}

@Composable
private fun SetupTaskAction(
    id: SetupTaskId,
    state: SetupUiState,
    onRefresh: () -> Unit,
    onRequestPermissions: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onInstallAnkiDroid: () -> Unit,
    onOpenAnkiDroid: () -> Unit,
    onInstallUniDic: () -> Unit,
    onChooseNoteType: () -> Unit,
    onResolveRecovery: () -> Unit,
) {
    when (id) {
        SetupTaskId.RUNTIME ->
            StatusAction(R.string.check_again, onRefresh)
        SetupTaskId.ANKIDROID ->
            when (state.ankiDroidAction) {
                AnkiDroidSetupAction.INSTALL ->
                    StatusAction(R.string.install_or_update_ankidroid, onInstallAnkiDroid)
                AnkiDroidSetupAction.OPEN ->
                    StatusAction(R.string.open_ankidroid, onOpenAnkiDroid)
                AnkiDroidSetupAction.OPEN_OR_INSTALL ->
                    StatusAction(R.string.open_ankidroid, onOpenAnkiDroid)
                AnkiDroidSetupAction.REQUEST_PERMISSION ->
                    StatusAction(R.string.allow_required_access, onRequestPermissions)
                null -> StatusAction(R.string.check_again, onRefresh)
            }
        SetupTaskId.NOTE_TYPE ->
            StatusAction(R.string.b3_status_choose_note_type, onChooseNoteType)
        SetupTaskId.RECOVERY ->
            StatusAction(R.string.b3_resolve, onResolveRecovery)
        SetupTaskId.UNIDIC ->
            StatusAction(R.string.unidic_install, onInstallUniDic)
        SetupTaskId.NOTIFICATIONS -> Unit
    }
    if (
        id == SetupTaskId.ANKIDROID &&
        state.ankiDroidAction == AnkiDroidSetupAction.REQUEST_PERMISSION
    ) {
        StatusAction(R.string.open_app_settings, onOpenAppSettings)
    }
}

@Composable
private fun StatusAction(
    label: Int,
    onClick: () -> Unit,
) {
    OutlinedButton(onClick = onClick) {
        Text(stringResource(label))
    }
}

private fun setupTaskLabel(id: SetupTaskId): Int =
    when (id) {
        SetupTaskId.RUNTIME -> R.string.b3_status_runtime
        SetupTaskId.ANKIDROID -> R.string.b3_status_ankidroid
        SetupTaskId.NOTE_TYPE -> R.string.b3_status_note_type
        SetupTaskId.RECOVERY -> R.string.b3_status_recovery
        SetupTaskId.UNIDIC -> R.string.b3_status_unidic
        SetupTaskId.NOTIFICATIONS -> R.string.b3_status_notifications
    }

private fun setupRoleLabel(role: SetupTaskRole): Int =
    when (role) {
        SetupTaskRole.SUCCESS -> R.string.b3_status_success
        SetupTaskRole.OPTIONAL_WARNING -> R.string.b3_status_optional
        SetupTaskRole.REQUIRED_ACTION -> R.string.b3_status_required_action
        SetupTaskRole.BUSY -> R.string.b3_status_busy
    }
