package me.rerere.ai.provider.claudep.bridge

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The execution claim: the one gate between an approval and a tool that runs.
 *
 * ## What is under test, and what is deliberately not
 *
 * The claim answers a single question — *may this exact call run, and what exactly is it?* — so
 * most of what follows is about answers that must be **refused**. A claim that grants too much is
 * the only way a tool runs twice or runs under another generation's context; a claim that grants
 * too little costs a tool call and nothing else.
 *
 * The run-id and pairing half of the contract is **not** here. This object never learns an Android
 * run id: the pairing between a Server generation and the run serving it is the app's, verified by
 * the app immediately before it asks for the claim. What is testable here is everything the ledger
 * itself owns — the canonical invocation, the record's state, the deadline, and the exactly-once
 * right.
 */
class BridgeExecutionClaimTest {

    private fun schema(): JsonObject = JsonObject(
        linkedMapOf(
            "type" to JsonPrimitive("object"),
            "properties" to JsonObject(
                linkedMapOf("path" to JsonObject(linkedMapOf("type" to JsonPrimitive("string")))),
            ),
        ),
    )

    private fun candidate(name: String) =
        BridgeToolCandidate.provenReadOnly(name, "reads", schema(), ToolSource.LOCAL)

    private fun catalog(vararg names: String) =
        BridgeToolCatalog.build(names.map(::candidate)).catalog

    private fun generation(
        generationId: String = "gen-1",
        timeoutMs: Long = 60_000,
    ) = GenerationBinding(
        deviceRef = "device-ref-1",
        assistantId = "assistant-1",
        conversationId = "conv-1",
        branchId = "branch-1",
        generationId = generationId,
        requestId = "req-1",
        catalogDigest = catalog("read_file").digest,
        bridgeAbi = BridgeContract.BRIDGE_ABI,
        timeoutMs = timeoutMs,
    )

    private fun args(path: String = "/a", token: String? = null): JsonObject =
        JsonObject(
            linkedMapOf("path" to JsonPrimitive(path)) +
                (token?.let { linkedMapOf("token" to JsonPrimitive(it)) } ?: emptyMap()),
        )

    /** A clock the test moves by hand, so an elapsed deadline is reachable in milliseconds. */
    private class Clock {
        var now: Long = 0L
        fun advance() {
            now = Long.MAX_VALUE / 2
        }
    }

    private fun adapter(
        generationId: String = "gen-1",
        timeoutMs: Long = 60_000,
        clock: Clock = Clock(),
    ) = BridgeToolAdapter(
        binding = generation(generationId, timeoutMs),
        catalog = catalog("read_file"),
        executions = BridgeExecutionHost.NONE,
        monotonicMs = { clock.now },
    )

    /** A call admitted exactly the way a real invoke admits one. */
    private fun admit(
        adapter: BridgeToolAdapter,
        toolCallId: String = "call-1",
        arguments: JsonObject = args(),
    ): BridgeInvocation {
        val decision = adapter.onInvoke(toolCallId, "read_file", arguments)
        assertTrue("the invoke must be admitted", decision is BridgeInvokeDecision.Execute)
        return (decision as BridgeInvokeDecision.Execute).invocation
    }

    private fun claimOf(
        invocation: BridgeInvocation,
        serverGenerationId: String = "gen-1",
        runId: String = "run-1",
        toolName: String? = null,
        argsDigest: String? = null,
        binding: InvocationBinding? = null,
        approval: BridgeExecutionApproval = BridgeExecutionApproval.NotRequired,
    ) = BridgeExecutionClaim(
        serverGenerationId = serverGenerationId,
        runId = runId,
        toolCallId = invocation.binding.toolCallId,
        toolName = toolName ?: invocation.toolNameForRuntime,
        argsDigest = argsDigest ?: invocation.argsDigest,
        binding = binding ?: invocation.binding,
        approval = approval,
    )

    private fun refusal(result: BridgeExecutionClaimResult): BridgeClaimRefusal {
        assertTrue("expected a refusal, got $result", result is BridgeExecutionClaimResult.Refused)
        return (result as BridgeExecutionClaimResult.Refused).reason
    }

    private fun claimed(result: BridgeExecutionClaimResult): BridgeInvocation {
        assertTrue("expected a claim, got $result", result is BridgeExecutionClaimResult.Claimed)
        return (result as BridgeExecutionClaimResult.Claimed).invocation
    }

    // -----------------------------------------------------------------------------------------
    // The one path that grants
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a claim returns the canonical invocation the ledger stored, not a rebuilt copy`() {
        val adapter = adapter()
        val admitted = admit(adapter)

        // Identity, not equality. A rebuild that produced equal fields is exactly the second
        // opinion about what the call was that this design exists to refuse — and it would pass
        // an `assertEquals` while being the thing that must not happen.
        assertSame(admitted, claimed(adapter.claim(claimOf(admitted))))
    }

    @Test
    fun `a claim is granted once and a duplicate caller is refused the second right`() {
        val adapter = adapter()
        val admitted = admit(adapter)

        claimed(adapter.claim(claimOf(admitted)))
        assertEquals(BridgeClaimRefusal.ALREADY_CLAIMED, refusal(adapter.claim(claimOf(admitted))))
    }

    @Test
    fun `a duplicate claim from a different run is refused as well`() {
        val adapter = adapter()
        val admitted = admit(adapter)

        claimed(adapter.claim(claimOf(admitted, runId = "run-1")))
        assertEquals(
            BridgeClaimRefusal.ALREADY_CLAIMED,
            refusal(adapter.claim(claimOf(admitted, runId = "run-2"))),
        )
    }

    @Test
    fun `a granted approval is recorded and still grants nothing twice`() {
        val adapter = adapter()
        val admitted = admit(adapter)

        val granted = claimOf(
            admitted,
            approval = BridgeExecutionApproval.Granted("approval-1", "exec-1"),
        )
        claimed(adapter.claim(granted))
        assertEquals(BridgeClaimRefusal.ALREADY_CLAIMED, refusal(adapter.claim(granted)))
    }

    // -----------------------------------------------------------------------------------------
    // Conflicts: the claim disagrees with the call the ledger admitted
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a claim naming another Server generation is refused`() {
        val adapter = adapter()
        val admitted = admit(adapter)

        assertEquals(
            BridgeClaimRefusal.CONFLICT,
            refusal(adapter.claim(claimOf(admitted, serverGenerationId = "gen-2"))),
        )
    }

    @Test
    fun `a claim carrying a different binding for the same call id is refused`() {
        val adapter = adapter()
        val admitted = admit(adapter)

        // The binding is compared whole rather than field-by-field at the call site, so a
        // conversation, branch, device, catalog or schema that changed after the call was
        // admitted cannot be waved through by naming the one field that still agrees.
        val elsewhere = admitted.binding.copy(branchId = "branch-2")
        assertEquals(
            BridgeClaimRefusal.CONFLICT,
            refusal(adapter.claim(claimOf(admitted, binding = elsewhere))),
        )
    }

    @Test
    fun `a claim carrying different arguments under the same call id is refused`() {
        val adapter = adapter()
        val admitted = admit(adapter)
        val other = admit(adapter, toolCallId = "call-2", arguments = args("/etc/passwd"))

        // Both the digest the ledger recorded beside the call and the binding it stored are
        // checked, so a claim cannot present the one that happens to still agree.
        assertEquals(
            BridgeClaimRefusal.CONFLICT,
            refusal(adapter.claim(claimOf(admitted, argsDigest = other.argsDigest))),
        )
        assertEquals(
            BridgeClaimRefusal.CONFLICT,
            refusal(adapter.claim(claimOf(admitted, binding = other.binding))),
        )
    }

    @Test
    fun `a claim naming a different tool for the same call id is refused`() {
        val adapter = adapter()
        val admitted = admit(adapter)

        assertEquals(
            BridgeClaimRefusal.CONFLICT,
            refusal(adapter.claim(claimOf(admitted, toolName = "write_file"))),
        )
    }

    @Test
    fun `a claim addressed at another generation is refused by this one`() {
        val adapter = adapter(generationId = "gen-1")
        val admitted = admit(adapter)

        assertEquals(
            BridgeClaimRefusal.CONFLICT,
            refusal(adapter.claim(claimOf(admitted, serverGenerationId = "gen-9"))),
        )
    }

    // -----------------------------------------------------------------------------------------
    // Nothing to claim
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a call this process never admitted cannot be claimed`() {
        val adapter = adapter()
        val admitted = admit(adapter)

        // The process-restart shape: the ledger is empty, so there is nothing to prove and
        // nothing to run. Emphatically not an invitation to re-run the tool to find out what it
        // did — a write whose result was never kept must not be executed twice.
        val stranger = claimOf(admitted).copy(toolCallId = "call-never-seen")
        assertEquals(BridgeClaimRefusal.NOT_FOUND, refusal(adapter.claim(stranger)))
        assertEquals(BridgeClaimRefusal.NOT_FOUND, adapter.admissible(stranger))
    }

    @Test
    fun `a record with no canonical invocation cannot be claimed`() {
        val ledger = BridgeLedger()
        val admitted = admit(adapter())

        // The shape a record would be in if a path ever admitted a call without keeping the
        // object. The claim refuses rather than assembling one from the request in front of it.
        ledger.admit(admitted.binding, admittedAtMonotonicMs = 0L, invocation = null)
        assertEquals(
            BridgeClaimRefusal.INTERRUPTED,
            refusal(ledger.claimForExecution(claimOf(admitted), nowMonotonicMs = 0L)),
        )
    }

    // -----------------------------------------------------------------------------------------
    // Lifecycle: what can change while the approval was open
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a call whose deadline elapsed while the approval was open cannot be claimed`() {
        val clock = Clock()
        val adapter = adapter(timeoutMs = 1_000, clock = clock)
        val admitted = admit(adapter)

        clock.advance()
        assertEquals(BridgeClaimRefusal.TIMED_OUT, refusal(adapter.claim(claimOf(admitted))))
    }

    @Test
    fun `a call that settled while the approval was open cannot be claimed`() {
        val adapter = adapter()
        val admitted = admit(adapter)

        adapter.complete(admitted.binding.toolCallId, ToolCallState.COMPLETED, "body")
        assertEquals(BridgeClaimRefusal.INTERRUPTED, refusal(adapter.claim(claimOf(admitted))))
    }

    @Test
    fun `a call a cancel reached cannot be claimed`() {
        val adapter = adapter()
        val admitted = admit(adapter)

        assertEquals(BridgeCancelDecision.Propagate, adapter.onCancel(admitted.binding.toolCallId))
        assertEquals(BridgeClaimRefusal.CANCELLED, refusal(adapter.claim(claimOf(admitted))))
    }

    @Test
    fun `a call whose generation began closing cannot be claimed`() {
        val adapter = adapter()
        val admitted = admit(adapter)

        adapter.concludeForClosedGeneration()
        assertEquals(BridgeClaimRefusal.CANCELLED, refusal(adapter.claim(claimOf(admitted))))
    }

    // -----------------------------------------------------------------------------------------
    // Malformed claims
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a claim with no run id is refused`() {
        val adapter = adapter()
        val admitted = admit(adapter)

        assertEquals(BridgeClaimRefusal.REFUSED, refusal(adapter.claim(claimOf(admitted, runId = ""))))
    }

    @Test
    fun `an asserted approval with no identity is refused`() {
        val adapter = adapter()
        val admitted = admit(adapter)

        assertEquals(
            BridgeClaimRefusal.APPROVAL_REQUIRED,
            refusal(
                adapter.claim(
                    claimOf(admitted, approval = BridgeExecutionApproval.Granted("", "exec-1")),
                ),
            ),
        )
        assertEquals(
            BridgeClaimRefusal.APPROVAL_REQUIRED,
            refusal(
                adapter.claim(
                    claimOf(admitted, approval = BridgeExecutionApproval.Granted("approval-1", "")),
                ),
            ),
        )
    }

    // -----------------------------------------------------------------------------------------
    // Admissibility: the same rules, read-only
    // -----------------------------------------------------------------------------------------

    @Test
    fun `admissibility grants nothing and consumes nothing`() {
        val adapter = adapter()
        val admitted = admit(adapter)

        assertNull(adapter.admissible(claimOf(admitted)))
        assertNull(adapter.admissible(claimOf(admitted)))
        // The read must not have taken the right: the claim still succeeds afterwards.
        claimed(adapter.claim(claimOf(admitted)))
    }

    @Test
    fun `admissibility refuses what the claim would refuse`() {
        val clock = Clock()
        val adapter = adapter(timeoutMs = 1_000, clock = clock)
        val admitted = admit(adapter)

        assertNull(adapter.admissible(claimOf(admitted)))
        clock.advance()
        assertEquals(BridgeClaimRefusal.TIMED_OUT, adapter.admissible(claimOf(admitted)))
    }

    @Test
    fun `admissibility reports a generation that has begun closing`() {
        val adapter = adapter()
        val admitted = admit(adapter)

        adapter.concludeForClosedGeneration()
        assertEquals(BridgeClaimRefusal.CANCELLED, adapter.admissible(claimOf(admitted)))
    }

    // -----------------------------------------------------------------------------------------
    // Redaction: the canonical invocation is the Server's, not the UI's copy
    // -----------------------------------------------------------------------------------------

    @Test
    fun `redacting a copy of the call does not change the invocation the claim returns`() {
        val adapter = adapter()
        val secret = "sk-live-0123456789abcdef"
        val admitted = admit(adapter, arguments = args("/a", token = secret))

        // The app rewrites `UIMessagePart.Tool.input` before it reaches the conversation, so a
        // *copy* of this call exists elsewhere with the secret removed. What the claim returns
        // must be the call the Server sent — the one whose arguments the tool is meant to get.
        val redactedCopy = admitted.copy(
            arguments = JsonObject(linkedMapOf("path" to JsonPrimitive("[redacted]"))),
        )
        assertTrue(redactedCopy.argsDigest == admitted.argsDigest)

        val canonical = claimed(adapter.claim(claimOf(admitted)))
        assertSame(admitted, canonical)
        assertTrue(canonical.arguments.toString().contains(secret))
    }
}
