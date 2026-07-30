package com.ankiminer.android.data.settings

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Differential test against CPython's own verdicts.
 *
 * [SubtitleRegexCheck] hand-implements Python's replacement-template grammar so a bad
 * (pattern, replacement) pair is reported in Settings rather than raising mid-run. A
 * hand-written dialect parser is only safe if it agrees with the engine in BOTH directions:
 * over-strictness would block a filter the desktop application accepts, which is a product
 * divergence, not a hardening.
 *
 * The corpus records what `compiled.sub(replacement, "")` does under real `re` — the same
 * preflight the vendored subtitle parser performs. `tests/python/android_bridge/`
 * `test_subtitle_regex_replacement_corpus.py` re-derives every verdict against the live
 * interpreter, so the fixture cannot drift into being a self-fulfilling oracle.
 */
class SubtitleRegexReplacementParityTest {
    private data class Case(
        val name: String,
        val pattern: String,
        val replacement: String,
        val rejected: Boolean,
    )

    @Test
    fun `kotlin replacement validation matches python for every committed case`() {
        val cases = cases()
        assertTrue("corpus should be substantial", cases.size >= 60)
        assertTrue("corpus needs accepted cases", cases.any { !it.rejected })
        assertTrue("corpus needs rejected cases", cases.any { it.rejected })

        cases.forEach { case ->
            val actual = SubtitleRegexCheck.rejection(case.pattern, case.replacement)
            if (case.rejected) {
                assertEquals(
                    "python rejects ${case.name} (${case.pattern} -> ${case.replacement}); " +
                        "kotlin must too",
                    InvalidAppSettingCode.SUBTITLE_REGEX_BACKREFERENCE,
                    actual,
                )
            } else {
                assertEquals(
                    "python accepts ${case.name} (${case.pattern} -> ${case.replacement}); " +
                        "kotlin must not reject it",
                    null,
                    actual,
                )
            }
        }
    }

    private fun cases(): List<Case> {
        val resource = "contracts/subtitle_regex_replacement_v1.json"
        val input = checkNotNull(javaClass.classLoader?.getResourceAsStream(resource)) {
            "missing parity corpus: $resource"
        }
        val parsed = mutableListOf<Case>()
        JsonFactory().createParser(input).use { parser ->
            check(parser.nextToken() == JsonToken.START_OBJECT)
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                check(parser.currentToken() == JsonToken.FIELD_NAME)
                val field = parser.currentName()
                parser.nextToken()
                if (field != "cases") {
                    parser.skipChildren()
                    continue
                }
                check(parser.currentToken() == JsonToken.START_ARRAY)
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    var name = ""
                    var pattern = ""
                    var replacement = ""
                    var rejected = false
                    while (parser.nextToken() != JsonToken.END_OBJECT) {
                        val key = parser.currentName()
                        parser.nextToken()
                        when (key) {
                            "name" -> name = parser.text
                            "pattern" -> pattern = parser.text
                            "replacement" -> replacement = parser.text
                            "rejected" -> rejected = parser.booleanValue
                            else -> parser.skipChildren()
                        }
                    }
                    parsed += Case(name, pattern, replacement, rejected)
                }
            }
        }
        return parsed
    }
}
