package me.rerere.ai.provider.claudep

import java.util.concurrent.atomic.AtomicInteger

/**
 * In-memory tombstone store, honouring the same contract the Android one must.
 *
 * It can be told to fail either write or clear independently, because those two failures have
 * different consequences: a failed **write** must stop the cleanup before any destructive step, while
 * a failed **clear** must stop the device from claiming it is clean.
 */
class InMemoryClaudePCleanupTombstoneStore : ClaudePCleanupTombstoneStore {
    @Volatile
    var stored: ClaudePCleanupTombstone? = null
        private set

    @Volatile
    var writeFails: Boolean = false

    @Volatile
    var clearFails: Boolean = false

    /** True when [clear] should report failure *and* keep the record — a stuck deletion. */
    @Volatile
    var clearKeepsData: Boolean = false

    @Volatile
    var writeCount: Int = 0
        private set

    /** When set, [write] suspends here — used to pause an unpair before any destructive step. */
    var writeGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    override suspend fun read(): ClaudePCleanupTombstone? = stored

    override suspend fun write(tombstone: ClaudePCleanupTombstone): Boolean {
        writeGate?.await()
        writeCount += 1
        if (writeFails) return false
        stored = tombstone
        return true
    }

    override suspend fun clear(): Boolean {
        if (clearFails) {
            if (!clearKeepsData) stored = null
            return false
        }
        stored = null
        return true
    }
}

/**
 * In-memory settings gateway.
 *
 * Models the one behaviour from the real settings layer that the coordinator depends on: a write can
 * fail, and the state it reports afterwards is the last one that actually landed.
 */
class InMemoryClaudePPairingSettingsGateway(
    initial: ClaudePPairingState = ClaudePPairingState.NOT_PAIRED,
) : ClaudePPairingSettingsGateway {

    @Volatile
    var state: ClaudePPairingState = initial
        private set

    @Volatile
    var metadata: ClaudePPairedMetadata? = null
        private set

    /** When true, every write reports failure and leaves the previous state in place. */
    @Volatile
    var writesFail: Boolean = false

    /** Provider enablement, which the coordinator must force off on revocation. */
    @Volatile
    var providerEnabled: Boolean = false

    @Volatile
    var failedWriteCount: Int = 0
        private set

    private val markRevokedCount = AtomicInteger(0)

    /** How many times the coordinator forced revocation — used to assert ordering. */
    val revokedMarkCount: Int get() = markRevokedCount.get()

    override suspend fun pairingState(): ClaudePPairingState = state

    override suspend fun pairedMetadata(): ClaudePPairedMetadata? = metadata

    override suspend fun markPaired(device: ClaudePPairedDevice): Boolean {
        if (writesFail) return false
        state = ClaudePPairingState.PAIRED
        metadata = ClaudePPairedMetadata(
            pairedOrigin = device.pairedOrigin,
            gatewayFingerprint = device.gatewayFingerprint,
            gatewayInstallationId = device.gatewayInstallationId,
            deviceId = device.deviceId,
        )
        return true
    }

    override suspend fun markRevoked(): Boolean {
        markRevokedCount.incrementAndGet()
        // Disabling is part of revocation, not a separate step: a revoked provider that stays enabled
        // is one settings merge away from being schedulable.
        providerEnabled = false
        if (writesFail) {
            failedWriteCount += 1
            return false
        }
        state = ClaudePPairingState.REVOKED
        return true
    }

    override suspend fun markNotPaired(): Boolean {
        if (writesFail) {
            failedWriteCount += 1
            return false
        }
        state = ClaudePPairingState.NOT_PAIRED
        metadata = null
        return true
    }
}
