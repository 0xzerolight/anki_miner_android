package com.ankiminer.android.anki.provider

/** One non-empty Anki destination assigned to multiple logical engine fields. */
internal data class AnkiFieldMapConflict(
    val destination: String,
    val logicalKeys: List<String>,
)

/** A retained mapping which had to change when the user selected a different note type. */
internal data class AnkiFieldMappingChange(
    val logicalKey: String,
    val previousDestination: String,
    val newDestination: String,
)

internal data class AnkiFieldMapMergeResult(
    val fieldMap: Map<String, String>,
    val changes: List<AnkiFieldMappingChange>,
)

/**
 * Pure ownership policy for user note-type fields.
 *
 * Every non-empty Anki destination has at most one logical owner. The note type's first field is
 * reserved for [AnkiFieldKeys.WORD]. Note-type changes retain valid manual choices, then auto-fill
 * only unowned destinations. A same-type reselection returns the exact input map.
 */
internal object AnkiFieldMapPolicy {
    fun merge(
        currentNoteType: String?,
        selectedNoteType: String,
        fieldNames: List<String>,
        currentFieldMap: Map<String, String>,
    ): AnkiFieldMapMergeResult {
        if (currentNoteType == selectedNoteType) {
            return AnkiFieldMapMergeResult(currentFieldMap, emptyList())
        }

        val merged = AnkiFieldKeys.ALL.associateWithTo(linkedMapOf()) { "" }
        val usedDestinations = mutableSetOf<String>()
        fieldNames.firstOrNull()?.let { firstField ->
            merged[AnkiFieldKeys.WORD] = firstField
            usedDestinations += firstField
        }

        AnkiFieldKeys.OPTIONAL.forEach { key ->
            val current = currentFieldMap[key].orEmpty()
            if (current.isNotEmpty() && current in fieldNames && current !in usedDestinations) {
                merged[key] = current
                usedDestinations += current
            }
        }

        AnkiFieldKeys.OPTIONAL.forEach { key ->
            if (merged.getValue(key).isNotEmpty()) return@forEach
            val suggested =
                AnkiFieldAutoMap.firstAvailableMatch(key, fieldNames, usedDestinations)
            if (suggested.isNotEmpty()) {
                merged[key] = suggested
                usedDestinations += suggested
            }
        }

        val changes =
            AnkiFieldKeys.ALL.mapNotNull { key ->
                val previous = currentFieldMap[key].orEmpty()
                val replacement = merged.getValue(key)
                if (previous.isNotEmpty() && previous != replacement) {
                    AnkiFieldMappingChange(key, previous, replacement)
                } else {
                    null
                }
            }
        return AnkiFieldMapMergeResult(merged, changes)
    }

    /** Return an updated map, or null when the manual choice violates destination ownership. */
    fun assign(
        currentFieldMap: Map<String, String>,
        logicalKey: String,
        destination: String,
        fieldNames: List<String>,
    ): Map<String, String>? {
        if (logicalKey !in AnkiFieldKeys.ALL) return null
        val firstField = fieldNames.firstOrNull() ?: return null
        if (logicalKey == AnkiFieldKeys.WORD && destination.isEmpty()) return null
        if (destination.isNotEmpty()) {
            if (destination !in fieldNames) return null
            if (logicalKey == AnkiFieldKeys.WORD && destination != firstField) return null
            if (logicalKey != AnkiFieldKeys.WORD && destination == firstField) return null
        }

        val updated = LinkedHashMap(currentFieldMap)
        if (updated[AnkiFieldKeys.WORD].isNullOrEmpty()) {
            updated[AnkiFieldKeys.WORD] = firstField
        }
        if (destination.isEmpty()) {
            updated.remove(logicalKey)
        } else {
            updated[logicalKey] = destination
        }
        return updated.takeIf { firstConflict(it) == null }
    }

    /** Valid UI destinations. Word is mandatory and owns only field[0], so it has no None option. */
    fun destinationOptions(
        logicalKey: String,
        fieldNames: List<String>,
    ): List<String> =
        if (logicalKey == AnkiFieldKeys.WORD) {
            fieldNames.take(1)
        } else {
            listOf("") + fieldNames
        }

    fun isDestinationAvailable(
        currentFieldMap: Map<String, String>,
        logicalKey: String,
        destination: String,
        fieldNames: List<String>,
    ): Boolean = assign(currentFieldMap, logicalKey, destination, fieldNames) != null

    fun firstConflict(fieldMap: Map<String, String>): AnkiFieldMapConflict? {
        val ownersByDestination = linkedMapOf<String, MutableList<String>>()
        orderedKeys(fieldMap).forEach { key ->
            val destination = fieldMap[key].orEmpty()
            if (destination.isNotEmpty()) {
                ownersByDestination.getOrPut(destination, ::mutableListOf) += key
            }
        }
        return ownersByDestination.entries
            .firstOrNull { (_, owners) -> owners.size > 1 }
            ?.let { (destination, owners) -> AnkiFieldMapConflict(destination, owners.toList()) }
    }

    fun conflictAfterAssignment(
        currentFieldMap: Map<String, String>,
        logicalKey: String,
        destination: String,
    ): AnkiFieldMapConflict? {
        if (destination.isEmpty()) return null
        val owners =
            orderedKeys(currentFieldMap + (logicalKey to destination))
                .filter { key ->
                    key == logicalKey || currentFieldMap[key] == destination
                }
        return if (owners.size > 1) AnkiFieldMapConflict(destination, owners) else null
    }

    private fun orderedKeys(fieldMap: Map<String, String>): List<String> =
        AnkiFieldKeys.ALL.filter(fieldMap::containsKey) +
            fieldMap.keys.filterNot(AnkiFieldKeys.ALL::contains).sorted()
}
