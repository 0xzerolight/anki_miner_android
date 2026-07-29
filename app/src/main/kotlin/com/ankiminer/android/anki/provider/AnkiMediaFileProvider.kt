package com.ankiminer.android.anki.provider

import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider

/**
 * The `FileProvider` behind the `.anki-media` authority, answering `getType` from an extension table
 * we control instead of leaving it entirely to the device.
 *
 * AnkiDroid accepts no MIME on its media insert: it calls `ContentResolver.getType()` on the URI it
 * is handed and names the stored file after `MimeTypeMap.getExtensionFromMimeType()` of the result.
 * Stock [FileProvider] answers `getType` with `getMimeTypeFromExtension(ext)` and falls back to
 * `application/octet-stream`, which AnkiDroid maps to `.bin` — so any extension missing from the
 * device's table produces an unplayable file. API 26 has no `opus` entry at all, so that is a live
 * gap at this app's `minSdk`, independent of the staged-name fix in [AnkiMediaExtensions].
 *
 * Two rules keep this from regressing the formats that already work:
 *
 * * **The stock lookup always wins where it answers.** [FILL] is consulted only when
 *   `getMimeTypeFromExtension` returns null, so on any API level where the platform knows an
 *   extension we return exactly what stock [FileProvider] would have.
 * * **Every value must reverse-map.** `getExtensionFromMimeType(value)` has to be non-null or
 *   AnkiDroid cannot name the file. An extension for which no value satisfies that belongs in
 *   [AnkiMediaExtensions.DEVICE_UNMAPPABLE_EXTENSIONS], not here.
 *
 * Both rules are asserted per extension on the API 26 lane by
 * `AndroidAnkiMediaStagingInstrumentedTest`.
 */
class AnkiMediaFileProvider : FileProvider() {
    override fun getType(uri: Uri): String? = resolveType(uri) ?: super.getType(uri)

    /**
     * Androidx hard-codes `application/octet-stream` here, and API 34+ routes `getType` to this
     * method whenever the caller lacks a URI grant. Answering it from the same table removes a
     * silent path back to `.bin`.
     */
    override fun getTypeAnonymous(uri: Uri): String? = resolveType(uri) ?: super.getTypeAnonymous(uri)

    private fun resolveType(uri: Uri): String? {
        val name = uri.lastPathSegment ?: return null
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.lastIndex) return null
        val extension = name.substring(dot + 1).lowercase()
        if (extension !in AnkiMediaExtensions.ALLOWED_EXTENSIONS) return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: FILL[extension]
    }

    private companion object {
        /**
         * MIME values for extensions the platform table can miss. Each is chosen because it reverse-
         * maps on API 26, not because it is the most precise label:
         *
         * * `opus` — API 26 registers no `opus` extension and no `audio/ogg` type; only
         *   `application/ogg`, listed first against `ogg`. `audio/opus` reverse-maps to null there,
         *   so AnkiDroid would be handed a MIME it cannot name. API 29+ resolves `opus` itself and
         *   never reaches this map.
         * * `jfif`/`pjpeg`/`pjp` — JPEG aliases Yomitan dictionaries may ship that no Android
         *   release registers; `image/jpeg` reverse-maps to `jpg`.
         * * `apng` — API 26 registers no `apng` extension and `image/apng` reverse-maps to null. An
         *   APNG *is* a PNG stream, so `image/png` is a truthful label here rather than a lossy one:
         *   AnkiDroid stores the bytes unchanged as `.png`, and a viewer that understands APNG
         *   animates it while one that does not shows the first frame.
         */
        private val FILL =
            mapOf(
                "opus" to "application/ogg",
                "jfif" to "image/jpeg",
                "pjpeg" to "image/jpeg",
                "pjp" to "image/jpeg",
                "apng" to "image/png",
            )
    }
}
