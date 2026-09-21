package me.rerere.rikkahub.data.claudep

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import me.rerere.ai.provider.claudep.ClaudePDeviceKey
import me.rerere.ai.provider.claudep.ClaudePDeviceKeyStore

/**
 * The device's non-exportable signing key, held in the Android Keystore.
 *
 * ### Why EC P-256 and not Ed25519
 *
 * `claudep/01-architecture-and-trust-boundaries.md` §4 allows either. Ed25519 is only available in
 * the Android Keystore from API 33, and this module's `minSdk` is 26, so P-256 is the choice that
 * actually holds across the supported range. `Signature.getInstance("SHA256withECDSA")` verifies on
 * any JVM or server runtime without a special provider.
 *
 * ### Creation and loading are separate, deliberately
 *
 * [createFresh] is the only method that mints a key, and only the pairing path calls it.
 * [loadExisting] never creates one. The runtime handshake uses the latter, so a device whose private
 * key is gone — after a restore, a Keystore invalidation, or a wipe — fails closed instead of quietly
 * generating a replacement and presenting it next to a credential the gateway issued for a different
 * identity.
 *
 * ### Fail-closed, everywhere
 *
 * Every method returns `null`/`false` rather than throwing when the key is missing, unreadable or
 * unusable. A Keystore key can be permanently invalidated by a device-credential change, and a
 * device restore can leave an alias present but undecryptable. Those are ordinary states, and the
 * only safe mapping for them is "this device cannot prove its identity" — never an exception a caller
 * might swallow and continue past.
 */
class AndroidKeystoreClaudePDeviceKeyStore : ClaudePDeviceKeyStore {

    override suspend fun createFresh(keyAlias: String): ClaudePDeviceKey? {
        if (keyAlias.isBlank()) return null
        return try {
            val keyStore = loadKeyStore()
            // Explicit replacement. `createFresh` is the one operation permitted to supersede an
            // identity, and pairing only calls it with a fresh per-attempt alias.
            if (keyStore.containsAlias(keyAlias)) keyStore.deleteEntry(keyAlias)
            generateKeyPair(keyAlias)
        } catch (t: Throwable) {
            Log.w(TAG, "Claude P device key creation failed: ${t::class.java.simpleName}")
            null
        }
    }

    override suspend fun loadExisting(keyAlias: String): ClaudePDeviceKey? {
        if (keyAlias.isBlank()) return null
        return try {
            val keyStore = loadKeyStore()
            // Checked explicitly so a missing alias is a definite `null` rather than relying on the
            // provider's behaviour — and so no code path here can ever fall through to generating.
            if (!keyStore.containsAlias(keyAlias)) return null

            val entry = keyStore.getEntry(keyAlias, null)
            if (entry is KeyStore.PrivateKeyEntry) {
                // The Keystore stores a self-signed certificate alongside an asymmetric key pair;
                // its public key is how the public half is recovered for the pairing request and for
                // the handshake transcript.
                AndroidKeystoreDeviceKey(keyAlias, entry.privateKey, entry.certificate?.publicKey)
            } else {
                // Something else is under our alias. Refusing is the only safe answer: it is not our
                // key, and an earlier revision would have overwritten it here.
                Log.w(TAG, "Claude P key alias holds a non-private-key entry; refusing to use it")
                null
            }
        } catch (t: Throwable) {
            // UnrecoverableKeyException, KeyPermanentlyInvalidatedException, provider failures and a
            // corrupt keystore all land here. The distinction is not actionable; the message may
            // name key material, so only the class name is logged.
            Log.w(TAG, "Claude P device key unavailable: ${t::class.java.simpleName}")
            null
        }
    }

    /**
     * Deletes the key and reports whether it is actually gone.
     *
     * `false` is a real outcome, not a warning: an unpair that could not remove the private key has
     * not removed the device's ability to authenticate.
     */
    override suspend fun delete(keyAlias: String): Boolean = try {
        val keyStore = loadKeyStore()
        if (keyStore.containsAlias(keyAlias)) keyStore.deleteEntry(keyAlias)
        !keyStore.containsAlias(keyAlias)
    } catch (t: Throwable) {
        Log.w(TAG, "Claude P device key deletion failed: ${t::class.java.simpleName}")
        false
    }

    private fun generateKeyPair(keyAlias: String): ClaudePDeviceKey {
        val generator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            ANDROID_KEYSTORE,
        )
        generator.initialize(
            KeyGenParameterSpec.Builder(
                keyAlias,
                // SIGN only. A VERIFY-purpose key would be pointless (the public half is public) and
                // the narrower purpose is easier to reason about.
                KeyProperties.PURPOSE_SIGN,
            )
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setAlgorithmParameterSpec(ECGenParameterSpec(CURVE))
                // Never require user authentication: pairing and reconnecting must work when the
                // screen is off, and an auth-bound key would silently start failing there.
                .setUserAuthenticationRequired(false)
                .build(),
        )
        val keyPair = generator.generateKeyPair()
        return AndroidKeystoreDeviceKey(keyAlias, keyPair.private, keyPair.public)
    }

    private fun loadKeyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private companion object {
        const val TAG = "ClaudePKeyStore"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val CURVE = "secp256r1"
    }
}

/**
 * One Keystore-held device key.
 *
 * The private key never leaves the Keystore: [sign] hands the payload to the provider and gets a
 * signature back, and nothing in this class can export the key itself.
 */
private class AndroidKeystoreDeviceKey(
    override val keyAlias: String,
    private val privateKey: PrivateKey,
    private val publicKey: PublicKey?,
) : ClaudePDeviceKey {

    /** X.509 SubjectPublicKeyInfo, or `null` when the entry had no recoverable public half. */
    override suspend fun publicKeyDer(): ByteArray? = try {
        publicKey?.encoded
    } catch (t: Throwable) {
        Log.w(TAG, "Claude P public key unavailable: ${t::class.java.simpleName}")
        null
    }

    override suspend fun sign(payload: ByteArray): ByteArray? = try {
        Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initSign(privateKey)
            update(payload)
            sign()
        }
    } catch (t: Throwable) {
        Log.w(TAG, "Claude P device key signing failed: ${t::class.java.simpleName}")
        null
    }

    private companion object {
        const val TAG = "ClaudePKeyStore"
        const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    }
}
