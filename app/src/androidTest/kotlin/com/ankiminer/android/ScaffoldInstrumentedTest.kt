package com.ankiminer.android

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
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
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context))
        }

        val snapshot =
            JSONObject(
                Python.getInstance()
                    .getModule("scaffold_probe")
                    .callAttr("snapshot")
                    .toString(),
            )
        val expectedVersion = BuildConfig.PYTHON_VERSION.split('.').map(String::toInt)

        assertEquals("CPython", snapshot.getString("implementation"))
        assertEquals(expectedVersion[0], snapshot.getInt("major"))
        assertEquals(expectedVersion[1], snapshot.getInt("minor"))
        assertTrue(context.applicationInfo.nativeLibraryDir.isNotBlank())
    }
}
