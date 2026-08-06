package com.lightfastread.ui.comic

import android.graphics.BitmapFactory
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
 * One page — or one half of one — drawn by hand.
 *
 * A `Canvas` rather than an `Image`, because five things have to agree about the same rectangle: the
 * crop, the 4-koma split, the fit, the zoom and the scroll. Expressed as one source rect and one
 * destination rect that is arithmetic you can read; expressed as nested `ContentScale` and
 * `graphicsLayer` transforms it is a multiplication order that is easy to get subtly wrong and
 * impossible to debug on a phone with no logcat attached.
 */
@Composable
internal fun ComicPageCanvas(
    bookId: String,
    page: Int,
    crop: Boolean,
    /** -1 for a whole page; 0 for the top half and 1 for the bottom in 4-koma mode. */
    half: Int,
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
    onMeasured: (maxScroll: Float, contentHeight: Float) -> Unit,
) {
    val context = LocalContext.current
    val key = "$bookId#$page"  // the bitmap is the whole page; the half is a source rect over it
    var loaded by remember(key) { mutableStateOf(PageCache.get(key)) }

    LaunchedEffect(key) {
        if (loaded != null) return@LaunchedEffect
        val result = withContext(Dispatchers.IO) {
            val file = ComicPages.pageFile(context, bookId, page)
            if (!file.exists()) return@withContext null
            runCatching {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return@runCatching null
                // Measured once, here, while the pixels are already in hand — and kept with the page,
                // so toggling the crop back and forth costs nothing.
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                val bounds = PageCrop.contentBounds(pixels, bitmap.width, bitmap.height)
                LoadedPage(bitmap.asImageBitmap(), bounds)
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
    // The 4-koma split, cut *after* the crop: the paper margin is not one of the two strips, and
    // splitting the uncropped page would put half the margin in each.
    val source = when (half) {
        0 -> cropped.copy(bottom = cropped.top + cropped.height / 2)
        1 -> cropped.copy(top = cropped.top + cropped.height / 2)
        else -> cropped
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
    LaunchedEffect(page, half, limit, drawH, active) { if (active) onMeasured(limit, drawH) }

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

internal data class LoadedPage(val image: ImageBitmap, val bounds: PageCrop.Bounds)

/**
 * Decoded pages, kept briefly.
 *
 * Six entries rather than five: the transition draws the page you are leaving *and* the one you are
 * arriving at, and in 4-koma mode both halves of both pages come out of the same two bitmaps.
 */
internal object PageCache {
    private val cache = LruCache<String, LoadedPage>(6)
    fun get(key: String): LoadedPage? = cache.get(key)
    fun put(key: String, value: LoadedPage) = cache.put(key, value)
}
