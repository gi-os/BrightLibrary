package com.lightfastread.ui.comic

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
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
import com.lightfastread.data.SeriesTitle
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
 *
 * **4-koma mode** takes a page printed as two strips side by side and reads them as two pages: the
 * right strip first in a Japanese book, then the left, each one tall image taken in four scrolls.
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

    // Whether a book is four strips to a page is a fact about the series, not a preference, so it is
    // remembered against the series rather than globally. A book with no series keys on its own title.
    val seriesKey = remember(book.id, book.title) {
        SeriesTitle.key(SeriesTitle.parse(book.title)?.series ?: book.title)
    }

    var options by remember {
        mutableStateOf(
            ComicOptions(
                fitWidth = settings.comicFitWidth,
                crop = settings.comicCropBorders,
                fourKoma = seriesKey in settings.comicFourKomaSeries,
                tapToTurn = settings.comicTapToTurn,
            )
        )
    }
    var showChrome by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    // In 4-koma mode a page is two strips printed side by side, each four panels tall. The reader
    // counts in *strips* and only converts back to pages when it has to — for the page number, and for
    // where the book is left.
    //
    // Reading order is not left to right. A Japanese book is read right column first, so the strip you
    // are on has to be turned into a physical column: strip 0 is the rightmost.
    val fourKoma = options.fourKoma
    val perPage = if (fourKoma) FOURKOMA_COLUMNS else 1
    val slots = pageCount * perPage

    // Pages with no gutter down the middle: a splash, a chapter break, a spread. They are shown whole,
    // and their second strip does not exist, so moving through the book steps over it. Learned as pages
    // are measured rather than up front — measuring a 200-page volume to open it would be absurd.
    //
    // A `Set` in one state holder rather than a `SnapshotStateList`: `MutableList<Int>.remove(Int)` is
    // ambiguous with `removeAt(Int)` and will not compile, and a set is what this is anyway.
    var unsplittable by remember(bookId) { mutableStateOf(emptySet<Int>()) }
    fun pageOf(slot: Int) = slot / perPage
    fun columnOf(slot: Int): Int {
        if (!fourKoma) return -1
        val strip = slot % perPage
        return if (rtl) perPage - 1 - strip else strip
    }
    fun stripOf(slot: Int) = if (fourKoma) slot % perPage else -1

    /**
     * The next slot in reading order, skipping strips that do not exist.
     *
     * A page without a gutter has one strip, not two. Until it has been measured nothing knows that,
     * so its second slot is simply the whole page again — harmless, and corrected the moment the
     * measurement lands.
     */
    fun stepSlot(from: Int, direction: Int): Int {
        var candidate = from + direction
        while (candidate in 0 until slots) {
            if (!fourKoma || stripOf(candidate) == 0 || pageOf(candidate) !in unsplittable) return candidate
            candidate += direction
        }
        return from
    }

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
     * One screen at a time. A 4-koma strip usually needs none — it is wide and short, so it fits — and
     * the notch simply moves to the next strip. A strip that *is* taller than two screens gets at
     * least [MIN_TALL_STEPS] steps rather than two enormous jumps.
     *
     * A function rather than a derived value because the measurement arrives in a callback, and the
     * callback needs the answer *then* — deferring it to a keyed effect meant a page whose height
     * matched the previous one never recomputed at all.
     */
    fun stepsFor(limit: Float, height: Float): Int {
        if (limit <= 0f || viewportHeight <= 0f) return 0
        // A 4-koma strip is one image read from top to bottom in a fixed number of stops. Three
        // scrolls, which is four resting points — one per panel — because the first panel is where you
        // start rather than somewhere you scroll to. Dividing by screenfuls instead would land the
        // panel boundaries wherever the screen happened to end.
        //
        // Only while the page is actually split into two columns, though. A page with no gutter —
        // crop denied it a strip — is shown whole, at fit-width, and a whole page is short: it steps
        // like any other page, which is usually one scroll, not the fixed four stops meant for a tall
        // narrow column.
        if (fourKoma && pageOf(slot) !in unsplittable) return FOURKOMA_SCROLLS
        val ideal = viewportHeight * (1f - SCREEN_OVERLAP)
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
        val next = stepSlot(slot, direction)
        if (next != slot && viewportHeight > 0f) {
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
                // The dim runs longer than the slide on purpose: it is still deepening when the page
                // underneath is covered, so what you see is a gradual darkening rather than something
                // that arrives at full black and waits there.
                launch { dim.animateTo(DIM_MAX, tween(DIM_MS, easing = LinearEasing)) }
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
                part = columnOf(leaving.slot),
                parts = perPage,
                fitWidth = options.fitWidth,
                zoom = 1f,
                pan = Offset.Zero,
                scrollY = leaving.scroll,
                enterOffset = 0f,
                active = false,
                viewportW = viewportW,
                viewportH = viewportH,
                onMeasured = { _, _, _ -> },
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
            part = columnOf(slot),
            parts = perPage,
            fitWidth = options.fitWidth,
            zoom = zoom,
            pan = pan,
            scrollY = scroll.value,
            enterOffset = enter.value,
            active = true,
            viewportW = viewportW,
            viewportH = viewportH,
            onMeasured = { limit, height, splittable ->
                maxScroll = limit
                contentHeight = height
                val page = pageOf(slot)
                unsplittable = if (splittable) unsplittable - page else unsplittable + page
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
                    text = pageLabel(
                        page = pageOf(slot),
                        part = if (pageOf(slot) in unsplittable) -1 else stripOf(slot),
                        parts = perPage,
                        pageCount = pageCount,
                        steps = steps,
                        stepIndex = stepIndex,
                        rtl = rtl,
                    ),
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
                    val page = if (wasFourKoma) slot / FOURKOMA_COLUMNS else slot
                    slot = if (updated.fourKoma) page * FOURKOMA_COLUMNS else page
                }
                settingsRepo.update { current ->
                    current.copy(
                        comicFitWidth = updated.fitWidth,
                        comicCropBorders = updated.crop,
                        comicTapToTurn = updated.tapToTurn,
                        // Per series: every other volume of this series opens the same way, and nothing
                        // else changes.
                        comicFourKomaSeries = if (updated.fourKoma) {
                            current.comicFourKomaSeries + seriesKey
                        } else {
                            current.comicFourKomaSeries - seriesKey
                        },
                    )
                }
            },
            onDismiss = { showSettings = false },
        )
    }
}

/** The page, which strip of it, and how far down — everything the counter can usefully say. */
private fun pageLabel(
    page: Int,
    part: Int,
    parts: Int,
    pageCount: Int,
    steps: Int,
    stepIndex: Int,
    rtl: Boolean,
): String = buildString {
    append("${page + 1} / $pageCount")
    if (part >= 0 && parts > 1) append("  strip ${part + 1}/$parts")
    if (steps > 0) append("  ·  ${(stepIndex + 1).coerceAtMost(steps + 1)}/${steps + 1}")
    if (rtl) append("  ←")
}

/** The page being covered, and where it was when you left it. */
private data class Outgoing(val slot: Int, val scroll: Float)

/**
 * Turn the wheel into steps.
 *
 * Two notches per step: the sensor fires roughly every 35-60ms per detent, and three notches made a
 * single deliberate roll of the thumb feel like it wasn't registering. Two is still enough to reject
 * one stray notch, since a real turn arrives in a burst and a lone one does not.
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

/**
 * A yonkoma page prints **two** strips side by side, each four panels tall.
 *
 * So the page divides into two columns, not into panels — and the columns are read right one first in
 * a Japanese book. Each column is then one tall image, taken in [FOURKOMA_SCROLLS] stops.
 */
private const val FOURKOMA_COLUMNS = 2

/**
 * Scrolls per strip — *moves*, not positions.
 *
 * Three moves is four resting points, one for each panel of a yonkoma: you begin on the first and
 * scroll to the other three.
 */
private const val FOURKOMA_SCROLLS = 3

/** A strip taller than two screens gets at least this many steps rather than two big jumps. */
private const val MIN_TALL_STEPS = 4

/** How far sideways a drag has to go to count as a page turn. */
private const val SWIPE_FRACTION = 0.25f

/** How dark the page being left behind goes while the next one covers it. */
private const val DIM_MAX = 0.55f

/**
 * Slow enough to follow, quick enough that it never feels like waiting.
 *
 * The page slide is the slowest of the three: it travels a whole screen, and a turn that snaps is the
 * one thing that makes you lose your place. The dim outlasts it so the darkening still reads as a fade
 * rather than as a state.
 */
private const val SCROLL_MS = 260
private const val PAGE_ENTER_MS = 340
private const val DIM_MS = 420
private const val DOUBLE_TAP_ZOOM = 2.5f
private const val MAX_ZOOM = 6f
private const val NOTCHES_PER_STEP = 2
private const val WHEEL_IDLE_MS = 1_500L
private const val CHROME_VISIBLE_MS = 4_000L
