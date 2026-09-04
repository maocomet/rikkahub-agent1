package me.rerere.rikkahub.security

import android.content.Context
import android.content.Intent
import androidx.biometric.BiometricManager
import java.util.UUID
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.data.ai.tools.local.BiometricResult
import me.rerere.rikkahub.data.ai.tools.local.BiometricResultBuffer
import me.rerere.rikkahub.data.ai.tools.local.ToolHostActivity

/** Opaque, short-lived UI capability issued only after BIOMETRIC_STRONG succeeds. */
class SecondUserAuthorityUserAuthorization internal constructor(internal val issuedAtMs: Long)

/** Shared UI-only biometric bridge. PIN/device credentials are intentionally never enabled. */
class StrongBiometricAuthenticator(
    private val context: Context,
    private val buffer: BiometricResultBuffer,
) {
    /**
     * The only authority-token issuance path.  Keeping the token creation adjacent to the
     * successful BIOMETRIC_STRONG result prevents a caller from manufacturing a fresh-looking
     * token without showing the system biometric prompt.
     */
    suspend fun authorizeSecondUser(
        title: String,
        subtitle: String? = null,
    ): SecondUserAuthorityUserAuthorization? =
        authenticateStrong(title, subtitle)
            .takeIf { it }
            ?.let { SecondUserAuthorityUserAuthorization(System.currentTimeMillis()) }

    /** Same strong-only path for user vault reveal/edit/delete operations. */
    suspend fun authorizeSecretVault(
        title: String,
        subtitle: String? = null,
    ): SecretVaultUserAuthorization? =
        authenticateStrong(title, subtitle)
            .takeIf { it }
            ?.let { SecretVaultUserAuthorization(System.currentTimeMillis()) }

    /** Opens one process-local remote plaintext session; no device-credential fallback. */
    suspend fun authorizeSecretPlaintextSession(
        title: String,
        subtitle: String? = null,
    ): SecretPlaintextSessionAuthorization? =
        authenticateStrong(title, subtitle)
            .takeIf { it }
            ?.let { SecretPlaintextSessionAuthorization(System.currentTimeMillis()) }

    /**
     * Returns a user-presentable reason when the system BIOMETRIC_STRONG prompt cannot be shown,
     * or null when it is ready. Callers must surface a non-null reason instead of silently doing
     * nothing: a bare BiometricPrompt on an unenrolled / no-hardware device only reaches
     * onAuthenticationError inside the host activity, which yields no visible UI by itself.
     */
    fun strongBiometricUnavailableReason(): String? {
        val manager = BiometricManager.from(context)
        return when (manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> null
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                "No strong biometric (fingerprint or face) is enrolled on this device. " +
                    "Open Settings > Security to enroll one, then retry."
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE ->
                "Biometric hardware is temporarily unavailable. Please try again later."
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE ->
                "This device has no strong biometric hardware (fingerprint or face)."
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED ->
                "A security update is required before biometrics can be used."
            BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED ->
                "Strong biometric authentication is not supported on this device."
            BiometricManager.BIOMETRIC_STATUS_UNKNOWN ->
                "Biometric status is unknown; please try again."
            else -> "Biometric authentication is unavailable on this device."
        }
    }

    private suspend fun authenticateStrong(title: String, subtitle: String? = null): Boolean {
        val requestId = UUID.randomUUID().toString()
        val deferred = buffer.register(requestId)
        context.startActivity(
            Intent(context, ToolHostActivity::class.java).apply {
                putExtra(ToolHostActivity.EXTRA_MODE, ToolHostActivity.MODE_BIOMETRIC)
                putExtra(ToolHostActivity.EXTRA_REQUEST_ID, requestId)
                putExtra(ToolHostActivity.EXTRA_BIO_TITLE, title)
                putExtra(ToolHostActivity.EXTRA_BIO_SUBTITLE, subtitle)
                putExtra(ToolHostActivity.EXTRA_BIO_ALLOW_CRED, false)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
        return withTimeoutOrNull(120_000L) { deferred.await() } is BiometricResult.Success
    }
}
