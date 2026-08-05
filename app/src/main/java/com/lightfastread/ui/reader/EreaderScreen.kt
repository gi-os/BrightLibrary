package com.lightfastread.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lightfastread.data.TitleStyle
import com.gios.light.common.hw.WheelInDialog
import com.gios.light.common.hw.WheelSteps
import com.lightfastread.ui.light.LightBarItem
import com.lightfastread.ui.light.LightIcons
import com.lightfastread.ui.light.LightRule
import com.lightfastread.ui.light.LightText
import com.lightfastread.ui.light.LightTextVariant
import com.lightfastread.ui.light.LightThemeTokens
import com.lightfastread.ui.light.LightTopBar
import com.lightfastread.ui.light.designVerticalPxToDp
import com.lightfastread.ui.light.lightInset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

// Full-page reading mode, opened by tapping the three-line context preview above
// the RSVP word (see ReaderScreen's context-band tap handling).
//
// Page turning is Compose Foundation's own HorizontalPager. Earlier attempts at a
// hand-built gesture (a 3D rotationY flip, then a hand-drawn 2D page fold) each
// shipped a different bug - reversed direction, several pages skipped per swipe,
// two pages' text drawn over each other. A Pager holds one page per slot, its
// default fling is limited to a single adjacent page, and its default transition
// is a plain slide, so there is no custom animation or gesture arithmetic left to
// get wrong.
//
// Where the pages *come from* is in EreaderPagination.kt, and it is measured
// rather than estimated - see the comment there for why estimating silently
// deleted text.

/** Words per measuring block. ~8k characters: big enough to amortise, small
 *  enough that no single layout pass is expensive. */
private const val PROBE_WORDS = 1500

/**
 * How far a slow drag must travel before the page turns, as a fraction of the
 * page width.
 *
 * A Pager's own default is 0.5 - past halfway and it commits. That is right for a
 * carousel you flick through, but a page turn wants more intent behind it than a
 * thumb resting on a 3.9" screen can supply by accident.
 *
 * Only *slow* drags are affected: a deliberate flick still turns on velocity
 * alone, whatever the distance. Kept short of 0.8 on purpose - past that, a drag
 * that visibly moved most of the page still springs back, which reads as the page
 * refusing to turn rather than as a firmer threshold.
 */
private const val PAGE_TURN_DRAG_FRACTION = 0.7f

/**
 * Wheel notches per page turn.
 *
 * The sensor emits a notch roughly every 35ms, so one notch per page made a
 * single deliberate turn of the wheel fly through a handful of pages. Three
 * notches is about a quarter-turn under the thumb: enough that a page turn is
 * something you meant to do.
 */
private const val NOTCHES_PER_PAGE = 3

/** A turn abandoned for this long starts counting again, matching light-common's own idle
 *  window, so a stray brush never banks toward a later real turn. */
private const val WHEEL_IDLE_MS = 1_500L

/**
 * How long the top bar stays up after a tap.
 *
 * Long enough to read the title and reach either end of the bar, short enough that a tap
 * meant for the page doesn't leave a bar sitting over the text for the rest of the chapter.
 */
private const val CHROME_VISIBLE_MS = 4_000L

/**
 * Pagination survives closing and reopening the reader.
 *
 * Measuring a whole novel takes a second or two, which is fine behind a progress
 * indicator the first time but insulting on every open. One entry is enough: it is
 * always the book being read, and the key covers everything that changes the
 * layout, so a font or size change correctly misses and re-measures.
 */
private object PageCache {
    private var key: String? = null
    private var pages: List<IntRange>? = null

    fun get(k: String): List<IntRange>? = if (k == key) pages else null

    fun put(k: String, value: List<IntRange>) {
        key = k
        pages = value
    }
}

@Composable
fun EreaderScreen(
    bookId: String,
    /** Shown in the top bar when it is up, so you can tell what you have open. */
    title: String,
    words: List<String>,
    paragraphBreakAfter: BooleanArray,
    isItalicWord: BooleanArray,
    isTitleWord: BooleanArray,
    titleColor: Color,
    titleStyle: TitleStyle,
    fontFamily: FontFamily,
    fontKey: String,
    fontSizeSp: Int,
    initialWordIndex: Int,
    /** Hand the reader over to the RSVP screen, at the page currently showing. */
    onFastRead: (wordIndex: Int) -> Unit,
    /** Leave the book entirely, at the page currently showing. */
    onExit: (wordIndex: Int) -> Unit,
) {
    val applyColor = titleStyle == TitleStyle.Color || titleStyle == TitleStyle.Both
    val applyUnderline = titleStyle == TitleStyle.Underline || titleStyle == TitleStyle.Both
    val safeFontSizeSp = fontSizeSp.coerceAtLeast(8)

    val marginDp: Dp = 20.dp
    // The top inset used to reserve room for the close button, but the button is
    // hidden until tapped now, so reserving a permanent 56dp for it just wasted
    // the top of every page. It gets an opaque backing instead (see
    // EreaderChrome) and floats over the text for the moment it is on screen.
    // The bottom still clears the always-visible page counter.
    val topPadDp: Dp = 8.dp
    val bottomPadDp: Dp = 40.dp

    // The one style used for both measuring and drawing. Any difference between
    // the two would reintroduce exactly the class of bug this rewrite fixes, so
    // there is deliberately only one of them.
    val bodyStyle = remember(fontFamily, safeFontSizeSp) {
        TextStyle(
            fontFamily = fontFamily,
            fontSize = safeFontSizeSp.sp,
            lineHeight = (safeFontSizeSp * 1.45f).sp,
        )
    }

    // cacheSize = 0: this measurer is used from a background thread during
    // pagination, and TextMeasurer's internal layout cache is not built for
    // concurrent use. Nothing else shares this instance.
    val measurer = rememberTextMeasurer(cacheSize = 0)

    var pages by remember(bookId) { mutableStateOf<List<IntRange>>(emptyList()) }
    var progress by remember(bookId) { mutableFloatStateOf(0f) }
    // The chrome is collapsed until asked for, so nothing sits over the text while
    // reading. This is the book's home screen now - opening a book comes straight
    // here - so the bar it reveals carries the way *out* as well: back to the shelf
    // on the left, over to the word reader on the right.
    var showChrome by remember(bookId) { mutableStateOf(false) }
    val pagerState = rememberPagerState(initialPage = 0) { pages.size }

    // Whichever page is showing decides where the word reader resumes, so leaving
    // always lands on the first word of the page actually on screen.
    val currentWord: () -> Int = {
        pages.getOrNull(pagerState.currentPage)?.first ?: initialWordIndex
    }
    val exit: () -> Unit = { onExit(currentWord()) }
    val fastRead: () -> Unit = { onFastRead(currentWord()) }

    // Chrome asked for and then left alone goes away again. Without this it stays over the
    // first line of every page you turn until you remember to tap it off.
    LaunchedEffect(showChrome) {
        if (showChrome) {
            delay(CHROME_VISIBLE_MS)
            showChrome = false
        }
    }

    Dialog(
        onDismissRequest = exit,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // A Dialog is its own window, so it has to pick the wheel up itself - the
        // Activity's dispatchKeyEvent never runs while this is on screen.
        WheelInDialog()

        // The wheel used to be wired straight into the pager as a pixel scroller,
        // which left it parked between two pages - a Pager only snaps at the end
        // of a *gesture*, and a notch is not one. Notches now name a target page
        // and the pager animates to it, so the wheel can only ever land on a page.
        //
        // It takes NOTCHES_PER_PAGE notches to move, because the sensor fires far
        // faster than a thumb expects and one-notch-per-page ran away with itself.
        var wheelTarget by remember { mutableStateOf<Int?>(null) }
        var notchesTowardTurn by remember { mutableIntStateOf(0) }
        var lastNotchAtMs by remember { mutableLongStateOf(0L) }
        //
        // `notchesPerStep = 1, minIntervalMs = 0` because the counting above is this screen's
        // own: light-common's WheelSteps banks notches and rate-limits them for row-at-a-time
        // focus movement, and layering that on top of NOTCHES_PER_PAGE would multiply the two
        // thresholds together and make a page turn arrive late. One callback per notch, as
        // fast as they come, is what this code was written against.
        WheelSteps(notchesPerStep = 1, minIntervalMs = 0) { step ->
            if (pages.isNotEmpty()) {
                val now = System.currentTimeMillis()
                // Reversing, or picking the wheel up again after a pause, starts
                // the count over rather than spending notches banked earlier.
                val stale = now - lastNotchAtMs > WHEEL_IDLE_MS
                val reversed = notchesTowardTurn != 0 && (notchesTowardTurn > 0) != (step > 0)
                lastNotchAtMs = now
                notchesTowardTurn = if (stale || reversed) step else notchesTowardTurn + step
                if (abs(notchesTowardTurn) >= NOTCHES_PER_PAGE) {
                    val direction = if (notchesTowardTurn > 0) 1 else -1
                    notchesTowardTurn = 0
                    val from = wheelTarget ?: pagerState.currentPage
                    wheelTarget = (from + direction).coerceIn(0, pages.size - 1)
                }
            }
        }
        LaunchedEffect(wheelTarget) {
            val target = wheelTarget ?: return@LaunchedEffect
            // A fast spin retargets while this is still animating; the effect
            // restarts, cancelling the old animation, so a spin resolves to one
            // travel to the latest page instead of a queue of single steps.
            pagerState.animateScrollToPage(target)
            if (wheelTarget == target) wheelTarget = null
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = LightThemeTokens.colors.background,
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val density = LocalDensity.current
                // Real available pixels from the actual dialog window, not
                // LocalConfiguration's idea of the screen - those disagree once
                // system insets are involved, and measuring against the wrong
                // height is another way to clip the last line.
                val textWidthPx = (constraints.maxWidth - with(density) { marginDp.toPx() } * 2f)
                    .roundToInt().coerceAtLeast(1)
                val textHeightPx = (
                    constraints.maxHeight -
                        with(density) { topPadDp.toPx() } -
                        with(density) { bottomPadDp.toPx() }
                    ).coerceAtLeast(1f)

                LaunchedEffect(bookId, textWidthPx, textHeightPx, fontKey, safeFontSizeSp) {
                    // Re-measuring (rotation, font change) must not lose the
                    // reader's place, so the current page's first word is the
                    // anchor to come back to.
                    val anchor = currentWord()
                    val cacheKey = "$bookId|${words.size}|$textWidthPx|" +
                        "${textHeightPx.roundToInt()}|$fontKey|$safeFontSizeSp"

                    val cached = PageCache.get(cacheKey)
                    val built = if (cached != null) {
                        cached
                    } else {
                        pages = emptyList()
                        progress = 0f
                        val probe = LineStartsProbe { from, to ->
                            measureLineStarts(
                                measurer, bodyStyle, textWidthPx, words, from, to,
                                paragraphBreakAfter, isItalicWord, isTitleWord,
                                titleColor, applyColor, applyUnderline,
                            )
                        }
                        // One measurement on this thread first: it settles how many
                        // lines really fit, and resolves the font here rather than
                        // on the worker below.
                        val linesPerPage = measureLinesPerPage(
                            measurer, bodyStyle, textWidthPx, textHeightPx, words,
                            paragraphBreakAfter, isItalicWord, isTitleWord,
                            titleColor, applyColor, applyUnderline,
                        )
                        val paginate = {
                            buildMeasuredPages(
                                wordCount = words.size,
                                linesPerPage = linesPerPage,
                                probeWords = PROBE_WORDS,
                                probe = probe,
                            ) { done ->
                                if ((done * 100).toInt() != (progress * 100).toInt()) {
                                    progress = done
                                }
                            }
                        }
                        // Laying out a novel is seconds of work, so it runs off the
                        // main thread. If a device objects to text layout on a
                        // worker it is retried here rather than left broken - but
                        // cancellation has to keep propagating, or closing the
                        // reader mid-pagination would be "caught" and then
                        // repeated synchronously on the main thread.
                        var measured: List<IntRange>
                        try {
                            measured = withContext(Dispatchers.Default) { paginate() }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (workerRefused: Throwable) {
                            measured = try {
                                paginate()
                            } catch (givenUp: Throwable) {
                                emptyList()
                            }
                        }
                        measured
                    }

                    if (built.isEmpty()) {
                        // Nothing to page through. Falling back to the word reader is better
                        // than throwing the reader out to the shelf: there is still text, it
                        // just could not be laid out into pages at this size.
                        onFastRead(anchor)
                        return@LaunchedEffect
                    }
                    PageCache.put(cacheKey, built)
                    pages = built
                    pagerState.scrollToPage(pageOfWord(built, anchor))
                }

                if (pages.isEmpty()) {
                    PaginatingIndicator(progress = progress)
                } else {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        key = { it },
                        flingBehavior = PagerDefaults.flingBehavior(
                            state = pagerState,
                            snapPositionalThreshold = PAGE_TURN_DRAG_FRACTION,
                        ),
                    ) { page ->
                        // The tap target lives on the page content rather than
                        // wrapping the Pager, so it never competes with the
                        // Pager's own drag handling.
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    detectTapGestures(onTap = { showChrome = !showChrome })
                                },
                        ) {
                            Text(
                                // Keyed on the page's own word range, not on the
                                // pages list: comparing a few-thousand-entry list
                                // on every recomposition of every visible page
                                // costs more than the text it guards.
                                text = remember(pages[page], titleColor) {
                                    buildRangeText(
                                        words, pages[page], paragraphBreakAfter,
                                        isItalicWord, isTitleWord, titleColor,
                                        applyColor, applyUnderline,
                                    )
                                },
                                style = bodyStyle,
                                color = LightThemeTokens.colors.content,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(
                                        start = marginDp,
                                        end = marginDp,
                                        top = topPadDp,
                                        bottom = bottomPadDp,
                                    ),
                            )
                        }
                    }
                }

                EreaderChrome(
                    title = title,
                    pageNumber = if (pages.isEmpty()) 0 else pagerState.currentPage + 1,
                    pageCount = pages.size,
                    showBar = showChrome,
                    onExit = exit,
                    onFastRead = fastRead,
                )
            }
        }
    }
}

/**
 * Laying the book out into pages, the first time.
 *
 * A percentage and a rule that fills, not a spinning circle: a Material indicator draws an
 * animating grey arc, which on an unlit OLED is the brightest thing on the screen and says less
 * than the number does.
 */
@Composable
private fun PaginatingIndicator(progress: Float) {
    val done = progress.coerceIn(0f, 1f)
    val colors = LightThemeTokens.colors
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = lightInset()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LightText("Laying out pages", LightTextVariant.Detail, lighten = true)
            Spacer(Modifier.height(8f.designVerticalPxToDp()))
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(3f.designVerticalPxToDp())
                    .background(colors.rule),
            ) {
                if (done > 0f) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(done)
                            .background(colors.content),
                    )
                }
            }
            Spacer(Modifier.height(8f.designVerticalPxToDp()))
            LightText("${(done * 100).toInt()}%", LightTextVariant.Superfine, lighten = true)
        }
    }
}

/**
 * Lays out `words[from..to]` and reports the first word of each visual line.
 *
 * Line starts come back as character offsets, so each is mapped to the first word
 * beginning at or after it. A blank line (the gap between paragraphs) therefore
 * reports the first word of the paragraph following it, which is why
 * [buildMeasuredPages] tolerates equal consecutive entries.
 */
private fun measureLineStarts(
    measurer: TextMeasurer,
    style: TextStyle,
    widthPx: Int,
    words: List<String>,
    from: Int,
    to: Int,
    paragraphBreakAfter: BooleanArray,
    isItalicWord: BooleanArray,
    isTitleWord: BooleanArray,
    titleColor: Color,
    applyColor: Boolean,
    applyUnderline: Boolean,
): IntArray {
    val offsets = IntArray(to - from + 1)
    val text = buildRangeText(
        words, from..to, paragraphBreakAfter, isItalicWord, isTitleWord,
        titleColor, applyColor, applyUnderline, offsets,
    )
    val layout = measurer.measure(
        text = text,
        style = style,
        softWrap = true,
        constraints = Constraints(maxWidth = widthPx),
    )
    val out = IntArray(layout.lineCount)
    for (line in 0 until layout.lineCount) {
        out[line] = from + firstWordAtOrAfter(offsets, layout.getLineStart(line))
    }
    return out
}

/** How many whole lines fit the available height, from real line metrics. */
private fun measureLinesPerPage(
    measurer: TextMeasurer,
    style: TextStyle,
    widthPx: Int,
    heightPx: Float,
    words: List<String>,
    paragraphBreakAfter: BooleanArray,
    isItalicWord: BooleanArray,
    isTitleWord: BooleanArray,
    titleColor: Color,
    applyColor: Boolean,
    applyUnderline: Boolean,
): Int {
    if (words.isEmpty()) return 1
    val to = (PROBE_WORDS - 1).coerceAtMost(words.size - 1)
    val text = buildRangeText(
        words, 0..to, paragraphBreakAfter, isItalicWord, isTitleWord,
        titleColor, applyColor, applyUnderline,
    )
    val layout = measurer.measure(
        text = text,
        style = style,
        softWrap = true,
        constraints = Constraints(maxWidth = widthPx),
    )
    if (layout.lineCount == 0) return 1
    val top = layout.getLineTop(0)
    var fit = 0
    for (line in 0 until layout.lineCount) {
        if (layout.getLineBottom(line) - top <= heightPx) fit = line + 1 else break
    }
    return fit.coerceAtLeast(1)
}

/** Index (relative to the block) of the first word starting at or after [offset]. */
private fun firstWordAtOrAfter(offsets: IntArray, offset: Int): Int {
    var lo = 0
    var hi = offsets.size - 1
    var found = offsets.size
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (offsets[mid] >= offset) {
            found = mid
            hi = mid - 1
        } else {
            lo = mid + 1
        }
    }
    return found
}

/**
 * The text of a word range, used for measuring *and* for drawing.
 *
 * Both callers going through this one function is the guarantee that the layout
 * pagination measured is the layout the reader draws. [wordStartOffsets], when
 * given, receives the character offset each word begins at, which is how measured
 * line starts are mapped back to word indices.
 *
 * A paragraph break after the range's last word is deliberately not emitted: a
 * trailing blank line would count against the page's line budget while showing
 * nothing.
 *
 * Unlike the RSVP display this needs no synthetic quote wrapping - the words
 * already carry their own punctuation, and reading several together supplies the
 * context a single flashed word has to fake.
 */
internal fun buildRangeText(
    words: List<String>,
    range: IntRange,
    paragraphBreakAfter: BooleanArray,
    isItalicWord: BooleanArray,
    isTitleWord: BooleanArray,
    titleColor: Color,
    applyColor: Boolean,
    applyUnderline: Boolean,
    wordStartOffsets: IntArray? = null,
): AnnotatedString = buildAnnotatedString {
    var firstOnLine = true
    for (i in range) {
        val w = words.getOrNull(i) ?: continue
        if (!firstOnLine) append(' ')
        firstOnLine = false
        wordStartOffsets?.let { offsets ->
            val slot = i - range.first
            if (slot in offsets.indices) offsets[slot] = length
        }
        val isTitle = i in isTitleWord.indices && isTitleWord[i]
        val isItalic = i in isItalicWord.indices && isItalicWord[i]
        withStyle(
            SpanStyle(
                color = if (isTitle && applyColor) titleColor else Color.Unspecified,
                fontWeight = if (isTitle) FontWeight.Bold else null,
                fontStyle = if (isItalic) FontStyle.Italic else null,
                textDecoration = if (isTitle && applyUnderline) TextDecoration.Underline else null,
            )
        ) { append(w) }
        val breaksHere = i < paragraphBreakAfter.size && paragraphBreakAfter[i]
        if (breaksHere && i < range.last) {
            append("\n\n")
            firstOnLine = true
        }
    }
}

/**
 * The page view's own chrome: a page number that is always there, and a top bar that is not.
 *
 * The bar is the LightOS ActionBar shape — back on the left, title in the middle, one action on
 * the right — and the action is FASTREAD, which is the whole reason the word reader is still in
 * this app. Opaque rather than translucent, because the text runs right up under it: the page no
 * longer reserves a permanent gap for a bar that is visible for four seconds at a time.
 */
@Composable
private fun EreaderChrome(
    title: String,
    pageNumber: Int,
    pageCount: Int,
    showBar: Boolean,
    onExit: () -> Unit,
    onFastRead: () -> Unit,
) {
    val colors = LightThemeTokens.colors
    Box(modifier = Modifier.fillMaxSize()) {
        if (showBar) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(colors.background)
                    .statusBarsPadding(),
            ) {
                // No centred title in the bar itself: FASTREAD is set in the Button variant,
                // tracked out 15%, and on a 411dp screen it and a title would collide. The
                // title goes on its own line underneath, where it has the whole width.
                LightTopBar(
                    left = LightBarItem.Icon(icon = LightIcons.Back, onClick = onExit),
                    right = LightBarItem.Text(text = "FASTREAD", onClick = onFastRead),
                )
                LightText(
                    text = title,
                    variant = LightTextVariant.Superfine,
                    lighten = true,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = lightInset())
                        .padding(bottom = 6f.designVerticalPxToDp()),
                )
                LightRule()
            }
        }
        if (pageCount > 0) {
            LightText(
                text = "$pageNumber / $pageCount",
                variant = LightTextVariant.Superfine,
                lighten = true,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 14f.designVerticalPxToDp()),
            )
        }
    }
}
