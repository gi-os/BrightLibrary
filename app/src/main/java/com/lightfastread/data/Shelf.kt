package com.lightfastread.data

/**
 * What the shelf actually shows: books, and stacks of books.
 *
 * Eleven volumes of one series used to be eleven covers of the same artwork, which is both eleven
 * scrolls of noise and no way to see that volume 4 is missing. A series is now one cell that opens
 * into a shelf of its own.
 *
 * Pure, and separate from the screen, because the grouping rules are the part worth testing: what
 * counts as a series, where a stack sits among loose books, and what order a stack is read in.
 */
sealed interface ShelfEntry {

    /** A book that belongs to no stack — either standalone, or the only volume of its series. */
    data class Single(val book: Book) : ShelfEntry

    data class Stack(
        val series: String,
        /** Volumes in reading order, which is not the order they were added. */
        val books: List<Book>,
    ) : ShelfEntry {
        /** The cover shown for the stack: the first volume, the one anybody would recognise. */
        val cover: Book get() = books.first()

        /**
         * Progress across the whole series, weighted by length.
         *
         * Not "volumes finished": half of a 200-page volume is real progress and a stack that reads
         * 0% until a volume is finished would be wrong for most of the time you spend in it.
         */
        val progress: Float
            get() {
                val total = books.sumOf { it.totalWords.toLong() }
                if (total <= 0L) return 0f
                val read = books.sumOf { it.currentWordIndex.toLong() }
                return (read.toDouble() / total).toFloat().coerceIn(0f, 1f)
            }

        /** Volumes with something read in them, for the caption. */
        val started: Int get() = books.count { it.currentWordIndex > 0 }
    }
}

object Shelf {

    /** Below this a "series" is just a book with a number in its title. */
    private const val MIN_VOLUMES_FOR_STACK = 2

    /**
     * Group [books] into what the shelf draws, preserving the order they arrived in.
     *
     * A stack takes the position of its **most recently added** volume, so downloading volume 12 of
     * something moves that whole series to the front — the same behaviour a single book has, for the
     * same reason: what you just added is what you are about to read.
     */
    fun entries(books: List<Book>): List<ShelfEntry> {
        val parsedByKey = LinkedHashMap<String, MutableList<Pair<Book, SeriesTitle.Parsed>>>()
        val loose = LinkedHashMap<String, Book>()

        for (book in books) {
            val parsed = SeriesTitle.parse(book.title)
            if (parsed == null) {
                loose[book.id] = book
            } else {
                parsedByKey.getOrPut(SeriesTitle.key(parsed.series)) { mutableListOf() }
                    .add(book to parsed)
            }
        }

        // A key with one volume is not a series; put that book back among the loose ones, in the
        // place it would have had.
        val stacks = LinkedHashMap<String, ShelfEntry.Stack>()
        for ((key, members) in parsedByKey) {
            if (members.size < MIN_VOLUMES_FOR_STACK) {
                loose[members.first().first.id] = members.first().first
                continue
            }
            stacks[key] = ShelfEntry.Stack(
                series = SeriesTitle.displayName(members.map { it.second.series }),
                books = members.sortedWith(
                    compareBy({ it.second.index }, { it.first.title })
                ).map { it.first },
            )
        }

        // Walk the original order once, emitting each book or the stack it belongs to, whichever
        // comes first. That is what keeps a stack where its newest volume was.
        val emitted = HashSet<String>()
        val out = ArrayList<ShelfEntry>(books.size)
        for (book in books) {
            val key = SeriesTitle.parse(book.title)?.let { SeriesTitle.key(it.series) }
            val stack = key?.let { stacks[it] }
            if (stack != null) {
                if (emitted.add(key)) out.add(stack)
            } else if (loose.containsKey(book.id)) {
                out.add(ShelfEntry.Single(book))
            }
        }
        return out
    }

    /** The volumes of one stack, for the series screen. Empty when nothing matches any more. */
    fun volumesOf(books: List<Book>, seriesKey: String): List<Book> =
        (entries(books).firstOrNull {
            it is ShelfEntry.Stack && SeriesTitle.key(it.series) == seriesKey
        } as? ShelfEntry.Stack)?.books.orEmpty()

    /** The name to put in the top bar of the series screen. */
    fun nameOf(books: List<Book>, seriesKey: String): String =
        (entries(books).firstOrNull {
            it is ShelfEntry.Stack && SeriesTitle.key(it.series) == seriesKey
        } as? ShelfEntry.Stack)?.series ?: seriesKey
}
