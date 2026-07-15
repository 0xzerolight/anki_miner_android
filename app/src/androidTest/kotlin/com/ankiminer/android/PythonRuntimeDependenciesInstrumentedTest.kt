package com.ankiminer.android

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PythonRuntimeDependenciesInstrumentedTest {
    @Test
    fun commonRuntimeDependenciesLoadAndPerformRealWork() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context))
        }
        Python.getInstance()
            .getModule("android_bridge.bootstrap")
            .callAttr("initialize", context.filesDir.absolutePath)

        val snapshot =
            JSONObject(
                Python.getInstance()
                    .getModule("runtime_dependencies_probe")
                    .callAttr("snapshot")
                    .toString(),
            )
        val expectedVersions =
            mapOf(
                "certifi" to "2026.6.17",
                "charset-normalizer" to "3.4.7",
                "idna" to "3.18",
                "lxml" to "6.1.1",
                "pillow" to "12.2.0",
                "pysubs2" to "1.8.1",
                "requests" to "2.34.2",
                "urllib3" to "2.7.0",
            )

        assertEquals("cpython", snapshot.getString("implementation"))
        assertEquals(
            listOf(3, 12, 12),
            snapshot.getJSONArray("python").let { values ->
                List(values.length()) { values.getInt(it) }
            },
        )
        val versions = snapshot.getJSONObject("versions")
        expectedVersions.forEach { (name, version) ->
            assertEquals("wrong embedded version for $name", version, versions.getString(name))
        }

        val codecs = snapshot.getJSONObject("codec_support")
        listOf("freetype", "jpeg", "webp", "zlib").forEach { codec ->
            assertTrue("missing Pillow $codec support", codecs.getBoolean(codec))
        }
        val images = snapshot.getJSONObject("images")
        listOf("JPEG", "PNG", "WEBP").forEach { format ->
            assertEquals(format, images.getJSONObject(format).getString("format"))
            assertTrue(images.getJSONObject(format).getInt("bytes") > 0)
        }

        val forbidden = snapshot.getJSONObject("forbidden_present")
        listOf("gtts", "unidic", "unidic_lite", "yt_dlp").forEach { packageName ->
            assertFalse("$packageName must not be bundled", forbidden.getBoolean(packageName))
        }
    }
}
