package com.ankiminer.android.data.resources

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResourceIdentityTest {
    /** Names chosen to break the alphabet, the separator collapsing, and the empty-slug fallback. */
    private val hostileNames =
        listOf(
            "JPDB v2.1",
            "Imported frequency",
            "Café Frequency",
            "２０２４年版",
            "日本語頻度リスト",
            "---",
            "   ",
            "🔥🔥",
            "..",
            "._-",
            "a..b",
            "a--b",
            "-leading",
            "trailing-",
            "UPPER CASE",
            "tab\there",
            "new\nline",
            "sla/sh",
            "back\\slash",
            "quote\"quote",
            "sem;colon",
            "e".repeat(200),
            "ß",
            "Ｆｕｌｌｗｉｄｔｈ",
            "0",
        )

    @Test
    fun asciiNamesBecomeHyphenSlugs() {
        assertEquals("jpdb-v2-1", ResourceIdentity.derive("JPDB v2.1", "frequency"))
        assertEquals("imported-frequency", ResourceIdentity.derive("Imported frequency", "frequency"))
        assertEquals("cafe-frequency", ResourceIdentity.derive("Café Frequency", "frequency"))
        assertEquals("2024", ResourceIdentity.derive("２０２４年版", "frequency"))
        assertEquals("upper-case", ResourceIdentity.derive("UPPER CASE", "frequency"))
        assertEquals("a-b", ResourceIdentity.derive("a--b", "frequency"))
        assertEquals("leading", ResourceIdentity.derive("-leading", "frequency"))
        assertEquals("trailing", ResourceIdentity.derive("trailing-", "frequency"))
        assertEquals("0", ResourceIdentity.derive("0", "frequency"))
    }

    @Test
    fun namesWithoutAlphanumericsFallBackToAPrefixedDigest() {
        listOf("日本語頻度リスト", "---", "   ", "🔥🔥", "..", "._-").forEach { name ->
            val id = ResourceIdentity.derive(name, "frequency")
            assertTrue("$name -> $id", Regex("^frequency-[0-9a-f]{10}$").matches(id))
        }
    }

    @Test
    fun distinctNonAsciiNamesGetDistinctIds() {
        val ids =
            listOf("日本語頻度リスト", "漢字リスト", "ひらがな", "カタカナ")
                .map { ResourceIdentity.derive(it, "frequency") }
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun canonicallyEquivalentNonAsciiNamesGetOneFallbackId() {
        assertEquals(
            ResourceIdentity.derive("\u3070", "frequency"),
            ResourceIdentity.derive("\u306F\u3099", "frequency"),
        )
    }

    @Test
    fun longNamesAreTruncatedAndStillEndAlphanumeric() {
        val id = ResourceIdentity.derive("word ".repeat(60), "frequency")
        assertTrue(id.length <= 40)
        assertTrue(id.last().isLetterOrDigit())
    }

    @Test
    fun derivationIsPureAndRepeatable() {
        hostileNames.forEach { name ->
            assertEquals(
                ResourceIdentity.derive(name, "frequency"),
                ResourceIdentity.derive(name, "frequency"),
            )
        }
    }

    @Test
    fun derivationIsIndependentOfTheDefaultLocale() {
        val expected = hostileNames.map { ResourceIdentity.derive(it, "frequency") }
        val original = Locale.getDefault()
        try {
            // Turkish dotless-i and Arabic-Indic digits are the two classic locale traps for
            // lowercase() and hex formatting respectively.
            listOf("tr-TR", "ar-EG").forEach { tag ->
                Locale.setDefault(Locale.forLanguageTag(tag))
                assertEquals(tag, expected, hostileNames.map { ResourceIdentity.derive(it, "frequency") })
            }
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun derivedIdsNeverContainDoubleSeparators() {
        hostileNames.forEach { name ->
            val id = ResourceIdentity.derive(name, "frequency")
            assertFalse(id, id.contains("--"))
            assertFalse(id, id.contains(".."))
        }
    }

    /**
     * The real contract is the bridge codec's validation, not a copy of its regex, so feed every
     * derived id through the encoder. If the codec ever tightens, this fails instead of the app.
     */
    @Test
    fun everyDerivedIdIsAcceptedByTheBridgeCodec() {
        hostileNames.forEach { name ->
            val id = ResourceIdentity.derive(name, "frequency")
            ResourceBridgeCodec.encodeFrequencyImportRequest(
                operation = "00000000-0000-4000-8000-000000000000",
                sourcePath = "/data/user/0/com.ankiminer.android/cache/import.zip",
                sourceId = id,
                sourceName = "Imported frequency",
                sourceFormat = FrequencySourceFormat.YOMITAN_ZIP,
                overwrite = false,
            )
        }
    }

    @Test
    fun frequencyTargetReportsNoCollisionForAFreshName() {
        val target = ResourceIdentity.frequencyTarget("JPDB v2.1", emptyList())

        assertEquals("jpdb-v2-1", target.identity)
        assertNull(target.installedName)
        assertFalse(target.collides)
    }

    @Test
    fun frequencyTargetMatchesAnInstalledSourceById() {
        val installed = listOf(frequencySource(sourceId = "jpdb-v2-1", sourceName = "Something else"))

        val target = ResourceIdentity.frequencyTarget("JPDB v2.1", installed)

        assertEquals("jpdb-v2-1", target.identity)
        assertEquals("Something else", target.installedName)
    }

    /**
     * The migration case: a source installed before ids were derived lives under "frequency", which
     * the current derivation would never produce. Matching on name keeps the replace in place so
     * the chain entry survives.
     */
    @Test
    fun frequencyTargetMatchesAnOlderInstallByNameAndKeepsItsExistingId() {
        val installed = listOf(frequencySource(sourceId = "frequency", sourceName = "Imported frequency"))

        val target = ResourceIdentity.frequencyTarget("Imported frequency", installed)

        assertEquals("frequency", target.identity)
        assertEquals("Imported frequency", target.installedName)
    }

    @Test
    fun frequencyTargetNameMatchIgnoresCaseAndSurroundingSpace() {
        val installed = listOf(frequencySource(sourceId = "frequency", sourceName = "Imported Frequency"))

        val target = ResourceIdentity.frequencyTarget("  imported frequency  ", installed)

        assertEquals("frequency", target.identity)
    }

    @Test
    fun frequencyTargetMatchesCanonicalEquivalentLegacyNameAndKeepsItsId() {
        val installed = listOf(frequencySource(sourceId = "legacy-frequency", sourceName = "\u3070"))

        val target = ResourceIdentity.frequencyTarget("\u306F\u3099", installed)

        assertEquals("legacy-frequency", target.identity)
        assertEquals("\u3070", target.installedName)
    }

    @Test
    fun frequencyTargetPrefersAnIdMatchOverANameMatch() {
        val installed =
            listOf(
                frequencySource(sourceId = "other", sourceName = "JPDB v2.1"),
                frequencySource(sourceId = "jpdb-v2-1", sourceName = "Unrelated"),
            )

        val target = ResourceIdentity.frequencyTarget("JPDB v2.1", installed)

        assertEquals("jpdb-v2-1", target.identity)
        assertEquals("Unrelated", target.installedName)
    }

    @Test
    fun customDictionaryTargetMatchesAnOccupiedSlotEvenWhenItIsUnusable() {
        val installed =
            listOf(
                InstalledDictionary(
                    slotId = "jmdict",
                    occupied = true,
                    valid = false,
                    sourceName = "JMdict",
                    sourceRevision = "2026-07-17",
                    format = "yomitan",
                    entryCount = 0,
                    schemaOk = false,
                    embeddedAttribution = emptyMap(),
                    catalogResourceId = null,
                    attribution = emptyList(),
                ),
            )

        val target = ResourceIdentity.customDictionaryTarget("jmdict", installed)

        assertEquals("jmdict", target.identity)
        assertEquals("JMdict", target.installedName)
        assertTrue(target.collides)
    }

    @Test
    fun customDictionaryTargetIgnoresAnUnoccupiedSlot() {
        val installed =
            listOf(
                InstalledDictionary(
                    slotId = "jmdict",
                    occupied = false,
                    valid = false,
                    sourceName = "JMdict",
                    sourceRevision = "",
                    format = "yomitan",
                    entryCount = 0,
                    schemaOk = false,
                    embeddedAttribution = emptyMap(),
                    catalogResourceId = null,
                    attribution = emptyList(),
                ),
            )

        assertFalse(ResourceIdentity.customDictionaryTarget("jmdict", installed).collides)
    }

    @Test
    fun audioPackTargetKeepsTheDerivedIdAndReportsACollision() {
        val installed = listOf(InstalledAudioPack("nhk", "nhk", "zip", 100, true))

        assertTrue(ResourceIdentity.audioPackTarget("nhk", installed).collides)
        assertFalse(ResourceIdentity.audioPackTarget("forvo", installed).collides)
        assertEquals("forvo", ResourceIdentity.audioPackTarget("forvo", installed).identity)
    }

    @Test
    fun pitchTargetDerivesAnIdAndCollidesOnlyWithAMatchingSlot() {
        assertFalse(ResourceIdentity.pitchTarget("Kanjium", emptyList()).collides)
        assertEquals("kanjium", ResourceIdentity.pitchTarget("Kanjium", emptyList()).identity)

        val installed = listOf(pitchSource("kanjium", "Kanjium"))

        val matched = ResourceIdentity.pitchTarget("Kanjium", installed)
        assertEquals("kanjium", matched.identity)
        assertEquals("Kanjium", matched.installedName)
        assertTrue(matched.collides)
        // A different source is a separate slot now, not a collision with the
        // single installed pitch file it used to be.
        assertFalse(ResourceIdentity.pitchTarget("NHK 2016", installed).collides)
    }

    @Test
    fun pitchTargetMatchesCanonicalEquivalentLegacyNameAndKeepsItsId() {
        val installed = listOf(pitchSource("legacy-pitch", "\u3070"))

        val target = ResourceIdentity.pitchTarget("\u306F\u3099", installed)

        assertEquals("legacy-pitch", target.identity)
        assertEquals("\u3070", target.installedName)
    }

    private fun pitchSource(
        sourceId: String,
        sourceName: String,
    ) = InstalledPitchSource(
        sourceId = sourceId,
        sourceName = sourceName,
        sourceRevision = "2026-07-17",
        format = "csv",
        entryCount = 1000,
        schemaOk = true,
        schemaVersion = 1,
    )

    private fun frequencySource(
        sourceId: String,
        sourceName: String,
    ) = InstalledFrequencySource(
        sourceId = sourceId,
        sourceName = sourceName,
        format = "zip",
        entryCount = 100,
        schemaOk = true,
        schemaVersion = 1,
        isCategorical = false,
    )
}
