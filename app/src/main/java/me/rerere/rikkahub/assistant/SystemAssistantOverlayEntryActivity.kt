package me.rerere.rikkahub.assistant

import android.app.Activity
import android.content.Intent
import android.app.KeyguardManager
import android.os.Bundle
import android.os.UserManager
import android.util.Log

const val SYSTEM_ASSISTANT_HARDWARE_INVOCATION_ACTION =
    "me.rerere.rikkahub.action.SHOW_SYSTEM_ASSISTANT_HARDWARE"

internal fun isSystemAssistantHardwareInvocationAction(action: String?): Boolean =
    action == SYSTEM_ASSISTANT_HARDWARE_INVOCATION_ACTION

/**
 * The exported entry already lives in an isolated task. Forwarding with NEW_TASK would make
 * Android resolve the default RikkaHub affinity and resurrect RouteActivity behind the
 * translucent surface whenever the main app had previously been opened.
 */
internal fun systemAssistantHardwareOverlayLaunchFlags(): Int =
    Intent.FLAG_ACTIVITY_CLEAR_TOP or
        Intent.FLAG_ACTIVITY_SINGLE_TOP or
        Intent.FLAG_ACTIVITY_NO_ANIMATION

/**
 * Minimal exported adapter for OEM hardware-key launchers that require an Activity target.
 *
 * It accepts no prompt or destination extras and opens the Activity-hosted overlay only for the
 * unlocked Android owner. The standard VoiceInteraction session remains reserved for navigation
 * and power-button invocation, so the Honor AI key has an explicit Activity host.
 */
class SystemAssistantOverlayEntryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!isSystemAssistantHardwareInvocationAction(intent?.action)) {
            finish()
            return
        }

        showActivityOverlay()
    }

    private fun showActivityOverlay() {
        val userManager = getSystemService(UserManager::class.java)
        val keyguardManager = getSystemService(KeyguardManager::class.java)
        val mayShow = userManager != null && keyguardManager != null &&
            shouldShowLocalSystemAssistant(
                isSystemUser = userManager.isSystemUser,
                isDeviceLocked = keyguardManager.isDeviceLocked,
                isKeyguardLocked = keyguardManager.isKeyguardLocked,
            )
        if (!mayShow) {
            Log.w(TAG, "Ignoring hardware invocation outside the unlocked owner")
            finish()
            return
        }

        Log.i(TAG, "Opening the activity-hosted AI-key surface")
        val launched = runCatching {
            startActivity(
                Intent(this, SystemAssistantHardwareOverlayActivity::class.java).apply {
                    action = SYSTEM_ASSISTANT_HARDWARE_INVOCATION_ACTION
                    addFlags(systemAssistantHardwareOverlayLaunchFlags())
                },
            )
        }.isSuccess
        if (!launched) {
            Log.e(TAG, "Hardware overlay activity could not be opened; falling back to second-user surface")
            runCatching {
                startActivity(
                    Intent(this, SystemAssistantFallbackActivity::class.java).apply {
                        action = SYSTEM_ASSISTANT_SHORTCUT_ACTION
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
            }.onFailure { Log.e(TAG, "Fallback launch also failed", it) }
        }
        finish()
    }

    private companion object {
        const val TAG = "RikkaAssistHardware"
    }
}
