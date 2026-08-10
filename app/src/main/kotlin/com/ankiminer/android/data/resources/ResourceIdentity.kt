package com.ankiminer.android.data.resources

import com.ankiminer.android.anki.generated.UnicodeContractV151
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

/**
 * On-disk identities for locally imported resources.
 *
 * The id is a real key, not a label: it is the directory name under the resource root, the key the
 * inventory round-trips, the entry persisted in the priority chain, and the id the engine looks the
 * source up by. It is also not something a user should have to invent.
 *
 * Name derivation is pure for local resources. Frequency and pitch collisions resolve to the
 * existing id so their chain entries survive. Custom dictionaries differ: their base id comes from
 * archive metadata, a new import takes the next free slot, and a row-scoped replacement pins the
 * occupied id explicitly.
 */
internal object ResourceIdentity {
    /**
     * Cap well under the 64-char contract so the digest fallback and any future suffix still fit.
     */
    private const val MAX_SLUG_LENGTH = 40

    private const val DIGEST_LENGTH = 10

    private const val MAX_SLOT_LENGTH = 64

    private val COMBINING_MARKS = Regex("\\p{Mn}+")

    /**
     * A lowercase, hyphen-separated id for [displayName], falling back to
     * `<fallbackPrefix>-<digest>` when the name has no ASCII alphanumerics at all.
     *
     * Satisfies the engine's id contract by construction: the output alphabet excludes `.` and `_`
     * so `..` is impossible, a hyphen is only appended after a non-hyphen, index 0 is always
     * alphanumeric, and the trailing trim runs after truncation.
     */
    fun derive(
        displayName: String,
        fallbackPrefix: String,
    ): String {
        val name = canonicalDisplayName(displayName)
        // Locale.ROOT, never the default locale: in Turkish, "I".lowercase() is "ı", which would
        // give the same name two different ids depending on the phone's language.
        val folded =
            COMBINING_MARKS
                .replace(Normalizer.normalize(name, Normalizer.Form.NFKD), "")
                .lowercase(Locale.ROOT)
        val slug = StringBuilder()
        folded.forEach { character ->
            when {
                character in 'a'..'z' || character in '0'..'9' -> slug.append(character)
                slug.isNotEmpty() && slug.last() != '-' -> slug.append('-')
                else -> Unit
            }
        }
        val trimmed = slug.toString().take(MAX_SLUG_LENGTH).trimEnd('-')
        if (trimmed.isNotEmpty()) return trimmed
        // Digest pinned NFC, not platform-normalized or raw text. Canonically equivalent names
        // must own one slot, and the digest must not shift with platform Unicode tables.
        return "$fallbackPrefix-${digest(name)}"
    }

    /**
     * Where a frequency import would land, and what it would replace.
     *
     * Matches on id first, then on display name. The name branch is what keeps an install made
     * before ids were derived reachable: it lives under an id the current derivation would never
     * produce, so without it a re-import would orphan the old directory and its chain entry
     * instead of replacing it.
     */
    fun frequencyTarget(
        displayName: String,
        installed: List<InstalledFrequencySource>,
    ): ResourceImportTarget {
        val derived = derive(displayName, "frequency")
        val canonicalName = canonicalDisplayName(displayName)
        val match =
            installed.firstOrNull { it.sourceId == derived }
                ?: installed.firstOrNull {
                    canonicalDisplayName(it.sourceName).equals(canonicalName, ignoreCase = true)
                }
        return ResourceImportTarget(
            identity = match?.sourceId ?: derived,
            installedName = match?.sourceName,
        )
    }

    /** Audio-pack ids come from the engine preflight; this only resolves installed collisions. */
    fun audioPackTarget(
        packId: String,
        installed: List<InstalledAudioPack>,
    ): ResourceImportTarget {
        val match = installed.firstOrNull { it.packId == packId }
        return ResourceImportTarget(identity = packId, installedName = match?.sourceName)
    }

    /** Allocate the desktop-derived slot, adding the first free numeric suffix when occupied. */
    fun customDictionaryTarget(
        derivedSlotId: String,
        installed: List<InstalledDictionary>,
    ): ResourceImportTarget {
        val occupied = installed.filter(InstalledDictionary::occupied).mapTo(mutableSetOf()) { it.slotId }
        if (derivedSlotId !in occupied) return ResourceImportTarget(derivedSlotId, installedName = null)
        var suffix = 2
        while (true) {
            val tail = "-$suffix"
            val base =
                derivedSlotId
                    .take(MAX_SLOT_LENGTH - tail.length)
                    .trimEnd('-', '.', '_')
            val candidate = base + tail
            if (candidate !in occupied) return ResourceImportTarget(candidate, installedName = null)
            suffix += 1
        }
    }

    /** Replacement is row-scoped, so even an unusable dictionary keeps its exact occupied slot. */
    fun customDictionaryReplacementTarget(
        slotId: String,
        installed: List<InstalledDictionary>,
    ): ResourceImportTarget? {
        val match = installed.firstOrNull { it.occupied && it.slotId == slotId } ?: return null
        return ResourceImportTarget(identity = match.slotId, installedName = match.sourceName)
    }

    /**
     * Where a pitch import would land, and what it would replace.
     *
     * Pitch became a chain of per-source slots with the engine re-pin, so it now
     * derives an id from the display name exactly like frequency; it is no longer
     * a single unnamed file that anything installed collides with.
     */
    fun pitchTarget(
        displayName: String,
        installed: List<InstalledPitchSource>,
    ): ResourceImportTarget {
        val derived = derive(displayName, "pitch")
        val canonicalName = canonicalDisplayName(displayName)
        val match =
            installed.firstOrNull { it.sourceId == derived }
                ?: installed.firstOrNull {
                    canonicalDisplayName(it.sourceName).equals(canonicalName, ignoreCase = true)
                }
        return ResourceImportTarget(
            identity = match?.sourceId ?: derived,
            installedName = match?.sourceName,
        )
    }

    private fun canonicalDisplayName(value: String): String {
        val trimmed = value.trim()
        return UnicodeContractV151.normalizeNfc(trimmed) ?: trimmed
    }

    private fun digest(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(DIGEST_LENGTH)
        bytes.take((DIGEST_LENGTH + 1) / 2).forEach { byte ->
            val unsigned = byte.toInt() and 0xFF
            hex.append(HEX_DIGITS[unsigned ushr 4])
            hex.append(HEX_DIGITS[unsigned and 0x0F])
        }
        return hex.take(DIGEST_LENGTH).toString()
    }

    private val HEX_DIGITS = "0123456789abcdef".toCharArray()
}

/**
 * The id an import will actually be written under, plus the name of whatever it would replace.
 *
 * [installedName] is null when nothing collides. When it is non-null the caller must confirm before
 * dispatching, and must send [identity] — which may be an existing id rather than the derived one.
 */
internal data class ResourceImportTarget(
    val identity: String,
    val installedName: String?,
) {
    val collides: Boolean get() = installedName != null
}
