package com.ankiminer.android.reading

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `decode()` needs a real `BitmapFactory`: the JVM unit test build's `android.jar` stub throws on
 * every call, so its coverage lives here instead of [CurationPageImageDecoderTest] under `test/`.
 */
@RunWith(AndroidJUnit4::class)
class CurationPageImageDecoderTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun decodesAStagedPageDownsampledBelowTheLongEdgeCap() {
        val originalWidth = 2000
        val originalHeight = 100
        val archive = File(context.cacheDir, "curation-page-image-decoder-test.cbz")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("page1.png"))
            zip.write(renderPng(originalWidth, originalHeight))
            zip.closeEntry()
        }

        try {
            val result = CurationPageImageDecoder().decode(archive.path, "page1.png")

            assertNotNull(result)
            checkNotNull(result)
            assertEquals(originalWidth, result.originalWidth)
            assertEquals(originalHeight, result.originalHeight)
            assertTrue(maxOf(result.bitmap.width, result.bitmap.height) <= MAX_LONG_EDGE_PX)
        } finally {
            archive.delete()
        }
    }

    private fun renderPng(
        width: Int,
        height: Int,
    ): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.RED)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    private companion object {
        const val MAX_LONG_EDGE_PX = 1280
    }
}
