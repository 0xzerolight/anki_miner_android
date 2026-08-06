package com.ankiminer.android.ui.settings

import java.text.Normalizer

/**
 * Keeps ASCII typing and full-width IME output on one search path, so input mode and case do not
 * hide a setting the user knows by name.
 */
internal fun normalizeSettingsText(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase().trim()

/**
 * Keeps title separate from supporting text because helper copy may find a setting, but must not
 * outrank the setting whose name the user typed.
 */
internal data class ResolvedSettingsEntry(
    val id: String,
    val category: SettingsCategory,
    val cardKey: String,
    val title: String,
    val breadcrumb: String,
    /** Already normalized. `haystack[0]` is the normalized title, which is what ranking reads. */
    val haystack: List<String>,
)

/**
 * Requires every word to land somewhere so longer queries narrow results, then favors title hits
 * so a matching name stays ahead of incidental helper text.
 */
internal fun rankSettingsEntry(
    entry: ResolvedSettingsEntry,
    query: String,
    tokens: List<String>,
): Int? {
    if (tokens.any { token -> entry.haystack.none { field -> token in field } }) {
        return null
    }

    // firstOrNull, not [0]: an entry whose title resolved to blank would otherwise crash the
    // search rather than simply rank last.
    val title = entry.haystack.firstOrNull().orEmpty()
    return when {
        title == query -> 3
        title.startsWith(query) -> 2
        query in title -> 1
        else -> 0
    }
}

/**
 * Treats Japanese IME spacing as a word boundary and keeps equal matches in screen order. A blank
 * question yields no answers because dumping every setting would bury category navigation.
 */
internal fun searchSettings(
    entries: List<ResolvedSettingsEntry>,
    query: String,
): List<ResolvedSettingsEntry> {
    val normalizedQuery = normalizeSettingsText(query)
    val tokens =
        normalizedQuery
            .split(Regex("[\\s\\u3000]+"))
            .filter(String::isNotEmpty)
    if (tokens.isEmpty()) {
        return emptyList()
    }

    return entries
        .withIndex()
        .mapNotNull { (index, entry) ->
            rankSettingsEntry(entry, normalizedQuery, tokens)?.let { rank ->
                Triple(entry, rank, index)
            }
        }
        .sortedWith(
            compareByDescending<Triple<ResolvedSettingsEntry, Int, Int>> { it.second }
                .thenBy { it.third },
        )
        .map { it.first }
}
