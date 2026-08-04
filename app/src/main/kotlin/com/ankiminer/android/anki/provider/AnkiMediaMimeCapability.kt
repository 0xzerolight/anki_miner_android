package com.ankiminer.android.anki.provider

import android.webkit.MimeTypeMap

/**
 * Whether this device can round-trip a media extension through its own MIME table.
 *
 * AnkiDroid names a stored media file from `ContentResolver.getType()` alone, so an extension is
 * only usable when the platform maps it forward to a MIME type AND maps that MIME type back to an
 * extension. Both halves are required: a forward-only mapping still leaves AnkiDroid unable to name
 * the file, and it falls back to `.bin`.
 *
 * Measured on 2026-08-03: API 26 answers null for `avif` in both directions, API 36 answers
 * `image/avif` and maps it back to `avif`. That difference is the whole reason this is a runtime
 * predicate rather than the compile-time exclusion it replaced — the old set stored `.bin` on
 * devices that could have held a real `.avif`.
 *
 * Behind an interface so the extension -> path chain stays JVM-unit-testable, matching the
 * no-Android-imports rule [AnkiMediaExtensions] follows.
 */
interface AnkiMediaMimeCapability {
    fun canNameFilesFor(extension: String): Boolean
}

class PlatformAnkiMediaMimeCapability(
    private val mimeTypes: MimeTypeMap = MimeTypeMap.getSingleton(),
) : AnkiMediaMimeCapability {
    override fun canNameFilesFor(extension: String): Boolean {
        val mime = mimeTypes.getMimeTypeFromExtension(extension) ?: return false
        if (mime == OCTET_STREAM_MIME) return false
        return mimeTypes.getExtensionFromMimeType(mime) != null
    }

    private companion object {
        const val OCTET_STREAM_MIME = "application/octet-stream"
    }
}
