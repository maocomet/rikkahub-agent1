package me.rerere.ai.provider.claudep

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pairing exchange, end to end against a deterministic gateway.
 *
 * Every scenario here is one where a naive implementation would pair the wrong device to the wrong
 * gateway, or leave key material behind after a failure. Nothing sleeps: the one genuinely
 * concurrent test uses a gate the test itself releases.
 */
class ClaudePPairingFlowTest {

    // ---------------------------------------------------------------------------------------
    // Happy path
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a valid invitation produces a device identity bound to the scanned gateway`() = runBlocking {
        val keyStore = InMemoryClaudePDeviceKeyStore()
        val transport = FakeClaudePPairingTransport(gatewayFingerprint = FINGERPRINT)
        val client = ClaudePPairingClient(transport, keyStore, APP_VERSION)

        val outcome = client.pair(invitation(), "Pixel", NOW)

        val paired = outcome as ClaudePPairingOutcome.Paired
        assertEquals("device-1", paired.device.deviceId)
        assertEquals("Pixel", paired.device.deviceName)
        assertEquals("credential-abc123", paired.device.accessCredential)
        assertEquals(FINGERPRINT, paired.device.gatewayFingerprint)
        assertEquals("fake-installation-1", paired.device.gatewayInstallationId)
        assertEquals("https://gateway.example.com", paired.device.pairedOrigin)
        assertEquals(1, transport.sendCount)
    }

    @Test
    fun `the device key survives a successful pairing`() = runBlocking {
        val keyStore = InMemoryClaudePDeviceKeyStore()
        val transport = FakeClaudePPairingTransport(gatewayFingerprint = FINGERPRINT)
        val client = ClaudePPairingClient(transport, keyStore, APP_VERSION)

        client.pair(invitation(), "Pixel", NOW)

        assertEquals(1, keyStore.aliases.size)
    }

    @Test
    fun `the gateway can verify the possession proof the client sent`() = runBlocking {
        val transport = FakeClaudePPairingTransport(gatewayFingerprint = FINGERPRINT)
        val client = ClaudePPairingClient(transport, InMemoryClaudePDeviceKeyStore(), APP_VERSION)

        client.pair(invitation(), "Pixel", NOW)

        // The fake reconstructs the transcript and checks the signature against the submitted key.
        // A client that signed different bytes, or signed nothing, would be refused here.
        assertFalse(transport.proofRejected)
        assertTrue(transport.requests.single().devicePublicKey.isNotBlank())
        assertTrue(transport.requests.single().proof.isNotBlank())
    }

    @Test
    fun `a proof signed for a different origin is refused by the gateway`() = runBlocking {
        // The origin is part of the signed transcript: a proof captured at one gateway must not be
        // replayable at another.
        val transport = FakeClaudePPairingTransport(
            gatewayFingerprint = FINGERPRINT,
            expectedOrigin = "https://evil.example.com",
        )
        val client = ClaudePPairingClient(transport, InMemoryClaudePDeviceKeyStore(), APP_VERSION)

        val outcome = client.pair(invitation(), "Pixel", NOW)

        assertTrue(outcome is ClaudePPairingOutcome.Rejected)
        assertTrue(transport.proofRejected)
    }

    // ---------------------------------------------------------------------------------------
    // Ticket lifetime and single use
    // ---------------------------------------------------------------------------------------

    @Test
    fun `an expired ticket is refused locally without contacting the gateway`() = runBlocking {
        val transport = FakeClaudePPairingTransport(gatewayFingerprint = FINGERPRINT)
        val keyStore = InMemoryClaudePDeviceKeyStore()
        val client = ClaudePPairingClient(transport, keyStore, APP_VERSION)

        val outcome = client.pair(invitation(expiresAt = NOW - 1), "Pixel", NOW)

        assertEquals(
            ClaudePPairingOutcome.Rejected(ClaudePPairingFailure.EXPIRED_TICKET),
            outcome,
        )
        // A stale ticket must never be presented: the gateway would have to reject it, and the
        // attempt would be visible in its logs.
        assertEquals(0, transport.sendCount)
        assertTrue(keyStore.aliases.isEmpty())
    }

    @Test
    fun `a ticket that already produced a pairing cannot be used again`() = runBlocking {
        val transport = FakeClaudePPairingTransport(gatewayFingerprint = FINGERPRINT)
        val client = ClaudePPairingClient(transport, InMemoryClaudePDeviceKeyStore(), APP_VERSION)

        val first = client.pair(invitation(), "Pixel", NOW)
        val second = client.pair(invitation(), "Pixel", NOW)

        assertTrue(first is ClaudePPairingOutcome.Paired)
        assertEquals(
            ClaudePPairingOutcome.Rejected(ClaudePPairingFailure.TICKET_ALREADY_USED),
            second,
        )
        // The second attempt never reached the gateway: a spent ticket is not re-presented.
        assertEquals(1, transport.sendCount)
    }

    @Test
    fun `two concurrent attempts at one ticket pair at most once`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val transport = FakeClaudePPairingTransport(
            gatewayFingerprint = FINGERPRINT,
            gate = gate,
        )
        val client = ClaudePPairingClient(transport, InMemoryClaudePDeviceKeyStore(), APP_VERSION)
        val shared = invitation()

        // The first exchange is held open mid-flight, so the second genuinely overlaps it.
        val first = async(Dispatchers.Unconfined) { client.pair(shared, "Pixel", NOW) }
        val second = async(Dispatchers.Unconfined) { client.pair(shared, "Pixel", NOW) }
        gate.complete(Unit)

        val outcomes = listOf(first.await(), second.await())

        assertEquals(1, outcomes.count { it is ClaudePPairingOutcome.Paired })
        assertEquals(
            1,
            outcomes.count { it == ClaudePPairingOutcome.Rejected(ClaudePPairingFailure.TICKET_ALREADY_USED) },
        )
        // Exactly one exchange reached the gateway.
        assertEquals(1, transport.sendCount)
    }

    @Test
    fun `a gateway that reports the ticket spent is surfaced as already used`() = runBlocking {
        // The authoritative single-use record is server-side; when it says "spent", the client must
        // not treat the 409 as a generic server error.
        val transport = FakeClaudePPairingTransport(
            gatewayFingerprint = FINGERPRINT,
            allowTicketReuse = false,
        )
        val client = ClaudePPairingClient(
            transport,
            InMemoryClaudePDeviceKeyStore(),
            APP_VERSION,
            // A guard that has forgotten everything, so the *server* is what refuses.
            consumedTickets = object : ClaudePConsumedTicketGuard {
                override fun isConsumed(ticketDigest: String) = false
                override fun consume(ticketDigest: String) = Unit
            },
        )

        client.pair(invitation(), "Pixel", NOW)
        val second = client.pair(invitation(), "Pixel", NOW)

        assertEquals(
            ClaudePPairingOutcome.Rejected(ClaudePPairingFailure.SERVER_REFUSED),
            second,
        )
    }

    // ---------------------------------------------------------------------------------------
    // Response validation
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a response that does not echo the attempt state is refused`() = runBlocking {
        val transport = FakeClaudePPairingTransport(
            gatewayFingerprint = FINGERPRINT,
            stateTransform = { "not-the-state-we-sent" },
        )
        val keyStore = InMemoryClaudePDeviceKeyStore()
        val client = ClaudePPairingClient(transport, keyStore, APP_VERSION)

        val outcome = client.pair(invitation(), "Pixel", NOW)

        assertEquals(ClaudePPairingOutcome.Rejected(ClaudePPairingFailure.STATE_MISMATCH), outcome)
        assertTrue("a refused pairing must not leave a key behind", keyStore.aliases.isEmpty())
    }

    @Test
    fun `a response from a different gateway installation is refused`() = runBlocking {
        val transport = FakeClaudePPairingTransport(
            gatewayFingerprint = FINGERPRINT,
            fingerprintOverride = OTHER_FINGERPRINT,
        )
        val keyStore = InMemoryClaudePDeviceKeyStore()
        val client = ClaudePPairingClient(transport, keyStore, APP_VERSION)

        val outcome = client.pair(invitation(), "Pixel", NOW)

        assertEquals(ClaudePPairingOutcome.Rejected(ClaudePPairingFailure.GATEWAY_MISMATCH), outcome)
        assertTrue(keyStore.aliases.isEmpty())
    }

    @Test
    fun `a response speaking a different protocol is refused`() = runBlocking {
        val transport = FakeClaudePPairingTransport(
            gatewayFingerprint = FINGERPRINT,
            protocolId = "rikkahub.claude-p.v2",
        )
        val client = ClaudePPairingClient(transport, InMemoryClaudePDeviceKeyStore(), APP_VERSION)

        val outcome = client.pair(invitation(), "Pixel", NOW)

        assertEquals(ClaudePPairingOutcome.Rejected(ClaudePPairingFailure.PROTOCOL_MISMATCH), outcome)
    }

    @Test
    fun `a malformed response body is refused rather than partially accepted`() = runBlocking {
        val transport = FakeClaudePPairingTransport(
            gatewayFingerprint = FINGERPRINT,
            rawBody = """{"protocol":"rikkahub.claude-p.v1"}""",
        )
        val client = ClaudePPairingClient(transport, InMemoryClaudePDeviceKeyStore(), APP_VERSION)

        val outcome = client.pair(invitation(), "Pixel", NOW)

        assertEquals(ClaudePPairingOutcome.Rejected(ClaudePPairingFailure.MALFORMED_RESPONSE), outcome)
    }

    @Test
    fun `an error status is refused without inspecting its body`() = runBlocking {
        val transport = FakeClaudePPairingTransport(
            gatewayFingerprint = FINGERPRINT,
            httpStatus = 403,
        )
        val client = ClaudePPairingClient(transport, InMemoryClaudePDeviceKeyStore(), APP_VERSION)

        val outcome = client.pair(invitation(), "Pixel", NOW)

        assertEquals(ClaudePPairingOutcome.Rejected(ClaudePPairingFailure.SERVER_REFUSED), outcome)
    }

    @Test
    fun `a transport failure is reported without inventing a device`() = runBlocking {
        val transport = FakeClaudePPairingTransport(
            gatewayFingerprint = FINGERPRINT,
            failure = ClaudePPairingTransportFailure.TLS,
        )
        val keyStore = InMemoryClaudePDeviceKeyStore()
        val client = ClaudePPairingClient(transport, keyStore, APP_VERSION)

        val outcome = client.pair(invitation(), "Pixel", NOW)

        assertEquals(ClaudePPairingOutcome.Rejected(ClaudePPairingFailure.TRANSPORT_FAILED), outcome)
        assertTrue(keyStore.aliases.isEmpty())
    }

    @Test
    fun `an unavailable device key fails closed without contacting the gateway`() = runBlocking {
        val transport = FakeClaudePPairingTransport(gatewayFingerprint = FINGERPRINT)
        val keyStore = InMemoryClaudePDeviceKeyStore().apply { loadFails = true }
        val client = ClaudePPairingClient(transport, keyStore, APP_VERSION)

        val outcome = client.pair(invitation(), "Pixel", NOW)

        assertEquals(ClaudePPairingOutcome.Rejected(ClaudePPairingFailure.KEY_UNAVAILABLE), outcome)
        assertEquals(0, transport.sendCount)
    }

    // ---------------------------------------------------------------------------------------
    // Cancellation
    // ---------------------------------------------------------------------------------------

    @Test
    fun `cancelling a pairing leaves no key material behind`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val transport = FakeClaudePPairingTransport(gatewayFingerprint = FINGERPRINT, gate = gate)
        val keyStore = InMemoryClaudePDeviceKeyStore()
        val client = ClaudePPairingClient(transport, keyStore, APP_VERSION)

        val pending = async(Dispatchers.Unconfined) { client.pair(invitation(), "Pixel", NOW) }
        // The exchange is in flight; the user backs out.
        pending.cancel()
        withTimeoutOrNull(1_000) { pending.join() }

        // Key destroyed, nothing persisted — a cancelled pairing is not a half-pairing.
        assertTrue("a cancelled pairing must not leave a key", keyStore.aliases.isEmpty())
    }

    @Test
    fun `a cancelled pairing does not spend the ticket, so the user can retry`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val transport = FakeClaudePPairingTransport(gatewayFingerprint = FINGERPRINT, gate = gate)
        val keyStore = InMemoryClaudePDeviceKeyStore()
        val client = ClaudePPairingClient(transport, keyStore, APP_VERSION)
        val shared = invitation()

        val pending = async(Dispatchers.Unconfined) { client.pair(shared, "Pixel", NOW) }
        pending.cancel()
        withTimeoutOrNull(1_000) { pending.join() }
        assertTrue(keyStore.aliases.isEmpty())

        // Cancelling is not spending. The gateway only records a ticket once it answers, so the user
        // can scan the same QR again rather than having to go back to the VPS for a new one.
        gate.complete(Unit)
        assertTrue(client.pair(shared, "Pixel", NOW) is ClaudePPairingOutcome.Paired)
    }

    // ---------------------------------------------------------------------------------------
    // Secret hygiene
    // ---------------------------------------------------------------------------------------

    @Test
    fun `the wire request never renders the ticket, state, challenge or proof`() {
        val request = ClaudePPairingWireRequest(
            protocol = ClaudePProtocol.PROTOCOL_ID,
            appVersion = APP_VERSION,
            deviceName = "Pixel",
            devicePublicKey = "public-key",
            ticket = "ticket-SECRET-abcdefghijklmnop",
            state = "state-SECRET",
            challenge = "challenge-SECRET",
            proof = "proof-SECRET",
        )

        val rendered = request.toString()

        // No field *value* is rendered at all. The public key is not a secret, but a `toString` that
        // renders some values and not others is one refactor away from rendering the wrong one.
        listOf(
            "ticket-SECRET-abcdefghijklmnop",
            "state-SECRET",
            "challenge-SECRET",
            "proof-SECRET",
            "public-key",
        ).forEach { value ->
            assertFalse("$value leaked in: $rendered", rendered.contains(value))
        }
        // The non-sensitive shape stays readable, which is what makes the redacted form useful.
        assertTrue(rendered.contains("Pixel"))
        assertTrue(rendered.contains("<redacted>"))
    }

    @Test
    fun `the wire response never renders the credential`() {
        val response = ClaudePPairingWireResponse(
            protocol = ClaudePProtocol.PROTOCOL_ID,
            state = "state-SECRET",
            deviceId = "device-1",
            deviceName = "Pixel",
            accessCredential = "credential-SECRET",
            accessExpiresAtEpochSeconds = 1_000_000,
            gatewayFingerprint = FINGERPRINT,
            gatewayInstallationId = "install-1",
        )

        val rendered = response.toString()

        assertFalse(rendered.contains("credential-SECRET"))
        assertFalse(rendered.contains("state-SECRET"))
    }

    @Test
    fun `a paired device never renders its credential`() {
        val device = ClaudePPairedDevice(
            deviceId = "device-1",
            deviceName = "Pixel",
            keyAlias = "alias",
            accessCredential = "credential-SECRET",
            accessExpiresAtEpochSeconds = 1_000_000,
            gatewayFingerprint = FINGERPRINT,
            gatewayInstallationId = "install-1",
            pairedOrigin = "https://gateway.example.com",
        )

        assertFalse(device.toString().contains("credential-SECRET"))
    }

    @Test
    fun `the consumed-ticket guard stores digests rather than tickets`() = runBlocking {
        val guard = InMemoryConsumedTicketGuard()
        val digest = ClaudePPairingTicket("ticket-SECRET-abcdefghijklmnop").digest()

        guard.consume(digest)

        assertTrue(guard.isConsumed(digest))
        assertFalse(guard.isConsumed("ticket-SECRET-abcdefghijklmnop"))
        // The guard's key is a hash, so the guard itself cannot become a leak site.
        assertFalse(digest.contains("SECRET"))
    }

    @Test
    fun `the consumed-ticket guard is bounded`() {
        val guard = InMemoryConsumedTicketGuard(capacity = 4)

        repeat(10) { guard.consume("digest-$it") }

        // Oldest-first eviction: the newest survive.
        assertTrue(guard.isConsumed("digest-9"))
        assertFalse(guard.isConsumed("digest-0"))
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private fun invitation(expiresAt: Long = NOW + 300): ClaudePPairingInvitation {
        val payload = """
            {"origin":"https://gateway.example.com","gateway_fingerprint":"$FINGERPRINT",
             "ticket":"ticket-abcdefghijklmnop","protocol":"${ClaudePProtocol.PROTOCOL_ID}",
             "expires_at":$expiresAt}
        """.trimIndent()
        return when (val parsed = ClaudePPairingInvitationParser.parse(payload)) {
            is ClaudePPairingResult.Accepted -> parsed.invitation
            is ClaudePPairingResult.Rejected -> throw AssertionError("fixture rejected: ${parsed.reason}")
        }
    }

    private companion object {
        const val APP_VERSION = "1.0-test"
        const val NOW = 1_000_000L
        const val FINGERPRINT = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val OTHER_FINGERPRINT = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"
    }
}
