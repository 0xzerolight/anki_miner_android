package com.ankiminer.android.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

// Deliberately not a key in DataStoreAppSettingsRepository: that registry feeds the engine config
// snapshot golden parity holds, and the "Reset settings" actions would switch a tester's verbose
// mode off in the middle of the investigation it was turned on for.
private val Context.ankiMinerDiagnosticsDataStore by
    preferencesDataStore(name = "anki_miner_diagnostics_v1")

internal interface DiagnosticsSettingsRepository {
    val verboseLogging: Flow<Boolean>

    suspend fun setVerboseLogging(enabled: Boolean)
}

internal class DataStoreDiagnosticsSettingsRepository internal constructor(
    private val store: DataStore<Preferences>,
    private val now: () -> Long = System::currentTimeMillis,
) : DiagnosticsSettingsRepository {
    constructor(context: Context) : this(context.applicationContext.ankiMinerDiagnosticsDataStore)

    override val verboseLogging: Flow<Boolean> =
        flow {
            // The expiry write runs before this flow starts collecting store.data, never from
            // inside that collector, so it cannot contend with DataStore's own writer. An
            // expiry that elapses mid-collection is still reported as false by decode() below;
            // it is only the stored flag that waits for the next collection to be cleared.
            runCatching { clearExpiredFlag() }
            emitAll(
                store.data
                    .catch { failure -> if (failure is IOException) emit(emptyPreferences()) else throw failure }
                    .map(::decode),
            )
        }

    override suspend fun setVerboseLogging(enabled: Boolean) {
        store.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                if (enabled) {
                    this[VERBOSE_LOGGING] = true
                    this[VERBOSE_ENABLED_AT] = now()
                } else {
                    remove(VERBOSE_LOGGING)
                    remove(VERBOSE_ENABLED_AT)
                }
            }.toPreferences()
        }
    }

    private suspend fun clearExpiredFlag() {
        val stored =
            store.data
                .catch { failure -> if (failure is IOException) emit(emptyPreferences()) else throw failure }
                .first()
        if (!expired(stored)) return
        store.updateData { preferences ->
            if (!expired(preferences)) {
                preferences
            } else {
                preferences.toMutablePreferences().apply {
                    remove(VERBOSE_LOGGING)
                    remove(VERBOSE_ENABLED_AT)
                }.toPreferences()
            }
        }
    }

    private fun decode(preferences: Preferences): Boolean = readFlag(preferences) && !expired(preferences)

    private fun expired(preferences: Preferences): Boolean {
        if (!readFlag(preferences)) return false
        val enabledAt = readStamp(preferences) ?: return true
        // A stamp from the future is a clock the app cannot reason about (manual change, restored
        // backup); treating it as fresh would pin verbose logging on indefinitely.
        val age = now() - enabledAt
        return age !in 0..VERBOSE_WINDOW_MILLIS
    }

    private companion object {
        val VERBOSE_LOGGING = booleanPreferencesKey("verbose_logging_enabled")
        val VERBOSE_ENABLED_AT = longPreferencesKey("verbose_enabled_at")

        // Verbose logging multiplies log churn, so a tester who forgets the switch loses the start
        // of every later run to rotation -- the toggle meant to give more information ends up
        // giving less. Persisting across restarts is the requirement; persisting forever is not.
        val VERBOSE_WINDOW_MILLIS = TimeUnit.DAYS.toMillis(7)

        // A value stored under the wrong type throws on read. There is no honest recovery for a
        // diagnostics flag, and false is the safe side of it.
        fun readFlag(preferences: Preferences): Boolean =
            try {
                preferences[VERBOSE_LOGGING] ?: false
            } catch (_: ClassCastException) {
                false
            }

        fun readStamp(preferences: Preferences): Long? =
            try {
                preferences[VERBOSE_ENABLED_AT]
            } catch (_: ClassCastException) {
                null
            }
    }
}
