package com.ankiminer.android.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.ankiminer.android.data.settings.AppSettings
import com.ankiminer.android.data.settings.AppSettingsRepository
import com.ankiminer.android.data.settings.InvalidAppSettingException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal class SettingsViewModel(
    private val repository: AppSettingsRepository,
) : ViewModel() {
    val settings: StateFlow<AppSettings> =
        repository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val saving = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

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

    fun restoreDesktopDefaults() {
        val firstRunComplete = settings.value.firstRunComplete
        save(AppSettings(firstRunComplete = firstRunComplete))
    }

    fun dismissError() {
        error.value = null
    }

    class Factory(private val repository: AppSettingsRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            require(modelClass.isAssignableFrom(SettingsViewModel::class.java))
            return SettingsViewModel(repository) as T
        }
    }
}
