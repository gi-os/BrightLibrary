package com.lightfastread.parser

import android.util.Log
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.util.zip.ZipInputStream

object EpubParser {
    private const val TAG = "EpubParser"

    private data class ManifestItem(val href: String, val mediaType: String?, val properties: String?)

    fun parseBytes(bytes: ByteArray, fallbackTitle: String): ParsedBook {
        val entries = readZipEntries(bytes)

        val containerXml = entries["META-INF/container.xml"]?.toString(Charsets.UTF_8)
            ?: throw IllegalStateException("Not a valid EPUB: missing META-INF/container.xml")

        val opfPath = Regex("full-path=\"([^\"]+)\"").find(containerXml)?.groupValues?.get(1)
            ?: throw IllegalStateException("EPUB OPF path not found")

        val opfBytes = entries[opfPath] ?: throw IllegalStateException("OPF file not found")
        val opf = opfBytes.toString(Charsets.UTF_8)

        val title = Regex("<dc:title[^>]*>([^<]+)</dc:title>", RegexOption.IGNORE_CASE)
            .find(opf)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
            ?: fallbackTitle
        val author = Regex("<dc:creator[^>]*>([^<]+)</dc:creator>", RegexOption.IGNORE_CASE)
            .find(opf)?.groupValues?.get(1)?.trim().orEmpty()

        val manifest = HashMap<String, ManifestItem>()
        // `[^>]*` (was `[^/>]*`) so that media-type values like "application/xhtml+xml" don't
        // break the match. Trailing `/` for self-closing tags ends up inside the captured
        // group; the per-attribute regexes below ignore it.
        Regex("<item\\b([^>]*)>", RegexOption.IGNORE_CASE).findAll(opf).forEach { m ->
            val attrs = m.groupValues[1]
            val id = Regex("id=\"([^\"]+)\"").find(attrs)?.groupValues?.get(1)
            val href = Regex("href=\"([^\"]+)\"").find(attrs)?.groupValues?.get(1)
            if (id != null && href != null) {
                val mediaType = Regex("media-type=\"([^\"]+)\"").find(attrs)?.groupValues?.get(1)
                val props = Regex("properties=\"([^\"]+)\"").find(attrs)?.groupValues?.get(1)
                manifest[id] = ManifestItem(href, mediaType, props)
            }
        }
        Log.d(TAG, "manifest items: ${manifest.size}")

        val spineIds = Regex("<itemref\\b[^>]*idref=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
            .findAll(opf)
            .map { it.groupValues[1] }
            .toList()
        Log.d(TAG, "spine itemrefs: ${spineIds.size}")

        val opfDir = opfPath.substringBeforeLast('/', "")
        val sb = StringBuilder()
        val spineChapters = ArrayList<RawChapter>()
        // Map every plausible key for a spine file -> its char offset in the concatenated text,
        // so a TOC `content src=` lookup can hit regardless of whether it's relative to the OPF
        // or the NCX/nav doc, and regardless of URL-encoding.
        val hrefToOffset = HashMap<String, Int>()
        var spinePos = 0
        for (id in spineIds) {
            val item = manifest[id] ?: continue
            val href = item.href
            val fullPath = resolveRelativePath(opfDir, href)
            val data = entries[fullPath]
                ?: entries[normalizePath(href)]
                ?: entries[href]
                ?: continue
            val html = data.toString(Charsets.UTF_8)
            val text = HtmlStripper.strip(html)
            if (text.isNotBlank()) {
                spinePos++
                val offset = sb.length
                recordOffset(hrefToOffset, fullPath, href, offset)
                val chapterTitle = extractChapterTitle(html) ?: "Chapter $spinePos"
                spineChapters.add(RawChapter(chapterTitle, offset))
                sb.append(text)
                sb.append("\n\n")
            }
        }

        Log.d(TAG, "spine-derived chapters: ${spineChapters.size}, total chars: ${sb.length}")

        if (sb.isEmpty()) {
            Log.d(TAG, "spine produced no text; falling back to alphabetical HTML scan")
            entries.entries
                .filter { it.key.lowercase().let { k -> k.endsWith(".xhtml") || k.endsWith(".html") || k.endsWith(".htm") } }
                .sortedBy { it.key }
                .forEach {
                    val html = it.value.toString(Charsets.UTF_8)
                    val text = HtmlStripper.strip(html)
                    if (text.isNotBlank()) {
                        spinePos++
                        val offset = sb.length
                        recordOffset(hrefToOffset, it.key, it.key, offset)
                        val chapterTitle = extractChapterTitle(html) ?: "Chapter $spinePos"
                        spineChapters.add(RawChapter(chapterTitle, offset))
                        sb.append(text)
                        sb.append("\n\n")
                    }
                }
            Log.d(TAG, "fallback chapters: ${spineChapters.size}")
        }

        val tocChapters = parseTocChapters(entries, opfDir, manifest, hrefToOffset)
        val finalChapters = if (tocChapters.isNotEmpty()) tocChapters else spineChapters
        Log.d(
            TAG,
            "final chapters: ${finalChapters.size} (source=${if (tocChapters.isNotEmpty()) "TOC" else "spine"})",
        )
        finalChapters.take(30).forEachIndexed { i, c ->
            Log.d(TAG, "  [$i] charOff=${c.charOffset} title=${c.title}")
        }
        if (finalChapters.size > 30) {
            Log.d(TAG, "  … and ${finalChapters.size - 30} more")
        }

        return ParsedBook(
            title = title,
            author = author,
            format = "EPUB",
            text = sb.toString().trim(),
            chapters = finalChapters,
        )
    }

    private fun recordOffset(
        map: MutableMap<String, Int>,
        fullPath: String,
        href: String,
        offset: Int,
    ) {
        // Only the first occurrence wins, so duplicate names from the fallback don't clobber.
        map.putIfAbsent(fullPath, offset)
        map.putIfAbsent(normalizePath(href), offset)
        map.putIfAbsent(href, offset)
        // Bare filename, for TOCs that drop directories.
        val bare = fullPath.substringAfterLast('/')
        if (bare.isNotEmpty()) map.putIfAbsent(bare, offset)
    }

    private fun parseTocChapters(
        entries: Map<String, ByteArray>,
        opfDir: String,
        manifest: Map<String, ManifestItem>,
        hrefToOffset: Map<String, Int>,
    ): List<RawChapter> {
        // EPUB 2: NCX, identified by media-type.
        val ncxItem = manifest.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
        if (ncxItem != null) {
            val ncxPath = resolveRelativePath(opfDir, ncxItem.href)
            val ncxData = entries[ncxPath] ?: entries[normalizePath(ncxItem.href)] ?: entries[ncxItem.href]
            Log.d(TAG, "NCX: href=${ncxItem.href} resolved=$ncxPath found=${ncxData != null}")
            if (ncxData != null) {
                val ncxXml = ncxData.toString(Charsets.UTF_8)
                val ncxDir = ncxPath.substringBeforeLast('/', "")
                val chapters = parseNavPoints(ncxXml, ncxDir, hrefToOffset)
                Log.d(TAG, "NCX chapters resolved: ${chapters.size}")
                if (chapters.isNotEmpty()) return chapters
            }
        } else {
            Log.d(TAG, "NCX: no manifest item with media-type=application/x-dtbncx+xml")
        }

        // EPUB 3: nav doc, identified by properties="nav".
        val navItem = manifest.values.firstOrNull { it.properties?.contains("nav") == true }
        if (navItem != null) {
            val navPath = resolveRelativePath(opfDir, navItem.href)
            val navData = entries[navPath] ?: entries[normalizePath(navItem.href)] ?: entries[navItem.href]
            Log.d(TAG, "EPUB3 nav: href=${navItem.href} resolved=$navPath found=${navData != null}")
            if (navData != null) {
                val navXml = navData.toString(Charsets.UTF_8)
                val navDir = navPath.substringBeforeLast('/', "")
                val chapters = parseNavXhtml(navXml, navDir, hrefToOffset)
                Log.d(TAG, "EPUB3 nav chapters resolved: ${chapters.size}")
                if (chapters.isNotEmpty()) return chapters
            }
        }

        return emptyList()
    }

    private fun parseNavPoints(
        ncxXml: String,
        ncxDir: String,
        hrefToOffset: Map<String, Int>,
    ): List<RawChapter> {
        // Each navPoint contains its own <navLabel><text>…</text></navLabel> followed by <content src="…"/>.
        // Match the first such pair per navPoint in document order; this naturally flattens nested navPoints.
        val pairRegex = Regex(
            "<navLabel\\b[^>]*>\\s*<text\\b[^>]*>([\\s\\S]*?)</text>\\s*</navLabel>[\\s\\S]*?<content\\b[^>]*src=\"([^\"]+)\"",
            RegexOption.IGNORE_CASE,
        )
        val result = ArrayList<RawChapter>()
        var pairs = 0
        var unresolved = 0
        for (m in pairRegex.findAll(ncxXml)) {
            pairs++
            val label = HtmlStripper.stripItalicMarkers(HtmlStripper.strip(m.groupValues[1]))
                .replace('\n', ' ').trim()
            if (label.isEmpty()) continue
            val src = m.groupValues[2]
            val offset = resolveTocOffset(src, ncxDir, hrefToOffset)
            if (offset == null) {
                unresolved++
                Log.d(TAG, "NCX entry unresolved: src=$src label=$label")
                continue
            }
            result.add(RawChapter(label, offset))
        }
        Log.d(TAG, "NCX navPoint pairs found: $pairs, unresolved: $unresolved")
        return result
    }

    private fun parseNavXhtml(
        navXml: String,
        navDir: String,
        hrefToOffset: Map<String, Int>,
    ): List<RawChapter> {
        val tocBlock = Regex(
            "<nav\\b[^>]*epub:type=\"[^\"]*\\btoc\\b[^\"]*\"[^>]*>([\\s\\S]*?)</nav>",
            RegexOption.IGNORE_CASE,
        ).find(navXml)?.groupValues?.get(1) ?: return emptyList()

        val anchorRegex = Regex(
            "<a\\b[^>]*href=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</a>",
            RegexOption.IGNORE_CASE,
        )
        val result = ArrayList<RawChapter>()
        for (m in anchorRegex.findAll(tocBlock)) {
            val label = HtmlStripper.stripItalicMarkers(HtmlStripper.strip(m.groupValues[2]))
                .replace('\n', ' ').trim()
            if (label.isEmpty()) continue
            val offset = resolveTocOffset(m.groupValues[1], navDir, hrefToOffset) ?: continue
            result.add(RawChapter(label, offset))
        }
        return result
    }

    private fun resolveTocOffset(
        rawSrc: String,
        baseDir: String,
        hrefToOffset: Map<String, Int>,
    ): Int? {
        val noFragment = rawSrc.substringBefore('#')
        if (noFragment.isEmpty()) return null
        val full = resolveRelativePath(baseDir, noFragment)
        return hrefToOffset[full]
            ?: hrefToOffset[normalizePath(noFragment)]
            ?: hrefToOffset[noFragment]
            ?: hrefToOffset[full.substringAfterLast('/')]
    }

    private fun resolveRelativePath(baseDir: String, href: String): String {
        val decoded = try { URLDecoder.decode(href, "UTF-8") } catch (_: Exception) { href }
        val combined = if (baseDir.isEmpty()) decoded else "$baseDir/$decoded"
        return normalizePath(combined)
    }

    private fun extractChapterTitle(html: String): String? {
        val headingPatterns = listOf(
            Regex("<h1[^>]*>([\\s\\S]*?)</h1>", RegexOption.IGNORE_CASE),
            Regex("<h2[^>]*>([\\s\\S]*?)</h2>", RegexOption.IGNORE_CASE),
            Regex("<h3[^>]*>([\\s\\S]*?)</h3>", RegexOption.IGNORE_CASE),
            Regex("<title[^>]*>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE),
        )
        for (re in headingPatterns) {
            val raw = re.find(html)?.groupValues?.get(1) ?: continue
            val cleaned = HtmlStripper.stripItalicMarkers(HtmlStripper.strip(raw))
                .replace('\n', ' ').trim()
            if (cleaned.isNotEmpty()) return cleaned.take(120)
        }
        return null
    }

    private fun normalizePath(path: String): String {
        val parts = path.split('/').toMutableList()
        var i = 0
        while (i < parts.size) {
            when (parts[i]) {
                "." -> parts.removeAt(i)
                ".." -> {
                    if (i > 0) {
                        parts.removeAt(i)
                        parts.removeAt(i - 1)
                        i--
                    } else {
                        parts.removeAt(i)
                    }
                }
                else -> i++
            }
        }
        return parts.joinToString("/")
    }

    private fun readZipEntries(bytes: ByteArray): Map<String, ByteArray> {
        val map = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                if (!entry.isDirectory) {
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = zin.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                    }
                    map[entry.name] = out.toByteArray()
                }
                zin.closeEntry()
            }
        }
        return map
    }
}
