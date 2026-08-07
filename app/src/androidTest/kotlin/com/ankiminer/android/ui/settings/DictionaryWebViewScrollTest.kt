package com.ankiminer.android.ui.settings

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.test.platform.app.InstrumentationRegistry
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

        override fun requestDisallowInterceptTouchEvent(disallow: Boolean) {
            if (disallow) disallowIntercept = true
            super.requestDisallowInterceptTouchEvent(disallow)
        }
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
