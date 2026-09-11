package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

const val TRANSIENT_CONVERSATION_LIST_TOOL_NAME = "conversation_list_recent"
const val TRANSIENT_CONVERSATION_READ_TOOL_NAME = "conversation_read_recent"
const val TRANSIENT_CONVERSATION_SEARCH_TOOL_NAME = "transient_conversation_search"

/**
 * The Second-User transient cross-conversation reader surface.
 *
 * Every name here MUST be unique across the entire tool surface. A persisted tool result carries
 * no identity beyond its name, so a name shared with an ordinary tool makes the two
 * indistinguishable to [me.rerere.rikkahub.data.ai.sanitizeTransientConversationToolResults] —
 * the ordinary tool's real results would be replaced by the redacted envelope.
 *
 * `conversation_search` was exactly such a collision (the ordinary assistant's global search used
 * the same literal), so the reader's variant carries a `transient_` prefix. Keep this invariant
 * guarded by `TransientConversationToolIdentityTest`.
 */
val TRANSIENT_CONVERSATION_READER_TOOL_NAMES = setOf(
    TRANSIENT_CONVERSATION_LIST_TOOL_NAME,
    TRANSIENT_CONVERSATION_READ_TOOL_NAME,
    TRANSIENT_CONVERSATION_SEARCH_TOOL_NAME,
)

/**
 * Single source of truth for "is the transient reader surface active for this session".
 *
 * The tool-surface builder ([me.rerere.rikkahub.service.ChatService]) and the persistence
 * sanitizer must BOTH derive the answer from this function, so the two paths cannot drift. The
 * sanitizer therefore only ever redacts results that genuinely came from the transient reader
 * surface — never an ordinary assistant's same-shaped result.
 *
 * Returns an empty set unless the session is a privileged second-user session that has
 * explicitly enabled history read, i.e. it fails closed.
 */
fun transientReaderToolNamesFor(
    privileged: Boolean,
    historyReadEnabled: Boolean,
): Set<String> =
    if (privileged && historyReadEnabled) TRANSIENT_CONVERSATION_READER_TOOL_NAMES else emptySet()

fun createSecondUserConversationReaderTools(
    reader: ConversationLibraryReader,
    invocationContext: ToolInvocationContext,
    commandId: Uuid?,
    historyReadEnabled: Boolean,
    deviceUnlocked: () -> Boolean,
): List<Tool> {
    val budget = commandId?.let(::ConversationReadBudget)

    fun deny(operation: ConversationReadOperation): List<UIMessagePart>? {
        val privilege = invocationContext.privilege
            ?: return conversationReaderResult(false, "PRIVILEGED_SESSION_REQUIRED", "Second-user session required.")
        val resolvedCommandId = commandId
            ?: return conversationReaderResult(false, "COMMAND_ID_REQUIRED", "A live commandId is required.")
        val callerAssistant = invocationContext.callerAssistantId
        val callerConversation = invocationContext.callerConversationId
        if (callerAssistant != privilege.assistantId.toString() ||
            callerConversation != privilege.conversationId.toString()
        ) {
            return conversationReaderResult(false, "SESSION_IDENTITY_MISMATCH", "Second-user identity mismatch.")
        }
        val decision = SecondUserConversationAccessPolicy.evaluate(
            ConversationReadAccessRequest(
                assistantId = privilege.assistantId,
                privilegedConversationId = privilege.conversationId,
                commandId = resolvedCommandId,
                origin = invocationContext.callOrigin ?: privilege.origin,
                selectedPrivilegedConversation = privilege.isPrivileged && privilege.expandLocalTools,
                historyReadEnabled = historyReadEnabled,
                deviceUnlocked = deviceUnlocked(),
                operation = operation,
            )
        )
        if (decision is ConversationReadAccessDecision.Denied) {
            return conversationReaderResult(false, decision.code, decision.message)
        }
        if (budget?.consume(operation) != true) {
            val max = when (operation) {
                ConversationReadOperation.READ -> MAX_CONVERSATION_READ_CALLS_PER_COMMAND
                ConversationReadOperation.SEARCH -> MAX_CONVERSATION_SEARCH_CALLS_PER_COMMAND
                ConversationReadOperation.LIST -> 0
            }
            return conversationReaderResult(false, "COMMAND_READ_BUDGET_EXHAUSTED", "This task has reached its $max-call limit.")
        }
        return null
    }

    return listOf(
        Tool(
            name = TRANSIENT_CONVERSATION_LIST_TOOL_NAME,
            description = "Read-only: list recent local conversation names and IDs. Results are available only to this task and are not retained as history.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("limit", buildJsonObject { put("type", "integer") })
                    }
                )
            },
            execute = { input ->
                deny(ConversationReadOperation.LIST)?.let { return@Tool it }
                val privilege = checkNotNull(invocationContext.privilege)
                val limit = (input.jsonObject["limit"]?.jsonPrimitive?.intOrNull ?: 20)
                    .coerceIn(1, MAX_CONVERSATIONS_PER_LIST)
                runCatching { reader.listRecent(privilege.conversationId, limit) }
                    .fold(
                        onSuccess = { summaries ->
                            conversationReaderResult(
                                ok = true,
                                code = "OK",
                                message = "Listed ${summaries.size} recent conversations.",
                                metadata = buildJsonObject {
                                    put("operation", "list")
                                    put("count", summaries.size)
                                },
                                data = buildJsonArray {
                                    summaries.forEach { summary ->
                                        add(buildJsonObject {
                                            put("conversation_id", summary.conversationId.toString())
                                            put("assistant_id", summary.assistantId.toString())
                                            put("title", summary.title.ifBlank { "Untitled" })
                                            put("created_at", summary.createAt.toString())
                                            put("updated_at", summary.updateAt.toString())
                                            put("is_pinned", summary.isPinned)
                                        })
                                    }
                                },
                            )
                        },
                        onFailure = ::conversationReaderFailure,
                    )
            },
        ),
        Tool(
            name = TRANSIENT_CONVERSATION_READ_TOOL_NAME,
            description = "Read-only: read up to 50 selected, visible user/assistant messages from one conversation. Use before_node_index to page backwards. Raw text exists only for this task.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("conversation_id", buildJsonObject { put("type", "string") })
                        put("limit", buildJsonObject { put("type", "integer") })
                        put("before_node_index", buildJsonObject { put("type", "integer") })
                    },
                    required = listOf("conversation_id"),
                )
            },
            execute = { input ->
                deny(ConversationReadOperation.READ)?.let { return@Tool it }
                val args = input.jsonObject
                val conversationId = args.uuid("conversation_id")
                    ?: return@Tool conversationReaderResult(false, "INVALID_CONVERSATION_ID", "conversation_id must be a UUID.")
                val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: MAX_MESSAGES_PER_READ)
                    .coerceIn(1, MAX_MESSAGES_PER_READ)
                val before = args["before_node_index"]?.jsonPrimitive?.intOrNull
                runCatching { reader.readRecent(conversationId, limit, before) }
                    .fold(
                        onSuccess = { window ->
                            conversationReaderResult(
                                ok = true,
                                code = "OK",
                                message = "Temporarily read ${window.messages.size} messages; raw content was not saved.",
                                metadata = buildJsonObject {
                                    put("operation", "read")
                                    put("source_conversation_id", window.conversationId.toString())
                                    put("source_title", window.title.ifBlank { "Untitled" })
                                    put("count", window.messages.size)
                                    put("character_count", window.totalCharacters)
                                    put("truncated", window.truncated)
                                    put("has_more", window.hasMore)
                                    window.nextBeforeNodeIndex?.let { put("next_before_node_index", it) }
                                },
                                data = buildJsonArray {
                                    window.messages.forEach { message ->
                                        add(buildJsonObject {
                                            put("conversation_id", message.conversationId.toString())
                                            put("node_id", message.nodeId.toString())
                                            put("message_id", message.messageId.toString())
                                            put("role", message.role)
                                            put("created_at", message.createdAt)
                                            put("text", message.text)
                                            put("truncated", message.truncated)
                                        })
                                    }
                                },
                            )
                        },
                        onFailure = ::conversationReaderFailure,
                    )
            },
        ),
        Tool(
            name = TRANSIENT_CONVERSATION_SEARCH_TOOL_NAME,
            description = "Read-only: search visible messages inside one explicitly identified conversation. No global search. Raw snippets exist only for this task.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("conversation_id", buildJsonObject { put("type", "string") })
                        put("query", buildJsonObject { put("type", "string") })
                        put("limit", buildJsonObject { put("type", "integer") })
                    },
                    required = listOf("conversation_id", "query"),
                )
            },
            execute = { input ->
                deny(ConversationReadOperation.SEARCH)?.let { return@Tool it }
                val args = input.jsonObject
                val conversationId = args.uuid("conversation_id")
                    ?: return@Tool conversationReaderResult(false, "INVALID_CONVERSATION_ID", "conversation_id must be a UUID.")
                val query = args["query"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: MAX_CONVERSATION_SEARCH_RESULTS)
                    .coerceIn(1, MAX_CONVERSATION_SEARCH_RESULTS)
                runCatching { reader.search(conversationId, query, limit) }
                    .fold(
                        onSuccess = { window ->
                            conversationReaderResult(
                                ok = true,
                                code = "OK",
                                message = "Found ${window.hits.size} temporary matches; snippets were not saved.",
                                metadata = buildJsonObject {
                                    put("operation", "search")
                                    put("source_conversation_id", conversationId.toString())
                                    put("source_title", window.title.ifBlank { "Untitled" })
                                    put("count", window.hits.size)
                                    put("truncated", window.hits.size >= limit)
                                },
                                data = buildJsonArray {
                                    window.hits.forEach { hit ->
                                        add(buildJsonObject {
                                            put("conversation_id", hit.conversationId.toString())
                                            put("node_id", hit.nodeId.toString())
                                            put("message_id", hit.messageId.toString())
                                            put("title", hit.title.ifBlank { "Untitled" })
                                            put("updated_at", hit.updateAt.toString())
                                            put("snippet", hit.snippet)
                                        })
                                    }
                                },
                            )
                        },
                        onFailure = ::conversationReaderFailure,
                    )
            },
        ),
    )
}

private fun JsonObject.uuid(name: String): Uuid? = this[name]?.jsonPrimitive?.contentOrNull
    ?.trim()?.let { runCatching { Uuid.parse(it) }.getOrNull() }

private fun conversationReaderFailure(error: Throwable): List<UIMessagePart> = when (error) {
    is ConversationReaderException -> conversationReaderResult(false, error.code, error.message)
    else -> conversationReaderResult(false, "READ_FAILED", "Conversation history could not be read.")
}

private fun conversationReaderResult(
    ok: Boolean,
    code: String,
    message: String,
    metadata: JsonObject = JsonObject(emptyMap()),
    data: kotlinx.serialization.json.JsonElement? = null,
): List<UIMessagePart> = listOf(
    UIMessagePart.Text(
        buildJsonObject {
            put("ok", ok)
            put("code", code)
            put("message", message)
            put("transient", true)
            put("raw_content_saved", false)
            metadata.forEach { (key, value) -> put(key, value) }
            data?.let { put("data", it) }
        }.toString()
    )
)
