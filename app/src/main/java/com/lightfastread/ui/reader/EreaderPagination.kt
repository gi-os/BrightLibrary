package com.lightfastread.ui.reader

/**
 * Splitting a book into pages that actually fit the screen.
 *
 * The first version of the ereader guessed at line breaks: it derived an average
 * character width, divided the page width by it, and packed words onto a line
 * until that character budget ran out. That model is wrong often enough to matter.
 * Real glyphs are not all the average width, so a modelled line of N "average"
 * characters is frequently wider than the page and wraps early. Simulated against
 * realistic glyph widths, **45% of pages ended up assigned more text than could
 * physically fit**, by up to two lines.
 *
 * That silently *deleted* text. Compose's [androidx.compose.material3.Text]
 * defaults to clipping overflow, and the next page started after the whole
 * over-assigned word range - so the clipped lines were never rendered on any
 * page. It reads as "the bottom of the page is cut off" and "pages in between are
 * missing", which are the same bug seen from two angles.
 *
 * So nothing here estimates. Pages are built from real measured line breaks,
 * supplied by [LineStartsProbe], and the exact same text builder that feeds the
 * measurement also feeds the renderer - so what was measured is what is drawn.
 *
 * This file is deliberately free of Android and Compose imports so the awkward
 * part (the block walking) can be unit-tested on the JVM. See
 * `EreaderPaginationTest`.
 */

/** Lays out a range of words and reports where each resulting visual line begins. */
internal fun interface LineStartsProbe {
    /**
     * Lay out `words[from..toInclusive]` at the page width and return, for each
     * visual line produced, the index of the first word on that line.
     *
     * The result is non-decreasing and its first element is always [from]. Blank
     * lines (the gap between paragraphs) report the first word of the paragraph
     * that follows them, so two consecutive entries can be equal.
     */
    fun lineStarts(from: Int, toInclusive: Int): IntArray
}

/**
 * Walks the whole book through [probe] and groups the measured lines into pages of
 * [linesPerPage] lines each.
 *
 * Measuring the entire book in one layout pass is not an option - it is megabytes
 * of text - so it is measured in blocks of roughly [probeWords] words. The catch
 * is that a block's *last* line is untrustworthy: it ended because the block ran
 * out of words, not because the page ran out of width, so more text might really
 * have fitted on it. Every other line in the block is exact, because greedy line
 * breaking is local - restarting a layout at any line boundary reproduces the
 * same breaks from there on. So each block contributes all its lines except the
 * last, and the next block restarts at that dropped line. The result is identical
 * to measuring the book in one pass, which the unit tests assert by paginating the
 * same text at several block sizes and requiring the same pages out.
 *
 * @param onProgress called with 0f..1f as the walk proceeds; pagination of a novel
 *   takes long enough to want a progress indicator behind it.
 */
internal fun buildMeasuredPages(
    wordCount: Int,
    linesPerPage: Int,
    probeWords: Int,
    probe: LineStartsProbe,
    onProgress: (Float) -> Unit = {},
): List<IntRange> {
    if (wordCount <= 0 || linesPerPage <= 0) return emptyList()

    val lineStarts = ArrayList<Int>(wordCount / 8 + 16)
    val block = probeWords.coerceAtLeast(8)
    var word = 0

    while (word < wordCount) {
        var span = block
        var starts = IntArray(0)
        var ranToEnd = false
        // A block that yields a single line while text remains tells us nothing:
        // that line was cut short by the block boundary rather than by the page
        // width, and dropping it as untrustworthy would leave no lines at all and
        // no forward progress. Grow the block and measure again.
        while (true) {
            val to = (word + span - 1).coerceAtMost(wordCount - 1)
            ranToEnd = to == wordCount - 1
            starts = probe.lineStarts(word, to)
            if (starts.size >= 2 || ranToEnd) break
            span *= 2
        }
        if (starts.isEmpty()) break

        val usable = if (ranToEnd) starts.size else starts.size - 1
        for (i in 0 until usable) lineStarts.add(starts[i])

        val next = if (ranToEnd) wordCount else starts[usable]
        // Guard: never loop forever if a block somehow fails to advance.
        word = if (next > word) next else (word + span).coerceAtMost(wordCount)
        onProgress(word.toFloat() / wordCount)
    }

    if (lineStarts.isEmpty()) return emptyList()

    val pages = ArrayList<IntRange>(lineStarts.size / linesPerPage + 1)
    var line = 0
    while (line < lineStarts.size) {
        val startWord = lineStarts[line]
        val nextLine = line + linesPerPage
        val endExclusive = if (nextLine < lineStarts.size) lineStarts[nextLine] else wordCount
        // A page can come out empty if its whole run of lines was paragraph gaps.
        // Skipping it loses no words: the following page already starts at the
        // same word.
        if (endExclusive > startWord) pages.add(startWord until endExclusive)
        line = nextLine
    }
    return pages
}

/**
 * The page holding [word]. Pages are contiguous and sorted, so this is a binary
 * search; if [word] somehow falls outside every page the nearest page is returned
 * rather than failing, since this only ever decides where to open the reader.
 */
internal fun pageOfWord(pages: List<IntRange>, word: Int): Int {
    if (pages.isEmpty()) return 0
    var lo = 0
    var hi = pages.size - 1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        val range = pages[mid]
        when {
            word < range.first -> hi = mid - 1
            word > range.last -> lo = mid + 1
            else -> return mid
        }
    }
    return lo.coerceIn(0, pages.size - 1)
}
