package com.ankiminer.android.media

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ankiminer.android.PythonInstrumentationRuntime
import com.ankiminer.android.debug.S3TestDocumentsProvider
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The shipped ffmpeg can actually produce an animated screenshot, in both formats.
 *
 * The JVM tests assert the argv the engine builds; nothing asserted that `libaom-av1` and
 * `libwebp_anim` are compiled into the committed binaries and can write a file. A rebuild that
 * dropped either encoder would pass every other gate — `assert-ffmpeg-config.py` runs at build time
 * against a config header, not against the artifact that ends up in the APK.
 *
 * Runs the engine's own `_extract_animated_screenshot`, so the encoder probe and the argument
 * construction are exercised alongside the binary rather than re-stated here.
 */
@RunWith(AndroidJUnit4::class)
class AnimatedScreenshotEncodeInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun webpAnimatedScreenshotEncodesToARiffContainer() {
        val result = encode("webp")

        assertTrue("libwebp_anim did not produce a clip", result.getBoolean("encoded"))
        assertTrue(result.getLong("bytes") > 0)
        // RIFF....WEBP
        val header = result.getString("header")
        assertEquals("52494646", header.substring(0, 8))
        assertEquals("57454250", header.substring(16, 24))
    }

    @Test
    fun avifAnimatedScreenshotEncodesToAnIsobmffContainer() {
        // Device-independent: whether a `.avif` can be *named* varies by API level, but whether the
        // binary can *encode* one does not.
        val result = encode("avif")

        assertTrue("libaom-av1 did not produce a clip", result.getBoolean("encoded"))
        assertTrue(result.getLong("bytes") > 0)
        // ....ftyp
        assertEquals("66747970", result.getString("header").substring(8, 16))
    }

    private fun encode(format: String): JSONObject {
        val fixture = createFixture()
        val output = File(context.cacheDir, "animated-encode-$format").resetDirectory()
        return JSONObject(
            pythonModule()
                .callAttr(
                    "encode_animated_clip",
                    nativeTool("libffmpeg.so").path,
                    fixture.path,
                    output.path,
                    format,
                ).toString(),
        )
    }

    private fun createFixture(): File {
        val fixture = File(context.cacheDir, S3TestDocumentsProvider.FIXTURE_NAME)
        if (!fixture.isFile) {
            pythonModule().callAttr("create_fixture", nativeTool("libffmpeg.so").path, fixture.path)
        }
        assertTrue(fixture.isFile)
        return fixture
    }

    private fun pythonModule() =
        synchronized(PythonInstrumentationRuntime::class.java) {
            PythonInstrumentationRuntime.awaitReady().getModule("s3_media_probe")
        }

    private fun nativeTool(name: String): File {
        val tool = File(context.applicationInfo.nativeLibraryDir, name)
        assertTrue("Native tool is missing: $tool", tool.isFile)
        assertTrue("Native tool is not executable: $tool", tool.canExecute())
        return tool
    }

    private fun File.resetDirectory(): File {
        deleteRecursively()
        check(mkdirs()) { "Could not create $this" }
        return this
    }
}
