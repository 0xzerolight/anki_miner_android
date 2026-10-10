package com.ankiminer.android.anki.provider

import com.ankiminer.android.data.settings.CardType
import com.ankiminer.android.data.settings.PitchCategoryFormat
import com.ankiminer.android.engine.LanguageExtraCardField

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
    /** Keys "Fill in automatically" itself assigned ([AnkiFieldMapPolicy.remap], [AnkiFieldMapPolicy.applyPreset]). */
    val filledCount: Int = 0,
)

/** Everything a recognised note type's preset writes, beside the field map (desktop `apply_note_type_preset`). */
internal data class AnkiPresetApplication(
    val mapping: AnkiFieldMapMergeResult,
    val cardType: CardType?,
    val cardTypeMarkerField: String?,
    val pitchCategoryFormat: PitchCategoryFormat,
    val boldTargetInSentence: Boolean?,
)

/**
 * Pure ownership policy for user note-type fields.
 *
 * Every non-empty Anki destination has at most one logical owner. The note type's first field is
 * reserved for [AnkiFieldKeys.WORD]. Note-type changes retain valid manual choices, then auto-fill
 * only unowned destinations. A same-type reselection returns the exact input map.
 *
 * A mining language's own card fields (Hebrew `transliteration`, `root`, ...) come in as
 * `extraFields`: they are matched on their own placeholder spelling after the keyword pass, the rule
 * desktop's `auto_map_profile_fields` applies. Japanese passes none, so its maps are unchanged.
 */
internal object AnkiFieldMapPolicy {
    const val CARD_TYPE_MARKER_KEY = "card_type_marker"

    fun merge(
        currentNoteType: String?,
        selectedNoteType: String,
        fieldNames: List<String>,
        currentFieldMap: Map<String, String>,
        reservedDestinations: Set<String> = emptySet(),
        extraFields: List<LanguageExtraCardField> = emptyList(),
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

        val extraKeys = extraFields.map(LanguageExtraCardField::key)
        extraKeys.forEach { key ->
            val current = currentFieldMap[key].orEmpty()
            if (current.isNotEmpty() && current in fieldNames && current !in usedDestinations) {
                merged[key] = current
                usedDestinations += current
            }
        }
        autoMapProfileFields(fieldNames, extraFields.filterNot { it.key in merged }, usedDestinations)
            .forEach { (key, destination) ->
                merged[key] = destination
                usedDestinations += destination
            }

        // A stock two-field note type (Basic's Front/Back, or its localised twin) matches no
        // keyword past the word, so its second field stayed empty and the first mined card had no
        // back. Shape, not name: localised stock types call it "Rückseite", "Verso", "背面". Only on
        // this fresh pick and only into an unowned field, so a user's map is never rewritten.
        if (fieldNames.size == 2) {
            val second = fieldNames[1]
            if (second !in usedDestinations && merged.getValue(AnkiFieldKeys.DEFINITION).isEmpty()) {
                merged[AnkiFieldKeys.DEFINITION] = second
                usedDestinations += second
            }
        }

        val changes =
            (AnkiFieldKeys.ALL + extraKeys).mapNotNull { key ->
                val previous = currentFieldMap[key].orEmpty()
                val replacement = merged[key].orEmpty()
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
        extraFields: List<LanguageExtraCardField> = emptyList(),
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
        var filled = 1

        AnkiFieldKeys.OPTIONAL.forEach { key ->
            val suggested = AnkiFieldAutoMap.firstAvailableMatch(key, fieldNames, usedDestinations)
            if (suggested.isNotEmpty()) {
                merged[key] = suggested
                usedDestinations += suggested
                filled += 1
            }
        }

        autoMapProfileFields(fieldNames, extraFields, usedDestinations).forEach { (key, destination) ->
            merged[key] = destination
            usedDestinations += destination
            filled += 1
        }

        val extraKeys = extraFields.map(LanguageExtraCardField::key)
        (AnkiFieldKeys.OPTIONAL + extraKeys).forEach { key ->
            if (merged[key].orEmpty().isNotEmpty()) return@forEach
            val current = currentFieldMap[key].orEmpty()
            if (current.isNotEmpty() && current in fieldNames && current !in usedDestinations) {
                merged[key] = current
                usedDestinations += current
            }
        }

        return AnkiFieldMapMergeResult(merged, everyChange(currentFieldMap, merged, extraKeys), filled)
    }

    /**
     * Write a recognised note type's preset, desktop's "Fill in automatically" preset path.
     *
     * Every key the preset answers is overwritten, `""` included: Lapis has no sentence-reading
     * field, and that is an answer, not a gap. [extraFields] (the language's own fields the note type
     * has) come next, and a valid manual choice for a language field the fill did not answer stays.
     * Android's ownership rules still hold: the word owns field[0] whatever the preset calls it, and
     * no destination gets two owners. Desktop also writes the preset's pitch-category format and
     * marker names; Android keeps one marker for the active card mode, so a mode the note type cannot
     * render is turned off rather than left marking nothing. Bold target words are turned on when the
     * preset relies on them and never off.
     */
    fun applyPreset(
        preset: NoteTypePreset,
        presetFields: Map<String, String>,
        extraFields: Map<String, String>,
        fieldNames: List<String>,
        currentFieldMap: Map<String, String>,
        currentCardType: CardType?,
        currentBoldTargetInSentence: Boolean?,
    ): AnkiPresetApplication {
        val firstField = fieldNames.firstOrNull()
        val cardType = currentCardType?.takeIf { it in preset.supportedCardTypes }
        val marker =
            cardType
                ?.let { preset.cardTypeMarkerFields[it] }
                ?.takeIf { it.isNotEmpty() && it in fieldNames && it != firstField }
        val merged = AnkiFieldKeys.ALL.associateWithTo(linkedMapOf()) { "" }
        val usedDestinations = setOfNotNull(marker).toMutableSet()
        var filled = 0
        fun claim(
            key: String,
            destination: String,
        ): Boolean {
            if (destination.isEmpty() || destination !in fieldNames || destination in usedDestinations) return false
            merged[key] = destination
            usedDestinations += destination
            return true
        }
        if (firstField != null && claim(AnkiFieldKeys.WORD, firstField)) filled += 1
        AnkiFieldKeys.OPTIONAL.forEach { key ->
            if (claim(key, presetFields[key].orEmpty())) filled += 1
        }
        extraFields.forEach { (key, destination) ->
            if (key !in AnkiFieldKeys.ALL && claim(key, destination)) filled += 1
        }
        currentFieldMap.forEach { (key, destination) ->
            if (key !in AnkiFieldKeys.ALL && key !in merged) claim(key, destination)
        }
        val extraKeys = (currentFieldMap.keys + merged.keys).filterNot(AnkiFieldKeys.ALL::contains).distinct()
        return AnkiPresetApplication(
            mapping = AnkiFieldMapMergeResult(merged, everyChange(currentFieldMap, merged, extraKeys), filled),
            cardType = cardType,
            cardTypeMarkerField = marker,
            pitchCategoryFormat = preset.pitchCategoryFormat,
            boldTargetInSentence = if (preset.boldTargetInSentence) true else currentBoldTargetInSentence,
        )
    }

    private fun everyChange(
        previousMap: Map<String, String>,
        updatedMap: Map<String, String>,
        extraKeys: List<String>,
    ): List<AnkiFieldMappingChange> =
        (AnkiFieldKeys.ALL + extraKeys).mapNotNull { key ->
            val previous = previousMap[key].orEmpty()
            val replacement = updatedMap[key].orEmpty()
            if (previous != replacement) AnkiFieldMappingChange(key, previous, replacement) else null
        }

    /** Return an updated map, or null when the manual choice violates destination ownership. */
    fun assign(
        currentFieldMap: Map<String, String>,
        logicalKey: String,
        destination: String,
        fieldNames: List<String>,
        reservedDestinations: Set<String> = emptySet(),
        /** The active language's own card-field keys, assignable beside [AnkiFieldKeys.ALL]. */
        extraKeys: Collection<String> = emptyList(),
    ): Map<String, String>? {
        if (logicalKey !in AnkiFieldKeys.ALL && logicalKey !in extraKeys) return null
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
        extraKeys: Collection<String> = emptyList(),
    ): Boolean =
        assign(
            currentFieldMap,
            logicalKey,
            destination,
            fieldNames,
            reservedDestinations,
            extraKeys,
        ) != null

    /**
     * Desktop `note_presets.auto_map_profile_fields`: each spec takes the first field, in
     * [fieldNames] order, whose normalised name equals its placeholder's or one of its aliases',
     * unless [claimed] or an earlier spec holds it. A spec with no match is absent, never `""`.
     */
    fun autoMapProfileFields(
        fieldNames: List<String>,
        specs: List<LanguageExtraCardField>,
        claimed: Set<String>,
    ): Map<String, String> {
        val taken = claimed.filterTo(mutableSetOf()) { it.isNotEmpty() }
        val mapping = linkedMapOf<String, String>()
        specs.forEach { spec ->
            val spellings = (listOf(spec.placeholder) + spec.aliases).mapTo(mutableSetOf(), AnkiFieldAutoMap::normalize)
            fieldNames
                .firstOrNull { it !in taken && AnkiFieldAutoMap.normalize(it) in spellings }
                ?.let { match ->
                    mapping[spec.key] = match
                    taken += match
                }
        }
        return mapping
    }

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
