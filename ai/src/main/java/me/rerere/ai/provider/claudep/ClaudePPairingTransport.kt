package me.rerere.ai.provider.claudep

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The HTTPS leg of pairing.
 *
 * Pairing is the one point where the device *gains* an identity, so it is deliberately the narrowest
 * interface in the feature: take a request body, return a response body or a bounded failure. There
 * is no generic "make a request" surface here, because a generic one is what would let a caller send
 * a ticket somewhere the user never approved.
 *
 * Implementations must use TLS only and must not follow redirects — a redirect would carry the
 * one-time ticket, and the device's public key and signature, to a different host.
 */
fun interface ClaudePPairingTransport {
    suspend fun send(request: ClaudePPairingWireRequest): ClaudePPairingTransportResult
}

/**
 * Wire shape of the pairing request.
 *
 * Separate from the domain objects on purpose: this is what a server sees, so a change to it is a
 * protocol change, while a change to [ClaudePPairingClient]'s inputs is an implementation detail.
 * `ticket`, `state` and `challenge` are redacted in `toString` — this object holds two of the three
 * values in the whole feature that must never be logged.
 */
@Serializable
data class ClaudePPairingWireRequest(
    @SerialName("protocol") val protocol: String,
    @SerialName("app_version") val appVersion: String,
    @SerialName("device_name") val deviceName: String,
    /** base64url, X.509 SubjectPublicKeyInfo. */
    @SerialName("device_public_key") val devicePublicKey: String,
    @SerialName("ticket") val ticket: String,
    /** Per-attempt random value the response must echo. */
    @SerialName("state") val state: String,
    /** Per-attempt random value covered by [proof]. */
    val challenge: String,
    /** base64url signature over [ClaudePPairingTranscript]. */
    val proof: String,
) {
    override fun toString(): String =
        "ClaudePPairingWireRequest(protocol=$protocol, appVersion=$appVersion, " +
            "deviceName=$deviceName, devicePublicKey=<public>, ticket=<redacted>, " +
            "state=<redacted>, challenge=<redacted>, proof=<redacted>)"
}

/**
 * Wire shape of the pairing response.
 *
 * Every field is required — no defaults — so a truncated or hostile response fails to decode rather
 * than arriving with an empty credential that some later check might accept.
 */
@Serializable
data class ClaudePPairingWireResponse(
    @SerialName("protocol") val protocol: String,
    /** Must equal the request's `state`. This is the pairing equivalent of an OAuth `state` check. */
    @SerialName("state") val state: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_name") val deviceName: String,
    @SerialName("access_credential") val accessCredential: String,
    @SerialName("access_expires_at") val accessExpiresAtEpochSeconds: Long,
    @SerialName("gateway_fingerprint") val gatewayFingerprint: String,
    @SerialName("gateway_installation_id") val gatewayInstallationId: String,
) {
    override fun toString(): String =
        "ClaudePPairingWireResponse(protocol=$protocol, state=<redacted>, " +
            "deviceId=${deviceId.redactedRef()}, deviceName=$deviceName, " +
            "accessCredential=<redacted>, accessExpiresAtEpochSeconds=$accessExpiresAtEpochSeconds, " +
            "gatewayFingerprint=${gatewayFingerprint.redactedRef()}, " +
            "gatewayInstallationId=${gatewayInstallationId.redactedRef()})"
}

/** Outcome of one pairing HTTP call. */
sealed interface ClaudePPairingTransportResult {
    /** A well-formed HTTP response body, still untrusted and still to be validated. */
    data class Responded(val httpStatus: Int, val body: String) : ClaudePPairingTransportResult

    data class Failed(val reason: ClaudePPairingTransportFailure) : ClaudePPairingTransportResult
}

/** Why the pairing call did not complete. Stable enum — never a network-supplied string. */
enum class ClaudePPairingTransportFailure {
    /** DNS, refused, or the socket died. */
    NETWORK,

    /** TLS could not be established, or the certificate was rejected. */
    TLS,

    /** The exchange exceeded its budget. */
    TIMEOUT,

    /** The response was larger than the client is willing to read. */
    RESPONSE_TOO_LARGE,
}

/**
 * Transcript the device signs to prove possession of its private key.
 *
 * Binding all five values is what stops the proof from being replayed somewhere else:
 *
 * - without the **origin**, a proof captured at one gateway could be replayed at an attacker's;
 * - without the **ticket**, two pairing attempts could be swapped;
 * - without the **public key**, a proof could be presented for a different key;
 * - without **state** and **challenge**, a captured proof would remain valid for its whole lifetime.
 *
 * Fields are length-prefixed, for the same reason as [ClaudePHandshakeTranscript]: raw concatenation
 * would let `("ab","c")` and `("a","bc")` sign the same bytes, so a signature would not actually
 * cover the values it claims to.
 */
object ClaudePPairingTranscript {
    private const val DOMAIN = "rikkahub-claude-p-pairing-v1"

    fun build(
        origin: String,
        ticket: String,
        devicePublicKeyBase64Url: String,
        state: String,
        challenge: String,
        appVersion: String,
    ): ByteArray {
        fun field(value: String) = "${value.length}:$value"
        return buildString {
            append(field(DOMAIN))
            append(field(origin))
            append(field(ticket))
            append(field(devicePublicKeyBase64Url))
            append(field(state))
            append(field(challenge))
            append(field(appVersion))
        }.toByteArray(Charsets.UTF_8)
    }
}
