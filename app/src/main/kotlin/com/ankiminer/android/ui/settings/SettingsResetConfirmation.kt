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

    /**
     * Clear the pending action only once the reset was accepted.
     *
     * There is no unconditional confirm: clearing first threw away the answer, so a reset the
     * ViewModel refused closed the dialog having done nothing and reported nothing.
     */
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

/**
 * The confirm button's whole behaviour: dispatch, then clear only what was accepted.
 *
 * It lives here rather than inline in the composable so the order is testable. Inline, the state
 * was cleared before the dispatch and the accepted flag was dropped on the floor.
 */
internal fun SettingsResetConfirmationState.confirmDispatching(
    onRestoreMiningDefaults: () -> Boolean,
    onResetAnkiTarget: () -> Boolean,
    onResetResourceChoices: () -> Boolean,
): SettingsResetConfirmationState =
    confirmIfAccepted(
        dispatchConfirmedSettingsReset(
            action = pendingAction,
            onRestoreMiningDefaults = onRestoreMiningDefaults,
            onResetAnkiTarget = onResetAnkiTarget,
            onResetResourceChoices = onResetResourceChoices,
        ),
    )
