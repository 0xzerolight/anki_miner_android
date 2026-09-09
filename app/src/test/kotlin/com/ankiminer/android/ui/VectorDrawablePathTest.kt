package com.ankiminer.android.ui

import androidx.compose.ui.graphics.vector.PathParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Compose honours the SVGO-collapsed arc flag form ("0 00-1-1") only on the first segment of an
 * a/A command. An implicitly repeated segment falls back to generic float scanning, so the two
 * flags merge into one token, every parameter after them shifts by one, and a trailing segment
 * left short of seven values is dropped without a word: the icon renders malformed rather than
 * failing to load. Icon sets ship optimised paths, so pasting one in verbatim is the natural
 * mistake, and only a render catches it by eye.
 */
class VectorDrawablePathTest {
    @Test
    fun everyVectorDrawableParsesTheSameWithItsArcFlagsSeparated() {
        val drawables =
            locateFromWorkspace("app/src/main/res/drawable")
                .listFiles { file -> file.extension == "xml" }
                ?.sortedBy { it.name }
                .orEmpty()
        assertTrue("No vector drawables found to check", drawables.isNotEmpty())

        drawables.forEach { drawable ->
            PATH_DATA.findAll(drawable.readText()).forEach { match ->
                val pathData = match.groupValues[1]
                if (!ARC_COMMAND.containsMatchIn(pathData)) return@forEach
                assertEquals(
                    "${drawable.name} has arc flags Compose cannot read; separate them",
                    nodesOf(separateArcFlags(pathData)),
                    nodesOf(pathData),
                )
            }
        }
    }

    private fun nodesOf(pathData: String) = PathParser().parsePathString(pathData).toNodes()

    private fun separateArcFlags(pathData: String) =
        pathData.replace(COLLAPSED_ARC_FLAGS, " 0 $1 $2 ")

    private companion object {
        val PATH_DATA = Regex("""android:pathData="([^"]+)"""")
        val ARC_COMMAND = Regex("""[aA][\d.\-]""")

        /** An arc's two boolean flags written as one token after a zero x-axis rotation. */
        val COLLAPSED_ARC_FLAGS = Regex(""" 0 ([01])([01])(?=[-.\d])""")
    }
}
