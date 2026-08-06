package com.lightfastread

import com.lightfastread.calibre.CalibreQr
import com.lightfastread.data.CalibreConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The setup code, from both ends.
 *
 * Worth pinning because a QR is scanned once, in a hurry, by somebody who will not read what it
 * filled in — so a field silently dropped or a password mangled by URL-encoding is a connection that
 * fails later for no visible reason.
 */
class CalibreQrTest {

    private val full = CalibreConfig(
        baseUrl = "192.168.68.59:8768",
        username = "lightbooks",
        password = "p@ss word/+&=",
        koboUrl = "http://192.168.68.59:8768/kobo/c15fb843c08c8f4ea8558419e887afec",
    )

    @Test
    fun `a code round-trips every field, punctuation and all`() {
        val decoded = CalibreQr.parse(CalibreQr.encode(full))
        assertEquals(full, decoded)
    }

    @Test
    fun `JSON is accepted too`() {
        val decoded = CalibreQr.parse(
            """{"url":"basilnet:8768","user":"gio","pass":"hunter2","kobo":"http://basilnet:8768/kobo/abc"}"""
        )
        assertEquals("basilnet:8768", decoded?.baseUrl)
        assertEquals("gio", decoded?.username)
        assertEquals("hunter2", decoded?.password)
        assertEquals("http://basilnet:8768/kobo/abc", decoded?.koboUrl)
    }

    @Test
    fun `a bare sync URL fills in the address as well`() {
        // What calibre-web itself puts on the user page. It names its own server, so there is no
        // reason to make somebody type the address in separately.
        val decoded = CalibreQr.parse("http://192.168.68.59:8768/kobo/abc123/v1/library/sync")
        assertEquals("http://192.168.68.59:8768/kobo/abc123", decoded?.koboUrl)
        assertEquals("http://192.168.68.59:8768", decoded?.baseUrl)
    }

    @Test
    fun `a bare server URL sets only the address, keeping what was there`() {
        val existing = CalibreConfig(username = "gio", password = "hunter2")
        val decoded = CalibreQr.parse("http://basilnet:8768", existing)
        assertEquals("http://basilnet:8768", decoded?.baseUrl)
        assertEquals("gio", decoded?.username)
        assertEquals("hunter2", decoded?.password)
    }

    @Test
    fun `a partial code leaves the other fields alone`() {
        val decoded = CalibreQr.parse("lightbooks://calibre?user=someone", full)
        assertEquals("someone", decoded?.username)
        assertEquals(full.baseUrl, decoded?.baseUrl)
        assertEquals(full.password, decoded?.password)
    }

    @Test
    fun `somebody else's QR code is refused, not half-applied`() {
        assertNull(CalibreQr.parse("WIFI:S=Basil;T=WPA;P=hunter2;;"))
        assertNull(CalibreQr.parse("https not a url"))
        assertNull(CalibreQr.parse(""))
        assertNull(CalibreQr.parse("lightbooks://something-else?url=x"))
        assertNull(CalibreQr.parse("{}"))
    }

    @Test
    fun `the summary names what changed`() {
        val before = CalibreConfig()
        assertEquals(
            "Filled in address, username, password and sync URL.",
            CalibreQr.summarise(before, full),
        )
        assertEquals("That code matched what was already set.", CalibreQr.summarise(full, full))
        assertEquals(
            "Filled in the sync URL.",
            CalibreQr.summarise(full.copy(koboUrl = ""), full),
        )
    }
}
