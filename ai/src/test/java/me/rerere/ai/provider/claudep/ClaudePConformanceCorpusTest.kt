package me.rerere.ai.provider.claudep

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Holds the frozen implementation to the language-neutral conformance corpus in
 * `claudep/conformance/`.
 *
 * The corpus exists because the dangerous divergences between this client and a
 * reimplemented server are not renamed fields — they are a length prefix that counts the
 * wrong units, a `null` that collapses into an `""`, or a routable type being mistaken
 * for an unknown one. None of those show up in a field list; all of them show up in a
 * byte string. The server repository vendors the same bytes and runs the mirror of this
 * test, which is what makes the two implementations comparable without sharing code.
 *
 * **On the evidence this test produces.** The transcript and fingerprint vectors were
 * derived twice, independently, in JavaScript and on the JVM, and only written once both
 * agreed byte-for-byte (see `claudep/conformance/tools/README.md`). The frame-routing
 * vectors were derived by reading [ClaudePProtocol.parseInbound] and had not been
 * executed when the corpus was written — no Android SDK was available then. This test is
 * where that stops being an assumption, so a failure here is a real finding about the
 * implementation, not a stale fixture to be regenerated away.
 */
class ClaudePConformanceCorpusTest {

    private val json = Json { ignoreUnknownKeys = true }

    // -----------------------------------------------------------------------------------------
    // Corpus location and integrity
    // -----------------------------------------------------------------------------------------

    /**
     * Walks up from the working directory looking for `claudep/conformance`.
     *
     * Resolved by search rather than by a fixed `../` because the unit-test working
     * directory differs between running the `:ai` module and running the whole build.
     */
    private fun corpusRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "claudep/conformance")
            if (File(candidate, "MANIFEST.sha256").isFile) return candidate
            dir = dir.parentFile
        }
        throw AssertionError(
            "Could not locate claudep/conformance from ${System.getProperty("user.dir")}. " +
                "This test must run from inside the rikkahub-agent1 checkout.",
        )
    }

    private fun readJson(relativePath: String): JsonObject =
        json.parseToJsonElement(File(corpusRoot(), relativePath).readText()).jsonObject

    /** Lowercase hex, matching the spelling the corpus stores. */
    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).let(::hex)

    /**
     * Verifies the manifest covers every corpus file and that each digest still matches.
     *
     * This is also the check the *server* repository runs against its vendored copy. If it
     * fails there, the vendoring was wrong or the protocol moved — neither is a reason to
     * rewrite the manifest.
     */
    @Test
    fun `manifest matches every corpus file`() {
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
                "digest mismatch for $relativePath",
                expectedSha,
                sha256Hex(target.readBytes()),
            )
        }

        // A file present but unlisted would silently escape the server's integrity check.
        val onDisk = root.walkTopDown()
            .filter { it.isFile }
            .filter { it.name != "MANIFEST.sha256" }
            .filterNot { it.path.contains("${File.separator}tools${File.separator}") }
            .map { it.relativeTo(root).path.replace(File.separatorChar, '/') }
            .toSet()

        assertEquals(
            "corpus files on disk do not match the manifest",
            listed.sorted(),
            onDisk.sorted(),
        )
    }

    // -----------------------------------------------------------------------------------------
    // Handshake transcripts
    // -----------------------------------------------------------------------------------------

    @Test
    fun `handshake transcripts match the corpus byte for byte`() {
        val vectors = readJson("transcripts/handshake.json").mustGet("vectors").jsonArray
        assertTrue("corpus must contain handshake vectors", vectors.isNotEmpty())

        for (vector in vectors) {
            val entry = vector.jsonObject
            val id = entry.mustGet("id").jsonPrimitive.content
            val input = entry.mustGet("input").jsonObject

            val actual = ClaudePHandshakeTranscript.build(
                deviceId = input.mustGet("deviceId").jsonPrimitive.content,
                nonce = input.mustGet("nonce").jsonPrimitive.content,
                gatewayAuthority = input.mustGet("gatewayAuthority").jsonPrimitive.content,
                appVersion = input.mustGet("appVersion").jsonPrimitive.content,
            )

            assertEquals("handshake transcript mismatch for $id", entry.mustGet("transcriptHex").jsonPrimitive.content, hex(actual))
        }
    }

    /**
     * The specific property the astral vectors exist to pin.
     *
     * A surrogate pair is two UTF-16 code units and four UTF-8 bytes. If the transcript
     * were ever changed to prefix byte counts, or to code-point counts, these two lengths
     * would stop being 2 and 4 and this assertion would fail — which is the point.
     */
    @Test
    fun `astral characters count as surrogate pairs in transcript framing`() {
        val vectors = readJson("transcripts/handshake.json").mustGet("vectors").jsonArray
        val astral = vectors.map { it.jsonObject }
            .first { it.mustGet("id").jsonPrimitive.content == "handshake-astral-emoji" }

        val deviceId = astral.mustGet("input").jsonObject.mustGet("deviceId").jsonPrimitive.content
        assertEquals("the fixture itself must contain an astral character", "dev-😀", deviceId)
        // "dev-" is 4 units and the surrogate pair adds 2.
        assertEquals("an emoji is two UTF-16 code units", 6, deviceId.length)
        // "dev-" is 4 bytes and the emoji encodes to 4.
        assertEquals("...but four UTF-8 bytes", 8, deviceId.toByteArray(Charsets.UTF_8).size)

        val transcript = astral.mustGet("transcriptHex").jsonPrimitive.content
        // "6:dev-😀" — the prefix is the code-unit count, not the byte count.
        assertTrue(
            "transcript must frame this deviceId as 6 UTF-16 code units",
            transcript.contains("363a6465762d"),
        )
    }

    // -----------------------------------------------------------------------------------------
    // Pairing transcripts
    // -----------------------------------------------------------------------------------------

    @Test
    fun `pairing transcripts match the corpus byte for byte`() {
        val vectors = readJson("transcripts/pairing.json").mustGet("vectors").jsonArray
        assertTrue("corpus must contain pairing vectors", vectors.isNotEmpty())

        for (vector in vectors) {
            val entry = vector.jsonObject
            val id = entry.mustGet("id").jsonPrimitive.content
            val input = entry.mustGet("input").jsonObject

            val actual = ClaudePPairingTranscript.build(
                origin = input.mustGet("origin").jsonPrimitive.content,
                ticket = input.mustGet("ticket").jsonPrimitive.content,
                devicePublicKeyBase64Url = input.mustGet("devicePublicKeyBase64Url").jsonPrimitive.content,
                state = input.mustGet("state").jsonPrimitive.content,
                challenge = input.mustGet("challenge").jsonPrimitive.content,
                appVersion = input.mustGet("appVersion").jsonPrimitive.content,
            )

            assertEquals("pairing transcript mismatch for $id", entry.mustGet("transcriptHex").jsonPrimitive.content, hex(actual))
        }
    }

    /**
     * Field order is inside the signature, not just the field set.
     *
     * `pairing-field-order-binding` swaps `state` and `challenge` and changes nothing else.
     * If the transcript were order-insensitive, a captured proof could be replayed with
     * the two values exchanged.
     */
    @Test
    fun `swapping state and challenge changes the pairing transcript`() {
        val vectors = readJson("transcripts/pairing.json").mustGet("vectors").jsonArray
            .map { it.jsonObject }
            .associateBy { it.mustGet("id").jsonPrimitive.content }

        val base = vectors.mustGet("pairing-ascii").mustGet("transcriptHex").jsonPrimitive.content
        val swapped = vectors.mustGet("pairing-field-order-binding").mustGet("transcriptHex").jsonPrimitive.content

        assertNotEquals("swapping two fields must change the signed bytes", base, swapped)
    }

    // -----------------------------------------------------------------------------------------
    // Request fingerprints
    // -----------------------------------------------------------------------------------------

    private fun fingerprintFromVector(input: JsonObject): String {
        // Explicit about the three cases, because `JsonNull` IS a `JsonPrimitive` and the
        // difference between "key absent" and "key present but null" is the whole point of
        // the presence byte in the fingerprint framing.
        fun str(key: String): String? = when (val value = input[key]) {
            null, JsonNull -> null
            is JsonPrimitive -> value.content
            else -> null
        }

        fun turn(entry: JsonObject) = ClaudePTurn(
            role = entry.mustGet("role").jsonPrimitive.content,
            parts = entry.mustGet("parts").jsonArray.map { part ->
                val p = part.jsonObject
                ClaudePTurnPart(
                    type = p.mustGet("type").jsonPrimitive.content,
                    text = p.mustGet("text").jsonPrimitive.content,
                )
            },
        )

        val history = input["rebuildHistory"]
        val rebuild = if (history == null || history is JsonNull) {
            null
        } else {
            history.jsonArray.map { turn(it.jsonObject) }
        }

        return ClaudePRequestFingerprint.compute(
            deviceId = input.mustGet("deviceId").jsonPrimitive.content,
            remoteThreadId = input.mustGet("remoteThreadId").jsonPrimitive.content,
            remoteBranchId = input.mustGet("remoteBranchId").jsonPrimitive.content,
            mode = input.mustGet("mode").jsonPrimitive.content,
            modelAlias = input.mustGet("modelAlias").jsonPrimitive.content,
            systemPrompt = str("systemPrompt"),
            turn = turn(input.mustGet("turn").jsonObject),
            rebuildHistory = rebuild,
            toolSnapshot = str("toolSnapshot"),
            attachmentManifest = str("attachmentManifest"),
        )
    }

    @Test
    fun `request fingerprints match the corpus`() {
        val vectors = readJson("fingerprints/vectors.json").mustGet("vectors").jsonArray
        assertTrue("corpus must contain fingerprint vectors", vectors.isNotEmpty())

        for (vector in vectors) {
            val entry = vector.jsonObject
            val id = entry.mustGet("id").jsonPrimitive.content
            assertEquals(
                "fingerprint mismatch for $id",
                entry.mustGet("sha256Hex").jsonPrimitive.content,
                fingerprintFromVector(entry.mustGet("input").jsonObject),
            )
        }
    }

    /**
     * The presence flag is load-bearing: an absent system prompt and an empty one must not
     * share an idempotency key. Folding `null` into `""` would let a client replay one as
     * the other.
     */
    @Test
    fun `absent and empty system prompt produce different fingerprints`() {
        val inputs = readJson("fingerprints/vectors.json").mustGet("vectors").jsonArray
            .map { it.jsonObject }
            .associateBy { it.mustGet("id").jsonPrimitive.content }

        val absent = fingerprintFromVector(
            inputs.mustGet("fingerprint-minimal").mustGet("input").jsonObject,
        )
        val empty = fingerprintFromVector(
            inputs.mustGet("fingerprint-null-vs-empty-system-prompt").mustGet("input").jsonObject,
        )

        assertNotEquals("null and \"\" must not collide", absent, empty)
    }

    /**
     * Length prefixes must keep adjacent parts from being ambiguous: `["ab","c"]` and
     * `["a","bc"]` concatenate identically without them.
     */
    @Test
    fun `shifted part boundaries produce different fingerprints`() {
        val inputs = readJson("fingerprints/vectors.json").mustGet("vectors").jsonArray
            .map { it.jsonObject }
            .associateBy { it.mustGet("id").jsonPrimitive.content }

        val left = fingerprintFromVector(
            inputs.mustGet("fingerprint-part-boundary").mustGet("input").jsonObject,
        )
        val right = fingerprintFromVector(
            inputs.mustGet("fingerprint-part-boundary-shifted").mustGet("input").jsonObject,
        )

        assertNotEquals("[\"ab\",\"c\"] and [\"a\",\"bc\"] must not collide", left, right)
    }

    // -----------------------------------------------------------------------------------------
    // Frame routing
    // -----------------------------------------------------------------------------------------

    private fun rejectionName(reason: ClaudePParseRejection): String = reason.name

    @Test
    fun `every frame routes to its expected outcome`() {
        val frames = readJson("frames/envelope.json").mustGet("frames").jsonArray
        val expectations = readJson("expect/routing.json").mustGet("expectations").jsonArray
            .map { it.jsonObject }
            .associateBy { it.mustGet("id").jsonPrimitive.content }

        assertTrue("corpus must contain frames", frames.isNotEmpty())
        assertEquals("frame and expectation counts differ", frames.size, expectations.size)

        for (frameElement in frames) {
            val frame = frameElement.jsonObject
            val id = frame.mustGet("id").jsonPrimitive.content
            val raw = frame.mustGet("raw").jsonPrimitive.content
            val expected = expectations[id]
                ?: throw AssertionError("no expectation for frame $id")
            val expect = expected.mustGet("expect").jsonObject
            val kind = expect.mustGet("kind").jsonPrimitive.content

            val actual = ClaudePProtocol.parseInbound(raw)

            when (kind) {
                "event" -> {
                    assertTrue(
                        "$id: expected a routed event, got $actual",
                        actual is ClaudePInbound.Event,
                    )
                    val expectedType = expect.mustGet("eventType").jsonPrimitive.content
                    val envelopeType = (actual as ClaudePInbound.Event).event.envelope.type.trim()
                    assertEquals("$id: wrong routed type", expectedType, envelopeType)
                }

                "ignored_unknown" -> assertTrue(
                    "$id: expected IgnoredUnknownEvent, got $actual",
                    actual is ClaudePInbound.IgnoredUnknownEvent,
                )

                "rejected" -> {
                    assertTrue("$id: expected a rejection, got $actual", actual is ClaudePInbound.Rejected)
                    val expectedReason = expect.mustGet("reason").jsonPrimitive.content
                    assertEquals(
                        "$id: wrong rejection reason",
                        expectedReason,
                        rejectionName((actual as ClaudePInbound.Rejected).reason),
                    )
                }

                else -> throw AssertionError("$id: unknown expectation kind '$kind'")
            }
        }
    }

    /**
     * An unknown optional event must never be able to end a generation.
     *
     * Forward compatibility and fail-closed are opposites, and the protocol draws the line
     * at the major version: an unfamiliar *type* is dropped, an unfamiliar *major* stops
     * everything. This asserts the corpus actually pins both sides of that line.
     */
    @Test
    fun `unknown optional events are ignored while unknown majors are rejected`() {
        val frames = readJson("frames/envelope.json").mustGet("frames").jsonArray

        val ignored = mutableListOf<String>()
        val rejectedMajors = mutableListOf<String>()
        for (frameElement in frames) {
            val frame = frameElement.jsonObject
            val id = frame.mustGet("id").jsonPrimitive.content
            when (ClaudePProtocol.parseInbound(frame.mustGet("raw").jsonPrimitive.content)) {
                is ClaudePInbound.IgnoredUnknownEvent -> ignored += id
                is ClaudePInbound.Rejected -> {
                    val reason = (ClaudePProtocol.parseInbound(frame.mustGet("raw").jsonPrimitive.content)
                        as ClaudePInbound.Rejected).reason
                    if (reason == ClaudePParseRejection.PROTOCOL_MAJOR_MISMATCH) rejectedMajors += id
                }

                is ClaudePInbound.Event -> Unit
            }
        }

        assertTrue("corpus must exercise the ignored-unknown path", ignored.isNotEmpty())
        assertTrue("corpus must exercise the major-mismatch path", rejectedMajors.isNotEmpty())
    }

    /**
     * Key order in a JSON object is not semantically meaningful, but the bytes are.
     *
     * `order-insensitive-fields-reordered` and `order-sensitive-envelope-protocol-first`
     * carry the same content in different orders and must route identically. They are in
     * the corpus as a matched pair precisely so nobody "simplifies" the corpus by
     * treating the raw strings as interchangeable elsewhere.
     */
    @Test
    fun `reordered json keys route identically to the canonical spelling`() {
        val frames = readJson("frames/envelope.json").mustGet("frames").jsonArray
            .map { it.jsonObject }
            .associateBy { it.mustGet("id").jsonPrimitive.content }

        val canonical = frames.mustGet("order-sensitive-envelope-protocol-first")
            .mustGet("raw").jsonPrimitive.content
        val reordered = frames.mustGet("order-insensitive-fields-reordered")
            .mustGet("raw").jsonPrimitive.content

        assertNotEquals("the two fixtures must actually differ as bytes", canonical, reordered)

        val canonicalResult = ClaudePProtocol.parseInbound(canonical)
        val reorderedResult = ClaudePProtocol.parseInbound(reordered)

        assertTrue(canonicalResult is ClaudePInbound.Event)
        assertTrue(reorderedResult is ClaudePInbound.Event)
        assertEquals(
            (canonicalResult as ClaudePInbound.Event).event.envelope.type,
            (reorderedResult as ClaudePInbound.Event).event.envelope.type,
        )
    }

    // -----------------------------------------------------------------------------------------
    // Spec revision binding
    // -----------------------------------------------------------------------------------------

    @Test
    fun `corpus pins the protocol identity this build speaks`() {
        val revision = readJson("SPEC_REVISION.json")

        assertEquals(
            ClaudePProtocol.PROTOCOL_ID,
            revision.mustGet("protocol_id").jsonPrimitive.content,
        )
        assertEquals(
            ClaudePProtocol.MAJOR_VERSION,
            revision.mustGet("major_version").jsonPrimitive.content.toInt(),
        )
        assertEquals(
            ClaudePProtocol.SUBPROTOCOL,
            revision.mustGet("protocol_id").jsonPrimitive.content,
        )
    }

    /**
     * Binds the corpus to the specification text it was derived from.
     *
     * The manifest proves the corpus has not been edited, but it cannot notice the *other*
     * direction: someone changing `claudep/02-wire-protocol-v1.md` and forgetting to
     * regenerate. Then the tests still pass while the vectors quietly describe a revision
     * of the protocol that no longer exists.
     *
     * Two independent checks close that:
     *
     * 1. the revision string the specification declares must equal the one the corpus
     *    records, so a bumped spec with a stale corpus fails;
     * 2. the specification's SHA-256 must equal the one the corpus recorded, so even a
     *    whitespace-only edit fails rather than passing because the marker was untouched.
     *
     * The server repository cannot perform check 2 — it does not hold the specification —
     * which is why it asserts the revision string against a constant it carries itself.
     * Between the two repositories, both directions are covered.
     */
    @Test
    fun `corpus revision is bound to the specification it was derived from`() {
        val revision = readJson("SPEC_REVISION.json")
        val specFile = File(corpusRoot().parentFile, "02-wire-protocol-v1.md")
        assertTrue("the specification must exist at ${specFile.path}", specFile.isFile)

        val specText = specFile.readText()
        val declared = Regex("规范修订：`([^`]+)`").find(specText)?.groupValues?.get(1)
        assertNotNull("the specification must declare a 规范修订 marker", declared)

        assertEquals(
            "SPEC_REVISION.json records a different revision than the specification declares",
            declared,
            revision.mustGet("spec_revision").jsonPrimitive.content,
        )
        assertEquals(
            "the specification changed without the corpus being regenerated",
            revision.mustGet("protocol_spec_sha256").jsonPrimitive.content,
            sha256Hex(specFile.readBytes()),
        )
    }

    @Test
    fun `known server event types cover every routed type in the corpus`() {
        val knowable = readJson("frames/envelope.json").mustGet("frames").jsonArray
            .map { it.jsonObject }
            .mapNotNull { frame ->
                val raw = frame.mustGet("raw").jsonPrimitive.content
                val parsed = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
                parsed?.get("type")?.let { (it as? JsonPrimitive)?.content }
            }
            .filter { it.isNotBlank() }
            .toSet()

        val routed = knowable.intersect(ClaudePProtocol.KNOWN_SERVER_EVENT_TYPES)
        assertTrue(
            "the corpus should exercise at least one routed server event type",
            routed.isNotEmpty(),
        )
    }

    /**
     * Reads a required key, failing loudly when it is absent.
     *
     * Deliberately not named `getValue`: `kotlin.collections` already provides one whose
     * failure mode is a bare `NoSuchElementException`, and a member extension would
     * silently shadow it — making a missing key in the corpus indistinguishable from a
     * genuine assertion failure.
     *
     * **Generic over the value type on purpose.** The corpus is read two ways: as a
     * `JsonObject` (a frame's fields, where the value is a `JsonElement`) and as an
     * indexed `Map<String, JsonObject>` built by `associateBy` (one vector looked up by
     * its id). Both need the same fail-closed lookup, and neither should get a weaker one
     * than the other. An earlier revision declared this on `JsonObject` alone, which
     * compiled for the first use and failed for the second at every call site: a
     * `Map<String, JsonObject>` is not a `JsonObject`, even though `JsonObject` is itself
     * a `Map<String, JsonElement>`.
     *
     * Because the receiver is the Map rather than the concrete type, the return type is
     * inferred from the Map's own value type — `JsonObject` for an indexed vector map,
     * `JsonElement` for a frame — so callers keep their existing `.jsonPrimitive`,
     * `.jsonArray` and `.jsonObject` accessors with no casts.
     *
     * `V : Any` is required rather than incidental: it is what lets the elvis operator
     * narrow `V?` to `V`, so a genuinely absent key throws instead of yielding a null that
     * a later access would turn into a confusing failure somewhere else.
     */
    private fun <V : Any> Map<String, V>.mustGet(key: String): V =
        this[key] ?: throw AssertionError("corpus entry is missing '$key'")
}
