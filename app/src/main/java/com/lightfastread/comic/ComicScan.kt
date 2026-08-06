package com.lightfastread.comic

/**
 * Working out what a comic file's pages are, and in what order.
 *
 * Deliberately free of Android: it takes a list of zip entry names and a way to read one, so a unit
 * test can hand it a synthesised archive. Everything that needs a `Bitmap` lives in [ComicPages].
 *
 * Two formats arrive here and they disagree about everything:
 *
 * - **CBZ** is a zip of images and nothing else. No manifest, no metadata, no declared order — the
 *   file names *are* the order, which is why [compareNatural] matters more than it looks.
 * - **An image EPUB** is a real EPUB whose every page is a full-page scan. The spine is the order,
 *   and each spine document is an XHTML wrapper around one `<img>`. Reading it as text (which is
 *   what this app did until now) yields a hundred blank pages.
 */
object ComicScan {

    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "avif")

    /** Junk every archiver leaves behind, and which sorts to the front if you let it. */
    private val JUNK_PREFIXES = listOf("__MACOSX/", ".")

    fun isImage(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

    private fun isJunk(name: String): Boolean {
        val leaf = name.substringAfterLast('/')
        return leaf.isEmpty() ||
            leaf.startsWith(".") ||
            JUNK_PREFIXES.any { name.startsWith(it) } ||
            name.contains("/__MACOSX/")
    }

    /**
     * The pages of a CBZ, in reading order.
     *
     * Ordinary string sort puts `page10` before `page2`, which shuffles a chapter into nonsense
     * roughly a tenth of the way in — far enough that it looks like a corrupt file rather than a
     * sorting bug. [compareNatural] compares runs of digits as numbers, so it doesn't.
     */
    fun cbzPages(names: List<String>): List<String> =
        names.filter { isImage(it) && !isJunk(it) }.sortedWith(::compareNatural)

    /**
     * Compare two names the way a person would read them.
     *
     * Digits are compared as numbers wherever they appear, so `ch2/p9` precedes `ch2/p10` and
     * `ch10/p1`. Leading zeros are ignored for value but kept as a tie-break, so `01` and `1` are
     * ordered stably rather than arbitrarily.
     */
    fun compareNatural(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ei = i
                while (ei < a.length && a[ei].isDigit()) ei++
                var ej = j
                while (ej < b.length && b[ej].isDigit()) ej++
                val na = a.substring(i, ei).trimStart('0')
                val nb = b.substring(j, ej).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val cmp = na.compareTo(nb)
                if (cmp != 0) return cmp
                i = ei
                j = ej
            } else {
                val cmp = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (cmp != 0) return cmp
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }

    /**
     * The pages of an image EPUB, in spine order.
     *
     * Follows the real chain — `META-INF/container.xml` names the OPF, the OPF's manifest maps ids
     * to hrefs and the spine names the ids in order — because a scanned EPUB's file names are
     * frequently `p_0001.xhtml` referencing `i_0032.jpg`, so the images alone sort into the wrong
     * order. Falls back to a natural sort of every image when any link in that chain is missing,
     * which is what a malformed EPUB usually needs anyway.
     *
     * [read] returns null for an entry that cannot be read; that is treated as "skip this page",
     * never as a failure.
     */
    fun epubPages(names: List<String>, read: (String) -> ByteArray?): List<String> {
        val fromSpine = runCatching { spinePages(names, read) }.getOrNull().orEmpty()
        // A handful of spine entries that resolved is worse than no spine at all: it means the
        // manifest and the archive disagree, and a partial book reads as a corrupt one.
        val images = cbzPages(names)
        return if (fromSpine.size >= images.size / 2 && fromSpine.isNotEmpty()) fromSpine else images
    }

    private fun spinePages(names: List<String>, read: (String) -> ByteArray?): List<String> {
        val containerXml = read("META-INF/container.xml")?.toString(Charsets.UTF_8) ?: return emptyList()
        val opfPath = Regex("""full-path="([^"]+)"""").find(containerXml)?.groupValues?.get(1)
            ?: return emptyList()
        val opf = read(opfPath)?.toString(Charsets.UTF_8) ?: return emptyList()
        val opfDir = opfPath.substringBeforeLast('/', "")

        val manifest = HashMap<String, String>()
        for (item in Regex("""<item\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(opf)) {
            val tag = item.value
            val id = attr(tag, "id") ?: continue
            val href = attr(tag, "href") ?: continue
            manifest[id] = resolve(opfDir, href)
        }

        val entries = names.toHashSet()
        val pages = ArrayList<String>()
        for (ref in Regex("""<itemref\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(opf)) {
            val idref = attr(ref.value, "idref") ?: continue
            val target = manifest[idref] ?: continue
            if (isImage(target)) {
                if (target in entries) pages.add(target)
                continue
            }
            // An XHTML wrapper: the page is whatever image it points at. `<image xlink:href>` as
            // well as `<img src>`, because scanned EPUBs very often wrap the scan in an SVG to
            // pin it to the viewport.
            val doc = read(target)?.toString(Charsets.UTF_8) ?: continue
            val src = Regex("""<img\b[^>]*\bsrc\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(doc)?.groupValues?.get(1)
                ?: Regex("""<image\b[^>]*?href\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    .find(doc)?.groupValues?.get(1)
                ?: continue
            val resolved = resolve(target.substringBeforeLast('/', ""), decodeEntities(src))
            if (resolved in entries) pages.add(resolved)
        }
        return pages
    }

    private fun attr(tag: String, name: String): String? =
        Regex("""\b$name\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
            .find(tag)?.groupValues?.get(1)

    private fun decodeEntities(s: String): String =
        s.replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"")

    /** Resolve an href against the directory it was found in, collapsing `..` and `.`. */
    fun resolve(baseDir: String, href: String): String {
        val cleaned = href.substringBefore('#').trim()
        if (cleaned.startsWith("/")) return cleaned.trimStart('/')
        val parts = ArrayList<String>()
        if (baseDir.isNotEmpty()) parts.addAll(baseDir.split('/').filter { it.isNotEmpty() })
        for (segment in cleaned.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts.add(segment)
            }
        }
        return parts.joinToString("/")
    }

    /**
     * Is this EPUB a book of pictures rather than a book of words?
     *
     * The question is worth asking because the answer decides which reader opens, and because
     * getting it wrong is loud in both directions: a manga volume opened as text is a hundred blank
     * pages, and a novel opened as a comic is a hundred unreadable page images.
     *
     * The test is the ratio, not either number alone. An illustrated novel has plenty of images and
     * plenty of text; a scanned volume has one image per page and a few characters of alt text or
     * credits. [textChars] is the visible text across the whole book, [imageCount] the images in the
     * archive.
     */
    fun looksLikeComic(imageCount: Int, textChars: Int): Boolean =
        imageCount >= MIN_PAGES && textChars < imageCount * MAX_TEXT_CHARS_PER_PAGE

    /** Below this it is an illustrated something, not a volume of scans. */
    private const val MIN_PAGES = 8

    /**
     * A scanned page carries a credit line, a chapter label, or nothing. Real prose is thousands of
     * characters a page, so there is a lot of daylight either side of this.
     */
    private const val MAX_TEXT_CHARS_PER_PAGE = 60

    /** Strip tags for the text measurement above. Crude on purpose — this is a ratio, not a parse. */
    fun visibleTextLength(xhtml: String): Int =
        xhtml.replace(Regex("""(?is)<(script|style)\b.*?</\1>"""), " ")
            .replace(Regex("""<[^>]+>"""), " ")
            .replace(Regex("""&[a-zA-Z#0-9]+;"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .length
}
