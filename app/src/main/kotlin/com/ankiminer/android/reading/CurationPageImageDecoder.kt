package com.ankiminer.android.reading

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.ankiminer.android.diagnostics.log.AppLog
import com.ankiminer.android.diagnostics.log.LogComponent
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/** Desktop `page_image_view.py` parity: a single zip member this large is refused outright. */
private const val MAX_MEMBER_BYTES = 64L * 1024 * 1024

/** Worst decoded bitmap is ~905x1280x4 bytes ~= 4.6 MB, safe on the CI emulator. */
private const val MAX_LONG_EDGE_PX = 1280

private const val READ_CHUNK_BYTES = 8 * 1024

/**
 * Decodes one manga page image out of a staged mokuro companion `.cbz`/`.zip`, downsampled to a
 * bounded long edge. Never extracts the member to disk and never trusts the zip's declared entry
 * size; every failure path — missing entry, oversized or lying declared size, unreadable or
 * unbounded bitmap, archive vanishing mid-decode (a run-teardown cancel race) — is just `null`.
 */
class CurationPageImageDecoder {
    /**
     * [originalWidth]/[originalHeight] are the pre-downsample page pixel dimensions. mokuro's
     * `blockBox` coordinates are in that space, so the renderer must rescale by
     * `bitmap.width / originalWidth.toFloat()` using these actual decoded dimensions —
     * [computeInSampleSize] rounds, so the requested sample factor cannot be trusted instead.
     */
    data class DecodedPageImage(
        val bitmap: Bitmap,
        val originalWidth: Int,
        val originalHeight: Int,
    )

    fun decode(
        archivePath: String,
        entryName: String,
    ): DecodedPageImage? =
        try {
            decodeOrThrow(archivePath, entryName)
        } catch (failure: Exception) {
            AppLog.w(
                LogComponent.READING,
                "page_image.decode",
                failure,
                "outcome" to "page_image_decode_failed",
                "entry" to entryName,
            )
            null
        }

    private fun decodeOrThrow(
        archivePath: String,
        entryName: String,
    ): DecodedPageImage {
        val bytes =
            readBoundedZipEntry(File(archivePath), entryName, MAX_MEMBER_BYTES)
                ?: throw PageImageRejected("entry_missing_or_over_cap")

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) throw PageImageRejected("invalid_bounds")

        val decodeOptions =
            BitmapFactory.Options().apply {
                inSampleSize = computeInSampleSize(width, height, MAX_LONG_EDGE_PX)
            }
        val bitmap =
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
                ?: throw PageImageRejected("bitmap_decode_failed")

        return DecodedPageImage(bitmap, width, height)
    }
}

/** Carries a reason string to the single [AppLog] site in [CurationPageImageDecoder.decode]. */
private class PageImageRejected(reason: String) : Exception(reason)

/**
 * Smallest power-of-two sample factor that brings the long edge at or under [maxLongEdge].
 * `inSampleSize` rounds down to a power of two internally regardless of what is requested, so
 * this mirrors that rounding rather than computing an exact ratio.
 */
internal fun computeInSampleSize(
    width: Int,
    height: Int,
    maxLongEdge: Int,
): Int {
    val longEdge = maxOf(width, height)
    var inSampleSize = 1
    while (longEdge / inSampleSize > maxLongEdge) {
        inSampleSize *= 2
    }
    return inSampleSize
}

/**
 * Looks up [entryName] in [archive] by plain string match — no path resolution, so a
 * traversal-shaped name is just a lookup miss, not a zip-slip surface — and reads it fully,
 * refusing anything the declared or actual size puts over [byteCap].
 *
 * The declared [java.util.zip.ZipEntry.getSize] is untrusted: a deflated entry's declared
 * uncompressed size does not bound what actually comes out of the inflater, so the read itself
 * is bounded through a counting loop rather than trusting the declared size alone.
 */
internal fun readBoundedZipEntry(
    archive: File,
    entryName: String,
    byteCap: Long,
): ByteArray? =
    try {
        ZipFile(archive).use { zip ->
            val entry = zip.getEntry(entryName) ?: return@use null
            if (entry.size < 0 || entry.size > byteCap) return@use null
            zip.getInputStream(entry).use { input -> readBounded(input, byteCap) }
        }
        // instrumentation: silent — archive can vanish mid-decode on a run-teardown cancel race; same as a missing entry
    } catch (_: Exception) {
        null
    }

private fun readBounded(
    input: InputStream,
    byteCap: Long,
): ByteArray? {
    val output = ByteArrayOutputStream()
    val chunk = ByteArray(READ_CHUNK_BYTES)
    var total = 0L
    while (true) {
        val read = input.read(chunk)
        if (read < 0) break
        total += read
        if (total > byteCap) return null
        output.write(chunk, 0, read)
    }
    return output.toByteArray()
}
