package me.rerere.rikkahub.ui.pages.setting

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.assistant.SecondUserTargetResolution
import me.rerere.rikkahub.assistant.SecondUserTargetResolver
import me.rerere.rikkahub.assistant.MagicVoiceRecoveryStep
import me.rerere.rikkahub.assistant.SystemAssistantRoleController
import me.rerere.rikkahub.assistant.SystemAssistantRoleState
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.Select
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

private const val MAGIC_VOICE_COMPONENT =
    "com.hihonor.magicvoice/com.hihonor.magicvoice.voiceui.service.MagicVoiceInteractionService"

private data class AssistantTargetOption(
    val id: Uuid?,
    val label: String,
)

@Composable
fun SettingSystemAssistantPage(
    settingsStore: SettingsStore = koinInject(),
    targetResolver: SecondUserTargetResolver = koinInject(),
    roleController: SystemAssistantRoleController = koinInject(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var refreshGeneration by remember { mutableIntStateOf(0) }
    var resolution by remember { mutableStateOf<SecondUserTargetResolution?>(null) }
    val roleState = remember(refreshGeneration) { roleController.snapshot() }
    val launchFailed = stringResource(R.string.system_assistant_launch_failed)
    val notSelectedLabel = stringResource(R.string.system_assistant_not_selected)
    val assistantOptions = listOf(
        AssistantTargetOption(id = null, label = notSelectedLabel)
    ) + settings.assistants.map { assistant ->
        AssistantTargetOption(
            id = assistant.id,
            label = assistant.name.ifBlank { assistant.id.toString() },
        )
    }

    val roleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        refreshGeneration++
    }
    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        refreshGeneration++
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshGeneration++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(settings.systemAssistantTargetAssistantId, refreshGeneration) {
        resolution = targetResolver.resolve()
    }

    fun launch(intent: Intent, forResult: Boolean = false) {
        runCatching {
            if (forResult) settingsLauncher.launch(intent) else context.startActivity(intent)
        }.onFailure {
            Toast.makeText(context, launchFailed, Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.system_assistant_settings_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.system_assistant_settings_intro),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            item {
                TargetCard(
                    selectedAssistantId = settings.systemAssistantTargetAssistantId,
                    assistantOptions = assistantOptions,
                    resolution = resolution,
                    assistantName = { assistantId ->
                        settings.assistants.firstOrNull { it.id == assistantId }
                            ?.name
                            ?.ifBlank { assistantId.toString() }
                            ?: assistantId.toString()
                    },
                    onSelect = { assistantId ->
                        scope.launch {
                            settingsStore.update { current ->
                                current.copy(systemAssistantTargetAssistantId = assistantId)
                            }
                        }
                    },
                )
            }
            item {
                RoleStatusCard(roleState)
            }
            item {
                Card {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.system_assistant_shortcut_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(R.string.system_assistant_shortcut_desc),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(
                            onClick = {
                                launch(
                                    roleController.createAccessibilitySettingsIntent(),
                                    forResult = true,
                                )
                            },
                            enabled = roleState.manageAccessibilitySettingsAvailable,
                        ) {
                            Text(
                                stringResource(
                                    R.string.system_assistant_open_accessibility_settings
                                )
                            )
                        }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            runCatching {
                                roleLauncher.launch(roleController.createRoleRequestOrSettingsIntent())
                            }.onFailure {
                                Toast.makeText(context, launchFailed, Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(
                                if (roleState.roleHeld && !roleState.voiceServiceActive) {
                                    R.string.system_assistant_open_assist_settings
                                } else {
                                    R.string.system_assistant_request_role
                                },
                            ),
                        )
                    }
                    TextButton(
                        onClick = {
                            launch(roleController.createVoiceInputSettingsIntent(), forResult = true)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.system_assistant_open_assist_settings))
                    }
                    Button(
                        onClick = { launch(roleController.createFallbackAssistIntent()) },
                        enabled = roleState.fallbackAssistDeclared,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.system_assistant_open_fixed_target))
                    }
                    TextButton(
                        onClick = {
                            scope.launch {
                                if (!roleController.requestCurrentSystemAssistant()) {
                                    Toast.makeText(context, launchFailed, Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        enabled = roleState.voiceServiceActive,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.system_assistant_test_current_entry))
                    }
                    TextButton(
                        onClick = {
                            copyDiagnostics(
                                context = context,
                                roleState = roleState,
                                resolution = resolution,
                                selectedAssistantId = settings.systemAssistantTargetAssistantId,
                                voiceServiceComponent = roleController.voiceInteractionServiceComponent
                                    .flattenToShortString(),
                            )
                            Toast.makeText(
                                context,
                                context.getString(R.string.system_assistant_diagnostics_copied),
                                Toast.LENGTH_SHORT,
                            ).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.system_assistant_copy_diagnostics))
                    }
                }
            }
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(
                                if (roleState.voiceServiceActive) {
                                    R.string.system_assistant_activation_active
                                } else {
                                    R.string.system_assistant_activation_oem_limited
                                },
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = stringResource(R.string.system_assistant_voice_input_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            text = stringResource(R.string.system_assistant_restore_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(
                                when (roleState.magicVoiceRecoveryStep) {
                                    MagicVoiceRecoveryStep.EnablePackage ->
                                        R.string.system_assistant_restore_disabled_desc
                                    MagicVoiceRecoveryStep.SelectAssistant ->
                                        R.string.system_assistant_restore_desc
                                    MagicVoiceRecoveryStep.SnapshotRequired ->
                                        R.string.system_assistant_restore_missing_desc
                                },
                                MAGIC_VOICE_COMPONENT,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (roleState.magicVoiceRecoveryStep == MagicVoiceRecoveryStep.EnablePackage) {
                            TextButton(
                                onClick = {
                                    launch(
                                        roleController.createMagicVoiceAppDetailsIntent(),
                                        forResult = true,
                                    )
                                },
                            ) {
                                Text(stringResource(R.string.system_assistant_enable_magicvoice))
                            }
                        }
                        TextButton(
                            onClick = {
                                launch(roleController.createVoiceInputSettingsIntent(), forResult = true)
                            },
                            enabled = roleState.magicVoiceRecoveryStep ==
                                MagicVoiceRecoveryStep.SelectAssistant,
                        ) {
                            Text(stringResource(R.string.system_assistant_open_assist_settings))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TargetCard(
    selectedAssistantId: Uuid?,
    assistantOptions: List<AssistantTargetOption>,
    resolution: SecondUserTargetResolution?,
    assistantName: (Uuid) -> String,
    onSelect: (Uuid?) -> Unit,
) {
    val selected = assistantOptions.firstOrNull { it.id == selectedAssistantId }
        ?: assistantOptions.first()
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.system_assistant_fixed_target_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.system_assistant_fixed_target_desc),
                style = MaterialTheme.typography.bodySmall,
            )
            Select(
                options = assistantOptions,
                selectedOption = selected,
                onOptionSelected = { onSelect(it.id) },
                optionToString = { it.label },
                modifier = Modifier.fillMaxWidth(),
            )
            val status = targetStatusText(resolution, assistantName)
            Text(
                text = status.first,
                color = if (status.second) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun targetStatusText(
    resolution: SecondUserTargetResolution?,
    assistantName: (Uuid) -> String,
): Pair<String, Boolean> = when (resolution) {
    null -> stringResource(R.string.system_assistant_loading) to false
    SecondUserTargetResolution.TargetNotSelected ->
        stringResource(R.string.system_assistant_target_not_selected) to false
    is SecondUserTargetResolution.AssistantNotFound ->
        stringResource(R.string.system_assistant_target_assistant_missing) to false
    is SecondUserTargetResolution.PrivilegedConversationNotConfigured ->
        stringResource(R.string.system_assistant_target_conversation_unconfigured) to false
    is SecondUserTargetResolution.ConversationNotFound ->
        stringResource(R.string.system_assistant_target_conversation_missing) to false
    is SecondUserTargetResolution.ConversationAssistantMismatch ->
        stringResource(R.string.system_assistant_target_conversation_mismatch) to false
    is SecondUserTargetResolution.Resolved -> stringResource(
        R.string.system_assistant_target_ready,
        assistantName(resolution.assistantId),
        resolution.displayName,
    ) to true
}

@Composable
private fun RoleStatusCard(state: SystemAssistantRoleState) {
    Card {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Text(
                text = stringResource(R.string.system_assistant_role_status_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            RoleStatusRow(R.string.system_assistant_role_available, state.roleAvailable)
            RoleStatusRow(R.string.system_assistant_role_held, state.roleHeld)
            RoleStatusRow(R.string.system_assistant_service_declared, state.voiceServiceDeclared)
            RoleStatusRow(R.string.system_assistant_service_active, state.voiceServiceActive)
            RoleStatusRow(
                R.string.system_assistant_shortcut_service_declared,
                state.accessibilityShortcutServiceDeclared,
            )
            RoleStatusRow(
                R.string.system_assistant_shortcut_service_enabled,
                state.accessibilityShortcutServiceEnabled,
            )
            RoleStatusRow(
                R.string.system_assistant_shortcut_selected,
                state.accessibilityShortcutSelected,
            )
            RoleStatusRow(
                R.string.system_assistant_fallback_declared,
                state.fallbackAssistDeclared,
            )
            RoleStatusRow(
                R.string.system_assistant_fallback_resolved,
                state.systemAssistResolvesToFallback,
            )
            RoleStatusRow(
                R.string.system_assistant_manage_settings_available,
                state.manageAssistSettingsAvailable,
            )
            RoleStatusRow(
                R.string.system_assistant_accessibility_settings_available,
                state.manageAccessibilitySettingsAvailable,
            )
        }
    }
}

@Composable
private fun RoleStatusRow(label: Int, ready: Boolean) {
    ListItem(
        headlineContent = { Text(stringResource(label)) },
        trailingContent = {
            Text(
                text = stringResource(
                    if (ready) R.string.system_assistant_status_yes else R.string.system_assistant_status_no,
                ),
                color = if (ready) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
            )
        },
    )
}

private fun copyDiagnostics(
    context: Context,
    roleState: SystemAssistantRoleState,
    resolution: SecondUserTargetResolution?,
    selectedAssistantId: Uuid?,
    voiceServiceComponent: String,
) {
    val summary = buildString {
        appendLine("RikkaHub system assistant diagnostics")
        appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("sdk=${Build.VERSION.SDK_INT}")
        appendLine("voiceServiceComponent=$voiceServiceComponent")
        appendLine("voiceServiceDeclared=${roleState.voiceServiceDeclared}")
        appendLine("active=${roleState.voiceServiceActive}")
        appendLine(
            "accessibilityShortcutServiceDeclared=" +
                roleState.accessibilityShortcutServiceDeclared
        )
        appendLine(
            "accessibilityShortcutServiceEnabled=" +
                roleState.accessibilityShortcutServiceEnabled
        )
        appendLine("accessibilityShortcutSelected=${roleState.accessibilityShortcutSelected}")
        appendLine("fallbackAssistDeclared=${roleState.fallbackAssistDeclared}")
        appendLine("systemAssistResolvesToFallback=${roleState.systemAssistResolvesToFallback}")
        appendLine(
            "resolvedSystemAssistComponent=" +
                (roleState.resolvedSystemAssistComponent?.flattenToShortString() ?: "none")
        )
        appendLine("roleAvailable=${roleState.roleAvailable}")
        appendLine("roleHeld=${roleState.roleHeld}")
        appendLine("manageSettings=${roleState.manageAssistSettingsAvailable}")
        appendLine(
            "manageAccessibilitySettings=" +
                roleState.manageAccessibilitySettingsAvailable
        )
        appendLine("magicVoiceInstalled=${roleState.magicVoiceInstalled}")
        appendLine("magicVoiceEnabled=${roleState.magicVoiceEnabled}")
        appendLine("magicVoiceRecoveryStep=${roleState.magicVoiceRecoveryStep}")
        appendLine("targetAssistantId=${selectedAssistantId ?: "none"}")
        appendLine("targetResolution=${resolution?.javaClass?.simpleName ?: "loading"}")
        appendLine("magicVoiceComponent=$MAGIC_VOICE_COMPONENT")
        appendLine("adb shell settings get --user 0 secure voice_interaction_service")
        appendLine("adb shell settings get --user 0 secure voice_recognition_service")
        appendLine("adb shell dumpsys voiceinteraction")
        appendLine("adb shell cmd role get-role-holders android.app.role.ASSISTANT")
        appendLine("adb shell cmd package query-services -a android.service.voice.VoiceInteractionService")
        append("adb shell cmd package query-activities -a android.intent.action.ASSIST")
    }
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard?.setPrimaryClip(ClipData.newPlainText("RikkaHub assistant diagnostics", summary))
}
