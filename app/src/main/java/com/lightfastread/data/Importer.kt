package com.lightfastread.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.lightfastread.comic.ComicPages
import com.lightfastread.parser.BookParser
import java.io.File
import java.util.UUID

/**
 * One way in for every book, from anywhere.
 *
 * Both sources — the ADD button's file picker and a download from Calibre — now hand a *file* to
 * this, rather than each doing its own parse. That matters more than it did: a comic arrives as a
 * 250 MB archive, and the old path read the whole thing into a `ByteArray` before parsing. On a
 * phone with a couple of hundred megabytes of heap that is not a slow import, it is an
 * OutOfMemoryError. A file on disk can be opened as a zip and read one entry at a time.
 *
 * Everything here blocks; call it from `Dispatchers.IO`.
 */
object Importer {

    sealed interface Result {
        data class Added(val book: Book) : Result
        data class Failed(val message: String) : Result
    }

    /** Progress during the slow half of a comic import, as (page, total). */
    fun interface Progress {
        fun onPage(done: Int, total: Int)
    }

    /**
     * Import [file] as a book.
     *
     * [displayName] supplies the extension and the fallback title. [title] and [author] override
     * whatever the file says — Calibre's metadata is better than an EPUB's own, and for a CBZ there
     * is no metadata at all, only a file name.
     *
     * The source file is the caller's to delete; on the comic path its contents have been converted
     * into stored pages by the time this returns, so nothing needs it afterwards.
     */
    fun importFile(
        context: Context,
        file: File,
        displayName: String,
        title: String? = null,
        author: String? = null,
        calibreUuid: String? = null,
        progress: Progress = Progress { _, _ -> },
    ): Result {
        val repo = BookRepository.get(context)
        val id = UUID.randomUUID().toString()

        // Comics first: an image EPUB is a valid EPUB, so asking the text parser first would
        // succeed and produce a book of blank pages.
        val pages = ComicPages.scan(file, displayName)
        if (pages != null) {
            val converted = try {
                ComicPages.convert(context, id, file, pages) { done, total ->
                    progress.onPage(done, total)
                }
            } catch (e: Exception) {
                ComicPages.delete(context, id)
                return Result.Failed(e.message ?: "Could not read that comic.")
            }
            val written = converted.pages
            if (written == 0) {
                ComicPages.delete(context, id)
                return Result.Failed("No readable pages in “${title ?: displayName}”.")
            }
            val book = Book(
                id = id,
                title = title?.trim().orEmpty().ifBlank { tidyName(displayName) },
                author = author?.trim().orEmpty(),
                format = displayName.substringAfterLast('.', "cbz").uppercase(),
                // No text file at all. Kept non-empty so nothing downstream has to handle a blank
                // path; `BookRepository.textFile` on a comic simply names a file that never exists.
                textFileName = "$id.txt",
                totalWords = written,
                chapters = emptyList(),
                calibreUuid = calibreUuid,
                kind = BookKind.Comic,
            )
            repo.addComic(book)
            ComicPages.coverBytes(context, id)?.let { repo.setCover(id, it) }
            // The import has already looked at these pages closely enough to know the volume prints
            // two strips to a page, so it says so: 4-koma mode is a *fact about the book*, not a
            // preference, and making the reader discover it in a settings menu after a baffling first
            // page is worse than turning it on and leaving it easy to turn off.
            //
            // Only for the first volume of a series, deliberately. The setting lives on the series, so
            // a reader who turned it off must not have it switched back on by the next volume they
            // import — and "first volume" is the only moment at which nobody has had an opinion yet.
            if (converted.strips) {
                val key = SeriesTitle.key(SeriesTitle.parse(book.title)?.series ?: book.title)
                val firstOfItsSeries = repo.books.none { other ->
                    other.id != id &&
                        SeriesTitle.key(SeriesTitle.parse(other.title)?.series ?: other.title) == key
                }
                if (firstOfItsSeries) {
                    SettingsRepository.get(context).update { current ->
                        current.copy(comicFourKomaSeries = current.comicFourKomaSeries + key)
                    }
                }
            }
            return Result.Added(repo.getBook(id) ?: book)
        }

        // The text parsers take the whole file as a `ByteArray`, so this is the one place in the app
        // that allocates a book's worth of heap at once. A 175 MB manga volume that slipped past
        // [ComicPages.scan] did exactly that on a 128 MB heap and killed the process — an
        // OutOfMemoryError is not catchable in any useful way, so the size is checked *before* the
        // read rather than the failure being handled after it.
        if (!Storage.fitsInHeap(file.length())) {
            return Result.Failed(Storage.tooBigForHeapMessage(file.length()))
        }

        val parsed = try {
            BookParser.parseBytes(file.readBytes(), displayName)
        } catch (e: OutOfMemoryError) {
            // Belt and braces. The parsers build a string of the whole text on top of the bytes, so
            // the guard above is a floor, not a guarantee.
            return Result.Failed("“$displayName” needs more memory than this phone has for a text book.")
        } catch (e: Exception) {
            return Result.Failed(e.message ?: "Failed to import")
        }
        val words = BookRepository.countWords(parsed.text)
        if (words == 0) return Result.Failed("No readable text found in file")

        val book = Book(
            id = id,
            title = title?.trim().orEmpty().ifBlank { parsed.title }.ifBlank { "Untitled" },
            author = author?.trim().orEmpty().ifBlank { parsed.author },
            format = parsed.format,
            textFileName = "$id.txt",
            totalWords = words,
            chapters = repo.chaptersFromCharOffsets(
                parsed.text,
                parsed.chapters.map { it.title to it.charOffset },
            ),
            calibreUuid = calibreUuid,
        )
        repo.addBook(book, parsed.text)
        parsed.coverImage?.takeIf { Covers.looksLikeImage(it) }?.let { repo.setCover(id, it) }
        return Result.Added(repo.getBook(id) ?: book)
    }

    /**
     * Copy a picked document somewhere it can be opened as a zip.
     *
     * A `content://` Uri is a stream, and a zip needs to seek. Streaming it to the cache first is
     * what makes a 250 MB CBZ importable at all.
     */
    fun cacheCopy(context: Context, uri: Uri): File {
        val target = File(context.cacheDir, "import-${System.currentTimeMillis()}")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot open file" }
            target.outputStream().use { output -> input.copyTo(output, 128 * 1024) }
        }
        return target
    }

    /**
     * What the picker calls the file the user chose.
     *
     * Only the extension and the fallback title come from it, but both matter: the extension is how
     * [ComicPages.scan] knows a `.cbz` without opening it, and for a comic the file name is very
     * often the only title there is.
     */
    fun displayName(context: Context, uri: Uri): String? {
        DocumentFile.fromSingleUri(context, uri)?.name?.let { return it }
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (column >= 0) return cursor.getString(column)
                }
            }
        return uri.lastPathSegment
    }

    /** A file name turned into something worth putting on a shelf, when nothing better exists. */
    private fun tidyName(fileName: String): String =
        fileName.substringBeforeLast('.')
            .replace('_', ' ')
            .replace(Regex("""\s+"""), " ")
            .trim()
            .ifBlank { "Untitled" }
}
