package com.ankiminer.android.anki

import com.ankiminer.android.anki.generated.UnicodeContractV151
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnicodeContractV151Test {
    @Test
    fun scalarCategoryCAndPythonWhitespaceMatchPinnedTruth() {
        val categoryC = categoryCTruth()
        val whitespace = whitespaceTruth()

        for (codePoint in 0..MAX_CODE_POINT) {
            val isScalar = codePoint !in 0xD800..0xDFFF
            assertEquals(
                "scalar U+${codePoint.hex()}",
                isScalar,
                UnicodeContractV151.isUnicodeScalar(codePoint),
            )
            if (isScalar) {
                assertEquals(
                    "category C U+${codePoint.hex()}",
                    categoryC[codePoint].toInt() != 0,
                    UnicodeContractV151.isCategoryC(codePoint),
                )
                assertEquals(
                    "whitespace U+${codePoint.hex()}",
                    whitespace[codePoint].toInt() != 0,
                    UnicodeContractV151.isPythonWhitespace(codePoint),
                )
            }
        }

        for (invalid in intArrayOf(-1, 0xD800, 0xDFFF, 0x110000)) {
            assertFalse(UnicodeContractV151.isUnicodeScalar(invalid))
            assertFalse(UnicodeContractV151.isCategoryC(invalid))
            assertFalse(UnicodeContractV151.isPythonWhitespace(invalid))
        }
    }

    @Test
    fun completeUnicode151NormalizationCorpusPasses() {
        var rowNumber = 0
        unicodeResource("NormalizationTest.txt").bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (rawLine in lines) {
                val line = rawLine.substringBefore('#').trim()
                if (line.isEmpty() || line.startsWith('@')) continue
                val fields = line.split(';').map(String::trim)
                check(fields.size == 6 && fields.last().isEmpty())
                val values = fields.take(5).map(::codePointsToString)
                val targets = listOf(values[1], values[1], values[1], values[3], values[3])
                rowNumber += 1
                for (column in values.indices) {
                    assertEquals(
                        "NormalizationTest row $rowNumber column ${column + 1}",
                        values[column] == targets[column],
                        UnicodeContractV151.isNfc(values[column]),
                    )
                }
            }
        }
        assertEquals(19_074, rowNumber)
    }

    @Test
    fun nontrivialReorderingAndScalarHelpersPass() {
        val nonNormalized = "\u1e0a\u0323"
        val normalized = "\u1e0c\u0307"

        assertFalse(UnicodeContractV151.isNfc(nonNormalized))
        assertTrue(UnicodeContractV151.isNfc(normalized))
        assertEquals(2, UnicodeContractV151.scalarCount("A😀"))
        assertEquals(5, UnicodeContractV151.strictUtf8Length("A😀"))
        assertTrue(UnicodeContractV151.hasLeadingOrTrailingPythonWhitespace("\u001cA"))
        assertFalse(UnicodeContractV151.hasLeadingOrTrailingPythonWhitespace("A😀"))

        for (value in listOf("\ud800", "A\udfff")) {
            assertNull(UnicodeContractV151.scalarCount(value))
            assertNull(UnicodeContractV151.strictUtf8Length(value))
            assertFalse(UnicodeContractV151.isNfc(value))
            assertFalse(UnicodeContractV151.hasLeadingOrTrailingPythonWhitespace(value))
        }
        assertEquals(1, UnicodeContractV151.scalarCount("\ud800\udfff"))
        assertEquals(4, UnicodeContractV151.strictUtf8Length("\ud800\udfff"))
    }

    private fun categoryCTruth(): ByteArray {
        val truth = ByteArray(MAX_CODE_POINT + 1) { 1 }
        var pendingStart: Int? = null
        var pendingValue = false
        unicodeResource("UnicodeData.txt").bufferedReader(Charsets.US_ASCII).useLines { lines ->
            for (line in lines) {
                val fields = line.split(';')
                val codePoint = fields[0].toInt(16)
                val name = fields[1]
                val isCategoryC = fields[2].startsWith('C')
                when {
                    name.endsWith(", First>") -> {
                        check(pendingStart == null)
                        pendingStart = codePoint
                        pendingValue = isCategoryC
                    }
                    name.endsWith(", Last>") -> {
                        val start = checkNotNull(pendingStart)
                        check(pendingValue == isCategoryC)
                        truth.fill(if (isCategoryC) 1 else 0, start, codePoint + 1)
                        pendingStart = null
                    }
                    else -> {
                        check(pendingStart == null)
                        truth[codePoint] = if (isCategoryC) 1 else 0
                    }
                }
            }
        }
        check(pendingStart == null)
        return truth
    }

    private fun whitespaceTruth(): ByteArray {
        val source =
            unicodeResource("python-3.13-isspace.json")
                .bufferedReader(Charsets.UTF_8)
                .use { reader -> reader.readText() }
        check(Regex("\\\"pythonVersion\\\"\\s*:\\s*\\\"3\\.13\\\"").containsMatchIn(source))
        check(Regex("\\\"unicodeVersion\\\"\\s*:\\s*\\\"15\\.1\\.0\\\"").containsMatchIn(source))
        val ranges = Regex("\\[(\\d+),\\s*(\\d+)]").findAll(source).toList()
        check(ranges.size == 10)
        return ByteArray(MAX_CODE_POINT + 1).also { truth ->
            for (match in ranges) {
                val start = match.groupValues[1].toInt()
                val end = match.groupValues[2].toInt()
                truth.fill(1, start, end + 1)
            }
        }
    }

    private fun unicodeResource(name: String) =
        checkNotNull(javaClass.getResourceAsStream("/$name")) { "Missing Unicode test resource: $name" }

    private fun codePointsToString(field: String): String =
        buildString {
            if (field.isEmpty()) return@buildString
            for (token in field.split(' ')) {
                val codePoint = token.toInt(16)
                check(codePoint in 0..MAX_CODE_POINT && codePoint !in 0xD800..0xDFFF)
                if (codePoint <= 0xFFFF) {
                    append(codePoint.toChar())
                } else {
                    val supplementary = codePoint - 0x10000
                    append((0xD800 + (supplementary ushr 10)).toChar())
                    append((0xDC00 + (supplementary and 0x3FF)).toChar())
                }
            }
        }

    private fun Int.hex(): String = toString(16).uppercase().padStart(4, '0')

    private companion object {
        const val MAX_CODE_POINT = 0x10FFFF
    }
}
