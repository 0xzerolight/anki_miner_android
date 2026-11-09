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
    fun `an override outside the default travel is seated in a travel of its own`() {
        // Legal when audio_padding is 5.0s: the user lowers padding to 0.1s, so the default
        // travel (9.6, 18.1) no longer reaches the stored override. Desktop set_word seats the
        // travel around the override instead, so it still shows inside its own travel.
        val stale = CurationClipWindow(5.0, 6.0)
        val narrow = clipWindowUiState(12.7, 15.0, 0.1, override = stale)!!
        assertEquals(5.0, narrow.window.startSeconds, 1e-9)
        assertEquals(6.0, narrow.window.endSeconds, 1e-9)
        assertEquals(2.0, narrow.travelStartSeconds, 1e-9)
        assertEquals(9.0, narrow.travelEndSeconds, 1e-9)
        assertTrue(narrow.overridden)
    }

    @Test
    fun `an override inside the default travel keeps the default travel`() {
        // A drag that ends with the start handle on the travel's own edge must not widen the
        // travel on release, or the handles jump under the finger.
        val state = clipWindowUiState(12.7, 15.0, 0.3, override = CurationClipWindow(9.4, 15.3))!!
        assertEquals(9.4, state.travelStartSeconds, 1e-9)
        assertEquals(18.3, state.travelEndSeconds, 1e-9)
    }

    @Test
    fun `a far scrub seats the travel around the new window only`() {
        val state = clipWindowUiState(600.0, 603.0, 0.3, override = null)!!
        val (moved, travel) = clipEdgeAtPlayhead(state.window, state.travel, 10.0, end = false)
        assertEquals(ClipWindowSeconds(10.0, 40.0), moved)
        assertEquals(ClipTravel(7.0, 43.0), travel)

        val stored = clipWindowUiState(600.0, 603.0, 0.3, CurationClipWindow(moved.startSeconds, moved.endSeconds))!!
        assertEquals(ClipTravel(7.0, 43.0), stored.travel)
        assertEquals(travel, retainedClipTravel(travel, stored))
    }

    @Test
    fun `a playhead set inside the travel leaves the travel where it is`() {
        val state = clipWindowUiState(12.7, 15.0, 0.3, override = null)!!
        val (moved, travel) = clipEdgeAtPlayhead(state.window, state.travel, 16.0, end = true)
        assertEquals(ClipWindowSeconds(12.4, 16.0), moved)
        assertEquals(state.travel, travel)
    }

    @Test
    fun `a drag inside the travel leaves it unchanged`() {
        // After the far scrub the travel is (7, 43). Dragging the window to (9, 38) stores an
        // override whose own seat would be (6, 41); the travel on screen must not follow it.
        val seated = ClipTravel(7.0, 43.0)
        val dragged = clipWindowUiState(600.0, 603.0, 0.3, CurationClipWindow(9.0, 38.0))!!
        assertEquals(seated, retainedClipTravel(seated, dragged))
    }

    @Test
    fun `a window outside the travel on screen takes the state's travel`() {
        // A reset after a far scrub: the default window is nowhere near (7, 43).
        val reset = clipWindowUiState(600.0, 603.0, 0.3, override = null)!!
        assertEquals(reset.travel, retainedClipTravel(ClipTravel(7.0, 43.0), reset))
    }

    @Test
    fun `an end set at a playhead ten seconds past the line lands exactly there`() {
        val state = clipWindowUiState(12.7, 15.0, 0.3, override = null)!!
        val moved = clipWindowAtPlayhead(state.window, 25.3, end = true)
        assertEquals(12.4, moved.startSeconds, 1e-9)
        assertEquals(25.3, moved.endSeconds, 1e-9)

        val reseated = clipWindowUiState(12.7, 15.0, 0.3, CurationClipWindow(moved.startSeconds, moved.endSeconds))!!
        assertEquals(moved, reseated.window)
        assertEquals(9.4, reseated.travelStartSeconds, 1e-9)
        assertEquals(28.3, reseated.travelEndSeconds, 1e-9)
    }

    @Test
    fun `a start set at a playhead ten seconds before the line lands exactly there`() {
        val state = clipWindowUiState(12.7, 15.0, 0.3, override = null)!!
        val moved = clipWindowAtPlayhead(state.window, 2.4, end = false)
        assertEquals(2.4, moved.startSeconds, 1e-9)
        assertEquals(15.3, moved.endSeconds, 1e-9)

        val reseated = clipWindowUiState(12.7, 15.0, 0.3, CurationClipWindow(moved.startSeconds, moved.endSeconds))!!
        assertEquals(moved, reseated.window)
        assertEquals(0.0, reseated.travelStartSeconds, 1e-9)
    }

    @Test
    fun `an edge set past the other end keeps the minimum length by moving the other end`() {
        val window = ClipWindowSeconds(12.4, 15.3)
        assertEquals(ClipWindowSeconds(12.1, 12.3), clipWindowAtPlayhead(window, 12.3, end = true))
        assertEquals(ClipWindowSeconds(15.2, 15.4), clipWindowAtPlayhead(window, 15.2, end = false))
    }

    @Test
    fun `an edge set beyond the maximum length pulls the other end along`() {
        assertEquals(ClipWindowSeconds(20.0, 50.0), clipWindowAtPlayhead(ClipWindowSeconds(12.4, 15.3), 50.0, end = true))
        assertEquals(ClipWindowSeconds(0.0, 30.0), clipWindowAtPlayhead(ClipWindowSeconds(40.0, 45.0), 0.0, end = false))
    }

    @Test
    fun `a playhead before the start of the file sets the start at zero`() {
        assertEquals(ClipWindowSeconds(0.0, 15.3), clipWindowAtPlayhead(ClipWindowSeconds(12.4, 15.3), -1.0, end = false))
    }

    @Test
    fun `an end drag held past the start handle settles instead of walking the window down`() {
        // RangeSliderState clamps the dragged end to the start thumb, so a drag held past it
        // reports (start, start) once per event - against a start the previous callback just
        // lowered. Ten events must leave the window where the first one put it.
        var live = ClipWindowSeconds(5.3, 7.1)
        repeat(10) {
            live = nextClipWindow(live, ClipWindowSeconds(live.startSeconds, live.startSeconds), 0.0, 20.0)
        }
        assertEquals(5.1, live.startSeconds, 1e-9)
        assertEquals(5.3, live.endSeconds, 1e-9)
    }

    @Test
    fun `a start drag held past the end handle settles the same way`() {
        // The mirror case: the start clamps up to the end, and the moved handle keeping its
        // position pushes the end one tick out. It must not keep pushing.
        var live = ClipWindowSeconds(5.3, 7.1)
        repeat(10) {
            live = nextClipWindow(live, ClipWindowSeconds(live.endSeconds, live.endSeconds), 0.0, 20.0)
        }
        assertEquals(7.1, live.startSeconds, 1e-9)
        assertEquals(7.3, live.endSeconds, 1e-9)
    }

    @Test
    fun `an ordinary drag still moves the handle the pointer carried`() {
        val live = ClipWindowSeconds(1.0, 3.0)
        val next = nextClipWindow(live, ClipWindowSeconds(1.0, 2.4), 0.0, 6.0)
        assertEquals(1.0, next.startSeconds, 1e-9)
        assertEquals(2.4, next.endSeconds, 1e-9)
    }

    @Test
    fun `a minimum-length window still follows a press somewhere else on the track`() {
        // Only the echo of this window's own collapsed handles is ignored. A degenerate report
        // anywhere else is a real press and still moves the window there.
        val live = ClipWindowSeconds(5.1, 5.3)
        val next = nextClipWindow(live, ClipWindowSeconds(9.0, 9.0), 0.0, 20.0)
        assertEquals(9.0, next.startSeconds, 1e-9)
        assertEquals(9.2, next.endSeconds, 1e-9)
    }
}
