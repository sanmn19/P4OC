package dev.blazelight.p4oc.core.network

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerUrlTest {

    @Test
    fun `authenticated origin requires exact scheme host and port`() {
        val configured = "https://server.example:8443".toHttpUrl()

        assertTrue("https://server.example:8443/session".toHttpUrl().hasSameOrigin(configured))
        val webSocketUrl = Request.Builder().url("wss://server.example:8443/pty/id/connect").build().url
        assertTrue(webSocketUrl.hasSameOrigin(configured))
        assertFalse("https://other.example:8443/session".toHttpUrl().hasSameOrigin(configured))
        assertFalse("http://server.example:8443/session".toHttpUrl().hasSameOrigin(configured))
        assertFalse("https://server.example/session".toHttpUrl().hasSameOrigin(configured))
    }

    @Test
    fun `bare host defaults to http without persisted port`() {
        assertEquals("http://example.com", ServerUrl.normalizeConnectUrl("example.com"))
    }

    @Test
    fun `explicit port is preserved`() {
        assertEquals("http://example.com:1234", ServerUrl.normalizeConnectUrl("example.com:1234"))
    }

    @Test
    fun `explicit http without port preserves absent port`() {
        assertEquals("http://example.com", ServerUrl.normalizeConnectUrl("http://example.com"))
    }

    @Test
    fun `explicit https without port preserves absent port`() {
        assertEquals("https://example.com", ServerUrl.normalizeConnectUrl("https://example.com"))
    }

    @Test
    fun `path is preserved for connect url`() {
        assertEquals(
            "http://example.com/foo/bar",
            ServerUrl.normalizeConnectUrl("example.com/foo/bar"),
        )
    }

    @Test
    fun `query and fragment are stripped but path is preserved`() {
        assertEquals(
            "http://example.com/foo/bar",
            ServerUrl.normalizeConnectUrl("example.com/foo/bar?x=1#frag"),
        )
    }

    @Test
    fun `host is lowercased`() {
        assertEquals("https://example.com", ServerUrl.normalizeConnectUrl("https://EXAMPLE.COM"))
    }

    @Test
    fun `unsupported scheme is rejected`() {
        assertNull(ServerUrl.normalizeConnectUrl("ftp://example.com"))
    }

    @Test
    fun `blank is rejected`() {
        assertNull(ServerUrl.normalizeConnectUrl("   "))
    }

    @Test
    fun `ipv6 is bracketed and zone id is stripped with path preserved`() {
        assertEquals(
            "http://[2001:db8::1]/foo",
            ServerUrl.normalizeConnectUrl("http://[2001:db8::1%wlan0]/foo"),
        )
    }

    @Test
    fun `equivalent root forms share endpoint key`() {
        val bare = ServerUrl.endpointKey("Example.com")
        val full = ServerUrl.endpointKey("http://example.com:4096/?x=1#frag")

        assertEquals(full, bare)
    }

    @Test
    fun `different paths do not share endpoint key`() {
        assertNotEquals(
            ServerUrl.endpointKey("http://example.com:4096/a"),
            ServerUrl.endpointKey("http://example.com:4096/b"),
        )
    }

    @Test
    fun `same path with query and fragment shares endpoint key`() {
        assertEquals(
            ServerUrl.endpointKey("http://example.com:4096/a?x=1#frag"),
            ServerUrl.endpointKey("http://example.com:4096/a"),
        )
    }
}
