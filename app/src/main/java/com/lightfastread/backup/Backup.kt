package com.lightfastread.backup

import com.gios.light.common.sync.Contents
import com.gios.light.common.sync.FileStore
import com.gios.light.common.sync.LightSyncBackup
import com.lightfastread.reading.Sessions

/**
 * What LightBooks offers LightSync.
 *
 * The thing worth backing up here is not the books. It is **where you are in them**.
 *
 * `fastread_books` holds the shelf: every book's id, title, author, format, chapter table,
 * bookmarks, and `currentWordIndex` — the word you stopped on. None of that can be recovered
 * from anywhere else. Re-importing an EPUB gives you the book back at word zero, with the
 * bookmarks gone. It is also tiny: a few KB of JSON for a shelf of a hundred books, which is
 * what makes it cheap enough to back up on every run.
 *
 * `fastread_settings` is the reading configuration — words per minute, ramp, fonts, bionic
 * weights, gesture mode, theme. Small, and annoying to reconstruct by hand from memory, so it
 * travels as its own store: a restore should be able to bring the shelf back without
 * overwriting the settings of the phone it lands on, and vice versa.
 *
 * Deliberately **not** included:
 *
 *  - `files/books/` — the extracted text of every imported book, and by far the largest thing
 *    the app owns. Megabytes per title, and re-addable: you still have the EPUB you imported
 *    it from. A restore puts every book back on the shelf pointing at its text file; re-import
 *    the file and you land exactly where you left off, because the position came back with the
 *    shelf. Backing up a copy of a file you already have, every night, is the wrong trade on a
 *    home server.
 *  - `files/covers/` — cover JPEGs. Every one of them is derived: read out of the book file's
 *    own artwork, or fetched from Open Library by title and author, or drawn typographically
 *    at display time from the title. Nothing here is user data, and all of it comes back on
 *    its own — the shelf re-fetches a missing cover, and draws one if the fetch misses.
 *
 * Both exclusions are the same judgement: back up what cannot be regenerated, and let the
 * regenerable bulk regenerate.
 */
class Backup : LightSyncBackup() {

    override fun label() = "Books"

    override fun stores() = listOf(
        FileStore("shelf", Contents(prefs = listOf("fastread_books"))),
        FileStore("settings", Contents(prefs = listOf("fastread_settings"))),
        // The reading log. Restoring a shelf without it would put every book back and leave the
        // day it was read on blank, which is the half of the story a diary keeps.
        FileStore("reading", Contents(prefs = listOf(Sessions.FILE))),
    )
}
