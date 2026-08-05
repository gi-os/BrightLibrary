package com.lightfastread.calibre

import android.content.Context
import com.lightfastread.data.Book
import com.lightfastread.data.BookRepository
import com.lightfastread.data.CalibreConfig
import com.lightfastread.data.Covers
import com.lightfastread.parser.BookParser
import java.util.UUID

/**
 * Getting a book off the server and onto the shelf.
 *
 * Blocking from end to end — call it from `Dispatchers.IO`. Kept out of the screen because the order
 * of these steps is the interesting part, and because two of them are allowed to fail without the
 * import failing.
 *
 * The order: download, parse, add, cover, position. The book is on the shelf before the cover is
 * fetched and before the server is asked where it left off, so a slow or half-broken server costs a
 * cover or a resume point rather than the book.
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

    fun download(context: Context, config: CalibreConfig, entry: OpdsEntry): Result {
        val repo = BookRepository.get(context)
        entry.uuid?.let { uuid ->
            repo.books.firstOrNull { it.calibreUuid == uuid }?.let { return Result.AlreadyOnShelf(it) }
        }

        val (link, ext) = entry.bestDownload()
            ?: return Result.Failed("“${entry.title}” is not in a format this app can read.")

        val client = CalibreClient(config)
        val bytes = try {
            client.bytes(link.href)
        } catch (e: Exception) {
            return Result.Failed(e.message ?: "Download failed.")
        }

        val parsed = try {
            BookParser.parseBytes(bytes, "${entry.title.ifBlank { "book" }}.$ext")
        } catch (e: Exception) {
            return Result.Failed(e.message ?: "That file would not parse.")
        }

        val words = BookRepository.countWords(parsed.text)
        if (words == 0) return Result.Failed("No readable text in “${entry.title}”.")

        val id = UUID.randomUUID().toString()
        val book = Book(
            id = id,
            // Calibre's metadata over the file's own, deliberately: a Calibre library has been
            // curated, and the title inside an EPUB is frequently a filename or a marketing
            // subtitle. This is also what makes the cover search work if it comes to that.
            title = entry.title.ifBlank { parsed.title }.ifBlank { "Untitled" },
            author = entry.author.ifBlank { parsed.author },
            format = parsed.format,
            textFileName = "$id.txt",
            totalWords = words,
            chapters = repo.chaptersFromCharOffsets(
                parsed.text,
                parsed.chapters.map { it.title to it.charOffset },
            ),
            calibreUuid = entry.uuid,
        )
        repo.addBook(book, parsed.text)

        // Calibre's cover before the file's own: it is the same image in the common case, and where
        // it differs it is because somebody fixed it in Calibre. Either way this is a cover in hand,
        // so no Open Library lookup is ever needed for a book that came from the library.
        val cover = entry.coverHref?.let { href ->
            runCatching { client.bytes(href, MAX_COVER_BYTES) }.getOrNull()
        }?.takeIf { Covers.looksLikeImage(it) }
            ?: parsed.coverImage?.takeIf { Covers.looksLikeImage(it) }
        if (cover != null) repo.setCover(id, cover)

        val resumed = runCatching { ProgressSync.adopt(context, book) }.getOrNull()
        return Result.Added(repo.getBook(id) ?: book, resumed)
    }

    private const val MAX_COVER_BYTES = 8L * 1024 * 1024
}
