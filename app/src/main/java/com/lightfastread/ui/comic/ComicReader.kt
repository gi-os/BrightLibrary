package com.lightfastread.ui.comic

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.gios.light.common.hw.WheelSteps
import com.lightfastread.comic.ComicPages
import com.lightfastread.comic.PageCrop
import com.lightfastread.data.Book
import com.lightfastread.data.BookRepository
import com.lightfastread.data.SettingsRepository
import com.lightfastread.ui.light.LightBarItem
import com.lightfastread.ui.light.LightBottomBar
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
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Reading a comic.
 *
 * **Fitted to the width by default, and scrolled down.** A manga page shown whole on a 3.9" screen
 * is a page of unreadable speech bubbles: the page was drawn for paper seven inches across, and
 * fitting all of it on this panel is a demonstration that it exists rather than a way to read it.
 * Fitted to the width, the lettering is the size it was drawn to be and the page takes two screens.
 *
 * The wheel then reads as one gesture: **a notch takes you a screen down the page, and the notch
 * after the last one takes you to the next page.** No hunting for the bottom, no half-screens of
 * overlap to lose your place in — each notch lands somewhere deliberate.
 *
 * Right to left is still the default (see [Book.rightToLeft]) and still done by mirroring the pager,
 * so a swipe turns pages the way paper does while the wheel ignores direction entirely — down is
 * always onwards.
 *
 * Pinch zooms, a drag pans what is zoomed, a double tap goes back to the fit. The outer thirds of
 * the screen turn pages, the middle asks for the chrome.
 */
@Composable
fun ComicReader(bookId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { BookRepository.get(context) }
    val settingsRepo = remember { SettingsRepository.get(context) }
    val settings by settingsRepo.state
    val book = remember(bookId) { repo.getBook(bookId) }
    val colors = LightThemeTokens.colors

    if (book == null || book.totalWords <= 0) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val pageCount = book.totalWords
    val rtl = book.rightToLeft
    val lastIndex = pageCount - 1

    fun toPage(position: Int): Int = if (rtl) lastIndex - position else position
    fun toPosition(page: Int): Int = if (rtl) lastIndex - page else page

    val pagerState = rememberPagerState(
        initialPage = toPosition(book.currentWordIndex.coerceIn(0, lastIndex)),
        pageCount = { pageCount },
    )
    val scope = rememberCoroutineScope()
    val currentPage = toPage(pagerState.currentPage)

    var showChrome by remember { mutableStateOf(false) }
    var fitWidth by remember { mutableStateOf(settings.comicFitWidth) }
    var crop by remember { mutableStateOf(settings.comicCropBorders) }

    // Zoom and position live here rather than in the page, so a page turn cannot leave the next page
    // scrolled to the middle of nowhere.
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    // Animated rather than assigned: a page that jumps a screen with no motion gives you nothing to
    // follow, and finding your place again after every notch is most of the work of reading.
    val scroll = remember { Animatable(0f) }
    var maxScroll by remember { mutableFloatStateOf(0f) }
    // How far the page is displaced while it arrives. A turn is a continuation of the same downward
    // movement, so the next page comes *up from the bottom* rather than appearing whole.
    val enter = remember { Animatable(0f) }
    // Filled in by the layout below. The wheel and the page turn both need it, and both run outside
    // the layout scope.
    var viewportHeight by remember { mutableFloatStateOf(0f) }

    /**
     * Move through the book.
     *
     * Forwards lands at the top of the next page, backwards at the *bottom* of the previous one —
     * which is where you were reading when you left it, and the only landing that lets you turn back
     * to check something and carry straight on.
     *
     * While fitted to the width the page change is vertical: the pager jumps without its horizontal
     * animation and the new page slides in from the edge you were travelling towards, so a turn
     * looks like more of the same scroll rather than a different gesture. Whole-page mode keeps the
     * pager's sideways turn, which is what a page turn looks like when you can see the whole page.
     */
    val turnPage: (Int) -> Unit = { direction ->
        val next = (currentPage + direction).coerceIn(0, lastIndex)
        if (next != currentPage) {
            zoom = 1f
            pan = Offset.Zero
            scope.launch {
                if (fitWidth) {
                    enter.snapTo(if (direction > 0) viewportHeight else -viewportHeight)
                    pagerState.scrollToPage(toPosition(next))
                    // Backwards asks for the bottom before anything knows how tall the page is; the
                    // measurement clamps it as soon as it does.
                    scroll.snapTo(if (direction < 0) Float.MAX_VALUE else 0f)
                    enter.animateTo(0f, tween(PAGE_ENTER_MS, easing = FastOutSlowInEasing))
                } else {
                    scroll.snapTo(0f)
                    pagerState.animateScrollToPage(toPosition(next))
                }
            }
        }
    }

    /**
     * One notch of the wheel: one step down the page, or the next page when the page runs out.
     *
     * **Every step down a page is the same distance.** The page's overflow is divided into whole
     * steps of at most a screen each, so a page one and a half screens tall is two equal half-screen
     * steps rather than a screen and then a stub — and the last step always lands exactly on the
     * bottom. Uneven steps are the thing that makes a reader feel like it is guessing.
     */
    val scrollOrTurn: (Int) -> Unit = { direction ->
        val ideal = viewportHeight * (1f - SCREEN_OVERLAP)
        // `ideal` is zero until the first layout pass has happened; dividing by it makes the step a
        // denormal that slips past every guard below and turns a notch into nothing at all.
        val steps = if (maxScroll <= 0f || ideal <= 0f) 1 else ceil(maxScroll / ideal).toInt().coerceAtLeast(1)
        val step = if (steps > 0) maxScroll / steps else 0f
        val at = scroll.value
        if (direction > 0) {
            if (at >= maxScroll - 1f || step <= 0f) {
                turnPage(1)
            } else {
                scope.launch { scroll.animateTo((at + step).coerceAtMost(maxScroll), tween(SCROLL_MS, easing = FastOutSlowInEasing)) }
            }
        } else {
            if (at <= 1f || step <= 0f) {
                turnPage(-1)
            } else {
                scope.launch { scroll.animateTo((at - step).coerceAtLeast(0f), tween(SCROLL_MS, easing = FastOutSlowInEasing)) }
            }
        }
    }

    // `pointerInput` compares only its keys, so a lambda capturing `currentPage` would go stale the
    // moment the page changed without one of those keys changing with it — and tapping forward twice
    // would land on the same page both times.
    val turn by rememberUpdatedState(turnPage)

    LaunchedEffect(pagerState, bookId) {
        snapshotFlow { pagerState.currentPage }.collect { position ->
            repo.updateProgress(bookId, toPage(position))
            // A page reached by swiping never went through `turnPage`, so nothing had reset the
            // scroll: the new page would open wherever the last one had been scrolled to.
            zoom = 1f
            pan = Offset.Zero
            if (enter.value == 0f) scroll.snapTo(0f)
        }
    }

    val exit = {
        repo.updateProgress(bookId, currentPage)
        onBack()
    }
    BackHandler(onBack = exit)

    LaunchedEffect(zoom) { if (zoom <= 1f) pan = Offset.Zero }

    if (showChrome) {
        LaunchedEffect(showChrome, currentPage) {
            delay(CHROME_VISIBLE_MS)
            showChrome = false
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        val viewportW = constraints.maxWidth.toFloat()
        val viewportH = constraints.maxHeight.toFloat()
        LaunchedEffect(viewportH) { viewportHeight = viewportH }

        WheelPaging { direction -> scrollOrTurn(direction) }

        HorizontalPager(
            state = pagerState,
            // A zoomed page belongs to the fingers on it, not to the pager.
            userScrollEnabled = zoom <= 1f,
            modifier = Modifier.fillMaxSize(),
        ) { position ->
            val page = toPage(position)
            val active = position == pagerState.currentPage
            ComicPageCanvas(
                bookId = bookId,
                page = page,
                crop = crop,
                fitWidth = fitWidth,
                zoom = if (active) zoom else 1f,
                pan = if (active) pan else Offset.Zero,
                scrollY = if (active) scroll.value else 0f,
                enterOffset = if (active) enter.value else 0f,
                active = active,
                viewportW = viewportW,
                viewportH = viewportH,
                onMeasured = { limit ->
                    if (active) {
                        maxScroll = limit
                        // A page arriving from a backwards turn asked for its bottom before anything
                        // knew where that was.
                        if (scroll.value > limit) scope.launch { scroll.snapTo(limit) }
                    }
                },
            )
        }

        Box(
            Modifier
                .fillMaxSize()
                // Taps first: this detector never consumes a drag, so the gesture handler below and
                // the pager both still see them.
                .pointerInput(rtl, pageCount, zoom) {
                    detectTapGestures(
                        onDoubleTap = { zoom = if (zoom > 1f) 1f else DOUBLE_TAP_ZOOM },
                        onTap = { offset ->
                            val third = size.width / 3f
                            val forward = if (rtl) offset.x < third else offset.x > size.width - third
                            val back = if (rtl) offset.x > size.width - third else offset.x < third
                            when {
                                zoom > 1f -> showChrome = !showChrome
                                forward -> turn(1)
                                back -> turn(-1)
                                else -> showChrome = !showChrome
                            }
                        },
                    )
                }
                .pointerInput(fitWidth) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var vertical = false
                        var dragTarget = scroll.value
                        do {
                            val event = awaitPointerEvent()
                            val moving = event.changes.any { it.positionChanged() }
                            if (event.changes.size >= 2) {
                                // Two fingers is always a zoom, whatever the page is doing.
                                val factor = event.calculateZoom()
                                val centroid = event.calculateCentroid(useCurrent = false)
                                val move = event.calculatePan()
                                if (factor != 1f || move != Offset.Zero) {
                                    val before = zoom
                                    zoom = (zoom * factor).coerceIn(1f, MAX_ZOOM)
                                    // Zoom about the fingers, not about the middle of the screen:
                                    // pinching on a speech bubble should keep that bubble under the
                                    // fingers rather than sending it off the edge.
                                    val ratio = zoom / before
                                    pan = (pan + centroid - Offset(size.width / 2f, size.height / 2f)) *
                                        ratio - (centroid - Offset(size.width / 2f, size.height / 2f)) + move
                                    event.changes.forEach { it.consume() }
                                }
                            } else if (moving) {
                                val drag = event.changes.first()
                                val delta = drag.position - drag.previousPosition
                                val canScroll = maxScroll > 0f
                                // A single finger scrolls the page when there is page to scroll, and
                                // is left alone otherwise so the pager can turn a page with it.
                                if (zoom > 1f) {
                                    pan += delta
                                    drag.consume()
                                } else if ((vertical || abs(delta.y) > abs(delta.x)) && canScroll) {
                                    if (!vertical) dragTarget = scroll.value
                                    vertical = true
                                    // The target is absolute, not a delta: `Animatable` mutations go
                                    // through a mutex that cancels the previous one, so a fast drag
                                    // would otherwise drop whichever deltas lost that race.
                                    dragTarget = (dragTarget - delta.y).coerceIn(0f, maxScroll)
                                    scope.launch { scroll.snapTo(dragTarget) }
                                    drag.consume()
                                }
                            }
                        } while (event.changes.any { it.pressed })
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
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(colors.background)
                    .align(Alignment.BottomCenter),
            ) {
                LightText(
                    text = "${currentPage + 1} / $pageCount" + if (rtl) "  ←" else "",
                    variant = LightTextVariant.Superfine,
                    lighten = true,
                    align = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = lightInset(), vertical = 6f.designVerticalPxToDp()),
                )
                // The two things worth changing while reading, and nothing else. Both are remembered
                // as the default for the next book you open.
                LightBottomBar(
                    items = listOf(
                        LightBarItem.Text(
                            text = if (fitWidth) "FIT WIDTH" else "FIT PAGE",
                            active = fitWidth,
                            onClick = {
                                fitWidth = !fitWidth
                                zoom = 1f
                                pan = Offset.Zero
                                scope.launch { scroll.snapTo(0f) }
                                settingsRepo.update { it.copy(comicFitWidth = fitWidth) }
                            },
                        ),
                        LightBarItem.Text(
                            text = "CROP",
                            active = crop,
                            onClick = {
                                crop = !crop
                                scope.launch { scroll.snapTo(0f) }
                                settingsRepo.update { it.copy(comicCropBorders = crop) }
                            },
                        ),
                    ),
                )
            }
        }
    }
}

/**
 * One page, drawn by hand.
 *
 * A `Canvas` rather than an `Image` because four things have to agree about the same rectangle —
 * the crop, the fit, the zoom and the scroll — and expressing them as one source rect and one
 * destination rect is the only version of this that can be reasoned about. Nested `ContentScale` and
 * `graphicsLayer` transforms multiply in an order that is easy to get subtly wrong and impossible to
 * debug on a phone.
 */
@Composable
private fun ComicPageCanvas(
    bookId: String,
    page: Int,
    crop: Boolean,
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
     * Whether this is the page being read. A page composes while it is still off screen, so the
     * measurement has to be re-reported when it becomes the live one — otherwise the scroll limit
     * belongs to the page you just left, and the first notch on a taller page turns straight past it.
     */
    active: Boolean,
    viewportW: Float,
    viewportH: Float,
    onMeasured: (maxScroll: Float) -> Unit,
) {
    val context = LocalContext.current
    val key = "$bookId#$page"
    var loaded by remember(key) { mutableStateOf(PageCache.get(key)) }

    LaunchedEffect(key) {
        if (loaded != null) return@LaunchedEffect
        val result = withContext(Dispatchers.IO) {
            val file = ComicPages.pageFile(context, bookId, page)
            if (!file.exists()) return@withContext null
            runCatching {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return@runCatching null
                // Measured once, here, while the pixels are already in hand — and kept with the
                // page, so toggling the crop back and forth costs nothing.
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

    val source = if (crop) image.bounds else PageCrop.Bounds(0, 0, image.image.width, image.image.height)

    // All of the arithmetic happens in composition, never in the draw scope: reporting the scroll
    // limit from inside `Canvas` would be a state write during drawing, which is how a reader ends
    // up in an invalidation loop that only shows itself on a real device.
    val srcW = source.width.toFloat().coerceAtLeast(1f)
    val srcH = source.height.toFloat().coerceAtLeast(1f)
    val base = if (fitWidth) viewportW / srcW else minOf(viewportW / srcW, viewportH / srcH)
    val scale = base * zoom
    val drawW = srcW * scale
    val drawH = srcH * scale
    val limit = max(0f, drawH - viewportH)

    LaunchedEffect(limit, active) { if (active) onMeasured(limit) }

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
            // Bilinear: a zoomed page is scaled *up* past the panel's own resolution, and
            // nearest-neighbour on line art at 2x looks like a fax.
            filterQuality = FilterQuality.Low,
        )
    }
}

private data class LoadedPage(val image: ImageBitmap, val bounds: PageCrop.Bounds)

private object PageCache {
    private val cache = LruCache<String, LoadedPage>(5)
    fun get(key: String): LoadedPage? = cache.get(key)
    fun put(key: String, value: LoadedPage) = cache.put(key, value)
}

/**
 * Turn the wheel into one step per notch-and-a-bit.
 *
 * Same three notches per step as the text reader: the sensor fires roughly every 35ms, so one notch
 * per step sends a deliberate quarter-turn of the thumb through half a chapter.
 */
@Composable
private fun WheelPaging(onStep: (Int) -> Unit) {
    var banked by remember { mutableIntStateOf(0) }
    var lastNotchAtMs by remember { mutableLongStateOf(0L) }
    var pending by remember { mutableIntStateOf(0) }
    val step by rememberUpdatedState(onStep)

    WheelSteps(notchesPerStep = 1, minIntervalMs = 0) { notch ->
        val now = System.currentTimeMillis()
        val stale = now - lastNotchAtMs > WHEEL_IDLE_MS
        val reversed = banked != 0 && (banked > 0) != (notch > 0)
        lastNotchAtMs = now
        banked = if (stale || reversed) notch else banked + notch
        if (abs(banked) >= NOTCHES_PER_STEP) {
            pending = if (banked > 0) 1 else -1
            banked = 0
        }
    }
    LaunchedEffect(pending) {
        if (pending == 0) return@LaunchedEffect
        step(pending)
        pending = 0
    }
}

/** How much of the previous screen stays visible after a scroll, so a line is never cut in half. */
private const val SCREEN_OVERLAP = 0.08f

/**
 * Long enough to see which way the page went, short enough that it never feels like waiting. The
 * page turn is a touch quicker than a scroll step: it covers a whole screen rather than part of one.
 */
private const val SCROLL_MS = 220
private const val PAGE_ENTER_MS = 190

private const val DOUBLE_TAP_ZOOM = 2.5f
private const val MAX_ZOOM = 6f
private const val NOTCHES_PER_STEP = 3
private const val WHEEL_IDLE_MS = 1_500L
private const val CHROME_VISIBLE_MS = 4_000L
