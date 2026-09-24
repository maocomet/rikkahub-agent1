package me.rerere.ai.provider.claudep.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Holds the Kotlin half of the M2 bridge contract to the corpus the Server authored.
 *
 * The corpus in `claudep/conformance-bridge/vectors/` is vendored byte-for-byte from
 * `rikkahub-claude-p-server`'s `test/bridge/vectors/`. That repository is the authoritative
 * source: its own README says the vectors "will be vendored *into* maocomet/rikkahub-agent1
 * when M2-B implements the Kotlin half", and that a consumer should verify the manifest, check
 * `format_version`, and then run every accepted vector through its own implementation.
 *
 * This is the Kotlin side of that agreement. A failure here is a **real divergence**, and
 * which of the two implementations is wrong is a judgement only a person can make — which is
 * exactly why the manifest is never regenerated to make a build green.
 *
 * ## Why byte comparison rather than value comparison
 *
 * Every rule the corpus exists to pin is a rule about *bytes*: a length prefix that counts
 * UTF-16 code units in one implementation and UTF-8 bytes in the other, a `null` and an `""`
 * collapsing, an unpaired surrogate surviving a round trip. None of those are visible in a
 * parsed value. So a canonical vector carries `canonical`, the exact expected output as a
 * string, and `sha256`, and neither is implied.
 */
class BridgeConformanceCorpusTest {

    private val json = Json { ignoreUnknownKeys = true }

    // -----------------------------------------------------------------------------------------
    // Corpus location and integrity
    // -----------------------------------------------------------------------------------------

    /**
     * Walks up from the working directory looking for the vendored corpus.
     *
     * Resolved by search rather than by a fixed `../` because the unit-test working directory
     * differs between running the `:ai` module and running the whole build.
     */
    private fun corpusRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "claudep/conformance-bridge/vectors")
            if (File(candidate, "MANIFEST.sha256").isFile) return candidate
            // Stop at the checkout root rather than walking into the parent filesystem.
            if (File(dir, ".git").exists()) break
            dir = dir.parentFile
        }
        throw AssertionError(
            "Could not locate claudep/conformance-bridge/vectors from " +
                "${System.getProperty("user.dir")}. This test must run from inside the " +
                "rikkahub-agent1 checkout.",
        )
    }

    private fun readJson(name: String): JsonObject =
        json.parseToJsonElement(File(corpusRoot(), name).readText()).jsonObject

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonArray.objects(): List<JsonObject> = map { it as JsonObject }

    private fun reasonOf(block: () -> Unit): String? = try {
        block()
        null
    } catch (e: BridgeRejected) {
        e.reason.wire
    } catch (e: CanonicalRejected) {
        e.reason.wire
    }

    private fun formatVersionIsOne(root: JsonObject) {
        assertEquals(
            "the corpus envelope this build implements is format_version 1",
            "1",
            (root["format_version"] as? JsonPrimitive)?.content,
        )
    }

    // -----------------------------------------------------------------------------------------

    /**
     * Every listed file is present and unchanged, and no file is present that the manifest
     * does not list.
     *
     * The second half matters as much as the first: a vector added without regenerating the
     * manifest would otherwise be silently skipped by every consumer, so the corpus would
     * appear to cover less than it does while reporting success.
     */
    @Test
    fun `manifest matches every file in the vendored corpus`() {
        val root = corpusRoot()
        val manifest = File(root, "MANIFEST.sha256").readLines().filter { it.isNotBlank() }
        assertTrue("manifest must not be empty", manifest.isNotEmpty())

        val listed = mutableSetOf<String>()
        for (line in manifest) {
            val parts = line.trim().split(Regex("\\s+"), limit = 2)
            assertEquals("malformed manifest line: $line", 2, parts.size)
            val (expectedSha, relativePath) = parts
            listed += relativePath

            val target = File(root, relativePath)
            assertTrue("manifest names a missing file: $relativePath", target.isFile)
            assertEquals(
                "digest mismatch for $relativePath — the vendored copy has moved, and the " +
                    "server copy is authoritative",
                expectedSha,
                sha256Hex(target.readBytes()),
            )
        }

        val onDisk = root.walkTopDown()
            .filter { it.isFile }
            .filter { it.name != "MANIFEST.sha256" }
            .map { it.relativeTo(root).path.replace(File.separatorChar, '/') }
            .toSet()

        assertEquals(
            "a corpus file exists that the manifest does not list",
            listed.sorted(),
            onDisk.sorted(),
        )
    }

    /**
     * Canonical JSON: the exact bytes a second implementation must reproduce.
     *
     * The rules the accepted vectors pin are the ones a host language gets wrong by default —
     * code-unit key order rather than locale or code-point order, `-0` refused rather than
     * rendered `0`, an unpaired surrogate escaped while a well-formed astral pair is emitted
     * raw, and numbers in ECMAScript's shortest round-tripping form, which disagrees with
     * `Double.toString` at both threshold exponents.
     */
    @Test
    fun `canonical vectors reproduce the exact bytes and digests`() {
        val root = readJson("canonical.json")
        formatVersionIsOne(root)

        for (vector in root["accepted"]!!.let { it as JsonArray }.objects()) {
            val id = vector.str("id")!!
            val produced = BridgeCanonical.canonicalizeText(vector.str("inputText")!!)
            assertEquals("canonical/$id bytes", vector.str("canonical"), produced)
            assertEquals("canonical/$id sha256", vector.str("sha256"), BridgeCanonical.sha256Hex(produced))
        }

        for (vector in (root["rejected"] as JsonArray).objects()) {
            val id = vector.str("id")!!
            assertEquals(
                "canonical-rejected/$id",
                vector.str("reason"),
                reasonOf { BridgeCanonical.canonicalizeText(vector.str("inputText")!!) },
            )
        }
    }

    /**
     * The frozen catalog: name normalization, the closed key sets, the sort order, and the
     * digest computed over that order rather than over the order supplied.
     */
    @Test
    fun `catalog vectors freeze to the recorded digest and names`() {
        val root = readJson("catalog.json")
        formatVersionIsOne(root)

        assertEquals(
            "the empty catalog digest is a protocol constant, not a computed coincidence",
            root.str("emptyCatalogDigest"),
            BridgeCatalog.EMPTY.digest,
        )

        for (vector in (root["accepted"] as JsonArray).objects()) {
            val id = vector.str("id")!!
            val frozen = BridgeCatalog.freeze(vector["catalog"] as JsonObject)
            assertEquals("catalog/$id digest", vector.str("digest"), frozen.digest)
            assertEquals(
                "catalog/$id names",
                (vector["names"] as JsonArray).map { it.jsonPrimitive.content },
                frozen.entries.map { it.name },
            )
            assertEquals(
                "catalog/$id bridgedNames",
                (vector["bridgedNames"] as JsonArray).map { it.jsonPrimitive.content },
                frozen.entries.map { it.bridgedName },
            )
        }

        for (vector in (root["rejected"] as JsonArray).objects()) {
            val id = vector.str("id")!!
            assertEquals(
                "catalog-rejected/$id",
                vector.str("reason"),
                reasonOf { BridgeCatalog.freeze(vector["catalog"] as JsonObject) },
            )
        }
    }

    /**
     * Bindings and argument digests, including the ledger key — which is the tool call id and
     * nothing else, and is the reason a repeat under a different generation is a conflict
     * rather than a second execution.
     */
    @Test
    fun `binding and argument vectors digest identically`() {
        val root = readJson("bindings.json")
        formatVersionIsOne(root)

        for (vector in (root["accepted"] as JsonArray).objects()) {
            val id = vector.str("id")!!
            BridgeBinding.validateGenerationBinding(vector["generation"] as JsonObject)
            val binding = BridgeBinding.validateInvocationBinding(vector["binding"] as JsonObject)
            vector.str("invocationDigest")?.let {
                assertEquals("bindings/$id digest", it, BridgeBinding.invocationDigest(binding))
            }
            vector.str("invocationKey")?.let {
                assertEquals(
                    "bindings/$id key",
                    it,
                    BridgeBinding.invocationKey(binding.toolCallId),
                )
            }
        }

        for (vector in (root["rejected"] as JsonArray).objects()) {
            val id = vector.str("id")!!
            assertEquals(
                "bindings-rejected/$id",
                vector.str("reason"),
                reasonOf { BridgeBinding.validateInvocationBinding(vector["binding"] as JsonObject) },
            )
        }

        val arguments = root["arguments"] as JsonObject
        for (vector in (arguments["accepted"] as JsonArray).objects()) {
            val id = vector.str("id")!!
            val produced = BridgeArguments.validate(vector["args"]!!)
            assertEquals("arguments/$id canonical", vector.str("canonical"), produced.canonical)
            assertEquals("arguments/$id digest", vector.str("digest"), produced.digest)
        }
        for (vector in (arguments["rejected"] as JsonArray).objects()) {
            val id = vector.str("id")!!
            assertEquals(
                "arguments-rejected/$id",
                vector.str("reason"),
                reasonOf { BridgeArguments.validate(vector["args"]!!) },
            )
        }
    }

    /**
     * The idempotency rules: what a repeat means, what one exact id answers with, and which
     * answers Android may give at all.
     *
     * The vector `expect` values here are hand-written in the Server's generator rather than
     * derived from its implementation, which makes this family the specification of the rules
     * with the vectors as instances.
     */
    @Test
    fun `lifecycle vectors pin the idempotency rules`() {
        val root = readJson("lifecycle.json")
        formatVersionIsOne(root)

        for (vector in (root["decisions"] as JsonArray).objects()) {
            val id = vector.str("id")!!
            val binding = BridgeBinding.validateInvocationBinding(vector["binding"] as JsonObject)
            val existing = recordOf(vector["record"])
            assertEquals(
                "decisions/$id",
                vector.str("decision"),
                BridgeRules.decide(existing, binding).name.lowercase(),
            )
        }

        val queries = root["queries"] as JsonObject
        for (vector in (queries["accepted"] as JsonArray).objects()) {
            val id = vector.str("id")!!
            val outcome = BridgeRules.resolveQuery(recordOf(vector["record"]), vector.str("toolCallId"))
            assertEquals("queries/$id state", vector.str("state"), outcome.state.wire)
            assertEquals("queries/$id body", vector.str("body"), outcome.body)
        }
        for (vector in (queries["rejected"] as JsonArray).objects()) {
            val id = vector.str("id")!!
            assertEquals(
                "queries-rejected/$id",
                vector.str("reason"),
                reasonOf {
                    BridgeRules.resolveQuery(recordOf(vector["record"]), vector.str("toolCallId"))
                },
            )
        }

        val answers = root["answers"] as JsonObject
        for (vector in (answers["accepted"] as JsonArray).objects()) {
            val id = vector.str("id")!!
            val record = recordOf(vector["record"])!!
            val outcome = BridgeOutcomes.validate(vector["outcome"] as JsonObject)
            val result = vector["result"] as JsonObject
            val updated = BridgeRules.applyOutcome(record, outcome)
            assertEquals("answers/$id state", result.str("state"), updated.state.wire)
            assertEquals("answers/$id body", result.str("body"), updated.body)
        }
        for (vector in (answers["rejected"] as JsonArray).objects()) {
            val id = vector.str("id")!!
            val record = recordOf(vector["record"])
            assertEquals(
                "answers-rejected/$id",
                vector.str("reason"),
                reasonOf {
                    val outcome = BridgeOutcomes.validate(vector["outcome"] as JsonObject)
                    if (record != null) BridgeRules.applyOutcome(record, outcome)
                },
            )
        }
    }

    private fun recordOf(node: JsonElement?): InvocationRecord? {
        if (node == null || node is JsonNull) return null
        val o = node as JsonObject
        return InvocationRecord(
            key = o.str("key")!!,
            toolCallId = o.str("toolCallId")!!,
            invocationDigest = o.str("invocationDigest")!!,
            state = ToolCallState.fromWire(o.str("state"))!!,
            body = o.str("body"),
            expiresAtMs = o["expiresAtMs"]!!.jsonPrimitive.content.toLong(),
        )
    }
}
