package com.lightfastread.calibre

import android.util.Base64
import android.util.Log
import com.lightfastread.data.CalibreConfig
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder

/**
 * The HTTP half of Calibre support: fetch a feed, fetch a cover, fetch a book.
 *
 * `HttpURLConnection` and nothing else. The app already talks to GitHub and Open Library this way
 * and adding a client library for three request shapes would cost more APK than the whole feature.
 *
 * Every method here blocks and is meant to be called from `Dispatchers.IO`. Failures are
 * [IOException] with a message written to be *shown to a person* — "401 — check the username and
 * password" rather than a stack trace — because on a phone with no logcat attached that message is
 * the entire diagnosis.
 */
class CalibreClient(private val config: CalibreConfig) {

    /** Resolved once per client and remembered: the OSD is a second round trip nobody needs twice. */
    private var searchTemplate: String? = null

    fun rootUrl(): String = catalogUrl(config.baseUrl)

    fun root(): OpdsFeed = feed(rootUrl())

    fun feed(url: String): OpdsFeed {
        val body = text(url)
        if (!body.contains("<feed", ignoreCase = true) && !body.contains("<entry", ignoreCase = true)) {
            // calibre-web answers an unconfigured library with an HTML redirect to /admin/dbconfig,
            // and a wrong port answers with somebody else's home page. Both are 200s, so the status
            // code cannot be what decides this.
            throw IOException(
                "That URL answered with a web page, not an OPDS feed. Check the port, and that the " +
                    "server has a library configured."
            )
        }
        return Opds.parse(body, url)
    }

    /**
     * Search the catalogue.
     *
     * The template comes from the feed's own OpenSearch description when it advertises one. When it
     * does not — older COPS, a proxy that strips the link — `/opds/search/<terms>` is the path both
     * calibre-server and calibre-web use anyway, so guessing it is better than refusing to search.
     */
    fun search(query: String): OpdsFeed = feed(searchUrl(query))

    /**
     * The URL a search resolves to, separately from running it.
     *
     * Exposed because the LIBRARY screen keeps a trail of feed URLs and RELOAD re-fetches the last
     * one. A search result has to be a real URL in that trail — a placeholder would come back as
     * "not a URL this phone can open" the first time somebody reloaded a search.
     */
    fun searchUrl(query: String): String {
        val template = searchTemplate ?: resolveSearchTemplate().also { searchTemplate = it }
        val terms = URLEncoder.encode(query.trim(), "UTF-8")
        return if (template != null) {
            Opds.fillSearchTemplate(template, terms)
        } else {
            "${catalogUrl(config.baseUrl).trimEnd('/')}/search/$terms"
        }
    }

    /**
     * Ask the server what it is, in one request.
     *
     * Used by the TEST row in Settings, where the useful answer is either the library's own name or
     * the reason it did not answer — not a boolean.
     */
    fun probe(): String {
        val feed = root()
        val counted = feed.entries.size
        val name = feed.title.ifBlank { "Calibre" }
        return when {
            feed.publications.isNotEmpty() -> "$name — ${feed.publications.size} books"
            counted > 0 -> "$name — $counted sections"
            else -> "$name — connected, but the catalogue is empty"
        }
    }

    fun text(url: String): String = get(url, MAX_FEED_BYTES).toString(Charsets.UTF_8)

    fun bytes(url: String, limit: Long = MAX_TEXT_BOOK_BYTES): ByteArray = get(url, limit)

    /**
     * Download to a file, without ever holding the whole thing.
     *
     * A comic volume is 100-250 MB. Read into a `ByteArray` on a phone with a couple of hundred
     * megabytes of heap, that is not a slow download but an OutOfMemoryError - and a zip has to be
     * seekable to be read entry by entry anyway, which a stream is not.
     */
    fun download(url: String, target: File, limit: Long = Long.MAX_VALUE): Long {
        var current = url
        var hops = 0
        while (true) {
            val connection = open(current)
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: throw IOException("The server redirected without saying where.")
                    if (++hops > MAX_REDIRECTS) throw IOException("Too many redirects.")
                    current = Opds.resolve(current, location)
                    continue
                }
                if (code != HttpURLConnection.HTTP_OK) throw IOException(explain(code))
                var total = 0L
                connection.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > limit) {
                                // Reached only when the server never declared a size; a declared one
                                // is checked before the transfer starts. See data/Storage.kt.
                                throw IOException(
                                    "Ran out of room after ${total / (1024 * 1024)} MB — " +
                                        "the phone has ${limit / (1024 * 1024)} MB to spare."
                                )
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                }
                return total
            } catch (e: Exception) {
                target.delete()
                throw e
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun resolveSearchTemplate(): String? {
        val osdHref = runCatching { root().searchHref }.getOrNull() ?: return null
        return runCatching { Opds.parseSearchTemplate(text(osdHref), osdHref) }.getOrNull()
    }

    /**
     * One GET, with basic auth, following redirects by hand.
     *
     * `instanceFollowRedirects` is not used on purpose. It drops the Authorization header on the
     * hop — a redirect to a login page then reads as an empty 200 — and it refuses http→https
     * outright, which is exactly the redirect a server behind a tunnel issues.
     */
    private fun get(url: String, limit: Long): ByteArray {
        var current = url
        var hops = 0
        while (true) {
            val connection = open(current)
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: throw IOException("The server redirected without saying where.")
                    if (++hops > MAX_REDIRECTS) throw IOException("Too many redirects.")
                    current = Opds.resolve(current, location)
                    continue
                }
                if (code != HttpURLConnection.HTTP_OK) throw IOException(explain(code))
                return connection.inputStream.use { stream ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > limit) {
                            throw IOException("That file is larger than ${limit / (1024 * 1024)} MB.")
                        }
                        out.write(buffer, 0, read)
                    }
                    out.toByteArray()
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun open(url: String): HttpURLConnection {
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            // The cause is named, not swallowed. "That is not a URL this phone can open" was true
            // and useless: it covers a missing scheme, a host Android refuses, and a cleartext
            // policy rejection, and on a phone with no logcat attached the message is the whole
            // diagnosis. Both the URL and what the platform actually said now reach the screen.
            throw IOException("Cannot open “$url” — ${e.javaClass.simpleName}: ${e.message}", e)
        }
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", "${Opds.TYPE_FEED},*/*")
        connection.setRequestProperty("User-Agent", USER_AGENT)
        authHeader()?.let { connection.setRequestProperty("Authorization", it) }
        return connection
    }

    private fun authHeader(): String? {
        if (config.username.isBlank()) return null
        val raw = "${config.username}:${config.password}".toByteArray(Charsets.UTF_8)
        return "Basic " + Base64.encodeToString(raw, Base64.NO_WRAP)
    }

    private fun explain(code: Int): String = when (code) {
        // Two different problems wearing the same status code, and the difference is the whole fix.
        // calibre-web logs the failure as `OPDS Login failed for user ""` when no credentials were
        // sent at all — which is what an address typed in without an account, or a scan that was
        // never saved, looks like from the server's side.
        HttpURLConnection.HTTP_UNAUTHORIZED -> if (config.username.isBlank()) {
            "401 — this server wants an account, and no username is set. Settings → Calibre."
        } else {
            "401 — the server rejected that username and password."
        }
        HttpURLConnection.HTTP_FORBIDDEN -> "403 — that account cannot read the catalogue."
        HttpURLConnection.HTTP_NOT_FOUND -> "404 — no OPDS catalogue at that address."
        429 -> "429 — the server is rate-limiting. Try again in a minute."
        in 500..599 -> "$code — the server errored. Check its own log."
        else -> "$code from the server."
    }

    companion object {
        private const val TAG = "CalibreClient"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_REDIRECTS = 5
        private const val MAX_FEED_BYTES = 8L * 1024 * 1024
        /**
         * A cap for things read into memory — a feed, a cover, a text book's bytes. Not for
         * downloads, which stream to disk and are limited by free space instead.
         */
        private const val MAX_TEXT_BOOK_BYTES = 256L * 1024 * 1024
        private const val USER_AGENT = "LightBooks/1.7 (github.com/gi-os/LightFastread)"

        /**
         * Turn whatever was typed into Settings into a catalogue URL.
         *
         * People type `192.168.68.59:8768`, or paste `http://basilnet:8768/`, or paste the OPDS URL
         * itself. All three should work, so a missing scheme becomes http (this is a LAN address by
         * design) and an empty path gains `/opds`. A path that is already there is left alone —
         * `/opds/new` is a perfectly good place to start from.
         */
        fun catalogUrl(raw: String): String {
            var text = raw.trim()
            if (text.isEmpty()) throw IOException("No Calibre server set. Settings → Calibre.")
            if (!text.startsWith("http://") && !text.startsWith("https://")) text = "http://$text"
            text = text.trimEnd('/')
            val path = runCatching { URI(text).path.orEmpty() }.getOrElse { "" }
            return if (path.isEmpty() || path == "/") "$text/opds" else text
        }

        /** Somewhere to log a failure that is not worth showing anybody. */
        fun logQuietly(message: String, e: Throwable? = null) {
            Log.w(TAG, message, e)
        }
    }
}
