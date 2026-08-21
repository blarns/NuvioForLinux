package com.nuvio.app.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * These are not style assertions — [redactSourceUrl] is the control that keeps working Real-Debrid
 * and TorBox API keys out of logs users paste into public bug reports. Every case here is a real
 * URL shape from an installed addon or a resolved stream, with the secret replaced.
 */
private const val KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="

class UrlRedactionTest {

    @Test
    fun `keeps the host and drops a credential path segment`() {
        assertEquals(
            "https://addon.example.com/…/manifest.json",
            redactSourceUrl("https://addon.example.com/$KEY/manifest.json"),
        )
    }

    @Test
    fun `drops the query string whole`() {
        val redacted = redactSourceUrl("https://addon.example.com/stream/series/tt1.json?apikey=$KEY")
        assertFalse(redacted.contains(KEY), "query string leaked the key: $redacted")
        assertTrue(redacted.startsWith("https://addon.example.com/"), redacted)
    }

    @Test
    fun `drops userinfo credentials from the authority`() {
        val redacted = redactSourceUrl("https://user:hunter2@addon.example.com/x/file.mkv")
        assertEquals("https://addon.example.com/…/file.mkv", redacted)
        assertFalse(redacted.contains("hunter2"), redacted)
    }

    @Test
    fun `a query before any slash cannot smuggle itself into the host`() {
        val redacted = redactSourceUrl("https://addon.example.com?token=$KEY")
        assertEquals("https://addon.example.com/…", redacted)
    }

    @Test
    fun `keeps a media file name because it is the useful part of a playback log`() {
        assertEquals(
            "https://cdn.example.com/…/Some.Show.S01E01.1080p.mkv",
            redactSourceUrl("https://cdn.example.com/dl/$KEY/Some.Show.S01E01.1080p.mkv"),
        )
    }

    @Test
    fun `does not mistake a base64 blob for a file name`() {
        // A padded base64 segment can contain a dot; it is a credential, not a file.
        val redacted = redactSourceUrl("https://cdn.example.com/dl/abc.def$KEY")
        assertEquals("https://cdn.example.com/…", redacted)
    }

    @Test
    fun `magnets are shortened but local paths are left alone`() {
        val magnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Some.Show&tr=udp://tracker"
        val redacted = redactSourceUrl(magnet)
        assertTrue(redacted.endsWith("…"), redacted)
        assertTrue(redacted.length < magnet.length, redacted)
        assertEquals("/home/user/Videos/film.mkv", redactSourceUrl("/home/user/Videos/film.mkv"))
    }

    @Test
    fun `never throws on the malformed input that is exactly when logging matters`() {
        assertEquals("<none>", redactSourceUrl(null))
        assertEquals("<none>", redactSourceUrl("   "))
        assertEquals("<unparseable url>", redactSourceUrl("https://"))
        assertEquals("<unparseable url>", redactSourceUrl("https:///path"))
    }
}
