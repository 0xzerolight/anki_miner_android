package com.ankiminer.android.mining

import com.ankiminer.android.anki.generated.UnicodeContractV151

/**
 * A user-visible name (an episode's file name, a subtitle series name) reduced to the label the
 * bridge accepts: lone surrogates and category-C code points dropped, Python whitespace trimmed,
 * NFC. Every table is the pinned Unicode 15.1 contract `BridgeJsonCodec` validates with, never the
 * platform's: on a device whose ICU knows a newer Unicode, a platform table keeps a code point the
 * contract still calls unassigned, and the run fails its own self-decode (AU-059).
 */
internal fun canonicalRunLabel(raw: String): String {
    val filtered =
        buildString(raw.length) {
            var index = 0
            while (index < raw.length) {
                val codePoint = raw.codePointAt(index)
                if (
                    UnicodeContractV151.isUnicodeScalar(codePoint) &&
                    !UnicodeContractV151.isCategoryC(codePoint)
                ) {
                    appendCodePoint(codePoint)
                }
                index += Character.charCount(codePoint)
            }
        }
    var start = 0
    var end = filtered.length
    while (start < end && UnicodeContractV151.isPythonWhitespace(filtered.codePointAt(start))) {
        start += Character.charCount(filtered.codePointAt(start))
    }
    while (end > start && UnicodeContractV151.isPythonWhitespace(filtered.codePointBefore(end))) {
        end -= Character.charCount(filtered.codePointBefore(end))
    }
    // Only scalars remain, so the pinned normalizer cannot refuse the input. Trimming first is
    // safe: no code point outside Python whitespace composes into whitespace under NFC.
    return checkNotNull(UnicodeContractV151.normalizeNfc(filtered.substring(start, end)))
}
