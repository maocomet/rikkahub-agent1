package me.rerere.ai.provider.claudep

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * The only OkHttp configuration Claude P is allowed to use.
 *
 * ### Why a dedicated client, and why "no logging interceptor" is not enough
 *
 * Claude P puts a device credential in an `Authorization` header and a one-time pairing ticket and
 * possession proof in a request body. The app's shared client carries a `RequestLoggingInterceptor`,
 * a debug `HttpLoggingInterceptor(Level.HEADERS)` and a shared AI interceptor.
 *
 * An earlier revision assumed `client.newBuilder()` started from a clean slate. **It does not** —
 * `newBuilder()` copies the interceptor lists, so every one of those would have run against Claude P
 * traffic and written the credential and the ticket to the log. Relying on "logging is off in
 * release" is not a fix either: the debug build is the one people paste into bug reports.
 *
 * So there are two independent guarantees here:
 *
 * 1. [newIsolated] builds a client from a **fresh** `OkHttpClient.Builder()` — not derived from the
 *    shared client — so nothing can be inherited in the first place.
 * 2. [hardened] explicitly **empties** both interceptor lists. This is defence in depth for the
 *    case where someone wires the shared client in by mistake; a wrong injection still cannot log a
 *    credential.
 *
 * Both are exercised by tests that install a recording interceptor and assert it is never invoked.
 */
object ClaudePOkHttp {
    /**
     * A client with no interceptors, no redirects and no automatic retry.
     *
     * Timeouts stay finite — deliberately *not* the shared client's 10-minute read timeout. A
     * gateway that accepts a connection and then goes silent must fail rather than pin a coroutine
     * for the rest of the session; the transport above turns that failure into a bounded reconnect.
     */
    fun newIsolated(): OkHttpClient = hardened(OkHttpClient())

    /**
     * Derives a hardened client from [source].
     *
     * Interceptors are *removed*, not merely not-added. Everything else is re-applied rather than
     * inherited so that the safety properties hold regardless of how [source] was configured.
     */
    fun hardened(source: OkHttpClient): OkHttpClient = source.newBuilder()
        .apply {
            // The point of this function. `newBuilder()` copies both lists from the source, so a
            // shared logging or AI interceptor would otherwise run on credential-bearing traffic.
            interceptors().clear()
            networkInterceptors().clear()
        }
        // A redirect would replay the device's Authorization header — and, during pairing, the
        // ticket and proof — against whatever host answered. `claudep/04-rikkahub-integration-map.md`
        // §2 requires redirects off; this is why.
        .followRedirects(false)
        .followSslRedirects(false)
        // A retry is another credentialed connection. The transport owns the reconnect budget and
        // must be the only thing deciding how many attempts are made.
        .retryOnConnectionFailure(false)
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private const val CONNECT_TIMEOUT_SECONDS = 15L
    private const val READ_TIMEOUT_SECONDS = 60L
    private const val WRITE_TIMEOUT_SECONDS = 30L
}
