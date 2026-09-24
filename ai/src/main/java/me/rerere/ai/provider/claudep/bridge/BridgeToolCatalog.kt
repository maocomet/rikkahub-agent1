package me.rerere.ai.provider.claudep.bridge

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One tool Android has already decided to offer, in the neutral shape this module freezes.
 *
 * The caller supplies this from the **existing** tool set — the assistant's allowlist as the
 * app already computes it for every other provider. Nothing here decides which tools those
 * are, and nothing here re-registers them: [name] is the name the runtime will be asked to
 * execute, and [inputSchema] is the schema the runtime already holds. A second tool registry
 * is exactly what this stage must not grow.
 *
 * ## [readOnly] is not a constructor argument, and that is the point
 *
 * `readOnly` is carried into the served catalog and into its digest, and a reviewer reading
 * `readOnly: true` reads it as "calling this cannot change anything". It must therefore be
 * impossible to set from a value that arrived over the wire — a Server response, a tool
 * frame, an MCP server's own description of itself, or an unvetted boolean from a caller.
 *
 * So there is no public constructor and no boolean parameter. A caller states a **claim** by
 * choosing a factory whose name is the claim, and the only one that can produce `true` is
 * [provenReadOnly], whose contract is that the tool has no side effect for *every* argument
 * its schema admits. [tool] is the default and asserts the weaker claim.
 *
 * This is deliberately not a policy engine. It is one constructor boundary, because the
 * danger is not that a caller decides wrongly — that can happen at any altitude — but that a
 * value from somewhere else reaches the field without anyone choosing anything.
 */
class BridgeToolCandidate private constructor(
    /** The name the Android runtime knows this tool by. */
    val name: String,
    val description: String,
    val inputSchema: JsonElement,
    /**
     * Whether this tool is asserted to have no side effects for every valid argument.
     *
     * Set only by [provenReadOnly]; `false` for every other way a candidate can be built.
     */
    val readOnly: Boolean,
    val source: ToolSource,
) {
    /** The same tool, asserted to be merely effectful. Used to downgrade when in doubt. */
    fun asEffectful(): BridgeToolCandidate =
        BridgeToolCandidate(name, description, inputSchema, false, source)

    companion object {
        /**
         * A tool that may have a side effect, or whose effect could not be proven absent.
         *
         * **This is the default and the correct answer for anything unknown.** It covers a
         * tool whose behaviour depends on its arguments, a tool this side did not write, and
         * every MCP tool — Android cannot prove a remote implementation is effect-free by
         * inspecting it, so it does not claim to.
         */
        fun tool(
            name: String,
            description: String,
            inputSchema: JsonElement,
            source: ToolSource,
        ): BridgeToolCandidate = BridgeToolCandidate(name, description, inputSchema, false, source)

        /**
         * A tool proven to have **no side effect for every argument its schema admits**.
         *
         * The proof is the caller's, and it must come from the code that owns the tool — not
         * from a description, a schema annotation, a remote claim, or a catalog entry. A tool
         * that is read-only for some arguments and not others does **not** qualify; it is
         * [tool], because the catalog describes the tool, not the call, and the call is what
         * decides.
         *
         * Restricted to [ToolSource.LOCAL]. Android cannot inspect an MCP server's
         * implementation, so it cannot hold this proof for one, and asserting it anyway would
         * be the exact overclaim this factory exists to prevent. This is stricter than the
         * ruling requires — the ruling asks MCP tools to *default* to false — and it is
         * stricter on purpose: a default is a thing a caller can override by not thinking,
         * and this one cannot be overridden at all.
         *
         * **This is never an authorization.** A `true` here does not skip `assess()`, does not
         * skip `ToolExecutionGate`, and does not skip human approval. It is a statement of fact
         * about a tool, carried so that the catalog describes the tool truthfully — nothing
         * downstream may branch on it to permit anything.
         */
        fun provenReadOnly(
            name: String,
            description: String,
            inputSchema: JsonElement,
            source: ToolSource,
        ): BridgeToolCandidate {
            require(source == ToolSource.LOCAL) {
                "provenReadOnly is a claim about an implementation this side can inspect; " +
                    "it cannot be made for a $source tool"
            }
            return BridgeToolCandidate(name, description, inputSchema, true, source)
        }
    }
}

/** Why one tool could not be frozen into a catalog. A closed set; never carries the value. */
enum class BridgeToolEligibility {
    /** The name is not one the frozen namespace can carry. */
    NAME_NOT_ENCODABLE,

    /** The name normalizes to one another candidate already uses. Ambiguous identity. */
    NAME_COLLISION,

    /** The description is larger than the frozen bound. */
    DESCRIPTION_TOO_LARGE,

    /** The input schema is not a shape the frozen bounds accept. */
    SCHEMA_NOT_ENCODABLE,
}

/** A tool that was left out, and the one reason it was. */
data class BridgeToolExclusion(
    val name: String,
    val reason: BridgeToolEligibility,
)

/** The outcome of building a catalog: what is offered, and what was left out and why. */
data class BridgeCatalogBuild(
    val catalog: FrozenCatalog,
    val snapshot: JsonObject,
    val exclusions: List<BridgeToolExclusion>,
) {
    /** True when the catalog carries no tools, which is the text path. */
    val isEmpty: Boolean get() = catalog.isEmpty
}

/**
 * Builds the frozen tool catalog Claude P sends with a bridged `generation.start`.
 *
 * ## What this is responsible for, and what it is not
 *
 * It is responsible for *stability*: the same set of tools must produce the same names, the
 * same order and the same bytes on every run and on every device, because the digest the
 * Server computes over those bytes is what binds a generation. It is **not** responsible for
 * deciding which tools exist — that is the assistant's allowlist, computed by the app's
 * existing tool assembly — and this module only ever narrows it.
 *
 * ## Fail-closed, in both directions
 *
 * Two different failures get two different answers, and the difference is the whole safety
 * argument:
 *
 * - **A tool that cannot be frozen is dropped**, and the drop is recorded. Claude then cannot
 *   call it, which loses a capability rather than inventing one.
 * - **A catalog that cannot be built at all is empty**, never "all the tools". An empty
 *   catalog is the Phase-1 text path: no bridge, no broker, no MCP config, and an argument
 *   vector byte-identical to the one M1 built. Degrading to a wider set would hand Claude
 *   tools the user did not authorize, which is the one outcome this must never produce.
 *
 * ## Why nothing is repaired
 *
 * A repaired entry is an entry the two ends disagree about: trimming a description here would
 * change the bytes the Server digests, and the disagreement would surface much later as an
 * unexplained `idempotency_conflict` at call time. The Server refuses rather than repairs, so
 * this side drops rather than repairs, and the two rules agree about what a catalog is — the
 * one both ends computed, or none at all.
 */
object BridgeToolCatalog {

    /** The empty build: the text path. */
    val EMPTY: BridgeCatalogBuild = build(emptyList())

    /**
     * Freezes the given candidates into a catalog and the `tool_snapshot` body to send.
     *
     * Deterministic by construction: the eligible set is sorted by frozen name before the
     * digest is computed, so an allowlist that iterates differently after a restart — or a
     * `Set` whose iteration order is not specified — cannot change the bytes.
     */
    fun build(candidates: List<BridgeToolCandidate>): BridgeCatalogBuild {
        val exclusions = mutableListOf<BridgeToolExclusion>()
        val eligible = mutableListOf<FrozenToolEntry>()

        // Normalization first, so a collision is detected against the *frozen* name rather than
        // the raw one. `Read_File` and `read_file` are one identity, and admitting both would
        // make "which tool does this call mean" a question with two answers.
        val byFrozenName = LinkedHashMap<String, MutableList<BridgeToolCandidate>>()
        for (candidate in candidates) {
            val frozen = frozenNameOf(candidate.name)
            if (frozen == null) {
                exclusions += BridgeToolExclusion(candidate.name, BridgeToolEligibility.NAME_NOT_ENCODABLE)
                continue
            }
            byFrozenName.getOrPut(frozen) { mutableListOf() }.add(candidate)
        }

        for ((frozen, group) in byFrozenName) {
            if (group.size > 1) {
                // Dropped, not resolved. Keeping the first would make the survivor depend on the
                // order the allowlist happened to iterate in, which is the instability the whole
                // frozen order exists to remove.
                for (candidate in group) {
                    exclusions += BridgeToolExclusion(candidate.name, BridgeToolEligibility.NAME_COLLISION)
                }
                continue
            }

            val candidate = group.single()
            when (val outcome = freezeCandidate(frozen, candidate)) {
                is CandidateOutcome.Frozen -> eligible += outcome.entry
                is CandidateOutcome.Dropped ->
                    exclusions += BridgeToolExclusion(candidate.name, outcome.reason)
            }
        }

        if (eligible.isEmpty()) return BridgeCatalogBuild(BridgeCatalog.EMPTY, bodyOf(emptyList()), exclusions)

        // The body is built from the entries and then handed to the same `freeze` the Server
        // runs, so this side's own rules are checked by the code that will check them again on
        // the other end rather than by a second, weaker copy.
        val body = bodyOf(eligible)
        val catalog = try {
            BridgeCatalog.freeze(body)
        } catch (error: BridgeRejected) {
            // Whatever the Server would have refused, this side refuses first — and by emptying
            // the catalog rather than by sending something it knows will be rejected, because a
            // refused `generation.start` costs the user the whole turn, not just the tools.
            exclusions += eligible.map {
                BridgeToolExclusion(it.displayName, BridgeToolEligibility.SCHEMA_NOT_ENCODABLE)
            }
            return BridgeCatalogBuild(BridgeCatalog.EMPTY, bodyOf(emptyList()), exclusions)
        }

        return BridgeCatalogBuild(catalog, body, exclusions)
    }

    private sealed interface CandidateOutcome {
        data class Frozen(val entry: FrozenToolEntry) : CandidateOutcome
        data class Dropped(val reason: BridgeToolEligibility) : CandidateOutcome
    }

    /**
     * The frozen name for an Android tool name, or `null` when it has none.
     *
     * The rule is the Server's, applied here so an unencodable name is a *drop* on this side
     * rather than a refused generation on the other. Android MCP tools are already namespaced
     * `mcp__<server>__<tool>` and local tools are plain identifiers, and both are already
     * `[A-Za-z][A-Za-z0-9_-]*` — a name that is not is a tool this contract cannot carry, and
     * inventing a replacement spelling would be inventing an identity the runtime does not have.
     */
    fun frozenNameOf(name: String): String? {
        if (name.isEmpty()) return null
        val first = name[0]
        if (!(first in 'a'..'z' || first in 'A'..'Z')) return null
        for (character in name) {
            val ok = character in 'a'..'z' || character in 'A'..'Z' ||
                character in '0'..'9' || character == '_' || character == '-'
            if (!ok) return null
        }
        val normalized = name.lowercase()
        if (normalized.toByteArray(Charsets.UTF_8).size > BridgeLimits.MAX_TOOL_NAME_BYTES) {
            return null
        }
        return normalized
    }

    private fun freezeCandidate(frozen: String, candidate: BridgeToolCandidate): CandidateOutcome {
        if (candidate.description.toByteArray(Charsets.UTF_8).size >
            BridgeLimits.MAX_DESCRIPTION_BYTES
        ) {
            return CandidateOutcome.Dropped(BridgeToolEligibility.DESCRIPTION_TOO_LARGE)
        }

        val schemaDigest = BridgeCatalog.validateAndDigestSchema(candidate.inputSchema)
            ?: return CandidateOutcome.Dropped(BridgeToolEligibility.SCHEMA_NOT_ENCODABLE)

        return CandidateOutcome.Frozen(
            FrozenToolEntry(
                name = frozen,
                bridgedName = BridgeContract.bridgedToolName(frozen),
                // The display name is the raw name the runtime knows, so a reviewer reading a
                // catalog can map every entry back to a real tool without a lookup table.
                displayName = candidate.name,
                description = candidate.description,
                inputSchema = candidate.inputSchema,
                schemaDigest = schemaDigest,
                readOnly = candidate.readOnly,
                source = candidate.source,
            ),
        )
    }

    /**
     * The `tool_snapshot` body: the exact key set the Server's `ENTRY_KEYS` closes over, in the
     * sorted order the digest is computed over.
     *
     * Sorted here rather than left to the caller, because the order is part of the identity and
     * a caller that assembled its list from a `Set` would otherwise produce a different digest
     * for the same tools on a different run.
     */
    private fun bodyOf(entries: List<FrozenToolEntry>): JsonObject = JsonObject(
        linkedMapOf(
            "tools" to JsonArray(
                entries.sortedBy { it.name }.map { entry ->
                    JsonObject(
                        linkedMapOf(
                            "name" to JsonPrimitive(entry.name),
                            "displayName" to JsonPrimitive(entry.displayName),
                            "description" to JsonPrimitive(entry.description),
                            "inputSchema" to entry.inputSchema,
                            "readOnly" to JsonPrimitive(entry.readOnly),
                            "source" to JsonPrimitive(entry.source.wire),
                        ),
                    )
                },
            ),
        ),
    )
}
