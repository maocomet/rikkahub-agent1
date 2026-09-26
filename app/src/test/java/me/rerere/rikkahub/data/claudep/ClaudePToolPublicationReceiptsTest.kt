package me.rerere.rikkahub.data.claudep

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
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
 * The one-shot publication receipt: exactly-once, exact identity, and no ordering that deadlocks.
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

    private fun id(
        generationId: String = "run-1",
        toolCallId: String = "call-1",
        toolName: String = "read_file",
    ) = ClaudePToolPublicationId.of(generationId, toolCallId, toolName)

    /** `begin`, asserted to have been admitted, returning the handle. */
    private fun ClaudePToolPublicationReceipts.began(
        key: ClaudePToolPublicationId,
    ): ClaudePToolPublicationRequest {
        val request = begin(key)
        assertNotNull("begin must admit $key", request)
        return request!!
    }

    private val committed = ClaudePToolPublicationOutcome.Committed(
        approvalId = "approval-1",
        executionId = "execution-1",
    )

    // ---------------------------------------------------------------------------------------
    // 1. The answer is the authority's, and it arrives exactly once
    // ---------------------------------------------------------------------------------------

    /** A committed receipt carries the identities the authority derived, not ones re-derived here. */
    @Test
    fun `a committed receipt carries the authority's own identities`() = runBlocking {
        val receipts = ClaudePToolPublicationReceipts()
        val key = id()
        val request = receipts.began(key)

        assertTrue(receipts.complete(key, "approval-9", "execution-9"))

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
        val receipts = ClaudePToolPublicationReceipts()
        val key = id()
        val request = receipts.began(key)

        assertTrue("the first completion settles the id", receipts.complete(key, "a-1", "e-1"))
        assertFalse("the second is a no-op", receipts.complete(key, "a-2", "e-2"))
        assertFalse("and a refusal after it is too", receipts.refuse(key, "late"))

        assertEquals(ClaudePToolPublicationOutcome.Committed("a-1", "e-1"), request.await(5_000))
    }

    /** A refusal is terminal in the same way, and is what a rolled-back barrier must produce. */
    @Test
    fun `a refused publication never becomes a commit`() = runBlocking {
        val receipts = ClaudePToolPublicationReceipts()
        val key = id()
        val request = receipts.began(key)

        assertTrue(receipts.refuse(key, "approval_authority_rollback"))
        assertFalse(receipts.complete(key, "a-1", "e-1"))

        assertEquals(
            ClaudePToolPublicationOutcome.Refused("approval_authority_rollback"),
            request.await(5_000),
        )
    }

    // ---------------------------------------------------------------------------------------
    // 2. Wrong identity does not touch the right waiter
    // ---------------------------------------------------------------------------------------

    /**
     * Every field of the id is load-bearing, checked one at a time.
     *
     * A completion that names a different generation, a different call or a different tool is a
     * completion for something else, and the waiter it does not describe must stay exactly where it
     * was — still waiting, not released, not refused.
     */
    @Test
    fun `a completion for another identity leaves the right waiter untouched`() = runBlocking {
        val receipts = ClaudePToolPublicationReceipts()
        val key = id()
        val request = receipts.began(key)

        for (wrong in listOf(
            id(generationId = "run-other"),
            id(toolCallId = "call-other"),
            id(toolName = "write_file"),
        )) {
            assertFalse("$wrong must not settle $key", receipts.complete(wrong, "a-x", "e-x"))
        }

        assertEquals("the real waiter is still live", 1, receipts.pendingCount)
        assertEquals(
            "and still unanswered",
            ClaudePToolPublicationOutcome.Abandoned(ClaudePToolPublicationAbandonReason.TIMEOUT),
            request.await(50),
        )
    }

    /** An id nobody began settles nothing, and does not grow the registry into doing so. */
    @Test
    fun `an id that was never begun settles nothing`() {
        val receipts = ClaudePToolPublicationReceipts()
        receipts.began(id())

        assertFalse(receipts.complete(id(generationId = "run-other"), "a", "e"))
        assertEquals(1, receipts.pendingCount)
        assertEquals("nothing was recorded as settled", 0, receipts.settledCount)
    }

    /**
     * An id cannot be published twice while it is live, or while its answer is still remembered.
     *
     * Re-arming a settled id is how a stale duplicate would release a *new* request it does not
     * describe. Refusing keeps one entry per id, which is what makes "exactly once" true by
     * construction rather than by timing.
     */
    @Test
    fun `an id cannot be re-armed while it is live or settled`() {
        val receipts = ClaudePToolPublicationReceipts()
        val key = id()

        assertNotNull(receipts.begin(key))
        assertNull("live", receipts.begin(key))

        assertTrue(receipts.complete(key, "a", "e"))
        assertNull("settled and not yet released", receipts.begin(key))

        receipts.release(key)
        assertNotNull("released, so publishable again", receipts.begin(key))
    }

    // ---------------------------------------------------------------------------------------
    // 3. Both orderings are correct, and neither deadlocks
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
        val receipts = ClaudePToolPublicationReceipts()
        val key = id()
        val request = receipts.began(key)

        assertTrue(receipts.complete(key, "approval-1", "execution-1"))

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
        val receipts = ClaudePToolPublicationReceipts()
        val key = id()
        val request = receipts.began(key)

        val publisherStarted = CompletableDeferred<Unit>()
        val publisher = async(Dispatchers.Default) {
            publisherStarted.complete(Unit)
            request.await(5_000)
        }
        publisherStarted.await()
        assertEquals("the publisher is registered", 1, receipts.pendingCount)

        // The authority's half, on this thread, against a genuinely suspended waiter.
        assertTrue(receipts.complete(key, "approval-1", "execution-1"))

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
        val receipts = ClaudePToolPublicationReceipts()
        val keys = (1..4).map { id(toolCallId = "call-$it") }
        val requests = keys.map { receipts.began(it) }
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
        assertTrue(keys.all { receipts.complete(it, "approval-$it", "execution-$it") })

        assertEquals(
            keys.map { ClaudePToolPublicationOutcome.Committed("approval-$it", "execution-$it") },
            withTimeout(5_000) { waiters.map { it.await() } },
        )
    }

    // ---------------------------------------------------------------------------------------
    // 4. Every way a wait can end without a commit
    // ---------------------------------------------------------------------------------------

    /** The caller's own deadline — the tool deadline, borrowed rather than invented. */
    @Test
    fun `a wait that outlives its deadline is abandoned and releases nothing`() = runBlocking {
        val receipts = ClaudePToolPublicationReceipts()
        val key = id()
        val request = receipts.began(key)

        assertEquals(
            ClaudePToolPublicationOutcome.Abandoned(ClaudePToolPublicationAbandonReason.TIMEOUT),
            request.await(50),
        )

        // A completion that lands afterwards must not resurrect anything: the host has released,
        // so there is no entry for it to settle.
        receipts.release(key)
        assertFalse(receipts.complete(key, "a", "e"))
        assertEquals(0, receipts.pendingCount)
        assertEquals(0, receipts.settledCount)
    }

    /** A cancel reaching the call before any commit ends the wait, and stays closed. */
    @Test
    fun `a cancel ends the wait and a later commit does not reopen it`() = runBlocking {
        val receipts = ClaudePToolPublicationReceipts()
        val key = id()
        val request = receipts.began(key)

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

        receipts.release(key)
        assertFalse("a commit after the cancel changes nothing", receipts.complete(key, "a", "e"))
    }

    /** A generation ending takes its own publications and nothing else's. */
    @Test
    fun `a generation close abandons only its own publications`() = runBlocking {
        val receipts = ClaudePToolPublicationReceipts()
        val mine = id(generationId = "run-1")
        val theirs = id(generationId = "run-2", toolCallId = "call-2")
        val myRequest = receipts.began(mine)
        val theirRequest = receipts.began(theirs)

        receipts.abandonGeneration("run-1", ClaudePToolPublicationAbandonReason.GENERATION_CLOSED)

        assertEquals(
            ClaudePToolPublicationOutcome.Abandoned(
                ClaudePToolPublicationAbandonReason.GENERATION_CLOSED,
            ),
            myRequest.await(5_000),
        )
        assertEquals("the other generation is untouched", 1, receipts.pendingCount)
        assertTrue(receipts.complete(theirs, "a-2", "e-2"))
        assertEquals(
            ClaudePToolPublicationOutcome.Committed("a-2", "e-2"),
            theirRequest.await(5_000),
        )
    }

    /** A registry close ends everything, and does not un-happen an answer already given. */
    @Test
    fun `a registry close ends every wait`() = runBlocking {
        val receipts = ClaudePToolPublicationReceipts()
        val answered = id(toolCallId = "call-answered")
        val live = id(toolCallId = "call-live")
        val answeredRequest = receipts.began(answered)
        val liveRequest = receipts.began(live)
        assertTrue(receipts.complete(answered, "a-1", "e-1"))

        receipts.abandonAll(ClaudePToolPublicationAbandonReason.REGISTRY_CLOSED)

        assertEquals(
            ClaudePToolPublicationOutcome.Committed("a-1", "e-1"),
            answeredRequest.await(5_000),
        )
        assertEquals(
            ClaudePToolPublicationOutcome.Abandoned(
                ClaudePToolPublicationAbandonReason.REGISTRY_CLOSED,
            ),
            liveRequest.await(5_000),
        )
        assertEquals(0, receipts.pendingCount)
    }

    // ---------------------------------------------------------------------------------------
    // 5. Bounds, and no residue on any path
    // ---------------------------------------------------------------------------------------

    /**
     * The registry is bounded, and going over the bound costs a capability rather than an execution.
     *
     * The evicted publication is *completed* as abandoned rather than dropped, so a publisher
     * blocked on it fails closed now instead of sitting until its deadline.
     */
    @Test
    fun `an evicted publication fails closed rather than hanging`() = runBlocking {
        val receipts = ClaudePToolPublicationReceipts(maxEntries = 2)
        val first = receipts.began(id(toolCallId = "call-1"))
        receipts.began(id(toolCallId = "call-2"))
        receipts.began(id(toolCallId = "call-3"))

        assertTrue("stays inside the bound", receipts.pendingCount <= 2)
        assertEquals(
            ClaudePToolPublicationOutcome.Abandoned(ClaudePToolPublicationAbandonReason.EVICTED),
            first.await(5_000),
        )
    }

    /** Terminal ids are remembered under the same bound, so `settled` cannot grow either. */
    @Test
    fun `remembered terminal ids are bounded too`() {
        val receipts = ClaudePToolPublicationReceipts(maxEntries = 2)
        repeat(5) { index ->
            val key = id(toolCallId = "call-$index")
            receipts.began(key)
            assertTrue(receipts.complete(key, "a-$index", "e-$index"))
        }

        assertEquals(0, receipts.pendingCount)
        assertTrue("settled ids stay inside the bound", receipts.settledCount <= 2)
    }

    /**
     * Every terminal path leaves the registry empty once the publisher is done with the id.
     *
     * A leak here is not a crash: it is a map that slowly stops accepting new publications, which
     * presents as approval-gated calls that time out for no visible reason.
     */
    @Test
    fun `no path leaves an entry behind once released`() = runBlocking {
        val receipts = ClaudePToolPublicationReceipts()

        val committedKey = id(toolCallId = "call-committed")
        receipts.began(committedKey)
        assertTrue(receipts.complete(committedKey, "a", "e"))
        receipts.release(committedKey)

        val refusedKey = id(toolCallId = "call-refused")
        receipts.began(refusedKey)
        assertTrue(receipts.refuse(refusedKey, "rollback"))
        receipts.release(refusedKey)

        val timedOutKey = id(toolCallId = "call-timeout")
        receipts.began(timedOutKey).await(20)
        receipts.release(timedOutKey)

        val cancelledKey = id(toolCallId = "call-cancelled")
        receipts.began(cancelledKey).cancel(ClaudePToolPublicationAbandonReason.CANCELLED)
        receipts.release(cancelledKey)

        val closedKey = id(toolCallId = "call-closed")
        receipts.began(closedKey)
        receipts.abandonGeneration("run-1", ClaudePToolPublicationAbandonReason.GENERATION_CLOSED)
        receipts.release(closedKey)

        assertEquals("no live entries", 0, receipts.pendingCount)
        assertEquals("no remembered terminals", 0, receipts.settledCount)
        assertTrue(receipts.pendingIds().isEmpty())
    }

    // ---------------------------------------------------------------------------------------
    // 6. It stays inside the process
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

    private fun projectFile(vararg candidates: String): File =
        requireNotNull(candidates.asSequence().map(::File).firstOrNull(File::isFile)) {
            "Cannot locate ${candidates.joinToString()} from ${File(".").absolutePath}"
        }

    /** A publication id renders as pseudonyms, so a log line or a test failure cannot leak it. */
    @Test
    fun `a publication id does not print what it holds`() {
        val rendered = id(
            generationId = "run-secret",
            toolCallId = "call-secret",
            toolName = "read_secret_file",
        ).toString()

        assertFalse(rendered, rendered.contains("run-secret"))
        assertFalse(rendered, rendered.contains("call-secret"))
        assertFalse(rendered, rendered.contains("read_secret_file"))
    }

    /** Two different invocations of the same call id are two different publications. */
    @Test
    fun `the tool name is part of the identity`() {
        assertFalse(id(toolName = "read_file") == id(toolName = "write_file"))
        assertEquals(id(toolName = "read_file"), id(toolName = "read_file"))
    }
}
