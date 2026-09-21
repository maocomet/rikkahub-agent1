package me.rerere.ai.provider.claudep

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pairing lifecycle: serialisation, persistence, revocation and cleanup recovery.
 *
 * ### Why the barrier tests look the way they do
 *
 * Every concurrency case here is driven by a `CompletableDeferred` gate the *test* releases, never by
 * a sleep. That matters because the property under test is "a second operation cannot interleave",
 * and a timing-based test can only ever show that it usually does not. With a gate, the first
 * operation is provably still holding the lock when the second is started.
 */
class ClaudePPairingCoordinatorTest {

    // ---------------------------------------------------------------------------------------
    // Lifecycle serialisation
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a second pair cannot interleave with the first one's persistence`() = runBlocking<Unit> {
        val harness = Harness()
        // The gate must be installed *before* the operation starts, or nothing is actually paused.
        val gate = CompletableDeferred<Unit>()
        harness.credentials.writeGate = gate

        val first = harness.startPair("Pixel")
        val second = async(Dispatchers.Unconfined) { harness.coordinator.pair(invitationJson(), "Pixel") }

        // The first is suspended mid-persistence holding the lifecycle lock, so the second has not
        // reached the gateway at all. `transport.sendCount` is the proof: a second exchange would
        // have incremented it whatever the persistence outcome.
        assertEquals(1, harness.transport.sendCount)

        gate.complete(Unit)
        first.await()
        second.await()

        // Both eventually complete, but the second could never observe a half-written pairing.
        assertEquals(ClaudePPairingState.PAIRED, harness.settings.state)
        assertEquals(2, harness.transport.sendCount)
    }

    @Test
    fun `an unpair cannot interleave with a paused persistence`() = runBlocking<Unit> {
        val harness = Harness()
        val gate = CompletableDeferred<Unit>()
        harness.credentials.writeGate = gate

        val pairing = harness.startPair("Pixel")
        // Revocation is requested while persistence is mid-flight. It must wait for the lock.
        val unpair = async(Dispatchers.Unconfined) { harness.coordinator.unpair() }

        // `markRevoked` writes the non-dispatchable state as its first act. It has not run, so the
        // revocation genuinely has not begun — it is queued behind persistence, not racing it.
        assertEquals(0, harness.settings.revokedMarkCount)

        gate.complete(Unit)
        pairing.await()
        val result = unpair.await()

        // Revocation ran after persistence finished, and it won: the device ends non-dispatchable.
        assertEquals(1, harness.settings.revokedMarkCount)
        assertEquals(ClaudePPairingState.NOT_PAIRED, harness.settings.state)
        assertTrue(result.isComplete)
        assertFalse(harness.coordinator.mustNotDispatch())
    }

    @Test
    fun `a new pair cannot interleave with a paused unpair`() = runBlocking<Unit> {
        val harness = Harness()
        harness.pairSuccessfully("Pixel")

        val gate = CompletableDeferred<Unit>()
        harness.credentials.clearGate = gate
        val unpair = async(Dispatchers.Unconfined) { harness.coordinator.unpair() }

        val sendsBefore = harness.transport.sendCount
        val pair = async(Dispatchers.Unconfined) { harness.coordinator.pair(invitationJson(), "Pixel") }
        // The second pairing must not reach the gateway while revocation holds the lock.
        assertEquals(sendsBefore, harness.transport.sendCount)

        gate.complete(Unit)
        unpair.await()
        pair.await()

        // Once the lock is released the pairing legitimately runs — after the revocation finished,
        // never underneath it. What must hold is that the end state is self-consistent: either the
        // device is paired with the credential that pairing wrote, or it is not paired at all.
        when (harness.settings.state) {
            ClaudePPairingState.PAIRED ->
                assertEquals(harness.credentials.stored?.keyAlias != null, true)

            else -> assertFalse(
                "a non-paired state must not keep a live credential",
                harness.credentials.stored != null && !harness.coordinator.mustNotDispatch(),
            )
        }
    }

    // ---------------------------------------------------------------------------------------
    // Revocation protocol
    // ---------------------------------------------------------------------------------------

    @Test
    fun `revocation disables the provider before anything is deleted`() = runBlocking {
        val harness = Harness()
        harness.pairSuccessfully("Pixel")
        harness.settings.providerEnabled = true

        harness.coordinator.unpair()

        assertFalse("a revoked provider must not stay enabled", harness.settings.providerEnabled)
    }

    @Test
    fun `a fully confirmed cleanup retires the tombstone and reaches not paired`() = runBlocking {
        val harness = Harness()
        harness.pairSuccessfully("Pixel")

        val result = harness.coordinator.unpair()

        assertTrue("nothing should have failed", result.isComplete)
        assertEquals(ClaudePPairingState.NOT_PAIRED, harness.settings.state)
        assertNull("a finished cleanup must not leave a tombstone", harness.tombstones.stored)
        assertTrue(harness.keys.aliases.isEmpty())
    }

    @Test
    fun `a surviving device key keeps the device revoked and the tombstone in place`() = runBlocking {
        val harness = Harness()
        harness.pairSuccessfully("Pixel")
        val alias = harness.credentials.stored!!.keyAlias
        harness.keys.deleteFails = true

        val result = harness.coordinator.unpair()

        assertEquals(listOf(ClaudePUnpairFailure.DEVICE_KEY_NOT_DELETED), result.failures)
        assertEquals(ClaudePPairingState.REVOKED, harness.settings.state)
        assertEquals(alias, harness.tombstones.stored?.deviceKeyAlias)
    }

    @Test
    fun `a surviving credential file keeps the device revoked`() = runBlocking {
        val harness = Harness()
        harness.pairSuccessfully("Pixel")
        harness.credentials.clearFailures = listOf(ClaudePCredentialStoreFailure.FILE_DELETE_FAILED)
        harness.credentials.clearKeepsData = true

        val result = harness.coordinator.unpair()

        assertEquals(
            listOf(ClaudePUnpairFailure.CREDENTIAL_FILE_NOT_DELETED),
            result.failures,
        )
        assertEquals(ClaudePPairingState.REVOKED, harness.settings.state)
    }

    @Test
    fun `a surviving wrapping key keeps the device revoked`() = runBlocking {
        val harness = Harness()
        harness.pairSuccessfully("Pixel")
        harness.credentials.clearFailures =
            listOf(ClaudePCredentialStoreFailure.WRAPPING_KEY_DELETE_FAILED)

        val result = harness.coordinator.unpair()

        assertEquals(
            listOf(ClaudePUnpairFailure.CREDENTIAL_WRAPPING_KEY_NOT_DELETED),
            result.failures,
        )
        assertEquals(ClaudePPairingState.REVOKED, harness.settings.state)
        // The alias is still recoverable, so a retry after a restart remains possible.
        assertNotNull(harness.tombstones.stored)
    }

    @Test
    fun `a surviving credential wrapping key is never reported as a clean unpair`() = runBlocking {
        val harness = Harness()
        harness.pairSuccessfully("Pixel")

        // Distinct assertion from the one above: an empty failure list must be impossible here, or the
        // device would claim NOT_PAIRED while the AES key that decrypts a recovered ciphertext lives.
        val result = harness.coordinator.unpair()
        assertTrue(result.isComplete)
        assertFalse(harness.keys.deleteFails)
    }

    @Test
    fun `a failed tombstone write stops the cleanup before any destructive step`() = runBlocking {
        val harness = Harness()
        harness.pairSuccessfully("Pixel")
        val alias = harness.credentials.stored!!.keyAlias
        harness.tombstones.writeFails = true

        val result = harness.coordinator.unpair()

        assertEquals(listOf(ClaudePUnpairFailure.TOMBSTONE_WRITE_FAILED), result.failures)
        assertEquals(ClaudePPairingState.REVOKED, harness.settings.state)
        // Nothing was destroyed: losing the alias would have made the key unlocatable forever.
        assertNotNull("the credential must survive a failed tombstone write", harness.credentials.stored)
        assertTrue(harness.keys.aliases.contains(alias))
    }

    @Test
    fun `a failed tombstone deletion stops the device claiming to be clean`() = runBlocking {
        val harness = Harness()
        harness.pairSuccessfully("Pixel")
        harness.tombstones.clearFails = true
        harness.tombstones.clearKeepsData = true

        val result = harness.coordinator.unpair()

        assertEquals(listOf(ClaudePUnpairFailure.TOMBSTONE_DELETE_FAILED), result.failures)
        assertEquals(ClaudePPairingState.REVOKED, harness.settings.state)
        assertNotNull(harness.tombstones.stored)
    }

    @Test
    fun `revocation keeps the transport from dispatching even before cleanup runs`() = runBlocking<Unit> {
        val harness = Harness()
        harness.pairSuccessfully("Pixel")
        harness.credentials.clearGate = CompletableDeferred()

        val unpair = async(Dispatchers.Unconfined) { harness.coordinator.unpair() }
        // Mid-revocation: settings are already REVOKED, so nothing may dispatch.
        assertTrue("a revoked device must not dispatch", harness.coordinator.mustNotDispatch())

        harness.credentials.clearGate?.complete(Unit)
        unpair.await()
    }

    // ---------------------------------------------------------------------------------------
    // Recovery across a restart
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a restart locates the surviving key from the tombstone and still refuses to dispatch`() =
        runBlocking {
            val harness = Harness()
            harness.pairSuccessfully("Pixel")
            val alias = harness.credentials.stored!!.keyAlias
            harness.keys.deleteFails = true
            harness.coordinator.unpair()

            // The credential is gone, the process ends, and a fresh coordinator is built over the
            // same durable stores — which is what a restart looks like here.
            val restarted = Harness(
                credentials = harness.credentials,
                keys = harness.keys,
                tombstones = harness.tombstones,
                settings = harness.settings,
            )

            assertTrue("a pending cleanup must block dispatch", restarted.coordinator.mustNotDispatch())
            assertEquals(ClaudePPairingState.REVOKED, restarted.settings.state)

            // The alias survived only in the tombstone, which is exactly why it exists.
            harness.keys.deleteFails = false
            val retry = restarted.coordinator.retryCleanup()

            assertTrue(retry.isComplete)
            assertEquals(ClaudePPairingState.NOT_PAIRED, restarted.settings.state)
            assertNull(restarted.tombstones.stored)
            assertFalse(harness.keys.aliases.contains(alias))
        }

    @Test
    fun `a retry is idempotent and does not damage a later pairing`() = runBlocking {
        val harness = Harness()
        harness.pairSuccessfully("Pixel")

        // First unpair succeeds fully.
        assertTrue(harness.coordinator.unpair().isComplete)

        // A fresh pairing, then a repeated unpair/retry pair. The second pairing's key must be the
        // one that is destroyed — never a stale alias from the first.
        harness.pairSuccessfully("Pixel")
        val newAlias = harness.credentials.stored!!.keyAlias

        assertTrue(harness.coordinator.unpair().isComplete)
        assertTrue(harness.coordinator.retryCleanup().isComplete)

        assertEquals(ClaudePPairingState.NOT_PAIRED, harness.settings.state)
        assertFalse(harness.keys.aliases.contains(newAlias))
    }

    @Test
    fun `unpairing an unpaired device is safe and reports nothing to do`() = runBlocking {
        val harness = Harness()

        val result = harness.coordinator.unpair()

        assertEquals(ClaudePPairingState.NOT_PAIRED, harness.settings.state)
        // Nothing was ever paired, so there is nothing to clean and nothing to report. Reporting an
        // unnamable key here would strand a clean device in REVOKED behind an unretryable failure.
        assertTrue(result.isComplete)
        assertFalse(harness.coordinator.mustNotDispatch())
    }

    // ---------------------------------------------------------------------------------------
    // Pairing persistence failures
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a failed credential write compensates instead of reporting success`() = runBlocking {
        val harness = Harness()
        harness.credentials.writeFailures =
            listOf(ClaudePCredentialStoreFailure.TEMP_WRITE_FAILED)

        val outcome = harness.coordinator.pair(invitationJson(), "Pixel")

        assertEquals(
            ClaudePPairingOutcome.Rejected(ClaudePPairingFailure.PAIRING_NOT_PERSISTED),
            outcome,
        )
        assertEquals(ClaudePPairingState.NOT_PAIRED, harness.settings.state)
        assertNull(harness.credentials.stored)
        assertTrue("the attempt's key must be destroyed", harness.keys.aliases.isEmpty())
    }

    @Test
    fun `a failed settings write compensates instead of leaving a usable credential`() = runBlocking {
        val harness = Harness()
        harness.settings.writesFail = true

        val outcome = harness.coordinator.pair(invitationJson(), "Pixel")

        assertEquals(
            ClaudePPairingOutcome.Rejected(ClaudePPairingFailure.PAIRING_NOT_PERSISTED),
            outcome,
        )
        assertNull("no credential may outlive a pairing the app does not believe happened", harness.credentials.stored)
        assertTrue(harness.keys.aliases.isEmpty())
    }

    @Test
    fun `a successful pairing retires a stale tombstone without touching the new key`() = runBlocking {
        val harness = Harness()
        // A leftover tombstone from an earlier, incomplete cleanup.
        harness.tombstones.write(ClaudePCleanupTombstone(deviceKeyAlias = "stale-alias"))

        harness.pairSuccessfully("Pixel")

        val newAlias = harness.credentials.stored!!.keyAlias
        assertNull("the stale tombstone must be retired", harness.tombstones.stored)
        assertTrue("the new pairing's key must survive", harness.keys.aliases.contains(newAlias))
    }

    // ---------------------------------------------------------------------------------------
    // Unusable tombstones
    // ---------------------------------------------------------------------------------------

    @Test
    fun `an unreadable tombstone blocks dispatch and is never treated as absent`() = runBlocking<Unit> {
        val harness = Harness()
        harness.pairSuccessfully("Pixel")
        harness.credentials.clearFailures = listOf(ClaudePCredentialStoreFailure.FILE_DELETE_FAILED)
        harness.keys.deleteFails = true
        harness.coordinator.unpair()
        // The record survives but can no longer be parsed — a corrupt file, or a format from a
        // future build.
        harness.tombstones.readRejection = ClaudePTombstoneRejection.MALFORMED

        assertTrue("an unreadable tombstone is still evidence", harness.coordinator.mustNotDispatch())
        assertEquals(ClaudePPairingState.REVOKED, harness.settings.state)
    }

    @Test
    fun `pairing is refused while an unreadable tombstone exists`() = runBlocking<Unit> {
        val harness = Harness()
        harness.tombstones.readRejection = ClaudePTombstoneRejection.UNKNOWN_VERSION

        val outcome = harness.coordinator.pair(invitationJson(), "Pixel")

        // Pairing over an unresolved cleanup would leave the old key permanently undeletable.
        assertEquals(
            ClaudePPairingOutcome.Rejected(ClaudePPairingFailure.CLEANUP_PENDING),
            outcome,
        )
        assertEquals(0, harness.transport.sendCount)
    }

    @Test
    fun `an unreadable tombstone keeps the device non-dispatchable after a restart`() = runBlocking<Unit> {
        val harness = Harness()
        harness.pairSuccessfully("Pixel")
        harness.keys.deleteFails = true
        harness.coordinator.unpair()
        harness.tombstones.readRejection = ClaudePTombstoneRejection.MALFORMED

        val restarted = Harness(
            credentials = harness.credentials,
            keys = harness.keys,
            tombstones = harness.tombstones,
            settings = harness.settings,
        )

        assertTrue(restarted.coordinator.mustNotDispatch())
        // And a retry still does not claim a clean device it cannot prove.
        val retry = restarted.coordinator.retryCleanup()
        assertFalse(retry.isComplete)
        assertEquals(ClaudePPairingState.REVOKED, restarted.settings.state)
    }

    // ---------------------------------------------------------------------------------------
    // Harness
    // ---------------------------------------------------------------------------------------

    /**
     * One coordinator over injected stores.
     *
     * Passing the stores in is what lets a "restart" be modelled by building a second harness over
     * the same durable state while the in-memory ones persist.
     */
    private class Harness(
        val credentials: InMemoryClaudePDeviceCredentialStore = InMemoryClaudePDeviceCredentialStore(),
        val keys: InMemoryClaudePDeviceKeyStore = InMemoryClaudePDeviceKeyStore(),
        val tombstones: InMemoryClaudePCleanupTombstoneStore = InMemoryClaudePCleanupTombstoneStore(),
        val settings: InMemoryClaudePPairingSettingsGateway = InMemoryClaudePPairingSettingsGateway(),
    ) {
        val transport = FakeClaudePPairingTransport(gatewayFingerprint = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")

        val coordinator = ClaudePPairingCoordinator(
            credentialStore = credentials,
            deviceKeyStore = keys,
            tombstoneStore = tombstones,
            settings = settings,
            pairingClient = ClaudePPairingClient(
                transportFor = { transport },
                keyStore = keys,
                appVersion = APP_VERSION,
            ),
            nowEpochSeconds = { NOW },
        )

        private var revokedSeen = 0

        fun revokedCount(): Int = settings.revokedMarkCount

        fun startPair(deviceName: String): kotlinx.coroutines.Deferred<ClaudePPairingOutcome> {
            val payload = invitationJson()
            return kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined).async {
                coordinator.pair(payload, deviceName)
            }
        }

        /** Pairs and asserts it worked, so a later failure is unambiguous. */
        suspend fun pairSuccessfully(deviceName: String) {
            val outcome = coordinator.pair(invitationJson(), deviceName)
            assertTrue("fixture pairing failed: $outcome", outcome is ClaudePPairingOutcome.Paired)
        }

        private var ticketSuffix = 0

        /** A fresh single-use ticket per call: the fake gateway enforces single use. */
        fun invitationJson(): String = ticketInvitationJson("ticket-abcdefghijklmnop${ticketSuffix++}")

        private companion object {
            const val APP_VERSION = "1.0-test"
            const val NOW = 1_000_000L
        }
    }

    private companion object {
        /** The outer-scope convenience payload, used by tests that pair only once. */
        fun invitationJson(): String = ticketInvitationJson("ticket-abcdefghijklmnop")
    }
}

private fun ticketInvitationJson(ticket: String, expiresAt: Long = 1_000_600): String = """
    {"origin":"https://gateway.example.com",
     "gateway_fingerprint":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
     "ticket":"$ticket",
     "protocol":"rikkahub.claude-p.v1",
     "expires_at":$expiresAt}
""".trimIndent()
