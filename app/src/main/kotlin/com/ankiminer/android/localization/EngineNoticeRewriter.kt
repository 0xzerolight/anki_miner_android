package com.ankiminer.android.localization

import com.ankiminer.android.R

/**
 * Replaces engine presenter notices whose English wording reads wrong on the Android result screen.
 *
 * The engine's own strings are vendored and cannot be edited here, so the swap happens at the
 * presenter seam where the localized catalogs live. Anything without a rule passes through
 * untouched — the engine emits dozens of notices and only the listed ones are restated.
 */
internal class EngineNoticeRewriter(
    private val strings: StringResourceResolver,
) {
    fun rewrite(notice: String): String {
        val match = NO_DEFINITION.matchEntire(notice) ?: return notice
        val count = match.groupValues[1].toIntOrNull() ?: return notice
        return strings.resolve(
            R.string.mining_notice_no_definition,
            listOf(count, match.groupValues[2]),
        )
    }

    private companion object {
        /**
         * `EpisodeProcessor` raises "Skipped %1 words with no definition found: %2%3" from two sites:
         * the pre-curation offline existence filter, which drops words the curator never sees, and the
         * Phase 5 lookup miss. Neither is the run's `Skipped / new` metric, which counts curated words
         * that produced no card — one screen showing both read as a contradiction. The replacement has
         * to hold for both sites, so it states the definition miss without claiming when it happened.
         *
         * Group 2 keeps the engine's word list and its "(+N more)" tail verbatim.
         *
         * `test_engine_notice_templates.py` pins the vendored literal so an engine re-pin that reworded
         * it fails the host suite instead of silently disabling this rule.
         */
        val NO_DEFINITION = Regex("""Skipped (\d+) words with no definition found: (.+)""", RegexOption.DOT_MATCHES_ALL)
    }
}
