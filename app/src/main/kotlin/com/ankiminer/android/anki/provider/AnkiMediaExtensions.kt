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
     * Extensions a producer can emit that no MIME on this platform reverse-maps to, so staging them
     * under their real name would still yield `.bin` — they stay out of [ALLOWED_EXTENSIONS] and fall
     * back to [STAGE_FALLBACK_EXTENSION] instead.
     *
     * Image-only by construction: every audio producer extension reverse-maps at API 26
     * (aac->audio/aac, flac->audio/flac, wav->audio/x-wav, mp4->video/mp4, webm->video/webm,
     * m4a->audio/mpeg, ogg/oga/opus->application/ogg). `opus` is never eligible regardless of what a
     * device reports: API 26 carries no `opus` extension at all, and excluding it would reintroduce
     * Issue #2 for the exact format local-audio-yomichan's default collection ships.
     *
     * Entries move in and out ONLY on evidence from the instrumented API 26 gate, with the measured
     * value recorded alongside.
     */
    internal val DEVICE_UNMAPPABLE_EXTENSIONS: Set<String> = linkedSetOf()

    /** Every real media extension, ordered for a stable regex alternation. */
    val ALLOWED_EXTENSIONS: List<String> =
        (AUDIO_EXTENSIONS + IMAGE_EXTENSIONS).filterNot { it in DEVICE_UNMAPPABLE_EXTENSIONS }

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
     * suffix for its [mediaKind], or carries one this platform cannot express as a MIME. A null
     * return means the caller falls back to [STAGE_FALLBACK_EXTENSION] (the safe behavior for
     * anything unexpected). [DEVICE_UNMAPPABLE_EXTENSIONS] is filtered here as well as out of
     * [ALLOWED_EXTENSIONS], or staging would reject the request outright instead of degrading.
     */
    fun sanitizedExtension(
        requestedFilename: String,
        mediaKind: MediaKind,
    ): String? {
        val dot = requestedFilename.lastIndexOf('.')
        if (dot <= 0 || dot == requestedFilename.lastIndex) return null
        val candidate = requestedFilename.substring(dot + 1).lowercase()
        if (candidate in DEVICE_UNMAPPABLE_EXTENSIONS) return null
        val allowed =
            when (mediaKind) {
                MediaKind.AUDIO -> AUDIO_EXTENSIONS
                MediaKind.IMAGE -> IMAGE_EXTENSIONS
            }
        return candidate.takeIf { it in allowed }
    }
}
