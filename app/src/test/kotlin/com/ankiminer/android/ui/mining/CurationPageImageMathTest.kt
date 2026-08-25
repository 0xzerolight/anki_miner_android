package com.ankiminer.android.ui.mining

import androidx.compose.ui.geometry.Rect
import com.ankiminer.android.mining.CurationBlockBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Desktop parity for `_PageCanvas.fit_transform`/`clamped_box`
 * (`page_image_view.py:178-205`) ported to [pageFitTransform]/[clampBlockBox].
 */
class CurationPageImageMathTest {
    @Test
    fun portraitPageInLandscapePaneLetterboxesOnTheXAxis() {
        val transform = pageFitTransform(paneWidth = 1000f, paneHeight = 600f, imageWidth = 500, imageHeight = 800)

        assertEquals(0.75f, transform.scale, 0.0001f)
        assertEquals(312.5f, transform.dx, 0.0001f)
        assertEquals(0f, transform.dy, 0.0001f)
    }

    @Test
    fun landscapePageInPortraitPaneLetterboxesOnTheYAxis() {
        val transform = pageFitTransform(paneWidth = 600f, paneHeight = 1000f, imageWidth = 800, imageHeight = 400)

        assertEquals(0.75f, transform.scale, 0.0001f)
        assertEquals(0f, transform.dx, 0.0001f)
        assertEquals(350f, transform.dy, 0.0001f)
    }

    @Test
    fun aPageSmallerThanThePaneIsScaledUpToFit() {
        val transform = pageFitTransform(paneWidth = 400f, paneHeight = 400f, imageWidth = 100, imageHeight = 200)

        assertEquals(2f, transform.scale, 0.0001f)
        assertEquals(100f, transform.dx, 0.0001f)
        assertEquals(0f, transform.dy, 0.0001f)
    }

    @Test
    fun degenerateImageDimensionsYieldAZeroScale() {
        val zeroWidth = pageFitTransform(paneWidth = 400f, paneHeight = 400f, imageWidth = 0, imageHeight = 200)
        val negativeHeight = pageFitTransform(paneWidth = 400f, paneHeight = 400f, imageWidth = 200, imageHeight = -1)

        assertEquals(PageFitTransform(0f, 0f, 0f), zeroWidth)
        assertEquals(PageFitTransform(0f, 0f, 0f), negativeHeight)
    }

    @Test
    fun degeneratePaneDimensionsYieldAZeroScale() {
        val zeroPaneWidth = pageFitTransform(paneWidth = 0f, paneHeight = 400f, imageWidth = 500, imageHeight = 800)
        val negativePaneHeight =
            pageFitTransform(paneWidth = 400f, paneHeight = -10f, imageWidth = 500, imageHeight = 800)

        assertEquals(PageFitTransform(0f, 0f, 0f), zeroPaneWidth)
        assertEquals(PageFitTransform(0f, 0f, 0f), negativePaneHeight)
    }

    @Test
    fun boxFullyOutsideThePageClampsToNull() {
        val box = CurationBlockBox(xMin = 1000, yMin = 1000, xMax = 1100, yMax = 1100)

        assertNull(clampBlockBox(box, imageWidth = 500, imageHeight = 800))
    }

    @Test
    fun boxFullyOutsideOnTheNegativeSideClampsToNull() {
        val box = CurationBlockBox(xMin = -100, yMin = -100, xMax = -10, yMax = -10)

        assertNull(clampBlockBox(box, imageWidth = 500, imageHeight = 800))
    }

    @Test
    fun degenerateBoxClampsToNull() {
        val box = CurationBlockBox(xMin = 50, yMin = 50, xMax = 50, yMax = 80)

        assertNull(clampBlockBox(box, imageWidth = 500, imageHeight = 800))
    }

    @Test
    fun boxPartiallyOutsideThePageIsClampedToTheVisiblePortion() {
        val box = CurationBlockBox(xMin = -50, yMin = 10, xMax = 100, yMax = 50)

        assertEquals(Rect(0f, 10f, 100f, 50f), clampBlockBox(box, imageWidth = 500, imageHeight = 800))
    }

    @Test
    fun boxFullyInsideThePageIsUnchanged() {
        val box = CurationBlockBox(xMin = 20, yMin = 30, xMax = 120, yMax = 90)

        assertEquals(Rect(20f, 30f, 120f, 90f), clampBlockBox(box, imageWidth = 500, imageHeight = 800))
    }

    @Test
    fun degenerateImageDimensionsClampToNull() {
        val box = CurationBlockBox(xMin = 10, yMin = 10, xMax = 20, yMax = 20)

        assertNull(clampBlockBox(box, imageWidth = 0, imageHeight = 800))
    }
}
