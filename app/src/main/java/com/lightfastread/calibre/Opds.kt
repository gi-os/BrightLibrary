package com.lightfastread.calibre

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory

/**
 * OPDS, the catalogue format every Calibre server already speaks.
 *
 * `calibre-server`, calibre-web and COPS all publish the same thing at `/opds`: an Atom feed of
 * either *navigation* entries (drill down — by author, by series, newest) or *publication* entries
 * (a book, with links to download it and to its cover). That is the whole of the integration.
 * Reading a Calibre library folder directly would mean SQLite over SAF for less benefit, on a phone
 * that cannot see the folder in the first place.
 *
 * Parsed with DOM rather than `XmlPullParser` for one deliberate reason: `android.util.Xml` does not
 * exist in a JVM unit test, and this is the part worth testing. `DocumentBuilderFactory` is in both.
 * Feeds are a few hundred kilobytes at most — servers page them — so holding one in memory is not
 * the cost it would be for a streaming parser.
 *
 * Namespace awareness is deliberately **off**. Feeds in the wild are inconsistent about whether
 * Atom is the default namespace or carries a prefix, and a namespace-aware parse then needs the
 * right URI for each of Atom, Dublin Core and OPDS or it silently finds nothing. Comparing local
 * names after the colon works on every feed either way.
 */
object Opds {

    const val REL_ACQUISITION = "http://opds-spec.org/acquisition"
    const val REL_IMAGE = "http://opds-spec.org/image"
    const val REL_THUMBNAIL = "http://opds-spec.org/image/thumbnail"

    /** Pre-1.0 OPDS, still emitted by older COPS builds. */
    const val REL_COVER_OLD = "http://opds-spec.org/cover"
    const val REL_THUMBNAIL_OLD = "http://opds-spec.org/thumbnail"

    const val TYPE_FEED = "application/atom+xml"
    const val TYPE_OSD = "application/opensearchdescription+xml"

    /**
     * Download formats this app can actually read, best first.
     *
     * EPUB before the Kindle formats because the EPUB parser keeps chapter structure; AZW3 last
     * because it is the least likely to carry usable metadata. A format not on this list is not
     * offered at all — a PDF would import as a wall of ligature soup, and a CBZ as nothing.
     */
    val READABLE: List<Pair<String, String>> = listOf(
        "application/epub+zip" to "epub",
        "application/x-mobipocket-ebook" to "mobi",
        "application/vnd.amazon.ebook" to "azw3",
        "application/x-mobi8-ebook" to "azw3",
        // Comics, added in v1.9. Last in the list so a volume published as both an image EPUB and a
        // CBZ downloads as the EPUB, whose spine gives a reliable page order — a CBZ has only its
        // file names to go on.
        "application/x-cbz" to "cbz",
        "application/vnd.comicbook+zip" to "cbz",
    )

    fun parse(xml: String, baseUrl: String): OpdsFeed {
        val doc = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            // A feed is data, not a document with an authority to consult. Fetching an external
            // DTD would be a request to somebody else's server in the middle of parsing ours.
            runCatching {
                setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        }.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))

        val root = doc.documentElement ?: return OpdsFeed("", emptyList(), null, null)
        val feedTitle = root.childText("title")
        val feedLinks = root.childElements("link").map { it.toLink(baseUrl) }

        val entries = root.childElements("entry").map { entry ->
            val id = entry.childText("id")
            OpdsEntry(
                id = id,
                title = entry.childText("title").collapseSpace(),
                author = entry.childElements("author")
                    .mapNotNull { it.childText("name").takeIf { name -> name.isNotBlank() } }
                    .joinToString(", ")
                    .collapseSpace(),
                summary = entry.childText("summary").ifBlank { entry.childText("content") }
                    .collapseSpace(),
                uuid = id.substringAfter("urn:uuid:", "").trim().ifBlank { null },
                links = entry.childElements("link").map { it.toLink(baseUrl) },
            )
        }

        return OpdsFeed(
            title = feedTitle.collapseSpace(),
            entries = entries,
            nextHref = feedLinks.firstOrNull { it.rel == "next" }?.href,
            searchHref = feedLinks.firstOrNull { it.rel == "search" }?.href,
        )
    }

    /**
     * The search URL template out of an OpenSearch description.
     *
     * Search is the one part of OPDS that is not a link to follow but a URL to build, and the
     * template lives in a separate document the feed only points at. Returns the template with
     * `{searchTerms}` still in it; [CalibreClient] substitutes.
     */
    fun parseSearchTemplate(xml: String, baseUrl: String): String? {
        val doc = runCatching {
            DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
                .newDocumentBuilder()
                .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        }.getOrNull() ?: return null

        val candidates = doc.documentElement?.childElements("Url").orEmpty()
        // An OSD advertises several result types — an Atom feed, sometimes HTML. Only the feed is
        // any use here, but take a lone template of unknown type rather than nothing at all.
        val url = candidates.firstOrNull { it.getAttribute("type").startsWith(TYPE_FEED) }
            ?: candidates.singleOrNull()
            ?: return null
        val template = url.getAttribute("template").trim()
        if (template.isBlank()) return null
        return resolve(baseUrl, template)
    }

    /**
     * Resolve a possibly relative href against the URL it was found at.
     *
     * Every server does this differently — calibre-web emits `/opds/...`, calibre emits
     * `/opds/navcatalog/...` and some proxies emit fully qualified URLs — so nothing downstream
     * should have to care. Unencoded spaces and braces appear in real hrefs (a search template, an
     * author name in a path) and make `URI` throw, so those are escaped first and a naive join is
     * the last resort.
     */
    fun resolve(baseUrl: String, href: String): String {
        if (href.isBlank()) return baseUrl
        if (href.startsWith("http://") || href.startsWith("https://")) return href
        val safe = href.replace(" ", "%20")
        runCatching { return URI(baseUrl).resolve(safe).toString() }
        val origin = runCatching {
            val uri = URI(baseUrl)
            "${uri.scheme}://${uri.authority}"
        }.getOrNull() ?: baseUrl.trimEnd('/')
        return if (safe.startsWith("/")) "$origin$safe" else "$origin/$safe"
    }

    private fun Element.toLink(baseUrl: String) = OpdsLink(
        href = resolve(baseUrl, getAttribute("href").trim()),
        rel = getAttribute("rel").trim(),
        type = getAttribute("type").trim(),
        title = getAttribute("title").trim().ifBlank { null },
    )

    /** Direct children with this local name, prefix ignored. */
    private fun Element.childElements(local: String): List<Element> {
        val out = ArrayList<Element>()
        val kids = childNodes
        for (i in 0 until kids.length) {
            val node = kids.item(i)
            if (node.nodeType == Node.ELEMENT_NODE &&
                node.nodeName.substringAfterLast(':') == local
            ) {
                out.add(node as Element)
            }
        }
        return out
    }

    private fun Element.childText(local: String): String =
        childElements(local).firstOrNull()?.textContent?.trim().orEmpty()

    private fun String.collapseSpace(): String = replace(Regex("""\s+"""), " ").trim()
}

data class OpdsLink(
    val href: String,
    val rel: String,
    val type: String,
    val title: String? = null,
)

data class OpdsEntry(
    val id: String,
    val title: String,
    val author: String,
    val summary: String,
    val uuid: String?,
    val links: List<OpdsLink>,
) {
    /** Every way this entry can be downloaded, in whatever order the server listed them. */
    val acquisitions: List<OpdsLink>
        get() = links.filter { it.rel.startsWith(Opds.REL_ACQUISITION) }

    /**
     * A publication is an entry you can download; anything else is a shelf to open.
     *
     * Some servers put an acquisition link on a navigation entry (a "download all" of a series),
     * which is why this asks about links rather than about the presence of a nested feed.
     */
    val isPublication: Boolean get() = acquisitions.isNotEmpty()

    val feedHref: String?
        get() = links.firstOrNull {
            it.type.startsWith(Opds.TYPE_FEED) && !it.rel.startsWith(Opds.REL_ACQUISITION)
        }?.href

    val coverHref: String?
        get() = links.firstOrNull { it.rel == Opds.REL_IMAGE || it.rel == Opds.REL_COVER_OLD }?.href

    /** The small one if the server offers it, the full cover if not. */
    val thumbnailHref: String?
        get() = links.firstOrNull {
            it.rel == Opds.REL_THUMBNAIL || it.rel == Opds.REL_THUMBNAIL_OLD
        }?.href ?: coverHref

    /**
     * The best download for this app, as a link and the file extension to parse it as.
     *
     * The extension comes from the MIME type rather than the URL: calibre-web serves
     * `/opds/download/12/epub` with no dot in it, and plenty of servers label everything
     * `application/octet-stream`, in which case the URL is all there is to go on.
     */
    fun bestDownload(): Pair<OpdsLink, String>? {
        for ((mime, ext) in Opds.READABLE) {
            acquisitions.firstOrNull { it.type.startsWith(mime) }?.let { return it to ext }
        }
        for (link in acquisitions) {
            // calibre-web serves `/opds/download/160/cbz/` — a trailing slash and no dot anywhere,
            // so the last *segment* is the format rather than a file extension.
            val tail = link.href.trimEnd('/').substringAfterLast('/').lowercase()
            val ext = if ('.' in tail) tail.substringAfterLast('.') else tail
            if (ext in READABLE_EXTENSIONS) return link to ext
        }
        return null
    }

    /** What the row shows on the right: EPUB, MOBI, or nothing this app can open. */
    fun formatLabel(): String? = bestDownload()?.second?.uppercase()

    private companion object {
        val READABLE_EXTENSIONS = setOf("epub", "mobi", "azw", "azw3", "cbz")
    }
}

data class OpdsFeed(
    val title: String,
    val entries: List<OpdsEntry>,
    /** The next page of a paged feed, already absolute. Null on the last page. */
    val nextHref: String?,
    /** Where the OpenSearch description lives, if the feed advertises one. */
    val searchHref: String?,
) {
    val publications: List<OpdsEntry> get() = entries.filter { it.isPublication }
    val navigation: List<OpdsEntry> get() = entries.filter { !it.isPublication }
}
