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
    const val CARD_TYPE_MARKER_KEY = "card_type_marker"

    fun merge(
        currentNoteType: String?,
        selectedNoteType: String,
        fieldNames: List<String>,
        currentFieldMap: Map<String, String>,
        reservedDestinations: Set<String> = emptySet(),
    ): AnkiFieldMapMergeResult {
        if (currentNoteType == selectedNoteType) {
            return AnkiFieldMapMergeResult(currentFieldMap, emptyList())
        }

        val merged = AnkiFieldKeys.ALL.associateWithTo(linkedMapOf()) { "" }
        val firstField = fieldNames.firstOrNull()
        val usedDestinations =
            reservedDestinations
                .filterTo(mutableSetOf()) { destination ->
                    destination.isNotEmpty() && destination in fieldNames && destination != firstField
                }
        firstField?.let { field ->
            merged[AnkiFieldKeys.WORD] = field
            usedDestinations += field
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

    /**
     * Re-run keyword auto-mapping over the note type the user already has selected.
     *
     * [merge] deliberately does nothing on a same-type reselection, so a map saved against an older
     * keyword table keeps its gaps forever. This is the explicit way out, and it mirrors desktop's
     * "Auto-Map Fields from Note Type": a key the keyword table matches is overwritten, a key it
     * does not match keeps whatever the user chose. The one place it goes further than desktop is
     * ownership — Android allows a destination exactly one owner, so a retained manual choice that
     * collides with a keyword match is dropped rather than duplicated.
     */
    fun remap(
        fieldNames: List<String>,
        currentFieldMap: Map<String, String>,
        reservedDestinations: Set<String> = emptySet(),
    ): AnkiFieldMapMergeResult {
        val firstField =
            fieldNames.firstOrNull()
                ?: return AnkiFieldMapMergeResult(currentFieldMap, emptyList())

        val merged = AnkiFieldKeys.ALL.associateWithTo(linkedMapOf()) { "" }
        val usedDestinations =
            reservedDestinations
                .filterTo(mutableSetOf()) { destination ->
                    destination.isNotEmpty() && destination in fieldNames && destination != firstField
                }
        merged[AnkiFieldKeys.WORD] = firstField
        usedDestinations += firstField

        AnkiFieldKeys.OPTIONAL.forEach { key ->
            val suggested = AnkiFieldAutoMap.firstAvailableMatch(key, fieldNames, usedDestinations)
            if (suggested.isNotEmpty()) {
                merged[key] = suggested
                usedDestinations += suggested
            }
        }

        AnkiFieldKeys.OPTIONAL.forEach { key ->
            if (merged.getValue(key).isNotEmpty()) return@forEach
            val current = currentFieldMap[key].orEmpty()
            if (current.isNotEmpty() && current in fieldNames && current !in usedDestinations) {
                merged[key] = current
                usedDestinations += current
            }
        }

        val changes =
            AnkiFieldKeys.ALL.mapNotNull { key ->
                val previous = currentFieldMap[key].orEmpty()
                val replacement = merged.getValue(key)
                if (previous != replacement) {
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
        reservedDestinations: Set<String> = emptySet(),
    ): Map<String, String>? {
        if (logicalKey !in AnkiFieldKeys.ALL) return null
        val firstField = fieldNames.firstOrNull() ?: return null
        if (logicalKey == AnkiFieldKeys.WORD && destination.isEmpty()) return null
        if (destination.isNotEmpty()) {
            if (destination !in fieldNames) return null
            if (destination in reservedDestinations) return null
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
        reservedDestinations: Set<String> = emptySet(),
    ): Boolean =
        assign(
            currentFieldMap,
            logicalKey,
            destination,
            fieldNames,
            reservedDestinations,
        ) != null

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
