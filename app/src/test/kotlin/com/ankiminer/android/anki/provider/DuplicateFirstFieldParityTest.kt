package com.ankiminer.android.anki.provider

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Differential test against the vendored engine's own `_strip_for_dedup` (AU-001).
 *
 * The run-state registry refuses a duplicate probe whose Python key and Kotlin re-normalization
 * differ, so any drift fails a whole create batch. The corpus records the engine's keys, and
 * `tests/python/android_bridge/test_duplicate_first_field_corpus.py` re-derives each one from the
 * live engine, so neither side can drift silently.
 */
class DuplicateFirstFieldParityTest {
    private data class Case(
        val name: String,
        val value: String,
        val key: String,
    )

    @Test
    fun `kotlin duplicate key matches the vendored engine for every committed case`() {
        val cases = cases()
        assertTrue("corpus should be substantial", cases.size >= 25)
        assertTrue("corpus needs cases normalization changes", cases.any { it.value != it.key })
        for (case in cases) {
            assertEquals(case.name, case.key, DuplicateFirstFieldNormalizer.normalize(case.value))
        }
    }

    private fun cases(): List<Case> {
        val resource = "contracts/duplicate_first_field_v1.json"
        val input =
            checkNotNull(javaClass.classLoader?.getResourceAsStream(resource)) {
                "missing parity corpus: $resource"
            }
        val parsed = mutableListOf<Case>()
        JsonFactory().createParser(input).use { parser ->
            check(parser.nextToken() == JsonToken.START_OBJECT)
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                val field = parser.currentName()
                parser.nextToken()
                if (field != "cases") {
                    parser.skipChildren()
                    continue
                }
                check(parser.currentToken() == JsonToken.START_ARRAY)
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    var name = ""
                    var value = ""
                    var key = ""
                    while (parser.nextToken() != JsonToken.END_OBJECT) {
                        val caseField = parser.currentName()
                        parser.nextToken()
                        when (caseField) {
                            "name" -> name = parser.text
                            "value" -> value = parser.text
                            "key" -> key = parser.text
                            else -> parser.skipChildren()
                        }
                    }
                    parsed += Case(name, value, key)
                }
            }
        }
        return parsed
    }
}
