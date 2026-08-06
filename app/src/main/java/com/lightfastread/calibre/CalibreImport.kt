package com.lightfastread.calibre

import android.content.Context
import com.lightfastread.data.Book
import com.lightfastread.data.BookRepository
import com.lightfastread.data.CalibreConfig
import com.lightfastread.data.Covers
import com.lightfastread.data.Importer
import java.io.File

/**
 * Getting a book off the server and onto the shelf.
 *
 * Blocking from end to end — call it from `Dispatchers.IO`. Kept out of the screen because the order
 * of these steps is the interesting part, and because two of them are allowed to fail without the
 * import failing.
 *
 * The order: download, import, cover, position. The book is on the shelf before the cover is fetched
 * and before the server is asked where it left off, so a slow or half-broken server costs a cover or
 * a resume point rather than the book.
 *
 * The download goes to a file rather than to memory, because since v1.9 a "book" may be a 250 MB
 * comic — see [CalibreClient.download] and [Importer].
 */
object CalibreImport {

    sealed interface Result {
        data class Added(
            val book: Book,
            /** Where the server said to resume, if it knew and if it was ahead of the start. */
            val resumedAtWord: Int?,
        ) : Result

        /** The uuid is already on the shelf. Not a failure — the useful answer is "open it". */
        data class AlreadyOnShelf(val existing: Book) : Result

        data class Failed(val message: String) : Result
    }

    fun download(
        context: Context,
        config: CalibreConfig,
        entry: OpdsEntry,
        progress: Importer.Progress = Importer.Progress { _, _ -> },
    ): Result {
        val repo = BookRepository.get(context)
        entry.uuid?.let { uuid ->
            repo.books.firstOrNull { it.calibreUuid == uuid }?.let { return Result.AlreadyOnShelf(it) }
        }

        val (link, ext) = entry.bestDownload()
            ?: return Result.Failed("“${entry.title}” is not in a format this app can read.")

        val client = CalibreClient(config)
        val staged = File(context.cacheDir, "calibre-${System.currentTimeMillis()}.$ext")
        try {
            client.download(link.href, staged)
        } catch (e: Exception) {
            staged.delete()
            return Result.Failed(e.message ?: "Download failed.")
        }

        val imported = try {
            Importer.importFile(
                context = context,
                file = staged,
                displayName = "${entry.title.ifBlank { "book" }}.$ext",
                // Calibre's metadata over the file's own, deliberately: a Calibre library has been
                // curated, and the title inside an EPUB is frequently a filename or a marketing
                // subtitle. A CBZ carries no metadata at all, so for comics this is the only source.
                title = entry.title,
                author = entry.author,
                calibreUuid = entry.uuid,
                progress = progress,
            )
        } finally {
            staged.delete()
        }

        val book = when (imported) {
            is Importer.Result.Failed -> return Result.Failed(imported.message)
            is Importer.Result.Added -> imported.book
        }

        // Calibre's cover before the file's own: it is the same image in the common case, and where
        // it differs it is because somebody fixed it in Calibre. Either way this is a cover in hand,
        // so no Open Library lookup is ever needed for a book that came from the library.
        entry.coverHref?.let { href ->
            runCatching { client.bytes(href, MAX_COVER_BYTES) }.getOrNull()
                ?.takeIf { Covers.looksLikeImage(it) }
                ?.let { repo.setCover(book.id, it) }
        }

        val resumed = runCatching { ProgressSync.adopt(context, book) }.getOrNull()
        return Result.Added(repo.getBook(book.id) ?: book, resumed)
    }

    private const val MAX_COVER_BYTES = 8L * 1024 * 1024
}
