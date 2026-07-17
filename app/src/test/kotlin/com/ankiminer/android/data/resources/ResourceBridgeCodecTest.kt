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
        assertEquals(
            listOf("jitendex-2026.07.09.0", "jmdict-en-2026-07-17"),
            catalog.dictionaries.map { it.resourceId },
        )

        val jitendex = catalog.dictionary("jitendex-2026.07.09.0")!!
        assertEquals("jitendex", jitendex.slotId)
        assertEquals(540_565_403L, jitendex.dictionary.uncompressedBytes)
        assertEquals(
            setOf("Jitendex", "JMdict", "Tatoeba example sentences", "Kanji alive pronunciation audio", "JmdictFurigana"),
            jitendex.attribution.mapTo(mutableSetOf()) { it.name },
        )

        val jmdict = catalog.dictionary("jmdict-en-2026-07-17")!!
        assertEquals("jmdict", jmdict.slotId)
        assertEquals(170_311_400L, jmdict.dictionary.uncompressedBytes)
        assertEquals(
            setOf("JMdict", "jmdict-yomitan"),
            jmdict.attribution.mapTo(mutableSetOf()) { it.name },
        )

        assertEquals(null, catalog.dictionary("not-in-catalog"))
    }

    @Test
    fun committedPythonCatalogJsonMatchesTheFrozenKotlinCatalog() {
        val payload =
            checkNotNull(javaClass.getResourceAsStream("/resource_catalog_v1.json")) {
                "resource_catalog_v1.json missing from the test classpath"
            }.bufferedReader().use { it.readText() }
        val raw =
            """{"schemaVersion":1,"type":"resource.catalog","payload":${payload.trim()}}"""

        // decodeCatalog throws resource_catalog_mismatch on any divergence.
        assertEquals(FrozenResourceCatalog.value, ResourceBridgeCodec.decodeCatalog(raw))
    }

    @Test
    fun lookupResponsePreservesEngineHtmlByteForByte() {
        val html = "<div class=\"dict-entry\"><b>猫</b> &amp; cat</div>"
        val raw =
            """{"schemaVersion":1,"type":"resource.dictionary.lookup.result","payload":{"slotId":"jitendex","term":"猫","html":"<div class=\"dict-entry\"><b>猫</b> &amp; cat</div>"}}"""

        assertEquals(html, ResourceBridgeCodec.decodeLookup(raw).html)
    }

    @Test
    fun dictionaryInventoryPreservesInvalidOccupiedSlots() {
        val raw =
            """{"schemaVersion":1,"type":"resource.dictionary.listed","payload":{"dictionaries":[{"slotId":"jitendex","occupied":true,"valid":false,"sourceName":"jitendex","sourceRevision":"","format":"unknown","entryCount":0,"schemaOk":false,"embeddedAttribution":{},"catalogResourceId":null,"attribution":[]}]}}"""

        val installed = ResourceBridgeCodec.decodeDictionaryList(raw).single()

        assertTrue(installed.occupied)
        assertTrue(!installed.valid)
        assertTrue(!installed.isUsable)
        val state =
            ResourceManagerState(
                catalog = FrozenResourceCatalog.value,
                dictionaries = listOf(installed),
            )
        val jitendexStatus = state.catalogDictionaries.single { it.resource.slotId == "jitendex" }
        assertTrue(jitendexStatus.slotOccupied)
        assertTrue(jitendexStatus.needsRepair)
        assertTrue(!jitendexStatus.installed)
        val jmdictStatus = state.catalogDictionaries.single { it.resource.slotId == "jmdict" }
        assertTrue(!jmdictStatus.slotOccupied)
        assertTrue(!jmdictStatus.installed)
    }

    @Test
    fun dictionaryInventoryRejectsInconsistentFlagsAndForgedAttribution() {
        val unoccupied =
            """{"schemaVersion":1,"type":"resource.dictionary.listed","payload":{"dictionaries":[{"slotId":"fixture","occupied":false,"valid":false,"sourceName":"fixture","sourceRevision":"","format":"unknown","entryCount":0,"schemaOk":false,"embeddedAttribution":{},"catalogResourceId":null,"attribution":[]}]}}"""
        assertThrows(ResourceBridgeException::class.java) {
            ResourceBridgeCodec.decodeDictionaryList(unoccupied)
        }

        val forgedAttribution =
            """{"schemaVersion":1,"type":"resource.dictionary.listed","payload":{"dictionaries":[{"slotId":"fixture","occupied":true,"valid":true,"sourceName":"Fixture","sourceRevision":"1","format":"yomitan","entryCount":1,"schemaOk":true,"embeddedAttribution":{},"catalogResourceId":null,"attribution":[{"name":"Fake","copyright":"Fake","license":"MIT","url":"https://example.com"}]}]}}"""
        assertThrows(ResourceBridgeException::class.java) {
            ResourceBridgeCodec.decodeDictionaryList(forgedAttribution)
        }
    }

    @Test
    fun catalogDictionaryStatusRequiresUsableCatalogIdentity() {
        val expected = FrozenResourceCatalog.value.dictionary("jitendex-2026.07.09.0")!!
        val customInCatalogSlot =
            InstalledDictionary(
                slotId = expected.slotId,
                occupied = true,
                valid = true,
                sourceName = "Custom dictionary",
                sourceRevision = "1",
                format = "yomitan",
                entryCount = 1,
                schemaOk = true,
                embeddedAttribution = emptyMap(),
                catalogResourceId = null,
                attribution = emptyList(),
            )
        val customState =
            ResourceManagerState(
                catalog = FrozenResourceCatalog.value,
                dictionaries = listOf(customInCatalogSlot),
            )
        val customStatus = customState.catalogDictionaries.single { it.resource.slotId == "jitendex" }
        assertTrue(customStatus.slotOccupied)
        assertTrue(customStatus.needsRepair)
        assertTrue(!customStatus.installed)

        val installedCatalog =
            customInCatalogSlot.copy(
                sourceName = expected.dictionary.title,
                sourceRevision = expected.dictionary.revision,
                catalogResourceId = expected.resourceId,
                attribution = expected.attribution,
            )
        val installedState = customState.copy(dictionaries = listOf(installedCatalog))
        val installedStatus =
            installedState.catalogDictionaries.single { it.resource.slotId == "jitendex" }
        assertTrue(installedStatus.installed)
        assertTrue(!installedStatus.needsRepair)
    }

    @Test
    fun installedDictionaryWithUnknownCatalogIdentityIsRejected() {
        val unknownCatalogId =
            """{"schemaVersion":1,"type":"resource.dictionary.listed","payload":{"dictionaries":[{"slotId":"jitendex","occupied":true,"valid":true,"sourceName":"Jitendex.org [2026-07-09]","sourceRevision":"2026.07.09.0","format":"yomitan","entryCount":1,"schemaOk":true,"embeddedAttribution":{},"catalogResourceId":"not-in-catalog","attribution":[]}]}}"""
        assertThrows(ResourceBridgeException::class.java) {
            ResourceBridgeCodec.decodeDictionaryList(unknownCatalogId)
        }
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

    @Test
    fun localResourceRequestsPreserveTypedFormatsAndPrivatePaths() {
        val frequency =
            ResourceBridgeCodec.encodeFrequencyImportRequest(
                operation = "resource_frequency",
                sourcePath = "/private/frequency.tsv",
                sourceId = "jpdb-frequency",
                sourceName = "JPDB Frequency",
                sourceFormat = FrequencySourceFormat.TSV,
                overwrite = false,
            )
        val pitch =
            ResourceBridgeCodec.encodePitchImportRequest(
                operation = "resource_pitch",
                sourcePath = "/private/pitch.zip",
                sourceName = "Pitch Source",
                sourceFormat = PitchAccentSourceFormat.YOMITAN_ZIP,
                overwrite = true,
            )
        val audio =
            ResourceBridgeCodec.encodeAudioPackImportRequest(
                operation = "resource_audio",
                sourcePath = "/private/audio.zip",
                packId = "nhk16",
                overwrite = false,
            )
        val known =
            ResourceBridgeCodec.encodeKnownWordsImportRequest(
                operation = "resource_known",
                sourcePath = "/private/known.json",
                sourceFormat = KnownWordsSourceFormat.JSON,
            )

        assertTrue(frequency.contains("\"sourceFormat\":\"tsv\""))
        assertTrue(pitch.contains("\"type\":\"resource.pitch.import\""))
        assertTrue(audio.contains("\"packId\":\"nhk16\""))
        assertTrue(known.contains("\"sourceFormat\":\"json\""))
        assertTrue(listOf(frequency, pitch, audio, known).none { it.contains("content://") })
    }

    @Test
    fun localResourceInventoryDecodesEveryInstalledClass() {
        val raw =
            """{"schemaVersion":1,"type":"resource.local.listed","payload":{"frequencies":[{"sourceId":"jpdb","sourceName":"JPDB","format":"yomitan-freq","entryCount":100,"schemaOk":true,"schemaVersion":2,"isCategorical":false}],"pitchAccent":{"sourceName":"NHK","sourceRevision":"1","sourceFormat":"zip","entryCount":20,"fileSizeBytes":300,"schemaOk":true},"audioPacks":[{"packId":"nhk16","sourceName":"nhk16","format":"nhk16","entryCount":30,"contentAvailable":true}],"knownWords":{"totalCount":12,"userCount":2,"ankiCount":9,"minedCount":1,"schemaOk":true},"wordsets":[{"wordsetId":"surnames","displayName":"Surnames","entryCount":98406}]}}"""

        val inventory = ResourceBridgeCodec.decodeLocalResourceList(raw)

        assertEquals(listOf("jpdb"), inventory.frequencies.map { it.sourceId })
        assertEquals("NHK", inventory.pitchAccent?.sourceName)
        assertEquals(listOf("nhk16"), inventory.audioPacks.map { it.packId })
        assertEquals(2L, inventory.knownWords.userCount)
        assertEquals(listOf("surnames"), inventory.wordsets.map { it.wordsetId })
    }

    @Test
    fun localResourceInventoryRejectsDuplicateIdsAndInconsistentCounts() {
        val duplicateFrequency =
            """{"schemaVersion":1,"type":"resource.local.listed","payload":{"frequencies":[{"sourceId":"same","sourceName":"One","format":"csv","entryCount":1,"schemaOk":true,"schemaVersion":2,"isCategorical":false},{"sourceId":"same","sourceName":"Two","format":"csv","entryCount":1,"schemaOk":true,"schemaVersion":2,"isCategorical":false}],"pitchAccent":null,"audioPacks":[],"knownWords":{"totalCount":0,"userCount":0,"ankiCount":0,"minedCount":0,"schemaOk":true},"wordsets":[]}}"""
        assertThrows(ResourceBridgeException::class.java) {
            ResourceBridgeCodec.decodeLocalResourceList(duplicateFrequency)
        }

        val inconsistentKnownWords =
            """{"schemaVersion":1,"type":"resource.local.listed","payload":{"frequencies":[],"pitchAccent":null,"audioPacks":[],"knownWords":{"totalCount":1,"userCount":1,"ankiCount":1,"minedCount":0,"schemaOk":true},"wordsets":[]}}"""
        assertThrows(ResourceBridgeException::class.java) {
            ResourceBridgeCodec.decodeLocalResourceList(inconsistentKnownWords)
        }
    }
}
