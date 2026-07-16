package com.ankiminer.android.debug.s2

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

/** Probe-only URI source used to exercise AnkiDroid's real addMediaFromUri path. */
class S2ProbeMediaProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String =
        when (validatedName(uri).substringAfterLast('.')) {
            "mp3" -> "audio/mpeg"
            "webp" -> "image/webp"
            else -> error("unsupported S2 probe media type")
        }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r") { "S2 probe media is read-only" }
        return ParcelFileDescriptor.open(
            fileFor(validatedName(uri)),
            ParcelFileDescriptor.MODE_READ_ONLY,
        )
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("S2 probe media is read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("S2 probe media is read-only")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("S2 probe media is read-only")

    private fun validatedName(uri: Uri): String {
        require(uri.pathSegments.size == 1) { "invalid S2 probe media URI" }
        val name = uri.pathSegments.single()
        require(NAME.matches(name)) { "invalid S2 probe media name" }
        return name
    }

    private fun fileFor(name: String): File {
        val root = File(requireNotNull(context).cacheDir, ROOT).canonicalFile
        val file = File(root, name).canonicalFile
        require(file.parentFile == root && file.isFile) { "S2 probe media is missing" }
        return file
    }

    companion object {
        const val AUTHORITY = "com.ankiminer.android.s2.provider"
        const val ROOT = "s2-provider-media"
        private val NAME = Regex("[a-z0-9_]{1,80}\\.(?:mp3|webp)")
    }
}
