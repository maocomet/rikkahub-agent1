package me.rerere.ai.provider.claudep

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The credential store contract, and the resolver that decides whether a device is paired.
 *
 * **Scope, stated plainly:** these tests exercise the *interface contract* and the pure
 * reconciliation logic against an in-memory implementation. They are **not** evidence that the
 * Android Keystore implementation works — that needs instrumentation on a device, because the whole
 * point of the Keystore is that its behaviour (non-exportable keys, invalidation on credential
 * change, `noBackupFilesDir`) cannot be reproduced on the JVM. See the CP1-B report for that
 * distinction.
 *
 * What they *do* establish is the part that is easy to get wrong and expensive to get wrong: that
 * every unreadable, missing or revoked state collapses to "unpaired", and never to "carry on".
 */
class ClaudePCredentialStoreTest {

    // ---------------------------------------------------------------------------------------
    // Store contract
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a written device reads back unchanged`() = runBlocking {
        val store = InMemoryClaudePDeviceCredentialStore()
        val device = device()

        store.write(device)

        assertEquals(ClaudePCredentialRead.Present(device), store.read())
    }

    @Test
    fun `an empty store reports absent rather than a blank device`() = runBlocking {
        assertEquals(ClaudePCredentialRead.Absent, InMemoryClaudePDeviceCredentialStore().read())
    }

    @Test
    fun `clearing removes the credential so nothing is left to authenticate with`() = runBlocking {
        val store = InMemoryClaudePDeviceCredentialStore()
        store.write(device())

        store.clear()

        assertEquals(ClaudePCredentialRead.Absent, store.read())
    }

    @Test
    fun `an undecryptable record reads as unusable rather than as absent or partial`() = runBlocking {
        val store = InMemoryClaudePDeviceCredentialStore()
        store.write(device())
        store.readFailure = ClaudePCredentialFailure.DECRYPTION_FAILED

        val read = store.read()

        // `Unusable` is a distinct state from `Absent` so the UI can say "pairing could not be read"
        // instead of silently pretending the user never paired.
        assertTrue(read is ClaudePCredentialRead.Unusable)
        assertEquals(ClaudePCredentialFailure.DECRYPTION_FAILED, (read as ClaudePCredentialRead.Unusable).reason)
    }

    @Test
    fun `writing again clears a previous read failure`() = runBlocking {
        val store = InMemoryClaudePDeviceCredentialStore()
        store.readFailure = ClaudePCredentialFailure.KEY_INVALIDATED

        store.write(device())

        assertTrue(store.read() is ClaudePCredentialRead.Present)
    }

    // ---------------------------------------------------------------------------------------
    // Pairing resolution — the fail-closed table
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a usable credential means paired`() {
        val resolution = ClaudePPairingResolver.resolve(
            settingsState = ClaudePPairingState.PAIRED,
            credentialRead = ClaudePCredentialRead.Present(device()),
            nowEpochSeconds = NOW,
        )

        assertTrue(resolution.isPaired)
    }

    @Test
    fun `settings claiming paired without a credential is not paired`() {
        // The restore case: the settings row survived a device transfer and the encrypted credential
        // did not. Believing the settings here would leave the user with a provider that cannot
        // authenticate and cannot explain why.
        val resolution = ClaudePPairingResolver.resolve(
            settingsState = ClaudePPairingState.PAIRED,
            credentialRead = ClaudePCredentialRead.Absent,
            nowEpochSeconds = NOW,
        )

        assertEquals(
            ClaudePPairingResolution.Unpaired(ClaudePUnpairedReason.CREDENTIAL_MISSING),
            resolution,
        )
    }

    @Test
    fun `an unusable credential is never treated as paired`() {
        val resolution = ClaudePPairingResolver.resolve(
            settingsState = ClaudePPairingState.PAIRED,
            credentialRead = ClaudePCredentialRead.Unusable(ClaudePCredentialFailure.KEY_INVALIDATED),
            nowEpochSeconds = NOW,
        )

        assertEquals(
            ClaudePPairingResolution.Unpaired(ClaudePUnpairedReason.CREDENTIAL_UNUSABLE),
            resolution,
        )
    }

    @Test
    fun `an expired credential is never treated as paired`() {
        val resolution = ClaudePPairingResolver.resolve(
            settingsState = ClaudePPairingState.PAIRED,
            credentialRead = ClaudePCredentialRead.Present(device(expiresAt = NOW - 1)),
            nowEpochSeconds = NOW,
        )

        assertEquals(
            ClaudePPairingResolution.Unpaired(ClaudePUnpairedReason.CREDENTIAL_EXPIRED),
            resolution,
        )
    }

    @Test
    fun `an unpaired device with no credential reports never paired`() {
        val resolution = ClaudePPairingResolver.resolve(
            settingsState = ClaudePPairingState.NOT_PAIRED,
            credentialRead = ClaudePCredentialRead.Absent,
            nowEpochSeconds = NOW,
        )

        assertEquals(
            ClaudePPairingResolution.Unpaired(ClaudePUnpairedReason.NEVER_PAIRED),
            resolution,
        )
    }

    @Test
    fun `revocation wins over a still-usable credential`() {
        // The user asked to unpair; a credential the gateway has not yet expired must not resurrect
        // the pairing locally.
        val resolution = ClaudePPairingResolver.resolve(
            settingsState = ClaudePPairingState.REVOKED,
            credentialRead = ClaudePCredentialRead.Present(device()),
            nowEpochSeconds = NOW,
        )

        assertEquals(ClaudePPairingResolution.Unpaired(ClaudePUnpairedReason.REVOKED), resolution)
    }

    @Test
    fun `a usable credential outranks stale settings that say not paired`() {
        // The credential is the thing that can authenticate, so it decides. A half-applied settings
        // write must not strand a device that is genuinely paired.
        val resolution = ClaudePPairingResolver.resolve(
            settingsState = ClaudePPairingState.NOT_PAIRED,
            credentialRead = ClaudePCredentialRead.Present(device()),
            nowEpochSeconds = NOW,
        )

        assertTrue(resolution.isPaired)
    }

    @Test
    fun `a zero or negative expiry is treated as expired rather than as no expiry`() {
        listOf(0L, -1L).forEach { expiry ->
            val resolution = ClaudePPairingResolver.resolve(
                settingsState = ClaudePPairingState.PAIRED,
                credentialRead = ClaudePCredentialRead.Present(device(expiresAt = expiry)),
                nowEpochSeconds = NOW,
            )
            assertEquals(
                "expiry=$expiry must not read as a valid credential",
                ClaudePPairingResolution.Unpaired(ClaudePUnpairedReason.CREDENTIAL_EXPIRED),
                resolution,
            )
        }
    }

    // ---------------------------------------------------------------------------------------
    // Device identity
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a device key can sign, and a different key cannot reproduce the signature`() = runBlocking {
        val key = InMemoryClaudePDeviceKeyStore().createFresh("alias") as InMemoryClaudePDeviceKey
        val other = InMemoryClaudePDeviceKeyStore().createFresh("alias") as InMemoryClaudePDeviceKey
        val payload = "transcript".toByteArray()

        val signature = key.sign(payload)!!
        val otherSignature = other.sign(payload)!!

        // Each key verifies its own signature...
        assertTrue(key.verify(payload, signature))
        assertTrue(other.verify(payload, otherSignature))
        // ...and neither can speak for the other. Two devices must not be interchangeable.
        assertFalse(other.verify(payload, signature))
        assertFalse(key.verify(payload, otherSignature))
    }

    @Test
    fun `an unavailable key reports null rather than throwing`() = runBlocking {
        val key = InMemoryClaudePDeviceKeyStore().createFresh("alias") as InMemoryClaudePDeviceKey
        key.signFails = true
        key.publicKeyFails = true

        // A Keystore key can be permanently invalidated by a device-credential change. That is an
        // expected state, not an exception: it must map onto "unpaired".
        assertNull(key.sign("x".toByteArray()))
        assertNull(key.publicKeyDer())
    }

    // ---------------------------------------------------------------------------------------
    // Creation and loading are separate operations
    // ---------------------------------------------------------------------------------------

    @Test
    fun `loading an absent key returns null and never creates one`() = runBlocking {
        val store = InMemoryClaudePDeviceKeyStore()

        val loaded = store.loadExisting("alias")

        // The runtime handshake uses `loadExisting`. If it could mint a key, a device whose private
        // key was wiped would silently acquire a new identity while still presenting the credential
        // the gateway issued for the old one.
        assertNull(loaded)
        assertEquals(0, store.createCount)
        assertTrue(store.aliases.isEmpty())
    }

    @Test
    fun `createFresh mints a key that loadExisting can then find`() = runBlocking {
        val store = InMemoryClaudePDeviceKeyStore()

        store.createFresh("alias")
        val loaded = store.loadExisting("alias")

        assertEquals(1, store.createCount)
        assertTrue(loaded is InMemoryClaudePDeviceKey)
    }

    @Test
    fun `a broken key still loads as absent rather than being replaced`() = runBlocking {
        val store = InMemoryClaudePDeviceKeyStore()
        store.createFresh("alias")
        store.loadFails = true

        assertNull(store.loadExisting("alias"))
        // Still exactly one creation: the failure did not trigger a replacement.
        assertEquals(1, store.createCount)
        assertEquals(listOf("alias"), store.aliases)
    }

    @Test
    fun `delete reports whether the key is actually gone`() = runBlocking {
        val store = InMemoryClaudePDeviceKeyStore()
        store.createFresh("alias")

        assertTrue("a successful delete must report success", store.delete("alias"))
        assertTrue(store.aliases.isEmpty())

        store.deleteFails = true
        store.createFresh("alias")
        // An unpair that cannot remove the private key has not finished, so this must not be
        // reported as success.
        assertFalse("a failed delete must report failure", store.delete("alias"))
        assertEquals(listOf("alias"), store.aliases)
    }

    @Test
    fun `the handshake transcript binds every field it claims to`() {
        val base = ClaudePHandshakeTranscript.build("device", "nonce", "gateway.example.com", "1.0")

        // Every single-field change must change the transcript, or the signature would not actually
        // cover that field.
        val variants = listOf(
            ClaudePHandshakeTranscript.build("device2", "nonce", "gateway.example.com", "1.0"),
            ClaudePHandshakeTranscript.build("device", "nonce2", "gateway.example.com", "1.0"),
            ClaudePHandshakeTranscript.build("device", "nonce", "evil.example.com", "1.0"),
            ClaudePHandshakeTranscript.build("device", "nonce", "gateway.example.com", "2.0"),
        )

        variants.forEach { variant ->
            assertFalse(
                "a field change must alter the transcript",
                base.contentEquals(variant),
            )
        }
    }

    @Test
    fun `the handshake transcript is not ambiguous under concatenation`() {
        // Without length prefixes, ("ab","c") and ("a","bc") would sign identical bytes.
        val first = ClaudePHandshakeTranscript.build("ab", "c", "d", "e")
        val second = ClaudePHandshakeTranscript.build("a", "bc", "d", "e")

        assertFalse(first.contentEquals(second))
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

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
