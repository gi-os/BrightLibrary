package com.lightfastread.comic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.util.Log
import com.lightfastread.data.Storage
import java.io.File
import java.util.zip.ZipFile

/**
 * A comic on disk: one greyscale JPEG per page, converted once at import.
 *
 * The conversion is the whole design. A volume arrives as 100–250 MB of colour scans at print
 * resolution, and the phone is a 1080×1240 greyscale panel — so keeping the archive and decoding
 * from it on every page turn would mean carrying twenty times the bytes needed and paying a JPEG
 * decode of a 3000px page for each turn. Converting up front costs a minute or two once, and after
 * that a page is a small file the size of the screen: paging is instant and a volume takes a
 * fraction of the space. The source archive is deleted when the conversion finishes.
 *
 * **A page is stored at the width the reader will show it at**, which is the panel's 1080 — not at a
 * long-edge cap, which is what the first version did and which left every portrait page 868 wide to
 * be stretched by a quarter at draw time. A volume printed two strips to a page is stored at twice
 * that, because there the *column* is what gets fitted to the screen; [looksLikeStrips] decides which
 * kind of volume this is by looking at the pages themselves.
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
     * Width of a stored page: the panel's own width.
     *
     * **Width, not long edge.** The reader fits a page to the screen's *width* and scrolls down it,
     * so the width is the dimension that decides how much detail reaches the eye and the height is
     * whatever the paper's shape makes it. The old cap — 1240 on the long edge — put a portrait page
     * at 868 wide, which the reader then stretched to 1080 to fit: a 1.24x upscale on line art, and
     * every stored page slightly softer than the screen could have shown.
     */
    private const val TARGET_WIDTH_PX = 1080

    /**
     * Width of a stored page in a volume that prints **two strips to a page**.
     *
     * A yonkoma page is read one column at a time, so the column — half the page — is what gets
     * fitted to the screen. At 1080 across the whole page a column is 540 and arrives on screen at
     * 2x, which is where softness stops being theoretical and starts looking like a fax. Twice the
     * width puts the column at 1080: pixel for pixel with the panel.
     *
     * It is not the default because it is roughly four times the storage, and a volume read whole
     * never sees the difference.
     */
    private const val TARGET_WIDTH_STRIPS_PX = 2160

    /**
     * Ceiling on a page's pixel count, whatever its shape.
     *
     * The width target alone says nothing about height, and comics contain pages that are not pages:
     * a double spread is twice as wide, a poster or a stitched-together strip can be five times as
     * tall. Without a ceiling one of those is a 100 MB bitmap that no heap will decode. Sized so an
     * ordinary page passes untouched and only the outliers are pulled back.
     */
    private const val MAX_PIXELS = 1080 * 2600

    private const val MAX_PIXELS_STRIPS = 2160 * 3600

    /**
     * How wide a copy of the page to measure the crop and the gutter on.
     *
     * Both measurements read every sampled pixel through an `IntArray`, which at 2160x3086 is a
     * 27 MB allocation to answer a question about where the white is. Neither answer changes at
     * a fifth of the size — the crop threshold is a *fraction* of ink per line and the gutter's are
     * fractions of the width — so they are taken small and scaled up.
     */
    private const val MEASURE_WIDTH_PX = 640

    /**
     * How many pages to look at when deciding whether a volume is printed two strips to a page, and
     * how many of them need a gutter down the middle before it counts.
     *
     * Not all of them: this happens before the conversion, and decoding 180 pages twice to answer one
     * question would double the slowest part of an import. Ten spread through the book is enough, and
     * the bar is deliberately high — a volume of ordinary manga with a handful of gutter-ish pages
     * must not be stored at four times the size, and must not have 4-koma mode switched on for it.
     */
    private const val STRIP_SAMPLE_PAGES = 10
    private const val STRIP_SAMPLE_SHARE = 0.7

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

    /** What one conversion produced. */
    data class Converted(
        val pages: Int,
        /** Whether this volume prints two strips to a page — see [looksLikeStrips]. */
        val strips: Boolean,
    )

    /**
     * Convert [entries] out of [file] into stored pages.
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
    ): Converted {
        val target = dir(context, bookId)
        var written = 0
        var strips = false
        ZipFile(file).use { zip ->
            fun read(name: String): ByteArray? = runCatching {
                zip.getEntry(name)?.let { entry -> zip.getInputStream(entry).use { it.readBytes() } }
            }.getOrNull()

            // Asked before anything is written, because it decides what everything is written at.
            onProgress(0, entries.size)
            strips = looksLikeStrips(entries, ::read) && roomForStrips(context, entries.size)
            Log.i(TAG, "$bookId: ${entries.size} pages, strips=$strips")
            val targetWidth = if (strips) TARGET_WIDTH_STRIPS_PX else TARGET_WIDTH_PX
            val maxPixels = if (strips) MAX_PIXELS_STRIPS else MAX_PIXELS

            for ((index, name) in entries.withIndex()) {
                onProgress(index, entries.size)
                val bytes = read(name) ?: continue
                val grey = toGreyscale(bytes, targetWidth, maxPixels) ?: continue
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
        return Converted(written, strips)
    }

    /**
     * Whether this volume prints **two strips to a page** — a yonkoma, read one column at a time.
     *
     * Asked by looking, not by asking the user or trusting a tag: a sample of pages spread through
     * the book is decoded small and each is asked whether it has a band of blank paper down its
     * middle ([PageCrop.centreGutter], the same test the reader uses to decide whether a page can be
     * split at all). A yonkoma has one on nearly every story page; ordinary manga has one on almost
     * none, because its panels cross the centre of the page constantly.
     *
     * The front and back of the book are skipped. Covers, chapter breaks, the credits page and an
     * afterword are the pages *least* likely to have a gutter in a book that otherwise has one, and
     * including them only waters the sample down.
     *
     * Being wrong is not expensive in either direction: a false positive stores a volume four times
     * larger than it needed and offers a split the reader will refuse page by page, and a false
     * negative stores it at the ordinary width, which is what every volume did until now.
     */
    private fun looksLikeStrips(entries: List<String>, read: (String) -> ByteArray?): Boolean {
        if (entries.size < 8) return false
        val first = (entries.size * 0.15).toInt()
        val last = (entries.size * 0.9).toInt().coerceAtLeast(first + 1)
        val stride = ((last - first) / STRIP_SAMPLE_PAGES).coerceAtLeast(1)
        var looked = 0
        var gutters = 0
        var index = first
        while (index < last && looked < STRIP_SAMPLE_PAGES) {
            val bytes = read(entries[index])
            val small = bytes?.let { decodeSampled(it, MEASURE_WIDTH_PX) }
            if (small != null) {
                looked++
                if (gutterOf(small) != null) gutters++
                small.recycle()
            }
            index += stride
        }
        // Four is the fewest worth a verdict; below that one splash page swings it.
        return looked >= 4 && gutters >= kotlin.math.ceil(looked * STRIP_SAMPLE_SHARE).toInt()
    }

    /**
     * Whether there is room to store this volume at strip resolution.
     *
     * Four times the pixels is roughly four times the file, and a shelf of yonkoma at 150 MB each
     * fills a phone. A volume that will not fit is stored at the ordinary width instead of failing:
     * a slightly soft book you can read beats a full disk.
     */
    private fun roomForStrips(context: Context, pages: Int): Boolean {
        val estimate = pages.toLong() * ESTIMATED_STRIP_PAGE_BYTES
        val room = estimate < Storage.downloadLimit(context)
        if (!room) Log.i(TAG, "not enough room for strip resolution (${Storage.humanBytes(estimate)})")
        return room
    }

    /** Rough size of one page stored at [TARGET_WIDTH_STRIPS_PX], from measuring converted volumes. */
    private const val ESTIMATED_STRIP_PAGE_BYTES = 1_200_000L

    /** The crop box and the gutter of a stored page, both in [toWidth] x [toHeight] coordinates. */
    data class Measured(val bounds: PageCrop.Bounds, val gutter: Int?)

    /**
     * Measure a stored page's crop box and gutter, on a small decode of it.
     *
     * Deliberately a *second*, smaller decode rather than reading the pixels of the bitmap being
     * drawn: `getPixels` on a 2160x3086 page is a 27 MB `IntArray` on top of the 27 MB bitmap, and
     * the answer is identical at a fifth of the size because every threshold in [PageCrop] is a
     * fraction rather than a pixel count.
     */
    fun measure(file: File, toWidth: Int, toHeight: Int): Measured {
        val whole = PageCrop.Bounds(0, 0, toWidth, toHeight)
        val small = decodeSampledFile(file, MEASURE_WIDTH_PX) ?: return Measured(whole, null)
        return try {
            val pixels = IntArray(small.width * small.height)
            small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
            val bounds = PageCrop.contentBounds(pixels, small.width, small.height)
            val gutter = PageCrop.centreGutter(pixels, small.width, small.height, bounds)
            Measured(
                bounds = PageCrop.scale(bounds, small.width, small.height, toWidth, toHeight),
                gutter = gutter?.let { PageCrop.scaleX(it, small.width, toWidth) },
            )
        } catch (e: OutOfMemoryError) {
            Measured(whole, null)
        } finally {
            small.recycle()
        }
    }

    /**
     * Decode a stored page for the screen, at a size the heap can hold.
     *
     * A strip-resolution page is 2160x3086, which is 27 MB decoded — fine on a 256 MB heap and fatal
     * on a small one. Rather than store less for everybody, the *reader* backs off: an eighth of the
     * heap per page, halving the decode until it fits. Everything measured afterwards is measured
     * against whatever came back, so a page decoded at half size is simply a smaller page and no
     * other code has to know.
     */
    fun decodeForDisplay(file: File): Bitmap? {
        val budget = Runtime.getRuntime().maxMemory() / DISPLAY_HEAP_SHARE
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (sample < MAX_SAMPLE &&
            (bounds.outWidth / sample).toLong() * (bounds.outHeight / sample) * 4 > budget
        ) {
            sample *= 2
        }
        return try {
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    /** How much of the heap one decoded page may take. */
    private const val DISPLAY_HEAP_SHARE = 8

    private const val MAX_SAMPLE = 16

    /**
     * Decode, downscale and desaturate one page.
     *
     * Sampled decode first, then an exact scale, because sampling alone lands anywhere between 1x
     * and 2x the target — and a volume whose pages are each a slightly different size makes the
     * reader jump on every turn.
     *
     * Never *up*: a page that arrives smaller than [targetWidth] is stored as it is. Upscaling at
     * import would spend storage inventing detail, and the reader can stretch it at draw time for
     * nothing.
     */
    private fun toGreyscale(bytes: ByteArray, targetWidth: Int, maxPixels: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val sourceW = bounds.outWidth
        val sourceH = bounds.outHeight
        if (sourceW <= 0 || sourceH <= 0) return null

        val (finalW, finalH) = PageSize.fit(sourceW, sourceH, targetWidth, maxPixels)
        val sample = PageSize.sampleFor(sourceW, finalW, MAX_SAMPLE)

        val decoded = try {
            BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
        } catch (e: OutOfMemoryError) {
            null
        } ?: return null

        val scaled = if (decoded.width <= finalW) {
            decoded
        } else {
            Bitmap.createScaledBitmap(decoded, finalW, finalH, true)
                .also { if (it != decoded) decoded.recycle() }
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

    /** A sampled decode of image [bytes], roughly [targetWidth] wide. Cheap, and never exact. */
    private fun decodeSampled(bytes: ByteArray, targetWidth: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sample = PageSize.sampleFor(bounds.outWidth, targetWidth, MAX_SAMPLE)
        return try {
            BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    /** The same, from a file. */
    private fun decodeSampledFile(file: File, targetWidth: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sample = PageSize.sampleFor(bounds.outWidth, targetWidth, MAX_SAMPLE)
        return try {
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    /** Whether one already-decoded page has a gutter down its middle. */
    private fun gutterOf(bitmap: Bitmap): Int? = try {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val bounds = PageCrop.contentBounds(pixels, bitmap.width, bitmap.height)
        PageCrop.centreGutter(pixels, bitmap.width, bitmap.height, bounds)
    } catch (e: OutOfMemoryError) {
        null
    }

    /** The first page, as bytes, for use as the book's cover. */
    fun coverBytes(context: Context, bookId: String): ByteArray? =
        pageFile(context, bookId, 0).takeIf { it.exists() }?.readBytes()
}
