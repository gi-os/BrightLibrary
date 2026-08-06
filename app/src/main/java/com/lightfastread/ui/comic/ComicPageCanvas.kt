package com.lightfastread.ui.comic

import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.lightfastread.comic.ComicPages
import com.lightfastread.comic.PageCrop
import com.lightfastread.ui.light.LightText
import com.lightfastread.ui.light.LightTextVariant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * One page — or one column of one — drawn by hand.
 *
 * A `Canvas` rather than an `Image`, because five things have to agree about the same rectangle: the
 * crop, the strip, the fit, the zoom and the scroll. Expressed as one source rect and one
 * destination rect that is arithmetic you can read; expressed as nested `ContentScale` and
 * `graphicsLayer` transforms it is a multiplication order that is easy to get subtly wrong and
 * impossible to debug on a phone with no logcat attached.
 */
@Composable
internal fun ComicPageCanvas(
    bookId: String,
    page: Int,
    crop: Boolean,
    /**
     * Which column of the page to show, and how many columns it is cut into (1 = the whole page).
     *
     * This is the *physical* column, counted from the left. Which one is read first is the reader's
     * business — for a Japanese book that is the rightmost.
     */
    part: Int,
    parts: Int,
    fitWidth: Boolean,
    zoom: Float,
    pan: Offset,
    scrollY: Float,
    /**
     * How far the page is displaced while it arrives, in pixels, applied *after* the scroll is
     * clamped — a page sliding in from below is deliberately outside its own scroll range, and
     * clamping it would make the animation disappear.
     */
    enterOffset: Float,
    /**
     * Whether this is the page being read. A page composes before it is on screen, so its
     * measurement has to be re-reported when it becomes the live one — otherwise the scroll limit
     * belongs to the page you just left, and the first notch on a taller page turns straight past it.
     */
    active: Boolean,
    viewportW: Float,
    viewportH: Float,
    onMeasured: (maxScroll: Float, contentHeight: Float, splittable: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val key = "$bookId#$page"  // the bitmap is the whole page; a strip is a source rect over it
    var loaded by remember(key) { mutableStateOf(PageCache.get(key)) }

    LaunchedEffect(key) {
        if (loaded != null) return@LaunchedEffect
        val result = withContext(Dispatchers.IO) {
            val file = ComicPages.pageFile(context, bookId, page)
            if (!file.exists()) return@withContext null
            runCatching {
                // Decoded against the heap rather than at whatever size it was stored: a strip
                // volume's page is 27 MB of bitmap, and a phone with a small heap gets half of one
                // instead of a dead process.
                val bitmap = ComicPages.decodeForDisplay(file) ?: return@runCatching null
                // Measured once, here, and kept with the page, so toggling the crop back and forth
                // costs nothing. The crop box and the gutter — the band of white that decides whether
                // this page can be read as two strips — are both far too expensive to ask per frame,
                // and both are measured on a small copy rather than on these pixels.
                val measured = ComicPages.measure(file, bitmap.width, bitmap.height)
                LoadedPage(bitmap.asImageBitmap(), measured.bounds, measured.gutter)
            }.getOrNull()
        }
        if (result != null) {
            PageCache.put(key, result)
            loaded = result
        }
    }

    val image = loaded
    if (image == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            LightText("${page + 1}", LightTextVariant.Detail, lighten = true)
        }
        return
    }

    val whole = PageCrop.Bounds(0, 0, image.image.width, image.image.height)
    val cropped = if (crop) image.bounds else whole
    // The 4-koma split is a **vertical cut**: a yonkoma page prints two strips side by side, each four
    // panels tall, so the page divides into columns and each column is read whole, scrolling down it.
    // Cutting horizontally — which an earlier version did — slices every strip through the middle of
    // its panels.
    //
    // Cut *after* the crop, deliberately: the paper margin is not part of a strip, and slicing the
    // uncropped page would hand half the margin to each column. The last column takes the remainder so
    // rounding cannot lose a column of pixels between them.
    // Split only where the page actually has a gutter. A splash page, a chapter break or a
    // double-page spread has no blank band down the middle, and cutting one at the halfway point
    // slices a drawing in half — so a page that cannot split is simply shown whole, and the reader is
    // told so it can skip the strip that does not exist.
    val gutter = image.gutter?.takeIf { it > cropped.left && it < cropped.right }
    val source = if (parts <= 1 || part < 0 || gutter == null) {
        cropped
    } else if (part == 0) {
        cropped.copy(right = gutter)
    } else {
        cropped.copy(left = gutter)
    }

    // All of the arithmetic happens in composition, never in the draw scope: reporting the scroll
    // limit from inside `Canvas` would be a state write during drawing, which is how a reader ends up
    // in an invalidation loop that only shows itself on a real device.
    val srcW = source.width.toFloat().coerceAtLeast(1f)
    val srcH = source.height.toFloat().coerceAtLeast(1f)
    val base = if (fitWidth) viewportW / srcW else minOf(viewportW / srcW, viewportH / srcH)
    val scale = base * zoom
    val drawW = srcW * scale
    val drawH = srcH * scale
    val limit = max(0f, drawH - viewportH)

    // Keyed on which page this is, not only on how big it is: a converted volume's pages are all the
    // same pixel size, so keying on the measurement alone meant the effect never re-ran on a page turn
    // and the new page was never measured at all.
    LaunchedEffect(page, part, parts, limit, drawH, active) {
        if (active) onMeasured(limit, drawH, gutter != null)
    }

    Canvas(Modifier.fillMaxSize()) {
        val x = (viewportW - drawW) / 2f + pan.x
        val y = (if (limit <= 0f) (viewportH - drawH) / 2f else -scrollY.coerceIn(0f, limit)) +
            pan.y + enterOffset
        drawImage(
            image = image.image,
            srcOffset = IntOffset(source.left, source.top),
            srcSize = IntSize(source.width, source.height),
            dstOffset = IntOffset(x.roundToInt(), y.roundToInt()),
            dstSize = IntSize(drawW.roundToInt().coerceAtLeast(1), drawH.roundToInt().coerceAtLeast(1)),
            // Bilinear: a zoomed page is scaled up past the panel's own resolution, and
            // nearest-neighbour on line art at 2x looks like a fax.
            filterQuality = FilterQuality.Low,
        )
    }
}

internal data class LoadedPage(
    val image: ImageBitmap,
    val bounds: PageCrop.Bounds,
    /** Centre of the white gutter down the middle, or null when the page has none and cannot split. */
    val gutter: Int?,
)

/**
 * Decoded pages, kept briefly, within a share of the heap.
 *
 * **Budgeted in bytes, not counted in entries.** Six pages was safe while a page was 868x1240 — four
 * megabytes each — and is fatal now that a strip volume's page is 2160x3086: six of those is 160 MB
 * and the heap is not that big. A quarter of the heap always holds at least the two the page turn
 * draws at once, which is the only number that has to be guaranteed.
 *
 * Nothing is recycled on eviction, deliberately. An evicted page may still be the one on screen —
 * the reader holds it directly — so it is left to the garbage collector, which knows that and a
 * `recycle()` here would not.
 */
internal object PageCache {
    private val budgetKb =
        (Runtime.getRuntime().maxMemory() / 4 / 1024).toInt().coerceAtLeast(16 * 1024)

    private val cache = object : LruCache<String, LoadedPage>(budgetKb) {
        override fun sizeOf(key: String, value: LoadedPage): Int =
            (value.image.width.toLong() * value.image.height * 4 / 1024).toInt().coerceAtLeast(1)
    }

    fun get(key: String): LoadedPage? = cache.get(key)
    fun put(key: String, value: LoadedPage) = cache.put(key, value)
}
