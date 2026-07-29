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
 * 2. The vendored engine only guards `re.compile`, not the substitution, so a replacement naming a
 *    group the pattern does not have would raise mid-run instead of degrading.
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
            backreferenceOutOfRange(pattern, replacement) ->
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

    /**
     * Whether [replacement] names a capture group [pattern] does not have. Python spells
     * backreferences `\1`, so a Java `replaceAll` smoke test cannot stand in for the engine's own;
     * count the groups instead. Skipped when Java cannot compile the pattern — the group count is
     * unknown then, and Python remains the authority.
     */
    private fun backreferenceOutOfRange(
        pattern: String,
        replacement: String,
    ): Boolean {
        val groups =
            try {
                Pattern.compile(pattern).matcher("").groupCount()
            } catch (_: PatternSyntaxException) {
                return false
            }
        return PYTHON_BACKREFERENCE
            .findAll(replacement)
            .mapNotNull { it.groupValues[1].ifEmpty { it.groupValues[2] }.toIntOrNull() }
            .any { it > groups }
    }

    /**
     * Ported from desktop `_NESTED_UNBOUNDED_REPEAT_RE`. The `[` inside the negated class is
     * escaped, which Python leaves as-is and which keeps `java.util.regex` from reading the
     * following `[\]\\]` as a class union.
     */
    private const val REGEX_ATOM = """(?:\\.|\[(?:\\.|[^\]\\])*\]|[^()\[\]\\])"""
    private val NESTED_UNBOUNDED_REPEAT =
        Regex("""\(""" + REGEX_ATOM + """*(?:[*+]|\{\d+,\})""" + REGEX_ATOM + """*\)(?:[*+]|\{\d+,\})""")

    /**
     * `\1` and `\g<1>`. The leading escaped-backslash alternative consumes `\\1` — a literal
     * backslash followed by a digit — before it can read as a group reference. Named `\g<name>`
     * groups carry no number to range-check.
     */
    private val PYTHON_BACKREFERENCE = Regex("""\\\\|\\(?:(\d{1,2})|g<(\d{1,2})>)""")
}
