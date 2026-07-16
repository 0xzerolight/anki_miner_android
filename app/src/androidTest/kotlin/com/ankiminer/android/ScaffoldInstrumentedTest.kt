package com.ankiminer.android

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScaffoldInstrumentedTest {
    @Test
    fun chaquopyStartsPinnedPythonAndPackagesDebugSources() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val python = PythonInstrumentationRuntime.awaitReady()

        val snapshot =
            JSONObject(
                python.getModule("scaffold_probe")
                    .callAttr("snapshot")
                    .toString(),
            )
        val expectedVersion =
            BuildConfig.PYTHON_TARGET_VERSION
                .substringBefore('-')
                .split('.')
                .map(String::toInt)

        assertEquals("CPython", snapshot.getString("implementation"))
        assertEquals(expectedVersion[0], snapshot.getInt("major"))
        assertEquals(expectedVersion[1], snapshot.getInt("minor"))
        assertEquals(expectedVersion[2], snapshot.getInt("micro"))
        assertTrue(context.applicationInfo.nativeLibraryDir.isNotBlank())
    }
}
