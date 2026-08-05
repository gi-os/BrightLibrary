package com.lightfastread.parser

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile

data class RawChapter(
    val title: String,
    val charOffset: Int,
)

data class ParsedBook(
    val title: String,
    val author: String,
    val format: String,
    val text: String,
    val chapters: List<RawChapter> = emptyList(),
    /**
     * The book's own cover art, exactly as it was stored in the file. Null is a common case —
     * plenty of EPUBs declare no cover — and the importer falls back to an Open Library lookup
     * and then to a typographic cover, so nothing downstream treats this as required.
     *
     * A `ByteArray` in a data class means `equals`/`hashCode` compare by identity. That is fine
     * here (a [ParsedBook] is consumed once, at import, and never compared) but it is why this
     * field must not be relied on for equality.
     */
    val coverImage: ByteArray? = null,
)

object BookParser {
    fun parse(context: Context, uri: Uri): ParsedBook {
        val name = displayName(context, uri) ?: "Unknown"
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("Cannot open file")
        return parseBytes(bytes, name)
    }

    /**
     * Parse a book already in memory.
     *
     * Split out of [parse] for the Calibre download, which has the bytes and a format but never had
     * a `Uri` — a download that had to be written to a temporary file and handed back through
     * `ContentResolver` just to be read again would be two copies of a 40 MB EPUB for nothing.
     *
     * [name] is only used for its extension and as the fallback title, so a synthesised
     * `"$title.$ext"` is a perfectly good thing to pass.
     */
    fun parseBytes(bytes: ByteArray, name: String): ParsedBook {
        val ext = name.substringAfterLast('.', "").lowercase()
        val fallbackTitle = name.substringBeforeLast('.')
        return when (ext) {
            "epub" -> EpubParser.parseBytes(bytes, fallbackTitle = fallbackTitle)
            "mobi", "azw", "azw3" -> MobiParser.parseBytes(bytes, fallbackTitle = fallbackTitle)
            else -> throw IllegalArgumentException("Unsupported format: .$ext (use .epub or .mobi)")
        }
    }

    private fun displayName(context: Context, uri: Uri): String? {
        val df = DocumentFile.fromSingleUri(context, uri)
        df?.name?.let { return it }
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return it.getString(idx)
            }
        }
        return uri.lastPathSegment
    }
}
