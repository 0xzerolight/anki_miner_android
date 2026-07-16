package com.ankiminer.android.data.resources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ResourceBridgeCodecTest {
    @Test
    fun frozenCatalogCarriesEveryRequiredIdentityAndAttribution() {
        val catalog = FrozenResourceCatalog.value

        assertEquals("unidic-lite-1.0.8", catalog.unidic.resourceId)
        assertEquals(260_467_176L, catalog.unidic.install.sizeBytes)
        assertEquals("jitendex-2026.07.09.0", catalog.recommendedDictionary.resourceId)
        assertEquals(540_565_403L, catalog.recommendedDictionary.dictionary.uncompressedBytes)
        assertEquals(
            setOf("Jitendex", "JMdict", "Tatoeba example sentences", "Kanji alive pronunciation audio", "JmdictFurigana"),
            catalog.recommendedDictionary.attribution.mapTo(mutableSetOf()) { it.name },
        )
    }

    @Test
    fun lookupResponsePreservesEngineHtmlByteForByte() {
        val html = "<div class=\"dict-entry\"><b>猫</b> &amp; cat</div>"
        val raw =
            """{"schemaVersion":1,"type":"resource.dictionary.lookup.result","payload":{"slotId":"jitendex","term":"猫","html":"<div class=\"dict-entry\"><b>猫</b> &amp; cat</div>"}}"""

        assertEquals(html, ResourceBridgeCodec.decodeLookup(raw).html)
    }

    @Test
    fun duplicateOrUnknownResponseFieldsFailClosed() {
        val duplicate =
            """{"schemaVersion":1,"type":"resource.cleanup.result","payload":{"clean":true,"clean":true}}"""
        assertThrows(ResourceBridgeException::class.java) {
            ResourceBridgeCodec.decodeCleanup(duplicate)
        }
        val unknown =
            """{"schemaVersion":1,"type":"resource.cleanup.result","payload":{"clean":true,"extra":false}}"""
        assertThrows(ResourceBridgeException::class.java) {
            ResourceBridgeCodec.decodeCleanup(unknown)
        }
    }

    @Test
    fun resourceRequestsContainOnlyStrictOperationFields() {
        val raw =
            ResourceBridgeCodec.encodeDictionaryImportRequest(
                operation = "resource_0123456789abcdef",
                sourcePath = "/private/staged.zip",
                selectedSlotId = "jitendex",
                overwrite = true,
                catalogResourceId = "jitendex-2026.07.09.0",
            )
        assertTrue(raw.contains("\"type\":\"resource.dictionary.import\""))
        assertTrue(raw.contains("\"overwrite\":true"))
        assertTrue(!raw.contains("content://"))
    }
}
