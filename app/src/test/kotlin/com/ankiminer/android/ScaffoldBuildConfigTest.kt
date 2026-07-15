package com.ankiminer.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScaffoldBuildConfigTest {
    @Test
    fun embeddedPythonAndExactTargetRemainExplicitlyPinned() {
        assertEquals("3.12", BuildConfig.PYTHON_VERSION)
        assertEquals("3.12.12-0", BuildConfig.PYTHON_TARGET_VERSION)
        assertTrue(BuildConfig.RUNTIME_WHEEL_BUILD_KEY.matches(Regex("[0-9a-f]{64}")))
    }
}
