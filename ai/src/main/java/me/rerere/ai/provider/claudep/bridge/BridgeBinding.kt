package me.rerere.ai.provider.claudep.bridge

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Generation and invocation bindings.
 *
 * A binding is the answer to "what exactly was this call about?". Every field in one is there
 * because a change to it would change what executing the call means, and the whole point of
 * binding them is that a repeat carrying a different value is a **conflict** — refused, never
 * executed — rather than a replay that quietly returns the earlier answer.
 *
 * ## Why a digest rather than the fields
 *
 * The ledger holds one digest per call, not the call: a digest answers "is this the same
 * call?" while leaving nothing to hold, nothing to log and nothing to write into a state file.
 * The fields are not secrets, but the discipline of not accumulating peer identifiers in a
 * durable record is worth keeping uniform with the Server's.
 *
 * ## Why `timeoutMs` is a duration and not a deadline
 *
 * Two devices comparing instants is a clock comparison, and Android's clock and the Server's
 * are not the same clock: a skew of a few seconds is enough to make a legitimate generation
 * look already-expired. A duration is measured on the clock of whoever waits, so there is
 * nothing to disagree about. The Server converts it to an absolute deadline once, at accept
 * time, and that instant never travels.
 */
data class GenerationBinding(
    val deviceRef: String,
    val assistantId: String,
    val conversationId: String,
    val branchId: String,
    val generationId: String,
    val requestId: String,
    val catalogDigest: String,
    val bridgeAbi: String,
    val timeoutMs: Long,
)

/**
 * One invocation, bound to everything that could make a replay dangerous.
 *
 * The **execution identity** is the `toolCallId` and nothing else; every other field here is
 * bound by *verification*. A repeat carrying the same `toolCallId` but any different field is
 * a conflict, and a conflict is never executed.
 */
data class InvocationBinding(
    val deviceRef: String,
    val assistantId: String,
    val conversationId: String,
    val branchId: String,
    val generationId: String,
    val requestId: String,
    val catalogDigest: String,
    val bridgeAbi: String,
    val timeoutMs: Long,
    val toolCallId: String,
    val toolName: String,
    val schemaDigest: String,
    val argsDigest: String,
)

/** The Kotlin half of `src/bridge/binding.ts`. */
object BridgeBinding {

    private val GENERATION_KEYS = setOf(
        "deviceRef", "assistantId", "conversationId", "branchId", "generationId", "requestId",
        "catalogDigest", "bridgeAbi", "timeoutMs",
    )

    private val INVOCATION_KEYS = GENERATION_KEYS + setOf(
        "toolCallId", "toolName", "schemaDigest", "argsDigest",
    )

    /** Lowercase hex, the only encoding any digest in this contract is written in. */
    private val HEX64 = Regex("^[0-9a-f]{64}$")

    /** A frozen tool name: already normalized, so it is lowercase by construction. */
    private val FROZEN_NAME = Regex("^[a-z][a-z0-9_-]*$")

    /**
     * True for a string usable as an opaque identifier: non-empty, byte-bounded, and free of
     * control characters.
     *
     * The control-character rule is not cosmetic. These values are written into log lines and
     * into framed IPC, where a newline or a NUL is how one record becomes two records — a
     * malformed identity would be a framing injection. Nothing legitimate needs them.
     */
    fun isOpaqueIdentity(value: String?): Boolean {
        if (value.isNullOrEmpty()) return false
        if (value.toByteArray(Charsets.UTF_8).size > BridgeLimits.MAX_IDENTITY_BYTES) return false
        return value.none { it.isControlCharacter() }
    }

    /** A `toolCallId`: the same rule as an identity, at the id's own bound. */
    fun isToolCallId(value: String?): Boolean {
        if (value.isNullOrEmpty()) return false
        if (value.toByteArray(Charsets.UTF_8).size > BridgeLimits.MAX_TOOL_CALL_ID_BYTES) return false
        return value.none { it.isControlCharacter() }
    }

    /**
     * C0 controls, DEL, and the C1 range.
     *
     * The C1 range is included because it is a control range that a naive `code < 0x20` check
     * misses entirely, and it is encodable in UTF-8 — so it would pass a byte-length bound
     * while still being a control character in a framed record.
     */
    private fun Char.isControlCharacter(): Boolean {
        val code = code
        return code < 0x20 || code == 0x7f || (code in 0x80..0x9f)
    }

    /**
     * Validates a generation binding from a parsed frame.
     *
     * @throws BridgeRejected with a reason from the closed set.
     */
    fun validateGenerationBinding(raw: JsonObject): GenerationBinding {
        for (key in raw.keys) {
            if (key !in GENERATION_KEYS) throw BridgeRejected(BridgeRejection.BINDING_FIELD_UNKNOWN)
        }
        return readGenerationFields(raw)
    }

    /**
     * Validates an invocation binding from a parsed frame.
     *
     * @throws BridgeRejected with a reason from the closed set.
     */
    fun validateInvocationBinding(raw: JsonObject): InvocationBinding {
        for (key in raw.keys) {
            if (key !in INVOCATION_KEYS) throw BridgeRejected(BridgeRejection.BINDING_FIELD_UNKNOWN)
        }

        val generation = readGenerationFields(raw)

        val toolCallId = raw.string("toolCallId")
        if (!isToolCallId(toolCallId)) {
            throw BridgeRejected(BridgeRejection.INVOCATION_ID_INVALID)
        }

        val toolName = raw.string("toolName")
        if (toolName == null ||
            !FROZEN_NAME.matches(toolName) ||
            toolName.toByteArray(Charsets.UTF_8).size > BridgeLimits.MAX_TOOL_NAME_BYTES
        ) {
            throw BridgeRejected(BridgeRejection.TOOL_NAME_NOT_BRIDGED)
        }

        for (key in listOf("schemaDigest", "argsDigest")) {
            val value = raw.string(key)
            if (value == null || !HEX64.matches(value)) {
                throw BridgeRejected(BridgeRejection.BINDING_FIELD_INVALID)
            }
        }

        return InvocationBinding(
            deviceRef = generation.deviceRef,
            assistantId = generation.assistantId,
            conversationId = generation.conversationId,
            branchId = generation.branchId,
            generationId = generation.generationId,
            requestId = generation.requestId,
            catalogDigest = generation.catalogDigest,
            bridgeAbi = generation.bridgeAbi,
            timeoutMs = generation.timeoutMs,
            toolCallId = toolCallId!!,
            toolName = toolName,
            schemaDigest = raw.string("schemaDigest")!!,
            argsDigest = raw.string("argsDigest")!!,
        )
    }

    /**
     * The generation fields, validated, with no opinion about whether the record carries
     * others.
     *
     * Separated from the key-set check because an invocation binding *contains* a generation
     * binding: validating it must apply the generation field rules without applying the
     * generation key set, which would refuse the four keys that make it an invocation.
     */
    private fun readGenerationFields(raw: JsonObject): GenerationBinding {
        for (key in listOf(
            "deviceRef", "assistantId", "conversationId", "branchId", "generationId", "requestId",
        )) {
            if (!isOpaqueIdentity(raw.string(key))) {
                throw BridgeRejected(BridgeRejection.BINDING_FIELD_INVALID)
            }
        }

        val catalogDigest = raw.string("catalogDigest")
        if (catalogDigest == null || !HEX64.matches(catalogDigest)) {
            throw BridgeRejected(BridgeRejection.BINDING_FIELD_INVALID)
        }

        // Exact equality, never a prefix or a major-version comparison. A catalog frozen under
        // a different ABI was frozen under different rules, and the digest that accompanies it
        // is not a digest this build knows how to interpret.
        val bridgeAbi = raw.string("bridgeAbi")
        if (bridgeAbi != BridgeContract.BRIDGE_ABI) {
            throw BridgeRejected(BridgeRejection.BRIDGE_ABI_MISMATCH)
        }

        val timeoutPrimitive = raw["timeoutMs"] as? JsonPrimitive
        val timeoutMs = timeoutPrimitive
            ?.takeIf { !it.isString }
            ?.content
            ?.toLongOrNull()
        if (timeoutMs == null || timeoutMs <= 0 || timeoutMs > BridgeLimits.MAX_DEADLINE_MS) {
            throw BridgeRejected(BridgeRejection.TIMEOUT_INVALID)
        }

        return GenerationBinding(
            deviceRef = raw.string("deviceRef")!!,
            assistantId = raw.string("assistantId")!!,
            conversationId = raw.string("conversationId")!!,
            branchId = raw.string("branchId")!!,
            generationId = raw.string("generationId")!!,
            requestId = raw.string("requestId")!!,
            catalogDigest = catalogDigest,
            bridgeAbi = bridgeAbi,
            timeoutMs = timeoutMs,
        )
    }

    /**
     * The digest of everything an invocation is bound to.
     *
     * Two invocations with the same `toolCallId` and the same digest are the same call.
     * Anything that differs produces a different digest, and a different digest under a
     * repeated `toolCallId` is a conflict — which is the property that makes it safe for the
     * ledger to key on the call id alone.
     */
    fun invocationDigest(binding: InvocationBinding): String {
        val payload = JsonObject(
            linkedMapOf(
                "bridge_abi" to JsonPrimitive(binding.bridgeAbi),
                "device_ref" to JsonPrimitive(binding.deviceRef),
                "assistant_id" to JsonPrimitive(binding.assistantId),
                "conversation_id" to JsonPrimitive(binding.conversationId),
                "branch_id" to JsonPrimitive(binding.branchId),
                "generation_id" to JsonPrimitive(binding.generationId),
                "request_id" to JsonPrimitive(binding.requestId),
                "catalog_digest" to JsonPrimitive(binding.catalogDigest),
                "timeout_ms" to JsonPrimitive(binding.timeoutMs),
                "tool_call_id" to JsonPrimitive(binding.toolCallId),
                "tool_name" to JsonPrimitive(binding.toolName),
                "schema_digest" to JsonPrimitive(binding.schemaDigest),
                "args_digest" to JsonPrimitive(binding.argsDigest),
            ),
        )
        return BridgeCanonical.digestOf(payload)
    }

    /**
     * The ledger key for one tool call. Keyed on the tool call id and on nothing else.
     *
     * Every other key is wrong in a way that is worse than it looks:
     *
     * - Keying on `(generationId, toolCallId)` looks more precise and is less safe. A repeat
     *   arriving under a *different* generation — the "wrong generation" case that must fail
     *   closed — would land in a different bucket, find nothing, and look fresh, so it would
     *   execute a second time. Keying on the tool call id alone makes it a hit, and a hit whose
     *   binding does not match is a conflict instead of a second side effect.
     * - Keying on the invocation digest would make a changed invocation a *miss* rather than a
     *   conflict, which is the same failure by another route.
     *
     * The tool call id is validated to be free of control characters before it reaches here, so
     * the separator cannot be forged by its content.
     */
    fun invocationKey(toolCallId: String): String =
        BridgeCanonical.sha256Hex(INVOCATION_KEY_DOMAIN + NUL + toolCallId)

    private const val INVOCATION_KEY_DOMAIN = "rikkahub-claude-p-bridge-invocation-v1"

    /** The separator between the domain and the id: `U+0000`. */
    private val NUL = Char(0)
}
