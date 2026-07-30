package com.lightfastread.ui.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lightfastread.data.TitleStyle
import com.lightfastread.hw.WheelInDialog
import com.lightfastread.hw.WheelScroll
import com.lightfastread.ui.theme.LocalIsLightPhone
import com.lightfastread.ui.theme.LpContrast
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

// Full-page reading mode, opened by tapping the three-line context preview
// above the RSVP word (see ReaderScreen's context-band tap handling). Where
// the RSVP loop shows one word at a time, this shows a whole page of the
// book and lets you move between pages by swipe or by the hardware wheel,
// with a flat page-fold flip between them (see the render code below for why
// it isn't a 3D rotation).
//
// Pagination reuses WordDisplay's own line-break heuristic (`paginateByChars`)
// rather than a real text layout pass: an average-glyph-width estimate is
// O(words) and cheap enough to redo on rotation, where laying out an entire
// novel with a real TextMeasurer would stall opening a long book. Lines are
// then grouped into pages by how many fit the available height. Both numbers
// are biased conservative - deliberately more so than the context window,
// which only ever shows three lines and had far less room to be wrong - so a
// full page never silently clips text at the bottom on device.
internal data class PagePagination(
    val pages: List<IntRange>,
    val wordToPage: IntArray,
)

private fun paginatePages(
    words: List<String>,
    paragraphBreakAfter: BooleanArray,
    charsPerLine: Int,
    linesPerPage: Int,
): PagePagination {
    val lineInfo = paginateByChars(words, paragraphBreakAfter, charsPerLine)
    if (lineInfo.lines.isEmpty() || linesPerPage <= 0) {
        return PagePagination(emptyList(), IntArray(words.size))
    }
    val pages = ArrayList<IntRange>(lineInfo.lines.size / linesPerPage + 1)
    val wordToPage = IntArray(words.size)
    var i = 0
    while (i < lineInfo.lines.size) {
        val end = (i + linesPerPage - 1).coerceAtMost(lineInfo.lines.size - 1)
        val start = lineInfo.lines[i].first
        val last = lineInfo.lines[end].last
        val pageIdx = pages.size
        pages.add(start..last)
        for (w in start..last) if (w in words.indices) wordToPage[w] = pageIdx
        i = end + 1
    }
    return PagePagination(pages, wordToPage)
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
    fontSizeSp: Int,
    initialWordIndex: Int,
    onClose: (wordIndex: Int) -> Unit,
) {
    val density = LocalDensity.current
    val config = LocalConfiguration.current
    val widthPx = with(density) { config.screenWidthDp.dp.toPx() }
    val heightPx = with(density) { config.screenHeightDp.dp.toPx() }

    val applyColor = titleStyle == TitleStyle.Color || titleStyle == TitleStyle.Both
    val applyUnderline = titleStyle == TitleStyle.Underline || titleStyle == TitleStyle.Both

    val safeFontSizeSp = fontSizeSp.coerceAtLeast(8)
    val fontPx = with(density) { safeFontSizeSp.sp.toPx() }
    val marginPx = with(density) { 20.dp.toPx() }
    val topChromePx = with(density) { 56.dp.toPx() }
    val bottomChromePx = with(density) { 40.dp.toPx() }
    // PageBody's own padding is derived from these same three values (passed
    // down below) rather than hardcoded separately, so the space pagination
    // assumes is available is exactly the space the Text actually gets.
    // Two independent numbers that were each "roughly this much chrome" is
    // what let them drift apart and clip text at the bottom in practice.
    val marginDp = with(density) { marginPx.toDp() }
    val topChromeDp = with(density) { topChromePx.toDp() }
    val bottomChromeDp = with(density) { bottomChromePx.toDp() }

    // Average-glyph-width heuristic. Deliberately more conservative (wider
    // assumed glyphs, more assumed line height) than the three-line context
    // window's own version of this estimate: a wrong guess there just makes
    // one preview line wrap oddly, but a wrong guess here silently clips
    // words off the bottom of a full page, which is much worse and was
    // exactly the bug reported after the first cut of this feature.
    val avgCharFactor = 0.64f
    val pageWidthPx = (widthPx - marginPx * 2f).coerceAtLeast(1f)
    val pageHeightPx = (heightPx - topChromePx - bottomChromePx).coerceAtLeast(1f)
    val charsPerLine = (pageWidthPx / (fontPx * avgCharFactor)).toInt().coerceAtLeast(8)
    val lineHeightPx = fontPx * 1.45f
    // Paragraph breaks render as a blank line, which this char-count heuristic
    // doesn't account for - 0.75 leaves real headroom (rather than shaving a
    // sliver off) so a page's last line is never actually the one that gets
    // cut off on a real device.
    val linesPerPage = ((pageHeightPx / lineHeightPx) * 0.75f).toInt().coerceAtLeast(3)

    val pagination = remember(bookId, charsPerLine, linesPerPage) {
        paginatePages(words, paragraphBreakAfter, charsPerLine, linesPerPage)
    }
    val pageCount = pagination.pages.size

    if (pageCount == 0) {
        LaunchedEffect(Unit) { onClose(initialWordIndex) }
        return
    }

    val initialPage = pagination.wordToPage.getOrElse(
        initialWordIndex.coerceIn(0, (words.size - 1).coerceAtLeast(0))
    ) { 0 }
    var pageOffset by remember(bookId, pageCount) { mutableFloatStateOf(initialPage.toFloat()) }
    val maxOffset = (pageCount - 1).toFloat()
    var showChrome by remember(bookId) { mutableStateOf(false) }

    // Single source of truth for "which page is on screen right now", read by
    // both the flip renderer and the close handler below - closing always
    // lands on exactly the page you're looking at, never a page computed a
    // different way that could drift from it.
    val clamped = pageOffset.coerceIn(0f, maxOffset)
    val basePage = floor(clamped).toInt().coerceIn(0, pageCount - 1)
    val frac = (clamped - basePage).coerceIn(0f, 1f)
    val nextPage = (basePage + 1).coerceAtMost(pageCount - 1)
    val showPage = if (frac < 0.5f) basePage else nextPage

    val closeToCurrentPage: () -> Unit = {
        onClose(pagination.pages.getOrNull(showPage)?.first ?: initialWordIndex)
    }

    // Touch drag and the hardware wheel both drive this one ScrollableState,
    // exactly like WheelScroll(listState) already does for every LazyColumn
    // sheet in the app - the wheel just plays the same role a finger would.
    // NOTE: swipe direction here is a best guess (unverified on device); if
    // it turns out backwards, add `reverseDirection = true` below.
    val pageScrollableState = rememberScrollableState { delta ->
        val deltaPages = delta / pageWidthPx
        val old = pageOffset
        val new = (old + deltaPages).coerceIn(0f, maxOffset)
        val consumedPages = new - old
        pageOffset = new
        consumedPages * pageWidthPx
    }

    // Once neither a finger nor the wheel is actively driving the position,
    // spring to the nearest whole page - the same "glide, then land" feel as
    // the wheel's own debt-smoothing in hw/Wheel.kt, one axis over.
    val settleAnim = remember { Animatable(pageOffset) }
    LaunchedEffect(pageScrollableState.isScrollInProgress, pageCount) {
        if (pageScrollableState.isScrollInProgress) return@LaunchedEffect
        val target = pageOffset.roundToInt().coerceIn(0, pageCount - 1).toFloat()
        if (target == pageOffset) return@LaunchedEffect
        settleAnim.snapTo(pageOffset)
        settleAnim.animateTo(
            targetValue = target,
            animationSpec = spring(dampingRatio = 0.85f, stiffness = 380f),
        ) {
            pageOffset = value
        }
    }

    Dialog(
        onDismissRequest = closeToCurrentPage,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // A Dialog is its own window - the wheel needs picking up here the
        // same way every other full-screen sheet in this app does.
        WheelInDialog()
        WheelScroll(pageScrollableState)

        Surface(
            modifier = Modifier
                .fillMaxSize()
                .scrollable(state = pageScrollableState, orientation = Orientation.Horizontal)
                // A plain tap (no drag) toggles the close button - same
                // collapsed-by-default, tap-to-reveal pattern as the RSVP
                // reader's own top bar, so the button never sits over the
                // text while you're just reading.
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { showChrome = !showChrome })
                },
            color = MaterialTheme.colorScheme.background,
        ) {
            // A flat page-fold flip rather than a 3D rotation: the outgoing
            // page's trailing edge stays put while a rigid "flap" - the part
            // of it past the crease - swings left, off-screen, uncovering
            // the incoming page underneath. This models the rigid-fold +
            // gradient-shadow technique classic 2D page-turn implementations
            // use (turn.js's mirrored-flap fold, StPageFlip's canvas
            // clip-and-shade renderer) rather than harism's OpenGL cylinder
            // warp, which needs a real 3D pipeline Compose's Canvas doesn't
            // have. The flap itself is a plain shaded panel, not the page's
            // own text mirrored - real page-curl implementations don't
            // render readable backwards text on the turning leaf either,
            // since there's no "back side" art to show.
            val onBg = MaterialTheme.colorScheme.onBackground
            val bg = MaterialTheme.colorScheme.background
            val creaseXpx = (widthPx * (1f - frac)).coerceIn(0f, widthPx)
            val flapWidthPx = min(widthPx - creaseXpx, creaseXpx).coerceAtLeast(0f)
            val flapXpx = creaseXpx - flapWidthPx
            val shadowStripWidthPx = with(density) { 28.dp.toPx() }.coerceAtMost(widthPx - creaseXpx)

            Box(modifier = Modifier.fillMaxSize()) {
                // Incoming page, full screen underneath - progressively
                // uncovered as the flap swings away.
                PageBody(
                    words = words,
                    range = pagination.pages[nextPage],
                    paragraphBreakAfter = paragraphBreakAfter,
                    isItalicWord = isItalicWord,
                    isTitleWord = isTitleWord,
                    titleColor = titleColor,
                    applyColor = applyColor,
                    applyUnderline = applyUnderline,
                    fontFamily = fontFamily,
                    fontSizeSp = safeFontSizeSp,
                    marginDp = marginDp,
                    topPaddingDp = topChromeDp,
                    bottomPaddingDp = bottomChromeDp,
                )

                // A faint shadow the raised fold casts onto the incoming
                // page, right where the crease currently sits.
                if (shadowStripWidthPx > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(with(density) { shadowStripWidthPx.toDp() })
                            .offset(x = with(density) { creaseXpx.toDp() })
                            .background(
                                Brush.horizontalGradient(
                                    listOf(onBg.copy(alpha = 0.22f), Color.Transparent),
                                ),
                            ),
                    )
                }

                // The outgoing page's still-flat remainder - full text
                // layout at the normal page width so wrapping never changes,
                // just visually cut off past the crease.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawWithContent {
                            clipRect(left = 0f, right = creaseXpx) {
                                this@drawWithContent.drawContent()
                            }
                        },
                ) {
                    PageBody(
                        words = words,
                        range = pagination.pages[basePage],
                        paragraphBreakAfter = paragraphBreakAfter,
                        isItalicWord = isItalicWord,
                        isTitleWord = isTitleWord,
                        titleColor = titleColor,
                        applyColor = applyColor,
                        applyUnderline = applyUnderline,
                        fontFamily = fontFamily,
                        fontSizeSp = safeFontSizeSp,
                        marginDp = marginDp,
                        topPaddingDp = topChromeDp,
                        bottomPaddingDp = bottomChromeDp,
                    )
                }

                // The hinged flap: a plain shaded panel with a shadow that
                // deepens toward the crease, where the fold is raised
                // highest.
                if (flapWidthPx > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(with(density) { flapWidthPx.toDp() })
                            .offset(x = with(density) { flapXpx.toDp() })
                            .background(
                                Brush.horizontalGradient(
                                    listOf(bg, onBg.copy(alpha = 0.30f)),
                                ),
                            ),
                    )
                }

                EreaderChrome(
                    pageNumber = showPage + 1,
                    pageCount = pageCount,
                    showClose = showChrome,
                    onClose = closeToCurrentPage,
                )
            }
        }
    }
}

@Composable
private fun PageBody(
    words: List<String>,
    range: IntRange,
    paragraphBreakAfter: BooleanArray,
    isItalicWord: BooleanArray,
    isTitleWord: BooleanArray,
    titleColor: Color,
    applyColor: Boolean,
    applyUnderline: Boolean,
    fontFamily: FontFamily,
    fontSizeSp: Int,
    marginDp: Dp,
    topPaddingDp: Dp,
    bottomPaddingDp: Dp,
) {
    val text = remember(range, isItalicWord, isTitleWord, titleColor, applyColor, applyUnderline) {
        buildPageText(
            words, range, paragraphBreakAfter, isItalicWord, isTitleWord,
            titleColor, applyColor, applyUnderline,
        )
    }
    Text(
        text = text,
        fontFamily = fontFamily,
        fontSize = fontSizeSp.sp,
        lineHeight = (fontSizeSp * 1.45f).sp,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier
            .fillMaxSize()
            .padding(
                start = marginDp,
                end = marginDp,
                top = topPaddingDp,
                bottom = bottomPaddingDp,
            ),
    )
}

// Full original punctuation (including quote marks) is already present on the
// words that carry it, so unlike the RSVP single-word display this needs no
// synthetic quote-wrapping - reading several words together supplies the
// context RSVP mode has to fake.
private fun buildPageText(
    words: List<String>,
    range: IntRange,
    paragraphBreakAfter: BooleanArray,
    isItalicWord: BooleanArray,
    isTitleWord: BooleanArray,
    titleColor: Color,
    applyColor: Boolean,
    applyUnderline: Boolean,
): AnnotatedString = buildAnnotatedString {
    var firstOnLine = true
    for (i in range) {
        val w = words.getOrNull(i) ?: continue
        if (!firstOnLine) append(' ')
        firstOnLine = false
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
        val isParaEnd = i < paragraphBreakAfter.size && paragraphBreakAfter[i]
        if (isParaEnd) {
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
        // Collapsed by default so it never sits over the text - tap anywhere
        // on the page (see the tap handler on the Surface above) to reveal
        // it, same as the RSVP reader's own top bar.
        if (showClose) {
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 24.dp, start = 8.dp),
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close ereader")
            }
        }
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
