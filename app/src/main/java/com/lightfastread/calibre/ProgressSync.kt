package com.lightfastread.calibre

import android.content.Context
import com.lightfastread.data.Book
import com.lightfastread.data.BookRepository
import com.lightfastread.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * When the server gets told where you are.
 *
 * Reading fires a progress update on **every word** — a couple of hundred a minute at a normal RSVP
 * speed — so this cannot be a request per update. Instead the position is written locally as it
 * always was, and this pushes whatever has drifted since the last successful push: on a throttle
 * while reading, when the app goes to the background, and when the LIBRARY screen opens.
 *
 * The "what has drifted" question is answered by [Book.calibreSyncedPercent] rather than by a queue,
 * which is what makes the whole thing survive being offline. A push that fails leaves the book's
 * recorded percentage alone, so the next flush — an hour later, on the train, at home — picks it up
 * without anything having had to remember that it was owed.
 *
 * Nothing here reports failure to the user. A phone that cannot reach the LAN is the normal case for
 * most of the day, and an error banner every time would be noise about a thing that fixes itself.
 */
object ProgressSync {

    /**
     * Deliberately not tied to a screen's lifecycle. A flush is started by leaving the app, and a
     * scope that died with the composition would cancel exactly the push that matters most.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One flush at a time, or a fast reader would open a socket per word. */
    private val inFlight = AtomicBoolean(false)

    @Volatile private var lastFlushAtMs = 0L

    /**
     * Called from the reader as the position moves. Cheap and usually does nothing: at most one
     * push per [THROTTLE_MS], and none at all when Calibre is not set up.
     */
    fun onProgress(context: Context) {
        if (System.currentTimeMillis() - lastFlushAtMs < THROTTLE_MS) return
        flush(context)
    }

    /** Push everything outstanding. Safe to call often and from anywhere; returns immediately. */
    fun flush(context: Context) {
        val appContext = context.applicationContext
        val config = SettingsRepository.get(appContext).state.value.calibre
        if (!ReadingState.configured(config)) return
        if (!inFlight.compareAndSet(false, true)) return
        lastFlushAtMs = System.currentTimeMillis()

        scope.launch {
            try {
                val repo = BookRepository.get(appContext)
                // A copy, because this iterates off the main thread while the reader may still be
                // writing positions into the live list.
                val pending = repo.books.toList().filter { it.calibreUuid != null && it.needsPush() }
                for (book in pending) {
                    val uuid = book.calibreUuid ?: continue
                    val percent = ReadingState.percentOf(book.currentWordIndex, book.totalWords)
                    val stored = runCatching {
                        ReadingState.push(config, uuid, percent, book.currentWordIndex)
                    }.getOrElse {
                        CalibreClient.logQuietly("progress push failed for ${book.title}", it)
                        false
                    }
                    if (stored) repo.markCalibreSynced(book.id, percent)
                }
            } finally {
                inFlight.set(false)
            }
        }
    }

    /**
     * Take the server's position for a book, if it is ahead of ours.
     *
     * Used right after a download, where the point is to open the book where another device left it.
     * "Ahead" rather than "different" on purpose — a freshly downloaded book is at word zero, and a
     * server that has never heard of it must not be able to reset a position by answering nothing.
     */
    fun adopt(context: Context, book: Book): Int? {
        val appContext = context.applicationContext
        val config = SettingsRepository.get(appContext).state.value.calibre
        if (!ReadingState.configured(config)) return null
        val uuid = book.calibreUuid ?: return null
        val remote = ReadingState.pull(config, uuid) ?: return null
        val wordIndex = ReadingState.wordIndexOf(remote, book.totalWords)
        if (wordIndex <= book.currentWordIndex) return null
        val repo = BookRepository.get(appContext)
        repo.updateProgress(book.id, wordIndex)
        repo.markCalibreSynced(book.id, remote.percent)
        return wordIndex
    }

    private fun Book.needsPush(): Boolean =
        ReadingState.percentOf(currentWordIndex, totalWords) != calibreSyncedPercent

    /**
     * Long enough that a reader mid-chapter is not generating traffic, short enough that closing the
     * app is not the only thing that ever syncs. The background flush covers the rest.
     */
    private const val THROTTLE_MS = 60_000L
}
