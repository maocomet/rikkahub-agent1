package me.rerere.rikkahub.data.capability

import android.Manifest
import android.content.ComponentName
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.ai.InvocationSurfacePolicy
import me.rerere.rikkahub.data.ai.tools.LocalToolOption

/** Runtime surface a tool needs after it has passed its capability-origin policy. */
enum class ToolInvocationSurface {
    /** Can execute without launching an Activity or asking Android for a new grant. */
    Background,

    /** Needs an Activity-backed UI that a VoiceInteractionSession cannot safely host. */
    Activity,

    /** Opens a system consent surface and therefore needs the full foreground app flow. */
    SystemConsent,

    /** Executes caller-controlled commands or generic UI/intent actions. */
    UnboundedExecution,

    /** Creates, resumes, or dispatches work that can outlive the visible invocation. */
    DeferredExecution,

    /** Mutates broad system policy rather than performing one bounded device action. */
    SystemMutation,

    /** Creates, replaces, moves, or deletes persistent files. */
    FileMutation,

    /** Sends local/user data outside its current protected storage or conversation. */
    DataEgress,

    /** Mutates persistent app/user configuration without launching a foreground surface. */
    DataMutation,

    /** Deliberately withheld from the phase-1 system-assistant overlay. */
    Phase1Unavailable,

    /** Known to the catalog but not intentionally approved for the assistant overlay. */
    Unclassified,
}

/**
 * Central registry of every capability in RikkaHub.
 *
 * This is the single source of truth. All UI pages, permission checks, approval
 * policies, and diagnostic tools should read from this catalog rather than
 * duplicating capability metadata in their own code.
 *
 * Usage:
 *   CapabilityCatalog.capabilityOf(CapabilityId.Battery)
 *   CapabilityCatalog.allCapabilities()
 *   CapabilityCatalog.byLocalToolOption(LocalToolOption.Battery)
 */
object CapabilityCatalog {

    /**
     * Exact names emitted by [LocalToolOption] implementations.
     *
     * This migration table deliberately replaces capability-id substring matching. A new tool
     * is not part of any security surface until its real name is added here (or directly to its
     * descriptor). Options without a CapabilityId remain unavailable to the system-assistant
     * surface rather than being guessed into one.
     */
    private val exactToolNamesByOption: Map<LocalToolOption, Set<String>> = mapOf(
        LocalToolOption.TimeInfo to setOf("get_time_info"),
        LocalToolOption.JavascriptEngine to setOf("eval_javascript"),
        LocalToolOption.Clipboard to setOf("clipboard_tool"),
        LocalToolOption.Tts to setOf("text_to_speech", "tts_library_list", "tts_library_play"),
        LocalToolOption.AskUser to setOf("ask_user"),
        LocalToolOption.ScreenTime to setOf("get_screen_time"),
        LocalToolOption.Calendar to setOf(
            "calendar_query", "calendar_create", "calendar_delete", "calendar_update",
        ),
        LocalToolOption.Battery to setOf("get_battery_status"),
        LocalToolOption.AudioInfo to setOf("get_audio_info"),
        LocalToolOption.TelephonyInfo to setOf("get_telephony_info"),
        LocalToolOption.WifiInfo to setOf("get_wifi_info"),
        LocalToolOption.Sensors to setOf("list_sensors", "read_sensor"),
        LocalToolOption.HealthSensors to setOf("list_health_sensors", "read_health_sensor"),
        LocalToolOption.StorageInfo to setOf("get_storage_info"),
        LocalToolOption.Toast to setOf("show_toast"),
        LocalToolOption.Notification to setOf("post_notification"),
        LocalToolOption.Share to setOf("share"),
        LocalToolOption.Torch to setOf("set_torch"),
        LocalToolOption.Vibrate to setOf("vibrate"),
        LocalToolOption.Brightness to setOf("get_brightness", "set_brightness"),
        LocalToolOption.Volume to setOf("get_volume", "set_volume"),
        LocalToolOption.MediaPlayer to setOf(
            "play_media", "stop_media", "pause_media", "resume_media", "seek_media",
            "get_media_status",
        ),
        LocalToolOption.MediaScanner to setOf("scan_media"),
        LocalToolOption.Download to setOf("download_file", "write_text_file"),
        LocalToolOption.Wallpaper to setOf("set_wallpaper"),
        LocalToolOption.Location to setOf("get_location"),
        LocalToolOption.StepCounter to setOf("get_step_count"),
        LocalToolOption.Contacts to setOf("search_contacts", "list_contacts"),
        LocalToolOption.CallLog to setOf("list_call_log"),
        LocalToolOption.SmsInbox to setOf("list_sms_inbox", "search_sms"),
        LocalToolOption.SmsSend to setOf("send_sms"),
        LocalToolOption.CameraPhoto to setOf("take_photo"),
        LocalToolOption.MicRecorder to setOf("record_audio"),
        LocalToolOption.SpeechToText to setOf("speech_to_text"),
        LocalToolOption.Fingerprint to setOf("verify_fingerprint"),
        LocalToolOption.CronJobs to setOf(
            "schedule_job", "list_jobs", "delete_job", "pause_job", "resume_job",
            "trigger_job_now", "get_job_history",
        ),
        LocalToolOption.ScreenAutomation to setOf(
            "tap", "long_press", "swipe", "read_window_tree", "find_node", "click_node",
            "set_text", "scroll", "global_action", "take_screenshot", "wake_screen",
        ),
        LocalToolOption.AppLauncher to setOf("launch_app", "list_installed_apps", "open_url"),
        LocalToolOption.SystemIntents to setOf(
            "create_calendar_event", "create_contact", "send_email_intent", "send_sms_intent",
            "open_wifi_settings", "show_location_on_map",
        ),
        LocalToolOption.KeyboardControl to setOf(
            "keyboard_type", "keyboard_input", "keyboard_read_field", "keyboard_press_key", "keyboard_delete",
            "keyboard_clear", "keyboard_editor_info", "keyboard_set_cursor",
            "keyboard_select_range",
        ),
        LocalToolOption.Files to setOf(
            "list_files", "read_file", "write_binary_file", "delete_file", "move_file",
            "copy_file", "create_directory", "file_info", "find_files", "show_image",
            "open_file", "batch_copy", "batch_move", "batch_delete",
        ),
        LocalToolOption.ExternalStorage to setOf(
            "list_storage_volumes", "list_granted_directories", "grant_directory_access",
        ),
        LocalToolOption.Archive to setOf("zip_files", "unzip_file", "list_zip_contents"),
        LocalToolOption.MediaLibrary to setOf("media_list_images", "media_list_audio"),
        LocalToolOption.ExportConversation to setOf("export_conversation"),
        LocalToolOption.Ssh to setOf(
            "ssh_exec", "save_ssh_host", "list_ssh_hosts", "delete_ssh_host",
            "ssh_exec_saved", "ssh_upload", "ssh_download", "ssh_forget_host_key",
        ),
        LocalToolOption.Termux to setOf(
            "termux_run_command", "termux_session_start", "termux_session_send",
            "termux_session_read", "termux_session_kill", "termux_session_list",
            "transcribe_audio_file", "whisper_status",
            "linux_profile_list", "linux_run",
            "linux_session_create", "linux_session_exec", "linux_session_inspect",
            "linux_session_list", "linux_session_close",
            "linux_grant_request", "linux_grant_list", "linux_grant_revoke",
        ),
        LocalToolOption.TelegramBot to setOf(
            "telegram_set_token", "telegram_status", "telegram_enable", "telegram_disable",
            "telegram_add_whitelist", "telegram_remove_whitelist",
            "telegram_set_default_chat", "telegram_set_assistant", "telegram_send_message",
            "telegram_send_photo", "telegram_send_document", "telegram_set_commands",
            "telegram_get_commands", "telegram_delete_commands",
        ),
        LocalToolOption.McpControl to setOf(
            "mcp_list", "mcp_get", "mcp_add", "mcp_update", "mcp_delete",
            "mcp_set_enabled", "mcp_test", "mcp_list_tools", "mcp_set_tool_approval",
        ),
        LocalToolOption.ExternalAutomation to setOf(
            "external_automation_status", "external_automation_set_enabled",
            "external_automation_add_trusted_package",
            "external_automation_remove_trusted_package",
        ),
        LocalToolOption.WebFetch to setOf("web_fetch"),
        LocalToolOption.Browser to me.rerere.rikkahub.browser.BrowserToolDefaults.ALL_TOOLS.toSet(),
        LocalToolOption.NotificationListener to setOf(
            "list_recent_notifications", "list_active_notifications", "dismiss_notification",
            "notification_action_click", "notification_reply", "notification_status",
        ),
        LocalToolOption.Workflows to setOf(
            "workflow_create", "workflow_list", "workflow_get", "workflow_update",
            "workflow_delete", "workflow_set_enabled", "workflow_run",
        ),
        LocalToolOption.CostGuards to setOf("check_token_usage"),
        LocalToolOption.SubAgents to setOf(
            "subagent_dispatch", "subagent_list", "subagent_get", "subagent_cancel",
            "research_start", "research_status", "research_cancel",
        ),
        LocalToolOption.Alarm to setOf("alarm_create", "alarm_list", "alarm_delete"),
        LocalToolOption.Keystore to setOf(
            "keystore_generate_key", "keystore_sign", "keystore_verify", "keystore_encrypt",
            "keystore_decrypt", "keystore_delete_key", "keystore_list_keys",
        ),
        LocalToolOption.Nfc to setOf("nfc_read_tag", "nfc_write_tag"),
        LocalToolOption.Reliability to setOf("check_app_updates", "generate_bug_report"),
        LocalToolOption.SkillImport to setOf("skill_install_from_url", "skill_install_from_text"),
        LocalToolOption.JsSkills to setOf("run_js"),
        LocalToolOption.MediaWrite to setOf("media_copy", "media_move"),
        LocalToolOption.NearbyDevices to setOf(
            "bluetooth_scan", "list_paired_bluetooth_devices",
        ),
        LocalToolOption.ExternalPrivilegeBridge to setOf(
            "shizuku_status", "list_packages", "force_stop_app", "clear_app_cache",
        ),
        LocalToolOption.PhoneActions to setOf("call_phone"),
        LocalToolOption.PackageManagement to setOf("install_apk"),
    )

    private val activityToolNames: Set<String> = setOf(
        "share",
        "launch_app",
        "open_url",
        "show_image",
        "open_file",
        "create_calendar_event",
        "create_contact",
        "send_email_intent",
        "send_sms_intent",
        "open_wifi_settings",
        "show_location_on_map",
        "privileged_start_activity",
        "ask_user",
    ) + me.rerere.rikkahub.browser.BrowserToolDefaults.ALL_TOOLS

    private val systemConsentToolNames: Set<String> = setOf(
        "take_photo",
        "record_audio",
        "speech_to_text",
        "verify_fingerprint",
        "grant_directory_access",
        "nfc_read_tag",
        "nfc_write_tag",
        "install_apk",
    )

    /**
     * General-purpose execution cannot coexist with phase-1's hard telephony prohibition:
     * a shell, raw key event, notification PendingIntent, or generic accessibility click can
     * reproduce a phone call without ever invoking the named call_phone tool.
     */
    private val unboundedExecutionToolNames: Set<String> = setOf(
        "external_bridge_run_command",
        "termux_run_command",
        "termux_session_start",
        "termux_session_send",
        "termux_session_kill",
        "termux_session_list",
        "termux_session_read",
        "ssh_exec",
        "ssh_exec_saved",
        "run_js",
        "eval_javascript",
        "workspace_shell",
        "workspace_process_start",
        "workspace_process_restart",
        "ui_click_node_verified",
        "ui_set_text_verified",
        "ui_scroll_until",
        "keyboard_type",
        "keyboard_input",
        "keyboard_press_key",
        "keyboard_delete",
        "keyboard_clear",
        "keyboard_set_cursor",
        "keyboard_select_range",
        "privileged_send_broadcast",
        "linux_run",
        "linux_session_create",
        "linux_session_exec",
        "linux_session_close",
    )

    private val deferredExecutionToolNames: Set<String> = setOf(
        "schedule_job",
        "delete_job",
        "pause_job",
        "resume_job",
        "trigger_job_now",
        "alarm_create",
        "alarm_delete",
        "workflow_create",
        "workflow_update",
        "workflow_delete",
        "workflow_set_enabled",
        "workflow_run",
        "subagent_dispatch",
        "subagent_cancel",
        "subagent_get",
        "subagent_list",
        "research_start",
        "research_status",
        "research_cancel",
        "workflow_get",
        "workflow_list",
        "workspace_process_start",
        "workspace_process_stop",
        "workspace_process_restart",
    )

    private val systemMutationToolNames: Set<String> = setOf(
        "privileged_settings_put",
        "privileged_settings_delete",
        "privileged_appop_set",
        "privileged_appop_reset",
        "privileged_permission_grant",
        "privileged_permission_revoke",
        "privileged_package_enable",
        "privileged_package_disable",
        "privileged_package_suspend",
        "privileged_package_unsuspend",
        "privileged_package_uninstall",
    )

    private val fileMutationToolNames: Set<String> = setOf(
        "download_file",
        "write_text_file",
        "write_binary_file",
        "delete_file",
        "move_file",
        "copy_file",
        "create_directory",
        "batch_copy",
        "batch_move",
        "batch_delete",
        "zip_files",
        "unzip_file",
        "ssh_download",
        "skill_install_from_url",
        "skill_install_from_text",
        "generate_bug_report",
        "workspace_write_file",
        "workspace_edit_file",
    )

    private val dataEgressToolNames: Set<String> = setOf(
        "ssh_upload",
        "telegram_send_message",
        "telegram_send_photo",
        "telegram_send_document",
        "export_conversation",
        "media_copy",
        "send_sms",
    )

    private val dataMutationToolNames: Set<String> = setOf(
        "media_move",
        "scan_media",
        "dismiss_notification",
        "mcp_add",
        "mcp_update",
        "mcp_delete",
        "mcp_set_enabled",
        "mcp_test",
        "mcp_set_tool_approval",
        "external_automation_set_enabled",
        "external_automation_add_trusted_package",
        "external_automation_remove_trusted_package",
        "telegram_set_token",
        "telegram_enable",
        "telegram_disable",
        "telegram_add_whitelist",
        "telegram_remove_whitelist",
        "telegram_set_default_chat",
        "telegram_set_assistant",
        "telegram_set_commands",
        "telegram_delete_commands",
        "save_ssh_host",
        "delete_ssh_host",
        "ssh_forget_host_key",
        "keystore_generate_key",
        "keystore_delete_key",
        "set_wallpaper",
        "calendar_create",
        "calendar_delete",
        "calendar_update",
        "linux_grant_request",
        "linux_grant_revoke",
    )

    private val phase1UnavailableToolNames: Set<String> = setOf(
        "call_phone",
        "external_automation_status",
        "keyboard_editor_info",
        "keyboard_read_field",
        "keystore_decrypt",
        "keystore_encrypt",
        "keystore_list_keys",
        "keystore_sign",
        "keystore_verify",
        "list_ssh_hosts",
        "mcp_get",
        "mcp_list",
        "mcp_list_tools",
        "telegram_get_commands",
        "telegram_status",
        "transcribe_audio_file",
        "web_fetch",
        "whisper_status",
        "clipboard_tool",
        "text_to_speech",
    )

    /**
     * Phase-1 VoiceInteraction surface. This is intentionally an allowlist: a catalogued tool
     * that is missing from every classification becomes [ToolInvocationSurface.Unclassified]
     * and cannot be injected into the privileged overlay.
     */
    private val backgroundToolNames: Set<String> = setOf(
        "search_web",
        "scrape_web",
        "get_time_info",
        "calendar_query",
        "get_battery_status",
        "get_audio_info",
        "get_telephony_info",
        "get_wifi_info",
        "list_sensors",
        "read_sensor",
        "list_health_sensors",
        "read_health_sensor",
        "get_storage_info",
        "show_toast",
        "post_notification",
        "set_torch",
        "vibrate",
        "get_brightness",
        "set_brightness",
        "get_volume",
        "set_volume",
        "play_media",
        "stop_media",
        "pause_media",
        "resume_media",
        "seek_media",
        "get_media_status",
        "get_location",
        "reverse_geocode",
        "get_step_count",
        "search_contacts",
        "list_contacts",
        "list_call_log",
        "list_sms_inbox",
        "search_sms",
        "list_jobs",
        "get_job_history",
        "list_installed_apps",
        "list_files",
        "read_file",
        "file_info",
        "find_files",
        "list_storage_volumes",
        "list_granted_directories",
        "list_zip_contents",
        "media_list_images",
        "media_list_audio",
        "list_recent_notifications",
        "list_active_notifications",
        "notification_status",
        "check_token_usage",
        "alarm_list",
        "check_app_updates",
        "bluetooth_scan",
        "list_paired_bluetooth_devices",
        "shizuku_status",
        "list_packages",
        "force_stop_app",
        "clear_app_cache",
        "privileged_settings_get",
        "privileged_appop_get",
        "privileged_permission_status",
        "privileged_package_inspect",
        "privileged_dumpsys",
        "privileged_process_list",
        "privileged_service_status",
        "privileged_resolve_intent",
        "privileged_query_activities",
        "privileged_logcat_read",
        "privileged_window_state",
        "privileged_job_status",
        "privileged_alarm_status",
        "workspace_process_list",
        "workspace_process_status",
        "workspace_process_logs",
        "ui_wait_for_window",
        "ui_wait_for_node",
        "workspace_read_file",
        "conversation_list_recent",
        "conversation_read_recent",
        "conversation_search",
        "linux_profile_list",
        "linux_grant_list",
        "linux_session_inspect",
        "linux_session_list",
        "tts_library_list",
        "tts_library_play",
    )

    private val registry: Map<CapabilityId, CapabilityDescriptor> = buildRegistry()

    private val exactToolRegistry: Map<String, CapabilityDescriptor> = buildMap {
        registry.values.forEach { descriptor ->
            descriptor.toolNames.forEach { toolName ->
                val previous = put(toolName, descriptor)
                require(previous == null || previous.id == descriptor.id) {
                    "$toolName is owned by both ${previous?.id} and ${descriptor.id}"
                }
            }
        }
    }

    /** Look up a capability by its ID. */
    fun capabilityOf(id: CapabilityId): CapabilityDescriptor? = registry[id]

    /** List every registered capability. */
    fun allCapabilities(): Collection<CapabilityDescriptor> = registry.values

    /** Find the capability descriptor that corresponds to a given [LocalToolOption]. */
    fun byLocalToolOption(option: LocalToolOption): CapabilityDescriptor? =
        registry.values.firstOrNull { it.localToolOption == option }

    /** Resolve only a real LLM tool name. Security decisions never use ID substrings. */
    fun byToolName(toolName: String): CapabilityDescriptor? = exactToolRegistry[toolName]

    /** Exact runtime surface for a catalogued tool; unknown tools fail closed with `null`. */
    fun toolInvocationSurface(toolName: String): ToolInvocationSurface? {
        val descriptor = byToolName(toolName) ?: return null
        return when {
            toolName in systemConsentToolNames -> ToolInvocationSurface.SystemConsent
            toolName in activityToolNames || descriptor.requiresForegroundApp ->
                ToolInvocationSurface.Activity
            toolName in unboundedExecutionToolNames -> ToolInvocationSurface.UnboundedExecution
            toolName in deferredExecutionToolNames -> ToolInvocationSurface.DeferredExecution
            toolName in systemMutationToolNames -> ToolInvocationSurface.SystemMutation
            toolName in fileMutationToolNames -> ToolInvocationSurface.FileMutation
            toolName in dataEgressToolNames -> ToolInvocationSurface.DataEgress
            toolName in dataMutationToolNames -> ToolInvocationSurface.DataMutation
            toolName in phase1UnavailableToolNames -> ToolInvocationSurface.Phase1Unavailable
            toolName in backgroundToolNames -> ToolInvocationSurface.Background
            else -> ToolInvocationSurface.Unclassified
        }
    }

    /** The VoiceInteraction overlay receives only explicitly local, non-Activity tools. */
    fun isAvailableFromSystemAssistant(toolName: String): Boolean {
        val descriptor = byToolName(toolName) ?: return false
        return ToolCallOrigin.SystemAssistant in descriptor.allowedOrigins &&
            toolInvocationSurface(toolName) == ToolInvocationSurface.Background
    }

    /** All capabilities that are actually implemented (not Reserved / ManualOnly). */
    fun implementedCapabilities(): List<CapabilityDescriptor> =
        registry.values.filter { it.implementationState == ImplementationState.Implemented }

    // ── Registry builder ────────────────────────────────────────────────────────────

    private fun buildRegistry(): Map<CapabilityId, CapabilityDescriptor> {
        val map = mutableMapOf<CapabilityId, CapabilityDescriptor>()

        fun reg(descriptor: CapabilityDescriptor) {
            val exactToolNames = descriptor.toolNames.ifEmpty {
                descriptor.localToolOption?.let(exactToolNamesByOption::get).orEmpty()
            }
            map[descriptor.id] = descriptor.copy(toolNames = exactToolNames)
        }

        // ═══════════════════════════════════════════════════════════════════════════
        // SECTION: Device Info
        // ═══════════════════════════════════════════════════════════════════════════

        reg(CapabilityDescriptor(
            id = CapabilityId.TimeInfo,
            localToolOption = LocalToolOption.TimeInfo,
            requirements = emptyList(),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.Low,
            approvalPolicy = ApprovalPolicy.Default,
            allowedOrigins = InvocationSurfacePolicy.ALL_NON_KEYGUARD,
        ))

        reg(CapabilityDescriptor(
            id = CapabilityId.Battery,
            localToolOption = LocalToolOption.Battery,
            requirements = emptyList(),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.Low,
            approvalPolicy = ApprovalPolicy.Default,
            allowedOrigins = InvocationSurfacePolicy.ALL_NON_KEYGUARD,
        ))

        reg(CapabilityDescriptor(
            id = CapabilityId.AudioInfo,
            localToolOption = LocalToolOption.AudioInfo,
            requirements = emptyList(),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.Low,
            approvalPolicy = ApprovalPolicy.Default,
            allowedOrigins = InvocationSurfacePolicy.ALL_NON_KEYGUARD,
        ))

        reg(CapabilityDescriptor(
            id = CapabilityId.TelephonyInfo,
            localToolOption = LocalToolOption.TelephonyInfo,
            requirements = listOf(
                CapabilityRequirement.RuntimePermission(Manifest.permission.READ_PHONE_STATE),
            ),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.Medium,
            approvalPolicy = ApprovalPolicy.AlwaysAsk, // Phone number is PII
            allowedOrigins = InvocationSurfacePolicy.ALL_NON_KEYGUARD,
        ))

        reg(CapabilityDescriptor(
            id = CapabilityId.WifiInfo,
            localToolOption = LocalToolOption.WifiInfo,
            requirements = listOf(
                CapabilityRequirement.RuntimePermission(Manifest.permission.ACCESS_FINE_LOCATION),
            ),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.Low,
            approvalPolicy = ApprovalPolicy.Default,
            allowedOrigins = InvocationSurfacePolicy.ALL_NON_KEYGUARD,
        ))

        reg(CapabilityDescriptor(
            id = CapabilityId.Sensors,
            localToolOption = LocalToolOption.Sensors,
            requirements = listOf(
                CapabilityRequirement.ManifestPermission(Manifest.permission.HIGH_SAMPLING_RATE_SENSORS),
            ),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.Low,
            approvalPolicy = ApprovalPolicy.Default,
            allowedOrigins = InvocationSurfacePolicy.ALL_NON_KEYGUARD,
        ))

        reg(CapabilityDescriptor(
            id = CapabilityId.StorageInfo,
            localToolOption = LocalToolOption.StorageInfo,
            requirements = emptyList(),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.Low,
            approvalPolicy = ApprovalPolicy.Default,
            allowedOrigins = InvocationSurfacePolicy.ALL_NON_KEYGUARD,
        ))

        reg(CapabilityDescriptor(
            id = CapabilityId.JavascriptEngine,
            localToolOption = LocalToolOption.JavascriptEngine,
            requirements = emptyList(),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.High,
            approvalPolicy = ApprovalPolicy.AlwaysAsk,
            allowedOrigins = InvocationSurfacePolicy.ALL_NON_KEYGUARD,
        ))

        reg(CapabilityDescriptor(
            id = CapabilityId.Clipboard,
            localToolOption = LocalToolOption.Clipboard,
            requirements = emptyList(),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.Medium,
            approvalPolicy = ApprovalPolicy.AlwaysAsk,
            allowedOrigins = InvocationSurfacePolicy.ALL_NON_KEYGUARD,
        ))

        reg(CapabilityDescriptor(
            id = CapabilityId.TextToSpeech,
            localToolOption = LocalToolOption.Tts,
            requirements = emptyList(),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.Low,
            approvalPolicy = ApprovalPolicy.Default,
            allowedOrigins = InvocationSurfacePolicy.ALL_NON_KEYGUARD,
        ))

        reg(CapabilityDescriptor(
            id = CapabilityId.AskUser,
            localToolOption = LocalToolOption.AskUser,
            requirements = emptyList(),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.Low,
            approvalPolicy = ApprovalPolicy.Default,
            allowedOrigins = InvocationSurfacePolicy.ALL_NON_KEYGUARD,
            requiresForegroundApp = true,
        ))

        // ═══════════════════════════════════════════════════════════════════════════
        // SECTION: Device Control
        // ═══════════════════════════════════════════════════════════════════════════

        reg(CapabilityDescriptor(
            id = CapabilityId.Toast,
            localToolOption = LocalToolOption.Toast,
            requirements = emptyList(),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.Low,
            approvalPolicy = ApprovalPolicy.Default,
            allowedOrigins = InvocationSurfacePolicy.ALL_NON_KEYGUARD,
        ))

        reg(CapabilityDescriptor(
            id = CapabilityId.Notification,
            localToolOption = LocalToolOption.Notification,
            requirements = listOf(
                CapabilityRequirement.RuntimePermission(Manifest.permission.POST_NOTIFICATIONS),
            ),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.Medium,
            approvalPolicy = ApprovalPolicy.AlwaysAsk,
            allowedOrigins = InvocationSurfacePolicy.LOCAL_OR_WORKFLOW,
        ))

        reg(CapabilityDescriptor(
            id = CapabilityId.Share,
            localToolOption = LocalToolOption.Share,
            requirements = emptyList(),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.Medium,
            approvalPolicy = ApprovalPolicy.AlwaysAsk,
            allowedOrigins = InvocationSurfacePolicy.ALL_NON_KEYGUARD,
        ))

        reg(CapabilityDescriptor(
            id = CapabilityId.Torch,
            localToolOption = LocalToolOption.Torch,
            requirements = emptyList(),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.Low,
            approvalPolicy = ApprovalPolicy.Default,
            allowedOrigins = InvocationSurfacePolicy.ALL_NON_KEYGUARD,
        ))

        reg(CapabilityDescriptor(
            id = CapabilityId.Vibrate,
            localToolOption = LocalToolOption.Vibrate,
            requirements = emptyList(),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.Low,
            approvalPolicy = ApprovalPolicy.Default,
            allowedOrigins = InvocationSurfacePolicy.ALL_NON_KEYGUARD,
        ))

        reg(CapabilityDescriptor(
            id = CapabilityId.Brightness,
            localToolOption = LocalToolOption.Brightness,
            requirements = listOf(
                CapabilityRequirement.SpecialAccess(SpecialAccessType.WriteSettings),
            ),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.Medium,
            approvalPolicy = ApprovalPolicy.AlwaysAsk,
            allowedOrigins = InvocationSurfacePolicy.LOCAL_OR_WORKFLOW,
        ))

        reg(CapabilityDescriptor(
            id = CapabilityId.Volume,
            localToolOption = LocalToolOption.Volume,
            requirements = listOf(
                CapabilityRequirement.SpecialAccess(SpecialAccessType.WriteSettings),
            ),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.Medium,
            approvalPolicy = ApprovalPolicy.AlwaysAsk,
            allowedOrigins = InvocationSurfacePolicy.LOCAL_OR_WORKFLOW,
        ))

        reg(CapabilityDescriptor(
            id = CapabilityId.MediaPlayer,
            localToolOption = LocalToolOption.MediaPlayer,
            requirements = emptyList(),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.Low,
            approvalPolicy = ApprovalPolicy.Default,
            allowedOrigins = InvocationSurfacePolicy.ALL_NON_KEYGUARD,
        ))

        reg(CapabilityDescriptor(
            id = CapabilityId.MediaScanner,
            localToolOption = LocalToolOption.MediaScanner,
            requirements = emptyList(),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.Low,
            approvalPolicy = ApprovalPolicy.AlwaysAsk, // scanning can make files visible to other apps
            allowedOrigins = InvocationSurfacePolicy.ALL_NON_KEYGUARD,
        ))

        reg(CapabilityDescriptor(
            id = CapabilityId.Download,
            localToolOption = LocalToolOption.Download,
            requirements = listOf(
                CapabilityRequirement.SpecialAccess(SpecialAccessType.AllFilesAccess),
            ),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.Medium,
            approvalPolicy = ApprovalPolicy.AlwaysAsk,
            allowedOrigins = InvocationSurfacePolicy.LOCAL_OR_WORKFLOW,
        ))

        reg(CapabilityDescriptor(
            id = CapabilityId.SetWallpaper,
            localToolOption = LocalToolOption.Wallpaper,
            requirements = listOf(
                CapabilityRequirement.ManifestPermission(Manifest.permission.SET_WALLPAPER),
            ),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.Medium,
            approvalPolicy = ApprovalPolicy.AlwaysAsk,
            allowedOrigins = InvocationSurfacePolicy.LOCAL_OR_WORKFLOW,
        ))

        // ═══════════════════════════════════════════════════════════════════════════
        // SECTION: Location & Sensors
        // ═══════════════════════════════════════════════════════════════════════════

        reg(CapabilityDescriptor(
            id = CapabilityId.Location,
            localToolOption = LocalToolOption.Location,
            requirements = listOf(
                CapabilityRequirement.RuntimePermission(Manifest.permission.ACCESS_COARSE_LOCATION),
            ),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.Medium,
            approvalPolicy = ApprovalPolicy.AlwaysAsk,
            allowedOrigins = InvocationSurfacePolicy.ALL_NON_KEYGUARD,
        ))

        reg(CapabilityDescriptor(
            id = CapabilityId.ReverseGeocoding,
            localToolOption = null,
            toolNames = setOf("reverse_geocode"),
            requirements = emptyList(),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.Medium,
            approvalPolicy = ApprovalPolicy.AlwaysAsk,
            allowedOrigins = InvocationSurfacePolicy.CONFIRMED_LOCAL_SECOND_USER,
            requiresUnlockedDevice = true,
            requiresForegroundApp = false,
        ))

        reg(CapabilityDescriptor(
            id = CapabilityId.GnssDiagnostics,
            localToolOption = null,
            toolNames = setOf("get_gnss_status"),
            requirements = listOf(
                CapabilityRequirement.RuntimePermission(Manifest.permission.ACCESS_FINE_LOCATION),
            ),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.Medium,
            approvalPolicy = ApprovalPolicy.AlwaysAsk,
            allowedOrigins = setOf(ToolCallOrigin.LocalChat),
            requiresUnlockedDevice = true,
            requiresForegroundApp = true,
        ))

        reg(CapabilityDescriptor(
            id = CapabilityId.StepCounter,
            localToolOption = LocalToolOption.StepCounter,
            requirements = listOf(
                CapabilityRequirement.RuntimePermission(Manifest.permission.ACTIVITY_RECOGNITION),
            ),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.Low,
            approvalPolicy = ApprovalPolicy.Default,
            allowedOrigins = InvocationSurfacePolicy.ALL_NON_KEYGUARD,
        ))

        // ═══════════════════════════════════════════════════════════════════════════
        // SECTION: Contacts & Communication
        // ═══════════════════════════════════════════════════════════════════════════

        reg(CapabilityDescriptor(
            id = CapabilityId.Calendar,
            localToolOption = LocalToolOption.Calendar,
            requirements = listOf(
                CapabilityRequirement.RuntimePermission(Manifest.permission.READ_CALENDAR),
                CapabilityRequirement.RuntimePermission(Manifest.permission.WRITE_CALENDAR),
            ),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.Medium,
            approvalPolicy = ApprovalPolicy.AlwaysAsk,
            allowedOrigins = InvocationSurfacePolicy.LOCAL_UNLOCKED,
        ))

        reg(CapabilityDescriptor(
            id = CapabilityId.Contacts,
            localToolOption = LocalToolOption.Contacts,
            requirements = listOf(
                CapabilityRequirement.RuntimePermission(Manifest.permission.READ_CONTACTS),
            ),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.Medium,
            approvalPolicy = ApprovalPolicy.AlwaysAsk,
            allowedOrigins = InvocationSurfacePolicy.ALL_NON_KEYGUARD,
        ))

        reg(CapabilityDescriptor(
            id = CapabilityId.CallLog,
            localToolOption = LocalToolOption.CallLog,
            requirements = listOf(
                CapabilityRequirement.RuntimePermission(Manifest.permission.READ_CALL_LOG),
            ),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.Medium,
            approvalPolicy = ApprovalPolicy.AlwaysAsk,
            allowedOrigins = InvocationSurfacePolicy.ALL_NON_KEYGUARD,
        ))

        reg(CapabilityDescriptor(
            id = CapabilityId.SmsInbox,
            localToolOption = LocalToolOption.SmsInbox,
            requirements = listOf(
                CapabilityRequirement.RuntimePermission(Manifest.permission.READ_SMS),
            ),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.Medium,
            approvalPolicy = ApprovalPolicy.AlwaysAsk,
            allowedOrigins = InvocationSurfacePolicy.ALL_NON_KEYGUARD,
        ))

        reg(CapabilityDescriptor(
            id = CapabilityId.SmsSend,
            localToolOption = LocalToolOption.SmsSend,
            requirements = listOf(
                CapabilityRequirement.RuntimePermission(Manifest.permission.SEND_SMS),
            ),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.High,
            approvalPolicy = ApprovalPolicy.AlwaysAsk,
            allowedOrigins = InvocationSurfacePolicy.LOCAL_UNLOCKED,
            requiresUnlockedDevice = true,
        ))

        reg(CapabilityDescriptor(
            id = CapabilityId.CameraPhoto,
            localToolOption = LocalToolOption.CameraPhoto,
            requirements = listOf(
                CapabilityRequirement.RuntimePermission(Manifest.permission.CAMERA),
            ),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.Medium,
            approvalPolicy = ApprovalPolicy.AlwaysAsk,
            allowedOrigins = InvocationSurfacePolicy.ALL_NON_KEYGUARD,
        ))

        reg(CapabilityDescriptor(
            id = CapabilityId.MicRecorder,
            localToolOption = LocalToolOption.MicRecorder,
            requirements = listOf(
                CapabilityRequirement.RuntimePermission(Manifest.permission.RECORD_AUDIO),
            ),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.Medium,
            approvalPolicy = ApprovalPolicy.AlwaysAsk,
            allowedOrigins = InvocationSurfacePolicy.ALL_NON_KEYGUARD,
        ))

        reg(CapabilityDescriptor(
            id = CapabilityId.SpeechToText,
            localToolOption = LocalToolOption.SpeechToText,
            requirements = listOf(
                CapabilityRequirement.RuntimePermission(Manifest.permission.RECORD_AUDIO),
            ),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.Medium,
            approvalPolicy = ApprovalPolicy.AlwaysAsk,
            allowedOrigins = InvocationSurfacePolicy.ALL_NON_KEYGUARD,
        ))

        reg(CapabilityDescriptor(
            id = CapabilityId.Fingerprint,
            localToolOption = LocalToolOption.Fingerprint,
            requirements = listOf(
                CapabilityRequirement.RuntimePermission(Manifest.permission.USE_BIOMETRIC),
            ),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.Medium,
            approvalPolicy = ApprovalPolicy.AlwaysAsk,
            allowedOrigins = setOf(ToolCallOrigin.LocalChat),
            requiresUnlockedDevice = true,
        ))

        // ═══════════════════════════════════════════════════════════════════════════
        // SECTION: System Automation
        // ═══════════════════════════════════════════════════════════════════════════

        reg(CapabilityDescriptor(
            id = CapabilityId.ScreenTime,
            localToolOption = LocalToolOption.ScreenTime,
            requirements = listOf(
                CapabilityRequirement.SpecialAccess(SpecialAccessType.UsageStats),
            ),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.Medium,
            approvalPolicy = ApprovalPolicy.AlwaysAsk,
            allowedOrigins = InvocationSurfacePolicy.LOCAL_UNLOCKED,
            requiresForegroundApp = true,
        ))

        reg(CapabilityDescriptor(
            id = CapabilityId.CronJobs,
            localToolOption = LocalToolOption.CronJobs,
            requirements = listOf(
                CapabilityRequirement.ManifestPermission(Manifest.permission.RECEIVE_BOOT_COMPLETED),
                CapabilityRequirement.SpecialAccess(SpecialAccessType.ExactAlarm),
            ),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.Medium,
            approvalPolicy = ApprovalPolicy.AlwaysAsk,
            allowedOrigins = InvocationSurfacePolicy.LOCAL_OR_WORKFLOW,
        ))

        reg(CapabilityDescriptor(
            id = CapabilityId.ScreenAutomation,
            localToolOption = LocalToolOption.ScreenAutomation,
            requirements = listOf(
                CapabilityRequirement.EnabledService(
                    ComponentName("me.rerere.rikkahub", "me.rerere.rikkahub.service.RikkaAccessibilityService"),
                ),
            ),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.High,
            approvalPolicy = ApprovalPolicy.AlwaysAsk,
            allowedOrigins = InvocationSurfacePolicy.LOCAL_OR_WORKFLOW,
            requiresForegroundApp = true,
        ))

        reg(CapabilityDescriptor(
            id = CapabilityId.AppLauncher,
            localToolOption = LocalToolOption.AppLauncher,
            requirements = listOf(
                CapabilityRequirement.ManifestPermission(Manifest.permission.QUERY_ALL_PACKAGES),
            ),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.Medium,
            approvalPolicy = ApprovalPolicy.AlwaysAsk,
            allowedOrigins = InvocationSurfacePolicy.ALL_NON_KEYGUARD,
        ))

        reg(CapabilityDescriptor(
            id = CapabilityId.SystemIntents,
            localToolOption = LocalToolOption.SystemIntents,
            requirements = emptyList(),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.Low,
            approvalPolicy = ApprovalPolicy.Default,
            allowedOrigins = InvocationSurfacePolicy.ALL_NON_KEYGUARD,
        ))

        reg(CapabilityDescriptor(
            id = CapabilityId.KeyboardControl,
            localToolOption = LocalToolOption.KeyboardControl,
            requirements = listOf(
                CapabilityRequirement.RuntimePermission("dev.patrickgold.florisboard.permission.AGENT_KEYBOARD_API"),
            ),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.Medium,
            approvalPolicy = ApprovalPolicy.AlwaysAsk,
            allowedOrigins = InvocationSurfacePolicy.LOCAL_OR_WORKFLOW,
        ))

        // ═══════════════════════════════════════════════════════════════════════════
        // SECTION: Files & Storage
        // ═══════════════════════════════════════════════════════════════════════════

        reg(CapabilityDescriptor(
            id = CapabilityId.Files,
            localToolOption = LocalToolOption.Files,
            requirements = listOf(
                CapabilityRequirement.SpecialAccess(SpecialAccessType.AllFilesAccess),
            ),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.High,
            approvalPolicy = ApprovalPolicy.AlwaysAsk,
            allowedOrigins = InvocationSurfacePolicy.LOCAL_OR_WORKFLOW,
        ))

        reg(CapabilityDescriptor(
            id = CapabilityId.ExternalStorage,
            localToolOption = LocalToolOption.ExternalStorage,
            requirements = listOf(
                CapabilityRequirement.SpecialAccess(SpecialAccessType.AllFilesAccess),
            ),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.High,
            approvalPolicy = ApprovalPolicy.AlwaysAsk,
            allowedOrigins = InvocationSurfacePolicy.LOCAL_OR_WORKFLOW,
        ))

        reg(CapabilityDescriptor(
            id = CapabilityId.Archive,
            localToolOption = LocalToolOption.Archive,
            requirements = listOf(
                CapabilityRequirement.SpecialAccess(SpecialAccessType.AllFilesAccess),
            ),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.High,
            approvalPolicy = ApprovalPolicy.AlwaysAsk,
            allowedOrigins = InvocationSurfacePolicy.LOCAL_OR_WORKFLOW,
        ))

        reg(CapabilityDescriptor(
            id = CapabilityId.MediaLibrary,
            localToolOption = LocalToolOption.MediaLibrary,
            requirements = listOf(
                CapabilityRequirement.RuntimePermission(Manifest.permission.READ_MEDIA_IMAGES),
                CapabilityRequirement.RuntimePermission(Manifest.permission.READ_MEDIA_VIDEO),
                CapabilityRequirement.RuntimePermission(Manifest.permission.READ_MEDIA_AUDIO),
            ),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.Medium,
            approvalPolicy = ApprovalPolicy.AlwaysAsk,
            allowedOrigins = InvocationSurfacePolicy.ALL_NON_KEYGUARD,
        ))

        reg(CapabilityDescriptor(
            id = CapabilityId.ExportConversation,
            localToolOption = LocalToolOption.ExportConversation,
            requirements = emptyList(),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.Low,
            approvalPolicy = ApprovalPolicy.AlwaysAsk,
            allowedOrigins = InvocationSurfacePolicy.LOCAL_UNLOCKED,
        ))

        // Every name here reads message CONTENT across conversations, so all of them require an
        // unlocked device and a local origin.
        //
        // The ordinary assistant's `conversation_search` belongs here on its own merits, and
        // listing it explicitly matters: it used to be covered only because it shared a name
        // with the Second-User reader's search tool. When that collision was removed (the reader
        // is now `transient_conversation_search`), this entry had to name it directly or the
        // ordinary tool would have silently lost `requiresUnlockedDevice`, which
        // ToolExecutionGate enforces.
        //
        // `recent_chats` is intentionally absent: it returns titles and dates only, and is meant
        // to stay reachable from other origins.
        reg(CapabilityDescriptor(
            id = CapabilityId.ConversationHistoryRead,
            localToolOption = null,
            toolNames = me.rerere.rikkahub.data.ai.tools.TRANSIENT_CONVERSATION_READER_TOOL_NAMES +
                me.rerere.rikkahub.data.ai.tools.CONTENT_BEARING_CONVERSATION_TOOL_NAMES,
            requirements = emptyList(),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.Medium,
            approvalPolicy = ApprovalPolicy.Default,
            allowedOrigins = setOf(
                ToolCallOrigin.LocalChat,
                ToolCallOrigin.SystemAssistant,
                ToolCallOrigin.QuickCapture,
            ),
            requiresUnlockedDevice = true,
        ))

        // ═══════════════════════════════════════════════════════════════════════════
        // SECTION: Privileged Bridges
        // ═══════════════════════════════════════════════════════════════════════════

        reg(CapabilityDescriptor(
            id = CapabilityId.Ssh,
            localToolOption = LocalToolOption.Ssh,
            requirements = listOf(
                CapabilityRequirement.RuntimePermission(Manifest.permission.ACCESS_LOCAL_NETWORK),
            ),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.High,
            approvalPolicy = ApprovalPolicy.AlwaysAsk,
            allowedOrigins = InvocationSurfacePolicy.LOCAL_UNLOCKED,
            requiresUnlockedDevice = true,
        ))

        reg(CapabilityDescriptor(
            id = CapabilityId.Termux,
            localToolOption = LocalToolOption.Termux,
            requirements = listOf(
                CapabilityRequirement.RuntimePermission("com.termux.permission.RUN_COMMAND"),
            ),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.Critical,
            approvalPolicy = ApprovalPolicy.AlwaysAsk,
            allowedOrigins = InvocationSurfacePolicy.LOCAL_UNLOCKED,
            requiresUnlockedDevice = true,
        ))

        // ═══════════════════════════════════════════════════════════════════════════
        // SECTION: Remote Entry & Control
        // ═══════════════════════════════════════════════════════════════════════════

        reg(CapabilityDescriptor(
            id = CapabilityId.TelegramBot,
            localToolOption = LocalToolOption.TelegramBot,
            requirements = emptyList(), // network + FG service, already declared in manifest
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.High,
            approvalPolicy = ApprovalPolicy.AlwaysAsk,
            allowedOrigins = InvocationSurfacePolicy.LOCAL_OR_WORKFLOW,
        ))

        reg(CapabilityDescriptor(
            id = CapabilityId.McpControl,
            localToolOption = LocalToolOption.McpControl,
            requirements = emptyList(),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.High,
            approvalPolicy = ApprovalPolicy.AlwaysAsk,
            allowedOrigins = InvocationSurfacePolicy.LOCAL_OR_WORKFLOW,
        ))

        reg(CapabilityDescriptor(
            id = CapabilityId.ExternalAutomation,
            localToolOption = LocalToolOption.ExternalAutomation,
            requirements = emptyList(),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.High,
            approvalPolicy = ApprovalPolicy.AlwaysAsk,
            allowedOrigins = InvocationSurfacePolicy.LOCAL_UNLOCKED,
        ))

        reg(CapabilityDescriptor(
            id = CapabilityId.WebSearch,
            localToolOption = null,
            toolNames = setOf("search_web", "scrape_web"),
            requirements = emptyList(),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.Low,
            approvalPolicy = ApprovalPolicy.Default,
            allowedOrigins = InvocationSurfacePolicy.ALL_NON_KEYGUARD,
        ))

        reg(CapabilityDescriptor(
            id = CapabilityId.WebFetch,
            localToolOption = LocalToolOption.WebFetch,
            requirements = emptyList(),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.Medium,
            approvalPolicy = ApprovalPolicy.AlwaysAsk,
            allowedOrigins = InvocationSurfacePolicy.ALL_NON_KEYGUARD,
        ))

        reg(CapabilityDescriptor(
            id = CapabilityId.Browser,
            localToolOption = LocalToolOption.Browser,
            requirements = emptyList(),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.Medium,
            approvalPolicy = ApprovalPolicy.AlwaysAsk,
            allowedOrigins = InvocationSurfacePolicy.ALL_NON_KEYGUARD,
        ))

        // ═══════════════════════════════════════════════════════════════════════════
        // SECTION: Background & Automation
        // ═══════════════════════════════════════════════════════════════════════════

        reg(CapabilityDescriptor(
            id = CapabilityId.NotificationListener,
            localToolOption = LocalToolOption.NotificationListener,
            requirements = listOf(
                CapabilityRequirement.EnabledService(
                    ComponentName(
                        "me.rerere.rikkahub",
                        "me.rerere.rikkahub.service.RikkaNotificationListenerService",
                    ),
                ),
            ),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.High,
            approvalPolicy = ApprovalPolicy.AlwaysAsk,
            allowedOrigins = InvocationSurfacePolicy.LOCAL_OR_WORKFLOW,
            requiresForegroundApp = true,
        ))

        reg(CapabilityDescriptor(
            id = CapabilityId.Workflows,
            localToolOption = LocalToolOption.Workflows,
            requirements = emptyList(),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.Medium,
            approvalPolicy = ApprovalPolicy.AlwaysAsk,
            allowedOrigins = InvocationSurfacePolicy.LOCAL_OR_WORKFLOW,
        ))

        reg(CapabilityDescriptor(
            id = CapabilityId.CostGuards,
            localToolOption = LocalToolOption.CostGuards,
            requirements = emptyList(),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.Low,
            approvalPolicy = ApprovalPolicy.Default,
            allowedOrigins = InvocationSurfacePolicy.ALL_NON_KEYGUARD,
        ))

        reg(CapabilityDescriptor(
            id = CapabilityId.SubAgents,
            localToolOption = LocalToolOption.SubAgents,
            requirements = emptyList(),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.High,
            approvalPolicy = ApprovalPolicy.AlwaysAsk,
            allowedOrigins = InvocationSurfacePolicy.LOCAL_OR_WORKFLOW,
        ))

        reg(CapabilityDescriptor(
            id = CapabilityId.Alarm,
            localToolOption = LocalToolOption.Alarm,
            requirements = listOf(
                CapabilityRequirement.SpecialAccess(SpecialAccessType.ExactAlarm),
            ),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.Medium,
            approvalPolicy = ApprovalPolicy.AlwaysAsk,
            allowedOrigins = InvocationSurfacePolicy.LOCAL_OR_WORKFLOW,
        ))

        reg(CapabilityDescriptor(
            id = CapabilityId.BluetoothDevices,
            localToolOption = LocalToolOption.BluetoothDevices,
            requirements = listOf(
                CapabilityRequirement.RuntimePermission(Manifest.permission.BLUETOOTH_CONNECT),
                CapabilityRequirement.RuntimePermission(Manifest.permission.BLUETOOTH_SCAN),
            ),
            implementationState = ImplementationState.Reserved,
            riskLevel = RiskLevel.Medium,
            approvalPolicy = ApprovalPolicy.AlwaysAsk,
            allowedOrigins = setOf(ToolCallOrigin.LocalChat),
        ))

        // ═══════════════════════════════════════════════════════════════════════════
        // SECTION: Security & Crypto
        // ═══════════════════════════════════════════════════════════════════════════

        reg(CapabilityDescriptor(
            id = CapabilityId.Keystore,
            localToolOption = LocalToolOption.Keystore,
            requirements = emptyList(),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.High,
            approvalPolicy = ApprovalPolicy.AlwaysAsk,
            allowedOrigins = InvocationSurfacePolicy.LOCAL_UNLOCKED,
            requiresUnlockedDevice = true,
        ))

        reg(CapabilityDescriptor(
            id = CapabilityId.Nfc,
            localToolOption = LocalToolOption.Nfc,
            requirements = listOf(
                CapabilityRequirement.ManifestPermission(Manifest.permission.NFC),
            ),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.Medium,
            approvalPolicy = ApprovalPolicy.AlwaysAsk,
            allowedOrigins = setOf(ToolCallOrigin.LocalChat),
        ))

        reg(CapabilityDescriptor(
            id = CapabilityId.Reliability,
            localToolOption = LocalToolOption.Reliability,
            requirements = emptyList(),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.Low,
            approvalPolicy = ApprovalPolicy.Default,
            allowedOrigins = InvocationSurfacePolicy.LOCAL_OR_WORKFLOW,
        ))

        // ═══════════════════════════════════════════════════════════════════════════
        // SECTION: Extensibility
        // ═══════════════════════════════════════════════════════════════════════════

        reg(CapabilityDescriptor(
            id = CapabilityId.SkillImport,
            localToolOption = LocalToolOption.SkillImport,
            requirements = emptyList(),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.High,
            approvalPolicy = ApprovalPolicy.AlwaysAsk,
            allowedOrigins = InvocationSurfacePolicy.LOCAL_UNLOCKED,
        ))

        reg(CapabilityDescriptor(
            id = CapabilityId.JsSkills,
            localToolOption = LocalToolOption.JsSkills,
            requirements = emptyList(),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.High,
            approvalPolicy = ApprovalPolicy.AlwaysAsk,
            allowedOrigins = InvocationSurfacePolicy.LOCAL_UNLOCKED,
        ))

        // ═══════════════════════════════════════════════════════════════════════════
        // SECTION: V2 Reserved Capabilities (not yet implemented)
        // ═══════════════════════════════════════════════════════════════════════════

        reg(CapabilityDescriptor(
            id = CapabilityId.MediaWrite,
            localToolOption = LocalToolOption.MediaWrite,
            toolNames = setOf("media_copy", "media_move"),
            requirements = listOf(
                CapabilityRequirement.SpecialAccess(SpecialAccessType.AllFilesAccess),
            ),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.High,
            approvalPolicy = ApprovalPolicy.AlwaysAsk,
            allowedOrigins = InvocationSurfacePolicy.LOCAL_UNLOCKED,
            requiresUnlockedDevice = true,
        ))

        reg(CapabilityDescriptor(
            id = CapabilityId.ContactsWrite,
            localToolOption = null,
            requirements = listOf(
                CapabilityRequirement.RuntimePermission(Manifest.permission.WRITE_CONTACTS),
                CapabilityRequirement.RuntimePermission(Manifest.permission.GET_ACCOUNTS),
            ),
            implementationState = ImplementationState.Reserved,
            riskLevel = RiskLevel.High,
            approvalPolicy = ApprovalPolicy.AlwaysAsk,
            allowedOrigins = setOf(ToolCallOrigin.LocalChat),
        ))

        reg(CapabilityDescriptor(
            id = CapabilityId.PhoneActions,
            localToolOption = LocalToolOption.PhoneActions,
            toolNames = setOf("call_phone"),
            requirements = listOf(
                CapabilityRequirement.RuntimePermission(Manifest.permission.CALL_PHONE),
                CapabilityRequirement.RuntimePermission(Manifest.permission.READ_PHONE_STATE),
            ),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.Critical,
            approvalPolicy = ApprovalPolicy.AlwaysAsk,
            allowedOrigins = setOf(ToolCallOrigin.LocalChat),
            requiresUnlockedDevice = true,
        ))

        reg(CapabilityDescriptor(
            id = CapabilityId.SmsReceive,
            localToolOption = null,
            requirements = listOf(
                CapabilityRequirement.RuntimePermission(Manifest.permission.RECEIVE_SMS),
            ),
            implementationState = ImplementationState.Reserved,
            riskLevel = RiskLevel.High,
            approvalPolicy = ApprovalPolicy.AlwaysAsk,
            allowedOrigins = setOf(ToolCallOrigin.LocalChat),
        ))

        reg(CapabilityDescriptor(
            id = CapabilityId.NearbyDevices,
            localToolOption = LocalToolOption.NearbyDevices,
            toolNames = setOf("bluetooth_scan", "list_paired_bluetooth_devices"),
            requirements = listOf(
                CapabilityRequirement.RuntimePermission(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    maxSdk = 30,
                ),
                CapabilityRequirement.RuntimePermission(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    minSdk = 31,
                ),
                CapabilityRequirement.RuntimePermission(
                    Manifest.permission.BLUETOOTH_SCAN,
                    minSdk = 31,
                ),
            ),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.Medium,
            approvalPolicy = ApprovalPolicy.AlwaysAsk,
            allowedOrigins = InvocationSurfacePolicy.LOCAL_UNLOCKED,
        ))

        reg(CapabilityDescriptor(
            id = CapabilityId.HealthSensors,
            localToolOption = LocalToolOption.HealthSensors,
            toolNames = setOf("list_health_sensors", "read_health_sensor"),
            requirements = listOf(
                CapabilityRequirement.RuntimePermission(Manifest.permission.BODY_SENSORS),
            ),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.Medium,
            approvalPolicy = ApprovalPolicy.AlwaysAsk,
            allowedOrigins = InvocationSurfacePolicy.LOCAL_UNLOCKED,
        ))

        reg(CapabilityDescriptor(
            id = CapabilityId.PackageManagement,
            localToolOption = LocalToolOption.PackageManagement,
            toolNames = setOf("install_apk"),
            requirements = listOf(
                CapabilityRequirement.SpecialAccess(SpecialAccessType.InstallUnknownApps),
                CapabilityRequirement.ManifestPermission(Manifest.permission.REQUEST_INSTALL_PACKAGES),
            ),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.Critical,
            approvalPolicy = ApprovalPolicy.AlwaysAsk,
            allowedOrigins = setOf(ToolCallOrigin.LocalChat),
            requiresUnlockedDevice = true,
        ))

        reg(CapabilityDescriptor(
            id = CapabilityId.DeviceAdmin,
            localToolOption = null,
            requirements = listOf(
                CapabilityRequirement.SpecialAccess(SpecialAccessType.DeviceAdmin),
            ),
            implementationState = ImplementationState.Reserved,
            riskLevel = RiskLevel.Critical,
            approvalPolicy = ApprovalPolicy.AlwaysAsk,
            allowedOrigins = setOf(ToolCallOrigin.LocalChat),
            requiresUnlockedDevice = true,
        ))

        reg(CapabilityDescriptor(
            id = CapabilityId.VpnControl,
            localToolOption = null,
            requirements = listOf(
                CapabilityRequirement.VpnConsent,
            ),
            implementationState = ImplementationState.Reserved,
            riskLevel = RiskLevel.Critical,
            approvalPolicy = ApprovalPolicy.AlwaysAsk,
            allowedOrigins = setOf(ToolCallOrigin.LocalChat),
            requiresUnlockedDevice = true,
        ))

        reg(CapabilityDescriptor(
            id = CapabilityId.MediaProjection,
            localToolOption = null,
            requirements = listOf(
                CapabilityRequirement.MediaProjectionConsent,
            ),
            implementationState = ImplementationState.Reserved,
            riskLevel = RiskLevel.Critical,
            approvalPolicy = ApprovalPolicy.AlwaysAsk,
            allowedOrigins = setOf(ToolCallOrigin.LocalChat),
            requiresUnlockedDevice = true,
        ))

        reg(CapabilityDescriptor(
            id = CapabilityId.ExternalPrivilegeBridge,
            localToolOption = LocalToolOption.ExternalPrivilegeBridge,
            toolNames = setOf(
                "shizuku_status",
                "list_packages",
                "force_stop_app",
                "clear_app_cache",
            ),
            requirements = listOf(
                CapabilityRequirement.ExternalBridge(BridgeType.Shizuku),
            ),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.Critical,
            approvalPolicy = ApprovalPolicy.AlwaysAsk,
            allowedOrigins = InvocationSurfacePolicy.LOCAL_UNLOCKED,
            requiresUnlockedDevice = true,
        ))

        reg(CapabilityDescriptor(
            id = CapabilityId.PrivilegedShell,
            localToolOption = null,
            toolNames = setOf("external_bridge_run_command"),
            requirements = listOf(
                CapabilityRequirement.ExternalBridge(BridgeType.Shizuku),
            ),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.Critical,
            approvalPolicy = ApprovalPolicy.AlwaysAsk,
            allowedOrigins = InvocationSurfacePolicy.LOCAL_UNLOCKED,
            requiresUnlockedDevice = true,
        ))

        reg(CapabilityDescriptor(
            id = CapabilityId.StructuredPrivilegedSystemTools,
            localToolOption = null,
            toolNames = me.rerere.rikkahub.privilege.STRUCTURED_PRIVILEGED_TOOL_NAMES,
            requirements = listOf(
                CapabilityRequirement.ExternalBridge(BridgeType.Shizuku),
            ),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.High,
            approvalPolicy = ApprovalPolicy.AlwaysAsk,
            allowedOrigins = InvocationSurfacePolicy.LOCAL_UNLOCKED,
            requiresUnlockedDevice = true,
        ))

        reg(CapabilityDescriptor(
            id = CapabilityId.StructuredPrivilegedSystemToolsV2,
            localToolOption = null,
            toolNames = me.rerere.rikkahub.privilege.STRUCTURED_PRIVILEGED_V2_TOOL_NAMES,
            requirements = listOf(
                CapabilityRequirement.ExternalBridge(BridgeType.Shizuku),
            ),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.High,
            approvalPolicy = ApprovalPolicy.AlwaysAsk,
            allowedOrigins = InvocationSurfacePolicy.LOCAL_UNLOCKED,
            requiresUnlockedDevice = true,
        ))

        reg(CapabilityDescriptor(
            id = CapabilityId.VerifiedAccessibility,
            localToolOption = null,
            toolNames = me.rerere.rikkahub.data.ai.tools.local.VERIFIED_ACCESSIBILITY_TOOL_NAMES,
            requirements = listOf(
                CapabilityRequirement.EnabledService(
                    ComponentName(
                        "me.rerere.rikkahub",
                        "me.rerere.rikkahub.service.RikkaAccessibilityService",
                    ),
                ),
            ),
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.High,
            approvalPolicy = ApprovalPolicy.AlwaysAsk,
            allowedOrigins = InvocationSurfacePolicy.LOCAL_UNLOCKED,
            requiresUnlockedDevice = true,
        ))

        reg(CapabilityDescriptor(
            id = CapabilityId.WorkspaceTools,
            localToolOption = null,
            toolNames = me.rerere.rikkahub.data.ai.tools.WORKSPACE_TOOL_NAMES,
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.Critical,
            approvalPolicy = ApprovalPolicy.Default,
            allowedOrigins = InvocationSurfacePolicy.LOCAL_UNLOCKED,
            requiresUnlockedDevice = true,
            requirements = emptyList(),
        ))

        reg(CapabilityDescriptor(
            id = CapabilityId.WorkspaceProcessManagement,
            localToolOption = null,
            toolNames = me.rerere.rikkahub.data.ai.tools.WORKSPACE_PROCESS_TOOL_NAMES,
            implementationState = ImplementationState.Implemented,
            riskLevel = RiskLevel.High,
            approvalPolicy = ApprovalPolicy.AlwaysAsk,
            allowedOrigins = InvocationSurfacePolicy.LOCAL_UNLOCKED,
            requiresUnlockedDevice = false,
            requirements = emptyList(),
        ))

        return map
    }
}
