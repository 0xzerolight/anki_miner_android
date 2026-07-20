package com.ankiminer.android.media

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ankiminer.android.BuildConfig
import com.ankiminer.android.PythonInstrumentationRuntime
import com.ankiminer.android.debug.S3TestDocumentsProvider
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class S3SafFfmpegInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun safMkvCopiesToCacheProbesAndExtractsThenCleansUpAfterJob() {
        createFixture()
        val output = File(context.cacheDir, "s3-copy-output").resetDirectory()
        // The seekable provider variant is the production-realistic descriptor; since the
        // copy-always fix it must behave exactly like the pipe variant: plain cache path,
        // no /proc/self/fd, no inherited fds.
        val uri = Uri.parse("content://${BuildConfig.APPLICATION_ID}.s3.provider/seekable")
        var copiedPath = ""

        SafJobFileOwner(context).use { owner ->
            val input = owner.open(uri)
            copiedPath = input.path

            assertFalse(input.path.startsWith("/proc/self/fd/"))
            assertTrue(File(input.path).isFile)

            val result = probeAndExtract(input.path, output)
            assertEquals(
                "jpn",
                result.getJSONArray("streams").getJSONObject(0)
                    .getJSONObject("tags").getString("language"),
            )
            assertEquals(0, result.getInt("probeInheritedFds"))
            assertEquals(0, result.getJSONArray("parallelInheritedFds").getInt(0))
            assertEquals(0, result.getJSONArray("parallelInheritedFds").getInt(1))
            assertTrue(result.getLong("screenshotBytes") > 0)
            assertTrue(result.getLong("audioBytes") > 0)
        }

        assertFalse(File(copiedPath).exists())
    }

    private fun createFixture(): File {
        val fixture = File(context.cacheDir, S3TestDocumentsProvider.FIXTURE_NAME)
        fixture.delete()
        pythonModule().callAttr("create_fixture", nativeTool("libffmpeg.so").path, fixture.path)
        assertTrue(fixture.isFile)
        return fixture
    }

    private fun probeAndExtract(
        inputPath: String,
        output: File,
    ): JSONObject =
        JSONObject(
            pythonModule()
                .callAttr(
                    "probe_and_extract",
                    nativeTool("libffmpeg.so").path,
                    nativeTool("libffprobe.so").path,
                    inputPath,
                    output.path,
                ).toString(),
        )

    private fun pythonModule() =
        synchronized(PythonInstrumentationRuntime::class.java) {
            PythonInstrumentationRuntime.awaitReady().getModule("s3_media_probe")
        }

    private fun nativeTool(name: String): File {
        val tool = File(context.applicationInfo.nativeLibraryDir, name)
        assertTrue("Native S3 tool is missing: $tool", tool.isFile)
        assertTrue("Native S3 tool is not executable: $tool", tool.canExecute())
        return tool
    }

    private fun File.resetDirectory(): File {
        deleteRecursively()
        check(mkdirs()) { "Could not create $this" }
        return this
    }
}
