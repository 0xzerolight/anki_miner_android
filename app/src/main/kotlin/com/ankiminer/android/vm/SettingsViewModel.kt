package com.ankiminer.android.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.ankiminer.android.data.resources.ResourceManager
import com.ankiminer.android.data.resources.ResourceManagerState
import com.ankiminer.android.data.settings.AppSettings
import com.ankiminer.android.data.settings.AppSettingsRepository
import com.ankiminer.android.data.settings.InvalidAppSettingException
import com.ankiminer.android.data.settings.ResourceChainSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal class SettingsViewModel(
    private val repository: AppSettingsRepository,
    private val resources: ResourceManager,
) : ViewModel() {
    val settings: StateFlow<AppSettings> =
        repository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val saving = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val resourceState: StateFlow<ResourceManagerState> = resources.state

    fun save(value: AppSettings) {
        if (saving.value) return
        saving.value = true
        error.value = null
        viewModelScope.launch {
            try {
                repository.update(value)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: InvalidAppSettingException) {
                error.value = failure.message
            } catch (_: Exception) {
                error.value = "Settings could not be saved"
            } finally {
                saving.value = false
            }
        }
    }

    fun restoreDefaults() {
        val current = settings.value
        save(
            AppSettings(
                firstRunComplete = current.firstRunComplete,
                // Restoring mining defaults must never re-open the onboarding wizard or
                // change the user's chosen look.
                setupWizardSeen = current.setupWizardSeen,
                theme = current.theme,
                // An empty persisted chain means newly imported resources should become active.
                // Record current installs as disabled so "restore defaults" still means no local
                // frequency or expression-audio override at this point in time.
                frequencySources =
                    resources.installedFrequencyIds().map { ResourceChainSelection(it, false) },
                audioPacks =
                    resources.installedAudioPackIds().map { ResourceChainSelection(it, false) },
            ),
        )
    }

    fun dismissError() {
        error.value = null
    }

    class Factory(
        private val repository: AppSettingsRepository,
        private val resources: ResourceManager,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            require(modelClass.isAssignableFrom(SettingsViewModel::class.java))
            return SettingsViewModel(repository, resources) as T
        }
    }
}
