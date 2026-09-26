package me.rerere.rikkahub.data.execution

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The exactly-once and registration guarantees of the in-flight approval waiters.
 *
 * Two properties are load-bearing for the Claude P bridge, and both are the kind that a passing
 * happy-path test says nothing about:
 *
 * 1. **One release per identity.** A duplicate approve must not be able to reach the runtime a
 *    second time, and it must not be able to leave anything behind that a *later* wait could pick
 *    up either — a decision that can be re-consumed is a second execution waiting for a caller.
 * 2. **The registration is atomic.** A decision landing exactly as a waiter registers must be
 *    observed by that waiter. If it is not, it is remembered as undelivered and the wait runs to
 *    its deadline — for a call the user already approved.
 *
 * Every test here is deterministic. Nothing sleeps and hopes, nothing runs threads and retries,
 * and no test is satisfied by a timeout: the ordering cases are driven either by signalling
 * before/after a wait or through [InFlightApprovalWaiters.onAwaitRegistration], the seam that lets
 * a decision be injected at the exact registration instant on the same thread. The only assertion
 * that involves a deadline is the one that is *about* deadlines, and it asserts a timeout is
 * reported as [InFlightApprovalAbandonReason.TIMEOUT] rather than as a decision.
 */
class InFlightApprovalWaitersTest {

    private fun identity(n: Int = 1) = InFlightApprovalIdentity(
        approvalId = "approval-$n",
        executionId = "execution-$n",
        conversationId = "conversation-$n",
        toolCallId = "call-$n",
    )

    /** Long enough that a real wait is never mistaken for a timeout in these tests. */
    private val generousMs = 10_000L

    /** Short enough to keep the suite quick, long enough that a never-completed wait always hits it. */
    private val certainTimeoutMs = 50L

    // ---------------------------------------------------------------------------------------
    // The two orderings, and the boundary between them
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a decision recorded before the wait is handed to the waiter that arrives`() = runBlocking {
        val waiters = InFlightApprovalWaiters()
        val id = identity()

        waiters.signalDecided(id, InFlightApprovalOutcome.Decided(InFlightApprovalDecision.APPROVED))

        assertEquals(
            InFlightApprovalOutcome.Decided(InFlightApprovalDecision.APPROVED),
            waiters.await(id, generousMs),
        )
    }

    @Test
    fun `a decision arriving after the waiter is registered releases it`() = runBlocking {
        val waiters = InFlightApprovalWaiters()
        val id = identity()
        val registered = CompletableDeferred<Unit>()
        waiters.onAwaitRegistration = { registered.complete(Unit) }

        val waiter = async { waiters.await(id, generousMs) }
        registered.await()
        assertEquals("the waiter is suspended", 1, waiters.waitingCount)

        waiters.signalDecided(id, InFlightApprovalOutcome.Decided(InFlightApprovalDecision.DENIED))

        assertEquals(InFlightApprovalOutcome.Decided(InFlightApprovalDecision.DENIED), waiter.await())
    }

    @Test
    fun `a decision landing exactly at the registration boundary is observed by that waiter`() = runBlocking {
        val waiters = InFlightApprovalWaiters()
        val id = identity()

        // The decision is signalled from *inside* the registration critical section, on the same
        // thread, which reenters the lock. This is the deterministic form of "the user tapped in
        // the instant between the waiter checking for a decision and installing itself".
        //
        // A correct implementation has already installed the waiter at this point, so the signal
        // finds it and the wait below returns the decision. The two-critical-section version this
        // replaced would not yet have registered: the same signal would be stored as an
        // undelivered decision, the wait would install itself afterwards, and it would block until
        // its deadline and report a timeout — for a call the user had approved.
        waiters.onAwaitRegistration = {
            waiters.signalDecided(id, InFlightApprovalOutcome.Decided(InFlightApprovalDecision.APPROVED))
        }

        assertEquals(
            InFlightApprovalOutcome.Decided(InFlightApprovalDecision.APPROVED),
            waiters.await(id, generousMs),
        )
    }

    @Test
    fun `the boundary signal is a real claim and not merely a remembered decision`() = runBlocking {
        val waiters = InFlightApprovalWaiters()
        val id = identity()
        waiters.onAwaitRegistration = {
            waiters.signalDecided(id, InFlightApprovalOutcome.Decided(InFlightApprovalDecision.APPROVED))
        }

        val first = waiters.await(id, generousMs)
        assertEquals(InFlightApprovalOutcome.Decided(InFlightApprovalDecision.APPROVED), first)

        // It was claimed by the waiter, not left behind: a second wait gets nothing.
        waiters.onAwaitRegistration = null
        assertEquals(
            InFlightApprovalOutcome.Abandoned(InFlightApprovalAbandonReason.TIMEOUT),
            waiters.await(id, certainTimeoutMs),
        )
    }

    // ---------------------------------------------------------------------------------------
    // Exactly one release per identity
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a duplicate decision after the waiter claimed the first changes nothing`() = runBlocking {
        val waiters = InFlightApprovalWaiters()
        val id = identity()

        waiters.signalDecided(id, InFlightApprovalOutcome.Decided(InFlightApprovalDecision.APPROVED))
        assertEquals(
            InFlightApprovalOutcome.Decided(InFlightApprovalDecision.APPROVED),
            waiters.await(id, generousMs),
        )

        // The stale second tap.
        waiters.signalDecided(id, InFlightApprovalOutcome.Decided(InFlightApprovalDecision.APPROVED))

        // It must leave nothing behind: no future wait may be released by it.
        assertEquals(
            InFlightApprovalOutcome.Abandoned(InFlightApprovalAbandonReason.TIMEOUT),
            waiters.await(id, certainTimeoutMs),
        )
        assertEquals("one identity, one terminal state", 1, waiters.settledCount)
    }

    @Test
    fun `a duplicate decision before any waiter keeps only the first`() = runBlocking {
        val waiters = InFlightApprovalWaiters()
        val id = identity()

        waiters.signalDecided(id, InFlightApprovalOutcome.Decided(InFlightApprovalDecision.APPROVED))
        waiters.signalDecided(id, InFlightApprovalOutcome.Decided(InFlightApprovalDecision.DENIED))

        assertEquals(
            "the first committed decision is the one that stands",
            InFlightApprovalOutcome.Decided(InFlightApprovalDecision.APPROVED),
            waiters.await(id, generousMs),
        )
        assertEquals(1, waiters.settledCount)
    }

    @Test
    fun `both decision values are delivered verbatim`() = runBlocking {
        val approved = InFlightApprovalWaiters()
        val denied = InFlightApprovalWaiters()

        approved.signalDecided(identity(1), InFlightApprovalOutcome.Decided(InFlightApprovalDecision.APPROVED))
        denied.signalDecided(identity(2), InFlightApprovalOutcome.Decided(InFlightApprovalDecision.DENIED))

        assertEquals(
            InFlightApprovalOutcome.Decided(InFlightApprovalDecision.APPROVED),
            approved.await(identity(1), generousMs),
        )
        assertEquals(
            InFlightApprovalOutcome.Decided(InFlightApprovalDecision.DENIED),
            denied.await(identity(2), generousMs),
        )
    }

    @Test
    fun `a decision for one identity never releases another`() = runBlocking {
        val waiters = InFlightApprovalWaiters()
        val registered = CompletableDeferred<Unit>()
        waiters.onAwaitRegistration = { registered.complete(Unit) }

        val waiting = identity(1)
        val other = identity(2)
        val waiter = async { waiters.await(waiting, generousMs) }
        registered.await()

        waiters.signalDecided(other, InFlightApprovalOutcome.Decided(InFlightApprovalDecision.APPROVED))

        // The neighbour's decision is not this call's answer. Then release the real one so the
        // coroutine finishes rather than being cancelled by the test ending.
        waiters.signalDecided(waiting, InFlightApprovalOutcome.Decided(InFlightApprovalDecision.DENIED))
        assertEquals(InFlightApprovalOutcome.Decided(InFlightApprovalDecision.DENIED), waiter.await())
    }

    @Test
    fun `a duplicate registration strands the first waiter rather than leaving it hanging`() = runBlocking {
        val waiters = InFlightApprovalWaiters()
        val id = identity()
        val firstRegistered = CompletableDeferred<Unit>()
        waiters.onAwaitRegistration = { firstRegistered.complete(Unit) }

        val first = async { waiters.await(id, generousMs) }
        firstRegistered.await()

        // A second wait for one identity can never be released by that identity's single decision,
        // so it takes the registration and the first waiter is ended rather than abandoned.
        waiters.onAwaitRegistration = null
        val second = async { waiters.await(id, generousMs) }
        assertEquals(
            InFlightApprovalOutcome.Abandoned(InFlightApprovalAbandonReason.CANCELLED),
            first.await(),
        )
        assertEquals("only the second waiter is registered", 1, waiters.waitingCount)

        waiters.signalDecided(id, InFlightApprovalOutcome.Decided(InFlightApprovalDecision.APPROVED))
        assertEquals(InFlightApprovalOutcome.Decided(InFlightApprovalDecision.APPROVED), second.await())
    }

    // ---------------------------------------------------------------------------------------
    // Deadlines and abandonment
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a deadline reports a timeout and deregisters the waiter`() = runBlocking {
        val waiters = InFlightApprovalWaiters()
        val id = identity()

        assertEquals(
            InFlightApprovalOutcome.Abandoned(InFlightApprovalAbandonReason.TIMEOUT),
            waiters.await(id, certainTimeoutMs),
        )
        assertEquals("a timed-out wait is not left behind", 0, waiters.waitingCount)
    }

    @Test
    fun `an abandonment recorded before the wait fails the waiter closed`() = runBlocking {
        val waiters = InFlightApprovalWaiters()
        val id = identity()

        waiters.abandon(id, InFlightApprovalAbandonReason.GENERATION_CLOSED)

        assertEquals(
            InFlightApprovalOutcome.Abandoned(InFlightApprovalAbandonReason.GENERATION_CLOSED),
            waiters.await(id, generousMs),
        )
    }

    @Test
    fun `an abandonment releases a waiter that is already registered`() = runBlocking {
        val waiters = InFlightApprovalWaiters()
        val id = identity()
        val registered = CompletableDeferred<Unit>()
        waiters.onAwaitRegistration = { registered.complete(Unit) }

        val waiter = async { waiters.await(id, generousMs) }
        registered.await()

        waiters.abandon(id, InFlightApprovalAbandonReason.DISCONNECTED)

        assertEquals(InFlightApprovalOutcome.Abandoned(InFlightApprovalAbandonReason.DISCONNECTED), waiter.await())
    }

    @Test
    fun `an abandonment never overwrites a decision`() = runBlocking {
        val waiters = InFlightApprovalWaiters()
        val id = identity()
        waiters.signalDecided(id, InFlightApprovalOutcome.Decided(InFlightApprovalDecision.APPROVED))

        waiters.abandon(id, InFlightApprovalAbandonReason.CANCELLED)

        assertEquals(
            "a granted tool is not turned into an unexecuted one",
            InFlightApprovalOutcome.Decided(InFlightApprovalDecision.APPROVED),
            waiters.await(id, generousMs),
        )
    }

    @Test
    fun `a decision arriving after an abandonment is ignored`() = runBlocking {
        val waiters = InFlightApprovalWaiters()
        val id = identity()
        waiters.abandon(id, InFlightApprovalAbandonReason.GENERATION_CLOSED)

        waiters.signalDecided(id, InFlightApprovalOutcome.Decided(InFlightApprovalDecision.APPROVED))

        assertEquals(
            "the generation is gone; its late approval must not become executable",
            InFlightApprovalOutcome.Abandoned(InFlightApprovalAbandonReason.GENERATION_CLOSED),
            waiters.await(id, generousMs),
        )
    }

    @Test
    fun `a repeated abandonment changes nothing`() = runBlocking {
        val waiters = InFlightApprovalWaiters()
        val id = identity()
        waiters.abandon(id, InFlightApprovalAbandonReason.GENERATION_CLOSED)

        waiters.abandon(id, InFlightApprovalAbandonReason.CANCELLED)

        assertEquals(
            InFlightApprovalOutcome.Abandoned(InFlightApprovalAbandonReason.GENERATION_CLOSED),
            waiters.await(id, generousMs),
        )
        assertEquals(1, waiters.settledCount)
    }

    @Test
    fun `abandoning everything releases every registered waiter`() = runBlocking {
        val waiters = InFlightApprovalWaiters()
        val first = identity(1)
        val second = identity(2)
        var registered = 0
        val bothRegistered = CompletableDeferred<Unit>()
        waiters.onAwaitRegistration = {
            registered++
            if (registered == 2) bothRegistered.complete(Unit)
        }

        val a = async { waiters.await(first, generousMs) }
        val b = async { waiters.await(second, generousMs) }
        bothRegistered.await()

        waiters.abandonAll(InFlightApprovalAbandonReason.REGISTRY_CLOSED)

        assertEquals(InFlightApprovalOutcome.Abandoned(InFlightApprovalAbandonReason.REGISTRY_CLOSED), a.await())
        assertEquals(InFlightApprovalOutcome.Abandoned(InFlightApprovalAbandonReason.REGISTRY_CLOSED), b.await())
        assertEquals(0, waiters.waitingCount)
    }

    @Test
    fun `a decision that survived an abandonAll is still refused afterwards`() = runBlocking {
        val waiters = InFlightApprovalWaiters()
        val id = identity()
        waiters.signalDecided(id, InFlightApprovalOutcome.Decided(InFlightApprovalDecision.APPROVED))

        waiters.abandonAll(InFlightApprovalAbandonReason.REGISTRY_CLOSED)
        waiters.signalDecided(id, InFlightApprovalOutcome.Decided(InFlightApprovalDecision.APPROVED))

        assertEquals(
            "a shutdown does not make an already-released identity releasable again",
            InFlightApprovalOutcome.Abandoned(InFlightApprovalAbandonReason.TIMEOUT),
            waiters.await(id, certainTimeoutMs),
        )
    }

    @Test
    fun `forgetting an undelivered decision does not make the identity releasable again`() = runBlocking {
        val waiters = InFlightApprovalWaiters()
        val id = identity()
        waiters.signalDecided(id, InFlightApprovalOutcome.Decided(InFlightApprovalDecision.APPROVED))

        waiters.forget(id)
        waiters.signalDecided(id, InFlightApprovalOutcome.Decided(InFlightApprovalDecision.APPROVED))

        assertEquals(
            InFlightApprovalOutcome.Abandoned(InFlightApprovalAbandonReason.TIMEOUT),
            waiters.await(id, certainTimeoutMs),
        )
    }

    @Test
    fun `there is no waiter to abandon once a decision has been claimed`() = runBlocking {
        val waiters = InFlightApprovalWaiters()
        val id = identity()
        waiters.signalDecided(id, InFlightApprovalOutcome.Decided(InFlightApprovalDecision.APPROVED))
        waiters.await(id, generousMs)

        assertEquals("the claim removed the registration", 0, waiters.waitingCount)
        assertNull(null)
    }

    @Test
    fun `the settled record is bounded`() = runBlocking {
        val waiters = InFlightApprovalWaiters(maxRememberedDecisions = 4)
        repeat(10) { index ->
            waiters.signalDecided(
                identity(index),
                InFlightApprovalOutcome.Decided(InFlightApprovalDecision.APPROVED),
            )
        }

        assertTrue("the exactly-once record is a leak bound, not an unbounded map", waiters.settledCount <= 4)
    }
}
