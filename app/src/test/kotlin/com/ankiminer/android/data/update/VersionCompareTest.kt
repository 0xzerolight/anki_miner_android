package com.ankiminer.android.data.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionCompareTest {
    @Test
    fun `a later patch is newer`() = assertTrue(VersionCompare.isNewer("0.4.2", "0.4.1"))

    @Test
    fun `a later minor beats a longer patch`() =
        assertTrue(VersionCompare.isNewer("0.5.0", "0.4.99"))

    @Test
    fun `an equal version is not newer`() =
        assertFalse(VersionCompare.isNewer("0.4.1", "0.4.1"))

    @Test
    fun `an older version is not newer`() =
        assertFalse(VersionCompare.isNewer("0.4.0", "0.4.1"))

    @Test
    fun `a shorter prefix is not newer`() =
        assertFalse(VersionCompare.isNewer("0.4", "0.4.1"))

    @Test
    fun `a longer suffix is newer`() =
        assertTrue(VersionCompare.isNewer("0.4.1.1", "0.4.1"))

    @Test
    fun `an unparseable candidate is never newer`() {
        assertFalse(VersionCompare.isNewer("nightly", "0.4.1"))
        assertFalse(VersionCompare.isNewer("", "0.4.1"))
        assertFalse(VersionCompare.isNewer("0.4.1", "nightly"))
    }

    @Test
    fun `an absurdly long version string is rejected rather than parsed`() =
        assertFalse(VersionCompare.isNewer("1." + "9".repeat(400), "0.4.1"))
}
