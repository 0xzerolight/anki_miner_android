package com.ankiminer.android.data.update

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * Device-local update timing and dismissal state.
 *
 * This state has its own store because time-based device state must not enter settings exports or
 * backups, and "Reset settings" actions must not clear the daily window or skipped release.
 */
private val Context.ankiMinerUpdatesDataStore by
    preferencesDataStore(name = "anki_miner_updates_v1")

internal data class UpdateCheckPreferences(
    val enabled: Boolean = true,
    val lastCheckedAtMillis: Long = 0L,
    val availableVersion: String? = null,
    val availableUrl: String? = null,
    val skippedVersion: String? = null,
)

internal interface UpdateCheckRepository {
    val state: Flow<UpdateCheckPreferences>

    suspend fun setEnabled(enabled: Boolean)

    suspend fun recordCheck(
        atMillis: Long,
        found: AvailableUpdate?,
    )

    suspend fun skip(version: String)
}

internal class DataStoreUpdateCheckRepository internal constructor(
    private val store: DataStore<Preferences>,
) : UpdateCheckRepository {
    constructor(context: Context) : this(context.applicationContext.ankiMinerUpdatesDataStore)

    override val state: Flow<UpdateCheckPreferences> =
        store.data
            .catch { failure -> if (failure is IOException) emit(emptyPreferences()) else throw failure }
            .map(::decode)

    override suspend fun setEnabled(enabled: Boolean) {
        store.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                this[UPDATE_CHECK_ENABLED] = enabled
            }.toPreferences()
        }
    }

    override suspend fun recordCheck(
        atMillis: Long,
        found: AvailableUpdate?,
    ) {
        store.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                this[UPDATE_LAST_CHECKED_AT] = atMillis
                if (found == null) {
                    remove(UPDATE_AVAILABLE_VERSION)
                    remove(UPDATE_AVAILABLE_URL)
                } else {
                    this[UPDATE_AVAILABLE_VERSION] = found.version
                    this[UPDATE_AVAILABLE_URL] = found.releasePageUrl
                }
            }.toPreferences()
        }
    }

    override suspend fun skip(version: String) {
        store.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                this[UPDATE_SKIPPED_VERSION] = version
            }.toPreferences()
        }
    }

    private companion object {
        val UPDATE_CHECK_ENABLED = booleanPreferencesKey("update_check_enabled")
        val UPDATE_LAST_CHECKED_AT = longPreferencesKey("update_last_checked_at")
        val UPDATE_AVAILABLE_VERSION = stringPreferencesKey("update_available_version")
        val UPDATE_AVAILABLE_URL = stringPreferencesKey("update_available_url")
        val UPDATE_SKIPPED_VERSION = stringPreferencesKey("update_skipped_version")

        fun decode(preferences: Preferences): UpdateCheckPreferences =
            UpdateCheckPreferences(
                enabled = read(preferences, UPDATE_CHECK_ENABLED) ?: true,
                lastCheckedAtMillis = read(preferences, UPDATE_LAST_CHECKED_AT) ?: 0L,
                availableVersion = read(preferences, UPDATE_AVAILABLE_VERSION),
                availableUrl = read(preferences, UPDATE_AVAILABLE_URL),
                skippedVersion = read(preferences, UPDATE_SKIPPED_VERSION),
            )

        fun <T> read(
            preferences: Preferences,
            key: Preferences.Key<T>,
        ): T? =
            try {
                preferences[key]
                // instrumentation: silent — wrong-type update state maps to its field default
            } catch (_: ClassCastException) {
                null
            }
    }
}
