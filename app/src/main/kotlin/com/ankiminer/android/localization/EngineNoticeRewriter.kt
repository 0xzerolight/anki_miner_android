package com.ankiminer.android.localization

import com.ankiminer.android.R

/**
 * Drops or restates engine presenter notices, and restates terminal failures, before they reach
 * the Android result screen.
 *
 * The engine's own strings are vendored and cannot be edited here, so both verdicts are reached at
 * the presenter seam where the localized catalogs live. Anything without a rule passes through
 * untouched — the engine emits dozens of notices and only the listed ones are handled.
 */
internal class EngineNoticeRewriter(
    private val strings: StringResourceResolver,
) {
    /** Returns the notice to show, or `null` for a receipt the result screen must not carry. */
    fun rewrite(notice: String): String? {
        if (RECEIPTS.any { it.matches(notice) }) return null
        if (FREQUENCY_CUTOFF_IGNORED.matches(notice)) {
            return strings.resolve(R.string.mining_notice_frequency_cutoff_ignored)
        }
        val match = NO_DEFINITION.matchEntire(notice) ?: return notice
        val count = match.groupValues[1].toIntOrNull() ?: return notice
        return strings.resolve(
            R.string.mining_notice_no_definition,
            listOf(count, match.groupValues[2]),
        )
    }

    /**
     * The failure text for a terminal error. `resources_stale` is the engine's schema-staleness gate,
     * whose own sentence points at desktop Settings paths; the bridge sends only the stale resources'
     * names, as the engine quoted them, and the sentence comes from the catalogs. Every other code
     * keeps the bridge's message.
     */
    fun terminalMessage(
        code: String,
        message: String,
    ): String =
        when (code) {
            RESOURCES_STALE -> strings.resolve(R.string.mining_failure_resources_stale, listOf(message))
            else -> message
        }

    private companion object {
        /** Terminal code `mining._exception_terminal` gives a resource-staleness `SetupError`. */
        const val RESOURCES_STALE = "resources_stale"

        /**
         * `EpisodeProcessor` reports a definition miss from two sites: the pre-curation offline
         * existence filter ("Skipped %1 words missing from your offline dictionaries: %2%3"), which
         * drops words the curator never sees, and the Phase 5 lookup miss ("Skipped %1 words with no
         * definition found: %2%3"). Neither is the run's `Skipped / new` metric, which counts curated
         * words that produced no card — one screen showing both read as a contradiction. The
         * replacement has to hold for both sites, so it states the definition miss without claiming
         * when it happened.
         *
         * Group 2 keeps the engine's word list and its "(+N more)" tail verbatim.
         *
         * `test_android_localization.py` pins both vendored literals so an engine re-pin that reworded
         * one fails the host suite instead of silently disabling this rule.
         */
        val NO_DEFINITION =
            Regex(
                """Skipped (\d+) words (?:with no definition found|missing from your offline dictionaries): (.+)""",
                RegexOption.DOT_MATCHES_ALL,
            )

        /**
         * `EpisodeProcessor` warns that a frequency cutoff was ignored because no ranked frequency
         * source is loaded, and names desktop's "Settings → Frequency" as the fix. Android keeps its
         * frequency sources under Settings → Resources, so the warning is restated rather than shown
         * pointing at a category the app does not have. Pinned by `test_android_localization.py`.
         */
        val FREQUENCY_CUTOFF_IGNORED =
            Regex("""Frequency cutoff ignored — no ranked frequency source is loaded \(Settings → Frequency\)\.""")

        /**
         * Receipts: notices the engine raises on runs that went entirely to plan, where nothing was
         * lost and the user has nothing to do. Desktop files them into a searchable Activity console
         * as plain uncoloured text, one line among thirty; Android has no console, keeps only WARNING
         * and ERROR, and shows what survives as the run's issue list — so the same line arrives as a
         * bulleted problem, tinted red on a run that failed for an unrelated reason, and can even be
         * promoted to the failure headline when the engine sends no terminal message of its own.
         *
         * Severity cannot separate these from real warnings: the engine raises all of them through
         * `show_warning`. Content is the only key available.
         *
         * The list stays deliberately short. A notice reporting something lost — a filter that
         * emptied the run, a corrupt archive, media Anki refused, a resource that failed to load —
         * is not a receipt and belongs on screen.
         *
         * `test_android_localization.py` renders each vendored literal through the real engine i18n
         * helpers and fails closed if a pattern stops matching, so an `engine.lock` re-pin that
         * reworded one cannot silently put it back on screen.
         */
        val RECEIPTS =
            listOf(
                // EpisodeProcessor: a headword whose dictionary row carries more than one attested
                // reading. The engine declines to guess the homograph and keeps the reading it has.
                Regex("""\d+ word\(s\) have more than one reading — the parsed reading was kept\."""),
                // EpisodeProcessor: Anki rejected same-Expression notes. The result card's own
                // `Skipped / new` metric already counts them.
                Regex("""Skipped \d+ word\(s\) Anki flagged as duplicates \(same Expression\)"""),
                // EpisodeProcessor: a fact about the bundled ffmpeg build, identical on every device
                // and every run. The animated screenshot is still produced.
                Regex("""Using WebP for animated screenshots — this ffmpeg build has no AVIF encoder\."""),
                // Reading loaders, via the document.warnings pass-through. A text-only mokuro volume
                // and a gaiji image carrying no text are both the normal shape of those files.
                Regex("""text-only volume: pages have no paired images"""),
                Regex("""page \d+: no image matched .*""", RegexOption.DOT_MATCHES_ALL),
                Regex("""Skipped \d+ inline image\(s\) \(gaiji\) that carried no text\."""),
            )
    }
}
