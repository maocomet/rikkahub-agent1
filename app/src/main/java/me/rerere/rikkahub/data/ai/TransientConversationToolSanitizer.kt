package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.utils.JsonInstant

/**
 * Converts raw cross-conversation tool results into a paired audit record before any message
 * snapshot reaches ChatService state, Room, FTS, memory capture, title generation, or UI.
 * GenerationHandler keeps its own unsanitized list for the active provider loop.
 *
 * @param transientToolNames the transient reader surface that is ACTIVE for this session, as
 *   supplied by the caller from trusted runtime state — never derived from the message contents.
 *   Callers must pass the output of
 *   [me.rerere.rikkahub.data.ai.tools.transientReaderToolNamesFor], the same function the tool
 *   surface builder uses, so both paths agree by construction.
 *
 *   The parameter is deliberately REQUIRED with no default: a default would let a new call site
 *   silently either redact everything or redact nothing. An empty set means "no transient reader
 *   surface was active", which is the correct answer for every ordinary assistant.
 */
internal fun List<UIMessage>.sanitizeTransientConversationToolResults(
    transientToolNames: Set<String>,
): List<UIMessage> = map { message ->
    message.copy(parts = message.parts.map { part ->
        if (part is UIMessagePart.Tool && part.toolName == "owner_secret_manage") {
            sanitizeSecretOwnerTool(part)
        } else if (part is UIMessagePart.Tool && part.toolName in REDACTED_OWNER_INPUT_TOOLS) {
            part.copy(input = sanitizeOwnerOperationInput(part.input, OWNER_REDACTED_ARGUMENT_KEYS))
        } else if (part is UIMessagePart.Tool && part.toolName in transientToolNames) {
            part.copy(
                input = sanitizeTransientToolInput(part.input),
                output = if (part.output.isEmpty()) {
                    emptyList()
                } else {
                    listOf(UIMessagePart.Text(redactedConversationToolResult(part)))
                },
            )
        } else {
            part
        }
    })
}

private fun sanitizeOwnerOperationInput(raw: String, redactedKeys: Set<String>): String {
    val parsed = runCatching { JsonInstant.parseToJsonElement(raw).jsonObject }.getOrNull()
        ?: return "{}"
    val actions = parsed["actions"] as? JsonArray ?: JsonArray(emptyList())
    return buildJsonObject {
        parsed["request_id"]?.let { put("request_id", it) }
        put("actions", buildJsonArray {
            actions.forEach { element ->
                val action = element as? JsonObject ?: return@forEach
                addJsonObject {
                    action["type"]?.let { put("type", it) }
                    val arguments = action["arguments"] as? JsonObject ?: JsonObject(emptyMap())
                    put("arguments", buildJsonObject {
                        arguments.forEach { (key, value) ->
                            put(key, if (key in redactedKeys) JsonPrimitive("[OWNER_ARGUMENT_REDACTED]") else value)
                        }
                    })
                }
            }
        })
    }.toString()
}

private fun sanitizeSecretOwnerTool(tool: UIMessagePart.Tool): UIMessagePart.Tool = tool.copy(
    input = sanitizeSecretOwnerInput(tool.input),
    output = tool.output.map { output ->
        if (output is UIMessagePart.Text) {
            output.copy(text = sanitizeSecretOwnerOutput(output.text))
        } else output
    },
)

private fun sanitizeSecretOwnerInput(raw: String): String {
    val parsed = runCatching { JsonInstant.parseToJsonElement(raw).jsonObject }.getOrNull()
        ?: return "{}"
    val actions = parsed["actions"] as? JsonArray ?: JsonArray(emptyList())
    return buildJsonObject {
        parsed["request_id"]?.let { put("request_id", it) }
        put("actions", buildJsonArray {
            actions.forEach { element ->
                val action = element as? JsonObject ?: return@forEach
                val type = action["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
                addJsonObject {
                    put("type", type)
                    val arguments = action["arguments"] as? JsonObject ?: JsonObject(emptyMap())
                    put("arguments", if (type in SENSITIVE_SECRET_ACTIONS) {
                        buildJsonObject {
                            arguments["slot_id"]?.let { put("slot_id", it) }
                            arguments.keys.filterNot { it == "slot_id" }.forEach { key ->
                                put(key, "[SECRET_ARGUMENT_REDACTED]")
                            }
                        }
                    } else arguments)
                }
            }
        })
    }.toString()
}

private fun sanitizeSecretOwnerOutput(raw: String): String {
    val parsed = runCatching { JsonInstant.parseToJsonElement(raw).jsonObject }.getOrNull()
        ?: return buildJsonObject {
            put("ok", false)
            put("code", "SECRET_RESULT_REDACTED")
            put("value", "[SECRET_REVEALED]")
        }.toString()
    return sanitizeSecretJson(parsed).toString()
}

private fun sanitizeSecretJson(value: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonElement =
    when (value) {
        is JsonObject -> buildJsonObject {
            value.forEach { (key, child) ->
                when {
                    key == me.rerere.rikkahub.security.EphemeralToolResultStore.EPHEMERAL_TOKEN_FIELD -> Unit
                    key in setOf("value", "secret", "plaintext") -> put(key, "[SECRET_REVEALED]")
                    key == "base_url" -> put(key, "[PROVIDER_URL_REDACTED]")
                    else -> put(key, sanitizeSecretJson(child))
                }
            }
        }
        is JsonArray -> buildJsonArray { value.forEach { add(sanitizeSecretJson(it)) } }
        is JsonPrimitive -> value
        else -> value
    }

private val SENSITIVE_SECRET_ACTIONS = setOf(
    "secret_provider_credentials_reveal",
    "secret_plaintext_reveal",
    "secret_replace",
    "secret_remove_prefix",
)

private val REDACTED_OWNER_INPUT_TOOLS = setOf(
    "owner_assistant_manage",
    "owner_conversation_manage",
    "owner_provider_manage",
    "owner_tts_manage",
    "owner_service_manage",
    "owner_mcp_manage",
    "owner_skill_manage",
    "owner_workflow_manage",
)

private val OWNER_REDACTED_ARGUMENT_KEYS = setOf(
    "command",
    "executable",
    "arguments",
    "cwd",
    "working_dir",
    "health_url",
    "endpoint",
    "base_url",
    "source",
    "source_url",
    "archive_url",
    "download_url",
    "url",
    "git_url",
    "manifest",
    "headers",
    "tts_headers",
    "body",
    "body_template",
    "tts_body_template",
    "text",
    "test_texts",
    "system_prompt",
    "custom_system_prompt",
    "query",
    "definition",
)

private fun sanitizeTransientToolInput(raw: String): String {
    val parsed = runCatching { JsonInstant.parseToJsonElement(raw).jsonObject }.getOrNull()
        ?: return "{}"
    return buildJsonObject {
        parsed.forEach { (key, value) ->
            if (key == "query") put(key, "[redacted after current task]") else put(key, value)
        }
    }.toString()
}

private fun redactedConversationToolResult(tool: UIMessagePart.Tool): String {
    val raw = tool.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
    val parsed = runCatching { JsonInstant.parseToJsonElement(raw).jsonObject }.getOrNull()
        ?: JsonObject(emptyMap())
    fun string(name: String): String? = parsed[name]?.jsonPrimitive?.contentOrNull
    return buildJsonObject {
        put("ok", string("ok")?.toBooleanStrictOrNull() ?: false)
        put("code", string("code") ?: "REDACTED")
        put("operation", string("operation") ?: tool.toolName)
        string("source_conversation_id")?.let { put("source_conversation_id", it) }
        string("source_title")?.let { put("source_title", it.take(256)) }
        string("count")?.toIntOrNull()?.let { put("count", it) }
        string("character_count")?.toIntOrNull()?.let { put("character_count", it) }
        string("truncated")?.toBooleanStrictOrNull()?.let { put("truncated", it) }
        string("has_more")?.toBooleanStrictOrNull()?.let { put("has_more", it) }
        string("next_before_node_index")?.toIntOrNull()?.let { put("next_before_node_index", it) }
        put("transient", true)
        put("raw_content_saved", false)
        put("message", "Cross-conversation content was available only to the completed task and was not saved.")
    }.toString()
}
