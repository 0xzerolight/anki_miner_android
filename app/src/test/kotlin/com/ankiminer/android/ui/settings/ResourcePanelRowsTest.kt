package com.ankiminer.android.ui.settings

import com.ankiminer.android.data.resources.InstalledAudioPack
import com.ankiminer.android.data.resources.InstalledDictionary
import com.ankiminer.android.data.resources.InstalledFrequencySource
import com.ankiminer.android.data.resources.InstalledPitchSource
import com.ankiminer.android.data.settings.ResourceChainSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResourcePanelRowsTest {
    @Test
    fun `dictionary rows follow the chain order, not the inventory order`() {
        val rows =
            dictionaryPanelRows(
                chain =
                    listOf(
                        ResourceChainSelection("custom1", enabled = false),
                        ResourceChainSelection("jitendex", enabled = true),
                    ),
                installed = listOf(dictionary("jitendex"), dictionary("custom1", catalogResourceId = null)),
                jishoEnabled = false,
                strings = dictionaryStrings,
                onChainChange = {},
                onJishoChange = {},
                onRepair = {},
                onReplace = {},
            )

        assertEquals(listOf("custom1", "jitendex", JISHO_ROW_ID), rows.map { it.id })
        assertFalse(rows[0].enabled)
        assertTrue(rows[1].enabled)
        assertEquals("Name of custom1", rows[0].title)
        assertTrue(rows[1].movable)
    }

    @Test
    fun `a chain entry with no installed slot warns and stays removable`() {
        val rows =
            dictionaryPanelRows(
                chain = listOf(ResourceChainSelection("ghost")),
                installed = emptyList(),
                jishoEnabled = false,
                strings = dictionaryStrings,
                onChainChange = {},
                onJishoChange = {},
                onRepair = {},
                onReplace = {},
            )

        val ghost = rows.single { it.id == "ghost" }
        assertEquals("ghost", ghost.title)
        assertEquals("missing", ghost.warning)
        assertTrue(ghost.removable)
        assertNull(ghost.quietAction)
    }

    @Test
    fun `a broken catalog dictionary offers Repair against its catalog resource`() {
        var repaired: String? = null
        val rows =
            dictionaryPanelRows(
                chain = emptyList(),
                installed =
                    listOf(dictionary("jitendex", catalogResourceId = "jitendex-2024", valid = false)),
                jishoEnabled = false,
                strings = dictionaryStrings,
                onChainChange = {},
                onJishoChange = {},
                onRepair = { repaired = it },
                onReplace = { throw AssertionError("catalog slot must not offer Replace") },
            )

        val broken = rows.single { it.id == "jitendex" }
        assertEquals("Repair", broken.quietAction?.label)
        assertEquals("repair", broken.warning)
        broken.quietAction?.onClick?.invoke()
        assertEquals("jitendex-2024", repaired)
    }

    @Test
    fun `a custom dictionary slot offers Replace against its own slot`() {
        var replaced: String? = null
        val rows =
            dictionaryPanelRows(
                chain = listOf(ResourceChainSelection("custom1")),
                installed = listOf(dictionary("custom1", catalogResourceId = null)),
                jishoEnabled = false,
                strings = dictionaryStrings,
                onChainChange = {},
                onJishoChange = {},
                onRepair = { throw AssertionError("a custom slot has no catalog resource") },
                onReplace = { replaced = it },
            )

        val custom = rows.single { it.id == "custom1" }
        assertEquals("Replace", custom.quietAction?.label)
        custom.quietAction?.onClick?.invoke()
        assertEquals("custom1", replaced)
    }

    @Test
    fun `a healthy catalog dictionary can still be replaced by an import`() {
        var replaced: String? = null
        val rows =
            dictionaryPanelRows(
                chain = listOf(ResourceChainSelection("jitendex")),
                installed = listOf(dictionary("jitendex")),
                jishoEnabled = false,
                strings = dictionaryStrings,
                onChainChange = {},
                onJishoChange = {},
                onRepair = { throw AssertionError("a healthy slot has nothing to repair") },
                onReplace = { replaced = it },
            )

        val healthy = rows.single { it.id == "jitendex" }
        assertEquals("Replace", healthy.quietAction?.label)
        healthy.quietAction?.onClick?.invoke()
        assertEquals("jitendex", replaced)
    }

    @Test
    fun `Jisho is pinned last and can only be toggled`() {
        var toggled: Boolean? = null
        val rows =
            dictionaryPanelRows(
                chain = listOf(ResourceChainSelection("jitendex")),
                installed = listOf(dictionary("jitendex")),
                jishoEnabled = true,
                strings = dictionaryStrings,
                onChainChange = {},
                onJishoChange = { toggled = it },
                onRepair = {},
                onReplace = {},
            )

        val jisho = rows.last()
        assertEquals(JISHO_ROW_ID, jisho.id)
        assertEquals("Jisho", jisho.title)
        assertEquals(listOf("Online"), jisho.metadata)
        assertEquals("rate-limited", jisho.warning)
        assertTrue(jisho.enabled)
        assertFalse(jisho.movable)
        assertFalse(jisho.removable)
        jisho.onToggle?.invoke(false)
        assertEquals(false, toggled)
    }

    @Test
    fun `an unchained slot is pinned below the chain with its checkbox disabled`() {
        val rows =
            pitchPanelRows(
                chain = listOf(ResourceChainSelection("nhk")),
                installed = listOf(pitch("nhk"), pitch("kanjium", entryCount = 0)),
                strings = rowStrings,
                onChainChange = {},
            )

        assertEquals(listOf("nhk", "kanjium"), rows.map { it.id })
        val unchained = rows.last()
        assertNull(unchained.onToggle)
        assertFalse(unchained.enabled)
        assertFalse(unchained.movable)
        assertTrue(unchained.removable)
        assertTrue("not in chain" in unchained.metadata)
        assertEquals("repair", unchained.warning)
    }

    @Test
    fun `a chained row reports its own id and entry count`() {
        val rows =
            audioPanelRows(
                chain = listOf(ResourceChainSelection("jpod")),
                installed = listOf(audioPack("jpod", entryCount = 4_000L)),
                strings = rowStrings,
                onChainChange = {},
            )

        val row = rows.single()
        assertEquals("Name of jpod", row.title)
        assertEquals(listOf("jpod", "4000 entries"), row.metadata)
        assertNotNull(row.onToggle)
    }

    @Test
    fun `frequency rows read the frequency inventory`() {
        val rows =
            frequencyPanelRows(
                chain = listOf(ResourceChainSelection("cc100", enabled = false)),
                installed = listOf(frequency("cc100")),
                strings = rowStrings,
                onChainChange = {},
            )

        assertEquals(listOf("cc100"), rows.map { it.id })
        assertFalse(rows.single().enabled)
        assertNull(rows.single().warning)
    }

    @Test
    fun `toggling a row rewrites only its own selection`() {
        var updated: List<ResourceChainSelection>? = null
        val chain =
            listOf(ResourceChainSelection("a"), ResourceChainSelection("b", enabled = false))
        val rows =
            frequencyPanelRows(
                chain = chain,
                installed = listOf(frequency("a"), frequency("b")),
                strings = rowStrings,
                onChainChange = { updated = it },
            )

        rows.single { it.id == "b" }.onToggle?.invoke(true)

        assertEquals(
            listOf(ResourceChainSelection("a"), ResourceChainSelection("b", enabled = true)),
            updated,
        )
    }

    @Test
    fun `moving a row swaps it with its neighbour and clamps at the ends`() {
        val chain =
            listOf(
                ResourceChainSelection("a"),
                ResourceChainSelection("b"),
                ResourceChainSelection("c"),
            )

        assertEquals(
            listOf("b", "a", "c"),
            chain.movedResource("b", -1).map { it.resourceId },
        )
        assertEquals(
            listOf("a", "c", "b"),
            chain.movedResource("b", 1).map { it.resourceId },
        )
        assertEquals(chain, chain.movedResource("a", -1))
        assertEquals(chain, chain.movedResource("c", 1))
        assertEquals(chain, chain.movedResource("absent", -1))
    }

    @Test
    fun `dropping a chain entry leaves the rest in order`() {
        val chain =
            listOf(ResourceChainSelection("a"), ResourceChainSelection("b"))

        assertEquals(listOf("a"), chain.withoutResource("b").map { it.resourceId })
        assertEquals(chain, chain.withoutResource("absent"))
    }

    private val rowStrings =
        ResourceRowStrings(
            entries = { count -> "$count entries" },
            notInChain = "not in chain",
            missingWarning = "missing",
            repairWarning = "repair",
        )

    private val dictionaryStrings =
        DictionaryRowStrings(
            rows = rowStrings,
            repairAction = "Repair",
            replaceAction = "Replace",
            jishoTitle = "Jisho",
            jishoMeta = "Online",
            jishoWarning = "rate-limited",
        )

    private fun dictionary(
        slotId: String,
        catalogResourceId: String? = slotId,
        valid: Boolean = true,
        entryCount: Long = 1_000L,
    ) = InstalledDictionary(
        slotId = slotId,
        occupied = true,
        valid = valid,
        sourceName = "Name of $slotId",
        sourceRevision = "1",
        format = "yomitan",
        entryCount = entryCount,
        schemaOk = true,
        embeddedAttribution = emptyMap(),
        catalogResourceId = catalogResourceId,
        attribution = emptyList(),
    )

    private fun pitch(
        sourceId: String,
        entryCount: Long = 100L,
    ) = InstalledPitchSource(
        sourceId = sourceId,
        sourceName = "Name of $sourceId",
        sourceRevision = "1",
        format = "json",
        entryCount = entryCount,
        schemaOk = true,
        schemaVersion = 1L,
    )

    private fun audioPack(
        packId: String,
        entryCount: Long = 100L,
    ) = InstalledAudioPack(
        packId = packId,
        sourceName = "Name of $packId",
        format = "opus",
        entryCount = entryCount,
        contentAvailable = true,
    )

    private fun frequency(
        sourceId: String,
        entryCount: Long = 100L,
    ) = InstalledFrequencySource(
        sourceId = sourceId,
        sourceName = "Name of $sourceId",
        format = "json",
        entryCount = entryCount,
        schemaOk = true,
        schemaVersion = 1L,
        isCategorical = false,
    )
}
