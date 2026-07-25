package com.ankiminer.android.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TerminologyTest {
    @Test
    fun miningCreationCopyUsesAnkiNotesInsteadOfCards() {
        val strings = stringResources()
        // Only copy the app still renders. The intros, help lines, and wizard bodies this used
        // to police were deleted, not reworded.
        val creationKeys =
            listOf(
                "anki_note_type_status_not_selected",
                "anki_quality_limited_warning",
                "anki_quality_optional_warning",
                "mining_notification_channel_description",
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

        // The one introductory string that used the word is gone; nothing may reintroduce it.
        assertEquals(emptySet<String>(), flashcardKeys)
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
