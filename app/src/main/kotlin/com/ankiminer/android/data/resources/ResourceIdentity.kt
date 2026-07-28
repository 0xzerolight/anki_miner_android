package com.ankiminer.android.data.resources

import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

/**
 * On-disk identities for locally imported resources, derived from the name the user typed.
 *
 * The id is a real key, not a label: it is the directory name under the resource root, the key the
 * inventory round-trips, the entry persisted in the priority chain, and the id the engine looks the
 * source up by. It is also not something a user should have to invent, so it is derived here.
 *
 * Derivation is a pure function of the display name. It deliberately does NOT consult the installed
 * inventory to dodge collisions: a suffix would resolve the collision before the user could be asked
 * about it, and an inventory-dependent id is unstable, which loses the source's place in the chain
 * (see `resolveResourceChain`). Collisions are resolved by [frequencyTarget] and friends, which hand
 * the caller the existing id so a replace writes in place.
 */
internal object ResourceIdentity {
    /** Ids the engine reserves for built-in sources. */
    private const val RESERVED_AUDIO_PACK_ID = "jpod101"

    /**
     * Cap well under the 64-char contract so the digest fallback and any future suffix still fit.
     */
    private const val MAX_SLUG_LENGTH = 40

    private const val DIGEST_LENGTH = 10

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
        val name = displayName.trim()
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
        // Digest the raw name, not the normalized form, so the fallback id cannot shift when the
        // platform's Unicode tables change under us.
        return "$fallbackPrefix-${digest(name)}"
    }

    /** True when [packId] names a built-in source the engine will refuse to overwrite. */
    fun isReservedAudioPackId(packId: String): Boolean = packId == RESERVED_AUDIO_PACK_ID

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
        val match =
            installed.firstOrNull { it.sourceId == derived }
                ?: installed.firstOrNull { it.sourceName.trim().equals(displayName.trim(), ignoreCase = true) }
        return ResourceImportTarget(
            identity = match?.sourceId ?: derived,
            installedName = match?.sourceName,
        )
    }

    /** Audio packs keep an explicit id: the engine stores no display name for them to derive from. */
    fun audioPackTarget(
        packId: String,
        installed: List<InstalledAudioPack>,
    ): ResourceImportTarget {
        val match = installed.firstOrNull { it.packId == packId }
        return ResourceImportTarget(identity = packId, installedName = match?.sourceName)
    }

    /**
     * Custom dictionaries keep an explicit slot: repairing a broken slot means targeting that exact
     * slot. Matches on `occupied` rather than usability, because an unusable slot still blocks the
     * write on the Python side.
     */
    fun customDictionaryTarget(
        slotId: String,
        installed: List<InstalledDictionary>,
    ): ResourceImportTarget {
        val match = installed.firstOrNull { it.occupied && it.slotId == slotId }
        return ResourceImportTarget(identity = slotId, installedName = match?.sourceName)
    }

    /** Pitch accent is a single file with no id, so anything installed is always the collision. */
    fun pitchTarget(installed: InstalledPitchAccent?): ResourceImportTarget =
        ResourceImportTarget(identity = "pitch", installedName = installed?.sourceName)

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
