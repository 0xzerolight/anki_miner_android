package com.ankiminer.android.anki.protocol

import com.ankiminer.android.anki.generated.UnicodeContractV151

/**
 * The code-point rule for Anki deck and note-type names, the two identities a user picks from
 * AnkiDroid's own lists. U+200C ZWNJ and U+200D ZWJ are ordinary there (Persian words, emoji
 * sequences), so they are allowed. Every other category-C code point (bidi marks, embeddings and
 * overrides, the BOM, controls, private use, unassigned) stays refused, and field names, template
 * names, media filenames and marker fields keep the plain category-C rule (AU-020).
 * Python mirror: `android_bridge.config_map.refuses_target_name_code_point`.
 */
internal object AnkiTargetNames {
    private const val ZWNJ = 0x200C
    private const val ZWJ = 0x200D

    fun refuses(codePoint: Int): Boolean =
        UnicodeContractV151.isCategoryC(codePoint) && codePoint != ZWNJ && codePoint != ZWJ

    /** Whether [value] holds a refused code point. Callers reject malformed UTF-16 first. */
    fun containsRefused(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val codePoint = Character.codePointAt(value, index)
            if (refuses(codePoint)) return true
            index += Character.charCount(codePoint)
        }
        return false
    }
}
