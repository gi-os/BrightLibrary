package com.lightfastread.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lightfastread.data.TitleStyle
import com.gios.light.common.hw.WheelInDialog
import com.gios.light.common.hw.WheelSteps
import com.lightfastread.ui.theme.LocalIsLightPhone
import com.lightfastread.ui.theme.LpContrast
import kotlinx.coroutines.CancellationException
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

/** A turn abandoned for this long starts counting again, matching hw/Wheel.kt's
 *  own idle window, so a stray brush never banks toward a later real turn. */
private const val WHEEL_IDLE_MS = 1_500L

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
    onClose: (wordIndex: Int) -> Unit,
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
    // The close button is collapsed until asked for, so it never sits over the
    // text while reading - the same pattern as the RSVP reader's own top bar.
    var showChrome by remember(bookId) { mutableStateOf(false) }
    val pagerState = rememberPagerState(initialPage = 0) { pages.size }

    // Whichever page is showing decides where RSVP resumes, so closing always
    // lands on the first word of the page actually on screen.
    val currentWord: () -> Int = {
        pages.getOrNull(pagerState.currentPage)?.first ?: initialWordIndex
    }
    val close: () -> Unit = { onClose(currentWord()) }

    Dialog(
        onDismissRequest = close,
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
        // One step per notch, and no rate limit: this screen does its own banking just below
        // (NOTCHES_PER_PAGE), and light-common's WheelSteps banks by default. Taking the
        // defaults would bank twice and a page turn would cost twice the wheel it used to.
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
            color = MaterialTheme.colorScheme.background,
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
                        onClose(anchor)
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
                                color = MaterialTheme.colorScheme.onBackground,
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
                    pageNumber = if (pages.isEmpty()) 0 else pagerState.currentPage + 1,
                    pageCount = pages.size,
                    showClose = showChrome,
                    onClose = close,
                )
            }
        }
    }
}

@Composable
private fun PaginatingIndicator(progress: Float) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(progress = { progress.coerceIn(0f, 1f) })
            Text(
                "${(progress.coerceIn(0f, 1f) * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
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

@Composable
private fun EreaderChrome(
    pageNumber: Int,
    pageCount: Int,
    showClose: Boolean,
    onClose: () -> Unit,
) {
    val lp = LocalIsLightPhone.current
    Box(modifier = Modifier.fillMaxSize()) {
        if (showClose) {
            // Opaque, because the text now runs right up under it: the page no
            // longer keeps a permanent gap at the top for a button that is only
            // visible for a moment.
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 8.dp, start = 8.dp)
                    .background(MaterialTheme.colorScheme.background, CircleShape),
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close ereader")
            }
        }
        if (pageCount > 0) {
            Text(
                "$pageNumber / $pageCount",
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 20.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground.copy(
                    alpha = if (lp) LpContrast.floor(0.7f, 0.85f) else 0.7f,
                ),
            )
        }
    }
}
