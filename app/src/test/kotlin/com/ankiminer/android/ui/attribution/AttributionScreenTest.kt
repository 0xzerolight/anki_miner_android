package com.ankiminer.android.ui.attribution

import com.ankiminer.android.data.resources.FrozenResourceCatalog
import com.ankiminer.android.data.resources.InstalledDictionary
import com.ankiminer.android.data.resources.InstalledFrequencySource
import com.ankiminer.android.data.resources.InstalledPitchSource
import com.ankiminer.android.data.resources.ResourceAttribution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttributionScreenTest {
    @Test
    fun installedInventoryIsStableAndCatalogNoticesAreDeduplicated() {
        val notice = ResourceAttribution("Data", "Copyright", "CC", "https://example.com/data")
        val later = dictionary("zeta", notice)
        val first = dictionary("alpha", notice)

        val visible = attributionDictionaries(listOf(later, first, first))

        assertEquals(listOf("alpha", "zeta"), visible.map { it.slotId })
        assertEquals(listOf(notice), installedCatalogAttributions(visible))
    }

    @Test
    fun derivedTermsNoticeIsGatedOnJitendexOnly() {
        val jitendex = FrozenResourceCatalog.value.dictionary("jitendex-2026.07.09.0")!!
        val notice = ResourceAttribution("Data", "Copyright", "CC", "https://example.com/data")
        val installedJitendex =
            dictionary(jitendex.slotId, notice).copy(catalogResourceId = jitendex.resourceId)
        val installedJmdict =
            dictionary("jmdict", notice).copy(catalogResourceId = "jmdict-en-2026-07-17")

        assertTrue(hasInstalledJitendex(listOf(installedJitendex)))
        assertFalse(hasInstalledJitendex(listOf(installedJmdict)))
        assertFalse(hasInstalledJitendex(listOf(dictionary(jitendex.slotId, notice))))
        assertFalse(hasInstalledJitendex(emptyList()))
    }

    @Test
    fun onlyInstalledLocalCatalogDataContributesNotices() {
        val catalog = FrozenResourceCatalog.value
        val frequency = catalog.frequencies.single()
        val pitch = catalog.pitchSources.single()

        assertTrue(installedLocalCatalogAttributions(emptyList(), emptyList()).isEmpty())
        assertEquals(
            frequency.attribution,
            installedLocalCatalogAttributions(listOf(frequencySource(frequency.sourceId)), emptyList()),
        )
        assertEquals(
            frequency.attribution + pitch.attribution,
            installedLocalCatalogAttributions(
                listOf(frequencySource(frequency.sourceId)),
                listOf(pitchSource(pitch.sourceId)),
            ),
        )
        // A source id the catalog does not pin carries no notices of its own.
        assertTrue(
            installedLocalCatalogAttributions(listOf(frequencySource("hand-rolled")), emptyList())
                .isEmpty(),
        )
    }

    private fun frequencySource(sourceId: String) =
        InstalledFrequencySource(
            sourceId = sourceId,
            sourceName = sourceId,
            format = "yomitan-freq",
            entryCount = 1,
            schemaOk = true,
            schemaVersion = 3,
            isCategorical = false,
            rebuildSourcePath = null,
        )

    private fun pitchSource(sourceId: String) =
        InstalledPitchSource(
            sourceId = sourceId,
            sourceName = sourceId,
            sourceRevision = "1",
            format = "csv",
            entryCount = 1,
            schemaOk = true,
            schemaVersion = 3,
            rebuildSourcePath = null,
        )

    private fun dictionary(
        slotId: String,
        notice: ResourceAttribution,
    ) =
        InstalledDictionary(
            slotId = slotId,
            occupied = true,
            valid = true,
            sourceName = slotId,
            sourceRevision = "1",
            format = "yomitan",
            entryCount = 1,
            schemaOk = true,
            embeddedAttribution = emptyMap(),
            catalogResourceId = "catalog-$slotId",
            attribution = listOf(notice),
            rebuildSourcePath = null,
        )
}
