package me.rerere.ai.provider.claudep

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * The only OkHttp configuration Claude P is allowed to use.
 *
 * ### Why the client is built here and never accepted from outside
 *
 * Claude P puts a device credential in an `Authorization` header and, during pairing, a one-time
 * ticket and possession proof in a request body. Every one of those must reach exactly one host over
 * exactly one TLS connection.
 *
 * Two earlier revisions got this wrong in escalating ways:
 *
 * 1. It reused the app's shared client. That client carries a request-logging interceptor, a debug
 *    header-logging interceptor and a shared AI interceptor — and `newBuilder()` **copies**
 *    interceptor lists, so all of them ran against credential-bearing traffic.
 * 2. It derived from an injected client and stripped the interceptors. Better, but `newBuilder()`
 *    also carries over the **proxy, proxy authenticator, authenticator, cookie jar, cache, DNS,
 *    connection specs and event listener factory** from the source. A hostile or merely mis-wired
 *    source client could still have routed the credential through a proxy or attached a cookie.
 *
 * So there is no longer any way to supply a client. [newIsolated] builds one from a fresh
 * `OkHttpClient.Builder()`, and both connectors call it themselves. The "malicious source client"
 * test case is not passed because there is no API to pass one through — which is a stronger
 * guarantee than any amount of stripping.
 *
 * ### What is deliberately *not* changed
 *
 * The system default TLS stack, hostname verification and certificate validation. A trust-all
 * `SSLSocketFactory` or `HostnameVerifier` would make every other control here pointless, and
 * `claudep/00-scope-and-product-contract.md` §4 forbids plaintext or weakened production transport.
 */
object ClaudePOkHttp {
    /**
     * A client with no interceptors, no redirects, no automatic retry and no inherited transport
     * policy.
     *
     * Timeouts are finite and deliberately *not* the shared client's 10-minute read timeout: a
     * gateway that accepts a connection and then goes silent must fail rather than pin a coroutine
     * for the rest of the session.
     */
    fun newIsolated(): OkHttpClient = OkHttpClient.Builder()
        // A redirect would replay the device's Authorization header — and, during pairing, the
        // ticket and the proof — against whatever host answered. `claudep/04-rikkahub-integration-map.md`
        // §2 requires redirects off; this is why.
        .followRedirects(false)
        .followSslRedirects(false)
        // A retry is another credentialed connection. The transport owns the reconnect budget and
        // must be the only thing deciding how many attempts are made.
        .retryOnConnectionFailure(false)
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        // No proxy, no proxyAuthenticator, no authenticator, no cookieJar, no cache, no
        // eventListenerFactory, no DNS override and no connectionSpecs are set, so every one of them
        // keeps the OkHttp default: direct connection, no cookies, no disk cache, system TLS.
        .build()

    private const val CONNECT_TIMEOUT_SECONDS = 15L

    /**
     * Long enough for a slow pairing exchange, short enough that a silent gateway cannot hold a
     * coroutine for the session.
     */
    private const val READ_TIMEOUT_SECONDS = 60L

    private const val WRITE_TIMEOUT_SECONDS = 30L
}
