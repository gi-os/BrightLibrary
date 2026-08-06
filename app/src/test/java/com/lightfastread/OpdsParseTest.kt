package com.lightfastread

import com.lightfastread.calibre.CalibreClient
import com.lightfastread.calibre.Opds
import com.lightfastread.calibre.ReadingState
import com.lightfastread.data.Storage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The OPDS parse, pinned against the shapes real servers emit.
 *
 * All three feeds below are trimmed from actual responses: calibre-web's `/opds` (navigation, no
 * namespace prefix), calibre-web's book list (publications, relative hrefs, a cover and a thumbnail),
 * and calibre's own `calibre-server` (prefixed Atom, absolute hrefs, two formats per book). Between
 * them they cover every branch that decides what a row is and what gets downloaded.
 */
class OpdsParseTest {

    @Test
    fun `navigation feed yields sections and no publications`() {
        val feed = Opds.parse(NAVIGATION_FEED, "http://192.168.68.59:8768/opds")
        assertEquals("Calibre-Web", feed.title)
        assertEquals(2, feed.navigation.size)
        assertTrue(feed.publications.isEmpty())
        assertEquals("Recently added", feed.navigation[0].title)
        // Relative href resolved against the feed's own URL, not against the host root.
        assertEquals("http://192.168.68.59:8768/opds/new", feed.navigation[0].feedHref)
        assertEquals("http://192.168.68.59:8768/opds/osd", feed.searchHref)
    }

    @Test
    fun `publication carries uuid cover and a downloadable format`() {
        val feed = Opds.parse(BOOKS_FEED, "http://192.168.68.59:8768/opds/new")
        assertEquals(1, feed.publications.size)
        val book = feed.publications.single()
        assertEquals("The Dark Forest", book.title)
        assertEquals("Cixin Liu", book.author)
        assertEquals("1d2c0f9e-6c1e-4b6a-9a0e-8f2d3c4b5a6f", book.uuid)
        assertEquals("http://192.168.68.59:8768/opds/cover/3", book.coverHref)
        assertEquals("http://192.168.68.59:8768/opds/cover_thumb/3", book.thumbnailHref)
        val (link, ext) = book.bestDownload()!!
        assertEquals("epub", ext)
        assertEquals("http://192.168.68.59:8768/opds/download/3/epub", link.href)
        assertEquals("EPUB", book.formatLabel())
        assertEquals("http://192.168.68.59:8768/opds/new?offset=60", feed.nextHref)
    }

    @Test
    fun `EPUB wins over MOBI and a namespace prefix changes nothing`() {
        val feed = Opds.parse(PREFIXED_FEED, "http://basilnet:8080/opds/navcatalog/4e")
        val book = feed.publications.single()
        assertEquals("Neuromancer", book.title)
        assertEquals("epub", book.bestDownload()!!.second)
    }

    @Test
    fun `a book in no readable format offers no download`() {
        val feed = Opds.parse(PDF_ONLY_FEED, "http://basilnet:8080/opds")
        val book = feed.entries.single()
        // Still a publication — it has an acquisition link — but nothing this app can open, which is
        // why the row has to be able to say so rather than the parse pretending the book is absent.
        assertTrue(book.isPublication)
        assertNull(book.bestDownload())
        assertNull(book.formatLabel())
    }

    @Test
    fun `an octet-stream acquisition falls back to the URL for its format`() {
        val book = Opds.parse(OCTET_FEED, "http://basilnet:8080/opds").entries.single()
        // Plenty of servers label every download `application/octet-stream`, in which case the file
        // name is the only evidence of what the bytes are.
        assertEquals("epub", book.bestDownload()!!.second)
    }

    @Test
    fun `search template comes out of the OpenSearch description`() {
        val template = Opds.parseSearchTemplate(OSD, "http://192.168.68.59:8768/opds/osd")
        assertEquals("http://192.168.68.59:8768/opds/search/{searchTerms}", template)
    }

    @Test
    fun `an acquisition link carries the size the server declared`() {
        val book = Opds.parse(BOOKS_FEED, "http://192.168.68.59:8768/opds/new").publications.single()
        assertEquals(4_194_304L, book.bestDownload()!!.first.length)
        assertEquals("4 MB", Storage.humanBytes(4_194_304L))
        assertEquals("130 MB", Storage.humanBytes(137_307_346L))
        assertEquals("1.4 GB", Storage.humanBytes(1_500_000_000L))
    }

    @Test
    fun `search templates are filled without a regex`() {
        assertEquals(
            "http://basilnet:8768/opds/search/dune",
            Opds.fillSearchTemplate("http://basilnet:8768/opds/search/{searchTerms}", "dune"),
        )
        // Namespace prefixes and the optional marker are both real, and both appear in the wild.
        assertEquals(
            "/search?q=dune",
            Opds.fillSearchTemplate("/search?q={atom:searchTerms}", "dune"),
        )
        assertEquals("/search?q=dune", Opds.fillSearchTemplate("/search?q={searchTerms?}", "dune"))
        // An optional parameter this client cannot supply is dropped; a required one is left in
        // place so the request fails loudly rather than searching for something else.
        assertEquals(
            "/search?q=dune&start=",
            Opds.fillSearchTemplate("/search?q={searchTerms}&start={startIndex?}", "dune"),
        )
        assertEquals(
            "/search?q=dune&page={pageNumber}",
            Opds.fillSearchTemplate("/search?q={searchTerms}&page={pageNumber}", "dune"),
        )
        // A brace that is not a parameter is a character in a URL.
        assertEquals("/search/a{b", Opds.fillSearchTemplate("/search/a{b", "dune"))
    }

    @Test
    fun `catalog URL tolerates what people actually type`() {
        assertEquals("http://192.168.68.59:8768/opds", CalibreClient.catalogUrl("192.168.68.59:8768"))
        assertEquals("http://192.168.68.59:8768/opds", CalibreClient.catalogUrl("http://192.168.68.59:8768/"))
        assertEquals("http://basilnet:8768/opds", CalibreClient.catalogUrl("  basilnet:8768  "))
        // A path that is already there is somebody's deliberate starting point.
        assertEquals("https://books.example.com/opds/new", CalibreClient.catalogUrl("https://books.example.com/opds/new"))
    }

    @Test
    fun `kobo base is the token prefix and nothing after it`() {
        val expected = "http://192.168.68.59:8768/kobo/abc123"
        assertEquals(expected, ReadingState.koboBase("http://192.168.68.59:8768/kobo/abc123"))
        assertEquals(expected, ReadingState.koboBase("http://192.168.68.59:8768/kobo/abc123/v1/library/sync"))
        assertEquals(expected, ReadingState.koboBase("192.168.68.59:8768/kobo/abc123/"))
        // Not a Kobo URL at all, which is the normal state for someone who has not enabled sync.
        assertNull(ReadingState.koboBase("http://192.168.68.59:8768/opds"))
        assertNull(ReadingState.koboBase("http://192.168.68.59:8768/kobo"))
        assertNull(ReadingState.koboBase(""))
    }

    @Test
    fun `progress maps both ways and an exact word beats a rounded percent`() {
        assertEquals(0, ReadingState.percentOf(0, 1000))
        assertEquals(42, ReadingState.percentOf(420, 1000))
        assertEquals(100, ReadingState.percentOf(1000, 1000))
        // A book with no words cannot be anywhere, and must not divide by zero to find out.
        assertEquals(0, ReadingState.percentOf(10, 0))

        val ourOwn = ReadingState.Remote(percent = 42, wordIndex = 41_999)
        assertEquals(41_999, ReadingState.wordIndexOf(ourOwn, 100_000))
        // 1% of a long book is a thousand words, so a foreign bookmark is necessarily approximate.
        val foreign = ReadingState.Remote(percent = 42, wordIndex = null)
        assertEquals(42_000, ReadingState.wordIndexOf(foreign, 100_000))
        assertFalse(ReadingState.wordIndexOf(foreign, 100) >= 100)
    }

    private companion object {
        val NAVIGATION_FEED = """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Calibre-Web</title>
              <id>urn:uuid:2853dacf-ed79-42f5-8e8a-a7bb3d1ae6a2</id>
              <link rel="search" type="application/opensearchdescription+xml" href="/opds/osd"/>
              <entry>
                <title>Recently added</title>
                <id>urn:uuid:new</id>
                <content type="text">The newest 60 books</content>
                <link type="application/atom+xml;profile=opds-catalog" href="/opds/new"/>
              </entry>
              <entry>
                <title>Authors</title>
                <id>urn:uuid:authors</id>
                <link type="application/atom+xml;profile=opds-catalog" href="/opds/author"/>
              </entry>
            </feed>
        """.trimIndent().trim()

        val BOOKS_FEED = """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Recently added</title>
              <link rel="next" href="/opds/new?offset=60" type="application/atom+xml"/>
              <entry>
                <title>The Dark Forest</title>
                <id>urn:uuid:1d2c0f9e-6c1e-4b6a-9a0e-8f2d3c4b5a6f</id>
                <author><name>Cixin Liu</name></author>
                <summary>Book two of the Remembrance of Earth's Past.</summary>
                <link rel="http://opds-spec.org/image" href="/opds/cover/3" type="image/jpeg"/>
                <link rel="http://opds-spec.org/image/thumbnail" href="/opds/cover_thumb/3" type="image/jpeg"/>
                <link rel="http://opds-spec.org/acquisition" href="/opds/download/3/epub" type="application/epub+zip" length="4194304"/>
              </entry>
            </feed>
        """.trimIndent().trim()

        val PREFIXED_FEED = """
            <?xml version="1.0" encoding="UTF-8"?>
            <atom:feed xmlns:atom="http://www.w3.org/2005/Atom" xmlns:dc="http://purl.org/dc/terms/">
              <atom:title>By Author</atom:title>
              <atom:entry>
                <atom:title>Neuromancer</atom:title>
                <atom:id>urn:uuid:aa11bb22-cc33-dd44-ee55-ff6677889900</atom:id>
                <atom:author><atom:name>William Gibson</atom:name></atom:author>
                <atom:link rel="http://opds-spec.org/acquisition"
                           href="http://basilnet:8080/get/MOBI/17/library" type="application/x-mobipocket-ebook"/>
                <atom:link rel="http://opds-spec.org/acquisition"
                           href="http://basilnet:8080/get/EPUB/17/library" type="application/epub+zip"/>
              </atom:entry>
            </atom:feed>
        """.trimIndent().trim()

        val PDF_ONLY_FEED = """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Papers</title>
              <entry>
                <title>A Mathematical Theory of Communication</title>
                <id>urn:uuid:99887766-5544-3322-1100-aabbccddeeff</id>
                <link rel="http://opds-spec.org/acquisition" href="/get/PDF/9/library" type="application/pdf"/>
              </entry>
            </feed>
        """.trimIndent().trim()

        val OCTET_FEED = """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry>
                <title>Dune Messiah</title>
                <id>urn:uuid:11223344-5566-7788-99aa-bbccddeeff00</id>
                <link rel="http://opds-spec.org/acquisition" href="/files/dune_messiah.epub" type="application/octet-stream"/>
              </entry>
            </feed>
        """.trimIndent().trim()

        val OSD = """
            <?xml version="1.0" encoding="UTF-8"?>
            <OpenSearchDescription xmlns="http://a9.com/-/spec/opensearch/1.1/">
              <ShortName>Calibre-Web</ShortName>
              <Url type="application/atom+xml" template="/opds/search/{searchTerms}"/>
              <Url type="text/html" template="/search?query={searchTerms}"/>
            </OpenSearchDescription>
        """.trimIndent().trim()
    }
}
