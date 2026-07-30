package com.ankiminer.android.data.settings

import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * Pre-flight checks for the user's subtitle regex filter.
 *
 * Python `re` is the authority — the engine compiles the pattern itself and, if it fails, logs and
 * runs with the filter disabled. Two things still have to be caught here:
 *
 * 1. The vendored engine compiles without a wall-clock timeout, so a pathological pattern can hang
 *    the parser on a long subtitle line. The size caps and the nested-unbounded-repeat reject are
 *    ported from desktop `services/subtitle_parser.compile_subtitle_regex_filter`.
 * 2. The engine preflights replacement syntax with `compiled.sub(replacement, "")`. The Kotlin
 *    boundary mirrors Python replacement references so the persisted-settings validator can report
 *    the same pair error before mining.
 *
 * Everything else is a soft [compiles] warning: `java.util.regex` and Python `re` are different
 * dialects, so a pattern Java rejects may still be valid for the engine.
 */
internal object SubtitleRegexCheck {
    const val MAX_PATTERN_CHARS = 512
    const val MAX_REPLACEMENT_CHARS = 512

    /**
     * The hard rejection for [pattern]/[replacement], or null when nothing is broken. A null
     * [pattern] means no filter is stored, so the pattern-dependent checks — including the
     * replacement's group references, which are only meaningful against a pattern — are skipped.
     */
    fun rejection(
        pattern: String?,
        replacement: String,
    ): InvalidAppSettingCode? =
        when {
            (pattern?.length ?: 0) > MAX_PATTERN_CHARS ->
                InvalidAppSettingCode.SUBTITLE_REGEX_TOO_LONG
            replacement.length > MAX_REPLACEMENT_CHARS ->
                InvalidAppSettingCode.SUBTITLE_REGEX_REPLACEMENT_TOO_LONG
            pattern == null -> null
            NESTED_UNBOUNDED_REPEAT.containsMatchIn(pattern) ->
                InvalidAppSettingCode.SUBTITLE_REGEX_UNBOUNDED_REPEAT
            replacementRejected(pattern, replacement) ->
                InvalidAppSettingCode.SUBTITLE_REGEX_BACKREFERENCE
            else -> null
        }

    /** Whether `java.util.regex` accepts [pattern]. A false only warrants a warning. */
    fun compiles(pattern: String): Boolean =
        try {
            Pattern.compile(pattern)
            true
        } catch (_: PatternSyntaxException) {
            false
        }

    /** Python `re._parser.parse_template` parity for replacement escapes and group references. */
    private fun replacementRejected(
        pattern: String,
        replacement: String,
    ): Boolean {
        val captures = captureGroups(pattern)
        var index = 0
        while (index < replacement.length) {
            if (replacement[index] != '\\') {
                index += 1
                continue
            }
            if (index + 1 >= replacement.length) return true
            val escaped = replacement[index + 1]
            when {
                escaped == '\\' -> index += 2
                escaped == 'g' -> {
                    if (replacement.getOrNull(index + 2) != '<') return true
                    val end = replacement.indexOf('>', startIndex = index + 3)
                    if (end < 0) return true
                    val name = replacement.substring(index + 3, end)
                    val number = name.toIntOrNull()?.takeIf { name.all { it.isAsciiDigit() } }
                    if (
                        when {
                            number != null -> number > captures.count
                            else -> name !in captures.names
                        }
                    ) {
                        return true
                    }
                    index = end + 1
                }
                escaped == '0' -> {
                    index += 2
                    repeat(2) {
                        if (replacement.getOrNull(index)?.let { it in '0'..'7' } == true) {
                            index += 1
                        }
                    }
                }
                escaped in '1'..'9' -> {
                    var end = index + 2
                    if (replacement.getOrNull(end)?.let { it in '0'..'9' } == true) end += 1
                    val octal =
                        escaped in '0'..'7' &&
                            replacement.getOrNull(index + 2)?.let { it in '0'..'7' } == true &&
                            replacement.getOrNull(index + 3)?.let { it in '0'..'7' } == true
                    if (octal) {
                        val value = replacement.substring(index + 1, index + 4).toInt(radix = 8)
                        if (value > 0xff) return true
                        index += 4
                    } else {
                        val group = replacement.substring(index + 1, end).toInt()
                        if (group > captures.count) return true
                        index = end
                    }
                }
                escaped in PYTHON_REPLACEMENT_ESCAPES -> index += 2
                escaped.isAsciiLetter() -> return true
                else -> index += 2
            }
        }
        return false
    }

    /** Extract Python capture numbering without compiling the different Java regex dialect. */
    private fun captureGroups(pattern: String): PythonCaptureGroups {
        var count = 0
        val names = mutableSetOf<String>()
        var index = 0
        var verbose = false
        val enclosingVerboseModes = mutableListOf<Boolean>()
        while (index < pattern.length) {
            when (pattern[index]) {
                '\\' -> index += 2
                '[' -> index = endOfCharacterClass(pattern, index)
                '#' ->
                    if (verbose) {
                        index =
                            pattern
                                .indexOf('\n', startIndex = index + 1)
                                .let { if (it < 0) pattern.length else it + 1 }
                    } else {
                        index += 1
                    }
                '(' -> {
                    if (pattern.startsWith("(?#", index)) {
                        index =
                            pattern
                                .indexOf(')', startIndex = index + 3)
                                .let { if (it < 0) pattern.length else it + 1 }
                    } else {
                        val globalFlags = PYTHON_GLOBAL_FLAGS.matchAt(pattern, index)
                        val scopedFlags = PYTHON_SCOPED_FLAGS.matchAt(pattern, index)
                        when {
                            globalFlags != null -> {
                                verbose = 'x' in globalFlags.groupValues[1]
                                index = globalFlags.range.last + 1
                            }
                            scopedFlags != null -> {
                                enclosingVerboseModes += verbose
                                val enabled = scopedFlags.groupValues[1]
                                val disabled = scopedFlags.groupValues[2]
                                verbose =
                                    when {
                                        'x' in disabled -> false
                                        'x' in enabled -> true
                                        else -> verbose
                                    }
                                index = scopedFlags.range.last + 1
                            }
                            pattern.startsWith("(?(", index) -> {
                                enclosingVerboseModes += verbose
                                index =
                                    pattern
                                        .indexOf(')', startIndex = index + 3)
                                        .let { if (it < 0) pattern.length else it + 1 }
                            }
                            else -> {
                                enclosingVerboseModes += verbose
                                if (pattern.startsWith("(?P<", index)) {
                                    count += 1
                                    val end = pattern.indexOf('>', startIndex = index + 4)
                                    if (end < 0) {
                                        index = pattern.length
                                    } else {
                                        names += pattern.substring(index + 4, end)
                                        index = end + 1
                                    }
                                } else {
                                    if (!pattern.startsWith("(?", index)) count += 1
                                    index += 1
                                }
                            }
                        }
                    }
                }
                ')' -> {
                    if (enclosingVerboseModes.isNotEmpty()) {
                        verbose = enclosingVerboseModes.removeLast()
                    }
                    index += 1
                }
                else -> index += 1
            }
        }
        return PythonCaptureGroups(count, names)
    }

    /**
     * Return the first position after a Python character class. `]` is literal when it is the
     * first class member (after an optional `^`), so a class such as `[]()]` contains no group.
     */
    private fun endOfCharacterClass(
        pattern: String,
        opening: Int,
    ): Int {
        var index = opening + 1
        if (pattern.getOrNull(index) == '^') index += 1
        if (pattern.getOrNull(index) == ']') index += 1
        while (index < pattern.length) {
            when (pattern[index]) {
                '\\' -> index += 2
                ']' -> return index + 1
                else -> index += 1
            }
        }
        return pattern.length
    }

    /**
     * Python global and scoped inline-flag groups. Only `x` changes capture scanning because it
     * makes unescaped `#` start a comment outside character classes.
     */
    private val PYTHON_GLOBAL_FLAGS = Regex("""\(\?([aiLmsux]+)\)""")
    private val PYTHON_SCOPED_FLAGS = Regex("""\(\?([aiLmsux]*)(?:-([imsx]+))?:""")

    /**
     * Ported from desktop `_NESTED_UNBOUNDED_REPEAT_RE`. The `[` inside the negated class is
     * escaped, which Python leaves as-is and which keeps `java.util.regex` from reading the
     * following `[\]\\]` as a class union.
     */
    private const val REGEX_ATOM = """(?:\\.|\[(?:\\.|[^\]\\])*\]|[^()\[\]\\])"""
    private val NESTED_UNBOUNDED_REPEAT =
        Regex("""\(""" + REGEX_ATOM + """*(?:[*+]|\{\d+,\})""" + REGEX_ATOM + """*\)(?:[*+]|\{\d+,\})""")

    private data class PythonCaptureGroups(
        val count: Int,
        val names: Set<String>,
    )

    private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

    private fun Char.isAsciiLetter(): Boolean = this in 'A'..'Z' || this in 'a'..'z'

    private val PYTHON_REPLACEMENT_ESCAPES = setOf('a', 'b', 'f', 'n', 'r', 't', 'v')
}
