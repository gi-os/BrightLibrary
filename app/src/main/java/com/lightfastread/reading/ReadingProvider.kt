package com.lightfastread.reading

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * What you read on a given day, offered to the rest of the collection.
 *
 * `content://com.lightfastread.reading/reading/2026-08-25` answers with a row per sitting that began
 * that calendar date: the book, when it started, when it was last turned, how far it moved, and
 * whether those units are words or pages.
 *
 * The same shape as BrightRecorder's clips and BrightWay's trips, and for the same reason —
 * BrightNotebook's day is built out of what the other apps know, and "read 32 pages of Blood
 * Meridian" is a line only this app can write. Calendar dates and the log's own order, because a
 * journal day starting at four in the morning is the caller's opinion and not this app's.
 *
 * No permission, like the collection's other bridges: it reveals what you are reading, on a phone
 * with one user and a hand-picked set of applications. This is deliberately *not* the LightSync
 * provider next door, which checks its caller's signature — that one hands over the whole shelf,
 * and this one hands over a day.
 */
class ReadingProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(COLUMNS)
        val context = context ?: return cursor
        val segments = uri.pathSegments
        if (segments.size < 2 || segments[0] != PATH) return cursor
        val date = runCatching { LocalDate.parse(segments[1]) }.getOrNull() ?: return cursor
        val zone = ZoneId.systemDefault()
        runCatching {
            Sessions.all(context, meaningfulOnly = true)
                .filter { it.startedMs > 0L }
                .filter { Instant.ofEpochMilli(it.startedMs).atZone(zone).toLocalDate() == date }
                .forEach { session ->
                    cursor.addRow(
                        arrayOf(
                            session.startedMs,
                            session.lastMs,
                            session.title,
                            session.author,
                            session.advanced.toLong(),
                            if (session.pages) 1L else 0L,
                        ),
                    )
                }
        }
        return cursor
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.dir/vnd.$AUTHORITY.reading"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        const val AUTHORITY = "com.lightfastread.reading"
        const val PATH = "reading"

        val COLUMNS = arrayOf(
            "started_ms",
            "last_ms",
            "title",
            "author",
            "advanced",
            "pages",
        )
    }
}
