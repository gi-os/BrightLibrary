package com.lightfastread.reading

import android.content.Context
import com.lightfastread.data.Book
import org.json.JSONArray
import org.json.JSONObject

/**
 * When you read, and how much of it.
 *
 * The shelf has always known *where* you are in a book and never *when* you were there: a position
 * and an import date, and nothing in between. So "what did you read today" was unanswerable from
 * this app, which is the one thing BrightNotebook wanted from it — a day that had an hour of reading
 * in it looked like a day nothing happened on.
 *
 * ### Sessions, not page turns
 *
 * Progress is written on every word at RSVP speed, which is several times a second. A record per
 * write would be tens of thousands of rows a day and a timeline nobody could read. So a session is
 * *opened* by the first progress in a while and *extended* by everything after it, and closed by
 * [GAP_MS] of silence or by the reader being left. That is the same coalescing
 * `ProgressSync.onProgress` already does for the Calibre push, for the same reason.
 *
 * ### What a session says
 *
 * The book, when it started, when it was last touched, and the position at each end. Words rather
 * than percent, because a percentage of a book is a number that changes meaning between books and
 * "eleven pages" does not — and for a comic the position *is* the page, which the shelf has always
 * treated as one field for exactly this reason.
 *
 * Stored as JSON in its own preferences file, the way the shelf itself is. A database for a list
 * this size would be machinery this app has done without everywhere else.
 */
data class Session(
    val bookId: String,
    val title: String,
    val author: String,
    val startedMs: Long,
    val lastMs: Long,
    val fromIndex: Int,
    val toIndex: Int,
    /** True for a comic, where a position is a page rather than a word. */
    val pages: Boolean,
) {
    /** How far it moved. Negative when somebody read backwards, which is not a reading session. */
    val advanced: Int get() = toIndex - fromIndex

    val minutes: Int get() = (((lastMs - startedMs) / 60_000L).toInt()).coerceAtLeast(0)

    fun toJson(): JSONObject = JSONObject()
        .put("bookId", bookId)
        .put("title", title)
        .put("author", author)
        .put("startedMs", startedMs)
        .put("lastMs", lastMs)
        .put("fromIndex", fromIndex)
        .put("toIndex", toIndex)
        .put("pages", pages)

    companion object {
        fun fromJson(o: JSONObject): Session = Session(
            bookId = o.optString("bookId"),
            title = o.optString("title"),
            author = o.optString("author"),
            startedMs = o.optLong("startedMs"),
            lastMs = o.optLong("lastMs"),
            fromIndex = o.optInt("fromIndex"),
            toIndex = o.optInt("toIndex"),
            pages = o.optBoolean("pages"),
        )
    }
}

object Sessions {

    /**
     * Progress happened. Opens a session, or extends the one already open.
     *
     * Cheap on purpose: one preferences read and, in the common case, one write of a list that is a
     * few dozen entries long. It is called at reading speed, so anything more would be felt.
     */
    @Synchronized
    fun note(context: Context, book: Book, wordIndex: Int, now: Long = System.currentTimeMillis()) {
        val list = all(context)
        val open = list.firstOrNull()
        val extends = open != null &&
            open.bookId == book.id &&
            now - open.lastMs <= GAP_MS
        val next = if (extends && open != null) {
            listOf(open.copy(lastMs = now, toIndex = wordIndex)) + list.drop(1)
        } else {
            listOf(
                Session(
                    bookId = book.id,
                    title = book.title,
                    author = book.author,
                    startedMs = now,
                    lastMs = now,
                    fromIndex = wordIndex,
                    toIndex = wordIndex,
                    pages = book.kind == com.lightfastread.data.BookKind.Comic,
                ),
            ) + list
        }
        write(context, next)
    }

    /**
     * Every session, newest first — the whole log, for whoever is asking about a day.
     *
     * Sessions that never went anywhere are dropped on the way out rather than never written:
     * opening a book to check where you were is not reading, and it is only knowable afterwards.
     * A session that advanced nothing and lasted under a minute is that.
     */
    fun all(context: Context, meaningfulOnly: Boolean = false): List<Session> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        val list = runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { Session.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        if (!meaningfulOnly) return list
        return list.filter { it.advanced > 0 || it.minutes >= 1 }
    }

    private fun write(context: Context, list: List<Session>) {
        val trimmed = list.take(KEEP)
        val json = JSONArray().apply { trimmed.forEach { put(it.toJson()) } }.toString()
        prefs(context).edit().putString(KEY, json).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** The preferences file, named so LightSync can back it up alongside the shelf. */
    const val FILE = "fastread_reading"

    private const val KEY = "sessions"

    /** Silence longer than this and the next page turn is a new sitting. */
    const val GAP_MS = 10 * 60_000L

    /** Weeks of reading, and bounded whatever happens. */
    private const val KEEP = 400
}
