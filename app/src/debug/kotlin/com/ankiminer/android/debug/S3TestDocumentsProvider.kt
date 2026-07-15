package com.ankiminer.android.debug

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException
import kotlin.concurrent.thread

/** Debug-only provider exposing the same MKV as a seekable file and a non-seekable pipe. */
class S3TestDocumentsProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor {
        if (mode != "r") {
            throw FileNotFoundException("S3 fixture is read-only")
        }
        val fixture = File(requireNotNull(context).cacheDir, FIXTURE_NAME)
        if (!fixture.isFile) {
            throw FileNotFoundException("S3 fixture has not been generated")
        }
        return when (uri.path) {
            "/seekable" -> ParcelFileDescriptor.open(fixture, ParcelFileDescriptor.MODE_READ_ONLY)
            "/pipe" -> pipeFrom(fixture)
            else -> throw FileNotFoundException("Unknown S3 fixture URI: $uri")
        }
    }

    private fun pipeFrom(source: File): ParcelFileDescriptor {
        val (readSide, writeSide) = ParcelFileDescriptor.createPipe()
        thread(name = "s3-nonseekable-provider", isDaemon = true) {
            try {
                runCatching {
                    ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { output ->
                        source.inputStream().use { input -> input.copyTo(output) }
                    }
                }
            } finally {
                runCatching { writeSide.close() }
            }
        }
        return readSide
    }

    override fun getType(uri: Uri): String = "video/x-matroska"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        const val FIXTURE_NAME = "s3-saf-fixture.mkv"
    }
}
