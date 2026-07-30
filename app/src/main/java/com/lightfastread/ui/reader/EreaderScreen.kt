package com.lightfastread.ui.reader

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
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

// Full-page reading mode, opened by tapping the three-line context preview
// above the RSVP word (see ReaderScreen's context-band tap handling). Where
// the RSVP loop shows one word at a time, this shows a whole page of the
// book and lets you move between pages by swipe or by the hardware wheel.
//
// This is built on Compose Foundation's own HorizontalPager rather than a
// hand-rolled drag/flip - an earlier custom version (a 3D rotationY flip,
// then a hand-built 2D page-fold) kept shipping real bugs: swipe direction
// guessed wrong, multiple pages advancing on one fast swipe, two pages'
// text rendering on top of each other. A Pager is the well-tested primitive
// for exactly this - one page per slot, swipe or fling always lands on
// exactly one adjacent page, and the default transition is already the
// plain slide this is supposed to look like. PagerState also happens to
// implement ScrollableState, so the hardware wheel plugs in exactly like it
// does for every LazyColumn sheet elsewhere in this app.
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
    val marginDp = 20.dp
    val topChromeDp = 56.dp
    val bottomChromeDp = 40.dp
    val marginPx = with(density) { marginDp.toPx() }
    val topChromePx = with(density) { topChromeDp.toPx() }
    val bottomChromePx = with(density) { bottomChromeDp.toPx() }
    val pageWidthPx = (widthPx - marginPx * 2f).coerceAtLeast(1f)
    val pageHeightPx = (heightPx - topChromePx - bottomChromePx).coerceAtLeast(1f)

    // Real measurement rather than a guessed average-glyph-width factor: the
    // earlier heuristic version of this (0.56, then 0.64) kept underestimating
    // how wide this book's actual text renders and packed more words onto a
    // page than really fit, clipping the tail off the bottom of the screen.
    // A single measured line of this book's own words (forced onto one line,
    // not wrapped) gives both the real average character width and the real
    // line height for the chosen font and size directly, in one cheap call.
    val textMeasurer = rememberTextMeasurer()
    val bodyStyle = remember(fontFamily, safeFontSizeSp) {
        TextStyle(fontFamily = fontFamily, fontSize = safeFontSizeSp.sp)
    }
    val sampleText = remember(bookId, words) {
        words.asSequence().take(300).joinToString(" ")
            .ifEmpty { "The quick brown fox jumps over the lazy dog." }
    }
    val (charsPerLine, linesPerPage) = remember(sampleText, bodyStyle, pageWidthPx, pageHeightPx) {
        val measured = textMeasurer.measure(
            text = sampleText,
            style = bodyStyle,
            softWrap = false,
            maxLines = 1,
        )
        val avgCharWidthPx = (measured.size.width.toFloat() / sampleText.length.coerceAtLeast(1))
            .coerceAtLeast(1f)
        val realLineHeightPx = measured.size.height.toFloat().coerceAtLeast(1f)
        val perLine = (pageWidthPx / avgCharWidthPx).toInt().coerceAtLeast(8)
        // Paragraph breaks still render as a blank line, which this doesn't
        // know about, and charsPerLine still buckets words by count rather
        // than a true wrap - 0.92 is headroom for those two approximations
        // only, not for font-metric guesswork anymore.
        val perPage = ((pageHeightPx / realLineHeightPx) * 0.92f).toInt().coerceAtLeast(3)
        perLine to perPage
    }

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
    var showChrome by remember(bookId) { mutableStateOf(false) }
    val pagerState = rememberPagerState(initialPage = initialPage) { pageCount }

    val closeToCurrentPage: () -> Unit = {
        val shown = pagerState.currentPage.coerceIn(0, pageCount - 1)
        onClose(pagination.pages.getOrNull(shown)?.first ?: initialWordIndex)
    }

    Dialog(
        onDismissRequest = closeToCurrentPage,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // A Dialog is its own window - the wheel needs picking up here the
        // same way every other full-screen sheet in this app does.
        WheelInDialog()
        // PagerState implements ScrollableState directly, so the wheel drives
        // page turns exactly the way it drives every LazyColumn sheet
        // elsewhere in this app - no bespoke wiring needed.
        WheelScroll(pagerState)

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    // The tap-to-reveal-chrome gesture lives on each page's
                    // own content rather than on the Pager itself, so it
                    // never competes with the Pager's own swipe handling.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTapGestures(onTap = { showChrome = !showChrome })
                            },
                    ) {
                        PageBody(
                            words = words,
                            range = pagination.pages[page],
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
                }

                EreaderChrome(
                    pageNumber = pagerState.currentPage + 1,
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
        // on the page (see the tap handler in the pager content above) to
        // reveal it, same as the RSVP reader's own top bar.
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
