package com.lightfastread.data

/**
 * Reading a series and a volume number out of a book's title.
 *
 * There is no series field on a [Book], and there deliberately isn't one: the shelf's titles already
 * carry the answer — "BECK, Vol. 3", "Wind Breaker, Vol. 12" — and a stored field would be a second
 * source of truth that a renamed book, a re-import or a file dragged in from storage could
 * contradict. Parsing the title means a book is in a series exactly when it looks like it is.
 *
 * OPDS is no help here either. calibre-web publishes series as *navigation feeds* rather than as a
 * field on an entry, so a downloaded book arrives knowing its title and nothing about its family.
 *
 * Pure Kotlin, and pinned by tests: the shapes below came off a real shelf, and the cost of getting
 * one wrong is a volume that hides inside the wrong stack.
 */
object SeriesTitle {

    data class Parsed(
        val series: String,
        val index: Float,
        /** The bit after the volume number, when a volume has its own name. */
        val subtitle: String?,
    )

    /**
     * Patterns, most explicit first.
     *
     * The bare trailing-number case (`Wind Breaker 12`) is deliberately **not** here. It matches far
     * too much — *Fairy Tail: 100 Years Quest*, *Blake's 7*, *2001* — and the library's own titles
     * are normalised to the explicit forms anyway. A book whose title merely ends in a digit stays a
     * book, which is the safe way to be wrong.
     */
    private val PATTERNS = listOf(
        // "BECK, Vol. 3" / "BECK, Vol. 3: The Long Road" / "BECK, Volume 3"
        Regex("""^(.+?),\s*Vol(?:ume)?\.?\s*(\d+(?:\.\d+)?)\s*(?::\s*(.+))?$""", RegexOption.IGNORE_CASE),
        // "BECK Vol. 3" — no comma, which is how most files off disk are named
        Regex("""^(.+?)\s+Vol(?:ume)?\.?\s*(\d+(?:\.\d+)?)\s*(?::\s*(.+))?$""", RegexOption.IGNORE_CASE),
        // "BECK #3"
        Regex("""^(.+?)\s*#\s*(\d+(?:\.\d+)?)\s*(?::\s*(.+))?$"""),
        // "The Lord of the Rings, Book 2"
        Regex("""^(.+?),\s*Book\s*(\d+(?:\.\d+)?)\s*(?::\s*(.+))?$""", RegexOption.IGNORE_CASE),
        // "Bocchi the Rock! v01", "Nichijou v05" — how scanned files are named, and therefore how a
        // book imported straight off disk arrives. Only with digits directly after the v, so
        // "Vol." cannot reach this and neither can an ordinary word.
        Regex("""^(.+?)\s+v(\d{1,3})$""", RegexOption.IGNORE_CASE),
    )

    /**
     * Bracketed tags at the end of a title, which scans carry in quantity:
     * `Bocchi the Rock! v01 (2023) (Digital) (1r0n)`. They sit between the volume number and the end
     * of the string, so every pattern above would miss without this.
     */
    private val TRAILING_TAGS = Regex("""(?:\s*[\(\[\{][^)\]}]*[\)\]\}])+\s*$""")

    fun parse(title: String): Parsed? {
        var text = title.trim()
        // Strip repeatedly: one pass leaves "(Digital) (1r0n)" half-removed.
        while (true) {
            val stripped = TRAILING_TAGS.replace(text, "").trim()
            if (stripped == text || stripped.isEmpty()) break
            text = stripped
        }
        for (pattern in PATTERNS) {
            val match = pattern.find(text) ?: continue
            val name = match.groupValues[1].trim().trim(',', '-', ':', ' ')
            val index = match.groupValues[2].toFloatOrNull() ?: continue
            if (name.isBlank()) continue
            val subtitle = match.groupValues.getOrNull(3)?.trim()?.ifBlank { null }
            return Parsed(name, index, subtitle)
        }
        return null
    }

    /**
     * The key two books are grouped by.
     *
     * Case and punctuation are ignored, because the same series arrives spelled three ways over a
     * year of imports — "WIND BREAKER 1" from a publisher, "Wind Breaker, Vol. 2" from Calibre,
     * "wind breaker vol3" from a file. They belong in one stack.
     */
    fun key(series: String): String =
        series.lowercase().replace(Regex("""[^a-z0-9]+"""), " ").trim()

    /**
     * The name to show for a stack, given every spelling in it.
     *
     * The longest one wins, which sounds arbitrary and is not: abbreviations and truncations are the
     * common failure ("Fairy Tail" vs "Fairy Tail: 100 Years Quest"), and the fuller name is the one
     * that tells you which series this is. Ties go to the first, so the order stays stable.
     */
    fun displayName(names: List<String>): String =
        names.minWithOrNull(
            // Longest first, and on a tie prefer the one that isn't shouting: publishers title a
            // volume "WIND BREAKER 1" while the catalogue says "Wind Breaker, Vol. 2", and a shelf
            // full of block capitals reads worse than one that isn't. Order among equals is stable.
            compareByDescending<String> { it.length }
                .thenBy { if (it == it.uppercase() && it != it.lowercase()) 1 else 0 }
        ) ?: ""
}
