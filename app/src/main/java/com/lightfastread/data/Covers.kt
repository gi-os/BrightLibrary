package com.lightfastread.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Cover art on disk.
 *
 * Covers come from three places, in order: the book file's own artwork, an Open Library lookup
 * by title and author, and — if both miss — nothing at all, in which case the shelf draws a
 * typographic cover instead (see `ui/home/CoverArt.kt`). The generated one is not stored: it is
 * cheaper to lay out two lines of text than to keep a bitmap for them, and it re-renders in
 * whatever the theme is.
 *
 * Stored as JPEG at [MAX_EDGE_PX] on the long edge. A shelf holds a dozen covers at once on a
 * 411dp screen, so the full-size art a publisher ships — often 1600px — is a megabyte of heap
 * per book for no visible gain.
 */
object Covers {

    private const val TAG = "Covers"

    /** Comfortably over the ~120dp a shelf cell gets at 420dpi, with room for a 2x panel. */
    private const val MAX_EDGE_PX = 600
    private const val JPEG_QUALITY = 88

    private const val SEARCH_URL = "https://openlibrary.org/search.json"
    private const val COVER_URL = "https://covers.openlibrary.org/b/id"
    private const val TIMEOUT_MS = 12_000

    fun dir(context: Context): File = File(context.filesDir, "covers").apply { mkdirs() }

    fun fileFor(context: Context, book: Book): File? =
        book.coverFileName?.let { File(dir(context), it) }?.takeIf { it.exists() }

    fun fileName(bookId: String): String = "$bookId.jpg"

    /**
     * Decode, downscale and store [bytes] as [bookId]'s cover.
     *
     * Returns the file name to put on the [Book], or null if the bytes were not an image this
     * device can decode — which is the normal outcome for a malformed EPUB, not an error worth
     * failing an import over.
     */
    fun store(context: Context, bookId: String, bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        val bitmap = decodeScaled(bytes) ?: return null
        val target = File(dir(context), fileName(bookId))
        return try {
            target.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            bitmap.recycle()
            target.name
        } catch (e: Exception) {
            Log.w(TAG, "could not write cover for $bookId: ${e.message}")
            target.delete()
            null
        }
    }

    fun delete(context: Context, book: Book) {
        book.coverFileName?.let { File(dir(context), it).delete() }
    }

    /** Load a stored cover for drawing. Null if it is missing or no longer decodable. */
    fun load(context: Context, book: Book): ImageBitmap? {
        val file = fileFor(context, book) ?: return null
        return runCatching {
            BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
        }.getOrNull()
    }

    /**
     * Ask Open Library for cover art.
     *
     * Two requests: a search for the work, then the cover by its numeric id. `fields` keeps the
     * first response to a few hundred bytes rather than the whole record, which matters on a
     * phone that is often on a slow connection. Any failure returns null — no cover is a normal
     * state, and an import must not fail because a lookup did.
     */
    fun fetchFromOpenLibrary(title: String, author: String): ByteArray? {
        if (title.isBlank()) return null
        return try {
            val query = buildString {
                append("title=").append(enc(title))
                if (author.isNotBlank()) append("&author=").append(enc(author))
            }
            val body = get("$SEARCH_URL?$query&fields=cover_i&limit=1")?.toString(Charsets.UTF_8)
                ?: return null
            // Deliberately not a JSON parse: the response is `{"...":[{"cover_i":123}]}` and one
            // regex is cheaper than pulling the serializer in for a single integer.
            val id = Regex("\"cover_i\"\\s*:\\s*(\\d+)").find(body)?.groupValues?.get(1)
                ?: return null
            get("$COVER_URL/$id-L.jpg")?.takeIf { looksLikeImage(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Open Library lookup failed for \"$title\": ${e.message}")
            null
        }
    }

    /** Cheap magic-number check, so an HTML error page never gets stored as a cover. */
    fun looksLikeImage(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        val jpeg = bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
        val png = bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte()
        val gif = bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte()
        val webp = bytes.size > 12 && bytes[0] == 'R'.code.toByte() && bytes[8] == 'W'.code.toByte()
        return jpeg || png || gif || webp
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun get(url: String): ByteArray? {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = true
            // Open Library asks for an identifying agent and rate-limits anonymous traffic.
            connection.setRequestProperty("User-Agent", "LightBooks/1.0 (github.com/gi-os/LightBooks)")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            connection.inputStream.use { it.readBytes() }
        } catch (e: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Decode at a power-of-two sample size chosen from the real bounds, then finish the scale
     * exactly. Sampling alone leaves anything from 1x to 2x the target size; a shelf of covers
     * that are each a slightly different scale reads as sloppy.
     */
    private fun decodeScaled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longEdge / (sample * 2) >= MAX_EDGE_PX) sample *= 2

        val decoded = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null

        val edge = maxOf(decoded.width, decoded.height)
        if (edge <= MAX_EDGE_PX) return decoded
        val ratio = MAX_EDGE_PX.toFloat() / edge
        val scaled = Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * ratio).toInt().coerceAtLeast(1),
            (decoded.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled != decoded) decoded.recycle()
        return scaled
    }
}
