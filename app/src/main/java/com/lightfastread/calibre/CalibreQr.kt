package com.lightfastread.calibre

import com.lightfastread.data.CalibreConfig
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * The four Calibre settings, as one QR code.
 *
 * Typing a server address, a username, a password and a Kobo token URL into a Light Phone III means
 * four fields of borrowed-keyboard hunting for about 150 characters, most of which are a hex token
 * that is unreadable and unguessable by design. A camera and a code on a laptop screen is two
 * seconds, and it cannot typo.
 *
 * The payload is a URI so it stays human-readable and so a scanner that isn't this app shows
 * something meaningful rather than a wall of base64:
 *
 *     lightbooks://calibre?url=192.168.68.59:8768&user=lightbooks&pass=…&kobo=http://…/kobo/<token>
 *
 * Two other shapes are accepted, because they are what somebody will actually point the camera at:
 * a JSON object with the same four keys, and a bare URL — a plain server address, or a Kobo sync URL
 * copied straight out of calibre-web, which carries its own origin and so fills in both fields.
 *
 * No Android in here on purpose: this is the part worth unit-testing, and `android.net.Uri` cannot
 * be constructed in a JVM test.
 */
object CalibreQr {

    const val SCHEME = "lightbooks"
    const val HOST = "calibre"

    /**
     * Read a scanned string. Null when it is not something this app can use — a random QR on a
     * poster, a wifi config, a URL to a website — which the scanner reports rather than silently
     * overwriting somebody's settings with.
     */
    fun parse(raw: String, existing: CalibreConfig = CalibreConfig()): CalibreConfig? {
        val text = raw.trim()
        if (text.isEmpty()) return null

        if (text.startsWith("$SCHEME://", ignoreCase = true)) {
            val query = text.substringAfter('?', "")
            if (!text.startsWith("$SCHEME://$HOST", ignoreCase = true)) return null
            val fields = query.split('&')
                .mapNotNull { pair ->
                    if ('=' !in pair) return@mapNotNull null
                    val key = pair.substringBefore('=').lowercase()
                    val value = decode(pair.substringAfter('='))
                    key to value
                }
                .toMap()
            if (fields.isEmpty()) return null
            return existing.copy(
                baseUrl = fields["url"] ?: fields["server"] ?: existing.baseUrl,
                username = fields["user"] ?: existing.username,
                password = fields["pass"] ?: existing.password,
                koboUrl = fields["kobo"] ?: existing.koboUrl,
            )
        }

        if (text.startsWith("{")) {
            val url = jsonString(text, "url") ?: jsonString(text, "server")
            val user = jsonString(text, "user")
            val pass = jsonString(text, "pass")
            val kobo = jsonString(text, "kobo")
            if (url == null && user == null && pass == null && kobo == null) return null
            return existing.copy(
                baseUrl = url ?: existing.baseUrl,
                username = user ?: existing.username,
                password = pass ?: existing.password,
                koboUrl = kobo ?: existing.koboUrl,
            )
        }

        if (text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true)) {
            val kobo = ReadingState.koboBase(text)
            return if (kobo != null) {
                // A sync URL names its own server, so one scan of the thing calibre-web puts on the
                // user page fills in the address too.
                existing.copy(koboUrl = kobo, baseUrl = existing.baseUrl.ifBlank { origin(text) })
            } else {
                existing.copy(baseUrl = text)
            }
        }

        return null
    }

    /** Build the payload. Used by the QR generator, and by the round-trip test. */
    fun encode(config: CalibreConfig): String = buildString {
        append("$SCHEME://$HOST?")
        val parts = ArrayList<String>(4)
        if (config.baseUrl.isNotBlank()) parts.add("url=" + encodeComponent(config.baseUrl))
        if (config.username.isNotBlank()) parts.add("user=" + encodeComponent(config.username))
        if (config.password.isNotBlank()) parts.add("pass=" + encodeComponent(config.password))
        if (config.koboUrl.isNotBlank()) parts.add("kobo=" + encodeComponent(config.koboUrl))
        append(parts.joinToString("&"))
    }

    /** What a scan changed, for the line of feedback the settings page shows afterwards. */
    fun summarise(before: CalibreConfig, after: CalibreConfig): String {
        val changed = ArrayList<String>(4)
        if (before.baseUrl != after.baseUrl) changed.add("address")
        if (before.username != after.username) changed.add("username")
        if (before.password != after.password) changed.add("password")
        if (before.koboUrl != after.koboUrl) changed.add("sync URL")
        return when {
            changed.isEmpty() -> "That code matched what was already set."
            changed.size == 1 -> "Filled in the ${changed[0]}."
            else -> "Filled in " + changed.dropLast(1).joinToString(", ") + " and " + changed.last() + "."
        }
    }

    private fun origin(url: String): String =
        runCatching {
            val uri = java.net.URI(url)
            "${uri.scheme}://${uri.authority}"
        }.getOrDefault(url)

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private fun encodeComponent(value: String): String =
        runCatching { URLEncoder.encode(value, "UTF-8") }.getOrDefault(value)

    /**
     * One string field out of flat JSON, without a parser.
     *
     * Deliberately not a `kotlinx.serialization` decode: the input is whatever a stranger's QR code
     * contained, so this has to tolerate trailing commas, unknown keys and outright malformed
     * documents by returning null rather than throwing. A regex for `"key": "value"` does that and
     * nothing else.
     */
    private fun jsonString(json: String, key: String): String? =
        Regex("\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"", RegexOption.IGNORE_CASE)
            .find(json)
            ?.groupValues
            ?.get(1)
            ?.replace("\\\"", "\"")
            ?.replace("\\\\", "\\")
            ?.takeIf { it.isNotBlank() }
}
