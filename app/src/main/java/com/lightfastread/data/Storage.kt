package com.lightfastread.data

import android.content.Context
import android.os.StatFs

/**
 * How much room there is left, and how much of it a download may use.
 *
 * This exists because the download limit used to be a number somebody typed — 96 MB, which was
 * generous for an EPUB and absurd for a manga volume, and which failed with "that file is larger
 * than 96 MB" as though 96 were a fact about the phone. The real limit is the free space on the
 * device, and the real question is whether *this* file fits, which is a question with an answer.
 */
object Storage {

    /**
     * Never fill the disk. Android starts behaving badly well before zero — notifications stop,
     * other apps fail to write — so a download that would leave the phone with nothing is a failure
     * worth reporting before it happens rather than a success worth having.
     */
    private const val RESERVE_BYTES = 300L * 1024 * 1024

    /**
     * An upper bound that has nothing to do with space: a single book this size is a mistake
     * somewhere — a wrong link, a server streaming an error page forever — and a runaway download
     * on a metered connection is worse than a refused one.
     */
    private const val ABSOLUTE_MAX_BYTES = 4L * 1024 * 1024 * 1024

    /** Free bytes on the volume that holds the app's own files. */
    fun freeBytes(context: Context): Long = runCatching {
        val stat = StatFs(context.filesDir.absolutePath)
        stat.availableBlocksLong * stat.blockSizeLong
    }.getOrDefault(Long.MAX_VALUE)

    /** The largest download worth starting right now. */
    fun downloadLimit(context: Context): Long =
        (freeBytes(context) - RESERVE_BYTES).coerceIn(0, ABSOLUTE_MAX_BYTES)

    /**
     * Whether a file of [bytes] fits, allowing for what the import will do with it afterwards.
     *
     * A comic is downloaded *and then* converted into stored pages, so for a moment both exist. The
     * pages come to roughly a tenth of the archive, so 1.2x the download is a safe headroom — and
     * the archive is deleted as soon as the conversion finishes.
     */
    fun fits(context: Context, bytes: Long): Boolean =
        bytes > 0 && (bytes * 12 / 10) <= downloadLimit(context)

    /**
     * Whether a file can be read into memory whole.
     *
     * The text import path allocates the entire book as a `ByteArray` and then builds a string of
     * its text on top, so the safe ceiling is a fraction of the heap rather than the heap itself.
     * A quarter is conservative on purpose: the phone reported a 128 MB limit with 124 MB still to
     * give, and a 175 MB allocation ended the process outright.
     */
    fun fitsInHeap(bytes: Long): Boolean = bytes in 1..(Runtime.getRuntime().maxMemory() / 4)

    fun tooBigForHeapMessage(bytes: Long): String =
        "That book is ${humanBytes(bytes)} of text, which is more than this phone can open at once. " +
            "If it is a comic, it needs to be a CBZ or an image EPUB."

    /** "1.4 GB", "137 MB", "812 kB" — for a message a person has to act on. */
    fun humanBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> "${bytes / (1024 * 1024)} MB"
        bytes >= 1024L -> "${bytes / 1024} kB"
        else -> "$bytes bytes"
    }

    /** The sentence to show when something does not fit. Names both numbers, because both matter. */
    fun tooBigMessage(context: Context, bytes: Long): String =
        "That file is ${humanBytes(bytes)} and the phone has ${humanBytes(freeBytes(context))} free."
}
