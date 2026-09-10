package me.rerere.rikkahub.ui.pages.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.memory.MemoryAutoSaveMode
import me.rerere.rikkahub.memory.MemoryCaptureOrigin
import me.rerere.rikkahub.memory.dreaming.runtime.MAX_DREAMING_DAILY_INPUT_TOKEN_LIMIT
import me.rerere.rikkahub.memory.dreaming.runtime.MAX_DREAMING_DAILY_OUTPUT_TOKEN_LIMIT
import me.rerere.rikkahub.memory.dreaming.runtime.MAX_DREAMING_DAILY_RUN_LIMIT
import me.rerere.rikkahub.memory.dreaming.runtime.MAX_DREAMING_IDLE_THRESHOLD_MINUTES
import me.rerere.rikkahub.memory.dreaming.runtime.MAX_DREAMING_RETRY_LIMIT
import me.rerere.rikkahub.memory.dreaming.runtime.MIN_DREAMING_IDLE_THRESHOLD_MINUTES
import me.rerere.rikkahub.memory.dreaming.runtime.DreamNetworkPolicy
import me.rerere.rikkahub.memory.dreaming.runtime.DreamingCostPolicy
import me.rerere.rikkahub.memory.dreaming.runtime.DreamingScopePreferenceMutation
import me.rerere.rikkahub.memory.dreaming.runtime.DreamingScopePreferences
import kotlin.uuid.Uuid

@Composable
fun MemoryScopeSummary(
    assistant: Assistant,
    viewGlobal: Boolean,
    stats: MemoryCenterStats,
    onViewGlobalChange: (Boolean) -> Unit,
) {
    val companionName = assistant.name.trim().ifBlank {
        stringResource(R.string.memory_v2_narrative_companion_fallback)
    }
    val privateScopeName = stringResource(R.string.memory_v2_private_scope_named, companionName)
    val activeScopeName = if (assistant.useGlobalMemory) {
        stringResource(R.string.memory_v2_global_scope)
    } else {
        privateScopeName
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(
                    R.string.memory_v2_scope_in_use,
                    activeScopeName,
                ),
                style = MaterialTheme.typography.labelLarge,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !viewGlobal,
                    onClick = { onViewGlobalChange(false) },
                    label = { Text(privateScopeName) },
                )
                FilterChip(
                    selected = viewGlobal,
                    onClick = { onViewGlobalChange(true) },
                    label = { Text(stringResource(R.string.memory_v2_global_scope)) },
                )
            }
            Text(
                text = stringResource(
                    R.string.memory_v2_stats,
                    stats.active,
                    stats.pendingReview,
                    stats.failedCaptures,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun MemorySettingsTab(
    assistant: Assistant,
    stats: MemoryCenterStats,
    latestFailure: String?,
    extractionModel: MemoryExtractionModelUiState,
    modelOptions: List<MemoryModelOption>,
    recallState: MemoryRecallTestState,
    dreamingScopePreferences: DreamingScopePreferences,
    dreamingCostPolicy: DreamingCostPolicy,
    narrativeNamesForOrigin: (String?) -> MemoryNarrativeNames,
    onUpdateAssistant: ((Assistant) -> Assistant) -> Unit,
    onAutoSaveModeChange: (MemoryAutoSaveMode) -> Unit,
    onScheduleTuningChange: (Int, Int, Int) -> Unit,
    onNarrativeNamesChange: (String, String) -> Unit,
    onOriginChange: (MemoryCaptureOrigin, Boolean) -> Unit,
    onExtractionModelChange: (Uuid?) -> Unit,
    onProcessNow: () -> Unit,
    onRetryFailed: () -> Unit,
    onRecallTest: (String) -> Unit,
    onDreamingScopePreferenceChange: (DreamingScopePreferenceMutation) -> Unit,
    onDreamingCostPolicyChange: (DreamingCostPolicy) -> Unit,
) {
    val narrativeSelfFallback = stringResource(R.string.memory_v2_narrative_self_fallback)
    val narrativeCompanionFallback = stringResource(R.string.memory_v2_narrative_companion_fallback)
    val narrativeNames = remember(assistant, narrativeSelfFallback, narrativeCompanionFallback) {
        assistant.memoryNarrativeNames(narrativeSelfFallback, narrativeCompanionFallback)
    }
    var idleMinutes by remember(assistant.memoryIdleDelayMinutes) {
        mutableStateOf(assistant.memoryIdleDelayMinutes.toString())
    }
    var immediateThreshold by remember(assistant.memoryImmediateCaptureThreshold) {
        mutableStateOf(assistant.memoryImmediateCaptureThreshold.toString())
    }
    var contextTurns by remember(assistant.memoryConversationContextTurns) {
        mutableStateOf(assistant.memoryConversationContextTurns.toString())
    }
    var narrativeUserName by remember(assistant.memoryNarrativeUserName) {
        mutableStateOf(assistant.memoryNarrativeUserName)
    }
    var narrativeCompanionName by remember(assistant.memoryNarrativeCompanionName) {
        mutableStateOf(assistant.memoryNarrativeCompanionName)
    }
    val idleValue = idleMinutes.toIntOrNull()
    val thresholdValue = immediateThreshold.toIntOrNull()
    val contextTurnsValue = contextTurns.toIntOrNull()
    val scheduleValid = idleValue != null &&
        idleValue in MemoryCenterVM.MIN_IDLE_MINUTES..MemoryCenterVM.MAX_IDLE_MINUTES &&
        thresholdValue != null &&
        thresholdValue in MemoryCenterVM.MIN_IMMEDIATE_THRESHOLD..MemoryCenterVM.MAX_IMMEDIATE_THRESHOLD &&
        contextTurnsValue != null &&
        contextTurnsValue in MemoryCenterVM.MIN_CONVERSATION_CONTEXT_TURNS..
            MemoryCenterVM.MAX_CONVERSATION_CONTEXT_TURNS
    var showModelPicker by remember { mutableStateOf(false) }
    var recallQuery by remember { mutableStateOf("") }
    var dreamNetworkPolicy by remember(dreamingCostPolicy.networkPolicy) {
        mutableStateOf(dreamingCostPolicy.networkPolicy)
    }
    var dreamBatteryNotLow by remember(dreamingCostPolicy.requireBatteryNotLow) {
        mutableStateOf(dreamingCostPolicy.requireBatteryNotLow)
    }
    var dreamCharging by remember(dreamingCostPolicy.requireCharging) {
        mutableStateOf(dreamingCostPolicy.requireCharging)
    }
    var dreamDailyRuns by remember(dreamingCostPolicy.dailyRunLimit) {
        mutableStateOf(dreamingCostPolicy.dailyRunLimit.toString())
    }
    var dreamInputTokens by remember(dreamingCostPolicy.dailyInputTokenLimit) {
        mutableStateOf(dreamingCostPolicy.dailyInputTokenLimit?.toString().orEmpty())
    }
    var dreamOutputTokens by remember(dreamingCostPolicy.dailyOutputTokenLimit) {
        mutableStateOf(dreamingCostPolicy.dailyOutputTokenLimit?.toString().orEmpty())
    }
    var dreamRetryLimit by remember(dreamingCostPolicy.retryLimit) {
        mutableStateOf(dreamingCostPolicy.retryLimit.toString())
    }
    var dreamIdleMinutes by remember(dreamingCostPolicy.idleThresholdMinutes) {
        mutableStateOf(dreamingCostPolicy.idleThresholdMinutes.toString())
    }
    val dreamRunsValue = dreamDailyRuns.toIntOrNull()
    val dreamInputValue = dreamInputTokens.takeIf(String::isNotBlank)?.toLongOrNull()
    val dreamOutputValue = dreamOutputTokens.takeIf(String::isNotBlank)?.toLongOrNull()
    val dreamRetryValue = dreamRetryLimit.toIntOrNull()
    val dreamIdleValue = dreamIdleMinutes.toIntOrNull()
    val dreamPolicyValid = dreamRunsValue != null && dreamRunsValue in 0..MAX_DREAMING_DAILY_RUN_LIMIT &&
        (dreamInputTokens.isBlank() ||
            (dreamInputValue != null && dreamInputValue in 0L..MAX_DREAMING_DAILY_INPUT_TOKEN_LIMIT)) &&
        (dreamOutputTokens.isBlank() ||
            (dreamOutputValue != null && dreamOutputValue in 0L..MAX_DREAMING_DAILY_OUTPUT_TOKEN_LIMIT)) &&
        dreamRetryValue != null && dreamRetryValue in 0..MAX_DREAMING_RETRY_LIMIT &&
        dreamIdleValue != null &&
        dreamIdleValue in MIN_DREAMING_IDLE_THRESHOLD_MINUTES..MAX_DREAMING_IDLE_THRESHOLD_MINUTES

    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            SettingsCard(title = stringResource(R.string.memory_v2_core_settings)) {
                SettingSwitchRow(
                    title = stringResource(R.string.memory_v2_enable_memory),
                    description = stringResource(
                        R.string.memory_v2_enable_memory_desc_named,
                        narrativeNames.companionName,
                    ),
                    checked = assistant.enableMemory,
                    onCheckedChange = { enabled -> onUpdateAssistant { it.copy(enableMemory = enabled) } },
                )
                SettingSwitchRow(
                    title = stringResource(R.string.assistant_page_global_memory),
                    description = stringResource(
                        R.string.memory_v2_global_memory_desc_named,
                        narrativeNames.companionName,
                    ),
                    checked = assistant.useGlobalMemory,
                    enabled = assistant.enableMemory,
                    onCheckedChange = { enabled -> onUpdateAssistant { it.copy(useGlobalMemory = enabled) } },
                )
                SettingSwitchRow(
                    title = stringResource(R.string.memory_v21_narrative_events),
                    description = stringResource(R.string.memory_v21_narrative_events_desc),
                    checked = assistant.memoryNarrativeEventsEnabled,
                    enabled = assistant.enableMemory,
                    onCheckedChange = { enabled ->
                        onUpdateAssistant { it.copy(memoryNarrativeEventsEnabled = enabled) }
                    },
                )
                SettingSwitchRow(
                    title = stringResource(R.string.memory_v21_insights_theories),
                    description = stringResource(
                        R.string.memory_v2_insights_theories_desc_named,
                        narrativeNames.companionName,
                    ),
                    checked = assistant.memoryInsightsTheoriesEnabled,
                    enabled = assistant.enableMemory,
                    onCheckedChange = { enabled ->
                        onUpdateAssistant { it.copy(memoryInsightsTheoriesEnabled = enabled) }
                    },
                )
                SettingSwitchRow(
                    title = stringResource(R.string.assistant_page_recent_chats),
                    description = stringResource(
                        R.string.memory_v2_recent_chats_desc_named,
                        narrativeNames.companionName,
                    ),
                    checked = assistant.enableRecentChatsReference,
                    onCheckedChange = { enabled ->
                        onUpdateAssistant { it.copy(enableRecentChatsReference = enabled) }
                    },
                )
                // Separate from the switch above on purpose: that one controls the legacy static
                // recent-chats block in the system prompt, this one controls the on-demand
                // conversation-history tools. Off by default so an ordinary assistant carries no
                // cross-conversation read surface.
                SettingSwitchRow(
                    title = stringResource(R.string.assistant_page_conversation_history_tools),
                    description = stringResource(
                        R.string.assistant_page_conversation_history_tools_desc,
                    ),
                    checked = assistant.allowConversationHistoryTools,
                    onCheckedChange = { enabled ->
                        onUpdateAssistant { it.copy(allowConversationHistoryTools = enabled) }
                    },
                )
                SettingSwitchRow(
                    title = stringResource(R.string.assistant_page_time_reminder),
                    description = stringResource(R.string.assistant_page_time_reminder_desc),
                    checked = assistant.enableTimeReminder,
                    onCheckedChange = { enabled -> onUpdateAssistant { it.copy(enableTimeReminder = enabled) } },
                )
            }
        }

        item {
            SettingsCard(title = stringResource(R.string.memory_dream_settings_title)) {
                Text(
                    text = stringResource(R.string.memory_dream_settings_scope_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SettingSwitchRow(
                    title = stringResource(R.string.memory_dream_generate),
                    description = stringResource(R.string.memory_dream_generate_desc),
                    checked = dreamingScopePreferences.generate,
                    onCheckedChange = { enabled ->
                        onDreamingScopePreferenceChange(
                            DreamingScopePreferenceMutation.SetGenerate(enabled),
                        )
                    },
                )
                SettingSwitchRow(
                    title = stringResource(R.string.memory_dream_shadow),
                    description = stringResource(R.string.memory_dream_shadow_desc),
                    checked = dreamingScopePreferences.shadow,
                    onCheckedChange = { enabled ->
                        onDreamingScopePreferenceChange(
                            DreamingScopePreferenceMutation.SetShadow(enabled),
                        )
                    },
                )
                SettingSwitchRow(
                    title = stringResource(R.string.memory_dream_use),
                    description = stringResource(R.string.memory_dream_use_desc),
                    checked = dreamingScopePreferences.use,
                    onCheckedChange = { enabled ->
                        onDreamingScopePreferenceChange(
                            DreamingScopePreferenceMutation.SetUse(enabled),
                        )
                    },
                )
                SettingSwitchRow(
                    title = stringResource(R.string.memory_dream_deep),
                    description = stringResource(R.string.memory_dream_not_available),
                    checked = false,
                    enabled = false,
                    onCheckedChange = {},
                )
            }
        }

        item {
            SettingsCard(title = stringResource(R.string.memory_dream_cost_policy_title)) {
                Text(
                    text = stringResource(R.string.memory_dream_cost_policy_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(stringResource(R.string.memory_dream_network_title))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DreamNetworkPolicy.entries.forEach { policy ->
                        FilterChip(
                            selected = dreamNetworkPolicy == policy,
                            onClick = { dreamNetworkPolicy = policy },
                            label = { Text(policy.title()) },
                        )
                    }
                }
                SettingSwitchRow(
                    title = stringResource(R.string.memory_dream_battery_not_low),
                    description = stringResource(R.string.memory_dream_battery_not_low_desc),
                    checked = dreamBatteryNotLow,
                    onCheckedChange = { dreamBatteryNotLow = it },
                )
                SettingSwitchRow(
                    title = stringResource(R.string.memory_dream_require_charging),
                    description = stringResource(R.string.memory_dream_require_charging_desc),
                    checked = dreamCharging,
                    onCheckedChange = { dreamCharging = it },
                )
                DreamNumberField(
                    value = dreamDailyRuns,
                    onValueChange = { dreamDailyRuns = it.take(2) },
                    label = stringResource(R.string.memory_dream_daily_runs),
                    supportingText = stringResource(R.string.memory_dream_daily_runs_desc),
                    isError = dreamRunsValue == null || dreamRunsValue !in 0..MAX_DREAMING_DAILY_RUN_LIMIT,
                )
                DreamNumberField(
                    value = dreamInputTokens,
                    onValueChange = { dreamInputTokens = it.take(7) },
                    label = stringResource(R.string.memory_dream_daily_input_tokens),
                    supportingText = stringResource(R.string.memory_dream_token_limit_desc),
                    isError = dreamInputTokens.isNotBlank() &&
                        (dreamInputValue == null || dreamInputValue !in 0L..MAX_DREAMING_DAILY_INPUT_TOKEN_LIMIT),
                )
                DreamNumberField(
                    value = dreamOutputTokens,
                    onValueChange = { dreamOutputTokens = it.take(6) },
                    label = stringResource(R.string.memory_dream_daily_output_tokens),
                    supportingText = stringResource(R.string.memory_dream_token_limit_desc),
                    isError = dreamOutputTokens.isNotBlank() &&
                        (dreamOutputValue == null || dreamOutputValue !in 0L..MAX_DREAMING_DAILY_OUTPUT_TOKEN_LIMIT),
                )
                DreamNumberField(
                    value = dreamRetryLimit,
                    onValueChange = { dreamRetryLimit = it.take(1) },
                    label = stringResource(R.string.memory_dream_retry_limit),
                    supportingText = stringResource(R.string.memory_dream_retry_limit_desc),
                    isError = dreamRetryValue == null || dreamRetryValue !in 0..MAX_DREAMING_RETRY_LIMIT,
                )
                DreamNumberField(
                    value = dreamIdleMinutes,
                    onValueChange = { dreamIdleMinutes = it.take(4) },
                    label = stringResource(R.string.memory_dream_idle_minutes),
                    supportingText = stringResource(R.string.memory_dream_idle_minutes_desc),
                    isError = dreamIdleValue == null ||
                        dreamIdleValue !in MIN_DREAMING_IDLE_THRESHOLD_MINUTES..
                            MAX_DREAMING_IDLE_THRESHOLD_MINUTES,
                )
                Button(
                    onClick = {
                        onDreamingCostPolicyChange(
                            DreamingCostPolicy(
                                networkPolicy = dreamNetworkPolicy,
                                requireBatteryNotLow = dreamBatteryNotLow,
                                requireCharging = dreamCharging,
                                dailyRunLimit = dreamRunsValue!!,
                                dailyInputTokenLimit = dreamInputValue,
                                dailyOutputTokenLimit = dreamOutputValue,
                                retryLimit = dreamRetryValue!!,
                                idleThresholdMinutes = dreamIdleValue!!,
                            ),
                        )
                    },
                    enabled = dreamPolicyValid,
                ) {
                    Text(stringResource(R.string.memory_dream_save_policy))
                }
                Text(
                    text = stringResource(R.string.memory_dream_unknown_usage_pauses),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.memory_dream_cost_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SettingsCard(title = stringResource(R.string.memory_v2_auto_extraction)) {
                MemoryAutoSaveMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = assistant.memoryAutoSaveMode == mode,
                            onClick = { onAutoSaveModeChange(mode) },
                            enabled = assistant.enableMemory,
                        )
                        Text(mode.title(), modifier = Modifier.weight(1f))
                    }
                }
                Text(
                    text = stringResource(R.string.memory_v2_batching_explanation),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = idleMinutes,
                        onValueChange = { value ->
                            idleMinutes = value.filter(Char::isDigit).take(4)
                        },
                        label = { Text(stringResource(R.string.memory_v2_idle_minutes)) },
                        supportingText = { Text(stringResource(R.string.memory_v2_idle_minutes_desc)) },
                        isError = idleMinutes.isNotEmpty() &&
                            (idleValue == null || idleValue !in
                                MemoryCenterVM.MIN_IDLE_MINUTES..MemoryCenterVM.MAX_IDLE_MINUTES),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = immediateThreshold,
                        onValueChange = { value ->
                            immediateThreshold = value.filter(Char::isDigit).take(2)
                        },
                        label = { Text(stringResource(R.string.memory_v2_immediate_threshold)) },
                        supportingText = { Text(stringResource(R.string.memory_v2_immediate_threshold_desc)) },
                        isError = immediateThreshold.isNotEmpty() &&
                            (thresholdValue == null || thresholdValue !in
                                MemoryCenterVM.MIN_IMMEDIATE_THRESHOLD..MemoryCenterVM.MAX_IMMEDIATE_THRESHOLD),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = contextTurns,
                        onValueChange = { value ->
                            contextTurns = value.filter(Char::isDigit).take(2)
                        },
                        label = { Text(stringResource(R.string.memory_v2_context_turns)) },
                        supportingText = {
                            Text(stringResource(R.string.memory_v2_context_turns_desc))
                        },
                        isError = contextTurns.isNotEmpty() &&
                            (contextTurnsValue == null || contextTurnsValue !in
                                MemoryCenterVM.MIN_CONVERSATION_CONTEXT_TURNS..
                                    MemoryCenterVM.MAX_CONVERSATION_CONTEXT_TURNS),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Button(
                    onClick = {
                        onScheduleTuningChange(idleValue!!, thresholdValue!!, contextTurnsValue!!)
                    },
                    enabled = scheduleValid,
                ) {
                    Text(stringResource(R.string.memory_v2_save_batching))
                }
            }
        }

        item {
            SettingsCard(title = stringResource(R.string.memory_v2_narrative_names)) {
                OutlinedTextField(
                    value = narrativeUserName,
                    onValueChange = { narrativeUserName = it.take(MemoryCenterVM.MAX_NARRATIVE_NAME_CHARS) },
                    label = { Text(stringResource(R.string.memory_v2_narrative_self_name)) },
                    supportingText = { Text(stringResource(R.string.memory_v2_narrative_self_name_desc)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = narrativeCompanionName,
                    onValueChange = { narrativeCompanionName = it.take(MemoryCenterVM.MAX_NARRATIVE_NAME_CHARS) },
                    label = { Text(stringResource(R.string.memory_v2_narrative_companion_name)) },
                    supportingText = {
                        Text(stringResource(R.string.memory_v2_narrative_companion_name_desc, assistant.name))
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { onNarrativeNamesChange(narrativeUserName, narrativeCompanionName) },
                ) {
                    Text(stringResource(R.string.assistant_page_save))
                }
            }
        }

        item {
            SettingsCard(title = stringResource(R.string.memory_v2_capture_sources)) {
                MemoryCenterVM.USER_CONFIGURABLE_ORIGINS.forEach { origin ->
                    SettingSwitchRow(
                        title = origin.title(),
                        description = null,
                        checked = origin in assistant.memoryCaptureOrigins,
                        enabled = assistant.enableMemory && assistant.memoryAutoSaveMode != MemoryAutoSaveMode.OFF,
                        onCheckedChange = { enabled -> onOriginChange(origin, enabled) },
                    )
                }
                Text(
                    text = stringResource(R.string.memory_v2_forbidden_sources),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SettingsCard(title = stringResource(R.string.memory_v2_extraction_model)) {
                if (!extractionModel.available) {
                    Text(
                        text = stringResource(R.string.memory_v2_model_unavailable),
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Text(
                        text = if (extractionModel.usingFastModel) {
                            stringResource(
                                R.string.memory_v2_model_fast_value,
                                extractionModel.modelName,
                                extractionModel.providerName,
                            )
                        } else {
                            stringResource(
                                R.string.memory_v2_model_value,
                                extractionModel.modelName,
                                extractionModel.providerName,
                            )
                        },
                    )
                }
                OutlinedButton(onClick = { showModelPicker = true }) {
                    Text(stringResource(R.string.memory_v2_choose_model))
                }
                Text(
                    text = stringResource(R.string.memory_v2_privacy_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SettingsCard(title = stringResource(R.string.memory_v2_queue)) {
                Text(
                    stringResource(
                        R.string.memory_v2_queue_active_counts,
                        stats.pendingCaptures,
                        stats.processingCaptures,
                        stats.pausedCaptures,
                        stats.failedCaptures,
                    ),
                )
                Text(
                    stringResource(
                        R.string.memory_v2_queue_processed_counts,
                        stats.processedCaptures,
                        stats.noLongTermSignalCaptures,
                        stats.discardedCaptures,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                latestFailure?.let { failure ->
                    Text(
                        text = stringResource(R.string.memory_v2_latest_failure, failure),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onProcessNow,
                        enabled = stats.pendingCaptures > 0 || stats.pausedCaptures > 0,
                    ) {
                        Text(stringResource(R.string.memory_v2_process_now))
                    }
                    OutlinedButton(
                        onClick = onRetryFailed,
                        enabled = stats.failedCaptures > 0 || stats.pausedCaptures > 0,
                    ) {
                        Text(stringResource(R.string.memory_v2_retry_failed))
                    }
                }
            }
        }

        item {
            SettingsCard(title = stringResource(R.string.memory_v2_recall_test)) {
                OutlinedTextField(
                    value = recallQuery,
                    onValueChange = { recallQuery = it },
                    label = { Text(stringResource(R.string.memory_v2_recall_query)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
                Button(onClick = { onRecallTest(recallQuery) }, enabled = recallQuery.isNotBlank()) {
                    Text(stringResource(R.string.memory_v2_run_recall_test))
                }
                when (recallState) {
                    MemoryRecallTestState.Idle -> Unit
                    MemoryRecallTestState.Loading -> Text(stringResource(R.string.workspace_detail_loading))
                    is MemoryRecallTestState.Failed -> Text(
                        stringResource(R.string.memory_v2_recall_failed),
                        color = MaterialTheme.colorScheme.error,
                    )
                    is MemoryRecallTestState.Ready -> {
                        Text(
                            stringResource(
                                R.string.memory_v2_recall_budget,
                                recallState.usedCharacters,
                                recallState.characterBudget,
                            ),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        recallState.results.forEach { result ->
                            val readableResult = result.readableFor(narrativeNamesForOrigin)
                            HorizontalDivider()
                            Text(
                                readableResult.title ?: "#${readableResult.id}",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(readableResult.content, maxLines = 4)
                            Text(
                                stringResource(
                                    R.string.memory_v2_recall_result,
                                    readableResult.score,
                                    readableResult.matchedTerms.joinToString(),
                                    readableResult.reason,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (recallState.results.isEmpty()) {
                            Text(stringResource(R.string.search_page_no_results))
                        }
                    }
                }
            }
        }
    }

    if (showModelPicker) {
        ModelPickerDialog(
            options = modelOptions,
            onDismiss = { showModelPicker = false },
            onSelect = { modelId ->
                onExtractionModelChange(modelId)
                showModelPicker = false
            },
        )
    }
}

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    description: String?,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    val state = stringResource(
        if (checked) R.string.memory_dream_switch_on else R.string.memory_dream_switch_off,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title)
            description?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.semantics { stateDescription = state },
        )
    }
}

@Composable
private fun DreamNumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    supportingText: String,
    isError: Boolean,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { next -> onValueChange(next.filter(Char::isDigit)) },
        label = { Text(label) },
        supportingText = { Text(supportingText) },
        isError = isError,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun DreamNetworkPolicy.title(): String = when (this) {
    DreamNetworkPolicy.CONNECTED -> stringResource(R.string.memory_dream_network_connected)
    DreamNetworkPolicy.UNMETERED -> stringResource(R.string.memory_dream_network_unmetered)
}

@Composable
private fun ModelPickerDialog(
    options: List<MemoryModelOption>,
    onDismiss: () -> Unit,
    onSelect: (Uuid?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.memory_v2_choose_model)) },
        text = {
            LazyColumn {
                item {
                    TextButton(onClick = { onSelect(null) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.memory_v2_use_fast_model))
                    }
                }
                items(options, key = { it.id.toString() }) { option ->
                    TextButton(
                        onClick = { onSelect(option.id) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(option.name)
                            Text(
                                option.providerName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun MemoryAutoSaveMode.title(): String = when (this) {
    MemoryAutoSaveMode.OFF -> stringResource(R.string.memory_v2_auto_off)
    MemoryAutoSaveMode.REVIEW_ALL -> stringResource(R.string.memory_v2_auto_review_all)
    MemoryAutoSaveMode.SAFE_NEW_ONLY -> stringResource(R.string.memory_v2_auto_safe_new)
}

@Composable
private fun MemoryCaptureOrigin.title(): String = when (this) {
    MemoryCaptureOrigin.APP_UI -> stringResource(R.string.memory_v2_origin_app)
    MemoryCaptureOrigin.SYSTEM_ASSISTANT -> stringResource(R.string.memory_v2_origin_system_entry)
    MemoryCaptureOrigin.QUICK_CAPTURE -> stringResource(R.string.memory_v2_origin_quick_capture)
    MemoryCaptureOrigin.TELEGRAM -> stringResource(R.string.memory_v2_origin_telegram)
    MemoryCaptureOrigin.WEB_API -> stringResource(R.string.memory_v2_origin_web)
    else -> name
}
