package com.ankiminer.android.ui.settings

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The disallow-intercept handshake is what lets an overflowing definition scroll inside the
 * candidate LazyColumn; these tests pin it without rendering any HTML.
 */
class DictionaryWebViewScrollTest {
    private class RecordingFrame(context: Context) : FrameLayout(context) {
        var disallowIntercept = false
        var lastRequest: Boolean? = null

        override fun requestDisallowInterceptTouchEvent(disallow: Boolean) {
            if (disallow) disallowIntercept = true
            lastRequest = disallow
            super.requestDisallowInterceptTouchEvent(disallow)
        }
    }

    private class AtContentEndStub(context: Context) : DictionaryWebView(context) {
        override fun canScrollVertically(direction: Int): Boolean = direction < 0
    }

    private class ScrollableStub(
        context: Context,
        private val scrollable: Boolean,
    ) : DictionaryWebView(context) {
        override fun canScrollVertically(direction: Int): Boolean = scrollable
    }

    @Test
    fun overflowingContentClaimsTheGestureFromTheParent() {
        assertTrue(dispatchDownOn(scrollable = true))
    }

    @Test
    fun shortContentLeavesTheParentFreeToScroll() {
        assertFalse(dispatchDownOn(scrollable = false))
    }

    @Test
    fun draggingPastTheContentEndHandsTheGestureBackToTheList() {
        assertEquals(false, lastRequestAfterDrag(fromY = 200f, toY = 100f))
    }

    @Test
    fun draggingBackIntoTheContentKeepsTheClaim() {
        assertEquals(true, lastRequestAfterDrag(fromY = 100f, toY = 200f))
    }

    private fun lastRequestAfterDrag(
        fromY: Float,
        toY: Float,
    ): Boolean? {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var last: Boolean? = null
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val frame = RecordingFrame(context)
            val webView = AtContentEndStub(context)
            frame.addView(webView)
            frame.measure(
                View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            )
            frame.layout(0, 0, 320, 320)
            val now = SystemClock.uptimeMillis()
            listOf(
                MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 10f, fromY, 0),
                MotionEvent.obtain(now, now + 16, MotionEvent.ACTION_MOVE, 10f, toY, 0),
            ).forEach { event ->
                try {
                    webView.dispatchTouchEvent(event)
                } finally {
                    event.recycle()
                }
            }
            last = frame.lastRequest
            webView.destroy()
        }
        return last
    }

    private fun dispatchDownOn(scrollable: Boolean): Boolean {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var claimed = false
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val frame = RecordingFrame(context)
            val webView = ScrollableStub(context, scrollable)
            frame.addView(webView)
            frame.measure(
                View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            )
            frame.layout(0, 0, 320, 320)
            val now = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 10f, 10f, 0)
            try {
                webView.dispatchTouchEvent(down)
            } finally {
                down.recycle()
            }
            claimed = frame.disallowIntercept
            webView.destroy()
        }
        return claimed
    }
}
