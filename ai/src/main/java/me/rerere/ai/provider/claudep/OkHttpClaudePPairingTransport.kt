package me.rerere.ai.provider.claudep

import java.io.IOException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * The real pairing call, over HTTPS.
 *
 * Hardening mirrors the WebSocket connector, and for the same reason — this request carries the
 * one-time ticket and the device's possession proof:
 *
 * - **Redirects are disabled.** A 30x would forward both to whatever host answered, which is the
 *   classic way a "helpful" gateway turns into a credential exfiltration.
 * - **Automatic retry is disabled.** A retry re-presents the ticket; the gateway's single-use rule
 *   would then reject the *first* legitimate attempt's successor, and the retry itself would be a
 *   second consumption attempt against a one-shot secret.
 * - **The response is read with a hard size cap**, so a hostile endpoint cannot make the app
 *   allocate arbitrarily.
 * - No logging interceptor: the body contains the ticket and the proof.
 */
class OkHttpClaudePPairingTransport(
    /** Endpoint the request is posted to. Built from the validated pairing invitation. */
    private val endpoint: ClaudePEndpoint,
) : ClaudePPairingTransport {

    /**
     * Built here, never accepted from a caller. This request carries the one-time ticket and the
     * possession proof, so an inherited proxy, cookie jar or interceptor is a disclosure path.
     */
    private val client: OkHttpClient = ClaudePOkHttp.newIsolated()

    override suspend fun send(
        request: ClaudePPairingWireRequest,
    ): ClaudePPairingTransportResult = withContext(Dispatchers.IO) {
        val url = endpoint.pairingUrl()
        // The last check before a ticket leaves the device. A plaintext URL here would put the
        // one-time secret and the proof on the wire in the clear.
        if (!url.startsWith(HTTPS_SCHEME)) {
            return@withContext ClaudePPairingTransportResult.Failed(
                ClaudePPairingTransportFailure.NETWORK,
            )
        }

        val body = ClaudePProtocol.json.encodeToString(request)
            .toRequestBody(JSON_MEDIA_TYPE)

        val httpRequest = Request.Builder()
            .url(url)
            .post(body)
            .header(HEADER_ACCEPT, JSON_MEDIA_TYPE.toString())
            .build()

        try {
            client.newCall(httpRequest).execute().use { response ->
                readBounded(response)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            ClaudePPairingTransportResult.Failed(t.toPairingFailure())
        }
    }

    /**
     * Reads the status and a size-capped body.
     *
     * The cap is enforced *while* reading, not after: checking a fully-buffered body would defeat
     * the point.
     */
    private fun readBounded(response: Response): ClaudePPairingTransportResult {
        val source = response.body.source()
        val buffer = okio.Buffer()
        val read = try {
            source.read(buffer, MAX_RESPONSE_BYTES.toLong())
        } catch (_: IOException) {
            return ClaudePPairingTransportResult.Failed(ClaudePPairingTransportFailure.NETWORK)
        }

        if (read >= MAX_RESPONSE_BYTES && !source.exhausted()) {
            return ClaudePPairingTransportResult.Failed(
                ClaudePPairingTransportFailure.RESPONSE_TOO_LARGE,
            )
        }

        return ClaudePPairingTransportResult.Responded(
            httpStatus = response.code,
            body = buffer.readUtf8(),
        )
    }

    private companion object {
        const val HTTPS_SCHEME = "https://"
        const val HEADER_ACCEPT = "Accept"

        /** A pairing response is a handful of short fields; anything larger is not one. */
        const val MAX_RESPONSE_BYTES = 64 * 1024

        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

/** Maps a throwable onto the bounded pairing failure vocabulary. Never carries its message. */
internal fun Throwable.toPairingFailure(): ClaudePPairingTransportFailure = when (this) {
    is SSLException, is CertificateException -> ClaudePPairingTransportFailure.TLS
    is java.net.SocketTimeoutException -> ClaudePPairingTransportFailure.TIMEOUT
    is IOException -> ClaudePPairingTransportFailure.NETWORK
    else -> ClaudePPairingTransportFailure.NETWORK
}
