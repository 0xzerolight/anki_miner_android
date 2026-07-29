package com.ankiminer.android.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleRegexCheckTest {
    @Test
    fun ordinaryPatternsAndPresetsPass() {
        assertNull(SubtitleRegexCheck.rejection("""\([^)]*\)|（[^）]*）""", ""))
        assertNull(SubtitleRegexCheck.rejection("""^[^「『:：]+[:：]\s*""", ""))
        // A bounded repeat inside a group is fine; only unbounded-inside-unbounded is rejected.
        assertNull(SubtitleRegexCheck.rejection("(a{2,4})+", ""))
    }

    @Test
    fun sizeCapsMatchTheEngine() {
        assertEquals(
            InvalidAppSettingCode.SUBTITLE_REGEX_TOO_LONG,
            SubtitleRegexCheck.rejection("a".repeat(SubtitleRegexCheck.MAX_PATTERN_CHARS + 1), ""),
        )
        assertNull(SubtitleRegexCheck.rejection("a".repeat(SubtitleRegexCheck.MAX_PATTERN_CHARS), ""))
        assertEquals(
            InvalidAppSettingCode.SUBTITLE_REGEX_REPLACEMENT_TOO_LONG,
            SubtitleRegexCheck.rejection(
                "a",
                "b".repeat(SubtitleRegexCheck.MAX_REPLACEMENT_CHARS + 1),
            ),
        )
    }

    @Test
    fun nestedUnboundedRepeatsAreRejected() {
        // The engine compiles without a wall-clock timeout, so these would hang the parser.
        listOf("(a+)+", "(a*)*$", "(ab+)*", "(a{2,})+").forEach { pattern ->
            assertEquals(
                pattern,
                InvalidAppSettingCode.SUBTITLE_REGEX_UNBOUNDED_REPEAT,
                SubtitleRegexCheck.rejection(pattern, ""),
            )
        }
    }

    @Test
    fun replacementGroupReferencesMustExist() {
        assertEquals(
            InvalidAppSettingCode.SUBTITLE_REGEX_BACKREFERENCE,
            SubtitleRegexCheck.rejection("[0-9]", """\1"""),
        )
        assertEquals(
            InvalidAppSettingCode.SUBTITLE_REGEX_BACKREFERENCE,
            SubtitleRegexCheck.rejection("([0-9])", """\g<2>"""),
        )
        assertNull(SubtitleRegexCheck.rejection("([0-9])", """\1"""))
        assertNull(SubtitleRegexCheck.rejection("([0-9])", """\g<1>"""))
        // An escaped backslash is a literal, not a group reference.
        assertNull(SubtitleRegexCheck.rejection("[0-9]", """\\1"""))
        // Named groups carry no number, so there is nothing to range-check here.
        assertNull(SubtitleRegexCheck.rejection("(?P<digit>[0-9])", """\g<digit>"""))
    }

    @Test
    fun patternDependentChecksNeedAPattern() {
        // A replacement stored without a pattern must not be quarantined on read: the group
        // reference only becomes meaningful once a pattern is present.
        assertNull(SubtitleRegexCheck.rejection(null, """\1"""))
        assertEquals(
            InvalidAppSettingCode.SUBTITLE_REGEX_REPLACEMENT_TOO_LONG,
            SubtitleRegexCheck.rejection(
                null,
                "b".repeat(SubtitleRegexCheck.MAX_REPLACEMENT_CHARS + 1),
            ),
        )
    }

    @Test
    fun compileFailureIsOnlyAdvisory() {
        assertTrue(SubtitleRegexCheck.compiles("""\([^)]*\)"""))
        assertFalse(SubtitleRegexCheck.compiles("("))
        // Python-only syntax this platform rejects: the caller warns rather than blocking the save.
        assertFalse(SubtitleRegexCheck.compiles("(?P=name)"))
        assertNull(SubtitleRegexCheck.rejection("(", ""))
    }
}
