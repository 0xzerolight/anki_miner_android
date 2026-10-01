package com.ankiminer.android.data.resources

import androidx.annotation.StringRes
import com.ankiminer.android.R

/** Slot the recommended dictionary owns. Jitendex stays pinned but is not part of the set. */
internal const val JMDICT_SLOT_ID = "jmdict"

enum class RecommendedResourceAction {
    /** Nothing occupies the slot. */
    INSTALL,

    /** The slot is occupied but unusable, so the import overwrites it. */
    REPLACE,

    /** A healthy install already owns the slot; never clobber it. */
    SKIP,
}

data class RecommendedResourceItem(
    val resource: CatalogResource,
    val action: RecommendedResourceAction,
) {
    val replace: Boolean get() = action == RecommendedResourceAction.REPLACE
}

/**
 * What one press of "Download recommended resources" would do, in catalog order.
 *
 * The same value decides the button's enablement and drives the runner, so the two can never
 * disagree about what "already installed" means.
 */
data class RecommendedResourcePlan(
    val items: List<RecommendedResourceItem> = emptyList(),
) {
    val pending: List<RecommendedResourceItem>
        get() = items.filterNot { it.action == RecommendedResourceAction.SKIP }

    /** Every member is present and healthy: the button has nothing to do. */
    val isSatisfied: Boolean get() = items.isNotEmpty() && pending.isEmpty()

    /** A null catalog (pre-refresh) yields no items, which is neither satisfied nor actionable. */
    val isActionable: Boolean get() = pending.isNotEmpty()
}

/**
 * Health per kind mirrors that kind's own chain gate, not the weaker startup-corruption rule: a
 * zero-entry slot is intact but useless to mine from, so the batch rebuilds it.
 *
 * A language's engine data comes first: the language cannot mine without it, and a frequency list
 * the language lemmatises is ranked by the tagger that data feeds.
 */
internal fun recommendedResourcePlan(
    catalog: ResourceCatalog?,
    dictionaries: List<InstalledDictionary>,
    frequencySources: List<InstalledFrequencySource>,
    pitchSources: List<InstalledPitchSource>,
    installedLanguageData: Set<String> = emptySet(),
): RecommendedResourcePlan =
    RecommendedResourcePlan(
        (catalog?.languageData.orEmpty() + catalog?.recommendedResources.orEmpty()).map { resource ->
            val action =
                when (resource) {
                    // An incomplete extraction is not installed, and the install replaces it whole.
                    is LanguageDataCatalogResource ->
                        if (resource.resourceId in installedLanguageData) {
                            RecommendedResourceAction.SKIP
                        } else {
                            RecommendedResourceAction.INSTALL
                        }
                    is YomitanCatalogResource -> {
                        val slot = dictionaries.firstOrNull { it.occupied && it.slotId == resource.slotId }
                        when {
                            slot == null -> RecommendedResourceAction.INSTALL
                            // Deliberately not keyed on catalogResourceId: a healthy custom import
                            // owning the slot is the user's choice and must not be overwritten. A
                            // slot stamped for another language is not in this language's chain.
                            slot.isChainEligible && slot.language == catalog?.language ->
                                RecommendedResourceAction.SKIP
                            // An unusable occupied slot already fails startup, and the recommended
                            // set is the recovery path, so it repairs rather than refuses.
                            else -> RecommendedResourceAction.REPLACE
                        }
                    }
                    is FrequencyCatalogResource -> {
                        val source = frequencySources.firstOrNull { it.sourceId == resource.sourceId }
                        localAction(source?.schemaOk, source?.entryCount, source?.language == catalog?.language)
                    }
                    is PitchCatalogResource -> {
                        val source = pitchSources.firstOrNull { it.sourceId == resource.sourceId }
                        localAction(source?.schemaOk, source?.entryCount, source?.language == catalog?.language)
                    }
                    is UniDicCatalogResource ->
                        error("UniDic has its own install path and must not be recommended")
                }
            RecommendedResourceItem(resource, action)
        },
    )

private fun localAction(
    schemaOk: Boolean?,
    entryCount: Long?,
    sameLanguage: Boolean,
): RecommendedResourceAction =
    when {
        schemaOk == null -> RecommendedResourceAction.INSTALL
        schemaOk && sameLanguage && (entryCount ?: 0L) > 0L -> RecommendedResourceAction.SKIP
        else -> RecommendedResourceAction.REPLACE
    }

/**
 * One name per recommended member, shared by the progress label, the failure text and the card.
 *
 * Japanese members keep their own names; another language's members are named by what they are,
 * since their catalog display names are untranslated data.
 */
@StringRes
internal fun recommendedResourceTitleRes(resource: CatalogResource): Int {
    val japanese = FrozenResourceCatalog.catalogOf(resource.resourceId)?.language == JAPANESE
    return when (resource) {
        is YomitanCatalogResource ->
            when {
                !japanese -> R.string.language_dictionary_resource_title
                resource.slotId == JMDICT_SLOT_ID -> R.string.jmdict_resource_title
                else -> R.string.jitendex_resource_title
            }
        is FrequencyCatalogResource ->
            if (japanese) R.string.jpdb_frequency_resource_title else R.string.language_frequency_resource_title
        is PitchCatalogResource -> R.string.kanjium_pitch_resource_title
        is UniDicCatalogResource -> R.string.unidic_resource_title
        is LanguageDataCatalogResource -> R.string.language_data_resource_title
    }
}

/** What one Download press still fetches, by catalog name and archive size. */
internal data class RequiredDownloads(
    val names: List<String>,
    val bytes: Long,
)

/**
 * The tokenizer when this language needs it and it is missing, then the recommended set's pending
 * members. Built from the plan, so a Thai learner reads about Thai Wiktionary, not JMdict.
 */
internal fun requiredDownloads(
    plan: RecommendedResourcePlan,
    uniDicMissing: Boolean,
    uniDic: UniDicCatalogResource = FrozenResourceCatalog.value.unidic,
): RequiredDownloads {
    val resources = listOfNotNull(uniDic.takeIf { uniDicMissing }) + plan.pending.map { it.resource }
    return RequiredDownloads(resources.map { it.displayName }, resources.sumOf { it.archive.sizeBytes })
}
