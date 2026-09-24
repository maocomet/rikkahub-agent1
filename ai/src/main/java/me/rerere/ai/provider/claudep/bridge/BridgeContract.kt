package me.rerere.ai.provider.claudep.bridge

/**
 * The frozen vocabulary of the M2 bridge contract.
 *
 * This is the Kotlin half of `rikkahub-claude-p-server`'s `src/bridge/contract.ts`. Every
 * string here is a value the Server compares for equality, so a spelling change on either
 * side is a protocol change and not a rename.
 *
 * `BRIDGE_ABI` is checked by exact equality in both directions. There is deliberately no
 * "compatible major version" rule: a catalog frozen under a different ABI was frozen under
 * different rules, and the digest beside it is not a digest this build knows how to read.
 */
object BridgeContract {

    /** The bridge's own ABI. Bumped whenever the contract's meaning changes. */
    const val BRIDGE_ABI = "rikkahub-bridge/1"

    /** The single MCP server name. There is exactly one, and there is no second namespace. */
    const val BRIDGE_SERVER_NAME = "rikkahub_bridge"

    /** The only tool-name prefix Claude may see, as Claude Code namespaces MCP tools. */
    const val BRIDGE_TOOL_PREFIX = "mcp__${BRIDGE_SERVER_NAME}__"

    /** The `server.hello.features` entry that announces the bridge. */
    const val FEATURE_TOOL_BRIDGE = "tool.bridge.v1"

    /** The name Claude sees for a frozen tool name. */
    fun bridgedToolName(name: String): String = BRIDGE_TOOL_PREFIX + name

    /** The frozen name inside a bridged name, or `null` when the namespace does not match. */
    fun frozenNameOf(bridged: String): String? =
        if (bridged.startsWith(BRIDGE_TOOL_PREFIX)) {
            bridged.removePrefix(BRIDGE_TOOL_PREFIX)
        } else {
            null
        }
}

/**
 * Bounds. Every one of these is enforced before a value is accepted, on both ends.
 *
 * The numbers are byte limits, not character limits. A tool description in Chinese is three
 * UTF-8 bytes per character, so a limit that counted characters would admit a payload three
 * times the size the Server will accept — and the failure would surface as a refused
 * `generation.start` rather than as anything that named the cause.
 */
object BridgeLimits {
    /** Tools in one frozen catalog. */
    const val MAX_TOOLS = 64

    /** Bytes of a normalized frozen tool name (the part after the prefix). */
    const val MAX_TOOL_NAME_BYTES = 64

    /** Bytes of the stable display/raw identifier. */
    const val MAX_DISPLAY_NAME_BYTES = 128

    /** Bytes of a tool description. */
    const val MAX_DESCRIPTION_BYTES = 4096

    /** Nesting depth of an input schema, counted in containers from the root at 0. */
    const val MAX_SCHEMA_DEPTH = 16

    /** Canonical bytes of one input schema. */
    const val MAX_SCHEMA_BYTES = 16 * 1024

    /** Members of one input schema. Bounds a schema that is shallow but very wide. */
    const val MAX_SCHEMA_NODES = 512

    /** Bytes of any single string inside a schema. */
    const val MAX_SCHEMA_STRING_BYTES = 1024

    /** Canonical bytes of the whole frozen catalog. */
    const val MAX_CATALOG_BYTES = 128 * 1024

    /** Canonical bytes of a tool call's arguments. */
    const val MAX_ARGS_BYTES = 32 * 1024

    /** Nesting depth of tool call arguments, counted in containers from the root at 0. */
    const val MAX_ARGS_DEPTH = 16

    /** Bytes of a toolCallId. */
    const val MAX_TOOL_CALL_ID_BYTES = 128

    /** Bytes of any identity string (assistant, conversation, branch, request). */
    const val MAX_IDENTITY_BYTES = 128

    /** Bytes of the opaque result body Android may return. */
    const val MAX_RESULT_BYTES = 64 * 1024

    /** Longest a single tool call may remain pending. */
    const val MAX_DEADLINE_MS = 30 * 60 * 1000L

    /** The same ceiling the Server applies to a public `tool.result` body. */
    const val MAX_TOOL_RESULT_BYTES = 64 * 1024
}

/** Where a tool came from, as a closed enum. Describes provenance; decides nothing. */
enum class ToolSource(val wire: String) {
    LOCAL("local"),
    MCP("mcp");

    companion object {
        fun fromWire(value: String?): ToolSource? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * The state of one tool call. A closed vocabulary of nine, and the set is **total**: a call is
 * either still `pending` or it has reached one of the other eight.
 */
enum class ToolCallState(val wire: String) {
    /** Not an answer. The state a call has between being accepted and being answered. */
    PENDING("pending"),

    /** Android ran it and returned a body. */
    COMPLETED("completed"),

    /** Android's approval gate refused it. */
    DENIED("denied"),

    /** Android ran it and it failed. */
    FAILED("failed"),

    /** A cancel reached it, before or during execution. */
    CANCELLED("cancelled"),

    /** A deadline elapsed before Android answered. */
    TIMED_OUT("timed_out"),

    /** The Server holds no record of that toolCallId. A query verdict, never an answer. */
    NOT_FOUND("not_found"),

    /** A repeat of that toolCallId arrived carrying different content. Never executed. */
    CONFLICT("conflict"),

    /** The authenticated Android connection that owned the call went away. */
    DISCONNECTED("disconnected");

    companion object {
        private val byWire = entries.associateBy { it.wire }

        fun fromWire(value: String?): ToolCallState? = value?.let { byWire[it] }
    }
}

/** True when no further answer will arrive for this call. Everything but [PENDING]. */
fun ToolCallState.isTerminal(): Boolean = this != ToolCallState.PENDING

/**
 * The states Android may put in an outcome.
 *
 * Android is the execution authority, so it may conclude any of these about a call it was
 * given. The four it may **not** send are the Server's own verdicts about its own records:
 * `pending` (which would be a call that never terminates), `disconnected` (only the Server
 * observes a connection going away — Android *is* the end that went away), and `not_found` /
 * `conflict` (verdicts about the Server's ledger).
 *
 * An Android that has lost a call must say `failed`, which is honest. Saying `not_found` would
 * claim knowledge of the Server's records it does not have.
 */
val ANDROID_REPORTABLE_TOOL_CALL_STATES: Set<ToolCallState> = setOf(
    ToolCallState.COMPLETED,
    ToolCallState.DENIED,
    ToolCallState.FAILED,
    ToolCallState.CANCELLED,
    ToolCallState.TIMED_OUT,
)

fun ToolCallState.isAndroidReportable(): Boolean = this in ANDROID_REPORTABLE_TOOL_CALL_STATES

/**
 * Why a catalog, invocation or query was refused. A closed set, mirroring
 * `src/bridge/contract.ts#BridgeRejection`.
 *
 * These never carry the offending value. A reason travels into logs, IPC frames and a peer's
 * error handler, and the values are peer-supplied.
 */
enum class BridgeRejection(val wire: String) {
    // Catalog shape
    CATALOG_FIELD_UNKNOWN("catalog_field_unknown"),
    CATALOG_FIELD_INVALID("catalog_field_invalid"),
    CATALOG_TOO_LARGE("catalog_too_large"),
    CATALOG_TOO_MANY_TOOLS("catalog_too_many_tools"),
    CATALOG_DUPLICATE_NAME("catalog_duplicate_name"),
    CATALOG_NAME_COLLISION("catalog_name_collision"),
    CATALOG_NAME_INVALID("catalog_name_invalid"),
    CATALOG_NAME_RESERVED("catalog_name_reserved"),
    DESCRIPTION_TOO_LARGE("description_too_large"),
    SCHEMA_INVALID("schema_invalid"),
    SCHEMA_TOO_DEEP("schema_too_deep"),
    SCHEMA_TOO_LARGE("schema_too_large"),
    SCHEMA_TOO_MANY_NODES("schema_too_many_nodes"),
    SCHEMA_STRING_TOO_LONG("schema_string_too_long"),

    // Bindings
    BRIDGE_ABI_MISMATCH("bridge_abi_mismatch"),
    BINDING_FIELD_UNKNOWN("binding_field_unknown"),
    BINDING_FIELD_INVALID("binding_field_invalid"),
    TIMEOUT_INVALID("timeout_invalid"),
    INVOCATION_ID_INVALID("invocation_id_invalid"),
    INVOCATION_ID_UNKNOWN("invocation_id_unknown"),
    INVOCATION_BINDING_MISMATCH("invocation_binding_mismatch"),
    TOOL_NAME_NOT_BRIDGED("tool_name_not_bridged"),
    TOOL_NOT_IN_CATALOG("tool_not_in_catalog"),
    SCHEMA_DIGEST_MISMATCH("schema_digest_mismatch"),

    // Arguments, answers
    ARGS_NOT_AN_OBJECT("args_not_an_object"),
    ARGS_INVALID("args_invalid"),
    ARGS_TOO_LARGE("args_too_large"),
    ARGS_TOO_DEEP("args_too_deep"),
    OUTCOME_FIELD_UNKNOWN("outcome_field_unknown"),
    STATE_NOT_REPORTABLE("state_not_reportable"),
    RESULT_TOO_LARGE("result_too_large"),
    RESULT_STATE_MISMATCH("result_state_mismatch"),

    /** A second answer arrived for a call that had already been answered differently. */
    OUTCOME_ALREADY_ANSWERED("outcome_already_answered"),
}

/** Thrown with a reason from the closed set, and never with the offending value. */
class BridgeRejected(val reason: BridgeRejection) :
    Exception("bridge rejected: ${reason.wire}")
