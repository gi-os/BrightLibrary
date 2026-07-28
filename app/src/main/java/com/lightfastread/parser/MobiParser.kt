package com.lightfastread.parser

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

object MobiParser {
    fun parseBytes(bytes: ByteArray, fallbackTitle: String): ParsedBook {
        if (bytes.size < 78) throw IllegalStateException("File too small to be MOBI")

        val numRecords = readU16(bytes, 76)
        if (numRecords < 1) throw IllegalStateException("MOBI has no records")
        if (bytes.size < 78 + numRecords * 8) throw IllegalStateException("Truncated record list")

        val recordOffsets = IntArray(numRecords) { i -> readU32(bytes, 78 + i * 8) }

        val rec0 = recordSlice(bytes, recordOffsets, 0)
        if (rec0.size < 16) throw IllegalStateException("Record 0 too small")

        val compression = readU16(rec0, 0)
        val textLength = readU32(rec0, 4)
        val textRecordCount = readU16(rec0, 8)

        var encoding = "ISO-8859-1"
        var title = fallbackTitle
        var author = ""

        if (rec0.size >= 20 && rec0[16] == 'M'.code.toByte() && rec0[17] == 'O'.code.toByte()
            && rec0[18] == 'B'.code.toByte() && rec0[19] == 'I'.code.toByte()
        ) {
            val mobiOffset = 16
            val headerLen = readU32(rec0, mobiOffset + 4)
            val textEncoding = readU32(rec0, mobiOffset + 12)
            encoding = when (textEncoding) {
                65001 -> "UTF-8"
                1252 -> "Windows-1252"
                else -> "ISO-8859-1"
            }
            if (rec0.size >= mobiOffset + 92) {
                val fullNameOffsetInRec = readU32(rec0, mobiOffset + 84)
                val fullNameLen = readU32(rec0, mobiOffset + 88)
                if (fullNameLen in 1..2048 && fullNameOffsetInRec >= 0
                    && fullNameOffsetInRec + fullNameLen <= rec0.size
                ) {
                    val rawTitle = rec0.copyOfRange(fullNameOffsetInRec, fullNameOffsetInRec + fullNameLen)
                    runCatching {
                        title = String(rawTitle, charset(encoding)).trim().ifEmpty { fallbackTitle }
                    }
                }
            }

            val exthOffset = mobiOffset + headerLen
            if (exthOffset + 12 <= rec0.size
                && rec0[exthOffset] == 'E'.code.toByte() && rec0[exthOffset + 1] == 'X'.code.toByte()
                && rec0[exthOffset + 2] == 'T'.code.toByte() && rec0[exthOffset + 3] == 'H'.code.toByte()
            ) {
                val recordCount = readU32(rec0, exthOffset + 8)
                var pos = exthOffset + 12
                for (i in 0 until recordCount) {
                    if (pos + 8 > rec0.size) break
                    val type = readU32(rec0, pos)
                    val len = readU32(rec0, pos + 4)
                    if (len < 8 || pos + len > rec0.size) break
                    val data = rec0.copyOfRange(pos + 8, pos + len)
                    if (type == 100) { // creator
                        runCatching {
                            author = String(data, charset(encoding)).trim()
                        }
                    }
                    pos += len
                }
            }
        }

        if (compression != 1 && compression != 2) {
            throw IllegalStateException("Unsupported MOBI compression: $compression (HUFFCDIC not supported)")
        }

        val out = ByteArrayOutputStream()
        var written = 0
        for (i in 1..textRecordCount) {
            if (i >= numRecords) break
            val rec = recordSlice(bytes, recordOffsets, i)
            val trailingStripped = stripTrailingEntries(rec)
            val decoded = if (compression == 1) trailingStripped else palmDocDecompress(trailingStripped)
            val remaining = textLength - written
            if (remaining <= 0) break
            val toWrite = if (decoded.size > remaining) decoded.copyOf(remaining) else decoded
            out.write(toWrite)
            written += toWrite.size
            if (written >= textLength) break
        }

        val htmlBytes = out.toByteArray()
        val html = runCatching { String(htmlBytes, charset(encoding)) }
            .getOrElse { String(htmlBytes, Charsets.UTF_8) }

        val (text, chapters) = splitChapters(html)

        return ParsedBook(
            title = title,
            author = author,
            format = "MOBI",
            text = text,
            chapters = chapters,
        )
    }

    private fun splitChapters(html: String): Pair<String, List<RawChapter>> {
        val headingRe = Regex("<(h[1-3])[^>]*>([\\s\\S]*?)</\\1>", RegexOption.IGNORE_CASE)
        val matches = headingRe.findAll(html).toList()
        if (matches.size < 2) {
            // Not enough headings to be useful — treat as a single chapter.
            val text = HtmlStripper.strip(html).trim()
            return text to listOf(RawChapter("Book", 0))
        }

        val sb = StringBuilder()
        val chapters = ArrayList<RawChapter>()

        // Preface before first heading (if any non-blank text exists).
        if (matches[0].range.first > 0) {
            val pref = HtmlStripper.strip(html.substring(0, matches[0].range.first))
            if (pref.isNotBlank()) {
                chapters.add(RawChapter("Start", sb.length))
                sb.append(pref)
                sb.append("\n\n")
            }
        }

        for (i in matches.indices) {
            val m = matches[i]
            val end = if (i + 1 < matches.size) matches[i + 1].range.first else html.length
            val titleRaw = HtmlStripper.stripItalicMarkers(HtmlStripper.strip(m.groupValues[2]))
                .replace('\n', ' ').trim()
            val chapTitle = titleRaw.take(120).ifBlank { "Chapter ${chapters.size + 1}" }
            val segment = HtmlStripper.strip(html.substring(m.range.first, end))
            if (segment.isBlank()) continue
            chapters.add(RawChapter(chapTitle, sb.length))
            sb.append(segment)
            sb.append("\n\n")
        }

        return sb.toString().trim() to chapters
    }

    private fun recordSlice(bytes: ByteArray, offsets: IntArray, index: Int): ByteArray {
        val start = offsets[index]
        val end = if (index + 1 < offsets.size) offsets[index + 1] else bytes.size
        if (start < 0 || end > bytes.size || start > end) return ByteArray(0)
        return bytes.copyOfRange(start, end)
    }

    private fun stripTrailingEntries(rec: ByteArray): ByteArray {
        if (rec.isEmpty()) return rec
        var size = rec.size
        val flags = rec[size - 1].toInt() and 0xFF
        val numEntries = flags and 0x03
        val hasMultibyte = (flags and 0x04) != 0
        repeat(numEntries) {
            if (size <= 0) return@repeat
            val checkLen = minOf(4, size)
            var num = 0
            for (i in (size - checkLen) until size) {
                val b = rec[i].toInt() and 0xFF
                if (b and 0x80 != 0) num = 0
                num = (num shl 7) or (b and 0x7F)
            }
            if (num in 1..size) size -= num else return@repeat
        }
        if (hasMultibyte && size > 0) {
            val n = (rec[size - 1].toInt() and 0x03) + 1
            if (n in 1..size) size -= n
        }
        return if (size == rec.size) rec else rec.copyOfRange(0, size)
    }

    private fun palmDocDecompress(input: ByteArray): ByteArray {
        var buf = ByteArray(input.size * 2)
        var size = 0
        fun ensure(extra: Int) {
            if (size + extra > buf.size) {
                var newCap = buf.size * 2
                while (newCap < size + extra) newCap *= 2
                buf = buf.copyOf(newCap)
            }
        }
        fun put(byte: Int) {
            ensure(1)
            buf[size++] = byte.toByte()
        }

        var i = 0
        while (i < input.size) {
            val b = input[i].toInt() and 0xFF
            i++
            when {
                b == 0 -> put(0)
                b in 0x01..0x08 -> {
                    val end = (i + b).coerceAtMost(input.size)
                    ensure(end - i)
                    while (i < end) put(input[i++].toInt() and 0xFF)
                }
                b in 0x09..0x7F -> put(b)
                b in 0x80..0xBF -> {
                    if (i >= input.size) break
                    val next = input[i].toInt() and 0xFF
                    i++
                    val word = ((b shl 8) or next) and 0x3FFF
                    val distance = word ushr 3
                    val length = (word and 0x07) + 3
                    if (distance <= 0 || distance > size) continue
                    val srcStart = size - distance
                    ensure(length)
                    for (k in 0 until length) {
                        buf[size] = buf[srcStart + k]
                        size++
                    }
                }
                b in 0xC0..0xFF -> {
                    ensure(2)
                    put(' '.code)
                    put(b xor 0x80)
                }
            }
        }
        return buf.copyOf(size)
    }

    private fun readU16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private fun readU32(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)

    private fun charset(name: String): Charset =
        runCatching { Charset.forName(name) }.getOrDefault(Charsets.UTF_8)
}
