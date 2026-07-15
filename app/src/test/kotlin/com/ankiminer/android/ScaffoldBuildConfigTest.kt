package com.ankiminer.android

import org.junit.Assert.assertEquals
import org.junit.Test

class ScaffoldBuildConfigTest {
    @Test
    fun provisionalPythonVersionRemainsExplicitlyPinned() {
        assertEquals("3.13", BuildConfig.PYTHON_VERSION)
    }
}
