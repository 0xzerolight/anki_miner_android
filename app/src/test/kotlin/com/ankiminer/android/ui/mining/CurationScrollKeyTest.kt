package com.ankiminer.android.ui.mining

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CurationScrollKeyTest {
    private val key = curationProjectionKey("run:request:0", "", CurationFilter.ALL, CurationSort.FREQUENCY)

    @Test
    fun `the first composition records the key without scrolling`() {
        assertFalse(scrollKeyChanged(appliedKey = null, key = key))
    }

    @Test
    fun `a recreation that restored the applied key does not scroll`() {
        val restored = curationProjectionKey("run:request:0", "", CurationFilter.ALL, CurationSort.FREQUENCY)
        assertFalse(scrollKeyChanged(appliedKey = key, key = restored))
    }

    @Test
    fun `a changed projection scrolls`() {
        val searched = curationProjectionKey("run:request:0", "猫", CurationFilter.ALL, CurationSort.FREQUENCY)
        assertTrue(scrollKeyChanged(appliedKey = key, key = searched))
    }

    @Test
    fun `entering curation from setup changes the projection`() {
        val setup = curationProjectionKey(null, "", CurationFilter.ALL, CurationSort.FREQUENCY)
        assertTrue(scrollKeyChanged(appliedKey = setup, key = key))
    }

    @Test
    fun `every projection field changes the key`() {
        assertNotEquals(key, curationProjectionKey("run:request:1", "", CurationFilter.ALL, CurationSort.FREQUENCY))
        assertNotEquals(key, curationProjectionKey("run:request:0", "x", CurationFilter.ALL, CurationSort.FREQUENCY))
        assertNotEquals(key, curationProjectionKey("run:request:0", "", CurationFilter.SELECTED, CurationSort.FREQUENCY))
        assertNotEquals(key, curationProjectionKey("run:request:0", "", CurationFilter.ALL, CurationSort.OCCURRENCES))
        assertEquals(key, curationProjectionKey("run:request:0", "", CurationFilter.ALL, CurationSort.FREQUENCY))
    }
}
