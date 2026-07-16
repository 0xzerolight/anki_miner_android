package com.ankiminer.android.ui.setup

import com.ankiminer.android.anki.provider.AnkiProviderReadiness
import com.ankiminer.android.data.resources.DictionaryLookup
import com.ankiminer.android.data.resources.InstalledDictionary
import com.ankiminer.android.data.resources.ResourceFailure
import com.ankiminer.android.data.resources.ResourceOperationProgress
import com.ankiminer.android.data.resources.ResourceStartupReadiness
import com.ankiminer.android.engine.PythonRuntimeReadiness
import com.ankiminer.android.mining.NotificationPermissionReadiness

internal data class SetupUiState(
    val python: PythonRuntimeReadiness = PythonRuntimeReadiness.Pending,
    val resourceStartup: ResourceStartupReadiness = ResourceStartupReadiness.PENDING,
    val anki: AnkiProviderReadiness = AnkiProviderReadiness.NotChecked,
    val notifications: NotificationPermissionReadiness = NotificationPermissionReadiness.READY,
    val firstRunComplete: Boolean = false,
    val uniDicInstalled: Boolean = false,
    val recommendedDictionaryInstalled: Boolean = false,
    val dictionaries: List<InstalledDictionary> = emptyList(),
    val operation: ResourceOperationProgress? = null,
    val failure: ResourceFailure? = null,
    val lookup: DictionaryLookup? = null,
    val lookupTerm: String = "猫",
    val lookupSlotId: String? = null,
    val customSlotId: String = "custom-dictionary",
    val customReplace: Boolean = false,
    val completing: Boolean = false,
    val completionError: Boolean = false,
) {
    val customSlotValid: Boolean
        get() = CUSTOM_SLOT_ID.matches(customSlotId)

    val pythonReady: Boolean
        get() = python is PythonRuntimeReadiness.Ready

    val ankiReady: Boolean
        get() = anki is AnkiProviderReadiness.Ready

    val notificationReady: Boolean
        get() = notifications == NotificationPermissionReadiness.READY

    val isMiningReady: Boolean
        get() =
            pythonReady &&
                resourceStartup == ResourceStartupReadiness.READY &&
                ankiReady &&
                notificationReady &&
                uniDicInstalled &&
                operation == null

    val canFinishFirstRun: Boolean
        get() =
            resourceStartup == ResourceStartupReadiness.READY &&
                uniDicInstalled &&
                operation == null &&
                !completing

    private companion object {
        val CUSTOM_SLOT_ID = Regex("(?!.*(?:\\.\\.|--))[a-z0-9](?:[a-z0-9._-]{0,62}[a-z0-9])?")
    }
}
