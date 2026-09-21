package me.rerere.ai.provider.claudep

import java.security.MessageDigest
import java.util.Locale
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString

/**
 * The one-time ticket from a pairing QR.
 *
 * A value class with a redacting `toString` so that the ticket cannot reach a log, a crash report or
 * a `toString()` of any object that happens to hold one. `claudep/03-security-and-operations.md` §7
 * forbids recording prompts, tokens and pairing material; making the leak impossible is better than
 * remembering not to log it.
 *
 * The ticket is **not** a device credential. It is single-use, short-lived and is exchanged exactly
 * once, during pairing, for a scoped device credential. Nothing persists it.
 */
@JvmInline
value class ClaudePPairingTicket(val value: String) {
    override fun toString(): String = "ClaudePPairingTicket(<redacted:${value.length} chars>)"

    /**
     * Stable digest used by the local single-use guard.
     *
     * The guard needs to remember "this ticket was already spent" across process restarts, and the
     * only safe way to do that is to remember a hash of it rather than the ticket itself.
     */
    fun digest(): String = sha256Hex(value)
}

/**
 * A parsed, validated pairing invitation.
 *
 * Constructed only by [ClaudePPairingInvitationParser.parse], so every instance is known to carry a
 * TLS-only origin, a syntactically valid fingerprint, a well-formed ticket, a compatible protocol
 * major and a future expiry. Code downstream of this type never has to re-check those.
 *
 * `toString` is redacted wholesale: this object is the single most likely thing in the pairing path
 * to be captured by a debugger or an error reporter.
 */
class ClaudePPairingInvitation private constructor(
    /** The origin the device will be paired to. Never hand-edited — it comes from the QR. */
    val endpoint: ClaudePEndpoint,
    /** SHA-256 fingerprint of the gateway's key, normalized for comparison. */
    val gatewayFingerprint: String,
    val ticket: ClaudePPairingTicket,
    /** Unix epoch seconds after which the ticket is worthless. */
    val expiresAtEpochSeconds: Long,
) {
    /** True while the ticket may still be presented. Server-side TTL is the authority; this is the
     * client refusing to bother a gateway with something it already knows is stale. */
    fun isExpired(nowEpochSeconds: Long): Boolean = nowEpochSeconds >= expiresAtEpochSeconds

    override fun toString(): String =
        // `ClaudePEndpoint.toString` is already redacted, so interpolating it leaks nothing.
        "ClaudePPairingInvitation(endpoint=$endpoint, " +
            "gatewayFingerprint=${gatewayFingerprint.redactedRef()}, ticket=<redacted>, " +
            "expiresAtEpochSeconds=$expiresAtEpochSeconds)"

    internal companion object {
        /** Only the parser may build one, and only after every check has passed. */
        internal fun of(
            endpoint: ClaudePEndpoint,
            gatewayFingerprint: String,
            ticket: ClaudePPairingTicket,
            expiresAtEpochSeconds: Long,
        ): ClaudePPairingInvitation = ClaudePPairingInvitation(
            endpoint = endpoint,
            gatewayFingerprint = gatewayFingerprint,
            ticket = ticket,
            expiresAtEpochSeconds = expiresAtEpochSeconds,
        )
    }
}

/**
 * Parses the JSON payload of a Claude P pairing QR.
 *
 * The QR carries only public information: a normalized HTTPS origin, the gateway's public key
 * fingerprint, a short-lived one-time ticket and the protocol version
 * (`claudep/01-architecture-and-trust-boundaries.md` §4). It never carries a Claude token, and this
 * parser would have no field to put one in.
 *
 * Unknown fields are tolerated so a newer gateway can add optional metadata without breaking an
 * older client — the same forward-compatibility rule the wire protocol follows. Every field this
 * client *requires* has no default, so an omitted `ticket` or `protocol` fails decoding rather than
 * silently becoming an empty string that some later check might treat as valid.
 */
object ClaudePPairingInvitationParser {
    /**
     * A QR is a few hundred bytes. The cap exists so a hostile code cannot make the decoder allocate
     * unbounded memory before any validation has run.
     */
    const val MAX_PAYLOAD_CHARS: Int = 4096

    private const val MIN_TICKET_CHARS = 16
    private const val MAX_TICKET_CHARS = 512

    /** base64url / hex, the encodings a 128-bit-or-better ticket is serialized with. */
    private val TICKET_PATTERN = Regex("^[A-Za-z0-9_-]+$")

    private val json = ClaudePProtocol.json

    fun parse(raw: String?): ClaudePPairingResult {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            return ClaudePPairingResult.Rejected(ClaudePPairingRejection.EMPTY_PAYLOAD)
        }
        if (trimmed.length > MAX_PAYLOAD_CHARS) {
            return ClaudePPairingResult.Rejected(ClaudePPairingRejection.PAYLOAD_TOO_LARGE)
        }

        val payload = try {
            json.decodeFromString<ClaudePPairingPayload>(trimmed)
        } catch (_: Exception) {
            // The decoder's message can quote the payload, which contains the ticket. Never surface
            // it; only a bounded enum leaves this function.
            return ClaudePPairingResult.Rejected(ClaudePPairingRejection.MALFORMED_PAYLOAD)
        }

        if (!ClaudePProtocol.isCompatible(payload.protocol)) {
            return ClaudePPairingResult.Rejected(ClaudePPairingRejection.PROTOCOL_MISMATCH)
        }

        val endpoint = when (val parsed = ClaudePEndpoint.parse(payload.origin)) {
            is ClaudePEndpointResult.Rejected ->
                return ClaudePPairingResult.Rejected(
                    ClaudePPairingRejection.INVALID_ORIGIN,
                    originRejection = parsed.reason,
                )

            is ClaudePEndpointResult.Accepted -> parsed.endpoint
        }

        val fingerprint = normalizeFingerprint(payload.gatewayFingerprint)
            ?: return ClaudePPairingResult.Rejected(ClaudePPairingRejection.INVALID_FINGERPRINT)

        val ticketValue = payload.ticket.trim()
        if (ticketValue.isEmpty()) {
            return ClaudePPairingResult.Rejected(ClaudePPairingRejection.MISSING_TICKET)
        }
        if (ticketValue.length < MIN_TICKET_CHARS ||
            ticketValue.length > MAX_TICKET_CHARS ||
            !TICKET_PATTERN.matches(ticketValue)
        ) {
            return ClaudePPairingResult.Rejected(ClaudePPairingRejection.MALFORMED_TICKET)
        }

        if (payload.expiresAtEpochSeconds <= 0) {
            return ClaudePPairingResult.Rejected(ClaudePPairingRejection.MISSING_EXPIRY)
        }

        return ClaudePPairingResult.Accepted(
            ClaudePPairingInvitation.of(
                endpoint = endpoint,
                gatewayFingerprint = fingerprint,
                ticket = ClaudePPairingTicket(ticketValue),
                expiresAtEpochSeconds = payload.expiresAtEpochSeconds,
            ),
        )
    }

    /**
     * Normalizes a fingerprint so the QR's copy and the gateway's copy can be compared exactly.
     *
     * Separators (`:` and whitespace, the two ways fingerprints are conventionally grouped) are
     * stripped. Hex is lowercased because hex case is not significant; base64url is **not**, because
     * its case *is* significant and folding it would make two genuinely different keys compare
     * equal. Returns `null` for anything that is not a plausible fingerprint.
     */
    fun normalizeFingerprint(raw: String?): String? {
        val cleaned = raw?.trim()
            ?.replace(":", "")
            ?.filterNot { it.isWhitespace() }
            .orEmpty()
        if (cleaned.isEmpty() || cleaned.length > 128) return null

        val isHex = cleaned.length == 64 && cleaned.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }
        if (isHex) return cleaned.lowercase(Locale.ROOT)

        val isBase64Url = cleaned.length >= 32 && cleaned.all { it.isLetterOrDigit() || it == '-' || it == '_' }
        if (isBase64Url) return cleaned

        return null
    }
}

/**
 * Raw QR payload.
 *
 * No field has a default value on purpose. CP1-A's most valuable review finding was that a default
 * on a security-relevant field (`ClaudePEnvelope.protocol`) turned an omitted field into an accepted
 * v1 frame; the same mistake here would turn an omitted ticket into `""` and an omitted protocol into
 * whatever `isCompatible("")` happens to say.
 */
@Serializable
internal data class ClaudePPairingPayload(
    val origin: String,
    @SerialName("gateway_fingerprint") val gatewayFingerprint: String,
    val ticket: String,
    val protocol: String,
    @SerialName("expires_at") val expiresAtEpochSeconds: Long,
) {
    /** Redacted: this object holds the ticket. */
    override fun toString(): String =
        "ClaudePPairingPayload(origin=${origin.redactedRef()}, " +
            "gatewayFingerprint=${gatewayFingerprint.redactedRef()}, ticket=<redacted>, " +
            "protocol=$protocol, expiresAtEpochSeconds=$expiresAtEpochSeconds)"
}

/** Outcome of [ClaudePPairingInvitationParser.parse]. */
sealed interface ClaudePPairingResult {
    data class Accepted(val invitation: ClaudePPairingInvitation) : ClaudePPairingResult

    data class Rejected(
        val reason: ClaudePPairingRejection,
        /** Set only when [reason] is [ClaudePPairingRejection.INVALID_ORIGIN]. */
        val originRejection: ClaudePEndpointRejection? = null,
    ) : ClaudePPairingResult
}

/** Why a pairing QR was refused. Stable enum — never the payload. */
enum class ClaudePPairingRejection {
    EMPTY_PAYLOAD,
    PAYLOAD_TOO_LARGE,
    MALFORMED_PAYLOAD,

    /** A different protocol family or major. Fail closed rather than guess field meanings. */
    PROTOCOL_MISMATCH,

    /** The origin was not a TLS-only bare origin; see [ClaudePEndpointRejection]. */
    INVALID_ORIGIN,

    INVALID_FINGERPRINT,
    MISSING_TICKET,
    MALFORMED_TICKET,

    /** No usable expiry, so the client cannot refuse a stale ticket before presenting it. */
    MISSING_EXPIRY,
}

internal fun sha256Hex(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
