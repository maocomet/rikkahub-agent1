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
 * actually holds across the supported range. `Signature.getInstance("SHA256withECDSA")` verifies
 * on any JVM or server runtime without a special provider.
 *
 * ### Fail-closed, everywhere
 *
 * Every method returns `null` rather than throwing when the key is missing, unreadable or
 * unusable. A Keystore key can be permanently invalidated by a device-credential change, and a
 * device restore can leave an alias present but undecryptable. Those are ordinary states, and the
 * only safe mapping for them is "this device is not paired" — never an exception a caller might
 * swallow and continue past.
 */
class AndroidKeystoreClaudePDeviceKeyStore : ClaudePDeviceKeyStore {

    override suspend fun loadOrCreate(keyAlias: String): ClaudePDeviceKey? {
        if (keyAlias.isBlank()) return null
        return try {
            val entry = loadKeyStore().getEntry(keyAlias, null)
            if (entry is KeyStore.PrivateKeyEntry) {
                // The Keystore stores a self-signed certificate alongside an asymmetric key pair;
                // its public key is how the public half is recovered for the pairing request and for
                // the handshake transcript.
                AndroidKeystoreDeviceKey(keyAlias, entry.privateKey, entry.certificate?.publicKey)
            } else if (entry != null) {
                // Something else is sitting under our alias. Refusing is the only safe answer: it is
                // not our key, and overwriting it could destroy another feature's material.
                Log.w(TAG, "Claude P key alias holds a non-private-key entry; refusing to use it")
                null
            } else {
                generateKeyPair(keyAlias)
            }
        } catch (t: Throwable) {
            // Covers UnrecoverableKeyException, KeyPermanentlyInvalidatedException, provider
            // failures and a corrupt keystore alike. The distinction is not actionable here; the
            // outcome is the same and the message may name key material.
            Log.w(TAG, "Claude P device key unavailable: ${t::class.java.simpleName}")
            null
        }
    }

    override suspend fun delete(keyAlias: String) {
        try {
            loadKeyStore().apply { deleteEntry(keyAlias) }
        } catch (t: Throwable) {
            Log.w(TAG, "Claude P device key deletion failed: ${t::class.java.simpleName}")
        }
    }

    private fun generateKeyPair(keyAlias: String): ClaudePDeviceKey? {
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
