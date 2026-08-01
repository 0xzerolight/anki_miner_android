package com.ankiminer.android.media

import android.content.Context
import android.content.SharedPreferences
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Locale

internal enum class SafSelectionSlot(val storageKey: String) {
    VIDEO("video"),
    VIDEO_SUBTITLE("video_subtitle"),
    READING_SOURCE("reading_source"),
    READING_ARCHIVE("reading_archive"),
    READING_SUBTITLE_SERIES("reading_subtitle_series"),
}

internal data class SafSelectionRecord(
    val uri: String,
    val displayName: String,
) {
    init {
        require(isValidSafSelectionRecord(uri, displayName))
    }
}

internal fun safSelectionRecordOrNull(
    uri: String?,
    displayName: String?,
): SafSelectionRecord? {
    if (!isValidSafSelectionRecord(uri, displayName)) return null
    return SafSelectionRecord(
        uri = requireNotNull(uri),
        displayName = requireNotNull(displayName),
    )
}

private fun isValidSafSelectionRecord(
    uri: String?,
    displayName: String?,
): Boolean =
    !uri.isNullOrBlank() &&
        uri.startsWith(CONTENT_URI_PREFIX) &&
        !displayName.isNullOrBlank() &&
        displayName != "." &&
        displayName != ".." &&
        !displayName.contains('/') &&
        !displayName.contains('\\') &&
        displayName.none(Character::isISOControl) &&
        displayName.toByteArray(StandardCharsets.UTF_8).size <= MAX_DISPLAY_NAME_BYTES

internal interface SafSelectionInventory {
    fun selection(slot: SafSelectionSlot): SafSelectionRecord?

    fun putSelection(
        slot: SafSelectionSlot,
        selection: SafSelectionRecord?,
    )

    /**
     * Persist one ownership transaction. Durable implementations must commit all entries together.
     */
    fun putSelections(selections: Map<SafSelectionSlot, SafSelectionRecord?>) {
        selections.forEach(::putSelection)
    }

    /**
     * Drop one slot from a caller that cannot suspend, such as a UI-thread clear.
     *
     * The removal must be visible to [selection] before this returns; only reaching storage may
     * be deferred. Callers that must not release a SAF grant until the removal is durable use
     * [putSelection] on an IO dispatcher instead.
     */
    fun clearSelectionEventually(slot: SafSelectionSlot) {
        putSelection(slot, null)
    }

    fun text(slot: SafSelectionSlot): String?

    fun putText(
        slot: SafSelectionSlot,
        value: String?,
    )

    fun ownedUris(): Set<String>

    fun pruneMissingGrants(grantedUris: Set<String>)
}

internal class SafSelectionPersistenceException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

internal class TransientSafSelectionInventory : SafSelectionInventory {
    private val monitor = Any()
    private val selections = mutableMapOf<SafSelectionSlot, SafSelectionRecord>()
    private val textValues = mutableMapOf<SafSelectionSlot, String>()

    override fun selection(slot: SafSelectionSlot): SafSelectionRecord? =
        synchronized(monitor) { selections[slot] }

    override fun putSelection(
        slot: SafSelectionSlot,
        selection: SafSelectionRecord?,
    ) = putSelections(mapOf(slot to selection))

    override fun putSelections(
        selections: Map<SafSelectionSlot, SafSelectionRecord?>,
    ) {
        synchronized(monitor) {
            selections.forEach { (slot, selection) ->
                if (selection == null) {
                    this.selections.remove(slot)
                } else {
                    this.selections[slot] = selection
                }
            }
        }
    }

    override fun text(slot: SafSelectionSlot): String? =
        synchronized(monitor) { textValues[slot] }

    override fun putText(
        slot: SafSelectionSlot,
        value: String?,
    ) {
        synchronized(monitor) {
            if (value.isNullOrBlank()) textValues.remove(slot) else textValues[slot] = value
        }
    }

    override fun ownedUris(): Set<String> =
        synchronized(monitor) {
            selections.values.mapTo(linkedSetOf(), SafSelectionRecord::uri)
        }

    override fun pruneMissingGrants(grantedUris: Set<String>) {
        synchronized(monitor) {
            val staleSlots =
                selections
                    .filterValues { selection -> selection.uri !in grantedUris }
                    .keys
                    .toList()
            staleSlots.forEach(selections::remove)
            val source = selections[SafSelectionSlot.READING_SOURCE]
            if (source?.hasExtension("mokuro") != true) {
                selections.remove(SafSelectionSlot.READING_ARCHIVE)
            }
            if (source?.hasExtension("ass", "srt", "ssa", "vtt") != true) {
                textValues.remove(SafSelectionSlot.READING_SUBTITLE_SERIES)
            }
        }
    }
}

internal class AndroidSafSelectionInventory(
    context: Context,
    preferencesName: String = PREFERENCES_NAME,
) : SafSelectionInventory {
    private val monitor = Any()
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    override fun selection(slot: SafSelectionSlot): SafSelectionRecord? =
        synchronized(monitor) {
            safSelectionRecordOrNull(
                uri = preferences.getString(slot.uriKey, null),
                displayName = preferences.getString(slot.displayNameKey, null),
            )
        }

    override fun putSelection(
        slot: SafSelectionSlot,
        selection: SafSelectionRecord?,
    ) = putSelections(mapOf(slot to selection))

    override fun putSelections(
        selections: Map<SafSelectionSlot, SafSelectionRecord?>,
    ) {
        synchronized(monitor) {
            val editor = preferences.edit()
            selections.forEach { (slot, selection) ->
                if (selection == null) {
                    editor.remove(slot.uriKey).remove(slot.displayNameKey)
                } else {
                    editor
                        .putString(slot.uriKey, selection.uri)
                        .putString(slot.displayNameKey, selection.displayName)
                }
            }
            if (!editor.commit()) {
                throw SafSelectionPersistenceException("Could not persist SAF selection inventory")
            }
        }
    }

    /**
     * Written with `apply`, unlike [putSelections]. Clearing runs from non-suspending UI callbacks
     * (`clearSource`, `clearArchive`, selection restore), where `commit` puts a disk write in front
     * of a frame. `apply` still mutates the in-memory map synchronously under [monitor], so every
     * read after this call — including the restore that follows a clear — already sees the slot
     * gone; only the file write moves to a background thread, and the platform flushes it before
     * the activity or service handoff that precedes a background process kill.
     *
     * A hard kill inside that window leaves the record on disk. That is recoverable and this
     * direction only: the reconciliation in [pruneMissingGrants] drops any slot whose URI is no
     * longer a persisted grant, and a clear releases its grant. Writes that must be durable before
     * their grant is released keep [putSelections] and its `commit`.
     */
    override fun clearSelectionEventually(slot: SafSelectionSlot) {
        synchronized(monitor) {
            preferences.edit().remove(slot.uriKey).remove(slot.displayNameKey).apply()
        }
    }

    override fun text(slot: SafSelectionSlot): String? =
        synchronized(monitor) {
            preferences.getString(slot.textKey, null)?.takeIf(String::isNotBlank)
        }

    /**
     * Written with `apply`, unlike [putSelection]. The series name arrives one keystroke at a time
     * straight off the UI thread, and a synchronous `commit` per character put a disk write in
     * front of a frame. `apply` still updates the in-memory map synchronously — a read immediately
     * after a write sees it — and persists on a background thread.
     */
    override fun putText(
        slot: SafSelectionSlot,
        value: String?,
    ) {
        synchronized(monitor) {
            val editor = preferences.edit()
            if (value.isNullOrBlank()) editor.remove(slot.textKey) else editor.putString(slot.textKey, value)
            editor.apply()
        }
    }

    override fun ownedUris(): Set<String> =
        synchronized(monitor) {
            DOCUMENT_SLOTS.mapNotNullTo(linkedSetOf()) { slot -> selection(slot)?.uri }
        }

    override fun pruneMissingGrants(grantedUris: Set<String>) {
        synchronized(monitor) {
            val staleSlots =
                DOCUMENT_SLOTS.filter { slot ->
                    val selection = selection(slot)
                    (
                        selection == null &&
                            (preferences.contains(slot.uriKey) ||
                                preferences.contains(slot.displayNameKey))
                    ) || selection?.uri?.let { it !in grantedUris } == true
                }.toMutableSet()
            val retainedSource =
                selection(SafSelectionSlot.READING_SOURCE)
                    ?.takeIf { SafSelectionSlot.READING_SOURCE !in staleSlots }
            if (retainedSource?.hasExtension("mokuro") != true) {
                staleSlots += SafSelectionSlot.READING_ARCHIVE
            }
            val clearSeries =
                retainedSource?.hasExtension("ass", "srt", "ssa", "vtt") != true
            if (
                staleSlots.isEmpty() &&
                (!clearSeries ||
                    !preferences.contains(SafSelectionSlot.READING_SUBTITLE_SERIES.textKey))
            ) {
                return
            }
            val editor = preferences.edit()
            staleSlots.forEach { slot ->
                editor.remove(slot.uriKey).remove(slot.displayNameKey)
            }
            if (clearSeries) {
                editor.remove(SafSelectionSlot.READING_SUBTITLE_SERIES.textKey)
            }
            if (!editor.commit()) {
                throw SafSelectionPersistenceException(
                    "Could not reconcile SAF selection inventory",
                )
            }
        }
    }

    private val SafSelectionSlot.uriKey: String
        get() = "${storageKey}.uri"

    private val SafSelectionSlot.displayNameKey: String
        get() = "${storageKey}.display_name"

    private val SafSelectionSlot.textKey: String
        get() = "${storageKey}.text"

    private companion object {
        const val PREFERENCES_NAME = "saf_selection_inventory"
        val DOCUMENT_SLOTS =
            setOf(
                SafSelectionSlot.VIDEO,
                SafSelectionSlot.VIDEO_SUBTITLE,
                SafSelectionSlot.READING_SOURCE,
                SafSelectionSlot.READING_ARCHIVE,
            )
    }
}

private const val CONTENT_URI_PREFIX = "content://"
private const val MAX_DISPLAY_NAME_BYTES = 255

private fun SafSelectionRecord.hasExtension(vararg extensions: String): Boolean {
    val extension = displayName.substringAfterLast('.', missingDelimiterValue = "")
    return extension.lowercase(Locale.ROOT) in extensions
}
