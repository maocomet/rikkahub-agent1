package me.rerere.rikkahub.assistant

import android.app.Activity
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.voice.VoiceInteractionService
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** How long to wait for the lightweight voice process to acknowledge a shown session. */
private const val TEST_ACK_TIMEOUT_MS = 3_000L

data class SystemAssistantRoleState(
    val roleAvailable: Boolean,
    val roleHeld: Boolean,
    val voiceServiceDeclared: Boolean,
    val voiceServiceActive: Boolean,
    val accessibilityShortcutServiceDeclared: Boolean,
    val accessibilityShortcutServiceEnabled: Boolean,
    val accessibilityShortcutSelected: Boolean,
    val fallbackAssistDeclared: Boolean,
    val systemAssistResolvesToFallback: Boolean,
    val resolvedSystemAssistComponent: ComponentName?,
    val manageAssistSettingsAvailable: Boolean,
    val manageAccessibilitySettingsAvailable: Boolean,
    val magicVoiceInstalled: Boolean,
    val magicVoiceEnabled: Boolean,
    val magicVoiceRecoveryStep: MagicVoiceRecoveryStep,
)

enum class MagicVoiceRecoveryStep {
    EnablePackage,
    SelectAssistant,
    SnapshotRequired,
}

internal fun magicVoiceRecoveryStep(
    installed: Boolean,
    enabled: Boolean,
): MagicVoiceRecoveryStep = when {
    !installed -> MagicVoiceRecoveryStep.SnapshotRequired
    !enabled -> MagicVoiceRecoveryStep.EnablePackage
    else -> MagicVoiceRecoveryStep.SelectAssistant
}

internal fun supportsSystemAssistantVoiceService(sdkInt: Int): Boolean = sdkInt >= 26

internal fun isComponentSelected(rawValue: String?, component: String): Boolean {
    val expected = canonicalComponentName(component) ?: return false
    return rawValue
        ?.split(':')
        ?.mapNotNull(::canonicalComponentName)
        ?.any { it == expected } == true
}

internal fun isAccessibilityServiceEnabled(
    accessibilityEnabled: Boolean,
    rawValue: String?,
    component: String,
): Boolean = accessibilityEnabled && isComponentSelected(rawValue, component)

private fun canonicalComponentName(value: String): Pair<String, String>? {
    val separator = value.indexOf('/')
    if (separator <= 0 || separator == value.lastIndex) return null
    val packageName = value.substring(0, separator)
    val rawClassName = value.substring(separator + 1)
    val className = if (rawClassName.startsWith('.')) {
        packageName + rawClassName
    } else {
        rawClassName
    }
    return packageName to className
}

internal fun shouldRequestSystemAssistantRole(
    roleAvailable: Boolean,
    roleHeld: Boolean,
    voiceServiceActive: Boolean,
): Boolean = roleAvailable && !roleHeld && !voiceServiceActive

/**
 * Read-only role/activation diagnostics plus user-mediated intents for selecting RikkaHub as
 * the system assistant. No method writes secure settings or silently changes the active role.
 */
class SystemAssistantRoleController(context: Context) {
    private val appContext = context.applicationContext

    val voiceInteractionServiceComponent: ComponentName = ComponentName(
        appContext,
        RikkaVoiceInteractionService::class.java,
    )
    val fallbackAssistActivityComponent: ComponentName = ComponentName(
        appContext,
        SystemAssistantFallbackActivity::class.java,
    )
    val accessibilityShortcutServiceComponent: ComponentName = ComponentName(
        appContext,
        SystemAssistantAccessibilityButtonService::class.java,
    )
    private val testInvocationReceiverComponent: ComponentName = ComponentName(
        appContext,
        SystemAssistantInvocationReceiver::class.java,
    )

    fun snapshot(): SystemAssistantRoleState {
        val roleManager = assistantRoleManager()
        val resolvedAssistComponent = resolveActivityComponent(Intent(Intent.ACTION_ASSIST))
        val magicVoiceState = packageState(MAGIC_VOICE_PACKAGE_NAME)
        val shortcutComponent = accessibilityShortcutServiceComponent.flattenToString()
        val accessibilityEnabled = runCatching {
            Settings.Secure.getInt(
                appContext.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                0,
            ) == 1
        }.getOrDefault(false)
        val enabledAccessibilityServices = runCatching {
            Settings.Secure.getString(
                appContext.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
        }.getOrNull()
        val accessibilityShortcutTarget = runCatching {
            Settings.Secure.getString(
                appContext.contentResolver,
                ACCESSIBILITY_SHORTCUT_TARGET_SERVICE,
            )
        }.getOrNull()
        return SystemAssistantRoleState(
            roleAvailable = supportsSafeVoiceAssistant() &&
                roleManager?.isRoleAvailable(RoleManager.ROLE_ASSISTANT) == true,
            roleHeld = supportsSafeVoiceAssistant() &&
                roleManager?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true,
            voiceServiceDeclared = isVoiceServiceDeclared(),
            voiceServiceActive = runCatching {
                VoiceInteractionService.isActiveService(
                    appContext,
                    voiceInteractionServiceComponent,
                )
            }.getOrDefault(false),
            accessibilityShortcutServiceDeclared = isAccessibilityShortcutServiceDeclared(),
            accessibilityShortcutServiceEnabled = isAccessibilityServiceEnabled(
                accessibilityEnabled,
                enabledAccessibilityServices,
                shortcutComponent,
            ),
            accessibilityShortcutSelected = isComponentSelected(
                accessibilityShortcutTarget,
                shortcutComponent,
            ),
            fallbackAssistDeclared = isFallbackAssistActivityDeclared(),
            systemAssistResolvesToFallback = resolvedAssistComponent == fallbackAssistActivityComponent,
            resolvedSystemAssistComponent = resolvedAssistComponent,
            manageAssistSettingsAvailable = canResolve(createVoiceInputSettingsIntent()),
            manageAccessibilitySettingsAvailable = canResolve(createAccessibilitySettingsIntent()),
            magicVoiceInstalled = magicVoiceState.installed,
            magicVoiceEnabled = magicVoiceState.enabled,
            magicVoiceRecoveryStep = magicVoiceRecoveryStep(
                installed = magicVoiceState.installed,
                enabled = magicVoiceState.enabled,
            ),
        )
    }

    /**
     * Returns the platform role request where supported, otherwise the system's assistant/
     * voice-input settings. The caller remains responsible for launching it from an Activity.
     */
    fun createRoleRequestOrSettingsIntent(): Intent {
        val roleManager = assistantRoleManager()
        val state = snapshot()
        return if (roleManager != null && shouldRequestSystemAssistantRole(
                roleAvailable = state.roleAvailable,
                roleHeld = state.roleHeld,
                voiceServiceActive = state.voiceServiceActive,
            )
        ) {
            roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
        } else {
            createVoiceInputSettingsIntent()
        }
    }

    fun createVoiceInputSettingsIntent(): Intent = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)

    fun createAccessibilitySettingsIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    fun createMagicVoiceAppDetailsIntent(): Intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:$MAGIC_VOICE_PACKAGE_NAME"),
    )

    fun createFallbackAssistIntent(): Intent = Intent(SYSTEM_ASSISTANT_SHORTCUT_ACTION).apply {
        component = fallbackAssistActivityComponent
    }

    /**
     * Sends the test invocation and waits for the lightweight voice process to acknowledge that a
     * session was actually shown (ordered broadcast with a short timeout). Returns true only on a
     * confirmed "shown"; it no longer treats "intent sent without an exception" as success.
     */
    suspend fun requestCurrentSystemAssistant(): Boolean {
        if (!snapshot().voiceServiceActive) return false
        val mainHandler = Handler(Looper.getMainLooper())
        return withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { cont ->
                val ackReceiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        mainHandler.removeCallbacksAndMessages(null)
                        if (cont.isActive) {
                            val ok = resultCode == Activity.RESULT_OK
                            runCatching { context.unregisterReceiver(this) }
                            cont.resume(ok)
                        }
                    }
                }
                val registered = runCatching {
                    ContextCompat.registerReceiver(
                        appContext,
                        ackReceiver,
                        IntentFilter(SYSTEM_ASSISTANT_TEST_INVOCATION_ACTION),
                        ContextCompat.RECEIVER_NOT_EXPORTED,
                    )
                    true
                }.getOrDefault(false)
                if (!registered) {
                    cont.resume(false)
                    return@suspendCancellableCoroutine
                }
                val timeout = Runnable {
                    if (cont.isActive) {
                        runCatching { appContext.unregisterReceiver(ackReceiver) }
                        cont.resume(false)
                    }
                }
                mainHandler.postDelayed(timeout, TEST_ACK_TIMEOUT_MS)
                cont.invokeOnCancellation {
                    mainHandler.removeCallbacks(timeout)
                    runCatching { appContext.unregisterReceiver(ackReceiver) }
                }
                val sent = runCatching {
                    appContext.sendOrderedBroadcast(
                        Intent(SYSTEM_ASSISTANT_TEST_INVOCATION_ACTION).apply {
                            component = testInvocationReceiverComponent
                        },
                        null,
                        ackReceiver,
                        mainHandler,
                        Activity.RESULT_CANCELED,
                        null,
                        null,
                    )
                    true
                }.getOrDefault(false)
                if (!sent) {
                    mainHandler.removeCallbacks(timeout)
                    if (cont.isActive) {
                        runCatching { appContext.unregisterReceiver(ackReceiver) }
                        cont.resume(false)
                    }
                }
            }
        }
    }

    private fun assistantRoleManager(): RoleManager? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        appContext.getSystemService(RoleManager::class.java)
    } else {
        null
    }

    private fun supportsSafeVoiceAssistant(): Boolean =
        supportsSystemAssistantVoiceService(Build.VERSION.SDK_INT)

    @Suppress("DEPRECATION")
    private fun isVoiceServiceDeclared(): Boolean = runCatching {
        val intent = Intent(VoiceInteractionService.SERVICE_INTERFACE).setPackage(appContext.packageName)
        val services = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.packageManager.queryIntentServices(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
            )
        } else {
            appContext.packageManager.queryIntentServices(intent, PackageManager.GET_META_DATA)
        }
        services.any { resolved ->
            val info = resolved.serviceInfo ?: return@any false
            ComponentName(info.packageName, info.name) == voiceInteractionServiceComponent &&
                info.enabled &&
                info.exported &&
                info.permission == android.Manifest.permission.BIND_VOICE_INTERACTION &&
                info.metaData?.containsKey(VoiceInteractionService.SERVICE_META_DATA) == true
        }
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    private fun isFallbackAssistActivityDeclared(): Boolean = runCatching {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.packageManager.getActivityInfo(
                fallbackAssistActivityComponent,
                PackageManager.ComponentInfoFlags.of(0L),
            )
        } else {
            appContext.packageManager.getActivityInfo(fallbackAssistActivityComponent, 0)
        }
        info.enabled && info.exported
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    private fun isAccessibilityShortcutServiceDeclared(): Boolean = runCatching {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.packageManager.getServiceInfo(
                accessibilityShortcutServiceComponent,
                PackageManager.ComponentInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
            )
        } else {
            appContext.packageManager.getServiceInfo(
                accessibilityShortcutServiceComponent,
                PackageManager.GET_META_DATA,
            )
        }
        info.enabled && info.exported &&
            info.permission == android.Manifest.permission.BIND_ACCESSIBILITY_SERVICE
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    private fun resolveActivityComponent(intent: Intent): ComponentName? = runCatching {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.packageManager.resolveActivity(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
            )
        } else {
            appContext.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }?.activityInfo ?: return@runCatching null
        ComponentName(info.packageName, info.name)
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun canResolve(intent: Intent): Boolean =
        appContext.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null

    @Suppress("DEPRECATION")
    private fun packageState(packageName: String): PackageState = runCatching {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.packageManager.getApplicationInfo(
                packageName,
                PackageManager.ApplicationInfoFlags.of(PackageManager.MATCH_DISABLED_COMPONENTS.toLong()),
            )
        } else {
            appContext.packageManager.getApplicationInfo(
                packageName,
                PackageManager.MATCH_DISABLED_COMPONENTS,
            )
        }
        PackageState(installed = true, enabled = info.enabled)
    }.getOrDefault(PackageState(installed = false, enabled = false))

    private data class PackageState(
        val installed: Boolean,
        val enabled: Boolean,
    )

    companion object {
        const val MAGIC_VOICE_PACKAGE_NAME = "com.hihonor.magicvoice"
        private const val ACCESSIBILITY_SHORTCUT_TARGET_SERVICE =
            "accessibility_shortcut_target_service"
    }
}
