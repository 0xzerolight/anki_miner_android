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
        assertNull(SubtitleRegexCheck.rejection("(a{2})+", ""))
        assertNull(SubtitleRegexCheck.rejection("^(cat|dog)+$", ""))
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
        val astral = "𠮷"
        assertNull(
            SubtitleRegexCheck.rejection(
                astral.repeat(SubtitleRegexCheck.MAX_PATTERN_CHARS),
                astral.repeat(SubtitleRegexCheck.MAX_REPLACEMENT_CHARS),
            ),
        )
        assertEquals(
            InvalidAppSettingCode.SUBTITLE_REGEX_TOO_LONG,
            SubtitleRegexCheck.rejection(
                astral.repeat(SubtitleRegexCheck.MAX_PATTERN_CHARS + 1),
                "",
            ),
        )
        assertEquals(
            InvalidAppSettingCode.SUBTITLE_REGEX_REPLACEMENT_TOO_LONG,
            SubtitleRegexCheck.rejection(
                "a",
                astral.repeat(SubtitleRegexCheck.MAX_REPLACEMENT_CHARS + 1),
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
    fun repeatedVariableWidthGroupsAndOverlappingAlternationsAreRejected() {
        listOf(
            "(a{2,4})+",
            "(a{2,4})*$",
            "^(a|aa)+$",
            "^(ab|abab)*$",
            "^(?:xy|xyxy){1,}$",
        ).forEach { pattern ->
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
        assertNull(SubtitleRegexCheck.rejection("(?P<数字>[0-9])", """\g<数字>"""))
        // An escaped backslash is a literal, not a group reference.
        assertNull(SubtitleRegexCheck.rejection("[0-9]", """\\1"""))
        assertNull(SubtitleRegexCheck.rejection("(?P<digit>[0-9])", """\g<digit>"""))
        assertEquals(
            InvalidAppSettingCode.SUBTITLE_REGEX_BACKREFERENCE,
            SubtitleRegexCheck.rejection("(?P<digit>[0-9])", """\g<missing>"""),
        )
        assertEquals(
            InvalidAppSettingCode.SUBTITLE_REGEX_BACKREFERENCE,
            SubtitleRegexCheck.rejection("(?P<digit>[0-9])(?P=digit)", """\g<missing>"""),
        )
    }

    @Test
    fun replacementGrammarMatchesPythonForLargeGroupsAndOctalEscapes() {
        val groups = "(a)".repeat(123)

        assertNull(SubtitleRegexCheck.rejection(groups, """\g<123>"""))
        assertNull(
            SubtitleRegexCheck.rejection("(a)", """\g<00000000000000000000000000001>"""),
        )
        assertEquals(
            InvalidAppSettingCode.SUBTITLE_REGEX_BACKREFERENCE,
            SubtitleRegexCheck.rejection("(a)", """\g<123>"""),
        )
        // Three bare octal digits are one character, not a reference to group 12.
        assertNull(SubtitleRegexCheck.rejection("[0-9]", """\123"""))
        assertEquals(
            InvalidAppSettingCode.SUBTITLE_REGEX_BACKREFERENCE,
            SubtitleRegexCheck.rejection("[0-9]", """\777"""),
        )
        assertEquals(
            InvalidAppSettingCode.SUBTITLE_REGEX_BACKREFERENCE,
            SubtitleRegexCheck.rejection("(a)", """\q"""),
        )
    }

    @Test
    fun replacementGroupCountSkipsCharacterClassesAndVerboseComments() {
        assertEquals(
            InvalidAppSettingCode.SUBTITLE_REGEX_BACKREFERENCE,
            SubtitleRegexCheck.rejection("""[]()]""", """\1"""),
        )
        assertEquals(
            InvalidAppSettingCode.SUBTITLE_REGEX_BACKREFERENCE,
            SubtitleRegexCheck.rejection("(?x)# ignored (\n(a)", """\2"""),
        )
        assertEquals(
            InvalidAppSettingCode.SUBTITLE_REGEX_BACKREFERENCE,
            SubtitleRegexCheck.rejection("(?x:# ignored (\n(a))", """\2"""),
        )
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
