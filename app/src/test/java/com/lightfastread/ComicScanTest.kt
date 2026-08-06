package com.lightfastread

import com.lightfastread.comic.ComicScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Page order, which is the only thing a comic file really has to get right.
 *
 * A wrong order is not a visible bug: the pages are all there, they look fine, and the story stops
 * making sense somewhere around page ten. So the sort and the spine walk are pinned here.
 */
class ComicScanTest {

    @Test
    fun `digits sort as numbers, not as text`() {
        val shuffled = listOf("p10.jpg", "p2.jpg", "p1.jpg", "p20.jpg", "p3.jpg")
        assertEquals(
            listOf("p1.jpg", "p2.jpg", "p3.jpg", "p10.jpg", "p20.jpg"),
            shuffled.sortedWith(ComicScan::compareNatural),
        )
    }

    @Test
    fun `numbers count in every segment of a path`() {
        val shuffled = listOf("ch10/p1.jpg", "ch2/p10.jpg", "ch2/p9.jpg", "ch1/p1.jpg")
        assertEquals(
            listOf("ch1/p1.jpg", "ch2/p9.jpg", "ch2/p10.jpg", "ch10/p1.jpg"),
            shuffled.sortedWith(ComicScan::compareNatural),
        )
    }

    @Test
    fun `leading zeros do not change the value`() {
        assertEquals(0, ComicScan.compareNatural("p007.jpg", "p007.jpg"))
        assertTrue(ComicScan.compareNatural("p007.jpg", "p8.jpg") < 0)
        assertTrue(ComicScan.compareNatural("p9.jpg", "p010.jpg") < 0)
    }

    @Test
    fun `a CBZ is its images, without the archiver's litter`() {
        val names = listOf(
            "__MACOSX/._001.jpg",
            "volume/.DS_Store",
            "volume/002.jpg",
            "volume/001.jpg",
            "volume/ComicInfo.xml",
            "volume/003.png",
        )
        assertEquals(
            listOf("volume/001.jpg", "volume/002.jpg", "volume/003.png"),
            ComicScan.cbzPages(names),
        )
    }

    @Test
    fun `an image EPUB follows its spine, not its file names`() {
        // The shape a scanned volume actually ships in: page documents numbered in reading order,
        // images numbered in the order somebody scanned them. Sorting the images would be wrong.
        val files = mapOf(
            "META-INF/container.xml" to
                """<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""",
            "OEBPS/content.opf" to """
                <package><manifest>
                  <item id="p1" href="text/p1.xhtml" media-type="application/xhtml+xml"/>
                  <item id="p2" href="text/p2.xhtml" media-type="application/xhtml+xml"/>
                  <item id="p3" href="text/p3.xhtml" media-type="application/xhtml+xml"/>
                </manifest>
                <spine><itemref idref="p1"/><itemref idref="p2"/><itemref idref="p3"/></spine>
                </package>
            """.trimIndent(),
            "OEBPS/text/p1.xhtml" to """<html><body><img src="../images/scan_88.jpg"/></body></html>""",
            // An SVG wrapper, which is how most publishers pin a scan to the viewport.
            "OEBPS/text/p2.xhtml" to
                """<html><body><svg><image xlink:href="../images/scan_12.jpg"/></svg></body></html>""",
            "OEBPS/text/p3.xhtml" to """<html><body><img src="../images/scan_40.jpg"/></body></html>""",
            "OEBPS/images/scan_88.jpg" to "",
            "OEBPS/images/scan_12.jpg" to "",
            "OEBPS/images/scan_40.jpg" to "",
        )
        val pages = ComicScan.epubPages(files.keys.toList()) { name ->
            files[name]?.toByteArray(Charsets.UTF_8)
        }
        assertEquals(
            listOf("OEBPS/images/scan_88.jpg", "OEBPS/images/scan_12.jpg", "OEBPS/images/scan_40.jpg"),
            pages,
        )
    }

    @Test
    fun `a broken spine falls back to the images themselves`() {
        val files = mapOf(
            "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="nope.opf"/></rootfiles></container>""",
            "img/002.jpg" to "",
            "img/001.jpg" to "",
        )
        assertEquals(
            listOf("img/001.jpg", "img/002.jpg"),
            ComicScan.epubPages(files.keys.toList()) { files[it]?.toByteArray(Charsets.UTF_8) },
        )
    }

    @Test
    fun `hrefs resolve relative to the document that named them`() {
        assertEquals("OEBPS/images/a.jpg", ComicScan.resolve("OEBPS/text", "../images/a.jpg"))
        assertEquals("OEBPS/images/a.jpg", ComicScan.resolve("OEBPS", "images/a.jpg"))
        assertEquals("images/a.jpg", ComicScan.resolve("OEBPS/text", "/images/a.jpg"))
        assertEquals("OEBPS/text/a.jpg", ComicScan.resolve("OEBPS/text", "./a.jpg#frag"))
    }

    @Test
    fun `pictures and prose are told apart by weight`() {
        // Every number below was measured off a real library of 76 EPUBs; the rule that shipped in
        // v1.10 read the second case as prose and killed the app trying to load 175 MB as text.

        // A scanned volume: a page each, a credit line, and effectively all of it images.
        assertTrue(ComicScan.looksLikeComic(193, textChars = 350, imageBytes = 231_000_000, totalBytes = 231_200_000))
        // A scanned volume *with a text afterword* — 16k characters of real prose over 194 scans.
        assertTrue(ComicScan.looksLikeComic(194, textChars = 15_960, imageBytes = 175_400_000, totalBytes = 175_566_328))
        // A novel with a dozen illustrations: a million characters, and 15% of its bytes in images.
        assertFalse(ComicScan.looksLikeComic(12, textChars = 1_084_934, imageBytes = 170_000, totalBytes = 1_140_000))
        // A novel with a cover and nothing else.
        assertFalse(ComicScan.looksLikeComic(2, textChars = 996_710, imageBytes = 76_000, totalBytes = 600_000))
        // A picture book of four plates is not enough to be worth opening as a comic.
        assertFalse(ComicScan.looksLikeComic(4, textChars = 0, imageBytes = 4_000_000, totalBytes = 4_000_100))
    }

    @Test
    fun `the text ratio is only consulted when sizes are unavailable`() {
        // No byte information at all — fall back to the old heuristic rather than refusing to guess.
        assertTrue(ComicScan.looksLikeComic(193, textChars = 350))
        assertFalse(ComicScan.looksLikeComic(40, textChars = 400_000))
        // With sizes present and low, a small text count must NOT override them: this shape is an
        // art-heavy novel, and opening it as a comic would show unreadable page images.
        assertFalse(ComicScan.looksLikeComic(20, textChars = 100, imageBytes = 100_000, totalBytes = 1_000_000))
    }

    @Test
    fun `markup does not count as text`() {
        val page = """
            <html><head><style>body { color: red; }</style></head>
            <body><div class="page"><img src="x.jpg" alt=""/></div>&#160;</body></html>
        """.trimIndent()
        assertTrue(ComicScan.visibleTextLength(page) < 10)
    }
}
