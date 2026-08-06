package com.lightfastread

import com.lightfastread.data.Book
import com.lightfastread.data.SeriesTitle
import com.lightfastread.data.Shelf
import com.lightfastread.data.ShelfEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What collects into a stack, and what stays a book.
 *
 * The titles here are real ones off the shelf. Two failure modes matter and neither is visible when
 * it happens: a volume that hides inside the wrong stack, and a novel dragged into a series because
 * its title happens to end in a number.
 */
class ShelfSeriesTest {

    private var seq = 0
    private fun book(title: String, words: Int = 100, read: Int = 0) = Book(
        id = "id${seq++}",
        title = title,
        format = "EPUB",
        textFileName = "x.txt",
        totalWords = words,
        currentWordIndex = read,
    )

    @Test
    fun `the library's own title shapes all parse`() {
        assertEquals("BECK" to 3f, SeriesTitle.parse("BECK, Vol. 3")!!.let { it.series to it.index })
        assertEquals("Wind Breaker" to 12f, SeriesTitle.parse("Wind Breaker, Vol. 12")!!.let { it.series to it.index })
        assertEquals("Chi's Sweet Home" to 5f, SeriesTitle.parse("Chi's Sweet Home Volume 5")!!.let { it.series to it.index })
        assertEquals("Love Hina" to 2f, SeriesTitle.parse("Love Hina #2")!!.let { it.series to it.index })
        val sub = SeriesTitle.parse("Twilight Out of Focus, Vol. 3: Overlap")!!
        assertEquals("Twilight Out of Focus", sub.series)
        assertEquals(3f, sub.index, 0f)
        assertEquals("Overlap", sub.subtitle)
    }

    @Test
    fun `a title that merely ends in a number is not a series`() {
        // The reason the bare trailing-number pattern is deliberately absent: these would all
        // become series, and "100 Years Quest" would become volume 100 of "Fairy Tail:".
        assertNull(SeriesTitle.parse("Fairy Tail: 100 Years Quest"))
        assertNull(SeriesTitle.parse("2001: A Space Odyssey"))
        assertNull(SeriesTitle.parse("The Three-Body Problem"))
        assertNull(SeriesTitle.parse("Vol. 4"))
    }

    @Test
    fun `two volumes make a stack and a lone volume does not`() {
        val books = listOf(
            book("BECK, Vol. 2"),
            book("The Three-Body Problem"),
            book("BECK, Vol. 1"),
            book("Parasyte, Vol. 6"),
        )
        val entries = Shelf.entries(books)
        assertEquals(3, entries.size)
        val stack = entries[0] as ShelfEntry.Stack
        assertEquals("BECK", stack.series)
        // Volumes read in order, not in the order they were added.
        assertEquals(listOf("BECK, Vol. 1", "BECK, Vol. 2"), stack.books.map { it.title })
        // The stack sits where its newest volume was, and the singles keep their places.
        assertTrue(entries[1] is ShelfEntry.Single)
        assertEquals("The Three-Body Problem", (entries[1] as ShelfEntry.Single).book.title)
        assertEquals("Parasyte, Vol. 6", (entries[2] as ShelfEntry.Single).book.title)
    }

    @Test
    fun `the same series spelled three ways is one stack`() {
        val entries = Shelf.entries(
            listOf(book("WIND BREAKER v1"), book("Wind Breaker, Vol. 2"), book("wind breaker vol3"))
        )
        assertEquals(1, entries.size)
        val stack = entries.single() as ShelfEntry.Stack
        assertEquals(3, stack.books.size)
        // The fullest spelling wins the label, and on a tie the one that isn't shouting.
        assertEquals("Wind Breaker", stack.series)
    }

    @Test
    fun `scan-group file names parse too`() {
        // What a file dragged in off disk actually looks like, tags and all.
        val bocchi = SeriesTitle.parse("Bocchi the Rock! v01 (2023) (Digital) (1r0n)")!!
        assertEquals("Bocchi the Rock!", bocchi.series)
        assertEquals(1f, bocchi.index, 0f)
        assertEquals("Nichijou", SeriesTitle.parse("Nichijou v12")!!.series)
        assertEquals(15f, SeriesTitle.parse("Yotsuba&!, Vol. 15 (Digital)")!!.index, 0f)
        // A pile of tags with no volume number is still one book.
        assertNull(SeriesTitle.parse("Azumanga Daioh {Omnibus Edition} (2009) (Scanned)"))
    }

    @Test
    fun `progress across a stack is weighted by length, not counted in volumes`() {
        val stack = Shelf.entries(
            listOf(
                book("BECK, Vol. 1", words = 1000, read = 1000),
                book("BECK, Vol. 2", words = 3000, read = 0),
            )
        ).single() as ShelfEntry.Stack
        // One of two volumes finished, but a quarter of the reading done.
        assertEquals(0.25f, stack.progress, 0.001f)
        assertEquals(1, stack.started)
    }

    @Test
    fun `a stack can be looked up by key after the fact`() {
        val books = listOf(book("BECK, Vol. 1"), book("BECK, Vol. 2"), book("Untitled"))
        val key = SeriesTitle.key("BECK")
        assertEquals(2, Shelf.volumesOf(books, key).size)
        assertEquals("BECK", Shelf.nameOf(books, key))
        assertTrue(Shelf.volumesOf(books, SeriesTitle.key("Nothing")).isEmpty())
    }
}
