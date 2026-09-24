package me.rerere.ai.provider.claudep.bridge

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One frozen tool, as it will exist for the whole generation.
 *
 * `name` is the normalized name, unique within the catalog, and the catalog's sort key. It is
 * **lowercase by construction** — see [BridgeCatalog.freeze] — because there is exactly one
 * spelling of a tool's identity and the Server refuses a catalog that could produce two.
 */
data class FrozenToolEntry(
    val name: String,
    val bridgedName: String,
    val displayName: String,
    val description: String,
    val inputSchema: JsonElement,
    val schemaDigest: String,
    val readOnly: Boolean,
    val source: ToolSource,
)

/** The frozen catalog. `entries` is sorted by `name`, whatever order was supplied. */
data class FrozenCatalog(
    val entries: List<FrozenToolEntry>,
    val digest: String,
) {
    val isEmpty: Boolean get() = entries.isEmpty()

    /**
     * The frozen entry with this name, or `null`.
     *
     * An exact match against the frozen set, and the lookup the invocation path uses. There is
     * no prefix search, no case folding and no alias: the mapping from a bridged name back to
     * an entry is injective because [BridgeCatalog.freeze] refuses a catalog in which two
     * names would collide.
     */
    fun findEntry(name: String): FrozenToolEntry? = entries.firstOrNull { it.name == name }

    /** The frozen entry a bridged tool name refers to, or `null` when it names none. */
    fun findEntryByBridgedName(bridged: String): FrozenToolEntry? {
        val frozen = BridgeContract.frozenNameOf(bridged) ?: return null
        if (frozen.isEmpty()) return null
        return findEntry(frozen)
    }
}

/**
 * The frozen tool catalog, built by Android and checked by the Server.
 *
 * ## Why every rule refuses instead of normalizing
 *
 * A repaired catalog is a catalog the two ends disagree about. If this side trimmed an
 * over-long description, the digest Android computed and the digest the Server computed would
 * differ, and the disagreement would surface later as an unexplained `idempotency_conflict` at
 * call time. Refusing at the boundary keeps one definition of "the catalog" — the one Android
 * sent, or none at all.
 *
 * ## The frozen order is the sorted order, and this is a protocol requirement
 *
 * Entries are sorted by frozen name in code-unit order, and the digest is computed over that
 * order. It is **not** the order Android supplied.
 *
 * Two peers that agree on the *set* of tools must agree on its digest, or a benign reordering —
 * an allowlist that happens to iterate differently after an Android restart — becomes a
 * mismatch the Server cannot explain. Sorting by name is the one order both sides can compute
 * from the data alone without knowing the other's iteration order.
 *
 * ## What this does not do
 *
 * It does not decide whether a tool is allowed, whether it is a write, or what it does.
 * `readOnly` is an **assertion made by Android** and is carried into the digest so it cannot be
 * changed silently; the Server never concludes anything from it, and neither does the catalog.
 * The approval decision is made by the existing `ToolExecutionGate` at execution time, not
 * here.
 */
object BridgeCatalog {

    /** An empty catalog: the text path, which must stay byte-identical to M1. */
    val EMPTY: FrozenCatalog by lazy {
        val entries = emptyList<FrozenToolEntry>()
        FrozenCatalog(entries, catalogDigestOf(entries))
    }

    /** The exact key set of a catalog entry. Closed — a key outside it is refused. */
    private val ENTRY_KEYS = setOf(
        "name", "displayName", "description", "inputSchema", "readOnly", "source",
    )

    /** The exact key set of the request body's catalog member. */
    private val CATALOG_KEYS = setOf("tools")

    /** The frozen-identity payload's key set, as it appears in the digest. */
    private val DIGEST_ENTRY_KEYS = listOf(
        "name", "display_name", "description", "schema_digest", "read_only", "source",
    )

    /** Lowercase `[a-z0-9_-]`, starting with a letter. Bounded by the caller. */
    private val RAW_NAME = Regex("^[A-Za-z][A-Za-z0-9_-]*$")

    /**
     * Names the bridge's own surface must not be shadowed by.
     *
     * **Deliberately not the list of Claude Code built-ins.** A frozen name never appears
     * unqualified — Claude sees `mcp__rikkahub_bridge__<name>` — so a catalog tool called
     * `read` or `grep` cannot collide with the built-in of the same name, and reserving those
     * names would reject a legitimate Local or MCP tool while adding no safety at all. What
     * actually keeps the built-ins out of a bridged generation is the Worker's `--allowedTools`
     * and its deny list, which are the surfaces that can express "this tool is not offered".
     *
     * What remains is the one thing that genuinely would be confusing: a tool that shares the
     * bridge's own name.
     */
    private val RESERVED_NAMES = setOf("rikkahub_bridge", "bridge")

    /** The digest of a frozen entry list: the catalog identity. */
    fun catalogDigestOf(entries: List<FrozenToolEntry>): String {
        val payload = JsonObject(
            linkedMapOf(
                "bridge_abi" to JsonPrimitive(BridgeContract.BRIDGE_ABI),
                "server" to JsonPrimitive(BridgeContract.BRIDGE_SERVER_NAME),
                "tools" to JsonArray(
                    entries.map { entry ->
                        JsonObject(
                            linkedMapOf(
                                "name" to JsonPrimitive(entry.name),
                                "display_name" to JsonPrimitive(entry.displayName),
                                "description" to JsonPrimitive(entry.description),
                                "schema_digest" to JsonPrimitive(entry.schemaDigest),
                                "read_only" to JsonPrimitive(entry.readOnly),
                                "source" to JsonPrimitive(entry.source.wire),
                            ),
                        )
                    },
                ),
            ),
        )
        return BridgeCanonical.digestOf(payload)
    }

    /**
     * Validates and freezes a catalog body, as it appears in `generation.start.tool_snapshot`.
     *
     * @throws BridgeRejected with a reason from the closed set. Never repairs, never trims.
     */
    fun freeze(raw: JsonElement): FrozenCatalog {
        if (raw !is JsonObject) throw BridgeRejected(BridgeRejection.CATALOG_FIELD_INVALID)
        for (key in raw.keys) {
            if (key !in CATALOG_KEYS) throw BridgeRejected(BridgeRejection.CATALOG_FIELD_UNKNOWN)
        }

        // Absent, null and empty all mean the same thing, and it is **not** an error: an
        // assistant with no tools runs the text path, which creates no bridge, no broker and no
        // MCP config at all. Refusing it here would make the text path unreachable.
        val toolsElement = raw["tools"]
        if (toolsElement == null || toolsElement is JsonNull) return EMPTY
        if (toolsElement !is JsonArray) throw BridgeRejected(BridgeRejection.CATALOG_FIELD_INVALID)
        if (toolsElement.isEmpty()) return EMPTY
        if (toolsElement.size > BridgeLimits.MAX_TOOLS) {
            throw BridgeRejected(BridgeRejection.CATALOG_TOO_MANY_TOOLS)
        }

        val entries = mutableListOf<FrozenToolEntry>()
        val seenRaw = mutableSetOf<String>()
        val seenFrozen = mutableSetOf<String>()

        for (tool in toolsElement) {
            val entry = freezeEntry(tool)

            // Two distinct reasons, and the difference is worth keeping: `MyTool` colliding
            // with `mytool` is a *normalization* collision a caller should fix by renaming,
            // while the same name sent twice is a caller that built its list wrong. Both are
            // refused — what makes the frozen name a sound identity is that neither is aliased.
            val rawName = (tool as? JsonObject)?.get("name")?.let { (it as? JsonPrimitive)?.content }
            if (rawName != null) {
                if (!seenRaw.add(rawName)) {
                    throw BridgeRejected(BridgeRejection.CATALOG_DUPLICATE_NAME)
                }
            }
            if (!seenFrozen.add(entry.name)) {
                throw BridgeRejected(BridgeRejection.CATALOG_NAME_COLLISION)
            }
            entries.add(entry)
        }

        // Sorted, so the digest is a function of the tool *set* and not of the order it
        // arrived in. `sortedBy` is `String.compareTo` — UTF-16 code-unit order.
        val sorted = entries.sortedBy { it.name }

        if (exceedsCatalogBound(sorted)) {
            throw BridgeRejected(BridgeRejection.CATALOG_TOO_LARGE)
        }

        return FrozenCatalog(sorted, catalogDigestOf(sorted))
    }

    /** True when the canonical entries are over the catalog bound, in this module's terms. */
    private fun exceedsCatalogBound(entries: List<FrozenToolEntry>): Boolean {
        val canonical = try {
            BridgeCanonical.canonicalize(
                JsonArray(
                    entries.map { entry ->
                        JsonObject(
                            linkedMapOf(
                                "name" to JsonPrimitive(entry.name),
                                "bridgedName" to JsonPrimitive(entry.bridgedName),
                                "displayName" to JsonPrimitive(entry.displayName),
                                "description" to JsonPrimitive(entry.description),
                                "inputSchema" to entry.inputSchema,
                                "schemaDigest" to JsonPrimitive(entry.schemaDigest),
                                "readOnly" to JsonPrimitive(entry.readOnly),
                                "source" to JsonPrimitive(entry.source.wire),
                            ),
                        )
                    },
                ),
            )
        } catch (error: CanonicalRejected) {
            // Every field has already been validated as canonicalizable and the nesting is
            // bounded by the schema depth rule, so the only canonical bound these entries can
            // still trip is size.
            if (error.reason == CanonicalRejection.SIZE_EXCEEDED) return true
            throw error
        }
        return canonical.toByteArray(Charsets.UTF_8).size > BridgeLimits.MAX_CATALOG_BYTES
    }

    private fun freezeEntry(raw: JsonElement): FrozenToolEntry {
        if (raw !is JsonObject) throw BridgeRejected(BridgeRejection.CATALOG_FIELD_INVALID)
        for (key in raw.keys) {
            if (key !in ENTRY_KEYS) throw BridgeRejected(BridgeRejection.CATALOG_FIELD_UNKNOWN)
        }

        val rawName = raw.string("name")
        if (rawName == null || !RAW_NAME.matches(rawName)) {
            throw BridgeRejected(BridgeRejection.CATALOG_NAME_INVALID)
        }
        val normalized = rawName.lowercase()
        if (normalized.toByteArray(Charsets.UTF_8).size > BridgeLimits.MAX_TOOL_NAME_BYTES) {
            throw BridgeRejected(BridgeRejection.CATALOG_NAME_INVALID)
        }
        if (normalized in RESERVED_NAMES) {
            throw BridgeRejected(BridgeRejection.CATALOG_NAME_RESERVED)
        }

        val displayName = raw.string("displayName")
        if (displayName.isNullOrEmpty()) throw BridgeRejected(BridgeRejection.CATALOG_FIELD_INVALID)
        if (displayName.toByteArray(Charsets.UTF_8).size > BridgeLimits.MAX_DISPLAY_NAME_BYTES) {
            throw BridgeRejected(BridgeRejection.CATALOG_FIELD_INVALID)
        }

        val description = raw.string("description")
            ?: throw BridgeRejected(BridgeRejection.CATALOG_FIELD_INVALID)
        if (description.toByteArray(Charsets.UTF_8).size > BridgeLimits.MAX_DESCRIPTION_BYTES) {
            throw BridgeRejected(BridgeRejection.DESCRIPTION_TOO_LARGE)
        }

        val readOnlyPrimitive = raw["readOnly"] as? JsonPrimitive
        if (readOnlyPrimitive == null || readOnlyPrimitive.isString) {
            throw BridgeRejected(BridgeRejection.CATALOG_FIELD_INVALID)
        }
        val readOnly = when (readOnlyPrimitive.content) {
            "true" -> true
            "false" -> false
            else -> throw BridgeRejected(BridgeRejection.CATALOG_FIELD_INVALID)
        }

        val source = ToolSource.fromWire(raw.string("source"))
            ?: throw BridgeRejected(BridgeRejection.CATALOG_FIELD_INVALID)

        val schema = raw["inputSchema"] ?: JsonNull
        validateSchema(schema)

        // **No content scan.** A catalog entry's name, description and schema are things a tool
        // *says* about itself, and this side does not read them as a credential policy: a tool
        // whose description documents a token format, or whose schema carries a `token`
        // property, is an ordinary tool, and refusing it would take it off the phone while
        // protecting nothing. What bounds this content is structural — the key set, the depth,
        // the node count and the byte limits — and the isolation is architectural: the bridge
        // holds no Android MCP credential and no VPS component receives one.
        val schemaDigest = try {
            val canonical = BridgeCanonical.canonicalize(schema)
            if (canonical.toByteArray(Charsets.UTF_8).size > BridgeLimits.MAX_SCHEMA_BYTES) {
                throw BridgeRejected(BridgeRejection.SCHEMA_TOO_LARGE)
            }
            BridgeCanonical.sha256Hex(canonical)
        } catch (error: CanonicalRejected) {
            throw BridgeRejected(BridgeRejection.SCHEMA_INVALID)
        }

        return FrozenToolEntry(
            name = normalized,
            bridgedName = BridgeContract.bridgedToolName(normalized),
            displayName = displayName,
            description = description,
            inputSchema = schema,
            schemaDigest = schemaDigest,
            readOnly = readOnly,
            source = source,
        )
    }

    /** True for a value that can be a schema: an object, or a boolean. */
    private fun isSchemaValue(value: JsonElement): Boolean =
        value is JsonObject || value is JsonPrimitive && !value.isString &&
            (value.content == "true" || value.content == "false")

    /**
     * Validates an input schema as a bounded JSON Schema.
     *
     * ## What is checked, and what is deliberately left alone
     *
     * Neither end executes a schema or interprets keywords, so the checks are the ones that are
     * true of *every* usable tool schema and therefore cannot reject a legitimate one:
     *
     * - it is a plain JSON object, inside the depth, node-count, string-length and byte bounds;
     * - every `properties` member is a schema (an object, or a boolean subschema);
     * - `required`, when present, is an array of distinct non-empty strings.
     *
     * What is **not** checked is anything about argument *names*, or about the content of the
     * strings a schema carries. `path`, `env`, `endpoint` and `header` are ordinary property
     * names of ordinary tools; a `default` documenting a token format is a description, not a
     * credential.
     */
    fun validateSchema(schema: JsonElement) {
        if (schema !is JsonObject) throw BridgeRejected(BridgeRejection.SCHEMA_INVALID)

        val counter = intArrayOf(0)
        walkSchema(schema, 0, counter)
    }

    /**
     * Validates a schema and returns its digest, or `null` when no frozen entry could carry it.
     *
     * Exposed for [BridgeToolCatalog], which needs exactly this answer *before* it builds a
     * catalog body — an unencodable schema must drop one tool, not refuse the whole generation.
     */
    fun validateAndDigestSchema(schema: JsonElement): String? = try {
        validateSchema(schema)
        val canonical = BridgeCanonical.canonicalize(schema)
        if (canonical.toByteArray(Charsets.UTF_8).size > BridgeLimits.MAX_SCHEMA_BYTES) {
            null
        } else {
            BridgeCanonical.sha256Hex(canonical)
        }
    } catch (error: BridgeRejected) {
        null
    } catch (error: CanonicalRejected) {
        null
    }

    private fun walkSchema(node: JsonElement, depth: Int, counter: IntArray) {
        counter[0] += 1
        if (counter[0] > BridgeLimits.MAX_SCHEMA_NODES) {
            throw BridgeRejected(BridgeRejection.SCHEMA_TOO_MANY_NODES)
        }
        if (depth > BridgeLimits.MAX_SCHEMA_DEPTH) {
            throw BridgeRejected(BridgeRejection.SCHEMA_TOO_DEEP)
        }

        when (node) {
            is JsonNull -> return
            is JsonPrimitive -> {
                if (node.isString &&
                    node.content.toByteArray(Charsets.UTF_8).size > BridgeLimits.MAX_SCHEMA_STRING_BYTES
                ) {
                    throw BridgeRejected(BridgeRejection.SCHEMA_STRING_TOO_LONG)
                }
                return
            }
            is JsonArray -> {
                for (item in node) walkSchema(item, depth + 1, counter)
                return
            }
            is JsonObject -> {
                for ((key, value) in node) {
                    if (key.toByteArray(Charsets.UTF_8).size > BridgeLimits.MAX_SCHEMA_STRING_BYTES) {
                        throw BridgeRejected(BridgeRejection.SCHEMA_STRING_TOO_LONG)
                    }

                    if (key == "properties") {
                        if (value !is JsonObject) throw BridgeRejected(BridgeRejection.SCHEMA_INVALID)
                        for ((propertyName, propertySchema) in value) {
                            if (propertyName.isEmpty()) {
                                throw BridgeRejected(BridgeRejection.SCHEMA_INVALID)
                            }
                            if (!isSchemaValue(propertySchema)) {
                                throw BridgeRejected(BridgeRejection.SCHEMA_INVALID)
                            }
                        }
                        // Recursed generically, so the `properties` object is counted and
                        // depth-checked like any other member rather than being a hole in both
                        // bounds.
                        walkSchema(value, depth + 1, counter)
                        continue
                    }

                    if (key == "required") {
                        if (value !is JsonArray) throw BridgeRejected(BridgeRejection.SCHEMA_INVALID)
                        val seen = mutableSetOf<String>()
                        for (item in value) {
                            val name = (item as? JsonPrimitive)?.takeIf { it.isString }?.content
                            if (name.isNullOrEmpty()) {
                                throw BridgeRejected(BridgeRejection.SCHEMA_INVALID)
                            }
                            if (!seen.add(name)) throw BridgeRejected(BridgeRejection.SCHEMA_INVALID)
                        }
                        walkSchema(value, depth + 1, counter)
                        continue
                    }

                    walkSchema(value, depth + 1, counter)
                }
            }
        }
    }
}

/** A string member, or `null` when absent or not a string. */
internal fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
