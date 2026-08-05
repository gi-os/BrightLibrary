package com.lightfastread

import com.lightfastread.data.Covers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The query builder, which is the whole of why a cover search finds anything.
 *
 * Every case here is a real shape ebook metadata arrives in. They are worth pinning because the
 * failure they cause is invisible: a query that matches nothing returns a valid, empty response,
 * so the app reports "no cover" and looks like it never searched at all.
 */
class CoverSearchTest {

    @Test
    fun `surname-first authors are flipped into reading order`() {
        assertEquals("Frank Herbert", Covers.normaliseAuthor("Herbert, Frank"))
        assertEquals("Frank Herbert", Covers.normaliseAuthor("  Herbert ,  Frank "))
    }

    @Test
    fun `a list of contributors becomes its first entry`() {
        // Open Library's `author=` matches a single value, so a list finds nothing at all.
        assertEquals("Neil Gaiman", Covers.normaliseAuthor("Neil Gaiman; Terry Pratchett"))
        assertEquals("Neil Gaiman", Covers.normaliseAuthor("Neil Gaiman & Terry Pratchett"))
    }

    @Test
    fun `two commas is a list, not a surname, so nothing is flipped`() {
        assertEquals("Smith, John, editor", Covers.normaliseAuthor("Smith, John, editor"))
    }

    @Test
    fun `filenames and bracketed suffixes are stripped from titles`() {
        // Case is left alone: the catalogue matches case-insensitively, and this string is only
        // ever a query — the title the shelf shows is whatever the user or the file said.
        assertEquals("dune messiah", Covers.normaliseTitle("dune_messiah.epub"))
        assertEquals("Neuromancer", Covers.normaliseTitle("Neuromancer (Z-Library)"))
        assertEquals("Piranesi", Covers.normaliseTitle("Piranesi [Retail]  - copy"))
    }

    @Test
    fun `the subtitle is dropped only in the later attempts`() {
        val queries = Covers.searchQueries(
            "The Body Keeps the Score: Brain, Mind, and Body",
            "van der Kolk, Bessel",
        )
        assertTrue(queries[0].contains("Brain"))
        assertTrue(queries[0].contains("author=Bessel+van+der+Kolk"))
        // Widening, in order: full title, short title, short title alone, then a fuzzy search.
        assertTrue(queries[1].contains("author=") && !queries[1].contains("Brain"))
        assertTrue(queries.last().startsWith("q="))
        assertEquals(4, queries.size)
    }

    @Test
    fun `no author means no author-qualified attempts`() {
        val queries = Covers.searchQueries("Piranesi", "")
        assertEquals(2, queries.size)
        assertTrue(queries.none { it.contains("author=") })
    }

    @Test
    fun `a title that normalises to nothing is not searched for`() {
        assertTrue(Covers.searchQueries("   (unknown)  ", "Anyone").isEmpty())
    }
}
