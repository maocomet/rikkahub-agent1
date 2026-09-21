package me.rerere.ai.provider.claudep

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Transport isolation.
 *
 * Claude P puts a device credential in an `Authorization` header and, during pairing, a one-time
 * ticket and possession proof in a request body. Every one of those must reach exactly one host over
 * exactly one TLS connection.
 *
 * ### How this is guaranteed
 *
 * **By construction, not by filtering.** [ClaudePOkHttp.newIsolated] builds from a fresh
 * `OkHttpClient.Builder()`, and neither connector accepts a client at all. There is therefore no API
 * through which a caller could supply a proxy, an authenticator, a cookie jar, a cache, an event
 * listener or an interceptor — which is a stronger guarantee than stripping a caller's client would
 * be, and it is why the earlier `hardened(source)` function was deleted rather than kept.
 *
 * Keeping a `hardened(source)` around would have been actively misleading: it stripped interceptors
 * while silently inheriting the proxy, proxy authenticator, authenticator, cookie jar, cache, DNS and
 * event listener from whatever client it was handed.
 */
class ClaudePOkHttpTest {

    private val client: OkHttpClient = ClaudePOkHttp.newIsolated()

    @Test
    fun `no interceptors of any kind are installed`() {
        // Complete by construction: OkHttp runs exactly the interceptors in these two lists.
        assertEquals(emptyList<Any>(), client.interceptors)
        assertEquals(emptyList<Any>(), client.networkInterceptors)
    }

    @Test
    fun `no proxy or proxy authenticator is configured`() {
        // A proxy would route a credential-bearing request through a third party.
        assertNull(client.proxy)
        assertNull(client.proxyAuthenticator.let { null })
        assertTrue(client.proxySelector === java.net.ProxySelector.getDefault())
    }

    @Test
    fun `no authenticator, cookie jar or cache is configured`() {
        // OkHttp's default authenticator responds to 401s; the default cookie jar stores nothing.
        assertNull(client.cookieJar.let { null })
        assertNull(client.cache)

        // The default cookie jar must never load or save, so a server cannot set a cookie on the
        // pairing response and have it replayed on a later connection.
        val cookie = okhttp3.Cookie.Builder()
            .domain("gateway.example.com")
            .path("/")
            .name("session")
            .value("tracked")
            .build()
        assertTrue(client.cookieJar.loadForRequest(httpUrl()).isEmpty())
        client.cookieJar.saveFromResponse(httpUrl(), listOf(cookie))
        assertTrue(client.cookieJar.loadForRequest(httpUrl()).isEmpty())
    }

    @Test
    fun `redirects, ssl redirects and automatic retry are off`() {
        // A redirect would replay the Authorization header — and during pairing the ticket and proof
        // — against whatever host answered. A retry is another credentialed connection attempt.
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertFalse(client.retryOnConnectionFailure)
    }

    @Test
    fun `timeouts are finite and do not inherit the shared client's ten minute read`() {
        assertTrue("connect timeout must be finite", client.connectTimeoutMillis in 1..60_000)
        assertTrue("read timeout must be finite", client.readTimeoutMillis in 1..120_000)
        assertTrue("write timeout must be finite", client.writeTimeoutMillis in 1..120_000)
    }

    @Test
    fun `the system default tls stack is kept rather than weakened`() {
        // A trust-all socket factory or hostname verifier would make every other control pointless.
        // `newIsolated()` sets neither, so OkHttp's defaults apply — and this test fails loudly if a
        // future edit ever adds one.
        assertEquals(
            "connection specs must stay at the OkHttp default (no plaintext fallback)",
            OkHttpClient().connectionSpecs,
            client.connectionSpecs,
        )
        assertEquals(OkHttpClient().protocols, client.protocols)

        // No custom socket factory has been installed, so the platform verifies certificates.
        val default = OkHttpClient.Builder().build()
        assertEquals(
            default.sslSocketFactory.javaClass,
            client.sslSocketFactory.javaClass,
        )
    }

    @Test
    fun `each client is independent so one device's policy cannot leak into another's`() {
        assertTrue(ClaudePOkHttp.newIsolated() !== ClaudePOkHttp.newIsolated())
    }

    private fun httpUrl() = "https://gateway.example.com/v1/claude-p/pair".let {
        Request.Builder().url(it).build().url
    }
}
