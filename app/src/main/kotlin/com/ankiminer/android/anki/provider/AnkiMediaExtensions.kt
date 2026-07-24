package com.ankiminer.android.anki.provider

import com.ankiminer.android.anki.protocol.MediaKind

/**
 * Single source of truth for the media file extension carried on a staged private copy, and for
 * the staging path regex that guards it.
 *
 * AnkiDroid derives the extension of a stored media file purely from `ContentResolver.getType()`
 * on the content:// URI it is handed; it accepts no MIME on the insert. Naming the staged copy
 * after its real extension lets the stock FileProvider report the correct MIME so AnkiDroid stops
 * defaulting unknown `.stage` files to `.bin`.
 *
 * Kept pure (no Android imports) so the whole extension -> path chain is JVM-unit-testable. The
 * on-device `resolveDestination` regex is not exercised by CI, so it MUST be built from
 * [STAGED_PATH_REGEX] here rather than re-listing the extensions, or the two can silently drift and
 * a mismatch fails the asset outright instead of degrading gracefully.
 */
internal object AnkiMediaExtensions {
    /** Named after an unrecognized format, and the shape crash-recovery of legacy records expects. */
    const val STAGE_FALLBACK_EXTENSION = "stage"

    // Exactly what the engine emits: audio is mp3 (default) or opus (opt-in); images are jpg/png
    // with jpeg/webp tolerated. A broader allowlist would only enlarge a security-sensitive path
    // regex with formats that never occur (and some reverse-map to the wrong extension on old APIs).
    private val AUDIO_EXTENSIONS = linkedSetOf("mp3", "opus")
    private val IMAGE_EXTENSIONS = linkedSetOf("jpg", "jpeg", "png", "webp")

    /** Every real media extension, ordered for a stable regex alternation. */
    val ALLOWED_EXTENSIONS: List<String> = (AUDIO_EXTENSIONS + IMAGE_EXTENSIONS).toList()

    /**
     * The exact shape a staged relative path may take. The 64-hex token is the identity; the
     * extension only carries the media type. [STAGE_FALLBACK_EXTENSION] stays in the alternation so
     * an unrecognized format and legacy pre-fix `.stage` records both remain valid.
     */
    val STAGED_PATH_REGEX: Regex =
        Regex("v1/[0-9a-f]{64}\\.(" + (ALLOWED_EXTENSIONS + STAGE_FALLBACK_EXTENSION).joinToString("|") + ")")

    /**
     * The extension to name a staged copy after, or null when [requestedFilename] has no recognized
     * suffix for its [mediaKind]. A null return means the caller falls back to
     * [STAGE_FALLBACK_EXTENSION] (today's safe behavior for anything unexpected).
     */
    fun sanitizedExtension(
        requestedFilename: String,
        mediaKind: MediaKind,
    ): String? {
        val dot = requestedFilename.lastIndexOf('.')
        if (dot <= 0 || dot == requestedFilename.lastIndex) return null
        val candidate = requestedFilename.substring(dot + 1).lowercase()
        val allowed =
            when (mediaKind) {
                MediaKind.AUDIO -> AUDIO_EXTENSIONS
                MediaKind.IMAGE -> IMAGE_EXTENSIONS
            }
        return candidate.takeIf { it in allowed }
    }
}
