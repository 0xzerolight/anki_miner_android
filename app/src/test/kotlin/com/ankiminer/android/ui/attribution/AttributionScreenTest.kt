package com.ankiminer.android.ui.attribution

import com.ankiminer.android.data.resources.InstalledDictionary
import com.ankiminer.android.data.resources.ResourceAttribution
import org.junit.Assert.assertEquals
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
