package com.ankiminer.android.debug

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException
import kotlin.concurrent.thread

/** Debug-only provider exposing deterministic SAF fixtures without filesystem-path shortcuts. */
class S3TestDocumentsProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor {
        if (mode != "r") {
            throw FileNotFoundException("S3 fixture is read-only")
        }
        val fixtureName =
            when (uri.path) {
                "/seekable", "/pipe" -> FIXTURE_NAME
                "/s5/video" -> S5_VIDEO_FIXTURE_NAME
                "/s5/subtitle" -> S5_SUBTITLE_FIXTURE_NAME
                else -> throw FileNotFoundException("Unknown test fixture URI: $uri")
            }
        val fixture = File(requireNotNull(context).cacheDir, fixtureName)
        if (!fixture.isFile) {
            throw FileNotFoundException("S3 fixture has not been generated")
        }
        return when (uri.path) {
            "/seekable" -> ParcelFileDescriptor.open(fixture, ParcelFileDescriptor.MODE_READ_ONLY)
            "/pipe" -> pipeFrom(fixture)
            "/s5/video", "/s5/subtitle" ->
                ParcelFileDescriptor.open(fixture, ParcelFileDescriptor.MODE_READ_ONLY)
            else -> throw FileNotFoundException("Unknown test fixture URI: $uri")
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

    override fun getType(uri: Uri): String =
        when (uri.path) {
            "/s5/subtitle" -> "application/x-subrip"
            else -> "video/x-matroska"
        }

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
        const val S5_VIDEO_FIXTURE_NAME = "s5-saf-fixture.mkv"
        const val S5_SUBTITLE_FIXTURE_NAME = "s5-saf-fixture.srt"
    }
}
