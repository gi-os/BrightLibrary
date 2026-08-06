package com.lightfastread.data

import android.content.Context
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import com.lightfastread.calibre.ProgressSync
import com.lightfastread.comic.ComicPages
import com.lightfastread.parser.HtmlStripper
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

data class LoadedText(
    val words: List<String>,
    val paragraphBreakAfter: BooleanArray,
    val italic: BooleanArray,
)

class BookRepository private constructor(private val appContext: Context) {
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val books: SnapshotStateList<Book> = load().toMutableStateList()

    private fun booksDir(): File = File(appContext.filesDir, "books").apply { mkdirs() }

    fun textFile(book: Book): File = File(booksDir(), book.textFileName)

    private fun load(): List<Book> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(Book.serializer()), raw)
        }.getOrElse { emptyList() }
    }

    private fun persist() {
        val raw = json.encodeToString(ListSerializer(Book.serializer()), books.toList())
        prefs.edit().putString(KEY, raw).apply()
    }

    fun addBook(book: Book, fullText: String) {
        val file = File(booksDir(), book.textFileName)
        file.writeText(fullText)
        books.add(0, book)
        persist()
    }

    /**
     * Convert chapter char offsets (positions in `fullText`) into word indices.
     * A "word" here matches the splitter used elsewhere: a maximal run of non-whitespace.
     */
    fun chaptersFromCharOffsets(
        fullText: String,
        rawChapters: List<Pair<String, Int>>,
    ): List<Chapter> {
        if (rawChapters.isEmpty()) return emptyList()
        val sorted = rawChapters
            .map { (title, off) -> title to off.coerceIn(0, fullText.length) }
            .sortedBy { it.second }
        val result = ArrayList<Chapter>(sorted.size)
        var charIdx = 0
        var wordIdx = 0
        var inWord = false
        var ci = 0
        while (charIdx < fullText.length && ci < sorted.size) {
            while (ci < sorted.size && sorted[ci].second <= charIdx) {
                result.add(Chapter(sorted[ci].first, wordIdx))
                ci++
            }
            val ch = fullText[charIdx]
            if (ch == HtmlStripper.ITALIC_START || ch == HtmlStripper.ITALIC_END) {
                // Zero-width markers — neither whitespace nor part of a word.
            } else if (ch.isWhitespace()) {
                if (inWord) {
                    wordIdx++
                    inWord = false
                }
            } else {
                inWord = true
                if (ch == '—' || ch == '–') {
                    wordIdx++
                    inWord = false
                }
            }
            charIdx++
        }
        // Any chapter whose offset is at/past end of text — clamp to last word.
        val tail = wordIdx + (if (inWord) 1 else 0)
        while (ci < sorted.size) {
            result.add(Chapter(sorted[ci].first, tail))
            ci++
        }
        return result
    }

    /**
     * Put a comic on the shelf.
     *
     * Separate from [addBook] because there is no text to write: the pages were converted into
     * `filesDir/comics/<id>` before this is called, and that directory is the book.
     */
    fun addComic(book: Book) {
        books.add(0, book)
        persist()
    }

    /**
     * Flip a comic between right-to-left and left-to-right.
     *
     * Per book rather than a setting: a shelf holds manga and Western scans at once, and the right
     * answer is a property of the volume, not of the reader.
     */
    fun setReadingDirection(id: String, rightToLeft: Boolean) {
        val idx = books.indexOfFirst { it.id == id }
        if (idx < 0 || books[idx].rightToLeft == rightToLeft) return
        books[idx] = books[idx].copy(rightToLeft = rightToLeft)
        persist()
    }

    fun deleteBook(book: Book) {
        textFile(book).delete()
        if (book.kind == BookKind.Comic) ComicPages.delete(appContext, book.id)
        Covers.delete(appContext, book)
        books.removeAll { it.id == book.id }
        persist()
    }

    /**
     * Attach cover art to a book that is already on the shelf.
     *
     * Separate from [addBook] because the Open Library fallback happens after the import — the
     * book should appear on the shelf the moment its text is parsed, not after a network round
     * trip that may never come back.
     */
    fun setCover(id: String, bytes: ByteArray): Boolean {
        val idx = books.indexOfFirst { it.id == id }
        if (idx < 0) return false
        val stored = Covers.store(appContext, id, bytes) ?: return false
        books[idx] = books[idx].copy(
            coverFileName = stored,
            coverUpdatedAtMs = System.currentTimeMillis(),
            coverSearchedAtMs = System.currentTimeMillis(),
        )
        persist()
        return true
    }

    /** Record that a cover was looked for and not found, so the sweep can back off. */
    fun markCoverSearched(id: String) {
        val idx = books.indexOfFirst { it.id == id }
        if (idx < 0) return
        books[idx] = books[idx].copy(coverSearchedAtMs = System.currentTimeMillis())
        persist()
    }

    /**
     * Correct a book's title and author.
     *
     * Ebook metadata is frequently wrong or absent — a filename where a title should be, an author
     * field holding a publisher — and everything downstream of it is downstream of that: what the
     * shelf reads, and what a cover search has to work with. Editing it is the fix for both, which
     * is why [com.lightfastread.ui.home.RenameBook] searches again straight afterwards.
     *
     * `coverSearchedAtMs` is cleared deliberately: a new name is a new question, so the sweep's
     * back-off must not suppress it.
     */
    fun rename(id: String, title: String, author: String) {
        val idx = books.indexOfFirst { it.id == id }
        if (idx < 0) return
        val cleanTitle = title.trim().ifBlank { books[idx].title }
        books[idx] = books[idx].copy(
            title = cleanTitle,
            author = author.trim(),
            coverSearchedAtMs = 0L,
        )
        persist()
    }

    fun getBook(id: String): Book? = books.firstOrNull { it.id == id }

    fun updateProgress(id: String, wordIndex: Int) {
        val idx = books.indexOfFirst { it.id == id }
        if (idx < 0) return
        val current = books[idx]
        if (current.currentWordIndex == wordIndex) return
        books[idx] = current.copy(currentWordIndex = wordIndex)
        persist()
        // Fires on every word at reading speed, so it has to be cheap: [ProgressSync.onProgress] is
        // a clock comparison and a return in all but one call a minute.
        if (current.calibreUuid != null) ProgressSync.onProgress(appContext)
    }

    /**
     * Record what the Calibre server has been told.
     *
     * Written only after the server confirms it stored the position, because this is the *only* thing
     * that knows a push is still owed — see [Book.calibreSyncedPercent].
     */
    fun markCalibreSynced(id: String, percent: Int) {
        val idx = books.indexOfFirst { it.id == id }
        if (idx < 0) return
        if (books[idx].calibreSyncedPercent == percent) return
        books[idx] = books[idx].copy(calibreSyncedPercent = percent)
        persist()
    }

    /** Whether a book from this Calibre uuid is already on the shelf. */
    fun hasCalibreBook(uuid: String): Boolean = books.any { it.calibreUuid == uuid }

    fun toggleBookmark(id: String, wordIndex: Int): Boolean {
        val idx = books.indexOfFirst { it.id == id }
        if (idx < 0) return false
        val current = books[idx]
        val newBookmarks = if (current.bookmarks.contains(wordIndex)) {
            current.bookmarks - wordIndex
        } else {
            (current.bookmarks + wordIndex).sorted()
        }
        books[idx] = current.copy(bookmarks = newBookmarks)
        persist()
        return newBookmarks.contains(wordIndex)
    }

    fun loadWordsAndBreaks(book: Book): LoadedText {
        val file = textFile(book)
        if (!file.exists()) return LoadedText(emptyList(), BooleanArray(0), BooleanArray(0))
        val text = file.readText()
        val words = ArrayList<String>()
        val breaks = ArrayList<Boolean>()
        val italics = ArrayList<Boolean>()
        val current = StringBuilder()
        var pendingNewline = false
        var italicOn = false
        var wordItalic = false

        fun flushWord() {
            if (current.isNotEmpty()) {
                words.add(current.toString())
                breaks.add(false)
                italics.add(wordItalic)
                current.clear()
                wordItalic = italicOn
            }
        }

        for (ch in text) {
            when {
                ch == HtmlStripper.ITALIC_START -> {
                    italicOn = true
                    wordItalic = true
                }
                ch == HtmlStripper.ITALIC_END -> {
                    italicOn = false
                }
                ch.isWhitespace() -> {
                    flushWord()
                    if (ch == '\n' || ch == '\r') pendingNewline = true
                }
                else -> {
                    if (pendingNewline && breaks.isNotEmpty()) {
                        breaks[breaks.size - 1] = true
                    }
                    pendingNewline = false
                    if (current.isEmpty()) wordItalic = italicOn
                    current.append(ch)
                    if (italicOn) wordItalic = true
                    // Em/en dashes terminate the current word (kept attached to it),
                    // so "reality—that" becomes "reality—" + "that".
                    if (ch == '—' || ch == '–') flushWord()
                }
            }
        }
        flushWord()
        return LoadedText(
            words,
            BooleanArray(breaks.size) { breaks[it] },
            BooleanArray(italics.size) { italics[it] },
        )
    }

    companion object {
        private const val PREFS = "fastread_books"
        private const val KEY = "books_json"

        /** Word count that matches the splitter in [loadWordsAndBreaks]. */
        fun countWords(text: String): Int {
            var count = 0
            var hasContent = false
            for (ch in text) {
                if (ch == HtmlStripper.ITALIC_START || ch == HtmlStripper.ITALIC_END) {
                    // Zero-width italic markers — ignore.
                } else if (ch.isWhitespace()) {
                    if (hasContent) { count++; hasContent = false }
                } else {
                    hasContent = true
                    if (ch == '—' || ch == '–') { count++; hasContent = false }
                }
            }
            if (hasContent) count++
            return count
        }

        @Volatile private var instance: BookRepository? = null
        fun get(context: Context): BookRepository =
            instance ?: synchronized(this) {
                instance ?: BookRepository(context.applicationContext).also { instance = it }
            }
    }
}
