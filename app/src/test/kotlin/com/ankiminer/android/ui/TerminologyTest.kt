package com.ankiminer.android.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TerminologyTest {
    @Test
    fun miningCreationCopyUsesAnkiNotesInsteadOfCards() {
        val strings = stringResources()
        val creationKeys =
            listOf(
                "video_mining_intro",
                "reading_mining_intro",
                "reading_series_help",
                "anki_note_type_description",
                "anki_note_type_status_not_selected",
                "anki_quality_limited_warning",
                "wizard_ankidroid_body",
                "wizard_deck_body",
            )

        creationKeys.forEach { key ->
            val value = requireNotNull(strings[key]) { "Missing string resource: $key" }
            val withoutRenderedReviewCards =
                value.replace(REVIEW_CARD_TERM, "")
            assertFalse(
                "$key still uses card creation copy: $value",
                CARD_TERM.containsMatchIn(withoutRenderedReviewCards),
            )
        }
    }

    @Test
    fun flashcardsRemainLimitedToIntroductoryProductCopy() {
        val flashcardKeys =
            stringResources()
                .filterValues { it.contains("flashcard", ignoreCase = true) }
                .keys

        assertEquals(setOf("wizard_welcome_body"), flashcardKeys)
    }

    private fun stringResources(): Map<String, String> {
        val source = locateFromWorkspace("app/src/main/res/values/strings.xml").readText()
        return STRING_RESOURCE
            .findAll(source)
            .associate { match ->
                match.groupValues[1] to match.groupValues[2].replace(Regex("<[^>]+>"), "")
            }
    }

    private fun locateFromWorkspace(relativePath: String): File {
        var cursor = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        repeat(8) {
            val candidate = File(cursor, relativePath)
            if (candidate.isFile) return candidate
            cursor = cursor.parentFile ?: return@repeat
        }
        error("Could not locate $relativePath from ${System.getProperty("user.dir")}")
    }

    private companion object {
        val CARD_TERM = Regex("""\bcards?\b""", RegexOption.IGNORE_CASE)
        val REVIEW_CARD_TERM = Regex("""\breview[ -]cards?\b""", RegexOption.IGNORE_CASE)
        val STRING_RESOURCE =
            Regex(
                """<string\s+name="([^"]+)"[^>]*>(.*?)</string>""",
                setOf(RegexOption.DOT_MATCHES_ALL),
            )
    }
}
