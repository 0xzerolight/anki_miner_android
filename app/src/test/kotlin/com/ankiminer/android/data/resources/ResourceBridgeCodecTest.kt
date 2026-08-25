package com.ankiminer.android.data.resources

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResourceBridgeCodecTest {
    @get:Rule
    val temporary = TemporaryFolder()

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
    fun frozenCatalogPinsTheLocalResourcesAndTheRecommendedSet() {
        val catalog = FrozenResourceCatalog.value

        val frequency = catalog.frequencies.single()
        assertEquals("jpdb", frequency.sourceId)
        assertEquals(FrequencySourceFormat.YOMITAN_ZIP, frequency.sourceFormat)
        assertEquals(5_996_363L, frequency.archive.sizeBytes)

        val pitch = catalog.pitchSources.single()
        assertEquals("kanjium", pitch.sourceId)
        assertEquals(PitchAccentSourceFormat.TSV, pitch.sourceFormat)
        assertEquals(3_226_405L, pitch.archive.sizeBytes)
        assertEquals(
            setOf("Kanjium pitch accent data", "EDICT and KANJIDIC"),
            pitch.attribution.mapTo(mutableSetOf()) { it.name },
        )

        // JMdict, not Jitendex: the set matches the desktop recommendation.
        assertEquals(
            listOf("jmdict-en-2026-07-17", "jpdb-v2.2-kana-2024-10-13", "kanjium-pitch-8a0cdaa1"),
            catalog.recommended,
        )
        assertEquals(catalog.recommended, catalog.recommendedResources.map { it.resourceId })
    }

    @Test
    fun committedPythonCatalogJsonMatchesTheFrozenKotlinCatalog() {
        // decodeCatalog throws resource_catalog_mismatch on any divergence.
        assertEquals(FrozenResourceCatalog.value, ResourceBridgeCodec.decodeCatalog(committedCatalogEnvelope()))
    }

    @Test
    fun catalogRejectsALocalFormatOutsideTheImporterContract() {
        // "txt" is a legal frequency format and an illegal pitch one.
        val raw = committedCatalogEnvelope().replace(""""format": "tsv"""", """"format": "txt"""")

        val failure =
            assertThrows(ResourceBridgeException::class.java) {
                ResourceBridgeCodec.decodeCatalog(raw)
            }
        assertEquals("invalid_resource_response", failure.code)
    }

    @Test
    fun catalogRejectsARecommendedIdThatNamesNoResource() {
        val raw =
            committedCatalogEnvelope()
                .replace(""""recommended": [""", """"recommended": ["not-in-the-catalog",""")

        // The Kotlin mirror is the authority here: an id the frozen copy does not list makes the
        // decoded catalog differ, which is the mismatch the equality check exists to catch.
        val failure =
            assertThrows(ResourceBridgeException::class.java) {
                ResourceBridgeCodec.decodeCatalog(raw)
            }
        assertEquals("resource_catalog_mismatch", failure.code)
    }

    private fun committedCatalogEnvelope(): String {
        val payload =
            checkNotNull(javaClass.getResourceAsStream("/resource_catalog_v1.json")) {
                "resource_catalog_v1.json missing from the test classpath"
            }.bufferedReader().use { it.readText() }
        return """{"schemaVersion":1,"type":"resource.catalog","payload":${payload.trim()}}"""
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
            """{"schemaVersion":1,"type":"resource.dictionary.listed","payload":{"dictionaries":[{"slotId":"jitendex","occupied":true,"valid":false,"sourceName":"jitendex","sourceRevision":"","format":"unknown","entryCount":0,"schemaOk":false,"embeddedAttribution":{},"catalogResourceId":null,"attribution":[],"rebuildSourcePath":"/data/user/0/files/dicts/jitendex/source.zip"}]}}"""

        val installed = ResourceBridgeCodec.decodeDictionaryList(raw).single()

        assertTrue(installed.occupied)
        assertTrue(!installed.valid)
        assertTrue(!installed.isUsable)
        assertEquals("/data/user/0/files/dicts/jitendex/source.zip", installed.rebuildSourcePath)
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
            """{"schemaVersion":1,"type":"resource.dictionary.listed","payload":{"dictionaries":[{"slotId":"fixture","occupied":false,"valid":false,"sourceName":"fixture","sourceRevision":"","format":"unknown","entryCount":0,"schemaOk":false,"embeddedAttribution":{},"catalogResourceId":null,"attribution":[],"rebuildSourcePath":null}]}}"""
        assertThrows(ResourceBridgeException::class.java) {
            ResourceBridgeCodec.decodeDictionaryList(unoccupied)
        }

        val forgedAttribution =
            """{"schemaVersion":1,"type":"resource.dictionary.listed","payload":{"dictionaries":[{"slotId":"fixture","occupied":true,"valid":true,"sourceName":"Fixture","sourceRevision":"1","format":"yomitan","entryCount":1,"schemaOk":true,"embeddedAttribution":{},"catalogResourceId":null,"attribution":[{"name":"Fake","copyright":"Fake","license":"MIT","url":"https://example.com"}],"rebuildSourcePath":null}]}}"""
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
                rebuildSourcePath = null,
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
            """{"schemaVersion":1,"type":"resource.dictionary.listed","payload":{"dictionaries":[{"slotId":"jitendex","occupied":true,"valid":true,"sourceName":"Jitendex.org [2026-07-09]","sourceRevision":"2026.07.09.0","format":"yomitan","entryCount":1,"schemaOk":true,"embeddedAttribution":{},"catalogResourceId":"not-in-catalog","attribution":[],"rebuildSourcePath":null}]}}"""
        assertThrows(ResourceBridgeException::class.java) {
            ResourceBridgeCodec.decodeDictionaryList(unknownCatalogId)
        }
    }

    @Test
    fun pythonBridgeErrorKeepsItsCodeWhateverOptionalFieldsItCarries() {
        // Every resource.* operation shares boundary.py's generic exception arm, so a Python crash
        // on this lane arrives with a faultId. Rejecting it would substitute invalid_resource_response
        // for the real code, and ResourceManager.userMessage formats that code into a user-visible
        // string -- the one thing this feature must not change.
        fun bridgeError(payload: String) = """{"schemaVersion":1,"type":"bridge.error","payload":$payload}"""

        val withFault =
            assertThrows(ResourceBridgeException::class.java) {
                ResourceBridgeCodec.decodeCleanup(
                    bridgeError(
                        """{"code":"internal_error","message":"Internal bridge failure","requestType":"resource.cleanup","faultId":"f0123abcd"}""",
                    ),
                )
            }
        assertEquals("internal_error", withFault.code)
        assertEquals("Internal bridge failure", withFault.message)
        assertEquals("f0123abcd", withFault.faultId)

        val withoutRequestType =
            assertThrows(ResourceBridgeException::class.java) {
                ResourceBridgeCodec.decodeCleanup(
                    bridgeError("""{"code":"internal_error","message":"Internal bridge failure","faultId":"f0123abcd"}"""),
                )
            }
        assertEquals("internal_error", withoutRequestType.code)
        assertEquals("f0123abcd", withoutRequestType.faultId)

        val withoutFault =
            assertThrows(ResourceBridgeException::class.java) {
                ResourceBridgeCodec.decodeCleanup(
                    bridgeError("""{"code":"resource_already_installed","message":"Slot is occupied","requestType":"resource.cleanup"}"""),
                )
            }
        assertEquals("resource_already_installed", withoutFault.code)
        assertEquals(null, withoutFault.faultId)

        val malformedFault =
            assertThrows(ResourceBridgeException::class.java) {
                ResourceBridgeCodec.decodeCleanup(
                    bridgeError("""{"code":"internal_error","message":"Internal bridge failure","faultId":"f0123abc"}"""),
                )
            }
        assertEquals("invalid_resource_response", malformedFault.code)

        val unknownField =
            assertThrows(ResourceBridgeException::class.java) {
                ResourceBridgeCodec.decodeCleanup(
                    bridgeError("""{"code":"internal_error","message":"Internal bridge failure","retryable":false}"""),
                )
            }
        assertEquals("invalid_resource_response", unknownField.code)
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
        val dictionaryPreflight =
            ResourceBridgeCodec.encodeDictionaryPreflightRequest(
                operation = "resource_dictionary_preflight",
                sourcePath = "/private/dictionary.zip",
            )
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
                sourceId = "pitch-source",
                sourceName = "Pitch Source",
                sourceFormat = PitchAccentSourceFormat.YOMITAN_ZIP,
                overwrite = true,
            )
        val audio =
            ResourceBridgeCodec.encodeAudioPackImportRequest(
                operation = "resource_audio",
                sourcePath = "/private/audio.zip",
                packId = "nhk16",
                packPath = "user_files/nhk16_files",
                overwrite = false,
            )
        val audioPreflight =
            ResourceBridgeCodec.encodeAudioPackPreflightRequest(
                operation = "resource_audio_preflight",
                sourcePath = "/private/audio.zip",
                displayName = "NHK Audio.zip",
            )
        val known =
            ResourceBridgeCodec.encodeKnownWordsImportRequest(
                operation = "resource_known",
                sourcePath = "/private/known.json",
                sourceFormat = KnownWordsSourceFormat.JSON,
            )

        assertTrue(dictionaryPreflight.contains("\"type\":\"resource.dictionary.preflight\""))
        assertEquals(
            "fixture-dictionary-2026-08",
            ResourceBridgeCodec.decodeDictionaryPreflight(
                """{"schemaVersion":1,"type":"resource.dictionary.preflighted","payload":{"slotId":"fixture-dictionary-2026-08"}}""",
            ),
        )
        assertTrue(frequency.contains("\"sourceFormat\":\"tsv\""))
        assertTrue(pitch.contains("\"type\":\"resource.pitch.import\""))
        assertTrue(audio.contains("\"packId\":\"nhk16\""))
        assertTrue(audio.contains("\"packPath\":\"user_files/nhk16_files\""))
        assertTrue(audioPreflight.contains("\"displayName\":\"NHK Audio.zip\""))
        // The upstream collection is one archive of four packs, so the preflight
        // reports a list and the caller picks; the order the bridge sent survives.
        assertEquals(
            listOf(
                AudioPackCandidate("jpod", "user_files/jpod_files", "ajt"),
                AudioPackCandidate("nhk16", "user_files/nhk16_files", "nhk16"),
            ),
            ResourceBridgeCodec.decodeAudioPackPreflight(
                """{"schemaVersion":1,"type":"resource.audiopack.preflighted","payload":{"packs":[""" +
                    """{"packId":"jpod","packPath":"user_files/jpod_files","format":"ajt"},""" +
                    """{"packId":"nhk16","packPath":"user_files/nhk16_files","format":"nhk16"}]}}""",
            ),
        )
        // An archive whose root is the pack has no sub-path, which is a value here.
        assertEquals(
            "",
            ResourceBridgeCodec
                .decodeAudioPackPreflight(
                    """{"schemaVersion":1,"type":"resource.audiopack.preflighted","payload":{"packs":""" +
                        """[{"packId":"nhk16","packPath":"","format":"nhk16"}]}}""",
                ).single()
                .packPath,
        )
        for (rejected in
            listOf(
                """{"packs":[{"packId":"jpod101","packPath":"","format":"ajt"}]}""",
                """{"packs":[]}""",
                """{"packs":[{"packId":"jpod","packPath":"a","format":"ajt"},""" +
                    """{"packId":"jpod","packPath":"b","format":"ajt"}]}""",
            )
        ) {
            assertThrows(ResourceBridgeException::class.java) {
                ResourceBridgeCodec.decodeAudioPackPreflight(
                    """{"schemaVersion":1,"type":"resource.audiopack.preflighted","payload":$rejected}""",
                )
            }
        }
        // A sub-path that climbs out of the archive must not reach the wire.
        assertThrows(ResourceBridgeException::class.java) {
            ResourceBridgeCodec.encodeAudioPackImportRequest(
                operation = "resource_audio",
                sourcePath = "/private/audio.zip",
                packId = "nhk16",
                packPath = "user_files/../../etc",
                overwrite = false,
            )
        }
        assertTrue(known.contains("\"sourceFormat\":\"json\""))
        assertTrue(
            listOf(dictionaryPreflight, frequency, pitch, audio, audioPreflight, known)
                .none { it.contains("content://") },
        )
    }

    @Test
    fun importedDictionaryAcceptsDesktopRevisionlessYomitanMetadata() {
        val imported =
            ResourceBridgeCodec.decodeImportedDictionary(
                """{"schemaVersion":1,"type":"resource.dictionary.imported","payload":{"slotId":"revisionless","catalogResourceId":null,"sourceName":"Revisionless","sourceRevision":"","entryCount":1,"skippedMalformed":0,"mediaWarnings":[],"archiveSha256":"${"0".repeat(64)}","attribution":[]}}""",
            )

        assertEquals("", imported.sourceRevision)
    }

    @Test
    fun importedPitchAcceptsDesktopYomitanInstalledFormat() {
        val imported =
            ResourceBridgeCodec.decodeImportedPitch(
                """{"schemaVersion":1,"type":"resource.pitch.imported","payload":{"sourceId":"yomitan-pitch","sourceName":"Yomitan Pitch","sourceRevision":"1","sourceFormat":"yomitan-pitch","entryCount":1,"skippedDisplayOnly":0,"skippedMalformed":0,"archiveSha256":"${"0".repeat(64)}"}}""",
            )

        assertEquals("yomitan-pitch", imported.sourceFormat)
    }

    @Test
    fun frequencyArchiveRevisionIsBoundedBeforePythonCanPublishItsSlot() {
        ResourceBridgeCodec.validateFrequencyArchiveMetadata(
            frequencyArchive("a".repeat(4096)),
        )

        val failure =
            assertThrows(ResourceBridgeException::class.java) {
                ResourceBridgeCodec.validateFrequencyArchiveMetadata(
                    frequencyArchive("a".repeat(4097)),
                )
            }

        assertEquals("frequency_import_failed", failure.code)
    }

    @Test
    fun insufficientStorageBridgeErrorMapsToTypedStorageFailure() {
        val failure =
            assertThrows(ResourceStorageException::class.java) {
                ResourceBridgeCodec.decodeImportedDictionary(
                    """{"schemaVersion":1,"type":"bridge.error","payload":{"code":"insufficient_storage","message":"Not enough free space for this resource operation"}}""",
                )
            }

        assertEquals(null, failure.requiredBytes)
        assertEquals(null, failure.availableBytes)
    }

    @Test
    fun knownWordManagementRequestsAndResponsesAreStrictAndBounded() {
        val previewRequest =
            ResourceBridgeCodec.encodeKnownWordsPreviewRequest(
                operation = "known_preview",
                sourcePath = "/private/known.txt",
                sourceFormat = KnownWordsSourceFormat.TEXT,
            )
        val listRequest =
            ResourceBridgeCodec.encodeKnownWordsListRequest(
                operation = "known_list",
                query = "猫",
                offset = 0,
                limit = 100,
            )
        val removeRequest =
            ResourceBridgeCodec.encodeKnownWordsRemoveRequest(
                operation = "known_remove",
                words = listOf("猫", "犬"),
            )
        val resetRequest =
            ResourceBridgeCodec.encodeKnownWordsResetRequest(
                operation = "known_reset",
                scope = KnownWordsResetScope.USER,
            )

        assertTrue(previewRequest.contains("\"type\":\"resource.knownwords.preview\""))
        assertTrue(listRequest.contains("\"query\":\"猫\""))
        assertTrue(removeRequest.contains("\"words\":[\"猫\",\"犬\"]"))
        assertTrue(resetRequest.contains("\"scope\":\"user\""))

        val preview =
            ResourceBridgeCodec.decodeKnownWordsPreview(
                """{"schemaVersion":1,"type":"resource.knownwords.previewed","payload":{"format":"generic","importedCount":2,"totalEntries":3,"isGeneric":true,"sampleWords":["犬","猫"]}}""",
            )
        assertEquals(listOf("犬", "猫"), preview.sampleWords)

        val page =
            ResourceBridgeCodec.decodeKnownWordsPage(
                """{"schemaVersion":1,"type":"resource.knownwords.listed","payload":{"query":"猫","offset":0,"totalCount":1,"words":["猫"],"hasMore":false}}""",
            )
        assertEquals(listOf("猫"), page.words)
        assertTrue(!page.hasMore)

        assertEquals(
            2L,
            ResourceBridgeCodec.decodeKnownWordsRemoved(
                """{"schemaVersion":1,"type":"resource.knownwords.removed","payload":{"removedCount":2}}""",
            ),
        )
        assertEquals(
            KnownWordsResetResult(KnownWordsResetScope.CACHE, 4L),
            ResourceBridgeCodec.decodeKnownWordsReset(
                """{"schemaVersion":1,"type":"resource.knownwords.reset","payload":{"scope":"cache","removedCount":4}}""",
            ),
        )
        assertEquals(
            KnownWordsExport("/private/export.txt", 2L, 14L),
            ResourceBridgeCodec.decodeKnownWordsExport(
                """{"schemaVersion":1,"type":"resource.knownwords.exported","payload":{"exportPath":"/private/export.txt","exportedCount":2,"sizeBytes":14}}""",
            ),
        )
    }

    @Test
    fun minedWordsRemovalRequestValidatesAndRoundTripsTheRemovedCount() {
        val request =
            ResourceBridgeCodec.encodeMinedWordsRemoveRequest(
                operation = "mined_remove",
                words = listOf("猫", "犬"),
            )

        assertTrue(request.contains("\"type\":\"resource.minedwords.remove\""))
        assertTrue(request.contains("\"words\":[\"猫\",\"犬\"]"))

        assertThrows(IllegalArgumentException::class.java) {
            ResourceBridgeCodec.encodeMinedWordsRemoveRequest("mined_remove", emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ResourceBridgeCodec.encodeMinedWordsRemoveRequest("mined_remove", List(257) { "word$it" })
        }
        assertThrows(IllegalArgumentException::class.java) {
            ResourceBridgeCodec.encodeMinedWordsRemoveRequest("mined_remove", listOf("dup", "dup"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ResourceBridgeCodec.encodeMinedWordsRemoveRequest("mined_remove", listOf(""))
        }

        assertEquals(
            2L,
            ResourceBridgeCodec.decodeMinedWordsRemoved(
                """{"schemaVersion":1,"type":"resource.minedwords.removed","payload":{"removedCount":2}}""",
            ),
        )
        assertThrows(ResourceBridgeException::class.java) {
            ResourceBridgeCodec.decodeMinedWordsRemoved(
                """{"schemaVersion":1,"type":"resource.minedwords.removed","payload":{"removedCount":2,"extra":1}}""",
            )
        }
    }

    @Test
    fun localResourceInventoryDecodesEveryInstalledClass() {
        val raw =
            """{"schemaVersion":1,"type":"resource.local.listed","payload":{"frequencies":[{"sourceId":"jpdb","sourceName":"JPDB","format":"yomitan-freq","entryCount":100,"schemaOk":true,"schemaVersion":2,"isCategorical":false,"rebuildSourcePath":null}],"pitchSources":[{"sourceId":"nhk","sourceName":"NHK","sourceRevision":"1","format":"csv","entryCount":20,"schemaOk":true,"schemaVersion":1,"rebuildSourcePath":null}],"audioPacks":[{"packId":"nhk16","sourceName":"nhk16","format":"nhk16","entryCount":30,"contentAvailable":true}],"knownWords":{"totalCount":12,"userCount":2,"ankiCount":9,"minedCount":1,"schemaOk":true},"wordsets":[{"wordsetId":"surnames","displayName":"Surnames","entryCount":98406}]}}"""

        val inventory = ResourceBridgeCodec.decodeLocalResourceList(raw)

        assertEquals(listOf("jpdb"), inventory.frequencies.map { it.sourceId })
        assertEquals(listOf("NHK"), inventory.pitchSources.map { it.sourceName })
        assertEquals(listOf("nhk16"), inventory.audioPacks.map { it.packId })
        assertEquals(2L, inventory.knownWords.userCount)
        assertEquals(listOf("surnames"), inventory.wordsets.map { it.wordsetId })
    }

    @Test
    fun localResourceInventoryRejectsDuplicateIdsAndInconsistentCounts() {
        val duplicateFrequency =
            """{"schemaVersion":1,"type":"resource.local.listed","payload":{"frequencies":[{"sourceId":"same","sourceName":"One","format":"csv","entryCount":1,"schemaOk":true,"schemaVersion":2,"isCategorical":false,"rebuildSourcePath":null},{"sourceId":"same","sourceName":"Two","format":"csv","entryCount":1,"schemaOk":true,"schemaVersion":2,"isCategorical":false,"rebuildSourcePath":null}],"pitchSources":[],"audioPacks":[],"knownWords":{"totalCount":0,"userCount":0,"ankiCount":0,"minedCount":0,"schemaOk":true},"wordsets":[]}}"""
        assertThrows(ResourceBridgeException::class.java) {
            ResourceBridgeCodec.decodeLocalResourceList(duplicateFrequency)
        }

        val inconsistentKnownWords =
            """{"schemaVersion":1,"type":"resource.local.listed","payload":{"frequencies":[],"pitchSources":[],"audioPacks":[],"knownWords":{"totalCount":1,"userCount":1,"ankiCount":1,"minedCount":0,"schemaOk":true},"wordsets":[]}}"""
        assertThrows(ResourceBridgeException::class.java) {
            ResourceBridgeCodec.decodeLocalResourceList(inconsistentKnownWords)
        }
    }

    @Test
    fun dictionaryDeleteRequestCarriesTheSlot() {
        val encoded = ResourceBridgeCodec.encodeDictionaryDeleteRequest("resource-abc", "jmdict")

        assertTrue(encoded.contains(""""type":"resource.dictionary.delete""""))
        assertTrue(encoded.contains(""""slotId":"jmdict""""))
    }

    @Test
    fun localResourceDeleteRequestCarriesKindAndSlot() {
        val encoded =
            ResourceBridgeCodec.encodeLocalResourceDeleteRequest(
                "resource-abc",
                InstalledResourceKind.AUDIO_PACK,
                "jpod101",
            )

        assertTrue(encoded.contains(""""type":"resource.local.delete""""))
        assertTrue(encoded.contains(""""kind":"audio-pack""""))
        assertTrue(encoded.contains(""""slotId":"jpod101""""))
    }

    @Test
    fun localResourceDeleteRequestRejectsTheDictionaryKind() {
        assertThrows(IllegalArgumentException::class.java) {
            ResourceBridgeCodec.encodeLocalResourceDeleteRequest(
                "resource-abc",
                InstalledResourceKind.DICTIONARY,
                "jmdict",
            )
        }
    }

    @Test
    fun deleteResponseFromAnotherSlotIsRejected() {
        val raw =
            """{"schemaVersion":1,"type":"resource.dictionary.deleted","payload":{"slotId":"other","removed":true}}"""

        assertThrows(ResourceBridgeException::class.java) {
            ResourceBridgeCodec.decodeDictionaryDeleted(raw, "jmdict")
        }
    }

    @Test
    fun deleteResponseFromAnotherKindIsRejected() {
        val raw =
            """{"schemaVersion":1,"type":"resource.local.deleted","payload":{"kind":"frequency","slotId":"nhk","removed":true}}"""

        assertThrows(ResourceBridgeException::class.java) {
            ResourceBridgeCodec.decodeLocalResourceDeleted(raw, InstalledResourceKind.PITCH, "nhk")
        }
    }

    @Test
    fun deleteResponseWithAnExtraFieldIsRejected() {
        val raw =
            """{"schemaVersion":1,"type":"resource.local.deleted","payload":{"kind":"pitch","slotId":"nhk","removed":true,"extra":1}}"""

        assertThrows(ResourceBridgeException::class.java) {
            ResourceBridgeCodec.decodeLocalResourceDeleted(raw, InstalledResourceKind.PITCH, "nhk")
        }
    }

    @Test
    fun deleteResponsesReportWhetherAnythingWasRemoved() {
        val dictionary =
            """{"schemaVersion":1,"type":"resource.dictionary.deleted","payload":{"slotId":"jmdict","removed":false}}"""
        val local =
            """{"schemaVersion":1,"type":"resource.local.deleted","payload":{"kind":"pitch","slotId":"nhk","removed":true}}"""

        assertEquals(false, ResourceBridgeCodec.decodeDictionaryDeleted(dictionary, "jmdict"))
        assertEquals(
            true,
            ResourceBridgeCodec.decodeLocalResourceDeleted(local, InstalledResourceKind.PITCH, "nhk"),
        )
    }

    @Test
    fun resourceProgressDecodesImportingItemsEnvelope() {
        val raw =
            """{"schemaVersion":1,"type":"resource.progress","payload":{"operationId":"resource_1a2b3c4d","phase":"importing","kind":"items","current":3,"total":30}}"""

        val event = ResourceBridgeCodec.decodeResourceProgress(raw)

        assertEquals("resource_1a2b3c4d", event.operationId)
        assertEquals(ResourceOperationPhase.IMPORTING, event.phase)
        assertEquals(ResourceProgressUnit.ITEMS, event.unit)
        assertEquals(3L, event.current)
        assertEquals(30L, event.total)
    }

    @Test
    fun resourceProgressDecodesInstallingBytesEnvelope() {
        val raw =
            """{"schemaVersion":1,"type":"resource.progress","payload":{"operationId":"resource_1a2b3c4d","phase":"installing","kind":"bytes","current":1024,"total":2048}}"""

        val event = ResourceBridgeCodec.decodeResourceProgress(raw)

        assertEquals(ResourceOperationPhase.INSTALLING, event.phase)
        assertEquals(ResourceProgressUnit.BYTES, event.unit)
        assertEquals(1024L, event.current)
        assertEquals(2048L, event.total)
    }

    @Test
    fun resourceProgressDecodesFinalizingStageMarker() {
        val raw =
            """{"schemaVersion":1,"type":"resource.progress","payload":{"operationId":"resource_1a2b3c4d","phase":"finalizing","kind":"items","current":0,"total":0}}"""

        val event = ResourceBridgeCodec.decodeResourceProgress(raw)

        assertEquals(ResourceOperationPhase.FINALIZING, event.phase)
        assertEquals(0L, event.current)
        assertEquals(0L, event.total)
    }

    @Test
    fun resourceProgressRejectsMalformedEnvelopes() {
        fun progress(payload: String) = """{"schemaVersion":1,"type":"resource.progress","payload":$payload}"""

        val rejected =
            listOf(
                // unknown phase
                progress(
                    """{"operationId":"resource_1a2b3c4d","phase":"downloading","kind":"items","current":1,"total":2}""",
                ),
                // unknown kind
                progress(
                    """{"operationId":"resource_1a2b3c4d","phase":"importing","kind":"percent","current":1,"total":2}""",
                ),
                // missing field (total)
                progress("""{"operationId":"resource_1a2b3c4d","phase":"importing","kind":"items","current":1}"""),
                // negative current
                progress(
                    """{"operationId":"resource_1a2b3c4d","phase":"importing","kind":"items","current":-1,"total":2}""",
                ),
                // negative total
                progress(
                    """{"operationId":"resource_1a2b3c4d","phase":"importing","kind":"items","current":1,"total":-2}""",
                ),
                // unexpected extra field
                progress(
                    """{"operationId":"resource_1a2b3c4d","phase":"importing","kind":"items","current":1,"total":2,"message":"hi"}""",
                ),
            )
        for (raw in rejected) {
            assertThrows(ResourceBridgeException::class.java) {
                ResourceBridgeCodec.decodeResourceProgress(raw)
            }
        }

        val wrongType =
            """{"schemaVersion":1,"type":"resource.progresss","payload":{"operationId":"resource_1a2b3c4d","phase":"importing","kind":"items","current":1,"total":2}}"""
        assertThrows(ResourceBridgeException::class.java) {
            ResourceBridgeCodec.decodeResourceProgress(wrongType)
        }

        val wrongSchemaVersion =
            """{"schemaVersion":2,"type":"resource.progress","payload":{"operationId":"resource_1a2b3c4d","phase":"importing","kind":"items","current":1,"total":2}}"""
        assertThrows(ResourceBridgeException::class.java) {
            ResourceBridgeCodec.decodeResourceProgress(wrongSchemaVersion)
        }
    }

    private fun frequencyArchive(revision: String): File {
        val archive = File(temporary.root, "frequency-${revision.length}.zip")
        ZipOutputStream(archive.outputStream()).use { output ->
            output.putNextEntry(ZipEntry("index.json"))
            output.write(
                """{"title":"Fixture Frequency","revision":"$revision"}"""
                    .toByteArray(Charsets.UTF_8),
            )
            output.closeEntry()
        }
        return archive
    }
}
