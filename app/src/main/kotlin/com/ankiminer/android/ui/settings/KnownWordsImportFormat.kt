package com.ankiminer.android.ui.settings

import androidx.annotation.StringRes
import com.ankiminer.android.R

/**
 * Format keys the engine's known-word parser reports on the wire, paired with the label the import
 * confirmation shows.
 *
 * The engine's own `FORMAT_KEYS` requires this mapping to stay in lockstep;
 * `tools/engine-sync/tests/test_known_words_format_labels_mirror.py` enforces it in both
 * directions, so an engine re-pin that adds a format fails the host job rather than shipping a raw
 * `migaku_legacy` into a dialog.
 */
internal enum class KnownWordsImportFormat(
    val wireValue: String,
    @StringRes val labelRes: Int,
) {
    JPDB("jpdb", R.string.known_words_format_jpdb),
    MIGAKU_JSON("migaku_json", R.string.known_words_format_migaku_json),
    MIGAKU_LEGACY("migaku_legacy", R.string.known_words_format_migaku_legacy),
    ANKIMORPHS("ankimorphs", R.string.known_words_format_ankimorphs),
    MIGAKU_CSV("migaku_csv", R.string.known_words_format_migaku_csv),
    GENERIC("generic", R.string.known_words_format_generic),
    ;

    companion object {
        fun forWireValue(value: String): KnownWordsImportFormat? = entries.firstOrNull { it.wireValue == value }
    }
}
