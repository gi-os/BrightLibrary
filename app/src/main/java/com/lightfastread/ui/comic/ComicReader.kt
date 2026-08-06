package com.lightfastread.ui.comic

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.gios.light.common.hw.WheelSteps
import com.lightfastread.comic.ComicPages
import com.lightfastread.data.Book
import com.lightfastread.data.BookRepository
import com.lightfastread.ui.light.LightBarItem
import com.lightfastread.ui.light.LightIcons
import com.lightfastread.ui.light.LightText
import com.lightfastread.ui.light.LightTextVariant
import com.lightfastread.ui.light.LightThemeTokens
import com.lightfastread.ui.light.LightTopBar
import com.lightfastread.ui.light.designVerticalPxToDp
import com.lightfastread.ui.light.lightInset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Reading a comic.
 *
 * One page at a time, filling the screen, with no chrome until you ask for it — a scan already has
 * its own margins and page numbers drawn into it, and anything this app puts on top is a second set.
 *
 * **Right to left is the default** (see [Book.rightToLeft]). The pager is mirrored rather than
 * reversed by hand: the page shown at pager position `i` is `last - i`, so the pager's own physics —
 * drag, fling, snap — carry over unchanged and a drag towards the right turns to the next page, as
 * it does on paper. Everything outside this file talks in page numbers and never has to know.
 *
 * The wheel is not a spatial gesture, so it ignores direction entirely: down is always the next
 * page. Turning it to go "forwards" and travelling backwards through the book because the volume is
 * Japanese would be indefensible.
 */
@Composable
fun ComicReader(bookId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { BookRepository.get(context) }
    val book = remember(bookId) { repo.getBook(bookId) }
    val colors = LightThemeTokens.colors

    if (book == null || book.totalWords <= 0) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val pageCount = book.totalWords
    val rtl = book.rightToLeft
    val lastIndex = pageCount - 1

    // Pager position <-> page number. The only two lines in the file that know about direction.
    fun toPage(position: Int): Int = if (rtl) lastIndex - position else position
    fun toPosition(page: Int): Int = if (rtl) lastIndex - page else page

    val pagerState = rememberPagerState(
        initialPage = toPosition(book.currentWordIndex.coerceIn(0, lastIndex)),
        pageCount = { pageCount },
    )

    var showChrome by remember { mutableStateOf(false) }
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val currentPage = toPage(pagerState.currentPage)
    val scope = rememberCoroutineScope()

    /** Move [direction] pages through the *book*, whichever way the pages happen to run. */
    val turnPage: (Int) -> Unit = { direction ->
        val next = (currentPage + direction).coerceIn(0, lastIndex)
        if (next != currentPage) {
            scope.launch { pagerState.animateScrollToPage(toPosition(next)) }
        }
    }

    // Position is written down as it changes rather than only on the way out: a comic is read in a
    // sitting that ends by the screen going off, not by anybody pressing back.
    LaunchedEffect(pagerState, bookId) {
        snapshotFlow { pagerState.currentPage }.collect { position ->
            repo.updateProgress(bookId, toPage(position))
        }
    }

    val exit = {
        repo.updateProgress(bookId, currentPage)
        onBack()
    }
    BackHandler(onBack = exit)

    // Zooming out has to also drop the pan, or the next page opens somewhere off its own edge.
    LaunchedEffect(scale) { if (scale <= 1f) pan = Offset.Zero }
    // A new page starts unzoomed. Keeping the zoom across a turn sounds convenient and is not: the
    // panel you were reading is nowhere near the same place on the next page.
    LaunchedEffect(pagerState.currentPage) {
        scale = 1f
        pan = Offset.Zero
    }

    if (showChrome) {
        LaunchedEffect(showChrome, currentPage) {
            delay(CHROME_VISIBLE_MS)
            showChrome = false
        }
    }

    WheelPaging(pageCount, turnPage)

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        HorizontalPager(
            state = pagerState,
            // A drag while zoomed in is a pan, not a page turn. Without this the page slides out
            // from under the thumb the moment you try to read a corner of it.
            userScrollEnabled = scale <= 1f,
            modifier = Modifier.fillMaxSize(),
        ) { position ->
            ComicPage(
                bookId = bookId,
                page = toPage(position),
                // Only the page actually being read is zoomed; its neighbours stay at rest so a
                // half-finished swipe doesn't reveal a magnified next page.
                scale = if (position == pagerState.currentPage) scale else 1f,
                pan = if (position == pagerState.currentPage) pan else Offset.Zero,
            )
        }

        // Taps: the outer thirds turn the page, the middle asks for the chrome. Which third is
        // "next" follows the reading direction, because a tap *is* a spatial gesture.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(rtl, pageCount, scale) {
                    detectTapGestures(
                        onDoubleTap = { scale = if (scale > 1f) 1f else ZOOM },
                        onTap = { offset ->
                            val third = size.width / 3f
                            val forward = if (rtl) offset.x < third else offset.x > size.width - third
                            val back = if (rtl) offset.x > size.width - third else offset.x < third
                            when {
                                scale > 1f -> showChrome = !showChrome
                                forward -> turnPage(1)
                                back -> turnPage(-1)
                                else -> showChrome = !showChrome
                            }
                        },
                    )
                }
                .pointerInput(scale) {
                    if (scale <= 1f) return@pointerInput
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        val limitX = size.width * (scale - 1f) / 2f
                        val limitY = size.height * (scale - 1f) / 2f
                        pan = Offset(
                            (pan.x + dragAmount.x).coerceIn(-limitX, limitX),
                            (pan.y + dragAmount.y).coerceIn(-limitY, limitY),
                        )
                    }
                },
        )

        if (showChrome) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(colors.background)
                    .statusBarsPadding()
                    .align(Alignment.TopCenter),
            ) {
                LightTopBar(
                    title = book.title,
                    left = LightBarItem.Icon(LightIcons.Back, onClick = exit),
                )
            }
            LightText(
                text = "${currentPage + 1} / $pageCount" + if (rtl) "  ←" else "",
                variant = LightTextVariant.Superfine,
                lighten = true,
                align = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(colors.background)
                    .padding(horizontal = lightInset(), vertical = 10f.designVerticalPxToDp()),
            )
        }
    }
}

/**
 * Turn the page from the wheel.
 *
 * Split out because the notch counting is fiddly and has nothing to do with layout. Same three
 * notches per page as the text reader: the sensor fires about every 35ms, so one notch per page
 * sends a deliberate quarter-turn of the thumb through half a chapter.
 */
@Composable
private fun WheelPaging(pageCount: Int, onTurn: (Int) -> Unit) {
    var banked by remember { mutableIntStateOf(0) }
    var lastNotchAtMs by remember { mutableLongStateOf(0L) }
    var pending by remember { mutableIntStateOf(0) }
    val turn by rememberUpdatedState(onTurn)

    WheelSteps(notchesPerStep = 1, minIntervalMs = 0) { step ->
        if (pageCount > 1) {
            val now = System.currentTimeMillis()
            val stale = now - lastNotchAtMs > WHEEL_IDLE_MS
            val reversed = banked != 0 && (banked > 0) != (step > 0)
            lastNotchAtMs = now
            banked = if (stale || reversed) step else banked + step
            if (abs(banked) >= NOTCHES_PER_PAGE) {
                pending = if (banked > 0) 1 else -1
                banked = 0
            }
        }
    }
    LaunchedEffect(pending) {
        if (pending == 0) return@LaunchedEffect
        turn(pending)
        pending = 0
    }
}

/**
 * One page, decoded from its stored file.
 *
 * Held in a tiny cache keyed by book and page: the pager keeps neighbours composed, and a decode
 * per recomposition would mean re-reading a JPEG on every frame of a drag. Five entries covers the
 * page either side of the one being read with room to spare, and each is at most the size of the
 * screen — see [ComicPages].
 */
@Composable
private fun ComicPage(bookId: String, page: Int, scale: Float, pan: Offset) {
    val context = LocalContext.current
    val key = "$bookId#$page"
    var bitmap by remember(key) { mutableStateOf(PageCache.get(key)) }

    LaunchedEffect(key) {
        if (bitmap != null) return@LaunchedEffect
        val decoded = withContext(Dispatchers.IO) {
            val file = ComicPages.pageFile(context, bookId, page)
            if (!file.exists()) return@withContext null
            runCatching { BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() }.getOrNull()
        }
        if (decoded != null) {
            PageCache.put(key, decoded)
            bitmap = decoded
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                // Fit, never crop. A cropped page loses the edge of a panel, and on manga the edge
                // of the panel is frequently where the dialogue is.
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = pan.x,
                        translationY = pan.y,
                    ),
            )
        } else {
            LightText("${page + 1}", LightTextVariant.Detail, lighten = true)
        }
    }
}

private object PageCache {
    private val cache = LruCache<String, ImageBitmap>(5)
    fun get(key: String): ImageBitmap? = cache.get(key)
    fun put(key: String, value: ImageBitmap) = cache.put(key, value)
}

/** Enough to read a speech bubble on a 411dp screen without becoming a magnifying-glass hunt. */
private const val ZOOM = 2.5f

private const val NOTCHES_PER_PAGE = 3
private const val WHEEL_IDLE_MS = 1_500L
private const val CHROME_VISIBLE_MS = 4_000L
