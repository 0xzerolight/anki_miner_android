package com.ankiminer.android.data.resources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendedResourcesTest {
    private val catalog = FrozenResourceCatalog.value
    private val dictionarySlot = catalog.dictionary(catalog.recommended.first())!!.slotId
    private val frequencyId = catalog.frequencies.single().sourceId
    private val pitchId = catalog.pitchSources.single().sourceId

    @Test
    fun emptyInventoryPlansEveryMemberAsAnInstall() {
        val plan = plan()

        assertEquals(catalog.recommended, plan.items.map { it.resource.resourceId })
        assertTrue(plan.items.all { it.action == RecommendedResourceAction.INSTALL })
        assertTrue(plan.isActionable)
        assertFalse(plan.isSatisfied)
    }

    @Test
    fun healthyMembersAreSkipped() {
        val plan =
            plan(
                dictionaries = listOf(dictionary(dictionarySlot)),
                frequencySources = listOf(frequency(frequencyId)),
                pitchSources = listOf(pitch(pitchId)),
            )

        assertTrue(plan.pending.isEmpty())
        assertTrue(plan.isSatisfied)
        assertFalse(plan.isActionable)
    }

    @Test
    fun zeroEntryDictionarySlotIsReplacedRatherThanSkipped() {
        val plan = plan(dictionaries = listOf(dictionary(dictionarySlot, entryCount = 0L)))

        val item = plan.items.first { it.resource is YomitanCatalogResource }
        assertEquals(RecommendedResourceAction.REPLACE, item.action)
        assertTrue(item.replace)
    }

    @Test
    fun schemaStaleLocalSourcesAreReplaced() {
        val plan =
            plan(
                frequencySources = listOf(frequency(frequencyId, schemaOk = false)),
                pitchSources = listOf(pitch(pitchId, entryCount = 0L)),
            )

        assertEquals(
            listOf(RecommendedResourceAction.REPLACE, RecommendedResourceAction.REPLACE),
            plan.items.filterNot { it.resource is YomitanCatalogResource }.map { it.action },
        )
    }

    @Test
    fun healthyCustomDictionaryOwningTheSlotIsSkipped() {
        // The user's own import owns the slot; the recommended set must not clobber it.
        val plan = plan(dictionaries = listOf(dictionary(dictionarySlot, catalogResourceId = null)))

        assertEquals(
            RecommendedResourceAction.SKIP,
            plan.items.first { it.resource is YomitanCatalogResource }.action,
        )
    }

    @Test
    fun nullCatalogYieldsAPlanThatIsNeitherSatisfiedNorActionable() {
        val plan = recommendedResourcePlan(null, emptyList(), emptyList(), emptyList())

        assertTrue(plan.items.isEmpty())
        assertFalse(plan.isActionable)
        assertFalse(plan.isSatisfied)
    }

    @Test
    fun theManagerStateExposesTheSamePlan() {
        val state = ResourceManagerState(catalog = catalog)

        assertEquals(plan(), state.recommendedPlan)
    }

    @Test
    fun everyRecommendedMemberHasAName() {
        catalog.recommendedResources.forEach { resource ->
            assertTrue(recommendedResourceTitleRes(resource) != 0)
        }
    }

    private fun plan(
        dictionaries: List<InstalledDictionary> = emptyList(),
        frequencySources: List<InstalledFrequencySource> = emptyList(),
        pitchSources: List<InstalledPitchSource> = emptyList(),
    ) = recommendedResourcePlan(catalog, dictionaries, frequencySources, pitchSources)

    private fun dictionary(
        slotId: String,
        catalogResourceId: String? = "jmdict-en-2026-07-17",
        entryCount: Long = 1_000L,
    ) = InstalledDictionary(
        slotId = slotId,
        occupied = true,
        valid = true,
        sourceName = "Name of $slotId",
        sourceRevision = "1",
        format = "yomitan",
        entryCount = entryCount,
        schemaOk = true,
        embeddedAttribution = emptyMap(),
        catalogResourceId = catalogResourceId,
        attribution = emptyList(),
        rebuildSourcePath = null,
    )

    private fun frequency(
        sourceId: String,
        entryCount: Long = 100L,
        schemaOk: Boolean = true,
    ) = InstalledFrequencySource(
        sourceId = sourceId,
        sourceName = "Name of $sourceId",
        format = "yomitan-freq",
        entryCount = entryCount,
        schemaOk = schemaOk,
        schemaVersion = 3L,
        isCategorical = false,
        rebuildSourcePath = null,
    )

    private fun pitch(
        sourceId: String,
        entryCount: Long = 100L,
        schemaOk: Boolean = true,
    ) = InstalledPitchSource(
        sourceId = sourceId,
        sourceName = "Name of $sourceId",
        sourceRevision = "1",
        format = "csv",
        entryCount = entryCount,
        schemaOk = schemaOk,
        schemaVersion = 3L,
        rebuildSourcePath = null,
    )
}
