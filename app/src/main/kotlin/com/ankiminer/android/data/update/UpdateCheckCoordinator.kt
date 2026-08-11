package com.ankiminer.android.data.update

import com.ankiminer.android.diagnostics.log.AppLog
import com.ankiminer.android.diagnostics.log.LogComponent
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal const val CHECK_INTERVAL_MILLIS = 24L * 60L * 60L * 1000L

internal data class UpdateCheckUiState(
    val enabled: Boolean = true,
    val checking: Boolean = false,
    val lastCheckedAtMillis: Long = 0L,
    val available: AvailableUpdate? = null,
    val lastCheckFailed: Boolean = false,
)

internal class UpdateCheckCoordinator(
    private val repository: UpdateCheckRepository,
    private val client: UpdateCheckClient,
    private val currentVersion: String,
    private val now: () -> Long = System::currentTimeMillis,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val preferences =
        MutableStateFlow(
            (repository.state as? StateFlow<UpdateCheckPreferences>)?.value
                ?: UpdateCheckPreferences(),
        )
    private val checking = MutableStateFlow(false)
    private val lastCheckFailed = MutableStateFlow(false)
    private val inFlightMutex = Mutex()
    private var inFlightCheck: Deferred<Unit>? = null
    private val mutableUiState =
        MutableStateFlow(
            toUiState(
                preferences = preferences.value,
                checking = checking.value,
                lastCheckFailed = lastCheckFailed.value,
            ),
        )

    val uiState: StateFlow<UpdateCheckUiState> = mutableUiState.asStateFlow()

    init {
        scope.launch {
            repository.state.collect { stored ->
                preferences.value = stored
                publishUiState()
            }
        }
    }

    suspend fun checkIfDue() {
        val stored = repository.state.first()
        val timestamp = now()
        if (!stored.enabled || !isDue(stored.lastAutomaticAttemptAtMillis, timestamp)) return
        repository.recordAutomaticAttempt(timestamp)
        runCheck(timestamp)
    }

    suspend fun checkNow() {
        val stored = repository.state.first()
        if (!stored.enabled) return
        runCheck(now())
    }

    suspend fun setEnabled(enabled: Boolean) {
        repository.setEnabled(enabled)
        refreshPreferences()
    }

    suspend fun skipAvailable() {
        val stored = repository.state.first()
        val available = available(stored) ?: return
        repository.skip(available.version)
        refreshPreferences()
    }

    private suspend fun runCheck(atMillis: Long) {
        val active =
            inFlightMutex.withLock {
                inFlightCheck?.takeUnless { it.isCompleted }
                    ?: scope.async { performCheck(atMillis) }.also { inFlightCheck = it }
            }
        try {
            active.await()
        } finally {
            inFlightMutex.withLock {
                if (inFlightCheck === active && active.isCompleted) inFlightCheck = null
            }
        }
    }

    private suspend fun performCheck(atMillis: Long) {
        setChecking(true)
        try {
            val result =
                try {
                    withContext(dispatcher) { client.latest(currentVersion) }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: IOException) {
                    recordFailure(failure)
                    return
                } catch (failure: RuntimeException) {
                    recordFailure(failure)
                    return
                }
            when (result) {
                is UpdateCheckResult.Available -> repository.recordCheck(atMillis, result.update)
                UpdateCheckResult.UpToDate -> repository.recordCheck(atMillis, null)
                UpdateCheckResult.Failure -> {
                    recordFailure()
                    return
                }
            }
            lastCheckFailed.value = false
            refreshPreferences()
        } finally {
            setChecking(false)
        }
    }

    private fun recordFailure(failure: Throwable? = null) {
        lastCheckFailed.value = true
        publishUiState()
        if (failure == null) {
            AppLog.i(
                LogComponent.SETTINGS,
                "update.check",
                "outcome" to "fail",
                "code" to "invalid_response",
            )
        } else {
            AppLog.w(
                LogComponent.SETTINGS,
                "update.check",
                failure,
                "outcome" to "fail",
            )
        }
    }

    private suspend fun refreshPreferences() {
        preferences.value = repository.state.first()
        publishUiState()
    }

    private fun setChecking(value: Boolean) {
        checking.value = value
        publishUiState()
    }

    private fun publishUiState() {
        mutableUiState.value =
            toUiState(
                preferences = preferences.value,
                checking = checking.value,
                lastCheckFailed = lastCheckFailed.value,
            )
    }

    private fun toUiState(
        preferences: UpdateCheckPreferences,
        checking: Boolean,
        lastCheckFailed: Boolean,
    ): UpdateCheckUiState =
        UpdateCheckUiState(
            enabled = preferences.enabled,
            checking = checking,
            lastCheckedAtMillis = preferences.lastCheckedAtMillis,
            available = available(preferences),
            lastCheckFailed = lastCheckFailed,
        )

    private fun available(preferences: UpdateCheckPreferences): AvailableUpdate? {
        if (!preferences.enabled) return null
        val version = preferences.availableVersion ?: return null
        val url = preferences.availableUrl ?: return null
        if (version == preferences.skippedVersion) return null
        if (!VersionCompare.isNewer(version, currentVersion)) return null
        return AvailableUpdate(version, url)
    }

    private fun isDue(
        lastAttemptAtMillis: Long,
        atMillis: Long,
    ): Boolean =
        lastAttemptAtMillis > atMillis ||
            atMillis - lastAttemptAtMillis >= CHECK_INTERVAL_MILLIS
}
