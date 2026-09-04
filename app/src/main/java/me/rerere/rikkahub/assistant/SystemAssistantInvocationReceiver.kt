package me.rerere.rikkahub.assistant

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.UserManager
import android.util.Log

const val SYSTEM_ASSISTANT_TEST_INVOCATION_ACTION =
    "me.rerere.rikkahub.action.SHOW_SYSTEM_ASSISTANT_TEST"
const val SYSTEM_ASSISTANT_ACCESSIBILITY_INVOCATION_ACTION =
    "me.rerere.rikkahub.action.SHOW_SYSTEM_ASSISTANT_ACCESSIBILITY"

/** Same-UID bridge from trusted local surfaces to the active lightweight voice process. */
class SystemAssistantInvocationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ALLOWED_ACTIONS) return
        val userManager = context.getSystemService(UserManager::class.java)
        val keyguardManager = context.getSystemService(KeyguardManager::class.java)
        val mayShow = userManager != null && keyguardManager != null &&
            shouldShowLocalSystemAssistant(
                isSystemUser = userManager.isSystemUser,
                isDeviceLocked = keyguardManager.isDeviceLocked,
                isKeyguardLocked = keyguardManager.isKeyguardLocked,
            )
        if (!mayShow) {
            Log.w(TAG, "Ignoring local invocation outside the unlocked Android owner")
            ack(ok = false, reason = "locked_or_not_owner")
            return
        }
        if (RikkaVoiceInteractionService.showLocalSession()) {
            ack(ok = true, reason = "shown")
            return
        }
        Log.w(TAG, "Voice assistant is not ready for a local invocation")
        // A real trigger (accessibility / quick) must still reach a usable surface. The settings
        // "test current entry" flow reports failure instead, so the UI can say why nothing opened.
        if (intent.action == SYSTEM_ASSISTANT_TEST_INVOCATION_ACTION) {
            ack(ok = false, reason = "voice_service_not_ready")
            return
        }
        val fallbackLaunched = runCatching {
            context.startActivity(
                Intent(context, SystemAssistantFallbackActivity::class.java).apply {
                    action = SYSTEM_ASSISTANT_SHORTCUT_ACTION
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
            true
        }.getOrDefault(false)
        if (!fallbackLaunched) {
            Log.e(TAG, "Voice assistant unavailable and the fallback surface could not be opened")
        }
        ack(ok = fallbackLaunched, reason = if (fallbackLaunched) "fallback" else "voice_service_not_ready")
    }

    /** Reports the invocation outcome to an ordered-broadcast caller (if any). */
    private fun ack(ok: Boolean, reason: String) {
        runCatching {
            setResultCode(if (ok) Activity.RESULT_OK else Activity.RESULT_CANCELED)
            setResultExtras(Bundle().apply { putString("reason", reason) })
        }
    }

    private companion object {
        const val TAG = "RikkaVoiceLocal"
        val ALLOWED_ACTIONS = setOf(
            SYSTEM_ASSISTANT_TEST_INVOCATION_ACTION,
            SYSTEM_ASSISTANT_ACCESSIBILITY_INVOCATION_ACTION,
        )
    }
}

internal fun shouldShowLocalSystemAssistant(
    isSystemUser: Boolean,
    isDeviceLocked: Boolean,
    isKeyguardLocked: Boolean,
): Boolean = isSystemUser && !isDeviceLocked && !isKeyguardLocked
