package com.ankiminer.android.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.ankiminer.android.R
import com.ankiminer.android.anki.provider.AnkiProviderReadiness
import com.ankiminer.android.ui.theme.AnkiMinerTokens
import com.ankiminer.android.ui.theme.PrimaryActionButton
import com.ankiminer.android.ui.theme.UtilityActionButton
import com.ankiminer.android.vm.AnkiDroidSetupAction
import com.ankiminer.android.vm.SetupUiState

/** The one sentence for the AnkiDroid step still to do, or null once it is connected. */
@StringRes
internal fun ankiDroidActionPrompt(state: SetupUiState): Int? =
    when (state.ankiDroidAction) {
        AnkiDroidSetupAction.INSTALL ->
            if (state.anki is AnkiProviderReadiness.Incompatible) {
                R.string.ankidroid_update_prompt
            } else {
                R.string.ankidroid_install_prompt
            }
        AnkiDroidSetupAction.OPEN,
        AnkiDroidSetupAction.OPEN_OR_INSTALL,
        -> R.string.ankidroid_open_prompt
        AnkiDroidSetupAction.REQUEST_PERMISSION -> R.string.ankidroid_permission_prompt
        null -> null
    }

/** "Install AnkiDroid" when it is absent; "Update" only when the installed one is too old. */
@StringRes
internal fun ankiDroidInstallLabel(anki: AnkiProviderReadiness): Int =
    if (anki is AnkiProviderReadiness.Incompatible) R.string.update_ankidroid else R.string.install_ankidroid

/**
 * One sentence and the action that moves AnkiDroid forward. Shown instead of a provider error:
 * while AnkiDroid is missing, closed or not yet allowed, the target read can only fail, and its
 * error said so in English above the button that fixes it. [emphasized] makes the action the
 * filled one, for the wizard page where it is the step's required action.
 */
@Composable
internal fun AnkiDroidConnectActions(
    state: SetupUiState,
    onRequestPermissions: () -> Unit,
    onInstallAnkiDroid: () -> Unit,
    onOpenAnkiDroid: () -> Unit,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
) {
    val prompt = ankiDroidActionPrompt(state) ?: return
    val action: @Composable (() -> Unit, Int) -> Unit = { onClick, label ->
        if (emphasized) {
            PrimaryActionButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(label))
            }
        } else {
            UtilityActionButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(label))
            }
        }
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related),
    ) {
        Text(stringResource(prompt))
        when (state.ankiDroidAction) {
            AnkiDroidSetupAction.INSTALL -> action(onInstallAnkiDroid, ankiDroidInstallLabel(state.anki))
            AnkiDroidSetupAction.OPEN -> action(onOpenAnkiDroid, R.string.open_ankidroid)
            AnkiDroidSetupAction.OPEN_OR_INSTALL -> {
                action(onOpenAnkiDroid, R.string.open_ankidroid)
                action(onInstallAnkiDroid, ankiDroidInstallLabel(state.anki))
            }
            AnkiDroidSetupAction.REQUEST_PERMISSION -> action(onRequestPermissions, R.string.allow_required_access)
            null -> Unit
        }
    }
}
