package com.lightfastread

import com.lightfastread.comic.PageCrop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the crop decides a page's content starts and stops.
 *
 * Every case here is a shape that turns up in a real scan, and each has a way of being wrong that
 * you would not notice until it ate a panel: dust read as art, a faint page read as blank, a black
 * scanner border read as ink.
 */
class PageCropTest {

    private fun page(
        width: Int,
        height: Int,
        background: Int = 255,
        block: (set: (Int, Int, Int) -> Unit) -> Unit = {},
    ): IntArray {
        val pixels = IntArray(width * height) { grey(background) }
        block { x, y, v -> if (x in 0 until width && y in 0 until height) pixels[y * width + x] = grey(v) }
        return pixels
    }

    private fun grey(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    private fun fill(set: (Int, Int, Int) -> Unit, x0: Int, y0: Int, x1: Int, y1: Int, v: Int) {
        for (y in y0..y1) for (x in x0..x1) set(x, y, v)
    }

    @Test
    fun `a panel in the middle of white paper is found`() {
        val w = 600
        val h = 900
        // Art inset from the paper edge, the way a scanned page actually looks.
        val pixels = page(w, h) { set -> fill(set, 60, 80, 539, 819, 20) }  // dark grey ink
        val b = PageCrop.contentBounds(pixels, w, h)
        // Content plus a whisker of margin, and never outside the page.
        assertTrue(b.left in 48..60)
        assertTrue(b.top in 68..80)
        assertTrue(b.right in 540..552)
        assertTrue(b.bottom in 820..832)
    }

    @Test
    fun `a page with no margin is left exactly as it is`() {
        val w = 400
        val h = 600
        val pixels = page(w, h, background = 128)
        val b = PageCrop.contentBounds(pixels, w, h)
        assertEquals(0, b.left)
        assertEquals(0, b.top)
        assertEquals(w, b.right)
        assertEquals(h, b.bottom)
    }

    @Test
    fun `dust and a scanner streak are not content`() {
        val w = 500
        val h = 500
        val pixels = page(w, h) { set ->
            // Five stray dark pixels along the top edge — 1% of the line: dirt, not a drawing.
            for (i in 0 until 5) set(i * 97, 3, 0)
            fill(set, 60, 100, 439, 449, 30)
        }
        val b = PageCrop.contentBounds(pixels, w, h)
        assertTrue("the streak at y=3 should not count as content", b.top > 50)
    }

    @Test
    fun `solid black is ink, so a black-bordered scan is left whole`() {
        // This is the trade. Treating black as a border would crop this page nicely — and would also
        // throw away every black panel and night scene in the book, because in comics solid black is
        // the drawing. So black counts as ink, and a page framed in it does not crop.
        val w = 400
        val h = 400
        val pixels = page(w, h) { set ->
            fill(set, 0, 0, w - 1, 40, 0)      // black bar across the top of the scan
            fill(set, 40, 60, 359, 379, 40)    // the art
        }
        val b = PageCrop.contentBounds(pixels, w, h)
        assertEquals(0, b.top)
    }

    @Test
    fun `a black panel is content, not a margin`() {
        val w = 400
        val h = 600
        val pixels = page(w, h) { set -> fill(set, 40, 60, 359, 539, 0) }
        val b = PageCrop.contentBounds(pixels, w, h)
        assertTrue("solid black art must be found", b.left in 28..40 && b.top in 48..60)
    }

    @Test
    fun `a page that would lose almost everything is not cropped at all`() {
        // One small mark on an otherwise empty page: cropping to it would show a postage stamp
        // blown up to fill the screen, which is not what anybody meant.
        val w = 800
        val h = 800
        val pixels = page(w, h) { set -> fill(set, 390, 390, 410, 410, 0) }
        val b = PageCrop.contentBounds(pixels, w, h)
        assertEquals(0, b.left)
        assertEquals(0, b.top)
        assertEquals(w, b.right)
        assertEquals(h, b.bottom)
    }

    @Test
    fun `a nearly-white scan still has its margin found`() {
        // Real paper scans as 246, not 255. A threshold that demanded pure white would find no
        // margin on exactly the pages that have the most of it.
        val w = 400
        val h = 400
        val pixels = page(w, h, background = 246) { set -> fill(set, 40, 40, 359, 359, 60) }
        val b = PageCrop.contentBounds(pixels, w, h)
        assertTrue(b.left in 28..40)
        assertTrue(b.right in 360..372)
    }

    @Test
    fun `a yonkoma page's gutter is found, and its centre is the cut`() {
        val w = 900
        val h = 1240
        // Two strips of four panels with a 20px blank gutter, deliberately off-centre: the page middle
        // is 450, the gutter is 430..449.
        val pixels = page(w, h) { set ->
            fill(set, 40, 60, 429, 1179, 30)
            fill(set, 450, 60, 859, 1179, 30)
        }
        val gutter = PageCrop.centreGutter(pixels, w, h, PageCrop.contentBounds(pixels, w, h))
        assertTrue("gutter should be found near 440, was $gutter", gutter != null && gutter in 430..450)
    }

    @Test
    fun `a page with no gutter is not splittable`() {
        val w = 900
        val h = 1240
        // The cases that used to be cut down the middle and shouldn't be.
        val splash = page(w, h) { set -> fill(set, 40, 60, 859, 1179, 30) }
        assertNull(PageCrop.centreGutter(splash, w, h, PageCrop.contentBounds(splash, w, h)))

        val hairline = page(w, h) { set ->
            fill(set, 40, 60, 447, 1179, 30)
            fill(set, 452, 60, 859, 1179, 30)
        }
        assertNull(
            "4px between two panels of one strip is not a gutter",
            PageCrop.centreGutter(hairline, w, h, PageCrop.contentBounds(hairline, w, h)),
        )

        val bubbleAcross = page(w, h) { set ->
            fill(set, 40, 60, 429, 1179, 30)
            fill(set, 450, 60, 859, 1179, 30)
            fill(set, 300, 500, 600, 700, 20)   // a speech bubble straddling the gutter
        }
        assertNull(
            "a bubble across the gutter means the strips are joined",
            PageCrop.centreGutter(bubbleAcross, w, h, PageCrop.contentBounds(bubbleAcross, w, h)),
        )
    }

    @Test
    fun `a little ink leaning into the gutter does not block the cut`() {
        val w = 900
        val h = 1240
        // The case that used to make a whole volume unsplittable page by page: a balloon tail, a
        // hand, a sound effect crossing the gap. 60 rows of 1240 is 5% of the column — paper with
        // something leaning into it, not a drawing.
        val pixels = page(w, h) { set ->
            fill(set, 40, 60, 429, 1179, 30)
            fill(set, 450, 60, 859, 1179, 30)
            fill(set, 420, 600, 470, 659, 20)
        }
        val gutter = PageCrop.centreGutter(pixels, w, h, PageCrop.contentBounds(pixels, w, h))
        assertTrue("the cut should still go through, was $gutter", gutter != null && gutter in 425..455)
    }

    @Test
    fun `a malformed page is handled rather than crashing`() {
        assertEquals(0, PageCrop.contentBounds(IntArray(0), 0, 0).left)
        val short = PageCrop.contentBounds(IntArray(10), 100, 100)
        assertEquals(100, short.right)
    }
}
