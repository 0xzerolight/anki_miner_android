package com.ankiminer.android.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ankiminer.android.R
import com.ankiminer.android.data.resources.ResourceStartupReadiness
import com.ankiminer.android.engine.PythonRuntimeReadiness
import com.ankiminer.android.ui.theme.AnkiMinerTokens
import com.ankiminer.android.ui.theme.SupportingText
import com.ankiminer.android.ui.theme.UtilityActionButton
import com.ankiminer.android.vm.AnkiDroidSetupAction
import com.ankiminer.android.vm.SetupUiState

internal enum class SetupTaskId {
    RUNTIME,
    ANKIDROID,
    NOTE_TYPE,
    RECOVERY,
    UNIDIC,
    DICTIONARY,
    NOTIFICATIONS,
}

internal enum class SetupTaskRole {
    OPTIONAL_WARNING,
    REQUIRED_ACTION,
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
    /** Startup has not settled (engine starting, resources recovering), so nothing can be judged yet. */
    val checking: Boolean,
    val runtimeReady: Boolean = true,
    val dictionaryReady: Boolean = true,
    /** Only Japanese tokenizes with UniDic; for every other language the row never appears. */
    val uniDicRequired: Boolean = true,
    /** A resource download is running; it marks only the UniDic and dictionary rows. */
    val downloading: Boolean = false,
) {
    companion object {
        fun ready(): SetupTaskFacts =
            SetupTaskFacts(
                ankiReady = true,
                noteTypeReady = true,
                recoveryReady = true,
                uniDicReady = true,
                notificationReady = true,
                checking = false,
                runtimeReady = true,
                dictionaryReady = true,
            )
    }
}

internal data class SetupTaskRow(
    val id: SetupTaskId,
    val role: SetupTaskRole,
    /** A running download is fixing this row: it says so and offers no action. */
    val inProgress: Boolean = false,
)

internal data class SetupTaskStatus(
    val summary: SetupSummaryKind,
    val requiredAttentionCount: Int,
    val rows: List<SetupTaskRow>,
)

/**
 * Only what needs action, root cause first: a failing runtime hides the resource rows it explains,
 * a failing AnkiDroid hides the note-type and recovery rows. A running download marks only its own
 * rows; every other row stays judged. Only startup makes the whole card wait.
 */
internal fun setupTaskStatus(facts: SetupTaskFacts): SetupTaskStatus {
    if (facts.checking) return SetupTaskStatus(SetupSummaryKind.BUSY, 0, emptyList())
    val failing =
        buildList {
            if (!facts.runtimeReady) add(SetupTaskId.RUNTIME)
            if (!facts.ankiReady) add(SetupTaskId.ANKIDROID)
            if (facts.ankiReady && !facts.noteTypeReady) add(SetupTaskId.NOTE_TYPE)
            if (facts.ankiReady && !facts.recoveryReady) add(SetupTaskId.RECOVERY)
            if (facts.runtimeReady && facts.uniDicRequired && !facts.uniDicReady) add(SetupTaskId.UNIDIC)
            if (facts.runtimeReady && !facts.dictionaryReady) add(SetupTaskId.DICTIONARY)
        }
    val rows =
        failing.map { id ->
            SetupTaskRow(
                id = id,
                role = SetupTaskRole.REQUIRED_ACTION,
                inProgress = facts.downloading && (id == SetupTaskId.UNIDIC || id == SetupTaskId.DICTIONARY),
            )
        } +
            listOfNotNull(
                SetupTaskRow(SetupTaskId.NOTIFICATIONS, SetupTaskRole.OPTIONAL_WARNING)
                    .takeUnless { facts.notificationReady },
            )
    val summary =
        when {
            failing.isNotEmpty() -> SetupSummaryKind.ATTENTION
            !facts.notificationReady -> SetupSummaryKind.READY_WITH_OPTIONAL_WARNING
            else -> SetupSummaryKind.READY
        }
    return SetupTaskStatus(summary, failing.size, rows)
}

internal fun SetupUiState.setupTaskStatus(): SetupTaskStatus = setupTaskStatus(taskFacts())

/** The blockers still showing, for the readiness notice's "Finish setup (N left)". */
internal fun SetupUiState.setupAttentionCount(): Int = setupTaskStatus().requiredAttentionCount

/**
 * True only when a required setup task is broken; the settings header renders the status card on
 * this alone, so a ready, optionally-warned, or merely checking state costs it no space.
 */
internal fun SetupUiState.setupNeedsAttention(): Boolean =
    setupTaskStatus().summary == SetupSummaryKind.ATTENTION

private fun SetupUiState.taskFacts(): SetupTaskFacts =
    SetupTaskFacts(
        ankiReady = ankiReady,
        noteTypeReady = targetReady,
        recoveryReady = recoveryReady,
        uniDicReady = tokenizerReady,
        dictionaryReady = dictionaryReady,
        notificationReady = notificationReady,
        checking =
            python == PythonRuntimeReadiness.Pending ||
                python == PythonRuntimeReadiness.Starting ||
                resourceStartup == ResourceStartupReadiness.PENDING ||
                resourceStartup == ResourceStartupReadiness.RECOVERING,
        runtimeReady = pythonReady && resourceStartup == ResourceStartupReadiness.READY,
        uniDicRequired = uniDicRequired,
        downloading = operation != null,
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
    onInstallUniDic: () -> Unit = {},
    onChooseNoteType: () -> Unit = {},
    onImportDictionary: () -> Unit = onRefresh,
    inlineFailureTaskId: SetupTaskId? = null,
    inlineFailure: (@Composable () -> Unit)? = null,
) {
    val status = state.setupTaskStatus()
    OutlinedCard(modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(AnkiMinerTokens.Space.content),
            verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related),
        ) {
            Text(
                setupSummary(status),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.titleMedium,
            )
            inlineFailure?.invoke()
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
                    onImportDictionary = onImportDictionary,
                    showAction = row.id != inlineFailureTaskId,
                )
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
    onImportDictionary: () -> Unit,
    showAction: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.line)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related),
            verticalAlignment = Alignment.Top,
        ) {
            SetupStatusGlyph(row.role, Modifier.padding(top = AnkiMinerTokens.Space.micro))
            // The status sits under the label, never squeezed beside it: at 320dp and 2x text a
            // chip there letter-wrapped the label ("Not/e type").
            Column(Modifier.weight(1f)) {
                Text(stringResource(setupTaskLabel(row.id)))
                SupportingText(
                    stringResource(
                        // A row its running download is fixing says so instead of "Required action".
                        if (row.inProgress) R.string.readiness_resource_operation else setupRoleLabel(row.role),
                    ),
                )
            }
        }
        if (row.role == SetupTaskRole.REQUIRED_ACTION && showAction && !row.inProgress) {
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
                onImportDictionary = onImportDictionary,
            )
        }
    }
}

/**
 * Filled for a blocker, a ring for an optional item: they differ in shape as well as colour, so a
 * row still reads in a palette where error and muted text sit close together.
 */
@Composable
private fun SetupStatusGlyph(
    role: SetupTaskRole,
    modifier: Modifier = Modifier,
) {
    val required = role == SetupTaskRole.REQUIRED_ACTION
    val ring = if (required) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    val mark = if (required) MaterialTheme.colorScheme.onError else ring
    Canvas(modifier.size(StatusGlyphSize).clearAndSetSemantics {}) {
        val stroke = StatusGlyphStroke.toPx()
        if (required) {
            drawCircle(color = ring)
        } else {
            drawCircle(color = ring, radius = size.minDimension / 2f - stroke / 2f, style = Stroke(width = stroke))
        }
        drawLine(
            color = mark,
            start = Offset(center.x, size.height * 0.28f),
            end = Offset(center.x, size.height * 0.56f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawCircle(color = mark, radius = stroke * 0.65f, center = Offset(center.x, size.height * 0.74f))
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
    onImportDictionary: () -> Unit,
) {
    when (id) {
        SetupTaskId.RUNTIME ->
            StatusAction(R.string.check_again, onRefresh, enabled = !state.busy)
        SetupTaskId.ANKIDROID ->
            when (state.ankiDroidAction) {
                AnkiDroidSetupAction.INSTALL ->
                    StatusAction(ankiDroidInstallLabel(state.anki), onInstallAnkiDroid, enabled = !state.busy)
                AnkiDroidSetupAction.OPEN,
                AnkiDroidSetupAction.OPEN_OR_INSTALL,
                -> StatusAction(R.string.open_ankidroid, onOpenAnkiDroid, enabled = !state.busy)
                AnkiDroidSetupAction.REQUEST_PERMISSION ->
                    StatusAction(R.string.allow_required_access, onRequestPermissions, enabled = !state.busy)
                AnkiDroidSetupAction.OPEN_APP_SETTINGS ->
                    StatusAction(R.string.allow_in_android_settings, onOpenAppSettings, enabled = !state.busy)
                null -> StatusAction(R.string.check_again, onRefresh, enabled = !state.busy)
            }
        SetupTaskId.NOTE_TYPE ->
            StatusAction(R.string.b3_status_choose_note_type, onChooseNoteType, enabled = !state.busy)
        SetupTaskId.RECOVERY ->
            StatusAction(R.string.check_again, onRefresh, enabled = !state.busy)
        SetupTaskId.UNIDIC ->
            StatusAction(R.string.unidic_install, onInstallUniDic, enabled = !state.busy)
        SetupTaskId.DICTIONARY ->
            StatusAction(
                if (state.dictionaries.any { it.occupied }) {
                    R.string.readiness_review_dictionaries
                } else {
                    R.string.readiness_install_dictionary
                },
                onImportDictionary,
                enabled = !state.busy,
            )
        SetupTaskId.NOTIFICATIONS -> Unit
    }
}

@Composable
private fun StatusAction(
    label: Int,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    // Install, open, allow, choose: supporting work, so the tonal utility wrapper.
    UtilityActionButton(onClick = onClick, enabled = enabled) {
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
        SetupTaskId.DICTIONARY -> R.string.b3_status_dictionary
        SetupTaskId.NOTIFICATIONS -> R.string.b3_status_notifications
    }

private fun setupRoleLabel(role: SetupTaskRole): Int =
    when (role) {
        SetupTaskRole.OPTIONAL_WARNING -> R.string.b3_status_optional
        SetupTaskRole.REQUIRED_ACTION -> R.string.b3_status_required_action
    }

private val StatusGlyphSize = 20.dp
private val StatusGlyphStroke = 2.dp
