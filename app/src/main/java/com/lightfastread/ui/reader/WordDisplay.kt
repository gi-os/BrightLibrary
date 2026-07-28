package com.lightfastread.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lightfastread.data.BionicMode
import com.lightfastread.data.TitleStyle
import com.lightfastread.ui.theme.LocalIsLightPhone
import com.lightfastread.ui.theme.LpContrast

@Composable
fun WordDisplay(
    bookId: String,
    words: List<String>,
    paragraphBreakAfter: BooleanArray,
    isTitleWord: BooleanArray,
    isItalicWord: BooleanArray,
    insideQuotes: BooleanArray,
    quoteOpenChar: CharArray,
    titleColor: Color,
    titleStyle: TitleStyle,
    currentIndex: Int,
    fontSizeSp: Int,
    contextFontSizeSp: Int,
    contextAlpha: Float,
    fontFamily: FontFamily,
    bionicMode: BionicMode,
    bionicBoldWeight: Int,
    bionicLightWeight: Int,
    orpEnabled: Boolean,
    orpColor: Color,
    orpFocalSameColor: Boolean,
    mainWordAlpha: Float,
) {
    val mainBionic = bionicMode == BionicMode.MainOnly || bionicMode == BionicMode.Both
    val ctxBionic = bionicMode == BionicMode.ContextOnly || bionicMode == BionicMode.Both
    val boldFw = FontWeight(bionicBoldWeight)
    val lightFw = FontWeight(bionicLightWeight)
    val applyColor = titleStyle == TitleStyle.Color || titleStyle == TitleStyle.Both
    val applyUnderline = titleStyle == TitleStyle.Underline || titleStyle == TitleStyle.Both
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.5f)
                .align(Alignment.TopCenter)
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = (fontSizeSp * 1.6f).dp,
                ),
        ) {
            ContextLineWindow(
                bookId = bookId,
                words = words,
                paragraphBreakAfter = paragraphBreakAfter,
                isTitleWord = isTitleWord,
                isItalicWord = isItalicWord,
                titleColor = titleColor,
                applyColor = applyColor,
                applyUnderline = applyUnderline,
                currentIndex = currentIndex,
                contextFontSizeSp = contextFontSizeSp,
                contextAlpha = contextAlpha,
                fontFamily = fontFamily,
                bionic = ctxBionic,
                bionicBoldWeight = boldFw,
                bionicLightWeight = lightFw,
            )
        }
        val word = words.getOrNull(currentIndex).orEmpty()
        val isTitle = currentIndex in isTitleWord.indices && isTitleWord[currentIndex]
        val isItalic = currentIndex in isItalicWord.indices && isItalicWord[currentIndex]
        val isParagraphEnd = currentIndex in paragraphBreakAfter.indices && paragraphBreakAfter[currentIndex]
        val displayed = if (currentIndex in insideQuotes.indices && insideQuotes[currentIndex]) {
            wrapInQuotes(word, quoteOpenChar.getOrElse(currentIndex) { '"' })
        } else word
        val baseAnnotated = if (mainBionic) bionicAnnotated(displayed, boldWeight = boldFw, lightWeight = lightFw)
                        else AnnotatedString(displayed)
        val pivotIdx = if (orpEnabled) pivotCharIndex(displayed) else null
        val annotated = if (pivotIdx != null && !orpFocalSameColor) {
            buildAnnotatedString {
                append(baseAnnotated)
                addStyle(SpanStyle(color = orpColor), pivotIdx, pivotIdx + 1)
            }
        } else baseAnnotated
        val mainFontStyle = if (isItalic) FontStyle.Italic else FontStyle.Normal
        BoxWithConstraints(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .alpha(mainWordAlpha),
            contentAlignment = Alignment.Center,
        ) {
            val density = LocalDensity.current
            val maxWidthPx = with(density) { maxWidth.toPx() }
            val measurer = rememberTextMeasurer()
            val baseStyle = TextStyle(
                fontFamily = fontFamily,
                fontWeight = FontWeight.Normal,
                fontStyle = mainFontStyle,
                fontSize = fontSizeSp.sp,
            )
            val measured = measurer.measure(
                text = annotated,
                style = baseStyle,
                softWrap = false,
                maxLines = 1,
            )
            val totalWidthPx = measured.size.width.toFloat()
            // For ORP we shift the word so the pivot letter's center sits at the
            // box center. This makes the focal point a fixed horizontal anchor
            // regardless of word length — the eye stops moving between words.
            // The asymmetric placement means the word's effective horizontal
            // extent is 2 * max(prefix+pivot/2, suffix+pivot/2), which we use
            // for autoshrink instead of total width.
            val (extentPx, shiftPx) = if (pivotIdx != null && totalWidthPx > 0f) {
                val prefixW = if (pivotIdx > 0) {
                    measurer.measure(
                        annotated.subSequence(0, pivotIdx),
                        style = baseStyle, softWrap = false, maxLines = 1,
                    ).size.width.toFloat()
                } else 0f
                val pivotW = measurer.measure(
                    annotated.subSequence(pivotIdx, pivotIdx + 1),
                    style = baseStyle, softWrap = false, maxLines = 1,
                ).size.width.toFloat()
                val suffixW = if (pivotIdx + 1 < annotated.length) {
                    measurer.measure(
                        annotated.subSequence(pivotIdx + 1, annotated.length),
                        style = baseStyle, softWrap = false, maxLines = 1,
                    ).size.width.toFloat()
                } else 0f
                val extent = 2f * maxOf(prefixW + pivotW / 2f, suffixW + pivotW / 2f)
                val shift = (suffixW - prefixW) / 2f
                extent to shift
            } else {
                totalWidthPx to 0f
            }
            val fitSize = if (maxWidthPx <= 0f || extentPx <= maxWidthPx) {
                fontSizeSp.sp
            } else {
                (fontSizeSp * (maxWidthPx / extentPx)).sp
            }
            val scale = if (fontSizeSp > 0) fitSize.value / fontSizeSp.toFloat() else 1f
            val shiftDp = with(density) { (shiftPx * scale).toDp() }
            Text(
                text = annotated,
                fontSize = fitSize,
                fontFamily = fontFamily,
                fontWeight = FontWeight.Normal,
                fontStyle = mainFontStyle,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
                color = if (isTitle && applyColor) titleColor else MaterialTheme.colorScheme.onBackground,
                textDecoration = if (isTitle && applyUnderline) TextDecoration.Underline else null,
                modifier = Modifier.offset(x = shiftDp),
            )
            if (isParagraphEnd) {
                Text(
                    text = "¶",
                    fontSize = (fontSizeSp * 1.1f).sp,
                    fontFamily = fontFamily,
                    color = MaterialTheme.colorScheme.onBackground.copy(
                        alpha = if (LocalIsLightPhone.current) LpContrast.floor(0.45f, 0.6f) else 0.45f,
                    ),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .offset(y = (fontSizeSp * 1.4f).dp),
                )
            }
        }
    }
}

// Three-line context window above the main word.
//
// We do NOT want the surrounding text to change on every word advance — that
// was the previous behavior (a sliding window of the last N words, re-wrapped
// every tick), and it made the context flicker/reflow on every RSVP step.
//
// Instead we pre-paginate the entire book into stable lines (analogous to
// pages in a normal ebook reader): for a given (book, font, available width)
// the *first word of each line is fixed*. Changing the font size re-paginates,
// but within one pagination the line breaks never move. Then we render a
// 3-line window with the current word's line in the middle and the current
// word bolded. While the current word advances inside its line, only the
// bolded glyphs change; when it crosses to the next line, the window scrolls
// down by one line so the new line is in the middle.
//
// Only the current paragraph is shown: if the line above or below belongs to
// a different paragraph it is blanked out, which leaves the current line at
// the top or bottom of the visible context rather than padding with unrelated
// text from neighboring paragraphs.
//
// Line widths are estimated with an average-glyph-width heuristic (we use
// proportional fonts, not monospace), biased slightly conservative so a
// computed line never wraps to a second visual line in practice.
@Composable
private fun ContextLineWindow(
    bookId: String,
    words: List<String>,
    paragraphBreakAfter: BooleanArray,
    isTitleWord: BooleanArray,
    isItalicWord: BooleanArray,
    titleColor: Color,
    applyColor: Boolean,
    applyUnderline: Boolean,
    currentIndex: Int,
    contextFontSizeSp: Int,
    contextAlpha: Float,
    fontFamily: FontFamily,
    bionic: Boolean,
    bionicBoldWeight: FontWeight,
    bionicLightWeight: FontWeight,
) {
    if (words.isEmpty()) return
    val ctxFontSizeSp = contextFontSizeSp.toFloat().coerceAtLeast(8f)
    // The context lines are meant to be de-emphasised, not invisible. Matte
    // glass costs roughly a stop of perceived contrast, so on the LPIII we
    // compress the user's contextAlpha into 0.55..1.0 rather than 0..1. The
    // ordering they chose in settings survives; the bottom of the range no
    // longer resolves to unreadable.
    val ctxAlpha = if (LocalIsLightPhone.current) LpContrast.lift(contextAlpha) else contextAlpha
    val highlightColor = MaterialTheme.colorScheme.onBackground.copy(alpha = ctxAlpha)
    val faintColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f * ctxAlpha)
    val highlightTitleColor = titleColor.copy(alpha = ctxAlpha)
    val faintTitleColor = titleColor.copy(alpha = 0.7f * ctxAlpha)
    val density = LocalDensity.current

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val widthPx = with(density) { maxWidth.toPx() }
        val fontPx = with(density) { ctxFontSizeSp.sp.toPx() }
        val avgCharFactor = 0.58f
        val charsPerLine = (widthPx / (fontPx * avgCharFactor)).toInt().coerceAtLeast(8)

        val pagination = remember(bookId, charsPerLine) {
            paginateByChars(words, paragraphBreakAfter, charsPerLine)
        }
        if (pagination.lines.isEmpty()) return@BoxWithConstraints

        val safeIndex = currentIndex.coerceIn(0, words.size - 1)
        val currentLine = pagination.wordToLine.getOrElse(safeIndex) { 0 }
        val middle = pagination.lines[currentLine]
        val above = pagination.lines.getOrNull(currentLine - 1)?.takeIf { prev ->
            val lastOfPrev = prev.last
            lastOfPrev >= 0 && lastOfPrev < paragraphBreakAfter.size &&
                !paragraphBreakAfter[lastOfPrev]
        }
        val below = pagination.lines.getOrNull(currentLine + 1)?.takeIf {
            val lastOfMiddle = middle.last
            lastOfMiddle >= 0 && lastOfMiddle < paragraphBreakAfter.size &&
                !paragraphBreakAfter[lastOfMiddle]
        }

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Bottom,
        ) {
            ContextLine(words, above, ctxFontSizeSp.sp, fontFamily, faintColor,
                highlightWord = -1, bionic = bionic, highlightColor = highlightColor,
                isTitleWord = isTitleWord, isItalicWord = isItalicWord, titleColor = titleColor,
                faintTitleColor = faintTitleColor,
                highlightTitleColor = highlightTitleColor,
                applyColor = applyColor, applyUnderline = applyUnderline,
                bionicBoldWeight = bionicBoldWeight, bionicLightWeight = bionicLightWeight)
            ContextLine(words, middle, ctxFontSizeSp.sp, fontFamily, faintColor,
                highlightWord = safeIndex, bionic = bionic, highlightColor = highlightColor,
                isTitleWord = isTitleWord, isItalicWord = isItalicWord, titleColor = titleColor,
                faintTitleColor = faintTitleColor,
                highlightTitleColor = highlightTitleColor,
                applyColor = applyColor, applyUnderline = applyUnderline,
                bionicBoldWeight = bionicBoldWeight, bionicLightWeight = bionicLightWeight)
            ContextLine(words, below, ctxFontSizeSp.sp, fontFamily, faintColor,
                highlightWord = -1, bionic = bionic, highlightColor = highlightColor,
                isTitleWord = isTitleWord, isItalicWord = isItalicWord, titleColor = titleColor,
                faintTitleColor = faintTitleColor,
                highlightTitleColor = highlightTitleColor,
                applyColor = applyColor, applyUnderline = applyUnderline,
                bionicBoldWeight = bionicBoldWeight, bionicLightWeight = bionicLightWeight)
        }
    }
}

@Composable
private fun ContextLine(
    words: List<String>,
    range: IntRange?,
    fontSize: TextUnit,
    fontFamily: FontFamily,
    color: Color,
    highlightWord: Int,
    bionic: Boolean,
    highlightColor: Color,
    isTitleWord: BooleanArray,
    isItalicWord: BooleanArray,
    titleColor: Color,
    faintTitleColor: Color,
    highlightTitleColor: Color,
    applyColor: Boolean,
    applyUnderline: Boolean,
    bionicBoldWeight: FontWeight,
    bionicLightWeight: FontWeight,
) {
    val underline = if (applyUnderline) TextDecoration.Underline else null
    val text: AnnotatedString = if (range == null || range.isEmpty()) {
        AnnotatedString(" ")
    } else buildAnnotatedString {
        var first = true
        for (i in range) {
            if (!first) append(' ')
            first = false
            val w = words.getOrNull(i) ?: continue
            val isTitle = i in isTitleWord.indices && isTitleWord[i]
            val isItalic = i in isItalicWord.indices && isItalicWord[i]
            val render: AnnotatedString.Builder.() -> Unit = {
                when {
                    i == highlightWord -> withStyle(
                        SpanStyle(
                            color = if (isTitle && applyColor) highlightTitleColor else highlightColor,
                            textDecoration = if (isTitle) underline else null,
                        )
                    ) { append(w) }
                    isTitle -> withStyle(
                        SpanStyle(
                            color = if (applyColor) faintTitleColor else color,
                            textDecoration = underline,
                        )
                    ) {
                        if (bionic) appendBionic(w, boldWeight = bionicBoldWeight, lightWeight = bionicLightWeight) else append(w)
                    }
                    bionic -> appendBionic(w, boldWeight = bionicBoldWeight, lightWeight = bionicLightWeight)
                    else -> append(w)
                }
            }
            if (isItalic) {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { render() }
            } else {
                render()
            }
        }
    }
    Text(
        text = text,
        fontSize = fontSize,
        fontFamily = fontFamily,
        color = color,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth(),
    )
}

private data class LinePagination(
    val lines: List<IntRange>,
    val wordToLine: IntArray,
)

private fun paginateByChars(
    words: List<String>,
    paragraphBreakAfter: BooleanArray,
    charsPerLine: Int,
): LinePagination {
    if (words.isEmpty() || charsPerLine <= 0) {
        return LinePagination(emptyList(), IntArray(0))
    }
    val lines = ArrayList<IntRange>(words.size / 8 + 1)
    val wordToLine = IntArray(words.size)
    var lineStart = 0
    var lineLen = 0
    var lineIdx = 0
    for (i in words.indices) {
        val ww = words[i].length
        val needed = if (i == lineStart) ww else lineLen + 1 + ww
        if (i != lineStart && needed > charsPerLine) {
            lines.add(lineStart until i)
            for (j in lineStart until i) wordToLine[j] = lineIdx
            lineIdx++
            lineStart = i
            lineLen = ww
        } else {
            lineLen = needed
        }
        val isParaEnd = i < paragraphBreakAfter.size && paragraphBreakAfter[i]
        if (isParaEnd) {
            lines.add(lineStart..i)
            for (j in lineStart..i) wordToLine[j] = lineIdx
            lineIdx++
            lineStart = i + 1
            lineLen = 0
        }
    }
    if (lineStart < words.size) {
        lines.add(lineStart until words.size)
        for (j in lineStart until words.size) wordToLine[j] = lineIdx
    }
    return LinePagination(lines, wordToLine)
}

private fun bionicBoldCount(letters: Int): Int = when {
    letters <= 1 -> letters
    letters <= 3 -> 1
    letters == 4 -> 2
    letters <= 6 -> 3
    letters <= 9 -> 4
    letters <= 12 -> 5
    else -> ((letters * 0.4f) + 0.5f).toInt().coerceAtLeast(5)
}

private fun AnnotatedString.Builder.appendBionic(
    word: String,
    boldWeight: FontWeight,
    lightWeight: FontWeight,
) {
    val letterCount = word.count { it.isLetter() }
    val n = bionicBoldCount(letterCount)
    var bolded = 0
    for (c in word) {
        if (c.isLetter() && bolded < n) {
            withStyle(SpanStyle(fontWeight = boldWeight)) { append(c) }
            bolded++
        } else {
            withStyle(SpanStyle(fontWeight = lightWeight)) { append(c) }
        }
    }
}

internal fun bionicAnnotated(word: String, boldWeight: FontWeight, lightWeight: FontWeight): AnnotatedString =
    buildAnnotatedString { appendBionic(word, boldWeight, lightWeight) }

// Spritz-canonical pivot table by letter count.
private fun pivotLetterIndex(letters: Int): Int = when {
    letters <= 1 -> 0
    letters <= 5 -> 1
    letters <= 9 -> 2
    letters <= 13 -> 3
    else -> 4
}

// Returns the character index of the pivot letter inside `displayed`, counting
// only letters (so leading punctuation/quotes don't shift the focal point).
internal fun pivotCharIndex(displayed: String): Int? {
    var letters = 0
    for (c in displayed) if (c.isLetter()) letters++
    if (letters == 0) return null
    val target = pivotLetterIndex(letters).coerceAtMost(letters - 1)
    var seen = 0
    for (i in displayed.indices) {
        if (displayed[i].isLetter()) {
            if (seen == target) return i
            seen++
        }
    }
    return null
}

// Quote characters we recognize. Single quotes ' and ’ are intentionally
// excluded — they're ambiguous with apostrophes ("don't", "it's").
private val openQuoteChars = setOf('"', '“', '«', '„')
private val closeQuoteChars = setOf('"', '”', '»')
private val anyQuoteChar = openQuoteChars + closeQuoteChars

private fun matchingClose(openChar: Char): Char = when (openChar) {
    '“', '„' -> '”'
    '«' -> '»'
    else -> '"'
}

private fun startsWithQuote(s: String): Boolean {
    for (c in s) {
        if (c in anyQuoteChar) return true
        if (c.isLetterOrDigit()) return false
    }
    return false
}

private fun endsWithQuote(s: String): Boolean {
    for (i in s.indices.reversed()) {
        val c = s[i]
        if (c in anyQuoteChar) return true
        if (c.isLetterOrDigit()) return false
    }
    return false
}

private fun wrapInQuotes(word: String, openChar: Char): String {
    if (word.isEmpty()) return word
    val needLeading = !startsWithQuote(word)
    val needTrailing = !endsWithQuote(word)
    if (!needLeading && !needTrailing) return word
    val sb = StringBuilder(word.length + 2)
    if (needLeading) sb.append(openChar)
    sb.append(word)
    if (needTrailing) sb.append(matchingClose(openChar))
    return sb.toString()
}

// Walk the words and determine, for each word, whether it sits inside a
// double-quoted region. The opener character is recorded so the wrap uses a
// matching pair (curly with curly, guillemets with guillemets, etc.).
//
// Straight ASCII " has no open/close distinction, so we toggle on each
// occurrence. State is reset at paragraph boundaries to avoid runaway "stuck
// inside quote" behavior when an opening quote has no matching close (common
// with poorly-formed source text).
fun computeQuoteInfo(
    words: List<String>,
    paragraphBreakAfter: BooleanArray,
): Pair<BooleanArray, CharArray> {
    val n = words.size
    val inside = BooleanArray(n)
    val openChars = CharArray(n) { '"' }
    if (n == 0) return inside to openChars

    var state = false
    var openCh = '"'
    for (i in 0 until n) {
        val w = words[i]
        var any = state
        var anyOpen = if (state) openCh else '"'
        for (c in w) {
            when {
                c == '"' -> {
                    if (state) {
                        any = true
                        state = false
                    } else {
                        state = true
                        openCh = '"'
                        any = true
                        anyOpen = '"'
                    }
                }
                c in openQuoteChars -> {
                    state = true
                    openCh = c
                    any = true
                    anyOpen = c
                }
                c in closeQuoteChars -> {
                    any = true
                    state = false
                }
            }
        }
        inside[i] = any
        openChars[i] = anyOpen
        if (i < paragraphBreakAfter.size && paragraphBreakAfter[i]) {
            state = false
        }
    }
    return inside to openChars
}
