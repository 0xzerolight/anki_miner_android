package com.ankiminer.android.anki.provider

import com.ankiminer.android.anki.protocol.MediaKind

/**
 * Single source of truth for the media file extension carried on a staged private copy, and for
 * the staging path regex that guards it.
 *
 * AnkiDroid derives the extension of a stored media file purely from `ContentResolver.getType()`
 * on the content:// URI it is handed; it accepts no MIME on the insert. The staged copy's extension
 * is therefore the key [AnkiMediaFileProvider] looks up to answer that call — it no longer feeds the
 * platform's MimeTypeMap directly — and it is half of the staged-path identity.
 *
 * Kept pure (no Android imports) so the whole extension -> path chain is JVM-unit-testable. The
 * on-device `resolveDestination` regex is not exercised by CI, so it MUST be built from
 * [STAGED_PATH_REGEX] here rather than re-listing the extensions, or the two can silently drift and
 * a mismatch fails the asset outright instead of degrading gracefully.
 */
internal object AnkiMediaExtensions {
    /** Named after an unrecognized format, and the shape crash-recovery of legacy records expects. */
    const val STAGE_FALLBACK_EXTENSION = "stage"

    // Union of every producer that can hand this app a media file: downloaded expression audio
    // (audio_fetch_common.AUDIO_MEDIA_TYPE_EXTENSIONS, chosen from the response Content-Type), local
    // audio packs (audio_packs.formats.AUDIO_EXTENSIONS), Android offline TTS (.wav, see
    // CachedSentenceAudioSynthesizer), and Yomitan dictionary media
    // (yomitan_importer._MEDIA_EXTENSION_WHITELIST, which arrives as MediaKind.IMAGE). Kept honest by
    // test_media_extension_allowlist.py and the API 26 round-trip gate in
    // AndroidAnkiMediaStagingInstrumentedTest. An extension missing here is not rejected — it is
    // staged as ".stage", which AnkiDroid stores as ".bin".
    private val AUDIO_EXTENSIONS =
        linkedSetOf("mp3", "opus", "ogg", "oga", "aac", "m4a", "mp4", "wav", "flac", "webm")
    private val IMAGE_EXTENSIONS =
        linkedSetOf(
            "apng", "avif", "bmp", "gif", "ico", "cur", "jpg", "jpeg",
            "jfif", "pjpeg", "pjp", "png", "svg", "tif", "tiff", "webp",
        )

    /**
     * Extensions whose usability is a property of the device, asked at runtime rather than decided
     * at compile time. A member keeps its real extension where the device MIME predicate answers
     * true, and degrades to [STAGE_FALLBACK_EXTENSION] where it answers false.
     *
     * Image-only by construction: every audio producer extension reverse-maps at API 26
     * (aac->audio/aac, flac->audio/flac, wav->audio/x-wav, mp4->video/mp4, webm->video/webm,
     * m4a->audio/mpeg, ogg/oga/opus->application/ogg). `opus` is never eligible regardless of what a
     * device reports: API 26 carries no `opus` extension at all, and excluding it would reintroduce
     * Issue #2 for the exact format local-audio-yomichan's default collection ships.
     *
     * `apng` is unmapped at API 26 and API 36 alike but is NOT here: an APNG is a valid PNG stream,
     * so [AnkiMediaFileProvider] names it `image/png` and AnkiDroid stores a `.png` that renders. An
     * extension belongs here only when no compatible MIME exists for it and the answer varies by
     * platform version.
     *
     * Entries move in and out ONLY on evidence from the instrumented gate, with the measured value
     * recorded alongside. Measured 2026-08-03: `avif` is null in both directions on API 26 and
     * resolves `image/avif` -> `avif` on API 36. The previous compile-time exclusion therefore
     * stored `.bin` even on devices that could hold a real `.avif` (audit finding AM-127), and
     * animated screenshots need the working case.
     */
    internal val DEVICE_CONDITIONAL_EXTENSIONS: Set<String> =
        linkedSetOf(
            "avif",
        )

    /**
     * Every real media extension, ordered for a stable regex alternation. Device-conditional
     * extensions are included: staging must be able to name a copy `.avif` on a device that can
     * hold one, and [AnkiMediaStaging] rejects any extension outside this list outright.
     */
    val ALLOWED_EXTENSIONS: List<String> = (AUDIO_EXTENSIONS + IMAGE_EXTENSIONS).toList()

    /**
     * The exact shape a staged relative path may take. The 64-hex token is the identity; the
     * extension only carries the media type. [STAGE_FALLBACK_EXTENSION] stays in the alternation as
     * defense-in-depth for a format no producer is known to emit, and because crash recovery of
     * records staged before v0.1.8 still resolves through it. (It is NOT there because packs can ship
     * arbitrary suffixes — local_resources.py fails the whole import for a suffix outside the
     * vendored pack format list, so every producer set is closed and enumerable.)
     */
    val STAGED_PATH_REGEX: Regex =
        Regex("v1/[0-9a-f]{64}\\.(" + (ALLOWED_EXTENSIONS + STAGE_FALLBACK_EXTENSION).joinToString("|") + ")")

    /**
     * The extension to name a staged copy after, or null when [requestedFilename] has no recognized
     * suffix for its [mediaKind], or carries a [DEVICE_CONDITIONAL_EXTENSIONS] member this device
     * cannot name. A null return means the caller falls back to [STAGE_FALLBACK_EXTENSION] (the safe
     * behavior for anything unexpected).
     *
     * The allowlist check comes first so an unknown suffix never reaches the platform MIME table.
     */
    fun sanitizedExtension(
        requestedFilename: String,
        mediaKind: MediaKind,
        canNameFilesFor: (String) -> Boolean,
    ): String? {
        val dot = requestedFilename.lastIndexOf('.')
        if (dot <= 0 || dot == requestedFilename.lastIndex) return null
        val candidate = requestedFilename.substring(dot + 1).lowercase()
        val allowed =
            when (mediaKind) {
                MediaKind.AUDIO -> AUDIO_EXTENSIONS
                MediaKind.IMAGE -> IMAGE_EXTENSIONS
            }
        if (candidate !in allowed) return null
        if (candidate in DEVICE_CONDITIONAL_EXTENSIONS && !canNameFilesFor(candidate)) return null
        return candidate
    }
}
