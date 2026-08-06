package com.lightfastread.comic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.util.Log
import java.io.File
import java.util.zip.ZipFile

/**
 * A comic on disk: one greyscale JPEG per page, converted once at import.
 *
 * The conversion is the whole design. A volume arrives as 100–250 MB of colour scans at print
 * resolution, and the phone is a 1080×1240 greyscale panel — so keeping the archive and decoding
 * from it on every page turn would mean carrying twenty times the bytes needed and paying a JPEG
 * decode of a 3000px page for each turn. Converting up front costs a minute or two once, and after
 * that a page is a small file the size of the screen: paging is instant and a volume takes about a
 * tenth of the space. The source archive is deleted when the conversion finishes.
 *
 * Greyscale is done here rather than at draw time for the same reason. LightOS renders everything
 * through a display-wide daltonizer anyway, so colour data in these files would never reach an eye —
 * it would only cost storage and decode time.
 *
 * Pages are named `%04d.jpg`, which both orders them and means the reader never has to hold a list.
 */
object ComicPages {

    private const val TAG = "ComicPages"

    /**
     * Long edge of a stored page.
     *
     * The panel is 1080×1240, and a page is taller than it is wide, so its long edge is the screen's
     * height. Storing more than the panel can show is storage spent on pixels the eye cannot reach —
     * and the reader's zoom works on the decoded bitmap, where the extra detail would have to be
     * re-read from a bigger file every time anyway.
     */
    private const val MAX_EDGE_PX = 1240

    /**
     * Manga is line art and screentone, and JPEG artefacts around hard black edges are visible on a
     * matte panel in a way they are not on a phone photo. 88 is a compromise found by looking.
     */
    private const val JPEG_QUALITY = 88

    /** How many XHTML documents to read when deciding whether an EPUB is a book of pictures. */
    private const val TEXT_SAMPLE_DOCS = 40

    fun dir(context: Context, bookId: String): File =
        File(File(context.filesDir, "comics"), bookId).apply { mkdirs() }

    fun pageFile(context: Context, bookId: String, index: Int): File =
        File(dir(context, bookId), pageName(index))

    fun pageName(index: Int): String = String.format("%04d.jpg", index + 1)

    fun count(context: Context, bookId: String): Int =
        dir(context, bookId).listFiles { f -> f.name.endsWith(".jpg") }?.size ?: 0

    fun delete(context: Context, bookId: String) {
        dir(context, bookId).deleteRecursively()
    }

    /**
     * The pages inside [file], or null if this is not a comic.
     *
     * Extension decides for CBZ — a `.cbz` is a zip of images by definition, and there is nothing
     * else it could be. An EPUB has to be looked at, because the same extension covers a novel and a
     * scanned volume; see [ComicScan.looksLikeComic].
     */
    fun scan(file: File, fileName: String): List<String>? {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return try {
            ZipFile(file).use { zip ->
                val entries = zip.entries().asSequence().filter { !it.isDirectory }.toList()
                val names = entries.map { it.name }
                // Sizes come from the zip's central directory, so this costs no reads.
                val imageBytes = entries.filter { ComicScan.isImage(it.name) }.sumOf { it.size.coerceAtLeast(0) }
                val totalBytes = entries.sumOf { it.size.coerceAtLeast(0) }
                fun read(name: String): ByteArray? =
                    zip.getEntry(name)?.let { entry -> zip.getInputStream(entry).use { it.readBytes() } }

                when (ext) {
                    "cbz", "cbr", "zip" -> ComicScan.cbzPages(names).takeIf { it.isNotEmpty() }
                    "epub" -> {
                        val images = names.count { ComicScan.isImage(it) }
                        val docs = names.filter {
                            it.substringAfterLast('.', "").lowercase() in setOf("xhtml", "html", "htm")
                        }
                        var textChars = 0
                        for (doc in docs.take(TEXT_SAMPLE_DOCS)) {
                            val body = read(doc)?.toString(Charsets.UTF_8) ?: continue
                            textChars += ComicScan.visibleTextLength(body)
                        }
                        // Scale the sample up to the whole book, or a 300-page volume looks like it
                        // has less text than a 40-page one purely because fewer of its pages were read.
                        val sampled = docs.size.coerceAtLeast(1)
                        val estimated = if (docs.size > TEXT_SAMPLE_DOCS) {
                            textChars.toLong() * sampled / TEXT_SAMPLE_DOCS
                        } else {
                            textChars.toLong()
                        }
                        if (ComicScan.looksLikeComic(
                                imageCount = images,
                                textChars = estimated.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                                imageBytes = imageBytes,
                                totalBytes = totalBytes,
                            )
                        ) {
                            ComicScan.epubPages(names, ::read).takeIf { it.isNotEmpty() }
                        } else {
                            null
                        }
                    }
                    else -> null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not scan ${file.name}: ${e.message}")
            null
        }
    }

    /**
     * Convert [entries] out of [file] into stored pages. Returns how many were written.
     *
     * A page that will not decode is skipped rather than fatal: a single corrupt scan in the middle
     * of a volume should cost that page, not the book. [onProgress] is called with (done, total) so
     * the import screen can say where it is — this is a minute or more of work and silence would
     * read as a hang.
     */
    fun convert(
        context: Context,
        bookId: String,
        file: File,
        entries: List<String>,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Int {
        val target = dir(context, bookId)
        var written = 0
        ZipFile(file).use { zip ->
            for ((index, name) in entries.withIndex()) {
                onProgress(index, entries.size)
                val entry = zip.getEntry(name) ?: continue
                val bytes = try {
                    zip.getInputStream(entry).use { it.readBytes() }
                } catch (e: Exception) {
                    Log.w(TAG, "page $name unreadable: ${e.message}")
                    continue
                }
                val grey = toGreyscale(bytes) ?: continue
                val out = File(target, pageName(written))
                try {
                    out.outputStream().use { stream ->
                        grey.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
                    }
                    written++
                } catch (e: Exception) {
                    Log.w(TAG, "could not write page ${out.name}: ${e.message}")
                    out.delete()
                } finally {
                    grey.recycle()
                }
            }
        }
        onProgress(entries.size, entries.size)
        return written
    }

    /**
     * Decode, downscale and desaturate one page.
     *
     * Sampled decode first, then an exact scale, because sampling alone lands anywhere between 1x
     * and 2x the target — and a volume whose pages are each a slightly different size makes the
     * reader jump on every turn.
     */
    private fun toGreyscale(bytes: ByteArray): Bitmap? {
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
        val scaled = if (edge <= MAX_EDGE_PX) {
            decoded
        } else {
            val ratio = MAX_EDGE_PX.toFloat() / edge
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * ratio).toInt().coerceAtLeast(1),
                (decoded.height * ratio).toInt().coerceAtLeast(1),
                true,
            ).also { if (it != decoded) decoded.recycle() }
        }

        // Straight desaturation, no curve. The scans are already black and white artwork; lifting
        // contrast on top of that crushes screentone into either solid grey or solid white, which is
        // exactly the detail a matte panel has the least of to spare.
        val grey = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        }
        Canvas(grey).drawBitmap(scaled, 0f, 0f, paint)
        scaled.recycle()
        return grey
    }

    /** The first page, as bytes, for use as the book's cover. */
    fun coverBytes(context: Context, bookId: String): ByteArray? =
        pageFile(context, bookId, 0).takeIf { it.exists() }?.readBytes()
}
