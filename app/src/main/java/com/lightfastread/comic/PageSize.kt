package com.lightfastread.comic

import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * What size a page gets stored at.
 *
 * Pulled out of [ComicPages] because it is arithmetic, and arithmetic about resolution is exactly
 * the kind of thing that is wrong by a factor of two in a way nobody notices until every page in the
 * library is soft. Here it can be tested without a device.
 */
object PageSize {

    /**
     * [width] x [height] scaled to fit [targetWidth] and [maxPixels], keeping its shape.
     *
     * The width target comes first because the reader fits a page to the screen's width: that is the
     * dimension that decides how much detail arrives. The pixel ceiling only ever tightens the
     * result, and exists for the pages that are not page-shaped — a double spread, a poster, a strip
     * stitched into one tall image — where a width target alone permits a bitmap no heap will hold.
     *
     * **Never enlarges.** A page that arrives narrower than the target is stored as it is: upscaling
     * at import would spend storage on invented detail that the reader can invent for free at draw
     * time. Which also means the source is the ceiling — a volume scanned 1600 wide cannot be stored
     * at 2160 however much the reader would like it.
     */
    fun fit(width: Int, height: Int, targetWidth: Int, maxPixels: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 1 to 1
        // The width is chosen outright rather than derived from a ratio: a ratio multiplied back out
        // lands on 1079.9999 often enough to matter, and a page one pixel under the panel is a page
        // the reader stretches.
        var w = minOf(width, targetWidth)
        var h = (height.toDouble() * w / width).roundToInt().coerceAtLeast(1)
        if (w.toLong() * h > maxPixels) {
            val k = sqrt(maxPixels.toDouble() / (w.toDouble() * h))
            w = (w * k).toInt().coerceAtLeast(1)
            h = (h * k).toInt().coerceAtLeast(1)
        }
        return w to h
    }

    /**
     * The `inSampleSize` to decode with before the exact scale.
     *
     * A sampled decode is cheap and lands anywhere between 1x and 2x the size wanted, so it is used
     * to get close and an exact scale finishes the job — a volume whose pages are each a slightly
     * different size makes the reader jump on every turn.
     */
    fun sampleFor(sourceWidth: Int, finalWidth: Int, maxSample: Int = 16): Int {
        var sample = 1
        while (sample < maxSample && finalWidth > 0 && sourceWidth / (sample * 2) >= finalWidth) {
            sample *= 2
        }
        return sample
    }
}
