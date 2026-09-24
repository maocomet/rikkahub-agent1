package me.rerere.ai.provider.claudep.bridge

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The identity, idempotency and lifecycle rules of the Claude P tool bridge.
 *
 * Everything here runs on the JVM with no Android dependency and no framework: the catalog
 * builder and the adapter are pure by construction, which is what makes the rules assertable
 * rather than merely reviewable.
 *
 * ## What this file does **not** cover
 *
 * It proves the rules, not the wiring. That a valid invocation actually reaches
 * `DefaultToolRuntime`, that approval is decided by the existing `ToolExecutionGate` and the
 * existing approval UI, and that an MCP tool is executed through `McpManager`, are claims
 * about the app-side adapter and can only be tested where those components exist. Those tests
 * live in `app/src/`; this file never asserts them, and a green run here is not evidence for
 * them.
 *
 * The test names carry the requirement they come from so a reviewer can check coverage
 * against the M2-B list rather than trusting a summary.
 */
class ClaudePToolBridgeTest {

    // -----------------------------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------------------------

    private fun schema(vararg keys: String): JsonObject = JsonObject(
        linkedMapOf(
            "type" to JsonPrimitive("object"),
            "properties" to JsonObject(
                keys.associateWith {
                    JsonObject(linkedMapOf("type" to JsonPrimitive("string")))
                },
            ),
        ),
    )

    /** The default candidate: effectful, which is what an unproven tool must be. */
    private fun candidate(
        name: String,
        source: ToolSource = ToolSource.LOCAL,
        description: String = "does a thing",
    ) = BridgeToolCandidate.tool(name, description, schema("path"), source)

    /** A candidate whose tool is proven to have no side effects for any valid argument. */
    private fun provenReadOnlyCandidate(name: String) =
        BridgeToolCandidate.provenReadOnly(name, "reads a thing", schema("path"), ToolSource.LOCAL)

    private fun generation(
        generationId: String = "gen-1",
        catalogDigest: String = "",
    ) = GenerationBinding(
        deviceRef = "device-ref-1",
        assistantId = "assistant-1",
        conversationId = "conv-1",
        branchId = "branch-1",
        generationId = generationId,
        requestId = "req-1",
        catalogDigest = catalogDigest,
        bridgeAbi = BridgeContract.BRIDGE_ABI,
        timeoutMs = 60_000,
    )

    /** An adapter bound to a catalog built from [candidates]. */
    private fun adapterFor(
        candidates: List<BridgeToolCandidate>,
        generationId: String = "gen-1",
    ): BridgeToolAdapter {
        val build = BridgeToolCatalog.build(candidates)
        return BridgeToolAdapter(
            generation(generationId, build.catalog.digest),
            build.catalog,
        )
    }

    private fun args(vararg pairs: Pair<String, String>): JsonObject =
        JsonObject(pairs.associate { (key, value) -> key to JsonPrimitive(value) })

    private val readFile = provenReadOnlyCandidate("read_file")
    private val writeFile = candidate("write_file")
    private val mcpTool = candidate("mcp__a1b2c3d4_files__read", source = ToolSource.MCP)

    // -----------------------------------------------------------------------------------------
    // 1. Catalog stability, ordering and digest
    // -----------------------------------------------------------------------------------------

    @Test
    fun `catalog order and digest do not depend on input order`() {
        val forward = BridgeToolCatalog.build(listOf(readFile, mcpTool))
        val backward = BridgeToolCatalog.build(listOf(mcpTool, readFile))

        assertEquals(forward.catalog.digest, backward.catalog.digest)
        assertEquals(forward.snapshot.toString(), backward.snapshot.toString())
        assertEquals(
            listOf("mcp__a1b2c3d4_files__read", "read_file"),
            forward.catalog.entries.map { it.name },
        )
    }

    @Test
    fun `catalog digest is stable across repeated builds of the same set`() {
        val first = BridgeToolCatalog.build(listOf(readFile, writeFile, mcpTool)).catalog.digest
        repeat(5) {
            assertEquals(first, BridgeToolCatalog.build(listOf(readFile, writeFile, mcpTool)).catalog.digest)
        }
    }

    // -----------------------------------------------------------------------------------------
    // 2. Local and MCP name mapping
    // -----------------------------------------------------------------------------------------

    @Test
    fun `local and mcp tools map to their bridged names and keep their source`() {
        val build = BridgeToolCatalog.build(listOf(readFile, mcpTool))

        val local = build.catalog.findEntry("read_file")!!
        assertEquals("mcp__rikkahub_bridge__read_file", local.bridgedName)
        assertEquals(ToolSource.LOCAL, local.source)
        assertEquals("read_file", local.displayName)

        val mcp = build.catalog.findEntry("mcp__a1b2c3d4_files__read")!!
        assertEquals("mcp__rikkahub_bridge__mcp__a1b2c3d4_files__read", mcp.bridgedName)
        assertEquals(ToolSource.MCP, mcp.source)

        // The mapping is injective in both directions, which is what makes the frozen name a
        // usable identity rather than a label.
        assertEquals("read_file", build.catalog.findEntryByBridgedName(local.bridgedName)?.name)
        assertEquals(
            "mcp__a1b2c3d4_files__read",
            build.catalog.findEntryByBridgedName(mcp.bridgedName)?.name,
        )
    }

    @Test
    fun `a write tool is never reported read-only`() {
        val build = BridgeToolCatalog.build(listOf(readFile, writeFile))
        assertTrue(build.catalog.findEntry("read_file")!!.readOnly)
        assertFalse(build.catalog.findEntry("write_file")!!.readOnly)
    }

    // -----------------------------------------------------------------------------------------
    // Ruling 2. `readOnly` cannot be set from a value that arrived over the wire
    // -----------------------------------------------------------------------------------------

    /**
     * There is no public constructor and no boolean parameter, so the only ways to build a
     * candidate are two factories whose *names* are the claim being made.
     *
     * Checked by reflection rather than by reading the source, because the property is about
     * the shape of the type: a future change that re-exposes a constructor taking a boolean
     * would pass a source review and fail here.
     */
    @Test
    fun `there is no way to construct a read-only candidate from a boolean`() {
        val constructors = BridgeToolCandidate::class.java.declaredConstructors
        val real = constructors.filterNot { it.isSynthetic }

        assertEquals("exactly one constructor is written in the source", 1, real.size)
        assertTrue(
            "the constructor must be private, or a caller can state read-only directly",
            java.lang.reflect.Modifier.isPrivate(real.single().modifiers),
        )

        // Kotlin emits an additional **synthetic** public constructor carrying a
        // `DefaultConstructorMarker` so the companion can reach the private one. It is a
        // compiler artifact rather than a reachable API — Kotlin source cannot call it — and it
        // is checked here so that a change in what the compiler emits is noticed rather than
        // silently widening the surface.
        for (synthetic in constructors.filter { it.isSynthetic }) {
            assertTrue(
                "a synthetic constructor exists only to carry the companion marker",
                synthetic.parameterTypes.any { it.name.endsWith("DefaultConstructorMarker") },
            )
        }

        // The only public ways to build one name the claim they make. `tool` asserts the weaker
        // claim and is always available; `provenReadOnly` is the only source of `true`.
        val factories = BridgeToolCandidate.Companion::class.java.declaredMethods
            .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) }
            .filter { it.returnType == BridgeToolCandidate::class.java }
            .map { it.name }
            .sorted()
        assertEquals(listOf("provenReadOnly", "tool"), factories)
    }

    @Test
    fun `the default candidate is effectful and only the explicit one is read-only`() {
        assertFalse(
            "an unproven tool must never be reported as having no side effects",
            BridgeToolCandidate.tool("t", "d", schema("path"), ToolSource.LOCAL).readOnly,
        )
        assertTrue(
            BridgeToolCandidate.provenReadOnly("t", "d", schema("path"), ToolSource.LOCAL).readOnly,
        )
    }

    /**
     * Android cannot inspect an MCP server's implementation, so it cannot hold this proof for
     * one. The factory refuses rather than quietly producing a false `true`.
     */
    @Test
    fun `a proven-read-only claim cannot be made for an MCP tool`() {
        val refused = try {
            BridgeToolCandidate.provenReadOnly("t", "d", schema("path"), ToolSource.MCP)
            false
        } catch (error: IllegalArgumentException) {
            true
        }
        assertTrue("an MCP tool must not be claimable as proven read-only", refused)
    }

    @Test
    fun `every MCP tool in a built catalog is effectful`() {
        val build = BridgeToolCatalog.build(listOf(mcpTool))
        assertFalse(
            "an MCP tool's side effects are not Android's to rule out",
            build.catalog.findEntry("mcp__a1b2c3d4_files__read")!!.readOnly,
        )
    }

    @Test
    fun `a candidate can always be downgraded when the proof does not hold`() {
        assertFalse(provenReadOnlyCandidate("t").asEffectful().readOnly)
    }

    /**
     * The catalog's `readOnly` is a description, never an authorization.
     *
     * A call to a tool the catalog marked read-only is admitted and refused by exactly the same
     * rules as any other — nothing in the adapter branches on the flag. This is asserted here
     * because the flag is the one field a reviewer might expect to unlock something.
     */
    @Test
    fun `a read-only catalog entry grants no shortcut through the adapter`() {
        val adapter = adapterFor(listOf(readFile))

        // The same identity rules apply: an unfrozen tool is refused even if some other entry
        // in the catalog claims to be read-only.
        val refused = adapter.onInvoke("call-1", "some_other_tool", args("path" to "/a"))
        assertEquals(
            BridgeRejection.TOOL_NOT_IN_CATALOG,
            (refused as BridgeInvokeDecision.Refused).reason,
        )

        // And an admitted call is still just admitted: awaiting a terminal, not pre-approved.
        val admitted = adapter.onInvoke("call-2", "read_file", args("path" to "/a"))
        assertTrue(admitted is BridgeInvokeDecision.Execute)
        assertEquals(ToolCallState.PENDING, adapter.recordedOutcome("call-2")?.state)
    }

    // -----------------------------------------------------------------------------------------
    // 1 (fail-closed). Tools that cannot be frozen are dropped, never renamed
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a name the frozen namespace cannot carry is dropped and recorded`() {
        val build = BridgeToolCatalog.build(listOf(candidate("shell.exec"), readFile))
        assertEquals(listOf("read_file"), build.catalog.entries.map { it.name })
        assertEquals(
            BridgeToolEligibility.NAME_NOT_ENCODABLE,
            build.exclusions.single().reason,
        )
    }

    @Test
    fun `a name normalizing onto another drops both rather than picking one`() {
        val build = BridgeToolCatalog.build(listOf(candidate("Read_File"), candidate("read_file")))

        // Keeping either would make the survivor depend on the order the allowlist happened to
        // iterate in, which is the instability the frozen sort order exists to remove.
        assertTrue(build.catalog.isEmpty)
        assertEquals(2, build.exclusions.size)
        assertTrue(build.exclusions.all { it.reason == BridgeToolEligibility.NAME_COLLISION })
    }

    @Test
    fun `a description past the bound drops that tool only`() {
        val build = BridgeToolCatalog.build(
            listOf(candidate("wordy", description = "x".repeat(4097)), readFile),
        )
        assertEquals(listOf("read_file"), build.catalog.entries.map { it.name })
        assertEquals(
            BridgeToolEligibility.DESCRIPTION_TOO_LARGE,
            build.exclusions.single().reason,
        )
    }

    @Test
    fun `an empty catalog is the text path and never means all tools`() {
        assertTrue(BridgeToolCatalog.build(emptyList()).isEmpty)
        assertTrue(BridgeToolCatalog.build(listOf(candidate("bad name!"))).isEmpty)
        assertEquals(BridgeCatalog.EMPTY.digest, BridgeToolCatalog.build(emptyList()).catalog.digest)
    }

    // -----------------------------------------------------------------------------------------
    // 3. Identity: device, assistant, generation and call
    // -----------------------------------------------------------------------------------------

    @Test
    fun `an adapter serves exactly one generation`() {
        val adapter = adapterFor(listOf(readFile))
        assertTrue(adapter.serves("gen-1"))
        assertFalse("a frame for another generation must not be served here", adapter.serves("gen-2"))
        assertFalse(adapter.serves(null))
    }

    @Test
    fun `a call recorded under one generation is invisible to another`() {
        val first = adapterFor(listOf(readFile), generationId = "gen-1")
        val second = adapterFor(listOf(readFile), generationId = "gen-2")

        assertTrue(first.onInvoke("call-1", "read_file", args("path" to "/a")) is BridgeInvokeDecision.Execute)

        // Not "found by searching across generations": simply not held. The ledger is the
        // adapter's own and dies with it, so the forbidden search cannot be expressed.
        assertEquals(ToolCallState.NOT_FOUND, second.query("call-1")?.state)
    }

    @Test
    fun `a malformed call id is refused before anything else is considered`() {
        val adapter = adapterFor(listOf(readFile))
        for (bad in listOf(null, "", "a b", "x".repeat(129))) {
            val decision = adapter.onInvoke(bad, "read_file", args("path" to "/a"))
            assertEquals(
                "call id ${bad?.length}",
                BridgeRejection.INVOCATION_ID_INVALID,
                (decision as BridgeInvokeDecision.Refused).reason,
            )
        }
        assertEquals(0, adapter.pendingCount)
    }

    // -----------------------------------------------------------------------------------------
    // 4. A tool outside the frozen catalog is not reachable
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a tool that was never frozen is refused and runs nothing`() {
        val adapter = adapterFor(listOf(readFile))

        val unfrozen = adapter.onInvoke("call-1", "delete_everything", args("path" to "/x"))
        assertEquals(
            BridgeRejection.TOOL_NOT_IN_CATALOG,
            (unfrozen as BridgeInvokeDecision.Refused).reason,
        )

        val malformed = adapter.onInvoke("call-1", "bad name!", args("path" to "/x"))
        assertEquals(
            BridgeRejection.TOOL_NAME_NOT_BRIDGED,
            (malformed as BridgeInvokeDecision.Refused).reason,
        )

        // A name the server would have to have invented: not the canonical frozen spelling.
        val wrongCase = adapter.onInvoke("call-1", "Read_File", args("path" to "/x"))
        assertEquals(
            BridgeRejection.TOOL_NAME_NOT_BRIDGED,
            (wrongCase as BridgeInvokeDecision.Refused).reason,
        )

        assertEquals("nothing was admitted", 0, adapter.pendingCount)
    }

    @Test
    fun `arguments that are not an object are refused`() {
        val adapter = adapterFor(listOf(readFile))
        for (bad in listOf(null, kotlinx.serialization.json.JsonArray(emptyList()), JsonPrimitive("x"))) {
            val decision = adapter.onInvoke("call-1", "read_file", bad)
            assertTrue("$bad", decision is BridgeInvokeDecision.Refused)
        }
    }

    // -----------------------------------------------------------------------------------------
    // 5, 6. Replay, conflict, and the count of executions
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a repeated invoke executes once`() {
        val adapter = adapterFor(listOf(readFile))

        val first = adapter.onInvoke("call-1", "read_file", args("path" to "/a"))
        assertTrue(first is BridgeInvokeDecision.Execute)
        assertEquals(1, adapter.pendingCount)

        // The repeat attaches to the call already in flight. Nothing runs a second time.
        val repeat = adapter.onInvoke("call-1", "read_file", args("path" to "/a"))
        assertTrue(repeat is BridgeInvokeDecision.Await)
        assertEquals(1, adapter.pendingCount)
    }

    @Test
    fun `the same call id with different arguments conflicts and still executes once`() {
        val adapter = adapterFor(listOf(readFile, writeFile))
        adapter.onInvoke("call-1", "read_file", args("path" to "/a"))

        val changedArgs = adapter.onInvoke("call-1", "read_file", args("path" to "/b"))
        assertEquals(
            BridgeRejection.INVOCATION_BINDING_MISMATCH,
            (changedArgs as BridgeInvokeDecision.Refused).reason,
        )

        // A different tool under the same id is the same failure by another route.
        val changedTool = adapter.onInvoke("call-1", "write_file", args("path" to "/a"))
        assertEquals(
            BridgeRejection.INVOCATION_BINDING_MISMATCH,
            (changedTool as BridgeInvokeDecision.Refused).reason,
        )

        assertEquals("still exactly one call", 1, adapter.pendingCount)
    }

    @Test
    fun `argument order does not turn a replay into a conflict`() {
        val adapter = adapterFor(listOf(readFile))
        adapter.onInvoke("call-1", "read_file", args("a" to "1", "b" to "2"))

        // Canonicalization sorts members, so this is the same call stated a different way —
        // which is exactly why a retry is safe to make.
        val repeat = adapter.onInvoke("call-1", "read_file", args("b" to "2", "a" to "1"))
        assertTrue(repeat is BridgeInvokeDecision.Await)
    }

    // -----------------------------------------------------------------------------------------
    // 7, 8, 9. Query: exact, inert, and replayable
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a settled call replays its recorded outcome without running again`() {
        val adapter = adapterFor(listOf(readFile))
        adapter.onInvoke("call-1", "read_file", args("path" to "/a"))
        adapter.complete("call-1", ToolCallState.COMPLETED, "contents")

        val replay = adapter.onInvoke("call-1", "read_file", args("path" to "/a"))
        assertEquals(
            "contents",
            (replay as BridgeInvokeDecision.Replay).outcome.body,
        )
        assertEquals(0, adapter.pendingCount)
    }

    @Test
    fun `a terminal result stays queryable and repeated queries are identical`() {
        val adapter = adapterFor(listOf(readFile))
        adapter.onInvoke("call-1", "read_file", args("path" to "/a"))
        adapter.complete("call-1", ToolCallState.COMPLETED, "contents")

        val first = adapter.query("call-1")
        val second = adapter.query("call-1")
        assertEquals(ToolCallState.COMPLETED, first?.state)
        assertEquals(first, second)
    }

    @Test
    fun `a query never triggers an execution`() {
        val adapter = adapterFor(listOf(readFile))
        assertEquals(ToolCallState.NOT_FOUND, adapter.query("never-seen")?.state)
        assertEquals(0, adapter.pendingCount)
        assertNull("a malformed id is not a lookup that found nothing", adapter.query(""))
    }

    // -----------------------------------------------------------------------------------------
    // 10-13. Approval lifecycle, cancel, and honest reporting
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a call settles to the state the runtime reports`() {
        val adapter = adapterFor(listOf(readFile))
        adapter.onInvoke("call-1", "read_file", args("path" to "/a"))

        val settled = adapter.complete("call-1", ToolCallState.COMPLETED, "contents")
        assertEquals(
            ToolCallState.COMPLETED,
            (settled as BridgeCompletion.Settled).outcome.state,
        )
        assertEquals("contents", adapter.recordedOutcome("call-1")?.body)
    }

    @Test
    fun `a denial is reported as denied and carries no body`() {
        val adapter = adapterFor(listOf(writeFile))
        adapter.onInvoke("call-1", "write_file", args("path" to "/w"))

        adapter.complete("call-1", ToolCallState.DENIED)
        assertEquals(ToolCallState.DENIED, adapter.recordedOutcome("call-1")?.state)
        assertNull("a denial carries no result body", adapter.recordedOutcome("call-1")?.body)
    }

    @Test
    fun `cancel is idempotent and propagates at most once`() {
        val adapter = adapterFor(listOf(writeFile))
        adapter.onInvoke("call-1", "write_file", args("path" to "/w"))

        assertEquals(BridgeCancelDecision.Propagate, adapter.onCancel("call-1"))
        assertEquals(BridgeCancelDecision.NoOp, adapter.onCancel("call-1"))
        assertEquals(BridgeCancelDecision.NoOp, adapter.onCancel("call-1"))
    }

    @Test
    fun `cancel is a no-op for a call that is unknown or already settled`() {
        val adapter = adapterFor(listOf(readFile))
        assertEquals(BridgeCancelDecision.NoOp, adapter.onCancel("never-seen"))
        assertEquals(BridgeCancelDecision.NoOp, adapter.onCancel(""))
        assertEquals(BridgeCancelDecision.NoOp, adapter.onCancel(null))

        adapter.onInvoke("call-1", "read_file", args("path" to "/a"))
        adapter.complete("call-1", ToolCallState.COMPLETED, "contents")
        assertEquals(
            "a settled call's outcome stands and nothing is re-sent",
            BridgeCancelDecision.NoOp,
            adapter.onCancel("call-1"),
        )
    }

    @Test
    fun `a call that completed after its cancel is reported completed, not cancelled`() {
        val adapter = adapterFor(listOf(writeFile))
        adapter.onInvoke("call-1", "write_file", args("path" to "/w"))
        assertEquals(BridgeCancelDecision.Propagate, adapter.onCancel("call-1"))

        val settled = adapter.complete("call-1", ToolCallState.COMPLETED, "wrote")
        assertEquals(
            "reporting `cancelled` here would claim a stop that did not happen",
            ToolCallState.COMPLETED,
            (settled as BridgeCompletion.Settled).outcome.state,
        )
    }

    @Test
    fun `a cancelled call that never ran is reported cancelled`() {
        val adapter = adapterFor(listOf(writeFile))
        adapter.onInvoke("call-1", "write_file", args("path" to "/w"))
        adapter.onCancel("call-1")

        adapter.complete("call-1", ToolCallState.CANCELLED)
        assertEquals(ToolCallState.CANCELLED, adapter.recordedOutcome("call-1")?.state)
    }

    // -----------------------------------------------------------------------------------------
    // 21. A repeated or late terminal never changes the settled state
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a repeated terminal is not re-announced and a different one is dropped`() {
        val adapter = adapterFor(listOf(readFile))
        adapter.onInvoke("call-1", "read_file", args("path" to "/a"))

        assertTrue(adapter.complete("call-1", ToolCallState.COMPLETED, "contents") is BridgeCompletion.Settled)
        assertTrue(
            "the same fact stated twice is not a second announcement",
            adapter.complete("call-1", ToolCallState.COMPLETED, "contents") is BridgeCompletion.Repeat,
        )

        val late = adapter.complete("call-1", ToolCallState.FAILED)
        assertEquals(
            "a different late terminal does not replace the recorded one",
            ToolCallState.COMPLETED,
            (late as BridgeCompletion.Superseded).outcome.state,
        )
        assertEquals(ToolCallState.COMPLETED, adapter.recordedOutcome("call-1")?.state)
    }

    @Test
    fun `a state Android may not report is dropped rather than turned into a claim`() {
        val adapter = adapterFor(listOf(readFile))
        adapter.onInvoke("call-1", "read_file", args("path" to "/a"))

        // `pending` would be a call that never terminates; `not_found` and `disconnected` are
        // verdicts about the Server's own records, which Android does not hold.
        for (unreportable in listOf(
            ToolCallState.PENDING,
            ToolCallState.NOT_FOUND,
            ToolCallState.DISCONNECTED,
            ToolCallState.CONFLICT,
        )) {
            assertTrue(
                "$unreportable",
                adapter.complete("call-1", unreportable) is BridgeCompletion.Superseded,
            )
        }
        assertEquals(
            "the record is untouched by an unusable report",
            ToolCallState.PENDING,
            adapter.recordedOutcome("call-1")?.state,
        )
    }

    // -----------------------------------------------------------------------------------------
    // 14, 15. Disconnect, reconnect and page rebuild re-dispatch nothing
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a rebuilt adapter holds no record, so it re-runs nothing it cannot prove`() {
        val first = adapterFor(listOf(readFile))
        first.onInvoke("call-1", "read_file", args("path" to "/a"))
        first.complete("call-1", ToolCallState.COMPLETED, "contents")

        // What a page rebuild or a process restart looks like: the same generation, a fresh
        // adapter. The honest answer is `not_found`, and it is deliberately not an invitation
        // to run the tool again to find out what it did.
        val rebuilt = adapterFor(listOf(readFile))
        assertEquals(ToolCallState.NOT_FOUND, rebuilt.query("call-1")?.state)
        assertEquals(0, rebuilt.pendingCount)
    }

    @Test
    fun `a deadline settles a call and a late result for it is dropped`() {
        val adapter = adapterFor(listOf(readFile))
        adapter.onInvoke("call-1", "read_file", args("path" to "/a"))

        val expired = adapter.expire(System.nanoTime() / 1_000_000L + 120_000L)
        assertEquals(listOf("call-1"), expired.map { it.toolCallId })
        assertEquals(ToolCallState.TIMED_OUT, adapter.recordedOutcome("call-1")?.state)

        assertTrue(
            adapter.complete("call-1", ToolCallState.COMPLETED, "late") is BridgeCompletion.Superseded,
        )
        assertEquals(ToolCallState.TIMED_OUT, adapter.recordedOutcome("call-1")?.state)
    }

    @Test
    fun `a frozen catalog digest is what the generation is bound to`() {
        val build = BridgeToolCatalog.build(listOf(readFile))
        val adapter = BridgeToolAdapter(generation("gen-1", build.catalog.digest), build.catalog)
        assertEquals(build.catalog.digest, adapter.catalogDigest)
        assertEquals("gen-1", adapter.generationId)
    }
}
