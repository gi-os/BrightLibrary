package com.lightfastread.ui.comic

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.gios.light.common.hw.WheelSteps
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Reading a comic.
 *
 * **Fitted to the width and scrolled**, because a page drawn for seven-inch paper shown whole on a
 * 3.9" panel is a demonstration that the page exists rather than a way to read it. One wheel notch is
 * one step down; the notch after the last step turns the page.
 *
 * **The transition is hand-rolled rather than a `HorizontalPager`, and that is the point.** A pager
 * animates its own way — sideways, with the space between pages showing through as a black frame — and
 * it owns the scroll position, which made turning backwards land in the wrong place. Here the page you
 * are leaving stays exactly where it was and *darkens* while the new one slides over it in the
 * direction you were already travelling. Nothing is ever visible between two pages because there is
 * never a gap: the outgoing page is only dropped once the incoming one covers it.
 *
 * Steps are counted, not measured. Each page's overflow is divided into a whole number of equal steps
 * and the reader moves between step *indices*, so no amount of scrolling can leave you half a step
 * from the bottom, and the last step always lands exactly on it.
 */
@Composable
fun ComicReader(bookId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { BookRepository.get(context) }
    val settingsRepo = remember { SettingsRepository.get(context) }
    val settings by settingsRepo.state
    val book = remember(bookId) { repo.getBook(bookId) }
    val colors = LightThemeTokens.colors
    val scope = rememberCoroutineScope()

    if (book == null || book.totalWords <= 0) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val pageCount = book.totalWords
    val rtl = book.rightToLeft

    var options by remember {
        mutableStateOf(
            ComicOptions(
                fitWidth = settings.comicFitWidth,
                crop = settings.comicCropBorders,
                fourKoma = settings.comicFourKoma,
                tapToTurn = settings.comicTapToTurn,
            )
        )
    }
    var showChrome by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    // In 4-koma mode every page is two, so the reader counts in *halves* and only converts back to
    // pages when it has to — for the page number, and for where the book is left.
    val fourKoma = options.fourKoma
    val perPage = if (fourKoma) 2 else 1
    val slots = pageCount * perPage
    fun pageOf(slot: Int) = slot / perPage
    fun halfOf(slot: Int) = if (fourKoma) slot % perPage else -1

    // Deliberately *not* keyed on `fourKoma`: the settings callback converts the slot itself when the
    // mode changes, and re-keying here would throw that away and jump back to wherever the book was
    // opened — which `LaunchedEffect(slot)` would then save as your place.
    var slot by remember {
        mutableIntStateOf((book.currentWordIndex.coerceIn(0, pageCount - 1)) * perPage)
    }
    /** The page being covered up, if a turn is in flight. Never null while one is. */
    var outgoing by remember { mutableStateOf<Outgoing?>(null) }

    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val scroll = remember { Animatable(0f) }
    /** Offset of the arriving page, animated to zero. */
    val enter = remember { Animatable(0f) }
    /** How dark the page being left behind goes as it is covered. */
    val dim = remember { Animatable(0f) }

    var maxScroll by remember { mutableFloatStateOf(0f) }
    var contentHeight by remember { mutableFloatStateOf(0f) }
    var stepIndex by remember { mutableIntStateOf(0) }
    var landAtBottom by remember { mutableStateOf(false) }
    var viewportHeight by remember { mutableFloatStateOf(0f) }

    /**
     * How many steps a page of this shape is worth.
     *
     * One screen at a time normally. In 4-koma mode the strips are wide and short, so a half fills the
     * screen twice over and half-screen steps read better — and a half taller than two screens is
     * given at least [MIN_TALL_STEPS] of them, so a long strip is never two enormous jumps.
     *
     * A function rather than a derived value because the measurement arrives in a callback, and the
     * callback needs the answer *then* — deferring it to a keyed effect meant a page whose height
     * matched the previous one never recomputed at all.
     */
    fun stepsFor(limit: Float, height: Float): Int {
        val ideal = viewportHeight * (if (fourKoma) FOURKOMA_STEP else (1f - SCREEN_OVERLAP))
        if (limit <= 0f || ideal <= 0f) return 0
        val n = ceil(limit / ideal).toInt().coerceAtLeast(1)
        return if (height > viewportHeight * 2f) maxOf(n, MIN_TALL_STEPS) else n
    }

    val steps = stepsFor(maxScroll, contentHeight)
    val stepSize = if (steps > 0) maxScroll / steps else 0f
    // The gesture handler runs in a coroutine that outlives the composition it started in, so these
    // two derived values have to be read through state or it keeps the ones from the first frame —
    // when the viewport was still zero and every page was worth no steps at all.
    val stepsNow by rememberUpdatedState(steps)
    val stepSizeNow by rememberUpdatedState(stepSize)

    val turnTo: (Int) -> Unit = { direction ->
        val next = slot + direction
        if (next in 0 until slots && viewportHeight > 0f) {
            val leaving = Outgoing(slot, scroll.value)
            zoom = 1f
            pan = Offset.Zero
            landAtBottom = direction < 0
            outgoing = leaving
            slot = next
            scope.launch {
                enter.snapTo(if (direction > 0) viewportHeight else -viewportHeight)
                scroll.snapTo(0f)
                stepIndex = 0
                dim.snapTo(0f)
                launch { dim.animateTo(DIM_MAX, tween(PAGE_ENTER_MS)) }
                enter.animateTo(0f, tween(PAGE_ENTER_MS, easing = FastOutSlowInEasing))
                // Only now is the page underneath invisible, so only now can it go.
                outgoing = null
                dim.snapTo(0f)
            }
        }
    }
    val turn by rememberUpdatedState(turnTo)

    /** One notch: the next step down this page, or the next page when the steps run out. */
    val scrollOrTurn: (Int) -> Unit = { direction ->
        if (direction > 0) {
            if (stepIndex >= steps) {
                turn(1)
            } else {
                stepIndex += 1
                val target = stepSize * stepIndex
                scope.launch { scroll.animateTo(target, tween(SCROLL_MS, easing = FastOutSlowInEasing)) }
            }
        } else {
            if (stepIndex <= 0) {
                turn(-1)
            } else {
                stepIndex -= 1
                val target = stepSize * stepIndex
                scope.launch { scroll.animateTo(target, tween(SCROLL_MS, easing = FastOutSlowInEasing)) }
            }
        }
    }

    LaunchedEffect(slot, fourKoma) { repo.updateProgress(bookId, pageOf(slot)) }
    LaunchedEffect(zoom) { if (zoom <= 1f) pan = Offset.Zero }

    val exit = {
        repo.updateProgress(bookId, pageOf(slot))
        onBack()
    }
    BackHandler(onBack = exit)

    if (showChrome) {
        LaunchedEffect(showChrome, slot) {
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

        // The page being left behind: still exactly where it was, going dark under the new one.
        outgoing?.let { leaving ->
            ComicPageCanvas(
                bookId = bookId,
                page = pageOf(leaving.slot),
                crop = options.crop,
                half = halfOf(leaving.slot),
                fitWidth = options.fitWidth,
                zoom = 1f,
                pan = Offset.Zero,
                scrollY = leaving.scroll,
                enterOffset = 0f,
                active = false,
                viewportW = viewportW,
                viewportH = viewportH,
                onMeasured = { _, _ -> },
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = dim.value)),
            )
        }

        ComicPageCanvas(
            bookId = bookId,
            page = pageOf(slot),
            crop = options.crop,
            half = halfOf(slot),
            fitWidth = options.fitWidth,
            zoom = zoom,
            pan = pan,
            scrollY = scroll.value,
            enterOffset = enter.value,
            active = true,
            viewportW = viewportW,
            viewportH = viewportH,
            onMeasured = { limit, height ->
                maxScroll = limit
                contentHeight = height
                if (landAtBottom) {
                    landAtBottom = false
                    // Turning backwards lands at the *bottom* of the previous page, which is where you
                    // were reading when you left it — and nothing knew how tall the page was until
                    // this callback, so the step count is worked out here from what it just said.
                    stepIndex = stepsFor(limit, height)
                    scope.launch { scroll.snapTo(limit) }
                } else if (scroll.value > limit) {
                    stepIndex = stepsFor(limit, height)
                    scope.launch { scroll.snapTo(limit) }
                }
            },
        )

        // A safety net only: the step count is set where the measurement arrives, and this catches a
        // page that changes shape underneath the reader — a crop toggled, or the mode switched.
        LaunchedEffect(steps, maxScroll) {
            if (stepIndex > steps) {
                stepIndex = steps
                scroll.snapTo(maxScroll)
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(rtl, options.tapToTurn, zoom) {
                    detectTapGestures(
                        onDoubleTap = { zoom = if (zoom > 1f) 1f else DOUBLE_TAP_ZOOM },
                        onTap = { offset ->
                            val third = size.width / 3f
                            val forward = if (rtl) offset.x < third else offset.x > size.width - third
                            val back = if (rtl) offset.x > size.width - third else offset.x < third
                            when {
                                // Turning by tap is optional: it is the gesture most easily made by
                                // accident, and with the wheel to hand it is not the only way.
                                !options.tapToTurn -> showChrome = !showChrome
                                zoom > 1f -> showChrome = !showChrome
                                forward -> turn(1)
                                back -> turn(-1)
                                else -> showChrome = !showChrome
                            }
                        },
                    )
                }
                .pointerInput(options.fitWidth, rtl) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var vertical = false
                        var horizontal = false
                        var dragTarget = scroll.value
                        var swipe = 0f
                        do {
                            val event = awaitPointerEvent()
                            val moving = event.changes.any { it.positionChanged() }
                            if (event.changes.size >= 2) {
                                val factor = event.calculateZoom()
                                val centroid = event.calculateCentroid(useCurrent = false)
                                val move = event.calculatePan()
                                if (factor != 1f || move != Offset.Zero) {
                                    val before = zoom
                                    zoom = (zoom * factor).coerceIn(1f, MAX_ZOOM)
                                    val ratio = zoom / before
                                    val middle = Offset(size.width / 2f, size.height / 2f)
                                    // Zoom about the fingers: pinching on a speech bubble should keep
                                    // that bubble under the fingers, not send it off the edge.
                                    pan = (pan + centroid - middle) * ratio - (centroid - middle) + move
                                    event.changes.forEach { it.consume() }
                                }
                            } else if (moving) {
                                val drag = event.changes.first()
                                val delta = drag.position - drag.previousPosition
                                if (zoom > 1f) {
                                    pan += delta
                                    drag.consume()
                                } else if (!horizontal && (vertical || abs(delta.y) > abs(delta.x)) && maxScroll > 0f) {
                                    if (!vertical) dragTarget = scroll.value
                                    vertical = true
                                    // The target is absolute rather than a delta: `Animatable` puts
                                    // mutations through a mutex that cancels the one before, so a fast
                                    // drag would otherwise drop whichever deltas lost that race.
                                    dragTarget = (dragTarget - delta.y).coerceIn(0f, maxScroll)
                                    scope.launch { scroll.snapTo(dragTarget) }
                                    drag.consume()
                                } else if (!vertical) {
                                    horizontal = true
                                    swipe += delta.x
                                    drag.consume()
                                }
                            }
                        } while (event.changes.any { it.pressed })

                        if (vertical && stepSizeNow > 0f) {
                            // A drag lands between steps; the step it is nearest to becomes the one it
                            // is on, so the next notch is a whole step rather than a stub.
                            stepIndex = (scroll.value / stepSizeNow).roundToInt().coerceIn(0, stepsNow)
                        }
                        if (horizontal && abs(swipe) > size.width * SWIPE_FRACTION) {
                            // A swipe still turns pages, in the direction the book runs.
                            val forward = if (rtl) swipe > 0 else swipe < 0
                            turn(if (forward) 1 else -1)
                        }
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
                    text = pageLabel(pageOf(slot), halfOf(slot), pageCount, steps, stepIndex, rtl),
                    variant = LightTextVariant.Superfine,
                    lighten = true,
                    align = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = lightInset(), vertical = 6f.designVerticalPxToDp()),
                )
                LightBottomBar(
                    items = listOf(
                        LightBarItem.Text(
                            text = if (options.fitWidth) "FIT WIDTH" else "FIT PAGE",
                            active = options.fitWidth,
                            onClick = {
                                options = options.copy(fitWidth = !options.fitWidth)
                                zoom = 1f
                                pan = Offset.Zero
                                stepIndex = 0
                                scope.launch { scroll.snapTo(0f) }
                                settingsRepo.update { it.copy(comicFitWidth = options.fitWidth) }
                            },
                        ),
                        LightBarItem.Text(text = "SETTINGS", onClick = { showSettings = true }),
                    ),
                )
            }
        }
    }

    if (showSettings) {
        ComicSettings(
            options = options,
            onChange = { updated ->
                val wasFourKoma = options.fourKoma
                options = updated
                zoom = 1f
                pan = Offset.Zero
                stepIndex = 0
                scope.launch { scroll.snapTo(0f) }
                // Switching 4-koma mode changes what a slot means, so the reader has to be told where
                // it now is — the top half of the page it was on.
                if (updated.fourKoma != wasFourKoma) {
                    val page = if (wasFourKoma) slot / 2 else slot
                    slot = if (updated.fourKoma) page * 2 else page
                }
                settingsRepo.update {
                    it.copy(
                        comicFitWidth = updated.fitWidth,
                        comicCropBorders = updated.crop,
                        comicFourKoma = updated.fourKoma,
                        comicTapToTurn = updated.tapToTurn,
                    )
                }
            },
            onDismiss = { showSettings = false },
        )
    }
}

/** The page you are on, which half of it, and how far down — everything the counter can usefully say. */
private fun pageLabel(
    page: Int,
    half: Int,
    pageCount: Int,
    steps: Int,
    stepIndex: Int,
    rtl: Boolean,
): String = buildString {
    append("${page + 1} / $pageCount")
    if (half == 0) append(" top") else if (half == 1) append(" bottom")
    if (steps > 0) append("  ·  ${(stepIndex + 1).coerceAtMost(steps + 1)}/${steps + 1}")
    if (rtl) append("  ←")
}

/** The page being covered, and where it was when you left it. */
private data class Outgoing(val slot: Int, val scroll: Float)

/**
 * Turn the wheel into steps.
 *
 * Three notches per step: the sensor fires roughly every 35ms, so one notch per step sends a
 * deliberate quarter-turn of the thumb through half a chapter.
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

/** How much of the previous screen stays visible after a step, so a line is never cut in half. */
private const val SCREEN_OVERLAP = 0.08f

/** 4-koma strips are short and wide, so half a screen at a time reads better than a whole one. */
private const val FOURKOMA_STEP = 0.5f

/** A strip taller than two screens gets at least this many steps rather than two big jumps. */
private const val MIN_TALL_STEPS = 4

/** How far sideways a drag has to go to count as a page turn. */
private const val SWIPE_FRACTION = 0.25f

/** How dark the page being left behind goes while the next one covers it. */
private const val DIM_MAX = 0.55f

private const val SCROLL_MS = 220
private const val PAGE_ENTER_MS = 210
private const val DOUBLE_TAP_ZOOM = 2.5f
private const val MAX_ZOOM = 6f
private const val NOTCHES_PER_STEP = 3
private const val WHEEL_IDLE_MS = 1_500L
private const val CHROME_VISIBLE_MS = 4_000L
