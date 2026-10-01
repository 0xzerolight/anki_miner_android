package com.ankiminer.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Actions choose meaning through the design wrappers; a raw Material button bypasses that. */
class ButtonWrapperUsageTest {
    @Test
    fun outlinedButtonsGoThroughTheDesignWrappers() {
        val offenders =
            uiSources()
                .filterNot { (relative, _) -> relative in RAW_OUTLINED_BUTTON_OWNERS }
                .flatMap { (relative, text) ->
                    RAW_OUTLINED_BUTTON.findAll(text).map { "$relative:${lineOf(text, it.range.first)}" }.toList()
                }
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun everyPlainTextButtonNamesItsColours() {
        val offenders =
            uiSources().flatMap { (relative, text) ->
                RAW_TEXT_BUTTON.findAll(text)
                    .filter { match -> "colors" !in callArguments(text, match.range.last) }
                    .map { match -> "$relative:${lineOf(text, match.range.first)}" }
                    .toList()
            }
        assertEquals(emptyList<String>(), offenders)
    }

    /** The argument list of the call whose `(` sits at [openParen], through its matching `)`. */
    private fun callArguments(
        text: String,
        openParen: Int,
    ): String {
        var depth = 0
        for (index in openParen until text.length) {
            when (text[index]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return text.substring(openParen, index + 1)
            }
        }
        return text.substring(openParen)
    }

    private fun uiSources(): List<Pair<String, String>> {
        val root = locateFromWorkspace("app/src/main/kotlin/com/ankiminer/android/ui")
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(root).invariantSeparatorsPath to it.readText() }
            .sortedBy { it.first }
            .toList()
    }

    private fun lineOf(
        text: String,
        offset: Int,
    ): Int = text.substring(0, offset).count { it == '\n' } + 1

    private companion object {
        /** A call, never an import line or `OutlinedIconButton`/`outlinedButtonColors`. */
        val RAW_OUTLINED_BUTTON = Regex("""(?<![\w.])OutlinedButton\(""")

        /** The wrapper itself, and the two mining buttons that need colours the wrappers lack. */
        val RAW_OUTLINED_BUTTON_OWNERS = setOf("theme/DesignSystem.kt", "mining/SharedMiningComponents.kt")

        /** The stock TextButton paints `primary`, which is below 4.5:1 as text on several palettes. */
        val RAW_TEXT_BUTTON = Regex("""(?<![\w.])TextButton\(""")
    }
}
