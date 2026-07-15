package com.ankiminer.android.tokenizer

import android.content.Context
import android.os.ParcelFileDescriptor.AutoCloseInputStream
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ankiminer.android.BuildConfig
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MecabNativeTokenizerInstrumentedTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun readGolden(): String =
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("engine-v1.json")
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }

    private fun stageExternalDictionary(expectedHash: String): File {
        val parent = File(context.filesDir, "test-assets").apply { mkdirs() }
        val destination = File(parent, "unidic-$expectedHash")
        if (destination.isDirectory) {
            return destination
        }
        val staging = File(parent, ".unidic-$expectedHash.staging")
        staging.deleteRecursively()
        check(staging.mkdirs()) { "could not create UniDic staging directory" }
        val rootPrefix = staging.canonicalPath + File.separator
        var entryCount = 0
        var totalBytes = 0L
        val names = mutableSetOf<String>()
        val descriptor =
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "cat ${BuildConfig.S1B_TEST_UNIDIC_ARCHIVE}",
            )
        ZipInputStream(AutoCloseInputStream(descriptor).buffered()).use { archive ->
            while (true) {
                val entry = archive.nextEntry ?: break
                check(entry.name.isNotBlank() && names.add(entry.name)) {
                    "external UniDic ZIP has an invalid or duplicate entry"
                }
                val target = File(staging, entry.name).canonicalFile
                check(target.path.startsWith(rootPrefix)) {
                    "external UniDic ZIP escapes its staging directory"
                }
                if (entry.isDirectory) {
                    check(target.mkdirs() || target.isDirectory)
                } else {
                    target.parentFile?.let { parent ->
                        check(parent.mkdirs() || parent.isDirectory)
                    }
                    FileOutputStream(target).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = archive.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            totalBytes += count
                            check(totalBytes <= 512L * 1024 * 1024) {
                                "external UniDic ZIP exceeds the test size limit"
                            }
                        }
                    }
                }
                entryCount += 1
                check(entryCount <= 512) {
                    "external UniDic ZIP has too many entries"
                }
                archive.closeEntry()
            }
        }
        check(entryCount > 0) {
            "external UniDic ZIP is missing; run the emulator provisioning script"
        }
        check(staging.renameTo(destination)) {
            "could not publish the versioned UniDic test directory"
        }
        return destination
    }

    @Test
    fun nativeBoundaryRejectsIncompleteArgvWithoutExposingHandles() {
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                MecabNativeTokenizer.tokenize(
                    "猫".toByteArray(Charsets.UTF_8),
                    arrayOf("anki_miner", "-C"),
                )
            }

        assertTrue(error.message.orEmpty().contains("mecab_new argv"))
    }

    @Test
    fun externalUniDicMatchesAllGoldensThroughPythonKotlinAndJni() {
        val goldenJson = readGolden()
        val golden = JSONObject(goldenJson)
        val expectedHash =
            golden.getJSONObject("provenance")
                .getJSONObject("data")
                .getJSONObject("assets_sha256")
                .getString("unidic_dicdir")
        val dicdir = stageExternalDictionary(expectedHash)
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context))
        }

        val result =
            JSONObject(
                Python.getInstance()
                    .getModule("tokenizer_s1b_instrumented")
                    .callAttr("run", goldenJson, dicdir.absolutePath)
                    .toString(),
            )

        assertEquals(
            golden.getJSONObject("cases").getJSONArray("tokenization").length(),
            result.getInt("case_count"),
        )
        assertEquals(
            golden.getJSONArray("unidic_feature_fields").length(),
            result.getInt("feature_field_count"),
        )
        assertEquals(26, result.getInt("feature_field_count"))
        assertEquals(expectedHash, result.getString("dictionary_sha256"))
        assertTrue(result.getInt("unknown_count") > 0)
        assertEquals(1, result.getInt("oov_utf16_start"))
        assertEquals(7, result.getInt("oov_utf16_end"))
        assertTrue(result.getBoolean("sys_dic_mapped"))
        assertTrue(result.getBoolean("matrix_mapped"))
    }
}
