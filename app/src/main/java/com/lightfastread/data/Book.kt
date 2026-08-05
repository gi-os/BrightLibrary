package com.lightfastread.data

import kotlinx.serialization.Serializable

@Serializable
data class Chapter(
    val title: String,
    val startWordIndex: Int,
)

@Serializable
data class Book(
    val id: String,
    val title: String,
    val author: String = "",
    val format: String,
    val textFileName: String,
    val totalWords: Int,
    val currentWordIndex: Int = 0,
    val addedAtMs: Long = System.currentTimeMillis(),
    val chapters: List<Chapter> = emptyList(),
    val bookmarks: List<Int> = emptyList(),
    /**
     * File name of the stored cover inside `filesDir/covers`, or null when the book has none and
     * the shelf should draw a typographic cover for it. See [Covers].
     */
    val coverFileName: String? = null,
    /**
     * When the cover was last written. The file name is derived from the book id and so never
     * changes, which means a replacement cover would leave the [Book] equal to its old self —
     * no recomposition, and a decoded bitmap keyed on the name would stay stale. This is what
     * makes a re-fetch visible.
     */
    val coverUpdatedAtMs: Long = 0L,
    /**
     * When a cover was last *looked for*, successfully or not.
     *
     * Without this a lookup that failed — no signal at import, a title the catalogue doesn't know —
     * never happened again, because the only thing that triggered one was the import itself. The
     * shelf now sweeps for books that have no cover and haven't been asked about recently, and this
     * is what keeps that sweep from asking Open Library the same unanswerable question every time
     * the shelf is opened.
     */
    val coverSearchedAtMs: Long = 0L,
    /**
     * The Calibre `uuid` this book came from, when it was downloaded from a Calibre server.
     *
     * The uuid rather than the numeric id: it is what calibre-web's Kobo sync endpoint is keyed on,
     * it survives the library being moved or re-imported, and it is what an OPDS entry already
     * carries in its `<id>` as `urn:uuid:…`. Null for a book imported from the phone's storage,
     * which is also what marks a book as having nothing to sync.
     */
    val calibreUuid: String? = null,
    /**
     * The percentage the server was last told, or -1 if it has never been told anything.
     *
     * This is what makes the sync eventually consistent instead of best-effort. Reading happens on a
     * phone that is often off the network, so a push that fails has to be retryable — and the only
     * way to know a retry is needed is to record what the far end already knows. -1 rather than 0
     * because "never pushed" and "pushed a position at the very start" are different states.
     */
    val calibreSyncedPercent: Int = -1,
)
