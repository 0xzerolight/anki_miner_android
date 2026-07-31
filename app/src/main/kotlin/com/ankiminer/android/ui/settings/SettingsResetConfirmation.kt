package com.ankiminer.android.ui.settings

internal enum class SettingsResetAction {
    RESTORE_MINING_DEFAULTS,
    RESET_ANKI_TARGET,
    RESET_RESOURCE_CHOICES,
}

internal data class SettingsResetConfirmationState(
    val pendingAction: SettingsResetAction? = null,
) {
    fun request(action: SettingsResetAction) = copy(pendingAction = action)

    fun cancel() = copy(pendingAction = null)

    fun confirm(): Pair<SettingsResetConfirmationState, SettingsResetAction?> =
        copy(pendingAction = null) to pendingAction

    fun confirmIfAccepted(accepted: Boolean): SettingsResetConfirmationState =
        if (accepted) cancel() else this
}

internal fun dispatchConfirmedSettingsReset(
    action: SettingsResetAction?,
    onRestoreMiningDefaults: () -> Boolean,
    onResetAnkiTarget: () -> Boolean,
    onResetResourceChoices: () -> Boolean,
): Boolean =
    when (action) {
        SettingsResetAction.RESTORE_MINING_DEFAULTS -> onRestoreMiningDefaults()
        SettingsResetAction.RESET_ANKI_TARGET -> onResetAnkiTarget()
        SettingsResetAction.RESET_RESOURCE_CHOICES -> onResetResourceChoices()
        null -> false
    }
