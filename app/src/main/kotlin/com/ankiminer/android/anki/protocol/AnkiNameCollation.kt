package com.ankiminer.android.anki.protocol

import java.text.Normalizer
import java.util.Locale

/**
 * Anki's own equality for tags. A readback must compare through this, not with String equality,
 * because AnkiDroid stores the spelling Anki resolves, not the one it was sent.
 *
 * Anki keeps tags in a `COLLATE unicase` column (rslib `storage/upgrades/schema17_upgrade.sql`),
 * compared with the `unicase` crate's full Unicode case folding (CaseFolding.txt statuses C and
 * F), and canonifies every tag on save (rslib `tags/register.rs` `canonify_tags_inner`): split on
 * ' ' and U+3000; per "::" component NFC, drop ASCII controls, trim whitespace, empty becomes
 * "blank"; an existing tag or parent lends its spelling; case-insensitive duplicates collapse.
 */
internal object AnkiNameCollation {
    fun sameTagSet(
        left: Iterable<String>,
        right: Iterable<String>,
    ): Boolean = tagKeys(left) == tagKeys(right)

    private fun tagKeys(tags: Iterable<String>): Set<String> =
        tags.asSequence()
            .flatMap { it.split(' ', IDEOGRAPHIC_SPACE).asSequence() }
            .filter(String::isNotEmpty)
            .map { tag ->
                // Anki compares the rejoined "::" string, so the key keeps that joiner.
                tag.split(COMPONENT_SEPARATOR).joinToString(COMPONENT_SEPARATOR) { component ->
                    foldCase(normalizedComponent(component, trimColons = false))
                }
            }.toSet()

    private fun normalizedComponent(
        raw: String,
        trimColons: Boolean,
    ): String =
        Normalizer.normalize(raw, Normalizer.Form.NFC)
            .filterNot { it < ' ' || it == '\u007f' }
            .trim { it.isWhitespace() || it == NEXT_LINE || (trimColons && it == ':') }
            .ifEmpty { BLANK }

    /**
     * Full case folding as `unicase` applies it. Upper- then lower-casing each code point twice
     * reproduces CaseFolding.txt C+F for every code point the runtime knows (the second round
     * takes U+1E9E through U+00DF to "ss"). U+0131 folds to itself, but upper-casing would turn
     * it into I, so it is kept as is.
     */
    private fun foldCase(value: String): String {
        if (value.all { it < '\u0080' }) return value.lowercase(Locale.ROOT)
        val folded = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            index += Character.charCount(codePoint)
            if (codePoint == DOTLESS_I) {
                folded.appendCodePoint(codePoint)
            } else {
                val once = String(Character.toChars(codePoint)).uppercase(Locale.ROOT).lowercase(Locale.ROOT)
                folded.append(once.uppercase(Locale.ROOT).lowercase(Locale.ROOT))
            }
        }
        return folded.toString()
    }

    private const val COMPONENT_SEPARATOR = "::"
    private const val IDEOGRAPHIC_SPACE = '　'
    private const val NEXT_LINE = '\u0085'
    private const val BLANK = "blank"
    private const val DOTLESS_I = 0x0131
}
