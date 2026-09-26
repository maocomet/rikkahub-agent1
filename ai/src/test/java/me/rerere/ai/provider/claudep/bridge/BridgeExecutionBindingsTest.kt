package me.rerere.ai.provider.claudep.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The generation-keyed execution binding.
 *
 * A tool call arrives carrying a generation id and nothing else that identifies its generation,
 * so the app's authoritative context for that call has to be reachable by exactly that id. These
 * tests are the properties that makes it safe to reach it that way.
 *
 * The one that matters most is the negative half of the lookup: a binding must never be found by
 * an id that is *near* the one it was stored under. Every way of being near — a prefix, a longer
 * id, a sibling generation — is checked, because a lookup that fell back to "the closest match"
 * would answer one generation's call with another generation's conversation, assistant and
 * approval policy, and would look correct in every test that only ever used one generation.
 *
 * The rest is the lifecycle the provider drives: a plan is staged before its generation has an
 * id, redeemed once the id exists, and released on every path that ends a generation. Each of the
 * failure modes ends with the same answer — the caller is told the binding could not be made and
 * must fail the generation rather than run tools it cannot answer.
 */
class BridgeExecutionBindingsTest {

    private class Plan(val tag: String)

    private fun bindings(maxEntries: Int = 8) = BridgeExecutionBindings<Plan>(maxEntries)

    @Test
    fun `a null reference is the text path and binds nothing`() {
        val bindings = bindings()

        assertTrue("the text path is not a failure", bindings.open("g-text", null))
        assertNull(bindings.lookup("g-text"))
    }

    @Test
    fun `a plan is found by its exact generation id`() {
        val bindings = bindings()
        assertTrue(bindings.stage("ref", "identity", Plan("plan")))

        assertTrue(bindings.open("generation-1", "ref"))
        assertEquals("plan", bindings.lookup("generation-1")?.tag)
    }

    @Test
    fun `a generation id that is merely near another is a miss`() {
        val bindings = bindings()
        bindings.stage("ref", "identity", Plan("plan"))
        bindings.open("generation-1", "ref")

        assertNull("a sibling generation", bindings.lookup("generation-2"))
        assertNull("a prefix", bindings.lookup("generation-"))
        assertNull("a longer id", bindings.lookup("generation-11"))
        assertNull("the empty id", bindings.lookup(""))
    }

    @Test
    fun `opening consumes the staged plan so a reference cannot be redeemed twice`() {
        val bindings = bindings()
        bindings.stage("ref", "identity", Plan("plan"))
        bindings.open("generation-1", "ref")

        assertEquals("the plan was consumed", 0, bindings.stagedCount)
        assertFalse("a redeemed reference cannot open a second generation", bindings.open("generation-2", "ref"))
        assertNull(bindings.lookup("generation-2"))
    }

    @Test
    fun `an unknown reference refuses rather than binding nothing`() {
        val bindings = bindings()

        assertFalse(bindings.open("generation-1", "never-staged"))
        assertNull(bindings.lookup("generation-1"))
    }

    @Test
    fun `a duplicate reference is refused and the first plan stands`() {
        val bindings = bindings()
        assertTrue(bindings.stage("ref", "identity", Plan("first")))

        assertFalse(bindings.stage("ref", "identity", Plan("second")))
        assertTrue(bindings.open("generation-1", "ref"))
        assertEquals("first", bindings.lookup("generation-1")?.tag)
    }

    @Test
    fun `re-opening a generation with the same identity is idempotent`() {
        val bindings = bindings()
        bindings.stage("ref-1", "identity", Plan("first"))
        assertTrue(bindings.open("generation-1", "ref-1"))

        bindings.stage("ref-2", "identity", Plan("second"))
        assertTrue("a retry that would bind the same plan is allowed", bindings.open("generation-1", "ref-2"))
        assertEquals("the plan already bound stands", "first", bindings.lookup("generation-1")?.tag)
    }

    @Test
    fun `re-opening a generation with a different identity is refused and changes nothing`() {
        val bindings = bindings()
        bindings.stage("ref-1", "identity", Plan("first"))
        bindings.open("generation-1", "ref-1")

        bindings.stage("ref-2", "OTHER", Plan("second"))
        assertFalse(bindings.open("generation-1", "ref-2"))
        assertEquals("first", bindings.lookup("generation-1")?.tag)
    }

    @Test
    fun `closing a generation revokes its binding and leaves the others alone`() {
        val bindings = bindings()
        bindings.stage("ref-1", "a", Plan("a"))
        bindings.stage("ref-2", "b", Plan("b"))
        bindings.open("generation-1", "ref-1")
        bindings.open("generation-2", "ref-2")

        bindings.close("generation-1")

        assertNull(bindings.lookup("generation-1"))
        assertEquals("b", bindings.lookup("generation-2")?.tag)
    }

    @Test
    fun `closing a generation that was never bound is a no-op`() {
        val bindings = bindings()
        bindings.stage("ref", "identity", Plan("plan"))
        bindings.open("generation-1", "ref")

        bindings.close("never-opened")

        assertEquals("plan", bindings.lookup("generation-1")?.tag)
    }

    @Test
    fun `staged plans are bounded and an evicted one fails closed`() {
        val bindings = bindings(maxEntries = 2)
        repeat(3) { index -> bindings.stage("ref-$index", "identity-$index", Plan("plan-$index")) }

        assertEquals("the bound holds", 2, bindings.stagedCount)
        assertFalse("the oldest staged plan is gone", bindings.open("generation-0", "ref-0"))
        assertTrue("the newest staged plan survives", bindings.open("generation-2", "ref-2"))
    }

    @Test
    fun `bound plans are bounded and the oldest is evicted`() {
        val bindings = bindings(maxEntries = 2)
        repeat(3) { index ->
            bindings.stage("ref-$index", "identity-$index", Plan("plan-$index"))
            bindings.open("generation-$index", "ref-$index")
        }

        assertEquals(2, bindings.boundCount)
        assertNull("the oldest binding was evicted", bindings.lookup("generation-0"))
    }

    @Test
    fun `the bound generation ids are the ones actually bound`() {
        val bindings = bindings()
        bindings.stage("ref", "identity", Plan("plan"))
        bindings.open("generation-1", "ref")
        bindings.open("generation-text", null)

        assertEquals(setOf("generation-1"), bindings.boundGenerationIds())
    }

    @Test
    fun `closing everything revokes every binding and every staged plan`() {
        val bindings = bindings()
        bindings.stage("ref-1", "a", Plan("a"))
        bindings.stage("ref-2", "b", Plan("b"))
        bindings.open("generation-1", "ref-1")

        bindings.closeAll()

        assertEquals(0, bindings.boundCount)
        assertEquals(0, bindings.stagedCount)
        assertNull(bindings.lookup("generation-1"))
    }
}
