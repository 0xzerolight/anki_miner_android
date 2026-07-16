package com.ankiminer.android.anki.provider

import com.ankiminer.android.anki.generated.Html5EntitiesV312
import org.junit.Assert.assertEquals
import org.junit.Test

class DuplicateFirstFieldNormalizerTest {
    @Test
    fun `every pinned CPython HTML5 entity has its exact dedup result`() {
        val whitespaceEntities =
            setOf(
                "MediumSpace;",
                "NewLine;",
                "NonBreakingSpace;",
                "Tab;",
                "ThickSpace;",
                "ThinSpace;",
                "VeryThinSpace;",
                "emsp13;",
                "emsp14;",
                "emsp;",
                "ensp;",
                "hairsp;",
                "nbsp",
                "nbsp;",
                "numsp;",
                "puncsp;",
                "thinsp;",
            )
        assertEquals(2231, Html5EntitiesV312.ENTRY_COUNT)
        for (index in 0 until Html5EntitiesV312.ENTRY_COUNT) {
            val name = Html5EntitiesV312.nameAt(index)
            val expected = if (name in whitespaceEntities) "" else Html5EntitiesV312.valueAt(index)
            assertEquals(name, expected, DuplicateFirstFieldNormalizer.normalize("&$name"))
        }
    }

    @Test
    fun `numeric references follow CPython invalid reference and code point rules`() {
        val expectedC1 =
            listOf(
                "\u20AC", "\u0081", "\u201A", "\u0192", "\u201E", "\u2026", "\u2020", "\u2021",
                "\u02C6", "\u2030", "\u0160", "\u2039", "\u0152", "\u008D", "\u017D", "\u008F",
                "\u0090", "\u2018", "\u2019", "\u201C", "\u201D", "\u2022", "\u2013", "\u2014",
                "\u02DC", "\u2122", "\u0161", "\u203A", "\u0153", "\u009D", "\u017E", "\u0178",
            )
        expectedC1.forEachIndexed { index, expected ->
            val value = 0x80 + index
            assertEquals(expected, DuplicateFirstFieldNormalizer.normalize("&#$value;"))
            assertEquals(expected, DuplicateFirstFieldNormalizer.normalize("&#x${value.toString(16)}"))
        }

        val removed =
            (0x01..0x08).toList() +
                listOf(0x0B) +
                (0x0E..0x1F).toList() +
                listOf(0x7F) +
                (0xFDD0..0xFDEF).toList() +
                (0..16).flatMap { plane -> listOf((plane shl 16) + 0xFFFE, (plane shl 16) + 0xFFFF) }
        for (value in removed) {
            assertEquals("U+${value.toString(16)}", "ab", DuplicateFirstFieldNormalizer.normalize("a&#$value;b"))
        }

        assertEquals("\uFFFD", DuplicateFirstFieldNormalizer.normalize("&#0;"))
        assertEquals("\uFFFD", DuplicateFirstFieldNormalizer.normalize("&#xD800;"))
        assertEquals("\uFFFD", DuplicateFirstFieldNormalizer.normalize("&#x110000;"))
        assertEquals("\uFFFD", DuplicateFirstFieldNormalizer.normalize("&#999999999999999999999999999999;"))
        assertEquals("a b", DuplicateFirstFieldNormalizer.normalize("a&#13;b"))
    }

    @Test
    fun `named references use CPython longest match and malformed reference rules`() {
        val cases =
            mapOf(
                "&notit;" to "\u00ACit;",
                "&notin" to "\u00ACin",
                "&notin;" to "\u2209",
                "&amp=foo" to "&=foo",
                "&foo;" to "&foo;",
                "&&amp;" to "&&",
                "&amp;&lt;&gt;" to "&<>",
                "&CounterClockwiseContourIntegralX" to "&CounterClockwiseContourIntegralX",
                "&CounterClockwiseContourIntegral;X" to "\u2233X",
                "&Afr;" to "\uD835\uDD04",
                "&#x;" to "&#x;",
                "&#;" to "&#;",
            )
        for ((raw, expected) in cases) {
            assertEquals(raw, expected, DuplicateFirstFieldNormalizer.normalize(raw))
        }
    }

    @Test
    fun `media markup NFC whitespace furigana and astral scalars match desktop goldens`() {
        val whitespaceScalars =
            (0x09..0x0D).toList() +
                (0x1C..0x20).toList() +
                listOf(0x85, 0xA0, 0x1680) +
                (0x2000..0x200A).toList() +
                listOf(0x2028, 0x2029, 0x202F, 0x205F, 0x3000)
        val separated = buildString {
            append("a")
            whitespaceScalars.forEach { appendCodePoint(it) }
            append("b")
        }
        assertEquals("a b", DuplicateFirstFieldNormalizer.normalize(separated))
        assertEquals("a\u200Bb", DuplicateFirstFieldNormalizer.normalize("a\u200Bb"))
        assertEquals(
            "\u00E9 \uD83D\uDE3A \u98DF\u3079\u308B[\u305F\u3079\u308B]",
            DuplicateFirstFieldNormalizer.normalize(
                "<b> e\u0301 </b>[sound:clip.mp3] \uD83D\uDE3A " +
                    "\u98DF\u3079\u308B[\u305F\u3079\u308B]",
            ),
        )
        assertEquals("x]", DuplicateFirstFieldNormalizer.normalize("[AnKi:PlAy:q:thing]x]</bad>"))
        assertEquals("<>x<b", DuplicateFirstFieldNormalizer.normalize("<>x<b"))
    }
}
