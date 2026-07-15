package com.ankiminer.android

import android.content.Context
import android.os.ParcelFileDescriptor.AutoCloseInputStream
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TokenizerS1aInstrumentedTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun golden(): String =
        InstrumentationRegistry.getInstrumentation().context.assets.open("engine-v1.json")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun stageDictionary(expectedHash: String): File {
        val parent = File(context.filesDir, "test-assets").apply { mkdirs() }
        val destination = File(parent, "unidic-$expectedHash")
        if (destination.isDirectory) return destination
        val staging = File(parent, ".unidic-$expectedHash.staging")
        staging.deleteRecursively()
        check(staging.mkdirs())
        val prefix = staging.canonicalPath + File.separator
        var entries = 0
        var bytes = 0L
        val names = mutableSetOf<String>()
        val descriptor =
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "cat ${BuildConfig.TOKENIZER_TEST_UNIDIC_ARCHIVE}",
            )
        ZipInputStream(AutoCloseInputStream(descriptor).buffered()).use { archive ->
            while (true) {
                val entry = archive.nextEntry ?: break
                check(entry.name.isNotBlank() && names.add(entry.name))
                val target = File(staging, entry.name).canonicalFile
                check(target.path.startsWith(prefix))
                if (entry.isDirectory) {
                    check(target.mkdirs() || target.isDirectory)
                } else {
                    target.parentFile?.let { check(it.mkdirs() || it.isDirectory) }
                    FileOutputStream(target).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = archive.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            bytes += count
                            check(bytes <= 512L * 1024 * 1024)
                        }
                    }
                }
                entries += 1
                check(entries <= 512)
                archive.closeEntry()
            }
        }
        check(entries > 0) { "external UniDic ZIP is missing" }
        check(staging.renameTo(destination))
        return destination
    }

    @Test
    fun externalUniDicMatchesDesktopGoldens() {
        assumeTrue("S1a wheels are not enabled", BuildConfig.S1A_SPIKE_ENABLED)
        val goldenJson = golden()
        val golden = JSONObject(goldenJson)
        val expectedHash =
            golden.getJSONObject("provenance").getJSONObject("data")
                .getJSONObject("assets_sha256").getString("unidic_dicdir")
        val dicdir = stageDictionary(expectedHash)
        if (!Python.isStarted()) Python.start(AndroidPlatform(context))
        Python.getInstance().getModule("android_bridge.bootstrap")
            .callAttr("initialize", context.filesDir.absolutePath)
        val result =
            JSONObject(
                Python.getInstance().getModule("tokenizer_s1a_instrumented")
                    .callAttr(
                        "run",
                        goldenJson,
                        dicdir.absolutePath,
                        context.applicationInfo.nativeLibraryDir,
                    ).toString(),
            )
        assertEquals(26, result.getInt("feature_field_count"))
        assertEquals(expectedHash, result.getString("dictionary_sha256"))
        assertTrue(result.getInt("case_count") > 0)
        assertTrue(result.getInt("unknown_count") > 0)
    }
}
