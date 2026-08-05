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
        val ext = name.substringAfterLast('.', "").lowercase()
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("Cannot open file")

        return when (ext) {
            "epub" -> EpubParser.parseBytes(bytes, fallbackTitle = name.substringBeforeLast('.'))
            "mobi", "azw", "azw3" -> MobiParser.parseBytes(bytes, fallbackTitle = name.substringBeforeLast('.'))
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
