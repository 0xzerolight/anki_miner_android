package com.ankiminer.android.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.ankiminer.android.data.resources.ResourceManager
import com.ankiminer.android.data.settings.AppSettingsRepository
import com.ankiminer.android.engine.PythonRuntimeReadiness
import com.ankiminer.android.mining.MiningRunAdmissionState
import com.ankiminer.android.ui.setup.SetupUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class SetupViewModel(
    private val resources: ResourceManager,
    settingsRepository: AppSettingsRepository,
    pythonReadiness: StateFlow<PythonRuntimeReadiness>,
    miningAdmission: StateFlow<MiningRunAdmissionState>,
    private val refreshAdmission: () -> Unit,
) : ViewModel() {
    private data class LocalState(
        val lookupTerm: String = "猫",
        val lookupSlotId: String? = null,
        val customSlotId: String = "custom-dictionary",
        val customReplace: Boolean = false,
        val completing: Boolean = false,
        val completionError: Boolean = false,
    )

    private val local = MutableStateFlow(LocalState())
    private val settings = settingsRepository.settings
    private val repository = settingsRepository

    val uiState: StateFlow<SetupUiState> =
        combine(resources.state, settings, pythonReadiness, miningAdmission, local) {
                resourceState,
                appSettings,
                python,
                admission,
                localState,
            ->
            val selectedSlot =
                localState.lookupSlotId?.takeIf { selected ->
                    resourceState.dictionaries.any { it.slotId == selected }
                } ?: resourceState.dictionaries.firstOrNull()?.slotId
            SetupUiState(
                python = python,
                resourceStartup = resourceState.startupReadiness,
                anki = admission.anki,
                notifications = admission.notifications,
                firstRunComplete = appSettings.firstRunComplete,
                uniDicInstalled = resourceState.hasUniDic,
                recommendedDictionaryInstalled = resourceState.hasRecommendedDictionary,
                dictionaries = resourceState.dictionaries,
                operation = resourceState.activeOperation,
                failure = resourceState.failure,
                lookup = resourceState.lastLookup,
                lookupTerm = localState.lookupTerm,
                lookupSlotId = selectedSlot,
                customSlotId = localState.customSlotId,
                customReplace = localState.customReplace,
                completing = localState.completing,
                completionError = localState.completionError,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            SetupUiState(),
        )

    fun refresh() {
        refreshAdmission()
        viewModelScope.launch { resources.recoverAndRefresh() }
    }

    fun installUniDic() {
        viewModelScope.launch { resources.installUniDic() }
    }

    fun installRecommendedDictionary() {
        val replace = uiState.value.recommendedDictionaryInstalled
        viewModelScope.launch { resources.installRecommendedDictionary(replace) }
    }

    fun importCustomDictionary(uri: String) {
        val state = uiState.value
        if (!SLOT_ID.matches(state.customSlotId)) return
        viewModelScope.launch {
            resources.importCustomDictionary(uri, state.customSlotId, state.customReplace)
        }
    }

    fun setCustomSlotId(value: String) {
        if (value.length <= 64) local.update { it.copy(customSlotId = value.lowercase()) }
    }

    fun setCustomReplace(value: Boolean) {
        local.update { it.copy(customReplace = value) }
    }

    fun setLookupTerm(value: String) {
        if (value.toByteArray().size <= 1024) local.update { it.copy(lookupTerm = value) }
    }

    fun setLookupSlot(value: String) {
        if (uiState.value.dictionaries.any { it.slotId == value }) {
            local.update { it.copy(lookupSlotId = value) }
        }
    }

    fun lookup() {
        val state = uiState.value
        val slot = state.lookupSlotId ?: return
        if (state.lookupTerm.isBlank()) return
        viewModelScope.launch { resources.lookup(slot, state.lookupTerm) }
    }

    fun cancelOperation() = resources.cancelActive()

    fun dismissFailure() = resources.dismissFailure()

    fun permissionsReturned() = refreshAdmission()

    fun finishFirstRun() {
        if (!uiState.value.canFinishFirstRun) return
        local.update { it.copy(completing = true, completionError = false) }
        viewModelScope.launch {
            try {
                repository.update { it.copy(firstRunComplete = true) }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                local.update { it.copy(completionError = true) }
            } finally {
                local.update { it.copy(completing = false) }
            }
        }
    }

    class Factory(
        private val resources: ResourceManager,
        private val settings: AppSettingsRepository,
        private val python: StateFlow<PythonRuntimeReadiness>,
        private val admission: StateFlow<MiningRunAdmissionState>,
        private val refreshAdmission: () -> Unit,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            require(modelClass.isAssignableFrom(SetupViewModel::class.java))
            return SetupViewModel(resources, settings, python, admission, refreshAdmission) as T
        }
    }

    private companion object {
        val SLOT_ID = Regex("(?!.*(?:\\.\\.|--))[a-z0-9](?:[a-z0-9._-]{0,62}[a-z0-9])?")
    }
}
