package me.rerere.rikkahub.data.claudep

import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import me.rerere.ai.ui.MessageChunk
import me.rerere.rikkahub.data.execution.ApprovalContinuationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one-shot publication receipt: two identities kept apart, exactly-once, no ordering that
 * deadlocks.
 *
 * ## What these tests can and cannot prove
 *
 * They exercise the **registry** and the **sequencing contract** it exists to enforce. They cannot
 * prove the Room transaction: `ChatService` completes a receipt only after
 * `RuntimeRunAuthority.checkpointWaiting` returns, and that call needs an `AppDatabase` and five
 * collaborators, with no in-memory harness in this module. The atomicity of "card applied and
 * barrier written, or neither" is therefore **pinned here as a contract and left unverified as a
 * database fact** — the managed-device items named in the batch report are what still has to run
 * on a device. Nothing below is described as Room evidence.
 *
 * ## Why there is no `sleep` anywhere
 *
 * Ordering is forced with `CompletableDeferred` handshakes and with the registry's own state, so a
 * test says what happened rather than hoping it happened. Every wait that crosses a coroutine is
 * wrapped in `withTimeout`, so a regression that reintroduced a cycle **fails** instead of hanging.
 */
class ClaudePToolPublicationReceiptsTest {

    /** The Server's generation id. Never a run id, and never substituted by one anywhere below. */
    private val serverGenerationId = "server-gen-1"

    private val otherServerGenerationId = "server-gen-2"

    private fun invocation(
        serverGenerationId: String = this.serverGenerationId,
        runId: String = "run-1",
        toolCallId: String = "call-1",
        toolName: String = "read_file",
        argsDigest: String = "args-digest-1",
    ) = ClaudePToolPublicationInvocation(
        serverGenerationId = serverGenerationId,
        runId = runId,
        toolCallId = toolCallId,
        toolName = toolName,
        argsDigest = argsDigest,
    )

    private fun key(
        runId: String = "run-1",
        toolCallId: String = "call-1",
        toolName: String = "read_file",
    ) = ClaudePToolPublicationKey.of(runId, toolCallId, toolName)

    /**
     * A registry whose run is already paired with its Server generation.
     *
     * That pairing is what the host records at `openGeneration`, and nothing here can publish
     * without it — which is the first of the properties below.
     */
    private fun registry(maxEntries: Int = 64): ClaudePToolPublicationReceipts =
        ClaudePToolPublicationReceipts(maxEntries).also {
            assertTrue(
                "the fixture's own pairing must be admitted",
                it.bindGeneration(serverGenerationId = serverGenerationId, runId = "run-1"),
            )
        }

    /** `begin`, asserted to have been admitted, returning the handle. */
    private fun began(
        receipts: ClaudePToolPublicationReceipts,
        runId: String = "run-1",
        toolCallId: String = "call-1",
        toolName: String = "read_file",
        argsDigest: String = "args-digest-1",
        serverGenerationId: String = this.serverGenerationId,
    ): ClaudePToolPublicationRequest {
        val request = receipts.begin(
            invocation(
                serverGenerationId = serverGenerationId,
                runId = runId,
                toolCallId = toolCallId,
                toolName = toolName,
                argsDigest = argsDigest,
            ),
        )
        assertNotNull("begin must admit ($runId, $toolCallId, $toolName)", request)
        return request!!
    }

    private val committed = ClaudePToolPublicationOutcome.Committed(
        approvalId = "approval-1",
        executionId = "execution-1",
    )

    // ---------------------------------------------------------------------------------------
    // 1. The two identities are separate, and both are required
    // ---------------------------------------------------------------------------------------

    /**
     * A publication cannot exist before its run and Server generation have been paired.
     *
     * The pairing is recorded once, when the generation is opened, and this is what makes a key
     * that was never proved unusable: there is no lookup by conversation, assistant or recency
     * that could stand in for it.
     */
    @Test
    fun `a publication is refused before its generation is bound`() {
        val receipts = ClaudePToolPublicationReceipts()

        assertNull(receipts.begin(invocation()))
        assertEquals(0, receipts.pendingCount)
    }

    /**
     * A run cannot be paired with two generations, and a generation cannot serve two runs.
     *
     * The first half is the case a caller holding *right run, wrong generation* produces; the
     * second is *right generation, wrong run*. Both are refused, and refusing them is what makes
     * the record an association rather than a hint.
     */
    @Test
    fun `a contradicted pairing is refused in both directions`() {
        val receipts = ClaudePToolPublicationReceipts()

        assertTrue(receipts.bindGeneration(serverGenerationId, "run-1"))
        assertFalse(
            "one run cannot serve a second generation",
            receipts.bindGeneration(otherServerGenerationId, "run-1"),
        )
        assertFalse(
            "one generation cannot be served by a second run",
            receipts.bindGeneration(serverGenerationId, "run-2"),
        )
        assertEquals("and the first pairing still stands", serverGenerationId, receipts.serverGenerationIdFor("run-1"))
    }

    /**
     * A publication whose generation does not match the pairing is refused, on either side.
     *
     * This is the required case spelled out: right run with the wrong Server generation, and the
     * right Server generation with the wrong run. Neither is a near-miss to be resolved — a
     * publication is a claim that a specific remote generation's call belongs to a specific local
     * run, and a claim that cannot be checked against the record is not one this side may act on.
     */
    @Test
    fun `a publication for a mismatched pairing is refused`() {
        val receipts = registry()

        assertNull(
            "right run, wrong Server generation",
            receipts.begin(invocation(serverGenerationId = otherServerGenerationId)),
        )
        assertNull(
            "right Server generation, wrong run",
            receipts.begin(invocation(runId = "run-2")),
        )
        assertEquals("nothing was admitted", 0, receipts.pendingCount)
    }

    /** Closing a generation drops its association, and a later publication cannot re-pair it. */
    @Test
    fun `closing a generation retires the pairing`() {
        val receipts = registry()

        receipts.unbindGeneration(serverGenerationId, ClaudePToolPublicationAbandonReason.GENERATION_CLOSED)

        assertEquals(0, receipts.boundRunCount)
        assertNull("the run can no longer publish", receipts.begin(invocation()))
        assertNull(receipts.serverGenerationIdFor("run-1"))
    }

    /**
     * The invocation record is immutable, and the argument digest is part of it.
     *
     * The digest comes from the Server's original invocation, never from a digest recomputed over
     * the conversation's copy of the arguments — that copy has been through
     * `RuntimeSecretRedactor`, so a digest taken from it would disagree with the ledger for exactly
     * the calls that carry a secret. Recording the original here is what makes "the same
     * invocation" checkable without ever moving the arguments themselves.
     */
    @Test
    fun `the recorded invocation carries the original argument digest`() {
        val receipts = registry()
        val request = began(receipts, argsDigest = "digest-from-the-server")

        assertEquals("digest-from-the-server", request.invocation.argsDigest)
        assertEquals(serverGenerationId, request.invocation.serverGenerationId)
        assertEquals("run-1", request.invocation.runId)
        assertEquals(
            "and the key is the half the authority rebuilds",
            key(),
            request.key,
        )
    }

    /** Two calls that share a key but differ in any recorded field are not the same call. */
    @Test
    fun `sameCallAs compares every recorded field`() {
        val original = invocation()

        assertTrue(original.sameCallAs(invocation()))
        assertFalse(original.sameCallAs(invocation(argsDigest = "different")))
        assertFalse(original.sameCallAs(invocation(toolName = "write_file")))
        assertFalse(original.sameCallAs(invocation(toolCallId = "call-2")))
        assertFalse(original.sameCallAs(invocation(runId = "run-2")))
        assertFalse(original.sameCallAs(invocation(serverGenerationId = otherServerGenerationId)))
    }

    /**
     * A key built from a **redacted** part still matches.
     *
     * This is the property that makes the whole in-flight path work for a call whose arguments
     * carry a secret. The conversation authority sees `UIMessagePart.Tool.input` after
     * `RuntimeSecretRedactor` has rewritten it, and it rebuilds the key from the run, the call id
     * and the tool name — none of which redaction touches. So the completion arrives, while the
     * argument digest the registry holds is still the one the Server sent.
     */
    @Test
    fun `redaction of the arguments does not change the key`() = runBlocking {
        val receipts = registry()
        val request = began(receipts, argsDigest = "original-args-digest")

        // What the authority can rebuild after the redactor has rewritten the part's input.
        val rebuiltAfterRedaction = ClaudePToolPublicationKey.of(
            runId = "run-1",
            toolCallId = "call-1",
            toolName = "read_file",
        )

        assertTrue(receipts.complete(rebuiltAfterRedaction, "approval-1", "execution-1"))
        assertEquals(committed, request.await(5_000))
        assertEquals(
            "and the original digest is what the publisher still holds",
            "original-args-digest",
            request.invocation.argsDigest,
        )
    }

    // ---------------------------------------------------------------------------------------
    // 2. The answer is the authority's, and it arrives exactly once
    // ---------------------------------------------------------------------------------------

    /** A committed receipt carries the identities the authority derived, not ones re-derived here. */
    @Test
    fun `a committed receipt carries the authority's own identities`() = runBlocking {
        val receipts = registry()
        val request = began(receipts)

        assertTrue(receipts.complete(key(), "approval-9", "execution-9"))

        assertEquals(
            ClaudePToolPublicationOutcome.Committed("approval-9", "execution-9"),
            request.await(5_000),
        )
    }

    /**
     * A duplicate changes nothing — not the delivered answer, and not the delivered-ness.
     *
     * This is the case a second tap, a replayed frame or a retried acknowledgement produces. The
     * second call must return `false` and leave the first answer intact: a receipt that could be
     * overwritten would let a later, staler decision replace the one a waiter already took.
     */
    @Test
    fun `a duplicate completion changes nothing`() = runBlocking {
        val receipts = registry()
        val request = began(receipts)

        assertTrue("the first completion settles the key", receipts.complete(key(), "a-1", "e-1"))
        assertFalse("the second is a no-op", receipts.complete(key(), "a-2", "e-2"))
        assertFalse("and a refusal after it is too", receipts.refuse(key(), "late"))

        assertEquals(ClaudePToolPublicationOutcome.Committed("a-1", "e-1"), request.await(5_000))
    }

    /** A refusal is terminal in the same way, and is what a rolled-back barrier must produce. */
    @Test
    fun `a refused publication never becomes a commit`() = runBlocking {
        val receipts = registry()
        val request = began(receipts)

        assertTrue(receipts.refuse(key(), "approval_authority_rollback"))
        assertFalse(receipts.complete(key(), "a-1", "e-1"))

        assertEquals(
            ClaudePToolPublicationOutcome.Refused("approval_authority_rollback"),
            request.await(5_000),
        )
    }

    // ---------------------------------------------------------------------------------------
    // 3. Wrong identity does not touch the right waiter
    // ---------------------------------------------------------------------------------------

    /**
     * Every field of the key is load-bearing, checked one at a time.
     *
     * A completion that names a different run, a different call or a different tool is a completion
     * for something else, and the waiter it does not describe must stay exactly where it was —
     * still waiting, not released, not refused.
     */
    @Test
    fun `a completion for another identity leaves the right waiter untouched`() = runBlocking {
        val receipts = registry()
        val request = began(receipts)

        for (wrong in listOf(
            key(runId = "run-other"),
            key(toolCallId = "call-other"),
            key(toolName = "write_file"),
        )) {
            assertFalse("$wrong must not settle ${key()}", receipts.complete(wrong, "a-x", "e-x"))
        }

        assertEquals("the real waiter is still live", 1, receipts.pendingCount)
        assertEquals(
            "and still unanswered",
            ClaudePToolPublicationOutcome.Abandoned(ClaudePToolPublicationAbandonReason.TIMEOUT),
            request.await(50),
        )
    }

    /** A key nobody began settles nothing, and does not grow the registry into doing so. */
    @Test
    fun `a key that was never begun settles nothing`() {
        val receipts = registry()
        began(receipts)

        assertFalse(receipts.complete(key(runId = "run-other"), "a", "e"))
        assertEquals(1, receipts.pendingCount)
        assertEquals("nothing was recorded as settled", 0, receipts.settledCount)
    }

    /**
     * A key cannot be published twice while it is live, or while its answer is still remembered.
     *
     * Re-arming a settled key is how a stale duplicate would release a *new* request it does not
     * describe. This also covers the same call id carrying **different arguments**: the key does
     * not include the argument digest — it cannot, the authority cannot rebuild it — so the refusal
     * here is what keeps a second call with different arguments from taking over the first one's
     * pending answer.
     */
    @Test
    fun `a key cannot be re-armed while it is live or settled`() {
        val receipts = registry()

        assertNotNull(receipts.begin(invocation()))
        assertNull("live", receipts.begin(invocation()))
        assertNull(
            "and a live key with different arguments is the same key",
            receipts.begin(invocation(argsDigest = "tampered")),
        )

        assertTrue(receipts.complete(key(), "a", "e"))
        assertNull("settled and not yet released", receipts.begin(invocation()))

        receipts.release(key())
        assertNotNull("released, so publishable again", receipts.begin(invocation()))
    }

    // ---------------------------------------------------------------------------------------
    // 4. Both orderings are correct, and neither deadlocks
    // ---------------------------------------------------------------------------------------

    /**
     * Ordering A — the authority committed before the publisher got around to waiting.
     *
     * The deadline is deliberately tiny. A correct registry returns the recorded answer without
     * consulting it; a registry that only completed *present* waiters would block for the whole
     * deadline and come back `TIMEOUT`, which is exactly the failure this asserts against.
     */
    @Test
    fun `a receipt committed before the publisher waits is returned immediately`() = runBlocking {
        val receipts = registry()
        val request = began(receipts)

        assertTrue(receipts.complete(key(), "approval-1", "execution-1"))

        assertEquals(committed, request.await(50))
    }

    /**
     * Ordering B — the publisher is already suspended on another thread when the commit lands.
     *
     * This is the ordinary case, and the one the whole design rests on: `ChatService` commits on
     * one coroutine while the host is suspended on another. The handshake makes the arrangement
     * explicit — the registry is asserted to hold a live entry before the authority acts — and the
     * `withTimeout` turns a reintroduced cycle into a failure rather than a hung build.
     */
    @Test
    fun `a receipt committed while the publisher is suspended releases it`() = runBlocking {
        val receipts = registry()
        val request = began(receipts)

        val publisherStarted = CompletableDeferred<Unit>()
        val publisher = async(Dispatchers.Default) {
            publisherStarted.complete(Unit)
            request.await(5_000)
        }
        publisherStarted.await()
        assertEquals("the publisher is registered", 1, receipts.pendingCount)

        // The authority's half, on this thread, against a genuinely suspended waiter.
        assertTrue(receipts.complete(key(), "approval-1", "execution-1"))

        assertEquals(committed, withTimeout(5_000) { publisher.await() })
        assertEquals("and nothing is left behind", 0, receipts.pendingCount)
    }

    /**
     * The authority never needs a lock the publisher is holding.
     *
     * A whole commit cycle run *while* waits are suspended is the shape that would deadlock if
     * `settle` completed its deferred under the registry lock, or if registration and completion
     * ever had to acquire the same critical section in opposite orders.
     */
    @Test
    fun `committing does not require the lock a suspended wait holds`() = runBlocking {
        val receipts = registry()
        val requests = (1..4).map { began(receipts, toolCallId = "call-$it") }
        assertEquals(4, receipts.pendingCount)

        val started = CompletableDeferred<Unit>()
        val waiters = requests.map { request ->
            async(Dispatchers.Default) {
                started.complete(Unit)
                request.await(5_000)
            }
        }
        started.await()

        // All four settle from here, while all four waits are suspended elsewhere.
        assertTrue((1..4).all { receipts.complete(key(toolCallId = "call-$it"), "a-$it", "e-$it") })

        assertEquals(
            (1..4).map { ClaudePToolPublicationOutcome.Committed("a-$it", "e-$it") },
            withTimeout(5_000) { waiters.map { it.await() } },
        )
    }

    // ---------------------------------------------------------------------------------------
    // 5. Every way a wait can end without a commit
    // ---------------------------------------------------------------------------------------

    /** The caller's own deadline — the tool deadline, borrowed rather than invented. */
    @Test
    fun `a wait that outlives its deadline is abandoned and releases nothing`() = runBlocking {
        val receipts = registry()
        val request = began(receipts)

        assertEquals(
            ClaudePToolPublicationOutcome.Abandoned(ClaudePToolPublicationAbandonReason.TIMEOUT),
            request.await(50),
        )

        // A completion that lands afterwards must not resurrect anything: the host has released,
        // so there is no entry for it to settle.
        receipts.release(key())
        assertFalse(receipts.complete(key(), "a", "e"))
        assertEquals(0, receipts.pendingCount)
        assertEquals(0, receipts.settledCount)
    }

    /** A cancel reaching the call before any commit ends the wait, and stays closed. */
    @Test
    fun `a cancel ends the wait and a later commit does not reopen it`() = runBlocking {
        val receipts = registry()
        val request = began(receipts)

        val started = CompletableDeferred<Unit>()
        val waiting = async(Dispatchers.Default) {
            started.complete(Unit)
            request.await(5_000)
        }
        started.await()

        request.cancel(ClaudePToolPublicationAbandonReason.CANCELLED)
        assertEquals(
            ClaudePToolPublicationOutcome.Abandoned(ClaudePToolPublicationAbandonReason.CANCELLED),
            withTimeout(5_000) { waiting.await() },
        )

        receipts.release(key())
        assertFalse("a commit after the cancel changes nothing", receipts.complete(key(), "a", "e"))
    }

    /** A generation ending takes its own publications and nothing else's. */
    @Test
    fun `a generation close abandons only its own publications`() = runBlocking {
        val receipts = registry()
        assertTrue(receipts.bindGeneration(otherServerGenerationId, "run-2"))
        val mine = began(receipts)
        val theirs = began(
            receipts,
            runId = "run-2",
            toolCallId = "call-2",
            // A second run is a second generation: publishing it under the first run's Server id
            // is the mismatched pairing the registry refuses, so the fixture states its own.
            serverGenerationId = otherServerGenerationId,
        )

        receipts.unbindGeneration(
            serverGenerationId,
            ClaudePToolPublicationAbandonReason.GENERATION_CLOSED,
        )

        assertEquals(
            ClaudePToolPublicationOutcome.Abandoned(
                ClaudePToolPublicationAbandonReason.GENERATION_CLOSED,
            ),
            mine.await(5_000),
        )
        assertEquals("the other generation is untouched", 1, receipts.pendingCount)
        assertTrue(receipts.complete(key(runId = "run-2", toolCallId = "call-2"), "a-2", "e-2"))
        assertEquals(
            ClaudePToolPublicationOutcome.Committed("a-2", "e-2"),
            theirs.await(5_000),
        )
    }

    /** A run ending takes its own publications but keeps its generation pairing. */
    @Test
    fun `a run close abandons its publications and keeps the pairing`() = runBlocking {
        val receipts = registry()
        val request = began(receipts)

        receipts.abandonRun("run-1", ClaudePToolPublicationAbandonReason.CANCELLED)

        assertEquals(
            ClaudePToolPublicationOutcome.Abandoned(ClaudePToolPublicationAbandonReason.CANCELLED),
            request.await(5_000),
        )
        assertEquals("the pairing survives a run-scoped close", serverGenerationId, receipts.serverGenerationIdFor("run-1"))
        assertNotNull("so the run may publish again", receipts.begin(invocation(toolCallId = "call-2")))
    }

    /** A registry close ends everything, and does not un-happen an answer already given. */
    @Test
    fun `a registry close ends every wait`() = runBlocking {
        val receipts = registry()
        val answered = began(receipts, toolCallId = "call-answered")
        val live = began(receipts, toolCallId = "call-live")
        assertTrue(receipts.complete(key(toolCallId = "call-answered"), "a-1", "e-1"))

        receipts.abandonAll(ClaudePToolPublicationAbandonReason.REGISTRY_CLOSED)

        assertEquals(
            ClaudePToolPublicationOutcome.Committed("a-1", "e-1"),
            answered.await(5_000),
        )
        assertEquals(
            ClaudePToolPublicationOutcome.Abandoned(
                ClaudePToolPublicationAbandonReason.REGISTRY_CLOSED,
            ),
            live.await(5_000),
        )
        assertEquals(0, receipts.pendingCount)
        assertEquals("and the associations are gone with it", 0, receipts.boundRunCount)
    }

    // ---------------------------------------------------------------------------------------
    // 6. Bounds, and no residue on any path
    // ---------------------------------------------------------------------------------------

    /**
     * The registry is bounded, and going over the bound costs a capability rather than an execution.
     *
     * The evicted publication is *completed* as abandoned rather than dropped, so a publisher
     * blocked on it fails closed now instead of sitting until its deadline.
     */
    @Test
    fun `an evicted publication fails closed rather than hanging`() = runBlocking {
        val receipts = registry(maxEntries = 2)
        val first = began(receipts, toolCallId = "call-1")
        began(receipts, toolCallId = "call-2")
        began(receipts, toolCallId = "call-3")

        assertTrue("stays inside the bound", receipts.pendingCount <= 2)
        assertEquals(
            ClaudePToolPublicationOutcome.Abandoned(ClaudePToolPublicationAbandonReason.EVICTED),
            first.await(5_000),
        )
    }

    /** Terminal keys are remembered under the same bound, so `settled` cannot grow either. */
    @Test
    fun `remembered terminal keys are bounded too`() {
        val receipts = registry(maxEntries = 2)
        repeat(5) { index ->
            began(receipts, toolCallId = "call-$index")
            assertTrue(receipts.complete(key(toolCallId = "call-$index"), "a-$index", "e-$index"))
        }

        assertEquals(0, receipts.pendingCount)
        assertTrue("settled keys stay inside the bound", receipts.settledCount <= 2)
    }

    /**
     * Every terminal path leaves the registry empty once the publisher is done with the key.
     *
     * A leak here is not a crash: it is a map that slowly stops accepting new publications, which
     * presents as approval-gated calls that time out for no visible reason.
     */
    @Test
    fun `no path leaves an entry behind once released`() = runBlocking {
        val receipts = registry()

        began(receipts, toolCallId = "call-committed").let {
            assertTrue(receipts.complete(key(toolCallId = "call-committed"), "a", "e"))
            receipts.release(it.key)
        }

        began(receipts, toolCallId = "call-refused").let {
            assertTrue(receipts.refuse(it.key, "rollback"))
            receipts.release(it.key)
        }

        began(receipts, toolCallId = "call-timeout").let {
            it.await(20)
            receipts.release(it.key)
        }

        began(receipts, toolCallId = "call-cancelled").let {
            it.cancel(ClaudePToolPublicationAbandonReason.CANCELLED)
            receipts.release(it.key)
        }

        began(receipts, toolCallId = "call-closed").let {
            receipts.unbindGeneration(
                serverGenerationId,
                ClaudePToolPublicationAbandonReason.GENERATION_CLOSED,
            )
            receipts.release(it.key)
        }

        assertEquals("no live entries", 0, receipts.pendingCount)
        assertEquals("no remembered terminals", 0, receipts.settledCount)
        assertTrue(receipts.pendingKeys().isEmpty())
    }

    // ---------------------------------------------------------------------------------------
    // 7. It stays inside the process
    // ---------------------------------------------------------------------------------------

    /**
     * The continuation rides on the provider chunk in memory and nowhere else.
     *
     * `MessageChunk` is the carrier that actually crosses the provider boundary and it is
     * `@Serializable`, so this half is checked by **serializing one** and looking at the bytes
     * rather than by reading the declaration.
     */
    @Test
    fun `the continuation never enters a serialized provider chunk`() {
        val encoded = Json.encodeToString(
            MessageChunk(
                id = "gen-1",
                model = "claude_p",
                choices = emptyList(),
                pendingApprovalContinuation = ApprovalContinuationMode.IN_FLIGHT.name,
            ),
        )

        assertFalse(encoded, encoded.contains("IN_FLIGHT"))
        assertFalse(encoded, encoded.contains("pendingApprovalContinuation"))
    }

    /**
     * The generation-side chunk is checked at its declaration, and that is a deliberate downgrade.
     *
     * `GenerationChunk.Messages` is **not** `@Serializable` — the annotation on the interface is
     * vestigial, nothing in the app encodes it, and it is only ever a `Flow` element inside one
     * process. Adding a serializer to the subclass purely so this test could encode it would be a
     * production change made for the test's benefit, which is the wrong direction for a batch whose
     * whole discipline is not touching production shapes to make a test pass.
     *
     * So what is asserted is the mechanism the field actually relies on: that `continuationMode` is
     * declared `@Transient`, which is what makes "it cannot reach a persisted, serialized or
     * projected form" a property of the declaration rather than a rule someone has to remember.
     */
    @Test
    fun `the generation chunk declares the continuation transient`() {
        val declaration = projectFile(
            "app/src/main/java/me/rerere/rikkahub/data/ai/GenerationHandler.kt",
            "src/main/java/me/rerere/rikkahub/data/ai/GenerationHandler.kt",
        ).readText(Charsets.UTF_8)
            .substringAfter("data class Messages(")
            .substringBefore(") : GenerationChunk")

        assertTrue(
            "continuationMode must be declared @Transient:\n$declaration",
            Regex("""@Transient\s+val continuationMode""").containsMatchIn(declaration),
        )
    }

    /**
     * A publication identity renders as pseudonyms, so a log line or a test failure cannot leak it.
     *
     * Both halves are checked, including the Server's generation id — which is the one most easily
     * mistaken for something harmless to print.
     */
    @Test
    fun `a publication identity does not print what it holds`() {
        val rendered = invocation(
            serverGenerationId = "server-gen-secret",
            runId = "run-secret",
            toolCallId = "call-secret",
            toolName = "read_secret_file",
            argsDigest = "args-secret",
        ).toString() + " " + key(runId = "run-secret").toString()

        for (secret in listOf(
            "server-gen-secret",
            "run-secret",
            "call-secret",
            "read_secret_file",
            "args-secret",
        )) {
            assertFalse(rendered, rendered.contains(secret))
        }
    }

    /** Two different invocations of the same call id are two different publications. */
    @Test
    fun `the tool name is part of the identity`() {
        assertFalse(key(toolName = "read_file") == key(toolName = "write_file"))
        assertEquals(key(toolName = "read_file"), key(toolName = "read_file"))
    }

    private fun projectFile(vararg candidates: String): File =
        requireNotNull(candidates.asSequence().map(::File).firstOrNull(File::isFile)) {
            "Cannot locate ${candidates.joinToString()} from ${File(".").absolutePath}"
        }
}
