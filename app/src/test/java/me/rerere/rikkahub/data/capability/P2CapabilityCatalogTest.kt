package me.rerere.rikkahub.data.capability

import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.ai.InvocationSurfacePolicy
import me.rerere.rikkahub.data.ai.tools.ToolApprovalDefaults

class P2CapabilityCatalogTest {

    @Test
    fun `GNSS diagnostics is a precise local unlocked capability behind the Location switch`() {
        val capability = CapabilityCatalog.capabilityOf(CapabilityId.GnssDiagnostics)
        val permissions = capability?.requirements
            ?.filterIsInstance<CapabilityRequirement.RuntimePermission>()
            ?.map { it.permission }

        assertEquals(ImplementationState.Implemented, capability?.implementationState)
        assertEquals(null, capability?.localToolOption)
        assertEquals(setOf("get_gnss_status"), capability?.toolNames)
        assertEquals(listOf(Manifest.permission.ACCESS_FINE_LOCATION), permissions)
        assertEquals(setOf(ToolCallOrigin.LocalChat), capability?.allowedOrigins)
        assertTrue(capability?.requiresUnlockedDevice == true)
        assertTrue(capability?.requiresForegroundApp == true)
        assertTrue("get_gnss_status" in ToolApprovalDefaults.ALWAYS_ASK)
        assertTrue(ToolApprovalDefaults.allowsAlwaysAllow("get_gnss_status"))
    }

    @Test
    fun `location minimum permission is coarse while fine remains module controlled`() {
        val capability = CapabilityCatalog.capabilityOf(CapabilityId.Location)
        val permissions = capability?.requirements
            ?.filterIsInstance<CapabilityRequirement.RuntimePermission>()
            ?.map { it.permission }

        assertEquals(listOf(Manifest.permission.ACCESS_COARSE_LOCATION), permissions)
    }
    @Test
    fun `P2 tool names resolve to implemented capabilities`() {
        val expected = mapOf(
            "bluetooth_scan" to CapabilityId.NearbyDevices,
            "list_paired_bluetooth_devices" to CapabilityId.NearbyDevices,
            "list_health_sensors" to CapabilityId.HealthSensors,
            "read_health_sensor" to CapabilityId.HealthSensors,
            "media_copy" to CapabilityId.MediaWrite,
            "media_move" to CapabilityId.MediaWrite,
        )

        expected.forEach { (toolName, capabilityId) ->
            val descriptor = CapabilityCatalog.byToolName(toolName)
            assertEquals(capabilityId, descriptor?.id)
            assertEquals(ImplementationState.Implemented, descriptor?.implementationState)
            assertTrue(descriptor?.allowedOrigins?.isNotEmpty() == true)
        }
    }

    @Test
    fun `P2 capabilities expose their assistant switches`() {
        assertEquals(
            LocalToolOption.NearbyDevices,
            CapabilityCatalog.capabilityOf(CapabilityId.NearbyDevices)?.localToolOption,
        )
        assertEquals(
            LocalToolOption.HealthSensors,
            CapabilityCatalog.capabilityOf(CapabilityId.HealthSensors)?.localToolOption,
        )
        assertEquals(
            LocalToolOption.MediaWrite,
            CapabilityCatalog.capabilityOf(CapabilityId.MediaWrite)?.localToolOption,
        )
    }

    @Test
    fun `phone actions require call and phone-state permissions only`() {
        val phone = CapabilityCatalog.capabilityOf(CapabilityId.PhoneActions)
        val runtimePermissions = phone?.requirements
            ?.filterIsInstance<CapabilityRequirement.RuntimePermission>()
            ?.map { it.permission }
            ?.toSet()

        assertEquals(
            setOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE),
            runtimePermissions,
        )
        assertEquals(setOf("call_phone"), phone?.toolNames)
    }

    @Test
    fun `nearby permissions are SDK aware and legacy catalog entry is reserved`() {
        val nearby = CapabilityCatalog.capabilityOf(CapabilityId.NearbyDevices)
        val runtimePermissions = nearby?.requirements
            ?.filterIsInstance<CapabilityRequirement.RuntimePermission>()
            ?.associateBy { it.permission }
            .orEmpty()

        val legacyLocation = runtimePermissions.getValue(Manifest.permission.ACCESS_FINE_LOCATION)
        assertTrue(legacyLocation.appliesToSdk(30))
        assertTrue(!legacyLocation.appliesToSdk(31))

        val connect = runtimePermissions.getValue(Manifest.permission.BLUETOOTH_CONNECT)
        val scan = runtimePermissions.getValue(Manifest.permission.BLUETOOTH_SCAN)
        assertTrue(!connect.appliesToSdk(30))
        assertTrue(connect.appliesToSdk(31))
        assertTrue(!scan.appliesToSdk(30))
        assertTrue(scan.appliesToSdk(31))

        assertEquals(
            ImplementationState.Reserved,
            CapabilityCatalog.capabilityOf(CapabilityId.BluetoothDevices)?.implementationState,
        )
    }

    @Test
    fun `privileged shell is implemented but has no ordinary assistant switch`() {
        val shell = CapabilityCatalog.capabilityOf(CapabilityId.PrivilegedShell)

        assertEquals(ImplementationState.Implemented, shell?.implementationState)
        assertEquals(null, shell?.localToolOption)
        assertEquals(setOf("external_bridge_run_command"), shell?.toolNames)
        assertEquals(InvocationSurfacePolicy.LOCAL_UNLOCKED, shell?.allowedOrigins)
        assertTrue(shell?.requiresUnlockedDevice == true)
    }

    @Test
    fun `structured privileged tools are one local unlocked bridge capability`() {
        val structured = CapabilityCatalog.capabilityOf(CapabilityId.StructuredPrivilegedSystemTools)

        assertEquals(ImplementationState.Implemented, structured?.implementationState)
        assertEquals(null, structured?.localToolOption)
        assertEquals(me.rerere.rikkahub.privilege.STRUCTURED_PRIVILEGED_TOOL_NAMES, structured?.toolNames)
        assertEquals(InvocationSurfacePolicy.LOCAL_UNLOCKED, structured?.allowedOrigins)
        assertTrue(structured?.requiresUnlockedDevice == true)
        assertTrue(
            structured?.requirements?.any {
                it is CapabilityRequirement.ExternalBridge && it.type == BridgeType.Shizuku
            } == true,
        )
    }

    @Test
    fun `v2 structured tools are a separate local unlocked bridge capability`() {
        val capability = CapabilityCatalog.capabilityOf(CapabilityId.StructuredPrivilegedSystemToolsV2)

        assertEquals(ImplementationState.Implemented, capability?.implementationState)
        assertEquals(null, capability?.localToolOption)
        assertEquals(me.rerere.rikkahub.privilege.STRUCTURED_PRIVILEGED_V2_TOOL_NAMES, capability?.toolNames)
        assertEquals(InvocationSurfacePolicy.LOCAL_UNLOCKED, capability?.allowedOrigins)
        assertTrue(capability?.requiresUnlockedDevice == true)
        assertTrue(
            capability?.requirements?.any {
                it is CapabilityRequirement.ExternalBridge && it.type == BridgeType.Shizuku
            } == true,
        )
    }

    @Test
    fun `verified accessibility is local unlocked and has no ordinary assistant switch`() {
        val capability = CapabilityCatalog.capabilityOf(CapabilityId.VerifiedAccessibility)

        assertEquals(ImplementationState.Implemented, capability?.implementationState)
        assertEquals(null, capability?.localToolOption)
        assertEquals(
            me.rerere.rikkahub.data.ai.tools.local.VERIFIED_ACCESSIBILITY_TOOL_NAMES,
            capability?.toolNames,
        )
        assertEquals(InvocationSurfacePolicy.LOCAL_UNLOCKED, capability?.allowedOrigins)
        assertTrue(capability?.requiresUnlockedDevice == true)
        assertTrue(capability?.requirements?.any { it is CapabilityRequirement.EnabledService } == true)
    }

    @Test
    fun `catalog never grants any capability to the keyguard invocation identity`() {
        CapabilityCatalog.allCapabilities().forEach { capability ->
            assertTrue(
                "${capability.id} must not allow SystemAssistantKeyguard",
                ToolCallOrigin.SystemAssistantKeyguard !in capability.allowedOrigins,
            )
        }
        assertTrue(
            ToolCallOrigin.SystemAssistant !in
                CapabilityCatalog.capabilityOf(CapabilityId.PhoneActions)!!.allowedOrigins,
        )
    }

    @Test
    fun `system assistant surface is exact and excludes Activity-backed tools`() {
        assertEquals(
            CapabilityId.AppLauncher,
            CapabilityCatalog.byToolName("list_installed_apps")?.id,
        )
        assertEquals(
            ToolInvocationSurface.Background,
            CapabilityCatalog.toolInvocationSurface("list_installed_apps"),
        )
        assertTrue(CapabilityCatalog.isAvailableFromSystemAssistant("list_installed_apps"))

        setOf("launch_app", "open_url", "share").forEach { toolName ->
            assertEquals(
                "$toolName must stay out of the voice-session overlay",
                ToolInvocationSurface.Activity,
                CapabilityCatalog.toolInvocationSurface(toolName),
            )
            assertTrue(!CapabilityCatalog.isAvailableFromSystemAssistant(toolName))
        }
    }

    @Test
    fun `system assistant excludes arbitrary execution and generic interaction tools`() {
        val unboundedTools = setOf(
            "external_bridge_run_command",
            "termux_run_command",
            "termux_session_start",
            "termux_session_send",
            "termux_session_read",
            "termux_session_list",
            "termux_session_kill",
            "ssh_exec",
            "ssh_exec_saved",
            "run_js",
            "eval_javascript",
            "workspace_process_start",
            "workspace_process_restart",
            "ui_click_node_verified",
            "ui_set_text_verified",
            "ui_scroll_until",
            "keyboard_press_key",
            "privileged_send_broadcast",
        )

        unboundedTools.forEach { toolName ->
            assertEquals(
                "$toolName must not be exposed on the system-assistant overlay",
                ToolInvocationSurface.UnboundedExecution,
                CapabilityCatalog.toolInvocationSurface(toolName),
            )
            assertTrue(!CapabilityCatalog.isAvailableFromSystemAssistant(toolName))
        }

        setOf(
            "privileged_start_activity",
            "ask_user",
            "notification_action_click",
            "notification_reply",
        ).forEach { toolName ->
            assertEquals(
                ToolInvocationSurface.Activity,
                CapabilityCatalog.toolInvocationSurface(toolName),
            )
            assertTrue(!CapabilityCatalog.isAvailableFromSystemAssistant(toolName))
        }
        setOf("call_phone", "clipboard_tool", "text_to_speech", "web_fetch").forEach { toolName ->
            assertEquals(
                ToolInvocationSurface.Phase1Unavailable,
                CapabilityCatalog.toolInvocationSurface(toolName),
            )
            assertTrue(!CapabilityCatalog.isAvailableFromSystemAssistant(toolName))
        }
        assertTrue(CapabilityCatalog.isAvailableFromSystemAssistant("privileged_package_inspect"))
        assertTrue(CapabilityCatalog.isAvailableFromSystemAssistant("workspace_process_list"))
        assertTrue(CapabilityCatalog.isAvailableFromSystemAssistant("ui_wait_for_node"))
    }

    @Test
    fun `system assistant file surface is read only`() {
        val fileMutations = setOf(
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
        )

        fileMutations.forEach { toolName ->
            assertEquals(
                "$toolName must not mutate files from the system-assistant overlay",
                ToolInvocationSurface.FileMutation,
                CapabilityCatalog.toolInvocationSurface(toolName),
            )
            assertTrue(!CapabilityCatalog.isAvailableFromSystemAssistant(toolName))
        }

        setOf("list_files", "read_file", "file_info", "find_files", "list_zip_contents")
            .forEach { toolName ->
                assertEquals(
                    ToolInvocationSurface.Background,
                    CapabilityCatalog.toolInvocationSurface(toolName),
                )
                assertTrue(CapabilityCatalog.isAvailableFromSystemAssistant(toolName))
            }
    }

    @Test
    fun `system assistant cannot schedule work or rewrite broad system policy`() {
        val deferredTools = setOf(
            "schedule_job",
            "resume_job",
            "trigger_job_now",
            "workflow_create",
            "workflow_update",
            "workflow_set_enabled",
            "workflow_run",
            "subagent_dispatch",
        )
        deferredTools.forEach { toolName ->
            assertEquals(
                ToolInvocationSurface.DeferredExecution,
                CapabilityCatalog.toolInvocationSurface(toolName),
            )
            assertTrue(!CapabilityCatalog.isAvailableFromSystemAssistant(toolName))
        }

        val systemMutations = setOf(
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
        systemMutations.forEach { toolName ->
            assertEquals(
                ToolInvocationSurface.SystemMutation,
                CapabilityCatalog.toolInvocationSurface(toolName),
            )
            assertTrue(!CapabilityCatalog.isAvailableFromSystemAssistant(toolName))
        }

        setOf("privileged_package_inspect", "privileged_process_list", "force_stop_app")
            .forEach { toolName ->
                assertEquals(
                    ToolInvocationSurface.Background,
                    CapabilityCatalog.toolInvocationSurface(toolName),
                )
                assertTrue(CapabilityCatalog.isAvailableFromSystemAssistant(toolName))
        }
    }

    @Test
    fun `system assistant fails closed for egress deferred and configuration tools`() {
        val forbidden = setOf(
            "media_copy",
            "media_move",
            "ssh_upload",
            "ssh_download",
            "export_conversation",
            "telegram_send_message",
            "telegram_send_photo",
            "telegram_send_document",
            "skill_install_from_url",
            "skill_install_from_text",
            "send_sms",
            "alarm_create",
            "alarm_delete",
            "delete_job",
            "pause_job",
            "workflow_delete",
            "subagent_cancel",
            "workspace_process_stop",
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
            "notification_reply",
            "dismiss_notification",
        )

        forbidden.forEach { toolName ->
            assertTrue(
                "$toolName must not default to the background assistant surface",
                CapabilityCatalog.toolInvocationSurface(toolName) != ToolInvocationSurface.Background,
            )
            assertTrue(!CapabilityCatalog.isAvailableFromSystemAssistant(toolName))
        }
    }

    @Test
    fun `system assistant keeps explicit safe reads without permission UI`() {
        setOf("get_time_info", "calendar_query").forEach { toolName ->
            assertEquals(
                ToolInvocationSurface.Background,
                CapabilityCatalog.toolInvocationSurface(toolName),
            )
            assertTrue(CapabilityCatalog.isAvailableFromSystemAssistant(toolName))
        }

        setOf("calendar_create", "calendar_update", "calendar_delete").forEach { toolName ->
            assertEquals(
                ToolInvocationSurface.DataMutation,
                CapabilityCatalog.toolInvocationSurface(toolName),
            )
            assertTrue(!CapabilityCatalog.isAvailableFromSystemAssistant(toolName))
        }

        assertEquals(
            ToolInvocationSurface.Activity,
            CapabilityCatalog.toolInvocationSurface("get_screen_time"),
        )
        assertTrue(!CapabilityCatalog.isAvailableFromSystemAssistant("get_screen_time"))
    }

    @Test
    fun `tool lookup does not infer capabilities from identifier substrings`() {
        assertEquals(null, CapabilityCatalog.byToolName("nearby_devices"))
        assertEquals(null, CapabilityCatalog.byToolName("app_launcher"))
        assertEquals(null, CapabilityCatalog.toolInvocationSurface("uncatalogued_tool"))
        assertTrue(!CapabilityCatalog.isAvailableFromSystemAssistant("uncatalogued_tool"))
    }

    @Test
    fun `every implemented catalog option owns exact surfaced tool names`() {
        CapabilityCatalog.implementedCapabilities()
            .filter { it.localToolOption != null }
            .forEach { capability ->
                assertTrue("${capability.id} has no exact tool names", capability.toolNames.isNotEmpty())
                capability.toolNames.forEach { toolName ->
                    assertEquals(capability.id, CapabilityCatalog.byToolName(toolName)?.id)
                    assertTrue(
                        "$toolName has no invocation-surface classification",
                        CapabilityCatalog.toolInvocationSurface(toolName) != null,
                    )
                }
            }
    }

    @Test
    fun `every privileged local option has a catalog descriptor`() {
        LocalToolOption.PRIVILEGED_IMPLEMENTED.forEach { option ->
            assertTrue(
                "$option has no capability descriptor",
                CapabilityCatalog.byLocalToolOption(option) != null,
            )
        }
    }

    @Test
    fun `every exact catalog tool is deliberately classified`() {
        val unclassified = CapabilityCatalog.allCapabilities()
            .flatMap { it.toolNames }
            .filter {
                CapabilityCatalog.toolInvocationSurface(it) == ToolInvocationSurface.Unclassified
            }
            .sorted()

        assertTrue("Unclassified system-assistant tools: $unclassified", unclassified.isEmpty())
    }

    /**
     * The Second-User conversation reader's search tool is **withheld on purpose**.
     *
     * It is named here so the reason is recorded against the tool rather than inferred from its
     * absence from a failure message. It was `Unclassified` only because a rename that broke a name
     * collision with the ordinary `conversation_search` did not carry the new name into any
     * classification — its two siblings reuse their ordinary names and were covered by accident.
     *
     * It is genuinely read-only, and that is deliberately **not** a reason to allow it. It reads
     * conversation content, so exposing it to the system-assistant overlay or to any new consumer
     * needs its own privacy decision. This assertion is therefore a statement about intent, not a
     * bug fixed to make the test above pass: the tool stays exactly as reachable as it was.
     */
    @Test
    fun `the transient conversation search is withheld rather than unclassified`() {
        val toolName = me.rerere.rikkahub.data.ai.tools.TRANSIENT_CONVERSATION_SEARCH_TOOL_NAME

        assertEquals(
            "a decision, not an omission",
            ToolInvocationSurface.Phase1Unavailable,
            CapabilityCatalog.toolInvocationSurface(toolName),
        )
        assertTrue(
            "and it is still declared by the capability that owns it",
            CapabilityCatalog.byToolName(toolName) != null,
        )
    }
}
