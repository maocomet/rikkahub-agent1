package me.rerere.ai.provider.claudep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings-screen status derivation.
 *
 * The property under test is a safety property, not a cosmetic one: **a device that cannot
 * authenticate must never be shown as usable.** Because the derivation is a pure function, every
 * combination that could produce a misleading screen is enumerable rather than sampled.
 */
class ClaudePUiStatusTest {

    @Test
    fun `an unpaired device is never reported as online`() {
        ClaudePConnectionState.entries.forEach { connection ->
            val status = map(
                settingsState = ClaudePPairingState.NOT_PAIRED,
                credentialRead = ClaudePCredentialRead.Absent,
                connectionState = connection,
            )

            assertEquals(
                "connection=$connection must not make an unpaired device usable",
                ClaudePUiStatus.NOT_PAIRED,
                status,
            )
            assertFalse("connection=$connection reported as usable", status.allowsDispatch)
        }
    }

    @Test
    fun `a ready connection with no readable credential is not online`() {
        // The contradiction case: `READY` cannot have been reached legitimately without a credential,
        // and resolving it in favour of "online" is the fail-open this layer exists to prevent.
        val status = map(
            settingsState = ClaudePPairingState.PAIRED,
            credentialRead = ClaudePCredentialRead.Absent,
            connectionState = ClaudePConnectionState.READY,
        )

        assertEquals(ClaudePUiStatus.NOT_PAIRED, status)
        assertFalse(status.allowsDispatch)
    }

    @Test
    fun `an unusable credential asks the user to re-pair rather than to pair`() {
        val status = map(
            settingsState = ClaudePPairingState.PAIRED,
            credentialRead = ClaudePCredentialRead.Unusable(ClaudePCredentialFailure.KEY_INVALIDATED),
            connectionState = ClaudePConnectionState.DISCONNECTED,
        )

        assertEquals(ClaudePUiStatus.CREDENTIAL_INVALID, status)
        assertTrue(status.offersPairing)
    }

    @Test
    fun `an expired credential asks the user to re-pair`() {
        val status = map(
            settingsState = ClaudePPairingState.PAIRED,
            credentialRead = ClaudePCredentialRead.Present(device(expiresAt = NOW - 1)),
            connectionState = ClaudePConnectionState.DISCONNECTED,
        )

        assertEquals(ClaudePUiStatus.CREDENTIAL_INVALID, status)
    }

    @Test
    fun `a device whose settings were restored without its credential reads as unpaired`() {
        val status = map(
            settingsState = ClaudePPairingState.PAIRED,
            credentialRead = ClaudePCredentialRead.Absent,
            connectionState = ClaudePConnectionState.DISCONNECTED,
        )

        assertEquals(ClaudePUiStatus.NOT_PAIRED, status)
        assertTrue(status.offersPairing)
    }

    @Test
    fun `a paired device that has not connected yet reads as paired rather than offline`() {
        val status = map(
            settingsState = ClaudePPairingState.PAIRED,
            credentialRead = ClaudePCredentialRead.Present(device()),
            connectionState = ClaudePConnectionState.DISCONNECTED,
        )

        assertEquals(ClaudePUiStatus.PAIRED, status)
        assertTrue(status.allowsDispatch)
    }

    @Test
    fun `a ready connection reads as online`() {
        val status = map(
            settingsState = ClaudePPairingState.PAIRED,
            credentialRead = ClaudePCredentialRead.Present(device()),
            connectionState = ClaudePConnectionState.READY,
        )

        assertEquals(ClaudePUiStatus.ONLINE, status)
    }

    @Test
    fun `connecting and handshaking both read as connecting`() {
        listOf(ClaudePConnectionState.CONNECTING, ClaudePConnectionState.HANDSHAKING).forEach { connection ->
            assertEquals(
                ClaudePUiStatus.CONNECTING,
                map(
                    settingsState = ClaudePPairingState.PAIRED,
                    credentialRead = ClaudePCredentialRead.Present(device()),
                    connectionState = connection,
                ),
            )
        }
    }

    @Test
    fun `reconnecting and offline both read as offline and are not dispatchable`() {
        listOf(ClaudePConnectionState.RECONNECTING, ClaudePConnectionState.OFFLINE).forEach { connection ->
            val status = map(
                settingsState = ClaudePPairingState.PAIRED,
                credentialRead = ClaudePCredentialRead.Present(device()),
                connectionState = connection,
            )
            assertEquals(ClaudePUiStatus.OFFLINE, status)
            assertFalse("offline must not be dispatchable", status.allowsDispatch)
        }
    }

    @Test
    fun `a protocol error is surfaced distinctly and is not dispatchable`() {
        val status = map(
            settingsState = ClaudePPairingState.PAIRED,
            credentialRead = ClaudePCredentialRead.Present(device()),
            connectionState = ClaudePConnectionState.PROTOCOL_ERROR,
        )

        assertEquals(ClaudePUiStatus.PROTOCOL_ERROR, status)
        assertFalse(status.allowsDispatch)
    }

    @Test
    fun `an in-flight pairing shows progress regardless of the other inputs`() {
        val status = map(
            settingsState = ClaudePPairingState.PAIRED,
            credentialRead = ClaudePCredentialRead.Present(device()),
            connectionState = ClaudePConnectionState.READY,
            pairingInFlight = true,
        )

        assertEquals(ClaudePUiStatus.PAIRING, status)
        assertFalse(status.allowsDispatch)
        assertFalse("pairing must not offer a second pairing", status.offersPairing)
    }

    @Test
    fun `only paired and online states allow dispatch`() {
        val dispatchable = ClaudePUiStatus.entries.filter { it.allowsDispatch }

        assertEquals(listOf(ClaudePUiStatus.PAIRED, ClaudePUiStatus.ONLINE), dispatchable)
    }

    @Test
    fun `every state is reachable from some input`() {
        // A state nothing can produce is dead code in a UI, and usually means a mapping mistake.
        val reached = buildSet {
            ClaudePConnectionState.entries.forEach { connection ->
                ClaudePPairingState.entries.forEach { settings ->
                    listOf(
                        ClaudePCredentialRead.Absent,
                        ClaudePCredentialRead.Unusable(ClaudePCredentialFailure.DECRYPTION_FAILED),
                        ClaudePCredentialRead.Present(device()),
                        ClaudePCredentialRead.Present(device(expiresAt = NOW - 1)),
                    ).forEach { read ->
                        add(map(settings, read, connection, pairingInFlight = false))
                        add(map(settings, read, connection, pairingInFlight = true))
                    }
                }
            }
        }

        assertEquals(ClaudePUiStatus.entries.toSet(), reached)
    }

    @Test
    fun `revocation outranks an otherwise valid credential in the ui`() {
        val status = map(
            settingsState = ClaudePPairingState.REVOKED,
            credentialRead = ClaudePCredentialRead.Present(device()),
            connectionState = ClaudePConnectionState.READY,
        )

        assertEquals(ClaudePUiStatus.CREDENTIAL_INVALID, status)
    }

    private fun map(
        settingsState: ClaudePPairingState,
        credentialRead: ClaudePCredentialRead,
        connectionState: ClaudePConnectionState,
        pairingInFlight: Boolean = false,
    ) = ClaudePUiStatusMapper.map(
        settingsState = settingsState,
        credentialRead = credentialRead,
        connectionState = connectionState,
        pairingInFlight = pairingInFlight,
        nowEpochSeconds = NOW,
    )

    private fun device(expiresAt: Long = NOW + 3600) = ClaudePPairedDevice(
        deviceId = "device-1",
        deviceName = "Pixel",
        keyAlias = "alias-1",
        accessCredential = "credential-1",
        accessExpiresAtEpochSeconds = expiresAt,
        gatewayFingerprint = "fingerprint-1",
        gatewayInstallationId = "install-1",
        pairedOrigin = "https://gateway.example.com",
    )

    private companion object {
        const val NOW = 1_000_000L
    }
}
