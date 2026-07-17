package com.ankiminer.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ankiminer.android.R
import com.ankiminer.android.mining.NotificationPermissionReadiness
import com.ankiminer.android.ui.setup.AnkiDroidSetupAction
import com.ankiminer.android.ui.setup.SetupUiState

@Composable
internal fun SystemStatusCard(
    state: SetupUiState,
    onRefresh: () -> Unit,
    onRequestPermissions: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onInstallAnkiDroid: () -> Unit,
    onOpenAnkiDroid: () -> Unit,
) {
    OutlinedCard(
        Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.readiness_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.readiness_python, pythonStatus(state.python)))
            Text(stringResource(R.string.readiness_resource_recovery, resourceStartupStatus(state.resourceStartup)))
            Text(stringResource(R.string.readiness_ankidroid, ankiStatus(state.anki)))
            Text(
                stringResource(
                    R.string.readiness_anki_model,
                    stringResource(if (state.targetReady) R.string.status_ready else R.string.status_action_needed),
                ),
            )
            Text(
                stringResource(
                    R.string.readiness_anki_recovery,
                    stringResource(if (state.recoveryReady) R.string.status_ready else R.string.status_action_needed),
                ),
            )
            Text(
                stringResource(
                    R.string.readiness_notifications,
                    stringResource(
                        if (state.notifications == NotificationPermissionReadiness.READY) {
                            R.string.status_ready
                        } else {
                            R.string.status_optional_permission_denied
                        },
                    ),
                ),
            )
            if (!state.notificationReady) {
                Text(
                    stringResource(R.string.notification_permission_optional_help),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                stringResource(
                    R.string.readiness_unidic,
                    stringResource(
                        if (state.uniDicInstalled) R.string.status_unidic_found else R.string.status_unidic_missing,
                    ),
                ),
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (state.ankiDroidAction) {
                    AnkiDroidSetupAction.INSTALL ->
                        OutlinedButton(
                            onClick = onInstallAnkiDroid,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.install_or_update_ankidroid)) }
                    AnkiDroidSetupAction.OPEN ->
                        OutlinedButton(
                            onClick = onOpenAnkiDroid,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.open_ankidroid)) }
                    AnkiDroidSetupAction.OPEN_OR_INSTALL -> {
                        OutlinedButton(
                            onClick = onOpenAnkiDroid,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.open_ankidroid)) }
                        OutlinedButton(
                            onClick = onInstallAnkiDroid,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.install_or_update_ankidroid)) }
                    }
                    AnkiDroidSetupAction.REQUEST_PERMISSION ->
                        OutlinedButton(
                            onClick = onRequestPermissions,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.allow_required_access)) }
                    null -> Unit
                }
                if (!state.notificationReady) {
                    OutlinedButton(
                        onClick = onRequestPermissions,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.allow_notification_access)) }
                }
                if (
                    state.ankiDroidAction == AnkiDroidSetupAction.REQUEST_PERMISSION ||
                    !state.notificationReady
                ) {
                    OutlinedButton(
                        onClick = onOpenAppSettings,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.open_app_settings)) }
                }
                TextButton(onClick = onRefresh, enabled = !state.busy) {
                    Text(stringResource(R.string.check_again))
                }
            }
        }
    }
}
