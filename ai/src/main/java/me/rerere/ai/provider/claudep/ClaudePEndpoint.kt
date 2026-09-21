package me.rerere.ai.provider.claudep

import java.net.URI
import java.util.Locale

/**
 * A validated, normalized gateway endpoint — the single origin this device is allowed to reach.
 *
 * Every instance is produced by [parse], which is the one place an untrusted string (a pairing QR
 * payload, a setting read back off disk) becomes something the transport will open a socket to.
 * Each rule below rejects a way to make a device send its signed handshake — or its access
 * credential — somewhere the user never agreed to:
 *
 * - plaintext `ws://` / `http://`: this channel carries credentials in both directions;
 * - `user:password@host`: user-info is invisible in most UIs, so it silently redirects trust and
 *   tends to be the first thing a logging layer captures;
 * - a path, query or fragment: a "base URL with a path" is how the credential gets moved to a host
 *   an attacker controls while the user reads a familiar-looking origin;
 * - a missing or unparseable host.
 *
 * `wss://` is deliberately **not** accepted as an input spelling, even though it is the scheme of
 * the URL we ultimately connect to. `claudep/01-architecture-and-trust-boundaries.md` §4 defines the
 * pairing QR as carrying a normalized **HTTPS origin**, and [streamUrl] derives the `wss://` form
 * from it. Accepting both spellings would let two different strings name the same endpoint, which
 * is exactly the ambiguity a single normalized type exists to remove.
 */
data class ClaudePEndpoint private constructor(
    /** Normalized origin, e.g. `https://gateway.example.com`. Never carries a trailing slash. */
    val origin: String,
    /** Lowercase host, without brackets for IPv6 literals. */
    val host: String,
    /** Explicit non-default port, or `null` when the scheme default (443) applies. */
    val port: Int?,
) {
    /** Host and optional port, formatted for display *and* for URL construction. */
    val authority: String = buildString {
        if (host.contains(':')) append('[').append(host).append(']') else append(host)
        port?.let { append(':').append(it) }
    }

    /**
     * The only URL this device may open a WebSocket to.
     *
     * Derived rather than stored so a persisted endpoint can never carry a stream URL that disagrees
     * with its origin.
     */
    fun streamUrl(): String = "$WSS_SCHEME$authority$STREAM_PATH"

    /** HTTPS endpoint the pairing request is posted to. */
    fun pairingUrl(): String = "$origin$PAIR_PATH"

    /** Redacted: an origin is not secret, but it identifies the user's own infrastructure. */
    override fun toString(): String = "ClaudePEndpoint(origin=${origin.redactedRef()}, port=$port)"

    companion object {
        /** `claudep/02-wire-protocol-v1.md` §1. */
        const val STREAM_PATH: String = "/v1/claude-p/stream"

        /**
         * Pairing RPC path.
         *
         * **The server side of this endpoint does not exist in this repository.** CP1-B implements the
         * Android client only, against the pairing contract in `claudep/01-architecture-and-trust-boundaries.md`
         * §4. The path lives here as a single named constant so the client has exactly one place to
         * change when the deployment ADR fixes the server route, rather than a literal scattered
         * across the pairing code.
         */
        const val PAIR_PATH: String = "/v1/claude-p/pair"

        private const val WSS_SCHEME = "wss://"
        private const val HTTPS_SCHEME = "https"
        private const val DEFAULT_PORT = 443
        private const val MAX_LENGTH = 512

        /**
         * Parses and normalizes an origin.
         *
         * Never throws: an untrusted string is data, and every outcome other than
         * [ClaudePEndpointResult.Accepted] means "refuse to connect", not "try anyway".
         */
        fun parse(raw: String?): ClaudePEndpointResult {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isEmpty()) {
                return ClaudePEndpointResult.Rejected(ClaudePEndpointRejection.MISSING)
            }
            if (trimmed.length > MAX_LENGTH) {
                return ClaudePEndpointResult.Rejected(ClaudePEndpointRejection.TOO_LONG)
            }
            // Control characters are how a value smuggles a second line into a log or a header.
            if (trimmed.any { it.code < 0x20 || it.code == 0x7f }) {
                return ClaudePEndpointResult.Rejected(ClaudePEndpointRejection.MALFORMED)
            }

            val uri = try {
                URI(trimmed)
            } catch (_: Exception) {
                return ClaudePEndpointResult.Rejected(ClaudePEndpointRejection.MALFORMED)
            }

            val scheme = uri.scheme?.lowercase(Locale.ROOT)
                ?: return ClaudePEndpointResult.Rejected(ClaudePEndpointRejection.MALFORMED)
            if (scheme == "http" || scheme == "ws") {
                return ClaudePEndpointResult.Rejected(ClaudePEndpointRejection.PLAINTEXT_SCHEME)
            }
            if (scheme != HTTPS_SCHEME) {
                return ClaudePEndpointResult.Rejected(ClaudePEndpointRejection.UNSUPPORTED_SCHEME)
            }

            if (uri.rawUserInfo != null) {
                return ClaudePEndpointResult.Rejected(ClaudePEndpointRejection.USER_INFO)
            }
            if (uri.rawQuery != null || uri.rawFragment != null) {
                return ClaudePEndpointResult.Rejected(ClaudePEndpointRejection.QUERY_OR_FRAGMENT)
            }

            val rawPath = uri.rawPath.orEmpty()
            if (rawPath.isNotEmpty() && rawPath != "/") {
                return ClaudePEndpointResult.Rejected(ClaudePEndpointRejection.UNEXPECTED_PATH)
            }

            val rawHost = uri.host?.lowercase(Locale.ROOT)
                ?: return ClaudePEndpointResult.Rejected(ClaudePEndpointRejection.MISSING_HOST)
            // `URI.host` returns IPv6 literals bracketed on some JDKs and bare on others; strip so
            // the stored host has exactly one form.
            val host = rawHost.removePrefix("[").removeSuffix("]")
            if (host.isBlank()) {
                return ClaudePEndpointResult.Rejected(ClaudePEndpointRejection.MISSING_HOST)
            }

            val explicitPort = uri.port
            if (explicitPort != -1 && (explicitPort <= 0 || explicitPort > 65535)) {
                return ClaudePEndpointResult.Rejected(ClaudePEndpointRejection.INVALID_PORT)
            }
            val port = explicitPort.takeIf { it != -1 && it != DEFAULT_PORT }

            val authority = buildString {
                if (host.contains(':')) append('[').append(host).append(']') else append(host)
                port?.let { append(':').append(it) }
            }

            return ClaudePEndpointResult.Accepted(
                ClaudePEndpoint(
                    origin = "$HTTPS_SCHEME://$authority",
                    host = host,
                    port = port,
                ),
            )
        }
    }
}

/** Outcome of [ClaudePEndpoint.parse]. Anything but [Accepted] must block the connection. */
sealed interface ClaudePEndpointResult {
    data class Accepted(val endpoint: ClaudePEndpoint) : ClaudePEndpointResult

    data class Rejected(val reason: ClaudePEndpointRejection) : ClaudePEndpointResult
}

/** Why an origin was refused. Stable enum — never the offending string. */
enum class ClaudePEndpointRejection {
    MISSING,
    TOO_LONG,
    MALFORMED,

    /** `http://` or `ws://`. The one rejection that is a hard security rule, not a format nit. */
    PLAINTEXT_SCHEME,

    /** Anything that is not `https`, e.g. `wss://` or `ftp://`. */
    UNSUPPORTED_SCHEME,

    /** A `user:password@host` component. */
    USER_INFO,

    MISSING_HOST,
    QUERY_OR_FRAGMENT,

    /** A path other than `/`. An origin is not a base URL. */
    UNEXPECTED_PATH,
    INVALID_PORT,
}
