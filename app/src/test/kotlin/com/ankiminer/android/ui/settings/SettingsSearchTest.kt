package com.ankiminer.android.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal class SettingsSearchTest {
    private fun entry(
        id: String,
        title: String,
        detail: String = "",
        category: SettingsCategory = SettingsCategory.MEDIA,
    ) = ResolvedSettingsEntry(
        id = id,
        category = category,
        cardKey = "media-options",
        title = title,
        breadcrumb = "Media",
        haystack =
            listOf(
                normalizeSettingsText(title),
                normalizeSettingsText(detail),
                normalizeSettingsText("Media"),
            ).filter(String::isNotEmpty),
    )

    @Test
    fun `a blank query matches nothing`() {
        assertTrue(searchSettings(listOf(entry("a", "Theme")), "   ").isEmpty())
    }

    @Test
    fun `every token must match, so extra words narrow`() {
        val entries =
            listOf(
                entry("length", "Clip length"),
                entry("quality", "Clip quality"),
            )

        assertEquals(listOf("quality"), searchSettings(entries, "clip quality").map { it.id })
    }

    @Test
    fun `a title match outranks a detail-only match`() {
        val entries =
            listOf(
                entry(
                    id = "detail",
                    title = "Audio format",
                    detail = "Bitrate applies to the sentence audio",
                ),
                entry("title", "Audio bitrate (kbps)"),
            )

        assertEquals(listOf("title", "detail"), searchSettings(entries, "bitrate").map { it.id })
    }

    @Test
    fun `an exact title beats a prefix, which beats a substring`() {
        val entries =
            listOf(
                entry("sub", "Maximum theme size"),
                entry("prefix", "Theme mode"),
                entry("exact", "Theme"),
            )

        assertEquals(
            listOf("exact", "prefix", "sub"),
            searchSettings(entries, "theme").map { it.id },
        )
    }

    @Test
    fun `full-width Latin and case fold onto the same query`() {
        assertEquals(
            listOf("opus"),
            searchSettings(listOf(entry("opus", "Opus")), "ＯＰＵＳ").map { it.id },
        )
    }

    @Test
    fun `Japanese labels are searchable`() {
        assertEquals(
            listOf("subtitle"),
            searchSettings(listOf(entry("subtitle", "字幕タイミングのずれ")), "字幕").map { it.id },
        )
    }

    @Test
    fun `an ideographic space separates tokens`() {
        assertEquals(
            listOf("quality"),
            searchSettings(listOf(entry("quality", "Clip quality")), "clip　quality").map { it.id },
        )
    }

    @Test
    fun `the category name reaches its own settings`() {
        assertEquals(
            listOf("quality"),
            searchSettings(listOf(entry("quality", "Clip quality")), "media").map { it.id },
        )
    }

    @Test
    fun `ties keep the order the entries were given in`() {
        val entries =
            listOf(
                entry("second", "Current theme choice"),
                entry("first", "Maximum theme size"),
            )

        assertEquals(entries.map { it.id }, searchSettings(entries, "theme").map { it.id })
    }
}
