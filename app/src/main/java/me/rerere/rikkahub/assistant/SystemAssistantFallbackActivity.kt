package me.rerere.rikkahub.assistant

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import me.rerere.rikkahub.RouteActivity
import org.koin.android.ext.android.inject

/**
 * Honest fallback for devices whose OEM keeps hardware assistant gestures bound to its own
 * VoiceInteractionService. It resolves the configured Assistant again on every launch and opens
 * only that Assistant's verified second-user conversation in the full chat UI.
 *
 * No prompt or model submission is accepted through the exported intent. Calls made while the
 * device is locked, or outside Android user 0, are dismissed and cannot become usable after a
 * later unlock.
 */
class SystemAssistantFallbackActivity : ComponentActivity() {
    private val targetResolver: SecondUserTargetResolver by inject()
    private val accessState: SystemAssistantAccessState by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!isSystemAssistantFallbackAction(intent?.action)) {
            finish()
            return
        }

        val ownerUser = runCatching(accessState::isOwnerUser).getOrDefault(false)
        val deviceLocked = runCatching(accessState::isDeviceLocked).getOrDefault(true)
        if (!ownerUser || deviceLocked) {
            finish()
            return
        }

        lifecycleScope.launch {
            val resolution = runCatching { targetResolver.resolve() }.getOrNull()
            val lockedBeforeNavigation = runCatching(accessState::isDeviceLocked).getOrDefault(true)
            when (
                val destination = decideSystemAssistantFallbackDestination(
                    ownerUser = ownerUser,
                    deviceLocked = deviceLocked || lockedBeforeNavigation,
                    targetResolution = resolution,
                )
            ) {
                is SystemAssistantFallbackDestination.Conversation -> openMainApp(
                    RouteActivity.EXTRA_CONVERSATION_ID,
                    destination.conversationId.toString(),
                )
                SystemAssistantFallbackDestination.Configuration -> openMainApp(
                    RouteActivity.EXTRA_OPEN_SYSTEM_ASSISTANT_SETTINGS,
                    true,
                )
                SystemAssistantFallbackDestination.Dismiss -> Unit
            }
            finish()
        }
    }

    private fun openMainApp(extra: String, value: Any) {
        runCatching {
            startActivity(
                Intent(this, RouteActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    when (value) {
                        is Boolean -> putExtra(extra, value)
                        is String -> putExtra(extra, value)
                    }
                }
            )
        }.onFailure { error ->
            Log.e(TAG, "Failed to open the main RikkaHub surface", error)
            runCatching {
                android.widget.Toast.makeText(
                    this,
                    "无法打开 RikkaHub：${error.message ?: error.javaClass.simpleName}",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private companion object {
        const val TAG = "SystemAssistantFallback"
    }
}
