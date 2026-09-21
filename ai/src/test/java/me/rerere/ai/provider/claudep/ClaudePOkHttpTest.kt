package me.rerere.ai.provider.claudep

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Transport isolation.
 *
 * Claude P puts a device credential in an `Authorization` header and, during pairing, a one-time
 * ticket and possession proof in a request body. The app's shared client carries a request-logging
 * interceptor, a debug header-logging interceptor and a shared AI interceptor.
 *
 * An earlier revision relied on `client.newBuilder()` and claimed nothing was inherited. That was
 * wrong — `newBuilder()` copies the interceptor lists — so these tests exist to make the isolation a
 * property of the code rather than of a comment.
 *
 * The assertions are "the interceptor lists are empty". That is complete rather than merely
 * suggestive: OkHttp runs *exactly* the interceptors in those two lists for every call, so an empty
 * pair means no interceptor of any kind can observe a request, whatever the source client held.
 */
class ClaudePOkHttpTest {

    @Test
    fun `an isolated client carries no interceptors at all`() {
        val client = ClaudePOkHttp.newIsolated()

        assertEquals(emptyList<Interceptor>(), client.interceptors)
        assertEquals(emptyList<Interceptor>(), client.networkInterceptors)
    }

    @Test
    fun `hardening strips application interceptors copied from the source client`() {
        val source = OkHttpClient.Builder()
            .addInterceptor(recordingInterceptor(mutableListOf()))
            .build()

        val hardened = ClaudePOkHttp.hardened(source)

        // The regression this guards: `newBuilder()` alone would have carried the recorder through.
        assertEquals(1, source.interceptors.size)
        assertEquals(emptyList<Interceptor>(), hardened.interceptors)
    }

    @Test
    fun `hardening strips network interceptors copied from the source client`() {
        val source = OkHttpClient.Builder()
            .addNetworkInterceptor(recordingInterceptor(mutableListOf()))
            .build()

        val hardened = ClaudePOkHttp.hardened(source)

        assertEquals(1, source.networkInterceptors.size)
        assertEquals(emptyList<Interceptor>(), hardened.networkInterceptors)
    }

    @Test
    fun `hardening turns off redirects, ssl redirects and automatic retry`() {
        val source = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()

        val hardened = ClaudePOkHttp.hardened(source)

        // A redirect would replay the Authorization header — and during pairing the ticket and proof
        // — against whatever host answered. A retry is another credentialed connection attempt.
        assertFalse(hardened.followRedirects)
        assertFalse(hardened.followSslRedirects)
        assertFalse(hardened.retryOnConnectionFailure)
    }

    @Test
    fun `hardening keeps timeouts finite rather than inheriting an unbounded read`() {
        val source = OkHttpClient.Builder()
            .readTimeout(java.time.Duration.ofMinutes(10))
            .build()

        val hardened = ClaudePOkHttp.hardened(source)

        assertTrue("connect timeout must be finite", hardened.connectTimeoutMillis in 1..60_000)
        assertTrue("read timeout must be finite", hardened.readTimeoutMillis in 1..120_000)
    }

    @Test
    fun `a recorder on the shared client never observes a hardened client's credential`() {
        val seen = mutableListOf<String>()
        val source = OkHttpClient.Builder()
            .addInterceptor(recordingInterceptor(seen))
            .addNetworkInterceptor(recordingInterceptor(seen))
            .build()

        val hardened = ClaudePOkHttp.hardened(source)
        val request = Request.Builder()
            .url("https://127.0.0.1:1/v1/claude-p/pair")
            .header("Authorization", "Bearer credential-SECRET")
            .header("Content-Type", "application/json")
            .post("""{"ticket":"ticket-SECRET","proof":"proof-SECRET"}""".asJsonBody())
            .build()

        // The call is expected to fail: nothing is listening on that loopback port. The assertion is
        // not about the outcome, only that the shared client's recorders were never invoked — so a
        // failure here is irrelevant, and generating it touches no external network.
        runCatching { hardened.newCall(request).execute().close() }

        assertTrue("a shared interceptor observed Claude P traffic: $seen", seen.isEmpty())
    }

    private fun recordingInterceptor(sink: MutableList<String>) = Interceptor { chain ->
        val request = chain.request()
        sink += request.url.toString()
        sink += request.header("Authorization").orEmpty()
        sink += request.body?.toString().orEmpty()
        chain.proceed(request)
    }
}

private fun String.asJsonBody() = toRequestBody("application/json".toMediaType())
