package me.rerere.rikkahub.workflow.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.capability.CapabilityKey
import me.rerere.rikkahub.data.capability.ToolCapabilityResolver
import me.rerere.rikkahub.toolcatalog.ToolCatalogSnapshot

/**
 * Single action in a workflow. Identical wire shape to scheduled-jobs direct-mode actions —
 * we reuse [me.rerere.rikkahub.service.DirectModeActionRunner] for execution.
 */
@Serializable
data class WorkflowAction(
    /** Tool name from the existing tool registry. Validation rejects unknown names. */
    val tool: String,
    /** JSON args matching what the LLM would emit in a chat tool call. */
    val args: JsonObject,
    /** Per-action timeout, 1..600s. Default 60. */
    val timeoutSeconds: Int = 60,
    /**
     * Exact fingerprint of the reviewed Tool definition. New writes always persist it. A null
     * value is reserved for pre-P4 USER rows; LEARNED rows treat it as corrupt and never run.
     */
    val toolSchemaFingerprint: String? = null,
)

enum class WorkflowOrigin {
    USER,
    LEARNED,
}

/**
 * Server-stamped authority describing the tool surface a workflow was authored against.
 *
 * Null is reserved for rows that predate this field (legacy) — they are eligible for a bounded
 * legacy inference at run time ([me.rerere.rikkahub.workflow.model.WorkflowRunSurface]). Every
 * successful workflow_create / workflow_update re-stamps this from the caller's real privilege
 * context; it is never taken from LLM/user JSON.
 */
enum class WorkflowAuthoringAuthority {
    /** Authored from an ordinary (non-second-user-expanded) surface. Pinned to `assistant.localTools` at run. */
    LOCAL,
    /** Authored from the Second-User confirmed expanded surface. Run re-validates against the CURRENT active Second-User allowlist. */
    SECOND_USER_CONFIRMED;

    companion object {
        /** Tolerant decode for stored/round-trip JSON; null on any unknown or malformed value. */
        fun decodeOrNull(raw: String): WorkflowAuthoringAuthority? = entries.firstOrNull {
            it.name == raw
        }
    }
}

/**
 * Outcome of one workflow fire.
 *  - SUCCESS / FAILED — actually ran
 *  - SKIPPED_CONDITIONS — at least one condition evaluated false
 *  - SKIPPED_COOLDOWN — fired inside cooldown window
 *  - SKIPPED_DAILY_CAP — daily cap reached
 *  - SKIPPED_DISABLED — workflow toggle was off when trigger arrived (race-cleanup)
 */
enum class WorkflowRunStatus {
    SUCCESS,
    FAILED,
    SKIPPED_CONDITIONS,
    SKIPPED_COOLDOWN,
    SKIPPED_DAILY_CAP,
    SKIPPED_DISABLED,
}

/**
 * The full workflow definition the LLM authors. The server stores [definitionJson] in Room
 * as the source of truth and parses to this shape on every read; that way new fields can
 * be added without an Entity migration as long as defaults are sensible.
 */
@Serializable
data class WorkflowDefinition(
    val id: String,
    val name: String,
    val description: String? = null,
    val enabled: Boolean = true,
    val trigger: TriggerSpec,
    val conditions: List<ConditionSpec> = emptyList(),
    val actions: List<WorkflowAction>,
    /** Minimum gap between two consecutive fires in seconds. 0 = no cooldown. */
    val cooldownSeconds: Int = 0,
    /** Max successful+failed fires per local-day. null = unlimited. */
    val maxRunsPerDay: Int? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis(),
    /**
     * Stability fix (2026-05-07 audit) — UUID of the assistant that authored the workflow.
     * The engine resolves the runtime tool surface from THIS specific assistant at fire
     * time. If null (legacy rows pre-fix), the engine falls back to the previous
     * "any assistant with the Workflows toggle on" heuristic with a Log.w. New workflows
     * always have this set via the ToolInvocationContext propagation in workflow_create.
     */
    val authoringAssistantId: String? = null,
    /**
     * Capability keys captured when the workflow was created or updated. Runtime execution may
     * only use this fixed set; editing actions through workflow_update produces a new reviewed
     * snapshot rather than silently inheriting all of the author's current tools.
     */
    val capabilitySnapshot: Set<String> = emptySet(),
    /** Provenance is security state, not an id-prefix convention. */
    val origin: WorkflowOrigin = WorkflowOrigin.USER,
    val sourceCandidateId: String? = null,
    val sourceArtifactHash: String? = null,
    val grantDigest: String? = null,
    /**
     * Exact durable Learning scope provenance for a promoted definition. A null value means the
     * Assistant scope of [authoringAssistantId]; a non-null value means that exact
     * AuthoritySubject scope. Canonical learned JSON always persists the key even when its value
     * is null, so a missing key can never be confused with an Assistant-scoped artifact after a
     * LearningDatabase loss or restore.
     */
    val authoritySubjectId: String? = null,
    /**
     * Server-stamped authoring surface authority — see [WorkflowAuthoringAuthority]. Absent
     * (null) only for pre-marker legacy rows, which the engine may heal via bounded inference.
     * Set on every workflow_create / workflow_update from the caller's real privilege context,
     * never from the LLM's JSON.
     */
    val authoringAuthority: WorkflowAuthoringAuthority? = null,
)

object WorkflowCapabilitySnapshot {
    fun capture(actions: List<WorkflowAction>): Set<String> = actions
        .flatMap { action -> ToolCapabilityResolver.resolve(action.tool, action.args).capabilities }
        .map(CapabilityKey::value)
        .toSortedSet()

    fun parse(snapshot: Set<String>): Set<CapabilityKey> = snapshot.mapNotNull { raw ->
        runCatching { CapabilityKey.of(raw) }.getOrNull()
    }.toSet()

    /**
     * Learned workflows must never fall back to resolving their actions again. A persisted
     * snapshot is usable only when it is non-empty and every value is already in canonical
     * form. Returning null gives future learned-workflow callers a simple fail-closed API.
     */
    fun parsePersistedForLearnedExecution(snapshot: Set<String>): Set<CapabilityKey>? {
        if (snapshot.isEmpty()) return null
        val parsed = snapshot.map { raw ->
            runCatching { CapabilityKey.of(raw) }.getOrNull() ?: return null
        }.toSet()
        if (parsed.size != snapshot.size || parsed.map(CapabilityKey::value).toSet() != snapshot) {
            return null
        }
        return parsed
    }
}

/** Immutable schema identities captured alongside each action. */
object WorkflowToolSchemaSnapshot {
    private val canonicalSha256 = Regex("^[0-9a-f]{64}$")

    /**
     * Stamp every action from one immutable Tool catalogue. Null means at least one action has
     * no current definition/fingerprint, so callers must not persist a reviewed workflow.
     */
    fun capture(actions: List<WorkflowAction>, tools: Collection<Tool>): List<WorkflowAction>? {
        val catalog = ToolCatalogSnapshot.fromDefinitions(tools.toList())
        return actions.map { action ->
            val fingerprint = catalog.entry(action.tool)?.schemaFingerprint
                ?.takeIf(::isCanonical) ?: return null
            action.copy(toolSchemaFingerprint = fingerprint)
        }
    }

    fun isCanonical(value: String): Boolean = canonicalSha256.matches(value)

    fun isComplete(actions: List<WorkflowAction>): Boolean =
        actions.isNotEmpty() && actions.all { action ->
            action.toolSchemaFingerprint?.let(::isCanonical) == true
        }

    /** Stable projection used by the Room column and migration/reconciliation checks. */
    fun canonicalProjection(actions: List<WorkflowAction>): String {
        if (actions.all { it.toolSchemaFingerprint == null }) return "[]"
        return buildString {
        append('[')
        actions.forEachIndexed { index, action ->
            if (index > 0) append(',')
            append("{\"index\":").append(index)
            append(",\"schema_fingerprint\":")
            append(jsonString(action.toolSchemaFingerprint))
            append(",\"tool\":").append(jsonString(action.tool))
            append('}')
        }
        append(']')
        }
    }

    private fun jsonString(value: String?): String = when (value) {
        null -> "null"
        else -> buildString {
            append('"')
            value.forEach { c ->
                when (c) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\b' -> append("\\b")
                    '\u000c' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
                }
            }
            append('"')
        }
    }
}

/**
 * Fail-closed validator for the JSON-Schema subset represented by [InputSchema]. Tool schemas
 * are host definitions, while workflow arguments are durable untrusted data. This deliberately
 * rejects unknown root arguments instead of relying on tool bodies to ignore them.
 */
object WorkflowInputSchemaValidator {
    data class Error(val path: String, val detail: String)

    fun validate(args: JsonObject, schema: InputSchema?): Error? = when (schema) {
        null -> if (args.isEmpty()) null else Error("$", "tool declares no input schema")
        is InputSchema.Obj -> validateObject(
            value = args,
            properties = schema.properties,
            required = schema.required.orEmpty(),
            path = "$",
            rejectUnknown = true,
        )
    }

    private fun validateObject(
        value: JsonObject,
        properties: JsonObject,
        required: List<String>,
        path: String,
        rejectUnknown: Boolean,
    ): Error? {
        val missing = required.firstOrNull { it !in value }
        if (missing != null) return Error(child(path, missing), "required argument is missing")
        if (rejectUnknown) {
            val unknown = value.keys.firstOrNull { it !in properties }
            if (unknown != null) return Error(child(path, unknown), "unknown argument")
        }
        value.forEach { (name, element) ->
            val propertySchema = properties[name] as? JsonObject
                ?: return Error(child(path, name), "property schema is missing or malformed")
            validateElement(element, propertySchema, child(path, name))?.let { return it }
        }
        return null
    }

    private fun validateElement(value: JsonElement, schema: JsonObject, path: String): Error? {
        val alternatives = (schema["oneOf"] ?: schema["anyOf"]) as? JsonArray
        if (alternatives != null) {
            val accepted = alternatives.any { option ->
                val objectOption = option as? JsonObject ?: return@any false
                validateElement(value, objectOption, path) == null
            }
            if (!accepted) return Error(path, "does not match any allowed schema")
        }

        val allowedTypes = when (val type = schema["type"]) {
            null -> emptySet()
            is JsonPrimitive -> type.contentOrNull?.let(::setOf).orEmpty()
            is JsonArray -> type.mapNotNull { item ->
                (item as? JsonPrimitive)?.contentOrNull
            }.toSet()
            else -> return Error(path, "schema type is malformed")
        }
        if (allowedTypes.isNotEmpty() && allowedTypes.none { matchesType(value, it) }) {
            return Error(path, "expected ${allowedTypes.sorted().joinToString(" or ")}")
        }

        val enumValues = schema["enum"] as? JsonArray
        if (enumValues != null && enumValues.none { it == value }) {
            return Error(path, "value is outside enum")
        }

        when (value) {
            is JsonObject -> {
                val properties = schema["properties"] as? JsonObject
                if (properties != null) {
                    val required = (schema["required"] as? JsonArray).orEmpty().mapNotNull {
                        (it as? JsonPrimitive)?.contentOrNull
                    }
                    val allowAdditional = (schema["additionalProperties"] as? JsonPrimitive)
                        ?.booleanOrNull == true
                    validateObject(
                        value = value,
                        properties = properties,
                        required = required,
                        path = path,
                        rejectUnknown = !allowAdditional,
                    )?.let { return it }
                }
            }
            is JsonArray -> {
                schema["minItems"]?.jsonPrimitive?.intOrNull?.let { minimum ->
                    if (value.size < minimum) return Error(path, "requires at least $minimum item(s)")
                }
                schema["maxItems"]?.jsonPrimitive?.intOrNull?.let { maximum ->
                    if (value.size > maximum) return Error(path, "allows at most $maximum item(s)")
                }
                val itemSchema = schema["items"] as? JsonObject
                if (itemSchema != null) {
                    value.forEachIndexed { index, item ->
                        validateElement(item, itemSchema, "$path[$index]")?.let { return it }
                    }
                }
            }
            is JsonPrimitive -> if (value.isString) {
                schema["minLength"]?.jsonPrimitive?.intOrNull?.let { minimum ->
                    if (value.content.length < minimum) return Error(path, "is shorter than $minimum")
                }
                schema["maxLength"]?.jsonPrimitive?.intOrNull?.let { maximum ->
                    if (value.content.length > maximum) return Error(path, "is longer than $maximum")
                }
            } else {
                val number = value.doubleOrNull
                if (number != null) {
                    schema["minimum"]?.jsonPrimitive?.doubleOrNull?.let { minimum ->
                        if (number < minimum) return Error(path, "must be >= $minimum")
                    }
                    schema["maximum"]?.jsonPrimitive?.doubleOrNull?.let { maximum ->
                        if (number > maximum) return Error(path, "must be <= $maximum")
                    }
                }
            }
            JsonNull -> Unit
        }
        return null
    }

    private fun matchesType(value: JsonElement, type: String): Boolean = when (type) {
        "null" -> value === JsonNull
        "object" -> value is JsonObject
        "array" -> value is JsonArray
        "string" -> value is JsonPrimitive && value.isString
        "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
        "integer" -> value is JsonPrimitive && !value.isString && value.longOrNull != null
        "number" -> value is JsonPrimitive && !value.isString && value.doubleOrNull != null
        else -> false
    }

    private fun child(path: String, name: String): String =
        if (path == "$") "$.${name}" else "$path.$name"
}

/**
 * One row of fire history.
 */
@Serializable
data class WorkflowRun(
    val rowId: Long,
    val workflowId: String,
    val firedAtMs: Long,
    val status: WorkflowRunStatus,
    val durationMs: Long,
    val errorMessage: String?,
)

object WorkflowConstants {
    const val MAX_NAME_LENGTH = 80
    const val MAX_DESCRIPTION_LENGTH = 500
    const val MAX_ACTIONS = 32
    const val MIN_ACTION_TIMEOUT_S = 1
    const val MAX_ACTION_TIMEOUT_S = 600
    const val MAX_COOLDOWN_S = 24 * 60 * 60 // 24h
    const val MAX_RUNS_PER_DAY_FLOOR = 1
    const val MAX_RUNS_PER_DAY_CEIL = 1000
    const val MIN_GEOFENCE_RADIUS_M = 50
    const val MAX_GEOFENCE_RADIUS_M = 5000
    const val MAX_RUNS_HISTORY = 100
    const val MAX_ERROR_LENGTH = 500
}
