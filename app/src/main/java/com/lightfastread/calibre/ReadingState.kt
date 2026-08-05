package com.lightfastread.calibre

import com.lightfastread.data.CalibreConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * Reading position, pushed to and pulled from calibre-web.
 *
 * There is no "progress" field in OPDS, so this rides calibre-web's **Kobo sync** API — the one a
 * real Kobo e-reader uses. The endpoint is
 *
 *     PUT {base}/kobo/{token}/v1/library/{book_uuid}/state
 *
 * and the token in the URL is the entire authentication: calibre-web looks the user up from it, and
 * the route is `@csrf.exempt`, so no cookie, no basic auth, no header. Which also means **the token
 * is a credential in a URL** — it belongs on a LAN, which is where this feature is pointed.
 *
 * Three things about the wire format are worth knowing before changing anything here, all of them
 * from calibre-web's `cps/kobo.py`:
 *
 * 1. `CurrentBookmark`, `Statistics` and `StatusInfo` are all **required keys**, but each may be
 *    `null` to mean "leave this alone". A missing key is a `KeyError` and a flat 400, so this sends
 *    all three every time and nulls the one it has nothing to say about.
 * 2. An **unknown book is not an error**. `if not book or not book.data` returns `200 {}`, so a
 *    successful-looking push can mean the uuid is not in the library at all. Success is therefore
 *    `RequestResult == "Success"` in the body, never the status code.
 * 3. A GET returns a **one-element array**, not an object.
 *
 * Everything here blocks; call it from `Dispatchers.IO`.
 */
object ReadingState {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** What a book's position is, as far as the server knows. */
    data class Remote(
        val percent: Int,
        /**
         * The word index this app itself last wrote, when the server is echoing our own bookmark
         * back. Null when the position came from anything else, in which case [percent] is all
         * there is and the word index has to be derived from it.
         */
        val wordIndex: Int?,
    )

    /**
     * Push a position. Returns true only when calibre-web said it stored it.
     *
     * Location is filled in with our own word index so a later pull is exact rather than rounded
     * through a percentage: 1% of a 120,000-word book is twelve hundred words, which is several
     * minutes of reading to lose on every sync.
     */
    fun push(config: CalibreConfig, uuid: String, percent: Int, wordIndex: Int): Boolean {
        val base = koboBase(config.koboUrl) ?: return false
        val clamped = percent.coerceIn(0, 100)
        val body = """
            {"ReadingStates":[{"EntitlementId":"$uuid",
            "CurrentBookmark":{"ProgressPercent":$clamped,"ContentSourceProgressPercent":$clamped,
            "Location":{"Value":"$LOCATION_PREFIX$wordIndex","Type":"$LOCATION_TYPE","Source":"$LOCATION_SOURCE"}},
            "Statistics":null,
            "StatusInfo":{"Status":"${statusFor(clamped)}"}}]}
        """.trimIndent().replace("\n", "")

        val response = request("$base/v1/library/$uuid/state", "PUT", body)
        val ok = runCatching {
            json.parseToJsonElement(response).jsonObject["RequestResult"]?.jsonPrimitive?.content == "Success"
        }.getOrDefault(false)
        if (!ok) {
            // The overwhelmingly likely cause, and the one worth naming: calibre-web answers 200
            // with an empty object when the uuid is not a book it knows.
            CalibreClient.logQuietly("calibre-web did not store the position for $uuid: $response")
        }
        return ok
    }

    /** The server's idea of where this book is, or null if it has none or could not be asked. */
    fun pull(config: CalibreConfig, uuid: String): Remote? {
        val base = koboBase(config.koboUrl) ?: return null
        val response = runCatching { request("$base/v1/library/$uuid/state", "GET", null) }
            .getOrElse { return null }
        return runCatching {
            val element = json.parseToJsonElement(response)
            // A one-element array, per kobo.py. Tolerate a bare object in case that ever changes.
            val state = (element as? JsonArray)?.firstOrNull()?.jsonObject
                ?: (element as? JsonObject)
                ?: return null
            val bookmark = state["CurrentBookmark"]?.jsonObject ?: return null
            val percent = bookmark["ProgressPercent"]?.jsonPrimitive?.content?.toFloat()?.toInt()
                ?: return null
            val location = bookmark["Location"]?.jsonObject
            val source = location?.get("Source")?.jsonPrimitive?.content
            val value = location?.get("Value")?.jsonPrimitive?.content
            Remote(
                percent = percent.coerceIn(0, 100),
                wordIndex = if (source == LOCATION_SOURCE && value != null) {
                    value.removePrefix(LOCATION_PREFIX).toIntOrNull()
                } else {
                    null
                },
            )
        }.getOrNull()
    }

    private fun request(url: String, method: String, body: String?): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "LightBooks")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            if (code !in 200..299) {
                throw IOException(
                    when (code) {
                        401 -> "401 — that Kobo sync token is not one calibre-web knows."
                        404 -> "404 — Kobo sync is switched off on the server."
                        429 -> "429 — rate-limited; the next sync will pick this up."
                        else -> "$code from calibre-web."
                    }
                )
            }
            text
        } finally {
            connection.disconnect()
        }
    }

    /**
     * `Reading` for anything in the middle, because that is what makes calibre-web count a start.
     *
     * `ReadyToRead` and `Finished` are the only other strings it accepts — anything else is a
     * `KeyError` on its side and a 400 on ours.
     */
    private fun statusFor(percent: Int): String = when {
        percent >= 100 -> "Finished"
        percent <= 0 -> "ReadyToRead"
        else -> "Reading"
    }

    /**
     * The `/kobo/<token>` prefix, from whatever was pasted into Settings.
     *
     * calibre-web shows this as a full URL with a path after the token, so anything past the token
     * segment is dropped. Returns null when the string does not look like a Kobo sync URL at all,
     * which is the normal state for someone who has not turned sync on — callers treat null as
     * "not configured", never as an error.
     */
    fun koboBase(raw: String): String? {
        var text = raw.trim()
        if (text.isEmpty()) return null
        if (!text.startsWith("http://") && !text.startsWith("https://")) text = "http://$text"
        val uri = runCatching { URI(text) }.getOrNull() ?: return null
        val segments = uri.path.orEmpty().split('/').filter { it.isNotBlank() }
        val koboAt = segments.indexOf("kobo")
        if (koboAt < 0 || koboAt + 1 >= segments.size) return null
        val prefix = segments.take(koboAt + 2).joinToString("/")
        return "${uri.scheme}://${uri.authority}/$prefix"
    }

    /** Whether a token URL is present and shaped like one. Used to caption the Settings row. */
    fun configured(config: CalibreConfig): Boolean =
        config.syncProgress && koboBase(config.koboUrl) != null

    private const val TIMEOUT_MS = 15_000

    /**
     * `Location` is free-form on calibre-web's side — it stores the three strings verbatim — so this
     * marks our own bookmarks and carries the exact word index inside them. A bookmark from any
     * other reader is left to be interpreted as a percentage.
     */
    private const val LOCATION_SOURCE = "LightBooks"
    private const val LOCATION_TYPE = "LightBooksWord"
    private const val LOCATION_PREFIX = "word:"

    /** Kept next to the wire format it belongs to, and pinned by a unit test. */
    fun percentOf(wordIndex: Int, totalWords: Int): Int =
        if (totalWords <= 0) 0 else ((wordIndex.toLong() * 100) / totalWords).toInt().coerceIn(0, 100)

    fun wordIndexOf(remote: Remote, totalWords: Int): Int {
        remote.wordIndex?.let { return it.coerceIn(0, maxOf(totalWords - 1, 0)) }
        if (totalWords <= 0) return 0
        return ((remote.percent.toLong() * totalWords) / 100).toInt()
            .coerceIn(0, maxOf(totalWords - 1, 0))
    }
}
