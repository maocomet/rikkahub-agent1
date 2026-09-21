package me.rerere.ai.provider.claudep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Endpoint and pairing-invitation validation.
 *
 * These are the checks that stand between a scanned QR code and a socket, so every rejection below is
 * a rule about *where a device credential may be sent*. They are pure functions, which is why they
 * can be enumerated exhaustively here rather than sampled.
 */
class ClaudePEndpointTest {

    // ---------------------------------------------------------------------------------------
    // Origin acceptance and normalization
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a plain https origin is accepted and derives a wss stream url`() {
        val endpoint = accept("https://gateway.example.com")

        assertEquals("https://gateway.example.com", endpoint.origin)
        assertEquals("gateway.example.com", endpoint.host)
        assertNull(endpoint.port)
        assertEquals("wss://gateway.example.com/v1/claude-p/stream", endpoint.streamUrl())
        assertEquals("https://gateway.example.com/v1/claude-p/pair", endpoint.pairingUrl())
    }

    @Test
    fun `a trailing slash and surrounding whitespace are normalized away`() {
        assertEquals(accept("https://gateway.example.com").origin, accept("  https://gateway.example.com/  ").origin)
    }

    @Test
    fun `scheme and host case are normalized`() {
        val endpoint = accept("HTTPS://Gateway.Example.COM")

        assertEquals("https://gateway.example.com", endpoint.origin)
    }

    @Test
    fun `the default https port is dropped but an explicit one is kept`() {
        assertNull(accept("https://gateway.example.com:443").port)
        assertEquals(8443, accept("https://gateway.example.com:8443").port)
        assertEquals("wss://gateway.example.com:8443/v1/claude-p/stream", accept("https://gateway.example.com:8443").streamUrl())
    }

    @Test
    fun `an ipv6 literal keeps its brackets in the derived url`() {
        val endpoint = accept("https://[2001:db8::1]:8443")

        assertEquals("2001:db8::1", endpoint.host)
        assertEquals("wss://[2001:db8::1]:8443/v1/claude-p/stream", endpoint.streamUrl())
    }

    // ---------------------------------------------------------------------------------------
    // Rejections — every one of these is a way to move the credential somewhere unexpected
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a plaintext http origin is refused`() {
        assertEquals(ClaudePEndpointRejection.PLAINTEXT_SCHEME, reject("http://gateway.example.com"))
    }

    @Test
    fun `a plaintext ws origin is refused`() {
        assertEquals(ClaudePEndpointRejection.PLAINTEXT_SCHEME, reject("ws://gateway.example.com"))
    }

    @Test
    fun `a wss origin is refused because the QR contract carries an https origin`() {
        // Accepting both spellings would let two different strings name the same endpoint.
        assertEquals(ClaudePEndpointRejection.UNSUPPORTED_SCHEME, reject("wss://gateway.example.com"))
    }

    @Test
    fun `user info is refused`() {
        assertEquals(ClaudePEndpointRejection.USER_INFO, reject("https://user:secret@gateway.example.com"))
    }

    @Test
    fun `a query string is refused`() {
        assertEquals(ClaudePEndpointRejection.QUERY_OR_FRAGMENT, reject("https://gateway.example.com?x=1"))
    }

    @Test
    fun `a fragment is refused`() {
        assertEquals(ClaudePEndpointRejection.QUERY_OR_FRAGMENT, reject("https://gateway.example.com#frag"))
    }

    @Test
    fun `a non-root path is refused because an origin is not a base url`() {
        assertEquals(ClaudePEndpointRejection.UNEXPECTED_PATH, reject("https://gateway.example.com/other"))
    }

    @Test
    fun `a blank origin is refused`() {
        assertEquals(ClaudePEndpointRejection.MISSING, reject("   "))
        assertEquals(ClaudePEndpointRejection.MISSING, reject(null))
    }

    @Test
    fun `an origin with embedded control characters is refused`() {
        // A newline is how a value smuggles a second line into a log or a header.
        assertEquals(ClaudePEndpointRejection.MALFORMED, reject("https://gateway.example.com\nX-Injected: 1"))
    }

    @Test
    fun `an over-long origin is refused`() {
        assertEquals(ClaudePEndpointRejection.TOO_LONG, reject("https://" + "a".repeat(600) + ".example.com"))
    }

    @Test
    fun `an origin with no scheme is refused`() {
        val reason = reject("gateway.example.com")
        assertTrue(
            "expected a malformed or host rejection, got $reason",
            reason == ClaudePEndpointRejection.MALFORMED || reason == ClaudePEndpointRejection.MISSING_HOST,
        )
    }

    // ---------------------------------------------------------------------------------------
    // Pairing invitations
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a well-formed invitation parses into a validated endpoint and ticket`() {
        val invitation = acceptInvitation(invitationJson())

        assertEquals("https://gateway.example.com", invitation.endpoint.origin)
        assertEquals("ticket-abcdefghijklmnop", invitation.ticket.value)
        assertEquals(FINGERPRINT, invitation.gatewayFingerprint)
        assertEquals(1_000_600L, invitation.expiresAtEpochSeconds)
    }

    @Test
    fun `an invitation with no ticket field fails to decode rather than defaulting to empty`() {
        val payload = """
            {"origin":"https://gateway.example.com","gateway_fingerprint":"$FINGERPRINT",
             "protocol":"rikkahub.claude-p.v1","expires_at":1000600}
        """.trimIndent()

        // No default on the field, so an omitted ticket is a decode failure — not an empty string
        // that some later check might treat as valid.
        assertEquals(ClaudePPairingRejection.MALFORMED_PAYLOAD, rejectInvitation(payload))
    }

    @Test
    fun `an invitation with no protocol field fails to decode`() {
        val payload = """
            {"origin":"https://gateway.example.com","gateway_fingerprint":"$FINGERPRINT",
             "ticket":"ticket-abcdefghijklmnop","expires_at":1000600}
        """.trimIndent()

        assertEquals(ClaudePPairingRejection.MALFORMED_PAYLOAD, rejectInvitation(payload))
    }

    @Test
    fun `an invitation for a different protocol major is refused`() {
        assertEquals(
            ClaudePPairingRejection.PROTOCOL_MISMATCH,
            rejectInvitation(invitationJson(protocol = "rikkahub.claude-p.v2")),
        )
    }

    @Test
    fun `an invitation for a different protocol family is refused`() {
        assertEquals(
            ClaudePPairingRejection.PROTOCOL_MISMATCH,
            rejectInvitation(invitationJson(protocol = "someone-else.claude-p.v1")),
        )
    }

    @Test
    fun `an invitation with a plaintext origin is refused and reports why`() {
        val rejected = ClaudePPairingInvitationParser.parse(invitationJson(origin = "http://gateway.example.com"))
        val reason = (rejected as ClaudePPairingResult.Rejected)

        assertEquals(ClaudePPairingRejection.INVALID_ORIGIN, reason.reason)
        assertEquals(ClaudePEndpointRejection.PLAINTEXT_SCHEME, reason.originRejection)
    }

    @Test
    fun `an invitation with a malformed fingerprint is refused`() {
        assertEquals(
            ClaudePPairingRejection.INVALID_FINGERPRINT,
            rejectInvitation(invitationJson(fingerprint = "not a fingerprint")),
        )
    }

    @Test
    fun `an invitation with a too-short ticket is refused`() {
        assertEquals(
            ClaudePPairingRejection.MALFORMED_TICKET,
            rejectInvitation(invitationJson(ticket = "short")),
        )
    }

    @Test
    fun `an invitation with no expiry is refused because staleness could not be detected`() {
        assertEquals(
            ClaudePPairingRejection.MISSING_EXPIRY,
            rejectInvitation(invitationJson(expiresAt = 0)),
        )
    }

    @Test
    fun `an over-large pairing payload is refused before decoding`() {
        val huge = "x".repeat(ClaudePPairingInvitationParser.MAX_PAYLOAD_CHARS + 1)

        assertEquals(ClaudePPairingRejection.PAYLOAD_TOO_LARGE, rejectInvitation(huge))
    }

    @Test
    fun `an empty pairing payload is refused`() {
        assertEquals(ClaudePPairingRejection.EMPTY_PAYLOAD, rejectInvitation(""))
    }

    @Test
    fun `unknown optional fields in the payload are tolerated`() {
        val payload = """
            {"origin":"https://gateway.example.com","gateway_fingerprint":"$FINGERPRINT",
             "ticket":"ticket-abcdefghijklmnop","protocol":"rikkahub.claude-p.v1",
             "expires_at":1000600,"gateway_display_name":"Home"}
        """.trimIndent()

        // A newer gateway may add metadata; an older client must still pair.
        assertNotNull(acceptInvitation(payload))
    }

    @Test
    fun `expiry is evaluated against the supplied instant rather than the wall clock`() {
        val invitation = acceptInvitation(invitationJson(expiresAt = 1_000_000))

        assertFalse(invitation.isExpired(999_999))
        assertTrue(invitation.isExpired(1_000_000))
        assertTrue(invitation.isExpired(1_000_001))
    }

    // ---------------------------------------------------------------------------------------
    // Redaction
    // ---------------------------------------------------------------------------------------

    @Test
    fun `neither the invitation nor the ticket exposes the ticket in its string form`() {
        val invitation = acceptInvitation(invitationJson())
        val secrets = listOf(invitation.toString(), invitation.ticket.toString())

        secrets.forEach { rendered ->
            assertFalse("ticket leaked in: $rendered", rendered.contains(invitation.ticket.value))
        }
        assertTrue(invitation.toString().contains("<redacted>"))
    }

    @Test
    fun `fingerprint normalization strips separators and folds hex case`() {
        val normalized = ClaudePPairingInvitationParser.normalizeFingerprint(
            "AB:CD:EF:01:" + "23".repeat(28),
        )

        assertEquals("abcdef01" + "23".repeat(28), normalized)
    }

    @Test
    fun `fingerprint normalization does not fold base64 case`() {
        // Base64url case is significant: folding it would make two genuinely different keys compare
        // equal, which is exactly the check that protects against a swapped gateway.
        val upper = ClaudePPairingInvitationParser.normalizeFingerprint("A".repeat(43))
        val lower = ClaudePPairingInvitationParser.normalizeFingerprint("a".repeat(43))

        assertNotNull(upper)
        assertNotNull(lower)
        assertFalse(upper == lower)
    }

    @Test
    fun `fingerprint normalization rejects implausible values`() {
        assertNull(ClaudePPairingInvitationParser.normalizeFingerprint(""))
        assertNull(ClaudePPairingInvitationParser.normalizeFingerprint("two words"))
        assertNull(ClaudePPairingInvitationParser.normalizeFingerprint("a".repeat(200)))
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private fun accept(raw: String): ClaudePEndpoint =
        when (val result = ClaudePEndpoint.parse(raw)) {
            is ClaudePEndpointResult.Accepted -> result.endpoint
            is ClaudePEndpointResult.Rejected -> throw AssertionError("expected acceptance, got ${result.reason}")
        }

    private fun reject(raw: String?): ClaudePEndpointRejection =
        when (val result = ClaudePEndpoint.parse(raw)) {
            is ClaudePEndpointResult.Accepted -> throw AssertionError("expected rejection, got $result")
            is ClaudePEndpointResult.Rejected -> result.reason
        }

    private fun acceptInvitation(raw: String): ClaudePPairingInvitation =
        when (val result = ClaudePPairingInvitationParser.parse(raw)) {
            is ClaudePPairingResult.Accepted -> result.invitation
            is ClaudePPairingResult.Rejected -> throw AssertionError("expected acceptance, got ${result.reason}")
        }

    private fun rejectInvitation(raw: String?): ClaudePPairingRejection =
        when (val result = ClaudePPairingInvitationParser.parse(raw)) {
            is ClaudePPairingResult.Accepted -> throw AssertionError("expected rejection, got $result")
            is ClaudePPairingResult.Rejected -> result.reason
        }

    private fun invitationJson(
        origin: String = "https://gateway.example.com",
        fingerprint: String = FINGERPRINT,
        ticket: String = "ticket-abcdefghijklmnop",
        protocol: String = ClaudePProtocol.PROTOCOL_ID,
        expiresAt: Long = 1_000_600,
    ) = """
        {"origin":"$origin","gateway_fingerprint":"$fingerprint","ticket":"$ticket",
         "protocol":"$protocol","expires_at":$expiresAt}
    """.trimIndent()

    private companion object {
        /** A realistic 64-hex-character SHA-256 fingerprint. */
        const val FINGERPRINT = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
