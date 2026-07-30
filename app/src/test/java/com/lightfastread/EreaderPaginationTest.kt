package com.lightfastread

import com.lightfastread.ui.reader.LineStartsProbe
import com.lightfastread.ui.reader.buildMeasuredPages
import com.lightfastread.ui.reader.pageOfWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The invariants that matter for the ereader, checked against a stand-in text
 * layout.
 *
 * The bug these guard against was silent: pages were assigned more text than fit,
 * Text clipped the overflow, and because the next page began after the whole
 * over-assigned range the clipped lines were never drawn on any page at all. Two
 * properties rule that out, and both are asserted here:
 *
 *  1. **Nothing is skipped.** The pages tile the word range exactly - contiguous,
 *     no gaps, no overlaps, first word to last.
 *  2. **Nothing overflows.** Re-laying out any page's own word range produces no
 *     more lines than fit on a page.
 *
 * A third test pins the block-walking logic specifically: pagination is done in
 * blocks for speed, and paginating the same text with wildly different block sizes
 * has to produce byte-identical pages. That is what proves dropping each block's
 * last (untrustworthy) line and restarting there is equivalent to measuring the
 * whole book in one pass.
 */
class EreaderPaginationTest {

    /**
     * A greedy line breaker standing in for real text layout. Widths are in
     * characters, a space costs 1, and a paragraph break emits a blank line then
     * starts the next paragraph - the same shape the real
     * TextMeasurer-backed probe reports, including reporting a blank line as the
     * first word of the paragraph after it.
     */
    private class FakeLayout(
        val words: List<String>,
        val paragraphBreakAfter: BooleanArray,
        val maxWidth: Int,
    ) : LineStartsProbe {

        override fun lineStarts(from: Int, toInclusive: Int): IntArray {
            val out = ArrayList<Int>()
            out.add(from)
            var used = 0
            var lineHasWord = false
            for (i in from..toInclusive) {
                val w = words[i].length
                val add = if (lineHasWord) 1 + w else w
                if (lineHasWord && used + add > maxWidth) {
                    out.add(i)
                    used = w
                } else {
                    used += add
                }
                lineHasWord = true
                if (paragraphBreakAfter[i] && i < toInclusive) {
                    // The blank line, then the next paragraph's first line. Both
                    // report the next word, so equal consecutive entries are
                    // expected and the pager code has to tolerate them.
                    out.add(i + 1)
                    out.add(i + 1)
                    used = 0
                    lineHasWord = false
                }
            }
            return out.toIntArray()
        }

        /** Lines needed to render exactly [range] on its own, as the reader does. */
        fun lineCountOf(range: IntRange): Int =
            lineStarts(range.first, range.last).size
    }

    private fun corpus(seed: Int, count: Int): Pair<List<String>, BooleanArray> {
        val rng = Random(seed)
        val lengths = intArrayOf(1, 2, 2, 3, 3, 3, 4, 4, 4, 5, 5, 6, 7, 8, 9, 12, 16)
        val words = ArrayList<String>(count)
        val breaks = BooleanArray(count)
        for (i in 0 until count) {
            val n = lengths[rng.nextInt(lengths.size)]
            words.add(buildString { repeat(n) { append('a' + rng.nextInt(26)) } })
            // Paragraphs of ~40 words, so blank lines land at page boundaries too.
            breaks[i] = rng.nextInt(40) == 0
        }
        return words to breaks
    }

    private fun assertTilesExactly(pages: List<IntRange>, wordCount: Int) {
        assertTrue("expected pages", pages.isNotEmpty())
        assertEquals("first page starts at the first word", 0, pages.first().first)
        assertEquals("last page ends at the last word", wordCount - 1, pages.last().last)
        for (i in pages.indices) {
            assertTrue("page $i is non-empty", pages[i].last >= pages[i].first)
            if (i > 0) {
                assertEquals(
                    "page $i must start exactly where page ${i - 1} ended - a gap here " +
                        "is text the reader can never reach",
                    pages[i - 1].last + 1,
                    pages[i].first,
                )
            }
        }
    }

    @Test
    fun `pages tile the book with no gaps or overlaps`() {
        val (words, breaks) = corpus(seed = 1, count = 20_000)
        val layout = FakeLayout(words, breaks, maxWidth = 34)
        val pages = buildMeasuredPages(
            wordCount = words.size,
            linesPerPage = 10,
            probeWords = 1500,
            probe = layout,
        )
        assertTilesExactly(pages, words.size)
    }

    @Test
    fun `no page needs more lines than fit on a page`() {
        val linesPerPage = 10
        val (words, breaks) = corpus(seed = 2, count = 20_000)
        val layout = FakeLayout(words, breaks, maxWidth = 34)
        val pages = buildMeasuredPages(
            wordCount = words.size,
            linesPerPage = linesPerPage,
            probeWords = 1500,
            probe = layout,
        )
        assertTilesExactly(pages, words.size)
        for ((i, page) in pages.withIndex()) {
            val needed = layout.lineCountOf(page)
            assertTrue(
                "page $i ($page) needs $needed lines but only $linesPerPage fit - " +
                    "the overflow would be clipped and lost",
                needed <= linesPerPage,
            )
        }
    }

    @Test
    fun `block size does not change the resulting pages`() {
        val (words, breaks) = corpus(seed = 3, count = 12_000)
        val layout = FakeLayout(words, breaks, maxWidth = 40)
        val reference = buildMeasuredPages(
            wordCount = words.size,
            linesPerPage = 9,
            probeWords = 11,
            probe = layout,
        )
        assertTilesExactly(reference, words.size)
        for (probeWords in intArrayOf(37, 250, 1500, 100_000)) {
            val pages = buildMeasuredPages(
                wordCount = words.size,
                linesPerPage = 9,
                probeWords = probeWords,
                probe = layout,
            )
            assertEquals(
                "pagination must not depend on the measuring block size (probeWords=$probeWords)",
                reference,
                pages,
            )
        }
    }

    @Test
    fun `every word maps back to the page that contains it`() {
        val (words, breaks) = corpus(seed = 4, count = 6_000)
        val layout = FakeLayout(words, breaks, maxWidth = 30)
        val pages = buildMeasuredPages(
            wordCount = words.size,
            linesPerPage = 8,
            probeWords = 600,
            probe = layout,
        )
        assertTilesExactly(pages, words.size)
        for (word in words.indices step 7) {
            val page = pageOfWord(pages, word)
            assertTrue(
                "word $word mapped to page $page (${pages[page]}) which does not contain it",
                word in pages[page],
            )
        }
        // Out-of-range lookups clamp rather than throw; opening the reader must
        // never crash on a stale saved position.
        assertEquals(0, pageOfWord(pages, -5))
        assertEquals(pages.size - 1, pageOfWord(pages, words.size + 999))
        assertEquals(0, pageOfWord(emptyList(), 3))
    }

    @Test
    fun `degenerate inputs produce no pages instead of failing`() {
        val empty = buildMeasuredPages(0, 10, 100, { _, _ -> IntArray(0) })
        assertTrue(empty.isEmpty())
        val noLines = buildMeasuredPages(100, 0, 100, { from, _ -> intArrayOf(from) })
        assertTrue(noLines.isEmpty())
    }
}
