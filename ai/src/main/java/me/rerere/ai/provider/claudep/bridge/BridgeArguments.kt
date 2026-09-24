package me.rerere.ai.provider.claudep.bridge

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Arguments that passed every bound, with the canonical form and digest already computed. */
data class ValidatedArguments(
    val value: JsonElement,
    val canonical: String,
    val digest: String,
)

/**
 * The Kotlin half of `src/bridge/arguments.ts`.
 *
 * The digest computed here is what makes "the same toolCallId carrying different arguments" a
 * *conflict* rather than a replay that runs the tool twice. It is also what lets Android
 * recognise a re-delivered invocation as the one it already ran, without holding the arguments
 * themselves in any durable record.
 */
object BridgeArguments {

    /**
     * Validates one invocation's arguments.
     *
     * An MCP tool's arguments are an **object**. An array or a scalar is not a shape any tool
     * schema can describe, and accepting one would mean forwarding something that cannot be
     * bound to a named parameter.
     *
     * @throws BridgeRejected with a reason from the closed set.
     */
    fun validate(raw: JsonElement): ValidatedArguments {
        if (raw !is JsonObject) throw BridgeRejected(BridgeRejection.ARGS_NOT_AN_OBJECT)

        val canonical = try {
            BridgeCanonical.canonicalize(raw)
        } catch (error: CanonicalRejected) {
            // A canonical rejection is reported as the arguments rule it corresponds to, so a
            // caller learns *which* bound it hit rather than only that something was wrong.
            when (error.reason) {
                CanonicalRejection.DEPTH_EXCEEDED ->
                    throw BridgeRejected(BridgeRejection.ARGS_TOO_DEEP)
                CanonicalRejection.SIZE_EXCEEDED ->
                    throw BridgeRejected(BridgeRejection.ARGS_TOO_LARGE)
                else ->
                    throw BridgeRejected(BridgeRejection.ARGS_INVALID)
            }
        }

        // Canonicalization has already refused cycles and non-finite numbers, so this walk is
        // guaranteed to terminate and cannot be fooled by a structure that refers to itself.
        checkDepth(raw, 0)

        if (canonical.toByteArray(Charsets.UTF_8).size > BridgeLimits.MAX_ARGS_BYTES) {
            throw BridgeRejected(BridgeRejection.ARGS_TOO_LARGE)
        }

        return ValidatedArguments(raw, canonical, BridgeCanonical.sha256Hex(canonical))
    }

    private fun checkDepth(node: JsonElement, depth: Int) {
        if (depth > BridgeLimits.MAX_ARGS_DEPTH) throw BridgeRejected(BridgeRejection.ARGS_TOO_DEEP)
        when (node) {
            is JsonArray -> for (item in node) checkDepth(item, depth + 1)
            is JsonObject -> for ((_, value) in node) checkDepth(value, depth + 1)
            else -> return
        }
    }
}
