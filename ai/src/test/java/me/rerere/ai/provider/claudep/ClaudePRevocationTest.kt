package me.rerere.ai.provider.claudep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Revocation state rules.
 *
 * The property under test is that **`NOT_PAIRED` cannot be reached while anything survived**. An
 * earlier revision wrote `NOT_PAIRED` unconditionally at the end of every unpair, so a deletion that
 * failed — a locked file, a Keystore that refused, a process death mid-cleanup — left settings
 * claiming the device was clean while a credential or private key could still be on it.
 */
class ClaudePRevocationTest {

    @Test
    fun `a fully successful cleanup is the only path to not paired`() {
        assertEquals(
            ClaudePPairingState.NOT_PAIRED,
            ClaudePRevocation.stateAfterCleanup(emptyList()),
        )
    }

    @Test
    fun `any surviving item keeps the device revoked`() {
        ClaudePUnpairFailure.entries.forEach { failure ->
            val state = ClaudePRevocation.stateAfterCleanup(listOf(failure))

            assertEquals(
                "a surviving $failure must not be reported as a clean device",
                ClaudePPairingState.REVOKED,
                state,
            )
        }
    }

    @Test
    fun `a partially successful cleanup keeps the device revoked`() {
        val state = ClaudePRevocation.stateAfterCleanup(
            listOf(
                ClaudePUnpairFailure.CREDENTIAL_FILE_NOT_DELETED,
                ClaudePUnpairFailure.DEVICE_KEY_NOT_DELETED,
            ),
        )

        assertEquals(ClaudePPairingState.REVOKED, state)
    }

    @Test
    fun `a retry is offered exactly while the device is revoked`() {
        // The affordance and the state cannot disagree: `REVOKED` *means* "a cleanup did not finish".
        assertTrue(ClaudePRevocation.offersCleanupRetry(ClaudePPairingState.REVOKED))
        assertFalse(ClaudePRevocation.offersCleanupRetry(ClaudePPairingState.NOT_PAIRED))
        assertFalse(ClaudePRevocation.offersCleanupRetry(ClaudePPairingState.PAIRED))
    }

    @Test
    fun `an incomplete result reports itself and carries its categories`() {
        val result = ClaudePUnpairResult(
            listOf(ClaudePUnpairFailure.CREDENTIAL_WRAPPING_KEY_NOT_DELETED),
        )

        assertFalse(result.isComplete)
        assertEquals(ClaudePPairingState.REVOKED, result.resultingState)
        assertEquals(
            listOf(ClaudePUnpairFailure.CREDENTIAL_WRAPPING_KEY_NOT_DELETED),
            result.failures,
        )
    }

    @Test
    fun `a complete result reports itself`() {
        val result = ClaudePUnpairResult(emptyList())

        assertTrue(result.isComplete)
        assertEquals(ClaudePPairingState.NOT_PAIRED, result.resultingState)
    }

    @Test
    fun `both revocation states refuse to dispatch`() {
        // The safety argument for keeping `REVOKED` rather than forcing `NOT_PAIRED` is that neither
        // state can reach a gateway. If that were untrue, holding `REVOKED` would be a hazard.
        listOf(ClaudePPairingState.REVOKED, ClaudePPairingState.NOT_PAIRED).forEach { state ->
            val status = ClaudePUiStatusMapper.map(
                settingsState = state,
                credentialRead = ClaudePCredentialRead.Present(
                    ClaudePPairedDevice(
                        deviceId = "device-1",
                        deviceName = "Pixel",
                        keyAlias = "alias",
                        accessCredential = "credential",
                        accessExpiresAtEpochSeconds = 10_000,
                        gatewayFingerprint = "fingerprint",
                        gatewayInstallationId = "install",
                        pairedOrigin = "https://gateway.example.com",
                    ),
                ),
                connectionState = ClaudePConnectionState.READY,
                pairingInFlight = false,
                nowEpochSeconds = 0,
            )

            // Even with a live socket and a readable credential, a revoked device is not dispatchable.
            assertFalse("$state must not allow dispatch", status.allowsDispatch)
        }
    }
}
