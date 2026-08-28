package com.ankiminer.android.ui.mining

import com.ankiminer.android.mining.CurationClipWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CurationClipTest {
    @Test
    fun `default window widens the line by the configured padding`() {
        val window = defaultClipWindow(startTime = 12.7, endTime = 15.0, audioPaddingSeconds = 0.3)
        assertEquals(12.4, window.startSeconds, 1e-9)
        assertEquals(15.3, window.endSeconds, 1e-9)
    }

    @Test
    fun `default window never runs before the start of the file`() {
        val window = defaultClipWindow(startTime = 0.1, endTime = 2.0, audioPaddingSeconds = 0.5)
        assertEquals(0.0, window.startSeconds, 1e-9)
    }

    @Test
    fun `the moved handle keeps its position and pushes the other one`() {
        // Dragging the start past the end must move the end, not snap the start back:
        // a handle that refuses to follow the pointer reads as broken.
        val coerced = coerceClipWindow(
            start = 14.9, end = 15.0, travelStart = 9.4, travelEnd = 18.3, movedStart = true,
        )
        assertEquals(14.9, coerced.startSeconds, 1e-9)
        assertEquals(15.1, coerced.endSeconds, 1e-9)
    }

    @Test
    fun `the pushed handle stops at the end of travel and pulls the moved one back`() {
        val coerced = coerceClipWindow(
            start = 18.3, end = 18.3, travelStart = 9.4, travelEnd = 18.3, movedStart = true,
        )
        assertEquals(18.1, coerced.startSeconds, 1e-9)
        assertEquals(18.3, coerced.endSeconds, 1e-9)
    }

    @Test
    fun `a window longer than the ceiling is trimmed from the handle that did not move`() {
        val coerced = coerceClipWindow(
            start = 0.0, end = 40.0, travelStart = 0.0, travelEnd = 40.0, movedStart = false,
        )
        assertEquals(30.0, coerced.lengthSeconds, 1e-9)
        assertEquals(40.0, coerced.endSeconds, 1e-9)
    }

    @Test
    fun `handles quantise onto the tick grid`() {
        val coerced = coerceClipWindow(
            start = 12.43, end = 15.06, travelStart = 9.4, travelEnd = 18.3, movedStart = true,
        )
        assertEquals(12.4, coerced.startSeconds, 1e-9)
        assertEquals(15.1, coerced.endSeconds, 1e-9)
    }

    @Test
    fun `travel is three seconds either side of the default window and never negative`() {
        val state = clipWindowUiState(0.1, 2.0, 0.3, override = null)!!
        assertEquals(0.0, state.travelStartSeconds, 1e-9)
        assertEquals(5.3, state.travelEndSeconds, 1e-9)
    }

    @Test
    fun `travel is derived from the default window so it cannot move under the finger`() {
        val state = clipWindowUiState(12.7, 15.0, 0.3, override = CurationClipWindow(13.0, 14.0))!!
        assertEquals(9.4, state.travelStartSeconds, 1e-9)
        assertEquals(18.3, state.travelEndSeconds, 1e-9)
        assertTrue(state.overridden)
    }

    @Test
    fun `an unedited window reports no override`() {
        assertFalse(clipWindowUiState(12.7, 15.0, 0.3, override = null)!!.overridden)
    }

    @Test
    fun `a default window longer than the ceiling still seeds`() {
        // An over-long default belongs to the line, not to an edit the user made,
        // so seeding applies no MIN/MAX - only coercion does.
        assertNotNull(clipWindowUiState(0.0, 45.0, 0.3, override = null))
    }

    @Test
    fun `an override the recomputed travel no longer contains is dropped, and one it still contains survives`() {
        // Legal when audio_padding is 5.0s: default (7.7, 20.0), travel (4.7, 23.0) comfortably
        // contains the stored override.
        val stale = CurationClipWindow(5.0, 6.0)
        val wide = clipWindowUiState(12.7, 15.0, 5.0, override = stale)!!
        assertEquals(5.0, wide.window.startSeconds, 1e-9)
        assertEquals(6.0, wide.window.endSeconds, 1e-9)
        assertTrue(wide.overridden)

        // The user opens Settings and lowers padding to 0.1s, which pulls travel up to
        // (9.6, 18.1) - past the same stored override. The default reseeds instead of
        // returning a window outside its own travel.
        val narrow = clipWindowUiState(12.7, 15.0, 0.1, override = stale)!!
        assertEquals(12.6, narrow.window.startSeconds, 1e-9)
        assertEquals(15.1, narrow.window.endSeconds, 1e-9)
        assertTrue(narrow.window.startSeconds >= narrow.travelStartSeconds)
        assertTrue(narrow.window.endSeconds <= narrow.travelEndSeconds)
        assertFalse(narrow.overridden)
    }
}
