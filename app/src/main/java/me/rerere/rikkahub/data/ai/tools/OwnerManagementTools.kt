package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.InvocationSurfacePolicy
import me.rerere.rikkahub.owner.OwnerAction
import me.rerere.rikkahub.owner.OwnerOperationGateway
import me.rerere.rikkahub.owner.OwnerOperationRequest
import me.rerere.rikkahub.owner.OwnerOperationResult
import me.rerere.rikkahub.owner.OwnerActionRegistry
import me.rerere.rikkahub.owner.OwnerFamilySpec
import me.rerere.rikkahub.owner.OwnerToolFamily

/**
 * Stable, compact host-management schemas. Every call performs 1-20 ordered actions.
 *
 * [enabledFamilies] only narrows the MODEL-FACING schema generation (which owner families the
 * model can see/invoke this turn). `null` keeps every family (legacy behaviour). The runtime
 * OwnerActionRegistry / gateway / handlers / authority are intentionally untouched.
 */
fun createOwnerManagementTools(
    invocationContext: ToolInvocationContext,
    gateway: OwnerOperationGateway,
    enabledFamilies: Set<OwnerToolFamily>? = null,
): List<Tool> = OwnerActionRegistry.families
    .filter { spec -> enabledFamilies == null || spec.family in enabledFamilies }
    .map { spec ->
    Tool(
        name = spec.family.toolName,
        description = spec.description + " Supply one stable request_id and 1-20 ordered actions; " +
            "the host validates, applies, verifies and compensates inside this single call.",
        parameters = { ownerSchema(spec) },
        execute = { input ->
            val parsed = parseOwnerRequest(spec, input as? JsonObject, invocationContext)
            if (parsed is OwnerRequestParse.Error) {
                ownerToolError(parsed.code, parsed.message)
            } else {
                val value = parsed as OwnerRequestParse.Value
                encodeOwnerResult(gateway.execute(value.request, value.context))
            }
        },
    )
}

fun isOwnerToolSurfaceAvailable(context: ToolInvocationContext): Boolean {
    val privilege = context.privilege ?: return false
    return privilege.isPrivileged &&
        privilege.authoritySubjectId != null &&
        privilege.authorityEpoch != null &&
        privilege.origin in InvocationSurfacePolicy.CONFIRMED_LOCAL_SECOND_USER &&
        !context.isHeadless &&
        context.callerAssistantId == privilege.assistantId.toString() &&
        context.callerConversationId == privilege.conversationId.toString()
}

private sealed interface OwnerRequestParse {
    data class Value(
        val request: OwnerOperationRequest,
        val context: me.rerere.rikkahub.privilege.PrivilegedSessionContext,
    ) : OwnerRequestParse
    data class Error(val code: String, val message: String) : OwnerRequestParse
}

private fun parseOwnerRequest(
    spec: OwnerFamilySpec,
    input: JsonObject?,
    invocation: ToolInvocationContext,
): OwnerRequestParse {
    val privilege = invocation.privilege
        ?: return OwnerRequestParse.Error("OWNER_SESSION_REQUIRED", "Owner tools require the active local second-user conversation.")
    if (!isOwnerToolSurfaceAvailable(invocation)) {
        return OwnerRequestParse.Error("OWNER_SESSION_REQUIRED", "The live Owner authority or trusted local surface does not match.")
    }
    val obj = input ?: return OwnerRequestParse.Error("OWNER_INVALID_INPUT", "Expected an object.")
    if ((obj.keys - setOf("request_id", "actions")).isNotEmpty()) {
        return OwnerRequestParse.Error("OWNER_UNSUPPORTED_FIELD", "Only request_id and actions are accepted at the operation envelope.")
    }
    val requestId = obj["request_id"]?.jsonPrimitive?.contentOrNull?.trim()
        ?: return OwnerRequestParse.Error("OWNER_REQUEST_ID_REQUIRED", "request_id is required.")
    val array = obj["actions"] as? JsonArray
        ?: return OwnerRequestParse.Error("OWNER_ACTIONS_REQUIRED", "actions must be an array.")
    if (array.size !in 1..20) {
        return OwnerRequestParse.Error("OWNER_ACTION_COUNT_INVALID", "actions must contain 1-20 items.")
    }
    val actions = ArrayList<OwnerAction>(array.size)
    for ((index, element) in array.withIndex()) {
        val action = element as? JsonObject
            ?: return OwnerRequestParse.Error("OWNER_ACTION_INVALID", "action $index must be an object.")
        if ((action.keys - setOf("type", "arguments")).isNotEmpty()) {
            return OwnerRequestParse.Error("OWNER_ACTION_INVALID", "action $index accepts only type and arguments.")
        }
        val type = action["type"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return OwnerRequestParse.Error("OWNER_ACTION_TYPE_REQUIRED", "action $index requires type.")
        val risk = spec.operations[type]
            ?: return OwnerRequestParse.Error("OWNER_ACTION_UNSUPPORTED", "action $index is not supported by ${spec.family.toolName}.")
        val arguments = action["arguments"] as? JsonObject ?: JsonObject(emptyMap())
        if (spec.family == OwnerToolFamily.SERVICE &&
            type in setOf("service_register", "emotion_tts_setup")
        ) {
            val command = arguments["command"]?.jsonPrimitive?.contentOrNull
            val blocked = HardlineCommandGuard.checkCommand(command)
                ?: HardlineCommandGuard.checkTool("termux_run_command", arguments.toString())
            if (blocked != null) {
                return OwnerRequestParse.Error(
                    "HARDLINE_BLOCKED",
                    "The service command is permanently blocked by HARDLINE: $blocked",
                )
            }
        }
        actions += OwnerAction(type = type, arguments = arguments, risk = risk)
    }
    return OwnerRequestParse.Value(
        request = OwnerOperationRequest(
            requestId = requestId,
            family = spec.family,
            actions = actions,
            authoritySubjectId = requireNotNull(privilege.authoritySubjectId),
            authorityEpoch = requireNotNull(privilege.authorityEpoch),
            assistantId = privilege.assistantId.toString(),
            conversationId = privilege.conversationId.toString(),
            modelId = invocation.callerModelId,
            providerId = invocation.callerProviderId,
            availableToolNames = invocation.toolNameSurface.snapshot().available,
            availableTools = invocation.toolExecutionSurface.snapshot(),
        ),
        context = privilege,
    )
}

private fun ownerSchema(spec: OwnerFamilySpec): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        put("request_id", buildJsonObject {
            put("type", "string")
            put("description", "Stable idempotency ID; reuse only when retrying the exact same operation")
        })
        put("actions", buildJsonObject {
            put("type", "array")
            put("minItems", 1)
            put("maxItems", 20)
            put("items", buildJsonObject {
                put("type", "object")
                put("additionalProperties", false)
                put("properties", buildJsonObject {
                    put("type", buildJsonObject {
                        put("type", "string")
                        put("enum", JsonArray(spec.operations.keys.map(::JsonPrimitive)))
                    })
                    put("arguments", buildJsonObject {
                        put("type", "object")
                        put(
                            "description",
                            "Typed fields by action: " + spec.actions.joinToString("; ") { action ->
                                "${action.type}(${action.argumentGuide})"
                            } + ". Fields marked *_id accept a caller-chosen stable UUID when the create action supports it; " +
                                "reuse that UUID in later actions from the same call. Never place a raw secret here.",
                        )
                    })
                })
                put("required", buildJsonArray {
                    add(JsonPrimitive("type"))
                    add(JsonPrimitive("arguments"))
                })
            })
        })
    },
    required = listOf("request_id", "actions"),
)

internal fun ownerToolSchemaUtf8Bytes(): Map<OwnerToolFamily, Int> =
    OwnerActionRegistry.families.associate { spec ->
        val modelFacing = spec.description + ownerSchema(spec).toString()
        spec.family to modelFacing.toByteArray(Charsets.UTF_8).size
    }

private fun encodeOwnerResult(result: OwnerOperationResult): List<UIMessagePart> = listOf(
    UIMessagePart.Text(buildJsonObject {
        put("ok", result.ok)
        put("request_id", result.requestId)
        put("state", result.state.name)
        put("code", result.code)
        put("message", result.message.take(500))
        put("replayed", result.replayed)
        put("actions", buildJsonArray {
            result.actions.forEach { action ->
                addJsonObject {
                    put("index", action.index)
                    put("type", action.type)
                    put("ok", action.ok)
                    put("code", action.code)
                    put("message", action.message.take(500))
                    action.data?.let { put("data", it) }
                }
            }
        })
    }.toString()),
)

private fun ownerToolError(code: String, message: String): List<UIMessagePart> = listOf(
    UIMessagePart.Text(buildJsonObject {
        put("ok", false)
        put("code", code)
        put("message", message.take(500))
    }.toString()),
)

internal fun ownerActionGuideCoverageGaps(): Set<String> = OwnerActionRegistry.families
    .flatMapTo(linkedSetOf()) { family ->
        family.actions.filter { it.argumentGuide.isBlank() && it.type !in OWNER_NO_ARGUMENT_ACTIONS }
            .map { it.type }
    }

private val OWNER_NO_ARGUMENT_ACTIONS = setOf(
    "provider_list",
    "secret_vault_list",
    "secret_session_status",
    "tts_list",
    "tts_get_playback_speed",
    "tts_stop",
    "service_list",
    "mcp_list",
    "skill_list",
    "workflow_list",
    "schedule_list",
    "alarm_list",
    "rikkahub_state_get",
    "doctor_check",
    "quick_capture_get",
    "quick_capture_trigger",
    "plugin_list",
    "prompt_library_list",
    "lorebook_list",
    "asr_list",
    "channel_get",
    "search_get",
    "backup_storage_get",
    "backup_local_export",
    "app_settings_get",
    "reverse_geocoder_get",
    "runtime_get",
    "safety_get",
    "safety_emergency_stop_activate",
    "pet_list",
    "pet_dialogue_state",
)

/* Registry moved to OwnerActionRegistry. Kept in this migration commit for blame continuity.
private val OWNER_ACTION_ARGUMENT_GUIDE = mapOf(
    "assistant_create" to "assistant_id?, name?, system_prompt?, model_id?",
    "assistant_clone" to "source_assistant_id, assistant_id?, name?",
    "assistant_update" to "assistant_id, name?, system_prompt?, chat_model_id?, workspace_id?, enable_memory?, use_global_memory?, enable_recent_chats_reference?, stream_output?, fast_path_router_enabled?, enable_web_search?, generation_max_steps? (0=auto, 1..256), generation_turn_budget_minutes? (0=auto, 1..60)",
    "assistant_delete" to "assistant_id",
    "assistant_set_default" to "assistant_id",
    "assistant_toggle_tool" to "assistant_id, tool_type, enabled",
    "assistant_update_skills" to "assistant_id, operation, skill_names",
    "assistant_update_mcp_servers" to "assistant_id, operation, server_ids",
    "assistant_switch_model" to "assistant_id, model_id",
    "assistant_switch_tts" to "tts_provider_id",
    "conversation_create" to "conversation_id?, assistant_id, title?",
    "conversation_branch" to "conversation_id, new_conversation_id?, title?",
    "conversation_archive" to "conversation_id",
    "conversation_restore" to "conversation_id",
    "conversation_update" to "conversation_id, title?, pinned?, custom_system_prompt?",
    "conversation_search" to "query?, limit?",
    "conversation_export" to "conversation_id",
    "conversation_open" to "conversation_id",
    "conversation_delete" to "conversation_id",
    "provider_list" to "",
    "provider_create" to "provider_id?, provider_type, name?, base_url?, vault_slot_id?",
    "provider_update" to "provider_id, name?, base_url?, enabled?",
    "provider_delete" to "provider_id",
    "provider_refresh_models" to "provider_id",
    "provider_test" to "provider_id",
    "provider_set_default" to "model_id, assistant_id?",
    "secret_vault_list" to "",
    "secret_vault_create_slot" to "slot_id, label, purpose",
    "secret_vault_set_binding" to "slot_id, kind, target_id, enabled",
    "secret_vault_test_binding" to "slot_id, kind, target_id",
    "secret_session_status" to "",
    "secret_provider_credentials_reveal" to "provider_ids? (omit for all Providers; max 32)",
    "secret_plaintext_reveal" to "slot_id",
    "secret_replace" to "slot_id, find, replacement",
    "secret_trim" to "slot_id",
    "secret_remove_prefix" to "slot_id, prefix",
    "secret_remove_quotes" to "slot_id",
    "secret_remove_newlines" to "slot_id",
    "tts_list" to "",
    "tts_create_generic_http" to "tts_provider_id?, name?, endpoint, method?, body_encoding?, body_template?, headers?, response_mode?, response_json_path?, audio_format?, voice?, language?, allow_private_network?, max_response_bytes?, vault_slot_id?",
    "tts_update" to "tts_provider_id, same optional Generic HTTP fields as create",
    "tts_delete" to "tts_provider_id",
    "tts_test" to "tts_provider_id, text?",
    "tts_play" to "artifact_id? or text?",
    "tts_set_default" to "tts_provider_id",
    "service_list" to "",
    "service_register" to "service_id?, runtime, workspace_id?, name?, command? or executable+arguments, cwd?, keep_awake?, restart_policy?, health_url?",
    "service_start" to "service_id",
    "service_stop" to "service_id, force?",
    "service_restart" to "service_id",
    "service_status" to "service_id",
    "service_delete" to "service_id",
    "emotion_tts_setup" to "service fields plus tts_endpoint, tts_name?, tts_method?, tts_body_encoding?, tts_body_template?, tts_headers?, tts_response_mode?, tts_response_json_path?, tts_audio_format?, tts_voice?, tts_language?, vault_slot_id?, test_texts?, set_default?",
    "mcp_list" to "",
    "mcp_discover" to "source_url, pin",
    "mcp_install" to "mcp_id?, name, transport, url, pin, source_url? (required for content hash), enabled?, headers?, wait_seconds?",
    "mcp_update" to "mcp_id, name, transport, url, pin, source_url? (required for content hash), enabled?, headers?, wait_seconds?",
    "mcp_delete" to "mcp_id",
    "mcp_bind" to "mcp_id, assistant_id",
    "mcp_unbind" to "mcp_id, assistant_id",
    "mcp_test" to "mcp_id, wait_seconds?",
    "skill_list" to "",
    "skill_install" to "skill_name?, one of source_url/git_url/archive_url, pin",
    "skill_update" to "skill_name, one of source_url/git_url/archive_url, pin",
    "skill_uninstall" to "skill_name",
    "skill_bind" to "skill_name, assistant_id",
    "skill_unbind" to "skill_name, assistant_id",
    "skill_test" to "skill_name",
    "workflow_list" to "",
    "workflow_create" to "definition",
    "workflow_update" to "definition",
    "workflow_delete" to "workflow_id",
    "workflow_set_enabled" to "workflow_id, enabled",
    "workflow_run" to "workflow_id",
    "ui_navigate" to "screen",
    "ui_open_conversation" to "conversation_id",
    "ui_open_provider" to "provider_id",
    "ui_open_tts" to "tts_provider_id",
    "ui_open_settings" to "screen",
    "rikkahub_state_get" to "",
    "doctor_check" to "",
    "doctor_repair" to "repair",
    "doctor_recover_operation" to "request_id",
)

private val OWNER_TOOL_SPECS = listOf(
    OwnerToolSpec(
        OwnerToolFamily.ASSISTANT,
        "Create, clone, update, delete or select ordinary assistants and update this Owner's model/TTS bindings. Authority fields are permanently excluded.",
        ops(
            "assistant_create" read OwnerOperationRisk.REVERSIBLE_WRITE,
            "assistant_clone" read OwnerOperationRisk.REVERSIBLE_WRITE,
            "assistant_update" read OwnerOperationRisk.REVERSIBLE_WRITE,
            "assistant_delete" read OwnerOperationRisk.IRREVERSIBLE,
            "assistant_set_default" read OwnerOperationRisk.REVERSIBLE_WRITE,
            "assistant_toggle_tool" read OwnerOperationRisk.REVERSIBLE_WRITE,
            "assistant_update_skills" read OwnerOperationRisk.REVERSIBLE_WRITE,
            "assistant_update_mcp_servers" read OwnerOperationRisk.REVERSIBLE_WRITE,
            "assistant_switch_model" read OwnerOperationRisk.REVERSIBLE_WRITE,
            "assistant_switch_tts" read OwnerOperationRisk.REVERSIBLE_WRITE,
        ),
    ),
    OwnerToolSpec(
        OwnerToolFamily.CONVERSATION,
        "Create, branch, archive, restore, rename, search, export, open or delete ordinary conversations. The protected Owner conversation remains immutable to deletion.",
        ops(
            "conversation_create" read OwnerOperationRisk.REVERSIBLE_WRITE,
            "conversation_branch" read OwnerOperationRisk.REVERSIBLE_WRITE,
            "conversation_archive" read OwnerOperationRisk.REVERSIBLE_WRITE,
            "conversation_restore" read OwnerOperationRisk.REVERSIBLE_WRITE,
            "conversation_update" read OwnerOperationRisk.REVERSIBLE_WRITE,
            "conversation_search" read OwnerOperationRisk.READ_ONLY,
            "conversation_export" read OwnerOperationRisk.EXTERNAL_SIDE_EFFECT,
            "conversation_open" read OwnerOperationRisk.REVERSIBLE_WRITE,
            "conversation_delete" read OwnerOperationRisk.IRREVERSIBLE,
        ),
    ),
    OwnerToolSpec(
        OwnerToolFamily.PROVIDER,
        "Manage OpenAI-compatible, Google and Claude Provider records, models, Vault references, tests and active selection.",
        ops(
            "provider_list" read OwnerOperationRisk.READ_ONLY,
            "provider_create" read OwnerOperationRisk.REVERSIBLE_WRITE,
            "provider_update" read OwnerOperationRisk.REVERSIBLE_WRITE,
            "provider_delete" read OwnerOperationRisk.IRREVERSIBLE,
            "provider_refresh_models" read OwnerOperationRisk.EXTERNAL_SIDE_EFFECT,
            "provider_test" read OwnerOperationRisk.EXTERNAL_SIDE_EFFECT,
            "provider_set_default" read OwnerOperationRisk.REVERSIBLE_WRITE,
        ),
    ),
    OwnerToolSpec(
        OwnerToolFamily.SECRET,
        "Manage Vault metadata/bindings and a user-enabled remote plaintext session. Secret values are accepted only by explicitly sensitive actions and are never persisted.",
        ops(
            "secret_vault_list" read OwnerOperationRisk.READ_ONLY,
            "secret_vault_create_slot" read OwnerOperationRisk.REVERSIBLE_WRITE,
            "secret_vault_set_binding" read OwnerOperationRisk.REVERSIBLE_WRITE,
            "secret_vault_test_binding" read OwnerOperationRisk.READ_ONLY,
            "secret_session_status" read OwnerOperationRisk.READ_ONLY,
            "secret_provider_credentials_reveal" read OwnerOperationRisk.EXTERNAL_SIDE_EFFECT,
            "secret_plaintext_reveal" read OwnerOperationRisk.EXTERNAL_SIDE_EFFECT,
            "secret_replace" read OwnerOperationRisk.REVERSIBLE_WRITE,
            "secret_trim" read OwnerOperationRisk.REVERSIBLE_WRITE,
            "secret_remove_prefix" read OwnerOperationRisk.REVERSIBLE_WRITE,
            "secret_remove_quotes" read OwnerOperationRisk.REVERSIBLE_WRITE,
            "secret_remove_newlines" read OwnerOperationRisk.REVERSIBLE_WRITE,
        ),
    ),
    OwnerToolSpec(
        OwnerToolFamily.TTS,
        "Manage all TTS types including Generic HTTP, test synthesis/playback, cache artifacts and default selection.",
        ops(
            "tts_list" read OwnerOperationRisk.READ_ONLY,
            "tts_create_generic_http" read OwnerOperationRisk.REVERSIBLE_WRITE,
            "tts_update" read OwnerOperationRisk.REVERSIBLE_WRITE,
            "tts_delete" read OwnerOperationRisk.IRREVERSIBLE,
            "tts_test" read OwnerOperationRisk.EXTERNAL_SIDE_EFFECT,
            "tts_play" read OwnerOperationRisk.EXTERNAL_SIDE_EFFECT,
            "tts_set_default" read OwnerOperationRisk.REVERSIBLE_WRITE,
        ),
    ),
    OwnerToolSpec(
        OwnerToolFamily.SERVICE,
        "Register and supervise Workspace/Termux local services using the execution ledger, health probes and restart backoff.",
        ops(
            "service_list" read OwnerOperationRisk.READ_ONLY,
            "service_register" read OwnerOperationRisk.EXTERNAL_SIDE_EFFECT,
            "service_start" read OwnerOperationRisk.EXTERNAL_SIDE_EFFECT,
            "service_stop" read OwnerOperationRisk.EXTERNAL_SIDE_EFFECT,
            "service_restart" read OwnerOperationRisk.EXTERNAL_SIDE_EFFECT,
            "service_status" read OwnerOperationRisk.READ_ONLY,
            "service_delete" read OwnerOperationRisk.IRREVERSIBLE,
            "emotion_tts_setup" read OwnerOperationRisk.EXTERNAL_SIDE_EFFECT,
        ),
    ),
    OwnerToolSpec(
        OwnerToolFamily.MCP,
        "Discover, install at a pinned version/hash, test, update, bind or remove MCP servers through existing MCP storage.",
        ops(
            "mcp_list" read OwnerOperationRisk.READ_ONLY,
            "mcp_discover" read OwnerOperationRisk.EXTERNAL_SIDE_EFFECT,
            "mcp_install" read OwnerOperationRisk.EXTERNAL_SIDE_EFFECT,
            "mcp_update" read OwnerOperationRisk.EXTERNAL_SIDE_EFFECT,
            "mcp_delete" read OwnerOperationRisk.IRREVERSIBLE,
            "mcp_bind" read OwnerOperationRisk.REVERSIBLE_WRITE,
            "mcp_unbind" read OwnerOperationRisk.REVERSIBLE_WRITE,
            "mcp_test" read OwnerOperationRisk.EXTERNAL_SIDE_EFFECT,
        ),
    ),
    OwnerToolSpec(
        OwnerToolFamily.SKILL,
        "Install from pinned HTTPS/Git content, update, test, bind, unbind or uninstall Skills with rollback.",
        ops(
            "skill_list" read OwnerOperationRisk.READ_ONLY,
            "skill_install" read OwnerOperationRisk.EXTERNAL_SIDE_EFFECT,
            "skill_update" read OwnerOperationRisk.EXTERNAL_SIDE_EFFECT,
            "skill_uninstall" read OwnerOperationRisk.IRREVERSIBLE,
            "skill_bind" read OwnerOperationRisk.REVERSIBLE_WRITE,
            "skill_unbind" read OwnerOperationRisk.REVERSIBLE_WRITE,
            "skill_test" read OwnerOperationRisk.EXTERNAL_SIDE_EFFECT,
        ),
    ),
    OwnerToolSpec(
        OwnerToolFamily.WORKFLOW,
        "Create, update, enable, run or delete Workflows. Interactive runs use Owner authority; automated runs use a frozen capability snapshot.",
        ops(
            "workflow_list" read OwnerOperationRisk.READ_ONLY,
            "workflow_create" read OwnerOperationRisk.REVERSIBLE_WRITE,
            "workflow_update" read OwnerOperationRisk.REVERSIBLE_WRITE,
            "workflow_delete" read OwnerOperationRisk.IRREVERSIBLE,
            "workflow_set_enabled" read OwnerOperationRisk.REVERSIBLE_WRITE,
            "workflow_run" read OwnerOperationRisk.EXTERNAL_SIDE_EFFECT,
        ),
    ),
    OwnerToolSpec(
        OwnerToolFamily.UI,
        "Navigate RikkaHub to typed pages or conversations without raw Intent or private-Activity access.",
        ops(
            "ui_navigate" read OwnerOperationRisk.REVERSIBLE_WRITE,
            "ui_open_conversation" read OwnerOperationRisk.REVERSIBLE_WRITE,
            "ui_open_provider" read OwnerOperationRisk.REVERSIBLE_WRITE,
            "ui_open_tts" read OwnerOperationRisk.REVERSIBLE_WRITE,
            "ui_open_settings" read OwnerOperationRisk.REVERSIBLE_WRITE,
        ),
    ),
    OwnerToolSpec(
        OwnerToolFamily.DOCTOR,
        "Read redacted Owner runtime diagnostics and run only safe repair/reconcile operations.",
        ops(
            "rikkahub_state_get" read OwnerOperationRisk.READ_ONLY,
            "doctor_check" read OwnerOperationRisk.READ_ONLY,
            "doctor_repair" read OwnerOperationRisk.REVERSIBLE_WRITE,
            "doctor_recover_operation" read OwnerOperationRisk.REVERSIBLE_WRITE,
        ),
    ),
)
*/
