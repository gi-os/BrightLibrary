package com.lightfastread.ui.library

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.lightfastread.calibre.CalibreClient
import com.lightfastread.data.CalibreConfig
import com.lightfastread.data.Covers
import com.lightfastread.ui.light.LightText
import com.lightfastread.ui.light.LightTextVariant
import com.lightfastread.ui.light.LightThemeTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A cover thumbnail straight off the Calibre server.
 *
 * Nothing is written to disk. These are covers for books that are not on the shelf — most of them
 * never will be — so they live in a small in-memory cache and go away with the process. The cache is
 * what makes scrolling back up a catalogue instant, and what stops a `LazyColumn` re-requesting the
 * same image every time a row leaves and re-enters composition.
 *
 * Sized for the row it sits in (about 40dp wide at 420dpi, so ~120px), which is why the decode
 * samples down hard. A full-size cover here would be a megabyte of heap per visible row.
 */
private object ThumbCache {
    /** Entry count rather than bytes: every one of these is sampled down to roughly the same size. */
    val bitmaps = LruCache<String, ImageBitmap>(48)

    /**
     * URLs that came back as something other than an image.
     *
     * Without this a 404 cover is retried on every recomposition, which on a scrolling list is a
     * request per frame. A miss is permanent for the life of the process, which is the right
     * trade-off for art that is not going to appear while you are looking at a list.
     */
    val misses = HashSet<String>()
}

@Composable
fun RemoteThumb(
    url: String?,
    config: CalibreConfig,
    fallbackLetter: String,
    modifier: Modifier = Modifier,
) {
    val colors = LightThemeTokens.colors
    var bitmap by remember(url) { mutableStateOf(url?.let { ThumbCache.bitmaps.get(it) }) }

    LaunchedEffect(url) {
        if (url == null || bitmap != null || url in ThumbCache.misses) return@LaunchedEffect
        val decoded = withContext(Dispatchers.IO) {
            val bytes = runCatching { CalibreClient(config).bytes(url, MAX_BYTES) }.getOrNull()
            if (bytes == null || !Covers.looksLikeImage(bytes)) return@withContext null
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
                while (longEdge / (sample * 2) >= TARGET_PX) sample *= 2
                BitmapFactory.decodeByteArray(
                    bytes, 0, bytes.size,
                    BitmapFactory.Options().apply { inSampleSize = sample },
                )?.asImageBitmap()
            }.getOrNull()
        }
        if (decoded != null) {
            ThumbCache.bitmaps.put(url, decoded)
            bitmap = decoded
        } else {
            ThumbCache.misses.add(url)
        }
    }

    Box(
        modifier = modifier.border(BORDER_WIDTH, colors.rule),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // The first letter, not a spinner. A list of twenty rows would otherwise be twenty
            // things moving, and on a matte greyscale panel that reads as static.
            LightText(fallbackLetter.take(1).uppercase(), LightTextVariant.Detail, lighten = true)
        }
    }
}

private const val TARGET_PX = 180
private val BORDER_WIDTH = androidx.compose.ui.unit.Dp.Hairline
private const val MAX_BYTES = 8L * 1024 * 1024
