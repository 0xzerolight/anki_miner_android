package com.ankiminer.android.mining

import com.ankiminer.android.anki.generated.UnicodeContractV151
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The label sanitizer must strip exactly what the bridge decoder refuses, and the decoder validates
 * with the pinned Unicode 15.1 tables. The JVM running these tests has older tables than 15.1 and an
 * API 36 device has newer ones: both directions of drift are pinned here (AU-059).
 */
class CanonicalRunLabelTest {
    @Test
    fun `keeps a code point Unicode 15_1 assigns even where the host tables do not`() {
        // U+2EBF0, first CJK Extension I ideograph (Unicode 15.1, Lo). JDK 17 calls it unassigned.
        assertEquals("a𮯰b", canonicalRunLabel("a𮯰b"))
        // U+1FAE8 SHAKING FACE (Unicode 15.0, So).
        assertEquals("a🫨b", canonicalRunLabel("a🫨b"))
    }

    @Test
    fun `drops a code point Unicode 15_1 leaves unassigned`() {
        // U+1FAE9 is an Emoji 16.0 face: assigned on an API 36 platform, unassigned in the contract.
        assertEquals("ab", canonicalRunLabel("a🫩b"))
    }

    @Test
    fun `drops format control private use and lone surrogate code points`() {
        assertEquals("ab", canonicalRunLabel("a‌b"))
        assertEquals("ab", canonicalRunLabel("a\u0007b"))
        assertEquals("ab", canonicalRunLabel("ab"))
        assertEquals("ab", canonicalRunLabel("a\uD800b"))
    }

    @Test
    fun `trims python whitespace and composes NFC`() {
        assertEquals("Episode 1", canonicalRunLabel("　Episode 1 "))
        assertEquals("é", canonicalRunLabel(" é "))
        assertEquals("", canonicalRunLabel("​ 　"))
    }

    @Test
    fun `every label satisfies the bridge decoder's canonical rule`() {
        val inputs =
            listOf(
                "a🫩b",
                " x ",
                "é",
                "\u0085name\u001C",
                "﻿series‮",
                "𮯰 volume 2",
            )
        for (input in inputs) {
            val label = canonicalRunLabel(input)
            assertTrue(input, UnicodeContractV151.isNfc(label))
            assertFalse(input, UnicodeContractV151.hasLeadingOrTrailingPythonWhitespace(label))
            var index = 0
            while (index < label.length) {
                val codePoint = label.codePointAt(index)
                assertFalse(input, UnicodeContractV151.isCategoryC(codePoint))
                index += Character.charCount(codePoint)
            }
        }
    }
}
