package me.rerere.rikkahub.data.claudep

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.encodeToJsonElement
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.claudep.bridge.BridgeCatalogBuild
import me.rerere.ai.provider.claudep.bridge.BridgeToolCandidate
import me.rerere.ai.provider.claudep.bridge.BridgeToolCatalog
import me.rerere.ai.provider.claudep.bridge.ToolSource
import me.rerere.rikkahub.data.ai.execution.InternalToolSecurityCatalog

/**
 * Turns the app's own assembled tool list into the frozen catalog Claude P sends.
 *
 * ## Why this is separation of concerns and not a second registry
 *
 * The list handed in is the one the app already computes for **every** provider — the assistant's
 * allowlist, the connected MCP servers' tools, the same objects `ChatService` passes to
 * `GenerationHandler`. Nothing here decides which tools exist or re-registers them; it only
 * restates a tool the runtime already holds in the neutral shape the bridge freezes. A second
 * registry is exactly what the M2 gate forbids, and this file deliberately has no way to become
 * one: its only input is a `List<Tool>` somebody else built.
 *
 * ## Where `readOnly` may come from
 *
 * Only from [InternalToolSecurityCatalog.READ_ONLY], which is the app's own statement about its
 * own tools and the same authority `DefaultToolExecutionPolicyResolver` already consults to grant
 * read-only concurrency. Nothing else can produce `true`:
 *
 * - not the Server, which never sees this decision;
 * - not the model, which has no vote;
 * - not a caller's boolean, because `BridgeToolCandidate` has no constructor that takes one;
 * - never an MCP tool, because Android cannot inspect a remote implementation to hold that proof,
 *   and `provenReadOnly` refuses a non-local source outright.
 *
 * The name is matched **raw**, not normalized: the proof is about the tool the runtime is asked to
 * run, and the runtime resolves `ToolExecutionPolicy` by that same raw name. A name that freezes to
 * something else is still judged under the name it was proven under.
 *
 * ## What it does not do
 *
 * It does not assess a call. `readOnly` here describes the **tool**; whether a particular
 * invocation is safe is decided later, against the real arguments, by the runtime. A `true` in the
 * catalog skips nothing — not `assess()`, not the gate, not approval.
 *
 * A tool whose schema cannot be encoded is dropped by [BridgeToolCatalog], never repaired: a
 * repaired entry is one the two ends would digest differently, and that disagreement surfaces much
 * later as an unexplained conflict at call time.
 */
object ClaudePToolCatalogAssembly {

    /**
     * A `Json` that only ever encodes an `InputSchema` to a `JsonElement`.
     *
     * `explicitNulls = false` is load-bearing, not cosmetic. `InputSchema.Obj.required` is nullable
     * and defaults to `null`, and encoding that as an explicit `"required": null` produces a schema
     * the frozen contract refuses — so every tool that declares no required list would be *dropped*
     * from the catalog, silently, and the drop would look like an eligibility problem rather than
     * an encoding one. Omitting an absent `required` is also what JSON Schema means by it.
     *
     * `encodeDefaults` keeps `properties`, whose default is an empty object and which must still
     * appear: a schema without it is not an object schema.
     */
    private val schemaJson = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    /** The frozen catalog and snapshot body for these tools. Never throws; drops what it cannot freeze. */
    fun build(tools: List<Tool>): BridgeCatalogBuild = BridgeToolCatalog.build(tools.map(::candidateOf))

    /**
     * One `Tool` as a bridge candidate, with the strongest claim the app can actually support.
     *
     * `mcp__`-prefixed names are [ToolSource.MCP] and are never proven read-only; everything else is
     * the app's own [ToolSource.LOCAL] surface.
     */
    fun candidateOf(tool: Tool): BridgeToolCandidate {
        val source = sourceOf(tool.name)
        val schema = schemaOf(tool)
        val description = tool.description

        val provenReadOnly = source == ToolSource.LOCAL &&
            tool.name in InternalToolSecurityCatalog.READ_ONLY
        return if (provenReadOnly) {
            BridgeToolCandidate.provenReadOnly(
                name = tool.name,
                description = description,
                inputSchema = schema,
                source = source,
            )
        } else {
            BridgeToolCandidate.tool(
                name = tool.name,
                description = description,
                inputSchema = schema,
                source = source,
            )
        }
    }

    /**
     * The provenance of a tool, from the only marker the app's assembly actually produces.
     *
     * MCP tools are namespaced `mcp__<slug>_<server>__<tool>` by `ChatService`, and this is the
     * same prefix `ToolApprovalDefaults.requiresApproval` and the security descriptor resolver key
     * on. It is a *provenance* statement and decides nothing on its own — but it does decide that a
     * read-only proof is unavailable, because the implementation is not this side's to inspect.
     */
    fun sourceOf(toolName: String): ToolSource =
        if (toolName.startsWith(MCP_TOOL_PREFIX)) ToolSource.MCP else ToolSource.LOCAL

    /**
     * The tool's declared input schema as a [JsonElement].
     *
     * A tool declaring no schema yields [JsonNull], which the catalog refuses to freeze — and that
     * is the intended outcome rather than a gap. Offering Claude a tool whose arguments nobody
     * declared would invite calls the runtime cannot bind to named parameters, and dropping the
     * tool costs a capability while inventing a schema would cost correctness.
     */
    private fun schemaOf(tool: Tool): JsonElement {
        val declared: InputSchema = tool.parameters() ?: return JsonNull
        return schemaJson.encodeToJsonElement(InputSchema.serializer(), declared)
    }

    /** The namespace `ChatService` gives an MCP tool: see the MCP assembly block there. */
    private const val MCP_TOOL_PREFIX = "mcp__"
}
