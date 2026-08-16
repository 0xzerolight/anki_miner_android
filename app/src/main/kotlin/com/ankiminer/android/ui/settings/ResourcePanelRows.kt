package com.ankiminer.android.ui.settings

import com.ankiminer.android.data.resources.InstalledAudioPack
import com.ankiminer.android.data.resources.InstalledDictionary
import com.ankiminer.android.data.resources.InstalledFrequencySource
import com.ankiminer.android.data.resources.InstalledPitchSource
import com.ankiminer.android.data.settings.ResourceChainSelection

/** Row id of the pinned Jisho fallback, which is a draft boolean rather than a chain entry. */
internal const val JISHO_ROW_ID = "jisho"

/**
 * The strings one panel's rows need.
 *
 * Row assembly is deliberately not composable — it is the part with branching worth testing on the
 * JVM — so the caller resolves the text and passes it down.
 */
internal data class ResourceRowStrings(
    val entries: (Long) -> String,
    val notInChain: String,
    val missingWarning: String,
    val repairWarning: String,
)

/** [ResourceRowStrings] plus the dictionary-only row actions and the pinned Jisho row. */
internal data class DictionaryRowStrings(
    val rows: ResourceRowStrings,
    val repairAction: String,
    val replaceAction: String,
    val jishoTitle: String,
    val jishoMeta: String,
    val jishoWarning: String,
)

/**
 * One installed resource flattened to what a row shows, so the four inventories share one builder.
 *
 * [usable] is each kind's own health rule — the same one `SettingsViewModel` uses to decide which
 * ids may enter a chain, which is why an unusable slot only ever appears as a pinned row.
 */
internal data class ResourcePanelSlot(
    val id: String,
    val title: String,
    val entryCount: Long,
    val usable: Boolean,
)

/**
 * Rows for the dictionary panel: the chain in its own order, then any slot the chain does not
 * name, then the pinned Jisho fallback.
 *
 * A catalog-owned slot that has gone bad offers Repair — a re-install of the same catalog
 * resource. Every other slot offers Replace, which is what the inventory card this panel absorbs
 * offered for each occupied slot: importing over the slot is the only way to change what fills it.
 */
internal fun dictionaryPanelRows(
    chain: List<ResourceChainSelection>,
    installed: List<InstalledDictionary>,
    jishoEnabled: Boolean,
    strings: DictionaryRowStrings,
    onChainChange: (List<ResourceChainSelection>) -> Unit,
    onJishoChange: (Boolean) -> Unit,
    onRepair: (catalogResourceId: String) -> Unit,
    onReplace: (slotId: String) -> Unit,
): List<ResourceRowSpec> {
    val occupied = installed.filter { it.occupied }
    val catalogResourceIds = occupied.associate { it.slotId to it.catalogResourceId }
    return resourcePanelRows(
        chain = chain,
        slots =
            occupied.map {
                ResourcePanelSlot(it.slotId, it.sourceName, it.entryCount, it.isUsable)
            },
        strings = strings.rows,
        onChainChange = onChainChange,
        quietAction = { slot ->
            val catalogResourceId = catalogResourceIds[slot.id]
            if (!slot.usable && catalogResourceId != null) {
                ResourcePanelAction(strings.repairAction) { onRepair(catalogResourceId) }
            } else {
                ResourcePanelAction(strings.replaceAction) { onReplace(slot.id) }
            }
        },
    ) +
        ResourceRowSpec(
            id = JISHO_ROW_ID,
            title = strings.jishoTitle,
            metadata = listOf(strings.jishoMeta),
            enabled = jishoEnabled,
            onToggle = onJishoChange,
            warning = strings.jishoWarning,
            movable = false,
            removable = false,
        )
}

internal fun pitchPanelRows(
    chain: List<ResourceChainSelection>,
    installed: List<InstalledPitchSource>,
    strings: ResourceRowStrings,
    onChainChange: (List<ResourceChainSelection>) -> Unit,
): List<ResourceRowSpec> =
    resourcePanelRows(
        chain = chain,
        slots =
            installed.map {
                ResourcePanelSlot(
                    id = it.sourceId,
                    title = it.sourceName,
                    entryCount = it.entryCount,
                    usable = it.schemaOk && it.entryCount > 0,
                )
            },
        strings = strings,
        onChainChange = onChainChange,
    )

internal fun audioPanelRows(
    chain: List<ResourceChainSelection>,
    installed: List<InstalledAudioPack>,
    strings: ResourceRowStrings,
    onChainChange: (List<ResourceChainSelection>) -> Unit,
): List<ResourceRowSpec> =
    resourcePanelRows(
        chain = chain,
        slots =
            installed.map {
                ResourcePanelSlot(
                    id = it.packId,
                    title = it.sourceName,
                    entryCount = it.entryCount,
                    usable = it.contentAvailable && it.entryCount > 0,
                )
            },
        strings = strings,
        onChainChange = onChainChange,
    )

internal fun frequencyPanelRows(
    chain: List<ResourceChainSelection>,
    installed: List<InstalledFrequencySource>,
    strings: ResourceRowStrings,
    onChainChange: (List<ResourceChainSelection>) -> Unit,
): List<ResourceRowSpec> =
    resourcePanelRows(
        chain = chain,
        slots =
            installed.map {
                ResourcePanelSlot(
                    id = it.sourceId,
                    title = it.sourceName,
                    entryCount = it.entryCount,
                    usable = it.schemaOk && it.entryCount > 0,
                )
            },
        strings = strings,
        onChainChange = onChainChange,
    )

/**
 * The chain in order, then every installed slot the chain does not name.
 *
 * A chain entry with no slot behind it survives as its own row rather than being hidden: the draft
 * is reconciled against the inventory asynchronously, and a silently dropped row would leave the
 * user unable to clear an entry the engine is still going to look for. Pinned rows cannot be
 * enabled — the chain is what enabling means — but they can be removed, which is the only way to
 * delete a slot too broken to enter a chain.
 */
private fun resourcePanelRows(
    chain: List<ResourceChainSelection>,
    slots: List<ResourcePanelSlot>,
    strings: ResourceRowStrings,
    onChainChange: (List<ResourceChainSelection>) -> Unit,
    quietAction: (ResourcePanelSlot) -> ResourcePanelAction? = { null },
): List<ResourceRowSpec> {
    val slotsById = slots.associateBy { it.id }
    val chained =
        chain.map { selection ->
            val slot = slotsById[selection.resourceId]
            ResourceRowSpec(
                id = selection.resourceId,
                title = slot?.title ?: selection.resourceId,
                metadata = slot.metadata(strings),
                enabled = selection.enabled,
                onToggle = { enabled ->
                    onChainChange(chain.withResourceEnabled(selection.resourceId, enabled))
                },
                warning =
                    when {
                        slot == null -> strings.missingWarning
                        !slot.usable -> strings.repairWarning
                        else -> null
                    },
                quietAction = slot?.let(quietAction),
            )
        }
    val chainedIds = chain.mapTo(mutableSetOf(), ResourceChainSelection::resourceId)
    val pinned =
        slots.filterNot { it.id in chainedIds }.map { slot ->
            ResourceRowSpec(
                id = slot.id,
                title = slot.title,
                metadata = slot.metadata(strings) + strings.notInChain,
                enabled = false,
                onToggle = null,
                warning = if (slot.usable) null else strings.repairWarning,
                movable = false,
                quietAction = quietAction(slot),
            )
        }
    return chained + pinned
}

/**
 * The id line and the entry count.
 *
 * The id is shown because it, not the display name, is what the chain and the engine key on: two
 * imports of the same pack differ only by id, and removing the wrong one is unrecoverable.
 */
private fun ResourcePanelSlot?.metadata(strings: ResourceRowStrings): List<String> =
    if (this == null) emptyList() else listOf(id, strings.entries(entryCount))

/** Enable or disable one chain entry, leaving the order and every other entry untouched. */
internal fun List<ResourceChainSelection>.withResourceEnabled(
    id: String,
    enabled: Boolean,
): List<ResourceChainSelection> =
    map { if (it.resourceId == id) it.copy(enabled = enabled) else it }

/** Swap one chain entry with its neighbour; a move off either end is a no-op. */
internal fun List<ResourceChainSelection>.movedResource(
    id: String,
    delta: Int,
): List<ResourceChainSelection> {
    val index = indexOfFirst { it.resourceId == id }
    val target = index + delta
    if (index < 0 || target !in indices) return this
    return toMutableList().also { values ->
        val held = values[index]
        values[index] = values[target]
        values[target] = held
    }
}

/** Drop a chain entry. Used for entries whose resource is gone, which have nothing to delete. */
internal fun List<ResourceChainSelection>.withoutResource(id: String): List<ResourceChainSelection> =
    filterNot { it.resourceId == id }
