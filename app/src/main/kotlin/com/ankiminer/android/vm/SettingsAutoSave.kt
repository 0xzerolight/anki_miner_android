package com.ankiminer.android.vm

import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce

internal const val SETTINGS_AUTOSAVE_DEBOUNCE_MILLIS = 300L

internal enum class SettingsWriteCadence {
    DEBOUNCED,
    IMMEDIATE,
}

/**
 * Text and numeric fields produce continuous edit streams. Every other field is a discrete UI
 * action and defaults to immediate persistence, including newly added fields not listed here.
 */
internal fun settingsWriteCadence(
    previous: SettingsDraft,
    current: SettingsDraft,
): SettingsWriteCadence {
    val withoutContinuousEdits =
        current.copy(
            deckName = previous.deckName,
            tags = previous.tags,
            audioPadding = previous.audioPadding,
            screenshotOffset = previous.screenshotOffset,
            subtitleOffset = previous.subtitleOffset,
            bitrate = previous.bitrate,
            maxDuration = previous.maxDuration,
            maxCharacters = previous.maxCharacters,
            readingOccurrence = previous.readingOccurrence,
            maxFrequency = previous.maxFrequency,
            workers = previous.workers,
        )
    return if (withoutContinuousEdits == previous) {
        SettingsWriteCadence.DEBOUNCED
    } else {
        SettingsWriteCadence.IMMEDIATE
    }
}

private data class SettingsWriteKey(
    val editRevision: Long,
    val draft: SettingsDraft,
    val deckDirty: Boolean,
)

/** Tracks completed writes. Failed or skipped attempts never suppress a retry of the same state. */
internal class SuccessfulSettingsWriteTracker {
    private var lastSuccessfulKey: SettingsWriteKey? = null

    fun shouldWrite(state: SettingsDraftState): Boolean = keyOf(state) != lastSuccessfulKey

    fun markSuccessful(state: SettingsDraftState) {
        lastSuccessfulKey = keyOf(state)
    }

    private fun keyOf(state: SettingsDraftState): SettingsWriteKey =
        SettingsWriteKey(
            editRevision = state.editRevision,
            draft = state.draft,
            deckDirty = state.deckDirty,
        )
}

/** Coalesces continuous edits while allowing discrete actions through immediately. */
@OptIn(FlowPreview::class)
internal fun Flow<SettingsDraftState>.coalesceSettingsWrites(
    debounceMillis: Long = SETTINGS_AUTOSAVE_DEBOUNCE_MILLIS,
): Flow<SettingsDraftState> =
    debounce { state ->
        if (state.writeCadence == SettingsWriteCadence.IMMEDIATE) {
            0L
        } else {
            debounceMillis
        }
    }
