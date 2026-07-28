package com.lightfastread.data

import android.content.Context
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
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

    fun deleteBook(book: Book) {
        textFile(book).delete()
        books.removeAll { it.id == book.id }
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
    }

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
