package com.lightfastread

import com.lightfastread.comic.PageCrop
import com.lightfastread.comic.PageSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What size a page is stored at, and how a measurement taken on a small copy of it moves onto a big
 * one.
 *
 * The cases that matter are the ones where being wrong is invisible: a page stored at 868 instead of
 * 1080 does not look broken, it just looks slightly soft on every page of every book.
 */
class PageSizeTest {

    private val screen = 1080
    private val strips = 2160
    private val maxPixels = 1080 * 2600
    private val maxPixelsStrips = 2160 * 3600

    @Test
    fun `an ordinary page is stored exactly the width of the screen`() {
        val (w, h) = PageSize.fit(1575, 2250, screen, maxPixels)
        assertEquals(1080, w)
        // Shape kept: 1575x2250 is 1 to 1.4286.
        assertEquals(1543, h)
    }

    @Test
    fun `a strip volume is stored at twice the width, so a column lands at the screen's`() {
        val (w, h) = PageSize.fit(2400, 3400, strips, maxPixelsStrips)
        assertEquals(2160, w)
        assertEquals(3060, h)
        // Which is the whole point: half of it is the panel, pixel for pixel.
        assertEquals(screen, w / 2)
    }

    @Test
    fun `a scan narrower than the strip target is the ceiling on its own sharpness`() {
        // Worth stating as a test because it is the answer to "why is this volume still soft": the
        // source is 1600 wide, so a column of it is 800, and no amount of storage invents the rest.
        val (w, _) = PageSize.fit(1600, 2400, strips, maxPixelsStrips)
        assertEquals(1600, w)
    }

    @Test
    fun `a page smaller than the target is left alone rather than blown up`() {
        val (w, h) = PageSize.fit(800, 1200, screen, maxPixels)
        assertEquals(800, w)
        assertEquals(1200, h)
    }

    @Test
    fun `a very tall page is pulled back by the pixel ceiling`() {
        // A stitched strip: 1600 wide and eight pages tall. The width target alone would store a
        // 1080x9450 bitmap — 40 MB decoded, which no heap here will hold.
        val (w, h) = PageSize.fit(1600, 14000, screen, maxPixels)
        assertTrue("the width must give way to the ceiling", w < 1080)
        assertTrue(w.toLong() * h <= maxPixels)
        // Still the same shape, which is what stops the reader jumping.
        assertEquals(1600.0 / 14000, w.toDouble() / h, 0.01)
    }

    @Test
    fun `a double spread fits under the ceiling untouched`() {
        val (w, h) = PageSize.fit(3200, 2400, screen, maxPixels)
        assertEquals(1080, w)
        assertEquals(810, h)
    }

    @Test
    fun `nonsense dimensions do not divide by zero`() {
        assertEquals(1 to 1, PageSize.fit(0, 0, screen, maxPixels))
        assertEquals(1 to 1, PageSize.fit(-5, 100, screen, maxPixels))
    }

    @Test
    fun `the sampled decode lands at or above the size wanted, never below`() {
        for (source in listOf(1200, 1575, 1600, 2048, 3000, 4961)) {
            for (target in listOf(540, 640, 1080, 2160)) {
                val sample = PageSize.sampleFor(source, target)
                val decoded = source / sample
                if (source >= target) {
                    assertTrue(
                        "source=$source target=$target sample=$sample decoded=$decoded",
                        decoded >= target,
                    )
                }
            }
        }
    }

    @Test
    fun `a measurement taken small lands in the right place on the big page`() {
        // Measured on a 640-wide copy, drawn on a 2160-wide one.
        val small = PageCrop.Bounds(20, 30, 620, 900)
        val scaled = PageCrop.scale(small, 640, 960, 2160, 3240)
        // 2160/640 is 3.375, and the box rounds outwards so it can never eat a line of ink.
        assertEquals(67, scaled.left)
        assertEquals(101, scaled.top)
        assertEquals(2093, scaled.right)
        assertEquals(3038, scaled.bottom)
    }

    @Test
    fun `scaling never leaves the page`() {
        val edge = PageCrop.Bounds(0, 0, 640, 960)
        val scaled = PageCrop.scale(edge, 640, 960, 2160, 3240)
        assertEquals(0, scaled.left)
        assertEquals(2160, scaled.right)
        assertEquals(3240, scaled.bottom)
    }

    @Test
    fun `a gutter measured small cuts in the same place on the big page`() {
        assertEquals(1080, PageCrop.scaleX(320, 640, 2160))
        assertEquals(2160, PageCrop.scaleX(9999, 640, 2160))
        // The cut has to stay inside the strip it is dividing, or the reader shows an empty column.
        assertTrue(PageCrop.scaleX(319, 640, 2160) < 2160)
    }

    @Test
    fun `the same size is left untouched`() {
        val b = PageCrop.Bounds(11, 22, 33, 44)
        assertEquals(b, PageCrop.scale(b, 100, 200, 100, 200))
        assertNull(null)
    }
}
