package com.ankiminer.android.ui.attribution

import com.ankiminer.android.data.resources.FrozenResourceCatalog
import com.ankiminer.android.data.resources.InstalledDictionary
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
        )
}
