package com.lightfastread.comic

/**
 * Finding where a scanned page's *content* actually is.
 *
 * Scans carry margins: the white edge of the paper, and the white of the scanner bed around it. On a
 * 411dp screen those margins are a fifth of the width you paid for, and fitting the page to the
 * screen fits the margins too.
 *
 * **White margins only, deliberately.** Ink is anything that is not paper — *including solid black*,
 * which an earlier version excluded as "probably a scanner border" and which cost it every black
 * panel, gutter and night scene in the book. The price of that decision is that a scan with a black
 * border around it simply will not crop, which shows you the whole page: the safe way to be wrong.
 *
 * The measurement is pure and works on a plain pixel array, so it can be tested without a device —
 * [ComicPages] passes the decoded bitmap's pixels in. It is a *measurement*, not an edit: nothing is
 * written back, so the crop can be turned off again and a page that was mis-measured costs a
 * setting rather than the file.
 */
object PageCrop {

    /** left, top, right (exclusive), bottom (exclusive). */
    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    /**
     * Anything at least this bright counts as blank paper.
     *
     * Deliberately not 255: a scan's white is never 255. It is 246, or 250 with a grain of noise,
     * and a threshold that demands pure white finds no margin at all on exactly the pages that have
     * the most of it.
     */
    private const val WHITE = 236

    /**
     * How much ink a line needs before it counts as content.
     *
     * Not one pixel: dust, a scanner streak and JPEG ringing along an edge all produce a scattering
     * of dark pixels in an otherwise empty line, and cropping to those crops to the dirt.
     *
     * Two percent is safe for comics specifically. The outermost real line of a page is a panel
     * border or the edge of a drawing, and those run most of the way across — a page whose first
     * content row is 2% ink is a page of dust.
     */
    private const val MIN_INK_FRACTION = 0.02

    /**
     * Leave this much of the margin behind, as a fraction of the page's short edge.
     *
     * A crop flush against the ink looks wrong — panels bleed into the bezel and speech balloons
     * touch the edge of the glass. A whisker of white reads as intentional.
     */
    private const val MARGIN_FRACTION = 0.01

    /**
     * Keep at least this much of the page, or crop nothing.
     *
     * A page that measures as 90% margin is not a page with big margins; it is a measurement gone
     * wrong — a splash of open sky, a chapter break, a scan that faded — and blowing the remaining
     * tenth up to fill the screen is worse than leaving the margins on. A quarter is the line: real
     * margins take 10-20% of a page, so anything that would drop three quarters of it is a mistake.
     */
    private const val MIN_KEPT_FRACTION = 0.25

    /**
     * Where the white gutter down the middle of a page is, or null if there isn't one.
     *
     * This is what decides whether a page *can* be read as two 4-koma strips. A yonkoma page prints
     * two strips with a band of blank paper between them; a splash page, a chapter break or a
     * double-page spread has no such band, and cutting one down the middle would slice a drawing in
     * half. So the mode asks the page rather than assuming.
     *
     * Returns the **centre of the widest near-blank run** near the middle, not the geometric middle: the
     * gutter is rarely centred on a scan, and cutting at the halfway point puts a sliver of the right
     * strip at the end of the left one.
     *
     * A candidate is thrown out unless **both sides of the cut have real content** on them. Without
     * that check, a page that is entirely or almost entirely blank — a chapter break, a title page, a
     * splash that faded to white — offers a "blank run" the width of the whole search band, and nothing
     * before this stopped it from being read as a gutter and split into two empty strips.
     *
     * [bounds] is the content area — the cropped page, so the paper margin is not mistaken for a
     * gutter. Pure, and tested against the shapes real pages come in.
     */
    fun centreGutter(
        pixels: IntArray,
        width: Int,
        height: Int,
        bounds: Bounds = Bounds(0, 0, width, height),
        step: Int = 3,
    ): Int? {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return null
        if (bounds.width < MIN_SPLITTABLE_PX || bounds.height <= 0) return null

        fun columnIsBlank(x: Int): Boolean {
            var ink = 0
            var counted = 0
            var y = bounds.top
            while (y < bounds.bottom) {
                if ((pixels[y * width + x] and 0xFF) < WHITE) ink++
                counted++
                y += step
            }
            return counted > 0 && ink.toDouble() / counted < GUTTER_MAX_INK_FRACTION
        }

        // Only near the middle. A blank column a tenth of the way in is the space beside a panel, not
        // the gutter between two strips.
        val from = bounds.left + (bounds.width * (0.5 - GUTTER_SEARCH_SPAN / 2)).toInt()
        val to = bounds.left + (bounds.width * (0.5 + GUTTER_SEARCH_SPAN / 2)).toInt()
        if (to <= from) return null

        var bestStart = -1
        var bestEnd = -1
        var runStart = -1
        var x = from.coerceAtLeast(0)
        val limit = to.coerceAtMost(width - 1)
        while (x <= limit) {
            if (columnIsBlank(x)) {
                if (runStart < 0) runStart = x
                if (x - runStart > bestEnd - bestStart) {
                    bestStart = runStart
                    bestEnd = x
                }
            } else {
                runStart = -1
            }
            x += step
        }
        if (bestStart < 0) return null

        val runWidth = bestEnd - bestStart + step
        val minRun = (bounds.width * MIN_GUTTER_FRACTION).toInt().coerceAtLeast(step)
        if (runWidth < minRun) return null
        // A "gutter" a quarter of the page wide is not a gutter, it is an empty page.
        if (runWidth > bounds.width * MAX_GUTTER_FRACTION) return null
        val cut = (bestStart + bestEnd) / 2

        // The blank-run search only ever proved the *middle* is empty — it says nothing about
        // whether either side is. A page that is blank corner to corner passes every check above
        // with a run the width of the whole search band; this is what actually stops it splitting.
        fun regionInk(left: Int, right: Int): Double {
            if (left >= right) return 0.0
            var ink = 0
            var counted = 0
            var y = bounds.top
            while (y < bounds.bottom) {
                var x = left
                while (x < right) {
                    if ((pixels[y * width + x] and 0xFF) < WHITE) ink++
                    counted++
                    x += step
                }
                y += step
            }
            return if (counted > 0) ink.toDouble() / counted else 0.0
        }
        if (regionInk(bounds.left, cut) < MIN_STRIP_INK_FRACTION ||
            regionInk(cut, bounds.right) < MIN_STRIP_INK_FRACTION
        ) {
            return null
        }
        return cut
    }

    /** How wide a band of the page to look in, centred on the middle. */
    private const val GUTTER_SEARCH_SPAN = 0.34

    /**
     * How much ink a column may carry and still count as gutter.
     *
     * Started at 0.4% — blank paper, nothing else — and that was too pure to be useful on real
     * scans. A yonkoma gutter routinely carries *something*: the tail of a speech balloon, a hand or
     * a sound effect leaning out of one strip, a page number, a scanner's crease. Any of those made
     * the page unsplittable, and an unsplittable page in 4-koma mode reads as a whole page in the
     * middle of a book of strips.
     *
     * A tenth turned out to be too much: at that tolerance a page with only faint, sparse marks —
     * screentone, a light wash, dust across a whole near-blank page — could read as "blank" almost
     * everywhere, which is a different failure than the one this was fixing. 5% is the corrected
     * number, and it may need to come down further still; the [MIN_STRIP_INK_FRACTION] check below
     * is the backstop for exactly that case, since a wide tolerance here now costs nothing if a
     * blank-looking page fails that check regardless.
     */
    private const val GUTTER_MAX_INK_FRACTION = 0.05

    /** Narrower than this and it is the space between two panels of one strip, not a gutter. */
    private const val MIN_GUTTER_FRACTION = 0.012

    /** Wider than this and the middle of the page is simply empty. */
    private const val MAX_GUTTER_FRACTION = 0.25

    /** Below this width a page is too small to be two strips of anything. */
    private const val MIN_SPLITTABLE_PX = 200

    /**
     * How much ink each side of a candidate cut needs, or the page is left whole.
     *
     * The blank-run search proves the *middle* is empty; it is silent on whether either side has
     * anything on it. Without this, a page that is white corner to corner — or white with one small
     * mark nowhere near the middle — passes the run-width checks exactly as a real two-strip page
     * would, and gets split into two blank halves. One percent is barely anything, which is the
     * point: a real strip, even a sparse one, clears this by a wide margin, so it only ever catches
     * the pages that truly have nothing on one side of the cut.
     */
    private const val MIN_STRIP_INK_FRACTION = 0.01

    /**
     * The same bounds, measured on one size of the page and expressed on another.
     *
     * The crop and the gutter are measured on a *small* decode of the page — a 2160px page is 27 MB
     * of `IntArray` to read pixel by pixel, and none of this measurement needs that resolution — so
     * the answer arrives in the small copy's coordinates and has to be moved into the one being
     * drawn. Rounding is outwards for the box and nearest for the gutter: a crop a pixel too generous
     * shows a pixel of paper, a crop a pixel too tight eats a line of ink.
     */
    fun scale(bounds: Bounds, fromWidth: Int, fromHeight: Int, toWidth: Int, toHeight: Int): Bounds {
        if (fromWidth <= 0 || fromHeight <= 0 || toWidth <= 0 || toHeight <= 0) return bounds
        if (fromWidth == toWidth && fromHeight == toHeight) return bounds
        val kx = toWidth.toDouble() / fromWidth
        val ky = toHeight.toDouble() / fromHeight
        return Bounds(
            left = kotlin.math.floor(bounds.left * kx).toInt().coerceIn(0, toWidth),
            top = kotlin.math.floor(bounds.top * ky).toInt().coerceIn(0, toHeight),
            right = kotlin.math.ceil(bounds.right * kx).toInt().coerceIn(0, toWidth),
            bottom = kotlin.math.ceil(bounds.bottom * ky).toInt().coerceIn(0, toHeight),
        )
    }

    /** An x measured on a [fromWidth]-wide copy, expressed on a [toWidth]-wide one. */
    fun scaleX(x: Int, fromWidth: Int, toWidth: Int): Int {
        if (fromWidth <= 0 || toWidth <= 0) return x
        return (x.toDouble() * toWidth / fromWidth).toInt().coerceIn(0, toWidth)
    }

    /**
     * Content bounds of a greyscale-ish page.
     *
     * [pixels] is ARGB, row-major, [width] × [height] — exactly what `Bitmap.getPixels` gives. Only
     * the blue channel is read: these pages were desaturated at import, so all three channels agree,
     * and reading one is three times less work.
     *
     * [step] samples every nth pixel along a line. Page margins are hundreds of pixels wide; nothing
     * about this needs every pixel, and a 1240×900 page at step 3 is a tenth of the work.
     */
    fun contentBounds(
        pixels: IntArray,
        width: Int,
        height: Int,
        step: Int = 3,
    ): Bounds {
        val whole = Bounds(0, 0, width, height)
        if (width <= 0 || height <= 0 || pixels.size < width * height) return whole

        fun rowHasInk(y: Int): Boolean {
            var ink = 0
            var counted = 0
            var x = 0
            val row = y * width
            while (x < width) {
                val v = pixels[row + x] and 0xFF
                if (v < WHITE) ink++
                counted++
                x += step
            }
            return counted > 0 && ink.toDouble() / counted >= MIN_INK_FRACTION
        }

        fun columnHasInk(x: Int): Boolean {
            var ink = 0
            var counted = 0
            var y = 0
            while (y < height) {
                val v = pixels[y * width + x] and 0xFF
                if (v < WHITE) ink++
                counted++
                y += step
            }
            return counted > 0 && ink.toDouble() / counted >= MIN_INK_FRACTION
        }

        var top = 0
        while (top < height && !rowHasInk(top)) top += step
        var bottom = height - 1
        while (bottom > top && !rowHasInk(bottom)) bottom -= step
        var left = 0
        while (left < width && !columnHasInk(left)) left += step
        var right = width - 1
        while (right > left && !columnHasInk(right)) right -= step

        if (left >= right || top >= bottom) return whole

        val margin = (minOf(width, height) * MARGIN_FRACTION).toInt()
        val bounds = Bounds(
            left = (left - margin).coerceAtLeast(0),
            top = (top - margin).coerceAtLeast(0),
            right = (right + 1 + margin).coerceAtMost(width),
            bottom = (bottom + 1 + margin).coerceAtMost(height),
        )

        val keptArea = bounds.width.toLong() * bounds.height
        val wholeArea = width.toLong() * height
        if (keptArea < wholeArea * MIN_KEPT_FRACTION) return whole
        return bounds
    }
}
