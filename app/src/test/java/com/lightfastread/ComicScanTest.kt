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
    fun `pictures and prose are told apart by the ratio, not the count`() {
        // A scanned volume: a page each, and a few characters of credit line.
        assertTrue(ComicScan.looksLikeComic(imageCount = 193, textChars = 350))
        // An illustrated novel: plenty of images, and an actual book's worth of words.
        assertFalse(ComicScan.looksLikeComic(imageCount = 40, textChars = 400_000))
        // A picture book of eight plates is not enough to be worth opening as a comic.
        assertFalse(ComicScan.looksLikeComic(imageCount = 4, textChars = 0))
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
