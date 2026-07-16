package com.ankiminer.android.anki.provider

import com.ankiminer.android.anki.generated.Html5EntitiesV312
import com.ankiminer.android.anki.generated.UnicodeContractV151

/** Exact Kotlin port of desktop `anki_note_builder._strip_for_dedup`. */
internal object DuplicateFirstFieldNormalizer {
    private val soundReference =
        Regex(
            pattern = """\[(?:sound|anki:play[^\]]*):[^\]]*\]""",
            option = RegexOption.IGNORE_CASE,
        )
    private val htmlTag = Regex("<[^>]+>")

    fun normalize(value: String): String {
        val mediaStripped = soundReference.replace(value, "")
        val markupStripped = htmlTag.replace(mediaStripped, "")
        val unescaped = unescapeHtml5(markupStripped)
        val normalized =
            UnicodeContractV151.normalizeNfc(unescaped)
                ?: throw IllegalArgumentException("duplicate first field is not valid Unicode")
        return collapsePythonWhitespace(normalized)
    }

    private fun unescapeHtml5(value: String): String {
        if ('&' !in value) return value
        val result = StringBuilder(value.length)
        var copiedThrough = 0
        while (copiedThrough < value.length) {
            val ampersand = value.indexOf('&', copiedThrough)
            if (ampersand < 0) {
                result.append(value, copiedThrough, value.length)
                break
            }
            result.append(value, copiedThrough, ampersand)
            val matchEnd = charReferenceEnd(value, ampersand + 1)
            if (matchEnd == null) {
                result.append('&')
                copiedThrough = ampersand + 1
            } else {
                result.append(replaceCharReference(value.substring(ampersand + 1, matchEnd)))
                copiedThrough = matchEnd
            }
        }
        return result.toString()
    }

    /** End-exclusive UTF-16 offset for CPython 3.12's `_charref` regex match body. */
    private fun charReferenceEnd(
        value: String,
        start: Int,
    ): Int? {
        if (start >= value.length) return null
        if (value[start] == '#') {
            if (start + 1 < value.length && value[start + 1].isAsciiDigit()) {
                var end = start + 2
                while (end < value.length && value[end].isAsciiDigit()) end += 1
                return if (end < value.length && value[end] == ';') end + 1 else end
            }
            if (
                start + 2 < value.length &&
                    (value[start + 1] == 'x' || value[start + 1] == 'X') &&
                    value[start + 2].isAsciiHexDigit()
            ) {
                var end = start + 3
                while (end < value.length && value[end].isAsciiHexDigit()) end += 1
                return if (end < value.length && value[end] == ';') end + 1 else end
            }
            return null
        }

        var end = start
        var scalarCount = 0
        while (end < value.length && scalarCount < MAX_NAMED_REFERENCE_SCALARS) {
            val codePoint = Character.codePointAt(value, end)
            if (codePoint.isNamedReferenceDelimiter()) break
            end += Character.charCount(codePoint)
            scalarCount += 1
        }
        if (scalarCount == 0) return null
        if (end < value.length && value[end] == ';') end += 1
        return end
    }

    private fun replaceCharReference(reference: String): String =
        if (reference[0] == '#') {
            replaceNumericReference(reference)
        } else {
            replaceNamedReference(reference)
        }

    private fun replaceNumericReference(reference: String): String {
        val hexadecimal = reference.length > 1 && (reference[1] == 'x' || reference[1] == 'X')
        val start = if (hexadecimal) 2 else 1
        val digitEnd = if (reference.last() == ';') reference.lastIndex else reference.length
        val radix = if (hexadecimal) 16 else 10
        var value = 0
        for (index in start until digitEnd) {
            val digit = reference[index].digitToInt(radix)
            if (value > (MAX_UNICODE_SCALAR - digit) / radix) return REPLACEMENT_CHARACTER
            value = value * radix + digit
        }
        invalidNumericReference(value)?.let { return it }
        if (value in SURROGATE_RANGE || value > MAX_UNICODE_SCALAR) return REPLACEMENT_CHARACTER
        if (value.isInvalidHtml5CodePoint()) return ""
        return String(Character.toChars(value))
    }

    private fun replaceNamedReference(reference: String): String {
        Html5EntitiesV312.lookup(reference)?.let { return it }
        val scalarEnds = ArrayList<Int>(reference.length)
        var end = 0
        while (end < reference.length) {
            end += Character.charCount(Character.codePointAt(reference, end))
            scalarEnds += end
        }
        for (scalarCount in scalarEnds.size - 1 downTo 2) {
            val prefixEnd = scalarEnds[scalarCount - 1]
            val replacement = Html5EntitiesV312.lookup(reference.substring(0, prefixEnd))
            if (replacement != null) {
                return replacement + reference.substring(prefixEnd)
            }
        }
        return "&$reference"
    }

    private fun collapsePythonWhitespace(value: String): String {
        val result = StringBuilder(value.length)
        var index = 0
        var pendingSeparator = false
        while (index < value.length) {
            val codePoint = Character.codePointAt(value, index)
            index += Character.charCount(codePoint)
            if (UnicodeContractV151.isPythonWhitespace(codePoint)) {
                pendingSeparator = result.isNotEmpty()
            } else {
                if (pendingSeparator) result.append(' ')
                result.appendCodePoint(codePoint)
                pendingSeparator = false
            }
        }
        return result.toString()
    }

    private fun invalidNumericReference(value: Int): String? =
        when (value) {
            0x00 -> REPLACEMENT_CHARACTER
            0x0D -> "\r"
            0x80 -> "\u20AC"
            0x81 -> "\u0081"
            0x82 -> "\u201A"
            0x83 -> "\u0192"
            0x84 -> "\u201E"
            0x85 -> "\u2026"
            0x86 -> "\u2020"
            0x87 -> "\u2021"
            0x88 -> "\u02C6"
            0x89 -> "\u2030"
            0x8A -> "\u0160"
            0x8B -> "\u2039"
            0x8C -> "\u0152"
            0x8D -> "\u008D"
            0x8E -> "\u017D"
            0x8F -> "\u008F"
            0x90 -> "\u0090"
            0x91 -> "\u2018"
            0x92 -> "\u2019"
            0x93 -> "\u201C"
            0x94 -> "\u201D"
            0x95 -> "\u2022"
            0x96 -> "\u2013"
            0x97 -> "\u2014"
            0x98 -> "\u02DC"
            0x99 -> "\u2122"
            0x9A -> "\u0161"
            0x9B -> "\u203A"
            0x9C -> "\u0153"
            0x9D -> "\u009D"
            0x9E -> "\u017E"
            0x9F -> "\u0178"
            else -> null
        }

    private fun Int.isInvalidHtml5CodePoint(): Boolean =
        this in 0x01..0x08 ||
            this == 0x0B ||
            this in 0x0E..0x1F ||
            this in 0x7F..0x9F ||
            this in 0xFDD0..0xFDEF ||
            (this and 0xFFFF) in 0xFFFE..0xFFFF

    private fun Int.isNamedReferenceDelimiter(): Boolean =
        this == '\t'.code ||
            this == '\n'.code ||
            this == '\u000C'.code ||
            this == ' '.code ||
            this == '<'.code ||
            this == '&'.code ||
            this == '#'.code ||
            this == ';'.code

    private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

    private fun Char.isAsciiHexDigit(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private const val MAX_NAMED_REFERENCE_SCALARS = 32
    private const val MAX_UNICODE_SCALAR = 0x10FFFF
    private const val REPLACEMENT_CHARACTER = "\uFFFD"
    private val SURROGATE_RANGE = 0xD800..0xDFFF
}
