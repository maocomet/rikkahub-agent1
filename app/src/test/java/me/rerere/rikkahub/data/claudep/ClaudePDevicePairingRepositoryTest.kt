package me.rerere.rikkahub.data.claudep

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.claudep.ClaudePCleanupTombstone
import me.rerere.ai.provider.claudep.ClaudePCredentialStoreFailure
import me.rerere.ai.provider.claudep.InMemoryClaudePCleanupTombstoneStore
import me.rerere.ai.provider.claudep.InMemoryClaudePDeviceCredentialStore
import me.rerere.ai.provider.claudep.InMemoryClaudePDeviceKeyStore
import me.rerere.ai.provider.claudep.InMemoryClaudePPairingSettingsGateway
import me.rerere.ai.provider.claudep.ClaudePPairingOutcome
import me.rerere.ai.provider.claudep.ClaudePPairedMetadata
import me.rerere.ai.provider.claudep.ClaudePPairingState
import me.rerere.ai.provider.claudep.ClaudePTombstoneRejection
import me.rerere.ai.provider.claudep.ClaudePUiStatus
import me.rerere.ai.provider.claudep.FakeClaudePPairingTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The production repository, wired to its real coordinator.
 *
 * ### What these tests are for
 *
 * R1.3 replaced the repository's own revocation, compensation and key-deletion logic with delegation
 * to `ClaudePPairingCoordinator`. That is a structural claim, and a structural claim that is only
 * reviewed by reading is one refactor away from silently coming back.
 *
 * These tests go through the **real** repository methods — not a coordinator built in the test — so
 * deleting the wiring, or reintroducing a second cleanup path, makes them fail.
 *
 * The repository has no Android imports, which is what makes this possible: it reaches settings,
 * credentials, keys and the tombstone only through interfaces.
 */
class ClaudePDevicePairingRepositoryTest {

    // ---------------------------------------------------------------------------------------
    // No transport without a provable identity
    // ---------------------------------------------------------------------------------------

    @Test
    fun `an unpaired repository hands out no client`() = withRepository { repository, _ ->
        assertNull(repository.gatewayClientOrNull())
    }

    @Test
    fun `a revoked repository hands out no client`() = withRepository { repository, harness ->
        harness.settings.state = ClaudePPairingState.REVOKED

        assertNull(repository.gatewayClientOrNull())
    }

    @Test
    fun `a repository with a pending tombstone hands out no client`() =
        withRepository { repository, harness ->
            // Even with settings claiming PAIRED and a readable credential, a pending cleanup wins.
            harness.settings.state = ClaudePPairingState.PAIRED
            harness.tombstones.stored = ClaudePCleanupTombstone(deviceKeyAlias = "some-alias")

            assertNull(repository.gatewayClientOrNull())
            assertTrue(repository.hasPendingCleanup())
        }

    @Test
    fun `a repository with an unreadable tombstone hands out no client`() =
        withRepository { repository, harness ->
            harness.settings.state = ClaudePPairingState.PAIRED
            harness.tombstones.readRejection = ClaudePTombstoneRejection.MALFORMED

            // Unreadable evidence is still evidence: the device must not be treated as clean.
            assertNull(repository.gatewayClientOrNull())
            assertTrue(repository.hasPendingCleanup())
        }

    @Test
    fun `an unknown tombstone version hands out no client`() = withRepository { repository, harness ->
        harness.settings.state = ClaudePPairingState.PAIRED
        harness.tombstones.readRejection = ClaudePTombstoneRejection.UNKNOWN_VERSION

        assertNull(repository.gatewayClientOrNull())
    }

    // ---------------------------------------------------------------------------------------
    // Delegation to the coordinator
    // ---------------------------------------------------------------------------------------

    @Test
    fun `pair goes through the coordinator and reaches a paired state`() =
        withRepository { repository, harness ->
            val outcome = repository.pair(invitationJson(), "Pixel")

            assertTrue("pairing failed: $outcome", outcome is ClaudePPairingOutcome.Paired)
            assertEquals(ClaudePPairingState.PAIRED, harness.settings.state)
            // The credential landed, which is what the coordinator's persistence step exists to do.
            assertNotNull(harness.credentials.stored)
        }

    @Test
    fun `unpair goes through the coordinator and revokes the device`() =
        withRepository { repository, harness ->
            repository.pair(invitationJson(), "Pixel")

            val result = repository.unpair()

            assertTrue(result.isComplete)
            assertEquals(ClaudePPairingState.NOT_PAIRED, harness.settings.state)
            // Revocation disables the provider; that is the coordinator's first act, not the UI's.
            assertFalse(harness.settings.providerEnabled)
            assertNull(harness.credentials.stored)
            assertTrue(harness.keys.aliases.isEmpty())
        }

    @Test
    fun `retry cleanup goes through the same coordinator path`() =
        withRepository { repository, harness ->
            repository.pair(invitationJson(), "Pixel")
            harness.keys.deleteFails = true
            val first = repository.unpair()
            assertFalse(first.isComplete)
            assertEquals(ClaudePPairingState.REVOKED, harness.settings.state)

            // Retry must be able to finish what unpair could not, and must reach the same state
            // machine rather than a parallel implementation.
            harness.keys.deleteFails = false
            val retry = repository.retryCleanup()

            assertTrue(retry.isComplete)
            assertEquals(ClaudePPairingState.NOT_PAIRED, harness.settings.state)
            assertNull(harness.tombstones.stored)
        }

    @Test
    fun `a partially cleaned device stays non-dispatchable`() = withRepository { repository, harness ->
        repository.pair(invitationJson(), "Pixel")
        harness.keys.deleteFails = true

        repository.unpair()

        assertEquals(ClaudePPairingState.REVOKED, harness.settings.state)
        assertFalse(harness.settings.providerEnabled)
        assertNull(repository.gatewayClientOrNull())
        assertTrue(repository.hasPendingCleanup())
    }

    // ---------------------------------------------------------------------------------------
    // Restart
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a restarted repository still refuses to dispatch and can finish the cleanup`() =
        withRepository { repository, harness ->
            repository.pair(invitationJson(), "Pixel")
            harness.keys.deleteFails = true
            repository.unpair()

            // Same durable stores, fresh in-memory process state — what a restart looks like here.
            val restarted = harness.newRepository()

            assertTrue(restarted.hasPendingCleanup())
            assertNull(restarted.gatewayClientOrNull())

            harness.keys.deleteFails = false
            assertTrue(restarted.retryCleanup().isComplete)
            assertEquals(ClaudePPairingState.NOT_PAIRED, harness.settings.state)
        }

    @Test
    fun `unpairing a never-paired device is idempotent and does not strand it`() =
        withRepository { repository, harness ->
            val first = repository.unpair()
            val second = repository.unpair()
            val retry = repository.retryCleanup()

            // A clean device must not end up REVOKED behind a retry that could never succeed.
            assertTrue(first.isComplete)
            assertTrue(second.isComplete)
            assertTrue(retry.isComplete)
            assertEquals(ClaudePPairingState.NOT_PAIRED, harness.settings.state)
            assertFalse(repository.hasPendingCleanup())
        }

    @Test
    fun `a failed settings write never reaches a paired state`() =
        withRepository { repository, harness ->
            harness.settings.writesFail = true

            val outcome = repository.pair(invitationJson(), "Pixel")

            assertTrue(outcome is ClaudePPairingOutcome.Rejected)
            // Compensation ran: no credential may outlive a pairing the app does not believe happened.
            assertNull(harness.credentials.stored)
            assertTrue(harness.keys.aliases.isEmpty())
        }

    @Test
    fun `a failed credential write never reaches a paired state`() =
        withRepository { repository, harness ->
            harness.credentials.writeFailures =
                listOf(ClaudePCredentialStoreFailure.TEMP_WRITE_FAILED)

            val outcome = repository.pair(invitationJson(), "Pixel")

            assertTrue(outcome is ClaudePPairingOutcome.Rejected)
            assertEquals(ClaudePPairingState.NOT_PAIRED, harness.settings.state)
        }

    @Test
    fun `the published status never reports a dispatchable device while cleanup is pending`() =
        withRepository { repository, harness ->
            repository.pair(invitationJson(), "Pixel")
            harness.keys.deleteFails = true
            repository.unpair()

            // Derived status comes from the repository, not from the UI's own reasoning.
            assertEquals(ClaudePUiStatus.OFFLINE, repository.status.value)
            assertFalse(repository.status.value.allowsDispatch)
        }

    // ---------------------------------------------------------------------------------------
    // Durable device identity
    // ---------------------------------------------------------------------------------------

    @Test
    fun `the device id comes from durable state after pairing`() = withRepository { repository, harness ->
        repository.pair(invitationJson(), "Pixel")

        val id = repository.currentDeviceIdOrNull()

        assertEquals(harness.credentials.stored?.deviceId, id)
        assertNotNull(id)
    }

    @Test
    fun `unpair revokes the device id rather than serving a cached one`() =
        withRepository { repository, _ ->
            repository.pair(invitationJson(), "Pixel")
            assertNotNull(repository.currentDeviceIdOrNull())

            repository.unpair()

            // The volatile cache still held the old id at this point in an earlier revision, so the
            // provider would have bound a fingerprint to a device this app can no longer
            // authenticate. Re-derivation from durable state is what prevents that.
            assertNull(repository.currentDeviceIdOrNull())
        }

    @Test
    fun `a pending cleanup hides the device id even with settings claiming paired`() =
        withRepository { repository, harness ->
            repository.pair(invitationJson(), "Pixel")
            harness.settings.state = ClaudePPairingState.REVOKED

            assertNull(repository.currentDeviceIdOrNull())
        }

    @Test
    fun `a misleading tombstone blocks the device id`() = withRepository { repository, harness ->
        repository.pair(invitationJson(), "Pixel")
        harness.tombstones.readRejection = ClaudePTombstoneRejection.MALFORMED

        assertNull(repository.currentDeviceIdOrNull())
    }

    @Test
    fun `a restarted repository recovers the same device id from durable state`() =
        withRepository { repository, harness ->
            repository.pair(invitationJson(), "Pixel")
            val before = repository.currentDeviceIdOrNull()

            val restarted = harness.newRepository()

            assertEquals(before, restarted.currentDeviceIdOrNull())
        }

    @Test
    fun `settings that disagree with the credential yield no device id`() =
        withRepository { repository, harness ->
            repository.pair(invitationJson(), "Pixel")
            // A partial write, or a tampered record: there is no safe way to pick a winner.
            harness.settings.metadata = ClaudePPairedMetadata(
                pairedOrigin = "https://other.example.com",
                gatewayFingerprint = "other",
                gatewayInstallationId = "other",
                deviceId = "other",
            )

            assertNull(repository.currentDeviceIdOrNull())
        }

    @Test
    fun `a wiped device key yields no device id`() = withRepository { repository, harness ->
        repository.pair(invitationJson(), "Pixel")
        harness.keys.loadFails = true

        assertNull(repository.currentDeviceIdOrNull())
    }

    // ---------------------------------------------------------------------------------------
    // Harness
    // ---------------------------------------------------------------------------------------

    private fun withRepository(
        block: suspend (ClaudePDevicePairingRepository, Harness) -> Unit,
    ) = runBlocking<Unit> {
        val harness = Harness()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val repository = harness.newRepository(scope)
        try {
            block(repository, harness)
        } finally {
            scope.cancel()
        }
    }

    private class Harness {
        val credentials = InMemoryClaudePDeviceCredentialStore()
        val keys = InMemoryClaudePDeviceKeyStore()
        val tombstones = InMemoryClaudePCleanupTombstoneStore()
        val settings = InMemoryClaudePPairingSettingsGateway()
        private val transport = FakeClaudePPairingTransport(
            gatewayFingerprint = FINGERPRINT,
        )

        fun newRepository(
            scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined),
        ) = ClaudePDevicePairingRepository(
            credentialStore = credentials,
            deviceKeyStore = keys,
            tombstoneStore = tombstones,
            settingsGateway = settings,
            scope = scope,
            appVersion = APP_VERSION,
            nowEpochSeconds = { NOW },
            // Production defaults are the real OkHttp transports; the seam exists so this wiring can
            // be exercised without a socket.
            pairingTransportFor = { transport },
        )

        private companion object {
            const val APP_VERSION = "1.0-test"
            const val NOW = 1_000_000L
            const val FINGERPRINT =
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        }
    }

    private companion object {
        fun invitationJson(ticket: String = "ticket-abcdefghijklmnop"): String = """
            {"origin":"https://gateway.example.com",
             "gateway_fingerprint":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
             "ticket":"$ticket",
             "protocol":"rikkahub.claude-p.v1",
             "expires_at":1000600}
        """.trimIndent()
    }
}
